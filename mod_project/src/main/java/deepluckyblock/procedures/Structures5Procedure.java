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
    private static final long FIND_FLAT_TIME_BUDGET_MS = 3000;

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
        deepluckyblock.util.SafeSurface.requestZone(lvl, ccx0, ccz0, ccx1, ccz1);
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
    private static final double FLAT_MAX_WET = 0.10;
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
                if (level.getChunkSource().getChunk(cx + dx, cz + dz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null) loaded++;
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
        if (k > 0) LOGGER.info("[STRUCT5] {} items laggy nettoyes", k);
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
                        LOGGER.info("[STRUCT5-SAFETY] Zone sure a {} (r={}, {}ms)", candidate.toShortString(), radius, System.currentTimeMillis() - t0);
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
        // T73 : ne JAMAIS reposer de l'air sur de l'air. Les entrees d'air
        // interieur servent a CREUSER l'interieur de la structure : si la case est
        // deja vide (le cas le plus frequent), le setBlock ne servait a rien et
        // coutait un acces chunk + une mise a jour de bloc.
        if (ts.isAir() && j.level.getBlockState(bp).isAir()) return true;
        j.level.setBlock(bp, ts, FAST_FLAG);
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
    private static void retryDeferred(PasteJob j, StructurePlaceSettings rs, BlockPos.MutableBlockPos bp) {
        j.waitTicks++;
        boolean force = j.waitTicks > 600;
        if (force && j.waitTicks == 601) {
            LOGGER.warn("[STRUCT5] {} : {} blocs en attente depuis 30 s -- chargement force des chunks restants", j.name, j.defer.size());
        }
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
            LOGGER.info("[STRUCT5] {} : {} bloc(s) reposes apres chargement de la zone ({} restants, {} ticks d'attente)", j.name, placed, j.defer.size(), j.waitTicks);
        }
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
                    LOGGER.info("[STRUCT5] {} LIGHT/vines techniques souterrains supprimes apres paste", technicalRemoved);
                }
                if (isEverestNbt(j.name)) {
                    cleanupEverestChests(j.level, j.chests);
                }
                LOGGER.info("[STRUCT5] {} en {}ms ({} blocs, {} a gravite -- ticks de chute neutralises par DLB-CLAMP)", j.name, el, j.blocks.size(), j.grav);
                if (j.player != null) {
                    j.player.sendSystemMessage(Component.literal("\u00a7a\u2713 \u00a7e" + j.name + "\u00a7a en " + el + "ms"));
                    announceStructureSpawn(j.player, j.name, j.min, j.max);
                }
                CarveJob carve = buildCarveJob(j);
                CARVE_Q.offer(carve);
                LOGGER.info("[STRUCT5] carve {} programmee ({} colonnes, {} blocs occupes, bottomY={})", j.name, carve.columns.size(), carve.occupied.size(), carve.bottomY);
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
                LOGGER.info("[STRUCT5] {} termine : {} blocs supprimes, {} blocs de motte (bottomY={})", cj.name, cj.carved, cj.grounded, cj.bottomY);
                // Le decor thematique est desormais pose PAR decorate(), juste
                // AVANT le replant des arbres (demande utilisateur : les arbres
                // doivent pousser AUTOUR des decors, jamais dedans ni dessus).
                // On lui transmet le nom de la structure pour le choix du theme.
                StructureTerrainPrep.setStructureName(cj.name);
                StructureTerrainPrep.decorateFinish(cj.level, cj.min, cj.max);   // T39 : finitions post-pose
                POST_Q.offer(new PostJob(cj.level, cj.min, cj.max, cj.name));
                LOGGER.info("[STRUCT5] decorate + post de {} termine", cj.name);
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
                LOGGER.info("[STRUCT5] Post-process de {} termine", pj.name);
                // Structure entierement terminee : liberer la file d'attente.
                notifyGenerationFinished(pj.level);
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
        switch (id) {
            case 0, 16 -> doPaste(level, origin, CITADEL_NBT, CITADEL_OFFSET_X, CITADEL_OFFSET_Y, CITADEL_OFFSET_Z, CITADEL_DISTANCE, CITADEL_FACING, CITADEL_FOLLOW_SURFACE, CITADEL_USE_FLAT, CITADEL_KEEP_INT);
            case 1, 17 -> doPaste(level, origin, OBS_NBT, OBS_OFFSET_X, OBS_OFFSET_Y, OBS_OFFSET_Z, OBS_DISTANCE, OBS_FACING, OBS_FOLLOW_SURFACE, OBS_USE_FLAT, OBS_KEEP_INT);
            case 2, 18 -> doPaste(level, origin, DRAGON_NBT, DRAGON_OFFSET_X, DRAGON_OFFSET_Y, DRAGON_OFFSET_Z, DRAGON_DISTANCE, DRAGON_FACING, DRAGON_FOLLOW_SURFACE, DRAGON_USE_FLAT, DRAGON_KEEP_INT);
            default -> LOGGER.warn("[STRUCT5] ID inconnu : {}", id);
        }
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
    private record PendingStructure(int id, BlockPos origin, ServerLevel level) {}

    private static final java.util.ArrayDeque<PendingStructure> PENDING_STRUCTURES = new java.util.ArrayDeque<>();
    private static volatile boolean GENERATION_BUSY = false;
    private static volatile long GENERATION_START_MS = 0L;
    /** Garde-fou : si une generation ne signale jamais sa fin, on debloque. */
    private static final long GENERATION_TIMEOUT_MS = 240_000L;
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

    /** Appele par les procedures de generation quand une structure est terminee. */
    public static void notifyGenerationFinished(ServerLevel level) {
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
                LOGGER.info("[STRUCT5] '{}' : NBT charge en TACHE DE FOND ({} octets sur disque) -- generation retenue quelques ticks, "
                                + "le serveur n'est PAS bloque. Elle demarrera seule des que le chargement sera fini.",
                        nbtName, nbtFileHint(nbtName));
                if (next.level() != null && next.level().getServer() != null) {
                    for (Player p : next.level().getServer().getPlayerList().getPlayers()) {
                        if (p != null) p.sendSystemMessage(Component.literal(
                                "§e⏳ " + nbtName + " : préparation du terrain en cours…"));
                    }
                }
            } else if (DEFER_WAIT_TICKS % 200 == 0) {
                LOGGER.info("[STRUCT5] '{}' : toujours en cours de chargement ({} s) -- structure maintenue en attente", nbtName, DEFER_WAIT_TICKS / 20);
            }
            return;
        }
        PENDING_STRUCTURES.poll();
        DEFER_WAIT_TICKS = 0;
        LOGGER.info("[STRUCT5] File d'attente : demarrage de la structure ID={} ({} encore en attente)",
                next.id(), PENDING_STRUCTURES.size());
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
        LOGGER.info("[STRUCT5] execute() ID={}, origin={}", id, origin.toShortString());
        // 🏆 Achievement "structure spawnée" : chaque structure a le sien + 2 blocs.
        grantStructureSpawnAchievement(level, origin, player, id);

        // Deblocage de securite : une generation qui n'a jamais signale sa fin
        // ne doit pas geler la file pour toujours.
        if (GENERATION_BUSY && System.currentTimeMillis() - GENERATION_START_MS > GENERATION_TIMEOUT_MS) {
            LOGGER.warn("[STRUCT5] Generation precedente sans fin signalee apres {} s -- file debloquee",
                    GENERATION_TIMEOUT_MS / 1000);
            GENERATION_BUSY = false;
        }

        if (GENERATION_BUSY) {
            PENDING_STRUCTURES.add(new PendingStructure(id, origin, level));
            LOGGER.info("[STRUCT5] Une generation est deja en cours : ID={} mis en file ({} en attente)",
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
            PENDING_STRUCTURES.addFirst(new PendingStructure(id, origin, level));
            deepluckyblock.util.StructureTemplateCache.requestAsync(level, nbtName);
            DEFER_WAIT_TICKS = 0;
            startNextPending(level);
            return;
        }
        beginGeneration(level, origin, id);
    }

    private static void beginGeneration(ServerLevel level, BlockPos origin, int id) {
        GENERATION_BUSY = true;
        GENERATION_START_MS = System.currentTimeMillis();
        switch (id) {
            case 0, 1, 2, 16, 17, 18 -> generate(level, origin, id, level.getRandom());
            case 3 -> Structures4Procedure.spawnCircus(level, origin);
            case 4 -> Structures4Procedure.spawnShipdead(level, origin);
            case 5 -> Structures4Procedure.spawnEverest(level, origin);
            default -> {
                LOGGER.warn("[STRUCT5] ID inconnu dans execute : {}", id);
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
        for (int[] off : offsets) {
            int cx = origin.getX() + off[0], cz = origin.getZ() + off[1];
            // FIX T7 : hauteur lue sur un chunk REELLEMENT genere (voir SafeSurface).
            // Avant, cette lecture renvoyait -65 (heightmap non initialisee) et
            // c'est CE point precis qui ancrait observatory a Y=-78 et everest a Y=-90.
            int cy = deepluckyblock.util.SafeSurface.surfaceY(level, cx, cz, origin.getY());
            BlockPos cand = new BlockPos(cx, cy, cz);
            if (!isPlayerChunk(cand, null, origin)) return cand;
        }
        return origin.offset(32, 0, 32);
    }

    private static BlockPos findFlat(ServerLevel lvl, BlockPos origin, int structW, int structD, int minEdgeDist) {
        Player p = lvl.getNearestPlayer(origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5, 128, false);
        double lx = 0, lz = 0; BlockPos pp = origin;
        if (p != null) { float yaw = p.getYRot(); lx = -Math.sin(Math.toRadians(yaw)); lz = Math.cos(Math.toRadians(yaw)); pp = p.blockPosition(); }
        int checkR = Math.max(8, Math.max(structW, structD) / 2);
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
        int searchR = Math.min(Math.max(SEARCH_R, minCenterDist + 16), 300);

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
                int wx = origin.getX() + (gi - gN / 2) * gstep;
                int wz = origin.getZ() + (gj - gN / 2) * gstep;
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
        LOGGER.info("[STRUCT5-FLAT] grille de scoring : {}x{} cellules ({} connues) en {} ms -- T59/T62 "
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
        for (int dx = -searchR; dx <= searchR; dx += SEARCH_S) {
            for (int dz = -searchR; dz <= searchR; dz += SEARCH_S) {
                int cx = origin.getX() + dx, cz = origin.getZ() + dz;
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
                    LOGGER.info("[STRUCT5-FLAT-PROBE] en cours : {} candidat(s) retenus, {} ms joueur, {} ms emprises, {} ms mesures",
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
                    // T33 (constat en jeu : « le programme de base, celui qui selectionne
                    // la zone en cherchant la plus grande plane dans un rayon puis
                    // l'attribue a la structure, est HS »). Cause retrouvee : les
                    // centaines de « REJETE : emprise 'everest' » d'affilee VIDAIENT la
                    // liste des candidats plats, et le selecteur retombait sur le repli
                    // « le moins pentu » -- donc sur une zone mediocre alors que le
                    // candidat rejete etait, lui, reellement plat. Un chevauchement
                    // d'emprise n'est PAS une raison de jeter une zone plate : on decale
                    // le candidat le long de la spirale libre (freeOffset, T29) et on le
                    // garde si le point decale est libre.
                    BlockPos shifted = deepluckyblock.util.StructureSites.freeOffset(lvl,
                            new BlockPos(cx, origin.getY(), cz), 8, 32);
                    String clash2 = deepluckyblock.util.StructureSites.conflict(lvl, shifted.getX(), shifted.getZ(), checkR);
                    if (clash2 != null) {
                        LOGGER.info("[STRUCT5-FLAT] candidat {},{} REJETE : emprise '{}' (decalage libre vers {},{} -> emprise '{}') (T27/T33)",
                                cx, cz, clash1, shifted.getX(), shifted.getZ(), clash2);
                        continue;
                    }
                    LOGGER.info("[STRUCT5-FLAT] candidat {},{} DECALE au lieu d'etre rejete (emprise '{}') -> nouveau point {},{} libre (T33)",
                            cx, cz, clash1, shifted.getX(), shifted.getZ());
                    cx = shifted.getX(); cz = shifted.getZ();
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
        candidates.sort((a, b) -> Double.compare(b.total(), a.total()));
        LOGGER.info("[STRUCT5-FLAT] scoring termine : {} candidat(s) en {} ms (joueur {} ms, emprises {} ms, mesures terrain {} ms) -- T57",
                candidates.size(), tPlayerMs + tSiteMs + tMeasureMs, tPlayerMs, tSiteMs, tMeasureMs);
        if (wetRanked > 0)
            LOGGER.info("[STRUCT5-FLAT] {} candidat(s) ecarte(s) : surface en EAU (un lac est plat mais inconstructible) -- T75", wetRanked);
        // T33 : 12 -> 24 candidats verifies. Avec le decalage des emprises
        // chevauchantes (ci-dessus), la liste des candidats n'est plus videe par les
        // collisions et on a le droit d'aller chercher plus loin avant de tomber sur
        // le repli « le moins pentu ». Le budget de temps (FIND_FLAT_TIME_BUDGET_MS)
        // reste la vraie borne, donc aucun risque de gel.
        int maxVerified = Math.min(candidates.size(), 24); // essaie jusqu'a 24 candidats avant d'abandonner
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
        for (int i = 0; i < maxVerified; i++) {
            if (i > 0 && System.currentTimeMillis() - verifyStart > FIND_FLAT_TIME_BUDGET_MS) {
                LOGGER.warn("[STRUCT5-FLAT] Budget de temps ({}ms) depasse apres {} candidat(s) verifie(s) -- abandon des {} candidats restants",
                        FIND_FLAT_TIME_BUDGET_MS, i, maxVerified - i);
                break;
            }
            FlatCandidate c = candidates.get(i);
            LOGGER.info("[STRUCT5-FLAT] Verification candidat #{}/{} a {},{} (force generation de la zone, checkR={})...",
                    i + 1, maxVerified, c.cx(), c.cz(), checkR);

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
                // Zone pas encore prete : on demande en tache de fond et on differe le candidat.
                deepluckyblock.util.SafeSurface.requestZone(lvl, ccx0, ccz0, ccx1, ccz1);
                LOGGER.info("[STRUCT5-FLAT] Candidat #{}/{} a {},{} : zone pas encore en memoire -- verification differee (tache de fond, aucun blocage)",
                        i + 1, maxVerified, c.cx(), c.cz());
                pendingVerif.add(c);
                continue;
            }
            if (realFq == null) continue; // devenu invalide une fois genere (ex: fond marin)
            // T75 : refus AVANT l'analyse de securite (qui declenchait sinon une
            // relocalisation aleatoire "Zone sure a ..." et un ancrage dans les airs).
            if (realFq.wetRatio() > FLAT_MAX_WET) {
                LOGGER.warn("[STRUCT5-FLAT] Candidat {},{} REJETE : zone en EAU ({} % des colonnes sont liquides) -- "
                        + "plate mais inconstructible (T75)", c.cx(), c.cz(), (int)(realFq.wetRatio() * 100));
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
                LOGGER.warn("[STRUCT5-FLAT] Candidat {},{} REJETE : terrain fantome demasque apres generation reelle des chunks (pente avant={}, apres={}, flat avant={}%, apres={}%)",
                        c.cx(), c.cz(), c.slope(), realFq.slope(), (int)(c.extent()*100), (int)(realFq.flatExtent()*100));
                continue;
            }
            int realY = deepluckyblock.util.SafeSurface.surfaceY(lvl, c.cx(), c.cz(), origin.getY());
            LOGGER.info("[STRUCT5-FLAT] Zone choisie a {},{} (candidat #{}/{}) : flat={}%, pente={} (score={}, terrain reel verifie, Y reel={})",
                    c.cx(), c.cz(), i + 1, maxVerified, (int)(realFq.flatExtent() * 100), realFq.slope(), (int) c.total(), realY);
            return new BlockPos(c.cx(), realY, c.cz());
        }
        // T49 : seconde passe sur les candidats differes (chunks demandes en tache de
        // fond pendant la premiere passe). Beaucoup sont prets a ce stade.
        for (FlatCandidate c : pendingVerif) {
            if (System.currentTimeMillis() - verifyStart > FIND_FLAT_TIME_BUDGET_MS) break;
            int ccx0 = (c.cx() - checkR) >> 4, ccx1 = (c.cx() + checkR) >> 4;
            int ccz0 = (c.cz() - checkR) >> 4, ccz1 = (c.cz() + checkR) >> 4;
            FlatQuality realFq2 = qualityOnReady(lvl, c.cx(), c.cz(), checkR);   // T64 : sans attente
            if (realFq2 == null) continue;
            if (realFq2.wetRatio() > FLAT_MAX_WET) continue;   // T75 : zone en eau
            if (Math.abs(realFq2.slope() - c.slope()) > (FLAT_TOLERANCE * 2) || realFq2.flatExtent() < 0.5) continue;
            int realY2 = deepluckyblock.util.SafeSurface.surfaceY(lvl, c.cx(), c.cz(), origin.getY());
            LOGGER.info("[STRUCT5-FLAT] Zone choisie a {},{} (seconde passe T49, terrain reel verifie, Y reel={})",
                    c.cx(), c.cz(), realY2);
            return new BlockPos(c.cx(), realY2, c.cz());
        }
        if (!candidates.isEmpty()) {
            LOGGER.warn("[STRUCT5-FLAT] {} candidats testes, tous rejetes comme terrain fantome -- fallback pente minimale", maxVerified);
        }
        // Aucun candidat solide (tout air/eau, ex: ocean). On retombe sur la pente
        // minimale (mesure tolerante). findFlat ne retourne JAMAIS null, sinon la
        // structure tombe sur un flanc et une partie part dans le vide.
        // Meme correctif "chunk fantome" que ci-dessus : on garde les meilleurs
        // candidats et on force la generation reelle avant de valider le premier.
        record SlopeCandidate(int cx, int cz, int slope) {}
        List<SlopeCandidate> slopeCandidates = new ArrayList<>();
        // T79 : CE REPLI A DEJA FIGE LE SERVEUR 60 s (watchdog, run du 25/09 02:30).
        // Cause exacte : le scoring ne mesure QUE les chunks deja en memoire (grille
        // de 49x49 cellules, 484 connues, 3 ms) alors que cette boucle-ci mesurait
        // TOUTES les cellules du carre de recherche avec measureSlope(), soit
        // jusqu'a 121x121 = 14 641 cellules x ~49 lectures de heightmap = ~700 000
        // lectures. Une heightmap non primee coute un rescan COMPLET du chunk (voir
        // T58, mesure : 180 us par lecture) : d'ou un blocage silencieux de 60 s,
        // sans aucune ligne de log dans l'intervalle. Memes garde-fous que partout
        // ailleurs dans le projet : (1) on ne mesure QUE les chunks deja charges --
        // meme filtre que la grille de scoring, zero generation declenchee ;
        // (2) budget de temps de veille, teste en cours de balayage ; (3) sonde
        // toutes les 1500 ms : plus jamais de gel muet a cet endroit.
        final long SLOPE_FALLBACK_BUDGET_MS = 500;
        long slopeScanStart = System.currentTimeMillis();
        long slopeProbeMark = slopeScanStart;
        int slopeCells = 0, slopeSkipped = 0;
        LOGGER.info("[STRUCT5-FLAT] repli pente-minimale : balayage {}x{} cellules (pas {}), chunks deja charges seulement, budget {}ms -- T79",
                (2 * searchR / SEARCH_S + 1), (2 * searchR / SEARCH_S + 1), SEARCH_S, SLOPE_FALLBACK_BUDGET_MS);
        slopeScan:
        for (int dx = -searchR; dx <= searchR; dx += SEARCH_S) {
            for (int dz = -searchR; dz <= searchR; dz += SEARCH_S) {
                if ((slopeCells & 63) == 0
                        && System.currentTimeMillis() - slopeScanStart > SLOPE_FALLBACK_BUDGET_MS) {
                    LOGGER.warn("[STRUCT5-FLAT] repli pente-minimale : budget de {}ms atteint apres {} cellule(s) mesuree(s) ({} ignoree(s) faute de chunk charge, {} candidat(s)) -- arret du balayage (T79)",
                            SLOPE_FALLBACK_BUDGET_MS, slopeCells, slopeSkipped, slopeCandidates.size());
                    break slopeScan;
                }
                if (System.currentTimeMillis() - slopeProbeMark > 1500) {
                    slopeProbeMark = System.currentTimeMillis();
                    LOGGER.info("[STRUCT5-FLAT-PROBE] repli pente-minimale en cours : {} mesuree(s), {} ignoree(s), {} candidat(s) (T79)",
                            slopeCells, slopeSkipped, slopeCandidates.size());
                }
                int cx = origin.getX() + dx, cz = origin.getZ() + dz;
                // T79 : chunk absent -> mesure impossible (et couteuse) : on ne
                // declenche rien ici, on ignore la cellule.
                if (!deepluckyblock.util.SafeSurface.loaded(lvl, cx >> 4, cz >> 4)) { slopeSkipped++; continue; }
                slopeCells++;
                // Meme correctif que ci-dessus : distance HORIZONTALE uniquement.
                if (p != null) {
                    double dpx = cx - p.blockPosition().getX(), dpz = cz - p.blockPosition().getZ();
                    if ((dpx * dpx + dpz * dpz) < (double) minCenterDist * minCenterDist) continue;
                }
                if (isPlayerChunk(new BlockPos(cx, 64, cz), p, origin)) continue;
                String clash2 = deepluckyblock.util.StructureSites.conflict(lvl, cx, cz, checkR);   // T27
                if (clash2 != null) continue;
                int slope = measureSlope(lvl, cx, cz, checkR);
                slopeCandidates.add(new SlopeCandidate(cx, cz, slope));
            }
        }
        LOGGER.info("[STRUCT5-FLAT] repli pente-minimale : {} cellule(s) mesuree(s) en {} ms, {} ignoree(s) (chunk non charge), {} candidat(s) retenu(s) -- T79",
                slopeCells, System.currentTimeMillis() - slopeScanStart, slopeSkipped, slopeCandidates.size());
        slopeCandidates.sort(Comparator.comparingInt(SlopeCandidate::slope));
        int maxSlopeVerified = Math.min(slopeCandidates.size(), 12);
        // FIX (meme freeze silencieux que ci-dessus, voir Javadoc en tete de
        // findFlat) : ce fallback pente-minimale force EXACTEMENT le meme
        // genre de generation de chunks lourde -- il a besoin du meme budget.
        long verifySlopeStart = System.currentTimeMillis();
        for (int i = 0; i < maxSlopeVerified; i++) {
            if (i > 0 && System.currentTimeMillis() - verifySlopeStart > FIND_FLAT_TIME_BUDGET_MS) {
                LOGGER.warn("[STRUCT5-FLAT] Budget de temps ({}ms) depasse (fallback pente-minimale) apres {} candidat(s) -- abandon des {} candidats restants",
                        FIND_FLAT_TIME_BUDGET_MS, i, maxSlopeVerified - i);
                break;
            }
            SlopeCandidate c = slopeCandidates.get(i);
            int ccx0 = (c.cx() - checkR) >> 4, ccx1 = (c.cx() + checkR) >> 4;
            int ccz0 = (c.cz() - checkR) >> 4, ccz1 = (c.cz() + checkR) >> 4;
            if (!forceLoadZoneBudgeted(lvl, ccx0, ccz0, ccx1, ccz1, verifyStart)) {
                LOGGER.warn("[STRUCT5-FLAT] Budget de temps depasse PENDANT la generation du candidat pente-minimale -- abandon");
                break;
            }
            int realSlope = measureSlope(lvl, c.cx(), c.cz(), checkR);
            if (Math.abs(realSlope - c.slope()) > (FLAT_TOLERANCE * 2)) {
                LOGGER.warn("[STRUCT5-FLAT] Candidat pente-minimale {},{} REJETE : terrain fantome (pente avant={}, apres={})",
                        c.cx(), c.cz(), c.slope(), realSlope);
                continue;
            }
            int slY = deepluckyblock.util.SafeSurface.surfaceY(lvl, c.cx(), c.cz(), origin.getY());
            LOGGER.warn("[STRUCT5-FLAT] Aucune zone solide plate valide, utilise la pente minimale verifiee (diff={}) a {},{} (Y reel={})", realSlope, c.cx(), c.cz(), slY);
            return new BlockPos(c.cx(), slY, c.cz());
        }
        // FIX T7 (log en jeu : "[STRUCT5-FLAT] Aucune zone valide trouvee" suivi
        // d'une structure ancree a Y=-65/-78) : findFlat ne renvoie plus null des
        // qu'un seul candidat a ete mesure. Renvoyer null faisait retomber tout
        // l'appelant sur findSafeOutsideChunkPos(), dont la hauteur etait lue sur
        // un chunk non genere -- la structure finissait alors sous terre, ce qui
        // est bien pire qu'une zone "pas parfaite". On assume desormais le
        // meilleur candidat mesure, avec une hauteur REELLE (chunk force).
        if (!candidates.isEmpty()) {
            FlatCandidate c = candidates.get(0);
            int y = deepluckyblock.util.SafeSurface.surfaceY(lvl, c.cx(), c.cz(), origin.getY());
            LOGGER.warn("[STRUCT5-FLAT] Aucun candidat n'a pu etre verifie dans le budget de temps -- repli sur le meilleur candidat mesure {},{} (pente mesuree={}, Y reel={})",
                    c.cx(), c.cz(), c.slope(), y);
            return new BlockPos(c.cx(), y, c.cz());
        }
        if (!slopeCandidates.isEmpty()) {
            SlopeCandidate c = slopeCandidates.get(0);
            int y = deepluckyblock.util.SafeSurface.surfaceY(lvl, c.cx(), c.cz(), origin.getY());
            LOGGER.warn("[STRUCT5-FLAT] Aucune zone mesuree exploitable -- repli sur la pente minimale brute {},{} (Y reel={})", c.cx(), c.cz(), y);
            return new BlockPos(c.cx(), y, c.cz());
        }
        LOGGER.warn("[STRUCT5-FLAT] Aucune zone valide trouvee -- repli sur une position hors chunk avec hauteur REELLE");
        return findSafeOutsideChunkPos(lvl, origin);
    }

    // Ecart max (en blocs) pour qu'une colonne compte comme "plate" par rapport au
    // centre du candidat. Sert a mesurer la TAILLE de la zone plate (flatExtent).
    private static final int FLAT_TOLERANCE = 4;

    // Qualite d'une zone candidate : slope (ecart max de hauteur) + flatExtent
    // (fraction des echantillons dans la tolerance = a quel point la zone plate est
    // GRANDE). null si le centre est air/eau/non-solide (candidat invalide).
    /** T75 : wetRatio = part des colonnes mesurees dont la SURFACE est un liquide. */
    private record FlatQuality(int slope, double flatExtent, double wetRatio) {}

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
        if (known * 10 < total * 3) return null;      // moins de 30 % de terrain reel : on ne juge pas
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
                int y = deepluckyblock.util.SafeSurface.height(l, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + dx, cz + dz) - 1;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        return maxY - minY;
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
        LOGGER.info("[STRUCT5] === PASTE {} === off=[{},{},{}] dist={} facing={}", nbt, ox, oy, oz, dist, nf);
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
        BlockPos txz;
        if (useFlat) {
            BlockPos f = findFlat(level, origin, Math.abs(size.getX()), Math.abs(size.getZ()), minEdgeDist);
            if (f != null) txz = f;
            else if (dist > 0) { txz = findFarthestLoadedPos(level, origin, dist, minChunksReq); if (txz == null) return false; }
            else txz = findSafeOutsideChunkPos(level, origin);
        } else if (dist > 0) {
            txz = findFarthestLoadedPos(level, origin, dist, minChunksReq); if (txz == null) return false;
        } else txz = findSafeOutsideChunkPos(level, origin);

        BlockPos safePos = resolveSafePosition(level, txz, nbt);
        if (safePos == null) return false;
        txz = safePos;
        if (isPlayerChunk(txz, pl, origin)) txz = findSafeOutsideChunkPos(level, origin);
        // T29 : garde-fou au point de passage commun (voir StructureSites) --
        // couvre aussi le repli « pente minimale brute » des replis de recherche.
        txz = deepluckyblock.util.StructureSites.freeOffset(level, txz, 8, 32);
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
        Rotation rot = pickRotation(fp, pl, nf, level.getRandom());
        BlockPos rp = adjRot(fp, tmpl, rot);
        LOGGER.info("[STRUCT5] {} -> {} (baseY={}, minRelY={}, rot={}, {}ms)", nbt, rp.toShortString(), baseY, minRelY, rot, System.currentTimeMillis() - tGlobal);
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
        BlockPos min = new BlockPos(bMinX, bMinY, bMinZ);
        BlockPos max = new BlockPos(bMaxX, bMaxY, bMaxZ);
        // T14 : groupe par section 16x16x16 + spirale (guide sections 42/43).
        filt = orderBySectionSpiral(filt, bboxRs, rp);
        LOGGER.info("[STRUCT5] {} : {} a poser ({} verre supprime, air exterieur deja ecarte au chargement) -- T73",
                nbt, filt.size(), glassSkipped);
        final List<StructureTemplate.StructureBlockInfo> fFilt = filt;
        final BlockPos fTxz = txz;   // XZ ou baseY a ete mesure (AVANT smooth)
        final int fBaseY = baseY;    // surface AVANT smooth
        final BlockPos fRp = rp, fMin = min, fMax = max;
        StructureTerrainPrep.setTerrainRingScalePercent(ringScaleFor(nbt));
        StructureTerrainPrep.prepZone(level, min, max, fBaseY + oy, () -> {
            // RE-mesurer la surface APRES le smooth : la structure doit rentrer dans
            // le sol du terrain LISSE (pas l'ancien). On ne touche que Y (X/Z = idem,
            // donc l'alignement smooth/structure reste parfait).
            // FIX T7 : re-mesure sur un chunk dont la heightmap est GARANTIE
            // initialisee (voir SafeSurface) -- c'est cette mesure qui pilote le
            // placement final, elle ne doit jamais pouvoir renvoyer -65.
            // T75 : l'ancrage post-smooth ne lit plus UNE colonne mais le SOL MEDIAN
            // de toute l'emprise. Une cime, un pilier ou un pic de 2x2 blocs sur la
            // colonne du centre suffisait a decaler la structure dans le vide (c'est
            // la cause directe du re-ancrage "baseY 88 -> 87" inoperant en jeu).
            int[] gs = deepluckyblock.util.SafeSurface.groundStats(level,
                    fMin.getX(), fMin.getZ(), fMax.getX(), fMax.getZ(), 4, fBaseY,
                    deepluckyblock.util.SafeSurface.groundY(level, fTxz.getX(), fTxz.getZ(), fBaseY));
            int newBaseY = gs[0];
            LOGGER.info("[STRUCT5] {} : ancrage T75 -- sol median de l'emprise={} (min={}, max={}, {} colonnes mesurees)",
                    nbt, gs[0], gs[1], gs[2], gs[3]);
            if (gs[3] > 0 && (gs[2] - gs[1]) > 8)
                LOGGER.warn("[STRUCT5] {} : sol de l'emprise tres irregulier (min={}, max={}, ecart={}) -- ancrage sur la mediane {} (T75)",
                        nbt, gs[1], gs[2], gs[2] - gs[1], gs[0]);
            // ================================================================
            // T74 : VERIFICATION ANTI-VOL (consigne utilisateur du 23/09 22:42 :
            // « les structures sont dans le ciel ! elles vollent au dessus de la
            // terre c'est pas normal ! »)
            // ================================================================
            // Le sol REEL est re-mesure BLOC PAR BLOC sous toute l'emprise (jamais
            // la heightmap : elle rend la cime des arbres) et compare au BAS DE LA
            // STRUCTURE tel qu'il sera pose, offset `oy` compris. Si ce bas est
            // au-dessus du sol de plus d'un bloc, la structure flotterait : on la
            // descend exactement de l'ecart. Aucune structure ne peut plus voler.
            if (newBaseY > level.getMinBuildHeight()) {
                int[] g2 = deepluckyblock.util.SafeSurface.groundStats(level,
                        fMin.getX(), fMin.getZ(), fMax.getX(), fMax.getZ(), 6, newBaseY, newBaseY);
                int structBase = newBaseY + oy;              // bas de la structure (offset inclus)
                if (g2[3] > 0) {
                    if (g2[0] < structBase - 1) {
                        int fix = (g2[0] - 1) - structBase;   // negatif : on descend
                        LOGGER.warn("[STRUCT5] {} : structure SUSPENDUE de {} bloc(s) (bas de structure={}, "
                                        + "sol reel median={}, {} colonnes mesurees) -- ancrage corrige de {} bloc(s) (T74)",
                                nbt, structBase - g2[0], structBase, g2[0], g2[3], fix);
                        newBaseY += fix;
                    } else {
                        LOGGER.info("[STRUCT5] {} : controle anti-vol OK -- bas de structure={}, sol reel median={} "
                                        + "(marge={} bloc(s) d'enfoncement, {} colonnes mesurees, T74)",
                                nbt, structBase, g2[0], g2[0] - structBase, g2[3]);
                    }
                }
            }
            // GARDE-FOU : si la mesure est impossible (chunk pas encore genere), on
            // NE deplace PAS la structure (deltaY=0) au lieu de la deplacer vers
            // une hauteur inventee. C'est le filet de securite qui remplace
            // l'ancien bornage : en cas de doute, on ne touche a rien.
            boolean surfaceUnreliable = newBaseY <= level.getMinBuildHeight();
            if (surfaceUnreliable) {
                LOGGER.error("[STRUCT5] {} : hauteur post-smooth ILLISIBLE en {},{} (chunk pas encore genere) -- "
                                + "structure laissee a sa position initiale (baseY={})", nbt, fTxz.getX(), fTxz.getZ(), fBaseY);
            }
            int rawDeltaY = surfaceUnreliable ? 0 : newBaseY - fBaseY;
            // ================================================================
            // RE-ANCRAGE APRES SMOOTH (reecrit en T7)
            // ================================================================
            //
            // HISTORIQUE DU BUG (3 versions successives, toutes fausses) :
            //   v1 : la structure suivait TOUJOURS la re-mesure -> avec une
            //        mesure "fantome" (-65 au lieu de 71) elle remontait de 136
            //        blocs et ecrasait l'offset manuel oy (plainte utilisateur :
            //        « la structure est en partie DANS le sol, la ca fonctionne
            //        plus du tout »).
            //   v2 : correction descendante uniquement -> la structure restait
            //        figee a -78 pendant que le smooth construisait le vrai sol
            //        a 71 : ENTERREE de 149 blocs (le fameux "deltaY anormal
            //        BORNE a 0").
            //   v3 (celle-ci) : le vrai probleme n'etait ni le sens ni le
            //        bornage, mais la MESURE. La heightmap du chunk n'etait pas
            //        initialisee (-65 = minBuildHeight), donc fBaseY etait faux
            //        et le "delta" corrigeait une erreur... vers une autre
            //        erreur. Desormais fBaseY (avant smooth) ET newBaseY (apres
            //        smooth) sont lus via SafeSurface : chunk force en FULL +
            //        heightmap initialisee. Les deux mesures decrivent donc le
            //        MEME sol reel, et le delta qui les separe est petit et
            //        legitime.
            //
            // La structure suit ce delta dans les DEUX sens (plus de bornage) :
            //   - c'est ce qui garantit qu'elle reste posee sur le sol du
            //     terrain LISSE, et non sur celui d'avant le smooth ;
            //   - l'offset manuel oy reste applique par-dessus
            //     (fp = baseY + oy - minRelY), donc les structures volontairement
            //     enfoncees dans le sol (citadel -35, dragon -34, observatory -5)
            //     sont de nouveau respectees ;
            //   - au-dela de MAX_POST_SMOOTH_DELTA_Y on log un WARN bien visible :
            //     signe qu'une mesure a encore menti, a diagnostiquer.
            int deltaY = rawDeltaY;
            // T75 : PREUVE chiffree dans le log -- ecart entre le 1er bloc reel de la
            // structure et le sol median mesure. <= 0 : la structure touche/s'enfonce
            // dans le sol. > +2 : elle flotte (c'est ce qu'on interdit).
            LOGGER.info("[STRUCT5] {} : ancrage FINAL -- baseY={} et 1er bloc reel a Y={} (ecart au sol = {}, "
                    + "deltaY={}) : <=0 = pose sur le sol, >+2 = SUSPENDU (T75)",
                    nbt, newBaseY, fRp.getY() + deltaY + floorRelYFinal,
                    (fRp.getY() + deltaY + floorRelYFinal) - newBaseY, deltaY);
            BlockPos pasteRp = fRp.offset(0, deltaY, 0);
            BlockPos pasteMin = fMin.offset(0, deltaY, 0);
            BlockPos pasteMax = fMax.offset(0, deltaY, 0);
            if (Math.abs(rawDeltaY) > MAX_POST_SMOOTH_DELTA_Y)
                LOGGER.warn("[STRUCT5] {} : ecart de sol avant/apres smooth = {} blocs ({} -> {}) -- la structure suit le sol REEL "
                                + "(ancien comportement : borne a 0 = structure enterree/suspendue). Si ce chiffre est enorme, "
                                + "c'est qu'une lecture de hauteur a encore menti : voir les lignes [DLB-SURFACE] ci-dessus.", nbt, rawDeltaY, fBaseY, newBaseY);
            else if (deltaY != 0)
                LOGGER.info("[STRUCT5] {} : re-ancrage Y apres smooth (baseY {} -> {}, deltaY={})", nbt, fBaseY, newBaseY, deltaY);

            // Controle de coherence : si le sol de l'emprise n'est pas au niveau
            // du point de mesure, on le signale (diagnostic, pas de correction
            // automatique -- le smooth est cense avoir aplani l'emprise).
            int halfSpan = Math.max(Math.abs(fMax.getX() - fMin.getX()), Math.abs(fMax.getZ() - fMin.getZ())) / 2;
            int medianY = deepluckyblock.util.SafeSurface.medianSurfaceY(level, fTxz.getX(), fTxz.getZ(), Math.min(24, halfSpan), 4);
            if (medianY != Integer.MIN_VALUE && Math.abs(medianY - newBaseY) > 8)
                LOGGER.warn("[STRUCT5] {} : sol de l'emprise irregulier apres smooth (median={} contre {} au centre) -- la structure est ancree au centre", nbt, medianY, newBaseY);

            // NOTE (T7) : l'ancien filet de securite backfillVoidGap() (plateau
            // + rampe qui NIVELAIT tout le terrain au-dessus de l'emprise --
            // 279795 blocs detruits sur la citadelle) n'est plus appele : il ne
            // corrigeait que l'ecart vide cree par l'ANCIEN bornage de deltaY.
            // La structure suivant desormais le sol reel, cet ecart ne peut plus
            // apparaitre. La methode reste disponible dans StructureTerrainPrep.
            // T27 : emprise enregistree AVANT la pose -- une structure qui demarrerait
            // pendant ce paste la verrait et partirait ailleurs.
            // ================================================================
            // T39 : TOUT LE TERRAIN D'ABORD, LA STRUCTURE EN ABSOLU DERNIER
            // ================================================================
            // Consigne dev (23/09) : « la structure doit paste en absolu dernier,
            // apres tous les smooths ». Le paste n'est donc plus offert ici : il
            // part du rappel de fin de la phase terrain (smooths, failles, eau,
            // /fixwater + /fixlava compris).
            StructureTerrainPrep.decorateTerrainOnly(level, pasteMin, pasteMax, () ->
                // T43 : l'emprise du paste est RE-SCELLEE juste avant la pose (le
                // terrain a travaille ~40 s depuis le pre-chargement de prepZone :
                // sans ce rappel, la pose reclamait 160 chunks et gelait le jeu).
                StructureTerrainPrep.preloadBox(level, pasteMin, pasteMax, () -> {
                    deepluckyblock.util.StructureSites.register(level, nbt,
                            Math.min(pasteMin.getX(), pasteMax.getX()), Math.min(pasteMin.getZ(), pasteMax.getZ()),
                            Math.max(pasteMin.getX(), pasteMax.getX()), Math.max(pasteMin.getZ(), pasteMax.getZ()));
                    PASTE_Q.offer(new PasteJob(level, fFilt, pasteRp, rot, Mirror.NONE, nbt, tGlobal, pl, pasteMin, pasteMax));
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
            LOGGER.info("[STRUCT5-EVEREST] Coffre retire: {}", c.toShortString());
        }
        // Sur les coffres conserves : retirer tout item Shadow Essence du contenu.
        for (int i = 0; i < kept; i++) {
            removeShadowEssence(level, chests.get(i));
        }
        LOGGER.info("[STRUCT5-EVEREST] {} coffres retires, {} conserves (au hasard, sans Shadow Essence)", removed, kept);
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
                    LOGGER.info("[STRUCT5-EVEREST] Shadow Essence retire de {}", pos.toShortString());
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
        LOGGER.info("[STRUCT5-EVEREST] {} mobs spawnes sur la montagne (centre env. {},{})", spawned,
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
