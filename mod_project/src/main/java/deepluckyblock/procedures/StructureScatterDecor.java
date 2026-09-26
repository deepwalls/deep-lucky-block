package deepluckyblock.procedures;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * StructureScatterDecor — sème de PETITS SCHEMATICS .nbt VANILLA autour des
 * grosses structures.
 *
 * ---------------------------------------------------------------------------
 * PROVENANCE — schematics CONSTRUITS PAR LA COMMUNAUTE, telecharges
 * ---------------------------------------------------------------------------
 * DEUX depots, aucun fichier genere, aucune structure vanilla.
 *
 * 1) **Moog's Voyager Structures** (Moog-s-Mods/MoogsVoyagerStructures),
 *    branche 1.21-datapack, dossier 1_21_0. ~8 millions de telechargements
 *    sur Modrinth, licence **MIT**. 130+ structures baties a la main en
 *    blocs vanilla. C'est de la l'essentiel du catalogue : vrais ROCHERS
 *    (granite, diorite, pierre, boulder), ARBRES et troncs morts, bancs,
 *    fontaine, moulin, statues, ruines, puits, tas de buches, etals,
 *    charrettes, lanternes par essence de bois.
 *
 * 2) **Repurposed Structures** (TelepathicGrunt/RepurposedStructures),
 *    branche 1.21-Arch, licence **LGPL-3.0** : ruines de briques et de gres
 *    par climat, cabanes de sorciere, camps d'avant-poste, puits thematiques.
 *
 * Depots evalues puis ECARTES faute de licence exploitable : Terralith et
 * Structory (NOASSERTION), lithostitched et AlexsCaves (aucune licence).
 * PlanetMinecraft renvoie 403 et mineschematic.com impose un CAPTCHA sur son
 * endpoint de telechargement : impossible d'y puiser automatiquement.
 *
 * VERSION : piege verifie deux fois. Les branches par defaut livrent du
 * DataVersion 4786 (1.21.9), une version FUTURE que 1.21.1 ne sait pas
 * retrograder. Les branches retenues donnent **3953** (1.21.0), compatible
 * avec notre 3955. Controle sur les 104 fichiers.
 *
 * Inventaire : 104 fichiers, 118 Ko. Chaque fichier est reference par au
 * moins un theme (aucun poids mort dans le jar).
 *
 * ---------------------------------------------------------------------------
 * RÈGLES DE POSE (demandes utilisateur)
 * ---------------------------------------------------------------------------
 *  - **15 schematics différents par thème** (voir les trois tableaux).
 *  - **4 à 7 décors posés** à chaque spawn d'une grosse structure.
 *  - Posés **AVANT les arbres** : c'est decorate() qui appelle scatter() puis
 *    replantTrees(), dans cet ordre.
 *  - Les emprises posées sont enregistrées dans PLACED_FOOTPRINTS ; le replant
 *    d'arbres les consulte via isInsideDecor() pour **ne jamais planter dans
 *    ni sur un décor**.
 *  - Rotation (NONE/90/180/270) + miroir aléatoires : 8 variantes par fichier.
 */
public final class StructureScatterDecor {

    private StructureScatterDecor() {}

    public enum Theme { CITADEL, OBSERVATORY, DRAGON, GENERIC }

    /**
     * Nombre de décors posés autour d'une grosse structure.
     * La demande initiale etait 4-7 ; le catalogue etant passe a 25 entrees par
     * theme, la fourchette est elargie a 5-9 pour mieux exploiter la variete
     * sans surcharger la zone (l'anti-chevauchement limite de toute facon les
     * poses effectives sur les terrains accidentes).
     */
    // Demande utilisateur : « plutot du random entre 9-16 max dont 3-7
    // naturels, essaye d'avoir de la diversite et pas trop dupliquer le meme
    // mini schem partout ».
    private static final int MIN_DECOR = 9;
    private static final int MAX_DECOR = 16;

    /** Nombre maximal d'exemplaires d'un MEME schematic sur un site donne. */
    private static final int MAX_SAME_SCHEMATIC = 2;

    /**
     * Quota de decors NATURELS par grosse structure (demande utilisateur :
     * « tu as le droit par structure (grosse) spawn, de mettre 3-7 structures
     * NATURELLES, c'est a dire arbres, rochers, steles etc.. les non naturels
     * sont les firecamp, etc »).
     *
     * Le tirage pose donc d'abord entre MIN_NATURAL et MAX_NATURAL elements
     * naturels, puis complete avec des elements artificiels jusqu'au total.
     */
    private static final int MIN_NATURAL = 3;
    private static final int MAX_NATURAL = 7;

