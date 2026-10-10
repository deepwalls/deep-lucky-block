package deepluckyblock.procedures;

import deepluckyblock.advancements.DeepLuckyBlockAdvancements;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.*;

import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = "deep_lucky_block")
public class Structures5Procedure {

    private static final Logger LOGGER = LogUtils.getLogger();
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
    // Distance minimum absolue (en blocs) entre le build et le joueur. Aucune
    // structure ne spawn plus pres, quelle que soit sa taille. Modifiable ici en
    // haut pour eviter les problemes de terrain (build qui ecrase le joueur ou qui
    // modifie le terrain sous ses pieds).
    // T143 : 128 supprimait tous les candidats charges puis activait le fallback
    // aveugle (run ocean du 01/10). La securite reelle est validee sur l'emprise
    // complete juste avant toute edition, pas en eloignant artificiellement le centre.
    public static int MIN_PLAYER_DISTANCE = 65;
    private static final int BLOCKS_PER_TICK = 25000;
    /**
     * T33/T34 : budget de TEMPS par tick du paste. Un quota de blocs ne borne
     * rien en temps reel : 25 000 ecritures peuvent couter plus d'une seconde
     * selon la machine et la distance de vue (constat en jeu : gel pendant le
     * paste de l'everest). C'est la mesure en millisecondes qui garantit qu'un
     * tick ne s'eternise jamais.
     */
    private static final long PASTE_TICK_BUDGET_MS = 40;
    private static final int FLAT_SIZE = 10;
    private static final int MAX_HDIFF = 3;
    // Ecart (en blocs) au-dela duquel l'ecart de sol avant/apres smooth est
    // considere comme ANORMAL et logge en WARN (T7). Ce n'est plus un bornage :
    // la structure suit desormais le sol reel dans les deux sens (voir le
    // commentaire du re-ancrage dans doPaste), parce que les deux mesures sont
    // desormais fiables (SafeSurface). Un ecart superieur a cette valeur signale
    // qu'une hauteur a encore menti, pas une intention de placement.
    private static final int MAX_POST_SMOOTH_DELTA_Y = 4;
    private static final int SEARCH_R = 80;
    // FIX (freeze silencieux au paste, voir Javadoc dans findFlat) : budget de
    // temps maximum pour la verification "chunks reels" des candidats de zone
    // plate. Au-dela, on arrete d'essayer de nouveaux candidats et on retombe
    // sur le meilleur deja verifie / le fallback pente minimale.
    // T180 : 3000 ms PAR rayon produisait des recherches cumulées de plusieurs
    // minutes (jusqu'à rayon 2496 dans le run du 03/10). La recherche ne doit
    // jamais monopoliser un tick : seuls les candidats dont tous les chunks sont
    // déjà FULL sont examinés, avec une tranche très courte.
    private static final long FIND_FLAT_TIME_BUDGET_MS = 100;

    /**
     * Force la generation des chunks d'une zone, en respectant le budget de
     * temps de la recherche de terrain plat.
     *
     * FIX (rapporte en jeu : "parfois tu generes une structure trop loin et
     * n'arrive pas a charger les chunks a temps, ce qui produit un freeze
     * infini").
     *
     * CAUSE : la verification des candidats forcait la generation de la zone
     * de chaque candidat par une double boucle SYNCHRONE et SANS GARDE. Avec
     * checkR=52 (valeur reelle constatee dans dernierslogs6) cela represente
     * ~64 chunks par candidat, et jusqu'a 12 candidats sont testes, soit
     * potentiellement 768 generations completes de chunk en un seul tick.
     * Le budget FIND_FLAT_TIME_BUDGET_MS existait deja mais n'etait teste
     * QU'ENTRE deux candidats : une fois la boucle interne lancee, plus rien
     * ne pouvait l'arreter. Loin du joueur (terrain jamais visite), tous ces
     * chunks doivent etre generes de zero -> le tick dure des dizaines de
     * secondes et le watchdog du serveur finit par tuer la partie, ce que le
     * joueur percoit comme un freeze infini.
     *
     * CORRECTIF : le budget est desormais reevalue A CHAQUE CHUNK. Des qu'il
     * est depasse, on s'arrete et on retourne false ; l'appelant abandonne
     * alors ce candidat au lieu d'insister. La recherche se rabat sur le
     * meilleur candidat deja mesure -- comportement de repli qui existait
     * deja pour le cas "pas assez plat".
     *
     * @return true si toute la zone a ete chargee, false si le budget a expire
     */
    private static boolean forceLoadZoneBudgeted(ServerLevel lvl, int ccx0, int ccz0,
                                                 int ccx1, int ccz1, long startMs) {
        // T49 : NE BLOQUE PLUS. La version precedente forcait la generation
        // SYNCHRONE de chaque chunk de la zone (jusqu'a ~300 chunks par candidat,
        // x24 candidats sur un monde neuf) : c'etait le plus gros consommateur de
        // temps du mod, et le budget de temps en ms ne pouvait rien y faire
        // puisqu'un appel bloquant ne s'interrompt pas.
        // Desormais : demande en tache de fond, puis on dit si la zone est DEJA
        // entierement en memoire (l'appelant differera le candidat sinon).
        // Candidate verification is read-only; preload only the selected footprint.
        return deepluckyblock.util.SafeSurface.zoneLoaded(lvl, ccx0, ccz0, ccx1, ccz1);
    }
    private static final int SEARCH_S = 5;
    private static final double VIEW_CONE = Math.PI / 2.0;
    private static final int SAFETY_CHECK_RADIUS = 6;
    private static final int SAFETY_CHECK_DEPTH = 2;
    private static final double WATER_MAX_RATIO = 0.15;
    /**
     * T75 : part maximale de colonnes LIQUIDES toleree pour qu'une zone plate soit
     * retenue. Un plan d'eau est le terrain le plus « plat » du monde : sans ce
     * filtre il gagne systematiquement le scoring (constat en jeu : candidat note
     * « flat=98 % » puis « [STRUCT5-SAFETY] eau=100 % » dans la milliseconde qui
     * suit, structure finalement posee sur/au-dessus de l'eau).
     */
    // T161 : l'eau au-dessus d'un sol réel est autorisée; Y0 remonte ensuite la
    // plateforme strictement au-dessus des sources voisines.
    // T168 : une structure terrestre ne doit jamais être sélectionnée dans un
    // océan/lac. L'eau reste lisible par le détecteur, mais le candidat est rejeté.
    private static final double FLAT_MAX_WET = 0.00;
    private static final double LAVA_MAX_RATIO = 0.05;
    private static final int MAX_CLIFF_HEIGHT_DIFF = 10;
    private static final int ALT_SEARCH_RADIUS = 60;
    private static final int ALT_SEARCH_STEP = 10;
    private static final int CARVE_COLS_PER_TICK = 600;
    private static final int CARVE_CANOPY = 8;
    private static final int RING_PER_TICK = 6000;
    private static final int GROUND_MAX_DEPTH = 64;
    private static final int GROUND_COLS_PER_TICK = 200;

    // ==================== EVEREST POST-PROCESSING ====================
    // 1) Ne garder que quelques coffres dans l'Everest (structure de Laink) :
    //    les coffres inclus dans la structure sont retires, sauf un nombre
    //    ALÉATOIRE entre EVEREST_CHEST_MIN et EVEREST_CHEST_MAX, tiré parmi
    //    TOUS les coffres (jamais les "plus riches").
    private static final int EVEREST_CHEST_MIN = 2;
    private static final int EVEREST_CHEST_MAX = 3;
    private static final int EVEREST_CHEST_KEEP = EVEREST_CHEST_MAX; // max conserve (compat)
    // 2) Spawn de mobs sur la SURFACE de la montagne (pas dedans, elle est creuse).
    private static final int EVEREST_MOB_MIN = 10;
    private static final int EVEREST_MOB_MAX = 20;
    // Bande (en blocs) au sommet de laquelle on ne spawn JAMAIS : les PNJ y vivent.
    private static final int EVEREST_TOP_MARGIN = 8;
    // Pas d'echantillonnage de la surface (perf sur une grosse structure).
    private static final int EVEREST_SURFACE_STEP = 4;
    private static final EntityType<?>[] EVEREST_MOB_TYPES = {
        EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER,
        EntityType.CREEPER, EntityType.HUSK, EntityType.STRAY
    };

    // Taille de l'anneau de terrain par structure.
    // 100 = taille normale, 40 = anneau reduit de 60 %, 120 = +20 %.
    public static int CITADEL_RING_SCALE_PERCENT = 100;
    public static int OBSERVATORY_RING_SCALE_PERCENT = 100;
    public static int DRAGON_RING_SCALE_PERCENT = 100;
    public static int CIRCUS_RING_SCALE_PERCENT = 100;
    public static int SHIP_RING_SCALE_PERCENT = 100;
    public static int EVEREST_RING_SCALE_PERCENT = 100;

    private static int ringScaleFor(String nbt) {
        return switch (nbt.toLowerCase(Locale.ROOT)) {
            case "citadel" -> CITADEL_RING_SCALE_PERCENT;
            case "observatory" -> OBSERVATORY_RING_SCALE_PERCENT;
            case "dragon" -> DRAGON_RING_SCALE_PERCENT;
            case "circus" -> CIRCUS_RING_SCALE_PERCENT;
            case "shipdead" -> SHIP_RING_SCALE_PERCENT;
            case "everest" -> EVEREST_RING_SCALE_PERCENT;
            default -> 100;
        };
    }
    // Tolerance : on ne rebouche (motte) QUE les colonnes dont lo est pres du
    // niveau de base reel (median des lo). Les colonnes de toit/auvent (lo tres
    // haut) sont IGNOREES, sinon on cree des piliers de dirt qui pendant des
    // bords du toit. Rien n'est jamais place au-dessus du dernier bloc structure.
    private static final int FLOOR_TOLERANCE = 4;
    private static final String CITADEL_NBT = "citadel";
    public static int CITADEL_OFFSET_X = 20;
    public static int CITADEL_OFFSET_Y = -35;
    public static int CITADEL_OFFSET_Z = 20;
    public static int CITADEL_DISTANCE = 0;
    public static Direction CITADEL_FACING = Direction.SOUTH;
    public static boolean CITADEL_FOLLOW_SURFACE = true;
    public static boolean CITADEL_USE_FLAT = true;
    public static boolean CITADEL_KEEP_INT = true;
    private static final String OBS_NBT = "observatory";
    public static int OBS_OFFSET_X = 0;
    public static int OBS_OFFSET_Y = -5;
    public static int OBS_OFFSET_Z = 0;
    public static int OBS_DISTANCE = 0;
    public static Direction OBS_FACING = Direction.NORTH;
    public static boolean OBS_FOLLOW_SURFACE = true;
    public static boolean OBS_USE_FLAT = true;
    public static boolean OBS_KEEP_INT = true;
    private static final String DRAGON_NBT = "dragon";
    public static int DRAGON_OFFSET_X = 10;
    public static int DRAGON_OFFSET_Y = -34;
    public static int DRAGON_OFFSET_Z = 10;
    public static int DRAGON_DISTANCE = 0;
    public static Direction DRAGON_FACING = Direction.WEST;
    public static boolean DRAGON_FOLLOW_SURFACE = true;
    public static boolean DRAGON_USE_FLAT = true;
    public static boolean DRAGON_KEEP_INT = true;
    private static final int MIN_CHUNKS_NORMAL = 4;
    private static final int MIN_CHUNKS_EVEREST = 6;

    private static long colKey(int x, int z) { return (Integer.toUnsignedLong(x) << 32) | Integer.toUnsignedLong(z); }
    private static int keyX(long k) { return (int) (k >>> 32); }
    private static int keyZ(long k) { return (int) k; }

