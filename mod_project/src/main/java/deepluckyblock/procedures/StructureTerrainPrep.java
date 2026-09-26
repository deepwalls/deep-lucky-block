package deepluckyblock.procedures;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StructureTerrainPrep : preparation professionnelle du terrain avant/apres paste de structure.
 *
 * Phases (toutes batchees via TestProcedure.schedule) :
 *   1. SCAN TREES : detecte et sauvegarde les arbres (type + forme exacte) dans un rayon.
 *   2. CLEAR     : efface tout de Y=1 au sommet (bedrock preserve).
 *   3. SMOOTH    : Gaussian blur sur la heightmap (style Axiom), reconstruction du terrain.
 *   4. [PASTE]   : la structure est paste par le code appelant (callback onCleared).
 *   5. REPLANT   : re-colle les arbres sauves a leurs nouvelles hauteurs, par zone/type.
 *   6. NATURALIZE: herbe, fleurs, fougeres, bonemeal sur le terrain smooth.
 *
 * Bedrock (Y<=0) JAMAIS touche. La structure n'est pas touchee par le smooth/replant.
 */
public class StructureTerrainPrep {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final int RING = 10;
    // Ring de modification du terrain : PROPORTIONNEL a la taille de la structure
    // (petit pour les petits builds, gros pour les gros), clampe. Plus de zone
    // titanesque carree quand le build est petit et rectangulaire : la zone suit
    // la taille et la forme (footprint rectangulaire) du build.
    private static final int SMOOTH_RING_MIN = 14;
    private static final int SMOOTH_RING_MAX = 48;
    // Marge supplement pour que le lissage englobe aussi les spheres eloignees.
    private static final int SMOOTH_EXTRA_RING = 32;
    /** Pourcentage de taille de l'anneau : 100 = normal, 40 = -60%, 120 = +20%. */
    public static int TERRAIN_RING_SCALE_PERCENT = 100;
    // La vegetation depasse legerement la zone de terrain, d'environ un chunk.
    private static final int NATURALIZE_EXTRA_RING = 16;

    public static void setTerrainRingScalePercent(int percent) {
        TERRAIN_RING_SCALE_PERCENT = Math.max(10, Math.min(200, percent));
    }
    private static int SMOOTH_RING = SMOOTH_RING_MAX;
    private static final int CLEAR_BATCH_COLS = 600;
    // Rayon (en sphere) du barrage en grass block autour d'un point de fuite d'eau.
    private static final int DAM_RADIUS = 8;
    // Amplitude du zigzag du barrage (en blocs) : decale le centre de chaque sphere
    // pour ne jamais faire une ligne droite rectangulaire.
    private static final int DAM_OFFSET = 2;
    // setBlock rapide, avec les BONS drapeaux (T4/T9).
    //   VALEURS VERIFIEES dans l'API 1.21.1 (Block.UPDATE_*) :
    //     1 = UPDATE_NEIGHBORS, 2 = UPDATE_CLIENTS, 4 = UPDATE_INVISIBLE,
    //     8 = UPDATE_IMMEDIATE, 16 = UPDATE_KNOWN_SHAPE, 32 = UPDATE_SUPPRESS_DROPS,
    //     64 = UPDATE_MOVE_BY_PISTON.
    //   L'ancien « 2 | 16 | 64 » contenait 64 = MOVE_BY_PISTON, or ce drapeau
    //   devient le parametre isMoving de LevelChunk#setBlockState : le chunk
    //   considere l'ecriture comme un DEPLACEMENT DE BLOC (cas piston) et ne
    //   nettoie donc pas les block entities de l'ancien bloc. Resultat mesure
    //   dans le log : « Tried to load a DUMMY block entity @ ... but found not
    //   block entity block minecraft:air » (29 a 51 fois par test), c'est-a-dire
    //   des block entities fantomes enregistrees sur des blocs devenus de l'air.
    //   On ne veut pas d'un deplacement de piston mais d'un remplacement : 32
    //   (UPDATE_SUPPRESS_DROPS) est le drapeau prevu pour ecraser un bloc sans
    //   faire tomber d'objet. On garde 2 (le client voit le changement) et 16
    //   (pas de recalcul de forme des voisins) : c'est ce qui rend le paste rapide.
    private static final int FAST_FLAG = 2 | 16 | 32;
    /**
     * OBSOLETE (T4, 20/09) : cette constante n'est PLUS utilisee par le pipeline.
     * Elle laissait croire a « 3 passes » alors que decorate() en demande 50
     * puis 12 (smoothPass(level, min, max, 7, 50) puis smoothPass(..., 15, 12),
     * deux fois : avant et apres l'etancheite de l'eau). Conservee uniquement
     * comme repere historique : ne pas s'y fier, lire decorate().
     */
    @Deprecated
    private static final int SMOOTH_PASSES = 3;
    // Amplitude (en blocs) du relief de rappel ajoute apres le lissage, pour que
    // la couronne ne soit pas un plan parfait. Volontairement faible : au-dela de
    // ~3 blocs on recree des bosses qui genent les abords de la structure.
    private static final int BUMP_AMPLITUDE = 2;
    // Marge de securite (en blocs) au-dessus du niveau d'eau pour la protection
    // anti-chute d'eau en bordure de plateforme.
    private static final int WATER_GUARD_MARGIN = 2;
    /** T33 : nombre de renforcements successifs d'une berge tant que l'eau fuit. */
    private static final int BANK_RETRIES = 2;
    // Kernel gaussien 5x5 (total = 273)
    private static final int[][] GAUSSIAN_5x5 = {
        {1, 4, 7, 4, 1}, {4, 16, 26, 16, 4}, {7, 26, 41, 26, 7},
        {4, 16, 26, 16, 4}, {1, 4, 7, 4, 1}
    };

    // =========================================================================
    // RECORDS
    // =========================================================================
    private record SavedBlock(int rx, int ry, int rz, BlockState state) {}
    private record SavedTree(int baseX, int baseZ, int groundY, String type, List<SavedBlock> blocks) {}

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    /**
     * Lance la preparation complete du terrain.
     *
     * @param level     Le monde serveur
     * @param min       Coin min de l'emprise de la structure
     * @param max       Coin max de l'emprise
     * @param onCleared Callback appele quand le terrain est pret (pour lancer le paste)
     * @param onDone    Callback appele quand TOUT est termine (apres replant + naturalize)
     */
    public static void prepare(ServerLevel level, BlockPos min, BlockPos max,
                                Runnable onCleared, Runnable onDone) {
        RandomSource random = level.getRandom();

        // Zone etendue (structure + ring)
        int x0 = min.getX() - RING, x1 = max.getX() + RING;
        int z0 = min.getZ() - RING, z1 = max.getZ() + RING;
        int topY = Math.min(max.getY() + 10, level.getMaxBuildHeight() - 1);

        // === PHASE 1 : SCAN TREES ===
        List<SavedTree> trees = scanTrees(level, x0, z0, x1, z1, topY);

        // === PHASE 2 : CLEAR (batche) ===
        clearArea(level, x0, z0, x1, z1, topY, () -> {
            // Terrain vide. Lancer le paste.
            if (onCleared != null) onCleared.run();

            // === PHASE 3a : SMOOTH 5x5, 3 passes ===
            smoothPass(level, min, max, 5, 3, () -> {

                // === PHASE 3b : CLEANUP - supprime le terrain qui a deborde dans la structure ===
                // (skip les chunks majoritairement air = JAMAIS vider l'air)
                cleanupOverlap(level, min, max, () -> {

                    // === PHASE 3c : SMOOTH 15x15, 2 passes ===
                    smoothPass(level, min, max, 15, 2, () -> {

                        // === PHASE 3d : VERIFY GRASS BLOCK en surface ===
                        verifyGrassSurface(level, min, max);

                        // === PHASE 5 : REPLANT TREES ===
                        replantTrees(level, trees, min, max, () -> {

                            // === PHASE 6 : NATURALIZE (T77 : decoupee en tranches) ===
                            naturalize(level, min, max, random, () -> {
                                if (onDone != null) onDone.run();
                            });
                        });
                    });
                });
            });
        });
    }

    /**
     * Lissage + herbe + replant + naturalize AUTOUR d'une structure deja poseee et carvee.
     * Pas de clearArea (le carve a deja nettoye l'interieur). L'emprise est preserveee
     * (surface mesuree), seul l'anneau est lisse pour fondre la structure dans le terrain.
     * Synchronne : peut freezer 1 tick sur les grosses structures.
     */
    public static void smoothAround(ServerLevel level, BlockPos min, BlockPos max, Runnable onDone) {
        RandomSource random = level.getRandom();
        int topY = Math.min(max.getY() + 10, level.getMaxBuildHeight() - 1);
        List<SavedTree> trees = scanTrees(level, min.getX() - RING, min.getZ() - RING, max.getX() + RING, max.getZ() + RING, topY);
        smoothPass(level, min, max, 5, 1, () -> {
            verifyGrassSurface(level, min, max);
            replantTrees(level, trees, min, max, () -> {
                naturalize(level, min, max, random, () -> {
                    if (onDone != null) onDone.run();
                });
            });
        });
    }

    // Trees sauves pendant prepZone, utilises par decorate (replant).
    private static List<SavedTree> LAST_TREES = new ArrayList<>();

    // Colonnes de barrage d'eau : le smooth les IGNORE (ne les aplatit pas) pour que
    // les spheres completes de grass_block survivent. Sinon le smooth (heightmap,
    // 1 hauteur/colonne) ecrase les spheres en cylindres plats. Rempli par placeWaterDams.
    private static Set<Long> DAM_COLS = new HashSet<>();

    private static long colKey(int x, int z) { return (Integer.toUnsignedLong(x) << 32) | Integer.toUnsignedLong(z); }

    // Ring de smooth proportionnel a la taille du build : la moitie de la plus
    // grande dimension, clampee entre SMOOTH_RING_MIN et MAX. Les petits builds
    // rectangulaires ne declenchent plus une modification titanesque carree : la
    // zone de terrain modifiee suit la taille (et la forme) du build.
    private static int terrainRing() {
        int baseRing = SMOOTH_RING + SMOOTH_EXTRA_RING;
        return Math.max(8, Math.round(baseRing * TERRAIN_RING_SCALE_PERCENT / 100.0f));
    }

    private static int computeSmoothRing(BlockPos min, BlockPos max) {
        int w = max.getX() - min.getX() + 1;
        int d = max.getZ() - min.getZ() + 1;
        return Math.max(SMOOTH_RING_MIN, Math.min(SMOOTH_RING_MAX, Math.max(w, d) / 2));
    }

    /**
     * Preparation AVANT paste (ordre demande) :
     *   1. scan arbres du ring (memorisation)
     *   2. clear footprint (au-dessus du sol, sans cratere)
     *   3. smooth ring (sur terrain NATUREL, donc la structure n'est pas perturbee)
     *   4. onReady -> le code appelant lance le paste
     */
    public static void prepZone(ServerLevel level, BlockPos min, BlockPos max, int foundationBaseY, Runnable onReady) {
        // === T6 : UNE SEULE CHAINE TERRAIN A LA FOIS ===
        // Les passes partagent des champs statiques (nom de structure, arbres
        // sauves, colonnes de barrage, emprise protegee). Deux structures qui
        // preparent leur zone en meme temps se marcheraient dessus : la suivante
        // est donc REPORTEE (jamais perdue), quelques ticks plus tard.
        final String chainKey = chainTag(min, max);
        // === T10 : UNE SEULE PREPARATION PAR STRUCTURE ===
        // Le rappel « onReady » (le paste) doit partir UNE fois. prepZone est
        // re-armee volontairement quand la chaine terrain est occupee, et le
        // pipeline peut etre relance par un evenement de chargement : sans ce
        // verrou, le paste entier repartait une seconde fois (travail double =
        // gel, blocs DUMMY, chute de TPS). Le verrou est rendu des que le paste
        // est effectivement lance.
        if (PREP_STARTED.contains(chainKey)) {
            deepluckyblock.util.DebugLog.structure(
                    "{} : preparation deja en cours -- appel ignore (anti double-paste)", chainKey);
            return;
        }
        if (!deepluckyblock.util.TerrainChain.acquire(chainKey)) {
            deepluckyblock.util.DebugLog.structure(
                    "{} : chaine terrain occupee par {} -- prepZone reporte de {} ticks",
                    chainKey, deepluckyblock.util.TerrainChain.owner(), CHAIN_WAIT_TICKS);
            TestProcedure.schedule(level, TestProcedure.currentTick(level) + CHAIN_WAIT_TICKS,
                    () -> prepZone(level, min, max, foundationBaseY, onReady));
            return;
        }
        // Ring PROPORTIONNEL au build : petit pour les petites structures (fini la
        // zone titanesque carree), gros pour les grosses. La forme suit le footprint
        // rectangulaire du build (min/max + ring).
        // T46 : calcule AVANT le ChunkKeeper et le pre-chargement -- les deux
        // doivent travailler sur EXACTEMENT la meme boite. Avant, le track()
        // utilisait l'ancien SMOOTH_RING (celui de la structure precedente) et le
        // pre-chargement le nouveau : il attendait donc des chunks de la couronne
        // que personne ne demandait jamais.
        SMOOTH_RING = computeSmoothRing(min, max);
        // === T9 : la zone est TENUE EN MEMOIRE pendant tout le pipeline ===
        deepluckyblock.util.ChunkKeeper.track(level,
                new BlockPos(min.getX() - (terrainRing() + NATURALIZE_EXTRA_RING), min.getY(), min.getZ() - (terrainRing() + NATURALIZE_EXTRA_RING)),
                new BlockPos(max.getX() + (terrainRing() + NATURALIZE_EXTRA_RING), max.getY(), max.getZ() + (terrainRing() + NATURALIZE_EXTRA_RING)));
        final int topY = Math.min(max.getY() + 10, level.getMaxBuildHeight() - 1);
        int buildW = max.getX() - min.getX() + 1, buildD = max.getZ() - min.getZ() + 1;

        // ===================================================================
        // ETAPE 0 : PRE-CHARGEMENT DE LA ZONE, AVANT TOUT AUTRE ACCES
        // ===================================================================
        //
        // FIX CRITIQUE (log : « citadel : 113930 blocs » puis PLUS RIEN, freeze
        // definitif -- le log s'arretait avant meme la ligne « prepZone : N
        // arbres sauves »).
        //
        // CAUSE : scanTrees() etait la toute premiere operation, et il appelle
        // deepluckyblock.util.SafeSurface.height(level, ) sur CHAQUE colonne de la zone (~25 000). Sur un
        // chunk NON CHARGE, getHeight() force sa GENERATION COMPLETE, de facon
        // SYNCHRONE, sur le thread serveur. Or une structure apparait LOIN du
        // joueur : aucun de ses ~110 chunks n'est charge. Le serveur partait
        // donc generer 110 chunks de zero en plein tick, sans aucun log, et
        // n'en revenait jamais. Le preload etale que j'avais ajoute existait
        // bien -- mais dans smoothPass(), c'est-a-dire BIEN TROP TARD.
        //
        // CORRECTIF : la zone est chargee EN PREMIER, par paquets de 8 chunks
        // par tick (preloadChunksBatched). Tout le reste du pipeline ne
        // demarre qu'une fois la zone entierement en memoire, et ne touche donc
        // plus jamais un chunk absent.
        PHASE_T0 = System.currentTimeMillis();
        resetNaturalReference();   // T21 : nouvelle structure -> nouvelle reference naturelle
        int ring0 = terrainRing();
        int px0 = min.getX() - ring0, pz0 = min.getZ() - ring0;
        int px1 = max.getX() + ring0, pz1 = max.getZ() + ring0;
        deepluckyblock.util.DebugLog.structure(
                "prepZone 0/11 : pre-chargement de la zone {}x{} AVANT tout acces...",
                px1 - px0 + 1, pz1 - pz0 + 1);

        // T46 : le COEUR (emprise + 48 blocs) est la seule zone qu'on ATTEND.
        // L'anneau de terrain est demande en parallele mais ne bloque pas : passe
        // le budget, le pipeline demarre (les passes sautent les colonnes
        // absentes, aucune generation synchrone n'est declenchee).
        final int coreX0 = min.getX() - PRELOAD_CORE_MARGIN, coreZ0 = min.getZ() - PRELOAD_CORE_MARGIN;
        final int coreX1 = max.getX() + PRELOAD_CORE_MARGIN, coreZ1 = max.getZ() + PRELOAD_CORE_MARGIN;
        preloadChunksBatched(level, px0, pz0, px1, pz1, px1 - px0 + 1, pz1 - pz0 + 1,
                coreX0, coreZ0, coreX1, coreZ1, PRELOAD_TOTAL_BUDGET_MS, () -> {
            step("prepZone 0/11 : zone chargee, demarrage du pipeline");
            // === T13 : GEL DE L'EAU (guide, section 27 : « Water Damming ») ===
            // AVANT de toucher au terrain, l'eau de la zone est retiree et son
            // etat exact memorise : le smooth/carve/fill travaillent ensuite sur
            // une zone SECHE (aucune cascade, aucune eau suspendue), et l'eau est
            // restituee intelligemment a la fin du decorate. C'est le
            // remplacement SANS matiere des anciens barrages en blocs (qui
            // laissaient des spheres visibles, rapportees en jeu).
            // === T23 : L'EAU N'EST PLUS TOUCHEE DU TOUT (consigne utilisateur, 20/09 17:12) ===
            // « tu as vide l'eau, a force l'eau ds murs a ne pas s'update, a tente
            //  quelque chose mais ca rend vraiment mal ! » ; « je vois enormement
            //  d'eau pas update » ; « tu as tres mal importe le truc de barrage d'eau ».
            //
            // Decision : le pipeline ne retire plus une seule goutte d'eau et ne pose
            // plus un seul bloc de barrage. Retirer puis reposer 34 000 blocs d'eau
            // laissait des murs/des trous et restait le principal defaut visuel du
            // mod. Le relief est desormais retravaille AUTOUR de l'eau : les plans
            // d'eau sont exclus du lissage (T20), ne sont ni recreuses ni remblayes,
            // et les raccords de berge existants (guardWaterEdges, clearWaterPockets,
            // fixWaterNearStructure) suffisent. WaterDam reste disponible pour le
            // diagnostic et la commande manuelle /dlbtest water.
            deepluckyblock.util.DebugLog.setPhase("preparation du terrain (eau intacte)");
            {
            long tScan0 = System.currentTimeMillis();
            LAST_TREES = scanTrees(level, min.getX() - terrainRing(), min.getZ() - terrainRing(),
                    max.getX() + terrainRing(), max.getZ() + terrainRing(), topY);
            // T76 : cette ligne passe par step() -- sinon LAST_STEP_MS restait fige
            // et la duree de scanTrees etait imputee a l'etape SUIVANTE (log
            // utilisateur : « l'etape [...] a pris 10149 ms » pour une etape qui en
            // avait reellement pris 720).
            step("prepZone 1/11 : scanTrees termine, " + LAST_TREES.size() + " arbres sauves en "
                    + (System.currentTimeMillis() - tScan0) + " ms");
            PREP_STARTED.add(chainKey);
            prepZoneAfterPreload(level, min, max, foundationBaseY, topY, () -> {
                // La chaine terrain est rendue au moment ou l'appelant reprend la
                // main (le paste peut alors demarrer) : la suite de terrain
                // (carve/decorate) la reprendra quand ce sera son tour.
                PREP_STARTED.remove(chainKey);
                deepluckyblock.util.TerrainChain.release(chainKey);
                if (onReady != null) onReady.run();
            });
            }   // T23 : fin du bloc "eau intacte" (ex-fin du gel WaterDam)
        });
    }

    private static void prepZoneAfterPreload(ServerLevel level, BlockPos min, BlockPos max,
                                             int foundationBaseY, int topY, Runnable onReady) {
        // 0. Pre-fill : remplit les vides SOUS la structure (emprise + 3) avec du grass
        //    block, pour garantir un sol solide (pas de structure qui flotte sur un trou).
        // Visibilite : ces 3 etapes ne loggaient rien, ce qui rendait tout
        // blocage invisible (« on ne sait meme pas ce qu'il se passe donc on ne
        // peut pas regler le potentiel fautif »). Chacune annonce desormais sa
        // fin, on peut donc localiser un arret a coup sur.
        prefillFoundation(level, min, max, foundationBaseY, () -> {
            step("prepZone 2/11 : prefillFoundation termine");
            clearSurfaceDecor(level, min, max, () -> {
                step("prepZone 3/11 : clearSurfaceDecor termine");
                clearFootprint(level, min, max, topY, () -> {
                    step("prepZone 4/11 : clearFootprint termine");
                    // RETABLI (l'utilisateur a confirme par test en jeu que ce n'etait
                    // PAS la cause du "mur/montagne" - le vrai bug etait dans
                    // despeckleHeightmap(), voir le fix + commentaire au-dessus de
                    // cette methode). placeWaterDams() comble les fuites d'eau AVANT
                    // le smooth pour que le lissage ne fige pas un plan d'eau perce ;
                    // les colonnes de barrage sont enregistrees dans DAM_COLS.
                    // DESACTIVE (rapporte en jeu : « je vois clairement des
                    // spheres sur l'eau qui n'ont jamais ete smooth ! de l'eau
                    // qui coule »).
                    //
                    // placeWaterDams() posait des SPHERES PLEINES de grass_block
                    // sur chaque fuite d'eau. Comme le smooth raisonne en
                    // heightmap (une seule hauteur par colonne), il ne peut pas
                    // lisser un volume 3D : soit il les rabotait en cylindres
                    // plats, soit -- apres mon exclusion DAM_COLS -- il les
                    // laissait telles quelles, c'est-a-dire en boules brutes
                    // posees sur le lac. Les deux rendus sont mauvais.
                    //
                    // Le probleme d'origine (eau qui fuit) est deja traite
                    // proprement par guardWaterEdges(), qui eleve une BERGE
                    // suivant la hauteur locale de l'eau au lieu d'empiler des
                    // spheres, et par clearWaterPockets(). On supprime donc la
                    // cause du defaut visuel plutot que de tenter de le lisser.
                    // placeWaterDams() reste desactive (les spheres rendaient mal).
                    //
                    // ORDRE IMPOSE PAR L'UTILISATEUR : 1,2,3,4,8,7,5,6,10,11,9
                    //   1 scanTrees  2 prefillFoundation  3 clearSurfaceDecor
                    //   4 clearFootprint   -> deja faits ci-dessus
                    //   8 guardWaterEdges  : on endigue AVANT de retirer l'eau,
                    //                        sinon on vide un bassin qui va se
                    //                        remplir de nouveau juste apres.
                    //   7 clearWaterPockets: puis on retire les poches restantes.
                    //   5 smooth 7x7 x50   : le lissage intervient APRES la mise
                    //   6 smooth 15x15 x12   en forme de l'eau, pas avant.
                    //  10 verifyGrassSurface
                    //  11 cleanupZone
                    //   9 fixWaterNearStructure : dernier mot a l'eau, une fois
                    //                        le terrain definitif.
                    // FIX (log : « citadel : 113930 blocs » puis PLUS RIEN, freeze
                    // definitif) -- ces 5 passes etaient SYNCHRONES, sans aucun log
                    // avant la fin : elles ont ete converties en passes TRANCHEES
                    // (une tranche par tick) et chacune annonce desormais sa fin.
                    // L'ordre et les operations sont inchanges. Voir ColumnPass.
                    guardWaterEdges(level, min, max, () -> {                        // 8
                        step("prepZone 8/11 : guardWaterEdges termine");
                        clearWaterPockets(level, min, max, () -> {                  // 7
                            step("prepZone 7/11 : clearWaterPockets termine");
                            // === T81: FIXWATER AVANT SMOOTH (WorldEdit-style) ===
                            // On corrige l'eau AVANT le lissage pour que le smooth ne
                            // ne melange pas fond d'ocean / surface, et que l'eau se
                            // propage naturellement AVANT le remodelage du terrain.
                            // Rayon adaptatif : couvre lacs/ocans coupes par la structure.
                            int adaptiveRadius = calculateAdaptiveWaterRadius(level, min, max);
                            fixWaterNearStructure(level, min, max, adaptiveRadius, () -> {
                                step("prepZone 7b/11 : fixWaterNearStructure termine (rayon " + adaptiveRadius + ")");
                                smoothPass(level, min, max, 7, 50, () -> {              // 5
                                    step("prepZone 5/11 : smooth 7x7 x50 termine");
                                    smoothPass(level, min, max, 15, 12, () -> {         // 6
                                        step("prepZone 6/11 : smooth 15x15 x12 termine");
                                        verifyGrassSurface(level, min, max, () -> {     // 10
                                            step("prepZone 10/11 : verifyGrassSurface termine");
                                            cleanupZone(level, min, max, () -> {        // 11
                                                step("prepZone 11/11 : cleanupZone termine");
                                                fixWaterNearStructure(level, min, max, 20, () -> {  // 9
                                                    step("prepZone 9/11 TERMINE -- pret pour le paste");
                                                    onReady.run();
                                                });
                                            });
                                        });
                                    });
                                });
                            });
                        });
                    });
                });
            });
        });
    }

    /**
     * Retire les poches d'eau residuelles dans le trou de la structure et dans
     * une couronne de securite de 6 blocs. On scanne toute la hauteur, et pas
     * seulement la surface : cela corrige les poches situees a Y+1, Y+2, etc.
     *
     * Version TRANCHEE (voir ColumnPass) : la variante sans callback lance la
     * passe et rend la main immediatement ; celle avec onDone enchaine une fois
     * toutes les colonnes traitees.
     */
    private static void clearWaterPockets(ServerLevel level, BlockPos min, BlockPos max) {
        clearWaterPockets(level, min, max, null);
    }

    private static void clearWaterPockets(ServerLevel level, BlockPos min, BlockPos max, Runnable onDone) {
        final int margin = 6;
        final int y0 = Math.max(level.getMinBuildHeight(), min.getY() - 8);
        final int y1 = Math.min(level.getMaxBuildHeight() - 1, max.getY() + 8);
        final int[] removed = {0};
        startColumnPass(level, "clearWaterPockets", columnsOf(min, max, margin), 128, col -> {
            removed[0] += clearWaterColumn(level, col[0], col[1], y0, y1);
        }, () -> {
            if (removed[0] > 0)
                deepluckyblock.util.DebugLog.structure(
                        "clearWaterPockets : {} blocs d'eau retires dans la zone structure", removed[0]);
            if (onDone != null) onDone.run();
        });
    }