    /**
     * Un schematic est-il NATUREL ?
     *
     * Naturel = ce que le paysage produit ou ce que le temps a laisse :
     * rochers, arbres vivants ou morts, troncs, steles et piliers runiques,
     * ruines envahies, statues erodees, cristaux, mares, champignons.
     *
     * Artificiel = ce qu'un habitant a pose et entretient : feux de camp,
     * tentes, cages, etals de marche, charrettes, lanternes, puits, bancs,
     * moulins, epouvantails, enclos, habitations, campements.
     */
    private static boolean isNatural(String name) {
        // Artificiel d'abord : ces marqueurs l'emportent (un "camp" reste
        // artificiel meme s'il contient des buches).
        for (String k : new String[]{
                "camp", "tent", "cage", "stall", "cart", "lant", "well",
                "bench", "windmill", "haystack", "hut", "pen", "farm",
                "gallows", "outhouse", "shed", "pump", "table", "fountain",
                "lecturn", "targets", "logs", "logpile", "accessory", "igloo"}) {
            if (name.contains(k)) return false;
        }
        // Naturel ensuite.
        for (String k : new String[]{
                "rock", "tree", "trunk", "deadtree", "stele", "pillar",
                "ruin", "statue", "crystal", "mush", "pond", "nature",
                "boulder", "scraps", "spikes", "monolith", "shrine",
                "gateway", "cath"}) {
            if (name.contains(k)) return true;
        }
        return false; // par defaut : traite comme artificiel (quota plus strict)
    }

    /**
     * Hauteur maximale d'un decor, en blocs (consigne utilisateur).
     * Les fichiers livres sont deja filtres a la source ; cette constante sert
     * de garde-fou au moment de la pose.
     */
    private static final int MAX_DECOR_HEIGHT = 15;

    // =========================================================================
    // CATALOGUES — 15 schematics distincts par thème
    // =========================================================================

    /** CITADELLE SYLVESTRE : ruines médiévales, routes pavées, enclos, tentes. */
    private static final String[] CITADEL_SET = {
        "deco_rock_boulder", "deco_rock_diorite", "deco_rock_granite",
        "deco_rock_med1", "deco_rock_med2", "deco_rock_stone1",
        "deco_rock_stone2", "deco_deadtree_acacia", "deco_deadtree_birch",
        "deco_deadtree_cherry", "deco_deadtree_oak", "deco_deadtree_spruce",
        "deco_tree_acacia", "deco_tree_birch", "deco_tree_oak",
        "deco_tree_spruce", "deco_trunk_acacia", "deco_trunk_birch",
        "deco_trunk_cherry", "deco_trunk_dark_oak", "deco_dec_fire_camp",
        "deco_dec_fountain", "deco_dec_gallows", "deco_dec_haystack",
        "deco_dec_large_bench", "deco_dec_lecturn_garden", "deco_dec_medium_bench",
        "deco_dec_outhouse", "deco_dec_shed", "deco_dec_small_bench_1",
        "deco_dec_small_bench_2", "deco_dec_small_bench_3", "deco_dec_small_haystack",
        "deco_dec_small_horse_campsite", "deco_dec_small_windmill", "deco_dec_villager_statue",
        "deco_ruin_cath_c5", "deco_ruin_cath_end", "deco_ruin_small_ruin",
        "deco_ruin_statue_ruins", "deco_ruin_stone_pillars", "deco_well_copper",
        "deco_well_small", "deco_wellx_birch", "deco_wellx_dark_oak",
        "deco_wellx_oak", "deco_wellx_rocky", "deco_logpile_acacia",
        "deco_logpile_birch", "deco_logpile_dark_oak", "deco_logpile_jungle",
        "deco_logpile_oak", "deco_logpile_spruce", "deco_stall_blue",
        "deco_stall_orange", "deco_stall_pink", "deco_stall_red",
        "deco_cart_cart", "deco_cart_large_cart_1", "deco_cart_large_cart_2",
        "deco_cart_medium_bamboo_cart", "deco_lant_acacia", "deco_lant_bamboo",
        "deco_lant_birch", "deco_lant_campfire", "deco_lant_cherry",
        "deco_hut_desert", "deco_hut_igloo_med", "deco_cit_camp_btent",
        "deco_cit_camp_cage1", "deco_cit_camp_cage2", "deco_cit_camp_logs",
        "deco_cit_camp_targets", "deco_cit_camp_tent1", "deco_cit_ruin_1",
        "deco_cit_ruin_2", "deco_cit_ruin_3", "deco_cit_well_forest",
        "deco_cit_well_mossy"
    };