    // Verre standard sans couleur uniquement (les command blocks sont deja filtres par isLaggy/isLaggyBlock).
    /** Supprime uniquement les blocs techniques LIGHT et les vignes vanilla
     * situes aux couches 1, 0 et negatives de la structure. Les LIGHT au-dessus
     * et les vrais blocs lumineux sont conserves. */
    private static boolean isTechnicalLightOrVine(BlockState state) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) return false;
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.equals("light") || id.toString().equalsIgnoreCase("minecraft:vine");
    }

    private static boolean isUndergroundTechnicalBlock(BlockState state, int relativeY) {
        if (relativeY > 1) return false;
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) return false;
        String fullId = id.toString().toLowerCase(Locale.ROOT);
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.equals("light") || fullId.equals("minecraft:vine");
    }

    private static boolean isGlassBlock(BlockState s) {
        return s.is(Blocks.GLASS);
    }

    private static boolean isEverestNbt(String nbt) { return "everest".equalsIgnoreCase(nbt); }

    private static boolean isChunkAreaLoaded(ServerLevel level, BlockPos center, int minChunks, Player nearest) {
        int cx = center.getX() >> 4, cz = center.getZ() >> 4, loaded = 0;
        int radius = (int) Math.ceil(Math.sqrt(minChunks));
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) != null) loaded++;
        return loaded >= minChunks;
    }

    private static BlockPos findFarthestLoadedPos(ServerLevel level, BlockPos origin, int dist, int minChunksReq) {
        Player pl = level.getNearestPlayer(origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5, 128, false);
        RandomSource r = level.getRandom();
        BlockPos best = null;
        int bestDistSq = -1, attempts = 0;
        while (attempts < 60) {
            double a = r.nextDouble() * Math.PI * 2;
            int dx = (int) Math.round(Math.cos(a) * dist), dz = (int) Math.round(Math.sin(a) * dist);
            int cx = origin.getX() + dx, cz = origin.getZ() + dz;
            // FIX T7 : hauteur reelle (voir SafeSurface) -- l'ancien getHeight()
            // pouvait renvoyer -65 et fausser la comparaison des candidats.
            int cy = deepluckyblock.util.SafeSurface.surfaceY(level, cx, cz, origin.getY());
            BlockPos candidate = new BlockPos(cx, cy, cz);
            // T80 : un point lointain au beau milieu d'un ocean ne doit jamais
            // devenir un ancrage -- le Y heightmap serait le FOND, l'eau de la zone
            // serait videe et la structure spawnee « au centre de l'ocean ».
            if (deepluckyblock.util.SafeSurface.columnWet(level, cx, cz)) { attempts++; continue; }
            if (isChunkAreaLoaded(level, candidate, minChunksReq, pl)) {
                int dSq = dx * dx + dz * dz;
                if (dSq > bestDistSq) { bestDistSq = dSq; best = candidate; }
            }
            attempts++;
        }
        return best;
    }

    private static boolean isLaggy(BlockState s) {
        return s.is(Blocks.WHITE_CARPET) || s.is(Blocks.ORANGE_CARPET) || s.is(Blocks.MAGENTA_CARPET)
            || s.is(Blocks.LIGHT_BLUE_CARPET) || s.is(Blocks.YELLOW_CARPET) || s.is(Blocks.LIME_CARPET)
            || s.is(Blocks.PINK_CARPET) || s.is(Blocks.GRAY_CARPET) || s.is(Blocks.LIGHT_GRAY_CARPET)
            || s.is(Blocks.CYAN_CARPET) || s.is(Blocks.PURPLE_CARPET) || s.is(Blocks.BLUE_CARPET)
            || s.is(Blocks.BROWN_CARPET) || s.is(Blocks.GREEN_CARPET) || s.is(Blocks.RED_CARPET)
            || s.is(Blocks.BLACK_CARPET) || s.is(Blocks.MOSS_CARPET)
            || s.is(Blocks.TRIPWIRE) || s.is(Blocks.COMMAND_BLOCK)
            || s.is(Blocks.REPEATING_COMMAND_BLOCK) || s.is(Blocks.CHAIN_COMMAND_BLOCK);
    }

    private static boolean isTreePart(BlockState s) { return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES); }
    private static boolean isNaturalTerrain(BlockState s) {
        if (isTreePart(s)) return true;
        return s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.STONE)
            || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.COARSE_DIRT)
            || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
            || s.is(Blocks.GRANITE) || s.is(Blocks.DIORITE) || s.is(Blocks.ANDESITE)
            || s.is(Blocks.DEEPSLATE) || s.is(Blocks.TUFF) || s.is(Blocks.CALCITE)
            || s.is(Blocks.CLAY) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.MUD)
            || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.ICE) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE)
            || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN)
            || s.is(Blocks.DEAD_BUSH) || s.is(Blocks.VINE)
            || s.is(Blocks.COAL_ORE) || s.is(Blocks.IRON_ORE) || s.is(Blocks.COPPER_ORE) || s.is(Blocks.GOLD_ORE)
            || s.is(Blocks.REDSTONE_ORE) || s.is(Blocks.LAPIS_ORE) || s.is(Blocks.DIAMOND_ORE) || s.is(Blocks.EMERALD_ORE)
            || s.is(Blocks.DEEPSLATE_COAL_ORE) || s.is(Blocks.DEEPSLATE_IRON_ORE) || s.is(Blocks.DEEPSLATE_COPPER_ORE)
            || s.is(Blocks.DEEPSLATE_GOLD_ORE) || s.is(Blocks.DEEPSLATE_REDSTONE_ORE) || s.is(Blocks.DEEPSLATE_LAPIS_ORE)
            || s.is(Blocks.DEEPSLATE_DIAMOND_ORE) || s.is(Blocks.DEEPSLATE_EMERALD_ORE)
            || s.is(Blocks.NETHER_QUARTZ_ORE) || s.is(Blocks.NETHER_GOLD_ORE);
    }

    private static void killLaggyItems(ServerLevel lvl, BlockPos min, BlockPos max) {
        AABB box = new AABB(min.getX() - 2, min.getY() - 2, min.getZ() - 2, max.getX() + 2, max.getY() + 2, max.getZ() + 2);
        int k = 0;
        for (ItemEntity ie : lvl.getEntitiesOfClass(ItemEntity.class, box)) {
            ItemStack s = ie.getItem();
            if (s.is(Items.STRING) || s.is(Items.SNOW) || s.is(Items.SNOWBALL) || s.is(Items.DIRT)
                || s.is(Items.SAND) || s.is(Items.RED_SAND) || s.is(Items.GRAVEL) || s.is(Items.COARSE_DIRT)
                || s.getItem().getDescriptionId().contains("carpet")) { ie.discard(); k++; }
        }
        if (k > 0) deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} items laggy nettoyes", k);
    }

    private static class ZoneAnalysis {
        double waterRatio, lavaRatio; int heightDiff;
        boolean hasTooMuchWater() { return waterRatio > WATER_MAX_RATIO; }
        boolean hasTooMuchLava() { return lavaRatio > LAVA_MAX_RATIO; }
        boolean hasCliff() { return heightDiff > MAX_CLIFF_HEIGHT_DIFF; }
        boolean isUnsafe() { return hasTooMuchWater() || hasTooMuchLava() || hasCliff(); }
        String getReason() {
            List<String> r = new ArrayList<>();
            if (hasTooMuchWater()) r.add("eau=" + (int)(waterRatio * 100) + "%");
            if (hasTooMuchLava()) r.add("lave=" + (int)(lavaRatio * 100) + "%");
            if (hasCliff()) r.add("falaise=" + heightDiff);
            return String.join(", ", r);
        }
    }

    private static ZoneAnalysis analyzeZone(ServerLevel level, BlockPos center) {
        ZoneAnalysis result = new ZoneAnalysis();
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
        int waterCount = 0, lavaCount = 0, totalFluid = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int dx = -SAFETY_CHECK_RADIUS; dx <= SAFETY_CHECK_RADIUS; dx += 3) {
            for (int dz = -SAFETY_CHECK_RADIUS; dz <= SAFETY_CHECK_RADIUS; dz += 3) {
                int px = center.getX() + dx, pz = center.getZ() + dz;
                // T75 : mesure sur la surface REELLE de la colonne (ni cime d'arbre, ni
                // air) -- la heightmap renvoyait la cime des arbres : elle masquait
                // l'eau situee en dessous ET gonflait la hauteur de 7 a 26 blocs,
                // ce qui faussait a la fois le ratio d'eau et la mesure de falaise.
                int surfY = deepluckyblock.util.SafeSurface.surfaceMaterial(level, px, pz);
                if (surfY == Integer.MIN_VALUE) continue;   // chunk pas en memoire : echantillon ignore
                if (surfY < minY) minY = surfY;
                if (surfY > maxY) maxY = surfY;
                for (int dy = 0; dy >= -SAFETY_CHECK_DEPTH; dy--) {
                    check.set(px, surfY + dy, pz);
                    // T56 : lecture NON BLOQUANTE (un getBlockState sur chunk absent generait
                    // le chunk en synchrone, ici pour chacun des ~8 000 candidats testes).
                    BlockState state = deepluckyblock.util.SafeSurface.state(level, px, surfY + dy, pz);
                    if (state == null) continue;   // chunk pas encore en memoire : echantillon ignore
                    totalFluid++;
                    if (state.is(Blocks.WATER) || (!state.getFluidState().isEmpty() && state.getFluidState().is(FluidTags.WATER))) waterCount++;
                    else if (state.is(Blocks.LAVA) || (!state.getFluidState().isEmpty() && state.getFluidState().is(FluidTags.LAVA))) lavaCount++;
                }
            }
        }
        if (minY > maxY) {   // aucune colonne mesurable : on ne juge pas
            return result;
        }
        result.waterRatio = (double) waterCount / totalFluid;
        result.lavaRatio = (double) lavaCount / totalFluid;
        result.heightDiff = maxY - minY;
        return result;
    }

    private static BlockPos findSafeAlternative(ServerLevel level, BlockPos origin) {
        long t0 = System.currentTimeMillis();
        BlockPos bestWaterOnly = null;
        for (int radius = ALT_SEARCH_STEP; radius <= ALT_SEARCH_RADIUS; radius += ALT_SEARCH_STEP) {
            for (int dx = -radius; dx <= radius; dx += ALT_SEARCH_STEP) {
                for (int dz = -radius; dz <= radius; dz += ALT_SEARCH_STEP) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int cx = origin.getX() + dx, cz = origin.getZ() + dz;
                    // T75 : le Y d'une zone de repli se lit sur le SOL (jamais sur une
                    // cime d'arbre : c'est ce qui placait les structures 7 a 26 blocs
                    // au-dessus de la terre).
                    int cy = deepluckyblock.util.SafeSurface.groundY(level, cx, cz, origin.getY());
                    BlockPos candidate = new BlockPos(cx, cy, cz);
                    ZoneAnalysis a = analyzeZone(level, candidate);
                    if (!a.isUnsafe()) {
                        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-SAFETY] Zone sure a {} (r={}, {}ms)", candidate.toShortString(), radius, System.currentTimeMillis() - t0);
                        return candidate;
                    }
                    if (bestWaterOnly == null && !a.hasTooMuchLava() && !a.hasCliff()) bestWaterOnly = candidate;
                }
            }
        }
        return bestWaterOnly;
    }

    private static int findFluidSurfaceY(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int surfaceY = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = surfaceY + 2; y > level.getMinBuildHeight(); y--) {
            p.set(x, y, z);
            if (!level.getBlockState(p).getFluidState().isEmpty()) return y + 1;
        }
        return surfaceY;
    }

    private static BlockPos resolveSafePosition(ServerLevel level, BlockPos targetXZ, String nbtName) {
        // FIX T7 : l'analyse de zone lit la surface -- heightmap initialisee obligatoire.
        int surfaceY = deepluckyblock.util.SafeSurface.surfaceY(level, targetXZ.getX(), targetXZ.getZ(), targetXZ.getY());
        BlockPos surfPos = new BlockPos(targetXZ.getX(), surfaceY, targetXZ.getZ());
        ZoneAnalysis analysis = analyzeZone(level, surfPos);
        if (!analysis.isUnsafe()) return targetXZ;
        LOGGER.warn("[STRUCT5-SAFETY] {} : {} -> recherche alt...", nbtName, analysis.getReason());
        BlockPos safeAlt = findSafeAlternative(level, surfPos);
        if (safeAlt != null && !analyzeZone(level, safeAlt).hasTooMuchLava()) return safeAlt;
        if (analysis.hasTooMuchLava()) return null;
        if (analysis.hasTooMuchWater() && !analysis.hasCliff())
            return new BlockPos(targetXZ.getX(), findFluidSurfaceY(level, targetXZ.getX(), targetXZ.getZ()), targetXZ.getZ());
        return targetXZ;
    }

    private static final Queue<PasteJob> PASTE_Q = new ArrayDeque<>();
    private static final Queue<CarveJob> CARVE_Q = new ArrayDeque<>();
    private static final Queue<PostJob> POST_Q = new ArrayDeque<>();

    private static class PasteJob {
        final ServerLevel level; final List<StructureTemplate.StructureBlockInfo> blocks;
        final BlockPos origin; final Rotation rot; final Mirror mir;
        final String name; final long t0; final Player player;
        final BlockPos min, max; int idx;
        final List<BlockPos> chests;   // positions des coffres poses (Everest)
        int grav;                      // blocs soumis a la gravite poses (preuve clamp)
        final List<Integer> defer = new ArrayList<>();  // T12 : blocs en attente de chunk
        // T14 : cache de section (le chunk n'est resolu qu'une fois par section).
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        boolean lastLoaded = false;
        int waitTicks;                 // T12 : ticks d'attente de la passe de rattrapage
        int redeferred;                // T12 : blocs finalement poses par la passe de rattrapage
        PasteJob(ServerLevel l, List<StructureTemplate.StructureBlockInfo> b, BlockPos o, Rotation r, Mirror m, String n, long t, Player p, BlockPos mn, BlockPos mx) {
            level = l; blocks = b; origin = o; rot = r; mir = m; name = n; t0 = t; player = p; min = mn; max = mx; idx = 0; chests = new ArrayList<>(); grav = 0;
        }
    }

    private static class CarveJob {
        final ServerLevel level;
        final List<int[]> columns;  // colonnes occupees : { x, z, loY, hiY }
        final List<Long> occList;   // positions occupees (marge +1)
        final Set<Long> occupied;
        final Set<Long> baseCols;   // empreinte pour la motte
        final BlockPos min, max;
        final String name;
        final int bottomY;          // y le plus bas (global)
        final int baseLevel;        // median des lo = niveau de sol reel (anti toit/auvent)
        int idx, ringIdx, groundIdx;
        boolean groundDone;
        int carved, grounded;
        CarveJob(ServerLevel l, List<int[]> cols, List<Long> occ, Set<Long> occSet, Set<Long> base, BlockPos mn, BlockPos mx, String n, int by, int bl) {
            level = l; columns = cols; occList = occ; occupied = occSet; baseCols = base; min = mn; max = mx; name = n; bottomY = by; baseLevel = bl;
            idx = 0; ringIdx = occ.size(); groundIdx = 0; groundDone = false; carved = 0; grounded = 0;
        }
    }

    private static class PostJob {
        final ServerLevel level; final BlockPos min, max; final String name; int y;
        PostJob(ServerLevel l, BlockPos mn, BlockPos mx, String n) { level = l; min = mn; max = mx; name = n; y = mn.getY(); }
    }

    /**
     * T12 : pose UN bloc de la structure.
     *
     * <p>Renvoie {@code false} quand le chunk n'est PAS en memoire : dans ce cas
     * le bloc est mis de cote (voir {@link #retryDeferred}) au lieu de declencher
     * {@code getChunk(..., true)} -- cette generation synchrone etait la cause
     * des gels de 2,4 s mesures pendant le paste d'everest (20/09 : « Can't keep
     * up! Running 2387ms or 47 ticks behind », puis 2263 ms).
     */
    private static boolean placeOne(PasteJob j, int i, StructurePlaceSettings rs,
                                    BlockPos.MutableBlockPos bp, boolean force) {
        StructureTemplate.StructureBlockInfo info = j.blocks.get(i);
        BlockState ts = info.state().mirror(j.mir).rotate(j.rot);
        if (isLaggy(ts)) return true;   // bloc ignore : considere comme traite
        BlockPos rel = StructureTemplate.calculateRelativePosition(rs, info.pos());
        bp.set(j.origin.getX() + rel.getX(), j.origin.getY() + rel.getY(), j.origin.getZ() + rel.getZ());
        int cx = bp.getX() >> 4, cz = bp.getZ() >> 4;
        if (cx != j.lastCx || cz != j.lastCz) {
            j.lastCx = cx; j.lastCz = cz;
            j.lastLoaded = j.level.getChunkSource().getChunkNow(cx, cz) != null;
        }
        if (!j.lastLoaded) {
            // T49 : plus AUCUNE generation synchrone, meme en mode force.
            deepluckyblock.util.SafeSurface.request(j.level, cx, cz);
            j.lastCx = Integer.MIN_VALUE;
            return false;
        }
        // T102 (rappel dev 30/09 : « toutes les structures doivent etre
        // paste -air... etc ! ») : toute entree d'air du template est
        // SAUTEE (-- equivalent strict du -a de WorldEdit --), quelle que
        // soit la case cible. L'ancienne regle (ecrire l'air sur non-air
        // pour creuser l'interieur, T73) n'est plus utile : le cut
        // d'emprise (clearFootprint, au-dessus du sol planifie) a DEJA
        // vide le volume avant le paste.
        if (ts.isAir()) return true;
        // T171 : chemin de pose rapide pour les blocs ordinaires. Level#setBlock
        // exécute les callbacks onPlace/physique pour chacun des ~75 000 blocs,
        // même avec FAST_FLAG, ce qui a mesuré 9–13 s sur Dragon. Le chunk est
        // déjà FULL et épinglé : écrire via LevelChunk conserve palettes,
        // heightmaps et état sale, puis blockChanged groupe l'envoi client.
        // Les block entities gardent impérativement le chemin Level afin que leur
        // instance existe avant le chargement NBT ci-dessous.
        if (!ts.hasBlockEntity()) {
            net.minecraft.world.level.chunk.LevelChunk chunk = j.level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) return false;
            // T175 : une écriture rapide peut remplacer un ancien hopper,
            // comparator, coffre, etc. Supprimer explicitement son BlockEntity,
            // y compris le NBT différé du chunk, avant de poser un bloc ordinaire.
            // Sans cela le prochain chargement tentait de recréer par exemple un
            // hopper sur AIR ou un comparator sur spruce_wood.
            BlockState oldState = chunk.getBlockState(bp);
            if (oldState.hasBlockEntity()) chunk.removeBlockEntity(bp);
            chunk.setBlockState(bp, ts, false);
            chunk.setUnsaved(true);
            j.level.getChunkSource().blockChanged(bp);
        } else {
            j.level.setBlock(bp, ts, FAST_FLAG);
        }
        if (ts.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) j.grav++;
        if (info.nbt() != null) {
            var be = j.level.getBlockEntity(bp);
            if (be != null) { CompoundTag tag = info.nbt().copy(); tag.putInt("x", bp.getX()); tag.putInt("y", bp.getY()); tag.putInt("z", bp.getZ()); be.loadWithComponents(tag, j.level.registryAccess()); }
        }
        // Les coffres vanilla vides deviennent des coffres automatiques.
        // Les coffres ayant deja nos donnees NBT sont preserves.
        if (isEverestNbt(j.name) && ts.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
            j.chests.add(bp.immutable());
        }
        prepareAutomaticChest(j.level, bp, ts, info.nbt(), info.pos().getY(), j.blocks);
        return true;
    }

    /**
     * T12 : repose les blocs differes. Aucune generation forcee tant que la zone
     * arrive par le chunk system ; au-dela de 600 ticks (30 s) sans succes, on
     * force le chargement en DERNIER RECOURS : une structure doit etre complete,
     * jamais trouee.
     */
    /** T85/B4 : plafond honnete d'attente des blocs differes (meme regle que
     *  Structures4 -- aucune file de pose ne tourne plus a 100 % indefiniment). */
    private static final int MAX_DEFER_WAIT_TICKS = 2400;
    /** T85/B4 : en mode force, re-demande active des chunks manquants tous les N ticks. */
    private static final int DEFER_ESCALATE_TICKS = 200;

    private static void retryDeferred(PasteJob j, StructurePlaceSettings rs, BlockPos.MutableBlockPos bp) {
        j.waitTicks++;
        boolean force = j.waitTicks > 600;
        if (force && j.waitTicks == 601) {
            LOGGER.warn("[STRUCT5] {} : {} blocs en attente depuis 30 s -- chargement force des chunks restants", j.name, j.defer.size());
        }
        // T85/B4 : le mode « force » n'etait qu'un warn -- les chunks manquants
        // n'etaient plus re-demandes. Re-demande active toutes les
        // DEFER_ESCALATE_TICKS + plafond honnete (Structures4 montre le danger :
        // 5 blocs ont tenu l'everest 7229 ticks = 6 min a 100 %).
        if (force && j.waitTicks % DEFER_ESCALATE_TICKS == 0) escalateDeferredRequests(j, rs);
        int placed = 0;
        long retryT0 = System.currentTimeMillis();
        java.util.Iterator<Integer> it = j.defer.iterator();
        while (it.hasNext()) {
            // T34 : budget de temps (cf. Structures4Procedure.retryDeferred) : la
            // repose des blocs differes ne doit jamais tenir le tick plus de 40 ms.
            if (System.currentTimeMillis() - retryT0 > PASTE_TICK_BUDGET_MS) break;
            int i = it.next();
            if (placeOne(j, i, rs, bp, force)) { it.remove(); placed++; }
        }
        j.redeferred += placed;
        if (placed > 0) {
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} : {} bloc(s) reposes apres chargement de la zone ({} restants, {} ticks d'attente)", j.name, placed, j.defer.size(), j.waitTicks);
        }
        // T85/B4 : la file ne doit jamais attendre ces blocs plus longtemps
        // que MAX_DEFER_WAIT_TICKS -- abandon honnete (positions nommees).
        if (!j.defer.isEmpty() && j.waitTicks > MAX_DEFER_WAIT_TICKS) {
            StringBuilder sample = new StringBuilder();
            int shown = 0;
            for (int di : j.defer) {
                if (shown >= 6) { sample.append(", ..."); break; }
                StructureTemplate.StructureBlockInfo info = j.blocks.get(di);
                BlockPos rel = StructureTemplate.calculateRelativePosition(rs, info.pos());
                if (shown++ > 0) sample.append(", ");
                sample.append(j.origin.getX() + rel.getX()).append(',').append(j.origin.getZ() + rel.getZ());
            }
            LOGGER.warn("[STRUCT5] {} : {} bloc(s) SANS CHUNK apres {} s et re-demandes actives -- "
                            + "abandon honnete (positions x,z : {}) ; la file se termine, le reste ({}/{}) est pose (T85)",
                    j.name, j.defer.size(), MAX_DEFER_WAIT_TICKS / 20, sample, j.idx, j.blocks.size());
            j.defer.clear();
        }
    }

    /** T85/B4 : re-demande active les chunks des blocs encore differes (dedup, non bloquant). */
    private static void escalateDeferredRequests(PasteJob j, StructurePlaceSettings rs) {
        java.util.Set<Long> asked = new java.util.HashSet<>();
        int rq = 0;
        for (int di : j.defer) {
            StructureTemplate.StructureBlockInfo info = j.blocks.get(di);
            BlockPos rel = StructureTemplate.calculateRelativePosition(rs, info.pos());
            int cx = (j.origin.getX() + rel.getX()) >> 4, cz = (j.origin.getZ() + rel.getZ()) >> 4;
            if (!asked.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) continue;
            if (j.level.getChunkSource().getChunkNow(cx, cz) == null) {
                deepluckyblock.util.SafeSurface.reRequest(j.level, cx, cz);
                rq++;
            }
        }
        if (rq > 0)
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} : {} chunk(s) re-demande(s) activement pour les {} bloc(s) restants ({} ticks d'attente)",
                    j.name, rq, j.defer.size(), j.waitTicks);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        // FIX T7 : relance la file d'attente des qu'elle peut repartir. Sans ca,
        // une structure retenue parce que son NBT etait encore en cours de
        // chargement en tache de fond ne redemarrait jamais (l'ancien code
        // n'appelait startNextPending qu'a la FIN d'une generation).
        if (DEFER_WAIT_TICKS > 0 && !GENERATION_BUSY && !PENDING_STRUCTURES.isEmpty()) {
            startNextPending(null);
        }
        if (!PASTE_Q.isEmpty()) {
            PasteJob j = PASTE_Q.peek();
            // T12 : la zone reste TENUE pendant le paste. Sans ce appel, les
            // chunks se dechargent au bout de la grace (10 s) et la pose des
            // blocs suivants les regenere SYNCHRONEMENT sur le thread serveur --
            // c'est l'origine des gels de 2,4 s mesures en plein paste.
            deepluckyblock.util.ChunkKeeper.keep(j.level);
            StructurePlaceSettings rs = new StructurePlaceSettings().setRotation(j.rot).setMirror(j.mir);
            BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
            if (j.idx < j.blocks.size()) {
                int end = Math.min(j.idx + BLOCKS_PER_TICK, j.blocks.size());
                // T33 : budget de TEMPS (cf. PASTE_TICK_BUDGET_MS).
                long tickT0 = System.currentTimeMillis();
                int i = j.idx;
                for (; i < end; i++) {
                    if ((i & 127) == 0 && System.currentTimeMillis() - tickT0 > PASTE_TICK_BUDGET_MS) break;
                    if (!placeOne(j, i, rs, bp, false)) j.defer.add(i);
                }
                j.idx = i;
            } else {
                // Passe de rattrapage : les blocs dont le chunk n'etait pas en
                // memoire au moment de leur tour sont reposes ici, sans jamais
                // forcer la generation.
                retryDeferred(j, rs, bp);
                if (!j.defer.isEmpty()) return;
            }
            if (j.idx >= j.blocks.size() && j.defer.isEmpty()) {
                PASTE_Q.poll();
                long el = System.currentTimeMillis() - j.t0;
                int technicalRemoved = removeUndergroundTechnicalBlocks(j.level, j.min, j.max);
                if (technicalRemoved > 0) {
                    deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} LIGHT/vines techniques souterrains supprimes apres paste", technicalRemoved);
                }
                if (isEverestNbt(j.name)) {
                    cleanupEverestChests(j.level, j.chests);
                }
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} en {}ms ({} blocs, {} a gravite -- ticks de chute neutralises par DLB-CLAMP)", j.name, el, j.blocks.size(), j.grav);
                if (j.player != null) {
                    j.player.sendSystemMessage(Component.literal("\u00a7a\u2713 Pose brute \u00a7e" + j.name + "\u00a7a en " + el + "ms (phase paste seule, hors recherche) — finitions en cours"));
                    announceStructureSpawn(j.player, j.name, j.min, j.max);
                }
                CarveJob carve = buildCarveJob(j);
                CARVE_Q.offer(carve);
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] carve {} programmee ({} colonnes, {} blocs occupes, bottomY={})", j.name, carve.columns.size(), carve.occupied.size(), carve.bottomY);
            }
        }
        if (!CARVE_Q.isEmpty()) {
            CarveJob cj = CARVE_Q.peek();
            BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
            int minBuild = cj.level.getMinBuildHeight();
            if (cj.idx < cj.columns.size()) {
                int end = Math.min(cj.idx + CARVE_COLS_PER_TICK, cj.columns.size());
                for (int i = cj.idx; i < end; i++) {
                    int[] c = cj.columns.get(i);
                    int x = c[0], z = c[1], lo = c[2], hi = c[3];
                    for (int y = lo + 1; y <= hi; y++) {
                        if (cj.occupied.contains(BlockPos.asLong(x, y, z))) continue;
                        BlockState s = cj.level.getBlockState(mut.set(x, y, z));
                        if (!s.isAir() && isNaturalTerrain(s)) { cj.level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG); cj.carved++; }
                    }
                    for (int y = hi + 1; y <= hi + CARVE_CANOPY; y++) {
                        if (cj.occupied.contains(BlockPos.asLong(x, y, z))) continue;
                        BlockState s = cj.level.getBlockState(mut.set(x, y, z));
                        if (!s.isAir() && isTreePart(s)) { cj.level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG); cj.carved++; }
                    }
                }
                cj.idx = end;
            } else if (cj.ringIdx < cj.occList.size()) {
                int end = Math.min(cj.ringIdx + RING_PER_TICK, cj.occList.size());
                for (int i = cj.ringIdx; i < end; i++) {
                    long pos = cj.occList.get(i);
                    int px = BlockPos.getX(pos), py = BlockPos.getY(pos), pz = BlockPos.getZ(pos);
                    clearRing(cj, mut, px + 1, py, pz);
                    clearRing(cj, mut, px - 1, py, pz);
                    clearRing(cj, mut, px, py, pz + 1);
                    clearRing(cj, mut, px, py, pz - 1);
                }
                cj.ringIdx = end;
            } else if (!cj.groundDone) {
                // Par colonne : on rebouche UNIQUEMENT les colonnes de sol (lo pres
                // de baseLevel) ET uniquement les positions CACHEES. Une position
                // dont un voisin horizontal est exposed (air/non-solide) est sautee :
                // sinon on colle des blocs de dirt visibles sur les facades.
                int end = Math.min(cj.groundIdx + GROUND_COLS_PER_TICK, cj.columns.size());
                BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
                for (int i = cj.groundIdx; i < end; i++) {
                    int[] c = cj.columns.get(i);
                    int x = c[0], z = c[1], lo = c[2];
                    if (lo > cj.baseLevel + FLOOR_TOLERANCE) continue; // colonne de toit/auvent -> RIEN
                    BlockState below = cj.level.getBlockState(mut.set(x, lo - 1, z));
                    if (!below.isAir() && below.blocksMotion()) continue; // sol solide -> RIEN
                    int y = lo - 1;
                    int depth = 0;
                    while (y >= minBuild && depth < GROUND_MAX_DEPTH) {
                        BlockState cur = cj.level.getBlockState(mut.set(x, y, z));
                        if (!cur.isAir() && cur.blocksMotion()) break; // sol trouve -> stop
                        if (!isHorizontallyExposed(cj.level, side, x, y, z)) {
                            cj.level.setBlock(mut, Blocks.DIRT.defaultBlockState(), 2);
                            cj.grounded++;
                        }
                        y--; depth++;
                    }
                }
                cj.groundIdx = end;
                if (cj.groundIdx >= cj.columns.size()) cj.groundDone = true;
            }
            if (cj.idx >= cj.columns.size() && cj.ringIdx >= cj.occList.size() && cj.groundDone) {
                CARVE_Q.poll();
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} termine : {} blocs supprimes, {} blocs de motte (bottomY={})", cj.name, cj.carved, cj.grounded, cj.bottomY);
                // Le decor thematique est desormais pose PAR decorate(), juste
                // AVANT le replant des arbres (demande utilisateur : les arbres
                // doivent pousser AUTOUR des decors, jamais dedans ni dessus).
                // On lui transmet le nom de la structure pour le choix du theme.
                StructureTerrainPrep.setStructureName(cj.name);
                StructureTerrainPrep.decorateFinish(cj.level, cj.min, cj.max);   // T39 : finitions post-pose
                POST_Q.offer(new PostJob(cj.level, cj.min, cj.max, cj.name));
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] decorate + post de {} termine", cj.name);
            }
        }
        if (!POST_Q.isEmpty()) {
            PostJob pj = POST_Q.peek();
            for (int s = 0; s < 4 && pj.y <= pj.max.getY(); s++) { postLayer(pj.level, pj.min, pj.max, pj.y); pj.y++; }
            if (pj.y > pj.max.getY()) {
                killLaggyItems(pj.level, pj.min, pj.max);
                if (isEverestNbt(pj.name)) {
                    spawnEverestMobs(pj.level, pj.min, pj.max);
                }
                POST_Q.poll();
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] Post-process de {} termine", pj.name);
                // T184 : decorateFinish est asynchrone. T183 libérait ici la FIFO
                // alors que dressAndPlant/replantTrees travaillaient encore 4 s :
                // le second Dragon recherchait pendant les déchargements du premier.
                // La libération réelle de ChunkKeeper marque désormais la vraie fin.
                finishWhenTerrainReleased(pj.level, pj.name);
            }
        }
    }

    private static void clearRing(CarveJob cj, BlockPos.MutableBlockPos mut, int x, int y, int z) {
        if (cj.occupied.contains(BlockPos.asLong(x, y, z))) return;
        BlockState s = cj.level.getBlockState(mut.set(x, y, z));
        if (!s.isAir() && isNaturalTerrain(s)) { cj.level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG); cj.carved++; }
    }

    // Une position est "exposed" si un voisin horizontal est air ou non-solide.
    // Sert a ne JAMAIS placer de dirt visible sur les facades exterieures.
    private static boolean isHorizontallyExposed(ServerLevel level, BlockPos.MutableBlockPos m, int x, int y, int z) {
        BlockState n = level.getBlockState(m.set(x + 1, y, z));
        if (n.isAir() || !n.blocksMotion()) return true;
        n = level.getBlockState(m.set(x - 1, y, z));
        if (n.isAir() || !n.blocksMotion()) return true;
        n = level.getBlockState(m.set(x, y, z + 1));
        if (n.isAir() || !n.blocksMotion()) return true;
        n = level.getBlockState(m.set(x, y, z - 1));
        if (n.isAir() || !n.blocksMotion()) return true;
        return false;
    }

    // bottomY = le DERNIER bloc reel de la structure (le y le plus bas, hors verre).
    // La motte se construit EN DESSOUS de ce bloc (a partir de bottomY-1).
    private static CarveJob buildCarveJob(PasteJob j) {
        StructurePlaceSettings rs = new StructurePlaceSettings().setRotation(j.rot).setMirror(j.mir);
        Set<Long> occupied = new HashSet<>(j.blocks.size());
        Map<Long, int[]> cols = new HashMap<>();
        for (StructureTemplate.StructureBlockInfo info : j.blocks) {
            BlockPos rel = StructureTemplate.calculateRelativePosition(rs, info.pos());
            int x = j.origin.getX() + rel.getX(), y = j.origin.getY() + rel.getY(), z = j.origin.getZ() + rel.getZ();
            occupied.add(BlockPos.asLong(x, y, z));
            long ck = colKey(x, z);
            int[] e = cols.get(ck);
            if (e == null) cols.put(ck, new int[]{x, z, y, y});
            else { if (y < e[2]) e[2] = y; if (y > e[3]) e[3] = y; }
        }
        Set<Long> baseCols = new HashSet<>(cols.size());
        int bottomY = Integer.MAX_VALUE;
        List<Integer> los = new ArrayList<>(cols.size());
        for (int[] col : cols.values()) { baseCols.add(colKey(col[0], col[1])); if (col[2] < bottomY) bottomY = col[2]; los.add(col[2]); }
        // baseLevel = mediane des lo. Robuste : les quelques fondations profondes
        // et les colonnes de toit (minoritaires) ne faussent pas la mediane.
        // On ne rebouchera que les colonnes dont lo est pres de ce niveau de sol.
        Collections.sort(los);
        int baseLevel = los.isEmpty() ? bottomY : los.get(los.size() / 2);
        return new CarveJob(j.level, new ArrayList<>(cols.values()), new ArrayList<>(occupied), occupied, baseCols, j.min, j.max, j.name, bottomY, baseLevel);
    }

    public static void generate(ServerLevel level, BlockPos origin, int id, RandomSource random) {
        if (level == null || origin == null) return;
        boolean accepted = switch (id) {
            case 0, 16 -> doPaste(level, origin, CITADEL_NBT, CITADEL_OFFSET_X, CITADEL_OFFSET_Y, CITADEL_OFFSET_Z, CITADEL_DISTANCE, CITADEL_FACING, CITADEL_FOLLOW_SURFACE, CITADEL_USE_FLAT, CITADEL_KEEP_INT);
            case 1, 17 -> doPaste(level, origin, OBS_NBT, OBS_OFFSET_X, OBS_OFFSET_Y, OBS_OFFSET_Z, OBS_DISTANCE, OBS_FACING, OBS_FOLLOW_SURFACE, OBS_USE_FLAT, OBS_KEEP_INT);
            case 2, 18 -> doPaste(level, origin, DRAGON_NBT, DRAGON_OFFSET_X, DRAGON_OFFSET_Y, DRAGON_OFFSET_Z, DRAGON_DISTANCE, DRAGON_FACING, DRAGON_FOLLOW_SURFACE, DRAGON_USE_FLAT, DRAGON_KEEP_INT);
            default -> { LOGGER.warn("[STRUCT5] ID inconnu : {}", id); yield false; }
        };
        // Un refus avant création d'un job doit libérer la file immédiatement.
        // T165 laissait GENERATION_BUSY actif pendant 240 secondes.
        if (!accepted) notifyGenerationAborted(level);
    }

    // =====================================================================
    // FILE D'ATTENTE DES STRUCTURES : une seule generation a la fois
    // =====================================================================
    //
    // Demande utilisateur : « il faut faire en sorte que les structures
    // apparaissent par ordre de priorite, une fois le premier fini, le second
    // peut commencer ».
    //
    // Sans cela, ouvrir plusieurs lucky blocks d'affilee lancait 2, 3 voire 5
    // generations EN PARALLELE. Chacune planifie ~650 taches de terrain : les
    // files se melangeaient, le serveur saturait, et AUCUNE structure ne
    // terminait -- exactement le blocage constate (citadelle ET everest
    // demarrees, aucune finie apres une heure).
    //
    // Desormais une seule generation est active a la fois ; les suivantes
    // attendent leur tour dans PENDING et demarrent quand la precedente a
    // signale sa fin.

    /**
     * Une structure en attente : id + position de depart + le niveau concerne
     * (le niveau est desormais porte par l'entree, une structure pouvant etre
     * demandee dans n'importe quelle dimension).
     */
    // T228 : le contexte (joueur pour l'achievement, pose de test /dlbtest)
    // voyage AVEC chaque demande en file. Avant, execute() l'ecrasait dans des
    // globaux au moment du tirage : une seconde structure demandee pendant une
    // generation volait l'achievement de la premiere et perdait le sien.
    private record PendingStructure(int id, BlockPos origin, ServerLevel level,
                                    Player player, boolean manualTest) {}

    private static final java.util.ArrayDeque<PendingStructure> PENDING_STRUCTURES = new java.util.ArrayDeque<>();
    private static volatile boolean GENERATION_BUSY = false;
    // T226 : id de la structure actuellement en cours de pose. Sert a confirmer
    // le drapeau « unique par monde » UNIQUEMENT quand la pose a vraiment abouti.
    private static volatile int CURRENT_STRUCTURE_ID = -1;
    // T227 : l'achievement de structure attend la pose reelle (voir execute()).
    private static volatile BlockPos PENDING_ACHIEVEMENT_ORIGIN = null;
    private static volatile Player   PENDING_ACHIEVEMENT_PLAYER = null;
    /** T227 : true quand la pose vient d'une commande /dlbtest, pas d'un lucky block.
     *  Consomme par execute() au premier tick et copie dans CURRENT_MANUAL_TEST /
     *  le PendingStructure (T228). */
    private static volatile boolean  MANUAL_TEST_SPAWN = false;
    /** T228 : drapeau « pose de test » de la generation ACTIVE (pas de la demande
     *  la plus recente). Seul lui decide de consommer ou non l'unicite-monde. */
    private static volatile boolean  CURRENT_MANUAL_TEST = false;
    private static volatile long GENERATION_START_MS = 0L;
    /** Garde-fou : si une generation ne signale jamais sa fin, on debloque. */
    private static final long GENERATION_TIMEOUT_MS = 240_000L;
    /** Une seule génération active : compteur borné du chargement de son emprise choisie. */
    private static int FOOTPRINT_LOAD_RETRIES = 0;
    private static final int MAX_FOOTPRINT_LOAD_RETRIES = 200;
    // T232 : borne de temps de l'attente de l'emprise finale. Reproduit en lab
    // sandbox : sans elle, la boucle SAFETY tournait SANS FIN (reprise 480+/200,
    // 15+ minutes, aucune structure posee) parce que les chunks charges
    // etaient decharges avant la reprise suivante (ticket async retire trop
    // tot). L'accord SafeSurface<->ChunkKeeper supprime la cause ; cette borne
    // supprime le symptome si une variante reapparait : au pire, la generation
    // echoue PROPREMENT (file liberee, candidate suivante a la prochaine
    // invocation) au lieu de silencer le serveur.
    private static long SAFETY_AWAIT_START_MS = 0L;
    private static final long SAFETY_AWAIT_TIMEOUT_MS =
            Integer.getInteger("dlb.safetyAwaitTimeoutMs", 600_000); // 10 min par defaut
    /** T229 : reprises consecutives passees a attendre les controles de sol
     * (x0,6 / emprise entiere). Sert uniquement a rendre cette attente visible:
     * sans journal, le joueur ne voyait plus rien passer pendant des minutes. */
    private static int GROUND_WAIT_TICKS = 0;
    /** Centres démasqués par le contrôle exhaustif x0,6 pendant la génération active. */
    private static final Set<Long> REJECTED_RUGGED_CENTERS = new HashSet<>();
    /** Rayon ajouté lorsque la zone courante ne contient aucun centre admissible. */
    private static int SEARCH_EXTRA_RADIUS = 0;
    /** Rayon déjà classé pour la demande active : les reprises ne parcourent que
     * la nouvelle couronne, jamais tout le carré depuis zéro (T180). */
    private static int SEARCH_SCANNED_RADIUS = -1;
    // T230 : throttles de journalisation. La recherche d'un dragon en monde
    // neuf prenait 2 min 25 SANS la moindre ligne de log entre la demande et
    // la zone choisie : le joueur ne savait pas si le jeu etait gele ("toujours
    // rien ?" tape deux fois). On publie desormais un etat toutes les 10 s.
    private static long SEARCH_PROGRESS_LAST_MS = 0L;
    private static long DEFERRED_WAIT_LAST_MS = 0L;
    private static long DRAIN_WAIT_LAST_MS = 0L;
    /** Ultime candidat généré, une seule emprise à la fois, uniquement après
     * épuisement des zones historiques jusqu'à 4096 blocs. */
    private static BlockPos SEARCH_GENERATED_FALLBACK = null;
    /** Nombre de ticks d'attente du secours ciblé courant (T186). */
    private static int SEARCH_FALLBACK_WAIT_TICKS = 0;
    private static int SEARCH_FALLBACK_JUMPS = 0;
    private static final int MAX_FALLBACK_JUMPS = 8;
    /** T232-F : miroir de la derniere cible de secours REELLEMENT demandee
     * (jamais remis a null par giveUpFallback). Sans lui, le rearmement T231
     * relisait SEARCH_GENERATED_FALLBACK apres effacement -> chemin mort. */
    private static BlockPos SEARCH_FALLBACK_LAST_TARGET = null;
    /** T232-F : debut du regimede rearmements successifs (borne horaire). */
    private static long SEARCH_STALL_START_MS = 0L;
    /** Borne des rearmements successifs de secours avant abandon (default 180 s,
     * configurable : -Ddlb.search.stallTimeoutMs=<ms>). */
    private static final long SEARCH_STALL_TIMEOUT_MS =
            Long.parseLong(System.getProperty("dlb.search.stallTimeoutMs", "180000"));
    /** T229 : ticks passes a attendre que l'emprise de secours soit INTEGRALEMENT
     * livree alors que sa grille echantillonnee est deja prete (detectExactGround
     * renvoie null). Sans ce compteur ni la re-demande qui l'accompagne, la
     * recherche bouclait en silence : c'est le blocage observe en jeu (dragon
     * jamais pose, aucune ligne de log pendant des minutes). */
    private static int SEARCH_FALLBACK_STALL_TICKS = 0;
    /** T229 : candidats dont la zone a ete demandee en tache de fond et qui sont
     * reverifies a chaque reprise, AU LIEU d'etre consommes a vue (T185). T185
     * vidait tout terrain non visite : dans un monde neuf aucune zone au-dela du
     * spawn n'est FULL, donc 100 % des candidats sautaient en ~2 s et la seule
     * issue etait l'emprise generee a ~1000 blocs (puis le blocage ci-dessus). */
    private static final List<BlockPos> SEARCH_DEFERRED = new ArrayList<>();
    private static final Set<Long> SEARCH_DEFERRED_KEYS = new HashSet<>();
    private static final Map<Long, Integer> SEARCH_DEFERRED_ROUNDS = new HashMap<>();
    /** Cap anti-inondation (le grief d'origine de T185 reste impossible). */
    // T231 (campagne CI de calibration, demande dev 07/10 : "fais 11 tests
    // opti, garde le meilleur") : ces trois reglages deviennent variables par
    // propriete systeme (-Ddlb.search.*), UNIQUEMENT pour la batterie de runs
    // serveur. Les valeurs par defaut sont exactement celles du T230 ; la
    // variante gagnante sera refigee ici en constantes avec le livrable final.
    private static final int MAX_DEFERRED = propInt("dlb.search.deferCap", 96);
    /** Au-dela, un candidat differe dont la zone n'arrive jamais est consomme. */
    private static final int MAX_DEFER_ROUNDS = propInt("dlb.search.deferRounds", 600);
    // 2 = croissance double (T181), 1 = +64 lineaire (pre-T181).
    private static final int SEARCH_GROWTH_MODE = propInt("dlb.search.growth", 2);
    /* T233 : pre-filtre SEC de relief par bruit (sans charger/generer de chunk).
     * Mesure en partie reelle (log du 10/10, terrain montagneux relief brut 14)
     * : chaque candidat sans chunks en memoire declenchait en fond la
     * generation de toute son emprise (requestZone) ; des centaines de zones
     * vouees au rejet par la porte relief (max 10) inondaient le worldgen et
     * faisaient ramer le serveur ("Can't keep up" 32-40 s). La sonde seche
     * (API generateur, aucune lecture de chunk) ecarte d'emblee les candidats
     * dont l'ecart de hauteur depasse DRY_GATE_SPAN (~26, soit porte 10 x1,75
     * + marge 8 pour le decalage bruit/reel). Les portes de terrain REEL
     * (flat, eau, emprise exhaustive) restent le juge final de tous les
     * candidats restants : aucune tolerance d'acceptation desserree, seulement
     * moins de generations gaspillees. Desactivable : -Ddlb.search.dryGate=0. */
    private static final boolean SEARCH_DRY_GATE =
            !"0".equals(System.getProperty("dlb.search.dryGate", "1"));
    private static final int SEARCH_DRY_GATE_SPAN =
            propInt("dlb.search.dryGateSpan", (int) Math.ceil(MAX_CLIFF_HEIGHT_DIFF * 1.75) + 8);
    private static int SEARCH_DRY_REJECTS = 0;

    private static int propInt(String key, int def) {        try { return Integer.parseInt(System.getProperty(key, Integer.toString(def))); }
        catch (NumberFormatException bad) { return def; }
    }
    /** T233 : ecart de hauteur estime en 9 sondes bruit sur l'emprise, sans
     * charger ni generer le moindre chunk. Retourne 0 si le generateur ne sait
     * pas estimer (mode degrade : aucun rejet sec). */
    private static int dryRuggedSpan(net.minecraft.server.level.ServerLevel lvl, int cx, int cz, int r) {
        try {
            net.minecraft.world.level.chunk.ChunkGenerator gen = lvl.getChunkSource().getGenerator();
            net.minecraft.world.level.levelgen.RandomState rs = lvl.getChunkSource().randomState();
            int s = Math.max(24, r / 2);
            int[][] pts = {{0, 0}, {-s, -s}, {-s, s}, {s, -s}, {s, s}, {-s, 0}, {s, 0}, {0, -s}, {0, s}};
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (int[] pt : pts) {
                int h = gen.getBaseHeight(cx + pt[0], cz + pt[1],
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, lvl, rs);
                if (h < min) min = h;
                if (h > max) max = h;
            }
            return min == Integer.MAX_VALUE ? 0 : max - min;
        } catch (Throwable any) {
            return 0;   // generateur exotique : ne jamais rejeter a l'aveugle
        }
    }
    /** T190 : meilleur terrain sec, réel et intégralement chargé rencontré. Le
     * pipeline clear + deux smooths peut corriger son relief sans générer une
     * emprise distante pendant plusieurs minutes. */
    private static BlockPos SEARCH_BEST_LOADED_DRY = null;
    private static int SEARCH_BEST_LOADED_DRY_RELIEF = Integer.MAX_VALUE;
    /** Candidat choisi conservé pendant son chargement : aucune nouvelle recherche,
     * aucun changement de rotation ou d'emprise entre deux reprises. */
    private static BlockPos PENDING_FLAT_CENTER = null;
    /** T228 : le secours cible a epuise ses sauts (giveUpFallback). La generation
     * doit etre abandonnee UNE fois par l'appelant, sans replanification fantome :
     * avant ce drapeau, giveUpFallback abortait immediatement, puis doPaste
     * planifiait quand meme un retry qui relancait la generation en boucle et
     * abortait la structure SUIVANTE toutes les ~40 s. */
    private static volatile boolean SEARCH_ABORTED = false;
    /** Ticks d'attente du chargement de fond du NBT en cours (diagnostic). */
    private static int DEFER_WAIT_TICKS = 0;

    /** Id de structure -> nom du fichier .nbt (voir StructureTemplateCache). */
    public static String nbtNameForId(int id) {
        return switch (id) {
            case 0, 16 -> CITADEL_NBT;
            case 1, 17 -> OBS_NBT;
            case 2, 18 -> DRAGON_NBT;
            case 3 -> "circus";
            case 4 -> "shipdead";
            case 5 -> "everest";
            default -> null;
        };
    }

    /**
     * Vrai si le NBT de cette structure est deja en memoire (donc chargement
     * instantane, aucun blocage du thread serveur).
     */
    private static boolean templateReady(String nbtName) {
        return nbtName == null
                || deepluckyblock.util.StructureTemplateCache.isCached(nbtName)
                || deepluckyblock.util.StructureTemplateCache.hasFailed(nbtName);
    }

    private static void finishWhenTerrainReleased(ServerLevel level, String name) {
        if (deepluckyblock.util.ChunkKeeper.held(level)) {
            deepluckyblock.procedures.TestProcedure.schedule(level,
                    deepluckyblock.procedures.TestProcedure.currentTick(level) + 2,
                    () -> finishWhenTerrainReleased(level, name));
            return;
        }
        long totalMs = GENERATION_START_MS == 0L ? -1L
                : System.currentTimeMillis() - GENERATION_START_MS;
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} entièrement terminé (recherche, pose, terrain, végétation et arbres) en {} ms : FIFO libérée",
                name, totalMs);
        notifyGenerationFinished(level);
    }

    /** Appele par les procedures de generation quand une structure est terminee. */
    /**
     * T226 — ECHEC de generation : libere la file ET rend la structure a nouveau
     * tirable. A ne surtout pas confondre avec notifyGenerationFinished(), qui
     * grave « deja apparue sur ce monde ». Les deux partageaient la meme methode
     * avant T226 : un terrain refuse consommait donc la structure pour toujours.
     */
    /** T227 : a appeler juste avant une pose demandee par /dlbtest. */
    public static void markManualTestSpawn() { MANUAL_TEST_SPAWN = true; }

    public static void notifyGenerationAborted(ServerLevel level) {
        PENDING_ACHIEVEMENT_ORIGIN = null;
        PENDING_ACHIEVEMENT_PLAYER = null;
        MANUAL_TEST_SPAWN = false;
        CURRENT_MANUAL_TEST = false;
        GenerateLuckyItemProcedure.releaseStructureReservation(CURRENT_STRUCTURE_ID);
        CURRENT_STRUCTURE_ID = -1;
        GENERATION_BUSY = false;
        startNextPending(level);
    }

    public static void notifyGenerationFinished(ServerLevel level) {
        // T226 : c'est SEULEMENT ici que la structure est consideree comme
        // reellement apparue sur ce monde. Avant, le drapeau etait ecrit des le
        // tirage : toute generation interrompue (redemarrage, NBT non charge,
        // terrain refuse, joueur hors portee) brulait la structure a jamais.
        if (PENDING_ACHIEVEMENT_ORIGIN != null) {
            try {
                grantStructureSpawnAchievement(level, PENDING_ACHIEVEMENT_ORIGIN,
                        PENDING_ACHIEVEMENT_PLAYER, CURRENT_STRUCTURE_ID);
            } catch (Exception ignored) {}
            PENDING_ACHIEVEMENT_ORIGIN = null;
            PENDING_ACHIEVEMENT_PLAYER = null;
        }
        // T227 : une pose demandee a la main par /dlbtest ne doit PAS consommer
        // la structure pour le monde (sinon tester = bruler).
        // T228 : on lit le drapeau de la generation ACTIVE (CURRENT_MANUAL_TEST),
        // pas celui de la derniere commande passee (MANUAL_TEST_SPAWN), qui
        // pouvait appartenir a une autre structure encore en file.
        if (CURRENT_MANUAL_TEST) {
            GenerateLuckyItemProcedure.releaseStructureReservation(CURRENT_STRUCTURE_ID);
            CURRENT_MANUAL_TEST = false;
        } else {
            GenerateLuckyItemProcedure.commitStructureSpawn(level, CURRENT_STRUCTURE_ID);
        }
        CURRENT_STRUCTURE_ID = -1;
        GENERATION_BUSY = false;
        startNextPending(level);
    }

    /**
     * Demarre la premiere structure en attente -- mais seulement si son NBT est
     * deja en memoire. Sinon le chargement est lance en tache de fond (FIX T7,
     * cf. StructureTemplateCache.requestAsync) et la structure reste en tete de
     * file : elle demarrera automatiquement quelques ticks plus tard, sans
     * jamais bloquer le thread serveur.
     */
    private static void startNextPending(ServerLevel level) {
        if (GENERATION_BUSY || PENDING_STRUCTURES.isEmpty()) return;
        PendingStructure next = PENDING_STRUCTURES.peek();
        String nbtName = nbtNameForId(next.id());
        if (!templateReady(nbtName)) {
            deepluckyblock.util.StructureTemplateCache.requestAsync(next.level(), nbtName);
            DEFER_WAIT_TICKS++;
            if (DEFER_WAIT_TICKS == 1) {
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] '{}' : NBT charge en TACHE DE FOND ({} octets sur disque) -- generation retenue quelques ticks, "
                                + "le serveur n'est PAS bloque. Elle demarrera seule des que le chargement sera fini.",
                        nbtName, nbtFileHint(nbtName));
                if (next.level() != null && next.level().getServer() != null) {
                    for (Player p : next.level().getServer().getPlayerList().getPlayers()) {
                        if (p != null) p.sendSystemMessage(Component.literal(
                                "§e⏳ " + nbtName + " : préparation du terrain en cours…"));
                    }
                }
            } else if (DEFER_WAIT_TICKS % 200 == 0) {
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] '{}' : toujours en cours de chargement ({} s) -- structure maintenue en attente", nbtName, DEFER_WAIT_TICKS / 20);
            }
            return;
        }
        PENDING_STRUCTURES.poll();
        DEFER_WAIT_TICKS = 0;
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] File d'attente : demarrage de la structure ID={} ({} encore en attente)",
                next.id(), PENDING_STRUCTURES.size());
        // T228 : restaurer le contexte qui voyageait avec la demande.
        PENDING_ACHIEVEMENT_ORIGIN = next.origin();
        PENDING_ACHIEVEMENT_PLAYER = next.player();
        CURRENT_MANUAL_TEST = next.manualTest();
        beginGeneration(next.level() != null ? next.level() : level, next.origin(), next.id());
    }

    /** Taille indicative du fichier .nbt, pour les logs (aucune lecture). */
    private static String nbtFileHint(String nbtName) {
        return switch (nbtName) {
            case "citadel" -> "5,99 Mo";
            case "shipdead" -> "4,88 Mo";
            case "crimsonlake" -> "3,46 Mo";
            case "dragon" -> "2,32 Mo";
            case "circus" -> "2,05 Mo";
            case "observatory" -> "743 Ko";
            case "everest" -> "194 Ko";
            default -> "?";
        };
    }

    public static void execute(ServerLevel level, Player player, int id) {
        if (level == null) return;
        BlockPos origin = player != null ? player.blockPosition() : new BlockPos(0, 64, 0);
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] execute() ID={}, origin={}", id, origin.toShortString());
        // 🏆 Achievement "structure spawnée" : chaque structure a le sien + 2 blocs.
        // ⛔ T227 — retour « wtf ? j'ai eu l'achievement de la citadel, mais rien
        //    a spawn pour le moment ! ». L'achievement partait ICI, au moment du
        //    TIRAGE, alors que la pose arrive bien plus tard (file d'attente +
        //    chargement NBT + terrain). Dans ton log : achievement a 18:55:15,
        //    structure a 18:57:28 -> 2 min 13 d'ecart.
        //    Il est desormais accorde en meme temps que « STRUCTURE DISCOVERED ».
        // T228 : le contexte (joueur/origine de l'achievement + pose de test)
        //    voyage AVEC la demande. L'ancienne version ecrivait ici les globaux
        //    meme quand la structure partait en file : une seconde demande
        //    ecrasait l'achievement de la generation en cours, puis le sien
        //    n'etait jamais accorde. Les globaux ne sont poses qu'au demarrage
        //    reel (chemin immediat ci-dessous, ou startNextPending a la sortie
        //    de file). Le drapeau /dlbtest est consomme immediatement pour
        //    eviter qu'une commande suivante ne le lise en double.
        final boolean manualTest = MANUAL_TEST_SPAWN;
        MANUAL_TEST_SPAWN = false;

        // Deblocage de securite : une generation qui n'a jamais signale sa fin
        // ne doit pas geler la file pour toujours.
        if (GENERATION_BUSY && System.currentTimeMillis() - GENERATION_START_MS > GENERATION_TIMEOUT_MS) {
            LOGGER.warn("[STRUCT5] Generation precedente sans fin signalee apres {} s -- file debloquee",
                    GENERATION_TIMEOUT_MS / 1000);
            // T226 : la precedente n'a jamais abouti -> elle redevient tirable.
            GenerateLuckyItemProcedure.releaseStructureReservation(CURRENT_STRUCTURE_ID);
            CURRENT_STRUCTURE_ID = -1;
            GENERATION_BUSY = false;
            // T228 : nettoyer aussi le contexte de la generation perimee.
            PENDING_ACHIEVEMENT_ORIGIN = null;
            PENDING_ACHIEVEMENT_PLAYER = null;
            CURRENT_MANUAL_TEST = false;
        }

        if (GENERATION_BUSY) {
            PENDING_STRUCTURES.add(new PendingStructure(id, origin, level, player, manualTest));
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] Une generation est deja en cours : ID={} mis en file ({} en attente)",
                    id, PENDING_STRUCTURES.size());
            if (player != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§e⏳ Structure mise en file d'attente (" + PENDING_STRUCTURES.size()
                        + " avant elle) — elle apparaîtra dès que la précédente sera terminée."));
            }
            return;
        }
        // FIX T7 : ne JAMAIS parser un gros NBT sur le thread serveur en plein
        // jeu. Si la structure n'est pas encore en memoire, on la met en tete de
        // file et on laisse le thread de fond la charger (voir startNextPending
        // + StructureTemplateCache.requestAsync).
        String nbtName = nbtNameForId(id);
        if (!templateReady(nbtName)) {
            PENDING_STRUCTURES.addFirst(new PendingStructure(id, origin, level, player, manualTest));
            deepluckyblock.util.StructureTemplateCache.requestAsync(level, nbtName);
            DEFER_WAIT_TICKS = 0;
            startNextPending(level);
            return;
        }
        // T228 : demarrage immediat -> le contexte accompagne CETTE generation.
        PENDING_ACHIEVEMENT_ORIGIN = origin;
        PENDING_ACHIEVEMENT_PLAYER = player;
        CURRENT_MANUAL_TEST = manualTest;
        beginGeneration(level, origin, id);
    }

    private static void beginGeneration(ServerLevel level, BlockPos origin, int id) {
        GENERATION_BUSY = true;
        CURRENT_STRUCTURE_ID = id;
        GENERATION_START_MS = System.currentTimeMillis();
        FOOTPRINT_LOAD_RETRIES = 0;
        REJECTED_RUGGED_CENTERS.clear();
        SEARCH_EXTRA_RADIUS = 0;
        SEARCH_SCANNED_RADIUS = -1;
        SEARCH_PROGRESS_LAST_MS = 0L;
        DEFERRED_WAIT_LAST_MS = 0L;
        SEARCH_GENERATED_FALLBACK = null;
        SEARCH_FALLBACK_LAST_TARGET = null;   // T232-F
        SEARCH_STALL_START_MS = 0L;           // T232-F
        SEARCH_FALLBACK_WAIT_TICKS = 0;
        SEARCH_FALLBACK_STALL_TICKS = 0;
        SEARCH_DEFERRED.clear();
        SEARCH_DEFERRED_KEYS.clear();
        SEARCH_DEFERRED_ROUNDS.clear();
        SEARCH_DRY_REJECTS = 0;   // T233
        SEARCH_BEST_LOADED_DRY = null;
        SEARCH_BEST_LOADED_DRY_RELIEF = Integer.MAX_VALUE;
        PENDING_FLAT_CENTER = null;
        SEARCH_ABORTED = false;
        switch (id) {
            case 0, 1, 2, 16, 17, 18 -> generate(level, origin, id, level.getRandom());
            // T228 : un false = echec AVANT tout travail (template absent, zone
            // impossible...) ; sans abort explicite, la file restait verrouillee
            // jusqu'au contournement de 240 s. Les chemins asynchrones gardent
            // leur propre notification (finish/aborted) en fin de pipeline.
            case 3 -> { if (!Structures4Procedure.spawnCircus(level, origin)) notifyGenerationAborted(level); }
            case 4 -> { if (!Structures4Procedure.spawnShipdead(level, origin)) notifyGenerationAborted(level); }
            case 5 -> { if (!Structures4Procedure.spawnEverest(level, origin)) notifyGenerationAborted(level); }
            default -> {
                LOGGER.warn("[STRUCT5] ID inconnu dans execute : {}", id);
                GenerateLuckyItemProcedure.releaseStructureReservation(id);
                CURRENT_STRUCTURE_ID = -1;
                GENERATION_BUSY = false;
            }
        }
    }

    /** Mappe l'id de structure vers la cle d'achievement et accorde l'achievement + 2 blocs. */
    private static void grantStructureSpawnAchievement(ServerLevel level, BlockPos origin, Player player, int id) {
        String key = switch (id) {
            case 0, 16 -> "citadel";
            case 1, 17 -> "observatory";
            case 2, 18 -> "dragon";
            case 3 -> "circus";
            case 4 -> "ship";
            case 5 -> "everest";
            default -> null;
        };
        if (key == null) return;
        ServerPlayer target = player instanceof ServerPlayer sp ? sp : null;
        if (target == null) {
            Player near = level.getNearestPlayer(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5, 128.0, false);
            if (near instanceof ServerPlayer sp2) target = sp2;
        }
        if (target != null) {
            DeepLuckyBlockAdvancements.grantStructureSpawned(target, key);
        }
    }

    public static void buildSylvanCitadel(ServerLevel l, BlockPos o, RandomSource r) { generate(l, o, 0, r); }
    public static void buildFaeObservatory(ServerLevel l, BlockPos o, RandomSource r) { generate(l, o, 1, r); }
    public static void buildVerdantDragonSanctum(ServerLevel l, BlockPos o, RandomSource r) { generate(l, o, 2, r); }

    private static Rotation facePlayer(BlockPos sp, Player p, Direction nf) {
        if (p == null) return Rotation.NONE;
        double dx = p.getX() - sp.getX(), dz = p.getZ() - sp.getZ();
        Direction toP = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        int steps = (dSteps(toP) - dSteps(nf) + 4) % 4;
        return switch (steps) { case 1 -> Rotation.CLOCKWISE_90; case 2 -> Rotation.CLOCKWISE_180; case 3 -> Rotation.COUNTERCLOCKWISE_90; default -> Rotation.NONE; };
    }

    /**
     * Orientation finale du paste.
     *
     * FIX (demande en jeu : "pense bien a quand tu paste faire parfois des
     * rotations pour pas que ca se voie que tout ait ete paste, parfois vers
     * l'est, parfois tourne le vers le sud ouest, etc").
     *
     * Jusqu'ici l'orientation etait TOUJOURS facePlayer() : la facade regardait
     * systematiquement le joueur, donc deux generations de la meme structure
     * donnaient exactement la meme image -- l'effet "copier/coller" signale.
     *
     * Desormais on ne garde facePlayer que dans ~40% des cas (l'entree reste
     * souvent accueillante, ce qui est le comportement voulu a l'origine), et
     * sinon on tire une rotation au hasard parmi les 4 rotations vanilla. Les
     * templates .nbt ne peuvent pas etre tournes d'un angle libre -- Rotation
     * est une enumeration a 4 valeurs cote Minecraft -- mais le DECOR
     * environnant (StructureScatterDecor), lui, est genere mathematiquement et
     * utilise de vraies rotations libres sur 360 degres, y compris sud-ouest.
     * La combinaison des deux casse la repetition.
     */
    private static Rotation pickRotation(BlockPos sp, Player p, Direction nf, RandomSource random) {
        if (random != null && random.nextInt(100) >= 40) {
            return switch (random.nextInt(4)) {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                case 3 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };
        }
        return facePlayer(sp, p, nf);
    }
    private static int dSteps(Direction d) { return switch (d) { case SOUTH -> 0; case WEST -> 1; case NORTH -> 2; case EAST -> 3; default -> 0; }; }

    private static Set<Long> detectIntAir(List<StructureTemplate.StructureBlockInfo> blocks, Vec3i size) {
        int sx = size.getX(), sy = size.getY(), sz = size.getZ();
        boolean[][][] solid = new boolean[sx][sy][sz];
        for (var i : blocks) { BlockPos p = i.pos(); int x = p.getX(), y = p.getY(), z = p.getZ();
            if (x >= 0 && x < sx && y >= 0 && y < sy && z >= 0 && z < sz) solid[x][y][z] = !i.state().isAir(); }
        boolean[][][] ext = new boolean[sx][sy][sz]; ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int x = 0; x < sx; x++) for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++)
            if ((x == 0 || x == sx - 1 || y == 0 || y == sy - 1 || z == 0 || z == sz - 1) && !solid[x][y][z]) { ext[x][y][z] = true; q.add(new int[]{x, y, z}); }
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (!q.isEmpty()) { int[] c = q.poll(); for (int[] d : dirs) { int nx = c[0]+d[0], ny = c[1]+d[1], nz = c[2]+d[2];
            if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) continue; if (ext[nx][ny][nz] || solid[nx][ny][nz]) continue;
            ext[nx][ny][nz] = true; q.add(new int[]{nx, ny, nz}); } }
        Set<Long> r = new HashSet<>();
        for (int x = 0; x < sx; x++) for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) if (!solid[x][y][z] && !ext[x][y][z]) r.add(enc(x, y, z));
        return r;
    }
    private static long enc(int x, int y, int z) { return ((long)(x & 0x7FF) << 22) | ((long)(y & 0x7FF) << 11) | (long)(z & 0x7FF); }

    public static boolean isPlayerChunk(BlockPos candidate, Player player, BlockPos origin) {
        int cx = candidate.getX() >> 4, cz = candidate.getZ() >> 4, ox = origin.getX() >> 4, oz = origin.getZ() >> 4;
        if (cx == ox && cz == oz) return true;
        if (player != null && cx == (player.blockPosition().getX() >> 4) && cz == (player.blockPosition().getZ() >> 4)) return true;
        return false;
    }

    public static BlockPos findSafeOutsideChunkPos(ServerLevel level, BlockPos origin) {
        int[][] offsets = {{32,0},{-32,0},{0,32},{0,-32},{32,32},{-32,-32},{32,-32},{-32,32}};
        int wetSkipped = 0;
        for (int[] off : offsets) {
            int cx = origin.getX() + off[0], cz = origin.getZ() + off[1];
            // FIX T7 : hauteur lue sur un chunk REELLEMENT genere (voir SafeSurface).
            // Avant, cette lecture renvoyait -65 (heightmap non initialisee) et
            // c'est CE point precis qui ancrait observatory a Y=-78 et everest a Y=-90.
            int cy = deepluckyblock.util.SafeSurface.surfaceY(level, cx, cz, origin.getY());
            BlockPos cand = new BlockPos(cx, cy, cz);
            // T80 : jamais d'ancrage hors-chunk sous l'eau (le Y heightmap serait le
            // FOND) : l'offset humide est saute, le prochain est essaye.
            if (deepluckyblock.util.SafeSurface.columnWet(level, cx, cz)) { wetSkipped++; continue; }
            if (!isPlayerChunk(cand, null, origin)) return cand;
        }
        if (wetSkipped > 0)
            LOGGER.warn("[STRUCT5-FLAT] findSafeOutsideChunkPos : {} offset(s) sous l'eau ecartes (T80)", wetSkipped);
        return origin.offset(32, 0, 32);
    }

    private static BlockPos giveUpFallback(ServerLevel lvl, BlockPos origin) {
        int jumps = SEARCH_FALLBACK_JUMPS;
        SEARCH_GENERATED_FALLBACK  = null;
        SEARCH_FALLBACK_WAIT_TICKS = 0;
        SEARCH_FALLBACK_JUMPS      = 0;
        BlockPos best = SEARCH_BEST_LOADED_DRY;
        if (best != null) {
            int by = deepluckyblock.util.SafeSurface.surfaceY(lvl, best.getX(), best.getZ(), origin.getY());
            LOGGER.warn("[STRUCT5-FLAT] secours abandonne apres {} sauts (plafond {}); repli sur terrain deja charge a {},{} (Y={})",
                    jumps, MAX_FALLBACK_JUMPS, best.getX(), best.getZ(), by);
            SEARCH_STALL_START_MS = 0L;   // T232-F : terrain trouve -> fin du regime d'attente
            return new BlockPos(best.getX(), by, best.getZ());
        }
        // T231 (CI session serveur, monde dedie neuf) : "generation annulee" quand
        // RIEN de sec n'est deja charge cassait la suite des structures (et le
        // run T229 du dev aurait perdu la structure n'importe ou hors zone
        // pionniere). Le secours cible tient des requetes en vol dans le fond
        // executor : au lieu d'abandonner, on REARM le compteur de sauts et on
        // relance la meme emprise; la borne globale reste GENERATION_TIMEOUT_MS
        // cote doPaste (un timeout structure y est deja journalise).
        {
            // T232-F1 : l'ancien code lisait SEARCH_GENERATED_FALLBACK APRES
            // l'avoir mis a null au sommet de cette methode : ce bloc de
            // rearmement T231 etait donc TOTJOURS inerte (chemin mort). On
            // capture l'emprise AVANT la remise a zero pour que le rearmement
            // fonctionne reellement.
            BlockPos g2 = null;
            if (SEARCH_FALLBACK_LAST_TARGET != null) g2 = SEARCH_FALLBACK_LAST_TARGET;
            else if (SEARCH_GENERATED_FALLBACK != null) g2 = SEARCH_GENERATED_FALLBACK;
            if (g2 != null) {
                // T232-F2 : borne horaire sur les rearmements successifs. Sans
                // elle, un monde hostile (lacs/montagnes en continu) relance le
                // cycle de 8 sauts indefiniment entre giveUpFallback et le
                // rearmement T231 -- l'attente devenait alors infinie (reproduit
                // en labo : ~10 min sans aucune issue). Au-dela du delai
                // configurable on leve le drapeau SEARCH_ABORTED (deja prevu
                // pour ce cas en T228 mais jamais positionne) afin que le
                // pipeline aborte une seule fois et libere la file.
                long nowMs = System.currentTimeMillis();
                if (SEARCH_STALL_START_MS == 0L) SEARCH_STALL_START_MS = nowMs;
                if (nowMs - SEARCH_STALL_START_MS > SEARCH_STALL_TIMEOUT_MS) {
                    SEARCH_ABORTED = true;
                    SEARCH_STALL_START_MS = 0L;
                    SEARCH_FALLBACK_LAST_TARGET = null;
                    LOGGER.warn("[STRUCT5-FLAT] ABANDON de secours : aucune emprise livrable apres {} s de rearmements (borne dlb.search.stallTimeoutMs). Pose interrompue proprement.",
                            SEARCH_STALL_TIMEOUT_MS / 1000);
                    return null;
                }
                SEARCH_GENERATED_FALLBACK = g2;
                SEARCH_FALLBACK_WAIT_TICKS = 0;
                SEARCH_FALLBACK_STALL_TICKS = 0;
                deepluckyblock.util.SafeSurface.requestZone(lvl,
                        (g2.getX() - 96) >> 4, (g2.getZ() - 96) >> 4,
                        (g2.getX() + 96) >> 4, (g2.getZ() + 96) >> 4);
                deepluckyblock.util.ChunkKeeper.track(lvl,
                        g2.offset(-96, 0, -96), g2.offset(96, 0, 96));
                LOGGER.warn("[STRUCT5-FLAT] plafond de sauts atteint sans terrain sec charge : rearmement T231 du secours cible a {},{} (T229 annulait la structure) -- reste {} s avant abandon",
                        g2.getX(), g2.getZ(),
                        Math.max(0L, (SEARCH_STALL_TIMEOUT_MS - (nowMs - SEARCH_STALL_START_MS)) / 1000));
            }
            return null;
        }
    }

    private static BlockPos findFlat(ServerLevel lvl, BlockPos origin, int structW, int structD, int minEdgeDist) {
        Player p = lvl.getNearestPlayer(origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5, 128, false);
        double lx = 0, lz = 0; BlockPos pp = origin;
        if (p != null) { float yaw = p.getYRot(); lx = -Math.sin(Math.toRadians(yaw)); lz = Math.cos(Math.toRadians(yaw)); pp = p.blockPosition(); }
        // T182 : le centre des cercles est la position réelle du joueur au
        // moment de la commande, jamais le bloc visé transmis par /dlbtest.
        final BlockPos searchOrigin = pp;
        // T162 : zone de détection = 60 % de la largeur/profondeur du modèle,
        // donc rayon = 30 % de sa plus grande dimension (et non 50 %).
        int checkR = Math.max(8, (int) Math.ceil(Math.max(structW, structD) * 0.30));
        // T176 : le centre x0,6 décide du relief, mais l'emprise entière doit
        // être sèche. Le précédent contrôle ne testait ici que le carré x0,6 :
        // STRUCT5-GROUND découvrait ensuite 3 à 344 colonnes d'eau sur les bords
        // et relançait tout le scoring.
        int fullCheckR = Math.max(checkR, (int) Math.ceil(Math.max(structW, structD) * 0.50));

        // T230 : re-verifier les candidats differes a CHAQUE reprise, pas
        // seulement quand une couronne est epuisee. Le blocage T229 gerait une
        // couronne par ~7,5 s en monde neuf (2 min 25 pour dragon) ; les
        // couronnes continuent maintenant d'avancer pendant que les zones
        // demandees se chargent en tache de fond. Seul le secours lointain
        // (plus bas) attend encore la fin des differes proches.
        {
            BlockPos choisi = drainDeferred(lvl, checkR, fullCheckR);
            if (choisi != null) return choisi;
        }

        // T181 : après épuisement des régions déjà ouvertes, une seule emprise
        // de secours peut être générée. On attend ici sa disponibilité sans
        // rescanner le monde entier. Si son terrain réel échoue, elle avance
        // d'une largeur d'emprise et seule la suivante est demandée.
        if (SEARCH_GENERATED_FALLBACK != null) {
            BlockPos g = SEARCH_GENERATED_FALLBACK;
            FlatQuality q = qualityOnReady(lvl, g.getX(), g.getZ(), checkR);
            if (q == null) {
                SEARCH_FALLBACK_WAIT_TICKS++;
                // Une requête asynchrone peut être perdue/évincée : la renouveler
                // périodiquement, sans rappeler tout le scoring. Après 5 secondes,
                // avancer d'une emprise au lieu d'attendre éternellement.
                if (SEARCH_FALLBACK_WAIT_TICKS % 20 == 0) {
                    deepluckyblock.util.SafeSurface.requestZone(lvl,
                            (g.getX() - fullCheckR) >> 4, (g.getZ() - fullCheckR) >> 4,
                            (g.getX() + fullCheckR) >> 4, (g.getZ() + fullCheckR) >> 4);
                }
                if (SEARCH_FALLBACK_WAIT_TICKS < 100) return null;
                if (++SEARCH_FALLBACK_JUMPS > MAX_FALLBACK_JUMPS) return giveUpFallback(lvl, origin);
                int jump = Math.max(structW, structD) + 64;
                SEARCH_GENERATED_FALLBACK = g.offset(jump, 0, jump);
                SEARCH_FALLBACK_LAST_TARGET = SEARCH_GENERATED_FALLBACK; // T232-F
                SEARCH_FALLBACK_LAST_TARGET = SEARCH_GENERATED_FALLBACK; // T232-F
                SEARCH_FALLBACK_WAIT_TICKS = 0;
                deepluckyblock.util.SafeSurface.requestZone(lvl,
                        (SEARCH_GENERATED_FALLBACK.getX() - fullCheckR) >> 4,
                        (SEARCH_GENERATED_FALLBACK.getZ() - fullCheckR) >> 4,
                        (SEARCH_GENERATED_FALLBACK.getX() + fullCheckR) >> 4,
                        (SEARCH_GENERATED_FALLBACK.getZ() + fullCheckR) >> 4);
                LOGGER.warn("[STRUCT5-FLAT] secours ciblé non livré après 5 s; déplacement à {},{}",
                        SEARCH_GENERATED_FALLBACK.getX(), SEARCH_GENERATED_FALLBACK.getZ());
                return null;
            }
            SEARCH_FALLBACK_WAIT_TICKS = 0;
            FootprintGround cg = detectExactGround(lvl,
                    g.getX() - checkR, g.getZ() - checkR, g.getX() + checkR, g.getZ() + checkR);
            FootprintGround cf = detectExactGround(lvl,
                    g.getX() - fullCheckR, g.getZ() - fullCheckR,
                    g.getX() + fullCheckR, g.getZ() + fullCheckR);
            if (cg == null || cf == null) {
                // T229 : la grille echantillonnee est prete mais l'emprise
                // exhaustive ne l'est pas (requete perdue, chunk evince,
                // trou entre deux mailles). L'ANCIEN code renvoyait null ici
                // SANS re-demander NI journaliser : boucle silencieuse et
                // definitive -- c'est exactement le blocage observe en jeu
                // (dragon jamais pose, zero log pendant 3 min). Meme discipline
                // que la branche d'attente ci-dessus : re-demande bornee,
                // journal, puis saut/abandon honnete.
                int stalls = ++SEARCH_FALLBACK_STALL_TICKS;
                if (stalls % 20 == 0) {
                    deepluckyblock.util.SafeSurface.requestZone(lvl,
                            (g.getX() - fullCheckR) >> 4, (g.getZ() - fullCheckR) >> 4,
                            (g.getX() + fullCheckR) >> 4, (g.getZ() + fullCheckR) >> 4);
                }
                if (stalls == 1 || stalls % 100 == 0) {
                    LOGGER.warn("[STRUCT5-FLAT] secours cible a {},{} : emprise incomplete ({} ticks), re-demande en cours (T229)",
                            g.getX(), g.getZ(), stalls);
                }
                if (stalls < 100) return null;
                SEARCH_FALLBACK_STALL_TICKS = 0;
                if (++SEARCH_FALLBACK_JUMPS > MAX_FALLBACK_JUMPS) return giveUpFallback(lvl, origin);
                int stalledJump = Math.max(structW, structD) + 64;
                SEARCH_GENERATED_FALLBACK = g.offset(stalledJump, 0, stalledJump);
                SEARCH_FALLBACK_LAST_TARGET = SEARCH_GENERATED_FALLBACK; // T232-F
                deepluckyblock.util.SafeSurface.requestZone(lvl,
                        (SEARCH_GENERATED_FALLBACK.getX() - fullCheckR) >> 4,
                        (SEARCH_GENERATED_FALLBACK.getZ() - fullCheckR) >> 4,
                        (SEARCH_GENERATED_FALLBACK.getX() + fullCheckR) >> 4,
                        (SEARCH_GENERATED_FALLBACK.getZ() + fullCheckR) >> 4);
                LOGGER.warn("[STRUCT5-FLAT] secours cible incomplet apres {} ticks; deplacement a {},{} (T229)",
                        stalls, SEARCH_GENERATED_FALLBACK.getX(), SEARCH_GENERATED_FALLBACK.getZ());
                return null;
            }
            SEARCH_FALLBACK_STALL_TICKS = 0;
            if (cg.wet() == 0 && cf.wet() == 0
                    && cg.maxY() - cg.minY() <= MAX_CLIFF_HEIGHT_DIFF) {
                int gy = deepluckyblock.util.SafeSurface.surfaceY(lvl, g.getX(), g.getZ(), origin.getY());
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] secours ciblé prêt à {},{} : terrain réel validé, Y={}",
                        g.getX(), g.getZ(), gy);
                // Le centre peut être dans un trou local. Le sol zéro est le
                // sommet exhaustif de toute l'emprise x0,6, jamais ce creux.
                SEARCH_STALL_START_MS = 0L;   // T232-F : secours pret -> fin du regime d'attente
                return new BlockPos(g.getX(), cg.maxY(), g.getZ());
            }
            int jump = Math.max(structW, structD) + 64;
            SEARCH_GENERATED_FALLBACK = g.offset(jump, 0, jump);
                SEARCH_FALLBACK_LAST_TARGET = SEARCH_GENERATED_FALLBACK; // T232-F
            int r = fullCheckR;
            deepluckyblock.util.ChunkKeeper.track(lvl,
                    SEARCH_GENERATED_FALLBACK.offset(-r, 0, -r),
                    SEARCH_GENERATED_FALLBACK.offset(r, 0, r));
            deepluckyblock.util.SafeSurface.requestZone(lvl,
                    (SEARCH_GENERATED_FALLBACK.getX() - r) >> 4,
                    (SEARCH_GENERATED_FALLBACK.getZ() - r) >> 4,
                    (SEARCH_GENERATED_FALLBACK.getX() + r) >> 4,
                    (SEARCH_GENERATED_FALLBACK.getZ() + r) >> 4);
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] secours ciblé précédent rejeté; une seule nouvelle emprise demandée à {},{}",
                    SEARCH_GENERATED_FALLBACK.getX(), SEARCH_GENERATED_FALLBACK.getZ());
            return null;
        }
        // minEdgeDist = distance minimum entre le BORD du build (footprint) et le joueur.
        // Le centre du candidat doit etre a minEdgeDist + checkR (demi-footprint) du
        // joueur pour que le bord du build respecte minEdgeDist. Sinon le build (et son
        // terrain modifie) se retrouve sur le joueur meme en montant la distance, parce
        // que le footprint est enorme et revient vers le joueur.
        int minCenterDist = minEdgeDist + checkR;
        // Cap large (300) : la plupart des candidats sont skippes par le check de
        // distance (pas de generation de chunk), donc chercher loin coute peu. Comme
        // ca findFlat trouve direct une zone plate assez loin du joueur, meme pour un
        // gros dist, sans tomber sur le findFarthestLoadedPos (fragile, demande des
        // chunks deja charges).
        // T174 : pas de plafond à 512 qui ferait rescanner éternellement le même
        // carré. L'expansion reste bornée à 4096 par SEARCH_EXTRA_RADIUS.
        int searchR = Math.min(Math.max(SEARCH_R, minCenterDist + 16) + SEARCH_EXTRA_RADIUS, 768);
        reportSearchProgress(searchR);

        // On scan TOUTE la zone large (radius SEARCH_R) et on score CHAQUE candidat
        // valide, au lieu de prendre la 1re zone assez plate. Score combine :
        //   - flatExtent : quelle fraction de la zone est plate (la PLUS GRANDE zone
        //     plate gagne). Evite les petites poches plates sur un flanc de montagne.
        //   - slope : ecart max de hauteur (la MOINS pentue gagne).
        // La direction du regard casse l'egalite (prefere devant le joueur a qualite
        // de terrain egale). On compare TOUT et on garde les MEILLEURS candidats
        // (liste triee, pas seulement le n°1 -- voir FIX CHUNKS FANTOMES ci-dessous).
        //
        // FIX CHUNKS FANTOMES (bug "giga montagne"/citadelle rapporte en jeu,
        // logs a l'appui : "flat=100%, pente=0" AU CHOIX, puis "deltaY anormal
        // apres smooth (-61 -> -6, brut=55)" au moment du paste reel) :
        // measureFlatQuality() n'interroge QUE getHeight() SANS jamais forcer la
        // generation des chunks du candidat. Sur un chunk pas encore genere a cet
        // instant, getHeight() peut renvoyer une estimation par defaut TOTALEMENT
        // fausse (souvent "plat"), alors que le terrain REEL, une fois genere par
        // smoothPass() (qui, lui, force la generation), s'avere etre une montagne
        // entiere. Le garde-fou existant (MAX_POST_SMOOTH_DELTA_Y) ne fait que
        // brider le repositionnement DE LA STRUCTURE, mais ne corrige pas le
        // terrain autour, qui lui suit la VRAIE hauteur -> un pilier/montagne
        // se reconstruit litteralement autour du batiment reste a l'ancienne
        // fausse hauteur plate.
        //
        // Correctif : on garde les N meilleurs candidats (mesure rapide, chunks
        // PAS forces), puis on force reellement la generation des chunks du
        // MEILLEUR candidat et on re-mesure avec le terrain REEL. Si l'ecart
        // avec la mesure initiale est trop important (mesure fantome demasquee),
        // on rejette ce candidat et on essaie le suivant de la liste, jusqu'a
        // en trouver un dont le terrain reel est bien plat.
        record FlatCandidate(int cx, int cz, double total, double extent, int slope) {}
        // ==================================================================
        // T59 : SCORING SUR GRILLE PARTAGEE (au lieu de ~186 000 lectures)
        // ==================================================================
        // AVANT : pour CHAQUE candidat (~2 000), on relisait 81 colonnes de terrain (9x9
        // sur +-checkR), soit ~160 000 lectures de heightmap. Mesure en bac a sable :
        // 28 a 36 s de gel AVANT l'ouverture de la fenetre d'edition (donc invisible dans
        // [DLB-PERF]), et deux arrets par watchdog. MAINTENANT : une seule lecture par
        // cellule de grille (un point tous les SEARCH_S blocs) sur toute la zone de
        // recherche, puis chaque candidat est note par simple arithmetique. Meme critere
        // (fraction plate + ecart de hauteur), meme nombre de candidats, ~40 fois moins de
        // lectures. Les 24 meilleurs candidats restent verifies colonne par colonne sur
        // terrain REEL (T49) : la qualite du choix final n'est pas degradee.
        final int gstep = SEARCH_S;
        final int gN = (searchR / gstep) * 2 + 1;
        final int[] gh = new int[gN * gN];
        final boolean[] gk = new boolean[gN * gN];
        long tGrid = System.currentTimeMillis();
        // T60 : chaque cellule est lue sur un chunk DEJA pret (registre), sans jamais attendre
        // une generation en vol : `getChunkNow` attendait jusqu'a 7,7 ms par lecture mesuree,
        // soit 17 s pour cette grille. Les cellules des chunks en cours de generation restent
        // "inconnues" (le classement s'appuie sur ce qui est deja la ; la verification T49
        // des 24 meilleurs candidats, elle, mesure le terrain REEL).
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        net.minecraft.world.level.chunk.ChunkAccess lastChunk = null;
        final boolean[] gw = new boolean[gN * gN];    // T75 : cellule dont la surface est un liquide
        final boolean[] wetCell = new boolean[1];
        for (int gi = 0; gi < gN; gi++) {
            for (int gj = 0; gj < gN; gj++) {
                int wx = searchOrigin.getX() + (gi - gN / 2) * gstep;
                int wz = searchOrigin.getZ() + (gj - gN / 2) * gstep;
                int cxx = wx >> 4, czz = wz >> 4;
                if (cxx != lastCx || czz != lastCz) {
                    lastCx = cxx; lastCz = czz;
                    lastChunk = deepluckyblock.util.SafeSurface.chunkFor(lvl, cxx, czz);
                }
                // T61 : balayage borne des blocs du chunk (aucune heightmap, aucune attente).
                wetCell[0] = false;
                int y = lastChunk == null ? Integer.MIN_VALUE
                        : deepluckyblock.util.SafeSurface.surfaceScanEx(lastChunk, lvl, wx, wz, wetCell);
                gh[gi * gN + gj] = y;
                gk[gi * gN + gj] = y > lvl.getMinBuildHeight();
                gw[gi * gN + gj] = gk[gi * gN + gj] && wetCell[0];   // T75
            }
        }
        int knownCells = 0;
        for (boolean b : gk) if (b) knownCells++;
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] grille de scoring : {}x{} cellules ({} connues) en {} ms -- T59/T62 "
                        + "({} recherche(s) de chunk, dont {} ayant attendu >2 ms, {} ms au total)",
                gN, gN, knownCells, System.currentTimeMillis() - tGrid,
                deepluckyblock.util.SafeSurface.diagChunkCalls,
                deepluckyblock.util.SafeSurface.diagChunkWaits,
                deepluckyblock.util.SafeSurface.diagChunkNs / 1_000_000L);
        final int gWin = Math.max(1, checkR / gstep);   // fenetre de mesure, en cellules de grille
        // T57 : instrumentation de la boucle de scoring (le gel de ~29 s avant l'ouverture de
        // la fenetre d'edition venait d'ici et n'apparaissait dans AUCUN log). Trois compteurs
        // de temps, publies a la fin de la boucle.
        long tPlayerMs = 0L, tSiteMs = 0L, tMeasureMs = 0L;
        boolean probe = Boolean.getBoolean("dlb.flatDebug");
        long tMark = System.currentTimeMillis();
        List<FlatCandidate> candidates = new ArrayList<>();
        int wetRanked = 0;   // T75 : candidats ecartes parce que leur surface est liquide (lac = plat)
        final int previouslyScanned = SEARCH_SCANNED_RADIUS;
        for (int dx = -searchR; dx <= searchR; dx += SEARCH_S) {
            for (int dz = -searchR; dz <= searchR; dz += SEARCH_S) {
                // T180 : à chaque reprise, classer uniquement la couronne qui
                // vient d'être ajoutée. Le run T179 rescannait le même carré à
                // chacun des 39 rayons et dépassait 11 millions d'accès chunks.
                if (previouslyScanned >= 0
                        && Math.max(Math.abs(dx), Math.abs(dz)) <= previouslyScanned) continue;
                int cx = searchOrigin.getX() + dx, cz = searchOrigin.getZ() + dz;
                if (REJECTED_RUGGED_CENTERS.contains(BlockPos.asLong(cx, 0, cz))) continue;
                // BUG CRITIQUE CORRIGE : closerThan() sur des BlockPos calcule une
                // distance 3D (X/Y/Z). L'ancien code comparait le VRAI Y du joueur
                // (ex. 150 en haut d'une montagne) a un Y FICTIF de 64 sur le
                // candidat -> l'ecart vertical inutile (86 blocs ici) suffisait a
                // lui seul a "faire semblant" d'etre loin, alors que le candidat
                // pouvait etre juste a cote du joueur en horizontal. Resultat :
                // en altitude, le filtre "pas trop pres du joueur" ne filtrait
                // presque plus rien, et la structure pouvait se retrouver juste
                // sous les pieds du joueur / au sommet de sa montagne au lieu
                // d'aller chercher une vraie zone plate ailleurs. On compare
                // maintenant uniquement la distance HORIZONTALE (X/Z), le Y du
                // joueur ou du candidat n'a plus aucune influence ici.
                if (p != null) {
                    double dpx = cx - p.blockPosition().getX(), dpz = cz - p.blockPosition().getZ();
                    if ((dpx * dpx + dpz * dpz) < (double) minCenterDist * minCenterDist) continue;
                }
                long _t0 = System.currentTimeMillis();
                boolean _isPlayer = isPlayerChunk(new BlockPos(cx, 64, cz), p, origin);
                tPlayerMs += System.currentTimeMillis() - _t0;
                if (probe && (System.currentTimeMillis() - tMark) > 1500) {
                    tMark = System.currentTimeMillis();
                    deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT-PROBE] en cours : {} candidat(s) retenus, {} ms joueur, {} ms emprises, {} ms mesures",
                            candidates.size(), tPlayerMs, tSiteMs, tMeasureMs);
                }
                if (_isPlayer) continue;
                // T27 : aucune structure ne doit arriver DANS une autre. Un candidat
                // qui chevauche l'emprise d'une structure deja posee est rejete,
                // exactement comme un chunk de joueur.
                long _t1 = System.currentTimeMillis();
                String clash1 = deepluckyblock.util.StructureSites.conflict(lvl, cx, cz, checkR);
                tSiteMs += System.currentTimeMillis() - _t1;
                if (clash1 != null) {
                    // T165 : filtrage d'emprise AVANT scoring. Ne plus lancer une
                    // spirale et deux conflits pour chacun des centaines de points
                    // occupés, et surtout ne plus écrire une ligne par candidat.
                    continue;
                }
                long _t2 = System.currentTimeMillis();
                // T59 : notation du candidat par arithmetique sur la GRILLE PARTAGEE (aucune
                // lecture de terrain ici) : c'est ce qui remplace les ~160 000 lectures.
                FlatQuality fq = qualityFromGrid(gh, gk, gw, gN, gstep, origin, cx, cz, gWin);
                tMeasureMs += System.currentTimeMillis() - _t2;
                if (fq == null) continue;
                // T75 : une zone en eau n'est jamais constructible, meme notee plate.
                if (fq.wetRatio() > FLAT_MAX_WET) { wetRanked++; continue; }
                double terrainScore = Math.max(0, fq.flatExtent() * 1000.0 - fq.slope() * 8.0);
                double loc = Math.max(0, Math.min(100, sc(cx, cz, pp, lx, lz)));
                double total = terrainScore + loc;
                candidates.add(new FlatCandidate(cx, cz, total, fq.flatExtent(), fq.slope()));
            }
        }
        // T177 : essayer d'abord les centres dont le chunk est déjà FULL en
        // mémoire (typiquement une zone déjà ouverte par le joueur), puis le
        // score terrain. Cela évite de préférer un excellent score qui impose la
        // génération d'une zone neuve à un bon site déjà disponible.
        candidates.sort((a, b) -> {
            boolean al = lvl.getChunkSource().getChunkNow(a.cx() >> 4, a.cz() >> 4) != null;
            boolean bl = lvl.getChunkSource().getChunkNow(b.cx() >> 4, b.cz() >> 4) != null;
            if (al != bl) return al ? -1 : 1;
            return Double.compare(b.total(), a.total());
        });
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] scoring termine : {} candidat(s) en {} ms (joueur {} ms, emprises {} ms, mesures terrain {} ms) -- T57",
                candidates.size(), tPlayerMs + tSiteMs + tMeasureMs, tPlayerMs, tSiteMs, tMeasureMs);
        if (wetRanked > 0)
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] {} candidat(s) ecarte(s) : surface en EAU (un lac est plat mais inconstructible) -- T75", wetRanked);
        if (SEARCH_DRY_REJECTS > 0)
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] prefiltre relief sec (T233) : {} candidat(s) ecarte(s) SANS generation de chunks (economie worldgen, porte 10x1,75+8)", SEARCH_DRY_REJECTS);
        // T33 : 12 -> 24 candidats verifies. Avec le decalage des emprises
        // chevauchantes (ci-dessus), la liste des candidats n'est plus videe par les
        // collisions et on a le droit d'aller chercher plus loin avant de tomber sur
        // le repli « le moins pentu ». Le budget de temps (FIND_FLAT_TIME_BUDGET_MS)
        // reste la vraie borne, donc aucun risque de gel.
        // Parcourir tous les candidats déjà mesurés : limiter aux 24 mieux notés
        // sélectionnait uniquement des zones lointaines encore non chargées.
        int maxVerified = candidates.size();
        // T49 : candidats differes faute de chunks en memoire (reexamines apres la boucle).
        java.util.List<FlatCandidate> pendingVerif = new java.util.ArrayList<>();
        // FIX (rapporte en jeu : "je casse un bloc, giga freeze infini ou plus
        // rien ne s'update mais moi je peux me deplacer, rien de visible dans
        // les logs" -- freeze SILENCIEUX, sans exception ni ligne de log
        // pendant le blocage) : CAUSE PROBABLE -- pour CHAQUE candidat verifie
        // (jusqu'a 12), la boucle ci-dessous appelle getChunk(..., FULL, true)
        // en SYNCHRONE sur TOUS les chunks d'un carre de checkR*2 autour du
        // candidat (jusqu'a ~150 chunks pour shipdead, 12 candidats -> jusqu'a
        // ~1700 generations de chunk COMPLETES sur le thread principal) AVANT
        // >>> T49 : ce comportement est SUPPRIME (aucun getChunk bloquant ici :
        // les candidats sans chunks en memoire partent dans pendingVerif et sont
        // reexamines apres la boucle). Le paragraphe ci-dessous est conserve
        // comme historique de l'incident.
        // qu'aucune ligne "[STRUCT5-FLAT] Zone choisie"/"REJETE" ne soit
        // loggee -- un monde avec un chunk generator lent (mods de biomes/
        // structures tiers, terrain tres complexe) peut donc bloquer le
        // serveur plusieurs dizaines de secondes SANS que rien n'apparaisse
        // dans les logs avant que le budget de 12 candidats soit epuise
        // (aucun log intermediaire existait). Fix : (1) log AVANT de forcer
        // la generation de chaque candidat, pour que le prochain freeze soit
        // enfin visible dans les logs meme s'il ne se termine jamais ; (2)
        // budget de temps global (FIND_FLAT_TIME_BUDGET_MS) qui interrompt la
        // verification des candidats restants des que depasse, retombant sur
        // le meilleur candidat deja verifie (ou sur le fallback pente
        // minimale) plutot que d'insister indefiniment sur des candidats de
        // moins en moins bons.
        long verifyStart = System.currentTimeMillis();
        int phantomRejected = 0;
        boolean verificationPaused = false;
        for (int i = 0; i < maxVerified; i++) {
            if (i > 0 && System.currentTimeMillis() - verifyStart > FIND_FLAT_TIME_BUDGET_MS) {
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] tranche {}ms terminée après {} candidat(s); {} restent dans CE MÊME cercle",
                        FIND_FLAT_TIME_BUDGET_MS, i, maxVerified - i);
                verificationPaused = true;
                break;
            }
            FlatCandidate c = candidates.get(i);
            // Lecture seule des chunks déjà FULL : aucune génération de candidat.
            // Force la generation reelle des chunks couverts par checkR autour du
            // candidat (memes limites que measureFlatQuality) avant de re-mesurer :
            // sans ce forçage, getHeight() peut rester fantome et le probleme ne
            // serait jamais detecte avant le paste reel.
            int ccx0 = (c.cx() - checkR) >> 4, ccx1 = (c.cx() + checkR) >> 4;
            int ccz0 = (c.cz() - checkR) >> 4, ccz1 = (c.cz() + checkR) >> 4;
            // T64 : verification sans attente (terrain reel des chunks DEJA prets). Avant, cet
            // appel forcait le chargement de toute la zone (getChunk bloquant avant T49, puis
            // attente implicite de la source apres T49) : 27 s de gel dans un seul tick.
            FlatQuality realFq = qualityOnReady(lvl, c.cx(), c.cz(), checkR);
            if (realFq == null) {
                // T185 (historique) : candidat consomme a vue, aucun chargement
                // declenche. Effet mesure en jeu (run dragon du 06/10, monde
                // neuf) : AUCUNE zone au-dela du spawn n'est FULL -> 100 % des
                // candidats sautes en ~2 s, la recherche « epuise les zones
                // historiques » et n'a plus que l'emprise generee a ~1000 blocs
                // (qui se bloquait ensuite en silence). Depassement de T185 :
                // demander la zone UNE FOIS en tache de fond et conserver le
                // candidat dans SEARCH_DEFERRED (borne MAX_DEFERRED : la derive
                // 97k/294k recherches reste impossible, T180 ne balayant que la
                // nouvelle couronne). Reverifie a chaque cercle epuise ; consomme
                // definitivement seulement apres MAX_DEFER_ROUNDS ou rejet mesure.
                long ck = BlockPos.asLong(c.cx(), 0, c.cz());
                // T233 : pre-jet sec AVANT d'enfiler la generation en fond.
                if (SEARCH_DRY_GATE
                        && dryRuggedSpan(lvl, c.cx(), c.cz(), fullCheckR) > SEARCH_DRY_GATE_SPAN) {
                    REJECTED_RUGGED_CENTERS.add(ck);
                    SEARCH_DRY_REJECTS++;
                    continue;
                }
                if (SEARCH_DEFERRED_KEYS.add(ck) && SEARCH_DEFERRED.size() < MAX_DEFERRED) {
                    SEARCH_DEFERRED.add(new BlockPos(c.cx(), 0, c.cz()));
                    deepluckyblock.util.SafeSurface.requestZone(lvl,
                            (c.cx() - fullCheckR) >> 4, (c.cz() - fullCheckR) >> 4,
                            (c.cx() + fullCheckR) >> 4, (c.cz() + fullCheckR) >> 4);
                } else {
                    REJECTED_RUGGED_CENTERS.add(ck);
                }
                continue;
            }
            // T75 : refus AVANT l'analyse de securite (qui declenchait sinon une
            // relocalisation aleatoire "Zone sure a ..." et un ancrage dans les airs).
            if (realFq.wetRatio() > FLAT_MAX_WET) {
                REJECTED_RUGGED_CENTERS.add(BlockPos.asLong(c.cx(), 0, c.cz()));
                continue;
            }
            // Un ecart de pente important entre la mesure "avant generation" et la
            // mesure "apres generation forcee" signale un chunk fantome demasque.
            // Tolerance large (2x FLAT_TOLERANCE) pour ne pas rejeter un terrain
            // legitimement a la limite -- seuls les VRAIS ecarts (montagnes
            // cachees) declenchent le rejet.
            boolean phantomDetected = Math.abs(realFq.slope() - c.slope()) > (FLAT_TOLERANCE * 2)
                    || realFq.flatExtent() < 0.5;
            if (phantomDetected) {
                // T190 : la mesure exacte ci-dessous est désormais l'autorité. Ne
                // pas jeter ici un terrain sec accidenté que les smooths peuvent
                // corriger; compter seulement le désaccord de la grille.
                phantomRejected++;
            }
            // T173 : même verdict exhaustif que STRUCT5-GROUND AVANT de déclarer
            // la zone choisie. Le contrôle échantillonné acceptait encore pente=26
            // et 50 % de plat, puis le contrôle réel rejetait la zone à chaque
            // reprise. Chaque colonne du carré central x0,6 est maintenant lue.
            FootprintGround candidateGround = detectExactGround(lvl,
                    c.cx() - checkR, c.cz() - checkR, c.cx() + checkR, c.cz() + checkR);
            if (candidateGround == null) {
                // Pas de requestZone ici : priorité stricte aux zones déjà
                // ouvertes dans le passé. Épuiser ce candidat une seule fois.
                REJECTED_RUGGED_CENTERS.add(BlockPos.asLong(c.cx(), 0, c.cz()));
                continue;
            }
            if (candidateGround.wet() > 0) {
                REJECTED_RUGGED_CENTERS.add(BlockPos.asLong(c.cx(), 0, c.cz()));
                phantomRejected++;
                continue;
            }
            // T184 : ne pas exiger ici que la couronne de smooth entière soit
            // FULL. L'emprise de construction, elle, reste strictement FULL.
            FootprintGround candidateFull = detectExactGround(lvl,
                    c.cx() - fullCheckR, c.cz() - fullCheckR,
                    c.cx() + fullCheckR, c.cz() + fullCheckR);
            if (candidateFull == null) {
                REJECTED_RUGGED_CENTERS.add(BlockPos.asLong(c.cx(), 0, c.cz()));
                continue;
            }
            if (candidateFull.wet() > 0) {
                REJECTED_RUGGED_CENTERS.add(BlockPos.asLong(c.cx(), 0, c.cz()));
                phantomRejected++;
                continue;
            }
            int exactRelief = candidateGround.maxY() - candidateGround.minY();
            if (exactRelief > MAX_CLIFF_HEIGHT_DIFF) {
                // T190 : ne plus perdre un site sec déjà disponible. On mémorise
                // le moins accidenté, puis on l'emploie avant tout secours généré.
                if (exactRelief < SEARCH_BEST_LOADED_DRY_RELIEF) {
                    SEARCH_BEST_LOADED_DRY_RELIEF = exactRelief;
                    SEARCH_BEST_LOADED_DRY = new BlockPos(c.cx(), candidateGround.maxY(), c.cz());
                }
                REJECTED_RUGGED_CENTERS.add(BlockPos.asLong(c.cx(), 0, c.cz()));
                phantomRejected++;
                continue;
            }
            int realY = deepluckyblock.util.SafeSurface.surfaceY(lvl, c.cx(), c.cz(), origin.getY());
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] Zone choisie a {},{} (candidat #{}/{}) : flat={}%, pente={} (score={}, terrain reel exhaustif x0,6, Y reel={}, {} candidat(s) rejetes, {} s de recherche)",
                    c.cx(), c.cz(), i + 1, maxVerified, (int)(realFq.flatExtent() * 100), realFq.slope(), (int) c.total(), realY, phantomRejected,
                    GENERATION_START_MS == 0L ? -1L : (System.currentTimeMillis() - GENERATION_START_MS) / 1000);
            return new BlockPos(c.cx(), candidateGround.maxY(), c.cz());
        }
        // T182 : une tranche courte ne signifie PAS « cercle épuisé ». On
        // reprend exactement le même rayon au tick suivant; les candidats déjà
        // invalidés sont dans REJECTED_RUGGED_CENTERS, donc le curseur logique
        // avance sans rescanner leur terrain exhaustif.
        if (verificationPaused) return null;

        // T49 : seconde passe sur les candidats differes (chunks demandes en tache
        // fond pendant la premiere passe). Beaucoup sont prets a ce stade.
        for (FlatCandidate c : pendingVerif) {
            if (System.currentTimeMillis() - verifyStart > FIND_FLAT_TIME_BUDGET_MS) break;
            int ccx0 = (c.cx() - checkR) >> 4, ccx1 = (c.cx() + checkR) >> 4;
            int ccz0 = (c.cz() - checkR) >> 4, ccz1 = (c.cz() + checkR) >> 4;
            FlatQuality realFq2 = qualityOnReady(lvl, c.cx(), c.cz(), checkR);   // T64 : sans attente
            if (realFq2 == null) continue;
            if (realFq2.wetRatio() > FLAT_MAX_WET) continue;   // T75 : zone en eau
            if (Math.abs(realFq2.slope() - c.slope()) > (FLAT_TOLERANCE * 2) || realFq2.flatExtent() < 0.5) continue;
            FootprintGround candidateGround2 = detectExactGround(lvl,
                    c.cx() - checkR, c.cz() - checkR, c.cx() + checkR, c.cz() + checkR);
            if (candidateGround2 == null || candidateGround2.wet() > 0
                    || candidateGround2.maxY() - candidateGround2.minY() > MAX_CLIFF_HEIGHT_DIFF) continue;
            FootprintGround candidateFull2 = detectExactGround(lvl,
                    c.cx() - fullCheckR, c.cz() - fullCheckR,
                    c.cx() + fullCheckR, c.cz() + fullCheckR);
            if (candidateFull2 == null || candidateFull2.wet() > 0) continue;
            int realY2 = deepluckyblock.util.SafeSurface.surfaceY(lvl, c.cx(), c.cz(), origin.getY());
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] Zone choisie a {},{} (seconde passe T49, terrain reel verifie, Y reel={}, {} s de recherche)",
                    c.cx(), c.cz(), realY2,
                    GENERATION_START_MS == 0L ? -1L : (System.currentTimeMillis() - GENERATION_START_MS) / 1000);
            return new BlockPos(c.cx(), candidateGround2.maxY(), c.cz());
        }
        // T230 : le drain tourne deja en tete de findFlat ; on ne bloque plus
        // la croissance du rayon sur les differes. Un candidat pret est pris
        // des qu'il l'est ; sinon la couronne suivante est scannee pendant
        // que sa zone se charge en tache de fond.
        {
            BlockPos choisi = drainDeferred(lvl, checkR, fullCheckR);
            if (choisi != null) return choisi;
        }
        // T174 : aucun fallback sur une pente "moins mauvaise" ni sur une
        // position hors chunk. Ces replis contournaient les critères finaux et
        // recréaient la même boucle. Agrandir la recherche est la seule issue.
        SEARCH_SCANNED_RADIUS = Math.max(SEARCH_SCANNED_RADIUS, searchR);
        // T230 : tant qu'un candidat differe (proche du joueur) attend encore
        // sa zone, on differe le secours lointain/historique. En monde neuf
        // ces candidats A DISTANCE NORMALE valident leur terrain au bout de
        // quelques secondes supplementaires ; le secours T190/T174 aurait
        // sinon envoye la structure hors de vue. L'attente reste bornee :
        // chaque differe est consomme apres MAX_DEFER_ROUNDS reprises.
        if (!SEARCH_DEFERRED.isEmpty()) {
            long nowWait = System.currentTimeMillis();
            if (nowWait - DEFERRED_WAIT_LAST_MS > 20_000) {
                DEFERRED_WAIT_LAST_MS = nowWait;
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] secours lointain differe : {} candidat(s) proche(s) encore en chargement (T230)",
                        SEARCH_DEFERRED.size());
            }
            return null;
        }
        if (SEARCH_EXTRA_RADIUS >= 512 && SEARCH_BEST_LOADED_DRY != null) {
            BlockPos fallback = SEARCH_BEST_LOADED_DRY;
            // T232-F3 : la porte STRUCT5-GROUND rejette ce candidat (relief x0,6 > max)
            // puis la recherche le RE-PROPOSE ici a chaque reprise : la boucle
            // "secours local charge choisi" -> "ecarte avant terrain" tournait
            // sans fin (obsee en labo t07/t0x). Si la porte l'a deja banni, on ne
            // le propose plus : on oublie ce "meilleur" terrain pour laisser la
            // place a un autre, et on continue vers le secours lointain borne.
            if (!REJECTED_RUGGED_CENTERS.contains(BlockPos.asLong(fallback.getX(), 0, fallback.getZ()))) {
                LOGGER.warn("[STRUCT5-FLAT] secours local chargé choisi à {},{} : relief brut {}, eau=0, génération distante évitée -- T190",
                        fallback.getX(), fallback.getZ(), SEARCH_BEST_LOADED_DRY_RELIEF);
                return fallback;
            }
            LOGGER.warn("[STRUCT5-FLAT] secours local {},{} deja banni par la porte relief (relief brut {}) -- on l'oublie et on passe au secours lointain (T232-F3)",
                    fallback.getX(), fallback.getZ(), SEARCH_BEST_LOADED_DRY_RELIEF);
            SEARCH_BEST_LOADED_DRY = null;
            SEARCH_BEST_LOADED_DRY_RELIEF = Integer.MAX_VALUE;
        }
        if (SEARCH_EXTRA_RADIUS >= 512) {
            double fx = Math.abs(lx) + Math.abs(lz) < 0.01 ? 1.0 : lx;
            double fz = Math.abs(lx) + Math.abs(lz) < 0.01 ? 0.0 : lz;
            int far = 768 + Math.max(structW, structD) + 64;
            SEARCH_GENERATED_FALLBACK = new BlockPos(
                    searchOrigin.getX() + (int) Math.round(fx * far), searchOrigin.getY(),
                    searchOrigin.getZ() + (int) Math.round(fz * far));
            SEARCH_FALLBACK_LAST_TARGET = SEARCH_GENERATED_FALLBACK;   // T232-F
            deepluckyblock.util.ChunkKeeper.track(lvl,
                    SEARCH_GENERATED_FALLBACK.offset(-fullCheckR, 0, -fullCheckR),
                    SEARCH_GENERATED_FALLBACK.offset(fullCheckR, 0, fullCheckR));
            deepluckyblock.util.SafeSurface.requestZone(lvl,
                    (SEARCH_GENERATED_FALLBACK.getX() - fullCheckR) >> 4,
                    (SEARCH_GENERATED_FALLBACK.getZ() - fullCheckR) >> 4,
                    (SEARCH_GENERATED_FALLBACK.getX() + fullCheckR) >> 4,
                    (SEARCH_GENERATED_FALLBACK.getZ() + fullCheckR) >> 4);
            LOGGER.warn("[STRUCT5-FLAT] zones historiques épuisées jusqu'à 768 blocs : génération ciblée d'une seule emprise à {},{}",
                    SEARCH_GENERATED_FALLBACK.getX(), SEARCH_GENERATED_FALLBACK.getZ());
            return null;
        }
        // T181 : expansion exponentielle. Avec +64 linéaire, atteindre une zone
        // historique à 2496 blocs demandait 39 reprises, même si chaque reprise
        // était courte. Comme T180 ne traite que la NOUVELLE couronne, doubler
        // le rayon ne répète aucun candidat et rejoint rapidement les anciennes
        // zones ouvertes : 64, 128, 256, 512, 1024, 2048, 4096.
        SEARCH_EXTRA_RADIUS = Math.min(
                SEARCH_EXTRA_RADIUS == 0 ? 64
                        : SEARCH_EXTRA_RADIUS + (SEARCH_GROWTH_MODE == 1 ? 64 : SEARCH_EXTRA_RADIUS),
                512);
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] aucun centre x0,6 conforme (relief <= {}, eau=0) -- nouvelle couronne jusqu'a {} blocs; reprise asynchrone",
                MAX_CLIFF_HEIGHT_DIFF, SEARCH_EXTRA_RADIUS);
        return null;
    }

    // T230 : drain des candidats differes (extrait du bloc T229). Invoque a
    // CHAQUE reprise de findFlat au lieu d'attendre l'epuisement d'une
    // couronne. Verdicts identiques a la boucle de verification (eau, relief
    // exhaustif x0,6, emprise entiere seche) ; un candidat dont la zone
    // n'arrive pas est consomme apres MAX_DEFER_ROUNDS reprises.
    private static BlockPos drainDeferred(ServerLevel lvl, int checkR, int fullCheckR) {
        if (SEARCH_DEFERRED.isEmpty()) return null;
        long drainT0 = System.currentTimeMillis();
        int drainReady = 0;
        for (int i = 0; i < SEARCH_DEFERRED.size(); i++) {
            if (i > 0 && System.currentTimeMillis() - drainT0 > FIND_FLAT_TIME_BUDGET_MS) break;
            BlockPos c = SEARCH_DEFERRED.get(i);
            long ck = BlockPos.asLong(c.getX(), 0, c.getZ());
            FlatQuality q = qualityOnReady(lvl, c.getX(), c.getZ(), checkR);
            FootprintGround dg = (q == null) ? null : detectExactGround(lvl,
                    c.getX() - checkR, c.getZ() - checkR, c.getX() + checkR, c.getZ() + checkR);
            FootprintGround df = (dg == null) ? null : detectExactGround(lvl,
                    c.getX() - fullCheckR, c.getZ() - fullCheckR,
                    c.getX() + fullCheckR, c.getZ() + fullCheckR);
            if (q == null || dg == null || df == null) {
                int rounds = SEARCH_DEFERRED_ROUNDS.merge(ck, 1, Integer::sum);
                if (rounds >= MAX_DEFER_ROUNDS) {
                    SEARCH_DEFERRED.remove(i); SEARCH_DEFERRED_KEYS.remove(ck);
                    SEARCH_DEFERRED_ROUNDS.remove(ck);
                    REJECTED_RUGGED_CENTERS.add(ck);
                    i--;
                } else if (rounds % 100 == 0) {
                    deepluckyblock.util.SafeSurface.requestZone(lvl,
                            (c.getX() - fullCheckR) >> 4, (c.getZ() - fullCheckR) >> 4,
                            (c.getX() + fullCheckR) >> 4, (c.getZ() + fullCheckR) >> 4);
                }
                continue;
            }
            SEARCH_DEFERRED.remove(i); SEARCH_DEFERRED_KEYS.remove(ck);
            SEARCH_DEFERRED_ROUNDS.remove(ck); i--;
            drainReady++;
            REJECTED_RUGGED_CENTERS.add(ck);
            if (q.wetRatio() > FLAT_MAX_WET || dg.wet() > 0 || df.wet() > 0) continue;
            int relief = dg.maxY() - dg.minY();
            if (relief > MAX_CLIFF_HEIGHT_DIFF) {
                if (relief < SEARCH_BEST_LOADED_DRY_RELIEF) {
                    SEARCH_BEST_LOADED_DRY_RELIEF = relief;
                    SEARCH_BEST_LOADED_DRY = new BlockPos(c.getX(), dg.maxY(), c.getZ());
                }
                continue;
            }
            LOGGER.info("[STRUCT5-FLAT] Zone choisie a {},{} (candidat differe pret apres chargement fond, relief={}, flat={}%, Y sol={}, {} s de recherche)",
                    c.getX(), c.getZ(), relief, (int) (q.flatExtent() * 100), dg.maxY(),
                    GENERATION_START_MS == 0L ? -1L : (System.currentTimeMillis() - GENERATION_START_MS) / 1000);
            return new BlockPos(c.getX(), dg.maxY(), c.getZ());
        }
        if (!SEARCH_DEFERRED.isEmpty() && drainReady == 0) {
            long now = System.currentTimeMillis();
            if (now - DRAIN_WAIT_LAST_MS > 20_000) {
                DRAIN_WAIT_LAST_MS = now;
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-FLAT] {} candidat(s) differes en attente de leur zone (fond)",
                        SEARCH_DEFERRED.size());
            }
        }
        return null;
    }

    // T230 : etat public de la recherche. Sans ca, dragon en monde neuf =
    // 2 min 25 muettes et le joueur redemandait "toujours rien ?" dans le
    // vide. Une ligne INFO toutes les 10 s des que la recherche depasse 10 s.
    private static void reportSearchProgress(int searchR) {
        if (GENERATION_START_MS == 0L) return;
        long now = System.currentTimeMillis();
        long elapsed = now - GENERATION_START_MS;
        if (elapsed < 10_000 || now - SEARCH_PROGRESS_LAST_MS < 10_000) return;
        SEARCH_PROGRESS_LAST_MS = now;
        LOGGER.info("[STRUCT5-FLAT] recherche toujours en cours : rayon {} blocs, {} candidat(s) differe(s) en chargement de fond, {} centre(s) ecarte(s), {} s ecoulees -- T230",
                searchR, SEARCH_DEFERRED.size(), REJECTED_RUGGED_CENTERS.size(), elapsed / 1000);
    }

    // Ecart max (en blocs) pour qu'une colonne compte comme "plate" par rapport au
    // centre du candidat. Sert a mesurer la TAILLE de la zone plate (flatExtent).
    private static final int FLAT_TOLERANCE = 4;

    // Qualite d'une zone candidate : slope (ecart max de hauteur) + flatExtent
    // (fraction des echantillons dans la tolerance = a quel point la zone plate est
    // GRANDE). null si le centre est air/eau/non-solide (candidat invalide).
    /** T75 : wetRatio = part des colonnes mesurees dont la SURFACE est un liquide. */
    private record FlatQuality(int slope, double flatExtent, double wetRatio) {}
    private record FootprintGround(int columns, int wet, int minY, int maxY) {}

    /** Détecteur de sol exact sous l'emprise réellement tournée, sans échantillonnage. */
    private static FootprintGround detectExactGround(ServerLevel level,
                                                      int x0, int z0, int x1, int z1) {
        int wet = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, columns = 0;
        boolean[] wetCell = new boolean[1];
        net.minecraft.world.level.chunk.ChunkAccess last = null;
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            int cx = x >> 4, cz = z >> 4;
            if (cx != lastCx || cz != lastCz) {
                lastCx = cx; lastCz = cz;
                last = deepluckyblock.util.SafeSurface.chunkFor(level, cx, cz);
            }
            if (last == null) return null;
            wetCell[0] = false;
            int y = deepluckyblock.util.SafeSurface.surfaceScanEx(last, level, x, z, wetCell);
            if (y == Integer.MIN_VALUE) return null;
            columns++;
            if (wetCell[0]) wet++;
            // y est le sol solide sous la colonne, y compris sous une nappe.
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return new FootprintGround(columns, wet, minY, maxY);
    }

    /**
     * T59 : note un candidat a partir de la GRILLE de hauteurs partagee, sans aucune lecture
     * de terrain. Reproduit exactement le critere de {@link #measureFlatQuality} (fraction de
     * colonnes a +-FLAT_TOLERANCE du centre, ecart max de hauteur), sur la fenetre de
     * +-checkR autour du candidat, echantillonnee au pas de la grille.
     *
     * @return null si le terrain est majoritairement inconnu (mesure non fiable)
     */
    private static FlatQuality qualityFromGrid(int[] gh, boolean[] gk, boolean[] gw, int gN, int gstep,
                                               BlockPos origin, int cx, int cz, int gWin) {
        int ci = (cx - origin.getX()) / gstep + gN / 2;
        int cj = (cz - origin.getZ()) / gstep + gN / 2;
        int i0 = Math.max(0, ci - gWin), i1 = Math.min(gN - 1, ci + gWin);
        int j0 = Math.max(0, cj - gWin), j1 = Math.min(gN - 1, cj + gWin);
        if (i0 > i1 || j0 > j1) return null;
        int centerIdx = ci * gN + cj;
        if (ci < 0 || ci >= gN || cj < 0 || cj >= gN || !gk[centerIdx]) return null;
        int centerY = gh[centerIdx];
        int total = 0, known = 0, flat = 0, wet = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = i0; i <= i1; i++) {
            int row = i * gN;
            for (int j = j0; j <= j1; j++) {
                total++;
                if (!gk[row + j]) continue;
                int y = gh[row + j];
                known++;
                // T75 : une colonne d'eau n'est PAS du terrain plat (sinon le lac
                // gagne le classement et la structure part a la baille).
                if (gw != null && gw[row + j]) { wet++; continue; }
                if (Math.abs(y - centerY) <= FLAT_TOLERANCE) flat++;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        // T60 : seuil abaisse a 30 % de cellules connues -- la verification des 24 meilleurs
        // candidats (mesure reelle, T49) reste le vrai filtre anti-terrain-fantome ; ici on ne
        // fait qu'un classement, et exiger 50 % de cellules pretes retardait le placement sur
        // une zone en cours de generation.
        if (known * 10 < total * 3) return null;
        return new FlatQuality(maxY - minY, known > 0 ? (double) flat / known : 0,
                known > 0 ? (double) wet / known : 0);
    }

    /**
     * T64 : mesure de qualite SANS ATTENTE -- relit le terrain REEL sur les chunks DEJA en
     * memoire (balayage borne des blocs, aucune heightmap, aucune demande de chargement).
     *
     * <p>POURQUOI : la verification des candidats utilisait `requestZone` + `zoneLoaded` +
     * `surfaceY`, qui passent par la source de chunks : quand un chunk de la zone etait en
     * cours de generation, l'appel ATTENDAIT (mesure : 27 s de gel dans un seul tick sur un
     * monde neuf). Ici on ne fait que LIRE ce qui est pret -- le "terrain fantome" reste
     * detecte (c'est le but : comparer la mesure de grille avec le terrain reel), mais sans
     * jamais bloquer. Les candidats dont la zone n'est pas prete sont differes (T49) et
     * reexamines apres la boucle, une fois les chunks arrives.
     *
     * @return null si le terrain est majoritairement inconnu
     */
    private static FlatQuality qualityOnReady(ServerLevel l, int cx, int cz, int radius) {
        int step = Math.max(1, radius / 4);
        int total = 0, known = 0, flat = 0, wet = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int centerY = 0;
        boolean centerSet = false;
        boolean[] wetCell = new boolean[1];   // T75
        net.minecraft.world.level.chunk.ChunkAccess last = null;
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int px = cx + dx, pz = cz + dz;
                int cxx = px >> 4, czz = pz >> 4;
                if (cxx != lastCx || czz != lastCz) {
                    lastCx = cxx; lastCz = czz;
                    last = deepluckyblock.util.SafeSurface.chunkFor(l, cxx, czz);
                }
                total++;
                if (last == null) continue;
                wetCell[0] = false;
                int y = deepluckyblock.util.SafeSurface.surfaceScanEx(last, l, px, pz, wetCell);
                if (y == Integer.MIN_VALUE) continue;
                known++;
                if (!centerSet) { centerY = y; centerSet = true; }
                if (wetCell[0]) { wet++; continue; }   // T75 : colonne d'eau
                if (Math.abs(y - centerY) <= FLAT_TOLERANCE) flat++;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (!centerSet) return null;
        // T159 : une décision de placement exige maintenant 100 % des points
        // réels. Le seuil historique de 30 % a validé 29 chunks inconnus : après
        // leur génération ils étaient un océan (run dragon T158), puis leur
        // génération synchrone a produit des gels de 3 à 13 secondes.
        if (known != total) return null;
        return new FlatQuality(maxY - minY, (double) flat / known, (double) wet / known);
    }

    private static FlatQuality measureFlatQuality(ServerLevel l, int cx, int cz, int radius) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int step = Math.max(1, radius / 4);
        int centerY = deepluckyblock.util.SafeSurface.height(l, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 1;
        // T56 : lecture d'etat NON BLOQUANTE. AVANT : `l.getBlockState(...)` sur un chunk non
        // charge declenchait sa generation SYNCHRONE ; appele pour ~2 300 candidats, c'etait le
        // gel de 28 s mesure AVANT l'ouverture de la fenetre d'edition (donc invisible dans
        // [DLB-PERF]). Un chunk absent rend null : le candidat n'est simplement pas evalue
        // ici, il sera verifie au moment du placement par la seconde passe T49.
        BlockState centerState = deepluckyblock.util.SafeSurface.state(l, cx, centerY, cz);
        if (centerState == null) return null;   // chunk pas encore en memoire : pas de mesure fantome
        if (centerState.isAir() || !centerState.getFluidState().isEmpty() || !centerState.blocksMotion()) return null;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, total = 0, flat = 0, known = 0;
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int px = cx + dx, pz = cz + dz;
                int y = deepluckyblock.util.SafeSurface.height(l, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz) - 1;
                total++;
                // T56 : une colonne dont le chunk n'est pas en memoire renvoie la sentinelle
                // (minBuildHeight) : la compter fausserait la mesure (fausse platitude
                // « flat=100 %, pente=0 » qui a deja fait choisir des montagnes). On ne
                // compte que les colonnes REELLES et on exige une majorite de colonnes reelles.
                if (y <= l.getMinBuildHeight()) continue;
                known++;
                if (Math.abs(y - centerY) <= FLAT_TOLERANCE) flat++;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
            }
        }
        if (known * 2 < total) return null;     // terrain majoritairement inconnu : on ne juge pas
        return new FlatQuality(maxY - minY, known > 0 ? (double) flat / known : 0, 0.0);
    }

    // Mesure de pente TOLERANTE : juste l'ecart max de hauteur, sans rejeter l'air
    // ni l'eau. Utilisee en tout dernier recours par findFlat quand aucun candidat
    // solide n'a ete trouve (tout air/eau, ex: ocean). On prend la pente minimale.
    private static int measureSlope(ServerLevel l, int cx, int cz, int radius) {
        int step = Math.max(1, radius / 3);
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int x = cx + dx, z = cz + dz;
                var chunk = l.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) continue;
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                if (y <= l.getMinBuildHeight()) continue;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        return minY > maxY ? Integer.MAX_VALUE : maxY - minY;
    }

    private static int sc(int cx, int cz, BlockPos pp, double lx, double lz) {
        int sc = 0; double dx = cx - pp.getX(), dz = cz - pp.getZ(), dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1) return 100;
        sc += Math.max(0, 50 - (int)(Math.abs(dist - 25) * 2));
        if (lx != 0 || lz != 0) { double dot = (dx * lx + dz * lz) / dist;
            if (dot > Math.cos(VIEW_CONE / 2)) sc += 100 + (int)(dot * 50); else if (dot > 0) sc += 30; else sc -= 20; }
        return sc;
    }

    private static boolean doPaste(ServerLevel level, BlockPos origin, String nbt, int ox, int oy, int oz,
                                   int dist, Direction nf, boolean followSurf, boolean useFlat, boolean keepInt) {
        long tGlobal = System.currentTimeMillis();
        // Ne pas imprimer cette bannière à chaque tick d'attente du secours.
        if (PENDING_FLAT_CENTER == null && SEARCH_GENERATED_FALLBACK == null)
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] === PASTE {} === off=[{},{},{}] dist={} facing={}", nbt, ox, oy, oz, dist, nf);
        int minChunksReq = isEverestNbt(nbt) ? MIN_CHUNKS_EVEREST : MIN_CHUNKS_NORMAL;
        // FIX (freeze serveur au chargement à froid d'un gros NBT, ex :
        // citadel.nbt ~6 Mo) : cache mod-wide partagé (StructureTemplateCache,
        // aussi préchargé au démarrage serveur) au lieu d'un appel direct au
        // StructureTemplateManager.
        StructureTemplate tmpl = deepluckyblock.util.StructureTemplateCache.get(level, nbt);
        if (tmpl == null) return false;
        Vec3i size = tmpl.getSize();
        Player pl = level.getNearestPlayer(origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5, 128, false);

        // Distance minimum entre le BORD du build et le joueur : max du global
        // (MIN_PLAYER_DISTANCE) et du parametre par structure (dist). findFlat l'utilise
        // pour que le footprint (pas seulement le centre) respecte la distance, sinon
        // un gros build revient jusqu'au joueur meme en montant la distance.
        int minEdgeDist = Math.max(MIN_PLAYER_DISTANCE, dist > 0 ? dist : 0);
        BlockPos txz = PENDING_FLAT_CENTER;
        if (txz == null) {
            if (useFlat) {
                BlockPos f = findFlat(level, origin, Math.abs(size.getX()), Math.abs(size.getZ()), minEdgeDist);
                if (f != null) {
                    txz = f;
                } else {
                    // T228 : le secours cible a epuise ses sauts -> abandon net,
                    // AUCUN retry : la boucle fantome abortait la structure
                    // suivante en boucle (voir giveUpFallback). Le false remonte
                    // a generate()/au wrapper planifie, qui aborte une seule fois.
                    if (SEARCH_ABORTED) return false;
                    // T174 : findFlat vient d'élargir son rayon. Ne jamais
                    // contourner son verdict avec findFarthest/findSafeOutside :
                    // reprendre plus tard, sans édition et sans abandon final.
                    final BlockPos retryOrigin = origin;
                    // T185 : reprendre au tick suivant. L'attente fixe de 10 ticks
                    // ajoutait 0,5 s après CHAQUE tranche et explique les 26 s
                    // entre commande et pose alors que le CPU de scoring restait
                    // inférieur à 100 ms par tranche.
                    deepluckyblock.procedures.TestProcedure.schedule(level,
                            deepluckyblock.procedures.TestProcedure.currentTick(level) + 1, () -> {
                        boolean ok = doPaste(level, retryOrigin, nbt, ox, oy, oz,
                                dist, nf, followSurf, true, keepInt);
                        if (!ok) notifyGenerationAborted(level);
                    });
                    return true;
                }
            } else if (dist > 0) {
                txz = findFarthestLoadedPos(level, origin, dist, minChunksReq); if (txz == null) return false;
            } else txz = findSafeOutsideChunkPos(level, origin);

            // T173 : un centre issu de findFlat est définitif. L'ancien
            // resolveSafePosition constatait ensuite une falaise et déplaçait le
            // centre de 10 blocs vers un point jamais scoré; STRUCT5-GROUND le
            // rejetait, puis la recherche recommençait indéfiniment (plus de
            // 307 000 recherches dans le run T172). findFlat effectue désormais
            // lui-même le contrôle exhaustif x0,6 ci-dessous.
            if (!useFlat) {
                BlockPos safePos = resolveSafePosition(level, txz, nbt);
                if (safePos == null) return false;
                txz = safePos;
            }
            if (isPlayerChunk(txz, pl, origin)) txz = findSafeOutsideChunkPos(level, origin);
            // T172 : findFlat a déjà éliminé les emprises en conflit avec
            // StructureSites. freeOffset déplaçait ensuite le centre validé de
            // 32 blocs vers un point NON scoré : le log T171 montre ainsi
            // 127 093 recherches, puis une suite sans fin « zone choisie ->
            // déplacement -> STRUCT5-GROUND rejeté ». Cela violait aussi la
            // règle fondamentale : le point trouvé est le centre EXACT de la
            // structure. Ne jamais déplacer le candidat après son contrôle.
            PENDING_FLAT_CENTER = txz;
        }

        // T143 : garde dure AVANT le premier setBlock. Le fallback de findFlat ne
        // doit jamais transformer un ocean en plateforme. Le run fautif avait
        // 7 846 colonnes d'eau sur 11 656 (67 %) et fixwater a ensuite parcouru
        // 95 303 cellules pendant 135 s. Si l'emprise reelle n'est pas entierement
        // verifiable ou depasse le seuil humide, on abandonne sans toucher au monde.
        int safetyRadius = Math.max(8, (int) Math.ceil(
                Math.max(Math.abs(size.getX()), Math.abs(size.getZ())) * 0.30));
        FlatQuality finalQuality = qualityOnReady(level, txz.getX(), txz.getZ(), safetyRadius);
        if (finalQuality == null) {
            int cx0 = (txz.getX() - safetyRadius) >> 4;
            int cx1 = (txz.getX() + safetyRadius) >> 4;
            int cz0 = (txz.getZ() - safetyRadius) >> 4;
            int cz1 = (txz.getZ() + safetyRadius) >> 4;
            ++FOOTPRINT_LOAD_RETRIES; // diagnostic uniquement : aucun abandon sauf borne T232
            // Le scoring ne charge volontairement rien. Une fois LE candidat
            // final choisi, demander uniquement son emprise puis reprendre sans
            // bloquer le thread serveur. T165 refusait ici et gardait la file
            // verrouillée pendant quatre minutes.
            deepluckyblock.util.SafeSurface.requestZone(level, cx0, cz0, cx1, cz1);
            // T232 : trois garde-fous additifs dans la phase d'attente :
            //  (1) PIN de l'emprise finale via ChunkKeeper + keep immediat : le
            //      ticker autonome de ChunkKeeper ne tournait que toutes les
            //      100 ticks (5 s) -- assez tard pour qu'un chunk charge
            //      soit deja decharge quand la zone est hors-portee joueur.
            //  (2) Souscription de zone SafeSurface (diagnostic + accord avec
            //      le pin : ses tickets ne sont plus retires sur ces chunks).
            //  (3) Borne temporelle de 10 min puis abandon PROPRE : la
            //      generation obtient une sortie visible au lieu d'une
            //      boucle silencieuse infinie (bug "rien ne spawn").
            deepluckyblock.util.ChunkKeeper.track(level,
                    new BlockPos(cx0 << 4, level.getMinBuildHeight(), cz0 << 4),
                    new BlockPos((cx1 << 4) + 15, level.getMaxBuildHeight() - 1, (cz1 << 4) + 15));
            deepluckyblock.util.ChunkKeeper.keep(level);
            if (FOOTPRINT_LOAD_RETRIES == 1) {
                SAFETY_AWAIT_START_MS = System.currentTimeMillis();
                final String planNbt = nbt;
                deepluckyblock.util.SafeSurface.replanZone(level, cx0, cz0, cx1, cz1,
                        () -> deepluckyblock.util.DebugLog.info(LOGGER,
                                "[STRUCT5-SAFETY] {} : emprise finale complete (plan zone, {} ms)",
                                planNbt, System.currentTimeMillis() - SAFETY_AWAIT_START_MS));
            }
            long awaitElapsed = SAFETY_AWAIT_START_MS == 0L ? 0L
                    : System.currentTimeMillis() - SAFETY_AWAIT_START_MS;
            if (awaitElapsed > SAFETY_AWAIT_TIMEOUT_MS) {
                int missed = deepluckyblock.util.SafeSurface.zonePlanMissing();
                LOGGER.warn("[STRUCT5-SAFETY] {} : emprise finale INCOMPLETE apres {} ms ({} chunk(s) manquant(s) "
                                + "sur {}x{}) -- ABANDON PROPRE de ce candidat : la file est liberee, au prochain "
                                + "/dlbtest le pipeline refera une nouvelle recherche. (T232 : borne contre la "
                                + "boucle silencieuse infinie reproduite en lab)",
                        nbt, awaitElapsed, missed, cx1 - cx0 + 1, cz1 - cz0 + 1);
                deepluckyblock.util.SafeSurface.dropZonePlan();
                deepluckyblock.util.ChunkKeeper.release(level);   // le pin ne survit pas a un abandon
                FOOTPRINT_LOAD_RETRIES = 0;
                SAFETY_AWAIT_START_MS = 0L;
                return false;   // l'appelant evalue + notifyGenerationAborted
            }
            // T229 : attente visible -- DebugLog etant desactive par defaut, le
            // joueur n'avait AUCUNE trace pendant ce chargement (silence total
            // constate en jeu). WARN borne : au debut puis toutes les 100 reprises.
            if (FOOTPRINT_LOAD_RETRIES == 1 || FOOTPRINT_LOAD_RETRIES % 100 == 0)
                LOGGER.warn("[STRUCT5-SAFETY] {} : chargement asynchrone de l'emprise finale (reprise {}/{}, "
                                + "plan-zone restant={} chunk(s), SafeSurface PENDING={}, capDrops={}, {} ms)",
                        nbt, FOOTPRINT_LOAD_RETRIES, MAX_FOOTPRINT_LOAD_RETRIES,
                        deepluckyblock.util.SafeSurface.zonePlanMissing(),
                        deepluckyblock.util.SafeSurface.pendingCount(),
                        deepluckyblock.util.SafeSurface.CAP_DROP_COUNT,
                        awaitElapsed);
            else if (FOOTPRINT_LOAD_RETRIES % 20 == 0)
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-SAFETY] {} : chargement asynchrone de l'emprise finale (reprise {}/{})",
                        nbt, FOOTPRINT_LOAD_RETRIES, MAX_FOOTPRINT_LOAD_RETRIES);
            final BlockPos retryOrigin = origin;
            deepluckyblock.procedures.TestProcedure.schedule(level,
                    deepluckyblock.procedures.TestProcedure.currentTick(level) + 20, () -> {
                boolean ok = doPaste(level, retryOrigin, nbt, ox, oy, oz, dist, nf, followSurf, useFlat, keepInt);
                if (!ok) notifyGenerationAborted(level);
            });
            return true; // accepté et différé : conserver GENERATION_BUSY
        }
        FOOTPRINT_LOAD_RETRIES = 0;
        SAFETY_AWAIT_START_MS = 0L;   // T232
        deepluckyblock.util.SafeSurface.dropZonePlan();
        if (finalQuality.wetRatio() > FLAT_MAX_WET) {
            REJECTED_RUGGED_CENTERS.add(BlockPos.asLong(txz.getX(), 0, txz.getZ()));
            PENDING_FLAT_CENTER = null;
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-SAFETY] {} candidat aquatique écarté avant terrain; recherche du suivant", nbt);
            final BlockPos retryOrigin = origin;
            deepluckyblock.procedures.TestProcedure.schedule(level,
                    deepluckyblock.procedures.TestProcedure.currentTick(level) + 10, () -> {
                boolean ok = doPaste(level, retryOrigin, nbt, ox, oy, oz, dist, nf, followSurf, useFlat, keepInt);
                if (!ok) notifyGenerationAborted(level);
            });
            return true;
        }
        // Meme famille de bug que dans findFlat() : distSqr() sur des BlockPos est
        // une distance 3D. Ici txz.getY() vient de resolveSafePosition() (le vrai
        // terrain a la zone plate choisie), qui peut etre tres different du Y reel
        // du joueur (ex. joueur en haut d'une montagne, zone plate en contrebas) :
        // l'ecart vertical, legitime, ne doit pas fausser ce garde-fou "pas trop
        // pres du joueur horizontalement". On ne compare que X/Z.
        if (pl != null && dist > 0) {
            double ddx = pl.blockPosition().getX() - txz.getX(), ddz = pl.blockPosition().getZ() - txz.getZ();
            if (ddx * ddx + ddz * ddz < 100) return false;
        }

        // FIX T7 : hauteur d'ancrage lue sur un chunk REELLEMENT genere.
        // Logs a l'appui : "observatory -> -368, -78, -400 (baseY=-65)" alors que le
        // sol reel a cet endroit est a Y=71. baseY=-65 etait la sentinelle
        // "heightmap non initialisee" (minBuildHeight) : la zone n'avait pas encore
        // ete generee a cet instant (le pre-chargement, lui, ne demarre qu'apres,
        // dans prepZone). La structure etait donc ancree 136 blocs sous le sol, et
        // le garde-fou MAX_POST_SMOOTH_DELTA_Y finissait de la figer la ou elle
        // etait au lieu de corriger l'erreur.
        // T75 : ANCRAGE = SOL SOLIDE de la colonne (ni eau, ni cime d'arbre).
        int baseY = followSurf
                ? deepluckyblock.util.SafeSurface.groundY(level, txz.getX(), txz.getZ(), origin.getY())
                : origin.getY();
        // Reject an invalid selection before any terrain edit. The clearance API
        // expects the solid ground block Y, never a model offset or a heightmap's free Y.
        if (baseY < level.getMinBuildHeight() || baseY >= level.getMaxBuildHeight()) {
            LOGGER.error("[STRUCT5] {} : selected groundY={} outside build height; placement refused", nbt, baseY);
            return false;
        }
        // T73 : liste COMPACTEE du cache (blocs reels + air interieur seulement).
        // L'air exterieur du .nbt (91 a 96 % des entrees de nos structures !) n'est
        // plus ni materialise, ni parcouru : il etait de toute facon rejete par le
        // filtre ci-dessous, mais il coutait des millions d'objets en memoire.
        List<StructureTemplate.StructureBlockInfo> raw = deepluckyblock.util.StructureTemplateCache.compactBlocks(nbt);
        if (raw == null) raw = exBlocks(tmpl);
        if (raw.isEmpty()) {
            BlockPos fp0 = new BlockPos(txz.getX() + ox, baseY + oy, txz.getZ() + oz);
            Rotation rot0 = facePlayer(fp0, pl, nf);
            BlockPos rp0 = adjRot(fp0, tmpl, rot0);
            BlockPos min0 = new BlockPos(Math.min(rp0.getX(), rp0.getX() + size.getX()), rp0.getY(), Math.min(rp0.getZ(), rp0.getZ() + size.getZ()));
            BlockPos max0 = min0.offset(Math.abs(size.getX()), size.getY(), Math.abs(size.getZ()));
            StructurePlaceSettings fb = new StructurePlaceSettings().setRotation(rot0).setMirror(Mirror.NONE).setIgnoreEntities(true).addProcessor(BlockIgnoreProcessor.AIR);
            tmpl.placeInWorld(level, rp0, rp0, fb, level.getRandom(), FAST_FLAG);
            // T39 : PostJob SANS terrain -- le repli « NBT vide » ne remodule pas le sol
            // apres la pose (le seul chemin qui ne fait aucun travail de terrain).
            POST_Q.offer(new PostJob(level, min0, max0, nbt));
            return true;
        }
        // Filtrer d'abord (verre/laggy supprimes), PUIS minRelY sur la liste filtree.
        // Sinon le verre en bas du NBT fausse minRelY et fait flotter la structure.
        // T73 : l'air interieur est calcule UNE FOIS au chargement du template
        // (thread de fond) et mis en cache, au lieu d'etre recalcule a chaque pose
        // (deux grilles booleennes sur tout le volume + parcours complet, sur le
        // thread serveur).
        Set<Long> intAir = isEverestNbt(nbt) ? Collections.emptySet()
                : deepluckyblock.util.StructureTemplateCache.interiorAir(nbt);
        if (intAir == null) intAir = isEverestNbt(nbt) ? Collections.emptySet() : detectIntAir(raw, size);
        // Niveau reel du sol du template, hors LIGHT et vignes techniques.
        // Le Y brut du template peut etre positif tout en etant enterre apres
        // l'application de minRelY : c'est pourquoi on compare a floorRelY.
        int floorRelY = Integer.MAX_VALUE;
        for (var info : raw) {
            BlockState candidate = info.state();
            if (candidate.isAir() || isTechnicalLightOrVine(candidate)) continue;
            floorRelY = Math.min(floorRelY, info.pos().getY());
        }
        if (floorRelY == Integer.MAX_VALUE) floorRelY = 0;
        // T75 : copie FINALE (floorRelY est modifie par la boucle ci-dessus, donc non
        // capturable par le lambda du pipeline ; celle-ci l'est).
        final int floorRelYFinal = floorRelY;

        List<StructureTemplate.StructureBlockInfo> filt = new ArrayList<>(raw.size());
        int glassSkipped = 0;
        for (var info : raw) {
            BlockState s = info.state();
            if (isLaggy(s)) continue;
            // Y relatif 1, 0 et negatif = sol et blocs enterres de la structure.
            // On retire seulement l'id light technique et les vignes vanilla.
            // Les LIGHT en positif restent intacts.
            if (isUndergroundTechnicalBlock(s, info.pos().getY() - floorRelY)) continue;
            if (isGlassBlock(s)) { glassSkipped++; continue; }
            if (s.isAir()) { if (!isEverestNbt(nbt) && intAir.contains(info.pos().asLong())) filt.add(info); }
            else filt.add(info);
        }
        // minRelY calcule sur la liste FILTREE (hors verre) pour que la base arrive
        // exactement a baseY+offset. Si le verre etait en bas, il n'est plus la et
        // ne fausse plus le calcul.
        int minRelY = 0;
        if (!filt.isEmpty()) { minRelY = Integer.MAX_VALUE; for (var b : filt) { int by = b.pos().getY(); if (by < minRelY) minRelY = by; } }
        BlockPos fp = new BlockPos(txz.getX() + ox, baseY + oy - minRelY, txz.getZ() + oz);
        // Stable pendant les reprises asynchrones : changer la rotation changeait
        // l'emprise demandée à chaque seconde et empêchait le chargement de finir.
        long rotationSeed = BlockPos.asLong(txz.getX(), 0, txz.getZ()) ^ (long) nbt.hashCode() * 0x9E3779B97F4A7C15L;
        Rotation rot = pickRotation(fp, pl, nf, RandomSource.create(rotationSeed));
        BlockPos rp = adjRot(fp, tmpl, rot);
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} -> {} (baseY={}, minRelY={}, rot={}, {}ms)", nbt, rp.toShortString(), baseY, minRelY, rot, System.currentTimeMillis() - tGlobal);
        // Bounding box EXACTE de la structure (positions réellement rotées) : le smooth
        // et le paste seront parfaitement alignés. Corrige le décalage (~1 bloc) ET le
        // swap X/Z des rotations 90/270 (rp+size était approximatif).
        StructurePlaceSettings bboxRs = new StructurePlaceSettings().setRotation(rot).setMirror(Mirror.NONE);
        int bMinX=Integer.MAX_VALUE, bMinY=Integer.MAX_VALUE, bMinZ=Integer.MAX_VALUE;
        int bMaxX=Integer.MIN_VALUE, bMaxY=Integer.MIN_VALUE, bMaxZ=Integer.MIN_VALUE;
        for (StructureTemplate.StructureBlockInfo info : filt) {
            BlockPos rel = StructureTemplate.calculateRelativePosition(bboxRs, info.pos());
            int bx=rp.getX()+rel.getX(), by=rp.getY()+rel.getY(), bz=rp.getZ()+rel.getZ();
            if (bx<bMinX)bMinX=bx; if(bx>bMaxX)bMaxX=bx;
            if (by<bMinY)bMinY=by; if(by>bMaxY)bMaxY=by;
            if (bz<bMinZ)bMinZ=bz; if(bz>bMaxZ)bMaxZ=bz;
        }
        // T168 : findFlat choisit le CENTRE de la future structure. Jusqu'ici txz
        // était utilisé comme origine de template (+ offset), ce qui plaçait la
        // zone plane sous un bord après rotation. Recentre la boîte réellement
        // tournée sur le candidat, avant tout contrôle ou travail terrain.
        int centerShiftX = txz.getX() - Math.floorDiv(bMinX + bMaxX, 2);
        int centerShiftZ = txz.getZ() - Math.floorDiv(bMinZ + bMaxZ, 2);
        if (centerShiftX != 0 || centerShiftZ != 0) {
            rp = rp.offset(centerShiftX, 0, centerShiftZ);
            bMinX += centerShiftX; bMaxX += centerShiftX;
            bMinZ += centerShiftZ; bMaxZ += centerShiftZ;
        }
        BlockPos min = new BlockPos(bMinX, bMinY, bMinZ);
        BlockPos max = new BlockPos(bMaxX, bMaxY, bMaxZ);

        // T162 : correction utilisateur : détecteur de SOL sur les 60 % centraux
        // de l'emprise réellement tournée (pas x1,5). L'eau est autorisée :
        // surfaceScanEx rend le sol solide situé sous cette eau.
        int footprintW = max.getX() - min.getX() + 1;
        int footprintD = max.getZ() - min.getZ() + 1;
        int trimX = Math.max(0, (int) Math.floor(footprintW * 0.20));
        int trimZ = Math.max(0, (int) Math.floor(footprintD * 0.20));
        int gx0 = min.getX() + trimX, gz0 = min.getZ() + trimZ;
        int gx1 = max.getX() - trimX, gz1 = max.getZ() - trimZ;
        FootprintGround exactGround = detectExactGround(level, gx0, gz0, gx1, gz1);
        if (exactGround == null) {
            deepluckyblock.util.SafeSurface.requestZone(level, gx0 >> 4, gz0 >> 4, gx1 >> 4, gz1 >> 4);
            // T232 : meme accord sanitaire que la SAFETY -- pin + keep immediat +
            // borne temporelle. Mesure en lab (queue) : citadel enchainerait
            // 470 reprises de 20 ticks silencieusement (roue de hamster).
            deepluckyblock.util.ChunkKeeper.track(level,
                    new BlockPos((gx0 >> 4) << 4, level.getMinBuildHeight(), (gz0 >> 4) << 4),
                    new BlockPos(((gx1 >> 4) << 4) + 15, level.getMaxBuildHeight() - 1, ((gz1 >> 4) << 4) + 15));
            deepluckyblock.util.ChunkKeeper.keep(level);
            if (SAFETY_AWAIT_START_MS == 0L) {
                SAFETY_AWAIT_START_MS = System.currentTimeMillis();
                final String planNbt2 = nbt;
                deepluckyblock.util.SafeSurface.replanZone(level, gx0 >> 4, gz0 >> 4, gx1 >> 4, gz1 >> 4,
                        () -> deepluckyblock.util.DebugLog.info(LOGGER,
                                "[STRUCT5-GROUND] {} : emprise complete (plan zone, {} ms)", planNbt2,
                                System.currentTimeMillis() - SAFETY_AWAIT_START_MS));
            }
            long awaitElapsed = System.currentTimeMillis() - SAFETY_AWAIT_START_MS;
            if (awaitElapsed > SAFETY_AWAIT_TIMEOUT_MS) {
                LOGGER.warn("[STRUCT5-GROUND] {} : emprise x0,6 INCOMPLETE apres {} ms -- ABANDON PROPRE (T232 borne)", nbt, awaitElapsed);
                deepluckyblock.util.SafeSurface.dropZonePlan();
                deepluckyblock.util.ChunkKeeper.release(level);
                SAFETY_AWAIT_START_MS = 0L;
                GROUND_WAIT_TICKS = 0;
                return false;
            }
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-GROUND] {} : centre x0,6 incomplet, chargement asynchrone puis reprise (aucun refus)", nbt);
            // T229 : reprises bornees a 200 ci-dessus ; rendre l'attente visible.
            if (++GROUND_WAIT_TICKS % 10 == 0)
                LOGGER.warn("[STRUCT5-GROUND] {} : controles de sol toujours en chargement ({} reprises de 20 ticks)", nbt, GROUND_WAIT_TICKS);
            final BlockPos retryOrigin = origin;
            deepluckyblock.procedures.TestProcedure.schedule(level,
                    deepluckyblock.procedures.TestProcedure.currentTick(level) + 20, () -> {
                boolean ok = doPaste(level, retryOrigin, nbt, ox, oy, oz, dist, nf, followSurf, useFlat, keepInt);
                if (!ok) notifyGenerationAborted(level);
            });
            return true;
        }
        // La zone x0,6 décide de la planéité; l'emprise ENTIÈRE décide si la
        // structure toucherait l'océan. Ainsi aucun bord ne peut finir dans l'eau.
        FootprintGround fullGround = detectExactGround(level, min.getX(), min.getZ(), max.getX(), max.getZ());
        if (fullGround == null) {
            deepluckyblock.util.SafeSurface.requestZone(level, min.getX() >> 4, min.getZ() >> 4,
                    max.getX() >> 4, max.getZ() >> 4);
            // T232 : idem branche exactGround -- pin + keep + borne (la boucle
            // « emprise entiere en chargement » n'avait AUCUNE garde : c'est la
            // qu'une file de plusieurs structures restait perdue pour toujours).
            deepluckyblock.util.ChunkKeeper.track(level,
                    new BlockPos((min.getX() >> 4) << 4, level.getMinBuildHeight(), (min.getZ() >> 4) << 4),
                    new BlockPos(((max.getX() >> 4) << 4) + 15, level.getMaxBuildHeight() - 1, ((max.getZ() >> 4) << 4) + 15));
            deepluckyblock.util.ChunkKeeper.keep(level);
            if (SAFETY_AWAIT_START_MS == 0L) {
                SAFETY_AWAIT_START_MS = System.currentTimeMillis();
            }
            long awaitElapsed = System.currentTimeMillis() - SAFETY_AWAIT_START_MS;
            if (awaitElapsed > SAFETY_AWAIT_TIMEOUT_MS) {
                LOGGER.warn("[STRUCT5-GROUND] {} : emprise entiere INCOMPLETE apres {} ms -- ABANDON PROPRE (T232 borne)", nbt, awaitElapsed);
                deepluckyblock.util.SafeSurface.dropZonePlan();
                deepluckyblock.util.ChunkKeeper.release(level);
                SAFETY_AWAIT_START_MS = 0L;
                GROUND_WAIT_TICKS = 0;
                return false;
            }
            // T229 : cette reprise n'avait AUCUN journal. WARN periodique borne.
            if (++GROUND_WAIT_TICKS % 10 == 0)
                LOGGER.warn("[STRUCT5-GROUND] {} : emprise entiere en chargement ({} reprises de 20 ticks)", nbt, GROUND_WAIT_TICKS);
            final BlockPos retryOrigin = origin;
            deepluckyblock.procedures.TestProcedure.schedule(level,
                    deepluckyblock.procedures.TestProcedure.currentTick(level) + 20, () -> {
                boolean ok = doPaste(level, retryOrigin, nbt, ox, oy, oz, dist, nf, followSurf, useFlat, keepInt);
                if (!ok) notifyGenerationAborted(level);
            });
            return true;
        }
        GROUND_WAIT_TICKS = 0;
        SAFETY_AWAIT_START_MS = 0L;   // T232 : la zone est complete, borne disarmee
        int exactRelief = exactGround.maxY() - exactGround.minY();
        if (exactRelief > MAX_CLIFF_HEIGHT_DIFF || exactGround.wet() > 0 || fullGround.wet() > 0) {
            // Aucun abandon : après chaque groupe de candidats invalides, élargir
            // réellement la recherche. Le prochain doPaste rescannera ce rayon.
            if (REJECTED_RUGGED_CENTERS.size() > 0 && REJECTED_RUGGED_CENTERS.size() % 16 == 0) {
                SEARCH_EXTRA_RADIUS = Math.min(SEARCH_EXTRA_RADIUS + 64, 400);
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-GROUND] aucun centre terrestre plan dans le rayon courant : recherche élargie de {} blocs",
                        SEARCH_EXTRA_RADIUS);
            }
            // Le contrôle échantillonné avait accepté dans le run T166 une zone
            // annoncée pente=7, alors que les 1 288 colonnes exhaustives révélaient
            // Y=66..111. Ces pics traversaient ensuite Dragon. Bannir ce centre et
            // recommencer la sélection AVANT la moindre édition du monde.
            REJECTED_RUGGED_CENTERS.add(BlockPos.asLong(txz.getX(), 0, txz.getZ()));
            PENDING_FLAT_CENTER = null;
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-GROUND] {} candidat {},{} écarté avant terrain : relief x0,6={} (max={}), eau={}/{}; recherche terrestre élargissable",
                    nbt, txz.getX(), txz.getZ(), exactRelief, MAX_CLIFF_HEIGHT_DIFF,
                    fullGround.wet(), fullGround.columns());
            final BlockPos retryOrigin = origin;
            deepluckyblock.procedures.TestProcedure.schedule(level,
                    deepluckyblock.procedures.TestProcedure.currentTick(level) + 10, () -> {
                boolean ok = doPaste(level, retryOrigin, nbt, ox, oy, oz, dist, nf, followSurf, useFlat, keepInt);
                if (!ok) notifyGenerationAborted(level);
            });
            return true;
        }
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-GROUND] {} sol solide validé sous 100% de la zone centrale x0,6 : {} colonnes, {} avec eau autorisée, sol Y={}..{} (relief={})",
                nbt, exactGround.columns(), exactGround.wet(), exactGround.minY(), exactGround.maxY(), exactRelief);
        PENDING_FLAT_CENTER = null;

        // T178 : SafeSurface.groundY peut rendre son fallback (Y du joueur) si
        // le chunk central n'était pas FULL au tout premier accès. Le run T177 a
        // ainsi sélectionné un vrai terrain Y=66..71 mais conservé baseY=238,
        // puis construit 223 744 blocs de fondation jusqu'au ciel. L'intervalle
        // exhaustif validé ci-dessus est désormais l'autorité AVANT toute édition.
        if (followSurf) {
            int measuredBaseY = Math.floorDiv(exactGround.minY() + exactGround.maxY(), 2);
            if (baseY < exactGround.minY() - MAX_POST_SMOOTH_DELTA_Y
                    || baseY > exactGround.maxY() + MAX_POST_SMOOTH_DELTA_Y) {
                int delta = measuredBaseY - baseY;
                LOGGER.warn("[STRUCT5] {} : Y fallback invalide corrigé avant terrain : {} -> {} "
                                + "(sol exhaustif {}..{}, delta={})",
                        nbt, baseY, measuredBaseY, exactGround.minY(), exactGround.maxY(), delta);
                baseY = measuredBaseY;
                fp = fp.above(delta);
                rp = rp.above(delta);
                min = min.above(delta);
                max = max.above(delta);
            }
        }

        if (followSurf) {
            // T76: SAFE ANCHOR -- a single center column can lie (09/27 in-game
            // test: groundY=65 on a bare-fallback candidate while the measured
            // footprint median was 49, so the model pasted 18 blocks too deep).
            // Measure the WHOLE footprint now; when the gap exceeds the guard
            // threshold, fall back to the MEASURED MEDIAN, exactly once, before
            // any terrain work: the corrected Y feeds clearance, foundation and
            // paste alike, so the manual model offset (-34/-35/-5) is applied
            // exactly once and never twice.
            // T104 (run 29/09 21:44, citadel jamais apparue) : les colonnes d'EAU
            // sont exclues de la mediane. Sur un repli falaise/ocean la mediane
            // brute (64 avec de l'eau a 84 autour) activait la garde Y0 et la
            // generation etait abandonnee ; la mediane de terre donne l'ancre
            // honnete (~110) qui franchit la garde naturellement.
            int[] pre = deepluckyblock.util.SafeSurface.groundStats(level,
                    min.getX(), min.getZ(), max.getX(), max.getZ(), 4, baseY + 1, baseY, true);
            if (pre[3] >= 16 && Math.abs(pre[0] - baseY) > MAX_POST_SMOOTH_DELTA_Y) {
                int delta = pre[0] - baseY;
                LOGGER.warn("[STRUCT5] {} : anchor corrected BEFORE terrain (T76) -- measured ground median={} "
                                + "(min={}, max={}, {} columns) vs center groundY={}; vertical shift of {} block(s), "
                                + "single offset application preserved",
                        nbt, pre[0], pre[1], pre[2], pre[3], baseY, delta);
                baseY = pre[0];
                fp = fp.above(delta);
                rp = adjRot(fp, tmpl, rot);
                min = min.above(delta); max = max.above(delta);
            } else if (pre[3] > 0) {
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} : center anchor confirmed by footprint (T76) -- median={}, min={}, max={}, "
                                + "{} columns, delta={} <= {}",
                        nbt, pre[0], pre[1], pre[2], pre[3], Math.abs(pre[0] - baseY), MAX_POST_SMOOTH_DELTA_Y);
            } else {
                LOGGER.warn("[STRUCT5] {} : footprint ground NOT measurable before terrain ({} columns) -- center "
                                + "anchor {} kept, post-pass diagnostic still applies (T76)",
                        nbt, pre[3], baseY);
            }
        }
        // T14 : groupe par section 16x16x16 + spirale (guide sections 42/43).
        filt = orderBySectionSpiral(filt, bboxRs, rp);
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} : {} a poser ({} verre supprime, air exterieur deja ecarte au chargement) -- T73",
                nbt, filt.size(), glassSkipped);
        final List<StructureTemplate.StructureBlockInfo> fFilt = filt;
        // === T83 (Y0) : PLATEFORME >= EAU EXTERIEURE + 1 ===
        // Garde finale, sur la plateforme definitive (apres re-ancrage T76). T80
        // interdit deja de spawner SOUS l'eau ; Y0 exige en outre qu'aucun plan
        // d'eau exterieur (+1 bloc) n'atteigne la plateforme, sinon il deborderait
        // sur l'emprise malgre les digues. Mesure bloc par bloc (heightmap jamais
        // crue, voir SafeSurface.maxWaterLevelOutside).
        // T104 (run 29/09 21:44 : citadel abandonnee, le joueur a attendu 3 min
        // et « rien ne spawn ») : L'ABANDON PUR ET DUR EST INTERDIT -- meme
        // consigne que T88 pour l'Everest. La plateforme est REMONTEE au plafond
        // Y0 (eau exterieure + 1) et la pose a lieu quoi qu'il arrive : la
        // fondation/les digues font la garde de l'eau, et la regle
        // plateforme >= eau+1 est satisfaite par construction. La mesure se fait
        // AVANT de figer les valeurs du pipeline (les captures finales doivent
        // tenir compte du lift).
        int y0Water = deepluckyblock.util.SafeSurface.maxWaterLevelOutside(level,
                min.getX(), min.getZ(), max.getX(), max.getZ(),
                deepluckyblock.util.SafeSurface.Y0_BAND, deepluckyblock.util.SafeSurface.Y0_STEP);
        if (y0Water != Integer.MIN_VALUE && baseY < y0Water + 1) {
            int lift = (y0Water + 1) - baseY;
            int oldBaseY = baseY;
            baseY = y0Water + 1;
            fp = fp.above(lift);
            rp = adjRot(fp, tmpl, rot);
            min = min.above(lift); max = max.above(lift);
            LOGGER.warn("[STRUCT5] {} : plateforme Y={} sous le plafond Y0 (eau exterieure max Y={} "
                            + "a moins de {} blocs hors-emprise) -- PLATEFORME REMONTEE de {} bloc(s) a Y={} "
                            + "et POSE MAINTENUE (T104 : jamais d'abandon silencieux, precedent T88-everest)",
                    nbt, oldBaseY, y0Water, deepluckyblock.util.SafeSurface.Y0_BAND,
                    lift, baseY);
        }
        if (y0Water != Integer.MIN_VALUE && baseY >= y0Water + 1)
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} : Y0 OK -- plateforme Y={} >= eau exterieure Y={} + 1", nbt, baseY, y0Water);
        // Keep one selected ground plane throughout clearance, terrain and paste.
        // SafeSurface.groundY returns the SOLID block Y (no +/-1 conversion here).
        final int selectedGroundY = baseY;
        final int foundationBaseY = selectedGroundY + oy;
        final BlockPos pasteRp = rp, pasteMin = min, pasteMax = max;
        StructureTerrainPrep.setTerrainRingScalePercent(ringScaleFor(nbt));
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} : selected solid groundY={}, clearance starts at Y={}, model offsetY={}, foundationBaseY={}",
                nbt, selectedGroundY, selectedGroundY + 1, oy, foundationBaseY);
        StructureTerrainPrep.prepZone(level, pasteMin, pasteMax, foundationBaseY, selectedGroundY, () -> {
            // Do NOT re-anchor to the post-smooth median. In the reported Dragon
            // case it lowered the model by another six blocks (78 -> 72), on top
            // of its calibrated -34 offset. The clearance floor and the paste
            // must refer to the same selected plane. Low ground is diagnosed,
            // not hidden by sinking the model or by digging down to its bottom.
            StructureTerrainPrep.decorateTerrainOnly(level, pasteMin, pasteMax, () ->
                StructureTerrainPrep.preloadBox(level, pasteMin, pasteMax, () -> {
                    // Measure only AFTER all terrain passes, final clearance and
                    // water repair. These samples are diagnostics, not a new Y anchor.
                    int[] ground = deepluckyblock.util.SafeSurface.groundStats(level,
                            pasteMin.getX(), pasteMin.getZ(), pasteMax.getX(), pasteMax.getZ(),
                            4, selectedGroundY, selectedGroundY);
                    // T178 : l'ancre a déjà été corrigée depuis le sol exhaustif
                    // AVANT prefillFoundation. Ne surtout pas ré-ancrer sur la
                    // médiane post-prep : dans l'emprise elle mesure le plancher de
                    // fondation (baseY + offsetY), ce qui appliquerait l'offset une
                    // seconde fois (-34 supplémentaire pour Dragon).
                    BlockPos actualPasteRp = pasteRp;
                    BlockPos actualPasteMin = pasteMin;
                    BlockPos actualPasteMax = pasteMax;
                    int firstBlockY = actualPasteRp.getY() + floorRelYFinal;
                    deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5] {} : sol 0 exhaustif fixé avant terrain={}, offsetY={}, first model blockY={}; "
                                    + "diagnostic post-prep median={}, min={}, max={}, samples={} (aucun second décalage)",
                            nbt, selectedGroundY, oy, firstBlockY,
                            ground[0], ground[1], ground[2], ground[3]);
                    if (ground[3] == 0) {
                        LOGGER.warn("[STRUCT5] {} : no usable ground samples; support and entrance accessibility NOT verified", nbt);
                    } else {
                        if (Math.abs(ground[0] - selectedGroundY) > MAX_POST_SMOOTH_DELTA_Y
                                || ground[2] - ground[1] > 8) {
                            LOGGER.warn("[STRUCT5] {} : uneven ground remains (selected={}, median={}, min={}, max={}); "
                                            + "model anchor and manual offset preserved, visual inspection required",
                                    nbt, selectedGroundY, ground[0], ground[1], ground[2]);
                        }
                        if (firstBlockY > ground[0] + 1) {
                            LOGGER.warn("[STRUCT5] {} : possible unsupported model (first blockY={}, median ground={}); "
                                            + "no automatic sinking or excavation applied", nbt, firstBlockY, ground[0]);
                        }
                    }
                    // The lowest model block is not its entrance: no accessibility
                    // guarantee can be inferred from this diagnostic alone.
                    deepluckyblock.util.StructureSites.register(level, nbt,
                            Math.min(actualPasteMin.getX(), actualPasteMax.getX()), Math.min(actualPasteMin.getZ(), actualPasteMax.getZ()),
                            Math.max(actualPasteMin.getX(), actualPasteMax.getX()), Math.max(actualPasteMin.getZ(), actualPasteMax.getZ()));
                    PASTE_Q.offer(new PasteJob(level, fFilt, actualPasteRp, rot, Mirror.NONE, nbt, tGlobal, pl, actualPasteMin, actualPasteMax));
                }));
        });
        return true;
    }

    /**
     * T14 — ORDRE DE PASTE RECOMMANDE PAR LE GUIDE (sections 42 et 43).
     *
     * <p>Ce qui a ete remplace : un tri « layer par layer » (Y croissant). Le
     * guide explique pourquoi c'est le PIRE ordre pour la performance : une
     * couche Y d'une structure de 200x200 touche deja 13x13 = 169 chunks, donc
     * sur 150 couches on refait 25 350 fois la meme resolution de chunk/section
     * (cache CPU et lookups gaspilles), alors qu'il n'y a que 169 chunks reels.
     *
     * <p>Ce qui est fait ici : les blocs sont groupes par SECTION 16x16x16 (unite
     * de stockage reelle de Minecraft), et les sections sont triees par distance
     * au centre (spirale). Deux effets : le paste ne resout plus chaque section
     * qu'une fois (le cache de chunk par section est dans
     * {@code placeOne}), et la structure apparait du centre vers l'exterieur au
     * lieu d'un balayage TV de gauche a droite.
     *
     * <p>A l'interieur d'une section, l'ordre reste par Y croissant : les blocs
     * du bas sont poses avant ceux du haut (aucun bloc flottant a l'ecran).
     */
    private static List<StructureTemplate.StructureBlockInfo> orderBySectionSpiral(
            List<StructureTemplate.StructureBlockInfo> blocks, StructurePlaceSettings rs, BlockPos anchor) {
        final int acx = anchor.getX() >> 4, acz = anchor.getZ() >> 4;
        record Key(StructureTemplate.StructureBlockInfo info, long section, double dist2, int y) { }
        List<Key> keys = new ArrayList<>(blocks.size());
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockPos rel = StructureTemplate.calculateRelativePosition(rs, info.pos());
            int x = anchor.getX() + rel.getX(), y = anchor.getY() + rel.getY(), z = anchor.getZ() + rel.getZ();
            int cx = x >> 4, sy = y >> 4, cz = z >> 4;
            long section = ((long) (cx & 0x3FFFFF) << 42) | ((long) (sy & 0xFFFFF) << 22) | (cz & 0x3FFFFF);
            double d2 = (double) (cx - acx) * (cx - acx) + (double) (cz - acz) * (cz - acz);
            keys.add(new Key(info, section, d2, y));
        }
        keys.sort(java.util.Comparator.<Key>comparingDouble(Key::dist2)
                .thenComparingLong(Key::section)
                .thenComparingInt(Key::y));
        List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(blocks.size());
        for (Key k : keys) out.add(k.info());
        return out;
    }

    private static List<StructureTemplate.StructureBlockInfo> orderByY(List<StructureTemplate.StructureBlockInfo> blocks) {
        List<StructureTemplate.StructureBlockInfo> sorted = new ArrayList<>(blocks);
        sorted.sort((a, b) -> { int ya = a.pos().getY(), yb = b.pos().getY(); if (ya != yb) return Integer.compare(ya, yb);
            int xa = a.pos().getX(), xb = b.pos().getX(); if (xa != xb) return Integer.compare(xa, xb); return Integer.compare(a.pos().getZ(), b.pos().getZ()); });
        return sorted;
    }

    @SuppressWarnings("unchecked")
    private static List<StructureTemplate.StructureBlockInfo> exBlocks(StructureTemplate t) {
        try { for (Field f : StructureTemplate.class.getDeclaredFields()) { f.setAccessible(true); Object val = f.get(t);
            if (val instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof StructureTemplate.Palette)
                return ((StructureTemplate.Palette) list.get(0)).blocks(); }
        } catch (Exception e) { LOGGER.warn("[STRUCT5] Reflection echouee: {}", e.getMessage()); }
        return Collections.emptyList();
    }

    private static BlockPos adjRot(BlockPos p, StructureTemplate t, Rotation r) {
        Vec3i s = t.getSize();
        return switch (r) { case NONE -> p; case CLOCKWISE_90 -> p.offset(s.getZ() - 1, 0, 0);
            case CLOCKWISE_180 -> p.offset(s.getX() - 1, 0, s.getZ() - 1); case COUNTERCLOCKWISE_90 -> p.offset(0, 0, s.getX() - 1); };
    }

    /** Message vendeur affiche au joueur lorsque la structure est entierement posee. */
    private static int removeUndergroundTechnicalBlocks(ServerLevel level, BlockPos min, BlockPos max) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int removed = 0;
        int thresholdY = min.getY() + 1;

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int y = level.getMinBuildHeight(); y <= Math.min(max.getY(), thresholdY); y++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (isTechnicalLightOrVine(state)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    // FIX (rapporte en jeu : "les coffres relies a notre systeme de coffre
    // common a ultimate ne fonctionnent plus" + "l'Everest est sense
    // supprimer les coffres et n'en laisser que 3 max, la ils sont tous
    // la") : {@link #prepareAutomaticChest} et {@link #cleanupEverestChests}
    // etaient private static, utilisables UNIQUEMENT depuis la boucle de
    // paste (PasteJob) de CETTE classe -- or le VRAI pipeline de placement
    // de l'Everest vit entierement dans {@link Structures4Procedure}
    // (EverestJob/EVEREST_QUEUE, jamais PasteJob), qui n'appelait donc
    // JAMAIS ni l'une ni l'autre : aucun coffre d'Everest ne recevait les
    // drapeaux slb_auto_chest/slb_chest_rank (donc jamais rempli par
    // LuckyChestAutofillProcedure -- "coffres qui ne fonctionnent plus"),
    // et cleanupEverestChests n'etait jamais invoque pour Everest (donc
    // TOUS les coffres restaient en place). Passage a package-private pour
    // que Structures4Procedure puisse les appeler directement lors du
    // placement bloc-par-bloc de l'EverestJob, exactement comme le fait
    // deja PasteJob pour citadel/observatory/dragon.
    static void prepareAutomaticChest(ServerLevel level, BlockPos pos, BlockState state,
                                               CompoundTag originalNbt, int relativeY,
                                               List<StructureTemplate.StructureBlockInfo> blocks) {
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) return;
        var be = level.getBlockEntity(pos);
        if (!(be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)) return;

        CompoundTag data = chest.getPersistentData();
        if (data.getBoolean("slb_auto_chest")) return;

        // FIX CRITIQUE (rapporte en jeu : "tous les coffres sont vides !") :
        // ChestBlockEntity#saveAdditional ecrit TOUJOURS une liste "Items"
        // dans le NBT sauvegarde -- meme quand le coffre est totalement vide,
        // "Items" est present mais avec une ListTag de longueur 0. L'ancien
        // test originalNbt.contains("Items") est donc VRAI pour absolument
        // TOUS les coffres exportes dans un template de structure (verifie
        // via inspection directe de citadel.nbt/everest.nbt : 100% des
        // coffres portent une cle "Items", y compris ceux dont la liste est
        // vide) -> hasDefinedContent etait presque toujours vrai, et
        // prepareAutomaticChest() rendait la main AVANT de poser le moindre
        // drapeau slb_auto_chest, quel que soit le contenu reel du coffre.
        // Resultat : plus un seul coffre de citadel/observatoire/dragon/
        // everest ne recevait jamais les drapeaux exiges par
        // LuckyChestAutofillProcedure -> ils restaient vides pour toujours,
        // exactement le symptome rapporte. Correction : ne considerer le
        // contenu comme "deja defini" que si la liste Items existe ET
        // contient reellement au moins un item (ou si une LootTable est
        // presente) -- un coffre au NBT vide (le cas normal/voulu pour tous
        // les coffres a remplir automatiquement) reçoit desormais bien ses
        // drapeaux.
        boolean hasItems = originalNbt != null && originalNbt.contains("Items")
                && !originalNbt.getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty();
        boolean hasDefinedContent = originalNbt != null
                && (hasItems || originalNbt.contains("LootTable"));
        if (hasDefinedContent) return;

        // FIX (politique de correspondance exacte demandee : "coffre avec
        // NBT/titre correspondant a un rang -> meme rang de coffre modde ;
        // coffre sans NBT/titre special -> coffre aleatoire parmi le TOP 3
        // des rangs (Ultimate, Ultimate-1, Ultimate-2) UNIQUEMENT, jamais
        // parmi tout le pool de rangs") :
        //
        // A ce stade de la methode, le coffre n'a NI drapeau slb_auto_chest
        // pre-existant (deja gere par le retour anticipe ci-dessus -- ce
        // cas correspond au coffre "matched-rank NBT/titre", dont le rang
        // baked dans la structure (NeoForgeData -> slb_chest_rank) a deja
        // ete recharge tel quel par loadWithComponents et est donc deja
        // conserve), NI contenu vanilla reel deja defini (Items/LootTable,
        // cas ci-dessus). Il s'agit donc forcement d'un coffre "vierge" --
        // sans titre special ni contenu -- pour lequel l'ancienne logique
        // choisissait un rang par interpolation de hauteur relative sur
        // TOUTE la plage 1-5 (Chest a Ultimate). Remplace par un tirage
        // ALEATOIRE STRICTEMENT parmi les 3 rangs superieurs uniquement :
        // Ultimate (5), Ultimate-1 = Epic (4), Ultimate-2 = Rare (3).
        int[] topThreeRanks = {5, 4, 3};
        int rank = topThreeRanks[level.getRandom().nextInt(topThreeRanks.length)];

        data.putBoolean("slb_auto_chest", true);
        data.putBoolean("slb_filled", false);
        data.putInt("slb_chest_rank", rank);
        chest.setChanged();
    }

    // ==================== EVEREST: NETTOYAGE DES COFFRES ====================
    /** Rang auto du coffre (slb_chest_rank) ; 0 s'il n'est pas un coffre auto. */
    private static int chestRank(ServerLevel level, BlockPos p) {
        var be = level.getBlockEntity(p);
        if (be != null) return be.getPersistentData().getInt("slb_chest_rank");
        return 0;
    }

    /**
     * Retire la majorite des coffres de l'Everest, en ne conservant qu'un
     * nombre ALÉATOIRE entre EVEREST_CHEST_MIN et EVEREST_CHEST_MAX, tiré
     * parmi TOUS les coffres (aucune priorité "plus riche"). Appele juste apres
     * la pose, sur la liste des coffres releves pendant le paste (cheap -> pas
     * de rescan complet de la structure).
     */
    public static void cleanupEverestChests(ServerLevel level, List<BlockPos> chests) {
        if (level == null || chests == null || chests.isEmpty()) return;
        int keep = EVEREST_CHEST_MIN + level.getRandom().nextInt(EVEREST_CHEST_MAX - EVEREST_CHEST_MIN + 1);
        // Mélange complet -> on garde keep coffres STRICTEMENT au hasard.
        Collections.shuffle(chests, new java.util.Random(level.getRandom().nextLong()));

        int kept = Math.min(keep, chests.size());
        int removed = chests.size() - kept;
        for (int i = kept; i < chests.size(); i++) {
            BlockPos c = chests.get(i);
            // FIX (rapporte en jeu : "comme tu as cassé les coffres, les
            // items dedans se sont retrouvés au sol ! faut regler ca, pour
            // les faire disparaitre") : ChestBlock#onRemove (vanilla) fait
            // automatiquement Containers.dropContents() des qu'un
            // ChestBlockEntity disparait sous un bloc non-coffre -- poser
            // Blocks.AIR directement ejectait donc TOUT le contenu au sol.
            // Corrige en vidant explicitement le conteneur (clearContent,
            // sans drop) juste avant de retirer le bloc : le contenu
            // disparait purement et simplement avec le coffre, comme voulu.
            var be = level.getBlockEntity(c);
            if (be instanceof net.minecraft.world.Container container) {
                container.clearContent();
            }
            level.setBlock(c, Blocks.AIR.defaultBlockState(), FAST_FLAG);
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-EVEREST] Coffre retire: {}", c.toShortString());
        }
        // Sur les coffres conserves : retirer tout item Shadow Essence du contenu.
        for (int i = 0; i < kept; i++) {
            removeShadowEssence(level, chests.get(i));
        }
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-EVEREST] {} coffres retires, {} conserves (au hasard, sans Shadow Essence)", removed, kept);
    }

    /**
     * Retire TOUS les items "Shadow Essence" du contenu d'un coffre (celui deja
     * rempli via son NBT). Compatible avec les items dont le registry name est
     * "deep_lucky_block:shadow_essence" (ou toute variante de paquet finissant
     * par ":shadow_essence"). Ne fait rien si aucun n'est present.
     */
    private static void removeShadowEssence(ServerLevel level, BlockPos pos) {
        try {
            var be = level.getBlockEntity(pos);
            if (!(be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)) return;
            int size = chest.getContainerSize();
            boolean any = false;
            for (int i = 0; i < size; i++) {
                ItemStack stack = chest.getItem(i);
                if (stack == null || stack.isEmpty()) continue;
                if (isShadowEssence(stack)) {
                    chest.setItem(i, ItemStack.EMPTY);
                    any = true;
                    deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-EVEREST] Shadow Essence retire de {}", pos.toShortString());
                }
            }
            if (any) chest.setChanged();
        } catch (Throwable t) {
            LOGGER.warn("[STRUCT5-EVEREST] Impossible de nettoyer {} : {}", pos.toShortString(), t.getMessage());
        }
    }

    /** True si l'item est une Shadow Essence (registry path == "shadow_essence"). */
    private static boolean isShadowEssence(ItemStack stack) {
        try {
            var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key == null) return false;
            String path = key.getPath().toLowerCase(Locale.ROOT);
            return path.equals("shadow_essence") || path.endsWith(":shadow_essence") || path.contains("shadow_essence");
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== EVEREST: SPAWN DES MOBS SUR LA MONTAGNE ====================
    /**
     * Fait spawn 10-20 mobs SUR la surface de la montagne (le bloc le plus haut de
     * chaque colonne echantillonnee) et AUTOUR, mais JAMAIS au sommet : une bande
     * EVEREST_TOP_MARGIN au-dessus du sommet est exclue (les PNJ y vivent, on ne
     * veut pas que les mobs les tuent). La montagne etant creuse, on pose les mobs
     * sur la surface exterieure (pas dedans), via le Heightmap.
     */
    public static void spawnEverestMobs(ServerLevel level, BlockPos min, BlockPos max) {
        if (level == null || min == null || max == null) return;
        RandomSource r = level.getRandom();
        int count = EVEREST_MOB_MIN + r.nextInt(EVEREST_MOB_MAX - EVEREST_MOB_MIN + 1);
        int step = Math.max(1, EVEREST_SURFACE_STEP);

        Map<Long, Integer> surface = new HashMap<>();
        int maxSurf = Integer.MIN_VALUE;
        for (int x = min.getX(); x <= max.getX(); x += step) {
            for (int z = min.getZ(); z <= max.getZ(); z += step) {
                int surfY = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (surfY <= level.getMinBuildHeight()) continue;
                surface.put(colKey(x, z), surfY);
                if (surfY > maxSurf) maxSurf = surfY;
            }
        }
        if (surface.isEmpty()) return;

        // On exclut la bande au-dessus du sommet (PNJ) : ne pas y spawner.
        int summitThreshold = maxSurf - EVEREST_TOP_MARGIN;
        List<Map.Entry<Long, Integer>> valid = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : surface.entrySet()) {
            if (e.getValue() <= summitThreshold) valid.add(e);
        }
        if (valid.isEmpty()) {
            LOGGER.warn("[STRUCT5-EVEREST] Aucune surface hors sommet -> pas de mobs");
            return;
        }
        Collections.shuffle(valid, new java.util.Random(r.nextLong()));

        int spawned = 0;
        for (Map.Entry<Long, Integer> e : valid) {
            if (spawned >= count) break;
            int x = keyX(e.getKey()), z = keyZ(e.getKey()), y = e.getValue() + 1;
            EntityType<?> type = EVEREST_MOB_TYPES[r.nextInt(EVEREST_MOB_TYPES.length)];
            Mob mob = (Mob) type.create(level);
            if (mob == null) continue;
            mob.moveTo(x + 0.5, y, z + 0.5, r.nextFloat() * 360.0f, 0);
            mob.setPersistenceRequired();
            mob.setCanPickUpLoot(true);
            level.addFreshEntity(mob);
            spawned++;
        }
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT5-EVEREST] {} mobs spawnes sur la montagne (centre env. {},{})", spawned,
                Math.floorDiv(min.getX() + max.getX(), 2), Math.floorDiv(min.getZ() + max.getZ(), 2));
    }

    private static void announceStructureSpawn(Player player, String rawName, BlockPos min, BlockPos max) {
        if (player == null) return;

        String structureName = switch (rawName.toLowerCase(Locale.ROOT)) {
            case "citadel" -> "Sylvan Citadel";
            case "observatory" -> "Fae Observatory";
            case "dragon" -> "Dragon Sanctuary";
            case "circus" -> "Cursed Circus";
            case "shipdead" -> "Ghost Ship";
            case "everest" -> "Inoxtag Everest";
            default -> rawName;
        };

        int centerX = Math.floorDiv(min.getX() + max.getX(), 2);
        int centerY = Math.floorDiv(min.getY() + max.getY(), 2);
        int centerZ = Math.floorDiv(min.getZ() + max.getZ(), 2);

        Component message = Component.literal(
                "\u00a76\u00a7l✦ STRUCTURE DISCOVERED ✦ \u00a7r"
                        + "\u00a7e\u00a7l" + structureName
                        + " \u00a7f\u00a7lhas spawned! \u00a77Center coordinates: "
                        + "\u00a7b[" + centerX + ", " + centerY + ", " + centerZ + "]"
                        + " \u00a76✦");

        player.sendSystemMessage(message);
    }

    /** Vérifie sans charger/générer que toute la couronne d'édition est en mémoire. */
    private static boolean zoneInMemory(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        var source = level.getChunkSource();
        int cx0 = minX >> 4, cx1 = maxX >> 4, cz0 = minZ >> 4, cz1 = maxZ >> 4;
        for (int cx = cx0; cx <= cx1; cx += 4)
            for (int cz = cz0; cz <= cz1; cz += 4)
                if (source.getChunkNow(cx, cz) == null) return false;
        return source.getChunkNow(cx1, cz1) != null
                && source.getChunkNow(cx0, cz1) != null
                && source.getChunkNow(cx1, cz0) != null;
    }

    private static void postLayer(ServerLevel lvl, BlockPos min, BlockPos max, int y) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos chk = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x < max.getX(); x++) for (int z = min.getZ(); z < max.getZ(); z++) {
            pos.set(x, y, z); BlockState s = lvl.getBlockState(pos); if (s.isAir()) continue;
            if (isGlassBlock(s) && isFloat(lvl, pos, chk)) lvl.setBlock(pos, Blocks.AIR.defaultBlockState(), FAST_FLAG);
            else if (s.is(Blocks.VINE) && removeVine(lvl, pos, chk)) lvl.setBlock(pos, Blocks.AIR.defaultBlockState(), FAST_FLAG);
            else if (s.is(Blocks.CRIMSON_HYPHAE) || s.is(Blocks.STRIPPED_CRIMSON_HYPHAE) || s.is(Blocks.CRIMSON_STEM) || s.is(Blocks.STRIPPED_CRIMSON_STEM) || s.is(Blocks.CRIMSON_PLANKS)) {
                lvl.setBlock(pos, Blocks.AIR.defaultBlockState(), FAST_FLAG); spawnGlow(lvl, pos, mkItem(lvl, lvl.getRandom()));
            }
        }
    }

    private static boolean isFloat(ServerLevel l, BlockPos p, BlockPos.MutableBlockPos c) {
        for (Direction d : Direction.values()) { c.set(p.getX() + d.getStepX(), p.getY() + d.getStepY(), p.getZ() + d.getStepZ());
            BlockState n = l.getBlockState(c); if (!n.isAir() && !n.blocksMotion()) return false; }
        return true;
    }

    private static boolean removeVine(ServerLevel l, BlockPos p, BlockPos.MutableBlockPos c) {
        int solid = 0;
        for (Direction d : Direction.values()) { c.set(p.getX() + d.getStepX(), p.getY() + d.getStepY(), p.getZ() + d.getStepZ());
            BlockState n = l.getBlockState(c); if (!n.isAir() && !n.is(Blocks.VINE) && n.blocksMotion()) solid++; }
        return solid >= 4;
    }

    private static ItemStack mkItem(ServerLevel l, RandomSource r) {
        try { int luck = 60 + r.nextInt(31); ItemStack res = GenerateLuckyItemProcedure.generate(l, luck, TestProcedure.QualityTier.getQualityTier(luck), r);
            if (res != null && !res.isEmpty()) return res; } catch (Exception ignored) {}
        ItemStack a = new ItemStack(Items.GOLDEN_APPLE); a.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("\u00a76\u00a7l\u2726 Tresor \u2726")); return a;
    }

    private static void spawnGlow(ServerLevel lvl, BlockPos pos, ItemStack item) {
        if (!item.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("\u00a76\u00a7l\u2726 Recompense \u2726"));
        ItemEntity e = new ItemEntity(lvl, pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, item);
        e.setGlowingTag(true); e.setNoGravity(true); e.setInvulnerable(true); e.setDeltaMovement(0, 0, 0); e.setPickUpDelay(40);
        CompoundTag nbt = new CompoundTag(); e.save(nbt); nbt.putShort("Age", (short) -32768); nbt.putShort("Health", (short) 32767);
        nbt.putBoolean("Invulnerable", true); nbt.putBoolean("PersistenceRequired", true);
        nbt.putShort("PickupDelay", (short) 40); nbt.putInt("Lifespan", Integer.MAX_VALUE);
        e.load(nbt); e.setCustomName(Component.literal("\u00a76\u00a7l\u2726 Recompense \u2726")); e.setCustomNameVisible(false);
        lvl.addFreshEntity(e);
    }
}
