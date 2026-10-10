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

    /**
     * T135 : applyMcreatorPatch restaure encore le vieux cache T75 avant chaque
     * compilation. Cette garde vit donc volontairement dans CE fichier, celui
     * effectivement remplace par la livraison. L'auto-subscriber charge la classe
     * au demarrage et neutralise l'index prive avant le premier tick de warmup.
     * Reflection best-effort : compatible avec l'ancien cache restaure comme avec
     * T131, sans modifier son API publique ni le chargement A LA DEMANDE.
     */
    // T148 : le préchargement des templates ne dépend plus de cette énorme
    // classe de terrain. Il est enregistré séparément par
    // util.StructureTemplateStartupPreloader ; prepZone ne fait que consommer
    // StructureTemplateCache et peut donc démarrer immédiatement si le warmup
    // de lancement a déjà terminé.

    private static final int RING = 10;
    // Ring de modification du terrain : PROPORTIONNEL a la taille de la structure
    // (petit pour les petits builds, gros pour les gros), clampe. Plus de zone
    // titanesque carree quand le build est petit et rectangulaire : la zone suit
    // la taille et la forme (footprint rectangulaire) du build.
    private static final int SMOOTH_RING_MIN = 14;
    // T136 : le profil sans warmup reste a 35,5 s sur 18 544 colonnes.
    // Un halo terrain de 24 blocs entoure integralement l'emprise : une fenetre
    // 3x3 chunks glissante peut etre centree sur chaque bord avec recouvrement,
    // il reste plus d'un chunk exterieur et le rayon eau >=10 est preserve.
    // NATURALIZE_EXTRA_RING ajoute encore un chunk pour arbres/decoration.
    private static final int SMOOTH_RING_MAX = 24;
    // Marge supplement pour que le lissage englobe aussi les spheres eloignees.
    // T133 : l'ancien +32 s'ajoutait encore au rayon proportionnel et transformait
    // dragon (92x62) en zone 216x186 : 168 chunks a generer et 40 176 colonnes
    // rescanees plusieurs fois. Le rayon calcule (14..48) fournit deja les 3x3
    // chunks et leur halo ; aucun second halo de 32 blocs n'est necessaire.
    private static final int SMOOTH_EXTRA_RING = 0;
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
     * OBSOLETE (T4, 20/09 ; T109, 30/09) : cette constante n'est PLUS utilisee
     * par le pipeline. Elle laissait croire a « 3 passes » alors que le
     * smooth est desormais la SERIE GRADUELLE de 10 passes a rayon croissant
     * de T109 (smoothPassGradual, noyaux 7>11>15>21>29>49>55>61>67>71). Ne pas
     * s'y fier : lire prepZoneAfterPreload() et SMOOTH_KERNEL_SCHEDULE_T109.
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
    /** T152 : chunks photographies/nettoyes une seule fois des leur disponibilite. */
    private static final java.util.Set<Long> STREAMED_CHUNKS = new java.util.HashSet<>();

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
    /** Compatibility entry point: the old foundation Y includes model offsets.
     * Never interpret it as the selected terrain floor. */
    public static void prepZone(ServerLevel level, BlockPos min, BlockPos max, int foundationBaseY, Runnable onReady) {
        prepZoneInternal(level, min, max, foundationBaseY, null, onReady);
    }

    /** groundY is the selected SOLID ground block, not the first free Y or model bottom.
     * Example: groundY=12 clears only Y>=13. Callers must pass their selection explicitly. */
    public static void prepZone(ServerLevel level, BlockPos min, BlockPos max,
                                int foundationBaseY, int groundY, Runnable onReady) {
        if (groundY < level.getMinBuildHeight() || groundY >= level.getMaxBuildHeight())
            throw new IllegalArgumentException("Selected groundY is outside build height: " + groundY);
        prepZoneInternal(level, min, max, foundationBaseY, groundY, onReady);
    }

    private record ClearancePlan(ServerLevel level, BlockPos min, BlockPos max, int groundY) {
        boolean matches(ServerLevel other, BlockPos a, BlockPos b) {
            return level == other && min.getX() == a.getX() && min.getZ() == a.getZ()
                    && max.getX() == b.getX() && max.getZ() == b.getZ();
        }
        boolean contains(ServerLevel other, int x, int z) {
            return level == other && x >= min.getX() && x <= max.getX()
                    && z >= min.getZ() && z <= max.getZ();
        }
    }
    // Accessed on the server thread under the existing terrain-chain lock.
    private static ClearancePlan CLEARANCE_PLAN;

    private static void prepZoneInternal(ServerLevel level, BlockPos min, BlockPos max,
                                         int foundationBaseY, Integer groundY, Runnable onReady) {
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
                    () -> prepZoneInternal(level, min, max, foundationBaseY, groundY, onReady));
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
        CLEARANCE_PLAN = groundY == null ? null : new ClearancePlan(level, min.immutable(), max.immutable(), groundY);
        clearFootprintProtection();
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
        // B3 : le chrono inter-etapes doit etre rearme lui aussi a chaque pipeline.
        // Sans ca, le PREMIER step() d'une structure mesurait tout le temps d'inactivite
        // ecoule depuis le DERNIER step de la structure precedente (log en jeu :
        // « 47531005 ms de temps REEL » = ~13 h d'attente entre deux evenements).
        LAST_STEP_MS = 0L;
        resetNaturalReference();   // T21 : nouvelle structure -> nouvelle reference naturelle
        // T155 : le callback chunk-local doit couvrir TOUTE la zone finale,
        // pas uniquement les 63 chunks du coeur terrain. Le test T153 montrait
        // 99 chunks suivis mais seulement 63 photographiés/nettoyés : les 36
        // chunks de couronne conservaient donc leur canopée. On demande ici la
        // même boîte finale que ChunkKeeper ; chaque arrivée FULL appelle
        // streamLoadedChunk exactement une fois via STREAMED_CHUNKS.
        int ring0 = terrainRing() + NATURALIZE_EXTRA_RING;
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
        LAST_TREES = new ArrayList<>();
        STREAMED_CHUNKS.clear();
        // T153 : matrice finale allouee AVANT la premiere future de chunk. Chaque
        // chunk y photographie sa part de nappe dès son arrivée; fixwater ne
        // devra plus refaire un scan d'initialisation global pour connaître l'eau.
        NW_X0 = px0; NW_Z0 = pz0; NW_W = px1 - px0 + 1; NW_H = pz1 - pz0 + 1;
        NATURAL_WATER = new boolean[NW_W][NW_H];
        NATURAL_WATER_COUNT = 0;
        preloadChunksBatched(level, px0, pz0, px1, pz1, px1 - px0 + 1, pz1 - pz0 + 1,
                coreX0, coreZ0, coreX1, coreZ1, PRELOAD_TOTAL_BUDGET_MS,
                cp -> streamLoadedChunk(level, cp.x, cp.z, topY), () -> {
            step("prepZone 0/11 : zone chargee, demarrage du pipeline");
            // T103 : PREMIER GESTE APRES CHARGEMENT -- memoriser la nappe d'eau
            // VANILLA (monde encore intact) sur toute la zone chargee. fixLiquids
            // sera borne a cette base : « reprendre la meme base que vanilla et
            // l'expender », sans jamais creer de lac artificiel sur la terre.
            captureNaturalWater(level, px0, pz0, px1 - px0 + 1, pz1 - pz0 + 1);
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
            // T145 : photographie differee avant toute edition, mais seulement
            // lorsque les 99 chunks de la zone finale sont FULL.
            step("prepZone 1/11 : chunks disponibles deja photographies/nettoyes en flux");
            // T-HABILL.4 : echantillonne les palettes de surface AVANT la moindre
            // edition (invariant I1) puis les CONGELE pour la future passe
            // dressAndPlant. Aucun setBlock : mesure et journal seulement.
            {
                long tPal0 = System.currentTimeMillis();
                sampleZonePalettes(level, min, max);
                step("prepZone 1b/11 : palettes de surface echantillonnees en "
                        + (System.currentTimeMillis() - tPal0) + " ms (aucune edition)");
            }
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
        // T145 : le preload 63 chunks pouvait expirer puis scanTrees travaillait
        // pendant la generation des 99 chunks suivis : 6,6 s de scan + 3,9 s
        // d'attente, avec 524 demandes concurrentes. Aucune lecture terrain avant
        // que ChunkKeeper confirme la zone finale FULL. Cette attente remplace ces
        // couts, elle ne s'y ajoute pas.
        String waitKey = chainTag(min, max);
        long waitStart = FULL_WAIT_SINCE.computeIfAbsent(waitKey, k -> System.currentTimeMillis());
        if (!deepluckyblock.util.ChunkKeeper.zoneComplete(level)
                && System.currentTimeMillis() - waitStart < FULL_WAIT_MAX_MS) {
            TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1,
                    () -> prepZoneAfterPreload(level, min, max, foundationBaseY, topY, onReady));
            return;
        }
        boolean full = deepluckyblock.util.ChunkKeeper.zoneComplete(level);
        FULL_WAIT_SINCE.remove(waitKey);
        if (!full) deepluckyblock.util.DebugLog.structure(
                "T146 : attente zone FULL plafonnee a {} ms -- demarrage sur chunks prets, les passes rattrapent le reste",
                FULL_WAIT_MAX_MS);
        // T154 : la zone suivie est plus grande que les 63 chunks du preload.
        // Traiter ici tous les chunks finaux déjà disponibles garantit que les
        // feuilles des 36 chunks de couronne ne survivent pas au clear. Chaque
        // chunk garde son verrou STREAMED_CHUNKS : aucune double photographie.
        int finalCx0 = (min.getX() - terrainRing() - NATURALIZE_EXTRA_RING) >> 4;
        int finalCz0 = (min.getZ() - terrainRing() - NATURALIZE_EXTRA_RING) >> 4;
        int finalCx1 = (max.getX() + terrainRing() + NATURALIZE_EXTRA_RING) >> 4;
        int finalCz1 = (max.getZ() + terrainRing() + NATURALIZE_EXTRA_RING) >> 4;
        for (int cx = finalCx0; cx <= finalCx1; cx++)
            for (int cz = finalCz0; cz <= finalCz1; cz++)
                if (level.getChunkSource().getChunkNow(cx, cz) != null)
                    streamLoadedChunk(level, cx, cz, topY);

        // La premiere photo a pu etre prise apres expiration partielle du preload.
        // Refaire avant toute edition garantit T103 et complete les palettes dont
        // les replis non-FULL ne sont desormais plus mis en cache.
        long tScan = System.currentTimeMillis();
        LAST_TREES.addAll(scanTrees(level, min.getX() - terrainRing(), min.getZ() - terrainRing(),
                max.getX() + terrainRing(), max.getZ() + terrainRing(), topY));
        step("prepZone 1c/11 : scanTrees zone FULL, " + LAST_TREES.size() + " arbres en "
                + (System.currentTimeMillis() - tScan) + " ms");
        int ring = terrainRing() + NATURALIZE_EXTRA_RING;
        captureNaturalWater(level, min.getX() - ring, min.getZ() - ring,
                max.getX() - min.getX() + 1 + ring * 2,
                max.getZ() - min.getZ() + 1 + ring * 2);
        sampleZonePalettes(level, min, max);

        // 0. Pre-fill : remplit les vides SOUS la structure (emprise + 3) avec du grass
        //    block, pour garantir un sol solide (pas de structure qui flotte sur un trou).
        // Visibilite : ces 3 etapes ne loggaient rien, ce qui rendait tout
        // blocage invisible (« on ne sait meme pas ce qu'il se passe donc on ne
        // peut pas regler le potentiel fautif »). Chacune annonce desormais sa
        // fin, on peut donc localiser un arret a coup sur.
        // T134 : clearSurfaceDecor, execute quelques lignes plus bas AVANT tout
        // smooth et bien avant le paste, parcourt deja toute la zone, y compris
        // 100 % des chunks centraux. L'ancienne purge centrale relisait donc les
        // memes 5 120 colonnes et supprimait les memes blocs une seconde fois
        // (4,5 s au profil T133). clearFootprint reste immediat ; le clear global
        // garantit ensuite qu'aucun arbre ancien ne survit au paste.
        clearFootprint(level, min, max, topY, () -> {
            step("prepZone : chunks centraux + clearFootprint termines AVANT preparation");
            if (CLEARANCE_PLAN != null && CLEARANCE_PLAN.matches(level, min, max))
                protectFootprint(min, max);
            prefillFoundation(level, min, max, foundationBaseY, () -> {
                step("prepZone 2/11 : prefillFoundation termine");
                clearSurfaceDecor(level, min, max, () -> {
                    step("prepZone 3/11 : clearSurfaceDecor termine");
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
                    // T171 : aucune suppression artificielle de l'eau. L'emprise
                    // aquatique est refusée avant édition et l'unique autorité de
                    // réparation reste fixLiquids/fixwater à la fin du remodelage.
                    // clearWaterPockets coûtait encore 2,2–2,8 s et contredisait
                    // explicitement la règle « seulement fixwater ».
                    {
                            step("prepZone 7/11 : clearWaterPockets supprime (T171, fixwater seul)");
                            // T126 : l'ancien fixwater adaptatif AVANT smooth etait
                            // redondant avec le fixwater final et fixLiquids T125.
                            // Sur dragon il parcourait l'ocean avec des ellipsoides
                            // rayon ~terrainRing et n'avait toujours pas fini apres
                            // huit minutes. Le terrain n'a besoin ici que des berges
                            // et des poches deja traitees ; l'eau definitive est
                            // restauree exhaustivement APRES le remodelage.
                            step("prepZone 7b/11 : fixwater pre-smooth redondant supprime (T126)");
                                // T109 (consignes dev 30/09) : les deux vagues de
                                // smooths (7x7 x50 puis 15x15 x12 = 62 passes) sont
                                // remplacees par UNE serie de 10 passes a rayon
                                // croissant (7>11>15>21>29>49>55>61>67>71, >=3*3
                                // chunks des le 6e), appliquees sur une seule zone
                                // structure + exterieure dans le meme champ de
                                // hauteurs. « Ensuite on remet la structure, puis
                                // naturalize » : le paste est le onReady de cette
                                // chaine, la naturalization suit dans decorate.
                                smoothPassGradual(level, min, max, () -> {                  // 5+6
                                    step("prepZone 5+6/11 : smooth graduel 10 passes (T109, rayon croissant >=48 des le 6e) termine");
                                    {

                                        verifyGrassSurface(level, min, max, () -> {     // 10
                                            step("prepZone 10/11 : verifyGrassSurface termine");
                                            // T164 : cleanupZone était exécuté ici puis une seconde
                                            // fois après dressAndPlant/replantTrees. Seule la passe
                                            // finale peut nettoyer les résidus réellement définitifs.
                                            // Suppression de ce parcours intégral redondant (~0,8 s
                                            // sur Dragon), sans retirer la garantie de nettoyage.
                                            {
                                                step("prepZone 11/11 : cleanup initial fusionne dans le cleanup final");
                                                // T185 : aucun re-clear après le lissage — il
                                                // redessinerait précisément la coupe que les DEUX
                                                // passes larges post-clear viennent de masquer.
                                                // La cible du noyau est bornée à Y0 plus bas, donc
                                                // ces smooths ne peuvent pas réintroduire une
                                                // montagne à l'intérieur de la structure.
                                                runWithoutRedundantFixWater(() -> {
                                                    step("prepZone : 2 smooths larges post-clear terminés; aucune recoupe finale");
                                                    onReady.run();
                                                });
                                            }
                                        });
                                    }
                                });
                    }
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
        if (CLEARANCE_PLAN != null && CLEARANCE_PLAN.contains(level, x, z))
            y0 = Math.max(y0, CLEARANCE_PLAN.groundY() + 1);
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

    // The new overload clears the whole sky column strictly ABOVE the selected floor.
    // The legacy overload deliberately retains its old behavior until its caller is migrated.
    private static void clearFootprint(ServerLevel level, BlockPos min, BlockPos max, int topY, Runnable onDone) {
        final ClearancePlan plan = CLEARANCE_PLAN != null && CLEARANCE_PLAN.matches(level, min, max)
                ? CLEARANCE_PLAN : null;
        new ClearFootprintJob(level, min, max, topY, plan, onDone).slice();
    }

    /**
     * T108 (consigne dev 30/09 : « on met plusieurs secondes a tout supprimer !
     * ca serait pas plus simple de set de l'air direct dans tout les chunks
     * comme ca sa supprime ? » -- 92 s mesurees sur le clear generique, 69 864
     * colonnes au run crimson) : le clear passe CHUNK PAR CHUNK et ecrit
     * DIRECTEMENT dans les palettes de sections, exactement le modele T101 du
     * clear crimson (« CLEAR COMPLETE 304 chunks » -- prouve en production).
     *
     * <p>On n'appelle PLUS JAMAIS level.setBlock() bloc par bloc : chaque
     * setBlock declenchait verification de voisins + heightmap + relumiere par
     * bloc. Desormais, par section : boucle (lx,ly,lz) bornee aux plafonds et
     * planchers PAR COLONNE (identiques a l'ancien code : MOTION_BLOCKING_NO_LEAVES
     * en plancher legacy / groundY du plan, WORLD_SURFACE-1 en plafond plan /
     * topY legacy), reecriture states.set(..., air), recalcBlockCounts,
     * updateSectionStatus, puis UNE FOIS par chunk : primeHeightmaps,
     * setUnsaved, tryScheduleUpdate. Filtres INCHANGES : L'air et la bedrock ne
     * sont jamais reecrits, et l'appelant legacy ne retire que le terrain
     * naturel (isNaturalTerrain). Les blocs-entites du volume sont liberes
     * proprement AVANT toute ecriture palette (sinon fantomes tickants). Repli
     * synchronne par chunk (ProtoChunk ou acces atypique) = ancien chemin
     * setBlock, securite d'abord, vitesse ensuite. Tranche de 40 ms par tick
     * (le budget T91 des passes terrain) : un chunk ~1-3 ms, jamais de freeze.
     */
    private static final class ClearFootprintJob {
        private final ServerLevel level;
        private final BlockPos min, max;
        private final int topY;
        private final ClearancePlan plan;
        private final Runnable onDone;
        private final int cx0, cx1, cz0, cz1;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private int cx, cz, chunksDone, fallbackChunks;
        private long removed;

        ClearFootprintJob(ServerLevel level, BlockPos min, BlockPos max, int topY,
                          ClearancePlan plan, Runnable onDone) {
            this.level = level; this.min = min; this.max = max; this.topY = topY;
            this.plan = plan; this.onDone = onDone;
            this.cx0 = min.getX() >> 4; this.cx1 = max.getX() >> 4;
            this.cz0 = min.getZ() >> 4; this.cz1 = max.getZ() >> 4;
            this.cx = cx0; this.cz = cz0;
        }

        /** Plancher de colonne = logique historique, inchangee. */
        private int columnFloor(int x, int z) {
            return plan == null
                    ? deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                    : plan.groundY();
        }

        /** Plafond de colonne = logique historique, inchangee. */
        private int columnCeiling(int x, int z) {
            return plan == null ? topY : Math.min(level.getMaxBuildHeight() - 1,
                    deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z) - 1);
        }

        void slice() {
            deepluckyblock.util.TerrainChain.heartbeat();
            deepluckyblock.util.ChunkKeeper.keep(level);
            if (!deepluckyblock.util.ChunkKeeper.zoneComplete(level)) { resume(); return; }
            long deadline = System.nanoTime() + 40_000_000L;   // 40 ms : budget des passes terrain T91
            while (cx <= cx1 && System.nanoTime() < deadline) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    deepluckyblock.util.SafeSurface.reRequest(level, cx, cz);
                    resume();
                    return;
                }
                if (chunk instanceof net.minecraft.world.level.chunk.LevelChunk lc) {
                    removed += clearChunkDirect(lc);
                } else {
                    fallbackChunks++;
                    removed += clearChunkPerColumn(chunk);
                }
                chunksDone++;
                if (++cz > cz1) { cz = cz0; cx++; }
            }
            if (cx <= cx1) { resume(); return; }
            deepluckyblock.util.SafeSurface.clearCache();
            deepluckyblock.util.DebugLog.structure(
                    "clearFootprint (T108 direct-sections): {} blocks removed on {} chunks{}; selected solid floor={} (exclusive clearance)",
                    removed, chunksDone,
                    fallbackChunks > 0 ? " (" + fallbackChunks + " via repli setBlock)" : "",
                    plan == null ? "legacy caller: not provided" : plan.groundY());
            if (onDone != null) onDone.run();
        }

        private void resume() {
            TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::slice);
        }

        /** Chemin rapide : ecriture directe de palette (T101/T108). */
        private long clearChunkDirect(net.minecraft.world.level.chunk.LevelChunk chunk) {
            long n = 0;
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            int lx0 = Math.max(0, min.getX() - baseX), lx1 = Math.min(15, max.getX() - baseX);
            int lz0 = Math.max(0, min.getZ() - baseZ), lz1 = Math.min(15, max.getZ() - baseZ);
            if (lx0 > lx1 || lz0 > lz1) return 0;
            // 1) blocs-entites du volume (y compris dans la fourchette de leurs
            //    colonnes) : liberes AVANT toute ecriture palette.
            java.util.List<BlockPos> kill = null;
            for (BlockPos be : chunk.getBlockEntities().keySet()) {
                int bex = be.getX() - baseX, bez = be.getZ() - baseZ;
                if (bex < lx0 || bex > lx1 || bez < lz0 || bez > lz1) continue;
                int floor = columnFloor(be.getX(), be.getZ());
                int ceiling = columnCeiling(be.getX(), be.getZ());
                if (be.getY() > floor && be.getY() <= ceiling) {
                    if (kill == null) kill = new ArrayList<>();
                    kill.add(be.immutable());
                }
            }
            if (kill != null) for (BlockPos bp : kill) level.removeBlockEntity(bp);
            // 2) ecriture palette directe, section par section.
            BlockState air = Blocks.AIR.defaultBlockState();
            var sections = chunk.getSections();
            int minB = level.getMinBuildHeight() + 1, maxB = level.getMaxBuildHeight() - 2;
            boolean anyTouched = false;
            for (int i = 0; i < sections.length; i++) {
                net.minecraft.world.level.chunk.LevelChunkSection sec = sections[i];
                if (sec == null || sec.hasOnlyAir()) continue;
                int secY = chunk.getSectionYFromSectionIndex(i);
                int sy = secY << 4;
                if (sy > maxB || sy + 15 < minB) continue;
                var states = sec.getStates();
                boolean touched = false;
                for (int lz = lz0; lz <= lz1; lz++) {
                    for (int lx = lx0; lx <= lx1; lx++) {
                        int x = baseX + lx, z = baseZ + lz;
                        int yTop = columnCeiling(x, z);
                        int floor = columnFloor(x, z);
                        int ly0 = Math.max(Math.max(floor + 1, minB) - sy, 0);
                        int ly1 = Math.min(Math.min(yTop, maxB) - sy, 15);
                        if (ly0 > ly1) continue;
                        for (int ly = ly0; ly <= ly1; ly++) {
                            BlockState prev = states.get(lx, ly, lz);
                            if (prev.isAir() || prev.getBlock() == Blocks.BEDROCK) continue;
                            // Appelant legacy : terrain naturel seulement (filtre inchange).
                            if (plan == null && !isNaturalTerrain(prev)) continue;
                            // T175 : getBlockEntities() ne contient pas forcément
                            // encore les NBT différés d'un chunk fraîchement chargé.
                            // Nettoyer aussi par position lorsqu'un état BE est
                            // remplacé via la palette directe.
                            if (prev.hasBlockEntity())
                                chunk.removeBlockEntity(new BlockPos(x, sy + ly, z));
                            states.set(lx, ly, lz, air);
                            touched = true; n++;
                        }
                    }
                }
                if (touched) {
                    anyTouched = true;
                    sec.recalcBlockCounts();
                    level.getChunkSource().getLightEngine().updateSectionStatus(
                            net.minecraft.core.SectionPos.of(chunk.getPos(), secY), sec.hasOnlyAir());
                }
            }
            // 3) une seule fois par chunk : heightmaps + flush lumiere + sauvegarde.
            if (anyTouched) {
                net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk,
                        java.util.EnumSet.of(
                                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR,
                                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE));
                chunk.setUnsaved(true);
                level.getChunkSource().getLightEngine().tryScheduleUpdate();
            }
            return n;
        }

        /** Repli (ProtoChunk ou acces atypique) : ancien chemin colonne par colonne, setBlock. */
        private long clearChunkPerColumn(net.minecraft.world.level.chunk.ChunkAccess chunk) {
            long n = 0;
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            int lx0 = Math.max(0, min.getX() - baseX), lx1 = Math.min(15, max.getX() - baseX);
            int lz0 = Math.max(0, min.getZ() - baseZ), lz1 = Math.min(15, max.getZ() - baseZ);
            for (int lx = lx0; lx <= lx1; lx++) {
                for (int lz = lz0; lz <= lz1; lz++) {
                    int x = baseX + lx, z = baseZ + lz;
                    int floor = columnFloor(x, z);
                    int ceiling = columnCeiling(x, z);
                    for (int y = ceiling; y > floor; y--) {
                        BlockState state = level.getBlockState(pos.set(x, y, z));
                        if (state.isAir() || state.is(Blocks.BEDROCK)) continue;
                        if (plan == null && !isNaturalTerrain(state)) continue;
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                        n++;
                    }
                }
            }
            return n;
        }
    }

    // Avant le smooth : remplit les vides SOUS la structure (emprise + 3 radius)
    // avec du grass block, du niveau de base de la structure jusqu'au premier bloc
    // solide. Garantit qu'il n'y a aucun vide sous la structure (pas de flottaison).
    // Batche via schedule.
    private static void prefillFoundation(ServerLevel level, BlockPos min, BlockPos max, int foundationBaseY, Runnable onDone) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int minBuild = level.getMinBuildHeight();
        int[] filled = {0};
        startColumnPass(level, "prefillFoundation", columnsOf(min, max, 0), BATCH_COLS * 4, col -> {
            int x = col[0], z = col[1];
            int surface = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            // T154 : l'ancien test était inversé. Il remplissait les cavernes
            // sous un terrain déjà plus haut que la base (55 480 écritures sur
            // dragon), et ignorait précisément les colonnes où la base flottait.
            // Une fondation n'est requise que dans l'espace entre une base plus
            // haute et le premier sol situé dessous.
            if (foundationBaseY <= surface) return;
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

                boolean snowy = isSnowyCell(level, mut.set(x, targetY, z));   // T-HABILL.3 : cache par chunk (T71)
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
    /** T79 : « cette section contient de l'air OU du decor » -- sinon aucun setBlock. */
    private static final java.util.function.Predicate<BlockState> DECOR_INTERESTING =
            s -> s.isAir() || isSurfaceDecor(s);

    /**
     * T122 : purge garantie de TOUS les chunks qui intersectent la structure.
     * Contrairement au clear rectangulaire, les bornes sont alignees sur les
     * chunks : aucun chunk central ne peut rester vierge du nettoyage. On ne
     * retire ici que la vegetation naturelle, jamais le terrain.
     */
    private static void clearVegetationInStructureChunks(ServerLevel level, BlockPos min, BlockPos max,
                                                         Runnable onDone) {
        int x0 = (min.getX() >> 4) << 4, x1 = ((max.getX() >> 4) << 4) + 15;
        int z0 = (min.getZ() >> 4) << 4, z1 = ((max.getZ() >> 4) << 4) + 15;
        java.util.List<int[]> cols = new java.util.ArrayList<>((x1 - x0 + 1) * (z1 - z0 + 1));
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) cols.add(new int[]{x, z});
        java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger();
        startColumnPass(level, "clearVegetation STRUCTURE-CHUNKS T122", cols, BATCH_COLS * 4, col -> {
            int x = col[0], z = col[1];
            ChunkAccess chunk = deepluckyblock.util.SafeSurface.chunkFor(level, x >> 4, z >> 4);
            BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
            int top = Math.min(level.getMaxBuildHeight() - 1,
                    deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z) + 2);
            int bottom = Math.max(level.getMinBuildHeight(),
                    (CLEARANCE_PLAN == null ? min.getY() : CLEARANCE_PLAN.groundY()) - 64);
            for (int y = top; y >= bottom; y--) {
                LevelChunkSection sec = sectionAt(chunk, y);
                if (sec != null && (sec.hasOnlyAir() || !sec.maybeHas(DECOR_INTERESTING))) {
                    y = Math.max(bottom, (y & ~15)) - 1;
                    continue;
                }
                BlockState state = readBlock(level, chunk, mut, x, y, z);
                if (isSurfaceDecor(state)) {
                    level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                    removed.incrementAndGet();
                }
            }
        }, () -> {
            deepluckyblock.util.SafeSurface.clearCache();
            deepluckyblock.util.DebugLog.structure(
                    "clearVegetation STRUCTURE-CHUNKS T122 : {} bloc(s) vegetal(aux) retires dans {} chunk(s) centraux (zone alignee {}..{} / {}..{})",
                    removed.get(), (x1 - x0 + 1) / 16 * ((z1 - z0 + 1) / 16), x0 >> 4, x1 >> 4, z0 >> 4, z1 >> 4);
            if (onDone != null) onDone.run();
        });
    }

    private static void clearSurfaceDecor(ServerLevel level, BlockPos min, BlockPos max, Runnable onDone) {
        // T137 : scanTrees a deja photographie exactement chaque arbre. Rebalayer
        // 43 a 89 blocs verticaux dans 11 656 colonnes coutait 14,1 s. Retirer
        // directement les blocs photographies est exhaustif pour les arbres ;
        // clearFootprint a deja vide toute l'emprise avant cette passe. Il ne
        // reste dans la couronne que les petits decors poses sur le sol, inspectes
        // dans une fenetre de 5 blocs autour de WORLD_SURFACE.
        int removedTrees = 0;
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (SavedTree tree : LAST_TREES) {
            for (SavedBlock saved : tree.blocks()) {
                BlockState now = level.getBlockState(mut.set(tree.baseX() + saved.rx(), tree.groundY() + saved.ry(), tree.baseZ() + saved.rz()));
                if (isLog(now) || isLeaf(now) || isSurfaceDecor(now)) {
                    level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                    removedTrees++;
                }
            }
        }
        final int treeBlocksRemoved = removedTrees;
        deepluckyblock.util.SafeSurface.clearCache();
        // T140 : clearFootprint a deja vide exhaustivement toute l'emprise et
        // les arbres de la couronne viennent d'etre retires depuis la photo.
        // Le second balayage de 58 280 cellules (5 niveaux x 11 656 colonnes)
        // coutait encore 9,8 s sous charge pour ne retirer que du petit decor,
        // ensuite couvert par verifyGrassSurface et le balayage flottant.
        deepluckyblock.util.DebugLog.structure(
                "clearSurfaceDecor T140 : {} bloc(s) d'arbre retires depuis la photographie; rescan vertical redondant supprime",
                treeBlocksRemoved);
        if (onDone != null) onDone.run();
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
        protectFootprint(min, max);   // keep the pasted building out of cleanup/grass passes
        StructureScatterDecor.scatter(level, min, max, LAST_STRUCTURE_NAME);              // 16
        deepluckyblock.util.DebugLog.structure("decorate 5/5 : decors (scatter) poses");
        // T92 (B13) : l'habillage (terre + vegetation) passe AVANT le replant.
        // Un arbre vanilla exige un sol grass/dirt : le ras exclusif du
        // footprint expose la stone, donc un replant tente AVANT le dress
        // echouait a coup sur dans le rectangle rase (crimsonlake), meme avec
        // la colonne decouverte detectee par footprintColumnDressable.
        // T-HABILL.8 : dressAndPlant + repose des sauvees REMPLACENT le couple
        // verifyGrassSurface/naturalize quand le drapeau est leve (chemins
        // historiques conserves sinon, D9). Cactus poses en tout dernier.
        // T98 : POST-POSE = l'emprise de la structure est FIGEE (aucun switch
        // de blocs terrain ne doit toucher la structure posee -- voir dressAndPlant).
        // T177 : T176 avait supprimé trop largement le post-traitement et laissé
        // le sol entièrement nu. On restaure uniquement l'habillage végétal :
        // plantes photographiées quand elles existent, puis végétation maison
        // sur les grass_blocks restés vides. Les rescans cleanup et le
        // SettleGate de 200 ticks restent supprimés.
        dressOrLegacy(level, min, max, true, () -> {
            // T180 : T177 avait restauré les plantes mais oublié l'appel aux
            // arbres photographiés. Les logs voyaient 528 arbres au scan puis
            // aucun replant. Repose exacte après habillage, comme dans la chaîne
            // historique; replantTrees respecte l'emprise protégée.
            replantTrees(level, LAST_TREES, min, max, () -> {
                deepluckyblock.util.TerrainChain.release(chainKey);
                deepluckyblock.util.ChunkKeeper.release(level);
                clearFootprintProtection();
                CLEARANCE_PLAN = null;
                step("decorate TERMINE T180 (végétation + arbres restaurés)");
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
                    () -> decorateChain(level, min, max, terrainPhase, afterTerrain));
            return;
        }
        // T159 : prepZone vient déjà d'effectuer, dans cet ordre, photographie,
        // clear, eau, smooth joint, naturalisation du sol et cleanup. Rejouer ici
        // tout le terrain ajoutait 7+ secondes (sweep 5,4 s, eau, berges,
        // sealWaterLeaks) avant le paste et contredisait le pipeline à passage
        // unique. En phase terrain, le paste est donc réellement l'étape suivante.
        if (terrainPhase) {
            deepluckyblock.util.TerrainChain.release(chainKey);
            step("decorate TERRAIN T159 : passes redondantes supprimées, paste immédiat");
            if (afterTerrain != null) afterTerrain.run();
            return;
        }

        // T23 : les vegetaux rendus flottants par le remodelage sont retires
        // AVANT le reste du decor (consigne « des cocoa qui volent »).
        SWEPT.set(0);
        SWEEP_ANCHORED.clear();
        CLUSTER_TIMEOUTS.set(0);
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
        // An explicitly cleared footprint must not grow a mountain again before paste.
        if (terrainPhase && (CLEARANCE_PLAN == null || !CLEARANCE_PLAN.matches(level, min, max)))
            clearFootprintProtection();
        else protectFootprint(min, max);

        // FIX (meme cause que prepZone : passes synchrones sans log = freeze
        // invisible). Chaque groupe est desormais enchaine sur la fin reelle de
        // la passe precedente, et chaque etape s'annonce dans le log.
        // ORDRE ET OPERATIONS INCHANGES.
        cleanupZone(level, min, max, () ->                  // 14
        clearWaterPockets(level, min, max, () ->            // 15
        guardWaterEdges(level, min, max, () ->
        runWithoutRedundantFixWater(() -> {

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
                        runWithoutRedundantFixWater(() -> {

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
                        // T134 : le balayage vertical complet est redondant avec
                        // clearSurfaceDecor (zone entiere) puis sweepFloatingDecor
                        // (emprise + halo), tous deux deja termines depuis la derniere
                        // operation susceptible de laisser l'ancien decor en l'air.
                        // T133 a relu 18 544 colonnes pendant 6,5 s pour seulement
                        // quatre blocs. Ne pas relire une troisieme fois la zone.
                        deepluckyblock.util.DebugLog.structure(
                                "balayage etendu fusionne avec clearSurfaceDecor + balayage emprise (T134)");
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
                                Runnable terrainReady = () -> {
                                    deepluckyblock.util.TerrainChain.release(chainKey);
                                    step("decorate TERRAIN TERMINE : terrain fige, la structure peut etre posee (T39)");
                                    if (afterTerrain != null) afterTerrain.run();
                                };
                                if (CLEARANCE_PLAN != null && CLEARANCE_PLAN.matches(level, min, max))
                                    clearFootprint(level, min, max, level.getMaxBuildHeight() - 1, terrainReady);
                                else terrainReady.run();
                            });
                            return;
                        }

                        // ============================================================
                        // BRANCHE POST-POSE (appel historique complet) : finitions
                        // uniquement, plus aucun smooth ni passe de terrain.
                        // ============================================================
                        protectFootprint(min, max);

                        StructureScatterDecor.scatter(level, min, max, LAST_STRUCTURE_NAME); // 16
                        deepluckyblock.util.DebugLog.structure("decorate 5/5 : decors (scatter) poses");

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
                            // T-HABILL.8 : dressAndPlant + repose des sauvees
                            // REMPLACENT le couple verifyGrassSurface/naturalize quand le
                            // drapeau est leve (chemins historiques conserves sinon, D9).
                            // T92 (B13) : le dress passe AVANT le replant (sol
                            // grass/dirt requis par les arbres vanilla ; le raz
                            // exclusif du footprint expose la stone).
                            // T98 : PRE-POSE ici (terrainPhase / chaine deco) --
                            // l'exemption colonne decouverte reste permise car les
                            // colonnes sont encore du terrain naturel rase.
                            dressOrLegacy(level, min, max, false, () -> {
                        replantTrees(level, LAST_TREES, min, max, () -> {                    // 17
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
                                    clearFootprintProtection();
                                    CLEARANCE_PLAN = null;
                                    step("decorate TERMINE (failles, smooths, fuites, decors, arbres, naturalisation)");
                                    });
                                    }   // T23 : fin du bloc "eau intacte" (ex-fin du degel)
                                });
                            });
                        });
                        }));
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
        // T120 : 419 tranches mesurees ; lot double, resultat identique.
        startColumnPass(level, "cleanupZone", columnsOf(min, max, terrainRing()), 192, col -> {
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
        if (isProtected(x, z) || StructureScatterDecor.isInsideDecor(x, z, 1)) return;
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
            if (isDecoration(s) && !isLooseSurface(s) && isFloatingFoliage(level, mut, x, y, z)) {
                level.setBlock(mut.set(x, y, z), Blocks.AIR.defaultBlockState(), FAST_FLAG); counters[0]++;
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

    /**
     * T152 — micro-programme unique d'un chunk. Appelé sur le thread serveur au
     * premier tick exact où getChunkNow devient non-null, pendant que ChunkKeeper
     * demande déjà les suivants. Photographie les arbres puis enlève immédiatement
     * tout l'ancien décor au-dessus du premier vrai sol. L'eau libre est laissée
     * intacte et les blocs moddés de terrain sont acceptés s'ils sont solides et
     * ne sont ni feuilles, ni logs, ni végétaux.
     */
    private static void streamLoadedChunk(ServerLevel level, int cx, int cz, int topY) {
        long ck = ((long) cx << 32) ^ (cz & 0xffffffffL);
        if (STREAMED_CHUNKS.contains(ck)) return;
        int x0 = cx << 4, z0 = cz << 4;
        long t0 = System.currentTimeMillis();
        List<SavedTree> saved = scanTrees(level, x0, z0, x0 + 15, z0 + 15, topY);
        LAST_TREES.addAll(saved);
        // Palette calculée une seule fois tant que le chunk naturel est intact.
        // paletteAt conserve le résultat confiant dans son cache mod-wide.
        paletteAt(level, cx, cz);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int removed = 0, wet = 0;
        // Photographie T103 locale avant la moindre écriture de ce chunk.
        for (int x = x0; x <= x0 + 15; x++) for (int z = z0; z <= z0 + 15; z++) {
            int i = x - NW_X0, j = z - NW_Z0;
            if (i < 0 || j < 0 || i >= NW_W || j >= NW_H) continue;
            int sy = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (level.getBlockState(m.set(x, sy, z)).is(Blocks.WATER) && !NATURAL_WATER[i][j]) {
                NATURAL_WATER[i][j] = true;
                NATURAL_WATER_COUNT++;
                wet++;
            }
        }
        for (int x = x0; x <= x0 + 15; x++) for (int z = z0; z <= z0 + 15; z++) {
            int top = Math.min(topY, deepluckyblock.util.SafeSurface.height(
                    level, Heightmap.Types.WORLD_SURFACE, x, z));
            int floor = level.getMinBuildHeight();
            for (int y = top - 1; y > level.getMinBuildHeight(); y--) {
                BlockState state = level.getBlockState(m.set(x, y, z));
                if (state.is(Blocks.WATER) || state.is(Blocks.LAVA)) { floor = y; break; }
                boolean moddedGround = state.blocksMotion() && !state.is(net.minecraft.tags.BlockTags.LOGS)
                        && !state.is(net.minecraft.tags.BlockTags.LEAVES)
                        && !(state.getBlock() instanceof net.minecraft.world.level.block.BushBlock);
                if (isGroundStop(state) || moddedGround) { floor = y; break; }
            }
            for (int y = top - 1; y > floor; y--) {
                BlockState state = level.getBlockState(m.set(x, y, z));
                if (state.is(Blocks.WATER) || state.is(Blocks.LAVA)) continue;
                if (!state.isAir()) {
                    level.setBlock(m, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                    removed++;
                }
            }
        }
        STREAMED_CHUNKS.add(ck);
        // T163 : pas de journal par chunk. Sur Crimson Lake cela produisait plus
        // de 500 lignes synchrones et masquait les vraies mesures de phase.
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
                // T138 : le preload T52 peut expirer avec des chunks encore en
                // generation. Une lecture synchrone ici a bloque 13,7 s et a
                // empeche le ChunkKeeper de progresser. Les colonnes non chargees
                // sont ignorees dans la couronne; l'emprise est de toute facon
                // integralement videe par clearFootprint avant le paste.
                if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, x, z)) continue;
                long streamCk = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                if (STREAMED_CHUNKS.contains(streamCk)) continue;
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
                    // T-HABILL.7 : sauvetage des plantes non couvertes, greffe ICI
                    // sur un bloc DEJA lu (zero colonne, zero descente en plus,
                    // guide LIVRE VII). On ne compte que l'ESPECE dans l'inventaire
                    // du chunk : la position d'origine n'a plus de sens, le terrain
                    // natif sera retaille entre-temps.
                    if (isRescuable(s)) noteRescue(x, z, s);
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
                half, smoothRing, x0, z0, w, h, null, onDone);
    }

    /**
     * T109 (35aine de consignes dev 30/09, verbatim) : « encore en faire,
     * monter a 10, les montagnes sont pas assez smooths » · « plus tu fais de
     * smooths plus tu aggrandit le rayon » · « minimum smooths en 3*3 chunks a
     * partir du 6e smooth » · « on est graduel » · « prendre la zone structure
     * + zone exterieure dans un meme smooth, sinon les deux se distinguent
     * encore plus ! » · « ensuite on remet la structure, puis naturalize ».
     *
     * <p>Serie de 10 noyaux STRICTEMENT CROISSANTS : les 5 premiers montent
     * progressivement (fin -> moyen -> large), puis du 6e au 10e smooth chaque
     * noyau fait AU MOINS 49 blocs de cote (> 48 = 3*3 chunks, borne du 6e
     * smooth respectee) en continuant d'augmenter. Toutes les passes s'appliquent
     * SUR UN SEUL ET MEME champ de hauteurs (capture + reconstruction uniques,
     * cout nul en O(1)/colonne grace a la somme glissante) couvrant la zone
     * structure + exterieure ensemble -- jamais separees. Apres le lissage, le
     * pipeline remet la structure (paste) puis naturalize : l'ordre demande.
     */
    // T185 : exactement DEUX passes larges après le clear. Elles travaillent
    // chacune sur l'emprise + toute la couronne dans un champ continu : la
    // première (49) efface la coupe, la seconde (71) fond le raccord lointain.
    private static final int[] SMOOTH_KERNEL_SCHEDULE_T109 = {49, 71};

    private static void smoothPassGradual(ServerLevel level, BlockPos structMin, BlockPos structMax, Runnable onDone) {
        int smoothRing = terrainRing();
        int x0 = structMin.getX() - smoothRing, x1 = structMax.getX() + smoothRing;
        int z0 = structMin.getZ() - smoothRing, z1 = structMax.getZ() + smoothRing;
        int w = x1 - x0 + 1, h = z1 - z0 + 1;
        step("smooth graduel T109 : " + SMOOTH_KERNEL_SCHEDULE_T109.length + " passes, noyaux "
                + Arrays.toString(SMOOTH_KERNEL_SCHEDULE_T109)
                + " (rayon croissant, >=3*3 chunks des le 6e, une seule zone structure+exterieur)");
        smoothPassAfterPreload(level, structMin, structMax, 0, SMOOTH_KERNEL_SCHEDULE_T109.length,
                0, smoothRing, x0, z0, w, h, SMOOTH_KERNEL_SCHEDULE_T109, onDone);
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
    // T133 : le coeur bloquant n'a pas a recopier le rayon de smooth complet.
    // Emprise + un vrai halo de chunk suffit ; le reste est demande en parallele.
    public static final int PRELOAD_CORE_MARGIN = 16;
    /**
     * T130 : scanTrees et la photographie T103 lisent toute la zone de facon
     * synchrone. Les lancer sur des chunks absents a produit un tick de 30,1 s
     * en T129. La barriere initiale est donc restauree : elle attend les lectures
     * requises au lieu de transformer une attente asynchrone en generation dans
     * le thread serveur. Le plafond reste strict.
     */
    // T133 : une attente de chunks ne peut plus consommer davantage que le
    // budget TOTAL de 20 s. Les passes reprenables rattrapent les rares absents.
    private static final long PRELOAD_CORE_BUDGET_MS = 4_000L;
    private static final long PRELOAD_RING_BUDGET_MS = 1_000L;
    public static final long PRELOAD_TOTAL_BUDGET_MS = 5_000L;
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
    /** T146 : attente FULL bornee; ne jamais retenir 29-67 s tout le pipeline. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> FULL_WAIT_SINCE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FULL_WAIT_MAX_MS = 0L;

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
    public static void preloadEditedTerrain(ServerLevel level, BlockPos min, BlockPos max,
                                             BlockPos heightAnchor, Runnable onReady) {
        SMOOTH_RING = computeSmoothRing(min, max);
        int margin = terrainRing() + NATURALIZE_EXTRA_RING;
        BlockPos loadMin = min.offset(-margin, 0, -margin);
        BlockPos loadMax = max.offset(margin, 0, margin);
        // The configurable offset may put the height anchor outside the build. Pin
        // that single chunk too, without generating a corridor to the footprint.
        deepluckyblock.util.ChunkKeeper.track(level, loadMin, loadMax);
        deepluckyblock.util.ChunkKeeper.trackAdditionalChunk(level, heightAnchor.getX() >> 4, heightAnchor.getZ() >> 4);
        // preloadBox adds eight blocks itself; the requested halo already includes them.
        preloadBox(level, loadMin.offset(8, 0, 8), loadMax.offset(-8, 0, -8), () -> {
            if (deepluckyblock.util.ChunkKeeper.zoneLoaded(level, loadMin, loadMax)) {
                if (onReady != null) onReady.run();
            } else {
                // A time budget is not permission to skip the unloaded border.
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1,
                        () -> preloadEditedTerrain(level, min, max, heightAnchor, onReady));
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
        final java.util.function.Consumer<net.minecraft.world.level.ChunkPos> onChunkReady;
        final java.util.Set<Long> delivered = new java.util.HashSet<>();
        final long budgetMs;
        final long t0 = System.currentTimeMillis();
        int total, coreTotal;
        long lastReport;
        int forced;
        // T47 : budgets calibres sur la TAILLE de la zone + repli synchrone autorise ou non.
        long coreBudgetMs = PRELOAD_CORE_BUDGET_MS;
        long ringBudgetMs = PRELOAD_RING_BUDGET_MS;
        boolean forceAllowed = true;
        PreloadState(ServerLevel level, long budgetMs, java.util.function.Consumer<net.minecraft.world.level.ChunkPos> onChunkReady, Runnable onReady) {
            this.level = level; this.budgetMs = budgetMs; this.onChunkReady = onChunkReady; this.onReady = onReady;
        }
        /** T47 : applique un budget de temps recalibre (zone geante). */
        PreloadState withBudget(long ms) {
            PreloadState c = new PreloadState(this.level, ms, this.onChunkReady, this.onReady);
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
        preloadChunksBatched(level, x0, z0, x1, z1, w, h, coreX0, coreZ0, coreX1, coreZ1, budgetMs, null, onReady);
    }

    private static void preloadChunksBatched(ServerLevel level, int x0, int z0, int x1, int z1,
                                             int w, int h, int coreX0, int coreZ0, int coreX1, int coreZ1,
                                             long budgetMs, java.util.function.Consumer<net.minecraft.world.level.ChunkPos> onChunkReady,
                                             Runnable onReady) {
        PreloadState st = new PreloadState(level, budgetMs, onChunkReady, onReady);
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
                long ck = (c[0] << 32) ^ (c[1] & 0xffffffffL);
                if (st.onChunkReady != null && st.delivered.add(ck))
                    st.onChunkReady.accept(new net.minecraft.world.level.ChunkPos((int)c[0], (int)c[1]));
                if (c[2] == 1L) coreReady++;
            } else if (c[2] == 1L && firstCoreMissing == null) {
                firstCoreMissing = c;
            }
        }
        deepluckyblock.util.DebugLog.setPhase("pre-chargement des chunks " + ready + "/" + st.total);

        // T130 : `st.total` est exactement la zone que captureNaturalWater et
        // scanTrees vont lire. ChunkKeeper suit volontairement une couronne plus
        // grande pour les phases futures : attendre cette autre zone ici ajoutait
        // des secondes sans proteger les lectures immediates.
        if (ready >= st.total) {
            preloadDone(st, ready, coreReady, elapsed, true);
            return;
        }

        boolean coreDone = coreReady >= st.coreTotal;
        boolean totalDeadline = elapsed >= st.coreBudgetMs + st.ringBudgetMs
                || elapsed >= st.budgetMs;
        boolean ringDeadline = coreDone && elapsed >= st.coreBudgetMs;

        // T86 (B5) : la deadline est DURE a nouveau. Elle avait ete relachee en
        // « simple diagnostic -- on attend la couverture complete », ce qui a
        // reintroduit l'attente indeterminee : everest en jeu, 74,6 s de pre-
        // chargement (45 % des 164,3 s totales) sur une zone de 920 chunks, en
        // boucles de reports toutes les 5 s -- la « lenteur inadmissible » de B5.
        // Depuis T82, les passes de terrain ne PERDENT plus jamais une colonne
        // dont le chunk manque : elles la re-enregistrent pour chargement et la
        // traitent a son arrivee (30 s max). Tenir le pipeline entier en otage en
        // attendant le dernier chunk ne gagne donc RIEN : on demarre avec ce qui
        // est en memoire, le reste arrive en tache de fond et est rattrape.
        if (totalDeadline || ringDeadline) {
            deepluckyblock.util.DebugLog.structure(
                    "preload target exceeded: demarrage quand meme ({}/{} chunks, coeur {}/{}) -- "
                            + "le reste sera rattrape par les passes (T82), jamais perdu (T86)",
                    ready, st.total, coreReady, st.coreTotal);
            preloadDone(st, ready, coreReady, elapsed, ready >= st.total);
            return;
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
                         : " -- " + (st.total - ready) + " chunk(s) non charges (rattrapes par les passes, T82 ; "
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
    // T91 (B12, consigne dev 29/09 : « faut au moins diviser les temps de paste
    // par 5 ! ») : budget de base porte de 12 a 18 ms et plafond adaptatif de
    // 24 a 32 ms. Le regulateur T9 ci-dessous LEVE TOUJOURS le pied sur un
    // serveur charge (facteur 0,5 a 0,25 au-dela de 55 mspt) : l'acceleration
    // ne joue que sur un serveur sain (facteur 1,75, soit ~31 ms/tranche).
    private static final long SLICE_BUDGET_MS = 18;
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
        // T91 (B12) : plafond 24 -> 32 ms (le facteur 1,75 atteint ~31 ms sur
        // serveur tranquille ; toujours tres en dessous du tick de 50 ms).
        return Math.max(2L, Math.min(32L, ms));
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
        /** T96 : colonnes dont l'action a leve une exception -- avant : avalees
         *  en silence, une colonne végétale cassée restait visible sans la moindre
         *  trace dans le log. */
        private int errored = 0;

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
            // T130 : les lectures synchrones initiales sont protegees par LEUR
            // preload exact. Ici, ne pas attendre la couronne plus grande du
            // ChunkKeeper : chaque colonne absente est deja mise dans `missed`
            // puis reprise par T70/T82. Cela conserve l'exhaustivite sans barriere
            // sans rapport avec la zone propre de cette passe.
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
                    try { action.run(col); } catch (Throwable t) { errored++; }
                    skippedCols--;
                    recovered++;
                    it.remove();
                    if (System.currentTimeMillis() - sliceT0 >= budget) break;
                }
                // === T82 : PLUS JAMAIS DE PASSE FERMEE AVEC DES COLONNES NON TRAITEES ===
                // L'ancien arret anticipe T70b se fondait sur zoneComplete(), qui prouve
                // seulement que la FILE DE DEMANDES du ChunkKeeper est videe -- ni que le
                // chunk d'une colonne est reellement en memoire (eviction apres epinglage,
                // ticket tombe entre deux rafraichissements), ni qu'on l'a seulement DEMANDE
                // (colonne hors zone tenue : a everest le balayage couvrait un anneau
                // plus grand que la zone traquee -> 22817 colonnes fermees en l'etat, leur
                // vieux terrain (sable/dirt/stone/plantes) restait visible autour et dans
                // la structure -- visible PRECISEMENT parce que T73 ne pose plus d'air
                // exterieur). Desormais chaque colonne manquante est re-enregistree pour
                // chargement a chaque reprise (hors zone -> trackAdditionalChunk l'ajoute
                // a la zone ; epinglee mais evincee -> reRequest la redemande) et on
                // reteste jusqu'au plafond T70 ; l'attente est productive au lieu d'etre
                // abandonnee.
                if (!missed.isEmpty()) reregisterMissedColumnChunks(level, missed, label, missRetries);
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
                    else { try { action.run(col); } catch (Throwable t) { errored++; } }
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
            if (errored > 0) {
                // T96 : rendu VISIBLE des colonnes cassees -- avec la position de
                // la premiere pour permettre la verification en jeu. Si le dev
                // revoit des arbres/feuillages, ce compte doit etre a 0 sur toutes
                // les passes de nettoyage ; sinon le log dit ou chercher.
                int[] first = cols.isEmpty() ? null : cols.get(0);
                LOGGER.warn("[DLB-STRUCTURE] {} : {} colonne(s) ont leve une exception pendant le traitement "
                                + "-- elles peuvent rester en l'etat (premiere colonne vers x={}, z={}).",
                        label, errored, first != null ? first[0] : 0, first != null ? first[1] : 0);
            }
            // T96 : bilan COMPLET de passe (rattrapages inclus) -- fini le doute
            // « passes qui sautent » : chaque passe annonce ce qu'elle a couvert.
            deepluckyblock.util.DebugLog.structure(
                    "{} : fini ({} colonnes{}{}{}, {} ms)",
                    label, cols.size(),
                    missed.isEmpty() ? ", 0 non traitee" : ", " + missed.size() + " NON TRAITEE(S) (voir WARN)",
                    missRetries > 0 ? ", rattrapage chunk en " + missRetries + " reprise(s)" : "",
                    errored > 0 ? ", " + errored + " erreur(s) (voir WARN)" : "",
                    System.currentTimeMillis() - t0);
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
                if (isProtected(x, z) || (x >= min.getX() && x <= max.getX()
                        && z >= min.getZ() && z <= max.getZ())
                        || StructureScatterDecor.isInsideDecor(x, z, 1)) continue;
                int surf = groundSurfaceY(level, new BlockPos.MutableBlockPos(), x, z);
                if (surf == Integer.MIN_VALUE) continue; // measure only exposed natural ground
                int delta = surf - ref[i][j];
                int d = Math.abs(delta);
                sum += d; n++;
                if (d <= 2) near++;
                if (delta > MAX_SMOOTH_RISE || delta < -MAX_SMOOTH_DROP) over++;
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

    /**
     * T82 : re-enregistre chaque colonne encore manquante pour chargement.
     * <ul>
     *   <li>Chunk hors zone tenue ({@code trackAdditionalChunk} ouvre l'accueil)
     *       : il rejoint la file du ChunkKeeper, qui le demandera, l'epinglera,
     *       puis la passe le rattrapera.</li>
     *   <li>Chunk deja epingle mais evince de la memoire ({@code reRequest}) :
     *       nouvelle demande individuelle sans casser les futures en vol.</li>
     * </ul>
     * Journalise par grappes (toutes les 6 reprises, puis une alerte aux
     * deux tiers du plafond) pour garder les logs lisibles.
     */
    private static void reregisterMissedColumnChunks(ServerLevel level, java.util.List<int[]> missed,
                                                     String label, int retry) {
        int joinedZone = 0, reRequested = 0;
        for (int[] col : missed) {
            int cx = col[0] >> 4, cz = col[1] >> 4;
            if (deepluckyblock.util.ChunkKeeper.trackAdditionalChunk(level, cx, cz)) joinedZone++;
            else { deepluckyblock.util.SafeSurface.reRequest(level, cx, cz); reRequested++; }
        }
        if (retry % 6 == 0 || retry == MAX_MISS_RETRIES - 20) {
            deepluckyblock.util.DebugLog.structure(
                    "{} : T82 -- {} colonne(s) attendent encore un chunk ({} ajout(s) zone, {} re-demande(s)) -- reprise {}/{}",
                    label, missed.size(), joinedZone, reRequested, retry + 1, MAX_MISS_RETRIES);
        }
    }

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

    // ==================================================================
    // T-HABILL.1 : ZONE ORDONNEE PAR CHUNK + NEIGE PAR CHUNK (scaffolding)
    // ==================================================================
    // Programme d'habillage de surface (docs/GUIDE_COMPLET_TERRAIN_ET_SURFACE.md,
    // livres IV et VIII, commits 1 et 2 du plan). Ce bloc est du SCAFFOLDING :
    // rien n'est encore appele, aucun comportement ne change. Deux ecarts
    // ASSUMES par rapport au texte du guide (consignes dans le journal du
    // 27/09) : (1) pas de nouvelle classe ChunkPass -- les gardes du mod
    // (ChunkKeeper.keep, TerrainChain.heartbeat, zoneReady, reprise T70,
    // budget adaptatif) vivent dans ColumnPass/startColumnPass, qui pilotera
    // l'habillage avec la liste chunk-major ci-dessous ; (2) pas de
    // WORLD_SURFACE_WG pour l'habillage post-edition -- c'est un instantane
    // de worldgen, on garde SafeSurface (garde T7).

    /**
     * Region d'habillage ordonnee PAR CHUNK : la zone (etendue de outerRing,
     * centre-inclus) est decoupee en 256-colonnes par chunk, dans l'ordre
     * x/z croissant. Le guide motive l'ordre chunk-major par (a) la lisibilite
     * des compteurs au grappe, (b) les caches par chunk (palette D5, neige,
     * plantes sauvees) -- une action par colonne de startColumnPass y accede
     * en O(1). Jamais d'attente : un chunk absent est simplement saute (pile).
     */
    private static final class ChunkMajorZone {
        final ServerLevel level;
        final BlockPos centre;
        final int outerRing;
        final int x0, z0, x1, z1;
        private final List<int[]> chunkMajorCols = new ArrayList<>();
        private final Map<Long, List<int[]>> byChunk = new LinkedHashMap<>();

        ChunkMajorZone(ServerLevel level, BlockPos centre, BlockPos min, BlockPos max, int outerRing) {
            this.level = level; this.centre = centre; this.outerRing = outerRing;
            x0 = Math.min(centre.getX(), min.getX() - outerRing);
            z0 = Math.min(centre.getZ(), min.getZ() - outerRing);
            x1 = Math.max(centre.getX(), max.getX() + outerRing);
            z1 = Math.max(centre.getZ(), max.getZ() + outerRing);
            int cx0 = x0 >> 4, cz0 = z0 >> 4, cx1 = x1 >> 4, cz1 = z1 >> 4;
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cz = cz0; cz <= cz1; cz++) {
                    if (!level.hasChunk(cx, cz)) continue;   // pile : jamais attendre un chunk
                    java.util.List<int[]> cols = new ArrayList<>(256);
                    for (int bx = 0; bx < 16; bx++)
                        for (int bz = 0; bz < 16; bz++) {
                            int x = (cx << 4) | bx, z = (cz << 4) | bz;
                            if (x < x0 || x > x1 || z < z0 || z > z1) continue;
                            int[] c = {x, z};
                            cols.add(c);
                            chunkMajorCols.add(c);
                        }
                    if (!cols.isEmpty()) byChunk.put(net.minecraft.world.level.ChunkPos.asLong(cx, cz), cols);
                }
        }

        /** Colonnes de la zone, ordre chunk-major (pour startColumnPass). */
        List<int[]> chunkMajorColumns() { return chunkMajorCols; }
        /** Découpe par chunk (cle = ChunkPos.asLong), pour les compteurs par chunk. */
        Map<Long, List<int[]>> byChunk() { return byChunk; }
        int size() { return chunkMajorCols.size(); }
        boolean isInside(int x, int z) { return x >= x0 && x <= x1 && z >= z0 && z <= z1; }
    }

    /**
     * T-HABILL.2 : neige prise une seule fois par CHUNK puis servie du cache
     * (le guide chiffre le cout de l'ancienne lecture a ~37 microsecondes par
     * colonne, soit ~1,05 s sur 28 444 colonnes -- la dette T71). Utilise
     * coldEnoughToSnow (API 1.21.1 verifiee) ; l'ile aux champignons reste
     * un cas special (biome froid sans neige). Cache memoire d'une visite :
     * invalide (clearSnowCellCache) entre deux passes d'habillage.
     */
    private static final Map<Long, Boolean> SNOWY_CELL_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean isSnowyCell(ServerLevel level, BlockPos pos) {
        long key = net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        Boolean cached = SNOWY_CELL_CACHE.get(key);
        if (cached != null) return cached;
        boolean snowy;
        try {
            var biome = level.getBiome(pos);
            snowy = !biome.is(net.minecraft.world.level.biome.Biomes.MUSHROOM_FIELDS)
                    && biome.value().coldEnoughToSnow(pos);
        } catch (Throwable t) {
            snowy = false;   // pile : jamais planter sur un biome illisible
        }
        SNOWY_CELL_CACHE.put(key, snowy);
        return snowy;
    }

    /** Invalide le cache de neige (entre deux habillages, avant reprise). */
    private static void clearSnowCellCache() {
        SNOWY_CELL_CACHE.clear();
    }

    // ==================================================================
    // T-HABILL.4 : PALETTE DE SURFACE (guide, LIVRE IV -- invariant I1)
    // ==================================================================
    // Bloc de surface dominant + sous-sol, echantillonnes AVANT toute edition
    // du terrain (pre-marrons, pre-smooth) puis CONGELES jusqu'a la fin de la
    // chaine : apres le lissage on ne lirait que de la pierre reconstruite et
    // la palette vaudrait « stone » partout. Ce commit ne fait qu'ECHANTILLONNER
    // et JOURNALISER (aucun setBlock, aucun appel d'habillage) : c'est la mesure
    // de la future passe dressAndPlant, qui s'en servira pour decider a quoi
    // ressemble le sol naturel de chaque chunk.
    // Adaptation assumee au code du guide : lectures via readBlock (T71) et
    // SafeSurface (garde T7) au lieu du getChunk brut ; le witness y4=14 reste
    // a l'interieur des bords du chunk.

    /** Palette d'un chunk : bloc de surface dominant + sous-sol associe. */
    private record SurfacePalette(Block top, Block filler, int samples, boolean confident) { }

    /** Cache par chunk (cle ChunkPos.asLong), vide entre deux chaines. */
    private static final Map<Long, SurfacePalette> PALETTE = new HashMap<>();
    /** Replis sur voisin (echantillon maigre) -- journalise : taux eleve = trop tard. */
    private static int PALETTE_FALLBACKS = 0;
    /** Temoins : grille 4x4, evite les bords du chunk (derniers biomes mixtes). */
    private static final int[] WITNESS = { 2, 6, 10, 14 };
    /** Témoins exploitables minimum pour se declarer confiant. */
    private static final int PALETTE_MIN_SAMPLES = 8;

    /** Sous-sol associe a une surface. null = sol inconnu (modde) : conserver dessous. */
    private static Block fillerFor(Block top) {
        if (top == Blocks.SAND)          return Blocks.SANDSTONE;
        if (top == Blocks.RED_SAND)      return Blocks.RED_SANDSTONE;
        if (top == Blocks.GRASS_BLOCK)   return Blocks.DIRT;
        if (top == Blocks.PODZOL)        return Blocks.DIRT;
        if (top == Blocks.MYCELIUM)      return Blocks.DIRT;
        if (top == Blocks.COARSE_DIRT)   return Blocks.DIRT;
        if (top == Blocks.MOSS_BLOCK)    return Blocks.DIRT;
        if (top == Blocks.GRAVEL)        return Blocks.STONE;
        if (top == Blocks.SNOW_BLOCK)    return Blocks.DIRT;
        return null;                      // inconnu : CONSERVER l'existant
    }

    /** Un bloc qui compte comme « sol » pour la palette. */
    private static boolean isGroundCandidate(BlockState s) {
        return s.blocksMotion() && isNaturalTerrain(s)
                && !isLog(s) && !isLeaf(s)
                && !s.is(Blocks.SNOW_BLOCK) && !s.is(Blocks.ICE)
                && !s.is(Blocks.PACKED_ICE) && !s.is(Blocks.BLUE_ICE);
    }

    /** Couverture a traverser sans conclure : air, eau, neige fine, vegetation. */
    private static boolean isSkippableCover(BlockState s) {
        return s.isAir() || s.is(Blocks.WATER) || s.is(Blocks.SNOW)
                || isLeaf(s) || isLog(s) || !s.blocksMotion();
    }

    /**
     * Echantillonne le sol naturel d'un chunk (16 témoins, descente borne par le
     * plancher). A APPELER AVANT TOUTE EDITION. Jamais de getChunk sur un chunk
     * absent : repli grass/dirt non confiant (pile : jamais attendre).
     */
    private static SurfacePalette samplePalette(ServerLevel level, int cx, int cz) {
        long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
        SurfacePalette cached = PALETTE.get(key);
        if (cached != null) return cached;
        if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, cx << 4, cz << 4)) {
            // T145 : un repli provisoire ne doit jamais empoisonner le cache.
            // Quand le chunk devient FULL, dressAndPlant doit pouvoir lire son vrai
            // biome/sol et fondre correctement la frontiere entre deux chunks.
            return new SurfacePalette(Blocks.GRASS_BLOCK, Blocks.DIRT, 0, false);
        }
        ChunkAccess chunk = level.getChunk(cx, cz);
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        Map<Block, Integer> tally = new HashMap<>();
        int used = 0;
        int floor = level.getMinBuildHeight() + 8;
        for (int lx : WITNESS) {
            for (int lz : WITNESS) {
                int x = (cx << 4) + lx, z = (cz << 4) + lz;
                int y = deepluckyblock.util.SafeSurface.height(
                        level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                while (y > floor) {
                    BlockState s = readBlock(level, chunk, mut, x, y, z);
                    if (isGroundCandidate(s)) { tally.merge(s.getBlock(), 1, Integer::sum); used++; break; }
                    if (!isSkippableCover(s)) break;   // bloc non-sol et non traversable
                    y--;
                }
            }
        }
        Block top = Blocks.GRASS_BLOCK;
        int best = 0;
        for (Map.Entry<Block, Integer> e : tally.entrySet())
            if (e.getValue() > best) { best = e.getValue(); top = e.getKey(); }
        SurfacePalette pal = new SurfacePalette(top, fillerFor(top), used,
                used >= PALETTE_MIN_SAMPLES);
        PALETTE.put(key, pal);
        return pal;
    }

    /** Palette CONFIANTE la plus proche (spirale, rayon 3 chunks). Point d'entree unique. */
    private static SurfacePalette paletteAt(ServerLevel level, int cx, int cz) {
        SurfacePalette p = samplePalette(level, cx, cz);
        if (p.confident()) return p;
        // T97/C3 (rapporte en jeu : « un damier 1 chunk sable / 1 chunk dirt,
        // sans fondu ») : l'ancienne regle « premier chunk confiant dans la
        // spirale » choisissait un voisin QUELCONQUE, different d'un chunk a
        // l'autre -> alternance au chunk (5 568 replis mesures sur dragon). On
        // elit desormais la MAJORITE des palettes confiantes de l'anneau le plus
        // proche qui en possede (egalite : premiere dans l'ordre de balayage,
        // deterministe grace a LinkedHashMap). Une zone continue obtient alors
        // la meme palette de repli sur toute sa largeur -- plus de damier ; le
        // fondu interieur (pickGround/edgeP) fait le reste.
        for (int r = 1; r <= 3; r++) {
            java.util.Map<SurfacePalette, Integer> votes = new java.util.LinkedHashMap<>();
            SurfacePalette best = null; int bestCount = 0;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // anneau seul
                    SurfacePalette q = samplePalette(level, cx + dx, cz + dz);
                    if (!q.confident()) continue;
                    int c = votes.merge(q, 1, Integer::sum);
                    if (c > bestCount) { bestCount = c; best = q; }
                }
            if (best != null) { PALETTE_FALLBACKS++; return best; }
        }
        return p;   // rien de confiant a 3 chunks : on garde le defaut
    }

    /** Vide la palette en fin de chaine (avec la neige). */
    private static void clearPaletteCache() {
        PALETTE.clear();
        PALETTE_FALLBACKS = 0;
    }

    /**
     * T-HABILL.4 (mesure seule) : echantillonne les chunks de la zone et JOURNALISE
     * la repartition des dominants. Aucun setBlock. Appele au tout debut de
     * prepZone, juste apres scanTrees -- AVANT le premier setBlock de la chaine.
     */
    private static void clearDressingCaches() {
        clearPaletteCache();
        clearSnowCellCache();
        RESCUE.clear();
        rescueTotal = 0;
    }

    // ==================================================================
    // T-HABILL.7 : SAUVETAGE DES PLANTES NON COUVERTES (guide, LIVRE VII)
    // ==================================================================
    // naturalize ne refait ni cactus, ni canne a sucre, ni plantes doubles
    // (tournesols, roses, pivoines), ni lichen, ni plantes moddees : elles
    // etaient detruites par le remodelage sans remplacement. Le sauvetage
    // est un INVENTAIRE (Block, quantite) par chunk, rempli au fil de
    // scanTrees (sur des blocs deja lus) puis repose a la fin de la passe
    // d'habillage, a des emplacements VALIDES — la seule validite etant
    // canSurvive, jamais une table sol↔plante ecrite a la main.

    /** Inventaire par chunk (cle ChunkPos.asLong) : espece -> quantite sauvee. */
    private static final Map<Long, Map<Block, Integer>> RESCUE = new HashMap<>();
    private static int rescueTotal = 0;
    private static final int RESCUE_MAX_PER_CHUNK = 64;
    private static final int RESCUE_MAX_TOTAL = 20_000;
    private static final int RESCUE_MAX_TRIES = 512;   // par espece et par chunk

    /** Plantes que l'ancien naturalize savait poser — exclues du sauvetage. */
    private static Set<Block> knownByNaturalize() {
        Set<Block> s = new HashSet<>();
        s.add(Blocks.SHORT_GRASS);  s.add(Blocks.TALL_GRASS);
        s.add(Blocks.FERN);          s.add(Blocks.LARGE_FERN);
        s.add(Blocks.DANDELION);     s.add(Blocks.POPPY);
        s.add(Blocks.BROWN_MUSHROOM); s.add(Blocks.RED_MUSHROOM);
        s.add(Blocks.DEAD_BUSH);
        return s;
    }

    /** Plante qui pousse sur le sol (par opposition a mur / plafond / eau). */
    private static boolean isGroundPlant(BlockState s) {
        if (!s.getFluidState().isEmpty()) return false;                  // aquatique
        if (s.is(net.minecraft.tags.BlockTags.CLIMBABLE)) return false;  // lianes, echelles
        Block b = s.getBlock();
        if (b == Blocks.SPORE_BLOSSOM || b == Blocks.HANGING_ROOTS) return false;
        if (b == Blocks.CACTUS || b == Blocks.SUGAR_CANE || b == Blocks.BAMBOO
                || b == Blocks.DEAD_BUSH || b == Blocks.SWEET_BERRY_BUSH) return true;
        if (b instanceof net.minecraft.world.level.block.DoublePlantBlock) return true;
        // Filet general, couvre les mods : une BushBlock se pose sur un sol.
        return b instanceof net.minecraft.world.level.block.BushBlock;
    }

    /** Une plante de SOL que naturalize ne sait pas refaire. */
    private static boolean isRescuable(BlockState s) {
        Block b = s.getBlock();
        if (knownByNaturalizeStatic().contains(b)) return false;      // deja couvert
        if (!isGroundPlant(s)) return false;                          // mur / plafond / eau
        // Moities HAUTES des plantes doubles : on ne compte que la moitie basse,
        // sinon chaque tournesol serait compte DEUX fois.
        if (b instanceof net.minecraft.world.level.block.DoublePlantBlock
                && s.getValue(net.minecraft.world.level.block.DoublePlantBlock.HALF)
                   == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
            return false;
        return true;
    }

    private static volatile Set<Block> KNOWN_NATURALIZE = null;
    private static Set<Block> knownByNaturalizeStatic() {
        Set<Block> s = KNOWN_NATURALIZE;
        if (s == null) { s = knownByNaturalize(); KNOWN_NATURALIZE = s; }
        return s;
    }

    /** Comptabilise, avec les deux plafonds de securite. */
    private static void noteRescue(int x, int z, BlockState s) {
        if (rescueTotal >= RESCUE_MAX_TOTAL) return;
        long key = net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4);
        Map<Block, Integer> inv = RESCUE.computeIfAbsent(key, k -> new HashMap<>());
        int already = 0;
        for (int v : inv.values()) already += v;
        if (already >= RESCUE_MAX_PER_CHUNK) return;
        inv.merge(s.getBlock(), 1, Integer::sum);
        rescueTotal++;
    }

    /** Ordre de visite deterministe mais disperse (permutation bijective par chunk). */
    private static int scramble(int n, int cx, int cz) {
        int k = (int) (posNoise(cx, cz, 0x2C1B) * 251) | 1;   // impair -> bijection
        return (n * k + (cx * 7 + cz * 13)) & 255;
    }

    /** Pose une plante sauvee si l'emplacement la supporte (gere piles et doubles). */
    private static boolean tryPlaceRescued(ServerLevel level, BlockPos.MutableBlockPos mut,
                                           Block plant, int x, int surfY, int z) {
        mut.set(x, surfY + 1, z);
        if (!level.getBlockState(mut).isAir()) return false;
        BlockState base = plant.defaultBlockState();
        if (!base.canSurvive(level, mut)) return false;      // LA seule verification

        if (plant instanceof net.minecraft.world.level.block.DoublePlantBlock) {
            mut.set(x, surfY + 2, z);
            if (!level.getBlockState(mut).isAir()) return false;
            // FAST_FLAG (pas de mise a jour de voisinage) : sans lui, poser la
            // moitie basse declenche une mise a jour qui la casse aussitot.
            level.setBlock(mut.set(x, surfY + 1, z), base.setValue(
                    net.minecraft.world.level.block.DoublePlantBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), FAST_FLAG);
            level.setBlock(mut.set(x, surfY + 2, z), base.setValue(
                    net.minecraft.world.level.block.DoublePlantBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), FAST_FLAG);
            return true;
        }

        // Plante en pile : cactus, canne a sucre, bambou. Hauteur deterministe,
        // jamais au-dela de la hauteur naturelle.
        int height = 1;
        if (plant == Blocks.CACTUS || plant == Blocks.SUGAR_CANE)
            height = 1 + (int) (posNoise(x, z, SALT_STACK) * 3);        // 1..3
        else if (plant == Blocks.BAMBOO)
            height = 2 + (int) (posNoise(x, z, SALT_STACK) * 6);        // 2..7
        for (int h = 0; h < height; h++) {
            mut.set(x, surfY + 1 + h, z);
            if (!level.getBlockState(mut).isAir()) break;
            level.setBlock(mut, base, FAST_FLAG);
        }
        return true;
    }

    /**
     * Repose les plantes sauvees d'un chunk a des emplacements VALIDES,
     * jamais a leur position d'origine (le terrain a change).
     * Le CACTUS passe en DERNIER : il casse si un bloc solide apparait a
     * cote apres sa pose (guide, section 52.2).
     */
    private static int replantRescued(ServerLevel level, int cx, int cz) {
        long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
        Map<Block, Integer> inv = RESCUE.get(key);
        if (inv == null || inv.isEmpty()) return 0;
        List<Map.Entry<Block, Integer>> ordered = new ArrayList<>(inv.entrySet());
        ordered.sort((a, b) -> Boolean.compare(a.getKey() == Blocks.CACTUS,
                                               b.getKey() == Blocks.CACTUS));
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int placed = 0;
        for (Map.Entry<Block, Integer> e : ordered) {
            Block plant = e.getKey();
            int wanted = e.getValue();
            int tries = 0;
            for (int n = 0; n < 256 && wanted > 0 && tries < RESCUE_MAX_TRIES; n++) {
                int idx = scramble(n, cx, cz);
                int lx = idx & 15, lz = idx >> 4;
                int x = (cx << 4) + lx, z = (cz << 4) + lz;
                int surfY = deepluckyblock.util.SafeSurface.height(
                        level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (surfY < 1) continue;
                tries++;
                if (tryPlaceRescued(level, mut, plant, x, surfY, z)) { wanted--; placed++; }
            }
        }
        RESCUE.remove(key);
        return placed;
    }

    /**
     * Passe de repose globale : un « colonne » synthetique par chunk porteur,
     * pilotee par startColumnPass (gardes du mod). Appelee APRES dressAndPlant,
     * donc APRES les decors disperses et les arbres replantes (ordre voulu
     * par le guide : le cactus est pose en tout dernier).
     */
    private static void replantAllRescued(ServerLevel level, Runnable onDone) {
        List<int[]> carriers = new ArrayList<>();
        for (long key : RESCUE.keySet()) {
            int cx = net.minecraft.world.level.ChunkPos.getX(key);
            int cz = net.minecraft.world.level.ChunkPos.getZ(key);
            carriers.add(new int[]{cx, cz});
        }
        if (carriers.isEmpty()) { if (onDone != null) onDone.run(); return; }
        final int[] placed = {0};
        startColumnPass(level, "repose des plantes sauvees", carriers, 8, col -> {
            placed[0] += replantRescued(level, col[0], col[1]);
        }, () -> {
            if (placed[0] > 0) deepluckyblock.util.DebugLog.structure(
                    "repose des plantes sauvees : {} replantees sur {} chunks porteurs",
                    placed[0], carriers.size());
            RESCUE.clear();
            rescueTotal = 0;
            if (onDone != null) onDone.run();
        });
    }

    /**
     * Chemin unique du couple habillage + vegetation (LIVRE IX) :
     * dressAndPlant + repose des sauvees quand le drapeau est leve ;
     * verifyGrassSurface + naturalize historiques sinon. Meme onDone.
     */
    private static void dressOrLegacy(ServerLevel level, BlockPos min, BlockPos max,
                                      boolean postPaste, Runnable onDone) {
        if (dressingEnabled()) {
            dressAndPlant(level, min, max, postPaste, () -> replantAllRescued(level, onDone));
            return;
        }
        verifyGrassSurface(level, min, max,
                () -> naturalize(level, min, max, level.getRandom(), onDone));
    }

    /**
     * T-HABILL.4 (mesure seule) : echantillonne les chunks de la zone et JOURNALISE
     * la repartition des dominants. Aucun setBlock. Appele au tout debut de
     * prepZone, juste apres scanTrees -- AVANT le premier setBlock de la chaine.
     */
    private static void sampleZonePalettes(ServerLevel level, BlockPos min, BlockPos max) {
        int ring = terrainRing() + NATURALIZE_EXTRA_RING;
        int cx0 = (min.getX() - ring) >> 4, cz0 = (min.getZ() - ring) >> 4;
        int cx1 = (max.getX() + ring) >> 4, cz1 = (max.getZ() + ring) >> 4;
        int chunks = 0, confident = 0, fallbacksBefore = PALETTE_FALLBACKS;
        Map<Block, Integer> dominants = new HashMap<>();
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++) {
                SurfacePalette p = paletteAt(level, cx, cz);
                chunks++;
                if (p.confident()) confident++;
                dominants.merge(p.top(), 1, Integer::sum);
            }
        if (!deepluckyblock.util.DebugLog.STRUCTURE) return;   // journal desactive : ne pas construire la ligne
        StringBuilder sb = new StringBuilder();
        dominants.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> sb.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(e.getKey()).getPath()).append('(').append(e.getValue()).append(") "));
        deepluckyblock.util.DebugLog.structure(
                "palette : {} chunks echantillonnes, {} confiants, {} repli, dominants = {}",
                chunks, confident, PALETTE_FALLBACKS - fallbacksBefore, sb.toString().trim());
    }

    // ==================================================================
    // RUNS 7-9/10 : PASS x2 puis FAIL machine-lente (everest 29,4/29,8/36,0).
    // BILAN 27/09 (tests 5-6/10, CI memes seed/coords) : dressAndPlant 0,2-0,3 s
    // par structure (budget guide 1 s), fixtures ALL PASS x4, latence des
    // structures = bruit machine (+-20 %), preload vanille 60-93 % du chrono.
    // ==================================================================
    // T-HABILL.5 : dressAndPlant -- HABILLAGE + VEGETATION EN UNE PASSE
    // ==================================================================
    // Programme du guide (docs/a-lire-guide-complet.txt, LIVRES IV a VIII).
    // Remplace verifyGrassSurface + naturalize : la DECISION se fonde sur le
    // bloc de surface REELLEMENT pose (palette echantillonnee avant edition,
    // degrade trame probabiliste en bande bordee, warping de la frontiere),
    // et la vegetation (densites de gout + canSurvive) est posee dans la MEME
    // descente de colonne.
    //
    // Cette etape livre la MACHINERIE COMPLETE, derriere un FASTFLAG
    // (dressingEnabled()). Avec le drapeau coupe, RIEN ne s'execute —
    // dressAndPlant ne sera branche en lieu et place de verifyGrassSurface +
    // naturalize qu'a l'etape suivante, apres mesure de recette (les 20
    // controles du guide, LIVRE XI).
    //
    // Adaptations assumees au texte du guide (journal du 27/09) :
    //  - pas de nouvelle classe ChunkPass : startColumnPass/ColumnPass pilote
    //    deja ChunkKeeper.keep, heartbeat, zoneReady, reprise T70, budget
    //    adaptatif — la liste chunk-major vient de ChunkMajorZone ;
    //  - pas de WORLD_SURFACE_WG (instantane worldgen) : SafeSurface ;
    //  - neige via coldEnoughToSnow (T-HABILL.2) au lieu de la chaine ;
    //  - le cri de posNoise est celui du guide, eprouve par ses mesures
    //    (moyenne 0,4985, aucun motif en bande, aucune periodicite).

    /** PALETTE_FALLBACKS lit dans le journal dressAndPlant. */
    private static int paletteFallbacksSoFar() { return PALETTE_FALLBACKS; }

    /** Drapeau de branchement de l'habillage (T-HABILL, programme du guide,
     *  v1.0 du 27/09/2026 : palette + degrade trame + warping + vegetation
     *  decidee par le sol final + sauvetage). FALSE = chemin historique intact. */
    private static boolean dressingEnabled() {
        return DRESSING_FASTFLAG;
    }
    private static final boolean DRESSING_FASTFLAG = true;

    // ---- Tramage : hash de position (guide §39, qualite mesuree) ----
    private static final int SALT_GROUND = 0x51D0;
    private static final int SALT_PLANT  = 0x91A7;
    private static final int SALT_PICK   = 0x3C29;
    private static final int SALT_STACK  = 0x7B41;
    private static final int SALT_WARP   = 0x6A19;

    /** Hash de position -> [0,1). Deterministe, sans allocation. */
    private static float posNoise(int x, int z, int salt) {
        int h = x * 0x27d4eb2d ^ z * 0x85ebca6b ^ salt * 0x165667b1;
        h ^= h >>> 15; h *= 0x2545f491; h ^= h >>> 13;
        return (h & 0x00ffffff) / (float) 0x01000000;
    }

    // ---- Degrade entre deux sols (guide §35-§36) ----
    private static final int BLEND_BAND = 5;

    /** Probabilite de prendre le voisin, selon la distance au bord. */
    private static float edgeP(int distance) {
        if (distance <= 0 || distance > BLEND_BAND) return 0f;
        return (BLEND_BAND + 1 - distance) * 0.5f / BLEND_BAND;   // 1 -> 50 %, 5 -> 10 %
    }

    // ---- Warping de la frontiere (guide §43, contrainte WARP_AMP+BLEND_BAND<=16) ----
    private static final int   WARP_CELL = 12;
    private static final float WARP_AMP  = 4.0f;

    private static float dressSmoothstep(float t) { return t * t * (3f - 2f * t); }

    /** Bruit de valeur 2D interpole -- basses frequences, sans allocation. */
    private static float dressValueNoise(int x, int z, int cell, int salt) {
        int gx = Math.floorDiv(x, cell), gz = Math.floorDiv(z, cell);
        float fx = dressSmoothstep(Math.floorMod(x, cell) / (float) cell);
        float fz = dressSmoothstep(Math.floorMod(z, cell) / (float) cell);
        float n00 = posNoise(gx,     gz,     salt), n10 = posNoise(gx + 1, gz,     salt);
        float n01 = posNoise(gx,     gz + 1, salt), n11 = posNoise(gx + 1, gz + 1, salt);
        return (n00 * (1 - fx) + n10 * fx) * (1 - fz) + (n01 * (1 - fx) + n11 * fx) * fz;
    }

    /** Distance au bord PERTURBEE : la frontiere serpente au lieu d'etre droite. */
    private static float effectiveDistance(int d, int x, int z, int salt) {
        return d - (dressValueNoise(x, z, WARP_CELL, salt) - 0.5f) * 2f * WARP_AMP;
    }

    /**
     * UN SEUL tirage sur TOUS les voisins reellement differents (guide §40).
     * Indices NOMMES dans nb (jamais ecrits a la main).
     */
    private static Block pickGround(SurfacePalette local, SurfacePalette[] nb,
                                    int x, int z, int lx, int lz) {
        // Ordre de remplissage : dx = -1..1 puis dz = -1..1, centre exclu :
        //   0 (-1,-1)   1 (-1, 0) OUEST   2 (-1,+1)
        //   3 ( 0,-1) NORD                4 ( 0,+1) SUD
        //   5 (+1,-1)   6 (+1, 0) EST     7 (+1,+1)
        final int W = 1, N = 3, S = 4, E = 6;
        float dW = effectiveDistance(lx + 1,  x, z, SALT_WARP);
        float dE = effectiveDistance(16 - lx, x, z, SALT_WARP + 1);
        float dN = effectiveDistance(lz + 1,  x, z, SALT_WARP + 2);
        float dS = effectiveDistance(16 - lz, x, z, SALT_WARP + 3);
        float pWest  = nb[W] != null ? edgeP(Math.round(dW)) : 0f;
        float pEast  = nb[E] != null ? edgeP(Math.round(dE)) : 0f;
        float pNorth = nb[N] != null ? edgeP(Math.round(dN)) : 0f;
        float pSouth = nb[S] != null ? edgeP(Math.round(dS)) : 0f;
        float total = pWest + pEast + pNorth + pSouth;
        if (total <= 0f) return local.top();
        if (total > 1f) {                       // coin : renormalisation
            pWest /= total; pEast /= total; pNorth /= total; pSouth /= total;
            total = 1f;
        }
        float r = posNoise(x, z, SALT_GROUND);
        if (r < pWest)                   return nb[W].top();
        if (r < pWest + pEast)           return nb[E].top();
        if (r < pWest + pEast + pNorth)  return nb[N].top();
        if (r < total)                   return nb[S].top();
        return local.top();
    }

    // ---- Vegetation ordinaire (guide §45-§47) ----

    /** Table de GOUT : quantite de vegetation par sol final. 0,00 = jamais deviner. */
    private static float densityFor(Block ground) {
        if (ground == Blocks.GRASS_BLOCK) return 0.30f;
        if (ground == Blocks.PODZOL)      return 0.20f;
        if (ground == Blocks.MYCELIUM)    return 0.15f;
        if (ground == Blocks.SAND)        return 0.02f;
        if (ground == Blocks.RED_SAND)    return 0.02f;
        return 0f;   // gravier, neige, inconnu : rien — ne jamais deviner
    }

    /** Table de GOUT : lesquelles, par sol final. null = rien ici. */
    private static BlockState pickPlant(Block ground, float r) {
        if (ground == Blocks.GRASS_BLOCK) {
            if (r < 0.42f) return Blocks.SHORT_GRASS.defaultBlockState();
            if (r < 0.58f) return Blocks.FERN.defaultBlockState();
            if (r < 0.68f) return Blocks.TALL_GRASS.defaultBlockState();
            if (r < 0.76f) return Blocks.LARGE_FERN.defaultBlockState();
            // T185 : palette complète plutôt que le couple visuellement
            // dominant dandelion/poppy. Les doubles plantes restent prises en
            // charge par plantOn et canSurvive tranche selon le biome/sol réel.
            if (r < 0.80f) return Blocks.DANDELION.defaultBlockState();
            if (r < 0.84f) return Blocks.POPPY.defaultBlockState();
            if (r < 0.87f) return Blocks.BLUE_ORCHID.defaultBlockState();
            if (r < 0.90f) return Blocks.ALLIUM.defaultBlockState();
            if (r < 0.93f) return Blocks.AZURE_BLUET.defaultBlockState();
            if (r < 0.95f) return Blocks.RED_TULIP.defaultBlockState();
            if (r < 0.97f) return Blocks.OXEYE_DAISY.defaultBlockState();
            if (r < 0.985f) return Blocks.CORNFLOWER.defaultBlockState();
            return Blocks.LILY_OF_THE_VALLEY.defaultBlockState();
        }
        if (ground == Blocks.PODZOL) {
            if (r < 0.50f) return Blocks.FERN.defaultBlockState();
            if (r < 0.78f) return Blocks.LARGE_FERN.defaultBlockState();
            return Blocks.BROWN_MUSHROOM.defaultBlockState();
        }
        if (ground == Blocks.MYCELIUM)
            return r < 0.50f ? Blocks.BROWN_MUSHROOM.defaultBlockState()
                             : Blocks.RED_MUSHROOM.defaultBlockState();
        if (ground == Blocks.SAND || ground == Blocks.RED_SAND)
            return Blocks.DEAD_BUSH.defaultBlockState();
        return null;
    }

    /**
     * Pose une plante ordinaire si le sol final l'accepte ; la validite est
     * tranchee par canSurvive (jamais par une table sol↔plante, guide §46).
     */
    private static boolean plantOn(ServerLevel level, BlockPos.MutableBlockPos mut,
                                   int x, int surfY, int z, Block ground, long vegSeed,
                                   float densityScale) {
        float r = posNoise(x, z, SALT_PLANT);
        if (r > densityFor(ground) * Math.max(0f, Math.min(1f, densityScale))) return false;
        BlockState plant = pickPlant(ground, posNoise(x, z, SALT_PICK));
        if (plant == null) return false;
        mut.set(x, surfY + 1, z);
        if (!level.getBlockState(mut).isAir()) return false;
        if (!plant.canSurvive(level, mut)) return false;
        if (plant.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock) {
            BlockPos lower = mut.immutable();
            BlockPos upper = lower.above();
            if (!level.getBlockState(upper).isAir()) return false;
            net.minecraft.world.level.block.DoublePlantBlock.placeAt(level, plant, lower, FAST_FLAG);
        } else {
            level.setBlock(mut, plant, FAST_FLAG);
        }
        return true;
    }

    /** Couche de neige au-dessus de la surface, si le biome est enneige. */
    private static void applySnowLayer(ServerLevel level, BlockPos.MutableBlockPos mut,
                                       int x, int surfY, int z) {
        mut.set(x, surfY + 1, z);
        if (level.getBlockState(mut).isAir())
            level.setBlock(mut, Blocks.SNOW.defaultBlockState(), FAST_FLAG);
    }

    // ---- Passe principale ----
    private static final int SUBSOIL_DEPTH = 3;

    /**
     * HABILLAGE + VEGETATION en UNE passe par colonne (guide §61),
     * pilote par startColumnPass (gardes du mod) sur la liste chunk-major.
     *
     * @return la passe ; appelee SEULEMENT si dressingEnabled().
     */
    /**
     * T92 (B13) : TRUE si une colonne PROTEGEE (emprise de la structure) est en
     * fait restee A CIEL OUVERT au-dessus d'un sol NATUREL — donc habillable
     * (dressAndPlant) et plantable (replantTrees).
     *
     * <p>Cause racine du « tu as oublie de remettre de la terre sur le dessus
     * du terrain original / de replanter arbres, plantes, vegetations »
     * (crimsonlake, 29/09) : le ras du footprint est EXCLUSIF (stone exposee
     * partout) et le lac de crimson ne couvre qu'une fraction de son enorme
     * rectangle (243 x 292) : tout le reste du rectangle restait nu et sans
     * vegetation, parce que l'emprise protegee etait entierement sautee par
     * dressAndPlant et replantTrees.
     *
     * <p>Discriminant, sans generation de chunk :
     * <ol>
     *   <li>{@code mb} = plus haut bloqueur (hors feuilles). Si ce bloc n'est
     *       PAS un terrain naturel (planches, brique, vitre...), la colonne
     *       porte de la STRUCTURE -> false ;</li>
     *   <li>tous les blocs entre {@code mb} et le sommet WORLD_SURFACE doivent
     *       etre de l'air, de la vegetation ou de la neige fine ; un bloc
     *       d'eau (lac reel) ou tout autre masse -> false.</li>
     * </ol>
     */
    private static boolean footprintColumnDressable(ServerLevel level, ChunkAccess chunk,
                                                    BlockPos.MutableBlockPos mut, int x, int z) {
        int mb = deepluckyblock.util.SafeSurface.height(
                level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (mb < 1) return false;
        BlockState ground = readBlock(level, chunk, mut, x, mb, z);
        if (!isNaturalTerrain(ground) || !ground.blocksMotion()) return false;
        int ws = Math.min(mb + 48,
                deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z) - 1);
        for (int y = mb + 1; y <= ws; y++) {
            BlockState s = readBlock(level, chunk, mut, x, y, z);
            if (s.isAir() || isVegetation(s) || s.is(Blocks.SNOW)) continue;
            return false;   // eau, decor construit, structure : pas une colonne a habiller
        }
        return true;
    }

    private static void dressAndPlant(ServerLevel level, BlockPos min, BlockPos max,
                                      boolean postPaste, Runnable onDone) {
        if (!dressingEnabled()) { if (onDone != null) onDone.run(); return; }
        final int innerRing = terrainRing();
        final int outerRing = terrainRing() + NATURALIZE_EXTRA_RING;
        final ChunkMajorZone zone = new ChunkMajorZone(level, new BlockPos(
                (min.getX() + max.getX()) / 2, min.getY(), (min.getZ() + max.getZ()) / 2),
                min, max, outerRing);

        deepluckyblock.util.ChunkKeeper.keep(level);

        final long vegSeed = level.getSeed()
                ^ ((long) min.getX() * 912931L) ^ ((long) min.getZ() * 182883L) ^ 0x5EEDL;

        // 0 fastChunks | 1 blendChunks | 2 surfaceSet | 3 fillerSet
        // 4 plantes    | 5 sautees     | 6 sauvees reposees
        final int[] stats = new int[7];

        // T120 : 282 tranches / 8,1 s mesurees ; moitié moins d'attentes.
        startColumnPass(level, "dressAndPlant", zone.chunkMajorColumns(), BATCH_COLS * 2, col -> {
            int x = col[0], z = col[1];
            int cx = x >> 4, cz = z >> 4;
            int lx = x & 15, lz = z & 15;
            if (StructureScatterDecor.isInsideDecor(x, z, 1)) { stats[5]++; return; }
            boolean protectedColumn = isProtected(x, z);
            // T98 (rapporte en jeu : « des blocs de ma giga structure ont ete
            // changes en grass block -- je crois que c'etait de la stone ») :
            // EN POST-POSE, plus JAMAIS de re-habillage de colonne de l'emprise.
            // L'exemption T92 (B13, « colonne decouverte et seche ») testait le
            // sommet actuel : la STONE d'un toit/sol de structure passe le test
            // isNaturalTerrain + « ciel ouvert au-dessus » → la colonne etait
            // re-dressee en palette (grass_block) PAR-DESSUS la structure posee.
            // Consigne dev : « ne pas integrer les switch de blocs TERRAIN avec
            // la structure » -- post-pose, l'emprise est figee, point.
            // L'exemption T92 reste valable UNIQUEMENT pre-pose (raz crimsonlake :
            // les colonnes decouvertes sont alors du terrain naturel rase).
            // T179 : dans l'emprise post-paste, ne jamais changer le SOL de la
            // structure, mais ne pas supprimer pour autant toute végétation des
            // clairières naturelles restées en grass. Le traitement végétal seul
            // est effectué plus bas à partir du vrai bloc de surface.
            boolean vegetationOnly = protectedColumn && postPaste;

            SurfacePalette pal = paletteAt(level, cx, cz);
            ChunkAccess chunk = deepluckyblock.util.SafeSurface.chunkFor(level, cx, cz);
            if (chunk == null) { stats[5]++; return; }
            BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
            // T92 (B13) : une colonne de l'emprise laissee a ciel ouvert et
            // seche (raz crimsonlake) est RE-HABILLEE comme le pourtour ;
            // une colonne couverte par la structure (ou sous son eau) reste
            // sautee, comme historiquement.
            if (protectedColumn && !footprintColumnDressable(level, chunk, mut, x, z)) { stats[5]++; return; }
            boolean snowy = isSnowyCell(level, new BlockPos(x, 80, z));

            int surfY = deepluckyblock.util.SafeSurface.height(
                    level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            if (surfY < 1) { stats[5]++; return; }

            boolean inner = x >= min.getX() - innerRing && x <= max.getX() + innerRing
                         && z >= min.getZ() - innerRing && z <= max.getZ() + innerRing;
            BlockState actualSurface = readBlock(level, chunk, mut, x, surfY, z);
            Block chosen = vegetationOnly ? actualSurface.getBlock() : pal.top();

            if (inner && !vegetationOnly) {
                // Court-circuit niveaux 1 et 2 : quels cotes different ?
                // Comparaison de BLOCK, jamais de BlockState (invariant I4).
                boolean anyDifferent = false;
                SurfacePalette[] nb = null;
                for (int dx = -1; dx <= 1 && !anyDifferent; dx++)
                    for (int dz = -1; dz <= 1 && !anyDifferent; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        if (paletteAt(level, cx + dx, cz + dz).top() != pal.top()) anyDifferent = true;
                    }
                if (anyDifferent) {
                    stats[1]++;
                    nb = new SurfacePalette[8];
                    int k = 0;
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dz == 0) continue;
                            SurfacePalette q = paletteAt(level, cx + dx, cz + dz);
                            nb[k] = (q.top() == pal.top()) ? null : q;
                            k++;
                        }
                    chosen = pickGround(pal, nb, x, z, lx, lz);
                } else {
                    stats[0]++;
                }

                BlockState cur = readBlock(level, chunk, mut, x, surfY, z);
                // Regle 6 : ne rien ecrire si c'est deja correct.
                if (cur.getBlock() != chosen && isNaturalTerrain(cur) && cur.blocksMotion()) {
                    level.setBlock(mut.set(x, surfY, z), chosen.defaultBlockState(), FAST_FLAG);
                    stats[2]++;
                }

                // T189 : après le paste, le rebuild/smooth a déjà reconstruit le
                // sous-sol. Ne pas réécrire 8–12 000 blocs identiques pendant la
                // décoration finale ; seule la surface et la végétation comptent.
                Block filler = postPaste ? null : fillerFor(chosen);
                if (filler != null) {
                    for (int d = 1; d <= SUBSOIL_DEPTH; d++) {
                        BlockState s = readBlock(level, chunk, mut, x, surfY - d, z);
                        if (!isNaturalTerrain(s) || !s.blocksMotion()) break;
                        if (s.getBlock() == filler) continue;
                        level.setBlock(mut.set(x, surfY - d, z), filler.defaultBlockState(), FAST_FLAG);
                        stats[3]++;
                    }
                }

                if (snowy) applySnowLayer(level, mut, x, surfY, z);
            }

            // Densité radiale continue : forte dans le cœur, puis ramenée à
            // 20 % au bord externe. Évite le rectangle de végétation beaucoup
            // plus dense que les chunks vanilla voisins.
            int dxEdge = x < min.getX() ? min.getX() - x : (x > max.getX() ? x - max.getX() : 0);
            int dzEdge = z < min.getZ() ? min.getZ() - z : (z > max.getZ() ? z - max.getZ() : 0);
            double edgeDist = Math.sqrt((double) dxEdge * dxEdge + (double) dzEdge * dzEdge);
            float densityScale = (float) (1.0 - 0.80 * Math.min(1.0, edgeDist / Math.max(1.0, outerRing)));
            if (plantOn(level, mut, x, surfY, z, chosen, vegSeed, densityScale)) stats[4]++;
        }, () -> {
            deepluckyblock.util.DebugLog.structure(
                    "dressAndPlant : {} chunks rapides / {} melanges, {} surfaces, "
                            + "{} sous-sols, {} plantes, {} colonnes sautees, {} replis de palette",
                    stats[0], stats[1], stats[2], stats[3], stats[4], stats[5],
                    paletteFallbacksSoFar());
            clearDressingCaches();
            if (onDone != null) onDone.run();
        });
    }

    /** Suite de smoothPass, executee une fois tous les chunks charges. */
    /** Variation maximale autorisee du lissage, en blocs, par colonne (T21). */
    private static final int MAX_SMOOTH_DELTA = 8;

    /** T20 : colonnes qui contenaient de l'eau au moment du gel (exclues du flou). */
    // ===== T103 : LA NAPPE VANILLA DE REFERENCE (memorisée par les smooths) =====
    // Consigne dev (30/09, en jeu) : « le fixwater ne reprend toujours pas la
    // meme base que vanilla et ne l'expand pas comme demande : il cree tout un
    // lac artificiel aux coordonnees du dernier bloc le plus bas de ma
    // structure, et ca expand a partir de cette hauteur y ». La nappe de
    // reference (colonnes reellement en eau dans le monde NATUREL, gel ou
    // digue comprises) est donc memorisee ici pendant les smooths (union des
    // captures), et fixLiquids est borne a CETTE nappe -- jamais au-dela.
    private static boolean[][] NATURAL_WATER = null;
    private static int NW_X0, NW_Z0, NW_W, NW_H;
    private static int NATURAL_WATER_COUNT;

    /** T103 : true = la colonne appartenait a la nappe vanilla memorisee. */
    /** T151 : prédiction partagée avec le scatter. Toute colonne appartenant à
     * la nappe photographiée peut être remise en eau par les passes finales et
     * ne doit donc jamais recevoir une mini-structure. */
    public static boolean willBeWaterColumn(int x, int z) {
        return hasNaturalWaterRef() && naturalWaterAt(x, z);
    }

    private static boolean naturalWaterAt(int x, int z) {
        if (NATURAL_WATER == null) return true;   // pas de reference : comportement historique
        int i = x - NW_X0, j = z - NW_Z0;
        if (i < 0 || j < 0 || i >= NW_W || j >= NW_H) return false;   // hors nappe connue = terre
        return NATURAL_WATER[i][j];
    }

    /** T103 : nappe connue (debug fin de fixLiquids). */
    private static boolean hasNaturalWaterRef() { return NATURAL_WATER != null; }

    /**
     * T103 : capture de la nappe vanilla AU REPOS, au tout debut du pipeline
     * (avant clearWaterPockets / digues / aplanissement). Indispensable : les
     * captures tardives des smooths sont deja post-drainage, la marge de lac
     * coupee y est seche et ne serait jamais restauree autour de la structure.
     * Coute une lecture de surface par colonne de la zone trackee (une fois).
     */
    private static void captureNaturalWater(ServerLevel level, int x0, int z0, int w, int h) {
        boolean sameStreamMatrix = NATURAL_WATER != null && NW_X0 == x0 && NW_Z0 == z0
                && NW_W == w && NW_H == h;
        boolean[][] mask = sameStreamMatrix ? NATURAL_WATER : new boolean[w][h];
        int n = sameStreamMatrix ? NATURAL_WATER_COUNT : 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int x = x0 + i, z = z0 + j;
                if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, x, z)) continue;
                int y = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (level.getBlockState(pos.set(x, y, z)).is(Blocks.WATER) && !mask[i][j]) {
                    mask[i][j] = true;
                    n++;
                }
            }
        }
        NATURAL_WATER = mask;
        NW_X0 = x0; NW_Z0 = z0; NW_W = w; NW_H = h; NATURAL_WATER_COUNT = n;
        deepluckyblock.util.DebugLog.structure(
                "nappe vanilla memorisee : {} colonne(s) d'eau sur {}x{} en {},{} -- "
                        + "fixwater borne a cette base naturelle (T103, plus de lac artificiel)",
                n, w, h, x0, z0);
    }

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
            // T103 : memorisation de la nappe vanilla (union des captures des
            // differentes passes de smooth). Zone deplacee (nouveau centre) :
            // on recommence la nappe ; sinon on fusionne par OU logique.
            if (NATURAL_WATER == null || NW_X0 != x0 || NW_Z0 != z0 || NW_W != w || NW_H != h) {
                NATURAL_WATER = new boolean[w][h];
                NW_X0 = x0; NW_Z0 = z0; NW_W = w; NW_H = h;
                for (int i = 0; i < w; i++) System.arraycopy(mask[i], 0, NATURAL_WATER[i], 0, h);
            } else {
                for (int i = 0; i < w; i++)
                    for (int j = 0; j < h; j++)
                        if (mask[i][j]) NATURAL_WATER[i][j] = true;
            }
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
        // Use the bottom-up, support-aware sweep. The former top-down scan treated
        // every grass/dirt surface below the sky as unsupported, stripping it to rock.
        sweepNaturalColumn(level, x, z);
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
    /** T24 : iterations de relaxation.
     *
     * T89 (B9) : la relaxation doit CONVERGER jusqu'a ce que TOUT ecart entre
     * voisins soit ramene a {@link #MAX_SLOPE_STEP}, pas s'arreter au bout de
     * 14 passes. Mesure en jeu du 29/09 : « pentes : plus grand ecart ramene
     * de 76 bloc(s) a 48 (limite 2) » -- il restait une paroi de 48 blocs,
     * exactement la « montagne coupee net » signalee par le dev. La boucle
     * sort des que plus aucune cellule ne bouge (coup normal : quelques
     * dizaines de passes) ; 512 est un simple plafond de securite contre
     * une oscillation pathologique. */
    private static final int SLOPE_ITERATIONS = 512;

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
                    // T121 : ne plus exclure l'emprise ; interieur et exterieur
                    // convergent dans le meme champ de pente.
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
        // T89 (B9) : la mesure inclut DESORMAIS les paires touchees par
        // l'emprise. Avant, toute paire contenant une colonne protegee etait
        // sautee -- c'est EXACTEMENT la frontiere plateau/paroi, le defaut
        // vise par le dev : le log « ramene a 2 (limite 2) » pouvait donc
        // s'afficher avec une falaise de 40 blocs toujours debout au bord.
        // La mesure reste hors colonnes d'eau (le rivage n'est pas un mur).
        int worst = 0;
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                if (waterMask != null && waterMask[i][j]) continue;
                if (i + 1 < w && (waterMask == null || !waterMask[i + 1][j]))
                    worst = Math.max(worst, Math.abs(hm[i][j] - hm[i + 1][j]));
                if (j + 1 < h && (waterMask == null || !waterMask[i][j + 1]))
                    worst = Math.max(worst, Math.abs(hm[i][j] - hm[i][j + 1]));
            }
        }
        return worst;
    }

    /** T23 : remblai maximal autorise du lissage (vers le haut), en blocs,
     *  LOIN de l'emprise (fenetre de fond T89). */
    private static final int MAX_SMOOTH_RISE = 10;
    /** T23 : creusage maximal autorise du lissage (vers le bas), en blocs,
     *  LOIN de l'emprise (fenetre de fond T89). */
    private static final int MAX_SMOOTH_DROP = 2;

    /** T89 (B9) : derive maximale autorisee PILE AU BORD de l'emprise. Le fade
     *  doit pouvoir rabattre une paroi naturelle (montagne coupee a vif au
     *  bord du rasement du footprint) en pente <= 2 ; mesure du run du 29/09 :
     *  derive naturelle jusqu'a ~111 blocs sur crimsonlake. La fenetre se
     *  referme de 2 blocs par bloc d'eloignement (pente du fade) et rejoint
     *  la fenetre classique +{@link #MAX_SMOOTH_RISE}/-{@link #MAX_SMOOTH_DROP}
     *  au-dela de ~27-31 blocs : le terrain eloigne reste strictement naturel. */
    private static final int ADAPTIVE_SMOOTH_MAX = 64;

    /** T21 : borne ABSOLUE de la variation du lissage par rapport au terrain naturel.
     *  T89 (B9) : la borne est ADAPTATIVE -- plus la colonne est proche de
     *  l'emprise ({@code sMinI..sMaxI} x {@code sMinJ..sMaxJ}), plus la marge
     *  est large (c'est la que le fade rabat les parois coupees) ; a distance,
     *  la fenetre classique +10/-2 s'applique (conservation du naturel). */
    private static int[][] clampSmoothDelta(ServerLevel level, int[][] hm, int[][] orig,
                                            boolean[][] waterMask, int x0, int z0, int w, int h,
                                            int sMinI, int sMaxI, int sMinJ, int sMaxJ) {
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
                // T89 (B9) : fenetre ADAPTATIVE par distance a l'emprise.
                // Pres du bord, le fade a besoin de beaucoup de marge pour
                // rabattre une paroi naturelle (montagne coupee net, derive
                // mesuree jusqu'a 111 blocs) ou remblayer un creux de berge ;
                // la marge se referme de 2 blocs par bloc d'eloignement
                // (pente du fade) jusqu'a la fenetre classique T23.
                //
                // T23 : DISSYMETRIE VOLONTAIRE (consigne « tu creuse encore des trous
                // autour de la structure, WTF ! »). Le lissage peut REMBLAYER jusqu'a
                // MAX_SMOOTH_RISE pour amenager le plateau et ses abords, mais il n'a
                // plus le droit de CREUSER sous le terrain naturel au-dela de
                // MAX_SMOOTH_DROP : les crateres/terrasses concentriques en bord de
                // zone (visibles sur les captures du 20/09) disparaissent.
                // T89 : cette dissymetrie reste la REGLE DE FOND (loin de
                // l'emprise) ; pres du bord, la continuite de pente (<= 2,
                // garantie par limitSlope a convergence) tient lieu de garde
                // contre crateres et terrasses.
                int dx = i < sMinI ? sMinI - i : (i > sMaxI ? i - sMaxI : 0);
                int dz = j < sMinJ ? sMinJ - j : (j > sMaxJ ? j - sMaxJ : 0);
                int reach = ADAPTIVE_SMOOTH_MAX - 2 * Math.max(dx, dz);
                int allowRise = Math.max(MAX_SMOOTH_RISE, reach);
                int allowDrop = Math.max(MAX_SMOOTH_DROP, reach);
                // T177 — feather radial vers la frontière de la zone. Un smooth
                // rectangulaire sans ce masque pouvait encore modifier de 10
                // blocs la dernière colonne puis rencontrer brutalement le chunk
                // extérieur intact. Ici la force suit smoothstep sur les 16 blocs
                // extérieurs et vaut exactement zéro sur le bord. Les chunks sont
                // toujours traités comme une seule surface; aucune couture par
                // chunk n'est introduite.
                int edgeDistance = Math.min(Math.min(i, w - 1 - i), Math.min(j, h - 1 - j));
                float edgeT = Math.max(0f, Math.min(1f, edgeDistance / 16f));
                float feather = edgeT * edgeT * (3f - 2f * edgeT);
                allowRise = Math.round(allowRise * feather);
                allowDrop = Math.round(allowDrop * feather);
                if (d > allowRise) { hm[i][j] = ref[i][j] + allowRise; clamped++; }
                else if (d < -allowDrop) { hm[i][j] = ref[i][j] - allowDrop; clamped++; }
            }
        }
        if (clamped > 0) {
            LOGGER.warn("[DLB-STRUCTURE] lissage bridé : {} colonne(s) sortaient de la fenetre autorisee "
                            + "(adaptative T89 : jusqu'a +{}/-{} au bord, +{}/-{} au loin ; derive maximale mesuree : {} blocs, "
                            + "colonne {},{} de la zone) -- ramenees dans la fenetre",
                    clamped, ADAPTIVE_SMOOTH_MAX, ADAPTIVE_SMOOTH_MAX,
                    MAX_SMOOTH_RISE, MAX_SMOOTH_DROP, worst, x0 + worstX, z0 + worstZ);
        } else {
            deepluckyblock.util.DebugLog.structure(
                    "lissage : derive maximale {} blocs par rapport au terrain naturel "
                            + "(fenetre adaptative T89 : +{}/-{} au bord, +{}/-{} au loin), aucune colonne bridée",
                    worst, ADAPTIVE_SMOOTH_MAX, ADAPTIVE_SMOOTH_MAX, MAX_SMOOTH_RISE, MAX_SMOOTH_DROP);
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
        NATURAL_WATER = null; NATURAL_WATER_COUNT = 0;   // T103 : nouvelle structure -> nouvelle nappe vanilla de reference
    }

    private static void smoothPassAfterPreload(ServerLevel level, BlockPos structMin, BlockPos structMax,
                                               int kernelSize, int passes, int half, int smoothRing,
                                               int x0, int z0, int w, int h, int[] kernelSchedule, Runnable onDone) {
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
            // T109 : quand une serie graduelle est fournie, chaque passe indexe
            // son propre demi-noyau (rayon croissant, >=24 des le 6e smooth) ;
            // sinon le demi-noyau historique est identique pour toutes les passes.
            int passHalf = (kernelSchedule == null) ? half : kernelSchedule[pass] / 2;
            // --- passe horizontale (somme glissante sur i, colonnes d'eau sautees) ---
            for (int j = 0; j < h; j++) {
                long sum = 0; int count = 0;
                for (int i = 0; i <= Math.min(passHalf, w - 1); i++) {
                    if (waterMask[i][j]) continue;
                    sum += hm[i][j]; count++;
                }
                for (int i = 0; i < w; i++) {
                    tmp[i][j] = (waterMask[i][j] || count == 0) ? orig[i][j]
                            : (int) Math.round((double) sum / count);
                    int out = i - passHalf, in = i + passHalf + 1;
                    if (out >= 0 && !waterMask[out][j]) { sum -= hm[out][j]; count--; }
                    if (in < w  && !waterMask[in][j])  { sum += hm[in][j];  count++; }
                }
            }
            // --- passe verticale (somme glissante sur j, colonnes d'eau sautees) ---
            for (int i = 0; i < w; i++) {
                long sum = 0; int count = 0;
                for (int j = 0; j <= Math.min(passHalf, h - 1); j++) {
                    if (waterMask[i][j]) continue;
                    sum += tmp[i][j]; count++;
                }
                for (int j = 0; j < h; j++) {
                    dst[i][j] = (waterMask[i][j] || count == 0) ? orig[i][j]
                            : (int) Math.round((double) sum / count);
                    int out = j - passHalf, in = j + passHalf + 1;
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
        hm = clampSmoothDelta(level, hm, orig, waterMask, x0, z0, w, h, sMinI, sMaxI, sMinJ, sMaxJ);

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
                // T121 : l'emprise fait partie du MEME champ de smooth. Elle
                // etait auparavant forcee a fade=0 puis restauree plus bas :
                // les chunks du batiment ne participaient donc jamais vraiment.
                boolean insideStructure = i >= sMinI && i <= sMaxI && j >= sMinJ && j <= sMaxJ;
                double fade;
                if (insideStructure) {
                    fade = 1.0;
                } else if (dist <= anchorMargin) {
                    fade = 0.0;
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
                // T185 : le noyau participe bien aux deux convolutions larges,
                // mais sa reconstruction ne dépasse jamais le sol zéro validé.
                // On masque ainsi la coupe dans la couronne sans remettre de
                // terre/montagne dans le volume réservé à la structure.
                if (insideStructure && CLEARANCE_PLAN != null
                        && CLEARANCE_PLAN.matches(level, structMin, structMax))
                    smoothed = CLEARANCE_PLAN.groundY();
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
        // limitSlope fait converger intérieur et extérieur et peut déplacer le
        // noyau après son bornage. Restaurer strictement Y0, sans nouvelle coupe
        // du monde : la couronne, elle, reste issue des deux smooths larges.
        if (CLEARANCE_PLAN != null && CLEARANCE_PLAN.matches(level, structMin, structMax)) {
            int y0 = CLEARANCE_PLAN.groundY();
            for (int i = Math.max(0, sMinI); i <= Math.min(w - 1, sMaxI); i++)
                for (int j = Math.max(0, sMinJ); j <= Math.min(h - 1, sMaxJ); j++)
                    target[i][j] = y0;
        }
        // La relaxation a pu deplacer des colonnes au-dela de la fenetre du lissage :
        // on reapplique la borne pour rester coherent avec la mesure finale.
        target = clampSmoothDelta(level, target, orig, waterMask, x0, z0, w, h, sMinI, sMaxI, sMinJ, sMaxJ);

        // Noise and slope relaxation must never alter water columns or the anchored footprint.
        //
        // T89 (B9) : niveau PLATEAU = hauteur d'origine la plus BASSE de
        // l'emprise (hors colonnes d'eau). Il sert de plancher au garde-fou
        // anti-tranchee ci-dessous.
        int plateauFloor = Integer.MIN_VALUE;
        {
            int pf = Integer.MAX_VALUE;
            for (int i = sMinI; i <= sMaxI && i < w; i++)
                for (int j = sMinJ; j <= sMaxJ && j < h; j++)
                    if (i >= 0 && j >= 0 && !waterMask[i][j] && orig[i][j] < pf) pf = orig[i][j];
            if (pf != Integer.MAX_VALUE) plateauFloor = pf + MAX_SLOPE_STEP;
            // (emprise entierement noyee : pas de plateau, le garde-fou s'eteint)
        }
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                // T121 : seule l'eau reste gelee. L'ancienne seconde condition
                // restaurait toute l'emprise a orig et annulait explicitement
                // chaque smooth calcule dans les chunks de la structure.
                if (waterMask[i][j]) {
                    target[i][j] = orig[i][j];
                } else if (i >= sMinI - 6 && i <= sMaxI + 6 && j >= sMinJ - 6 && j <= sMaxJ + 6) {
                    // T89 (B9) : garde-fou anti-tranchee REDEFINI.
                    //
                    // AVANT : target = max(target, orig) -- « jamais sous le
                    // naturel ». Or le naturel juste au bord, c'est la PAROI de
                    // la montagne coupee par le rasement du footprint (mesure
                    // 29/09 : 40 a 111 blocs au-dessus du plateau) : le
                    // garde-fou restaurait la falaise a 1 bloc du batiment.
                    // C'est l'une des 4 causes directes du « coupe au couteau ».
                    //
                    // DESORMAIS : le plancher est min(naturel, plateau + 2).
                    //  - naturel PLUS BAS que le plateau (vallee, creux) :
                    //    min = naturel -> comportement historique intact
                    //    (jamais de tranchee sous le naturel proche) ;
                    //  - naturel PLUS HAUT (paroi de montagne coupee) :
                    //    min = plateau + 2 -> le fade peut abaisser la paroi,
                    //    mais JAMAIS sous le niveau du plateau (pas de fosse
                    //    au pied du batiment). Au-dela des 6 blocs, la
                    //    continuite est portee par limitSlope a convergence.
                    target[i][j] = Math.max(target[i][j], Math.min(orig[i][j], plateauFloor));
                }
            }
        }

        // Garantie finale du noyau APRÈS tous les garde-fous numériques : aucune
        // étape du smooth ne peut relever l'emprise au-dessus du Y0.
        if (CLEARANCE_PLAN != null && CLEARANCE_PLAN.matches(level, structMin, structMax)) {
            int y0 = CLEARANCE_PLAN.groundY();
            for (int i = Math.max(0, sMinI); i <= Math.min(w - 1, sMaxI); i++)
                for (int j = Math.max(0, sMinJ); j <= Math.min(h - 1, sMaxJ); j++)
                    target[i][j] = y0;
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
                                // T87 (B7 « TU CREUSE ») : on ne comble que l'AIR. Avant, le break
                                // n'excluait que les FLUIDES : tout bloc SOLIDE au-dessus d'une
                                // heightmap sous-evaluee etait ECRASE en grass/dirt. Or les passes
                                // a FAST_FLAG ne recalculent pas les heightmaps : au centre de la
                                // citadel l'anneau venait d'etre retouche, la capture lisait trop
                                // bas et 2 199 blocs ont ete reecrits en une seconde -- le terrain
                                // paraissait litteralement creuse. Desormais, le premier bloc
                                // existant (solide OU fluide OU plante) ARRETE la colonne : jamais
                                // aucun bloc existant n'est remplace (regle « combler, jamais creuser »).
                                if (!cur.isAir()) break;
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
                                    // T87 : meme regle que la faille -- l'AIR uniquement, jamais
                                    // l'ecrasement d'un bloc existant (marche essuyant une heightmap
                                    // perimee = falaise/terrain reel rabote a la terre).
                                    if (!cur.isAir()) break;
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

    /**
     * B3 : rearme UNIQUEMENT le chrono inter-etapes ({@link #LAST_STEP_MS}),
     * sans toucher au bracket de phase ({@link #PHASE_T0}). A appeler au depart
     * des pipelines qui n'entrent PAS par prepZone (ex. l'Everest) : sinon leur
     * premiere etape mesurerait l'inactivite depuis la structure precedente.
     */
    public static void resetStepClock() {
        LAST_STEP_MS = 0L;
    }

    private static void step(String label) {
        long nowMs = System.currentTimeMillis();
        deepluckyblock.util.DebugLog.structure("[{} s] {}",
                String.format("%.1f", (nowMs - PHASE_T0) / 1000.0), label);
        // T16 : fil rouge de phase (lu par la sonde de tick) + duree REELLE de
        // l'etape qui vient de finir. Les passes decoupees affichent une duree
        // de travail cumulee (ex. « 12 000 ms ») qui ne dit pas si le serveur
        // tournait a 20 TPS ou a 2 TPS : cet ecart-ci, si.
        deepluckyblock.util.DebugLog.setPhase(label);
        // B3 (garde) : ne jamais mesurer un delta INTER-GENERATIONS. Un chemin qui
        // commencerait par step() SANS passer par prepZone (ou tout oubli futur de
        // rearmement) heriterait d'un LAST_STEP_MS d'une structure precedente ; le
        // test ci-dessous jette ce cas (le step precedent doit appartenir au
        // pipeline courant, c'est-a-dire posterieur a PHASE_T0).
        if (LAST_STEP_MS != 0L && LAST_STEP_MS >= PHASE_T0) {
            long delta = nowMs - LAST_STEP_MS;
            // Campagne /2 : delta REEL de CHAQUE etape, visible dans les
            // annotations CI (zero comportement -- instrumentation seule).
            if (delta >= 500L) {
                deepluckyblock.util.DebugLog.structure(
                        "[DLB-STEP] {} ms -- {}", delta, label);
            }
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
        // T82 : colonnes du lissage dont le chunk manquait lors de la capture --
        // elles etaient AUTREFOIS sautees en silence (continue) et leur vieux
        // terrain restait en place. Elles sont reconstruites des l'arrivee du
        // chunk (voir finishRebuildPasses).
        final List<int[]> missed = new ArrayList<>();
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
                // T82 : un chunk absent ne fait PLUS perdre la colonne -- elle
                // attend dans `missed` et sera reconstruite des son arrivee.
                if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, x0 + i, z0 + j)) {
                    missed.add(new int[]{x0 + i, z0 + j, hm[i][j], orig[i][j]});
                    continue;
                }
                cols.add(new int[]{x0 + i, z0 + j, hm[i][j], orig[i][j]});
            }
        Runnable finish = () -> finishRebuildPasses(level, missed, onDone);
        int total = (cols.size() + CLEAR_BATCH_COLS - 1) / CLEAR_BATCH_COLS;
        if (total == 0) { finish.run(); return; }
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
                if (bi == bt - 1) finish.run();
            });
        }
    }

    /**
     * T82 : cloture d'un lissage/reconstruction -- si des colonnes attendaient
     * leur chunk, on les re-enregistre pour chargement et on relance leur
     * reconstruction par reprises (meme plafond T70 que les passes) AVANT de
     * liberer la suite du pipeline (le paste ne depasse jamais une colonne
     * traitable). Seul un chunk jamais revenu apres {@link #MAX_MISS_RETRIES}
     * reprises termine avec une alerte honnete.
     */
    private static void finishRebuildPasses(ServerLevel level, java.util.List<int[]> missed, Runnable onDone) {
        finishRebuildPasses(level, missed, onDone, 0);
    }

    private static void finishRebuildPasses(ServerLevel level, java.util.List<int[]> missed,
                                            Runnable onDone, int retries) {
        if (missed.isEmpty() || retries >= MAX_MISS_RETRIES) {
            if (!missed.isEmpty())
                LOGGER.warn("[DLB-STRUCTURE] rebuildTerrain : {} colonne(s) restent non lissees "
                                + "(chunk jamais charge apres {} reprises T82) -- le reste est termine.",
                        missed.size(), retries);
            if (onDone != null) onDone.run();
            return;
        }
        if (retries == 0)
            deepluckyblock.util.DebugLog.structure(
                    "rebuildTerrain : T82 -- {} colonne(s) attendent un chunk (re-enregistrees pour chargement)",
                    missed.size());
        reregisterMissedColumnChunks(level, missed, "rebuildTerrain", retries);
        TestProcedure.schedule(level, TestProcedure.currentTick(level) + MISS_RETRY_TICKS, () -> {
            BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
            for (java.util.Iterator<int[]> it = missed.iterator(); it.hasNext(); ) {
                int[] c = it.next();
                if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, c[0], c[1])) continue;
                try { rebuildColumn(level, mut, c[0], c[1], c[2], c[3]); } catch (Throwable ignored) { }
                it.remove();
            }
            finishRebuildPasses(level, missed, onDone, retries + 1);
        });
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

    /** Solid ground reference in the six-block exterior collar; unknown elsewhere.
     * The reference is captured before the first smooth and is not lowered by later passes. */
    private static int collarGroundFloor(int x, int z) {
        if (NATURAL_REF == null) return Integer.MIN_VALUE;
        int i = x - NR_X0, j = z - NR_Z0;
        if (i < 0 || j < 0 || i >= NR_W || j >= NR_H) return Integer.MIN_VALUE;
        int ring = terrainRing();
        int minX = NR_X0 + ring, maxX = NR_X0 + NR_W - 1 - ring;
        int minZ = NR_Z0 + ring, maxZ = NR_Z0 + NR_H - 1 - ring;
        boolean inside = x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        if (inside || x < minX - 6 || x > maxX + 6 || z < minZ - 6 || z > maxZ + 6)
            return Integer.MIN_VALUE;
        return NATURAL_REF[i][j];
    }

    // Reconstruit une colonne : remet le terrain a la hauteur cible (targetY).
    // currentY = hauteur CAPTUREE (origY, despecklee) au debut du smooth. On ne
    // re-interroge PAS getHeight() : sur certaines colonnes (coins de chunk) la
    // heightmap renvoie une valeur corrompue, ce qui ferait monter une tour de
    // stone+dirt+grass a chaque coin de chunk.
    private static void rebuildColumn(ServerLevel level, BlockPos.MutableBlockPos mut, int cx, int cz, int targetY, int origY) {
        int currentY = origY;
        targetY = Math.max(targetY, collarGroundFloor(cx, cz));
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
        if (isProtected(x, z) || StructureScatterDecor.isInsideDecor(x, z, 1)) return;
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int surfY = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (surfY < 1) return;
        BlockState s = level.getBlockState(mut.set(x, surfY, z));
        if (s.isAir() || isLog(s) || isLeaf(s)) return;
        // T-HABILL.3 : neige une fois par chunk (cache) au lieu d'une comparaison
        // de chaine par colonne. Nuance assume vs l'ancienne chaine : grove/
        // snowy_slopes (biomes enneiges sans "snow/ice/frozen" dans le nom)
        // recoivent desormais la couche de neige -- correction voulue par le guide.
        boolean snowy = isSnowyCell(level, mut);
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
        new ReplantJob(level, new ArrayList<>(trees), structMin, structMax, onDone).slice();
    }

    private static final class ReplantJob {
        private final ServerLevel level;
        private final List<SavedTree> trees;
        private final BlockPos min, max;
        private final Runnable onDone;
        private final Set<Long> visited = new HashSet<>();
        private int cursor, planted, duplicates, protectedSkipped;
        private boolean done;

        ReplantJob(ServerLevel level, List<SavedTree> trees, BlockPos min, BlockPos max, Runnable onDone) {
            this.level = level; this.trees = trees; this.min = min; this.max = max; this.onDone = onDone;
        }

        void slice() {
            if (done) return;
            deepluckyblock.util.ChunkKeeper.keep(level);
            deepluckyblock.util.TerrainChain.heartbeat();
            deepluckyblock.util.DebugLog.setPhase("replantTrees " + cursor + "/" + trees.size());
            long deadline = System.nanoTime() + sliceBudgetMs(level) * 1_000_000L;
            int attempts = 0;
            // T120 : le test reel a paye ~57 attentes pour 454 arbres. Les
            // features restent budgetees individuellement, mais jusqu'a 16
            // tentatives peuvent partager la meme tranche.
            while (cursor < trees.size() && attempts < 16) {
                SavedTree tree = trees.get(cursor);
                int x = tree.baseX(), z = tree.baseZ();
                long key = colKey(x, z);
                if (visited.contains(key)) { duplicates++; cursor++; }
                else {
                    boolean covered = StructureScatterDecor.isInsideDecor(x, z, 1);
                    // T121 : un arbre sauvegarde ne doit JAMAIS etre replante
                    // dans l'emprise apres le paste. Le test T92 « colonne a
                    // ciel ouvert » laissait repousser d'anciens arbres jusque
                    // dans les maisons et leurs pieces interieures.
                    if (!covered && x >= min.getX() && x <= max.getX()
                            && z >= min.getZ() && z <= max.getZ()) {
                        covered = true;
                        protectedSkipped++;
                    }
                    if (covered) {
                        visited.add(key); cursor++;
                    } else {
                    // Vanilla features may read neighbouring chunks. Pin a local halo,
                    // then yield rather than generating them synchronously during place().
                    BlockPos a = new BlockPos(x - 16, min.getY(), z - 16);
                    BlockPos b = new BlockPos(x + 16, max.getY(), z + 16);
                    // T157 : ne jamais agrandir la zone pour replanter un arbre
                    // de bord. Sur citadel, 236 arbres ont ajouté 63 chunks et
                    // coûté 14,8 s. Les chunks centraux sont déjà garantis ; si
                    // le halo vanilla dépasse la zone chargée, on saute cet arbre
                    // plutôt que de générer du monde uniquement pour du décor.
                    if (!deepluckyblock.util.ChunkKeeper.zoneLoaded(level, a, b)) {
                        visited.add(key); cursor++; protectedSkipped++;
                        continue;
                    }
                    visited.add(key); cursor++; attempts++;
                    int y = findNaturalGroundY(level, x, z);
                    if (y > level.getMinBuildHeight()) {
                        BlockPos plantPos = new BlockPos(x, y, z);
                        // T231 (anneau nettoye non rendu) : le replant utilisait
                        // toujours le PETIT feature vanilla (epicea ~10 h,
                        // jungle ~11 h) meme pour les arbres photographies de
                        // 20-31 blocs -- les vieilles forets revenaient
                        // rabougries et la couronne paraissait « nettoyee sans
                        // etre rendue ». On choisit la variante naturelle
                        // selon la hauteur sauvegardee (repli sur le petit
                        // feature si la grande variante refuse la case).
                        String variant = naturalVariantOf(tree);
                        boolean ok = placeRealTree(level, variant, plantPos, level.getRandom());
                        if (!ok && !variant.equals(tree.type()))
                            ok = placeRealTree(level, tree.type(), plantPos, level.getRandom());
                        if (ok) planted++;
                    }
                    }
                }
                // A single vanilla feature is indivisible; check the budget after each attempt.
                if (System.nanoTime() >= deadline) break;
            }
            if (cursor < trees.size()) {
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::slice);
                return;
            }
            done = true;
            deepluckyblock.util.DebugLog.structure(
                    "replantTrees: {} trees planted, {} duplicate columns ignored, {} covered footprint columns skipped (T92)",
                    planted, duplicates, protectedSkipped);
            if (onDone != null) onDone.run();
        }
    }

    /** T231 : variante d'arbre la plus proche de la hauteur photographiee.
     *  Seuils prudents, juste sous les hauteurs des grandes variantes vanilla :
     *  mega_spruce 13-30, mega_jungle 10-31 (geante), fancy_oak ~6+ avec
     *  branches (le « oak » brut est un balai de 5-7 sans branches). */
    private static String naturalVariantOf(SavedTree tree) {
        int top = 0;
        for (SavedBlock b : tree.blocks()) if (b.ry() > top) top = b.ry();
        int h = top + 1;
        return switch (tree.type()) {
            case "spruce" -> h >= 15 ? "mega_spruce" : "spruce";
            case "jungle" -> h >= 20 ? "mega_jungle" : "jungle";
            case "oak"    -> h >= 9  ? "fancy_oak"   : "oak";
            default -> tree.type();
        };
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
        // Never erase an existing trunk or construction to force another tree here.
        BlockState existing = level.getBlockState(pos);
        if (!existing.isAir() && (!isSurfaceDecor(existing) || isLog(existing) || isLeaf(existing))) return false;
        if (!existing.getFluidState().isEmpty()) return false;

        // Get the configured tree feature for this type
        var featureOpt = getConfiguredTreeFeature(level, type);
        if (featureOpt.isEmpty()) return false;

        Holder<ConfiguredFeature<?, ?>> feature = featureOpt.get();
        // Place the tree feature at the target position
        // T105 : plus de log par arbre (676 lignes mesurees en jeu pour un
        // seul dragon = spam et cout de formatage) -- le ReplantJob rend deja
        // un compte global « N arbres replantes » en fin de passe.
        return feature.value().place(level, level.getChunkSource().getGenerator(), random, pos);
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

    /** T150 : les deux validations postérieures ont visité 13 668 cellules
     * pendant 17,5 s et posé exactement zéro bloc. La passe complète avant paste
     * reste exécutée; les rappels strictement identiques poursuivent directement. */
    private static void runWithoutRedundantFixWater(Runnable onDone) {
        if (onDone != null) onDone.run();
    }

    private static void fixWaterNearStructure(ServerLevel level, BlockPos min, BlockPos max, int radius) {
        fixWaterNearStructure(level, min, max, radius, null);
    }

    private static void fixWaterNearStructure(ServerLevel level, BlockPos min, BlockPos max, int radius,
                                              Runnable onDone) {
        // T90 (B10/B12) : la "cascade rayon x 10 passes" (T78/T81) est remplacee
        // par la propagation CONNECTEE FixWaterJob : voir sa javadoc.
        new FixWaterJob(level, min, max, radius, onDone).slice();
    }

    /** T90 : tours maximaux de la propagation connectee (le premier tour fait
     *  l'essentiel ; les suivants ne rattrapent que les cellules restees
     *  en attente du budget de tranche ou d'un chunk absent). T107 : avec le
     *  fixwater WorldEdit (pose de source directe), un tour suffit en pratique
     *  et le second constate rapidement « zone saine ». */
    private static final int FIXWATER_MAX_ROUNDS = 3;
    /** T90 : plafond de cellules d'eau visitees (garde-fou ocean). */
    private static final int FIXWATER_SEEN_MAX = 400_000;
    /** T90 : profondeur de recherche de la graine sous la surface. */
    private static final int FIXWATER_SEED_DEPTH = 48;
    /** T112 : largeur de la bande exterieure scannee autour de la zone, en chunks. */
    private static final int FIXWATER_BAND_CHUNKS = 1;
    /** T112 : rayon du fixwater declenche depuis le bord (consigne dev : « radius de 100 »). */
    private static final int FIXWATER_BAND_RADIUS = 10;
    /** T112 : fenetre glissante de verification, 4x4 chunks a la fois. */
    private static final int FIXWATER_BAND_WINDOW = 4;
    /** T112 : profondeur maximale de recherche d'eau dans une colonne de bande. */
    private static final int FIXWATER_BAND_DEPTH = 64;

    /**
     * T90 (B10/B12) -- CASCADE D'EAU CONNECTEE, en tranches.
     *
     * <p>Remplace la cascade « rayon » historique : chaque appel balayait
     * TOUTES les colonnes de la boite (jusqu'a ~93 000), jusqu'a
     * 10 passes a la suite, et la methode etait
     * appelee ~11 fois par pipeline (prepZone x2, decorate x2, sealWaterLeaks
     * x8). Mesure du run du 29/09 : 250,9 s de fixWaterNearStructure +
     * 179,3 s de sealWaterLeaks -- de loin le premier poste du run, et la
     * douleur signalee en direct par le dev (« c'est ATTROCEMENT LONG !! »,
     * ecrit pendant l'iteration 4 du seal).
     *
     * <p>Principe : on ne visite QUE l'eau REELLE, une fois chacune.
     * <ol>
     *   <li>GRAINES : chaque colonne contribue son PREMIER bloc d'eau (scan
     *       qui s'arrete au premier bloc plein, eau ou non) ;</li>
     *   <li>BFS 6 directions a travers les blocs d'eau connectes ; chaque
     *       bloc d'eau ayant de l'AIR sur un cote ou en dessous sert
     *       d'ORIGINE a un fixwater WorldEdit EXACT (T107 : source posee
     *       directement, plus de tick de fluide a l'aveugle) ;</li>
     *   <li>nouveau tour SEULEMENT si des cellules ont ete corrigees, au plus
     *       {@link #FIXWATER_MAX_ROUNDS} fois.</li>
     * </ol>
     * Une nappe stable ne coute plus que sa propre traversée, une fois.
     */
    private static final class FixWaterJob {
        private final ServerLevel level;
        private final int x0, z0, x1, z1, radius;
        private final Runnable onDone;
        private final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        private final java.util.Set<Long> seen = new java.util.HashSet<>();
        private final java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        private final long t0 = System.currentTimeMillis();
        private int round = 1, scanX, scanZ, changed, capped, skippedChunk, slices;
        // ---- T112 : bande exterieure d'un chunk autour de la zone ----
        private java.util.ArrayList<Long> bandRing;               // chunks de la bande (cle (cx<<32)^cz)
        private final java.util.ArrayDeque<Long> bandQueue = new java.util.ArrayDeque<>(); // origines detectees
        private final java.util.Set<Long> bandOrigins = new java.util.HashSet<>();         // anti-doublon exact
        // T115 : anti-recouvrement spatial. Un contact eau/air existe sur des
        // centaines de colonnes consecutives ; lancer un ellipsoide rayon 100
        // POUR CHAQUE colonne refaisait presque exactement le meme travail et
        // expliquait les minutes sans changement visible. On conserve des
        // centres espaces de 48 blocs : recouvrement largement suffisant pour
        // ne laisser aucun coin, sans recalculer le meme volume des centaines
        // de fois.
        private final java.util.ArrayList<Long> bandCenters = new java.util.ArrayList<>();
        // T116 : chunks d'air explicitement qualifies par un contact eau/air
        // dans la bande. T103 reste stricte partout ailleurs ; dans cette
        // fenetre 4x4 seulement, le fixwater peut restaurer une nappe absente
        // de la photographie initiale (celle-ci ne couvrait que l'interieur).
        private final java.util.Set<Long> bandRepairChunks = new java.util.HashSet<>();
        private int bandChunk = 0, bandOff = 0, bandLX = 0, bandLZ = 0, bandFires = 0;
        private boolean bandDone = false;

        FixWaterJob(ServerLevel level, BlockPos min, BlockPos max, int radius, Runnable onDone) {
            this.level = level; this.radius = radius; this.onDone = onDone;
            this.x0 = min.getX() - radius; this.x1 = max.getX() + radius;
            this.z0 = min.getZ() - radius; this.z1 = max.getZ() + radius;
            this.scanX = x0; this.scanZ = z0;
        }

        private void resume(int delay) {
            deepluckyblock.util.TerrainChain.heartbeat();
            TestProcedure.schedule(level, TestProcedure.currentTick(level) + delay, this::slice);
        }

        /** 1) graine : premier bloc d'eau de la colonne (scan borne, pas de generation). */
        private void seedColumn(int x, int z) {
            if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, x, z)) { skippedChunk++; return; }
            int surf = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z);
            int yBot = Math.max(level.getMinBuildHeight(), surf - FIXWATER_SEED_DEPTH);
            for (int y = surf - 1; y >= yBot; y--) {
                BlockState s = level.getBlockState(mut.set(x, y, z));
                if (s.isAir()) continue;
                if (s.is(Blocks.WATER))
                    queue.add(BlockPos.asLong(x, y, z));
                break;   // premier bloc plein (eau ou non) : la colonne est classee
            }
        }

        /**
         * T112 -- BANDE EXTERIEURE D'UN CHUNK autour de la zone (consignes du
         * dev, 30/09, dans l'ordre) :
         * <ul>
         *   <li>« il faut dans un premier temps ajouter un chunk
         *       supplementaire a notre grande zone, le charger, voir s'il y a
         *       de l'eau dedans, et voir si a cote de l'eau dans le chunk
         *       precedent il y a de l'air » ;</li>
         *   <li>« si et seulement s'il y a de l'air, alors retourner sur le
         *       chunk precedent et faire le fixwater avec un radius de 100,
         *       pour corriger le chunk a cote qui manquait l'eau, et remplir
         *       les autres chunks alentours qu'on a charges au prealable » ;</li>
         *   <li>« une bonne zone de 4x4 chunk, on verifie, ensuite on
         *       glisse, etc jusqu'a avoir tente sur toute la zone, + les
         *       nouveaux chunks ouverts tout autour » ;</li>
         *   <li>« en gros on agrandit le rayon » -- « le fixwater n'a rien
         *       fait ! ».</li>
         * </ul>
         *
         * Pourquoi : l'eau coupee au BORD vit dans les chunks situes JUSTE
         * AU-DELA de la boite [x0,x1]x[z0,z1] ; les phases historiques ne les
         * scannaient pas, donc aucune graine n'etait jamais trouvee au bord
         * (mesure en jeu : 0 cellule posee a chaque appel).
         *
         * Fenetre glissante de {@link #FIXWATER_BAND_WINDOW}x4 chunks sur tout
         * l'anneau : chaque chunk de bande est d'abord demande a ChunkKeeper
         * (chargement asynchrone, jamais de generation bloquante), la fenetre
         * est verifiee, puis on glisse a la suivante -- jusqu'a couvrir toute
         * la zone elargie, nouveaux chunks ouverts inclus.
         *
         * Pour chaque cellule d'eau de la bande ayant de l'AIR d'un cote (au
         * meme Y), on empile l'origine AU CONTACT, dans le chunk precedent
         * (cote air), et on applique le fixwater WorldEdit avec le RAYON 100
         * demande : le remplissage recouvre le chunk coupe ET les chunks
         * alentours deja charges.
         *
         * @return true tant que la bande n'est pas entierement traitee (une
         *         reprise est alors deja planifiee).
         */
        private boolean bandStep(long deadline) {
            if (bandRing == null) bandRing = bandBuildRing();

            // 1) d'abord, le fill en cours (T114 : reprendable entre tranches),
            //    puis vider les origines detectees (remplissages rayon 100) :
            //    pas de nouvelle detection si un remplissage attend son budget.
            while (!bandQueue.isEmpty() || activeFill != null) {
                if (System.currentTimeMillis() >= deadline) { resume(1); return true; }
                if (activeFill == null) {
                    long pk = bandQueue.poll();
                    activeFill = new FillState(BlockPos.getX(pk), BlockPos.getY(pk), BlockPos.getZ(pk),
                            FIXWATER_BAND_RADIUS, true);
                }
                if (!fillSlice(activeFill, deadline)) { resume(1); return true; }   // etat conserve, on revient a la tranche suivante
                changed += activeFill.fixed;
                bandFires++;
                activeFill = null;
            }

            // 2) fenetre glissante 4x4 chunks sur l'anneau
            int win = FIXWATER_BAND_WINDOW * FIXWATER_BAND_WINDOW;
            while (bandChunk < bandRing.size()) {
                int wEnd = Math.min(bandChunk + win, bandRing.size());
                boolean ready = true;
                for (int i = bandChunk; i < wEnd; i++) {
                    long key = bandRing.get(i);
                    int cx = (int) (key >> 32), cz = (int) (long) key;
                    // Demandes de chargement (async, petit debit gere par
                    // ChunkKeeper.keep, appele en tete de chaque tranche).
                    deepluckyblock.util.ChunkKeeper.trackAdditionalChunk(level, cx, cz);
                    var chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null || !deepluckyblock.util.SafeSurface.isReallyGenerated(chunk))
                        ready = false;
                }
                if (!ready) { resume(1); return true; }   // fenetre pas encore chargee

                // Colonnes de la fenetre strictement HORS boite interieure.
                for (; bandOff < wEnd - bandChunk; bandOff++) {
                    long key = bandRing.get(bandChunk + bandOff);
                    int cx = (int) (key >> 32), cz = (int) (long) key;
                    for (; bandLX < 16; bandLX++) {
                        for (; bandLZ < 16; bandLZ++) {
                            if (System.currentTimeMillis() >= deadline) { resume(1); return true; }
                            int x = (cx << 4) + bandLX, z = (cz << 4) + bandLZ;
                            if (x >= x0 && x <= x1 && z >= z0 && z <= z1) continue; // interieur : phases 1-2
                            bandDetectColumn(x, z);
                        }
                        bandLZ = 0;
                    }
                    bandLX = 0;
                }
                bandOff = 0;
                bandChunk = wEnd;   // ... ensuite on glisse

                // Les origines trouvees dans cette fenetre partiront a la
                // prochaine tranche si le budget est fini.
                if (System.currentTimeMillis() >= deadline) { resume(1); return true; }
            }
            // T116 : des origines peuvent avoir ete trouvees dans la DERNIERE
            // fenetre alors qu'il reste encore du budget. L'ancienne version
            // retournait false immediatement, bandDone passait a true et cette
            // file n'etait JAMAIS executee. C'est exactement compatible avec
            // le log observe : scan en 9 tranches, puis « 0 origine de bande »
            // et « 0 cellule posee » malgré les chunks d'eau absents.
            if (!bandQueue.isEmpty() || activeFill != null) {
                resume(1);
                return true;
            }
            return false;   // bande entierement scannee ET file entierement executee
        }

        /** T112 : anneau des chunks situes a {@link #FIXWATER_BAND_CHUNKS} chunk(s) au-dela de la boite. */
        private java.util.ArrayList<Long> bandBuildRing() {
            java.util.ArrayList<Long> ring = new java.util.ArrayList<>();
            int icx0 = x0 >> 4, icz0 = z0 >> 4, icx1 = x1 >> 4, icz1 = z1 >> 4;
            for (int cx = icx0 - FIXWATER_BAND_CHUNKS; cx <= icx1 + FIXWATER_BAND_CHUNKS; cx++) {
                for (int cz = icz0 - FIXWATER_BAND_CHUNKS; cz <= icz1 + FIXWATER_BAND_CHUNKS; cz++) {
                    if (cx >= icx0 && cx <= icx1 && cz >= icz0 && cz <= icz1) continue; // interieur
                    ring.add(((long) cx << 32) ^ (cz & 0xffffffffL));
                }
            }
            return ring;
        }

        /**
         * T112 : dans une colonne de bande, cherche l'eau ; si une cellule
         * d'eau a de l'AIR sur un cote (chunk precedent charge, au meme Y),
         * empile le contact cote air comme origine d'un fixwater rayon 100.
         * Si et seulement s'il y a de l'air -- sinon la colonne est classee.
         */
        private void bandDetectColumn(int x, int z) {
            if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, x, z)) { skippedChunk++; return; }
            int surf = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z);
            int yBot = Math.max(level.getMinBuildHeight(), surf - FIXWATER_BAND_DEPTH);
            for (int y = surf - 1; y >= yBot; y--) {
                BlockState s = level.getBlockState(mut.set(x, y, z));
                if (s.isAir()) continue;
                if (!s.getFluidState().is(net.minecraft.tags.FluidTags.WATER))
                    break;   // sol plein sous la colonne : classee
                // T116 : 8 voisins, pas seulement les 4 cardinaux. Les chunks
                // manquants « dans les coins » ne partagent parfois qu'un coin
                // avec le dernier chunk d'eau : l'ancien test ne pouvait donc
                // mathematiquement jamais les detecter.
                final int[] dx8 = {1,-1,0,0, 1,1,-1,-1};
                final int[] dz8 = {0,0,1,-1, 1,-1,1,-1};
                for (int d = 0; d < 8; d++) {
                    int nx = x + dx8[d];
                    int nz = z + dz8[d];
                    if (isProtected(nx, nz)) continue;                       // emprise structurelle : jamais touchee
                    if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, nx, nz)) continue;
                    if (!level.getBlockState(side.set(nx, y, nz)).isAir()) continue;
                    long origin = BlockPos.asLong(nx, y, nz);
                    // La consigne demande une fenetre 4x4 glissante : qualifier
                    // exactement les 16 chunks autour du contact. Eux seuls
                    // peuvent contourner T103 pendant CE fill de bande.
                    int rcx = nx >> 4, rcz = nz >> 4;
                    for (int wx = rcx - 1; wx <= rcx + 2; wx++)
                        for (int wz = rcz - 1; wz <= rcz + 2; wz++)
                            bandRepairChunks.add(((long) wx << 32) ^ (wz & 0xffffffffL));
                    if (bandOrigins.add(origin) && !nearBandCenter(nx, y, nz)) {
                        bandCenters.add(origin);
                        bandQueue.add(origin);                               // air trouve : fixwater rayon 100 au contact
                    }
                    return;   // une origine par colonne suffit (rayon 100)
                }
            }
        }

        /** T115 : true si un rayon 100 deja programme couvre largement ce contact. */
        private boolean nearBandCenter(int x, int y, int z) {
            for (long p : bandCenters) {
                int dx = BlockPos.getX(p) - x, dz = BlockPos.getZ(p) - z;
                int dy = Math.abs(BlockPos.getY(p) - y);
                if (dy <= 12 && (long) dx * dx + (long) dz * dz < 48L * 48L) return true;
            }
            return false;
        }

        /**
         * T107 -- PORTAGE EXACT du fixWater WorldEdit (commande
         * {@code //fixwater <rayon>}), importe depuis le code officiel
         * EngineHub/WorldEdit (worldedit-core, {@code EditSession#fixWater(Big
         * Vector3, double)} -> {@code fixLiquid(origin, radius, WATER)},
         * qui s'appuie sur {@code NonRisingVisitor} /
         * {@code BreadthFirstSearch}). Algorithme reproduit tel que concu :
         * <ul>
         *   <li><b>Masque</b> (MaskIntersection) :
         *     <ul>
         *       <li>BoundedHeightMask(minBuild, min(originY, maxBuild)) : la
         *           routine ne monte JAMAIS au-dessus du Y d'origine (l'eau ne
         *           peut que retomber/niveler, jamais s'elever) ;</li>
         *       <li>RegionMask(EllipsoidRegion(origin, (r,r,r))) : borne
         *           ellipsoidale de rayon {@code radius} ;</li>
         *       <li>MaskUnion(BlockTypeMask(WATER), !ExistingBlockMask) : seuls
         *           les blocs d'EAU et les blocs d'AIR sont visitables
         *           (tout bloc plein est un mur).</li>
         *     </ul></li>
         *   <li><b>Propagation</b> (NonRisingVisitor) : BFS dans 5 directions
         *       +X, -X, +Z, -Z, -Y -- lateralement et vers le bas, jamais vers
         *       le haut.</li>
         *   <li><b>Application</b> (BlockReplace) : chaque cellule visitee est
         *       posee en bloc SOURCE d'eau (le flowing devient stationnaire,
         *       les cellules d'air sous le plan d'eau sont remplies).</li>
         * </ul>
         * Adaptations d'environnement seulement (garanties du pipeline,
         * inchangees) : aucun acces sur chunk absent (garde isLoadedAt, T95),
         * aucune pose sur colonne protegee (emprise structurelle), borne nappe
         * vanilla T103 pour les cellules d'AIR (jamais d'eau sur la terre
         * naturelle hors rivage memorise quand la nappe est connue).
         *
         * T114 -- FILL REPRENDABLE ENTRE TRANCHES (bug constate en jeu 30/09 :
         * « crimsonlake semble avoir arrete en plein travail depuis pas mal de
         * temps ! trouve la cause, et repare ! »). L'ancienne version devait
         * terminer un fill ENTIER dans le budget d'une tranche (18 ms) : des
         * qu'un fill etait trop gros (lac geant crimsonlake : rayon adaptatif
         * enorme ; bande T112 : rayon 100 = ellipsoide de 4,19 M de cellules),
         * l'abandon en cours de route RE-EMPILAIT la graine et le fill
         * REPARTAIT DE ZERO a la tranche suivante -- boucle infinie silencieuse,
         * aucune log, jusqu'au relargage de la zone par le garde-fou des 600 s :
         * pipeline mort en plein vol. Desormais {@link FillState} conserve file
         * et marquage entre tranches : le fill avance a grandeur du budget,
         * autant de tranches que necessaire, JAMAIS de redemarrage a zero.
         */
        private final class FillState {
            final int ox, oy, oz, useRadius;
            final boolean bandRepair;
            final java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<>();
            final java.util.Set<Long> done = new java.util.HashSet<>();
            final long fillT0 = System.currentTimeMillis();
            long lastProgressLog = fillT0;
            int fixed = 0;
            FillState(int ox, int oy, int oz, int useRadius) {
                this(ox, oy, oz, useRadius, false);
            }
            FillState(int ox, int oy, int oz, int useRadius, boolean bandRepair) {
                this.ox = ox; this.oy = oy; this.oz = oz; this.useRadius = useRadius;
                this.bandRepair = bandRepair;
                // T115 / WorldEdit exact : EditSession#fixLiquid ne met PAS
                // aveuglement l'origine dans le visiteur. Il filtre le cube
                // 3x3x3 centre sur l'origine avec liquidMask et n'ajoute que
                // les cellules DEJA liquides. L'ancienne adaptation ajoutait
                // directement l'origine AIR detectee au contact : elle pouvait
                // alors explorer/remplir un immense volume d'air avant meme de
                // rejoindre l'eau, d'ou plusieurs minutes sans resultat.
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = ox + dx, y = oy + dy, z = oz + dz;
                        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) continue;
                        if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, x, z)) continue;
                        if (!level.getBlockState(mut.set(x, y, z)).is(Blocks.WATER)) continue;
                        long p = BlockPos.asLong(x, y, z);
                        if (done.add(p)) q.add(p);
                    }
            }
        }
        /** T114 : le fill courant, conserve entre tranches (null = aucun). */
        private FillState activeFill = null;
        /** T114 : garde-fou par fill (~2x l'ellipsoide rayon 100 : anti-explosion). */
        private static final int FILL_CELLS_MAX = 8_000_000;

        /**
         * T107/T114 : une TRANCHE du fill WorldEdit reprendable.
         *
         * @param f etat du fill (survit entre tranches)
         * @return true quand le fill est TERMINE (file vide ou plafond
         *         {@link #FILL_CELLS_MAX}) ; false si le budget de tranche est
         *         epuise -- l'etat est conserve, la suite reprendra a la
         *         tranche suivante sans rien recommencer.
         */
        private boolean fillSlice(FillState f, long deadlineMs) {
            int ox = f.ox, oy = f.oy, oz = f.oz;
            double invR = 1.0 / f.useRadius;
            int yCap = Math.min(oy, level.getMaxBuildHeight() - 1);
            int minB = level.getMinBuildHeight();
            java.util.ArrayDeque<Long> q = f.q;
            java.util.Set<Long> done = f.done;
            while (!q.isEmpty()) {
                long now = System.currentTimeMillis();
                if (now - f.lastProgressLog >= 5_000L) {
                    f.lastProgressLog = now;
                    deepluckyblock.util.DebugLog.structure(
                            "fixwater EN COURS : origine {},{},{} rayon {}, {} visitees, {} en attente, {} posees ({} s) -- T115",
                            ox, oy, oz, f.useRadius, done.size(), q.size(), f.fixed, (now - f.fillT0) / 1000L);
                }
                if (now >= deadlineMs) return false;
                if (done.size() > FILL_CELLS_MAX) { skippedChunk++; return true; }  // garde-fou : on clos le fill
                long pk = q.poll();
                int x = BlockPos.getX(pk), y = BlockPos.getY(pk), z = BlockPos.getZ(pk);
                BlockState cur = level.getBlockState(mut.set(x, y, z));
                if (cur.isAir()) {
                    // T103 (inchange) : aucune pose d'eau sur de la terre
                    // naturelle hors nappe vanilla, ni sur colonne protegee.
                    if (isProtected(x, z)) continue;
                    if (hasNaturalWaterRef() && !naturalWaterAt(x, z)) {
                        // T116 : la photo T103 ne couvre que la boite interieure.
                        // Une cellule d'un chunk exterieur explicitement
                        // qualifie par le contact eau/air de bande ne peut donc
                        // PAS etre rejetee parce qu'elle manque de cette photo.
                        long ck = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                        if (!f.bandRepair || !bandRepairChunks.contains(ck)) continue;
                    }
                    level.setBlock(mut, Blocks.WATER.defaultBlockState(), FAST_FLAG);
                    f.fixed++;    // BlockReplace : l'air devient eau stationnaire
                } else if (cur.is(Blocks.WATER)) {
                    if (!cur.getFluidState().isSource()) {
                        level.setBlock(mut, Blocks.WATER.defaultBlockState(), FAST_FLAG);
                        f.fixed++;  // fixwater : « flowing -> stationary »
                    }
                } else continue;  // bloc plein : hors masque (mur)
                // NonRisingVisitor : +X, -X, +Z, -Z, -Y (jamais +Y).
                for (int d = 0; d < 5; d++) {
                    int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    int ny = (d == 4) ? y - 1 : y;
                    if (ny < minB || ny > yCap) continue;                    // BoundedHeightMask
                    double ex = (nx + 0.5 - ox) * invR;                      // EllipsoidRegion
                    double ey = (ny + 0.5 - oy) * invR;
                    double ez = (nz + 0.5 - oz) * invR;
                    if (ex * ex + ey * ey + ez * ez >= 1.0) continue;        // RegionMask (ellipsoide)
                    long nk = BlockPos.asLong(nx, ny, nz);
                    if (!done.add(nk)) continue;
                    if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, nx, nz)) { skippedChunk++; continue; }
                    BlockState ns = level.getBlockState(side.set(nx, ny, nz));
                    // MaskUnion(WATER, !Existing) : eau ou air uniquement.
                    if (!ns.isAir() && !ns.is(Blocks.WATER)) continue;
                    q.add(nk);
                }
            }
            return true;
        }

        void slice() {
            slices++;
            deepluckyblock.util.TerrainChain.heartbeat();
            deepluckyblock.util.ChunkKeeper.keep(level);
            // Portillon de zone : jamais d'acces bloc au chunk absent.
            if (!deepluckyblock.util.ChunkKeeper.zoneComplete(level)) { resume(1); return; }
            long deadline = System.currentTimeMillis() + 45L;

            // T140 : budget dedie 45 ms pour reduire les 51 attentes inter-ticks
            // du fixwater, sans changer ordre, convergence ni rayon.
            // 0) T112 : bande exterieure d'un chunk (fenetre glissante 4x4,
            //    fixwater rayon 100 au contact eau/air du bord) -- SANS ELLE,
            //    l'eau coupee au bord n'a jamais de graine et le fixwater ne
            //    corrige rien (0 cellule posee, constate en jeu par le dev).
            if (!bandDone && bandStep(deadline)) return;   // bande en cours (reprise planifiee)
            bandDone = true;

            // 1) graines, colonne par colonne
            while (scanX <= x1) {
                while (scanZ <= z1) {
                    seedColumn(scanX, scanZ);
                    scanZ++;
                    if (System.currentTimeMillis() >= deadline) { resume(1); return; }
                }
                scanZ = z0; scanX++;
            }

            // 2) BFS dans la nappe connectee
            while (!queue.isEmpty() || activeFill != null) {
                if (System.currentTimeMillis() >= deadline) { resume(1); return; }
                if (activeFill != null) {
                    // T114 : terminer le fill en cours AVANT toute nouvelle
                    // origine. L'etat survit entre tranches -- jamais de
                    // redemarrage a zero (gel crimsonlake repare).
                    if (!fillSlice(activeFill, deadline)) { resume(1); return; }
                    changed += activeFill.fixed;
                    activeFill = null;
                    continue;
                }
                long packed = queue.poll();
                if (!seen.add(packed)) continue;
                if (seen.size() > FIXWATER_SEEN_MAX) { capped++; continue; }
                int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) { skippedChunk++; continue; }
                BlockState state = level.getBlockState(mut.set(x, y, z));
                if (!state.is(Blocks.WATER)) continue;

                // De l'air lateralement OU dessous => l'eau aurait du couler la :
                // T107 (PRIORITE dev 30/09 : « IMPORTER EXACTEMENT le fixwater de
                // worldedit ... remplacer mon fixwater bidon par lui ») : on ne
                // declenche plus de tick de fluide vanilla a l'aveugle (T81),
                // on applique l'algorithme WorldEdit //fixwater lui-meme -- voir
                // fillSlice()/FillState (T114) definis plus bas. T95 : chaque lecture
                // laterale reste GARDEE par isLoadedAt (sinon generation
                // synchrone ~0,3-0,5 s piece en pleine boucle BFS).
                boolean open = false;
                for (int d = 0; d < 5 && !open; d++) {
                    int sx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int sz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    int sy = (d == 4) ? y - 1 : y;
                    if (sy < level.getMinBuildHeight()) continue;
                    if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, sx, sz)) { skippedChunk++; continue; }
                    open = level.getBlockState(side.set(sx, sy, sz)).isAir();
                }
                if (open && activeFill == null) {
                    // T114 : on CREE l'etat du fill reprendable ; il sera
                    // execute en tete de boucle des la prochaine iteration,
                    // sur autant de tranches qu'il faut.
                    activeFill = new FillState(x, y, z, radius);
                }

                // Propagation 6 directions dans l'eau (nappe entiere, une fois).
                // T95 : le BFS est BORNE a la boite [x0,x1]x[z0,z1] -- sans cette
                // borne, une graine au bord d'un ocean/lac voisin traversait la
                // nappe ENTIERE (66 908 cellules mesurees pour 371 declenchements
                // utiles) et les lectures hors boite tombaient dans des chunks
                // non charges (generation synchrone, voir plus haut).
                for (int d = 0; d < 6; d++) {
                    int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    int ny = y + (d == 4 ? -1 : d == 5 ? 1 : 0);
                    if (ny < level.getMinBuildHeight() || ny > level.getMaxBuildHeight() - 1) continue;
                    if (nx < x0 || nx > x1 || nz < z0 || nz > z1) continue;
                    long nk = BlockPos.asLong(nx, ny, nz);
                    if (seen.contains(nk)) continue;
                    if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, nx, nz)) { skippedChunk++; continue; }
                    if (level.getBlockState(side.set(nx, ny, nz)).is(Blocks.WATER))
                        queue.add(nk);
                }
            }

            // 3) fin de tour : on ne recommence que si des cellules ont ete corrigees
            if (changed > 0 && round < FIXWATER_MAX_ROUNDS) {
                round++;
                seen.clear();
                scanX = x0; scanZ = z0;
                resume(2);   // laisse les ticks de fluide s'executer d'abord
                return;
            }
            deepluckyblock.util.DebugLog.structure(
                    "fixWaterNearStructure (rayon {}) TERMINE : {} cellule(s) posee(s) source par fixwater WorldEdit exact (T107) "
                            + "sur {} cellule(s) d'eau connectee(s) visitees, {} tour(s), {} origine(s) de bande rayon 100 (T112){}{}{} ({} tranches, {} ms) -- cascade connectee T90, fills reprenables T114",
                    radius, changed, seen.size(), round, bandFires,
                    skippedChunk > 0 ? ", " + skippedChunk + " colonne(s)/cellule(s) sans chunk" : "",
                    capped > 0 ? ", PLAFOND " + FIXWATER_SEEN_MAX + " atteint (" + capped + " cellule(s) ignorees)" : "",
                    changed == 0 ? ", zone saine" : "",
                    slices, System.currentTimeMillis() - t0);
            if (onDone != null) onDone.run();
        }
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
                if (level.getBlockState(pos.set(x, y, z)).is(Blocks.WATER)) return ring;
            }
        }
        return 20;
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
    // T91 (B12) : scale 4 -> 2. La zone de scan (et ses chunks ~16x la boite de
    // force en x4) etait le premier poste de fixLiquids (67 s mesures + ~102 s
    // d'attente de chunks epingles au run du 29/09). En x2, chaque cote couvre
    // encore 2x l'emprise + 2x l'anneau de terrain : le bandeau rase, qui est
    // SEUL a reparer, reste tres largement couvert. La propagation connectee
    // et les preuves d'enclave T77 sont inchangees.
    // T144 : x2 transformait une zone terrain de 99 chunks en 462 chunks et
    // coutait 8,6 s, alors que T125 propage deja chaque frontiere humide avec
    // deux couronnes et un rayon local de 10. Garder la zone effective + halo.
    private static final int FIXLIQ_REPAIR_SCALE = 1;

    /** Profondeur maximale exploree sous la surface d'une colonne de liquide. */
    private static final int FIXLIQ_SCAN_DEPTH = 40;

    /** Creux maximal (en blocs) comble par la propagation du niveau d'eau. */
    private static final int FIXLIQ_MAX_GAP = 12;

    /** T90 (B10) : fenetre de recherche du FOND pour l'EAU, relevee de 12 a 32
     *  blocs (la lave garde {@link #FIXLIQ_MAX_GAP}).
     *
     *  Cause racine des « trous dans les lacs tres larges » signales le 29/09
     *  (« lac +30 blocs de largeur : tu suis les bords et remplis avec un rayon,
     *  le rayon est trop court, au centre jamais rempli ! ») : le bandeau rase
     *  en travers du lac n'etait rebouche qu'a condition de trouver un fond a
     *  moins de 12 blocs sous le niveau de la nappe. Or le fond d'un lac large
     *  descend a 15-30 blocs au centre (ocean : ~18) : la colonne centrale
     *  echouait au pre-filtre {@code gateTop} et restait un trou a jamais.
     *
     *  Securite inchangee : le verdict ENCLOSED/OPEN de la preuve d'enclave
     *  (T77) reste LE garde-fou anti-inondation ; la fenetre ne fait que
     *  permettre de trouver un fond plus profond dans un bassin PROUVE clos. */
    private static final int FIXLIQ_MAX_GAP_WATER = 32;

    /** Chunks traites par tick a l'etape 1 (conversion flowing -> source). */
    private static final int FIXLIQ_CHUNKS_PER_TICK = 24;

    /** Plafonds de volume : on ne noie pas la carte si le rayon est enorme. */
    // Water is bounded spatially per job, rather than cut off at an arbitrary volume.
    private static final int FIXLIQ_MAX_LAVA_FILL = 4_000;

    /** Entrees de propagation traitees au maximum par tick (garde-fou memoire). */
    private static final int FIXLIQ_QUEUE_PER_TICK = 4_000;
    private static final int FIXLIQ_QUEUE_MAX = 400_000;

    /** T77: max columns a single enclosure proof may explore before the OPEN
     *  verdict (safety: never drown land not proven enclosed). */
    private static final int FIXLIQ_ESCAPE_MAX = 200_000;

    /** Budget de temps par tick (aucun tick ne doit depasser 40 ms ici). */
    private static final long FIXLIQ_SLICE_MS = 40;

    /** Colonne (x,z) de liquide -> Y du plus haut bloc de liquide de la colonne. */
    private static final java.util.Map<Long, Integer> LIQ_WATER = new java.util.HashMap<>();
    private static final java.util.Map<Long, Integer> LIQ_LAVA = new java.util.HashMap<>();
    /** File de propagation du niveau (cles = BlockPos.asLong(x, niveau, z)). */
    private static final java.util.ArrayDeque<Long> LIQ_QUEUE = new java.util.ArrayDeque<>();

    /** T77: liquid surface COLUMNS (column keys = BlockPos.asLong(x, 0, z)): the walls
     *  an enclosure proof never crosses. Column-level mirror of LIQ_WATER/LIQ_LAVA
     *  whose keys embed the level Y. */
    private static final java.util.Set<Long> LIQ_SURF = new java.util.HashSet<>();
    /** T77: enclosure proof verdicts, by column: level proven OPEN (land component
     *  reaching the repair region boundary = open land) or ENCLOSED (sealed basin). */
    private static final java.util.Map<Long, Integer> LIQ_OPEN = new java.util.HashMap<>();
    private static final java.util.Map<Long, Integer> LIQ_BASIN = new java.util.HashMap<>();

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
     *
     * <p><b>T77 -- strict anti-flooding rule</b> (in-game test of 09/27:
     * 46,539 dry plain columns drowned in 34.3 s after the x4 range expansion):
     * pure WorldEdit-style propagation fills ANY dry land below the water level
     * out to the region edges -- plains, marshes, valleys. The rule is now: a
     * dry column below the nappe level is filled ONLY when a bounded escape
     * search proves its entire sub-level land component is ENCLOSED inside the
     * loaded repair area (a truly sealed basin: a dry hole left in water). A
     * component that reaches the region boundary, an unknown chunk outside the
     * near ring, or the exploration budget is OPEN = never filled. We plug dry
     * holes left inside water; we never add water, and never above the level
     * of the originating nappe.
     */
    public static void fixLiquidsPass(ServerLevel level, BlockPos min, BlockPos max, Runnable onDone) {
        fixLiquidsPass(level, min, max, true, onDone);
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
        // T138 : une photographie T103 entierement seche interdit toute
        // ecriture d'eau. Evite seulement CETTE passe liquide (et jamais les
        // passes terrain generiques : le garde T137 etait place trop haut).
        if (hasNaturalWaterRef() && NATURAL_WATER_COUNT == 0) {
            RUNNING_PASSES.remove(label);
            deepluckyblock.util.DebugLog.structure(
                    "fixLiquids T138 SAUTE : photographie vanilla seche, aucune ecriture admissible T103");
            if (onDone != null) onDone.run();
            return;
        }
        LIQ_WATER.clear(); LIQ_LAVA.clear(); LIQ_QUEUE.clear();
        LIQ_SURF.clear(); LIQ_OPEN.clear(); LIQ_BASIN.clear();
        LIQ_SRC.set(0); LIQ_FILL.set(0); LIQ_LAVA_FILL.set(0); LIQ_FILL_COLS.set(0);
        final int cx = (min.getX() + max.getX()) / 2, cz = (min.getZ() + max.getZ()) / 2;
        // Start with the former effective bounds, then expand water repair by x4.
        // Discover from loaded sources and load the connected frontier, not all dry chunks.
        int margin = Math.max(FIXLIQ_FORCE_MARGIN, terrainRing() + 16);
        int x0 = Math.max(cx - FIXLIQ_RADIUS, min.getX() - margin);
        int x1 = Math.min(cx + FIXLIQ_RADIUS, max.getX() + margin);
        int z0 = Math.max(cz - FIXLIQ_RADIUS, min.getZ() - margin);
        int z1 = Math.min(cz + FIXLIQ_RADIUS, max.getZ() + margin);
        // T77: the near force-ring (inForceRing) is computed BEFORE the x4 repair
        // expansion. Scaling it inflated the ring to the whole repair area
        // (measured CI storm: 729+ requested chunks, retry rounds beyond 40).
        int fx0 = x0 >> 4, fx1 = x1 >> 4, fz0 = z0 >> 4, fz1 = z1 >> 4;
        // Multiply each effective radius by four, including asymmetric odd-sized bounds.
        x0 = cx + (x0 - cx) * FIXLIQ_REPAIR_SCALE;
        x1 = cx + (x1 - cx) * FIXLIQ_REPAIR_SCALE;
        z0 = cz + (z0 - cz) * FIXLIQ_REPAIR_SCALE;
        z1 = cz + (z1 - cz) * FIXLIQ_REPAIR_SCALE;
        // T123 : une couronne complete d'un chunk autour de la grande zone.
        // Elle fait partie des bornes reparables et du balayage convergent.
        x0 -= 16; x1 += 16; z0 -= 16; z1 += 16;
        int sx0 = x0 >> 4, sx1 = x1 >> 4, sz0 = z0 >> 4, sz1 = z1 >> 4;
        List<int[]> chunks = new ArrayList<>();
        for (int ccx = sx0; ccx <= sx1; ccx++)
            for (int ccz = sz0; ccz <= sz1; ccz++) {
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
        step("fixLiquids: effective bounds x" + FIXLIQ_REPAIR_SCALE + " X=" + x0 + ".." + x1
                + " Z=" + z0 + ".." + z1 + "; loaded seeds and connected liquid frontier");
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
        final int seedMargin;
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
        // T77: enclosure-proof machinery (strict anti-flooding rule) -- pending
        // candidates plus the live escape-BFS state.
        final java.util.ArrayDeque<Long> verifyQueue = new java.util.ArrayDeque<>();
        final java.util.Set<Long> verifySet = new java.util.HashSet<>();
        final java.util.Set<Long> verifyLava = new java.util.HashSet<>();   // parked lava candidates
        final java.util.ArrayDeque<Long> escQueue = new java.util.ArrayDeque<>();
        final java.util.Set<Long> escSeen = new java.util.HashSet<>();
        long escOrigin; int escLvl; boolean escActive, escLava; int escExplored;
        int openRejected, proofs, fastFills;
        int naturalDrySkipped;   // T103 : colonnes séches hors nappe vanilla refusées
        // T117 : photographie AVANT ecriture des colonnes qui appartenaient a
        // la nappe vanilla mais sont maintenant vides, groupees par chunk.
        // Aucun remplissage n'a lieu pendant ce scan : le chunk N+1 ne peut
        // donc pas rater le trou parce que le chunk N vient d'etre modifie.
        final java.util.Map<Long, java.util.ArrayList<Long>> dryWaterByChunk = new java.util.HashMap<>();
        // T118 : niveaux d'eau REELLEMENT observes par chunk sur toute la zone
        // x2, independamment de T103. T117 utilisait LIQ_WATER (filtre T103) :
        // sur le log 02:41 il etait vide, donc la routine chunk-aware sortait
        // avant meme son diagnostic. Ces sources exterieures sont la reference
        // du BFS de chunks vides ; elles ne donnent jamais a elles seules le
        // droit de remplir une colonne.
        final java.util.Map<Long, Integer> observedWaterLevelByChunk = new java.util.HashMap<>();
        // T127 : colonnes d'eau de surface photographiees, y compris celles
        // hors T103. La convergence n'a besoin de visiter que ces sources :
        // une colonne sans eau ne peut, par definition, prouver eau/air.
        final java.util.Map<Long, Integer> observedSurfaceWaterColumns = new java.util.HashMap<>();
        int chunkAwareChunks, chunkAwareColumns, chunkAwareBlocks;
        boolean convergenceStarted, convergenceDone;
        int convergentRounds;


        LiquidJob(ServerLevel l, int cx, int cz, List<int[]> chunks, String label, Runnable onDone,
                  int fx0, int fx1, int fz0, int fz1, BlockPos min, BlockPos max,
                  int x0, int x1, int z0, int z1, boolean onDemand) {
            this.onDemand = onDemand;
            this.seedMargin = terrainRing() + NATURALIZE_EXTRA_RING;
            this.level = l; this.cx = cx; this.cz = cz; this.chunks = chunks; this.label = label; this.onDone = onDone;
            this.fx0 = fx0; this.fx1 = fx1; this.fz0 = fz0; this.fz1 = fz1;
            this.structureMin = min; this.structureMax = max;
            this.x0 = x0; this.x1 = x1; this.z0 = z0; this.z1 = z1;
            this.waterFillLimit = (int) Math.min(Integer.MAX_VALUE,
                    (long) (x1 - x0 + 1) * (z1 - z0 + 1) * FIXLIQ_MAX_GAP_WATER);   // T90
        }

        private boolean canRepair(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1
                    && !(x >= structureMin.getX() && x <= structureMax.getX()
                    && z >= structureMin.getZ() && z <= structureMax.getZ())
                    && !isProtected(x, z);
        }

        private boolean inOriginalLiquidBounds(int x, int z) {
            return x >= cx + (x0 - cx) / FIXLIQ_REPAIR_SCALE && x <= cx + (x1 - cx) / FIXLIQ_REPAIR_SCALE
                    && z >= cz + (z0 - cz) / FIXLIQ_REPAIR_SCALE && z <= cz + (z1 - cz) / FIXLIQ_REPAIR_SCALE;
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
                    // T77: in on-demand mode the scan seeds ONLY from already loaded
                    // chunks -- requesting the whole x4 scan list here was the chunk
                    // storm measured in CI. The near-halo frontier is still loaded by
                    // the refill traversal below, bounded to the seed margin.
                    if (onDemand) { skippedFar++; continue; }
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
            // Tous les chunks ont maintenant ete photographies. T125 : la
            // convergence est une machine tranchee ; elle ne doit plus bloquer
            // le thread serveur 72 secondes dans un seul tick.
            if (!convergenceStarted) {
                convergenceStarted = true;
                applyChunkAwareWater(); // coeur + deux couronnes figees
                new WaterConvergenceJob(() -> {
                    convergenceDone = true;
                    TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::slice);
                }).slice();
                return;
            }
            if (!convergenceDone) return;
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
            // T146 : une stair/slab waterlogged expose un FluidState WATER mais
            // n'est PAS une cellule de lac. Elle ne doit jamais devenir graine ni
            // imposer une continuite. Seuls les blocs liquides libres comptent.
            boolean waterTop = top.is(Blocks.WATER) && topFluid.is(net.minecraft.tags.FluidTags.WATER);
            boolean lavaTop = top.is(Blocks.LAVA) && topFluid.is(net.minecraft.tags.FluidTags.LAVA);
            // T118 : photographier d'abord les SOURCES REELLES de toute la
            // boite x2, y compris hors photographie T103. Aucun bloc n'est pose.
            if (waterTop) {
                long ck = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                observedWaterLevelByChunk.merge(ck, surface - 1, Math::max);
                // T128 : indexer seulement les eaux ayant deja de l'air
                // lateral. Une eau interieure ne peut pas devenir frontiere :
                // toutes les passes suivantes ajoutent de l'eau, jamais l'inverse.
                int lvl = surface - 1;
                boolean boundary = false;
                for (int d = 0; d < 4 && !boundary; d++) {
                    int ax = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int az = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (ax < x0 || ax > x1 || az < z0 || az > z1) continue;
                    if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, ax, az)) continue;
                    boundary = level.getBlockState(mut.set(ax, lvl, az)).isAir();
                }
                if (boundary) observedSurfaceWaterColumns.put(BlockPos.asLong(x, 0, z), lvl);
            }
            // T103 continue de filtrer l'ancien moteur colonne-par-colonne.
            if (!naturalWaterAt(x, z)) return;
            // T117 : colonne de la nappe de reference devenue seche. On ne la
            // repare PAS ici : on la photographie par chunk, avant la moindre
            // ecriture chunk-aware. Les terres naturellement seches n'arrivent
            // jamais ici grace au naturalWaterAt juste au-dessus.
            if (!waterTop && !lavaTop) {
                long ck = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                dryWaterByChunk.computeIfAbsent(ck, k -> new java.util.ArrayList<>())
                        .add(BlockPos.asLong(x, 0, z));
                return;
            }
            if (lavaTop && !inOriginalLiquidBounds(x, z)) return;
            int depth = Math.min(FIXLIQ_SCAN_DEPTH, surface - level.getMinBuildHeight());
            int hiWater = Integer.MIN_VALUE, hiLava = Integer.MIN_VALUE;
            for (int k = 0; k < depth; k++) {
                int y = surface - 1 - k;
                BlockState s = level.getBlockState(mut.set(x, y, z));
                var fs = s.getFluidState();
                if (s.is(Blocks.WATER) && fs.is(net.minecraft.tags.FluidTags.WATER)) {
                    if (hiWater == Integer.MIN_VALUE) hiWater = y;
                    if (!fs.isSource()) {
                        level.setBlock(mut, Blocks.WATER.defaultBlockState(), FAST_FLAG);
                        LIQ_SRC.incrementAndGet();
                    }
                } else if (s.is(Blocks.LAVA) && fs.is(net.minecraft.tags.FluidTags.LAVA)) {
                    if (hiLava == Integer.MIN_VALUE) hiLava = y;
                    if (!fs.isSource()) {
                        level.setBlock(mut, Blocks.LAVA.defaultBlockState(), FAST_FLAG);
                        LIQ_SRC.incrementAndGet();
                    }
                } else if (!s.isAir()) {
                    break;   // sol atteint : le reste de la colonne n'est plus du liquide
                }
            }
            if (hiWater != Integer.MIN_VALUE) {
                LIQ_WATER.put(BlockPos.asLong(x, hiWater, z), hiWater);
                LIQ_SURF.add(BlockPos.asLong(x, 0, z));
            }
            if (hiLava != Integer.MIN_VALUE) {
                LIQ_LAVA.put(BlockPos.asLong(x, hiLava, z), hiLava);
                LIQ_SURF.add(BlockPos.asLong(x, 0, z));
            }
        }

        /**
         * T117 -- REPARATION CHUNK-AWARE, jamais par petits rayons 3x3.
         *
         * La phase de scan a photographie simultanement TOUS les chunks avant
         * toute ecriture. Pour chaque chunk contenant une zone de nappe devenue
         * vide, on cherche le niveau d'eau d'un chunk voisin dans une fenetre
         * 5x5 chunks (donc largement >= 10x10 blocs), puis on remplit ENSEMBLE
         * toutes les colonnes de nappe manquantes de ce chunk. Le chunk suivant
         * utilise toujours la photographie initiale : le premier remplissage ne
         * peut jamais masquer sa propre absence -- exigence « chunkaware ».
         */
        private void applyChunkAwareWater() {
            // T118 : ne depend PLUS de LIQ_WATER/T103. Les sources sont toutes
            // les nappes reellement observees dans la boite x2.
            if (observedWaterLevelByChunk.isEmpty()) {
                deepluckyblock.util.DebugLog.structure(
                        "fixLiquids CHUNK-AWARE T118 : 0 chunk source d'eau observe -- aucun fill possible");
                return;
            }
            final int MIN_ZONE_COLUMNS = 100; // minimum reel >= 10x10 demande
            java.util.ArrayDeque<long[]> frontier = new java.util.ArrayDeque<>();
            java.util.Set<Long> classified = new java.util.HashSet<>();
            java.util.Map<Long, java.util.ArrayList<Long>> plans = new java.util.LinkedHashMap<>();
            java.util.Map<Long, Integer> planLevels = new java.util.HashMap<>();
            for (var e : observedWaterLevelByChunk.entrySet()) {
                frontier.add(new long[]{e.getKey(), e.getValue()});
                classified.add(e.getKey());
            }
            // PHOTOGRAPHIE/BFS COMPLET avant la premiere ecriture. Un chunk
            // qualifie propage la recherche au suivant, mais pas via de l'eau
            // nouvellement posee : uniquement via ce plan fige en memoire.
            final int[] dx8 = {1,-1,0,0,1,1,-1,-1};
            final int[] dz8 = {0,0,1,-1,1,-1,1,-1};
            while (!frontier.isEmpty()) {
                long[] from = frontier.poll();
                int ccx = (int) (from[0] >> 32), ccz = (int) from[0], lvl = (int) from[1];
                for (int d = 0; d < 8; d++) {
                    int ncx = ccx + dx8[d], ncz = ccz + dz8[d];
                    long nk = ((long) ncx << 32) ^ (ncz & 0xffffffffL);
                    if (!classified.add(nk)) continue;
                    if (level.getChunkSource().getChunkNow(ncx, ncz) == null) continue;
                    java.util.ArrayList<Long> empty = snapshotEmptyChunkAtLevel(ncx, ncz, lvl);
                    if (empty.size() < MIN_ZONE_COLUMNS) continue; // jamais de petit patch
                    plans.put(nk, empty);
                    planLevels.put(nk, lvl);
                    frontier.add(new long[]{nk, lvl}); // cascade chunk-aware figee
                }
            }
            // T121 : correction explicite, chaque extension a un rayon de 10
            // blocs et une deuxieme passe suit la premiere. Les deux couronnes sont
            // photographiees AVANT toute ecriture : la passe 2 ne prend donc
            // jamais l'eau posee par la passe 1 pour une preuve naturelle.
            java.util.Map<Long, Integer> pass1Levels = new java.util.HashMap<>();
            java.util.Map<Long, java.util.ArrayList<Long>> pass1 =
                    expandWaterPlans(plans, planLevels, 10, pass1Levels);
            java.util.Map<Long, Integer> pass2Levels = new java.util.HashMap<>();
            java.util.Map<Long, java.util.ArrayList<Long>> pass2 =
                    expandWaterPlans(pass1, pass1Levels, 10, pass2Levels);

            // ECRITURE seulement apres classification de TOUS les chunks et
            // des DEUX couronnes. Ordre explicite : coeur, rayon 10, puis rayon 10.
            applyWaterPlans(plans, planLevels);
            applyWaterPlans(pass1, pass1Levels);
            applyWaterPlans(pass2, pass2Levels);
            deepluckyblock.util.DebugLog.structure(
                    "fixLiquids CHUNK-AWARE T125 initial : {} chunk(s) source observes ; {} zone(s) >=10x10 ; coeur + 2 couronnes rayon 10 appliques ; demarrage de la grille 3x3 TRANCHEE sans freeze ; {} chunk-passe(s), {} colonne(s), {} bloc(s)",
                    observedWaterLevelByChunk.size(), plans.size(),
                    chunkAwareChunks, chunkAwareColumns, chunkAwareBlocks);
        }

        /**
         * T123 : grille glissante de fenetres 3x3 chunks, gauche->droite puis
         * droite->gauche a la ligne suivante. Chaque chunk central est lu avec
         * son voisinage de 1 chunk ; les fenetres se recouvrent donc bien.
         * Un tour photographie TOUT avant d'ecrire. S'il ecrit, on repart du
         * premier chunk. Le seul verdict de fin est zero air de bassin adjacent.
         */
        /** T125 : meme convergence T123/T124, mais photographie et ecriture
         * reprises sur plusieurs ticks du thread serveur. Aucun monde n'est lu
         * hors thread et aucune ecriture ne commence avant la photo complete. */
        private final class WaterConvergenceJob {
            final Runnable done;
            final int cminX = x0 >> 4, cmaxX = x1 >> 4;
            final int chunksPerRow = cmaxX - cminX + 1;
            final java.util.Map<Long, Boolean> basinCache = new java.util.HashMap<>();
            java.util.Map<Long, Integer> candidates;
            java.util.Set<Long> tested;
            java.util.Map<Long, Integer> waterLevels;
            java.util.ArrayList<Long> waters;
            // T128 : apres le premier tour exhaustif, une pose d'eau ne peut
            // creer une nouvelle frontiere que sur une colonne remplie. Les
            // eaux interieures sans air adjacent ne peuvent jamais devenir une
            // frontiere puisque l'algorithme ajoute de l'eau sans en retirer.
            final java.util.Map<Long, Integer> frontierWaters = new java.util.HashMap<>();
            java.util.ArrayList<java.util.Map.Entry<Long, Integer>> writes;
            int round, waterIndex, direction;
            int expandX = 99, expandZ, triggerX, triggerZ, triggerLevel;
            int writeIndex, beforeCols, beforeBlocks;
            final java.util.Set<Long> touchedChunks = new java.util.HashSet<>();
            boolean writing;

            WaterConvergenceJob(Runnable done) { this.done = done; startRound(); }

            void startRound() {
                round++;
                candidates = new java.util.LinkedHashMap<>();
                tested = new java.util.HashSet<>();
                // T127 : parcourir 250 000 colonnes vides trois fois coutait
                // 40 secondes et des ticks de 5 s. Seules les colonnes contenant
                // reellement de l'eau peuvent avoir de l'air adjacent. Union de
                // la photographie initiale et des remplissages des tours precedents.
                // T128 : le premier tour photographie toutes les eaux. Aux
                // reprises a zero, on repart bien du debut de la liste utile :
                // anciennes frontieres + colonnes ajoutees. On ne reconstruit
                // et ne retrie plus les ~96 000 eaux interieures immuables.
                waterLevels = new java.util.HashMap<>(round == 1
                        ? observedSurfaceWaterColumns : frontierWaters);
                for (var e : LIQ_WATER.entrySet())
                    waterLevels.merge(BlockPos.asLong(BlockPos.getX(e.getKey()), 0, BlockPos.getZ(e.getKey())),
                            e.getValue(), Math::max);
                waters = new java.util.ArrayList<>(waterLevels.keySet());
                waters.sort((a, b) -> {
                    int ax = BlockPos.getX(a), az = BlockPos.getZ(a);
                    int bx = BlockPos.getX(b), bz = BlockPos.getZ(b);
                    int ar = (az >> 4) - (z0 >> 4), br = (bz >> 4) - (z0 >> 4);
                    if (ar != br) return Integer.compare(ar, br);
                    int ac = ((ar & 1) == 0) ? (ax >> 4) : -(ax >> 4);
                    int bc = ((br & 1) == 0) ? (bx >> 4) : -(bx >> 4);
                    if (ac != bc) return Integer.compare(ac, bc);
                    if (ax != bx) return Integer.compare(ax, bx);
                    return Integer.compare(az, bz);
                });
                waterIndex = direction = 0;
                expandX = 99;
                writing = false;
            }

            void later() {
                deepluckyblock.util.TerrainChain.heartbeat();
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::slice);
            }

            void slice() {
                deepluckyblock.util.TerrainChain.heartbeat();
                deepluckyblock.util.ChunkKeeper.keep(level);
                if (!deepluckyblock.util.ChunkKeeper.zoneComplete(level)) { later(); return; }
                long deadline = System.nanoTime() + 8_000_000L;
                final int[] ddx = {1, -1, 0, 0};
                final int[] ddz = {0, 0, 1, -1};

                if (!writing) {
                    while (waterIndex < waters.size()) {
                        long wp = waters.get(waterIndex);
                        int x = BlockPos.getX(wp), z = BlockPos.getZ(wp);
                        Integer knownLevel = waterLevels.get(wp);
                        int lvl = knownLevel == null
                                ? deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.WORLD_SURFACE, x, z) - 1
                                : knownLevel;
                        if (x < x0 || x > x1 || z < z0 || z > z1
                                || lvl < level.getMinBuildHeight()
                                || !level.getBlockState(mut.set(x, lvl, z)).getFluidState()
                                        .is(net.minecraft.tags.FluidTags.WATER)) {
                            waterIndex++; direction = 0; continue;
                        }
                        while (direction < 4) {
                            if (expandX == 99) {
                                int ax = x + ddx[direction], az = z + ddz[direction];
                                if (ax < x0 || ax > x1 || az < z0 || az > z1
                                        || !level.getBlockState(mut.set(ax, lvl, az)).isAir()
                                        || !basinCached(ax, az, lvl, basinCache)) {
                                    direction++; continue;
                                }
                                // Cette eau est une vraie frontiere reparable ;
                                // elle seule merite d'etre revue aux tours suivants.
                                frontierWaters.put(wp, lvl);
                                triggerX = ax; triggerZ = az; triggerLevel = lvl;
                                expandX = expandZ = -10;
                            }
                            while (expandX <= 10) {
                                while (expandZ <= 10) {
                                    int nx = triggerX + expandX, nz = triggerZ + expandZ++;
                                    if (nx >= x0 && nx <= x1 && nz >= z0 && nz <= z1) {
                                        long key = BlockPos.asLong(nx, triggerLevel, nz);
                                        if (tested.add(key)) {
                                            long col = BlockPos.asLong(nx, 0, nz);
                                            if (!candidates.containsKey(col)
                                                    && level.getBlockState(mut.set(nx, triggerLevel, nz)).isAir()
                                                    && basinCached(nx, nz, triggerLevel, basinCache))
                                                candidates.put(col, triggerLevel);
                                        }
                                    }
                                    if (System.nanoTime() >= deadline) { later(); return; }
                                }
                                expandX++; expandZ = -10;
                            }
                            expandX = 99; direction++;
                        }
                        waterIndex++; direction = 0;
                        if (System.nanoTime() >= deadline) { later(); return; }
                    }
                    if (candidates.isEmpty()) {
                        convergentRounds = round;
                        deepluckyblock.util.DebugLog.structure(
                                "fixLiquids grille 3x3 T127 tour {} : PLUS AUCUN air de bassin adjacent a l'eau ({} sources utiles, vides non rescannes)", round, waters.size());
                        done.run(); return;
                    }
                    writes = new java.util.ArrayList<>(candidates.entrySet());
                    writeIndex = 0; beforeCols = LIQ_FILL_COLS.get(); beforeBlocks = LIQ_FILL.get();
                    touchedChunks.clear(); writing = true;
                }

                while (writeIndex < writes.size()) {
                    var e = writes.get(writeIndex++);
                    int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
                    int old = LIQ_FILL_COLS.get();
                    tryFillColumn(x, z, e.getValue(), false);
                    if (LIQ_FILL_COLS.get() > old)
                        touchedChunks.add(((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL));
                    if (System.nanoTime() >= deadline) { later(); return; }
                }
                int dc = LIQ_FILL_COLS.get() - beforeCols;
                if (dc <= 0) {
                    convergentRounds = round;
                    deepluckyblock.util.DebugLog.structure(
                            "fixLiquids grille 3x3 T127 tour {} : {} candidat(s), 0 colonne modifiable -- stable", round, candidates.size());
                    done.run(); return;
                }
                int db = LIQ_FILL.get() - beforeBlocks;
                chunkAwareChunks += touchedChunks.size(); chunkAwareColumns += dc; chunkAwareBlocks += db;
                deepluckyblock.util.DebugLog.structure(
                        "fixLiquids grille 3x3 T127 tour {} : {} colonne(s) / {} bloc(s) ; REPRISE A ZERO", round, dc, db);
                deepluckyblock.util.SafeSurface.clearCache();
                startRound(); later();
            }
        }

        private boolean basinCached(int x, int z, int lvl, java.util.Map<Long, Boolean> cache) {
            long key = BlockPos.asLong(x, lvl, z);
            Boolean known = cache.get(key);
            if (known != null) return known;
            boolean result = emptyBasinColumnAtLevel(x, z, lvl);
            cache.put(key, result);
            return result;
        }

        /**
         * Photographie une couronne horizontale autour d'un plan deja qualifie.
         * La distance est un rayon en BLOCS (10 pour T121), pas un petit
         * remplissage autonome : sans zone chunk >=10x10 en entree, rien ne part.
         */
        private java.util.Map<Long, java.util.ArrayList<Long>> expandWaterPlans(
                java.util.Map<Long, java.util.ArrayList<Long>> source,
                java.util.Map<Long, Integer> sourceLevels, int width,
                java.util.Map<Long, Integer> outLevels) {
            java.util.Map<Long, java.util.ArrayList<Long>> out = new java.util.LinkedHashMap<>();
            java.util.Set<Long> sourceColumns = new java.util.HashSet<>();
            java.util.Set<Long> accepted = new java.util.HashSet<>();
            java.util.Set<Long> tested = new java.util.HashSet<>();
            java.util.Map<Long, Boolean> basinCache = new java.util.HashMap<>();
            for (var e : source.entrySet()) sourceColumns.addAll(e.getValue());
            for (var e : source.entrySet()) {
                Integer lvlObj = sourceLevels.get(e.getKey());
                if (lvlObj == null) continue;
                int lvl = lvlObj;
                for (long p : e.getValue()) {
                    int px = BlockPos.getX(p), pz = BlockPos.getZ(p);
                    for (int dx = -width; dx <= width; dx++)
                        for (int dz = -width; dz <= width; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) > width) continue;
                            int x = px + dx, z = pz + dz;
                            long col = BlockPos.asLong(x, 0, z);
                            long testKey = BlockPos.asLong(x, lvl, z);
                            if (sourceColumns.contains(col) || accepted.contains(col) || !tested.add(testKey)) continue;
                            if (!basinCached(x, z, lvl, basinCache)) continue;
                            accepted.add(col);
                            long ck = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                            out.computeIfAbsent(ck, k -> new java.util.ArrayList<>()).add(col);
                            outLevels.putIfAbsent(ck, lvl);
                        }
                }
            }
            return out;
        }

        private void applyWaterPlans(java.util.Map<Long, java.util.ArrayList<Long>> plans,
                                     java.util.Map<Long, Integer> levels) {
            for (var e : plans.entrySet()) {
                Integer lvlObj = levels.get(e.getKey());
                if (lvlObj == null) continue;
                int beforeCols = LIQ_FILL_COLS.get(), beforeBlocks = LIQ_FILL.get();
                for (long p : e.getValue())
                    tryFillColumn(BlockPos.getX(p), BlockPos.getZ(p), lvlObj, false);
                int dc = LIQ_FILL_COLS.get() - beforeCols;
                if (dc > 0) {
                    chunkAwareChunks++;
                    chunkAwareColumns += dc;
                    chunkAwareBlocks += LIQ_FILL.get() - beforeBlocks;
                }
            }
        }

        private boolean emptyBasinColumnAtLevel(int x, int z, int lvl) {
            if (!canRepair(x, z) || isProtected(x, z)) return false;
            BlockState at = level.getBlockState(mut.set(x, lvl, z));
            if (!at.isAir() && !at.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return false;
            int floor = topSolidAt(x, z, lvl, lvl - FIXLIQ_MAX_GAP_WATER, false);
            if (floor == Integer.MIN_VALUE || floor >= lvl) return false;
            BlockState fs = level.getBlockState(mut.set(x, floor, z));
            if (isSurfaceDecor(fs) || !fs.blocksMotion()) return false;
            for (int y = floor + 1; y <= lvl; y++) {
                BlockState s = level.getBlockState(mut.set(x, y, z));
                if (!s.isAir() && !s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return false;
            }
            return true;
        }

        /** Photographie les colonnes de bassin vides d'un chunk au Y voisin. */
        private java.util.ArrayList<Long> snapshotEmptyChunkAtLevel(int ccx, int ccz, int lvl) {
            java.util.ArrayList<Long> out = new java.util.ArrayList<>();
            int bx = ccx << 4, bz = ccz << 4;
            for (int x = bx; x < bx + 16; x++) for (int z = bz; z < bz + 16; z++) {
                if (!canRepair(x, z) || isProtected(x, z)) continue;
                BlockState at = level.getBlockState(mut.set(x, lvl, z));
                if (!at.isAir() && !at.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) continue;
                int floor = topSolidAt(x, z, lvl, lvl - FIXLIQ_MAX_GAP_WATER, false);
                if (floor == Integer.MIN_VALUE || floor >= lvl) continue;
                BlockState fs = level.getBlockState(mut.set(x, floor, z));
                if (isSurfaceDecor(fs) || !fs.blocksMotion()) continue;
                boolean free = true;
                for (int y = floor + 1; y <= lvl && free; y++) {
                    BlockState s = level.getBlockState(mut.set(x, y, z));
                    free = s.isAir() || s.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
                }
                if (free) out.add(BlockPos.asLong(x, 0, z));
            }
            return out;
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
                    if (!canRepair(nx, nz) || (lava && !inOriginalLiquidBounds(nx, nz))) continue;
                    if (level.getChunkSource().getChunkNow(nx >> 4, nz >> 4) == null) {
                        // T75 : le voisin n'est pas en memoire. Dans l'anneau proche on le
                        // demande et on RETENTE ce point (avant, la propagation s'arretait net
                        // a la frontiere des chunks charges : c'est le « mur d'eau ») ; plus
                        // loin on ne force rien.
                        if (onDemand) {
                            // T77: on-demand frontier loads stay inside the near halo of the
                            // footprint (seedMargin); even a liquid-connected cold chunk beyond
                            // it is never requested (fixture: the intact padding source one
                            // column past its chunk must NOT seed an unrelated frontier).
                            if (nx < structureMin.getX() - seedMargin || nx > structureMax.getX() + seedMargin
                                    || nz < structureMin.getZ() - seedMargin || nz > structureMax.getZ() + seedMargin) continue;
                            // Keep the frontier sparse: no rectangular dry halo expansion.
                            // The next slice waits until these chunks are loaded AND pinned.
                            if (deepluckyblock.util.ChunkKeeper.trackAdditionalChunk(level, nx >> 4, nz >> 4)) requested++;
                            // T77: same bounded-round discipline as the request() branch.
                            // An unbounded onDemand retry could never conclude on a cold
                            // frontier (measured: rounds beyond 40, job never ended).
                            if (refillRound < FIXLIQ_PENDING_ROUNDS) retry.add(packed); else stalled++;
                        } else {
                            if (!inForceRing(nx >> 4, nz >> 4)) continue;
                            deepluckyblock.util.SafeSurface.request(level, nx >> 4, nz >> 4);
                            if (refillRound < FIXLIQ_PENDING_ROUNDS) retry.add(packed); else stalled++;
                        }
                        continue;
                    }
                    // Traverse an already full source column too. Otherwise the BFS
                    // stops on untouched water and never reaches a dry hole beyond it.
                    BlockState atLevel = level.getBlockState(mut.set(nx, lvl, nz));
                    if (atLevel.is(lava ? Blocks.LAVA : Blocks.WATER) && atLevel.getFluidState().isSource()) {
                        if (onDemand) deepluckyblock.util.ChunkKeeper.trackAdditionalChunk(level, nx >> 4, nz >> 4);
                        if (lava) LIQ_LAVA.put(nk, lvl); else LIQ_WATER.put(nk, lvl);
                        LIQ_SURF.add(BlockPos.asLong(nx, 0, nz));
                        LIQ_QUEUE.add(nk);
                        continue;
                    }
                    if (onDemand) deepluckyblock.util.ChunkKeeper.trackAdditionalChunk(level, nx >> 4, nz >> 4);
                    // T77: STRICT RULE -- never fill a dry column that does not belong to
                    // a pre-existing connected nappe, and never above the nappe's own
                    // level. The verdict comes from a bounded escape BFS (drainVerify):
                    // ENCLOSED -> fill right away, OPEN -> permanent reject at this level,
                    // unproven -> park the candidate until its proof concludes.
                    // T94 (consigne terrain 29/09 : « continuer seulement l'eau en
                    // fixant, SANS modifier le y de celle-ci » -- prendre le y de
                    // l'eau coupee par le vide comme reference de profondeur) : si
                    // la colonne seche candidate touche DEJA une source d'eau
                    // voisine, on la remet AU NIVEAU REEL de cette eau (jamais au-
                    // dessus du niveau parent ni au-dela du plus bas voisin) SANS
                    // payer la preuve d'enclave. Mesure dragon/everest : 73 770 et
                    // 93 911 preuves = 199 s et 170 s de serveur. Le passage rapide
                    // couvre les murs et fonds de lac coupes (le cas massif), tout
                    // en restant gate par toutes les regles de tryFillColumn (fond
                    // naturel proche, colonne libre, plafonds de volume). Toute
                    // colonne SANS source voisine garde le chemin de preuve strict
                    // (anti-inondation T77 inchange), de meme que la lave.
                    // T103 (consigne dev 30/09 : « reprendre la meme base que
                    // vanilla et l'expender », interdiction du « lac artificiel »
                    // vu en jeu) : une colonne SECHE n'est remplie QUE si elle
                    // appartenait a la nappe vanilla memorisee par les smooths.
                    // Ni le passage rapide T94 ni la preuve d'enclave T77 ne
                    // peuvent desormais etendre l'eau sur de la terre naturelle --
                    // la propagation s'arrete pile sur le rivage vanilla.
                    if (!lava && !naturalWaterAt(nx, nz)) { naturalDrySkipped++; continue; }
                    // T117 : INTERDICTION des petits remplissages voisins
                    // colonne-par-colonne (rendu « radius 3x3 » constate). Toute
                    // eau manquante de la nappe T103 a deja ete photographiee et
                    // traitee EN BLOC par applyChunkAwareWater(). La propagation
                    // historique reste uniquement pour la lave.
                    if (!lava) continue;
                    int fastLvl = Integer.MIN_VALUE;
                    if (fastLvl != Integer.MIN_VALUE) {
                        fastFills++;
                        tryFillColumn(nx, nz, fastLvl, false);
                        continue;
                    }
                    long colKey = BlockPos.asLong(nx, 0, nz);
                    Integer openAt = LIQ_OPEN.get(colKey);
                    if (openAt != null && openAt == lvl) { openRejected++; continue; }
                    Integer basinAt = LIQ_BASIN.get(colKey);
                    if (basinAt == null || basinAt != lvl) {
                        // Cheap exact pre-filter so hopeless candidates never start a proof.
                        // T90 (B10) : fond cherche jusqu'a 32 blocs pour l'eau (lacs larges).
                        int gateTop = topSolidAt(nx, nz, lvl,
                                lvl - (lava ? FIXLIQ_MAX_GAP : FIXLIQ_MAX_GAP_WATER), lava);
                        if (gateTop == Integer.MIN_VALUE || gateTop >= lvl) continue;
                        // Park the DRY CANDIDATE (nk), never the polled water column:
                        // the proof must start on land, and the parent liquid type
                        // rides along in verifyLava (water/lava stay separate).
                        if (verifySet.add(nk)) {
                            verifyQueue.add(nk);
                            if (lava) verifyLava.add(nk);
                        }
                        continue;
                    }
                    tryFillColumn(nx, nz, lvl, lava);
                }
            }
            if (!LIQ_QUEUE.isEmpty()) {
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::slice);
                return;
            }
            if (!verifyQueue.isEmpty() || escActive) {
                // T77: unproven dry candidates remain; conclude their enclosure
                // proofs (fills re-feed LIQ_QUEUE and are processed next tick).
                drainVerify(t0);
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
         * T77: fills ONE proven-enclosed column up to the nappe level (never above).
         * The body is word for word the former inline fill block: every exact
         * condition (nearby floor, natural solid floor, free pocket, volume caps)
         * is re-checked at fill time, so a stale candidate can never overfill.
         */
        private void tryFillColumn(int nx, int nz, int lvl, boolean lava) {
            long nk = BlockPos.asLong(nx, lvl, nz);
            if (LIQ_WATER.containsKey(nk) || LIQ_LAVA.containsKey(nk)) return;   // deja plein
            // T90 (B10) : meme fenetre de fond elargie pour l'eau (32 blocs).
            int top = topSolidAt(nx, nz, lvl, lvl - (lava ? FIXLIQ_MAX_GAP : FIXLIQ_MAX_GAP_WATER), lava);
            if (top == Integer.MIN_VALUE || top >= lvl) return;    // pas de fond proche / deja plein
            int need = 0;
            int cap = lava ? FIXLIQ_MAX_LAVA_FILL : waterFillLimit;
            BlockState floor = level.getBlockState(mut.set(nx, top, nz));
            if (isSurfaceDecor(floor) || !floor.blocksMotion()) return;   // fond naturel solide uniquement
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
            if (!free || need == 0) return;
            if ((lava ? LIQ_LAVA_FILL.get() : LIQ_FILL.get()) + need > cap) { capped++; return; }
            BlockState source = lava ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState();
            for (int y = top + 1; y <= lvl; y++) {
                mut.set(nx, y, nz);
                if (!level.getBlockState(mut).equals(source)) level.setBlock(mut, source, FAST_FLAG);
            }
            if (lava) LIQ_LAVA_FILL.addAndGet(need); else LIQ_FILL.addAndGet(need);
            LIQ_FILL_COLS.incrementAndGet();
            if (lava) LIQ_LAVA.put(nk, lvl); else LIQ_WATER.put(nk, lvl);
            // Filled columns are NOT added to LIQ_SURF: only pre-existing nappe
            // columns wall off escape proofs. A basin raised to one level must
            // stay traversable by a later proof at a higher connected level.
            LIQ_QUEUE.add(nk);
        }

        /**
         * T94 : Y REEL de la source d'eau voisine la plus pertinente pour une
         * colonne seche candidate. Fenetre [lvl - FIXLIQ_MAX_GAP_WATER, lvl + 1] :
         * on descend depuis le haut de la fenetre dans chacune des 4 colonnes
         * voisines directes ; a la premiere SOURCE d'eau non tombante rencontree
         * on retient ce Y (c'est le vrai sommet de la nappe voisine -- l'eau
         * « coupee par le vide » de la consigne), et on s'arrete des qu'on touche
         * un solide (l'eau recherchée borde le trou, elle n'est pas enterree).
         * Renvoie le MIN des niveaux trouves (jamais au-dessus de l'eau reelle la
         * plus basse qui borde la colonne), Integer.MIN_VALUE s'il n'y a pas de
         * source voisine (la preuve d'enclave stricte T77 prend le relais).
         */
        private int neighborWaterLevel(int x, int z, int lvl) {
            int hi = Math.min(lvl + 1, level.getMaxBuildHeight() - 1);
            int lo = Math.max(lvl - FIXLIQ_MAX_GAP_WATER, level.getMinBuildHeight());
            int best = Integer.MIN_VALUE;
            for (int d = 0; d < 4; d++) {
                int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                int nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (nx < x0 || nx > x1 || nz < z0 || nz > z1) continue;      // T95 : borne boite
                if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, nx, nz)) continue;
                for (int y = hi; y >= lo; y--) {
                    BlockState s = level.getBlockState(mut.set(nx, y, nz));
                    if (s.is(Blocks.WATER)) {
                        if (s.getFluidState().isSource())
                            best = (best == Integer.MIN_VALUE) ? y : Math.min(best, y);
                        break;   // premier bloc d'eau touche : la colonne est classee
                    }
                    if (!s.getFluidState().isEmpty()) break;   // autre liquide (lave) : mur
                    if (s.blocksMotion()) break;               // solide au-dessus : eau enterree, ignorer
                }
            }
            return best == Integer.MIN_VALUE ? Integer.MIN_VALUE : Math.min(best, lvl);
        }

        /**
         * T77: enclosure-proof engine. Every dry candidate below the nappe level
         * starts an escape BFS over its sub-level land component (heightmap
         * MOTION_BLOCKING reads only -- no block scans, no long ticks, the slice
         * budget is honored). Verdicts: OPEN when the component reaches the
         * repair-region boundary, an unknown chunk outside the near ring, or the
         * exploration budget; ENCLOSED when the search fully exhausts INSIDE the
         * loaded repair area (sealed basin proven).
         *
         * @return true once nothing is left awaiting a proof.
         */
        private boolean drainVerify(long t0) {
            while (System.currentTimeMillis() - t0 < FIXLIQ_SLICE_MS) {
                if (!escActive) {
                    if (verifyQueue.isEmpty()) return true;
                    long p = verifyQueue.poll(); verifySet.remove(p);
                    boolean lv = verifyLava.remove(p);   // parent liquid type rides with the candidate
                    int px = BlockPos.getX(p), pl = BlockPos.getY(p), pz = BlockPos.getZ(p);
                    long ck = BlockPos.asLong(px, 0, pz);
                    Integer oa = LIQ_OPEN.get(ck);
                    if (oa != null && oa == pl) { openRejected++; continue; }
                    Integer ba = LIQ_BASIN.get(ck);
                    if (ba != null && ba == pl) { tryFillColumn(px, pz, pl, lv); continue; }
                    escOrigin = p; escLvl = pl; escLava = lv;
                    escSeen.clear(); escQueue.clear();
                    escSeen.add(ck); escQueue.add(ck);
                    escExplored = 0; escActive = true; proofs++;
                }
                while (escActive && !escQueue.isEmpty()) {
                    if (System.currentTimeMillis() - t0 >= FIXLIQ_SLICE_MS) return false;
                    long ck = escQueue.poll();
                    int ex0 = BlockPos.getX(ck), ez0 = BlockPos.getZ(ck);
                    for (int d = 0; d < 4; d++) {
                        int ex = ex0 + (d == 0 ? 1 : d == 1 ? -1 : 0);
                        int ez = ez0 + (d == 2 ? 1 : d == 3 ? -1 : 0);
                        long ek = BlockPos.asLong(ex, 0, ez);
                        if (escSeen.contains(ek)) continue;
                        // Reaching the repair-region edge = connected to open land.
                        if (ex < x0 || ex > x1 || ez < z0 || ez > z1) { concludeOpen(); break; }
                        if (LIQ_SURF.contains(ek)) continue;                    // nappe = paroi
                        Integer oa = LIQ_OPEN.get(ek);
                        if (oa != null && oa == escLvl) { concludeOpen(); break; }   // transitivity
                        if (isProtected(ex, ez)) continue;
                        if (level.getChunkSource().getChunkNow(ex >> 4, ez >> 4) == null) {
                            // Unknown land is OPEN land: never drown what we cannot
                            // see, and never request nor generate a chunk for a
                            // proof (measured CI storm: 1,360 load requests, retry
                            // rounds beyond 40, dlbverify timed out at 240 s).
                            concludeOpen(); break;
                        }
                        // Below the nappe level? (heightmap read only, no block scan)
                        int topY = deepluckyblock.util.SafeSurface.height(level,
                                Heightmap.Types.MOTION_BLOCKING, ex, ez) - 1;
                        if (topY >= escLvl) continue;    // emerging ground = wall
                        Integer ba = LIQ_BASIN.get(ek);
                        if (ba == null || ba != escLvl) {
                            // dry land column below the level: the component grows
                            if (++escExplored > FIXLIQ_ESCAPE_MAX) { concludeOpen(); break; }
                        }
                        escSeen.add(ek); escQueue.add(ek);
                    }
                }
                if (escActive) concludeBasin();   // frontier exhausted without an exit: sealed
            }
            return false;   // tick budget spent: resume next tick
        }

        /** T77: verdict OPEN -- the component touches open land; nothing is ever filled. */
        private void concludeOpen() {
            for (Long c : escSeen) LIQ_OPEN.put(c, escLvl);
            openRejected++;
            escActive = false; escQueue.clear(); escSeen.clear();
        }

        /** T77: verdict ENCLOSED -- sealed basin proven; members become fillable at this level. */
        private void concludeBasin() {
            for (Long c : escSeen) LIQ_BASIN.put(c, escLvl);
            int px = BlockPos.getX(escOrigin), pz = BlockPos.getZ(escOrigin);
            escActive = false; escQueue.clear(); escSeen.clear();
            tryFillColumn(px, pz, escLvl, escLava);
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
                            + "{} colonne(s) / {} bloc(s) d'eau remis a niveau, {} colonne(s) / {} bloc(s) de lave, "
                            + "{} preuve(s) d'enclave, {} rejet(s) terre ferme ouverte (regle stricte T77), "
                    + "{} remise(s) directe(s) au niveau de l'eau voisine (T94), "
                    + "{} colonne(s) seche(s) hors nappe refusees (T103 nappe vanilla{}), {} ms",
                    requested, unloaded,
                    LIQ_SRC.get(), LIQ_FILL_COLS.get(), LIQ_FILL.get(),
                    LIQ_LAVA.size(), LIQ_LAVA_FILL.get(), proofs, openRejected, fastFills,
                    naturalDrySkipped, hasNaturalWaterRef() ? "" : " ABSENTE",
                    System.currentTimeMillis() - started);
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

    /** Colonnes traitees par tranche (la passe est time-sliced).
     *  T91 (B12) : 384 -> 768 -- le budget de tranche (T9/T91) reste le vrai
     *  regulateur ; un lot plus large reduit le nombre de ticks de la passe. */
    private static final int HOLEFILL_COLS_PER_SLICE = 768;

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
        // T144 : seules l'emprise et sa couture immediate peuvent produire un
        // vide visible lie a la structure. Les grottes du grand anneau restent
        // naturelles et ne sont plus rebouchees (5,2 s et 21 892 blocs au run).
        List<int[]> cols = columnsOf(min, max, 6);
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
        // T144 : WORLD_SURFACE prend les feuilles comme plafond. Le filler voyait
        // alors l'air sous une canopee comme une « cavite » et montait une colonne
        // de dirt/grass jusqu'aux feuilles. NO_LEAVES part exclusivement du sol.
        int top = Math.min(level.getMaxBuildHeight() - 2,
                deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1);
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
                        BlockState floor = sec.getBlockState(x & 15, y & 15, z & 15);
                        fillPocket(level, mut, x, z, y + 1, runTop, y, floor);
                        runTop = Integer.MIN_VALUE;
                    }
                    lastSolid = true;
                    y = low - 1;
                    continue;
                }
            }
            // Section mixte : lecture bloc par bloc (chemin d'origine).
            BlockState s = sec != null
                    ? sec.getBlockState(x & 15, y & 15, z & 15)
                    : readBlock(level, chunk, mut, x, y, z);
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
        // T132 : la poche vient d'etre photographiee bloc par bloc/section dans
        // sealColumn, sur ce meme thread et sans yield. Relire chaque case via
        // ServerLevel avant de l'ecrire doublait les acces (91 055 lectures
        // redondantes au test T131). Aucun autre code ne peut modifier la colonne
        // entre la detection et cet appel synchrone.
        for (int y = yLow; y <= yHigh; y++) {
            mut.set(x, y, z);
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
    // T120 : 105 tranches / 8,4 s mesurees ; 768 garde exactement le meme scan.
    private static final int SWEEP_COLS_PER_SLICE = 768;

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
            if (isLooseSurface(s) && y <= collarGroundFloor(x, z)) {
                supported = true; y++; continue;
            }
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
                supported = !level.getBlockState(mut.set(x, y, z)).isAir();
                y++;
                continue;
            }
            if (!hasNaturalSupport(level, mut, x, y, z, s)
                    && isFloatingCluster(level, x, y, z, s)) {
                // hasNaturalSupport moved mut to the supporting block / last neighbour.
                level.setBlock(mut.set(x, y, z), Blocks.AIR.defaultBlockState(), FAST_FLAG);
                SWEPT_NAT.incrementAndGet();
                SWEPT.incrementAndGet();
                supported = false;
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
            if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, x, z)) return false;
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
                if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, nx, nz)) return false;
                BlockState n = level.getBlockState(m.set(nx, ny, nz));
                if (n.isAir() || !isSweepableNatural(n)) continue;   // l'amas ne s'etend qu'entre NATURELS
                q.add(new long[]{nx, ny, nz});
            }
        }
        // A size limit is not proof of a floating cluster: preserve unexamined blocks.
        return q.isEmpty();
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
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, nx, nz)) return true;
            BlockState n = level.getBlockState(mut.set(nx, y + d.getStepY(), nz));
            if (!n.isAir() && n.blocksMotion()) return true;
        }
        return false;
    }
}