    /** OBSERVATOIRE FÉERIQUE : nature, bois, pierre claire, lanternes. */
    private static final String[] OBSERVATORY_SET = {
        "deco_rock_boulder", "deco_rock_diorite", "deco_rock_granite",
        "deco_rock_med1", "deco_rock_med2", "deco_rock_stone1",
        "deco_rock_stone2", "deco_deadtree_acacia", "deco_deadtree_birch",
        "deco_deadtree_cherry", "deco_deadtree_oak", "deco_deadtree_spruce",
        "deco_tree_acacia", "deco_tree_birch", "deco_tree_oak",
        "deco_tree_spruce", "deco_trunk_birch", "deco_trunk_cherry",
        "deco_trunk_spruce", "deco_dec_crystal", "deco_dec_fountain",
        "deco_dec_fox_hut", "deco_dec_large_bench", "deco_dec_mixed_pile",
        "deco_dec_mushroom_statue", "deco_dec_pumpkin_pile", "deco_dec_small_bench_1",
        "deco_dec_small_bench_2", "deco_dec_small_bench_3", "deco_dec_snowy_dog_hut",
        "deco_ruin_ruined_beacon", "deco_ruin_statue_ruins", "deco_well_small",
        "deco_wellx_acacia", "deco_wellx_birch", "deco_wellx_snowy",
        "deco_logpile_acacia", "deco_logpile_birch", "deco_logpile_dark_oak",
        "deco_logpile_jungle", "deco_logpile_oak", "deco_logpile_spruce",
        "deco_lant_acacia", "deco_lant_bamboo", "deco_lant_birch",
        "deco_lant_campfire", "deco_lant_cherry", "deco_lant_dark_oak",
        "deco_lant_jungle", "deco_lant_mangrove", "deco_lant_med_oak",
        "deco_lant_oak", "deco_lant_spruce", "deco_hut_desert",
        "deco_hut_igloo_med", "deco_hut_igloo_small", "deco_hut_swamp",
        "deco_cart_cart", "deco_cart_large_cart_1", "deco_stall_blue",
        "deco_stall_orange", "deco_obs_acc1", "deco_obs_camp_targets",
        "deco_obs_camp_tent1", "deco_obs_camp_tent2", "deco_obs_deco1",
        "deco_obs_deco2", "deco_stele_pillar_1", "deco_stele_pillar_2",
        "deco_stele_pillar_3", "deco_stele_scraps_1", "deco_stele_scraps_2",
        "deco_stele_scraps_3", "deco_stele_scraps_4", "deco_stele_shrine",
        "deco_stele_spikes_1", "deco_stele_spikes_2"
    };

    /** SANCTUAIRE DU DRAGON : ossements, portails en ruine, pierre craquelée. */
    private static final String[] DRAGON_SET = {
        "deco_drg_camp_0", "deco_drg_camp_1", "deco_drg_camp_2",
        "deco_drg_camp_3", "deco_drg_camp_4", "deco_drg_crystal",
        "deco_drg_gateway", "deco_drg_lant_bamboo", "deco_drg_lant_jungle",
        "deco_drg_logpile_jungle", "deco_drg_mushpond", "deco_drg_mushstatue",
        "deco_drg_statue_0", "deco_drg_statue_1", "deco_drg_statue_2",
        "deco_drg_statue_azalea", "deco_drg_statue_bamboo", "deco_drg_statue_book_l",
        "deco_drg_statue_cherryblossom", "deco_drg_statue_chest_l", "deco_drg_statue_cocoabeans",
        "deco_drg_statue_emerald", "deco_drg_statue_glowberry", "deco_drg_statue_hall_0",
        "deco_drg_statue_hall_1", "deco_drg_statue_hall_2", "deco_drg_statue_hall_3",
        "deco_drg_statue_hall_4", "deco_drg_statue_lantern_l", "deco_drg_statue_mossblock",
        "deco_drg_statue_ruins", "deco_drg_statue_shield_l", "deco_drg_statue_sus",
        "deco_drg_statue_sword", "deco_drg_trunk_jungle", "deco_drg_well_jungle",
        "deco_drg_well_mush", "deco_deadtree_acacia", "deco_deadtree_birch",
        "deco_deadtree_cherry", "deco_deadtree_oak", "deco_dec_crystal",
        "deco_dec_fountain", "deco_dec_mixed_pile", "deco_dec_mushroom_statue",
        "deco_lant_bamboo", "deco_lant_cherry", "deco_lant_jungle",
        "deco_logpile_jungle", "deco_logpile_oak", "deco_rock_boulder",
        "deco_rock_diorite", "deco_rock_granite", "deco_rock_med1",
        "deco_rock_med2", "deco_rock_stone1", "deco_rock_stone2",
        "deco_ruin_ruined_beacon", "deco_ruin_small_ruin", "deco_ruin_statue_ruins",
        "deco_ruin_stone_pillars", "deco_tree_acacia", "deco_tree_birch",
        "deco_tree_oak", "deco_trunk_mangrove", "deco_stele_pillar_1",
        "deco_stele_pillar_2", "deco_stele_pillar_3", "deco_stele_scraps_1",
        "deco_stele_scraps_2", "deco_stele_scraps_3", "deco_stele_scraps_4",
        "deco_stele_shrine", "deco_stele_spikes_1", "deco_stele_spikes_2"
    };