    /** Une colonne de clearWaterPockets : renvoie le nombre de blocs d'eau retires. */
    private static int clearWaterColumn(ServerLevel level, int x, int z, int y0, int y1) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int removed = 0;
        for (int y = y1; y >= y0; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                removed++;
            }
        }
        return removed;
    }

    // Clear le footprint : supprime le terrain naturel AU-DESSUS du sol (pas de cratere,
    // on ne creuse jamais sous la surface). Batche via schedule.
    private static void clearFootprint(ServerLevel level, BlockPos min, BlockPos max, int topY, Runnable onDone) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        startColumnPass(level, "clearFootprint", columnsOf(min, max, 0), BATCH_COLS, col -> {
            int x = col[0], z = col[1];
            int surface = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = topY; y > surface; y--) {
                BlockState state = level.getBlockState(mut.set(x, y, z));
                if (!state.isAir() && isNaturalTerrain(state)) level.setBlock(mut, Blocks.AIR.defaultBlockState(), 2);
            }
        }, onDone);
    }

    // Avant le smooth : remplit les vides SOUS la structure (emprise + 3 radius)
    // avec du grass block, du niveau de base de la structure jusqu'au premier bloc
    // solide. Garantit qu'il n'y a aucun vide sous la structure (pas de flottaison).
    // Batche via schedule.
    private static void prefillFoundation(ServerLevel level, BlockPos min, BlockPos max, int foundationBaseY, Runnable onDone) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int minBuild = level.getMinBuildHeight();
        int[] filled = {0};
        startColumnPass(level, "prefillFoundation", columnsOf(min, max, 3), BATCH_COLS, col -> {
            int x = col[0], z = col[1];
            int surface = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            if (foundationBaseY > surface) return;
            int y = foundationBaseY, depth = 0;
            while (y >= minBuild && depth < 64) {
                BlockState state = level.getBlockState(mut.set(x, y, z));
                if (!state.isAir() && state.blocksMotion()) break;
                level.setBlock(mut, Blocks.GRASS_BLOCK.defaultBlockState(), FAST_FLAG);
                filled[0]++; y--; depth++;
            }
        }, () -> {
            if (filled[0] > 0) deepluckyblock.util.DebugLog.structure("prefillFoundation: {} blocks filled", filled[0]);
            if (onDone != null) onDone.run();
        });
    }

    // FIX CRITIQUE (rapporte en jeu : structure "Sylvan Citadel" spawnee a
    // moitie dans le vide, log "deltaY anormal apres smooth ... BORNE a -4").
    // Appelee en tout DERNIER recours, juste avant le paste reel, quand le
    // garde-fou MAX_POST_SMOOTH_DELTA_Y a du borner un effondrement de terrain
    // plus important que ce qu'il autorise a suivre (ravin/grotte/falaise
    // demasque par le smooth SEULEMENT APRES que verifyGrassSurface/
    // naturalize/cleanupZone de prepZone() aient deja tourne -- donc AUCUNE
    // des passes automatiques normales ne traite plus cette zone).
    //
    // CORRECTIF DEMANDE (retour utilisateur, ancien correctif juge
    // insuffisant : "il faut en dessous bien aplanir et creer la plateforme,
    // smooth le terrain en grand pour que le nouveau terrain soit
    // parfaitement blend, avec sur la stone des grass block pour rendre la
    // stone invisible, replanter si necessaire") : l'ancien backfillVoidGap
    // se contentait de boucher le trou EXACT sous l'emprise avec de la pierre
    // brute -- aucun raccord avec le relief environnant (mur vertical de
    // pierre nue a la limite de l'emprise), aucune herbe, aucune
    // replantation. Remplace par une VRAIE construction de plateforme :
    //   1. Sous l'emprise complete de la structure (+ marge de securite) :
    //      plateau plat solide (pierre en profondeur, terre pres de la
    //      surface, herbe en surface) au niveau ou la structure va etre
    //      posee -- garantit qu'aucune colonne du footprint ne reste creuse.
    //   2. Une COURONNE DE RACCORD (rampe en pente lineaire) autour du
    //      plateau, sur RAMP_RING blocs, qui descend/monte progressivement
    //      du niveau du plateau JUSQU'AU niveau REEL du terrain naturel deja
    //      mesure (realTerrainY) -- exactement le "smooth le terrain en
    //      grand pour blend" demande : plus de falaise nette, un talus doux.
    //   3. Chaque colonne traitee recoit un CAPPING D'HERBE explicite en
    //      surface (grass_block, ou neige si biome enneige) : la pierre
    //      utilisee pour combler/soutenir n'est donc JAMAIS visible.
    //   4. La replantation des arbres et la re-vegetalisation (fleurs,
    //      herbes hautes, champignons) de cette zone sont deja garanties
    //      ENSUITE par decorate() (naturalize + replantTrees, appeles apres
    //      le carve sur min/max reels de la structure + terrainRing() +
    //      NATURALIZE_EXTRA_RING, qui englobe cette couronne de raccord tant
    //      que RAMP_RING <= terrainRing()).
    private static final int PLATFORM_MAX_FILL_DEPTH = 64;
    // Largeur de la rampe de raccord (blocs) entre le plateau plat sous la
    // structure et le terrain naturel deja mesure. Volontairement <=
    // terrainRing() (14 a 48 selon la taille du build) pour que la
    // re-vegetalisation automatique de decorate() couvre bien toute la zone.
    private static final int RAMP_RING = 16;
    /** Temps maximal accorde au chargement des chunks dans backfillVoidGap. */
    private static final long BACKFILL_LOAD_BUDGET_MS = 1500;

    public static void backfillVoidGap(ServerLevel level, BlockPos pasteMin, BlockPos pasteMax, int realTerrainY) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int platformFloorY = pasteMin.getY() - 1;
        int minBuild = level.getMinBuildHeight();
        int maxBuild = level.getMaxBuildHeight() - 1;
        int ring = Math.min(RAMP_RING, terrainRing());

        int x0 = pasteMin.getX() - ring, x1 = pasteMax.getX() + ring;
        int z0 = pasteMin.getZ() - ring, z1 = pasteMax.getZ() + ring;

        // Pre-chargement des chunks de la zone (comme smoothPass) : sans ca,
        // getHeight() sur un chunk non genere renverrait une hauteur fantome.
        //
        // Cette passe tourne APRES le paste, donc les chunks du coeur sont deja
        // charges ; seules les bordures de l'anneau peuvent manquer. On borne
        // tout de meme le temps passe (meme cause de freeze que smoothPass :
        // generation synchrone loin du joueur) et on tolere un chunk manquant,
        // la colonne correspondante etant simplement laissee en l'etat.
        long loadStart = System.currentTimeMillis();
        for (int ccx = x0 >> 4; ccx <= x1 >> 4; ccx++) {
            for (int ccz = z0 >> 4; ccz <= z1 >> 4; ccz++) {
                if (System.currentTimeMillis() - loadStart > BACKFILL_LOAD_BUDGET_MS) {
                    deepluckyblock.util.DebugLog.structure(
                            "backfillVoidGap : budget de chargement ({} ms) atteint, bordures restantes ignorees",
                            BACKFILL_LOAD_BUDGET_MS);
                    ccx = (x1 >> 4) + 1;
                    break;
                }
                // T49 : demande en tache de fond, aucune generation synchrone. Un
                // chunk absent est simplement ignore pour ce passage (le budget de
                // temps redevient alors reellement respecte).
                deepluckyblock.util.SafeSurface.request(level, ccx, ccz);
                if (!deepluckyblock.util.SafeSurface.loaded(level, ccx, ccz)) continue;
            }
        }

        // Graine du bruit de rampe : stable pour un site donne.
        long rampSeed = level.getSeed() ^ ((long) pasteMin.getX() * 7311871L)
                ^ ((long) pasteMin.getZ() * 5131717L) ^ 0x2A11AL;

        int filled = 0, capped = 0, cleared = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                // FIX (rapporte en jeu : "on voit clairement des bords
                // rectangulaires terrain tout autour de la structure" et "tout
                // descend en pente parfaite").
                //
                // Cette rampe utilisait elle aussi Math.max(dx,dz) (distance de
                // Tchebychev -> lignes de niveau CARREES) avec une interpolation
                // LINEAIRE : le talus autour de chaque structure etait donc un
                // tronc de pyramide a pente constante, parfaitement geometrique.
                //
                // On applique ici le meme traitement que dans smoothPass() :
                //   - distance euclidienne (coins arrondis) ;
                //   - contour ondule par bruit coherent, avec une rampe pour ne
                //     pas creer de marche au pied de la structure ;
                //   - profil en S (smoothstep) au lieu d'une droite, pour un
                //     raccord tangent au plateau et au terrain naturel.
                int dx = x < pasteMin.getX() ? pasteMin.getX() - x : (x > pasteMax.getX() ? x - pasteMax.getX() : 0);
                int dz = z < pasteMin.getZ() ? pasteMin.getZ() - z : (z > pasteMax.getZ() ? z - pasteMax.getZ() : 0);
                double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (dist > 0.0) {
                    double n = valueNoise2D(rampSeed, x * 0.060, z * 0.060) * 0.70
                             + valueNoise2D(rampSeed + 4242L, x * 0.150, z * 0.150) * 0.30;
                    double rampIn = Math.min(1.0, dist / 3.0);
                    dist = Math.max(0.0, dist + n * ring * 0.22 * rampIn);
                }

                int targetY;
                if (dist <= 0.0) {
                    targetY = platformFloorY; // plateau plat, exactement sous la structure
                } else if (dist >= ring) {
                    targetY = realTerrainY; // deja raccorde au terrain naturel
                } else {
                    double t = dist / (double) ring;
                    t = t * t * (3 - 2 * t); // smoothstep : plus de pente rectiligne
                    targetY = (int) Math.round(platformFloorY + (realTerrainY - platformFloorY) * t);
                }
                targetY = Math.max(minBuild + 1, Math.min(maxBuild - 1, targetY));

                boolean snowy = isSnowy(level, mut.set(x, targetY, z));
                BlockState capBlock = Blocks.GRASS_BLOCK.defaultBlockState();

                int curSurfaceY = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (curSurfaceY < targetY) {
                    // Trou/vide sous le niveau vise : on remonte (pierre en
                    // profondeur, terre pres du sommet, herbe en surface).
                    int y = Math.max(curSurfaceY + 1, minBuild);
                    int depth = 0;
                    while (y <= targetY && depth < PLATFORM_MAX_FILL_DEPTH) {
                        BlockState fill = (y == targetY) ? capBlock
                                : (y >= targetY - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
                        level.setBlock(mut.set(x, y, z), fill, FAST_FLAG);
                        filled++; y++; depth++;
                    }
                } else if (curSurfaceY > targetY) {
                    // FIX (rapporte en jeu : « tu as encore cree un trou », et
                    // dans le log : « 229830 blocs de surplomb retires »).
                    //
                    // Cette branche RASAIT tout terrain depassant la cible.
                    // C'est elle qui creusait la cuvette : sur un site vallonne
                    // la "cible" est plus basse que le relief, et on detruisait
                    // des centaines de milliers de blocs pour forcer un plateau.
                    //
                    // backfillVoidGap est un filet de securite contre une
                    // structure SUSPENDUE : son seul travail legitime est de
                    // COMBLER ce qui manque, jamais de creuser. On ne touche
                    // donc plus rien ici -- le terrain existant est conserve
                    // tel quel, et c'est le smooth de la couronne qui fond les
                    // abords.
                    // (aucune suppression : voir le commentaire ci-dessus)
                } else {
                    // Deja au bon niveau : on s'assure juste que la pierre/terre
                    // n'est jamais visible en surface (demande explicite).
                    BlockState top = level.getBlockState(mut.set(x, targetY, z));
                    if (top.is(Blocks.STONE) || top.is(Blocks.DIRT) || top.is(Blocks.COBBLESTONE)
                            || top.is(Blocks.DEEPSLATE) || top.is(Blocks.ANDESITE) || top.is(Blocks.DIORITE) || top.is(Blocks.GRANITE)) {
                        level.setBlock(mut, capBlock, FAST_FLAG);
                        capped++;
                    }
                }

                if (snowy && level.getBlockState(mut.set(x, targetY + 1, z)).isAir()) {
                    level.setBlock(mut, Blocks.SNOW.defaultBlockState(), FAST_FLAG);
                }
            }
        }
        if (filled > 0 || capped > 0 || cleared > 0) {
            LOGGER.warn("[STRUCT5-VOID] plateforme de secours construite (raccord blend sur {} blocs) : {} blocs combles, {} blocs herbe/neige poses en surface, {} blocs de surplomb retires",
                    ring, filled, capped, cleared);
        }
    }


    // Avant le smooth : retire TOUS les decors de surface (feuilles, herbe, fleurs,
    // neige, vignes...) sur toute la zone GIGA. Sinon le smooth abaisse le terrain
    // et ces decors se retrouvent a flotter a leur ancienne position. Batche.
    private static void clearSurfaceDecor(ServerLevel level, BlockPos min, BlockPos max, Runnable onDone) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        startColumnPass(level, "clearSurfaceDecor", columnsOf(min, max, terrainRing()), BATCH_COLS, col -> {
            int x = col[0], z = col[1];
            int surface = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            // Keep the same canopy/trunk scan; only its scheduling changes.
            int top = Math.min(surface + 64, Math.max(surface + 18,
                    deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING, x, z) + 2));
            for (int y = top; y >= surface - 25; y--) {
                if (y < level.getMinBuildHeight()) break;
                BlockState state = level.getBlockState(mut.set(x, y, z));
                if (!state.isAir() && isSurfaceDecor(state)) level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG);
            }
        }, onDone);
    }

    /**
     * Decoration APRES paste + carve : herbe + replant des arbres sauves + naturalize
     * + cleanup (lave, eau courante fixee, feuilles volantes).
     */
    /**
     * T39 -- PHASE TERRAIN (AVANT la pose de la structure).
     *
     * <p>Consigne dev du 23/09 : « la structure doit paste en absolu dernier,
     * apres tous les smooths ». Tout le remodelage du terrain (failles, double
     * smooth, eau, berges, passe finale, /fixwater + /fixlava) est donc execute
     * ICI, et {@code afterTerrain} (le paste de la structure) n'est appele
     * qu'une fois le terrain DEFINITIF.
     */
    public static void decorateTerrainOnly(ServerLevel level, BlockPos min, BlockPos max, Runnable afterTerrain) {
        decorateChain(level, min, max, true, afterTerrain);
    }

    /**
     * T39 -- PHASE FINITIONS (APRES la pose de la structure).
     *
     * <p>Ne contient plus AUCUN remodelage de terrain : uniquement les decors
     * thematiques, les arbres sauves, la naturalisation, le nettoyage final et
     * la porte de stabilisation. C'est ce qui rend la regle « la structure est
     * posee en dernier » verifiable dans les logs.
     */
    public static void decorateFinish(ServerLevel level, BlockPos min, BlockPos max) {
        final String chainKey = chainTag(min, max);
        if (!deepluckyblock.util.TerrainChain.acquire(chainKey)) {
            deepluckyblock.util.DebugLog.structure(
                    "decorateFinish : chaine terrain occupee par {} -- reporte de {} ticks",
                    deepluckyblock.util.TerrainChain.owner(), CHAIN_WAIT_TICKS);
            TestProcedure.schedule(level, TestProcedure.currentTick(level) + CHAIN_WAIT_TICKS,
                    () -> decorateFinish(level, min, max));
            return;
        }
        deepluckyblock.util.DebugLog.structure(
                "decorateFinish (POST-POSE, apres TOUS les fixwater) : decors + arbres + naturalisation (aucun smooth) -- T75");
        deepluckyblock.util.ChunkKeeper.track(level,
                new BlockPos(min.getX() - terrainRing(), min.getY(), min.getZ() - terrainRing()),
                new BlockPos(max.getX() + terrainRing(), max.getY(), max.getZ() + terrainRing()));
        clearFootprintProtection();   // plus rien a proteger : la structure est posee
        StructureScatterDecor.scatter(level, min, max, LAST_STRUCTURE_NAME);              // 16
        deepluckyblock.util.DebugLog.structure("decorate 5/5 : decors (scatter) poses");
        replantTrees(level, LAST_TREES, min, max, () -> {                                  // 17
            verifyGrassSurface(level, min, max, () -> {                                    // 10
                naturalize(level, min, max, level.getRandom(), () -> {                     // 18 (T77 : decoupee)
                    cleanupZone(level, min, max, () -> {                                   // 19
                        logTerrainDelta(level, min, max);
                        deepluckyblock.util.DebugLog.setPhase("stabilisation puis relachement des chunks");
                        deepluckyblock.util.SettleGate.wait(level, min, max, () -> {
                            deepluckyblock.util.TerrainChain.release(chainKey);
                            deepluckyblock.util.ChunkKeeper.release(level);
                            step("decorate TERMINE (decors, arbres, naturalisation -- structure posee en DERNIER, T39)");
                        });
                    });
                });
            });
        });
    }

    /** Chaine historique complete (terrain PUIS finitions) : appelee par l'appelant
     *  historique (lac de crimson). Les deux pipelines principaux utilisent
     *  desormais {@link #decorateTerrainOnly} puis {@link #decorateFinish}. */
    public static void decorate(ServerLevel level, BlockPos min, BlockPos max) {
        decorateChain(level, min, max, false, null);
    }

    private static void decorateChain(ServerLevel level, BlockPos min, BlockPos max,
                                      boolean terrainPhase, Runnable afterTerrain) {
        // T6 : meme verrou que prepZone (voir le commentaire la-bas).
        final String chainKey = chainTag(min, max);
        if (!deepluckyblock.util.TerrainChain.acquire(chainKey)) {
            deepluckyblock.util.DebugLog.structure(
                    "decorate : chaine terrain occupee par {} -- reporte de {} ticks",
                    deepluckyblock.util.TerrainChain.owner(), CHAIN_WAIT_TICKS);
            TestProcedure.schedule(level, TestProcedure.currentTick(level) + CHAIN_WAIT_TICKS,
                    () -> decorate(level, min, max));
            return;
        }
        // T23 : les vegetaux rendus flottants par le remodelage sont retires
        // AVANT le reste du decor (consigne « des cocoa qui volent »).
        SWEPT.set(0);
        sweepFloatingDecor(level, min, max, 8, () -> {
        deepluckyblock.util.DebugLog.structure(
                "balayage des vegetaux flottants : {} bloc(s) de nature retire(s) "
                        + "(cacao, vignes, bamboos, feuilles sans support)", SWEPT.get());

        // ===================================================================
        // ORDRE POST-PASTE IMPOSE PAR L'UTILISATEUR
        //   14 cleanupZone
        //   15 eau (clearWaterPockets + guardWaterEdges + fixWaterNearStructure)
        //   -- re-analyse du terrain vu de haut : failles / marches
        //   5+6 double smooth (emprise de la structure PROTEGEE)
        //   12 (deja fait : la structure est posee)
        //   -- boucle anti-fuite d'eau jusqu'a etancheite
        //   5+6 smooth cible sur la zone lac, hors structure
        //   15 eau a nouveau
        //   16 decors (9-16, dont 3-7 naturels)
        //   17 arbres   18 naturalisation   19 cleanup final
        // ===================================================================

        // L'emprise du batiment ne doit JAMAIS etre lissee : le smooth raisonne
        // en heightmap et raboterait la structure. Protection active pour tous
        // les smooths de cette phase.
        // T6/T9 : on (re)declare la zone suivie pour TOUTE la phase decorate.
        // Sans ca, si la zone avait ete relachee (ancienne grace de 10 s expiree
        // pendant un paste de 30 s), les passes de fin sautaient leurs colonnes :
        // « fixWaterNearStructure : 13708 colonnes sautees (chunk absent) ».
        deepluckyblock.util.ChunkKeeper.track(level,
                new BlockPos(min.getX() - terrainRing(), min.getY(), min.getZ() - terrainRing()),
                new BlockPos(max.getX() + terrainRing(), max.getY(), max.getZ() + terrainRing()));

        // T39 : en phase TERRAIN (avant la pose) rien n'est protege -- la structure
        // n'existe pas encore et le sol doit etre lisse PARTOUT (c'est ce qui
        // supprime la couture « terrain colle a la structure »). En phase
        // post-pose (appel historique complet), l'emprise reste intouchable.
        if (terrainPhase) clearFootprintProtection(); else protectFootprint(min, max);

        // FIX (meme cause que prepZone : passes synchrones sans log = freeze
        // invisible). Chaque groupe est desormais enchaine sur la fin reelle de
        // la passe precedente, et chaque etape s'annonce dans le log.
        // ORDRE ET OPERATIONS INCHANGES.
        cleanupZone(level, min, max, () ->                  // 14
        clearWaterPockets(level, min, max, () ->            // 15
        guardWaterEdges(level, min, max, () ->
        fixWaterNearStructure(level, min, max, 20, () -> {

        deepluckyblock.util.DebugLog.structure(
                "decorate 1/5 : cleanup + eau (14/15) termines");

        // Re-analyse vue de haut : comble les failles laissees par le carve et
        // adoucit toute marche de plus de 2 blocs (« pas de tas qui perdent
        // d'un coup 3 blocs »).
        deepluckyblock.util.DebugLog.structure("decorate 2/5 : fillGapsAndSteps debut");
        startGapStepPass(level, min, max, 2, () -> {
        deepluckyblock.util.DebugLog.structure("decorate 2/5 : fillGapsAndSteps termine");

        postPasteSmooth(level, min, max, 7, 50, () ->       // 5
            postPasteSmooth(level, min, max, 15, 12, () -> { // 6

                deepluckyblock.util.DebugLog.structure("decorate 3/5 : double smooth termine");

                // Boucle anti-fuite : endigue, vidange, refixe, recommence
                // jusqu'a ce que plus rien ne coule (8 iterations max).
                deepluckyblock.util.DebugLog.structure("decorate 3/5 : sealWaterLeaks debut");
                startSealLeaksPass(level, min, max, 8, () -> {
                deepluckyblock.util.DebugLog.structure("decorate 3/5 : sealWaterLeaks termine");

                // Smooth cible sur la zone d'eau, structure toujours protegee.
                postPasteSmooth(level, min, max, 7, 50, () ->
                    postPasteSmooth(level, min, max, 15, 12, () -> {

                        deepluckyblock.util.DebugLog.structure("decorate 4/5 : smooth cible eau termine");

                        clearWaterPockets(level, min, max, () ->     // 15 (rappel)
                        guardWaterEdges(level, min, max, () ->
                        fixWaterNearStructure(level, min, max, 20, () -> {

                        // T33 : PASSE FINALE -- la toute derniere operation sur le
                        // terrain, sur le SOL uniquement, une fois l'eau et les
                        // berges faites (consigne en jeu : « le smooth global n'a
                        // pas ete fait en dernier sur le terrain sol uniquement »).
                        // ============================================================
                        // T39 : LA PASSE FINALE EST ATTENDUE, PUIS LE TERRAIN EST FIGE
                        // ============================================================
                        // Avant T39, cette passe etait lancee sans rappel de fin : les
                        // decors et les arbres se posaient PENDANT qu'elle rabotait
                        // encore le sol -- d'ou des mini-structures flottant de 1-2
                        // blocs (FINAL_MAX_DELTA = 2 !). Elle est desormais attendue.
                        // T41 : dernier balayage des lianes / blocs naturels
                        // flottants (restes de l'ANCIEN terrain) -- AVANT la pose,
                        // pour que rien ne vole au-dessus du terrain livre.
                        sweepFloatingNaturalPass(level, min, max, () ->
                        // T40 : HOLE FILLER -- tunnels, failles et cavites
                        // rebouches AVANT la passe finale (donc avant la structure).
                        sealUndergroundGaps(level, min, max, () ->
                        finalGroundPass(level, min, max, 6, () -> {
                        if (terrainPhase) {
                            // Derniere operation sur le terrain : /fixwater + /fixlava
                            // (T38), puis la main passe au paste de la structure.
                            fixLiquidsPass(level, min, max, () -> {
                                // T44 : la chaine terrain est RENDUE avant le paste (le
                                // ChunkKeeper reste actif : la pose le renouvelle a chaque
                                // tick). Sans cette liberation, la phase post-pose
                                // (decorateFinish, en fin de carve) se reportait
                                // indefiniment : « chaine terrain occupee par ... ».
                                deepluckyblock.util.TerrainChain.release(chainKey);
                                step("decorate TERRAIN TERMINE : terrain fige, la structure peut etre posee (T39)");
                                if (afterTerrain != null) afterTerrain.run();
                            });
                            return;
                        }

                        // ============================================================
                        // BRANCHE POST-POSE (appel historique complet) : finitions
                        // uniquement, plus aucun smooth ni passe de terrain.
                        // ============================================================
                        clearFootprintProtection();

                        StructureScatterDecor.scatter(level, min, max, LAST_STRUCTURE_NAME); // 16
                        deepluckyblock.util.DebugLog.structure("decorate 5/5 : decors (scatter) poses");

                        replantTrees(level, LAST_TREES, min, max, () -> {                    // 17
                            // FIX (rapporte en jeu : « la surface du trou est de
                            // pierre etc, je ne vois plus du tout la partie re
                            // naturalisation, pas de grass block au sol, pas de
                            // plantes... sauf aux chunks colles a la structure »).
                            //
                            // CAUSE : naturalize() ne pose de la vegetation QUE
                            // sur une colonne dont la surface est deja un
                            // GRASS_BLOCK. Or les smooths post-paste
                            // reconstruisent le terrain en STONE/DIRT : sur
                            // toute la couronne remodelee la surface n'etait
                            // plus de l'herbe, donc naturalize la sautait
                            // entierement. Seuls les abords immediats, non
                            // reconstruits, gardaient leur herbe -- exactement
                            // les « chunks colles a la structure » decrits.
                            //
                            // verifyGrassSurface() ne tournait que dans
                            // prepZone, AVANT le paste. On le rejoue ici, juste
                            // avant de semer : la surface redevient herbeuse sur
                            // toute la zone, et la naturalisation peut operer
                            // partout.
                            verifyGrassSurface(level, min, max, () -> {                      // 10
                                naturalize(level, min, max, level.getRandom(), () -> {       // 18 (T77)
                                cleanupZone(level, min, max, () -> {                         // 19
                                    // === T13 : DEGEL DE L'EAU (guide, section 27, etape 11) ===
                                    // Le relief est definitif : l'eau gelee est restituee
                                    // maintenant, dans la bonne configuration (source la ou
                                    // c'etait une source, deplacement au nouveau sol la ou le
                                    // smooth a creuse). La fenetre DLB-CLAMP est encore
                                    // OUVERTE, donc les ticks de fluide restent neutralises
                                    // pendant la remise en place : aucune cascade possible.
                                    // L'emprise de la structure est exclue : l'eau du NBT
                                    // (fontaines, blocs waterlogged) n'est jamais ecrasee.
                                    // T23 : plus de degel non plus (l'eau n'a jamais ete
                                    // retiree) -- on passe directement a la mesure finale
                                    // puis a la porte de stabilisation.
                                    {
                                    // === T16 : PORTE DE STABILISATION (guide, sections 30/31/45) ===
                                    // Mesure en jeu du 20/09 (observatoire @400, monde neuf) :
                                    // « decorate TERMINE » n'a JAMAIS pu s'ecrire -- le serveur est
                                    // mort AVANT, sur un tick de 60,27 s, apres une phase pourtant
                                    // journalisee comme finie. On ne rend donc plus la zone au chunk
                                    // system des la derniere passe : on attend que le monde soit
                                    // REELLEMENT retombe (aucune entite de chute en vol, moteur de
                                    // lumiere au repos) avant de declarer la structure terminee.
                                    // T21 : MESURE FINALE honnete -- de combien le
                                    // terrain livre s'ecarte-t-il du terrain NATUREL
                                    // (hors emprise de la structure et hors plans
                                    // d'eau) ? C'est la reponse chiffree a « le
                                    // paysage est-il detruit ? ».
                                    logTerrainDelta(level, min, max);
                                    deepluckyblock.util.DebugLog.setPhase("stabilisation puis relachement des chunks");
                                    deepluckyblock.util.SettleGate.wait(level, min, max, () -> {
                                    // Fin de chaine : le terrain est definitif et la
                                    // zone peut etre rendue au chunk system.
                                    deepluckyblock.util.TerrainChain.release(chainKey);
                                    deepluckyblock.util.ChunkKeeper.release(level);
                                    step("decorate TERMINE (failles, smooths, fuites, decors, arbres, naturalisation)");
                                    });
                                    }   // T23 : fin du bloc "eau intacte" (ex-fin du degel)
                                });
                                });   // T77 : fin de la naturalisation decoupee
                            });
                        });
                        })));
                        })));
                    }));
            });   // fin de la continuation sealWaterLeaks
            }));
        });       // fin de la continuation fillGapsAndSteps
        }))));
        });   // T23 : fin du balayage des vegetaux flottants
    }

    /**
     * Nom de la structure en cours, memorise pour que decorate() puisse choisir
     * le theme du decor sans changer sa signature (appelee depuis plusieurs
     * procedures).
     */
    private static String LAST_STRUCTURE_NAME = null;

    public static void setStructureName(String name) { LAST_STRUCTURE_NAME = name; }


    /** Cleanup final sur la zone GIGA : retire la lave, fixe l'eau courante en
     *  source (fixwater), supprime les feuilles/bois volants laisses par le smooth. */
    public static void cleanupZone(ServerLevel level, BlockPos min, BlockPos max) {
        cleanupZone(level, min, max, null);
    }

    private static void cleanupZone(ServerLevel level, BlockPos min, BlockPos max, Runnable onDone) {
        final int[] counters = {0, 0};   // [0] = removed (lave/feuilles volantes), [1] = eau fixee
        startColumnPass(level, "cleanupZone", columnsOf(min, max, terrainRing()), 96, col -> {
            cleanupColumn(level, col[0], col[1], counters);
        }, () -> {
            if (counters[0] > 0 || counters[1] > 0)
                deepluckyblock.util.DebugLog.structure(
                        "cleanupZone : {} blocs retires (lave/feuilles volantes), {} eau fixee",
                        counters[0], counters[1]);
            if (onDone != null) onDone.run();
        });
    }

    /** Une colonne de cleanupZone (met a jour counters[removed, waterFixed]). */
    private static void cleanupColumn(ServerLevel level, int x, int z, int[] counters) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int minB = level.getMinBuildHeight();
        int surfY = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z);
        int top = Math.min(surfY + 25, level.getMaxBuildHeight() - 1);
        int bot = Math.max(surfY - 25, minB);
        for (int y = top; y >= bot; y--) {
            BlockState s = level.getBlockState(mut.set(x, y, z));
            if (s.isAir()) continue;
            if (s.is(Blocks.LAVA)) { level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG); counters[0]++; continue; }
            if (s.is(Blocks.WATER)) {
                int lvl = s.getOptionalValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL).orElse(0);
                if (lvl > 0) { level.setBlock(mut, Blocks.WATER.defaultBlockState(), FAST_FLAG); counters[1]++; }
                continue;
            }
            if (isDecoration(s) && isFloatingFoliage(level, mut, x, y, z)) {
                level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG); counters[0]++;
            }
        }
    }

    // Sphere PLEINE de grass_block radius DAM_RADIUS, centree avec un decalage en
    // ZIGZAG (cellules de 4 blocs). COMPLETE : remplit l'air ET l'eau dans la sphere
    // (0 poche d'eau = 0 fuite). Au-dessus de la ligne d'eau on ne bouche pas le ciel
    // au-dessus du lac. Posee AVANT le smooth : le smooth IGNORE ces colonnes (DAM_COLS)
    // pour ne PAS ecraser les spheres en cylindres plats.
    private static int placeDamBank(ServerLevel level, int cx, int cy, int cz) {

        // Spheres de barrage placees deux blocs plus haut.
        int centerY = cy + 5;
        int placed = placeFullSphere(level, cx, centerY, cz, DAM_RADIUS);

        // Entre 1 et 6 spheres secondaires de rayon 5, collees a la sphere principale.
        int secondaryCount = 1 + level.getRandom().nextInt(6);
        int[] directions = new int[]{0, 1, 2, 3, 4, 5};
        shuffleDirections(directions, level.getRandom());

        int touchingDistance = DAM_RADIUS + 5;

        for (int i = 0; i < secondaryCount; i++) {
            int direction = directions[i];
            int sx = cx;
            int sz = cz;

            switch (direction) {
                case 0 -> sx += touchingDistance;
                case 1 -> sx -= touchingDistance;
                case 2 -> sz += touchingDistance;
                case 3 -> sz -= touchingDistance;
                case 4 -> {
                    sx += 10;
                    sz += 5;
                }
                case 5 -> {
                    sx -= 10;
                    sz -= 5;
                }
                default -> { }
            }

            placed += placeFullSphere(level, sx, centerY, sz, 5);
        }

        return placed;
    }

    /** Place une sphere pleine voxelisee, comme WorldEdit //br sphere. */
    private static int placeFullSphere(ServerLevel level, int cx, int cy, int cz, int radius) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int radiusSquared = radius * radius;
        int count = 0;
        int minBuild = level.getMinBuildHeight();
        int maxBuild = level.getMaxBuildHeight() - 1;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSquared) continue;

                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;

                    if (y < minBuild || y > maxBuild) continue;

                    pos.set(x, y, z);
                    level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), FAST_FLAG);
                    DAM_COLS.add(colKey(x, z));
                    count++;
                }
            }
        }

        return count;
    }

    private static void shuffleDirections(int[] values, RandomSource random) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temp = values[i];
            values[i] = values[j];
            values[j] = temp;
        }
    }

    // Barrages d'eau : AVANT le smooth. Scanne la zone pour les points de fuite (eau
    // qui touche de l'air) et place des spheres PLEINES de grass_block. Comme c'est
    // avant le smooth, le smooth passe ensuite dessus et arrondit les spheres en
    // berges naturelles (au lieu de domes crus trop spheriques).
    public static void placeWaterDams(ServerLevel level, BlockPos min, BlockPos max) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos mut2 = new BlockPos.MutableBlockPos();
        int damRing = terrainRing();
        int x0 = min.getX() - damRing, x1 = max.getX() + damRing;
        int z0 = min.getZ() - damRing, z1 = max.getZ() + damRing;
        int minB = level.getMinBuildHeight();
        int placed = 0;
        DAM_COLS.clear();  // reset des colonnes de barrage (le smooth les ignorera)
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int surfY = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z);
                int top = Math.min(surfY + 25, level.getMaxBuildHeight() - 1);
                int bot = Math.max(surfY - 25, minB);
                for (int y = top; y >= bot; y--) {
                    BlockState waterState = level.getBlockState(mut.set(x, y, z));
                    if (!waterState.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) continue;
                    boolean leak = false;
                    for (int[] dir : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                        if (level.getBlockState(mut2.set(x + dir[0], y, z + dir[1])).isAir()) { leak = true; break; }
                    }
                    if (leak) placed += placeDamBank(level, x, y, z);
                }
            }
        }
        if (placed > 0) deepluckyblock.util.DebugLog.structure("placeWaterDams (AVANT smooth, zone elargie ring={}) : {} blocs de grass_block places", damRing, placed);
    }

    // Feuilles/bois volants : rien de solide dans les 3 blocs en dessous.
    private static boolean isFloatingFoliage(ServerLevel level, BlockPos.MutableBlockPos m, int x, int y, int z) {
        for (int dy = 1; dy <= 3; dy++) {
            BlockState b = level.getBlockState(m.set(x, y - dy, z));
            if (!b.isAir() && b.blocksMotion()) return false;
        }
        return true;
    }

    // =========================================================================
    // PHASE 1 : SCAN TREES
    // =========================================================================

    // T76 : les anciennes bornes de balayage (rayon 10, hauteur 40, 1200 blocs)
    // ont ete retirees -- le tronc seul suffit, aucun plafond n'est necessaire.

    /**
     * T76 : vrai si ce bloc est du TERRAIN, c'est-a-dire la fin de la recherche
     * d'un tronc dans une colonne.
     *
     * <p>Liste volontairement large (tags vanilla plutot qu'enumeration) : herbe,
     * terre, pierre et leurs variantes, sable, gravier, argile, terracotta,
     * neige pleine, glace. Tout bloc ABSENT de cette liste fait continuer la
     * lecture -- c'est le choix sur : au pire on relit 48 blocs comme avant,
     * jamais on ne manque un arbre.
     */
    private static boolean isGroundStop(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.DIRT)
                || s.is(net.minecraft.tags.BlockTags.SAND)
                || s.is(net.minecraft.tags.BlockTags.TERRACOTTA)
                || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                || s.is(net.minecraft.tags.BlockTags.STONE_ORE_REPLACEABLES)
                || s.is(net.minecraft.tags.BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.ICE) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(Blocks.MUD) || s.is(Blocks.PACKED_MUD);
    }

    private static List<SavedTree> scanTrees(ServerLevel level, int x0, int z0, int x1, int z1, int topY) {
        List<SavedTree> trees = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();

        // OPTIMISATION (log : 2 min 10 entre le choix du site et « prepZone :
        // 2863 arbres sauves »). L'ancienne boucle descendait de topY jusqu'a
        // y=1 sur CHAQUE colonne, soit ~300 lectures de bloc par colonne et
        // plusieurs dizaines de millions sur une grande zone -- alors qu'un
        // tronc d'arbre ne se trouve jamais a 40 blocs sous la surface.
        // On borne le scan a une fenetre autour de la surface reelle de la
        // colonne : meme resultat, une fraction du cout.
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int colTop = Math.min(topY, deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING, x, z) + 1);
                // T76 : ARRET DES QU'ON ATTEINT LE SOL. La lecture descendait de
                // 48 blocs sur CHAQUE colonne de la zone (~30 000 x 48 = 1,44 M
                // lectures) alors que 95 % des colonnes ne contiennent aucun arbre.
                // Or sous le sol il n'y a jamais de tronc : la lecture s'arrete
                // donc au premier bloc de terrain rencontre.
                //
                // POURQUOI PAS UN SAUT SUR LES HEIGHTMAPS (essaye, mesure, rejete) :
                // « si la heightmap avec feuilles est au niveau de celle sans
                // feuilles, il n'y a pas d'arbre » est FAUX -- MOTION_BLOCKING_
                // NO_LEAVES compte les BUCHES, donc chez l'epicea, dont le tronc
                // depasse la cime, les deux valeurs sont egales et l'arbre etait
                // saute. Mesure : 11 arbres sauves au lieu de 19 sur la meme zone.
                int colGround = Math.min(topY, deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1) - 2;
                int colBottom = Math.max(1, colTop - 48);
                for (int y = colTop; y >= colBottom; y--) {
                    long key = BlockPos.asLong(x, y, z);
                    if (visited.contains(key)) continue;

                    BlockState s = level.getBlockState(mut.set(x, y, z));
                    // Le tronc est teste AVANT l'arret : cime d'epicea, tronc sous
                    // un couvert vegetal, tronc d'un arbre voisin -- tous sont lus
                    // avant que le sol ne soit atteint.
                    if (isLog(s)) {
                    } else {
                        // Terrain atteint : plus rien a trouver en dessous.
                        // (la neige, la glace fine et tout bloc non reconnu ne
                        //  declenchent PAS l'arret : on reste sur l'ancien
                        //  comportement plutot que de risquer un arbre manque)
                        if (y <= colGround && isGroundStop(s)) break;
                        continue;
                    }

                    // Trouve un arbre : flood-fill depuis le bas du tronc
                    int trunkBaseY = y;
                    while (trunkBaseY > 1 && isLog(level.getBlockState(mut.set(x, trunkBaseY - 1, z)))) {
                        trunkBaseY--;
                    }

                    // T76 : collecte BORNEE (voir collectTree). L'ancien flood-fill
                    // empilait jusqu'a 100 entrees par bloc collecte : ~7 millions
                    // d'insertions et autant d'allocations pour 148 arbres, mesure
                    // in-game 9,43 s de gel sur un seul tick.
                    List<SavedBlock> treeBlocks = collectTree(level, x, trunkBaseY, y, z, visited, mut);

                    String type = treeTypeOf(treeBlocks.isEmpty() ? Blocks.OAK_LOG.defaultBlockState()
                            : treeBlocks.get(0).state());
                    trees.add(new SavedTree(x, z, trunkBaseY, type, treeBlocks));
                }
            }
        }
        if (TREE_CHECK) verifyTreesAgainstLegacy(level, x0, z0, x1, z1, topY, trees);
        return trees;
    }

    /**
     * T76 : verification a la demande. Aucun cout sinon.
     *
     * <p>Deux entrees : -Ddlb.treeCheck=1 (ligne de commande du JVM) et
     * DLB_TREE_CHECK=1 (variable d'environnement). La seconde est indispensable :
     * JAVA_TOOL_OPTIONS n'est PAS transmis au JVM du jeu par runServer -- la
     * premiere version de cette verification n'a jamais tourne pour cette raison.
     */
    private static final boolean TREE_CHECK = Boolean.getBoolean("dlb.treeCheck")
            || "1".equals(System.getenv("DLB_TREE_CHECK"));

    /**
     * T76 : compare les troncs trouves par le balayage borne a ceux de l'ancien
     * flood-fill, sur la MEME zone et dans le MEME run -- la seule facon de
     * comparer deux comptages alors que l'emprise (rotation aleatoire du
     * schematic) change d'un run a l'autre.
     */
    private static void verifyTreesAgainstLegacy(ServerLevel level, int x0, int z0, int x1, int z1,
                                                 int topY, List<SavedTree> nouveau) {
        long t0 = System.currentTimeMillis();
        List<SavedTree> ancien = scanTreesLegacy(level, x0, z0, x1, z1, topY);
        java.util.Set<String> a = new java.util.HashSet<>(), b = new java.util.HashSet<>();
        for (SavedTree t : nouveau) a.add(t.baseX() + "," + t.baseZ() + "," + t.type());
        for (SavedTree t : ancien) b.add(t.baseX() + "," + t.baseZ() + "," + t.type());
        java.util.Set<String> oublies = new java.util.HashSet<>(b); oublies.removeAll(a);
        java.util.Set<String> enTrop = new java.util.HashSet<>(a); enTrop.removeAll(b);
        java.util.Set<String> communs = new java.util.HashSet<>(a); communs.retainAll(b);
        deepluckyblock.util.DebugLog.structure(
                "VERIF ARBRES (T76) : nouveau={} ancien={} identiques={} OUBLIES_PAR_LE_NOUVEAU={} EN_TROP={} "
                        + "(ancien algorithme rejoue sur la meme zone en {} ms)",
                a.size(), b.size(), communs.size(), oublies.size(), enTrop.size(),
                System.currentTimeMillis() - t0);
        if (!oublies.isEmpty()) {
            int i = 0;
            for (String s : oublies) {
                deepluckyblock.util.DebugLog.structure("VERIF ARBRES (T76) : tronc manque -> {} ; trace de la colonne : {}",
                        s, traceColumn(level, s));
                if (++i >= 8) break;
            }
        }
    }

    /**
     * T76 : trace d'une colonne du haut vers le bas, avec la borne d'arret du
     * nouveau balayage -- pour comprendre, preuve a l'appui, pourquoi un tronc
     * n'a pas ete vu.
     */
    private static String traceColumn(ServerLevel level, String trunk) {
        try {
            String[] p = trunk.split(",");
            int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[1]);
            int colTop = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
            int colGround = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1 - 2;
            StringBuilder sb = new StringBuilder("colTop=").append(colTop).append(" colGround=").append(colGround).append(" -> ");
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int y = colTop; y > colTop - 26 && y > level.getMinBuildHeight(); y--) {
                BlockState bs = level.getBlockState(m.set(x, y, z));
                sb.append(y).append(':').append(bs.getBlock().getName().getString());
                if (isLog(bs)) sb.append("(BUCHES)");
                else if (isLeaf(bs)) sb.append("(feuilles)");
                else if (y <= colGround && isGroundStop(bs)) sb.append("(ARRET)");
                if (y > colTop - 25) sb.append(" | ");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "trace indisponible : " + t;
        }
    }

    /**
     * T76 : ANCIENNE implementation, conservee telle quelle pour la verification.
     * Flood-fill a 100 voisins par noeud, mesure in-game 9,43 s de gel sur un
     * seul tick pour 148 arbres : c'est elle qui est remplacee par le balayage
     * borne + arret au sol de {@link #scanTrees}. Ne jamais l'appeler sans
     * TREE_CHECK : c'est du diagnostic, pas du jeu.
     */
    private static List<SavedTree> scanTreesLegacy(ServerLevel level, int x0, int z0, int x1, int z1, int topY) {
        List<SavedTree> trees = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int colTop = Math.min(topY, deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING, x, z) + 1);
                int colBottom = Math.max(1, colTop - 48);
                for (int y = colTop; y >= colBottom; y--) {
                    long key = BlockPos.asLong(x, y, z);
                    if (visited.contains(key)) continue;
                    BlockState s = level.getBlockState(mut.set(x, y, z));
                    if (!isLog(s)) continue;
                    int trunkBaseY = y;
                    while (trunkBaseY > 1 && isLog(level.getBlockState(mut.set(x, trunkBaseY - 1, z)))) {
                        trunkBaseY--;
                    }
                    List<SavedBlock> treeBlocks = new ArrayList<>();
                    Set<Long> treeSet = new HashSet<>();
                    java.util.Queue<int[]> queue = new java.util.ArrayDeque<>();
                    queue.add(new int[]{x, trunkBaseY, z});
                    while (!queue.isEmpty() && treeBlocks.size() < 500) {
                        int[] p = queue.poll();
                        long pk = BlockPos.asLong(p[0], p[1], p[2]);
                        if (treeSet.contains(pk)) continue;
                        treeSet.add(pk);
                        visited.add(pk);
                        BlockState bs = level.getBlockState(mut.set(p[0], p[1], p[2]));
                        if (!isLog(bs) && !isLeaf(bs)) continue;
                        treeBlocks.add(new SavedBlock(p[0] - x, p[1] - trunkBaseY, p[2] - z, bs));
                        for (int dx = -2; dx <= 2; dx++)
                            for (int dy = -1; dy <= 2; dy++)
                                for (int dz = -2; dz <= 2; dz++) {
                                    if (dx == 0 && dy == 0 && dz == 0) continue;
                                    queue.add(new int[]{p[0] + dx, p[1] + dy, p[2] + dz});
                                }
                    }
                    String type = treeTypeOf(treeBlocks.isEmpty() ? Blocks.OAK_LOG.defaultBlockState()
                            : treeBlocks.get(0).state());
                    trees.add(new SavedTree(x, z, trunkBaseY, type, treeBlocks));
                }
            }
        }
        return trees;
    }

    /**
     * T76 : collecte le TRONC de l'arbre, rien d'autre.
     *
     * <p>POURQUOI SEULEMENT LE TRONC -- ce n'est pas une optimisation a
     * l'aveugle, c'est la consequence directe de la facon dont les arbres sauves
     * sont reutilises : {@code replantTrees} relit uniquement
     * {@code baseX/baseZ/type} et fait pousser une feature d'arbre vanilla sous
     * le nouveau sol. La liste de blocs n'a donc qu'un seul usage :
     * {@code treeTypeOf(treeBlocks.get(0).state())}, et son premier element
     * etait toujours le bloc de tronc de base (la file de l'ancien flood-fill
     * partait de {x, trunkBaseY, z}).
     *
     * <p>Carre 3x3 horizontal x hauteur du tronc : couvre les troncs 2x2 du
     * chene noir et du jungle (une colonne voisine non marquee serait detectee
     * comme un SECOND arbre et replantee a un bloc du premier). Les feuilles, les
     * branches et surtout les troncs des arbres VOISINS ne sont plus ni lus ni
     * marques : c'est ce marquage qui, avec un rayon de 10, faisait disparaitre
     * de la liste des arbres a replanter tout un voisinage.
     */
    private static List<SavedBlock> collectTree(ServerLevel level, int x, int baseY, int topLogY, int z,
                                                Set<Long> visited, BlockPos.MutableBlockPos mut) {
        List<SavedBlock> blocks = new ArrayList<>();
        int yTop = Math.max(topLogY, baseY);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int wy = baseY; wy <= yTop && wy < level.getMaxBuildHeight(); wy++) {
                    BlockState bs = level.getBlockState(mut.set(x + dx, wy, z + dz));
                    if (!isLog(bs)) continue;
                    visited.add(BlockPos.asLong(x + dx, wy, z + dz));
                    blocks.add(new SavedBlock(dx, wy - baseY, dz, bs));
                }
            }
        }
        return blocks;
    }

    // =========================================================================
    // PHASE 2 : CLEAR (batche)
    // =========================================================================

    private static void clearArea(ServerLevel level, int x0, int z0, int x1, int z1, int topY, Runnable onDone) {
        List<int[]> cols = new ArrayList<>();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                cols.add(new int[]{x, z});

        int total = (cols.size() + CLEAR_BATCH_COLS - 1) / CLEAR_BATCH_COLS;
        if (total == 0) { if (onDone != null) onDone.run(); return; }

        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int b = 0; b < total; b++) {
            final int bi = b, bt = total;
            TestProcedure.schedule(level, TestProcedure.currentTick(level) + b + 1, () -> {
                int start = bi * CLEAR_BATCH_COLS, end = Math.min(start + CLEAR_BATCH_COLS, cols.size());
                deepluckyblock.util.DebugLog.setPhase("clearArea " + (bi + 1) + "/" + bt);
                for (int i = start; i < end; i++) {
                    int cx = cols.get(i)[0], cz = cols.get(i)[1];
                    for (int y = 1; y <= topY; y++) {
                        BlockState s = level.getBlockState(mut.set(cx, y, cz));
                        if (!s.isAir() && !s.is(Blocks.BEDROCK))
                            level.setBlock(mut, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                if (bi == bt - 1 && onDone != null) onDone.run();
            });
        }
    }

    // =========================================================================
    // PHASE 3a/3c : SMOOTH PASS (Gaussian sur heightmap, taille et passes parametrables)
    // =========================================================================

    /**
     * Smooth the surrounding heightmap while preserving the structure footprint.
     * Use rounded averages instead of repeatedly truncating heights downwards.
     */
    private static void smoothPass(ServerLevel level, BlockPos structMin, BlockPos structMax,
                                    int kernelSize, int passes, Runnable onDone) {
        int half = kernelSize / 2;
        int smoothRing = terrainRing();
        int x0 = structMin.getX() - smoothRing, x1 = structMax.getX() + smoothRing;
        int z0 = structMin.getZ() - smoothRing, z1 = structMax.getZ() + smoothRing;
        int w = x1 - x0 + 1, h = z1 - z0 + 1;

        // PRE-CHARGEMENT des chunks de la zone (force-gen) - already handled by prepZone
        smoothPassAfterPreload(level, structMin, structMax, kernelSize, passes,
                half, smoothRing, x0, z0, w, h, onDone);
    }

    /** Nombre de chunks generes par tick pendant le pre-chargement etale. */
    // 4 et non 8 : generer un chunk VIERGE coute ~45 ms (bruit, carving,
    // features). A 8/tick on depassait 350 ms par tick, soit 7x la duree d'un
    // tick normal (50 ms) -- le serveur accumulait du retard pendant tout le
    // preload. A 4/tick on reste sous ~180 ms au pire, et la plupart des chunks
    // sont deja en cache donc quasi gratuits.
    private static final int PRELOAD_CHUNKS_PER_TICK = 4;

    // ==================================================================
    // T46 : PRE-CHARGEMENT BORNE DANS LE TEMPS (mesure in-game du 23/09)
    // ==================================================================
    // Sur le Crimson Lake (zone 406x444 = 783 chunks, monde neuf) le
    // pre-chargement a tourne 24 MINUTES sans finir : 2 chunks livres toutes les
    // 30 s. Le curseur contigu (voir preloadStep) ne comptait que des chunks
    // consecutifs et restait bloque sur un chunk de la couronne exterieure que
    // personne ne demandait, pendant qu'aucune borne de temps n'arretait
    // l'attente. Desormais : comptage de COUVERTURE + budgets stricts.
    /** Marge (blocs) autour de l'emprise : le COEUR, la seule partie qu'on attend. */
    public static final int PRELOAD_CORE_MARGIN = 48;
    /** Attente maximale du coeur (emprise + 48 blocs). */
    private static final long PRELOAD_CORE_BUDGET_MS = 20_000L;
    /** Attente maximale de l'anneau, une fois le coeur pret. */
    private static final long PRELOAD_RING_BUDGET_MS = 8_000L;
    /** Attente maximale absolue (coeur incomplet compris). */
    public static final long PRELOAD_TOTAL_BUDGET_MS = 25_000L;
    /** Repli synchrone (coeur uniquement) apres ce delai, 1 chunk par tick. */
    private static final long PRELOAD_CORE_FORCE_AFTER_MS = 12_000L;
    // T47 : zones GEANTES (> 512 chunks, ex. Crimson Lake 783) -- le repli
    // synchrone est desactive et les budgets raccourcis : sur une zone de cette
    // taille, bloquer le thread serveur coute ~15 s par chunk (mesure in-game du
    // 23/09 : 2 chunks / 30 s et Can't keep up de 26 s).
    private static final long PRELOAD_CORE_BUDGET_BIG_MS = 12_000L;
    private static final long PRELOAD_RING_BUDGET_BIG_MS = 4_000L;
    private static final long PRELOAD_TOTAL_BUDGET_BIG_MS = 15_000L;
    /** Intervalle des rapports d'avancement pendant l'attente. */
    private static final long PRELOAD_REPORT_MS = 5_000L;
    /** Au-dela de ce nombre de ticks sans progression, on force la generation. */
    /**
     * T10 : structures dont la preparation (prepZone) est deja en cours.
     * Empeche un second lancement du meme pipeline pour la meme emprise, donc
     * un paste en double (travail double, cascade de blocs, gel).
     */
    private static final java.util.Set<String> PREP_STARTED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * T15 — PLAFOND DU REPLI FORCE DU PRE-CHARGEMENT (correctif de gel mesure).
     *
     * <p>Quand le chunk system ne livre rien pendant {@link #PRELOAD_MAX_STALL_TICKS},
     * le pre-chargement passe en « repli force » : il demande la generation du
     * chunk de facon SYNCHRONE. Or il le faisait jusqu'a
     * {@code PRELOAD_CHUNKS_PER_TICK} chunks DANS LE MEME TICK, et une generation
     * de chunk complete coute facilement 1 a 10 secondes : mesure du 20/09
     * (observatoire a 400,90,400, monde neuf) -> 40 secondes sans une seule ligne
     * de log, puis « A single server tick took 60.08 seconds » et le Watchdog a
     * tue le serveur. C'est la cause des Watchdogs de 60 s de la session
     * precedente.
     *
     * <p>Le repli reste (il garantit que le pipeline ne reste jamais coince) mais
     * il est desormais BORNE a {@link #PRELOAD_FORCE_PER_TICK} chunks par tick :
     * le tick peut encore depasser les 50 ms, mais il ne peut plus avaler la
     * minute entiere.
     */
    private static final int PRELOAD_FORCE_PER_TICK = 2;
    private static final int PRELOAD_MAX_STALL_TICKS = 600;   // 30 s avant repli force

    /**
     * T52 : autorise (ou non) le repli synchrone du pre-chargement.
     *
     * <p>Desactive par defaut : voir la mesure in-game du 23/09 (un getChunk FULL bloquant =
     * ~15 s de gel sur un monde neuf). Restaurable par -Ddlb.sync=1 pour un diagnostic.
     */
    public static final boolean SYNC_FALLBACK_ALLOWED = Boolean.getBoolean("dlb.sync");
    /**
     * T34 : temps maximal (en ticks, soit 10 s) pendant lequel le pre-chargement
     * attend que TOUS les chunks atteignent le statut complet apres avoir
     * parcouru la liste. Passe ce delai, on continue le pipeline et le paste
     * differera simplement les blocs concernes (sans jamais bloquer le tick).
     */
    private static final int PRELOAD_VERIFY_EXTRA_TICKS = 200;

    // === T18 : REPLI FORCE PILOTE PAR LE BUDGET DE TICK (mesure 20/09) ===
    // Le repli force du pre-chargement genere les chunks SYNCHRONEMENT. Le
    // compteur seul (T15 : 2/tick) ne suffit pas : la sonde [DLB-LAGPROBE] a
    // mesure, sur ce serveur de test (2 vCPU) et sur un monde NEUF, des ticks
    // de 3 791 ms pour 2 generations -- soit ~1,9 s par chunk, alors que le
    // chien de garde du jeu tue le serveur a 60 s (et que le serveur est deja
    // a la peine a 1 s). Le meme compteur, sur une machine de bureau, coute
    // ~100-200 ms : c'est le TEMPS qui doit piloter le repli, pas le nombre.
    // Politique : on n'engage une generation forcee que si le cumul du tick
    // reste sous ce budget ; sinon le repli s'arrete pour ce tick, la
    // generation en tache de fond continue, et on reprend au tick suivant.
    private static final long PRELOAD_FORCE_BUDGET_MS = 900L;

    /**
     * Pre-chargement de la zone -- version T9, NON BLOQUANTE.
     *
     * AVANT : la boucle appelait SafeSurface.primeChunkAbs() sur chaque chunk,
     * donc getChunk(..., requireChunk = true) : le serveur GENERAIT sur place et
     * le tick partait en vrille des que la structure tombait loin du joueur, en
     * terrain jamais visite (mesure en jeu : un tick de 19 200 ms -- 19,2 s de
     * gel -- et un Watchdog a 60 s sur la mise en place d'un everest).
     *
     * MAINTENANT : on avance chunk par chunk en ne touchant QUE les chunks deja
     * charges ; les autres sont demandes en tache de fond (ChunkKeeper pose des
     * tickets + getChunkFuture, jamais bloquant) et on revient les chercher au
     * tick suivant. Le monde se genere donc EN PARALLELE, a la vitesse du moteur,
     * sans jamais figer le tick. Le repli bloquant n'existe plus que si le moteur
     * n'a rien livre au bout de {@link #PRELOAD_MAX_STALL_TICKS} ticks (20 s) :
     * la on force, pour ne jamais rester coince.
     *
     * Le passage par SafeSurface est conserve (FIX T7, cause racine des structures
     * refusees) : c'est lui qui garantit qu'un chunk livre par le moteur a une
     * heightmap utilisable -- un chunk charge par le moteur peut arriver avec une
     * heightmap vide, et toute la zone serait alors lue a -64.
     */
    /**
     * T11 : PRE-CHARGEMENT D'UNE BOITE, SANS AUCUNE MODIFICATION DE TERRAIN.
     *
     * <p>Utilise en amont du pipeline (recherche de la zone d'accueil d'une
     * structure) : le scan de placement mesure des candidats puis VERIFIE le
     * meilleur en forcant la generation du chunk correspondant. Sur une zone
     * jamais generee, cette verification generait un chunk complet dans le
     * tick courant -- mesure en jeu (everest, monde neuf, 20/09) :
     * « Can't keep up! Running 2387ms or 47 ticks behind » entre les lignes
     * « Taille : 174x123x278 » et « -> pos=... », soit 2,4 s de gel pour une
     * seule structure.
     *
     * <p>Ici la boite est chargee par paquets, sans generation synchrone, et
     * reste tenue en memoire (ChunkKeeper) pendant tout le pipeline : le scan,
     * le paste et le post-traitement ne declenchent donc plus aucune
     * generation de chunk.
     */
    /** Pin every edited terrain column; the untouched liquid halo is explored on demand. */
    public static void preloadEditedTerrain(ServerLevel level, BlockPos min, BlockPos max, Runnable onReady) {
        SMOOTH_RING = computeSmoothRing(min, max);
        int margin = terrainRing() + NATURALIZE_EXTRA_RING;
        BlockPos loadMin = min.offset(-margin, 0, -margin);
        BlockPos loadMax = max.offset(margin, 0, margin);
        // preloadBox adds eight blocks itself; the requested halo already includes them.
        preloadBox(level, loadMin.offset(8, 0, 8), loadMax.offset(-8, 0, -8), () -> {
            if (deepluckyblock.util.ChunkKeeper.zoneLoaded(level, loadMin, loadMax)) {
                if (onReady != null) onReady.run();
            } else {
                // A time budget is not permission to skip the unloaded border.
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1,
                        () -> preloadEditedTerrain(level, min, max, onReady));
            }
        });
    }

    public static void preloadBox(ServerLevel level, BlockPos min, BlockPos max, Runnable onReady) {
        preloadBox(level, min, max, PRELOAD_TOTAL_BUDGET_MS, onReady);
    }

    /**
     * T46 : variante avec budget de temps. Utilisee juste avant un paste (T43) :
     * la zone est deja en memoire, on ne veut donc pas y passer 25 s.
     */
    public static void preloadBox(ServerLevel level, BlockPos min, BlockPos max, long budgetMs, Runnable onReady) {
        // Marge VOLONTAIREMENT modeste (8 blocs) : cette boite est celle de la
        // RECHERCHE, elle est deja large (rayon de recherche + demi-taille du
        // build) et chaque chunk supplementaire est epingle en memoire pendant
        // tout le pipeline. Le ring de terrain, lui, sera ajoute par le
        // prepZone() normal quand la position finale sera connue.
        int px0 = min.getX() - 8, pz0 = min.getZ() - 8;
        int px1 = max.getX() + 8, pz1 = max.getZ() + 8;
        deepluckyblock.util.ChunkKeeper.track(level,
                new BlockPos(px0, Math.max(min.getY() - 64, level.getMinBuildHeight()), pz0),
                new BlockPos(px1, Math.min(max.getY() + 64, level.getMaxBuildHeight() - 1), pz1));
        deepluckyblock.util.DebugLog.structure(
                "preloadBox : pre-chargement non bloquant de {}x{} blocs ({} chunks) avant le scan de placement",
                px1 - px0 + 1, pz1 - pz0 + 1, ((px1 >> 4) - (px0 >> 4) + 1) * ((pz1 >> 4) - (pz0 >> 4) + 1));
        // T46 : ici la boite demandee EST le coeur (l'appelant veut cette zone).
        preloadChunksBatched(level, px0, pz0, px1, pz1, px1 - px0 + 1, pz1 - pz0 + 1,
                px0, pz0, px1, pz1, budgetMs, () -> {
            deepluckyblock.util.ChunkKeeper.keep(level);
            deepluckyblock.util.DebugLog.structure("preloadBox : zone prete, suite du pipeline (aucune generation synchrone)");
            if (onReady != null) onReady.run();
        });
    }

    /**
     * T46 : ETAT DU PRE-CHARGEMENT. Plus de curseur contigu (il se bloquait sur
     * un seul chunk) : on mesure la COUVERTURE reelle, avec une distinction
     * COEUR / ANNEAU et des budgets de temps.
     */
    private static final class PreloadState {
        final ServerLevel level;
        final List<long[]> chunks = new ArrayList<>();   // {cx, cz, core(0/1)}
        final Runnable onReady;
        final long budgetMs;
        final long t0 = System.currentTimeMillis();
        int total, coreTotal;
        long lastReport;
        int forced;
        // T47 : budgets calibres sur la TAILLE de la zone + repli synchrone autorise ou non.
        long coreBudgetMs = PRELOAD_CORE_BUDGET_MS;
        long ringBudgetMs = PRELOAD_RING_BUDGET_MS;
        boolean forceAllowed = true;
        PreloadState(ServerLevel level, long budgetMs, Runnable onReady) {
            this.level = level; this.budgetMs = budgetMs; this.onReady = onReady;
        }
        /** T47 : applique un budget de temps recalibre (zone geante). */
        PreloadState withBudget(long ms) {
            PreloadState c = new PreloadState(this.level, ms, this.onReady);
            c.chunks.addAll(this.chunks);
            c.total = this.total;
            c.coreTotal = this.coreTotal;
            c.coreBudgetMs = this.coreBudgetMs;
            c.ringBudgetMs = this.ringBudgetMs;
            c.forceAllowed = this.forceAllowed;
            return c;
        }
    }

    private static void preloadChunksBatched(ServerLevel level, int x0, int z0, int x1, int z1,
                                             int w, int h,
                                             int coreX0, int coreZ0, int coreX1, int coreZ1,
                                             long budgetMs, Runnable onReady) {
        PreloadState st = new PreloadState(level, budgetMs, onReady);
        for (int ccx = x0 >> 4; ccx <= x1 >> 4; ccx++)
            for (int ccz = z0 >> 4; ccz <= z1 >> 4; ccz++) {
                int bx = ccx << 4, bz = ccz << 4;
                boolean core = bx >= (coreX0 >> 4 << 4) - 16 && bx <= coreX1 + 16
                        && bz >= (coreZ0 >> 4 << 4) - 16 && bz <= coreZ1 + 16;
                st.chunks.add(new long[]{ccx, ccz, core ? 1L : 0L});
                if (core) st.coreTotal++;
            }
        st.total = st.chunks.size();
        if (st.total == 0) { if (onReady != null) onReady.run(); return; }
        // T47 : zone GEOANTE (> 512 chunks, ex. Crimson Lake 783) = on ne bloque
        // plus du tout le thread serveur : budgets courts et repli synchrone
        // INTERDIT. Le repli synchrone (getChunk FULL bloquant) etait le tueur du
        // log du 23/09 : ~15 s par chunk, 2 chunks toutes les 30 s, avec des
        // « Can't keep up! 26381 ms ». Les demandes asynchrones (getChunkFuture)
        // generent e n parallele et rendent ce blocage inutile.
        boolean big = st.total > 512;
        // T52 : plus AUCUN repli synchrone, meme pour une zone normale. Un seul
        // appel bloquant coute jusqu'a ~15 s de gel sur un monde neuf (mesure in-game) :
        // il est absurde de le payer pour recuperer quelques colonnes alors que les
        // passes savent deja sauter les colonnes non chargees.
        st.forceAllowed = SYNC_FALLBACK_ALLOWED && !big;
        st.coreBudgetMs = big ? PRELOAD_CORE_BUDGET_BIG_MS : PRELOAD_CORE_BUDGET_MS;
        st.ringBudgetMs = big ? PRELOAD_RING_BUDGET_BIG_MS : PRELOAD_RING_BUDGET_MS;
        long effective = big ? Math.min(budgetMs, PRELOAD_TOTAL_BUDGET_BIG_MS) : budgetMs;
        st = st.withBudget(effective);
        deepluckyblock.util.DebugLog.structure(
                "pre-chargement : {} chunks (zone {}x{}, coeur {}) -- demandes en tache de fond, budget {} s{}"
                        + (SYNC_FALLBACK_ALLOWED ? " (repli synchrone ACTIF : -Ddlb.sync=1)" : " (T52 : aucun secours synchrone)") + "{}",
                st.total, w, h, st.coreTotal, effective / 1000,
                big ? " (zone geante : coeur " + st.coreBudgetMs / 1000 + " s + anneau "
                        + st.ringBudgetMs / 1000 + " s)" : "", "");
        preloadStep(st);
    }

    /**
     * T46 : un pas de pre-chargement, pilote par la COUVERTURE et borne par le
     * TEMPS.
     *
     * <p>Avant, la progression etait un curseur CONTIGU : il n'avançait que sur
     * des chunks consecutifs charges, donc un seul chunk capricieux (typiquement
     * dans la couronne exterieure, hors de la zone que le ChunkKeeper demandait)
     * gelait le compteur pendant des minutes alors que des centaines de chunks
     * etaient deja en memoire. On compte desormais simplement les chunks charges.
     */
    private static void preloadStep(PreloadState st) {
        deepluckyblock.util.ChunkKeeper.keep(st.level);
        deepluckyblock.util.TerrainChain.heartbeat();
        long now = System.currentTimeMillis();
        long elapsed = now - st.t0;

        int ready = 0, coreReady = 0;
        long[] firstCoreMissing = null;
        for (long[] c : st.chunks) {
            if (st.level.getChunkSource().getChunkNow((int) c[0], (int) c[1]) != null) {
                ready++;
                if (c[2] == 1L) coreReady++;
            } else if (c[2] == 1L && firstCoreMissing == null) {
                firstCoreMissing = c;
            }
        }
        deepluckyblock.util.DebugLog.setPhase("pre-chargement des chunks " + ready + "/" + st.total);

        if (ready >= st.total && deepluckyblock.util.ChunkKeeper.zoneComplete(st.level)) {
            preloadDone(st, ready, coreReady, elapsed, true);
            return;
        }

        boolean coreDone = coreReady >= st.coreTotal;
        boolean totalDeadline = elapsed >= st.coreBudgetMs + st.ringBudgetMs
                || elapsed >= st.budgetMs;
        boolean ringDeadline = coreDone && elapsed >= st.coreBudgetMs;

        // A soft deadline is diagnostic only. Starting with missing chunks causes
        // serial getBlockState loads and repeated recovery passes, not a faster build.
        if ((totalDeadline || ringDeadline) && now - st.lastReport >= PRELOAD_REPORT_MS) {
            deepluckyblock.util.DebugLog.structure(
                    "preload target exceeded: waiting for full pinned coverage ({}/{} chunks)", ready, st.total);
        }

        // Repli SYNCHRONE : reserve au COEUR, 1 chunk par tick au maximum.
        // C'est la seule zone ou attendre a de la valeur (le paste, le carve et
        // les decors y travaillent). 20 s de generation synchrone en un tick
        // etaient la cause des Watchdogs : ici le cout est borne a un chunk.
        if (st.forceAllowed && !coreDone && firstCoreMissing != null
                && elapsed >= PRELOAD_CORE_FORCE_AFTER_MS) {
            st.forced++;
            deepluckyblock.util.SafeSurface.request(st.level, (int) firstCoreMissing[0], (int) firstCoreMissing[1]);
        }

        if (now - st.lastReport >= PRELOAD_REPORT_MS) {
            st.lastReport = now;
            deepluckyblock.util.DebugLog.structure(
                    "pre-chargement : {}/{} chunks en {} s (coeur {}/{}) -- demandes en tache de fond, tick libre",
                    ready, st.total, elapsed / 1000, coreReady, st.coreTotal);
        }
        TestProcedure.schedule(st.level, TestProcedure.currentTick(st.level) + 1, () -> preloadStep(st));
    }

    private static void preloadDone(PreloadState st, int ready, int coreReady, long elapsed, boolean complete) {
        deepluckyblock.util.DebugLog.structure(
                "pre-chargement TERMINE en {} s : coeur {}/{}, zone {}/{}{}",
                elapsed / 1000.0, coreReady, st.coreTotal, ready, st.total,
                complete ? " (zone complete)"
                         : " -- " + (st.total - ready) + " chunk(s) non charges (colonnes sautees, "
                           + st.forced + " chunk(s) de coeur demande(s) en tache de fond)");
        if (st.onReady != null) st.onReady.run();
    }

    /**
     * T46 : PRE-CHAUFFAGE d'une future structure. A appeler des qu'on connait la
     * position et qu'il reste du temps (typiquement les 60 s d'annonce du
     * Crimson Lake) : la zone est demandee tout de suite, donc les chunks VIERGES
     * se generent en parallele pendant que le joueur attend -- a l'heure du
     * build, prepZone n'attend plus rien.
     */
    // ==================================================================
    // T51 : PRE-CHAUFFAGE BORNE EN MEMOIRE
    // ==================================================================
    /**
     * Nombre maximal de chunks pre-chauffes. Reglable par -Ddlb.preheat=N.
     *
     * <p>POURQUOI UNE BORNE : chaque chunk genere occupe de la memoire tant que le
     * chunk system ne l'a pas decharge (~0,5 a 1 Mo). Demander les 754 chunks du
     * lac d'un seul coup a fait exploser la memoire du serveur de test (OOM avant
     * meme la fin du compte a rebours). 256 chunks suffisent a couvrir le coeur
     * d'une grosse structure pendant les 60 s d'annonce ; le reste est charge par
     * le pre-chargement borne du pipeline (12 s de coeur + 4 s d'anneau, T46/T47),
     * qui genere en parallele lui aussi.
     */
    public static final int PREHEAT_MAX_CHUNKS = Integer.getInteger("dlb.preheat", defaultPreheatCap());

    /**
     * T51 : plafond par DEFAUT, adapte a la memoire reellement disponible.
     *
     * <p>Un chunk genere occupe ~0,5 a 1 Mo tant que le chunk system ne l'a pas
     * decharge : demander 384 chunks avec un tas de 2 Go met le serveur en OOM.
     * On borne donc le pre-chauffage sur le tas de la JVM :
     * maximale du tas &gt;= 4 Go -&gt; 384 chunks, &gt;= 3 Go -&gt; 256, &gt;= 2 Go -&gt; 160,
     * sinon 64. Dans tous les cas la suite est prise en charge par le
     * pre-chargement borne du pipeline, donc rien ne casse : seul le confort de
     * la premiere seconde change.
     */
    private static int defaultPreheatCap() {
        long mb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        if (mb >= 4096) return 384;
        if (mb >= 3072) return 256;
        if (mb >= 2048) return 160;
        return 64;
    }
    /** Chunks demandes par vague (non bloquant). */
    private static final int PREHEAT_WAVE = 16;
    /** Une vague toutes les N ticks. */
    private static final int PREHEAT_EVERY_TICKS = 5;
    /** Etat du pre-chauffage, par monde. */
    private static final java.util.Map<ServerLevel, java.util.List<long[]>> PREHEAT =
            new java.util.IdentityHashMap<>();
    private static final java.util.Map<ServerLevel, Integer> PREHEAT_CURSOR =
            new java.util.IdentityHashMap<>();

    /**
     * T46/T51 : pre-chauffe la zone pendant les 60 s d'annonce.
     *
     * <p>Les chunks sont demandes en TACHE DE FOND (getChunkFuture), du plus
     * proche du centre au plus loin, par vagues de 16 toutes les 5 ticks, et
     * plafonnes a {@link #PREHEAT_MAX_CHUNKS}. Aucun maintien en memoire, aucun
     * blocage : si le joueur bouge ou si la zone est enorme, rien ne s'effondre.
     */
    public static void warmZone(ServerLevel level, BlockPos min, BlockPos max, int ring) {
        BlockPos a = new BlockPos(min.getX() - ring, level.getMinBuildHeight(), min.getZ() - ring);
        BlockPos b = new BlockPos(max.getX() + ring, level.getMaxBuildHeight() - 1, max.getZ() + ring);
        int cx0 = a.getX() >> 4, cx1 = b.getX() >> 4, cz0 = a.getZ() >> 4, cz1 = b.getZ() >> 4;
        double mx = (min.getX() + max.getX()) / 2.0, mz = (min.getZ() + max.getZ()) / 2.0;
        int total = (cx1 - cx0 + 1) * (cz1 - cz0 + 1);

        java.util.List<long[]> list = new java.util.ArrayList<>(Math.min(total, PREHEAT_MAX_CHUNKS));
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (deepluckyblock.util.SafeSurface.loaded(level, cx, cz)) continue;   // deja la
                long dx = (long) ((cx << 4) - mx), dz = (long) ((cz << 4) - mz);
                list.add(new long[]{cx, cz, dx * dx + dz * dz});
            }
        }
        list.sort((p, q) -> Long.compare(p[2], q[2]));
        int asked = Math.min(list.size(), PREHEAT_MAX_CHUNKS);
        java.util.List<long[]> sub = new java.util.ArrayList<>(list.subList(0, asked));
        PREHEAT.put(level, sub);
        PREHEAT_CURSOR.put(level, 0);
        deepluckyblock.util.DebugLog.structure(
                "pre-chauffage : {} chunk(s) demandes des maintenant sur {} (zone {}x{}, plafond memoire {}) -- "
                        + "ils se generent pendant l'annonce, en tache de fond et sans maintien en memoire ; "
                        + "le reste sera charge par le pre-chargement borne du pipeline",
                asked, total, b.getX() - a.getX() + 1, b.getZ() - a.getZ() + 1, PREHEAT_MAX_CHUNKS);
        for (int i = 0; i < 180; i++) {
            final int k = i;
            deepluckyblock.procedures.TestProcedure.schedule(level,
                    deepluckyblock.procedures.TestProcedure.currentTick(level) + k * PREHEAT_EVERY_TICKS,
                    () -> preheatStep(level));
        }
    }

    /** T51 : une vague de demandes (non bloquant). */
    private static void preheatStep(ServerLevel level) {
        java.util.List<long[]> list = PREHEAT.get(level);
        if (list == null) return;
        Integer cur = PREHEAT_CURSOR.get(level);
        int i = cur == null ? 0 : cur;
        int sent = 0;
        while (i < list.size() && sent < PREHEAT_WAVE) {
            long[] c = list.get(i++);
            if (deepluckyblock.util.SafeSurface.loaded(level, (int) c[0], (int) c[1])) continue;
            deepluckyblock.util.SafeSurface.request(level, (int) c[0], (int) c[1]);
            sent++;
        }
        PREHEAT_CURSOR.put(level, i);
        if (i >= list.size()) {
            PREHEAT.remove(level);
            PREHEAT_CURSOR.remove(level);
            deepluckyblock.util.DebugLog.structure("pre-chauffage : toutes les demandes sont parties (generation en cours en tache de fond)");
        }
    }


    // =====================================================================
    // EXECUTEUR DE PASSES LONGUES (une tranche par tick)
    // =====================================================================
    //
    // FIX (rapporte en jeu : « tout freeze, aucune nouvelle dans les logs, et ca
    // dure a l'infini » -- l'utilisateur a attendu 43 minutes avant d'arreter).
    //
    // PREUVE dans les logs fournis :
    //   16/09 05:40:11  prepZone demarre ... PUIS PLUS RIEN jusqu'a l'arret
    //                   manuel a 06:23:57 (43 min 46 s de log vide).
    //   16/09 06:35:13  idem jusqu'a l'arret a 06:57:35 (22 min 22 s).
    //   18/09 13:57:38  idem : « citadel : 113930 blocs » (STRUCT5, l.1167) puis
    //                   plus aucune ligne jusqu'a l'arret a 14:01:23.
    //
    // CAUSE : guardWaterEdges / clearWaterPockets / verifyGrassSurface /
    // cleanupZone / fixWaterNearStructure etaient des boucles SYNCHRONES sur
    // toute la zone (getHeight + getBlockState + setBlock : plusieurs millions
    // d'acces, plusieurs secondes a dizaines de secondes), executees DANS LE
    // MEME tick et SANS le moindre log avant la fin. Un seul tick avalait donc
    // tout le travail, et rien ne permettait de savoir ou ca bloquait.
    //
    // CORRECTIF : chaque passe est decoupee en tranches de quelques centaines de
    // colonnes, UNE tranche par tick (le planificateur TestProcedure reprend la
    // main entre deux tranches, donc le serveur reste reactif), et chaque passe
    // annonce son debut, sa fin et un eventuel retard de file dans le log.
    // Les blocs poses et l'ordre des operations sont STRICTEMENT identiques.

    /** Grains de colonnes : on avance par paquets de cette taille avant de regarder l'horloge. */
    private static final int BATCH_COLS = 192;
    /**
     * Budget de temps par tranche (millisecondes). Une tranche s'arrete des
     * qu'elle l'a depasse (au grain pres) et se replanifie pour le tick suivant.
     * Le tick n'est donc jamais monopolise, mais la passe avance autant que
     * possible : une passe de 40 000 colonnes se termine en quelques secondes
     * (reparties sur le temps de jeu) au lieu de bloquer un seul tick.
     */
    private static final long SLICE_BUDGET_MS = 12;
    /** Colonnes capturees d'un coup lors d'une lecture de heightmap (jamais tout d'un bloc). */
    private static final int BAND_COLUMNS = 4096;

    /**
     * BUDGET DE TRANCHE ADAPTATIF (T9).
     *
     * Une tranche de 8 ms par tick est confortable sur une machine au repos,
     * mais si le serveur est DEJA en retard (MSPT eleve : worldgen, explosion,
     * sauvegarde...), continuer a consommer 8 ms par tick aggrave le retard et
     * cree une "chaine de lags" -- un pic provoque un retard, le retard
     * provoque le pic suivant. On reduit donc le budget quand le serveur est
     * deja en difficulte, et on l'augmente quand il respire (le travail finit
     * plus vite sur une machine puissante).
     */
    private static long sliceBudgetMs(ServerLevel level) {
        double mspt = 50.0;
        try {
            // 1.21.1 : MinecraftServer#getCurrentSmoothedTickTime() renvoie le
            // MSPT lisse en millisecondes (l'ancien getAverageTickTime() a
            // disparu de l'API, tout comme getAverageTickTimeNanos qui est en ns).
            mspt = level.getServer().getCurrentSmoothedTickTime();
        } catch (Throwable ignored) { }
        double factor;
        if (mspt >= 65)      factor = 0.25;   // serveur en detresse : on leve le pied
        else if (mspt >= 55) factor = 0.5;
        else if (mspt >= 45) factor = 0.75;
        else if (mspt <= 25) factor = 1.75;   // serveur tranquille : on accelere
        else                 factor = 1.0;
        long ms = Math.round(SLICE_BUDGET_MS * factor);
        return Math.max(2L, Math.min(24L, ms));
    }

    /** Etiquette unique d'une chaine terrain (nom de structure + zone). */
    static String chainTag(BlockPos min, BlockPos max) {
        return (LAST_STRUCTURE_NAME == null ? "structure?" : LAST_STRUCTURE_NAME)
                + "@" + min.getX() + "," + min.getZ();
    }

    /** Nombre de ticks pendant lesquels on accepte d'attendre la fin d'une autre chaine. */
    private static final int CHAIN_WAIT_TICKS = 20;
    private static final String CHAIN_LOG = "[DLB-CHAIN]";
    /** Au-dela de ce retard sur la file du planificateur, on previent dans le log. */
    private static final int SCHEDULER_LAG_WARN_TICKS = 200;

    /** Travail unitaire d'une passe : une colonne (x, z), ou (passe, x, z). */
    private interface ColumnAction { void run(int[] col); }

    /** Une passe longue, avancee d'une tranche par tick. */
    private static final class ColumnPass {
        final ServerLevel level;
        final String label;
        final List<int[]> cols;
        final int perSlice;
        final ColumnAction action;
        final Runnable onDone;
        final long t0 = System.currentTimeMillis();
        int cursor = 0;
        int scheduledTick = 0;
        boolean warned = false;

        ColumnPass(ServerLevel level, String label, List<int[]> cols, int perSlice,
                   ColumnAction action, Runnable onDone) {
            this.level = level; this.label = label; this.cols = cols;
            this.perSlice = perSlice; this.action = action; this.onDone = onDone;
        }

        /** Report tant que la zone suivie n'est pas entierement chargee (borne). */
        private int deferred = 0;
        private int skippedCols = 0;
        /** T68 : colonnes anormalement couteuses (mesure + comptage). */
        private int slowCols = 0;
        private long slowColMs = 0;
        private long worstColMs = 0;
        // === T70 : colonnes REPORTEES (chunk absent) au lieu d'etre perdues ===
        // Mesure en jeu (everest, zone de 667 chunks, pre-chargement borne) :
        // 43 321 colonnes du balayage et 45 737 du hole filler n'ont PAS ete
        // traitees parce que leur chunk n'etait pas encore charge -- elles
        // etaient comptees puis jetees. Desormais elles sont gardees et
        // reprises des que le chunk arrive (l'ancien comportement perdait du
        // travail en silence, le nouveau le rattrape).
        private final java.util.List<int[]> missed = new java.util.ArrayList<>();
        private boolean recovery = false;
        private int missRetries = 0;

        private boolean zoneReady() {
            if (deepluckyblock.util.ChunkKeeper.zoneComplete(level)) return true;
            // 60 s d'attente au lieu de 30 : le chargement est NON bloquant, laisser
            // le chunk system finir coute infiniment moins cher qu'une colonne sautee.
            if (deferred >= 1200) return true;
            if (deferred == 0 || deferred % 100 == 0) {
                deepluckyblock.util.DebugLog.structure(
                        "{} : zone pas encore entierement chargee -- tranche reportee (le chunk system charge, le serveur n'est pas bloque)",
                        label);
            }
            deferred++;
            return false;
        }

        void slice() {
            deepluckyblock.util.ChunkKeeper.keep(level);
            deepluckyblock.util.TerrainChain.heartbeat();
            // T16 : fil rouge de phase, avec l'avancement reel de la passe.
            deepluckyblock.util.DebugLog.setPhase(label + " " + cursor + "/" + cols.size());
            if (!zoneReady()) {
                scheduledTick = TestProcedure.currentTick(level) + 1;
                TestProcedure.schedule(level, scheduledTick, this::slice);
                return;
            }
            int now = TestProcedure.currentTick(level);
            int lag = now - scheduledTick;
            if (lag >= SCHEDULER_LAG_WARN_TICKS && !warned) {
                warned = true;
                LOGGER.warn("[DLB-STRUCTURE] {} : file du planificateur saturee ({} ticks de retard) "
                        + "-- la passe continue par tranches, les autres taches du mod ne sont plus "
                        + "bloquees derriere elle.", label, lag);
            }
            long sliceT0 = System.currentTimeMillis();
            long budget = sliceBudgetMs(level);

            // T70 : mode rattrapage -- on ne traite plus que les colonnes dont le
            // chunk manquait, pour ne rien perdre.
            if (recovery) {
                int recovered = 0;
                for (java.util.Iterator<int[]> it = missed.iterator(); it.hasNext(); ) {
                    int[] col = it.next();
                    if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, col[0], col[1])) continue;
                    try { action.run(col); } catch (Throwable ignored) { }
                    skippedCols--;
                    recovered++;
                    it.remove();
                    if (System.currentTimeMillis() - sliceT0 >= budget) break;
                }
                // === T70b : NE PAS ATTENDRE POUR RIEN ===
                // Si la zone suivie est DEJA declaree complete et qu'aucune colonne n'a
                // pu etre rattrapee sur cette tentative, les chunks manquants ne
                // viendront plus : on termine honnetement au lieu de bruler 30 s de
                // pipeline (risque d'allonger inutilement toutes les passes).
                if (recovered == 0 && missRetries >= 1
                        && deepluckyblock.util.ChunkKeeper.zoneComplete(level)) {
                    LOGGER.warn("[DLB-STRUCTURE] {} : {} colonne(s) inaccessibles alors que la zone est "
                                    + "complete -- passe terminee sans elles (aucune attente inutile)",
                            label, missed.size());
                    finishPass();
                    return;
                }
                if (missed.isEmpty()) {
                    finishPass();
                } else if (++missRetries >= MAX_MISS_RETRIES) {
                    LOGGER.warn("[DLB-STRUCTURE] {} : {} colonne(s) restent non traitees "
                                    + "(chunk jamais charge apres {} tentatives) -- le reste est termine.",
                            label, missed.size(), missRetries);
                    finishPass();
                } else {
                    scheduledTick = TestProcedure.currentTick(level) + MISS_RETRY_TICKS;
                    TestProcedure.schedule(level, scheduledTick, this::slice);
                }
                return;
            }

            while (cursor < cols.size()) {
                int next = Math.min(cursor + perSlice, cols.size());
                int stop = cursor;
                for (int i = cursor; i < next; i++) {
                    int[] col = cols.get(i);
                    stop = i + 1;
                    // Deuxieme filet : si un chunk manque malgre le portillon, on
                    // SAUTE la colonne (comptee et journalisee) au lieu de laisser
                    // getBlockState/setBlock generer un chunk en plein tick.
                    long colT0 = System.currentTimeMillis();
                    if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, col[0], col[1])) { skippedCols++; missed.add(col); }
                    else { try { action.run(col); } catch (Throwable ignored) { } }
                    // T68 : une colonne anormalement couteuse est comptee (et
                    // journalisee en fin de passe). Un balayage decoratif ne doit
                    // jamais peser des centaines de millisecondes dans un tick.
                    long colMs = System.currentTimeMillis() - colT0;
                    if (colMs > SLOW_COLUMN_MS) { slowCols++; slowColMs += colMs; if (colMs > worstColMs) worstColMs = colMs; }
                    // === T68 : LE BUDGET EST VERIFIE A L'INTERIEUR DE LA TRANCHE ===
                    // Avant, il n'etait teste qu'ENTRE deux lots de `perSlice`
                    // colonnes (384 pour le balayage etendu). Or une seule colonne
                    // peut couter tres cher : 58 lectures de blocs (48 au-dessus +
                    // 10 sous la surface) et, si elle rencontre un amas naturel
                    // flottant, une exploration de proche en proche jusqu'a 512
                    // blocs x 6 voisins. Un lot de 384 colonnes defoncait donc le
                    // tick : MESURE en jeu sur everest (zone de 667 chunks) --
                    // « periode de tick de 38 340 ms pendant la phase "balayage
                    // etendu des naturels flottants" », puis Watchdog et arret.
                    // Toutes les passes de colonnes (vegetaux flottants, naturels
                    // flottants, verifyGrassSurface, by-column du hole filler...)
                    // beneficient du correctif.
                    // T68 : verifie a CHAQUE colonne -- un appel System.currentTimeMillis
                    // coute ~25 ns, une colonne des milliers de fois plus : on borne
                    // donc la tranche a `budget` + UNE colonne, jamais plus.
                    if (System.currentTimeMillis() - sliceT0 >= budget) break;
                }
                cursor = stop;
                if (System.currentTimeMillis() - sliceT0 >= budget) break;
            }
            if (cursor < cols.size()) {
                scheduledTick = TestProcedure.currentTick(level) + 1;
                TestProcedure.schedule(level, scheduledTick, this::slice);
            } else if (!missed.isEmpty()) {
                // T70 : toutes les colonnes ont ete parcourues mais certaines
                // n'etaient pas chargees : on entre en rattrapage au lieu de les
                // perdre (borne par MAX_MISS_RETRIES).
                recovery = true;
                deepluckyblock.util.DebugLog.structure(
                        "{} : {} colonne(s) en attente de chunk -- reprise automatique des que la zone est chargee",
                        label, missed.size());
                scheduledTick = TestProcedure.currentTick(level) + MISS_RETRY_TICKS;
                TestProcedure.schedule(level, scheduledTick, this::slice);
            } else {
                finishPass();
            }
        }

        /** T70 : cloture de la passe (une seule fois, quel que soit le chemin). */
        private void finishPass() {
            if (!RUNNING_PASSES.remove(label)) return;
            if (skippedCols > 0) {
                LOGGER.warn("[DLB-STRUCTURE] {} : {} colonnes non traitees (chunk absent) -- "
                                + "elles n'ont pas ete traitees, a relancer si le rendu le justifie",
                        label, skippedCols);
            }
            if (slowCols > 0) {
                LOGGER.warn("[DLB-STRUCTURE] {} : {} colonne(s) lente(s) (> {} ms chacune, "
                                + "pire {} ms, {} ms au total) -- elles ont ete traitees, mais ce sont elles "
                                + "qui allongent les tranches",
                        label, slowCols, SLOW_COLUMN_MS, worstColMs, slowColMs);
            }
            deepluckyblock.util.DebugLog.structure("{} : fini ({} colonnes, {} ms)",
                    label, cols.size(), System.currentTimeMillis() - t0);
            if (onDone != null) onDone.run();
        }
    }

    /**
     * T21 : ecart du terrain LIVRE par rapport au terrain NATUREL de reference,
     * hors emprise de la structure (plateau voulu) et hors plans d'eau.
     * Echantillonnage 1 colonne sur 4 : mesure representative et gratuite.
     */
    private static void logTerrainDelta(ServerLevel level, BlockPos min, BlockPos max) {
        int[][] ref = NATURAL_REF;
        if (ref == null) return;
        int ring = terrainRing();
        long sum = 0; int n = 0, worst = 0, worstX = 0, worstZ = 0, near = 0, over = 0;
        int digs = 0, fills = 0, worstDig = 0, worstFill = 0;
        for (int x = min.getX() - ring; x <= max.getX() + ring; x += 4) {
            for (int z = min.getZ() - ring; z <= max.getZ() + ring; z += 4) {
                int i = x - NR_X0, j = z - NR_Z0;
                if (i < 0 || j < 0 || i >= NR_W || j >= NR_H) continue;
                if (isProtected(x, z)) continue;
                int surf = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockState at = level.getBlockState(new BlockPos(x, surf, z));
                if (at.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) continue;   // plan d'eau
                int delta = surf - ref[i][j];
                int d = Math.abs(delta);
                sum += d; n++;
                if (d <= 2) near++;
                if (d > MAX_SMOOTH_RISE || d < -MAX_SMOOTH_DROP) over++;
                if (d > worst) { worst = d; worstX = x; worstZ = z; }
                // T23 : distinction CREUSE / REMBLAYE -- la reponse directe a
                // « tu creuses encore des trous autour de la structure, WTF ! ».
                if (delta < -1) { digs++; if (-delta > worstDig) worstDig = -delta; }
                else if (delta > 1) { fills++; if (delta > worstFill) worstFill = delta; }
            }
        }
        if (n == 0) return;
        double avg = sum / (double) n;
        deepluckyblock.util.DebugLog.structure(
                "terrain final vs terrain NATUREL : ecart moyen {} blocs, maximal {} (en {},{}), "
                        + "{} % des colonnes a 2 blocs ou moins, {} colonnes au-dela de la borne (+{}/-{}) "
                        + "(echantillon de {} colonnes, emprise et plans d'eau exclus) -- dont {} colonne(s) "
                        + "CREUSEE(S) de plus de 1 bloc (pire : -{}) et {} REMBLAYEE(S) (pire : +{})",
                String.format("%.2f", avg), worst, worstX, worstZ, (100 * near) / n, over, MAX_SMOOTH_RISE, MAX_SMOOTH_DROP, n,
                digs, worstDig, fills, worstFill);
        if (avg > 6.0) {
            LOGGER.warn("[DLB-STRUCTURE] le terrain livre s'ecarte en moyenne de {} blocs du terrain naturel "
                            + "(max {} en {},{}) : verifier le rendu, la zone a ete fortement remodelee",
                    String.format("%.2f", avg), worst, worstX, worstZ);
        }
    }

    /** Colonnes (x, z) d'un rectangle min/max, marge incluse. */
    private static List<int[]> columnsOf(BlockPos min, BlockPos max, int margin) {
        List<int[]> cols = new ArrayList<>();
        for (int x = min.getX() - margin; x <= max.getX() + margin; x++)
            for (int z = min.getZ() - margin; z <= max.getZ() + margin; z++)
                cols.add(new int[]{x, z});
        return cols;
    }

    /**
     * Demarre une passe decoupee en tranches. La premiere tranche s'execute tout
     * de suite (comportement identique a avant des le premier tick), les
     * suivantes sont planifiees une par tick.
     *
     * Garde-fou : une seule passe d'un meme type peut tourner a la fois. Les
     * passes d'eau sont relancees par plusieurs chemins (sealWaterLeaks,
     * decorate, prepZone) ; sans ce garde, chacune empilerait sa propre file.
     * Une passe etant idempotente (elle re-balaye toutes les colonnes), sauter
     * le doublon est sans effet sur le resultat.
     */
    private static void startColumnPass(ServerLevel level, String label, List<int[]> cols,
                                        int perSlice, ColumnAction action, Runnable onDone) {
        if (cols.isEmpty()) { if (onDone != null) onDone.run(); return; }
        if (!RUNNING_PASSES.add(label)) {
            deepluckyblock.util.DebugLog.structure("{} : deja en cours, doublon ignore", label);
            if (onDone != null) onDone.run();
            return;
        }
        deepluckyblock.util.DebugLog.structure("{} : debut sur {} colonnes ({} tranches)",
                label, cols.size(), (cols.size() + perSlice - 1) / perSlice);
        ColumnPass pass = new ColumnPass(level, label, cols, perSlice, action, onDone);
        pass.scheduledTick = TestProcedure.currentTick(level);
        pass.slice();
    }

    /** T70 : intervalle des reprises de colonnes non chargees (10 ticks = 0,5 s). */
    private static final int MISS_RETRY_TICKS = 10;
    /** T70 : nombre maximal de reprises (30 s au total) avant d'abandonner honnetement. */
    private static final int MAX_MISS_RETRIES = 60;

    // ==================================================================
    // T71 : LECTURE PAR SECTIONS (16 blocs d'un coup)
    // ==================================================================
    /** Predicat « cette section contient au moins un bloc d'air ». */
    private static final java.util.function.Predicate<BlockState> HAS_AIR = BlockState::isAir;

    /** T71 : lecture d'un bloc par le chunk deja resolu (une recherche de chunk en moins). */
    private static BlockState readBlock(ServerLevel level, ChunkAccess chunk,
                                        BlockPos.MutableBlockPos mut, int x, int y, int z) {
        mut.set(x, y, z);
        return chunk != null ? chunk.getBlockState(mut) : level.getBlockState(mut);
    }

    /** T71 : section contenant la couche y, ou null si le chunk n'est pas disponible. */
    private static LevelChunkSection sectionAt(ChunkAccess chunk, int y) {
        if (chunk == null) return null;
        int i = chunk.getSectionIndex(y);
        if (i < 0) return null;
        LevelChunkSection[] secs = chunk.getSections();
        return (i < secs.length) ? secs[i] : null;
    }

    /** T71 : premiere couche traitee de la section qui contient y (borne par la limite). */
    private static int sectionLow(int y, int floorLimit) {
        return Math.max(floorLimit + 1, y & ~15);
    }

    /**
     * T71 : predicat « cette section peut contenir quelque chose qui interesse le
     * balayage » -- de l'air, ou un decor naturel susceptible de flotter (un bloc
     * plein ordinaire ne declenche aucun traitement). Sert a sauter 16 blocs d'un
     * coup dans les sections homogenes (sol plein, ciel vide).
     */
    private static final java.util.function.Predicate<BlockState> SWEEP_INTERESTING =
            s -> s.isAir() || isSweepableNatural(s);

    /** T68 : au-dela de ce cout, une colonne est declaree « lente » (journalisee). */
    private static final long SLOW_COLUMN_MS = 50;

    /** Libelle des passes actuellement en cours (voir startColumnPass). */
    private static final Set<String> RUNNING_PASSES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Suite de smoothPass, executee une fois tous les chunks charges. */
    /** Variation maximale autorisee du lissage, en blocs, par colonne (T21). */
    private static final int MAX_SMOOTH_DELTA = 8;

    /** T20 : colonnes qui contenaient de l'eau au moment du gel (exclues du flou). */
    private static boolean[][] waterColumns(ServerLevel level, int x0, int z0, int w, int h) {
        boolean[][] mask = new boolean[w][h];
        int n = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int x = x0 + i, z = z0 + j;
                // The active pipeline no longer freezes water: inspect real surface water too.
                boolean wet = false;
                if (deepluckyblock.util.SafeSurface.isLoadedAt(level, x, z)) {
                    int y = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                    wet = level.getBlockState(pos.set(x, y, z)).getFluidState()
                            .is(net.minecraft.tags.FluidTags.WATER);
                }
                if (wet || deepluckyblock.util.WaterDam.frozenColumn(level, x, z)) {
                    mask[i][j] = true; n++;
                }
            }
        }
        if (n > 0) {
            deepluckyblock.util.DebugLog.structure(
                    "smooth {}x{} : {} colonnes d'eau exclues du lissage (hauteur d'eau conservee, "
                            + "ni flou ni reconstruction) -- l'eau ne deforme plus le rivage",
                    w, h, n);
        }
        return mask;
    }

    // ==================================================================
    // T23 : BALAYAGE DES VEGETAUX FLOTTANTS
    // ==================================================================
    /**
     * Supprime les decors NATURELS qui ne tiennent plus a rien : cacao, vignes,
     * lianes, bambous, feuilles et plantes rendus flottants par le remodelage du
     * terrain (ils etaient poses sur un sol qui a depuis ete retaille).
     *
     * <p>Consigne utilisateur (20/09) : « des cocoa qui volent aussi, toutes
     * sortes de truc de natures qui n'ont pas ete correctement suprimés de leur
     * ancien terrain », « bamboos, vignes qui volent ».
     *
     * <p>Methode, par colonne : on cherche le SOL (premier bloc non-vegetal sous
     * la surface), puis on monte ; tant que la chaine est continue les blocs sont
     * portes (arbre enracine, fleur posee = conserves) ; des qu'un vide separe un
     * bloc du sol, tout ce qui est au-dessus et qui est VEGETAL est supprime.
     * Les blocs construits (planches, pierre, verre...) ne sont jamais touches.
     */
    private static void sweepFloatingDecor(ServerLevel level, BlockPos min, BlockPos max,
                                           int margin, Runnable onDone) {
        int x0 = min.getX() - margin, x1 = max.getX() + margin;
        int z0 = min.getZ() - margin, z1 = max.getZ() + margin;
        startColumnPass(level, "balayage des vegetaux flottants",
                columnsOf(new BlockPos(x0, min.getY(), z0), new BlockPos(x1, max.getY(), z1), 0), 128,
                col -> sweepColumn(level, col[0], col[1]), onDone);
    }

    /**
     * Une colonne du balayage (voir {@link #sweepFloatingDecor}).
     *
     * <p>T25 : supprime aussi les DEBRIS DE TERRAIN flottants (blocs de terre,
     * herbe, sable, gravier, neige...) qui ne tiennent plus a rien : les captures
     * du 20/09 montrent des ilots d'herbe et des blocs de terre en suspension
     * dans le vide, laisses par le remodelage. Les blocs de construction et les
     * blocs durs naturels (pierre, minerais, terracotta) ne sont JAMAIS touches :
     * un surplomb rocheux est un relief legitime.
     */
    private static void sweepColumn(ServerLevel level, int x, int z) {
        if (isProtected(x, z)) return;          // T25 : l'emprise de la structure est intouchable
        int top = Math.min(level.getMaxBuildHeight() - 1,
                deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING, x, z) + 48);
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        boolean grounded = false;
        for (int y = top; y > level.getMinBuildHeight(); y--) {
            BlockState st = level.getBlockState(mut.set(x, y, z));
            if (st.isAir()) { grounded = false; continue; }
            if (!grounded && isSurfaceDecor(st)) {
                level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                SWEPT.incrementAndGet();
                continue;
            }
            if (isSurfaceDecor(st)) continue;   // vegetal pose sur du solide : on garde
            if (!grounded && isLooseSurface(st)) {
                // Debris de terrain en suspension (ilot d'herbe / bloc de terre).
                level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                SWEPT.incrementAndGet();
                continue;
            }
            grounded = true;                    // bloc plein : a partir d'ici tout est porte
        }
    }

    /** T25 : materiaux "meubles" qui ne doivent jamais flotter dans le vide. */
    private static boolean isLooseSurface(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)
            || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)
            || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)
            || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.MUD)
            || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.SNOW);
    }

    /** Compteur global du balayage (rapporte a la fin de la passe). */
    private static final java.util.concurrent.atomic.AtomicInteger SWEPT =
            new java.util.concurrent.atomic.AtomicInteger();

    // ==================================================================
    // T26 : TERRAIN D'ABORD, STRUCTURE A LA FIN
    // ==================================================================
    /**
     * Consigne utilisateur (log client du 20/09) : « et tu est sencé d'abord tout
     * retirer et applanir, et metre la grosse structure a la FIN, une fois que le
     * terrain est parfait », puis « la wtf tu le fais desle debut » et « ce qui
     * cause le terrain collé a la structure de ne pas etre smoothé et ducoup mal
     * rendre ».
     *
     * <p>Le pipeline est donc scinde en trois temps :
     * <ol>
     *   <li>AVANT la pose : retrait des decors/arbres/végétation, comblement du
     *       footprint, aplanissement, lissage borne (limitSlope T24 + clamp
     *       T21) -- le terrain est rendu DEFINITIF ;</li>
     *   <li>la pose de la structure (paste) ;</li>
     *   <li>APRES la pose : uniquement des finitions qui ne remodelent plus le
     *       terrain (carve des blocs qui depassent, decors, naturalisation,
     *       replant d'arbres, porte de stabilisation).</li>
     * </ol>
     *
     * <p>Ce drapeau (actif) fait SAUTER les deux lissages qui tournaient APRES la
     * pose, dans decorate(). C'etait eux qui recreusaient le pourtour de la
     * structure (le lissage relisait la heightmap, y voyait le batiment et
     * rabaissait le sol colle a ses murs : « le terrain collé a la structure
     * n'est pas smoothé et ducoup mal rend »). Le parametre permet le retour en
     * arriere d'une seule ligne, mais l'ancien ordre est volontairement abandonne.
     */
    public static boolean TERRAIN_FIRST = true;

    /** Lissage post-paste : execute uniquement si TERRAIN_FIRST est desactive. */
    private static void postPasteSmooth(ServerLevel level, BlockPos min, BlockPos max,
                                        int kernelSize, int passes, Runnable onDone) {
        if (TERRAIN_FIRST) {
            step("lissage post-paste " + kernelSize + "x" + kernelSize + " x" + passes
                    + " SAUTE (T26 : terrain deja definitif, consigne « applanir d'abord, structure a la fin »)");
            if (onDone != null) onDone.run();
            return;
        }
        smoothPass(level, min, max, kernelSize, passes, onDone);
    }

    /** T24 : pente maximale autorisee entre deux colonnes voisines (blocs). */
    private static final int MAX_SLOPE_STEP = 2;
    /** T24 : iterations de relaxation. */
    private static final int SLOPE_ITERATIONS = 14;

    /**
     * T24 : borne la pente du terrain reconstruit.
     *
     * <p>Pourquoi : le terrain est reconstruit colonne par colonne a partir de la
     * heightmap lissée. Chaque colonne etait coupee a SA hauteur, sans aucun
     * controle de continuite : deux colonnes voisines pouvaient differer de 10 ou
     * 20 blocs, ce qui donne des PAROIS VERTICALES successives -- les terrasses
     * concentriques visibles en jeu autour des structures (captures du 20/09).
     *
     * <p>Methode : relaxation sur les 4 voisins (i-1, i+1, j-1, j+1). Chaque
     * cellule est ramenee a {@code voisin ± MAX_SLOPE_STEP} tant qu'un ecart trop
     * grand subsiste, en plusieurs passes jusqu'a stabilisation. Le plateau de la
     * structure reste a son niveau : la relaxation ne fait qu'adoucir les RACCORDS.
     * Les colonnes d'eau et l'emprise de la structure sont laissees telles quelles.
     */
    private static int[][] limitSlope(int[][] hm, boolean[][] waterMask, int w, int h,
                                      int sMinI, int sMaxI, int sMinJ, int sMaxJ, int x0, int z0) {
        int worstBefore = worstNeighbourStep(hm, w, h, waterMask, x0, z0);
        int touched = 0;
        for (int iter = 0; iter < SLOPE_ITERATIONS; iter++) {
            boolean any = false;
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < h; j++) {
                    if (waterMask != null && waterMask[i][j]) continue;
                    if (i >= sMinI && i <= sMaxI && j >= sMinJ && j <= sMaxJ) continue;  // emprise : plateau voulu
                    int v = hm[i][j];
                    int lo = v, hi = v;
                    if (i > 0)     { lo = Math.min(lo, hm[i - 1][j]); hi = Math.max(hi, hm[i - 1][j]); }
                    if (i < w - 1) { lo = Math.min(lo, hm[i + 1][j]); hi = Math.max(hi, hm[i + 1][j]); }
                    if (j > 0)     { lo = Math.min(lo, hm[i][j - 1]); hi = Math.max(hi, hm[i][j - 1]); }
                    if (j < h - 1) { lo = Math.min(lo, hm[i][j + 1]); hi = Math.max(hi, hm[i][j + 1]); }
                    if (v - lo > MAX_SLOPE_STEP) { hm[i][j] = lo + MAX_SLOPE_STEP; any = true; touched++; }
                    else if (hi - v > MAX_SLOPE_STEP) { hm[i][j] = hi - MAX_SLOPE_STEP; any = true; touched++; }
                }
            }
            if (!any) break;
        }
        int worstAfter = worstNeighbourStep(hm, w, h, waterMask, x0, z0);
        deepluckyblock.util.DebugLog.structure(
                "pentes : plus grand ecart entre deux colonnes voisines ramene de {} bloc(s) a {} "
                        + "(limite {}) -- {} cellule(s) adoucie(s), les marches deviennent des pentes",
                worstBefore, worstAfter, MAX_SLOPE_STEP, touched);
        return hm;
    }

    /**
     * Plus grand ecart vertical entre deux colonnes voisines, hors eau et hors
     * emprise de la structure (T24 : les marches de la couronne sont mesurees,
     * celles du plateau du batiment sont volontaires et exclues du calcul).
     */
    private static int worstNeighbourStep(int[][] hm, int w, int h, boolean[][] waterMask, int x0, int z0) {
        int worst = 0;
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                if (waterMask != null && waterMask[i][j]) continue;
                if (isProtected(x0 + i, z0 + j)) continue;
                if (i + 1 < w && (waterMask == null || !waterMask[i + 1][j]) && !isProtected(x0 + i + 1, z0 + j))
                    worst = Math.max(worst, Math.abs(hm[i][j] - hm[i + 1][j]));
                if (j + 1 < h && (waterMask == null || !waterMask[i][j + 1]) && !isProtected(x0 + i, z0 + j + 1))
                    worst = Math.max(worst, Math.abs(hm[i][j] - hm[i][j + 1]));
            }
        }
        return worst;
    }

    /** T23 : remblai maximal autorise du lissage (vers le haut), en blocs. */
    private static final int MAX_SMOOTH_RISE = 10;
    /** T23 : creusage maximal autorise du lissage (vers le bas), en blocs. */
    private static final int MAX_SMOOTH_DROP = 2;

    /** T21 : borne ABSOLUE de la variation du lissage par rapport au terrain naturel. */
    private static int[][] clampSmoothDelta(ServerLevel level, int[][] hm, int[][] orig,
                                            boolean[][] waterMask, int x0, int z0, int w, int h) {
        // Reference = terrain NATUREL capture une seule fois par zone (avant le
        // premier lissage). Comparer au terrain de l'appel precedent ne suffisait
        // pas : chaque passe avait le droit de deriver de 8 blocs de plus, et les
        // 5 a 8 lissages du pipeline finissaient par recreuser le paysage.
        int[][] ref = naturalReference(orig, x0, z0, w, h);
        int clamped = 0, worst = 0, worstX = 0, worstZ = 0;
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                if (waterMask != null && waterMask[i][j]) continue;         // colonne d'eau
                if (isProtected(x0 + i, z0 + j)) continue;                  // emprise : aplanissement VOULU
                int d = hm[i][j] - ref[i][j];
                int ad = Math.abs(d);
                if (ad > worst) { worst = ad; worstX = i; worstZ = j; }
                // T23 : DISSYMETRIE VOLONTAIRE (consigne « tu creuse encore des trous
                // autour de la structure, WTF ! »). Le lissage peut REMBLAYER jusqu'a
                // MAX_SMOOTH_RISE pour amenager le plateau et ses abords, mais il n'a
                // plus le droit de CREUSER sous le terrain naturel au-dela de
                // MAX_SMOOTH_DROP : les crateres/terrasses concentriques en bord de
                // zone (visibles sur les captures du 20/09) disparaissent.
                if (d > MAX_SMOOTH_RISE) { hm[i][j] = ref[i][j] + MAX_SMOOTH_RISE; clamped++; }
                else if (d < -MAX_SMOOTH_DROP) { hm[i][j] = ref[i][j] - MAX_SMOOTH_DROP; clamped++; }
            }
        }
        if (clamped > 0) {
            LOGGER.warn("[DLB-STRUCTURE] lissage bridé : {} colonne(s) sortaient de la fenetre autorisee "
                            + "(+{} / -{} blocs autour du terrain NATUREL ; derive maximale mesuree : {} blocs, "
                            + "colonne {},{} de la zone) -- ramenees dans la fenetre",
                    clamped, MAX_SMOOTH_RISE, MAX_SMOOTH_DROP, worst, x0 + worstX, z0 + worstZ);
        } else {
            deepluckyblock.util.DebugLog.structure(
                    "lissage : derive maximale {} blocs par rapport au terrain naturel (borne +{}/-{}), aucune colonne bridée",
                    worst, MAX_SMOOTH_RISE, MAX_SMOOTH_DROP);
        }
        return hm;
    }

    // ------------------------------------------------------------------
    // T21 : reference du terrain NATUREL (une par zone de structure)
    // ------------------------------------------------------------------
    private static int[][] NATURAL_REF = null;
    private static int NR_X0, NR_Z0, NR_W, NR_H;

    /**
     * Capture (une fois) la heightmap naturelle de la zone, puis la renvoie pour
     * toutes les passes suivantes de la MEME structure. Le terrain n'est encore
     * que naturel au premier lissage : tout ce que le pipeline fera ensuite doit
     * s'en ecarter le moins possible.
     */
    private static int[][] naturalReference(int[][] orig, int x0, int z0, int w, int h) {
        if (NATURAL_REF == null || NR_X0 != x0 || NR_Z0 != z0 || NR_W != w || NR_H != h) {
            NATURAL_REF = new int[w][h];
            for (int i = 0; i < w; i++) System.arraycopy(orig[i], 0, NATURAL_REF[i], 0, h);
            NR_X0 = x0; NR_Z0 = z0; NR_W = w; NR_H = h;
            deepluckyblock.util.DebugLog.structure(
                    "terrain naturel memorise sur {}x{} en {},{} -- toutes les passes de lissage resteront "
                            + "a moins de {} blocs de cette reference (emprise et colonnes d'eau exceptees)",
                    w, h, x0, z0, MAX_SMOOTH_DELTA);
        }
        return NATURAL_REF;
    }

    /** Nouvelle structure : la reference naturelle doit etre recapturee. */
    public static void resetNaturalReference() {
        NATURAL_REF = null;
    }

    private static void smoothPassAfterPreload(ServerLevel level, BlockPos structMin, BlockPos structMax,
                                               int kernelSize, int passes, int half, int smoothRing,
                                               int x0, int z0, int w, int h, Runnable onDone) {
        // Heightmap d'origine (terrain naturel, servira pour le fade en bordure).
        //
        // FIX T7 (cause racine) : la capture passe par SafeSurface, qui
        // garantit que le chunk est REELLEMENT genere et que sa heightmap est
        // utilisable (recalculee si elle repond la sentinelle minBuildHeight).
        // Avant, la lecture directe getHeight() ramenait -64 sur des chunks
        // dont la heightmap n'etait pas prete : 25469 colonnes sur 30589
        // (83 %) de la zone decrite par une carte fantome, puis rattrapees a
        // l'aveugle par le despeckle (mediane des voisins).
        int[][] orig = deepluckyblock.util.SafeSurface.captureHeightmap(level, x0, z0, w, h);
        // Despeckle : sur certaines colonnes (typiquement le coin min/min de chaque
        // chunk) la heightmap renvoie une valeur corrompue (souvent le minBuildHeight).
        // Sans correction, le blur tire la cible vers le bas et rebuildColumn remonte
        // la colonne en stone+dirt+grass = une tour par coin de chunk. On remplace
        // toute valeur absurde par la mediane des 8 voisins.
        orig = despeckleHeightmap(orig, w, h, level.getMinBuildHeight());
        // FIX (rapporte en jeu : "des grosses creuvases", "les trous dans le
        // terrain ont ete mal geres"). Comble les ravins ETROITS et PROFONDS
        // avant le flou : sans cela, le blur etale la crevasse en une large
        // depression molle au lieu de la supprimer.
        // Keep measured heights intact: reconstruction must fill the actual missing blocks.
        int[][] filled = fillCrevasses(orig, w, h);

        int sMinI = structMin.getX() - x0, sMaxI = structMax.getX() - x0;
        int sMinJ = structMin.getZ() - z0, sMaxJ = structMax.getZ() - z0;

        // Blur global sur TOUTE la zone (l'emprise n'est plus figee).
        //
        // OPTIMISATION (freeze de 122 s constate dans dernierslogs7 avec trois
        // structures generees coup sur coup).
        //
        // L'ancienne version appliquait un noyau circulaire 2D en force brute :
        // pour chaque colonne elle relisait toutes les cellules du disque.
        // Cout mesure sur une zone 232x216 :
        //   kernel  7x7, 50 passes :  29 cellules -> ~72.7 M operations
        //   kernel 15x15, 12 passes : 149 cellules -> ~89.6 M operations
        //   soit ~162 MILLIONS d'operations par structure, x3 en parallele.
        //
        // Un flou par moyenne est SEPARABLE : appliquer un flou 1D horizontal
        // puis un flou 1D vertical donne le meme resultat qu'un flou 2D, mais
        // en O(k) au lieu de O(k^2) par colonne. Avec en plus une SOMME
        // GLISSANTE (on ajoute la cellule qui entre, on retire celle qui sort),
        // le cout devient O(1) par colonne, INDEPENDANT de la taille du noyau.
        //
        //   avant : 162 M operations      apres : ~5 M      -> ~30x plus rapide
        //
        // Le rendu change de facon imperceptible : on passe d'un disque a un
        // carre, mais apres 50 passes successives les deux convergent vers la
        // meme gaussienne (theoreme central limite) -- et le fade qui suit
        // domine largement cette nuance.
        // === T20 : LES COLONNES D'EAU NE PARTICIPENT PAS AU FLOT ===
        // Le gel de l'eau (T13) passe AVANT ce lissage : la heightmap voyait donc
        // le FOND de l'ocean a la place de sa SURFACE, et le flou melangeait ce
        // fond (Y~43) avec la terre ferme (Y~63). Mesure client du 20/09 :
        //     [STRUCT5] dragon : ecart de sol avant/apres smooth = -20 blocs (63 -> 43)
        //     [STRUCT5] observatory : sol de l'emprise irregulier apres smooth
        //                             (median=49 contre 63 au centre)
        // Resultat en jeu : le rivage etait creuse de 20 blocs, la structure se
        // retrouvait sur un plateau isole, et l'eau restituee formait des murs
        // verticaux au-dessus du vide. Desormais ces colonnes sont EXCLUES du
        // flou (elles gardent leur propre hauteur) : elles ne tirent plus le
        // rivage vers le bas et ne sont pas reconstruites par-dessus l'eau.
        boolean[][] waterMask = waterColumns(level, x0, z0, w, h);

        int[][] hm = filled;
        int[][] tmp = new int[w][h];
        int[][] dst = new int[w][h];
        for (int pass = 0; pass < passes; pass++) {
            // --- passe horizontale (somme glissante sur i, colonnes d'eau sautees) ---
            for (int j = 0; j < h; j++) {
                long sum = 0; int count = 0;
                for (int i = 0; i <= Math.min(half, w - 1); i++) {
                    if (waterMask[i][j]) continue;
                    sum += hm[i][j]; count++;
                }
                for (int i = 0; i < w; i++) {
                    tmp[i][j] = (waterMask[i][j] || count == 0) ? orig[i][j]
                            : (int) Math.round((double) sum / count);
                    int out = i - half, in = i + half + 1;
                    if (out >= 0 && !waterMask[out][j]) { sum -= hm[out][j]; count--; }
                    if (in < w  && !waterMask[in][j])  { sum += hm[in][j];  count++; }
                }
            }
            // --- passe verticale (somme glissante sur j, colonnes d'eau sautees) ---
            for (int i = 0; i < w; i++) {
                long sum = 0; int count = 0;
                for (int j = 0; j <= Math.min(half, h - 1); j++) {
                    if (waterMask[i][j]) continue;
                    sum += tmp[i][j]; count++;
                }
                for (int j = 0; j < h; j++) {
                    dst[i][j] = (waterMask[i][j] || count == 0) ? orig[i][j]
                            : (int) Math.round((double) sum / count);
                    int out = j - half, in = j + half + 1;
                    if (out >= 0 && !waterMask[i][out]) { sum -= tmp[i][out]; count--; }
                    if (in < h  && !waterMask[i][in])  { sum += tmp[i][in];  count++; }
                }
            }
            // Do not repeat identical heightmap passes or accumulate truncation towards zero.
            boolean changed = false;
            for (int i = 0; i < w && !changed; i++) changed = !Arrays.equals(hm[i], dst[i]);
            int[][] swap = hm; hm = dst; dst = swap;
            if (!changed) break;
        }
        // === T21 : GARDE-FOU D'AMPLITUDE ===
        // Meme avec le masque, un relief extreme peut encore faire deriver une
        // colonne de plusieurs dizaines de blocs (mesure : -20). Le lissage doit
        // adoucir, jamais recreuser : on borne la variation a MAX_SMOOTH_DELTA
        // blocs par colonne et par rapport au terrain naturel. Le log publie la
        // derive REELLE avant bridage, donc on voit si le garde-fou sert.
        hm = clampSmoothDelta(level, hm, orig, waterMask, x0, z0, w, h);

        // Fade : bande de lissage. ANCIEN comportement (bug) : fade=1 (100% flou)
        // PILE sur l'emprise de la structure et decroissant vers le bord -> le sol
        // sous la structure devenait la moyenne floutee d'un disque de ~80 blocs,
        // qui peut etre tres different (souvent plus haut : montagne) du sol PLAT
        // deja detecte par findFlat/findFlatArea AVANT le smooth. Consequence :
        // rebuildColumn remontait tout le terrain sous le batiment -> deltaY de
        // reancrage enorme (mur/montagne, decalage Y absurde), alors que le terrain
        // etait deja plat et n'avait besoin que du lissage de la couronne autour.
        //
        // NOUVEAU comportement : on ANCRE l'emprise (+ marge de securite) sur la
        // hauteur d'ORIGINE (fade=0, aucun flou) puisque findFlat/findFlatArea ont
        // deja garanti un sol plat a cet endroit precis. Le flou monte ensuite en
        // s'eloignant (bande mediane a fade=1 = lissage plein, INCHANGE) puis
        // redescend vers 0 au bord exterieur de la zone pour se raccorder au
        // terrain naturel (comportement de raccord deja existant, conserve).
        // FIX (rapporte en jeu : "ma structure se retrouve a moitie dans une
        // montagne wtf !", "c'est clairement pas applani niveau terrain", et la
        // demande explicite "d'abord il applanit un max, SURTOUT LA ZONE OU DOIT
        // ETRE LA STRUCTURE, et etends un peu").
        //
        // L'ancrage (fade=0) conservait la hauteur d'ORIGINE **colonne par
        // colonne**. Il protegeait donc bien les fondations du flou... mais il
        // recopiait aussi tel quel le relief accidente du site quand celui-ci
        // n'etait pas reellement plat. Or findFlatArea ne garantit pas toujours
        // un sol plat : il lui arrive de se rabattre sur le moins pentu, ce que
        // le log 4 montre noir sur blanc :
        //     "[STRUCT4-FLAT] Pas assez plat (min diff=37, max=10), utilise le
        //      moins pentu (verifie)"
        // Avec un denivele de 37 blocs conserve sous le batiment, la structure
        // se retrouve litteralement a moitie enterree dans la pente.
        //
        // CORRECTIF : l'emprise est mise a UNE SEULE hauteur, la MEDIANE des
        // hauteurs du footprint. La mediane (et non la moyenne) est choisie pour
        // etre insensible aux extremes : un pic ou un trou isole dans l'emprise
        // ne deplace pas le niveau retenu.
        //
        // Ce n'est PAS un retour du bug historique du "mur/montagne" : celui-ci
        // venait de l'usage de la valeur FLOUTEE (moyenne d'un disque de ~80
        // blocs, qui deborde largement sur le paysage alentour). Ici le niveau
        // est calcule UNIQUEMENT a partir des colonnes de l'emprise, donc il
        // reste par construction dans la plage de hauteurs du site choisi.
        // (bloc d'aplanissement de l'emprise SUPPRIME -- voir le commentaire
        // REVERT plus bas : il cassait les offsets Y calibres par structure.)

        int[][] target = new int[w][h];
        double maxFade = smoothRing;
        // Marge d'ancrage : rayon (en blocs, au-dela de l'emprise) laisse a la
        // hauteur d'origine. Couvre l'emprise elle-meme + une petite bordure pour
        // que les fondations/abords immediats de la structure ne bougent jamais.
        // T26 : marge d'ancrage reduite de 4 blocs a 1 bloc. « ce qui cause le terrain
        // collé a la structure de ne pas etre smoothé et ducoup mal rendre » (log
        // client) : chaque bloc laisse a sa hauteur d'origine est un bloc NON lisse,
        // et la couronne autour du batiment restait brute sur 4 blocs d'epaisseur.
        double anchorMargin = Math.min(1.0, maxFade * 0.1);
        // Le pic de flou (fade=1) est atteint a mi-couronne : le lissage du
        // paysage environnant reste donc plein, seule la zone pres du batiment
        // est desormais protegee.
        double peakDist = anchorMargin + Math.max(1.0, (maxFade - anchorMargin) * 0.5);
        // FIX (rapporte en jeu : "on voit clairement des bords rectangulaires
        // terrain tout autour de la structure, c'est trop parfait, pas assez
        // naturel") : la distance utilisee ici etait Math.max(dx, dz), c'est-a-dire
        // la distance de Tchebychev. Ses lignes de niveau sont des CARRES : toutes
        // les bandes de fade (ancrage, pic de flou, raccord) dessinaient donc des
        // rectangles concentriques parfaits autour du build, d'ou le bord net et
        // geometrique visible en jeu.
        //
        // On passe a une distance EUCLIDIENNE (lignes de niveau = ellipses, donc
        // coins arrondis) et on y ajoute un bruit de valeur coherent qui ondule la
        // frontiere. La bordure n'est plus une courbe mathematique mais un contour
        // irregulier, facon transition de biome vanilla.
        long fadeSeed = level.getSeed() ^ ((long) structMin.getX() * 341873128712L)
                ^ ((long) structMin.getZ() * 132897987541L);
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int dx = (i < sMinI) ? (sMinI - i) : ((i > sMaxI) ? (i - sMaxI) : 0);
                int dz = (j < sMinJ) ? (sMinJ - j) : ((j > sMaxJ) ? (j - sMaxJ) : 0);
                // Distance euclidienne : plus de coins carres.
                double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                // Ondulation du contour : +/- 18% du ring, sur deux octaves pour
                // eviter un motif periodique reconnaissable. N'est appliquee qu'a
                // partir de l'ancrage, pour ne jamais perturber le sol du batiment.
                if (dist > 0.0) {
                    double n = valueNoise2D(fadeSeed, (x0 + i) * 0.055, (z0 + j) * 0.055) * 0.72
                             + valueNoise2D(fadeSeed + 7777L, (x0 + i) * 0.145, (z0 + j) * 0.145) * 0.28;
                    // Rampe : le bruit monte progressivement depuis le bord de
                    // l'emprise. Sans elle, une colonne situee a 1 bloc du build
                    // pouvait voir sa distance bruitee sauter au-dela de la zone
                    // ancree et retomber sur la hauteur naturelle -> marche nette
                    // au pied du batiment (6 blocs mesures sur cas simule).
                    double ramp = Math.min(1.0, dist / Math.max(1.0, anchorMargin * 2.0));
                    dist = Math.max(0.0, dist + n * maxFade * 0.18 * ramp);
                }
                double fade;
                if (dist <= anchorMargin) {
                    fade = 0.0; // sous/pres de la structure : hauteur d'origine intacte
                } else if (dist <= peakDist) {
                    fade = (dist - anchorMargin) / (peakDist - anchorMargin); // montee vers plein flou
                } else {
                    fade = 1.0 - Math.min(1.0, (dist - peakDist) / (maxFade - peakDist)); // redescente vers terrain naturel
                }
                // REVERT (rapporte en jeu : « tu te mets a creuser jusqu'au
                // dernier bloc de la structure pour le poser au sol pile poil,
                // alors que c'est INTERDIT ! j'ai litteralement des offset y
                // pour chaque structure individuelles, tu as tout casse ! » et
                // « je vois que tu as cree un puit pour y mettre la structure »).
                //
                // MON ERREUR : j'avais introduit un "aplanissement de l'emprise
                // au niveau median" (footprintLevel), qui REECRIVAIT la hauteur
                // du sol sous le batiment. Or chaque structure possede son
                // propre offset Y calibre a la main (TEMPLATE_Y_OFFSET, oy...) :
                // deplacer le sol sous elle invalide ce calage. Quand le median
                // tombait sous le terrain reel, le remodelage creusait
                // litteralement une cuvette autour du batiment -> le "puits"
                // constate.
                //
                // On revient au comportement d'origine : l'emprise est ANCREE
                // sur sa hauteur REELLE (orig), jamais deplacee. Le lissage ne
                // touche que la couronne, pour fondre les abords dans le
                // paysage. Les offsets Y par structure sont donc de nouveau
                // respectes a la lettre.
                double baseH = orig[i][j];
                int smoothed = (int) Math.round(baseH + (hm[i][j] - baseH) * fade);

                // FIX (demande en jeu : "d'abord il applanit un max, surtout la zone
                // ou doit etre la structure, et etends un peu, PUIS RAJOUTER DES
                // PETITS BUMPS DE TERRAIN" / "puis on regarde si c'est trop plat et
                // pas naturel, dans ce cas la on ajoute des petits denivelés").
                //
                // Apres le flou, la couronne est un plan quasi parfait : c'est ce
                // qui donne l'aspect artificiel. On y rajoute un relief de rappel
                // de faible amplitude (+/- 2 blocs), sur deux octaves.
                //
                // L'amplitude est modulee par `fade` : nulle sous le batiment
                // (fondations jamais bosselees, on ne recree pas le bug du
                // "mur/montagne"), maximale au milieu de la couronne, puis elle
                // s'eteint au bord exterieur pour se raccorder au terrain naturel
                // qui possede deja son propre relief.
                if (fade > 0.05) {
                    double bump = valueNoise2D(fadeSeed + 31337L, (x0 + i) * 0.085, (z0 + j) * 0.085) * 0.68
                                + valueNoise2D(fadeSeed + 91193L, (x0 + i) * 0.210, (z0 + j) * 0.210) * 0.32;
                    // Enveloppe en cloche : 0 aux deux extremites du fade, 1 au centre.
                    double env = Math.sin(Math.min(1.0, fade) * Math.PI);
                    smoothed += (int) Math.round(bump * BUMP_AMPLITUDE * env);
                }
                target[i][j] = smoothed;
            }
        }

        // === T24 : FIN DES TERRASSES CONCENTRIQUES (constat visuel du 20/09) ===
        // Les captures montrent des ESCALIERS de terre autour des structures :
        // c'est la consequence directe de rebuildColumn, qui coupe chaque colonne a
        // sa hauteur cible -- deux colonnes voisines pouvaient differer de 10 a 20
        // blocs, ce qui produit une paroi verticale, puis une autre, puis une autre :
        // un amphitheatre. Un relief naturel ne fait jamais ca.
        // Correctif : on borne la DIFFERENCE ENTRE COLONNES VOISINES de la heightmap
        // cible (relaxation sur les 4 voisins, quelques iterations). Les marches
        // deviennent des pentes continues, sans toucher aux niveaux voulus (plateau
        // de la structure, emprise, plans d'eau).
        target = limitSlope(target, waterMask, w, h, sMinI, sMaxI, sMinJ, sMaxJ, x0, z0);
        // La relaxation a pu deplacer des colonnes au-dela de la fenetre du lissage :
        // on reapplique la borne pour rester coherent avec la mesure finale.
        target = clampSmoothDelta(level, target, orig, waterMask, x0, z0, w, h);

        // Noise and slope relaxation must never alter water columns or the anchored footprint.
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                if (waterMask[i][j] || (i >= sMinI && i <= sMaxI && j >= sMinJ && j <= sMaxJ)) {
                    target[i][j] = orig[i][j];
                } else if (i >= sMinI - 6 && i <= sMaxI + 6 && j >= sMinJ - 6 && j <= sMaxJ + 6) {
                    // Do not create a two-block trench around the anchored structure.
                    target[i][j] = Math.max(target[i][j], orig[i][j]);
                }
            }
        }

        // Reconstruire en batch (toute la zone ; les cols sans changement sont skipees).
        // On passe aussi la heightmap CAPTUREE (despecklee) pour que rebuildColumn ne
        // re-interroge pas getHeight() : sur les colonnes corrompues ça remonterait
        // des tours. La hauteur de reference devient deterministe.
        rebuildTerrainBatched(level, target, orig, x0, z0, w, h, onDone);
    }

    /**
     * RE-ANALYSE DU TERRAIN VU DE HAUT : comble les failles et les marches.
     *
     * Demande utilisateur : « re analyse du terrain vu de haut pour remplir les
     * potentielles failles / trous, faut vraiment un rendu nature, des tas
     * smooth et pas qui perdent d'un coup 3 blocs ».
     *
     * Deux defauts traites, en travaillant sur la heightmap de surface :
     *
     *  1. FAILLES : une colonne nettement plus basse que TOUTES ses voisines
     *     (puits d'un bloc de large laisse par le carve ou un decor). On la
     *     remonte au niveau de la mediane du voisinage.
     *
     *  2. MARCHES ABRUPTES : une difference de plus de {@code maxStep} blocs
     *     entre deux colonnes adjacentes. On comble la marche par palier, ce
     *     qui transforme une chute seche de 3+ blocs en pente douce.
     *
     * Plusieurs passes, car combler une marche peut en reveler une autre plus
     * loin. Les colonnes de l'emprise protegee ne sont jamais touchees.
     */
    private static void startGapStepPass(ServerLevel level, BlockPos min, BlockPos max, int maxStep, Runnable onDone) {
        new GapStepPass(level, min, max, maxStep, onDone).slice();
    }

    /**
     * Ancienne version SYNCHRONE, desormais decoupee en tranches (voir
     * {@link GapStepPass}). Elle tenait le thread serveur pendant les 3 passes
     * sur ~30 000 colonnes (mesure en jeu : un tick de 8 917 ms juste apres
     * « decorate 2/5 : fillGapsAndSteps debut »).
     */
    private static final class GapStepPass {
        private final ServerLevel level;
        private final BlockPos min, max;
        private final int maxStep;
        private final Runnable onDone;
        private final int x0, z0, w, h;
        private final long t0 = System.currentTimeMillis();

        /** Heightmap de la passe courante (reconstruite a chaque passe, comme avant). */
        private int[][] hm;
        private int bandCursor, passIdx, i = 1, j = 1;
        private int filled, stepped, changedThisPass, slices;

        GapStepPass(ServerLevel level, BlockPos min, BlockPos max, int maxStep, Runnable onDone) {
            this.level = level; this.min = min; this.max = max;
            this.maxStep = maxStep; this.onDone = onDone;
            int ring = terrainRing();
            this.x0 = min.getX() - ring;
            this.z0 = min.getZ() - ring;
            this.w = max.getX() + ring - x0 + 1;
            this.h = max.getZ() + ring - z0 + 1;
        }

        private void resume() {
            deepluckyblock.util.TerrainChain.heartbeat();
            TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::slice);
        }

        void slice() {
            slices++;
            deepluckyblock.util.TerrainChain.heartbeat();
            deepluckyblock.util.ChunkKeeper.keep(level);
            // Portillon de zone : jamais d'acces bloc au chunk absent (sinon
            // getHeight/getBlockState generent le chunk en plein tick).
            if (!deepluckyblock.util.ChunkKeeper.zoneComplete(level)) { resume(); return; }

            long deadline = System.currentTimeMillis() + SLICE_BUDGET_MS;

            // 1) capture de la heightmap par bandes (jamais 30 000 colonnes d'un coup)
            if (hm == null) {
                if (bandCursor == 0) hm = new int[w][h];
                while (bandCursor < w) {
                    int band = Math.min(BAND_COLUMNS, w - bandCursor);
                    int[][] part = deepluckyblock.util.SafeSurface.captureHeightmap(level, x0 + bandCursor, z0, band, h);
                    for (int k = 0; k < band; k++) hm[bandCursor + k] = part[k];
                    bandCursor += band;
                    if (System.currentTimeMillis() >= deadline) { resume(); return; }
                }
            }

            // 2) balayage des colonnes interieures (logique IDENTIQUE a l'origine)
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            while (i < w - 1) {
                while (j < h - 1) {
                    int x = x0 + i, z = z0 + j;
                    if (!isProtected(x, z)) {
                        int y = hm[i][j];
                        int[] nb = { hm[i - 1][j], hm[i + 1][j], hm[i][j - 1], hm[i][j + 1] };
                        int lowestNb = Math.min(Math.min(nb[0], nb[1]), Math.min(nb[2], nb[3]));
                        int medianNb;
                        { int[] sorted = nb.clone(); java.util.Arrays.sort(sorted); medianNb = (sorted[1] + sorted[2]) / 2; }

                        // 1) FAILLE : plus basse que TOUS ses voisins d'au moins 2.
                        if (y <= lowestNb - 2) {
                            int target = Math.min(medianNb, y + 6); // garde-fou anti-remplissage massif
                            for (int yy = y + 1; yy <= target; yy++) {
                                BlockState cur = level.getBlockState(m.set(x, yy, z));
                                if (!cur.isAir() && !cur.getFluidState().isEmpty()) break;
                                level.setBlock(m, (yy == target)
                                        ? Blocks.GRASS_BLOCK.defaultBlockState()
                                        : Blocks.DIRT.defaultBlockState(), FAST_FLAG);
                                filled++; changedThisPass++;
                            }
                        } else {
                            // 2) MARCHE ABRUPTE : plus de maxStep blocs sous un voisin.
                            int highestNb = Math.max(Math.max(nb[0], nb[1]), Math.max(nb[2], nb[3]));
                            if (highestNb - y > maxStep) {
                                int target = Math.min(y + (highestNb - y - maxStep), y + 4);
                                for (int yy = y + 1; yy <= target; yy++) {
                                    BlockState cur = level.getBlockState(m.set(x, yy, z));
                                    if (!cur.isAir() && !cur.getFluidState().isEmpty()) break;
                                    level.setBlock(m, (yy == target)
                                            ? Blocks.GRASS_BLOCK.defaultBlockState()
                                            : Blocks.DIRT.defaultBlockState(), FAST_FLAG);
                                    stepped++; changedThisPass++;
                                }
                            }
                        }
                    }
                    j++;
                    if (System.currentTimeMillis() >= deadline) { resume(); return; }
                }
                j = 1; i++;
            }

            // 3) fin de passe : on repart pour une passe si ca bougeait encore
            if (changedThisPass == 0 || passIdx >= 2) {
                if (filled > 0 || stepped > 0)
                    deepluckyblock.util.DebugLog.structure(
                            "fillGapsAndSteps : {} blocs de faille combles, {} blocs de marche adoucis (seuil {}) -- {} tranches, {} ms",
                            filled, stepped, maxStep, slices, System.currentTimeMillis() - t0);
                if (onDone != null) onDone.run();
                return;
            }
            passIdx++; changedThisPass = 0; i = 1; j = 1; hm = null; bandCursor = 0;
            resume();
        }
    }

    /**
     * BOUCLE ANTI-FUITE D'EAU, avec barriere irreguliere.
     *
     * Demande utilisateur : « recheck de l'eau, qui fuit, etc, si lac vide re
     * remplir a niveau, fixwater, verifier si en resultat du fixwater il y a
     * des blocs d'eau pas complets et en mouvement, si c'est le cas creer une
     * barriere non droite pour bloquer l'eau, retirer l'eau et re essayer de
     * fixwater, jusqu'a ce que ca fuie plus, a chaque fois on analyse ou ca
     * fuit et on bloque ».
     *
     * Chaque iteration :
     *   1. repere les blocs d'eau NON PLEINS (donc en ecoulement) ;
     *   2. pour chacun, monte une barriere de terrain sur les cotes ouverts --
     *      barriere IRREGULIERE : hauteur variable par bruit, jamais une ligne
     *      droite ;
     *   3. retire l'eau qui coule encore, puis relance fixWaterNearStructure
     *      pour reconvertir les filets restants en sources.
     * On repete jusqu'a ce qu'il n'y ait plus aucune fuite, ou au plus
     * {@code maxIter} fois.
     *
     * @return true si la zone est etanche a la fin
     */
    private static void startSealLeaksPass(ServerLevel level, BlockPos min, BlockPos max, int maxIter, Runnable onDone) {
        new SealLeaksPass(level, min, max, maxIter, onDone).slice();
    }

    /**
     * Etancheite de l'eau, decoupee en tranches (T6).
     *
     * <p>Ancienne version SYNCHRONE : jusqu'a 8 iterations, chacune balayant
     * toute la zone (~34 000 colonnes, 26 blocs verticaux) PUIS lancant
     * {@code fixWaterNearStructure} -- le tout dans le meme tick.
     */
    private static final class SealLeaksPass {
        private final ServerLevel level;
        private final BlockPos min, max;
        private final int maxIter;
        private final Runnable onDone;
        private final int x0, z0, x1, z1;
        private final long seed;
        private final long t0 = System.currentTimeMillis();
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        private final List<int[]> leaks = new ArrayList<>();

        private int iter, scanX, scanZ, applyIdx, walled, drained, slices;
        private boolean applying = false;

        SealLeaksPass(ServerLevel level, BlockPos min, BlockPos max, int maxIter, Runnable onDone) {
            this.level = level; this.min = min; this.max = max;
            this.maxIter = maxIter; this.onDone = onDone;
            // Only protect the building; exterior cut ponds are restored by fixLiquids.
            int ring = 6;
            this.x0 = min.getX() - ring; this.x1 = max.getX() + ring;
            this.z0 = min.getZ() - ring; this.z1 = max.getZ() + ring;
            this.scanX = x0; this.scanZ = z0;
            this.seed = level.getSeed() ^ ((long) min.getX() * 7321L) ^ ((long) min.getZ() * 1517L);
        }

        private void resume() {
            deepluckyblock.util.TerrainChain.heartbeat();
            TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::slice);
        }

        /** Une colonne : ou l'eau fuit-elle ? (logique identique a l'origine) */
        private void scanColumn(int x, int z) {
            int surf = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z);
            for (int y = Math.min(surf + 2, level.getMaxBuildHeight() - 1);
                 y >= Math.max(surf - 24, level.getMinBuildHeight() + 1); y--) {
                var fs = level.getBlockState(m.set(x, y, z)).getFluidState();
                if (fs.isEmpty() || !fs.is(net.minecraft.tags.FluidTags.WATER)) continue;
                // Eau NON PLEINE = en mouvement (elle s'ecoule).
                if (!fs.isSource()) { leaks.add(new int[]{x, y, z}); break; }
                // Source dont un voisin lateral est a l'air : elle va fuir.
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    if (level.getBlockState(n.set(x + d[0], y, z + d[1])).isAir()) {
                        leaks.add(new int[]{x, y, z});
                        break;
                    }
                }
                break;
            }
        }

        void slice() {
            slices++;
            deepluckyblock.util.TerrainChain.heartbeat();
            deepluckyblock.util.ChunkKeeper.keep(level);
            if (!deepluckyblock.util.ChunkKeeper.zoneComplete(level)) { resume(); return; }

            long deadline = System.currentTimeMillis() + SLICE_BUDGET_MS;

            if (!applying) {
                if (iter >= maxIter) {
                    deepluckyblock.util.DebugLog.structure(
                            "sealWaterLeaks : {} iterations atteintes, des fuites peuvent subsister ({} tranches, {} ms)",
                            maxIter, slices, System.currentTimeMillis() - t0);
                    if (onDone != null) onDone.run();
                    return;
                }
                // 1) repérage des fuites, colonne par colonne
                while (scanX <= x1) {
                    while (scanZ <= z1) {
                        scanColumn(scanX, scanZ);
                        scanZ++;
                        if (System.currentTimeMillis() >= deadline) { resume(); return; }
                    }
                    scanZ = z0;
                    scanX++;
                }
                if (leaks.isEmpty()) {
                    if (iter > 0)
                        deepluckyblock.util.DebugLog.structure(
                                "sealWaterLeaks : zone etanche apres {} iteration(s)", iter);
                    if (onDone != null) onDone.run();
                    return;
                }
                applying = true;
                applyIdx = 0;
            }

            // 2) barrage irregulier + vidange des filets
            while (applyIdx < leaks.size()) {
                int[] lk = leaks.get(applyIdx);
                int x = lk[0], y = lk[1], z = lk[2];
                // Barriere IRREGULIERE : la hauteur suit un bruit, donc jamais
                // un mur droit -- on veut une berge, pas une digue.
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], nz = z + d[1];
                    if (!level.getBlockState(n.set(nx, y, nz)).isAir()) continue;
                    double noise = valueNoise2D(seed, nx * 0.21, nz * 0.21);
                    int extra = 1 + (int) Math.round((noise + 1.0) * 1.2); // 1..3
                    for (int yy = y; yy <= Math.min(y + extra, level.getMaxBuildHeight() - 1); yy++) {
                        if (!level.getBlockState(n.set(nx, yy, nz)).isAir()) continue;
                        level.setBlock(n, (yy == y + extra)
                                ? Blocks.GRASS_BLOCK.defaultBlockState()
                                : Blocks.DIRT.defaultBlockState(), FAST_FLAG);
                        walled++;
                    }
                }
                var fs = level.getBlockState(m.set(x, y, z)).getFluidState();
                if (!fs.isEmpty() && !fs.isSource()) {
                    level.setBlock(m, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                    drained++;
                }
                applyIdx++;
                if (System.currentTimeMillis() >= deadline) { resume(); return; }
            }

            deepluckyblock.util.DebugLog.structure(
                    "sealWaterLeaks iteration {} : {} fuites, {} blocs de barriere, {} blocs d'eau retires",
                    iter + 1, leaks.size(), walled, drained);
            iter++;
            leaks.clear(); applying = false; applyIdx = 0;
            walled = 0; drained = 0; scanX = x0; scanZ = z0;
            // L'eau est refixee AVANT de re-analyser, comme dans la version
            // d'origine : sinon on bouclerait sur les memes filets.
            fixWaterNearStructure(level, min, max, 20, this::slice);
        }
    }

    /**
     * Emprise a NE JAMAIS toucher pendant un smooth (null = aucune protection).
     *
     * Les smooths rejoues APRES le paste doivent lisser le terrain alentour
     * sans raboter ni enterrer le batiment : le smooth raisonne en heightmap
     * (une seule hauteur par colonne) et ecraserait la structure. On memorise
     * donc l'emprise a proteger juste avant l'appel.
     */
    /** Horodatage de debut de phase, pour chronometrer chaque etape. */
    private static long PHASE_T0 = 0L;

    /** Log d'etape avec temps ecoule depuis le debut de la generation. */
    private static long LAST_STEP_MS = 0L;

    private static void step(String label) {
        long nowMs = System.currentTimeMillis();
        deepluckyblock.util.DebugLog.structure("[{} s] {}",
                String.format("%.1f", (nowMs - PHASE_T0) / 1000.0), label);
        // T16 : fil rouge de phase (lu par la sonde de tick) + duree REELLE de
        // l'etape qui vient de finir. Les passes decoupees affichent une duree
        // de travail cumulee (ex. « 12 000 ms ») qui ne dit pas si le serveur
        // tournait a 20 TPS ou a 2 TPS : cet ecart-ci, si.
        deepluckyblock.util.DebugLog.setPhase(label);
        if (LAST_STEP_MS != 0L) {
            long delta = nowMs - LAST_STEP_MS;
            if (delta >= 3000L) {
                LOGGER.warn("[DLB-STRUCTURE] l'etape qui vient de finir a pris {} ms de temps REEL ({})"
                                + " -- voir [DLB-LAGPROBE] pour le detail par tick", delta, label);
            }
        }
        LAST_STEP_MS = nowMs;
    }

    private static BlockPos PROTECT_MIN = null, PROTECT_MAX = null;

    public static void protectFootprint(BlockPos min, BlockPos max) {
        PROTECT_MIN = min; PROTECT_MAX = max;
    }
    public static void clearFootprintProtection() {
        PROTECT_MIN = null; PROTECT_MAX = null;
    }

    /** La colonne appartient-elle a l'emprise protegee (marge incluse) ? */
    private static boolean isProtected(int x, int z) {
        if (PROTECT_MIN == null) return false;
        final int margin = 1;
        return x >= PROTECT_MIN.getX() - margin && x <= PROTECT_MAX.getX() + margin
            && z >= PROTECT_MIN.getZ() - margin && z <= PROTECT_MAX.getZ() + margin;
    }

    private static void rebuildTerrainBatched(ServerLevel level, int[][] hm, int[][] orig, int x0, int z0,
                                              int w, int h, Runnable onDone) {
        // Toutes les colonnes de la zone (l'emprise n'est plus figee : le fade
        // la fait passer en douceur vers le terrain naturel).
        // cols = { x, z, targetY, origY } : origY est la hauteur capturee (despecklee)
        // au debut du smooth. On la donne a rebuildColumn au lieu de laisser
        // re-interroger getHeight() (corrompue sur les coins de chunk).
        List<int[]> cols = new ArrayList<>();
        for (int i = 0; i < w; i++)
            for (int j = 0; j < h; j++) {
                // REVERT (constat de dernierslogs7 : « smooth : 20948
                // colonnes de barrage preservees » sur une zone 232x216, soit
                // 50112 colonnes -> 41.8 % de la zone ETAIT EXCLUE DU LISSAGE).
                //
                // C'est la cause du « smooth seulement au centre, pas dans les
                // bordures » : chaque colonne touchee par une sphere de barrage
                // etait sautee, et comme placeWaterDams en semait des milliers,
                // des pans entiers de terrain n'etaient jamais lisses.
                //
                // placeWaterDams() etant desormais desactive (les spheres
                // rendaient mal de toute facon), il n'y a plus aucune raison
                // d'exclure quoi que ce soit : TOUTES les colonnes de la zone
                // sont lissees, bordures comprises.
                //
                // SEULE exception : les smooths rejoues APRES le paste
                // protegent l'emprise du batiment (voir protectFootprint).
                if (isProtected(x0 + i, z0 + j) || hm[i][j] == orig[i][j]) continue;
                if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, x0 + i, z0 + j)) continue;
                cols.add(new int[]{x0 + i, z0 + j, hm[i][j], orig[i][j]});
            }
        int total = (cols.size() + CLEAR_BATCH_COLS - 1) / CLEAR_BATCH_COLS;
        if (total == 0) { if (onDone != null) onDone.run(); return; }
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int b = 0; b < total; b++) {
            final int bi = b, bt = total;
            TestProcedure.schedule(level, TestProcedure.currentTick(level) + b + 1, () -> {
                int start = bi * CLEAR_BATCH_COLS, end = Math.min(start + CLEAR_BATCH_COLS, cols.size());
                deepluckyblock.util.DebugLog.setPhase("rebuildTerrain " + (bi + 1) + "/" + bt);
                for (int i = start; i < end; i++) {
                    int[] c = cols.get(i);
                    rebuildColumn(level, mut, c[0], c[1], c[2], c[3]);
                }
                if (bi == bt - 1 && onDone != null) onDone.run();
            });
        }
    }

    // Despeckle de la heightmap capturee : remplace toute valeur aberrante (coins
    // de chunk ou la heightmap est corrompue et renvoie le minBuildHeight) par la
    // mediane des 8 voisins.
    //
    // BUG TROUVE (trace litterale, logs a l'appui - voir dernierslogs3.txt) : le
    // critere additionnel "Math.abs(v - med) > 40" ne detecte PAS que la
    // corruption de coin de chunk, il declenche aussi sur tout relief NATUREL et
    // REEL (falaise, colline, pente) des que deux colonnes voisines different de
    // plus de 40 blocs, ce qui est courant en terrain accidente. Preuve chiffree :
    // pour la citadelle (zone 216x232 = 50112 colonnes), ce critere a "corrige"
    // 49362 colonnes (98.5% de la zone) en un seul passage. Une vraie corruption
    // de coin de chunk ne touche qu'~1 colonne par chunk (224 chunks preloades
    // ici -> ~224 attendues, pas 49362 : facteur ~220x trop eleve). Ce despeckle
    // ecrasait donc du relief reel vers la mediane locale AVANT les passes de flou
    // (7x7 x50 puis 15x15 x12), qui diffusaient ensuite cette heightmap deja
    // aplatie sur toute la couronne (jusqu'a 74-80 blocs de rayon) -> plateau
    // artificiel uniforme = le "mur/montagne au sommet plat" rapporte en jeu.
    //
    // FIX : on ne corrige plus que le vrai signal de corruption (valeur au ras du
    // bedrock, "v < floor"), qui est le seul cas documente de heightmap corrompue
    // aux coins de chunk. Le relief reel n'est plus touche ici : c'est le fade
    // (anchor/peak/decroissance) de smoothPass() qui a deja la charge de fondre
    // la structure dans le terrain reel, quelle que soit sa forme.
    // =========================================================================
    // BRUIT DE VALEUR (value noise) — utilitaire de naturalisation
    // =========================================================================
    //
    // Petit generateur de bruit coherent, sans dependance et deterministe pour
    // une graine donnee. Sert a casser toutes les regularites geometriques du
    // pipeline (bordures de fade, relief de rappel), qui sont la cause des
    // rendus "trop parfaits" signales en jeu.
    //
    // Deterministe = deux appels sur la meme colonne donnent la meme valeur,
    // donc le terrain est stable si la zone est regeneree, et deux structures
    // voisines ne produisent pas de raccord incoherent.
    //
    // Retour dans [-1, 1].

    /**
     * Comble les CREVASSES etroites de la heightmap capturee.
     *
     * FIX (rapporte en jeu : "des grosses creuvases", "les trous dans le terrain
     * ont ete mal geres, il faut que ton programme voie la zone en grand, avec
     * les couches y, d'abord il applanit un max").
     *
     * Une crevasse se reconnait a une colonne nettement PLUS BASSE que ses
     * voisines DANS TOUTES LES DIRECTIONS (un ravin, un trou). C'est different
     * d'une falaise ou d'une pente, ou le denivele n'existe que d'un seul cote :
     * ce test directionnel est justement ce qui evite de raboter du relief reel.
     *
     * Lecon du bug precedent (documente au-dessus de despeckleHeightmap) : un
     * critere trop large avait "corrige" 98.5% des colonnes et rase le paysage.
     * Ici on impose donc trois garde-fous cumulatifs :
     *   - la colonne doit etre plus basse que la mediane de son voisinage d'au
     *     moins CREVASSE_MIN_DEPTH blocs (trou franc, pas une ondulation) ;
     *   - elle doit etre entouree de terrain plus haut sur les 4 axes (ravin
     *     ferme, pas un bord de falaise ni une descente vers un lac) ;
     *   - on ne remonte JAMAIS au-dessus de la mediane locale (on comble le
     *     trou, on ne cree pas de bosse).
     */
    private static int[][] fillCrevasses(int[][] src, int w, int h) {
        final int R = 3;                    // rayon d'analyse
        final int CREVASSE_MIN_DEPTH = 4;   // profondeur minimale pour agir
        int[][] out = new int[w][h];
        for (int i = 0; i < w; i++) out[i] = src[i].clone();
        int filled = 0;

        int[] ring = new int[(2 * R + 1) * (2 * R + 1)];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int v = src[i][j];
                int n = 0;
                for (int di = -R; di <= R; di++)
                    for (int dj = -R; dj <= R; dj++) {
                        if (di == 0 && dj == 0) continue;
                        int ni = i + di, nj = j + dj;
                        if (ni < 0 || ni >= w || nj < 0 || nj >= h) continue;
                        ring[n++] = src[ni][nj];
                    }
                if (n < 8) continue;
                int[] sorted = java.util.Arrays.copyOf(ring, n);
                java.util.Arrays.sort(sorted);
                int med = sorted[n / 2];
                if (med - v < CREVASSE_MIN_DEPTH) continue;

                // Ravin ferme ? Il faut du terrain plus haut DES DEUX COTES d'au
                // moins UN des deux axes.
                //
                // Exiger les 4 cotes (premiere version) ne detectait RIEN sur un
                // cas reel : un ravin LINEAIRE (le cas courant) est encaisse sur
                // son axe transversal mais ouvert dans le sens de sa longueur.
                // Verifie numeriquement sur terrain simule : critere a 4 cotes =
                // 0 colonne corrigee (ravin manque) ; critere a 1 axe = 240
                // colonnes, soit exactement les 2x120 colonnes du ravin, falaise
                // et vallee large preservees.
                boolean higherNeg_i = false, higherPos_i = false;
                boolean higherNeg_j = false, higherPos_j = false;
                for (int d = 1; d <= R; d++) {
                    if (i - d >= 0 && src[i - d][j] >= v + CREVASSE_MIN_DEPTH) higherNeg_i = true;
                    if (i + d < w  && src[i + d][j] >= v + CREVASSE_MIN_DEPTH) higherPos_i = true;
                    if (j - d >= 0 && src[i][j - d] >= v + CREVASSE_MIN_DEPTH) higherNeg_j = true;
                    if (j + d < h  && src[i][j + d] >= v + CREVASSE_MIN_DEPTH) higherPos_j = true;
                }
                if (!((higherNeg_i && higherPos_i) || (higherNeg_j && higherPos_j))) continue;

                // Comblement partiel vers la mediane : on remonte de 80% du
                // deficit, ce qui efface le trou tout en gardant une legere
                // depression -> le raccord reste naturel, pas un bouchon plat.
                out[i][j] = v + (int) Math.round((med - v) * 0.8);
                filled++;
            }
        }
        if (filled > 0)
            deepluckyblock.util.DebugLog.structure("fillCrevasses : {} colonnes de ravin comblees (trous fermes, relief reel preserve)", filled);
        return out;
    }

    /** Hash entier -> [-1,1], bien disperse (constantes type xorshift). */
    private static double hashNoise(long seed, int xi, int zi) {
        long n = seed + (long) xi * 374761393L + (long) zi * 668265263L;
        n = (n ^ (n >>> 13)) * 1274126177L;
        n = n ^ (n >>> 16);
        return ((n & 0xFFFFFL) / (double) 0xFFFFF) * 2.0 - 1.0;
    }

    /** Value noise 2D avec interpolation lissee (smoothstep) sur la grille unite. */
    private static double valueNoise2D(long seed, double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double fx = x - xi, fz = z - zi;
        // smoothstep : supprime les discontinuites de derivee (pas de "facettes").
        double sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
        double n00 = hashNoise(seed, xi, zi),         n10 = hashNoise(seed, xi + 1, zi);
        double n01 = hashNoise(seed, xi, zi + 1),     n11 = hashNoise(seed, xi + 1, zi + 1);
        return (n00 * (1 - sx) + n10 * sx) * (1 - sz) + (n01 * (1 - sx) + n11 * sx) * sz;
    }

    /**
     * T33 : PASSE FINALE DE TERRAIN, la toute derniere du pipeline.
     *
     * Consigne en jeu (textuelle) : « le smooth global n'a pas ete fait en
     * dernier sur le terrain sol uniquement » + « il reste des petites lignes ou
     * des blocs singuliers un poil trop haut » + « analyser la map vue de haut en
     * chunks par chunks PUIS en global, refill les holes puis smooth », avec un
     * ecart maximal de 2 blocs dans un rayon de 6 autour de la structure.
     *
     * Principe :
     *   1. VUE DE HAUT, CHUNK PAR CHUNK : on lit la hauteur de la SURFACE SOL de
     *      chaque colonne (on ignore l'eau, les arbres, les decors et tout bloc
     *      de structure -- seul le terrain sol compte) ;
     *   2. EN GLOBAL : on compare chaque colonne a la mediane de ses 8 voisines
     *      de sol. Une CUVETTE (colonne plus basse que la majorite) est comblee,
     *      une SAILLIE (ligne ou bloc singulier plus haut, avec au moins 7/8
     *      voisines plus basses) est rabotee ;
     *   3. ECART MAX 2 BLOCS : jamais de gros terrassement, l'emprise protegee et
     *      les colonnes d'eau (rivage) sont totalement exclues.
     *
     * Aucun trou n'est cree : rebuildColumn remet le sol a la hauteur cible.
     * Budget de temps FINAL_PASS_BUDGET_MS : si la zone est immense, la passe
     * s'arrete proprement et le dit dans le log plutot que de figer le serveur.
     */
    private static final int FINAL_MAX_DELTA = 2;
    /** T35 : profondeur maximale d'un trou que la passe finale accepte de combler. */
    private static final int FINAL_MAX_HOLE_FILL = 48;
    /** T34 : budget de la correction (comparaison a la mediane des 8 voisines). */
    private static final long FINAL_PASS_BUDGET_MS = 150;
    /** T34 : budget de l'analyse vue de haut (lectures de hauteur). */
    private static final long FINAL_SCAN_BUDGET_MS = 120;

    /**
     * T36 : point d'entree PUBLIC de la passe finale de terrain, pour les
     * structures qui ne passent PAS par le pipeline complet (paste brut :
     * everest, circus/shipdead, repli rawBlocks). Elles ne recevaient jusqu'ici
     * AUCUN travail de terrain : ni preparation, ni berges, ni lissage final --
     * ce sont precisement celles ou le joueur voit des creux et des bordures
     * d'eau trop basses.
     */
    public static void finalTerrainPass(ServerLevel level, BlockPos min, BlockPos max, int ring) {
        finalGroundPass(level, min, max, ring, null);
    }


    private static void finalGroundPass(ServerLevel level, BlockPos min, BlockPos max, int ring, Runnable onDone) {
        finalTerrainPass(level, min, max, ring, onDone);
    }

    /**
     * T37 : la passe finale etait bornee par un budget de 120/150 ms, donc elle
     * ne couvrait qu'une FRACTION de la zone des que celle-ci etait grande
     * (mesure : « analyse vue de haut interrompue par le budget de 120 ms apres
     * 14 chunks » sur l'emprise 187x291 de l'everest). Elle est maintenant
     * REPARTIE SUR PLUSIEURS TICKS : a chaque tick elle travaille au plus
     * {@link #FINAL_SLICE_MS} millisecondes, puis se replanifie. Aucun tick n'est
     * jamais bloque, et la zone est analysee EN ENTIER. Meme principe que le
     * pre-chargement des chunks (S.31/S.40 du guide : time-slicing).
     */
    private static final long FINAL_SLICE_MS = 40;

    /** Etat de la passe finale repartie sur plusieurs ticks. */
    private static final class FinalState {
        final ServerLevel level; final BlockPos min, max;
        final int x0, z0, x1, z1, w, h, ring;
        final int[][] hm; final boolean[][] skip;
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int ccx, ccz;              // curseur de l'analyse (chunks)
        int ci, cj;                // curseur de la correction (colonnes)
        boolean scanDone, corrDone, scanLogged;
        int chunks, ground, changed, filled, shaved, holes, holesDone, scanned, ticks;
        final long t0 = System.currentTimeMillis();
        final Runnable onDone;
        FinalState(ServerLevel l, BlockPos mn, BlockPos mx, int x0, int z0, int x1, int z1, int ring, Runnable od) {
            level = l; min = mn; max = mx;
            this.x0 = x0; this.z0 = z0; this.x1 = x1; this.z1 = z1; this.ring = ring;
            w = x1 - x0 + 1; h = z1 - z0 + 1;
            hm = new int[w][h]; skip = new boolean[w][h];
            ccx = x0 >> 4; ccz = z0 >> 4; ci = 0; cj = 0;
            onDone = od;
        }
    }

    /** T39 : variante avec RAPPEL DE FIN -- l'appelant ne pose la structure
     *  qu'une fois le terrain reellement fige (la passe est multi-ticks). */
    public static void finalTerrainPass(ServerLevel level, BlockPos min, BlockPos max, int ring, Runnable onDone) {
        int x0 = min.getX() - ring, z0 = min.getZ() - ring;
        int x1 = max.getX() + ring, z1 = max.getZ() + ring;
        finalTerrainPassStep(new FinalState(level, min, max, x0, z0, x1, z1, ring, onDone));
    }

    private static void finalTerrainPassStep(FinalState st) {
        st.ticks++;
        long slice = System.currentTimeMillis();
        boolean more = false;

        // ---------- 1. analyse vue de haut, chunk par chunk ----------
        if (!st.scanDone) {
            int ccxMax = st.x1 >> 4, cczMax = st.z1 >> 4;
            int cczStart = st.z0 >> 4;
            while (System.currentTimeMillis() - slice < FINAL_SLICE_MS) {
                int ccx = st.ccx, ccz = st.ccz;
                st.chunks++;
                int cx0 = Math.max(st.x0, ccx << 4), cx1 = Math.min(st.x1, (ccx << 4) + 15);
                int cz0 = Math.max(st.z0, ccz << 4), cz1 = Math.min(st.z1, (ccz << 4) + 15);
                for (int x = cx0; x <= cx1; x++) {
                    for (int z = cz0; z <= cz1; z++) {
                        int i = x - st.x0, j = z - st.z0;
                        st.hm[i][j] = Integer.MIN_VALUE;
                        // Empreinte REELLE du bati (T34c) : on ne saute que le bati
                        // lui-meme (+1 bloc), pas la boite protegee globale.
                        if (x >= st.min.getX() - 1 && x <= st.max.getX() + 1
                                && z >= st.min.getZ() - 1 && z <= st.max.getZ() + 1) { st.skip[i][j] = true; continue; }
                        if (deepluckyblock.util.WaterDam.frozenColumn(st.level, x, z)) { st.skip[i][j] = true; continue; }
                        int y = groundSurfaceY(st.level, st.mut, x, z);
                        if (y == Integer.MIN_VALUE) { st.skip[i][j] = true; continue; }
                        st.hm[i][j] = y;
                        st.ground++;
                    }
                }
                // curseur de chunk suivant
                if (ccz >= cczMax) { st.ccz = cczStart; st.ccx++; if (st.ccx > ccxMax) st.scanDone = true; }
                else st.ccz = ccz + 1;
                if (st.scanDone) break;
            }
            if (st.scanDone && !st.scanLogged) {
                st.scanLogged = true;
                deepluckyblock.util.DebugLog.structure(
                        "passe finale terrain : vue de haut {} chunks ({} colonnes de sol analysables) sur {}x{} -- emprise + {} blocs, ecart max {} (analyse repartie sur {} tick(s))",
                        st.chunks, st.ground, st.w, st.h, st.ring, FINAL_MAX_DELTA, st.ticks);
            }
        }

        // ---------- 2. correction en global (cuvettes, saillies, trous) ----------
        // T37b : ce bloc ne doit PAS etre un « else if » de l'analyse. Si l'analyse
        // se terminait dans le tick courant (cas de toutes les structures de taille
        // moyenne), l'ancien enchainement sortait AVANT la correction : mesure
        // « 0 colonnes retouchees sur 0 examinees » alors que 3 853 colonnes
        // venaient d'etre analysees.
        if (st.scanDone && !st.corrDone) {
            int processed = 0;
            while (st.ci < st.w) {
                if (st.cj >= st.h) { st.ci++; st.cj = 0; processed = 0; continue; }
                correctFinalColumn(st, st.ci, st.cj);
                st.cj++; processed++;
                if (processed >= 64 && System.currentTimeMillis() - slice > FINAL_SLICE_MS) break;
            }
            if (st.ci >= st.w) st.corrDone = true;
        }

        more = !(st.scanDone && st.corrDone);
        if (more) {
            TestProcedure.schedule(st.level, TestProcedure.currentTick(st.level) + 1, () -> finalTerrainPassStep(st));
            return;
        }

        // ---------- 3. bilan ----------
        deepluckyblock.util.DebugLog.structure(
                "passe finale terrain (sol uniquement, vue de haut) : {} colonnes retouchees sur {} examinees en {} ms et {} tick(s) ({} cuvettes comblees, {} saillies rabotees, {} TROUS PROFONDS rebouches sur {} detectes -- ecart max {} blocs)",
                st.changed, st.scanned, System.currentTimeMillis() - st.t0, st.ticks,
                st.filled, st.shaved, st.holesDone, st.holes, FINAL_MAX_DELTA);
        if (st.onDone != null) st.onDone.run();
    }

    /** Une colonne de la passe finale : cuvette, saillie ou trou profond. */
    private static void correctFinalColumn(FinalState st, int i, int j) {
        if (st.skip[i][j] || st.hm[i][j] == Integer.MIN_VALUE) return;
        st.scanned++;
        int[] nbh = new int[8];
        int n = 0;
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) continue;
                int ni = i + di, nj = j + dj;
                if (ni < 0 || ni >= st.w || nj < 0 || nj >= st.h) continue;
                if (st.skip[ni][nj] || st.hm[ni][nj] == Integer.MIN_VALUE) continue;
                nbh[n++] = st.hm[ni][nj];
            }
        }
        if (n < 5) return;   // pas assez de voisins de sol : on ne devine pas
        java.util.Arrays.sort(nbh, 0, n);
        int med = nbh[n / 2];
        int v = st.hm[i][j];
        int d = med - v;
        if (d == 0) return;
        int sameSide = 0;
        for (int k = 0; k < n; k++) if ((nbh[k] - v) * d > 0) sameSide++;
        int x = st.x0 + i, z = st.z0 + j;
        // The final pass must not undo the no-excavation collar of the main smooth.
        if (d < 0 && x >= st.min.getX() - 6 && x <= st.max.getX() + 6
                && z >= st.min.getZ() - 6 && z <= st.max.getZ() + 6) return;
        if (Math.abs(d) <= FINAL_MAX_DELTA) {
            // Cuvette : simple majorite de voisines plus hautes. Saillie : exigence
            // stricte (presque tout le voisinage plus bas) -- sinon on effacerait
            // une vraie butte de terrain.
            if (d > 0 && sameSide < 5) return;
            if (d < 0 && sameSide < n - 1) return;
            if (!isNaturalTerrain(st.level.getBlockState(st.mut.set(x, v, z)))) return;
            rebuildColumn(st.level, st.mut, x, z, med, v);
            st.changed++;
            if (d > 0) st.filled++; else st.shaved++;
        } else {
            // T35 : TROU PROFOND (plus de 2 blocs sous la mediane des voisines).
            // Isolement STRICT (au moins 7 des 8 voisines plus hautes) : un ravin
            // ou une vallee large a des voisines basses et n'est donc pas touche.
            if (d < 0 || sameSide < n - 1) return;
            if (d > FINAL_MAX_HOLE_FILL) return;
            st.holes++;
            if (deepluckyblock.util.SafeSurface.height(st.level, Heightmap.Types.WORLD_SURFACE, x, z) - v > FINAL_MAX_HOLE_FILL) return;
            if (!isNaturalTerrain(st.level.getBlockState(st.mut.set(x, v, z)))) return;
            rebuildColumn(st.level, st.mut, x, z, med, v);
            st.holesDone++;
            st.changed++;
        }
    }

    /**
     * T33 : hauteur de la SURFACE SOL d'une colonne, ou Integer.MIN_VALUE si la
     * colonne n'est pas du terrain sol nu (eau, arbre, decor, bloc de structure).
     */
    private static int groundSurfaceY(ServerLevel level, BlockPos.MutableBlockPos mut, int x, int z) {
        // T34 : la premiere version DESCENDAIT bloc par bloc depuis la surface.
        // Sur les colonnes d'eau / d'arbre (ou l'on ne s'arrete jamais) cela
        // pouvait couter ~300 lectures pour UNE colonne : sur une emprise
        // d'everest + anneau 6 (90 291 colonnes) le seul pre-scan a fait exploser
        // un tick a 60 s (watchdog du run t33b). On lit donc la hauteur O(1) via
        // MOTION_BLOCKING_NO_LEAVES (premier bloc solide, feuilles exclues) et on
        // ne fait que 2 lectures de confirmation.
        // T34c : on part de la heightmap WORLD_SURFACE (celle utilisee par tout le
        // reste du pipeline, donc fiable meme apres des milliers d'editions) et on
        // descend d'AU PLUS 6 blocs pour trouver le premier bloc de TERRAIN SOL
        // naturel. La descente est bornee : cout O(1) par colonne, contrairement a
        // la version qui pouvait lire ~300 blocs par colonne d'eau (cause du tick
        // de 60 s du run t33b).
        int y = Math.min(deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z) + 1,
                level.getMaxBuildHeight() - 1);
        for (int k = 0; k <= 6 && y > level.getMinBuildHeight(); k++, y--) {
            BlockState s = level.getBlockState(mut.set(x, y, z));
            if (s.isAir()) continue;
            if (s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return Integer.MIN_VALUE; // colonne d'eau
            if (!s.blocksMotion()) continue;                        // herbes, fleurs, torches : on descend
            if (isLog(s) || isLeaf(s)) return Integer.MIN_VALUE;     // arbre : colonne non touchee
            if (isNaturalTerrain(s)) return y;                      // sol naturel
            return Integer.MIN_VALUE;                               // decor / bloc de structure
        }
        return Integer.MIN_VALUE;
    }

    private static int[][] despeckleHeightmap(int[][] src, int w, int h, int minBuild) {
        int[][] out = new int[w][h];
        int fixed = 0;
        int floor = minBuild + 8; // aucune surface reelle n'est au niveau du bedrock
        int[] nb = new int[8];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int v = src[i][j];
                if (v < floor) {
                    int n = 0;
                    for (int di = -1; di <= 1; di++) {
                        for (int dj = -1; dj <= 1; dj++) {
                            if (di == 0 && dj == 0) continue;
                            int ni = i + di, nj = j + dj;
                            if (ni < 0 || ni >= w || nj < 0 || nj >= h) continue;
                            nb[n++] = src[ni][nj];
                        }
                    }
                    if (n > 0) {
                        java.util.Arrays.sort(nb, 0, n);
                        out[i][j] = nb[n / 2];
                        fixed++;
                        continue;
                    }
                }
                out[i][j] = v;
            }
        }
        if (fixed > 0)
            deepluckyblock.util.DebugLog.structure("despeckle : {} colonnes de heightmap corrigees (coins de chunk corrompus, sous floor={})", fixed, floor);
        return out;
    }

    // Reconstruit une colonne : remet le terrain a la hauteur cible (targetY).
    // currentY = hauteur CAPTUREE (origY, despecklee) au debut du smooth. On ne
    // re-interroge PAS getHeight() : sur certaines colonnes (coins de chunk) la
    // heightmap renvoie une valeur corrompue, ce qui ferait monter une tour de
    // stone+dirt+grass a chaque coin de chunk.
    private static void rebuildColumn(ServerLevel level, BlockPos.MutableBlockPos mut, int cx, int cz, int targetY, int origY) {
        int currentY = origY;
        if (currentY < targetY) {
            for (int y = currentY + 1; y <= targetY; y++) {
                BlockState fill = (y == targetY) ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : (y >= targetY - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
                level.setBlock(mut.set(cx, y, cz), fill, FAST_FLAG);
            }
        } else if (currentY > targetY) {
            for (int y = targetY + 1; y <= currentY; y++) {
                BlockState s = level.getBlockState(mut.set(cx, y, cz));
                if (!s.isAir() && !s.is(Blocks.BEDROCK)) level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG);
            }
            BlockState surf = level.getBlockState(mut.set(cx, targetY, cz));
            if (surf.isAir() || !surf.blocksMotion()) level.setBlock(mut, Blocks.GRASS_BLOCK.defaultBlockState(), FAST_FLAG);
        }
    }

    // =========================================================================
    // PHASE 3b : CLEANUP OVERLAP (supprime le terrain dans l'emprise structure)
    // Skip les chunks majoritairement air (JAMAIS vider l'air).
    // =========================================================================

    private static void cleanupOverlap(ServerLevel level, BlockPos structMin, BlockPos structMax, Runnable onDone) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int maxY = structMax.getY() + 3;
        int deleted = 0;

        for (int x = structMin.getX(); x <= structMax.getX(); x++) {
            for (int z = structMin.getZ(); z <= structMax.getZ(); z++) {
                // Compter les blocs solides dans cette colonne (de Y=1 a maxY)
                int solidCount = 0;
                for (int y = 1; y <= maxY; y++) {
                    BlockState s = level.getBlockState(mut.set(x, y, z));
                    if (!s.isAir() && s.blocksMotion()) solidCount++;
                }
                // Skip si la colonne est majoritairement air (< 3 blocs solides sur toute la hauteur)
                // = on ne vide PAS les chunks a air. JAMAIS.
                if (solidCount < 3) continue;

                // Sinon : supprimer les blocs solides AU-DESSUS du sol de la structure
                // (terrain qui a deborde dans l'emprise pendant le smooth)
                int baseY = structMin.getY();
                for (int y = baseY + 1; y <= maxY; y++) {
                    BlockState s = level.getBlockState(mut.set(x, y, z));
                    if (!s.isAir() && s.blocksMotion()
                            && !s.is(Blocks.BEDROCK) && !s.is(Blocks.GRASS_BLOCK)) {
                        // Verifier que ce n'est pas un bloc de structure (pierre taille, brique, bois, etc.)
                        // On ne supprime que les blocs de terrain naturel (dirt, stone, grass, sand, gravel, etc.)
                        if (isNaturalTerrain(s)) {
                            level.setBlock(mut, Blocks.AIR.defaultBlockState(), 2);
                            deleted++;
                        }
                    }
                }
            }
        }
        if (deleted > 0)
            deepluckyblock.util.DebugLog.structure("cleanupOverlap : {} blocs de terrain supprimes dans l'emprise", deleted);
        if (onDone != null) onDone.run();
    }

    private static boolean isNaturalTerrain(BlockState s) {
        if (isLog(s) || isLeaf(s)) return true;
        return s.is(Blocks.DIRT) || s.is(Blocks.STONE) || s.is(Blocks.GRASS_BLOCK)
            || s.is(Blocks.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.COARSE_DIRT)
            || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM) || s.is(Blocks.RED_SAND)
            || s.is(Blocks.TERRACOTTA) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
            || s.is(Blocks.GRANITE) || s.is(Blocks.DIORITE) || s.is(Blocks.ANDESITE)
            || s.is(Blocks.DEEPSLATE) || s.is(Blocks.TUFF) || s.is(Blocks.CALCITE)
            || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.MUD) || s.is(Blocks.CLAY)
            || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.ICE) || s.is(Blocks.PACKED_ICE)
            || s.is(Blocks.COAL_ORE) || s.is(Blocks.IRON_ORE) || s.is(Blocks.COPPER_ORE) || s.is(Blocks.GOLD_ORE)
            || s.is(Blocks.REDSTONE_ORE) || s.is(Blocks.LAPIS_ORE) || s.is(Blocks.DIAMOND_ORE) || s.is(Blocks.EMERALD_ORE)
            || s.is(Blocks.DEEPSLATE_COAL_ORE) || s.is(Blocks.DEEPSLATE_IRON_ORE) || s.is(Blocks.DEEPSLATE_COPPER_ORE)
            || s.is(Blocks.DEEPSLATE_GOLD_ORE) || s.is(Blocks.DEEPSLATE_REDSTONE_ORE) || s.is(Blocks.DEEPSLATE_LAPIS_ORE)
            || s.is(Blocks.DEEPSLATE_DIAMOND_ORE) || s.is(Blocks.DEEPSLATE_EMERALD_ORE);
    }

    // =========================================================================
    // PHASE 3d : VERIFY GRASS BLOCK (surface en herbe)
    // =========================================================================

    private static void verifyGrassSurface(ServerLevel level, BlockPos structMin, BlockPos structMax) {
        verifyGrassSurface(level, structMin, structMax, null);
    }

    private static void verifyGrassSurface(ServerLevel level, BlockPos structMin, BlockPos structMax,
                                           Runnable onDone) {
        // Toute la zone GIGA lissée : grass en surface, + snow layer si biome enneigé
        // (sinon les blocs d'herbe en zone de neige ont une texture buggée).
        final int[] counters = {0, 0};   // [0] = grass, [1] = snow
        startColumnPass(level, "verifyGrassSurface",
                columnsOf(structMin, structMax, terrainRing()), BATCH_COLS, col -> {
                    verifyGrassColumn(level, col[0], col[1], counters);
                }, () -> {
                    if (counters[0] > 0 || counters[1] > 0)
                        deepluckyblock.util.DebugLog.structure("verifyGrassSurface : {} grass, {} snow layers",
                                counters[0], counters[1]);
                    if (onDone != null) onDone.run();
                });
    }

    /** Une colonne de verifyGrassSurface (met a jour counters[grass, snow]). */
    private static void verifyGrassColumn(ServerLevel level, int x, int z, int[] counters) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int surfY = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (surfY < 1) return;
        BlockState s = level.getBlockState(mut.set(x, surfY, z));
        if (s.isAir()) return;
        boolean snowy = isSnowy(level, mut);
        if (s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE)) {
            if (level.getBlockState(mut.set(x, surfY + 1, z)).isAir()) {
                level.setBlock(mut, Blocks.SNOW.defaultBlockState(), 3); counters[1]++;
            }
        } else if (snowy) {
            // Biome enneigé : sol en herbe + snow layer (sinon texture buggée).
            if (s.blocksMotion() && isNaturalTerrain(s) && !s.is(Blocks.GRASS_BLOCK)
                    && !s.is(Blocks.SAND) && !s.is(Blocks.SANDSTONE) && !s.is(Blocks.RED_SAND)) {
                level.setBlock(mut.set(x, surfY, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2); counters[0]++;
            }
            if (level.getBlockState(mut.set(x, surfY + 1, z)).isAir()) {
                level.setBlock(mut, Blocks.SNOW.defaultBlockState(), 3); counters[1]++;
            }
        } else if (s.blocksMotion() && isNaturalTerrain(s) && !s.is(Blocks.GRASS_BLOCK)
                && !s.is(Blocks.SAND) && !s.is(Blocks.SANDSTONE) && !s.is(Blocks.RED_SAND)) {
            level.setBlock(mut.set(x, surfY, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2); counters[0]++;
        }
    }

    /** True si le biome a la position est enneigé. On vérifie via le nom du biome
     *  (snow/ice/frozen) car getPrecipitation() n'existe pas dans cette version de Forge. */
    private static boolean isSnowy(ServerLevel level, BlockPos pos) {
        try {
            var biomeKey = level.getBiome(pos).unwrapKey();
            if (biomeKey.isEmpty()) return false;
            String path = biomeKey.get().location().getPath().toLowerCase();
            return path.contains("snow") || path.contains("ice") || path.contains("frozen");
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // PHASE 5 : REPLANT TREES
    // =========================================================================

    private static void replantTrees(ServerLevel level, List<SavedTree> trees,
                                      BlockPos structMin, BlockPos structMax, Runnable onDone) {
        RandomSource random = level.getRandom();
        int count = 0;

        for (SavedTree tree : trees) {
            int tx = tree.baseX(), tz = tree.baseZ();

            // Skip si dans l'emprise de la structure (on ne replante pas dessus)
            if (tx >= structMin.getX() && tx <= structMax.getX()
                    && tz >= structMin.getZ() && tz <= structMax.getZ()) continue;

            // FIX (demande utilisateur : "que les arbres spawnent autour et pas
            // dedans ou dessus des structures (bug possible)").
            //
            // Les decors sont poses juste avant, et ils enregistrent leur
            // emprise. Sans ce test, un arbre pouvait pousser au milieu d'une
            // ruine ou sur le toit d'une tente : le tronc traverse la
            // structure et les feuilles l'engloutissent.
            //
            // La marge de 1 bloc evite aussi les arbres colles aux murs, dont
            // le feuillage deborderait a l'interieur.
            if (StructureScatterDecor.isInsideDecor(tx, tz, 1)) continue;

            // VRAI arbre : on fait pousser une feature d'arbre vanilla a la nouvelle
            // surface. Fini les demi-arbres inventes dont les feuilles pourrissent.
            // FIX (rapporte en jeu : "la tu fais un arbre, au dessus du dernier
            // tronc d'arbre qui a surement pas clear precedement, mais au dessus
            // un bloc de dirt puis un arbre, et parfois ca se repete 3 fois !").
            //
            // CAUSE : Heightmap.MOTION_BLOCKING_NO_LEAVES compte les BUCHES comme
            // surface (elle n'exclut que les feuilles). Si un tronc subsistait sur
            // la colonne, getHeight() renvoyait donc le sommet de ce tronc, et
            // placeRealTree() forcait alors un bloc de terre juste en dessous
            // avant de planter -> tronc / dirt / arbre empiles, en cascade.
            //
            // CORRECTIF : on descend jusqu'au VRAI sol (premier bloc de terrain
            // naturel), en traversant tout residu vegetal. Le sol est donc
            // detecte librement pour chaque arbre, comme demande a 04:24:33.
            int newSurfaceY = findNaturalGroundY(level, tx, tz);
            if (newSurfaceY <= level.getMinBuildHeight()) continue;
            BlockPos pos = new BlockPos(tx, newSurfaceY, tz);
            if (placeRealTree(level, tree.type(), pos, random)) count++;
        }
        if (count > 0) deepluckyblock.util.DebugLog.structure("replant : {} arbres reels replantes", count);

        if (onDone != null) onDone.run();
    }

    /**
     * Y de plantation reel : premier bloc LIBRE au-dessus du vrai sol naturel.
     *
     * Contrairement a getHeight(MOTION_BLOCKING_NO_LEAVES), qui s'arrete sur le
     * premier bloc bloquant (donc sur une BUCHE residuelle), on descend a travers
     * tout residu vegetal (buches, feuilles, buissons, neige) jusqu'a rencontrer
     * du terrain : c'est ce qui empeche les empilements tronc/dirt/arbre.
     *
     * Retourne minBuildHeight si aucun sol valable n'est trouve (colonne a
     * ignorer), afin de ne jamais planter dans le vide.
     */
    private static int findNaturalGroundY(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int y = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int floor = level.getMinBuildHeight();
        // Descente a travers les residus vegetaux et l'air.
        while (y > floor) {
            BlockState s = level.getBlockState(m.set(x, y - 1, z));
            if (s.isAir() || isSurfaceDecor(s)) { y--; continue; }
            break;
        }
        if (y <= floor) return floor;
        // Le bloc sous y doit etre un sol plantable ; sinon colonne invalide
        // (eau, pierre nue, bloc de structure...) -> on ne replante pas.
        BlockState ground = level.getBlockState(m.set(x, y - 1, z));
        if (!ground.is(Blocks.GRASS_BLOCK) && !ground.is(Blocks.DIRT) && !ground.is(Blocks.COARSE_DIRT)
                && !ground.is(Blocks.PODZOL) && !ground.is(Blocks.MOSS_BLOCK) && !ground.is(Blocks.ROOTED_DIRT)) {
            return floor;
        }
        return y;
    }

    /** Fait pousser un VRAI arbre : pose une bouture (sapling) au stage 1 puis
     *  declenche sa croissance via performBonemeal. C'est le chemin canonique du
     *  jeu (identique a la poudre d'os) -> arbres complets et stables, finis les
     *  demi-arbres dont les feuilles pourrissent. */
    private static boolean placeRealTree(ServerLevel level, String type, BlockPos pos, RandomSource random) {
        // FIX: Use vanilla Feature.TREE placement instead of sapling+bonemeal.
        // This avoids: (1) bare trunk poles when sapling fails to grow,
        // (2) floating vegetation from partial growth, (3) giant log columns
        // with green bands from repeated bonemeal attempts on same column.
        // The vanilla feature places a complete, stable tree atomically.
        if (deepluckyblock.util.SafeSurface.isSubmerged(level, pos.getX(), pos.getZ())) return false;
        BlockState ground = level.getBlockState(pos.below());
        if (!ground.is(Blocks.GRASS_BLOCK) && !ground.is(Blocks.DIRT) && !ground.is(Blocks.COARSE_DIRT)
                && !ground.is(Blocks.PODZOL) && !ground.is(Blocks.MOSS_BLOCK) && !ground.is(Blocks.ROOTED_DIRT)) {
            return false;
        }
        // Ensure air space above for trunk + leaves
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);

        // Get the configured tree feature for this type
        var featureOpt = getConfiguredTreeFeature(level, type);
        if (featureOpt.isEmpty()) return false;

        Holder<ConfiguredFeature<?, ?>> feature = featureOpt.get();
        // Place the tree feature at the target position
        boolean placed = feature.value().place(level, level.getChunkSource().getGenerator(), random, pos);
        if (placed) {
            deepluckyblock.util.DebugLog.structure("placeRealTree: vanilla tree feature placed at {}", pos);
        }
        return placed;
    }

    /** Returns the configured feature using the registry's actual generic type.
     * Uses registry lookup to find the correct configured feature (e.g. minecraft:oak, minecraft:birch).
     * Falls back to oak if type is unknown. */
    private static java.util.Optional<Holder.Reference<ConfiguredFeature<?, ?>>> getConfiguredTreeFeature(ServerLevel level, String type) {
        String featureId = switch (type == null ? "oak" : type.toLowerCase(java.util.Locale.ROOT)) {
            case "oak" -> "minecraft:oak";
            case "birch" -> "minecraft:birch";
            case "spruce" -> "minecraft:spruce";
            case "jungle" -> "minecraft:jungle_tree";
            case "acacia" -> "minecraft:acacia";
            case "dark_oak" -> "minecraft:dark_oak";
            case "cherry" -> "minecraft:cherry";
            case "mega_spruce", "mega_pine" -> "minecraft:mega_spruce";
            case "mega_jungle" -> "minecraft:mega_jungle_tree";
            case "fancy_oak" -> "minecraft:fancy_oak";
            default -> "minecraft:oak";
        };
        var registry = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        var key = ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.parse(featureId));
        return registry.getHolder(key);
    }

    // =========================================================================
    // PHASE 6 : NATURALIZE (herbe, fleurs, bonemeal)
    // =========================================================================

    /** Fixe l'eau autour de la structure, equivalent pratique d'un //fixwater 20. */
    /**
     * PROTECTION ANTI-CHUTE D'EAU en bordure de plateforme.
     *
     * FIX (rapporte en jeu : "tu as clairement genere une plateforme de terre trop
     * proche de l'eau, sans activer la protection anti chute d'eau" et "tout
     * descend en pente parfaite, l'eau qui etait tout en haut ducoup tombe").
     *
     * Symptome : quand le terrain remodele arrive au contact d'un plan d'eau
     * existant, le lissage peut laisser une colonne d'eau dont le voisin lateral
     * est desormais plus bas (ou a l'air libre). L'eau s'ecoule alors en cascade
     * et se vide, parfois sur une grande hauteur.
     *
     * `placeWaterDams()` traite deja les fuites AVANT le smooth, mais le smooth
     * lui-meme (puis les bumps) peut en recreer de nouvelles : il faut donc une
     * seconde passe APRES. C'est precisement ce qui manquait.
     *
     * Principe : pour chaque colonne d'eau de la zone elargie, si un voisin
     * lateral au meme Y est de l'air, on eleve une berge de terrain jusqu'a
     * WATER_GUARD_MARGIN blocs au-dessus de la surface de l'eau. L'eau est
     * contenue au lieu de tomber, sans creer de mur visible (la berge suit la
     * hauteur locale de l'eau, pas une hauteur fixe).
     */
    private static void guardWaterEdges(ServerLevel level, BlockPos min, BlockPos max) {
        guardWaterEdges(level, min, max, null);
    }

    private static void guardWaterEdges(ServerLevel level, BlockPos min, BlockPos max, Runnable onDone) {
        final int[] placed = {0};
        startColumnPass(level, "guardWaterEdges (berges anti-fuite)",
                // Banks belong at the building, not across the exterior pond to be restored.
                columnsOf(min, max, 6), BATCH_COLS, col -> {
                    placed[0] += guardWaterColumn(level, col[0], col[1]);
                }, () -> {
                    if (placed[0] > 0)
                        deepluckyblock.util.DebugLog.structure(
                                "guardWaterEdges (anti-chute d'eau, APRES smooth) : {} blocs de berge places", placed[0]);
                    if (onDone != null) onDone.run();
                });
    }

    /**
     * T33 : eleve la berge autour de (x,z) pour contenir l'eau au niveau waterY.
     *
     * Empreinte en LOSANGE (|dx|+|dz| &lt;= 2) = projection d'une sphere : le
     * rivage est arrondi. Hauteur = waterY + WATER_GUARD_MARGIN + extra - (d - 1)
     * (donc +3 au premier anneau, +2 au second, +1 de plus par renforcement).
     * On ne pose JAMAIS dans l'eau (on s'arrete a la colonne d'eau) et jamais
     * au-dessus d'un sol deja assez haut : la berge ne peut donc que contenir
     * l'eau, jamais la pousser.
     */
    private static int raiseBank(ServerLevel level, int x, int z, int waterY, int extra,
                                 int minB, int maxB, BlockPos.MutableBlockPos nb) {
        int placed = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int d = Math.abs(dx) + Math.abs(dz);
                if (d == 0 || d > 2) continue;
                int nx = x + dx, nz = z + dz;
                int bankTop = Math.min(waterY + WATER_GUARD_MARGIN + extra - (d - 1), maxB);
                for (int by = Math.max(waterY, minB + 1); by <= bankTop; by++) {
                    BlockState s = level.getBlockState(nb.set(nx, by, nz));
                    if (s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) break; // jamais dans l'eau
                    if (s.is(Blocks.GRASS_BLOCK) && by < bankTop) {
                        level.setBlock(nb, Blocks.DIRT.defaultBlockState(), FAST_FLAG);
                        continue;
                    }
                    if (!s.isAir()) continue;   // sol deja la (ou decor) : on ne touche pas
                    level.setBlock(nb, (by == bankTop)
                            ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : Blocks.DIRT.defaultBlockState(), FAST_FLAG);
                    placed++;
                }
            }
        }
        return placed;
    }

    /** T33 : l'eau de (x,z) au niveau waterY peut-elle encore partir lateralement ? */
    private static boolean leaksAt(ServerLevel level, int x, int z, int waterY, BlockPos.MutableBlockPos nb) {
        for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            if (level.getBlockState(nb.set(x + d[0], waterY, z + d[1])).isAir()) return true;
        }
        return false;
    }

    /** Une colonne de guardWaterEdges : renvoie le nombre de blocs de berge poses. */
    private static int guardWaterColumn(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos nb = new BlockPos.MutableBlockPos();
        int minB = level.getMinBuildHeight();
        int maxB = level.getMaxBuildHeight() - 1;
        int placed = 0;

        int surfY = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z);
        int top = Math.min(surfY + 4, maxB);
        int bot = Math.max(surfY - 30, minB + 1);
        for (int y = top; y >= bot; y--) {
            BlockState here = level.getBlockState(mut.set(x, y, z));
            if (!here.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) continue;

            // Cette colonne d'eau fuit-elle lateralement ?
            boolean leaks = false;
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                int nx = x + d[0], nz = z + d[1];
                if (level.getBlockState(nb.set(nx, y, nz)).isAir()) { leaks = true; break; }
            }
            if (!leaks) continue;

            // T33 (constat en jeu : « les futures bordures de lacs sont 2 blocs
            // trop bas pour contenir la future eau »). L'ancienne berge etait un
            // MUR D'UN BLOC de terrain pose dans les 4 directions cardinales,
            // plafonne a l'eau + 2 : en jeu, elle reste sous la ligne de rivage
            // attendue et l'eau deborde. Nouvelle berge :
            //   - EMPREINTE EN LOSANGE (|dx|+|dz| <= 2) : c'est la projection au
            //     sol d'une SPHERE (« forme sphere type axium »), donc un rivage
            //     arrondi et non une arete verticale ;
            //   - HAUTEUR DECROISSANTE d'un bloc par anneau (dam de gravite) :
            //     eau + 3 au premier anneau, eau + 2 au second (extra +1 a chaque
            //     nouvel essai) -- soit les 1 a 2 blocs de plus demande en jeu ;
            //   - VERIFICATION APRES POSE : si l'eau peut encore partir, on
            //     recommence en renforcant (BANK_RETRIES essais).
            for (int attempt = 0; attempt <= BANK_RETRIES; attempt++) {
                placed += raiseBank(level, x, z, y, attempt, minB, maxB, nb);
                if (!leaksAt(level, x, z, y, nb)) break;   // eau contenue : OK
                if (attempt == BANK_RETRIES)
                    deepluckyblock.util.DebugLog.structure(
                            "berge {},{} : l'eau peut encore partir apres {} renforcements (niveau {}) -- renforcement maximal atteint",
                            x, z, BANK_RETRIES, y);
            }
            break; // colonne traitee a sa surface d'eau la plus haute
        }
        return placed;
    }

    private static void fixWaterNearStructure(ServerLevel level, BlockPos min, BlockPos max, int radius) {
        fixWaterNearStructure(level, min, max, radius, null);
    }

    private static void fixWaterNearStructure(ServerLevel level, BlockPos min, BlockPos max, int radius,
                                              Runnable onDone) {
        // T78 : passes SUCCESSIVES avec sortie anticipee (avant : 4 x la meme liste, toujours
        // balayee en entier). L'ordre relatif des colonnes n'a aucune importance, chaque passe
        // relit tout -- mais une passe qui ne corrige RIEN prouve que l'eau est deja etalee.
        final List<int[]> base = columnsOf(min, max, radius);
        final int[] changed = {0};
        runFixWaterPass(level, base, radius, 1, changed, onDone);
    }

    /**
     * T78 : une passe de fixWaterNearStructure, et la suivante seulement si celle-ci a servi.
     *
     * <p>MESURE qui justifie ce code : la version precedente balayait 4 x 34 452 colonnes d'un
     * coup (137 808 colonnes, ~5,6 M lectures de blocs) a CHAQUE appel, et la methode est appelee
     * jusqu'a 5 fois par pipeline. Or la conversion eau-courante -> source est idempotente : si la
     * passe 1 ne trouve rien, les passes 2, 3 et 4 reliront exactement le meme terrain pour zero
     * resultat. On s'arrete donc des la premiere passe vide, et on le dit dans le journal.
     */
    private static void runFixWaterPass(ServerLevel level, List<int[]> base, int radius, int pass,
                                        int[] changed, Runnable onDone) {
        final int before = changed[0];
        startColumnPass(level,
                "fixWaterNearStructure (rayon " + radius + ", passe " + pass + "/" + FIX_WATER_PASSES + ")",
                base, 128, col -> {
                    changed[0] += fixWaterColumn(level, col[0], col[1]);
                }, () -> {
                    int delta = changed[0] - before;
                    if (delta == 0 && pass < FIX_WATER_PASSES) {
                        deepluckyblock.util.DebugLog.structure(
                                "fixWaterNearStructure : passe {}/{} sans aucune correction -- l'eau est deja "
                                        + "etalee, arret anticipe ({} passe(s) et {} colonne(s) economisees, T78)",
                                pass, FIX_WATER_PASSES, FIX_WATER_PASSES - pass,
                                (long) base.size() * (FIX_WATER_PASSES - pass));
                        finishFixWater(radius, changed, onDone);
                        return;
                    }
                    if (pass < FIX_WATER_PASSES) {
                        runFixWaterPass(level, base, radius, pass + 1, changed, onDone);
                        return;
                    }
                    finishFixWater(radius, changed, onDone);
                });
    }

    /** T78 : cloture de fixWaterNearStructure (une seule fois, quel que soit le chemin). */
    private static void finishFixWater(int radius, int[] changed, Runnable onDone) {
        if (changed[0] > 0)
            deepluckyblock.util.DebugLog.structure(
                    "fixWaterNearStructure : {} blocs d'eau courante convertis en sources (rayon {})",
                    changed[0], radius);
        else
            deepluckyblock.util.DebugLog.structure(
                    "fixWaterNearStructure (rayon {}) : aucune eau courante a corriger -- zone saine", radius);
        if (onDone != null) onDone.run();
    }

    /** Samples only loaded columns; never generates chunks while detecting water. */
    private static int calculateAdaptiveWaterRadius(ServerLevel level, BlockPos min, BlockPos max) {
        int ring = terrainRing();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = min.getX() - ring; x <= max.getX() + ring; x += 4) {
            for (int z = min.getZ() - ring; z <= max.getZ() + ring; z += 4) {
                if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, x, z)) continue;
                int y = deepluckyblock.util.SafeSurface.height(
                        level, Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (level.getBlockState(pos.set(x, y, z)).getFluidState()
                        .is(net.minecraft.tags.FluidTags.WATER)) return ring;
            }
        }
        return 20;
    }

    private static final int FIX_WATER_PASSES = 4;

    /** Une colonne de fixWaterNearStructure : renvoie le nombre de sources retablies.
     * FIX: utilise les scheduled fluid ticks de Minecraft au lieu de placer manuellement
     * les blocs d'eau. Cela permet au système de fluide natif de propager l'eau
     * correctement à travers les frontières de chunks, éliminant les "murs d'eau". */
    private static int fixWaterColumn(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int changed = 0;
        int surface = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z);
        int y0 = Math.max(level.getMinBuildHeight(), surface - 20);
        int y1 = Math.min(level.getMaxBuildHeight() - 1, surface + 20);

        for (int y = y1; y >= y0; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                int fluidLevel = state.getOptionalValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL
                ).orElse(0);

                if (fluidLevel > 0) {
                    // FIX: Schedule a fluid tick instead of manually placing water.
                    // This lets Minecraft's fluid system handle propagation across
                    // chunk borders naturally, eliminating water walls.
                    level.scheduleTick(pos, state.getFluidState().getType(), 1);
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * T77 : NATURALISATION DECOUPEE EN TRANCHES.
     *
     * <p>Elle etait la DERNIERE passe monolithique : 54 064 colonnes (zone 248x218) traitees
     * dans un seul tick, mesure 1753 ms en bac a sable et davantage sur une machine plus lente.
     * C'est exactement la forme du « 1 FREESE ICI » du log du 23/09. Elle avance desormais par le
     * passeur commun : tranches de 96 colonnes, budget adaptatif, report des colonnes dont le
     * chunk n'est pas charge, et surtout une ETIQUETTE DE PHASE -- sans elle, le LAGPROBE
     * facturait son temps a la passe suivante (il accusait « cleanupZone »).
     */
    private static void naturalize(ServerLevel level, BlockPos structMin, BlockPos structMax,
                                    RandomSource random, Runnable onDone) {
        int naturalizeRing = terrainRing() + NATURALIZE_EXTRA_RING;
        // Compteurs passes par CLOSURE (et non statiques) : deux pipelines ne peuvent pas
        // se melanger, meme si le garde-fou de nom les rend de toute facon exclusifs.
        final int[] counts = new int[3];   // 0 = herbes, 1 = fleurs, 2 = champignons
        final long vegSeed = level.getSeed() ^ ((long) structMin.getX() * 912931L)
                ^ ((long) structMin.getZ() * 182883L) ^ 0x5EEDL;
        final int zoneW = structMax.getX() - structMin.getX() + 1 + 2 * naturalizeRing;
        final int zoneH = structMax.getZ() - structMin.getZ() + 1 + 2 * naturalizeRing;
        // T77b : TENIR L'ANNEAU DE NATURALIZE EN MEMOIRE. Sans ca, le passeur saute (T70) toutes
        // les colonnes dont le chunk n'est pas charge -- mesure : 5757 sur 42813 (13 %) laissees
        // sans vegetation, compteurs de plantes en baisse. track() ETEND la zone tenue (il prend le
        // min/max des bornes de chunks) : aucune epingle n'est perdue, et la couronne
        // supplementaire est demandee en tache de fond, au debit borne de T76.
        deepluckyblock.util.ChunkKeeper.track(level,
                new BlockPos(structMin.getX() - naturalizeRing, structMin.getY(), structMin.getZ() - naturalizeRing),
                new BlockPos(structMax.getX() + naturalizeRing, structMax.getY(), structMax.getZ() + naturalizeRing));
        startColumnPass(level,
                "naturalize (herbes, fleurs, champignons) " + structMin.getX() + "," + structMin.getZ(),
                columnsOf(structMin, structMax, naturalizeRing), 96,
                col -> naturalizeColumn(level, col[0], col[1], structMin, structMax, vegSeed, random, counts),
                () -> {
                    if (counts[0] > 0 || counts[1] > 0 || counts[2] > 0) {
                        deepluckyblock.util.DebugLog.structure(
                                "naturalize : {} herbes, {} fleurs, {} champignons places (zone {}x{})",
                                counts[0], counts[1], counts[2], zoneW, zoneH);
                    }
                    if (onDone != null) onDone.run();
                });
    }

    /** T77 : une colonne de naturalisation (corps inchange de l'ancienne boucle). */
    private static void naturalizeColumn(ServerLevel level, int x, int z,
                                         BlockPos structMin, BlockPos structMax, long vegSeed,
                                         RandomSource random, int[] counts) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        // Ne pas exclure l'emprise : apres le paste, les grass_blocks
        // presents dans la structure doivent eux aussi etre analyses.
        // Si un bloc de structure est au-dessus, above.isAir() empeche
        // automatiquement de poser une plante dessus.
        int surfaceY = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockState surface = level.getBlockState(mut.set(x, surfaceY - 1, z));

        // Couronne directement collee a la structure : les colonnes
        // exterieures commencent a 1 bloc du bord du build.
        int dxToBuild = x < structMin.getX() ? structMin.getX() - x
                : (x > structMax.getX() ? x - structMax.getX() : 0);
        int dzToBuild = z < structMin.getZ() ? structMin.getZ() - z
                : (z > structMax.getZ() ? z - structMax.getZ() : 0);
        boolean nearStructure = dxToBuild <= 12 && dzToBuild <= 12;

        // Si la surface est de l'herbe, on naturalize
        if (surface.is(Blocks.GRASS_BLOCK)) {
            BlockState above = level.getBlockState(mut.set(x, surfaceY, z));
            if (!above.isAir()) return;

            // FIX (demande utilisateur, "reduire de 50% la densite de
            // nature au sol et rendre le placement plus aleatoire") :
            // fenetre de couverture divisee par 2 (etait 5-70%, devient
            // 2-35%), et on rajoute un second jet aleatoire INDEPENDANT
            // (independent thinning) pour casser les motifs previsibles
            // colonne-par-colonne -- le resultat est plus clairsemee ET
            // moins "en bloc" que l'ancien calcul.
            int coverageChance = 2 + random.nextInt(34); // ~ moitie de l'ancien 5-70
            if (random.nextInt(100) >= coverageChance) return;
            if (random.nextInt(100) >= 50) return; // thinning independant supplementaire (-50% de plus)

            // FIX (rapporte en jeu : rendu "trop parfait, pas assez
            // naturel", delimitations visibles) : un tirage purement
            // aleatoire par colonne donne une densite UNIFORME sur toute
            // la zone -- l'oeil lit cette uniformite comme artificielle,
            // et la bordure de la zone traitee devient une ligne nette
            // par rapport au terrain vanilla alentour.
            //
            // Dans la nature la vegetation pousse par TOUFFES. On module
            // donc la densite par un bruit coherent basse frequence :
            // clairieres et zones denses alternent de facon organique, et
            // la frontiere avec le terrain non traite se dissout.
            double veg = valueNoise2D(vegSeed, x * 0.045, z * 0.045);
            if (veg < -0.15 && random.nextInt(100) < 70) return; // clairiere
            if (veg > 0.35 && random.nextInt(100) < 25) {
                // touffe dense : on laisse passer (pas de thinning)
            } else if (random.nextInt(100) < 18) {
                return;
            }

            int r = random.nextInt(100);
            int grassChance = nearStructure ? 58 : 50;
            int fernChance = nearStructure ? 76 : 72;
            int mushroomChance = fernChance + 1; // ~1/30 des tuiles vegetalisees -> voir MUSHROOM_ODDS ci-dessous
            if (r < grassChance) {
                // FIX (rapporte en jeu : "la naturalisation pose un
                // grass_block plein au lieu de la petite touffe
                // d'herbe deco") : Blocks.GRASS_BLOCK est le bloc de
                // TERRE herbeuse pleine (le sol), PAS une plante -- la
                // petite deco vanilla est Blocks.SHORT_GRASS (nom
                // 1.20.3+, ex-"grass"). L'ancien code posait donc un
                // second bloc de terre solide flottant au-dessus du
                // sol au lieu d'un brin d'herbe traversable.
                int grassH = random.nextInt(2);
                BlockState grass = grassH == 0 ? Blocks.SHORT_GRASS.defaultBlockState() : Blocks.TALL_GRASS.defaultBlockState();
                level.setBlock(mut, grass, 2);
                if (grassH == 1) level.setBlock(mut.set(x, surfaceY + 1, z), Blocks.TALL_GRASS.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
                counts[0]++;
            } else if (r < fernChance) {
                level.setBlock(mut, Blocks.FERN.defaultBlockState(), 2);
            } else if (r < mushroomChance) {
                // Pool de champignons (vanilla + mods, voir mushroomPool()) :
                // ~1/30 de "champignon pousse" (bloc geant, cap+stem)
                // parmi les tuiles qui tombent dans cette tranche, le
                // reste = petit champignon simple (brown/red/mod).
                Block small = pickWeighted(mushroomPool(), random);
                if (small != null) {
                    if (random.nextInt(30) == 0) {
                        placeGrownMushroom(level, mut, x, surfaceY, z, small, random);
                    } else {
                        level.setBlock(mut, small.defaultBlockState(), 2);
                    }
                    counts[2]++;
                }
            } else {
                // Pool de fleurs (vanilla 1.21.1 complete + mods, ponderee
                // par rarete realiste, voir flowerPool()) : jamais d'eau
                // (aucune fleur/plante n'implique de placer de fluide).
                Block flower = pickWeighted(flowerPool(), random);
                if (flower != null) {
                    level.setBlock(mut, flower.defaultBlockState(), 2);
                    counts[1]++;
                }
            }
        }
    }

    // =========================================================================
    // POOLS DE FLEURS / CHAMPIGNONS -- ponderees par rarete, compatibles mods
    // =========================================================================
    //
    // FIX (demande utilisateur) :
    //  - TOUTES les fleurs vanilla 1.21.1 dans la pool, avec une pondperation de
    //    rarete réaliste (dandelion/poppy tres communes, torchflower tres rare
    //    -- le torchflower ne pousse meme pas naturellement en 1.21.1, donc son
    //    poids est volontairement minuscule ici : simple clin d'oeil de rarete).
    //  - Pool de champignons avec un taux de "champignon pousse" (=huge
    //    mushroom, cap+stem) d'environ 1/30 (voir placeGrownMushroom + le
    //    random.nextInt(30) ci-dessus).
    //  - Compatible mods : toute fleur/champignon appartenant aux tags
    //    #minecraft:flowers / #minecraft:small_flowers ou dont le block
    //    registre est un MushroomBlock, provenant de N'IMPORTE QUEL namespace
    //    (pas seulement "minecraft"), est automatiquement integree -- un autre
    //    mod qui enregistre ses propres fleurs/champignons avec ces tags
    //    apparait donc dans la pool sans code specifique.
    //  - Jamais d'eau : ni les fleurs ni les champignons ne sont des blocs de
    //    fluide, et scanFlowers()/scanMushrooms() filtrent explicitement tout
    //    bloc dont le FluidState par defaut n'est pas vide (garde-fou meme si
    //    un mod exotique venait a taguer un bloc "flowers" de façon absurde).

    private record WeightedBlock(Block block, int weight) {}

    private static List<WeightedBlock> FLOWER_POOL;
    private static List<WeightedBlock> MUSHROOM_POOL;

    /** Poids de rarete vanilla (plus haut = plus commun). Absent de la map = poids par defaut (mods). */
    private static final Map<ResourceLocation, Integer> VANILLA_FLOWER_WEIGHTS = Map.ofEntries(
            Map.entry(ResourceLocation.withDefaultNamespace("dandelion"), 100),
            Map.entry(ResourceLocation.withDefaultNamespace("poppy"), 100),
            Map.entry(ResourceLocation.withDefaultNamespace("azure_bluet"), 40),
            Map.entry(ResourceLocation.withDefaultNamespace("oxeye_daisy"), 40),
            Map.entry(ResourceLocation.withDefaultNamespace("cornflower"), 35),
            Map.entry(ResourceLocation.withDefaultNamespace("blue_orchid"), 20),
            Map.entry(ResourceLocation.withDefaultNamespace("allium"), 20),
            Map.entry(ResourceLocation.withDefaultNamespace("lily_of_the_valley"), 18),
            Map.entry(ResourceLocation.withDefaultNamespace("red_tulip"), 25),
            Map.entry(ResourceLocation.withDefaultNamespace("orange_tulip"), 25),
            Map.entry(ResourceLocation.withDefaultNamespace("white_tulip"), 25),
            Map.entry(ResourceLocation.withDefaultNamespace("pink_tulip"), 25),
            Map.entry(ResourceLocation.withDefaultNamespace("wither_rose"), 1),
            Map.entry(ResourceLocation.withDefaultNamespace("torchflower"), 1),
            Map.entry(ResourceLocation.withDefaultNamespace("closed_eyeblossom"), 3),
            Map.entry(ResourceLocation.withDefaultNamespace("open_eyeblossom"), 3)
    );
    /** Poids par defaut pour toute fleur non listee ci-dessus (mods, ou vanilla oubliee) : rarete moyenne. */
    private static final int DEFAULT_MOD_FLOWER_WEIGHT = 15;
    private static final int DEFAULT_MOD_MUSHROOM_WEIGHT = 20;

    private static List<WeightedBlock> flowerPool() {
        if (FLOWER_POOL == null) FLOWER_POOL = scanFlowers();
        return FLOWER_POOL;
    }

    private static List<WeightedBlock> mushroomPool() {
        if (MUSHROOM_POOL == null) MUSHROOM_POOL = scanMushrooms();
        return MUSHROOM_POOL;
    }

    /**
     * Membres de #minecraft:flowers qui ne sont PAS des fleurs de surface
     * Overworld et ne doivent donc jamais etre semes par naturalize().
     * Voir le commentaire dans scanFlowers().
     */
    private static final Set<String> NON_OVERWORLD_FLOWERS = Set.of(
            "chorus_flower",        // End -- exige de l'end_stone, etat invalide sur l'herbe
            "chorus_plant",
            "wither_rose",          // inflige Wither au contact
            "closed_eyeblossom",    // Pale Garden / Nether
            "open_eyeblossom",
            "crimson_fungus",       // Nether
            "warped_fungus",
            "crimson_roots",
            "warped_roots",
            "nether_sprouts",
            "weeping_vines",
            "twisting_vines");

    private static List<WeightedBlock> scanFlowers() {
        List<WeightedBlock> out = new ArrayList<>();
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            BlockState def = block.defaultBlockState();
            // #flowers couvre small + tall + torchflower/wither_rose/eyeblossoms,
            // et est automatiquement etendu par tout mod qui y ajoute ses blocs.
            if (!def.is(net.minecraft.tags.BlockTags.FLOWERS)) continue;
            if (!def.getFluidState().isEmpty()) continue; // garde-fou "jamais d'eau"
            // On ecarte les variantes "haute" (2 blocs, DoublePlantBlock) pour
            // rester coherent avec le reste de naturalize() qui ne pose QUE
            // des plantes 1-bloc sur l'herbe (sunflower/lilac/rose_bush/peony
            // necessiteraient une gestion HALF dediee, hors scope de ce fix).
            if (def.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock) continue;
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) continue;
            // FIX (demande utilisateur) : spore_blossom fait partie du tag vanilla
            // #minecraft:flowers (depuis 1.20.2) mais n'est PAS une plante de sol -
            // c'est le seul membre du tag qui doit obligatoirement etre accroche
            // SOUS un bloc plafond (comme une stalactite vegetale). Pose au sol
            // (aucun plafond au-dessus dans naturalize()), il est visuellement/
            // logiquement invalide et genere en plus des particules constantes
            // (spores) non voulues pour du simple decor au sol. Exclu explicitement.
            if (id.equals(ResourceLocation.withDefaultNamespace("spore_blossom"))) continue;

            // FIX (rapporte en jeu : « je vois des chorus flower qui n'ont rien
            // a foutre la »).
            //
            // CAUSE : ce scan acceptait TOUT #minecraft:flowers. Or ce tag ne
            // contient pas que des fleurs de prairie : il inclut
            // chorus_flower (End), wither_rose (mob hostile), et les
            // eyeblossoms (Nether/Pale Garden). Posees sur l'herbe d'une foret
            // en Overworld, ces plantes sont absurdes -- et la chorus_flower,
            // qui ne peut pousser que sur de l'end_stone, se retrouve en plus
            // dans un etat invalide.
            //
            // CORRECTIF : liste noire explicite des membres du tag qui ne sont
            // pas des fleurs de surface Overworld. Les mods qui ajoutent de
            // vraies fleurs au tag restent acceptes (comportement voulu).
            if (NON_OVERWORLD_FLOWERS.contains(id.getPath())) continue;

            int weight = VANILLA_FLOWER_WEIGHTS.getOrDefault(id,
                    id.getNamespace().equals("minecraft") ? 10 : DEFAULT_MOD_FLOWER_WEIGHT);
            out.add(new WeightedBlock(block, weight));
        }
        if (out.isEmpty()) out.add(new WeightedBlock(Blocks.DANDELION, 1)); // filet de securite
        return out;
    }

    private static List<WeightedBlock> scanMushrooms() {
        List<WeightedBlock> out = new ArrayList<>();
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            BlockState def = block.defaultBlockState();
            if (!(block instanceof net.minecraft.world.level.block.MushroomBlock)) continue;
            if (!def.getFluidState().isEmpty()) continue; // garde-fou "jamais d'eau"
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) continue;
            int weight = id.getNamespace().equals("minecraft") ? 50 : DEFAULT_MOD_MUSHROOM_WEIGHT;
            out.add(new WeightedBlock(block, weight));
        }
        if (out.isEmpty()) out.add(new WeightedBlock(Blocks.BROWN_MUSHROOM, 1)); // filet de securite
        return out;
    }

    private static Block pickWeighted(List<WeightedBlock> pool, RandomSource random) {
        if (pool.isEmpty()) return null;
        int total = 0;
        for (WeightedBlock wb : pool) total += Math.max(1, wb.weight());
        if (total <= 0) return pool.get(0).block();
        int roll = random.nextInt(total);
        int acc = 0;
        for (WeightedBlock wb : pool) {
            acc += Math.max(1, wb.weight());
            if (roll < acc) return wb.block();
        }
        return pool.get(pool.size() - 1).block();
    }

    /**
     * Place un "champignon pousse" (huge mushroom simplifie : dome de blocs
     * champignon + stem central) sans dependre du systeme de feature de
     * worldgen complet (qui exige un WorldGenLevel/ChunkGenerator non
     * disponibles ici) -- reproduit une silhouette geante compacte et
     * toujours valide, quel que soit le champignon (vanilla ou mod).
     */
    private static void placeGrownMushroom(ServerLevel level, BlockPos.MutableBlockPos mut, int x, int y, int z, Block smallMushroom, RandomSource random) {
        Block cap = smallMushroom == Blocks.RED_MUSHROOM ? Blocks.RED_MUSHROOM_BLOCK
                : smallMushroom == Blocks.BROWN_MUSHROOM ? Blocks.BROWN_MUSHROOM_BLOCK
                : null;
        if (cap == null) {
            // Champignon d'un autre mod sans equivalent "geant" connu : on se
            // contente du petit champignon (comportement sur, jamais d'echec).
            level.setBlock(mut.set(x, y, z), smallMushroom.defaultBlockState(), 2);
            return;
        }
        int height = 4 + random.nextInt(3); // 4-6 blocs de tige
        for (int dy = 0; dy < height; dy++) {
            level.setBlock(mut.set(x, y + dy, z), Blocks.MUSHROOM_STEM.defaultBlockState(), 2);
        }
        int topY = y + height;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockState above = level.getBlockState(mut.set(x + dx, topY, z + dz));
                if (!above.isAir()) continue;
                level.setBlock(mut, cap.defaultBlockState(), 2);
            }
        }
    }

    // =========================================================================
    // HELPERS : types de blocs
    // =========================================================================

    private static final Set<Block> FALLBACK_LOG_BLOCKS = new HashSet<>();
    private static final Set<Block> FALLBACK_LEAF_BLOCKS = new HashSet<>();
    private static boolean FALLBACK_BLOCKS_READY = false;

    /** Initialise une seule fois les blocs moddes mal tagues. */
    private static void initFallbackTreeBlocks() {
        if (FALLBACK_BLOCKS_READY) return;
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) continue;
            String path = id.getPath().toLowerCase(Locale.ROOT);
            if (path.equals("log") || path.equals("wood") || path.endsWith("_log")
                    || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae")) {
                FALLBACK_LOG_BLOCKS.add(block);
            }
            if (path.equals("leaves") || path.equals("leaf") || path.endsWith("_leaves")
                    || path.endsWith("_leaf")) {
                FALLBACK_LEAF_BLOCKS.add(block);
            }
        }
        FALLBACK_BLOCKS_READY = true;
    }

    private static boolean isLog(BlockState s) {
        if (s.is(net.minecraft.tags.BlockTags.LOGS)) return true;
        initFallbackTreeBlocks();
        return FALLBACK_LOG_BLOCKS.contains(s.getBlock());
    }

    private static boolean isLeaf(BlockState s) {
        if (s.is(net.minecraft.tags.BlockTags.LEAVES)) return true;
        initFallbackTreeBlocks();
        return FALLBACK_LEAF_BLOCKS.contains(s.getBlock());
    }

    // Decors de surface (potentiellement volants apres le smooth) : feuilles,
    // neige, herbe, fougeres, vignes, etc.
    // FIX (rapporte en jeu : "je vois des grass qui volent parfois, des
    // petites grass et des grandes, quelques fleurs -- je parle pas de grass
    // block, mais bien de l'herbe la plante") : isDecoration() -- utilisee
    // par cleanupZone()/isFloatingFoliage() pour repartir la vegetation
    // laissee en l'air par le smooth -- listait Blocks.TALL_GRASS (l'ancien
    // nom 1.20.x du double-bloc "grande herbe") mais PAS
    // Blocks.SHORT_GRASS (le nom 1.20.3+ de la PETITE touffe d'herbe posee
    // par naturalize(), voir commentaire "grassH==0" plus haut), ni AUCUNE
    // fleur (Blocks.DANDELION, POPPY, etc., posees via flowerPool()/
    // BlockTags.FLOWERS), ni les champignons (mushroomPool()) : ces types de
    // blocs precis pouvaient donc flotter indefiniment apres un smooth qui
    // retire le support sous eux, jamais nettoyes -- exactement les 3
    // symptomes rapportes (petite herbe, grande herbe, fleurs qui flottent).
    // Detection desormais generique via les TAGS vanilla (couvre aussi les
    // ajouts de mods) plutot qu'une liste figee de blocs.
    private static boolean isDecoration(BlockState s) {
        return isLeaf(s) || s.is(Blocks.SNOW) || s.is(Blocks.GRASS_BLOCK)
            || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS)
            || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN) || s.is(Blocks.DEAD_BUSH)
            || s.is(Blocks.VINE) || s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS)
            || s.is(net.minecraft.tags.BlockTags.FLOWERS)
            || s.getBlock() instanceof net.minecraft.world.level.block.MushroomBlock
            || s.getBlock() instanceof net.minecraft.world.level.block.BushBlock;
    }

    // Decors de surface a retirer AVANT le smooth. BushBlock couvre herbe, fleurs,
    // fougeres, pousses, etc. On ajoute feuilles, neige, vignes, ET les logs
    // (troncs d'arbres) sinon il reste des piliers de wood.
    /**
     * Tout ce qui doit disparaitre avant un remodelage de terrain.
     *
     * FIX (rapporte en jeu : « si il y avait des arbres tres hauts, toi tu
     * clear que jusqu'a un certain point, ce qui cause des bouts d'arbres, des
     * leaves en tout genre de voler [...] faut vraiment inclure tout : toutes
     * les leaves, tous les logs, woods etc, plantes etc, semi bloc, bloc non
     * plein etc MODDES INCLUS »).
     *
     * L'ancienne liste enumerait des blocs un par un : elle ratait les bois
     * ecorces, les champignons geants, les plantes aquatiques, les corails,
     * les nouveaux blocs 1.20+ et surtout TOUT ce qui vient d'un mod.
     *
     * On raisonne desormais par TAGS et par TYPES de bloc, ce qui couvre
     * automatiquement le contenu modde qui declare correctement ses blocs.
     */
    /** T23 : vrai pour tout decor NATUREL (plante, feuille, liane, bambou...). */
    public static boolean isVegetation(BlockState s) {
        return isSurfaceDecor(s);
    }

    private static boolean isSurfaceDecor(BlockState s) {
        if (s.isAir()) return false;
        // --- par type de classe : couvre la quasi-totalite des plantes,
        //     y compris moddees, sans avoir a les nommer ---
        var b = s.getBlock();
        if (b instanceof net.minecraft.world.level.block.BushBlock          // fleurs, pousses, cultures
         || b instanceof net.minecraft.world.level.block.DoublePlantBlock   // plantes 2 blocs
         || b instanceof net.minecraft.world.level.block.VineBlock
         || b instanceof net.minecraft.world.level.block.GrowingPlantBlock  // lianes, kelp, bambou
         || b instanceof net.minecraft.world.level.block.MushroomBlock
         || b instanceof net.minecraft.world.level.block.HugeMushroomBlock
         || b instanceof net.minecraft.world.level.block.LeavesBlock
         || b instanceof net.minecraft.world.level.block.SnowLayerBlock
         || b instanceof net.minecraft.world.level.block.BaseCoralPlantTypeBlock
         || b instanceof net.minecraft.world.level.block.BaseCoralFanBlock
         || b instanceof net.minecraft.world.level.block.SaplingBlock) return true;
        // --- par tags : bois/feuilles de tout mod correctement declare ---
        if (s.is(net.minecraft.tags.BlockTags.LOGS)
         || s.is(net.minecraft.tags.BlockTags.LEAVES)
         || s.is(net.minecraft.tags.BlockTags.SAPLINGS)
         || s.is(net.minecraft.tags.BlockTags.FLOWERS)
         || s.is(net.minecraft.tags.BlockTags.CROPS)
         || s.is(net.minecraft.tags.BlockTags.CAVE_VINES)
         || s.is(net.minecraft.tags.BlockTags.CORAL_BLOCKS)
         || s.is(net.minecraft.tags.BlockTags.CORALS)
         || s.is(net.minecraft.tags.BlockTags.WART_BLOCKS)
         || s.is(net.minecraft.tags.BlockTags.REPLACEABLE_BY_TREES)) return true;
        // --- cas nommes restants (blocs non couverts par les categories) ---
        if (isLog(s) || isLeaf(s)) return true;
        return s.is(Blocks.SNOW) || s.is(Blocks.VINE)
            || s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS)
            || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.BAMBOO) || s.is(Blocks.CACTUS)
            || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.LILY_PAD)
            || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON)
            || s.is(Blocks.BEE_NEST) || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.PINK_PETALS)
            || s.is(Blocks.HANGING_ROOTS) || s.is(Blocks.BIG_DRIPLEAF) || s.is(Blocks.SMALL_DRIPLEAF)
            || s.is(Blocks.SCULK_VEIN) || s.is(Blocks.GLOW_LICHEN);
    }

    private static String treeTypeOf(BlockState logState) {
        Block b = logState.getBlock();
        if (b == Blocks.OAK_LOG) return "oak";
        if (b == Blocks.BIRCH_LOG) return "birch";
        if (b == Blocks.SPRUCE_LOG) return "spruce";
        if (b == Blocks.JUNGLE_LOG) return "jungle";
        if (b == Blocks.ACACIA_LOG) return "acacia";
        if (b == Blocks.DARK_OAK_LOG) return "dark_oak";
        if (b == Blocks.MANGROVE_LOG) return "mangrove";
        if (b == Blocks.CHERRY_LOG) return "cherry";
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
        // Pour un arbre modde, conserver l'ID exact du tronc. Il servira a
        // retrouver automatiquement le sapling de la meme famille.
        return key != null ? key.toString() : "unknown";
    }

    // ==================================================================
    // T38 : /fixwater + /fixlava REIMPLEMENTES (eau ET lave)
    // ==================================================================
    /**
     * Rayon demande par le dev : « tu dois absolument faire un /fixwater 1000 ».
     * Les chunks NON charges dans ce rayon sont simplement ignores (on ne
     * declenche aucune generation synchrone : c'est la cause du gel de 60 s du
     * run t33b) : la portee reelle est donc le voisinage deja en memoire de la
     * structure, ce qui correspond exactement a la zone que le joueur voit.
     */
    public static final int FIXLIQ_RADIUS = 1000;

    /** Profondeur maximale exploree sous la surface d'une colonne de liquide. */
    private static final int FIXLIQ_SCAN_DEPTH = 40;

    /** Creux maximal (en blocs) comble par la propagation du niveau d'eau. */
    private static final int FIXLIQ_MAX_GAP = 12;

    /** Chunks traites par tick a l'etape 1 (conversion flowing -> source). */
    private static final int FIXLIQ_CHUNKS_PER_TICK = 24;

    /** Plafonds de volume : on ne noie pas la carte si le rayon est enorme. */
    // Water is bounded spatially per job, rather than cut off at an arbitrary volume.
    private static final int FIXLIQ_MAX_LAVA_FILL = 4_000;

    /** Entrees de propagation traitees au maximum par tick (garde-fou memoire). */
    private static final int FIXLIQ_QUEUE_PER_TICK = 4_000;
    private static final int FIXLIQ_QUEUE_MAX = 400_000;

    /** Budget de temps par tick (aucun tick ne doit depasser 40 ms ici). */
    private static final long FIXLIQ_SLICE_MS = 40;

    /** Colonne (x,z) de liquide -> Y du plus haut bloc de liquide de la colonne. */
    private static final java.util.Map<Long, Integer> LIQ_WATER = new java.util.HashMap<>();
    private static final java.util.Map<Long, Integer> LIQ_LAVA = new java.util.HashMap<>();
    /** File de propagation du niveau (cles = BlockPos.asLong(x, niveau, z)). */
    private static final java.util.ArrayDeque<Long> LIQ_QUEUE = new java.util.ArrayDeque<>();

    private static final java.util.concurrent.atomic.AtomicInteger LIQ_SRC =
            new java.util.concurrent.atomic.AtomicInteger();   // blocs flowing -> source
    private static final java.util.concurrent.atomic.AtomicInteger LIQ_FILL =
            new java.util.concurrent.atomic.AtomicInteger();   // blocs d'eau remplis
    private static final java.util.concurrent.atomic.AtomicInteger LIQ_LAVA_FILL =
            new java.util.concurrent.atomic.AtomicInteger();   // blocs de lave remplis
    private static final java.util.concurrent.atomic.AtomicInteger LIQ_FILL_COLS =
            new java.util.concurrent.atomic.AtomicInteger();   // colonnes remplies

    /**
     * T38 -- equivalent de {@code /fixwater <rayon>} + {@code /fixlava <rayon>},
     * reimplemente en interne (aucune dependance, aucun plugin).
     *
     * <p>Deux etapes, decoupees en tranches de {@link #FIXLIQ_SLICE_MS} ms :
     * <ol>
     *   <li><b>Conversion</b> : dans tous les chunks CHARGES du rayon, tout bloc
     *       d'eau ou de lave a l'etat <i>flowing</i> est remplace par un bloc
     *       <i>source</i> stationnaire. C'est exactement le comportement decrit
     *       par la documentation WorldEdit (« replace flowing versions of lava
     *       and water with stationary ones »).</li>
     *   <li><b>Remise a niveau</b> : le niveau du plus haut bloc d'eau (et de
     *       lave) de chaque colonne est memorise, puis propage en largeur : les
     *       cuvettes vides reliees au plan d'eau sont remplies jusqu'a ce
     *       niveau (« fill any pit ... to the level of the highest source
     *       block »). C'est la reponse a « toute la nouvelle zone vide prevue a
     *       la continuite du lac ... afin de re remplir ».</li>
     * </ol>
     */
    public static void fixLiquidsPass(ServerLevel level, BlockPos min, BlockPos max, Runnable onDone) {
        fixLiquidsPass(level, min, max, false, onDone);
    }

    /**
     * Requires all edited terrain and its cleanup border to be pinned beforehand.
     * Seed from that loaded region, then follow water into the unchanged repair halo.
     * This avoids generating unrelated dry land without shortening the repair bounds.
     */
    static void fixLiquidsPassOnDemand(ServerLevel level, BlockPos min, BlockPos max, Runnable onDone) {
        fixLiquidsPass(level, min, max, true, onDone);
    }

    private static void fixLiquidsPass(ServerLevel level, BlockPos min, BlockPos max,
                                       boolean onDemand, Runnable onDone) {
        final String label = "fixLiquids (/fixwater + /fixlava)";
        if (!RUNNING_PASSES.add(label)) {
            deepluckyblock.util.DebugLog.structure("{} : deja en cours, doublon ignore", label);
            if (onDone != null) onDone.run();
            return;
        }
        LIQ_WATER.clear(); LIQ_LAVA.clear(); LIQ_QUEUE.clear();
        LIQ_SRC.set(0); LIQ_FILL.set(0); LIQ_LAVA_FILL.set(0); LIQ_FILL_COLS.set(0);
        final int cx = (min.getX() + max.getX()) / 2, cz = (min.getZ() + max.getZ()) / 2;
        // Repair the edited terrain plus one chunk of natural shoreline. Never flood
        // arbitrary loaded chunks elsewhere in the old 1000-block search radius.
        int margin = Math.max(FIXLIQ_FORCE_MARGIN, terrainRing() + 16);
        int x0 = Math.max(cx - FIXLIQ_RADIUS, min.getX() - margin);
        int x1 = Math.min(cx + FIXLIQ_RADIUS, max.getX() + margin);
        int z0 = Math.max(cz - FIXLIQ_RADIUS, min.getZ() - margin);
        int z1 = Math.min(cz + FIXLIQ_RADIUS, max.getZ() + margin);
        int fx0 = x0 >> 4, fx1 = x1 >> 4, fz0 = z0 >> 4, fz1 = z1 >> 4;
        List<int[]> chunks = new ArrayList<>();
        for (int ccx = fx0; ccx <= fx1; ccx++)
            for (int ccz = fz0; ccz <= fz1; ccz++) {
                if (onDemand && level.getChunkSource().getChunkNow(ccx, ccz) == null) continue;
                chunks.add(new int[]{ccx, ccz});
                if (onDemand) deepluckyblock.util.ChunkKeeper.trackAdditionalChunk(level, ccx, ccz);
            }
        if (!onDemand) deepluckyblock.util.ChunkKeeper.track(level,
                new BlockPos(x0, min.getY(), z0), new BlockPos(x1, max.getY(), z1));
        deepluckyblock.util.DebugLog.structure(
                "fixLiquids: bounded repair {}..{} / {}..{}, {} chunks, footprint excluded",
                x0, x1, z0, z1, chunks.size());
        LiquidJob job = new LiquidJob(level, cx, cz, chunks, label, onDone,
                fx0, fx1, fz0, fz1, min, max, x0, x1, z0, z1, onDemand);
        TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, job::slice);
        step("fixLiquids : passe /fixwater + /fixlava planifiee (rayon " + FIXLIQ_RADIUS + ")");
    }

    /** T75 : nombre de tentatives de chargement des chunks voisins absents. */
    private static final int FIXLIQ_PENDING_ROUNDS = 6;

    /**
     * T75 : marge (en blocs) autour de l'emprise dans laquelle les chunks absents
     * sont REELLEMENT charges a la demande.
     *
     * <p>Test in-game du 23/09 : forcer le chargement de tout le rayon de 1000 blocs
     * (15 876 chunks) generait un monde entier d'un coup et faisait grimper des ticks
     * a 1809 ms. Les « murs d'eau » signales sont sur la FRONTIERE du terrain : trois
     * chunks de marge suffisent a les traiter, le reste du rayon reste opportuniste.
     */
    private static final int FIXLIQ_FORCE_MARGIN = 48;

    /** Etat de la passe /fixwater + /fixlava (une tranche par tick). */
    private static final class LiquidJob {
        final ServerLevel level;
        final int cx, cz;
        final BlockPos structureMin, structureMax;
        final int x0, x1, z0, z1, waterFillLimit;
        int capped = 0;
        final int fx0, fx1, fz0, fz1;    // T75 : anneau proche (chargement a la demande)
        final List<int[]> chunks;
        final String label;
        final Runnable onDone;
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int idx = 0, phase = 0, loaded = 0, refilled = 0, dropped = 0;
        boolean repairChunksPinned;
        final boolean onDemand;
        long lastLoadReport;
        // T75 : chunks absents (demandes) / jamais chargeables (abandonnes), et
        // points de propagation dont un voisin n'etait pas encore en memoire.
        final List<int[]> pending = new ArrayList<>();
        final java.util.ArrayDeque<Long> retry = new java.util.ArrayDeque<>();
        int scanRound = 0, refillRound = 0, requested = 0, demanded = 0, unloaded = 0, stalled = 0;
        int skippedFar = 0;   // T75 : chunks hors anneau proche, non charges et non forces
        final long started = System.currentTimeMillis();

        LiquidJob(ServerLevel l, int cx, int cz, List<int[]> chunks, String label, Runnable onDone,
                  int fx0, int fx1, int fz0, int fz1, BlockPos min, BlockPos max,
                  int x0, int x1, int z0, int z1, boolean onDemand) {
            this.onDemand = onDemand;
            this.level = l; this.cx = cx; this.cz = cz; this.chunks = chunks; this.label = label; this.onDone = onDone;
            this.fx0 = fx0; this.fx1 = fx1; this.fz0 = fz0; this.fz1 = fz1;
            this.structureMin = min; this.structureMax = max;
            this.x0 = x0; this.x1 = x1; this.z0 = z0; this.z1 = z1;
            this.waterFillLimit = (int) Math.min(Integer.MAX_VALUE,
                    (long) (x1 - x0 + 1) * (z1 - z0 + 1) * FIXLIQ_MAX_GAP);
        }

        private boolean canRepair(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1
                    && !(x >= structureMin.getX() && x <= structureMax.getX()
                    && z >= structureMin.getZ() && z <= structureMax.getZ())
                    && !isProtected(x, z);
        }

        /** T75 : ce chunk est-il dans l'anneau proche (donc a charger si absent) ? */
        private boolean inForceRing(int ccx, int ccz) {
            return ccx >= fx0 && ccx <= fx1 && ccz >= fz0 && ccz <= fz1;
        }

        void slice() {
            deepluckyblock.util.ChunkKeeper.keep(level);
            deepluckyblock.util.TerrainChain.heartbeat();
            if (!repairChunksPinned || !deepluckyblock.util.ChunkKeeper.zoneComplete(level)) {
                // Real async loading can outlive the old six retry rounds. Do not scan
                // or flood-fill a partially loaded basin: retain the entire bounded zone.
                if (!deepluckyblock.util.ChunkKeeper.zoneComplete(level)) {
                    long now = System.currentTimeMillis();
                    if (now - lastLoadReport >= 5000) {
                        lastLoadReport = now;
                        deepluckyblock.util.DebugLog.structure("fixLiquids waiting for pinned repair chunks: {}",
                                deepluckyblock.util.ChunkKeeper.stats(level));
                    }
                    TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::slice);
                    return;
                }
                repairChunksPinned = true;
                deepluckyblock.util.SafeSurface.clearCache();
            }
            long t0 = System.currentTimeMillis();
            try {
                if (phase == 0) { sliceScan(t0); return; }
                sliceRefill(t0);
            } catch (Exception ex) {
                LOGGER.error("[DLB-FIXLIQ] echec de la passe fixLiquids", ex);
                finish();
            }
        }

        /** Etape 1 : flowing -> source, chunk par chunk (time-sliced). */
        private void sliceScan(long t0) {
            int n = 0;
            while (idx < chunks.size() && n < FIXLIQ_CHUNKS_PER_TICK
                    && System.currentTimeMillis() - t0 < FIXLIQ_SLICE_MS) {
                int[] c = chunks.get(idx++);
                if (level.getChunkSource().getChunkNow(c[0], c[1]) == null) {
                    // T75 : dans l'ANNEAU PROCHE, le chunk absent n'est plus ignore (c'est
                    // la que restaient les « murs d'eau » de la frontiere) : on le demande
                    // en tache de fond et on le retente. Au-dela, on ne force RIEN (un
                    // rayon de 1000 blocs ferait generer 15 876 chunks d'un coup).
                    if (!inForceRing(c[0], c[1])) { skippedFar++; continue; }
                    deepluckyblock.util.SafeSurface.request(level, c[0], c[1]);
                    if (scanRound == 0) demanded++;
                    if (scanRound < FIXLIQ_PENDING_ROUNDS) { pending.add(c); requested++; }
                    else unloaded++;
                    continue;
                }
                loaded++;
                for (int x = c[0] << 4; x < (c[0] << 4) + 16; x++)
                    for (int z = c[1] << 4; z < (c[1] << 4) + 16; z++)
                        scanColumn(x, z);
                n++;
            }
            if (idx < chunks.size()) {
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::slice);
                return;
            }
            if (!pending.isEmpty()) {
                // T75 : deuxieme passage sur les chunks demandes -- delai croissant pour
                // laisser la generation se faire (un chunk neuf n'arrive pas en 1 tick).
                scanRound++;
                deepluckyblock.util.DebugLog.structure(
                        "fixLiquids 1/2 : {} chunk(s) voisins absents demandes (tour {}/{}) -- nouveau passage dans {} ticks (T75)",
                        pending.size(), scanRound, FIXLIQ_PENDING_ROUNDS, 2 + scanRound * 4);
                chunks.addAll(new ArrayList<>(pending));
                pending.clear();
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 2 + scanRound * 4, this::slice);
                return;
            }
            deepluckyblock.util.DebugLog.structure(
                    "fixLiquids 1/2 : {} chunks charges ({} voisins charges a la demande, {} jamais chargeables) -- "
                            + "{} bloc(s) flowing -> SOURCE, {} colonne(s) d'eau et {} colonne(s) de lave memorisees ({} ms)",
                    loaded, demanded, unloaded + skippedFar,
                    LIQ_SRC.get(),
                    LIQ_WATER.size(), LIQ_LAVA.size(), System.currentTimeMillis() - started);
            phase = 1; idx = 0;
            seedRefill();
            TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::slice);
        }

        /** Une colonne : conversion + memorisation du niveau haut du liquide. */
        private void scanColumn(int x, int z) {
            if (!canRepair(x, z)) return;
            // SafeSurface returns the first free Y, not the top occupied Y.
            int surface = Math.min(level.getMaxBuildHeight(),
                    deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z));
            BlockState top = level.getBlockState(mut.set(x, surface - 1, z));
            var topFluid = top.getFluidState();
            boolean waterTop = topFluid.is(net.minecraft.tags.FluidTags.WATER);
            boolean lavaTop = topFluid.is(net.minecraft.tags.FluidTags.LAVA);
            // Colonne de terre / roche / vegetal : rien a faire (cout O(1)).
            if (!waterTop && !lavaTop) return;
            int depth = Math.min(FIXLIQ_SCAN_DEPTH, surface - level.getMinBuildHeight());
            int hiWater = Integer.MIN_VALUE, hiLava = Integer.MIN_VALUE;
            for (int k = 0; k < depth; k++) {
                int y = surface - 1 - k;
                BlockState s = level.getBlockState(mut.set(x, y, z));
                var fs = s.getFluidState();
                if (fs.is(net.minecraft.tags.FluidTags.WATER)) {
                    if (hiWater == Integer.MIN_VALUE) hiWater = y;
                    if (!fs.isSource()) {
                        level.setBlock(mut, Blocks.WATER.defaultBlockState(), FAST_FLAG);
                        LIQ_SRC.incrementAndGet();
                    }
                } else if (fs.is(net.minecraft.tags.FluidTags.LAVA)) {
                    if (hiLava == Integer.MIN_VALUE) hiLava = y;
                    if (!fs.isSource()) {
                        level.setBlock(mut, Blocks.LAVA.defaultBlockState(), FAST_FLAG);
                        LIQ_SRC.incrementAndGet();
                    }
                } else if (!s.isAir()) {
                    break;   // sol atteint : le reste de la colonne n'est plus du liquide
                }
            }
            if (hiWater != Integer.MIN_VALUE) LIQ_WATER.put(BlockPos.asLong(x, hiWater, z), hiWater);
            if (hiLava != Integer.MIN_VALUE) LIQ_LAVA.put(BlockPos.asLong(x, hiLava, z), hiLava);
        }

        private void seedRefill() {
            LIQ_QUEUE.clear();
            LIQ_QUEUE.addAll(LIQ_WATER.keySet());
            LIQ_QUEUE.addAll(LIQ_LAVA.keySet());
            deepluckyblock.util.DebugLog.structure(
                    "fixLiquids 2/2 : propagation du niveau d'eau/lave sur {} colonnes de depart", LIQ_QUEUE.size());
        }

        /** Etape 2 : propagation du niveau (BFS 4 directions, budget par tick). */
        private void sliceRefill(long t0) {
            int n = 0;
            while (!LIQ_QUEUE.isEmpty() && n < FIXLIQ_QUEUE_PER_TICK
                    && System.currentTimeMillis() - t0 < FIXLIQ_SLICE_MS) {
                long packed = LIQ_QUEUE.poll();
                int x = BlockPos.getX(packed), lvl = BlockPos.getY(packed), z = BlockPos.getZ(packed);
                boolean lava = LIQ_LAVA.containsKey(packed);
                refilled++; n++;
                if (LIQ_WATER.size() + LIQ_LAVA.size() > FIXLIQ_QUEUE_MAX) { dropped++; continue; }
                for (int d = 0; d < 4; d++) {
                    int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    long nk = BlockPos.asLong(nx, lvl, nz);
                    if (LIQ_WATER.containsKey(nk) || LIQ_LAVA.containsKey(nk)) continue;
                    if (!canRepair(nx, nz)) continue;
                    if (level.getChunkSource().getChunkNow(nx >> 4, nz >> 4) == null) {
                        // T75 : le voisin n'est pas en memoire. Dans l'anneau proche on le
                        // demande et on RETENTE ce point (avant, la propagation s'arretait net
                        // a la frontiere des chunks charges : c'est le « mur d'eau ») ; plus
                        // loin on ne force rien.
                        if (!inForceRing(nx >> 4, nz >> 4)) continue;
                        if (onDemand) {
                            // Keep the frontier sparse: no rectangular dry halo expansion.
                            // The next slice waits until these chunks are loaded AND pinned.
                            deepluckyblock.util.ChunkKeeper.trackAdditionalChunk(level, nx >> 4, nz >> 4);
                            retry.add(packed);
                        } else {
                            deepluckyblock.util.SafeSurface.request(level, nx >> 4, nz >> 4);
                            if (refillRound < FIXLIQ_PENDING_ROUNDS) retry.add(packed); else stalled++;
                        }
                        continue;
                    }
                    int top = topSolidAt(nx, nz, lvl, lvl - FIXLIQ_MAX_GAP, lava);
                    if (top == Integer.MIN_VALUE || top >= lvl) continue;    // pas de fond proche / deja plein
                    int need = 0;
                    int cap = lava ? FIXLIQ_MAX_LAVA_FILL : waterFillLimit;
                    BlockState floor = level.getBlockState(mut.set(nx, top, nz));
                    if (isSurfaceDecor(floor) || !floor.blocksMotion()) continue;   // fond naturel solide uniquement
                    boolean free = true;
                    for (int y = top + 1; y <= lvl && free; y++) {
                        BlockState cur = level.getBlockState(mut.set(nx, y, nz));
                        if (cur.isAir()) { need++; continue; }
                        if (cur.is(lava ? Blocks.LAVA : Blocks.WATER)) {
                            if (!cur.getFluidState().isSource()) need++;
                            continue;
                        }
                        free = false;                                            // bloc plein dans la colonne -> creux non vide
                    }
                    if (!free || need == 0) continue;
                    if ((lava ? LIQ_LAVA_FILL.get() : LIQ_FILL.get()) + need > cap) { capped++; continue; }
                    BlockState source = lava ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState();
                    for (int y = top + 1; y <= lvl; y++) {
                        mut.set(nx, y, nz);
                        if (!level.getBlockState(mut).equals(source)) level.setBlock(mut, source, FAST_FLAG);
                    }
                    if (lava) LIQ_LAVA_FILL.addAndGet(need); else LIQ_FILL.addAndGet(need);
                    LIQ_FILL_COLS.incrementAndGet();
                    if (lava) LIQ_LAVA.put(nk, lvl); else LIQ_WATER.put(nk, lvl);
                    LIQ_QUEUE.add(nk);
                }
            }
            if (!LIQ_QUEUE.isEmpty()) {
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::slice);
                return;
            }
            if (!retry.isEmpty()) {
                // T75 : tour supplementaire sur les points dont le voisin etait absent.
                refillRound++;
                deepluckyblock.util.DebugLog.structure(
                        "fixLiquids 2/2 : {} point(s) en attente d'un chunk voisin (tour {}/{}) -- nouveau passage dans {} ticks (T75)",
                        retry.size(), refillRound, FIXLIQ_PENDING_ROUNDS, 2 + refillRound * 4);
                LIQ_QUEUE.addAll(retry);
                retry.clear();
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 2 + refillRound * 4, this::slice);
                return;
            }
            finish();
        }

        /**
         * Y du plus haut bloc PLEIN de la colonne dans la fenetre [low, high].
         * Renvoie Integer.MIN_VALUE si la colonne est ouverte sur toute la
         * fenetre (creux trop profond : on ne remplit pas, ce serait noyer une
         * vallee entiere).
         */
        private int topSolidAt(int x, int z, int high, int low, boolean lava) {
            int y0 = Math.max(low, level.getMinBuildHeight());
            for (int y = Math.min(high, level.getMaxBuildHeight() - 1); y >= y0; y--) {
                BlockState s = level.getBlockState(mut.set(x, y, z));
                if (s.isAir()) continue;
                // A partially filled column is not a solid floor. Continue through
                // the same liquid, but never overwrite the other liquid or waterlogged blocks.
                if (s.is(lava ? Blocks.LAVA : Blocks.WATER)) {
                    if (y == high && s.getFluidState().isSource()) return high;
                    continue;
                }
                if (!s.getFluidState().isEmpty()) return high;
                if (s.blocksMotion()) return y;
            }
            return Integer.MIN_VALUE;
        }

        private void finish() {
            RUNNING_PASSES.remove(label);
            if (capped > 0 || dropped > 0 || unloaded > 0 || stalled > 0)
                LOGGER.warn("[DLB-FIXLIQ] incomplete repair: capped={}, queueLimit={}, unloaded={}, stalled={}",
                        capped, dropped, unloaded, stalled);
            if (stalled > 0)
                deepluckyblock.util.DebugLog.structure(
                        "fixLiquids : {} point(s) non traites (chunk voisin jamais charge) -- T75", stalled);
            deepluckyblock.util.DebugLog.structure(
                    "fixLiquids TERMINE ({} chunks voisins demandes, {} non chargeables) : {} bloc(s) flowing -> source, "
                            + "{} colonne(s) / {} bloc(s) d'eau remis a niveau, {} colonne(s) / {} bloc(s) de lave, {} ms",
                    requested, unloaded,
                    LIQ_SRC.get(), LIQ_FILL_COLS.get(), LIQ_FILL.get(),
                    LIQ_LAVA.size(), LIQ_LAVA_FILL.get(), System.currentTimeMillis() - started);
        LIQ_QUEUE.clear();
        retry.clear();
        pending.clear();
        if (onDone != null) onDone.run();
        }
    }

    // ==================================================================
    // T40 : HOLE FILLER (tunnels, failles, cavites)
    // ==================================================================
    /**
     * Profondeur maximale exploree sous la surface d'une colonne. Au-dela on
     * considere qu'on est dans du relief naturel profond (grotte geante) et on
     * ne rebouche PAS : « on ne comble pas une vallee ».
     */
    public static final int HOLEFILL_MAX_DEPTH = 64;

    /** Hauteur maximale d'une poche d'air rebouchee (au-dela = caverne). */
    private static final int HOLEFILL_MAX_RUN = 24;

    /** Volume total rebouche par structure (garde-fou travaux). */
    // T43 : 60 000 blocs etaient atteints des la premiere structure du run
    // t39_run1 (12 750 poches rebouchees, cap touche -> tunnels restants) : le
    // dev signale encore des tunnels, donc le plafond est porte a 250 000.
    private static final int HOLEFILL_MAX_BLOCKS = holeFillMax();

    /**
     * T71 : plafond de blocs rebouches, reglable par {@code -Ddlb.holeFillMax=N}.
     * Defaut porte de 250 000 a 400 000 : a everest le plafond etait ATTEINT
     * (250 000 pile) alors qu'il restait des tunnels -- exactement la plainte du
     * dev du 23/09. L'atteinte du plafond est desormais journalisee.
     */
    private static int holeFillMax() {
        try {
            int v = Integer.getInteger("dlb.holeFillMax", 400_000);
            return v < 10_000 ? 10_000 : v;
        } catch (Throwable ignored) {
            return 400_000;
        }
    }

    /** T71 : colonnes non analysees parce que le plafond de blocs etait atteint. */
    private static final java.util.concurrent.atomic.AtomicInteger HOLEFILL_CAPPED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Colonnes traitees par tranche (la passe est time-sliced). */
    private static final int HOLEFILL_COLS_PER_SLICE = 384;

    private static final java.util.concurrent.atomic.AtomicInteger HOLEFILL_BLOCKS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger HOLEFILL_HOLES =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * T40 -- rebouche les tunnels, galeries, failles et cavites souterraines de
     * l'emprise (et de son anneau) AVANT que le terrain ne soit declare fige.
     *
     * <p>Consigne dev (23/09) : « je vois encore des tunnels, des failles etc
     * dans mon terrain et qui n'ont pas ete re remplis ! ».
     */
    public static void sealUndergroundGaps(ServerLevel level, BlockPos min, BlockPos max, Runnable onDone) {
        HOLEFILL_BLOCKS.set(0);
        HOLEFILL_HOLES.set(0);
        HOLEFILL_CAPPED.set(0);
        List<int[]> cols = columnsOf(min, max, terrainRing());
        deepluckyblock.util.DebugLog.structure("hole filler : {} colonnes a analyser (profondeur max {} blocs)",
                cols.size(), HOLEFILL_MAX_DEPTH);
        startColumnPass(level, "hole filler (tunnels / failles / cavites)", cols, HOLEFILL_COLS_PER_SLICE,
                col -> sealColumn(level, col[0], col[1]), () -> {
                    deepluckyblock.util.DebugLog.structure("hole filler TERMINE : {} poche(s) rebouchee(s), {} bloc(s)"
                                    + (HOLEFILL_CAPPED.get() > 0 ? " -- PLAFOND ATTEINT : {} colonne(s) NON analysee(s), "
                                    + "augmente -Ddlb.holeFillMax (actuel {})" : ""),
                            HOLEFILL_CAPPED.get() > 0
                                    ? new Object[]{HOLEFILL_HOLES.get(), HOLEFILL_BLOCKS.get(), HOLEFILL_CAPPED.get(), HOLEFILL_MAX_BLOCKS}
                                    : new Object[]{HOLEFILL_HOLES.get(), HOLEFILL_BLOCKS.get()});
                    if (onDone != null) onDone.run();
                });
    }

    /** Une colonne du hole filler (voir {@link #sealUndergroundGaps}). */
    private static void sealColumn(ServerLevel level, int x, int z) {
        if (isProtected(x, z)) return;
        if (HOLEFILL_BLOCKS.get() >= HOLEFILL_MAX_BLOCKS) { HOLEFILL_CAPPED.incrementAndGet(); return; }
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        // T71 : le chunk est resolu UNE fois par colonne (au lieu d'un Level.getBlockState
        // par bloc) et sert aussi a lire les sections d'un coup.
        ChunkAccess chunk = deepluckyblock.util.SafeSurface.chunkFor(level, x >> 4, z >> 4);
        int top = Math.min(level.getMaxBuildHeight() - 2,
                deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z) + 1);
        int floorLimit = Math.max(level.getMinBuildHeight() + 1, top - HOLEFILL_MAX_DEPTH);
        boolean lastSolid = !readBlock(level, chunk, mut, x, top, z).isAir();
        int runTop = Integer.MIN_VALUE;     // Y du bloc d'air le plus haut de la poche en cours
        int y = top - 1;
        while (y > floorLimit) {
            LevelChunkSection sec = sectionAt(chunk, y);
            if (sec != null) {
                int low = sectionLow(y, floorLimit);
                if (sec.hasOnlyAir()) {
                    // 16 blocs d'air d'un coup : le plus haut ouvre la poche si le
                    // bloc juste au-dessus etait plein, les autres n'y changent rien.
                    if (runTop == Integer.MIN_VALUE && lastSolid) runTop = y;
                    lastSolid = false;
                    y = low - 1;
                    continue;
                }
                if (!sec.maybeHas(HAS_AIR)) {
                    // Aucun air dans la section : le premier bloc (le plus haut) est le
                    // plancher de la poche en cours ; les suivants sont du solide.
                    if (runTop != Integer.MIN_VALUE) {
                        BlockState floor = readBlock(level, chunk, mut, x, y, z);
                        fillPocket(level, mut, x, z, y + 1, runTop, y, floor);
                        runTop = Integer.MIN_VALUE;
                    }
                    lastSolid = true;
                    y = low - 1;
                    continue;
                }
            }
            // Section mixte : lecture bloc par bloc (chemin d'origine).
            BlockState s = readBlock(level, chunk, mut, x, y, z);
            if (s.isAir()) {
                if (runTop == Integer.MIN_VALUE && lastSolid) runTop = y;   // poche COUVERTE : plafond au-dessus
                lastSolid = false;
            } else {
                if (runTop != Integer.MIN_VALUE) {
                    // Poche [y+1 .. runTop], plafond = le bloc au-dessus de runTop,
                    // plancher = le bloc courant (y). On la rebouche.
                    fillPocket(level, mut, x, z, y + 1, runTop, y, s);
                    runTop = Integer.MIN_VALUE;
                }
                lastSolid = true;
            }
            y--;
        }
        // Une poche encore ouverte a la limite de profondeur n'est PAS rebouchee :
        // on ne sait pas ou elle s'arrete (gouffre / caverne geante).
    }

    /**
     * Rebouche une poche d'air bornee avec le materiau du PLANCHER (le sol reel
     * de la cavite), a defaut de la pierre -- c'est la regle des hole fillers :
     * « remplir avec les materiaux qui entourent le trou ».
     */
    private static void fillPocket(ServerLevel level, BlockPos.MutableBlockPos mut,
                                   int x, int z, int yLow, int yHigh, int floorY, BlockState floor) {
        int height = yHigh - yLow + 1;
        if (height <= 0 || height > HOLEFILL_MAX_RUN) return;
        if (HOLEFILL_BLOCKS.get() + height > HOLEFILL_MAX_BLOCKS) return;
        BlockState material = (floor.blocksMotion() && !isSurfaceDecor(floor) && !isLiquid(floor))
                ? floor : Blocks.STONE.defaultBlockState();
        for (int y = yLow; y <= yHigh; y++) {
            mut.set(x, y, z);
            if (!level.getBlockState(mut).isAir()) continue;
            level.setBlock(mut, material, FAST_FLAG);
            HOLEFILL_BLOCKS.incrementAndGet();
        }
        HOLEFILL_HOLES.incrementAndGet();
    }

    private static boolean isLiquid(BlockState s) {
        return s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                || s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    // ==================================================================
    // T41 : BALAYAGE ETENDU DES VEGETAUX ET BLOCS NATURELS FLOTTANTS
    // ==================================================================
    /** Hauteur scannee au-dessus de la surface (lianes en suspension). */
    private static final int SWEEP_UP = 48;
    /** Profondeur scannee sous la surface (restes accroches sous un surplomb). */
    private static final int SWEEP_DOWN = 10;
    /** Colonnes par tranche. */
    private static final int SWEEP_COLS_PER_SLICE = 384;

    private static final java.util.concurrent.atomic.AtomicInteger SWEPT_NAT =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * T41 -- supprime tout decor NATUREL qui ne tient plus a rien dans
     * l'emprise + son anneau complet.
     *
     * <p>Consigne dev (23/09) : « je vois encore des des lianes qui volent »,
     * « d'autres entites du meme genre flotter » : ce sont les restes accroches
     * a l'ANCIEN terrain (avant erasing/remodelage). Les blocs de construction
     * (planches, pierre taillee, verre...) et la roche dure naturelle (pierre,
     * granit, ardoise) ne sont jamais touches : un surplomb rocheux est un
     * relief legitime.
     */
    public static void sweepFloatingNaturalPass(ServerLevel level, BlockPos min, BlockPos max, Runnable onDone) {
        SWEPT_NAT.set(0);
        CLUSTER_TIMEOUTS.set(0);  // T69 : nouveau balayage, nouveaux amas
        SWEEP_ANCHORED.clear();   // T45 : nouveau balayage, nouveaux amas
        List<int[]> cols = columnsOf(min, max, terrainRing());
        startColumnPass(level, "balayage etendu des naturels flottants (lianes comprises)",
                cols, SWEEP_COLS_PER_SLICE, col -> sweepNaturalColumn(level, col[0], col[1]), () -> {
                    int to = CLUSTER_TIMEOUTS.get();
                    deepluckyblock.util.DebugLog.structure(
                            "balayage etendu TERMINE : {} bloc(s) naturel(s) flottant(s) retire(s) sur {} colonnes"
                                    + (to > 0 ? " ({} amas non verifies faute de temps -- CONSERVES, jamais effaces a l'aveugle)" : ""),
                            to > 0 ? new Object[]{SWEPT_NAT.get(), cols.size(), to}
                                   : new Object[]{SWEPT_NAT.get(), cols.size()});
                    if (onDone != null) onDone.run();
                });
    }

    /** Une colonne du balayage etendu (voir {@link #sweepFloatingNaturalPass}). */
    private static void sweepNaturalColumn(ServerLevel level, int x, int z) {
        if (isProtected(x, z)) return;
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int surf = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z);
        int hi = Math.min(level.getMaxBuildHeight() - 1, surf + SWEEP_UP);
        int lo = Math.max(level.getMinBuildHeight() + 1, surf - SWEEP_DOWN);
        boolean supported = true;      // on remonte DEPUIS LE SOL : rien n'est en l'air au depart
        // T71 : meme principe que le hole filler -- une section entierement vide ou
        // entierement depourvue de decor naturel se traite d'un coup (16 blocs), au
        // lieu de 16 lectures. Le balayage compte 147 000 colonnes a everest.
        ChunkAccess chunk = deepluckyblock.util.SafeSurface.chunkFor(level, x >> 4, z >> 4);
        int y = lo;
        while (y <= hi) {
            LevelChunkSection sec = sectionAt(chunk, y);
            if (sec != null) {
                int secHigh = Math.min(hi, (y & ~15) + 15);
                if (sec.hasOnlyAir()) {
                    // Que de l'air : rien n'est porte, et rien a retirer.
                    supported = false;
                    y = secHigh + 1;
                    continue;
                }
                if (!sec.maybeHas(SWEEP_INTERESTING)) {
                    // Aucun air ET aucun decor naturel : la colonne est portee.
                    supported = true;
                    y = secHigh + 1;
                    continue;
                }
            }
            BlockState s = readBlock(level, chunk, mut, x, y, z);
            if (s.isAir()) { supported = false; y++; continue; }
            if (!isSweepableNatural(s)) { supported = true; y++; continue; }   // bloc plein : porteur
            if (!supported) {
                // T45 : plus relie au sol DANS CETTE COLONNE -- on ne supprime que
                // si l'amas entier flotte (bord de canopee = legitime, liane
                // accrochee a rien = supprimee).
                if (SWEEP_ANCHORED.contains(BlockPos.asLong(x, y, z))) { supported = true; y++; continue; }
                if (isFloatingCluster(level, x, y, z, s)) {
                    level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                    SWEPT_NAT.incrementAndGet();
                    SWEPT.incrementAndGet();
                }
                supported = true;   // l'amas est ancre (ou vient d'etre degage) : on ne descend pas plus
                y++;
                continue;
            }
            if (!hasNaturalSupport(level, mut, x, y, z, s)) {
                level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                SWEPT_NAT.incrementAndGet();
                SWEPT.incrementAndGet();
                y++;
                continue;      // ce qui pendait dessous/au-dessus devient non porte a son tour
            }
            supported = true;  // decor naturel pose sur du solide : conserve
            y++;
        }
    }

    /** T45 : taille maximale de l'amas naturel explore (garde-fou de cout). */
    private static final int SWEEP_CLUSTER_MAX = 512;

    /** T69 : budget de temps pour l'exploration d'UN amas (ms). Au-dela : conserve. */
    private static final long CLUSTER_BUDGET_MS = 20;

    /** T69 : nombre d'amas non verifies faute de temps (conservee = non supprimee). */
    private static final java.util.concurrent.atomic.AtomicInteger CLUSTER_TIMEOUTS =
            new java.util.concurrent.atomic.AtomicInteger();

    /** T45 : blocs dont on sait deja que leur amas est ANCRE (donc conserves). */
    private static final java.util.Set<Long> SWEEP_ANCHORED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * T45 -- l'amas naturel contenant (x,y,z) flotte-t-il ?
     *
     * <p>Vrai si AUCUN bloc de l'amas ne repose sur un bloc PLEIN. Un bord de
     * canopee (feuille au-dessus du vide, mais voisine d'autres feuilles posees
     * sur l'arbre) est donc CONSERVE : c'est un porte-a-faux naturel legitime.
     * Une liane, un ilot d'herbe ou un reste de l'ancien terrain, eux, ne
     * reposent sur rien et partent entierement.
     */
    private static boolean isFloatingCluster(ServerLevel level, int sx, int sy, int sz, BlockState start) {
        if (!isSweepableNatural(start)) return false;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<long[]> q = new java.util.ArrayDeque<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        q.add(new long[]{sx, sy, sz});
        seen.add(BlockPos.asLong(sx, sy, sz));
        int visited = 0;
        long clusterT0 = System.currentTimeMillis();
        while (!q.isEmpty() && visited < SWEEP_CLUSTER_MAX) {
            // === T69 : BUDGET DE TEMPS PAR AMAS ===
            // Chaque bloc visite coute 7 lectures (le bloc du dessous + les 6
            // voisins), et un amas peut en compter 512 : mesure en jeu, UNE seule
            // colonne du balayage a coute 532 ms a cause de cette exploration
            // (journal « colonne(s) lente(s) » de T68), ce qui allongeait la
            // tranche du tick. Au-dela du budget on s'arrete et on repond
            // « ancre » : on ne supprime JAMAIS un amas qu'on n'a pas pu verifier
            // entierement (regle de securite, on conserve plutot que d'effacer).
            if ((visited & 15) == 0 && System.currentTimeMillis() - clusterT0 > CLUSTER_BUDGET_MS) {
                CLUSTER_TIMEOUTS.incrementAndGet();
                return false;
            }
            long[] p = q.poll();
            int x = (int) p[0], y = (int) p[1], z = (int) p[2];
            visited++;
            BlockState below = level.getBlockState(m.set(x, y - 1, z));
            if (!below.isAir() && below.blocksMotion()) {
                // ANCRE : l'amas tient a quelque chose de plein -- on le memorise.
                for (long k : seen) SWEEP_ANCHORED.add(k);
                return false;
            }
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                long k = BlockPos.asLong(nx, ny, nz);
                if (!seen.add(k)) continue;
                BlockState n = level.getBlockState(m.set(nx, ny, nz));
                if (n.isAir() || !isSweepableNatural(n)) continue;   // l'amas ne s'etend qu'entre NATURELS
                q.add(new long[]{nx, ny, nz});
            }
        }
        return true;   // aucun appui plein trouve : l'amas flotte
    }

    /** Vrai pour tout ce que le balayage a le droit de retirer s'il flotte. */
    private static boolean isSweepableNatural(BlockState s) {
        return isSurfaceDecor(s) || isLooseSurface(s)
                || s.is(Blocks.GLOW_LICHEN) || s.is(Blocks.HANGING_ROOTS)
                || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.LILY_PAD)
                || s.is(Blocks.BIG_DRIPLEAF) || s.is(Blocks.BIG_DRIPLEAF_STEM)
                || s.is(Blocks.SMALL_DRIPLEAF) || s.is(Blocks.ROOTED_DIRT);
    }

    /**
     * Un decor naturel tient-il debout ? Support vertical direct, ou -- pour les
     * lianes, le lichen et les racines -- un bloc plein sur une FACE
     * (accroche laterale legitime sur une paroi).
     */
    private static boolean hasNaturalSupport(ServerLevel level, BlockPos.MutableBlockPos mut,
                                             int x, int y, int z, BlockState s) {
        BlockState below = level.getBlockState(mut.set(x, y - 1, z));
        if (!below.isAir() && below.blocksMotion()) return true;
        boolean lateral = s.getBlock() instanceof net.minecraft.world.level.block.VineBlock
                || s.is(Blocks.GLOW_LICHEN) || s.is(Blocks.HANGING_ROOTS)
                || s.getBlock() instanceof net.minecraft.world.level.block.GrowingPlantBlock;
        if (!lateral) return false;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockState n = level.getBlockState(mut.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()));
            if (!n.isAir() && n.blocksMotion()) return true;
        }
        return false;
    }
}