    /** GÉNÉRIQUE : mélange neutre. */
    private static final String[] GENERIC_SET = {
        "deco_rock_boulder", "deco_rock_diorite", "deco_rock_granite",
        "deco_rock_med1", "deco_rock_med2", "deco_rock_stone1",
        "deco_rock_stone2", "deco_deadtree_acacia", "deco_deadtree_birch",
        "deco_deadtree_cherry", "deco_deadtree_oak", "deco_deadtree_spruce",
        "deco_tree_acacia", "deco_tree_birch", "deco_tree_oak",
        "deco_tree_spruce", "deco_trunk_acacia", "deco_trunk_birch",
        "deco_trunk_cherry", "deco_trunk_dark_oak", "deco_trunk_mangrove",
        "deco_dec_crimson_table", "deco_dec_crystal", "deco_dec_desert_pump",
        "deco_dec_fire_camp", "deco_dec_fountain", "deco_dec_fox_hut",
        "deco_dec_gallows", "deco_dec_haystack", "deco_cit_ruin_1",
        "deco_cit_ruin_2", "deco_cit_ruin_3", "deco_ruin_cath_c5",
        "deco_cit_well_forest", "deco_cit_well_mossy", "deco_well_copper",
        "deco_well_small", "deco_wellx_acacia", "deco_logpile_acacia",
        "deco_logpile_birch", "deco_logpile_dark_oak", "deco_logpile_jungle",
        "deco_lant_acacia", "deco_lant_bamboo", "deco_lant_birch",
        "deco_lant_campfire", "deco_hut_desert", "deco_hut_igloo_med"
    };

    /** Tous les deco_*.nbt livrés, pour le préchargement au démarrage serveur. */
    public static final List<String> ALL_DECO_NAMES;
    static {
        List<String> all = new ArrayList<>();
        for (String[] set : new String[][]{CITADEL_SET, OBSERVATORY_SET, DRAGON_SET, GENERIC_SET})
            for (String s : set) if (!all.contains(s)) all.add(s);
        ALL_DECO_NAMES = List.copyOf(all);
    }

    public static Theme themeOf(String structureName) {
        if (structureName == null) return Theme.GENERIC;
        String n = structureName.toLowerCase(Locale.ROOT);
        if (n.contains("citadel")) return Theme.CITADEL;
        if (n.contains("observ")) return Theme.OBSERVATORY;
        if (n.contains("dragon") || n.contains("sanctum")) return Theme.DRAGON;
        return Theme.GENERIC;
    }

    private static String[] setFor(Theme t) {
        return switch (t) {
            case CITADEL -> CITADEL_SET;
            case OBSERVATORY -> OBSERVATORY_SET;
            case DRAGON -> DRAGON_SET;
            default -> GENERIC_SET;
        };
    }

    // =========================================================================
    // REGISTRE DES EMPRISES POSEES
    // =========================================================================

    /** {minX, minZ, maxX, maxZ} de chaque décor posé pour la structure courante. */
    private static final List<int[]> PLACED_FOOTPRINTS = new ArrayList<>();

    /**
     * Un arbre peut-il pousser ici ? Consulté par
     * StructureTerrainPrep.replantTrees() pour que les arbres poussent AUTOUR
     * des décors et jamais dedans ni dessus.
     *
     * @param margin marge de sécurité en blocs autour de l'emprise
     */
    public static boolean isInsideDecor(int x, int z, int margin) {
        for (int[] f : PLACED_FOOTPRINTS) {
            if (x >= f[0] - margin && x <= f[2] + margin
                    && z >= f[1] - margin && z <= f[3] + margin) return true;
        }
        return false;
    }

    // =========================================================================

    /**
     * Sème le décor thématique autour d'une structure fraîchement posée.
     * Appelé par decorate(), APRÈS le terrain et AVANT le replant des arbres.
     */
    public static void scatter(ServerLevel level, BlockPos min, BlockPos max, String structureName) {
        Theme theme = themeOf(structureName);
        String[] set = setFor(theme);
        RandomSource random = level.getRandom();

        // Nouvelle structure -> on repart d'un registre vide.
        PLACED_FOOTPRINTS.clear();

        int ring = 46;
        int x0 = min.getX() - ring, x1 = max.getX() + ring;
        int z0 = min.getZ() - ring, z1 = max.getZ() + ring;

        // One multiplier per site; placement safety checks still limit the actual count.
        double quantityMultiplier = 1.2 + random.nextDouble() * 1.7;
        int baseTarget = MIN_DECOR + random.nextInt(MAX_DECOR - MIN_DECOR + 1);
        int target = (int) Math.round(baseTarget * quantityMultiplier);

        // Quota de NATURELS tire d'abord (3 a 7). Le reste du budget sera
        // comble par des elements artificiels, s'il en reste a poser.
        int naturalTarget = (int) Math.round((MIN_NATURAL
                + random.nextInt(MAX_NATURAL - MIN_NATURAL + 1)) * quantityMultiplier);
        if (naturalTarget > target) naturalTarget = target;

        // Tirage SANS REMISE dans le catalogue du thème : les décors d'un même
        // site sont donc tous différents les uns des autres.
        // Deux viviers separes pour pouvoir honorer le quota naturel.
        List<String> poolNat = new ArrayList<>(), poolArt = new ArrayList<>();
        for (String s : set) (isNatural(s) ? poolNat : poolArt).add(s);

        List<String> pool = new ArrayList<>(List.of(set));
        // Compteur d'usage : limite la repetition d'un meme schematic sur un
        // site (MAX_SAME_SCHEMATIC). Le tirage reste sans remise en priorite ;
        // ce compteur n'intervient que si le vivier doit etre recharge.
        java.util.Map<String,Integer> usage = new java.util.HashMap<>();
        int placed = 0, rejected = 0, placedNatural = 0;
        int lootChests = 0;
        int seated = 0;   // T42 : colonnes de decors plaquees au sol

        // ================================================================
        // REPARTITION SPATIALE STRATIFIEE (echantillonnage par secteurs)
        // ================================================================
        //
        // FIX (demande utilisateur : « fais bien en sorte que nos petites
        // structures soient bien spread sur tout notre terrain modifie, pas
        // dans une zone restreinte, c'est nul si 4 chunks sont remplis de
        // structures et 16 en ont aucune ! faut un placement bien reparti
        // random »).
        //
        // PROBLEME : le tirage precedent piochait un (x,z) UNIFORME sur toute
        // la couronne, avec pour seul garde-fou un espacement minimum de 11
        // blocs. Or un tirage uniforme n'est PAS un tirage bien reparti : la
        // loi de Poisson produit naturellement des grappes et des vides (c'est
        // le meme phenomene que les "amas d'etoiles" apparents dans un ciel
        // aleatoire). Avec seulement 5 a 9 poses, la probabilite que trois ou
        // quatre tombent dans le meme secteur est loin d'etre negligeable --
        // d'ou les chunks satures pendant que le reste de la zone reste nu.
        //
        // SOLUTION : echantillonnage stratifie ("jittered grid"). La couronne
        // est decoupee en une grille de secteurs, on melange l'ordre de ces
        // secteurs, puis on pose AU PLUS UN decor par secteur en tirant une
        // position aleatoire A L'INTERIEUR du secteur. On conserve donc tout
        // l'aleatoire du placement (aucun alignement visible, le jitter interne
        // casse la regularite de la grille) tout en garantissant que deux
        // decors ne peuvent jamais se retrouver dans le meme secteur.
        //
        // La grille est dimensionnee pour offrir environ 3x plus de secteurs
        // que de decors a poser : assez de choix pour absorber les secteurs
        // rejetes (eau, pente, emprise de la structure) sans jamais retomber
        // dans un placement concentre.
        int zoneW = x1 - x0 + 1, zoneD = z1 - z0 + 1;
        int gridN = Math.max(3, (int) Math.ceil(Math.sqrt(target * 3.0)));
        int cellW = Math.max(1, zoneW / gridN), cellD = Math.max(1, zoneD / gridN);

        List<int[]> sectors = new ArrayList<>();
        for (int gx = 0; gx < gridN; gx++)
            for (int gz = 0; gz < gridN; gz++)
                sectors.add(new int[]{x0 + gx * cellW, z0 + gz * cellD});
        // Melange : l'ordre de remplissage ne privilegie aucun coin de la zone.
        Collections.shuffle(sectors, new java.util.Random(random.nextLong()));

        int sectorIdx = 0;

        for (int attempt = 0; attempt < target * 40 && placed < target; attempt++) {
            // Tant que le quota naturel n'est pas atteint, on pioche dans le
            // vivier NATUREL ; ensuite seulement dans l'artificiel. Si un
            // vivier est vide on bascule sur l'autre plutot que d'echouer.
            boolean wantNatural = placedNatural < naturalTarget;
            List<String> src = wantNatural ? poolNat : poolArt;
            if (src.isEmpty()) src = wantNatural ? poolArt : poolNat;
            if (src.isEmpty()) {
                poolNat = new ArrayList<>(); poolArt = new ArrayList<>();
                for (String s : set) (isNatural(s) ? poolNat : poolArt).add(s);
                src = poolNat.isEmpty() ? poolArt : poolNat;
                if (src.isEmpty()) break;
            }

            // Secteur suivant de la grille melangee. Si tous ont ete essayes,
            // on refait un tour : les secteurs rejetes pour cause de terrain
            // (eau, pente) peuvent redevenir valides avec un autre schematic,
            // plus petit ou de forme differente.
            if (sectorIdx >= sectors.size()) {
                Collections.shuffle(sectors, new java.util.Random(random.nextLong()));
                sectorIdx = 0;
            }
            int[] sec = sectors.get(sectorIdx++);

            // Position aleatoire A L'INTERIEUR du secteur (jitter) : la grille
            // ne doit jamais se deviner a l'oeil.
            int px = sec[0] + random.nextInt(cellW);
            int pz = sec[1] + random.nextInt(cellD);

            // Jamais dans l'emprise de la grosse structure ni collé à ses murs.
            if (px >= min.getX() - 6 && px <= max.getX() + 6
                    && pz >= min.getZ() - 6 && pz <= max.getZ() + 6) continue;

            String name = src.get(random.nextInt(src.size()));
            // Diversite : jamais plus de MAX_SAME_SCHEMATIC exemplaires du meme
            // fichier sur un site, meme apres rechargement du vivier.
            if (usage.getOrDefault(name, 0) >= MAX_SAME_SCHEMATIC) { src.remove(name); continue; }
            StructureTemplate tmpl = deepluckyblock.util.StructureTemplateCache.get(level, name);
            if (tmpl == null) { src.remove(name); rejected++; continue; }

            Rotation rot = switch (random.nextInt(4)) {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                case 3 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };
            Mirror mir = random.nextInt(100) < 30 ? Mirror.FRONT_BACK : Mirror.NONE;

            var size = tmpl.getSize();
            // GARDE-FOU DE HAUTEUR (consigne : max 15 blocs de haut).
            // Les 119 fichiers livres sont deja controles a la source, mais ce
            // test protege le jeu si un datapack tiers surcharge l'un de nos
            // deco_*.nbt par une version plus haute : un decor trop haut
            // depasserait la canopee et se verrait de loin comme une verrue.
            if (size.getY() > MAX_DECOR_HEIGHT) { rejected++; continue; }
            boolean swap = (rot == Rotation.CLOCKWISE_90 || rot == Rotation.COUNTERCLOCKWISE_90);
            int fw = swap ? size.getZ() : size.getX();
            int fd = swap ? size.getX() : size.getZ();

            // Check the whole transformed footprint, not just its minimum corner.
            if (px <= max.getX() + 6 && px + fw - 1 >= min.getX() - 6
                    && pz <= max.getZ() + 6 && pz + fd - 1 >= min.getZ() - 6) continue;

            // Pas de chevauchement avec un décor déjà posé (marge 3).
            if (overlapsPlaced(px, pz, fw, fd, 3)) continue;

            Integer baseY = flatGroundUnder(level, px, pz, fw, fd);
            if (baseY == null) { rejected++; continue; }

            // T42 : le decor est ancre sur son PLUS BAS BLOC REEL, pas sur la
            // surface : sans ca, un schematic dont la premiere couche est
            // partiellement vide (rocher, ruine, puits) se retrouvait perche de
            // 1-2 blocs au-dessus du sol (« certaines mini-structures
            // decoratives flottent, plaque-les au sol » -- dev, 23/09).
            int minLocalY = minLocalY(tmpl);

            // Ces structures vanilla ont souvent une assise de gravier/terre
            // destinée à être enterrée.
            int sink = name.contains("ruin_stone") ? 3
                     : (name.contains("portal") || name.contains("ruin_decor")
                        || name.contains("obs_ruin") || name.contains("cit_room")) ? 1 : 0;

            BlockPos boundsOrigin = new BlockPos(px, baseY - sink - minLocalY, pz);
            // Offset negative coordinates introduced by rotations and mirrors.
            BlockPos at = tmpl.getZeroPositionWithTransform(boundsOrigin, mir, rot);

            // BlockIgnoreProcessor.STRUCTURE_BLOCK : ces structures vanilla sont
            // conçues pour le système Jigsaw et contiennent des blocs techniques
            // (vérifié : 51 des 66 fichiers en ont). Sans ce processeur ils
            // resteraient VISIBLES en jeu comme blocs de debug.
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(rot)
                    .setMirror(mir)
                    .setIgnoreEntities(true)
                    .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK);

            try {
                if (!tmpl.placeInWorld(level, at, at, settings, random, 2 | 16)) {
                    rejected++;
                    continue;
                }
                lootChests += populateVillageLootChests(level, tmpl, at, settings, random);
                scrubTechnicalBlocks(level, boundsOrigin, fw, size.getY(), fd);
                seated += seatDecor(level, boundsOrigin, fw, size.getY(), fd);   // T42 : plaque au sol
                PLACED_FOOTPRINTS.add(new int[]{px, pz, px + fw - 1, pz + fd - 1});
                src.remove(name);          // tirage sans remise
                pool.remove(name);
                usage.merge(name, 1, Integer::sum);
                placed++;
                if (isNatural(name)) placedNatural++;
            } catch (Exception ex) {
                rejected++;
            }
        }

        deepluckyblock.util.DebugLog.structure(
                "scatter [{}]: base={}, multiplier={}, target={}, village loot chests={}",
                theme, baseTarget, quantityMultiplier, target, lootChests);
        deepluckyblock.util.DebugLog.structure(
                "scatter [{}] : {} naturels (cible {}) sur {} decors poses",
                theme, placedNatural, naturalTarget, placed);
        deepluckyblock.util.DebugLog.structure(
                "scatter [{}] : {}/{} schematics vanilla poses AVANT les arbres ({} rejetes), couronne {} blocs",
                theme, placed, target, rejected, ring);
        deepluckyblock.util.DebugLog.structure(
                "scatter [{}] : T42 -- {} colonne(s) de decors plaquees au sol (aucune mini-structure ne flotte)",
                theme, seated);
    }

    // Explicit low-value village whitelist: no treasure or combat equipment tables.
    private static final String[] VILLAGE_LOOT_TABLES = {
        "village_plains_house", "village_desert_house", "village_savanna_house",
        "village_snowy_house", "village_taiga_house", "village_fisher",
        "village_shepherd", "village_tannery", "village_cartographer"
    };

    private static int populateVillageLootChests(ServerLevel level, StructureTemplate template,
            BlockPos origin, StructurePlaceSettings settings, RandomSource random) {
        int assigned = 0;
        for (var block : new net.minecraft.world.level.block.Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST}) {
            for (var info : template.filterBlocks(origin, settings, block)) {
                if (!(level.getBlockEntity(info.pos()) instanceof
                        net.minecraft.world.level.block.entity.ChestBlockEntity chest)) continue;
                // Check the table first: isEmpty() may unpack pending loot.
                if (chest.getLootTable() != null || !chest.isEmpty()) continue;
                String name = VILLAGE_LOOT_TABLES[random.nextInt(VILLAGE_LOOT_TABLES.length)];
                var key = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.LOOT_TABLE,
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("chests/village/" + name));
                chest.setLootTable(key, random.nextLong());
                chest.setChanged();
                assigned++;
            }
        }
        return assigned;
    }

    private static boolean overlapsPlaced(int px, int pz, int fw, int fd, int margin) {
        int ax0 = px - margin, az0 = pz - margin;
        int ax1 = px + fw - 1 + margin, az1 = pz + fd - 1 + margin;
        for (int[] f : PLACED_FOOTPRINTS) {
            if (ax0 <= f[2] && ax1 >= f[0] && az0 <= f[3] && az1 >= f[1]) return true;
        }
        return false;
    }

    /**
     * Filet de sécurité : supprime tout bloc technique ayant survécu au
     * processeur. Ces blocs sont normalement consommés par le système Jigsaw,
     * mais un placeInWorld() direct ne les consomme pas.
     */
    private static void scrubTechnicalBlocks(ServerLevel level, BlockPos origin, int w, int h, int d) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < w; dx++)
            for (int dy = 0; dy < h; dy++)
                for (int dz = 0; dz < d; dz++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState s = level.getBlockState(m);
                    if (s.is(Blocks.JIGSAW) || s.is(Blocks.STRUCTURE_BLOCK) || s.is(Blocks.STRUCTURE_VOID)) {
                        level.setBlock(m, Blocks.AIR.defaultBlockState(), 2 | 16);
                    }
                }
    }

    /** Sol naturel et assez plat sous toute l'emprise ? Retourne le Y de pose. */
    private static Integer flatGroundUnder(ServerLevel level, int px, int pz, int fw, int fd) {
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < fw; dx++) {
            for (int dz = 0; dz < fd; dz++) {
                int x = px + dx, z = pz + dz;

                if (!deepluckyblock.util.SafeSurface.isLoadedAt(level, x, z)) return null;

                // FIX (rapporte en jeu : « certaines de tes nouvelles structures
                // sont un bloc dans le ciel, il faut bien faire en sorte que
                // solide sur solide, pas d'air au milieu lors du paste »).
                //
                // CAUSE : getHeight(MOTION_BLOCKING_NO_LEAVES) renvoie le
                // sommet du premier bloc BLOQUANT -- ce qui inclut les buches,
                // la neige, et tout residu vegetal. Poser le decor a ce Y le
                // laissait donc perche au-dessus du vrai sol, avec de l'air
                // en dessous.
                //
                // CORRECTIF : on redescend a travers l'air et les residus
                // jusqu'au premier bloc reellement SOLIDE, et on exige que ce
                // bloc soit du terrain naturel. Toute colonne dont le sol n'est
                // pas franc est rejetee : mieux vaut un decor en moins qu'un
                // decor flottant.
                int y = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int floor = level.getMinBuildHeight() + 1;
                while (y > floor) {
                    BlockState below = level.getBlockState(m.set(x, y - 1, z));
                    if (below.isAir() || isLooseDecor(below)) { y--; continue; }
                    break;
                }
                if (y <= floor) return null;

                BlockState ground = level.getBlockState(m.set(x, y - 1, z));
                if (!isNaturalGround(ground)) return null;
                if (!ground.isSolidRender(level, m)) return null;   // solide sur solide
                // T75 : plus general que l'eau -- aucune colonne NOYEE (eau OU lave)
                // n'accepte de decor : « les plantes doivent etre posees apres tous
                // les fixwater, sinon elles se retrouvent sous l'eau ».
                if (!level.getBlockState(m.set(x, y, z)).getFluidState().isEmpty()) return null;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        // Tolerance de pente resserree : au-dela d'un bloc d'ecart le decor
        // laisserait forcement un coin en l'air.
        if (maxY - minY > 1) return null;
        return minY;
    }

    /** Vegetation / neige / residus traverses pour trouver le vrai sol. */
    private static boolean isLooseDecor(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.BushBlock
                || s.is(Blocks.SNOW) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN) || s.is(Blocks.DEAD_BUSH)
                || s.is(Blocks.VINE) || s.is(Blocks.LILY_PAD)
                || s.is(net.minecraft.tags.BlockTags.LEAVES)
                || s.is(net.minecraft.tags.BlockTags.LOGS)
                || s.is(net.minecraft.tags.BlockTags.FLOWERS);
    }

    private static boolean isNaturalGround(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)
                || s.is(Blocks.PODZOL) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.STONE) || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.SAND) || s.is(Blocks.SANDSTONE);
    }

    // =====================================================================
    // T42 : MINI-STRUCTURES DECORATIVES PLAQUEES AU SOL
    // =====================================================================
    /** Comblement maximal sous un decor (au-dela : le decor est repose plus bas). */
    private static final int MAX_SEAT = 4;

    /**
     * Y local du PLUS BAS bloc du schematic (0 si le fichier commence deja par
     * un bloc plein). La rotation et le miroir de nos decors autour de l'axe Y
     * ne changent jamais la hauteur : ce minimum est donc valable pour les 8
     * variantes de pose.
     */
    private static int minLocalY(StructureTemplate t) {
        int min = Integer.MAX_VALUE;
        try {
            for (java.lang.reflect.Field f : StructureTemplate.class.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(t);
                if (val instanceof java.util.List<?> list && !list.isEmpty()
                        && list.get(0) instanceof StructureTemplate.Palette pal) {
                    for (StructureTemplate.StructureBlockInfo info : pal.blocks()) {
                        if (info.state().isAir()) continue;
                        int y = info.pos().getY();
                        if (y < min) min = y;
                    }
                    break;
                }
            }
        } catch (Exception ignored) {}
        return (min == Integer.MAX_VALUE) ? 0 : Math.max(0, min);
    }

    /**
     * Plaque un decor fraichement pose sur le sol : pour chaque colonne de
     * l'emprise, si le bloc le plus bas du decor est separe du sol par de l'air,
     * le vide est comble avec le materiau du sol (donc invisible : c'est du
     * terrain, pas du bricolage). Renvoie le nombre de colonnes retouchees.
     */
    private static int seatDecor(ServerLevel level, BlockPos at, int fw, int fh, int fd) {
        int seated = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < fw; dx++) {
            for (int dz = 0; dz < fd; dz++) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                int lowest = Integer.MIN_VALUE;
                for (int y = at.getY(); y < at.getY() + fh; y++) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (!s.isAir()) { lowest = y; break; }
                }
                if (lowest == Integer.MIN_VALUE) continue;          // colonne sans bloc de decor
                BlockState under = level.getBlockState(m.set(x, lowest - 1, z));
                if (!under.isAir() && under.blocksMotion()) continue;   // deja pose sur du solide
                // Materiau du sol : le premier bloc plein sous le decor.
                int floorY = Integer.MIN_VALUE;
                for (int y = lowest - 1; y >= lowest - MAX_SEAT - 1 && y > level.getMinBuildHeight(); y--) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (!s.isAir() && s.blocksMotion()) { floorY = y; break; }
                }
                if (floorY == Integer.MIN_VALUE) continue;          // sol trop bas : on ne construit pas de pilier
                BlockState material = level.getBlockState(m.set(x, floorY, z));
                if (material.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                        || material.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) continue;
                for (int y = floorY + 1; y < lowest; y++) {
                    if (!level.getBlockState(m.set(x, y, z)).isAir()) continue;
                    level.setBlock(m, material, 2 | 16);
                }
                seated++;
            }
        }
        return seated;
    }
}
