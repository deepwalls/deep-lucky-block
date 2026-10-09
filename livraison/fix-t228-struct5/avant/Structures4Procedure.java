package deepluckyblock.procedures;

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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.*;

import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = "deep_lucky_block")
public class Structures4Procedure {

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
    private static final int LIGHT_UPDATE_FLAG = 2;
    private static final int BLOCKS_PER_TICK = 25000;
    // T33 (constat en jeu : « everest 61 s / LAGPROBE 36 824 ms » pendant
    // `paste everest (blocs)`) : un QUOTA DE BLOCS par tick ne borne rien du
    // tout en temps reel. Sur une machine plus lente / avec une vue plus
    // grande, 25 000 ecritures + mises a jour client peuvent couter plusieurs
    // secondes. On borne donc desormais aussi -- et surtout -- en MILLISECONDES
    // (S.32/S.41 du guide : time-slicing).
    private static final long PASTE_TICK_BUDGET_MS = 40;
    private static final int EVEREST_BLOCKS_PER_TICK = 100000;
    private static final long EVEREST_TICK_TIMEOUT_MS = 90;   // T33 : 300 -> 90 ms
    private static final int MAX_EVEREST_BLOCKS = 50_000_000;
    private static final int MIN_CHUNKS_NORMAL = 4;
    private static final int MIN_CHUNKS_EVEREST = 6;
    private static final int PROGRESS_LOG_INTERVAL = 20;
    private static final int EVEREST_FLAG = FAST_FLAG;

    /**
     * T102 (rappel dev 30/09 : « TOUTES les structures doivent etre paste -air,
     * les mini structures etc sont aussi pastees sans air... etc ! »).
     * false = paste -a strict : AUCUNE entree d'air n'entre dans la liste de
     * pose, y compris l'air d'interieur (ancien carve T73 -- desormais inutile :
     * le cut d'emprise a deja vide le volume au-dessus du sol planifie).
     * true = retablit le carve d'interieur historique (echappatoire documentee
     * en cas d'interieur bouche par du terrain residuel).
     */
    public static boolean PASTE_KEEP_INTERIOR_AIR = false;
    private static final int FLAT_AREA_SIZE = 10;
    private static final int MAX_HEIGHT_DIFF = 3;

    // FIX (rapporte en jeu, corrige ensuite : "augmente les prix de manish
    // random de son prix actuel x0.75 a x5" -> precision utilisateur :
    // "plutot prix original x1.1-x8 random") : multiplicateur applique au
    // COUT (jamais a la recompense) de chaque offre du villageois "Manish"
    // spawn au sommet de l'Everest. Chaque offre tire desormais SON PROPRE
    // multiplicateur aleatoire independant dans [MIN, MAX] (au lieu d'un
    // facteur fixe unique x5) -- les 7 offres de Manish varient donc en
    // "cherete" les unes par rapport aux autres, plafonnees a la taille de
    // stack vanilla (64) pour rester valides.
    // FIX v2 (demande en jeu : "change aussi les prix de manish pour un
    // nouveau min qui est 0.8 ... on garde le meme max") : le minimum passe
    // de 1.1 (toujours plus cher que l'original) a 0.8 (peut desormais aussi
    // etre 20% MOINS cher que l'original), le maximum (x8) est inchange.
    private static final double MANISH_PRICE_MULT_MIN = 0.8;
    private static final double MANISH_PRICE_MULT_MAX = 8.0;

    private static int manishPriced(int baseCount, RandomSource random) {
        double mult = MANISH_PRICE_MULT_MIN
                + random.nextFloat() * (MANISH_PRICE_MULT_MAX - MANISH_PRICE_MULT_MIN);
        int scaled = (int) Math.round(baseCount * mult);
        return Math.max(1, Math.min(64, scaled));
    }
    private static final int SEARCH_RADIUS = 80;
    // FIX (freeze silencieux au paste, voir Javadoc dans findFlatArea) : budget
    // de temps maximum pour la verification "chunks reels" des candidats.
    private static final long FIND_FLAT_TIME_BUDGET_MS = 3000;
    private static final int SEARCH_STEP = 5;
    private static final double VIEW_CONE = Math.PI / 2.0;
    private static final int SAFETY_CHECK_RADIUS = 6;
    private static final int SAFETY_CHECK_DEPTH = 2;
    private static final double WATER_MAX_RATIO = 0.15;
    private static final double LAVA_MAX_RATIO = 0.05;
    private static final int MAX_CLIFF_HEIGHT_DIFF = 10;
    private static final int ALT_SEARCH_RADIUS = 60;
    private static final int ALT_SEARCH_STEP = 10;
    private static final int VEG_RADIUS = 3;        // T23 : rayon de nettoyage vegetal
    private static final long VEG_SLICE_NANOS = 12_000_000L;
    private static final int CARVE_COLS_PER_TICK = 600;
    private static final int CARVE_CANOPY = 8;
    private static final int RING_PER_TICK = 6000;
    private static final int GROUND_MAX_DEPTH = 64;
    private static final int GROUND_COLS_PER_TICK = 200;
    // On ne rebouche (motte) QUE les colonnes dont lo est pres du niveau de base
    // reel (median des lo). Les colonnes de toit/auvent (lo tres haut) sont
    // IGNOREES, sinon on cree des piliers de dirt qui pendant des bords du toit.
    // Rien n'est jamais place au-dessus du dernier bloc structure.
    private static final int FLOOR_TOLERANCE = 4;

    private static long colKey(int x, int z) { return (Integer.toUnsignedLong(x) << 32) | Integer.toUnsignedLong(z); }
    private static int keyX(long k) { return (int) (k >>> 32); }
    private static int keyZ(long k) { return (int) k; }

    // Verre standard sans couleur uniquement (les command blocks sont deja filtres par isLaggy/isLaggyBlock).
    private static boolean isGlassBlock(BlockState s) {
        return s.is(Blocks.GLASS);
    }

    private static final String CIRCUS_NBT = "circus";
    public static int CIRCUS_OFFSET_X        = -60;
    public static int CIRCUS_OFFSET_Y        = -18;
    public static int CIRCUS_OFFSET_Z        = -89;
    public static int CIRCUS_DISTANCE        = 0;
    public static Direction CIRCUS_NATIVE_FACING = Direction.SOUTH;
    public static boolean CIRCUS_FOLLOW_SURFACE  = true;
    public static boolean CIRCUS_USE_FLAT_AREA   = true;
    public static boolean CIRCUS_PRESERVE_INTERIOR = true;

    private static final String SHIPDEAD_NBT = "shipdead";
    public static int SHIPDEAD_OFFSET_X      = -20;
    public static int SHIPDEAD_OFFSET_Y      = 0;
    public static int SHIPDEAD_OFFSET_Z      = 0;
    public static int SHIPDEAD_DISTANCE      = 0;
    public static Direction SHIPDEAD_NATIVE_FACING = Direction.SOUTH;
    public static boolean SHIPDEAD_FOLLOW_SURFACE  = true;
    public static boolean SHIPDEAD_USE_FLAT_AREA   = true;
    public static boolean SHIPDEAD_PRESERVE_INTERIOR = true;

    private static final String EVEREST_NBT = "everest";
    public static int EVEREST_OFFSET_X       = 0;
    public static int EVEREST_OFFSET_Y       = -25;
    /**
     * T28 : enfoncement supplementaire de l'Everest dans le terrain (blocs).
     * Consigne utilisateur du 20/09 : « l'everest etait sencé s'enfoncer dans le
     * terrain » -- il ne doit pas poser sa galette sur l'herbe mais entrer dans
     * le relief. 10 blocs (choix utilisateur du 22/09, apres un premier essai a 6) :
     * la base de la montagne disparait franchement dans le sol, aucune galette
     * flottante possible, sans pour autant noyer les premiers decors.
     */
    public static int EVEREST_SINK_BLOCKS   = 10;   // 22/09 : 6 -> 10 (choix utilisateur)
    /**
     * T32 : rayon maximal (blocs) de la boite pre-chargee pour l'everest.
     * 176 = de quoi couvrir les 296 blocs d'emprise (148 + marge) sans epingler une
     * zone demesuree : ~529 chunks, a comparer au MAX_PINNED de ChunkKeeper.
     */
    public static int EVEREST_PRELOAD_MAX_RING = 176;
    public static int EVEREST_OFFSET_Z       = 0;
    public static int EVEREST_DISTANCE       = 0;
    public static Direction EVEREST_NATIVE_FACING = Direction.SOUTH;
    public static boolean EVEREST_FOLLOW_SURFACE  = true;
    public static boolean EVEREST_USE_FLAT_AREA   = false;
    public static boolean EVEREST_PRESERVE_INTERIOR = false;

    private static boolean isLaggyBlock(BlockState state) {
        return state.is(Blocks.WHITE_CARPET) || state.is(Blocks.ORANGE_CARPET)
            || state.is(Blocks.MAGENTA_CARPET) || state.is(Blocks.LIGHT_BLUE_CARPET)
            || state.is(Blocks.YELLOW_CARPET) || state.is(Blocks.LIME_CARPET)
            || state.is(Blocks.PINK_CARPET) || state.is(Blocks.GRAY_CARPET)
            || state.is(Blocks.LIGHT_GRAY_CARPET) || state.is(Blocks.CYAN_CARPET)
            || state.is(Blocks.PURPLE_CARPET) || state.is(Blocks.BLUE_CARPET)
            || state.is(Blocks.BROWN_CARPET) || state.is(Blocks.GREEN_CARPET)
            || state.is(Blocks.RED_CARPET) || state.is(Blocks.BLACK_CARPET)
            || state.is(Blocks.MOSS_CARPET)
            || state.is(Blocks.TRIPWIRE)
            || state.is(Blocks.COMMAND_BLOCK) || state.is(Blocks.REPEATING_COMMAND_BLOCK) || state.is(Blocks.CHAIN_COMMAND_BLOCK);
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

    private static void killLaggyItems(ServerLevel level, BlockPos min, BlockPos max) {
        AABB box = new AABB(min.getX() - 2, min.getY() - 2, min.getZ() - 2, max.getX() + 2, max.getY() + 2, max.getZ() + 2);
        int killed = 0;
        for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
            ItemStack s = ie.getItem();
            if (s.is(Items.STRING) || s.is(Items.SNOW) || s.is(Items.SNOWBALL) || s.is(Items.DIRT) || s.is(Items.SAND) || s.is(Items.RED_SAND)
                || s.is(Items.GRAVEL) || s.is(Items.COARSE_DIRT) || s.getItem().getDescriptionId().contains("carpet")) { ie.discard(); killed++; }
        }
        if (killed > 0) deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} items laggy au sol nettoyés", killed);
    }

    private static class ZoneAnalysis {
        double waterRatio; double lavaRatio; int minY, maxY; int heightDiff;
        boolean hasTooMuchWater() { return waterRatio > WATER_MAX_RATIO; }
        boolean hasTooMuchLava() { return lavaRatio > LAVA_MAX_RATIO; }
        boolean hasCliff() { return heightDiff > MAX_CLIFF_HEIGHT_DIFF; }
        boolean isUnsafe() { return hasTooMuchWater() || hasTooMuchLava() || hasCliff(); }
        String getReason() {
            List<String> reasons = new ArrayList<>();
            if (hasTooMuchWater()) reasons.add("eau=" + (int) (waterRatio * 100) + "%");
            if (hasTooMuchLava()) reasons.add("lave=" + (int) (lavaRatio * 100) + "%");
            if (hasCliff()) reasons.add("falaise=" + heightDiff + "blocs");
            return String.join(", ", reasons);
        }
    }

    private static ZoneAnalysis analyzeZone(ServerLevel level, BlockPos center) {
        ZoneAnalysis result = new ZoneAnalysis();
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
        int waterCount = 0, lavaCount = 0, totalFluidChecks = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int dx = -SAFETY_CHECK_RADIUS; dx <= SAFETY_CHECK_RADIUS; dx += 3) {
            for (int dz = -SAFETY_CHECK_RADIUS; dz <= SAFETY_CHECK_RADIUS; dz += 3) {
                int px = center.getX() + dx, pz = center.getZ() + dz;
                if (level.getChunkSource().getChunkNow(px >> 4, pz >> 4) == null) continue;
                int surfY = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz) - 1;
                if (surfY < minY) minY = surfY; if (surfY > maxY) maxY = surfY;
                for (int dy = 0; dy >= -SAFETY_CHECK_DEPTH; dy--) {
                    check.set(px, surfY + dy, pz);
                    // T56 : lecture NON BLOQUANTE (un getBlockState sur chunk absent generait
                    // le chunk en synchrone, ici pour chacun des ~8 000 candidats testes).
                    BlockState state = deepluckyblock.util.SafeSurface.state(level, px, surfY + dy, pz);
                    if (state == null) continue;   // chunk pas encore en memoire : echantillon ignore
                    totalFluidChecks++;
                    if (state.is(Blocks.WATER) || (!state.getFluidState().isEmpty() && state.getFluidState().is(FluidTags.WATER))) waterCount++;
                    else if (state.is(Blocks.LAVA) || (!state.getFluidState().isEmpty() && state.getFluidState().is(FluidTags.LAVA))) lavaCount++;
                }
            }
        }
        if (totalFluidChecks == 0) return result;
        result.waterRatio = (double) waterCount / totalFluidChecks;
        result.lavaRatio = (double) lavaCount / totalFluidChecks;
        result.minY = minY; result.maxY = maxY; result.heightDiff = maxY - minY;
        return result;
    }

    private static BlockPos findSafeAlternative(ServerLevel level, BlockPos origin, boolean allowLavaFallback) {
        long t0 = System.currentTimeMillis();
        BlockPos bestWaterOnly = null;
        for (int radius = ALT_SEARCH_STEP; radius <= ALT_SEARCH_RADIUS; radius += ALT_SEARCH_STEP) {
            for (int dx = -radius; dx <= radius; dx += ALT_SEARCH_STEP) {
                for (int dz = -radius; dz <= radius; dz += ALT_SEARCH_STEP) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int cx = origin.getX() + dx, cz = origin.getZ() + dz;
                    int cy = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 1;
                    BlockPos candidate = new BlockPos(cx, cy, cz);
                    ZoneAnalysis analysis = analyzeZone(level, candidate);
                    if (!analysis.isUnsafe()) { deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-SAFETY] Zone sûre à {} (r={}, {}ms)", candidate.toShortString(), radius, System.currentTimeMillis() - t0); return candidate; }
                    if (bestWaterOnly == null && !analysis.hasTooMuchLava() && !analysis.hasCliff()) bestWaterOnly = candidate;
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
            // T56 : lecture NON BLOQUANTE -- un chunk absent interrompt le scan
            // au lieu de le generer en synchrone (jusqu'a 384 getBlockState).
            BlockState st = deepluckyblock.util.SafeSurface.state(level, x, y, z);
            if (st == null) return surfaceY;
            if (!st.getFluidState().isEmpty()) return y + 1;
        }
        return surfaceY;
    }

    private static BlockPos resolveSafePosition(ServerLevel level, BlockPos targetXZ, String nbtName) {
        // FIX T7 : l'analyse de zone lit la surface -- heightmap initialisee obligatoire.
        int surfaceY = deepluckyblock.util.SafeSurface.surfaceY(level, targetXZ.getX(), targetXZ.getZ(), targetXZ.getY());
        BlockPos surfPos = new BlockPos(targetXZ.getX(), surfaceY, targetXZ.getZ());
        ZoneAnalysis analysis = analyzeZone(level, surfPos);
        if (!analysis.isUnsafe()) { deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-SAFETY] Zone OK"); return targetXZ; }
        LOGGER.warn("[STRUCT4-SAFETY] {} : {} -> recherche alt...", nbtName, analysis.getReason());
        BlockPos safeAlt = findSafeAlternative(level, surfPos, true);
        if (safeAlt != null && !analyzeZone(level, safeAlt).hasTooMuchLava()) return safeAlt;
        if (analysis.hasTooMuchLava()) return null;
        if (analysis.hasTooMuchWater() && !analysis.hasCliff()) return new BlockPos(targetXZ.getX(), findFluidSurfaceY(level, targetXZ.getX(), targetXZ.getZ()), targetXZ.getZ());
        return targetXZ;
    }

    private static final List<DelayedAction> DELAYED_ACTIONS = new ArrayList<>();
    private static class DelayedAction { final Runnable action; int ticksRemaining; DelayedAction(Runnable a, int ticks) { action = a; ticksRemaining = ticks; } }

    private static final Queue<PasteJob> PASTE_QUEUE = new ArrayDeque<>();
    private static final Queue<CarveJob> CARVE_QUEUE = new ArrayDeque<>();
    private static final Queue<PostJob> POST_QUEUE = new ArrayDeque<>();

    // =======================================================================
    // FIX T7 : CHARGEMENT DU NBT HORS TICK (circus / shipdead / everest)
    // =======================================================================
    //
    // Meme probleme que citadel/observatory/dragon (voir StructureTemplateCache
    // .requestAsync) : StructureTemplateManager.get() deserialise TOUT le NBT
    // sur le thread serveur. Pour circus (2,05 Mo) et surtout shipdead (4,88 Mo,
    // ~1,2 M de blocs) cela represente jusqu'a ~1,5 s pendant lesquelles AUCUN
    // tick serveur n'avance -- le "freeze de 78 secondes" rapporte en jeu apres
    // le premier spawn de shipdead.
    //
    // Une structure dont le NBT n'est pas encore en memoire n'est donc plus
    // posee tout de suite : son chargement est lance sur le thread de fond et
    // la generation est reportee de quelques ticks (elle demarre seule, sans
    // aucune action du joueur, des que le fichier est parse). Le serveur ne
    // s'arrete jamais de ticker.
    private record DeferredSpawn(ServerLevel level, BlockPos origin, int id, String nbtName, long since) {}
    private static final List<DeferredSpawn> DEFERRED_SPAWNS = new ArrayList<>();
    private static final long DEFERRED_TIMEOUT_MS = 120_000L;

    /** Nom du fichier .nbt pour un id de Structures4 (3/4/5), sinon null. */
    private static String nbtForId(int id) {
        return switch (id) {
            case 3 -> CIRCUS_NBT;
            case 4 -> SHIPDEAD_NBT;
            case 5 -> EVEREST_NBT;
            default -> null;
        };
    }

    /**
     * Vrai si le template est utilisable MAINTENANT. Sinon, lance le chargement
     * en tache de fond et met la generation de cote (reprise automatique dans
     * onServerTick) : le thread serveur n'est jamais bloque par le parse.
     */
    private static boolean templateReadyOrDefer(ServerLevel level, BlockPos origin, int id, String nbtName) {
        if (nbtName == null) return true;
        if (deepluckyblock.util.StructureTemplateCache.isCached(nbtName)
                || deepluckyblock.util.StructureTemplateCache.hasFailed(nbtName)) return true;
        deepluckyblock.util.StructureTemplateCache.requestAsync(level, nbtName);
        boolean known = false;
        for (DeferredSpawn ds : DEFERRED_SPAWNS) if (ds.nbtName().equals(nbtName)) { known = true; break; }
        if (!known) {
            DEFERRED_SPAWNS.add(new DeferredSpawn(level, origin, id, nbtName, System.currentTimeMillis()));
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] '{}' : NBT charge en TACHE DE FOND -- generation retenue quelques ticks, "
                            + "le serveur n'est PAS bloque (elle demarrera seule des que le fichier sera pret).", nbtName);
        }
        return false;
    }

    /** Reprend les generations mises en attente de chargement NBT. */
    private static void tickDeferredSpawns() {
        if (DEFERRED_SPAWNS.isEmpty()) return;
        List<DeferredSpawn> ready = null;
        long now = System.currentTimeMillis();
        Iterator<DeferredSpawn> it = DEFERRED_SPAWNS.iterator();
        while (it.hasNext()) {
            DeferredSpawn ds = it.next();
            boolean loaded = deepluckyblock.util.StructureTemplateCache.isCached(ds.nbtName())
                    || deepluckyblock.util.StructureTemplateCache.hasFailed(ds.nbtName());
            if (loaded) {
                if (ready == null) ready = new ArrayList<>();
                ready.add(ds);
                it.remove();
            } else if (now - ds.since() > DEFERRED_TIMEOUT_MS) {
                it.remove();
                LOGGER.error("[STRUCT4] '{}' : chargement en tache de fond impossible apres {} s -- structure abandonnee",
                        ds.nbtName(), DEFERRED_TIMEOUT_MS / 1000);
            }
        }
        if (ready != null) {
            for (DeferredSpawn ds : ready) {
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] '{}' : NBT pret -- generation de la structure ID={} qui avait ete mise en attente", ds.nbtName(), ds.id());
                generate(ds.level(), ds.origin(), ds.id());
            }
        }
    }
    private static final Queue<EverestJob> EVEREST_QUEUE = new ArrayDeque<>();
    /** Ancres Everest de la session : complète StructureSites, dont le test
     * ponctuel a laissé repasser six fois exactement l'ancre -403,-163 en T179. */
    private static final java.util.Set<Long> EVEREST_SESSION_ANCHORS = new java.util.HashSet<>();
    static { PASTE_QUEUE.clear(); CARVE_QUEUE.clear(); POST_QUEUE.clear(); EVEREST_QUEUE.clear(); EVEREST_SESSION_ANCHORS.clear(); }

    private static class EverestJob {
        final ServerLevel level; final List<StructureTemplate.StructureBlockInfo> blocks;
        final BlockPos origin; final Rotation rotation; final Mirror mirror; final long startTime;
        final Player player; final Runnable onComplete; int index; int ticksElapsed;
        final BlockPos[] cherryHolder;
        // FIX (coffres Everest jamais nettoyes/lies au systeme de loot commun->ultimate,
        // voir Javadoc de Structures5Procedure#prepareAutomaticChest) : collecte au fil
        // du placement, exactement comme PasteJob.chests pour citadel/observatory/dragon.
        final List<BlockPos> chests = new ArrayList<>();
        /** T53 : memo de chunk pour la pose (une recherche par chunk au lieu d'une par bloc). */
        final deepluckyblock.util.SafeSurface.ChunkMemo memo = new deepluckyblock.util.SafeSurface.ChunkMemo();
        // T33 : blocs mis de cote faute de chunk charge. AVANT, cette boucle
        // appelait getChunk(..., true) -- generation SYNCHRONE -- avant CHAQUE
        // bloc : sur l'everest (52 979 blocs, emprise 241x345) cela a produit
        // 644 demandes de chargement dans le meme tick serveur = le gel de 36 s
        // constate en jeu. Desormais : demande NON bloquante + mise de cote +
        // repose par la passe de rattrapage, exactement comme PasteJob (T12).
        final List<Integer> defer = new ArrayList<>();
        int waitTicks;      // ticks d'attente de la passe de rattrapage
        int redeferred;     // blocs finalement reposes par la passe de rattrapage
        EverestJob(ServerLevel l, List<StructureTemplate.StructureBlockInfo> b, BlockPos o, Rotation r, Mirror m, long t, Player p, Runnable cb, BlockPos[] cherry) {
            level = l; blocks = b; origin = o; rotation = r; mirror = m; startTime = t; player = p; onComplete = cb; index = 0; ticksElapsed = 0; cherryHolder = cherry;
            waitTicks = 0; redeferred = 0;
        }
    }

    private static class PasteJob {
        final ServerLevel level; final List<StructureTemplate.StructureBlockInfo> blocks;
        final BlockPos origin; final Rotation rotation; final Mirror mirror;
        final String name; final long startTime; final Player player; final Runnable onComplete;
        final BlockPos min, max; final boolean skipTerrain; int index;
        int grav;                      // blocs soumis a la gravite poses (preuve du clamp DLB-CLAMP)
        final Set<net.minecraft.world.level.chunk.LevelChunkSection> gravitySections =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final List<Integer> defer = new ArrayList<>();  // T12 : blocs en attente de chunk
        // T14 : cache de section -> le chunk n'est resolu qu'une fois par section
        // au lieu d'une fois par bloc (guide section 42 : le tri par section
        // supprime les resolutions repetees de chunk).
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        boolean lastLoaded = false;
        int waitTicks;                 // T12 : ticks d'attente de la passe de rattrapage
        int redeferred;                // T12 : blocs finalement poses par la passe de rattrapage
        PasteJob(ServerLevel l, List<StructureTemplate.StructureBlockInfo> b, BlockPos o, Rotation r, Mirror m, String n, long t, Player p, Runnable cb, BlockPos mn, BlockPos mx, boolean st) {
            level = l; blocks = b; origin = o; rotation = r; mirror = m; name = n; startTime = t; player = p; onComplete = cb; min = mn; max = mx; skipTerrain = st; index = 0; grav = 0;
        }
    }

    private static class CarveJob {
        final ServerLevel level;
        final List<int[]> columns;
        final List<Long> occList;
        final Set<Long> occupied;
        final Set<Long> baseCols;
        final BlockPos min, max;
        final String name;
        final int bottomY;
        final int baseLevel;        // median des lo = niveau de sol reel (anti toit/auvent)
        int idx, ringIdx, groundIdx;
        boolean groundDone;
        int carved, grounded;
        boolean vegetationOnly;   // T23 : structures a terrain desactive -> on retire au moins la vegetation
        int vegIdx = 0, vegRemoved = 0;
        long vegetationCursor;
        CarveJob(ServerLevel l, List<int[]> cols, List<Long> occ, Set<Long> occSet, Set<Long> base, BlockPos mn, BlockPos mx, String n, int by, int bl) {
            level = l; columns = cols; occList = occ; occupied = occSet; baseCols = base; min = mn; max = mx; name = n; bottomY = by; baseLevel = bl;
            idx = 0; ringIdx = occ.size(); groundIdx = 0; groundDone = false; carved = 0; grounded = 0;
        }
    }

    private static class PostJob {
        final ServerLevel level; final BlockPos min, max; final String name; int y;
        // T36 : true = la structure a ete posee en « paste brut » (aucune
        // preparation de terrain). On lui applique alors la passe finale de
        // terrain (cuvettes comblees, saillies rabotees, trous profonds
        // rebouches) au lieu de la laisser telle quelle.
        final boolean terrainFinal;
        PostJob(ServerLevel l, BlockPos mn, BlockPos mx, String n) { this(l, mn, mx, n, false); }
        PostJob(ServerLevel l, BlockPos mn, BlockPos mx, String n, boolean tf) { level = l; min = mn; max = mx; name = n; y = mn.getY(); terrainFinal = tf; }
    }

    /**
     * T12 : pose UN bloc du paste. Renvoie false si le chunk n'est pas encore en
     * memoire -- le bloc est alors DIF FERE au lieu de forcer une generation
     * synchrone (cause mesuree des gels de 2,4 s pendant un paste sur zone neuve).
     */
    private static boolean placeOne(PasteJob job, int i, StructurePlaceSettings rotSettings,
                                    BlockPos.MutableBlockPos bp, boolean force) {
        StructureTemplate.StructureBlockInfo info = job.blocks.get(i);
        BlockState tState = info.state().mirror(job.mirror).rotate(job.rotation);
        if (isLaggyBlock(tState)) return true;
        BlockPos rel = StructureTemplate.calculateRelativePosition(rotSettings, info.pos());
        bp.set(job.origin.getX() + rel.getX(), job.origin.getY() + rel.getY(), job.origin.getZ() + rel.getZ());
        int cx = bp.getX() >> 4, cz = bp.getZ() >> 4;
        if (cx != job.lastCx || cz != job.lastCz) {
            job.lastCx = cx; job.lastCz = cz;
            job.lastLoaded = job.level.getChunkSource().getChunkNow(cx, cz) != null;
        }
        if (!job.lastLoaded) {
    // T49 : plus AUCUNE generation synchrone, meme en mode force (elle
    // gelait le serveur ~15 s par chunk). On demande en tache de fond et on
    // differe le bloc : la passe de rattrapage le reposera.
            deepluckyblock.util.SafeSurface.request(job.level, cx, cz);
            job.lastCx = Integer.MIN_VALUE;
            return false;
        }
        // T102 (rappel dev 30/09 : « toutes les structures doivent etre
        // paste -air... etc ! ») : toute entree d'air du template est
        // SAUTEE (-- equivalent strict du -a de WorldEdit --), quelle que
        // soit la case cible. L'ancienne regle (ecrire l'air sur non-air
        // pour creuser l'interieur, T73) n'est plus utile : le cut
        // d'emprise (clearFootprint, au-dessus du sol planifie) a DEJA
        // vide le volume avant le paste. Echappatoire documentee :
        // PASTE_KEEP_INTERIOR_AIR true retablit la logique historique du
        // FILTRE pre-paste (l'ecriture elle-meme reste sans air).
        if (tState.isAir()) return true;
        if (job.name.equals("shipdead") && tState.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) {
            // T165 : Level#setBlock appelle FallingBlock#onPlace, donc programme
            // ~60 000 ticks même sans mises à jour de voisins. Écriture palette
            // directe : même état visible, mais aucune physique différée.
            var chunk = job.level.getChunk(cx, cz);
            int si = chunk.getSectionIndex(bp.getY());
            if (si >= 0 && si < chunk.getSections().length) {
                var section = chunk.getSections()[si];
                BlockState old = section.setBlockState(bp.getX() & 15, bp.getY() & 15, bp.getZ() & 15, tState, false);
                job.gravitySections.add(section);
                chunk.setUnsaved(true);
                job.level.sendBlockUpdated(bp, old, tState, 2);
            }
            job.grav++;
        } else {
            job.level.setBlock(bp, tState, FAST_FLAG);
            if (tState.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) job.grav++;
        }
        if (info.nbt() != null) {
            var be = job.level.getBlockEntity(bp);
            if (be != null) { CompoundTag tag = info.nbt().copy(); tag.putInt("x", bp.getX()); tag.putInt("y", bp.getY()); tag.putInt("z", bp.getZ()); be.loadWithComponents(tag, job.level.registryAccess()); }
        }
        // FIX (rapporte en jeu : "les coffres relies a notre systeme de
        // coffre common a ultimate ne fonctionnent plus") : ce PasteJob
        // (circus/shipdead, et le fallback rawBlocks-vide d'Everest) ne
        // marquait JAMAIS ses coffres slb_auto_chest/slb_chest_rank --
        // seul Structures5Procedure.PasteJob (citadel/observatory/dragon)
        // le faisait. Sans ces drapeaux, LuckyChestAutofillProcedure
        // (qui exige slb_auto_chest=true) ignorait silencieusement TOUS
        // les coffres de ces structures : ils restaient vides pour
        // toujours, exactement le symptome rapporte.
        if (tState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
            Structures5Procedure.prepareAutomaticChest(job.level, bp.immutable(), tState, info.nbt(), info.pos().getY(), job.blocks);
        }
        return true;
    }

    /**
     * T33 : repose les blocs EVEREST mis de cote faute de chunk charge.
     * Meme politique que PasteJob : on redemande le chunk en tache de fond et on
     * repasse au tick suivant ; ce n'est qu'apres 30 s d'attente (600 ticks)
     * que l'on force la generation des derniers chunks restants, et seulement
     * pour ceux-la (jamais pendant la pose normale).
     */
    /** T85/B4 : apres ce delai SANS succes sur les blocs differes, la file se
     *  termine honnetement (warn + bloc(s) abandonnes) au lieu de tourner a 100 %
     *  indefiniment (5 blocs ont tenu l'everest en otage 7229 ticks = 6 min). */
    private static final int MAX_DEFER_WAIT_TICKS = 2400;
    /** T85/B4 : en mode force, re-demande agressive des chunks manquants tous les N ticks. */
    private static final int DEFER_ESCALATE_TICKS = 200;

    private static void retryDeferredEverest(EverestJob job, StructurePlaceSettings rotSettings, BlockPos.MutableBlockPos bp) {
        job.waitTicks++;
        boolean force = job.waitTicks > 600;
        if (force && job.waitTicks == 601) {
            LOGGER.warn("[STRUCT4-EVEREST] everest : {} blocs en attente depuis 30 s -- chargement force des chunks restants", job.defer.size());
        }
        // T85/B4 : le mode « force » n'etait qu'un warn -- les chunks manquants
        // n'etaient plus re-demandes que passivement (a la lecture). Desormais,
        // en mode force, on re-demande ACTIVEMENT chaque chunk manquant toutes
        // les DEFER_ESCALATE_TICKS (sans casser les futures en vol).
        if (force && job.waitTicks % DEFER_ESCALATE_TICKS == 0) escalateDeferredRequests(job, rotSettings, "everest");
        int placed = 0;
        long retryT0 = System.currentTimeMillis();
        java.util.Iterator<Integer> it = job.defer.iterator();
        while (it.hasNext()) {
            // T34 : meme budget de temps que retryDeferred (cf. ci-dessus).
            if (System.currentTimeMillis() - retryT0 > PASTE_TICK_BUDGET_MS) break;
            int i = it.next();
            if (placeEverestBlock(job, i, rotSettings, bp, force)) { it.remove(); placed++; }
        }
        job.redeferred += placed;
        if (placed > 0) {
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] everest : {} bloc(s) reposes apres chargement de la zone ({} restants, {} ticks d'attente)",
                    placed, job.defer.size(), job.waitTicks);
        }
        dropStuckDeferred(job, rotSettings, "everest");
    }

    /**
     * T85/B4 : re-demande active les chunks des blocs encore differes
     * (dedup en ensemble, lecture non bloquante, aucune generation synchrone).
     */
    private static void escalateDeferredRequests(EverestJob job, StructurePlaceSettings rotSettings, String tag) {
        java.util.Set<Long> asked = new java.util.HashSet<>();
        int rq = 0;
        for (int di : job.defer) {
            StructureTemplate.StructureBlockInfo info = job.blocks.get(di);
            BlockPos rel = StructureTemplate.calculateRelativePosition(rotSettings, info.pos());
            int cx = (job.origin.getX() + rel.getX()) >> 4, cz = (job.origin.getZ() + rel.getZ()) >> 4;
            if (!asked.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) continue;
            if (job.level.getChunkSource().getChunkNow(cx, cz) == null) {
                deepluckyblock.util.SafeSurface.reRequest(job.level, cx, cz);
                rq++;
            }
        }
        if (rq > 0)
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] everest : {} chunk(s) re-demande(s) activement pour les {} bloc(s) restants ({} ticks d'attente)",
                    rq, job.defer.size(), job.waitTicks);
    }

    /**
     * T85/B4 : plafond honnete -- si des blocs restent toujours sans chunk apres
     * {@link #MAX_DEFER_WAIT_TICKS} ticks (120 s), on les abandonne EN LES NOMMANT
     * (echantillon de positions) et on laisse la file se terminer. Plus jamais de
     * boucle « 100 % » infinie : une structure ne doit pas trouer la file des
     * generations plus longtemps que la generation elle-meme.
     */
    private static void dropStuckDeferred(EverestJob job, StructurePlaceSettings rotSettings, String tag) {
        if (job.defer.isEmpty() || job.waitTicks <= MAX_DEFER_WAIT_TICKS) return;
        StringBuilder sample = new StringBuilder();
        int shown = 0;
        for (int di : job.defer) {
            if (shown >= 6) { sample.append(", ..."); break; }
            StructureTemplate.StructureBlockInfo info = job.blocks.get(di);
            BlockPos rel = StructureTemplate.calculateRelativePosition(rotSettings, info.pos());
            if (shown++ > 0) sample.append(", ");
            sample.append(job.origin.getX() + rel.getX()).append(',').append(job.origin.getZ() + rel.getZ());
        }
        LOGGER.warn("[STRUCT4-EVEREST] everest : {} bloc(s) SANS CHUNK apres {} s et re-demandes actives -- "
                        + "abandon honnete (positions x,z : {}) ; la file se termine, le reste ({}/{}) est pose (T85)",
                job.defer.size(), MAX_DEFER_WAIT_TICKS / 20, sample, job.index, job.blocks.size());
        job.defer.clear();
    }

    /** T33 : pose un bloc Everest unitaire ; false = chunk pas encore pret (differes). */
    private static boolean placeEverestBlock(EverestJob job, int i, StructurePlaceSettings rotSettings,
                                             BlockPos.MutableBlockPos bp, boolean force) {
        StructureTemplate.StructureBlockInfo info = job.blocks.get(i);
        BlockState tState = info.state().mirror(job.mirror).rotate(job.rotation);
        BlockPos rel = StructureTemplate.calculateRelativePosition(rotSettings, info.pos());
        bp.set(job.origin.getX() + rel.getX(), job.origin.getY() + rel.getY(), job.origin.getZ() + rel.getZ());
        int cx = bp.getX() >> 4, cz = bp.getZ() >> 4;
        if (job.level.getChunkSource().getChunkNow(cx, cz) == null) {
            // T49 : demande en tache de fond uniquement (jamais de generation
            // synchrone, meme en mode force).
            deepluckyblock.util.SafeSurface.request(job.level, cx, cz);
            return false;
        }
        job.level.setBlock(bp, tState, EVEREST_FLAG);
        if (job.cherryHolder[0] == null && isStrippedCherry(tState)) job.cherryHolder[0] = bp.immutable();
        if (info.nbt() != null) {
            var be = job.level.getBlockEntity(bp);
            if (be != null) { CompoundTag tag = info.nbt().copy(); tag.putInt("x", bp.getX()); tag.putInt("y", bp.getY()); tag.putInt("z", bp.getZ()); be.loadWithComponents(tag, job.level.registryAccess()); }
        }
        if (tState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
            BlockPos chestPos = bp.immutable();
            job.chests.add(chestPos);
            Structures5Procedure.prepareAutomaticChest(job.level, chestPos, tState, info.nbt(), info.pos().getY(), job.blocks);
        }
        return true;
    }

    /** T12 : repose les blocs differes ; chargement force en dernier recours apres 30 s. */
    private static void retryDeferred(PasteJob job, StructurePlaceSettings rotSettings, BlockPos.MutableBlockPos bp) {
        job.waitTicks++;
        boolean force = job.waitTicks > 600;
        if (force && job.waitTicks == 601) {
            LOGGER.warn("[STRUCT4] {} : {} blocs en attente depuis 30 s -- chargement force des chunks restants", job.name, job.defer.size());
        }
        // T85/B4 : meme re-demande active + meme plafond honnete que la file everest
        // (la boucle « blocs jamais poses, file a 100 % indefiniment » touchait toutes
        // les files de pose, pas seulement l'everest).
        if (force && job.waitTicks % DEFER_ESCALATE_TICKS == 0) escalateDeferredRequestsPaste(job, rotSettings);
        int placed = 0;
        long retryT0 = System.currentTimeMillis();
        java.util.Iterator<Integer> it = job.defer.iterator();
        while (it.hasNext()) {
            // T34 : BUDGET DE TEMPS ici aussi. Sans lui, la passe de rattrapage
            // reposait TOUS les blocs differes dans le meme tick : mesure en
            // sandbox « 6757 bloc(s) reposes (1 ticks d'attente) » = 7 838 ms de
            // gel sur un seul tick. On repose par tranches de 40 ms et on reprend
            // au tick suivant ; les blocs restants gardent leur place dans la file.
            if (System.currentTimeMillis() - retryT0 > PASTE_TICK_BUDGET_MS) break;
            int i = it.next();
            if (placeOne(job, i, rotSettings, bp, force)) { it.remove(); placed++; }
        }
        job.redeferred += placed;
        if (placed > 0) {
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} : {} bloc(s) reposes apres chargement de la zone ({} restants, {} ticks d'attente)", job.name, placed, job.defer.size(), job.waitTicks);
        }
        dropStuckDeferredPaste(job, rotSettings);
    }

    /** T85/B4 : re-demande active des chunks des blocs PasteJob encore differes. */
    private static void escalateDeferredRequestsPaste(PasteJob job, StructurePlaceSettings rotSettings) {
        java.util.Set<Long> asked = new java.util.HashSet<>();
        int rq = 0;
        for (int di : job.defer) {
            StructureTemplate.StructureBlockInfo info = job.blocks.get(di);
            BlockPos rel = StructureTemplate.calculateRelativePosition(rotSettings, info.pos());
            int cx = (job.origin.getX() + rel.getX()) >> 4, cz = (job.origin.getZ() + rel.getZ()) >> 4;
            if (!asked.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) continue;
            if (job.level.getChunkSource().getChunkNow(cx, cz) == null) {
                deepluckyblock.util.SafeSurface.reRequest(job.level, cx, cz);
                rq++;
            }
        }
        if (rq > 0)
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} : {} chunk(s) re-demande(s) activement pour les {} bloc(s) restants ({} ticks d'attente)",
                    job.name, rq, job.defer.size(), job.waitTicks);
    }

    /** T85/B4 : plafond honnete identique a {@link #dropStuckDeferred} (file everest). */
    private static void dropStuckDeferredPaste(PasteJob job, StructurePlaceSettings rotSettings) {
        if (job.defer.isEmpty() || job.waitTicks <= MAX_DEFER_WAIT_TICKS) return;
        StringBuilder sample = new StringBuilder();
        int shown = 0;
        for (int di : job.defer) {
            if (shown >= 6) { sample.append(", ..."); break; }
            StructureTemplate.StructureBlockInfo info = job.blocks.get(di);
            BlockPos rel = StructureTemplate.calculateRelativePosition(rotSettings, info.pos());
            if (shown++ > 0) sample.append(", ");
            sample.append(job.origin.getX() + rel.getX()).append(',').append(job.origin.getZ() + rel.getZ());
        }
        LOGGER.warn("[STRUCT4] {} : {} bloc(s) SANS CHUNK apres {} s et re-demandes actives -- "
                        + "abandon honnete (positions x,z : {}) ; la file se termine, le reste ({}/{}) est pose (T85)",
                job.name, job.defer.size(), MAX_DEFER_WAIT_TICKS / 20, sample, job.index, job.blocks.size());
        job.defer.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {

        // FIX T7 : reprise des structures dont le NBT etait en cours de chargement.
        tickDeferredSpawns();

        if (!PASTE_QUEUE.isEmpty()) {
            PasteJob job = PASTE_QUEUE.peek();
            int end = Math.min(job.index + BLOCKS_PER_TICK, job.blocks.size());
            // T12 : zone TENUE pendant le paste (voir commentaire detaille dans
            // Structures5Procedure.placeOne) -- plus aucune generation synchrone
            // de chunk dans le tick.
            deepluckyblock.util.ChunkKeeper.keep(job.level);
            StructurePlaceSettings rotSettings = new StructurePlaceSettings().setRotation(job.rotation).setMirror(job.mirror);
            BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
            if (job.index < job.blocks.size()) {
                // T33 : budget de TEMPS (cf. PASTE_TICK_BUDGET_MS). Le quota de
                // 25 000 blocs/tick est conserve comme garde-fou haut, mais c'est
                // la mesure en ms qui garantit qu'aucun tick ne s'eternise.
                long tickT0 = System.currentTimeMillis();
                int i = job.index;
                for (; i < end; i++) {
                    if ((i & 127) == 0 && System.currentTimeMillis() - tickT0 > PASTE_TICK_BUDGET_MS) break;
                    if (!placeOne(job, i, rotSettings, bp, false)) job.defer.add(i);
                }
                job.index = i;
            } else {
                retryDeferred(job, rotSettings, bp);
                if (!job.defer.isEmpty()) return;
            }
            if (job.index >= job.blocks.size() && job.defer.isEmpty()) {
                // Une seule reconstruction des compteurs par section, après les
                // milliers d'écritures palette directes de Shipdead.
                for (var section : job.gravitySections) section.recalcBlockCounts();
                job.gravitySections.clear();
                PASTE_QUEUE.poll();
                long elapsed = System.currentTimeMillis() - job.startTime;
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} finie en {}ms ({} blocs, {} a gravite -- ticks de chute neutralises par DLB-CLAMP)", job.name, elapsed, job.blocks.size(), job.grav);
                if (job.player != null) job.player.sendSystemMessage(Component.literal("§a✓ §e" + job.name + "§a en " + elapsed + "ms"));
                if (job.onComplete != null) try { job.onComplete.run(); } catch (Exception ex) { LOGGER.error("[STRUCT4] onComplete error", ex); }
                if (job.skipTerrain) {
                    // T39 : plus de passe finale de terrain APRES le paste (elle tourne
                    // desormais AVANT la pose, dans decorateTerrainOnly).
                    POST_QUEUE.offer(new PostJob(job.level, job.min, job.max, job.name, false));
                    // T23 : la modif terrain reste desactivee, MAIS la vegetation naturelle
                    // qui traverse la structure est retiree (consigne utilisateur :
                    // « arbres par exemples qui sont dans mon cirque, bamboos, vignes qui
                    // volent, cocoa qui volent »).
                    CarveJob veg = buildVegetationJob(job);
                    CARVE_QUEUE.offer(veg);
                    deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} : modif terrain desactivee, nettoyage de la vegetation "
                            + "autour de la structure ({} colonnes concernees)", job.name, veg.columns.size());
                } else {
                    CarveJob carve = buildCarveJob(job);
                    CARVE_QUEUE.offer(carve);
                    deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] carve {} programmée ({} colonnes, {} blocs occupés, bottomY={})", job.name, carve.columns.size(), carve.occupied.size(), carve.bottomY);
                }
            }
        }

        if (!CARVE_QUEUE.isEmpty()) {
            CarveJob cj = CARVE_QUEUE.peek();
            BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
            int minBuild = cj.level.getMinBuildHeight();
            if (cj.vegetationOnly) {
                // T23 : retire la vegetation naturelle (feuilles, troncs, plantes, lianes,
                // bambous) qui n'appartient PAS a la structure, dans un rayon de 3 blocs
                // autour de chacun de ses blocs.
                // Visit each cell once, rather than its 343 overlapping neighborhoods.
                int x0 = cj.min.getX() - VEG_RADIUS, z0 = cj.min.getZ() - VEG_RADIUS;
                int y0 = Math.max(minBuild, cj.min.getY() - VEG_RADIUS);
                int width = cj.max.getX() - cj.min.getX() + 1 + 2 * VEG_RADIUS;
                int depth = cj.max.getZ() - cj.min.getZ() + 1 + 2 * VEG_RADIUS;
                int height = Math.min(cj.level.getMaxBuildHeight() - 1,
                        cj.max.getY() + VEG_RADIUS) - y0 + 1;
                long volume = (long) width * depth * height;
                long deadline = System.nanoTime() + VEG_SLICE_NANOS;
                while (cj.vegetationCursor < volume && System.nanoTime() < deadline) {
                    long i = cj.vegetationCursor;
                    int px = x0 + (int) (i % width);
                    int pz = z0 + (int) ((i / width) % depth);
                    int py = y0 + (int) (i / ((long) width * depth));
                    var chunk = cj.level.getChunkSource().getChunkNow(px >> 4, pz >> 4);
                    if (chunk == null) {
                        deepluckyblock.util.SafeSurface.reRequest(cj.level, px >> 4, pz >> 4);
                        break; // Retry this cell; never silently skip cleanup.
                    }
                    cj.vegetationCursor++;
                    if (cj.occupied.contains(BlockPos.asLong(px, py, pz))) continue;
                    BlockState st = chunk.getBlockState(mut.set(px, py, pz));
                    if (!isTreePart(st) && !StructureTerrainPrep.isVegetation(st)) continue;
                    boolean nearby = false;
                    search:
                    for (int dx = -VEG_RADIUS; dx <= VEG_RADIUS; dx++)
                        for (int dy = -VEG_RADIUS; dy <= VEG_RADIUS; dy++)
                            for (int dz = -VEG_RADIUS; dz <= VEG_RADIUS; dz++)
                                if (cj.occupied.contains(BlockPos.asLong(px + dx, py + dy, pz + dz))) {
                                    nearby = true;
                                    break search;
                                }
                    if (nearby) {
                        cj.level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                        cj.vegRemoved++;
                    }
                }
                if (cj.vegetationCursor >= volume) {
                    CARVE_QUEUE.poll();
                    deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} : vegetation nettoyee autour de la structure : {} bloc(s) retires",
                            cj.name, cj.vegRemoved);
                }
                return;
            }
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
                // de baseLevel). Les colonnes de toit/auvent (lo beaucoup plus haut)
                // sont sautees : sinon on fabrique des piliers de dirt qui pendant
                // des bords du toit. RIEN n'est jamais place au-dessus du toit.
                int end = Math.min(cj.groundIdx + GROUND_COLS_PER_TICK, cj.columns.size());
                for (int i = cj.groundIdx; i < end; i++) {
                    int[] c = cj.columns.get(i);
                    int x = c[0], z = c[1], lo = c[2];
                    if (lo > cj.baseLevel + FLOOR_TOLERANCE) continue; // colonne de toit/auvent -> RIEN
                    BlockState below = cj.level.getBlockState(mut.set(x, lo - 1, z));
                    if (!below.isAir() && below.blocksMotion()) continue; // sol solide -> RIEN
                    int y = lo - 1;
                    boolean first = true;
                    int depth = 0;
                    while (y >= minBuild && depth < GROUND_MAX_DEPTH) {
                        BlockState cur = cj.level.getBlockState(mut.set(x, y, z));
                        if (!cur.isAir() && cur.blocksMotion()) break; // sol trouve -> stop
                        cj.level.setBlock(mut, first ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2);
                        cj.grounded++;
                        first = false; y--; depth++;
                    }
                }
                cj.groundIdx = end;
                if (cj.groundIdx >= cj.columns.size()) cj.groundDone = true;
            }
            if (cj.idx >= cj.columns.size() && cj.ringIdx >= cj.occList.size() && cj.groundDone) {
                CARVE_QUEUE.poll();
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} terminée : {} blocs supprimés, {} blocs de motte (bottomY={})", cj.name, cj.carved, cj.grounded, cj.bottomY);
                // Transmet le nom pour que le decor pose par decorate() soit
                // thematise (sinon le catalogue GENERIC_SET s'applique par
                // defaut, ce qui reste correct mais moins cible).
                StructureTerrainPrep.setStructureName(cj.name);
                StructureTerrainPrep.decorateFinish(cj.level, cj.min, cj.max);   // T39 : finitions post-pose
                POST_QUEUE.offer(new PostJob(cj.level, cj.min, cj.max, cj.name));
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] decorate + post de {} terminée", cj.name);
            }
        }

        if (!EVEREST_QUEUE.isEmpty()) {
            EverestJob job = EVEREST_QUEUE.peek();
            job.ticksElapsed++;
            long tickStart = System.currentTimeMillis();
            int end = Math.min(job.index + EVEREST_BLOCKS_PER_TICK, job.blocks.size());
            StructurePlaceSettings rotSettings = new StructurePlaceSettings().setRotation(job.rotation).setMirror(job.mirror);
            BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
            int i = job.index; int placedThisTick = 0;
            for (; i < end; i++) {
                if (System.currentTimeMillis() - tickStart > EVEREST_TICK_TIMEOUT_MS) break;
                StructureTemplate.StructureBlockInfo info = job.blocks.get(i);
                BlockState tState = info.state().mirror(job.mirror).rotate(job.rotation);
                BlockPos rel = StructureTemplate.calculateRelativePosition(rotSettings, info.pos());
                bp.set(job.origin.getX() + rel.getX(), job.origin.getY() + rel.getY(), job.origin.getZ() + rel.getZ());
                // T33/T53 : JAMAIS de generation synchrone ici (cause du gel de 36 s), et
                // plus de recherche de chunk PAR BLOC : le memo ne re-interroge le chunk
                // source que quand on change de chunk (1 fois tous les ~16 blocs).
                if (!deepluckyblock.util.SafeSurface.present(job.level, bp.getX(), bp.getZ(), job.memo)) {
                    // Demande en tache de fond (non bloquant) et mise de cote du bloc :
                    // il sera repose par la passe de rattrapage, sans figer le tick serveur.
                    deepluckyblock.util.SafeSurface.request(job.level, bp.getX() >> 4, bp.getZ() >> 4);
                    job.memo.reset();
                    job.defer.add(i);
                    continue;
                }
                job.level.setBlock(bp, tState, EVEREST_FLAG);
                placedThisTick++;
                if (job.cherryHolder[0] == null && isStrippedCherry(tState)) job.cherryHolder[0] = bp.immutable();
                if (info.nbt() != null) {
                    var be = job.level.getBlockEntity(bp);
                    if (be != null) { CompoundTag tag = info.nbt().copy(); tag.putInt("x", bp.getX()); tag.putInt("y", bp.getY()); tag.putInt("z", bp.getZ()); be.loadWithComponents(tag, job.level.registryAccess()); }
                }
                // FIX (coffres Everest lies au systeme common->ultimate + nettoyage
                // "3 coffres max", voir Javadoc EverestJob.chests) : meme traitement
                // que PasteJob pour citadel/observatory/dragon -- on collecte chaque
                // coffre pose et on lui attribue immediatement ses drapeaux
                // slb_auto_chest/slb_chest_rank (necessaires a LuckyChestAutofillProcedure
                // pour le remplir), avant de decider plus tard lesquels survivent.
                if (tState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
                    BlockPos chestPos = bp.immutable();
                    job.chests.add(chestPos);
                    Structures5Procedure.prepareAutomaticChest(job.level, chestPos, tState, info.nbt(), info.pos().getY(), job.blocks);
                }
            }
            job.index = i;
            // T33 : une fois tous les blocs parcourus, on repose ceux qui
            // attendaient leur chunk (aucune ecriture bloquante).
            if (job.index >= job.blocks.size() && !job.defer.isEmpty()) retryDeferredEverest(job, rotSettings, bp);
            if (job.index >= job.blocks.size() && !job.defer.isEmpty()) {
                // T85/B4 : le job attend ses derniers chunks -- dire CE QU'IL ATTEND
                // toutes les 10 s, au lieu de spammer « 100% (N/N) » chaque seconde
                // (plainte Dev : « ca charge infini »).
                if (job.ticksElapsed % 200 == 0)
                    deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] tous les blocs parcourus ; en attente de chunk(s) pour les "
                                    + "{} dernier(s) bloc(s) ({} ticks d'attente)",
                            job.defer.size(), job.waitTicks);
            } else if (job.ticksElapsed % PROGRESS_LOG_INTERVAL == 0) {
                int pct = (int) ((job.index * 100L) / Math.max(1, job.blocks.size()));
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] {}% ({}/{})", pct, job.index, job.blocks.size());
            }
            if (job.index >= job.blocks.size() && job.defer.isEmpty()) {
                EVEREST_QUEUE.poll();
                long elapsed = System.currentTimeMillis() - job.startTime;
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] EVEREST TERMINÉ en {}s ({} blocs)", elapsed / 1000.0, job.blocks.size());
                if (job.player != null) job.player.sendSystemMessage(Component.literal("§b§l⛰ Everest en construction §7(~" + (elapsed / 1000.0) + "s)"));
                // FIX (coffres jamais nettoyes, voir Javadoc EverestJob.chests) :
                // appel EFFECTIF, cette fois, de cleanupEverestChests -- l'ancienne
                // methode existait deja dans Structures5Procedure mais n'etait
                // jamais atteignable depuis le VRAI pipeline de pose de l'Everest.
                Structures5Procedure.cleanupEverestChests(job.level, job.chests);
                if (job.onComplete != null) try { job.onComplete.run(); } catch (Exception ex) { LOGGER.error("[STRUCT4-EVEREST] onComplete error", ex); }
            }
        }

        // Post-processing must not release the chunk tickets while placement is active.
        if (!POST_QUEUE.isEmpty() && PASTE_QUEUE.isEmpty()
                && CARVE_QUEUE.isEmpty() && EVEREST_QUEUE.isEmpty()) {
            PostJob pj = POST_QUEUE.peek();
            for (int s = 0; s < 4 && pj.y <= pj.max.getY(); s++) { postProcessLayer(pj.level, pj.min, pj.max, pj.y); pj.y++; }
            if (pj.y > pj.max.getY()
                    && !deepluckyblock.util.TerrainChain.owns(StructureTerrainPrep.chainTag(pj.min, pj.max))) {
                killLaggyItems(pj.level, pj.min, pj.max);
                // T36 : passe finale de terrain pour les structures en paste brut
                // (everest, circus/shipdead, repli rawBlocks). Elle arrive APRES le
                // post-process et juste avant de rendre la zone : c'est la derniere
                // operation sur le terrain, comme dans le pipeline complet.
                // T39 : PLUS AUCUNE OPERATION DE TERRAIN APRES LE PASTE. La passe
                // finale (T36) et /fixwater + /fixlava (T38) tournent desormais
                // AVANT la pose (voir spawnEverest et le repli rawBlocks). Le champ
                // terrainFinal est conserve pour la signature des PostJob mais n'est
                // plus utilise : c'est la garantie, verifiable ici, que la structure
                // est bien le dernier objet pose sur la zone.
                if (pj.terrainFinal) {
                    LOGGER.warn("[STRUCT4] PostJob terrainFinal sur {} ignore (T39 : terrain avant le paste)", pj.name);
                }
                POST_QUEUE.poll();
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] Post-process de {} terminé", pj.name);
                deepluckyblock.util.ChunkKeeper.release(pj.level);
                Structures5Procedure.notifyGenerationFinished(pj.level);
            }
        }

        if (!DELAYED_ACTIONS.isEmpty()) {
            Iterator<DelayedAction> it = DELAYED_ACTIONS.iterator();
            while (it.hasNext()) { DelayedAction da = it.next(); if (--da.ticksRemaining <= 0) { try { da.action.run(); } catch (Exception ex) { LOGGER.error("[STRUCT4] delayed err", ex); } it.remove(); } }
        }
    }

    private static void clearRing(CarveJob cj, BlockPos.MutableBlockPos mut, int x, int y, int z) {
        if (cj.occupied.contains(BlockPos.asLong(x, y, z))) return;
        BlockState s = cj.level.getBlockState(mut.set(x, y, z));
        if (!s.isAir() && isNaturalTerrain(s)) { cj.level.setBlock(mut, Blocks.AIR.defaultBlockState(), FAST_FLAG); cj.carved++; }
    }

    // bottomY = le DERNIER bloc reel de la structure (le y le plus bas, hors verre).
    // La motte se construit EN DESSOUS de ce bloc (a partir de bottomY-1).
    /** T23 : job de NETTOYAGE VEGETAL seul (structures dont la modif terrain est desactivee). */
    private static CarveJob buildVegetationJob(PasteJob j) {
        CarveJob cj = buildCarveJob(j);
        cj.vegetationOnly = true;
        return cj;
    }

    private static CarveJob buildCarveJob(PasteJob j) {
        StructurePlaceSettings rs = new StructurePlaceSettings().setRotation(j.rotation).setMirror(j.mirror);
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
        // baseLevel = mediane des lo. Robuste : les fondations profondes et les
        // colonnes de toit (minoritaires) ne faussent pas la mediane.
        Collections.sort(los);
        int baseLevel = los.isEmpty() ? bottomY : los.get(los.size() / 2);
        return new CarveJob(j.level, new ArrayList<>(cols.values()), new ArrayList<>(occupied), occupied, baseCols, j.min, j.max, j.name, bottomY, baseLevel);
    }

    public static boolean generate(ServerLevel level, BlockPos origin, int structureId) {
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] generate() ID={}, pos={}", structureId, origin.toShortString());
        // FIX T7 : jamais de parse NBT sur le thread serveur (voir templateReadyOrDefer).
        String deferredNbt = nbtForId(structureId);
        if (deferredNbt != null && !templateReadyOrDefer(level, origin, structureId, deferredNbt)) {
            return true;   // mise en attente : la generation reprendra seule (onServerTick)
        }
        return switch (structureId) {
            case 3 -> spawnCircus(level, origin);
            case 4 -> spawnShipdead(level, origin);
            case 5 -> spawnEverest(level, origin);
            default -> { LOGGER.warn("[STRUCT4] ID inconnu : {}", structureId); yield false; }
        };
    }

    public static boolean spawnCircus(ServerLevel level, BlockPos origin) {
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] === SPAWN CIRCUS ===");
        if (!templateReadyOrDefer(level, origin, 3, CIRCUS_NBT)) return true;
        return doPaste(level, origin, CIRCUS_NBT, CIRCUS_OFFSET_X, CIRCUS_OFFSET_Y, CIRCUS_OFFSET_Z, CIRCUS_DISTANCE, CIRCUS_NATIVE_FACING, CIRCUS_FOLLOW_SURFACE, CIRCUS_USE_FLAT_AREA, CIRCUS_PRESERVE_INTERIOR, false, true, null);
    }
    public static boolean spawnShipdead(ServerLevel level, BlockPos origin) {
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] === SPAWN SHIPDEAD ===");
        if (!templateReadyOrDefer(level, origin, 4, SHIPDEAD_NBT)) return true;
        return doPaste(level, origin, SHIPDEAD_NBT, SHIPDEAD_OFFSET_X, SHIPDEAD_OFFSET_Y, SHIPDEAD_OFFSET_Z, SHIPDEAD_DISTANCE, SHIPDEAD_NATIVE_FACING, SHIPDEAD_FOLLOW_SURFACE, SHIPDEAD_USE_FLAT_AREA, SHIPDEAD_PRESERVE_INTERIOR, false, true, null);
    }
    public static boolean spawnEverest(ServerLevel level, BlockPos origin) {
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] === SPAWN EVEREST ===");
        if (level == null || origin == null) return false;
        if (!templateReadyOrDefer(level, origin, 5, EVEREST_NBT)) return true;
        long t0 = System.currentTimeMillis();
        StructureTerrainPrep.setStructureName(EVEREST_NBT);
        // B3 : l'Everest n'entre pas par prepZone -- rearmer le chrono inter-etapes,
        // sinon sa premiere etape mesure l'inactivite ecoulee depuis la structure
        // precedente (log en jeu : « 47531005 ms de temps REEL (fixLiquids ...) »).
        StructureTerrainPrep.resetStepClock();
        // Everest only shapes its six-block collar, not a broad building plateau.
        // Keep a local cleanup border; fixLiquids retains its minimum 48-block halo.
        StructureTerrainPrep.setTerrainRingScalePercent(25);
        // FIX (freeze serveur au chargement à froid d'un gros NBT) : cache
        // mod-wide partagé (voir StructureTemplateCache) au lieu d'un appel
        // direct au StructureTemplateManager.
        StructureTemplate template = deepluckyblock.util.StructureTemplateCache.get(level, EVEREST_NBT);
        if (template == null) { LOGGER.error("[STRUCT4-EVEREST] Template vide ou échec chargement"); return false; }
        Vec3i size = template.getSize();
        int totalVolume = size.getX() * size.getY() * size.getZ();
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] Taille : {}x{}x{} = {}", size.getX(), size.getY(), size.getZ(), totalVolume);
        if (totalVolume > MAX_EVEREST_BLOCKS) { LOGGER.error("[STRUCT4-EVEREST] TROP GROS"); return false; }
        Player nearestPlayer = level.getNearestPlayer(origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5, 128, false);
        // Rotation and horizontal bounds do not depend on terrain height. Plan them
        // first, load the final area once, then read Y from the pinned anchor chunk.
        Runnable planEverest = () -> {
            BlockPos targetXZ;
            if (EVEREST_DISTANCE > 0) {
                targetXZ = findFarthestLoadedPos(level, origin, EVEREST_DISTANCE, MIN_CHUNKS_EVEREST);
                if (targetXZ == null) { if (nearestPlayer != null) nearestPlayer.sendSystemMessage(Component.literal("§c❌ Everest annulé : pas assez de chunks chargés")); return; }
            } else targetXZ = origin;
            // T177 : l'Everest fait jusqu'à 278 blocs de côté; protéger seulement
            // son ancre pouvait donc laisser son volume engloutir le joueur. On
            // éloigne le site avant toute pose lorsqu'il se trouve dans la garde
            // de 20 blocs autour du volume maximal. Le décalage est dirigé à
            // l'opposé du joueur (direction déterministe si positions confondues).
            if (nearestPlayer != null) {
                double dx = targetXZ.getX() - nearestPlayer.getX();
                double dz = targetXZ.getZ() - nearestPlayer.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                final double safeAnchorDistance = Math.max(size.getX(), size.getZ()) * 0.5 + 20.0;
                if (dist < safeAnchorDistance) {
                    if (dist < 0.001) { dx = 1.0; dz = 0.0; dist = 1.0; }
                    int push = (int) Math.ceil(safeAnchorDistance - dist);
                    targetXZ = targetXZ.offset((int) Math.round(dx / dist * push), 0,
                            (int) Math.round(dz / dist * push));
                    deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] garde joueur 20 blocs : site éloigné de {} blocs vers {},{}",
                            push, targetXZ.getX(), targetXZ.getZ());
                }
            }
            // FIX T7 : EVEREST a ete vu pose a Y=-90 en jeu (baseY=-65 = heightmap non
            // initialisee). La hauteur est desormais lue via SafeSurface (chunk force
            // en FULL + heightmap initialisee si besoin) : Y reel garanti.
            // T29 : l'everest non plus ne doit pas arriver DANS une autre structure.
            // Deplacement borne (6 anneaux de 48 blocs = 288 blocs max) AVANT la
            // lecture de hauteur, pour que l'ancrage suive le site reel.
            targetXZ = deepluckyblock.util.StructureSites.freeOffset(level, targetXZ, 6, 48);
            // T73 : liste compactee (l'air exterieur n'est plus materialise).
            List<StructureTemplate.StructureBlockInfo> rawBlocks =
                    deepluckyblock.util.StructureTemplateCache.compactBlocks(EVEREST_NBT);
            if (rawBlocks == null) rawBlocks = extractBlocks(template);
            if (rawBlocks.isEmpty()) { LOGGER.error("[STRUCT4-EVEREST] Aucun bloc extrait"); return; }
            // Filtrer d'abord, PUIS minRelY sur la liste filtree (hors verre/air).
            List<StructureTemplate.StructureBlockInfo> filtered = new ArrayList<>(rawBlocks.size());
            int solidCount = 0, airSkipped = 0, laggySkipped = 0, glassSkipped = 0;
            for (StructureTemplate.StructureBlockInfo info : rawBlocks) {
                BlockState state = info.state();
                if (state.isAir()) { airSkipped++; continue; }
                if (isLaggyBlock(state)) { laggySkipped++; continue; }
                if (isGlassBlock(state)) { glassSkipped++; continue; }
                filtered.add(info); solidCount++;
            }
            int minRelY = Integer.MAX_VALUE; for (var b : filtered) { int by = b.pos().getY(); if (by < minRelY) minRelY = by; }
            // T180 : Everest ne doit pas systématiquement présenter la même face
            // au joueur. Tirage unique par spawn, réutilisé pour Y0, emprise,
            // preload et paste afin que tous les calculs portent sur la même boîte.
            final Rotation everestRotation = switch (level.getRandom().nextInt(4)) {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                case 3 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] rotation aléatoire retenue : {}", everestRotation);
            // ==================================================================
            // T30 : PRE-CHARGEMENT DE L'EMPRISE *FINALE* (avant la pose)
            // ==================================================================
            // Mesure en jeu du 21/09 (22:58) : « EVEREST TERMINE en 15.567s » mais un
            // tick de 10386 ms pendant « pre-chargement des chunks 168/169 ». Cause :
            // la boite pre-chargee au debut de spawnEverest est celle de l'ORIGINE,
            // alors que la position finale peut etre ailleurs (T29 deplace la
            // structure hors des emprises occupees, EVEREST_DISTANCE l'eloigne du
            // joueur). Les chunks manquants etaient generes PENDANT le paste
            // (getChunk(..., require=true) depuis la boucle de pose) : generation
            // synchrone sur le thread serveur, donc gel du jeu. (T30 = prechargement du site final, plus bas dans ce fichier) corrige ce
            // schema pour les structures generiques ; l'everest possede son propre
            // chemin (EverestJob) et garde donc sa propre boite, recalculee ici sur
            // le site FINAL, avant que les blocs ne commencent a tomber.
            // ==================================================================
            // T88 : L'EVEREST NE DOIT JAMAIS ETRE REFUSE (consigne du 29/09)
            // ==================================================================
            // Run du 29/09 : 3 tentatives, 3 fins en « plateforme Y=62 sous le
            // plafond Y0 (eau exterieure max Y=95 ...) -- abandon avant toute
            // pose (T83) » : PAS DE SPAWN DU TOUT, alors que la consigne impose
            // desormais que la structure demarre seule, sans rien demander dans
            // la console (« je ne veux pas avoir a trifouiller la console »).
            // La decision est donc prise ICI, AVANT l'epingle des chunks : on
            // evalue le Y0 du site retenu (plateforme >= eau exterieure + 1).
            // En cas d'echec, on repousse la demande dans les 8 directions via
            // StructureSites.freeOffset (l'API officielle anti-chevauchement
            // T29 est REUTILISEE, pas court-circuitee) et on ne retient que les
            // candidats dont la zone probe est DEJA EN MEMOIRE -- aucune
            // generation synchrone n'est jamais declenchee ici. Le premier site
            // acceptable l'emporte. Si aucun ne convient (relief bas partout,
            // ou chunks non charges), le site initial est CONSERVE avec alerte :
            // les eaux exterieures restent protegees par leurs digues et le
            // geant depasse tres largement leur niveau -- le vrai probleme
            // etait le refus muet de poser, pas une plateforme basse.
            BlockPos decidedSpot = targetXZ;
            {
                final Player fPlayer = nearestPlayer;
                final Rotation rot0 = everestRotation;
                final int[] off0 = everestFootprintOffsets(
                        new StructurePlaceSettings().setRotation(rot0), filtered);
                // Ecart Y0 du candidat : >= 1 acceptable ; Integer.MIN_VALUE si la
                // zone probe n'est pas entierement en memoire (lecture impossible
                // sans generer -- on ne JOUE PAS avec ca).
                java.util.function.ToIntFunction<BlockPos> y0Gap = spot -> {
                    BlockPos planned = new BlockPos(spot.getX() + EVEREST_OFFSET_X, 0,
                            spot.getZ() + EVEREST_OFFSET_Z);
                    Rotation rot = everestRotation;
                    int[] off = (rot == rot0) ? off0
                            : everestFootprintOffsets(new StructurePlaceSettings().setRotation(rot), filtered);
                    BlockPos bp = adjustForRotation(planned, template, rot);
                    int pMinX = bp.getX() + off[0], pMaxX = bp.getX() + off[2];
                    int pMinZ = bp.getZ() + off[1], pMaxZ = bp.getZ() + off[3];
                    int band = deepluckyblock.util.SafeSurface.Y0_BAND;
                    if (!zoneInMemory(level, pMinX - band, pMinZ - band,
                            pMaxX + band, pMaxZ + band)) return Integer.MIN_VALUE;
                    int pBase = EVEREST_FOLLOW_SURFACE
                            ? deepluckyblock.util.SafeSurface.surfaceY(level, spot.getX(), spot.getZ(), origin.getY())
                            : origin.getY();
                    int pWater = deepluckyblock.util.SafeSurface.maxWaterLevelOutside(level,
                            pMinX, pMinZ, pMaxX, pMaxZ,
                            deepluckyblock.util.SafeSurface.Y0_BAND,
                            deepluckyblock.util.SafeSurface.Y0_STEP);
                    return (pWater == Integer.MIN_VALUE) ? 1 : pBase - pWater;
                };
                int gap0 = y0Gap.applyAsInt(decidedSpot);
                if (gap0 != Integer.MIN_VALUE && gap0 < 1) {
                    LOGGER.warn("[STRUCT4-EVEREST] Y0 insuffisant au site retenu (plateforme {} bloc(s) "
                                    + "sous le plafond) -- essai des directions proches (T88)", 1 - gap0);
                    final int[][] DIRS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                            {1, 1}, {1, -1}, {-1, 1}, {-1, -1} };
                    boolean moved = false;
                    for (int[] d : DIRS) {
                        BlockPos near = targetXZ.offset(d[0] * 48, 0, d[1] * 48);
                        // freeOffset re-verifie l'absence de chevauchement (T29)
                        // autour de chaque direction poussee de 48 blocs.
                        BlockPos cand = deepluckyblock.util.StructureSites.freeOffset(level, near, 2, 48);
                        if (cand == null || cand.equals(decidedSpot)) continue;
                        int g = y0Gap.applyAsInt(cand);
                        if (g != Integer.MIN_VALUE && g >= 1) {
                            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] Y0 trouve : deplacement de {} bloc(s) vers {},{} (T88)",
                                    (int) Math.sqrt(targetXZ.distSqr(cand)), cand.getX(), cand.getZ());
                            decidedSpot = cand;
                            moved = true;
                            break;
                        }
                    }
                    if (!moved)
                        LOGGER.warn("[STRUCT4-EVEREST] Y0 introuvable a proximite (relief bas ou chunks "
                                + "non charges) -- pose QUAND MEME au site initial : digues conservees, "
                                + "geant largement au-dessus de l'eau exterieure (T88)");
                }
            }
            // T180 : StructureSites compare historiquement un point et a rendu
            // plusieurs fois la même ancre quand de très grandes emprises se
            // recouvraient. Interdiction dure de réutiliser une ancre Everest
            // pendant cette session; chaque collision part vers un anneau neuf.
            int duplicateRing = 0;
            while (EVEREST_SESSION_ANCHORS.contains(BlockPos.asLong(decidedSpot.getX(), 0, decidedSpot.getZ()))) {
                duplicateRing++;
                int step = Math.max(size.getX(), size.getZ()) + 48;
                int side = level.getRandom().nextInt(4);
                decidedSpot = switch (side) {
                    case 0 -> decidedSpot.offset(step * duplicateRing, 0, 0);
                    case 1 -> decidedSpot.offset(-step * duplicateRing, 0, 0);
                    case 2 -> decidedSpot.offset(0, 0, step * duplicateRing);
                    default -> decidedSpot.offset(0, 0, -step * duplicateRing);
                };
                decidedSpot = deepluckyblock.util.StructureSites.freeOffset(level, decidedSpot, 6, 48);
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] ancre déjà utilisée : anneau de repli {} vers {},{}",
                        duplicateRing, decidedSpot.getX(), decidedSpot.getZ());
            }
            // T179 : le repli Y0 T88 peut déplacer le site APRÈS la première
            // garde joueur (run T178 : -173,-256 puis Y0 -> -125,-256, emprise
            // revenue sur le joueur). Dernière garde, irrévocable, après TOUS les
            // déplacements. La distance d'ancre utilise le côté complet maximal
            // +20, pas le demi-côté : quelle que soit la rotation asymétrique du
            // NBT, le joueur reste hors du volume avec au moins 20 blocs.
            if (nearestPlayer != null) {
                double dx = decidedSpot.getX() - nearestPlayer.getX();
                double dz = decidedSpot.getZ() - nearestPlayer.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                double required = Math.max(size.getX(), size.getZ()) + 20.0;
                if (d < required) {
                    if (d < 0.001) { dx = 1.0; dz = 0.0; d = 1.0; }
                    int push = (int) Math.ceil(required - d);
                    decidedSpot = decidedSpot.offset((int) Math.round(dx / d * push), 0,
                            (int) Math.round(dz / d * push));
                    decidedSpot = deepluckyblock.util.StructureSites.freeOffset(level, decidedSpot, 6, 48);
                    deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] garde joueur FINALE après Y0 : ancre à {},{} (distance cible {} blocs)",
                            decidedSpot.getX(), decidedSpot.getZ(), (int) required);
                }
            }
            EVEREST_SESSION_ANCHORS.add(BlockPos.asLong(decidedSpot.getX(), 0, decidedSpot.getZ()));
            final BlockPos everestSpot = decidedSpot;
            final List<StructureTemplate.StructureBlockInfo> fEverestBlocks = filtered;
            final int fMinRelY = minRelY;
            final int fSolid = solidCount, fAirSkip = airSkipped, fGlassSkip = glassSkipped;
            // ------------------------------------------------------------------
            // T37 : EMPRISE REELLE AVANT PRE-CHARGEMENT
            // ------------------------------------------------------------------
            // La boite pre-chargee etait centree sur le POINT d'ancrage avec un rayon
            // de 155 blocs (327x327), alors que le paste couvre rotatedPos ->
            // rotatedPos + taille (174x123x278, soit 192x296 avec la marge de
            // StructureSites). Toute la bande de Z au-dela de +155 n'etait donc pas
            // epinglee : mesure au run t36_run2, « 435 demandes de chargement »
            // PENDANT la phase de pose (tick de 9 145 ms, 6 268 blocs differes).
            // On calcule maintenant la position finale et l'emprise AVANT de
            // pre-charger, et on pre-charge EXACTEMENT cette emprise.
            final BlockPos plannedPos = new BlockPos(everestSpot.getX() + EVEREST_OFFSET_X,
                    0, everestSpot.getZ() + EVEREST_OFFSET_Z);
            final Rotation rotation = everestRotation;
            final BlockPos plannedRotatedPos = adjustForRotation(plannedPos, template, rotation);
            StructurePlaceSettings footprintSettings = new StructurePlaceSettings().setRotation(rotation);
            // T88 : emprise par le helper partage (meme formule que l'evaluateur Y0).
            int[] fp = everestFootprintOffsets(footprintSettings, fEverestBlocks);
            final BlockPos plannedMin = plannedRotatedPos.offset(fp[0], 0, fp[1]);
            final BlockPos plannedMax = plannedRotatedPos.offset(fp[2], size.getY() - 1, fp[3]);
            StructureTerrainPrep.preloadEditedTerrain(level,
                    new BlockPos(plannedMin.getX(), level.getMinBuildHeight(), plannedMin.getZ()),
                    new BlockPos(plannedMax.getX(), level.getMaxBuildHeight() - 1, plannedMax.getZ()),
                    everestSpot, () -> {
            int baseY = EVEREST_FOLLOW_SURFACE
                    ? deepluckyblock.util.SafeSurface.surfaceY(level, everestSpot.getX(), everestSpot.getZ(), origin.getY())
                    : origin.getY();
            int pasteY = baseY + EVEREST_OFFSET_Y - fMinRelY - EVEREST_SINK_BLOCKS;
            final BlockPos rotatedPos = plannedRotatedPos.offset(0, pasteY, 0);
            final BlockPos eMin = plannedMin.offset(0, pasteY, 0);
            final BlockPos eMax = plannedMax.offset(0, pasteY, 0);
            // === T83/T88 (Y0) : PLATEFORME < EAU EXTERIEURE + 1 = ALERTE SEULE ===
            // Meme regle que les autres structures : l'Everest non plus ne devrait
            // pas s'ancrer sous un plan d'eau voisin (le spot est deja exige sec
            // en surface par T80 ; Y0 controle l'altitude relative). Depuis T88
            // (consigne 29/09), l'abandon pur et dur est INTERDIT : le choix du
            // site Y0 a deja ete fait en amont (anneaux de repli) et, si aucun
            // site voisin ne convenait, on pose quand meme -- la zone epinglee
            // continue et les digues exterieures font la garde de l'eau.
            int y0Water = deepluckyblock.util.SafeSurface.maxWaterLevelOutside(level,
                    eMin.getX(), eMin.getZ(), eMax.getX(), eMax.getZ(),
                    deepluckyblock.util.SafeSurface.Y0_BAND, deepluckyblock.util.SafeSurface.Y0_STEP);
            if (y0Water != Integer.MIN_VALUE && baseY < y0Water + 1)
                LOGGER.warn("[STRUCT4-EVEREST] plateforme Y={} sous le plafond Y0 (eau exterieure max Y={} "
                                + "a moins de {} blocs hors-emprise) -- POSE MAINTENUE (T88 : aucun site "
                                + "voisin ne faisait mieux ; digues conservees)",
                        baseY, y0Water, deepluckyblock.util.SafeSurface.Y0_BAND);
            if (y0Water != Integer.MIN_VALUE && baseY >= y0Water + 1)
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] Y0 OK -- plateforme Y={} >= eau exterieure Y={} + 1", baseY, y0Water);
            // === T28 : EVEREST ENFONCE DANS LE TERRAIN (consigne du 20/09 : « l'everest
            // etait sencé s'enfoncer dans le terrain ») : on retire EVEREST_SINK_BLOCKS
            // de plus que l'offset de base, pour que la montagne entre dans le relief.
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] enfoncement de {} bloc(s) dans le terrain (T28)", EVEREST_SINK_BLOCKS);
            deepluckyblock.util.DebugLog.setPhase("paste everest (blocs)");
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] Position : {} (minRelY={})", rotatedPos.toShortString(), fMinRelY);
            // T27 : emprise de l'Everest (192x296 au sol, cf. NBT) pour que les autres
            // structures ne viennent pas s'y poser.
            deepluckyblock.util.StructureSites.register(level, EVEREST_NBT,
                    rotatedPos.getX(), rotatedPos.getZ(), rotatedPos.getX() + 192, rotatedPos.getZ() + 296);
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] Filtrage : {} solides, air skip={}, verre skip={}", fSolid, fAirSkip, fGlassSkip);
            // T37 : l'emprise (eMin/eMax) est deja calculee plus haut, avant le
            // pre-chargement -- elle vient de la meme source (rotatedPos + taille).
            BlockPos min = eMin;
            BlockPos max = eMax;
            final BlockPos[] cherryHolder = new BlockPos[1];
            Runnable onComplete = () -> {
                scheduleEverestManish(level);
                BlockPos spawnAt = cherryHolder[0];
                if (spawnAt != null) { level.setBlock(spawnAt, Blocks.AIR.defaultBlockState(), 3); spawnManishNPC(level, spawnAt); }
                else { BlockPos topPos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(origin.getX(), 0, origin.getZ())); spawnManishNPC(level, topPos); }
            };
            // ==================================================================
            // T39 : LA PASSE FINALE DE TERRAIN PASSE *AVANT* LE PASTE
            // ==================================================================
            // Consigne dev (23/09) : « ENORME erreur : la structure doit paste en
            // absolu dernier, apres tous les smooths ». La passe finale de terrain
            // de l'everest (T36) tournait APRES le paste : elle est deplacee ici,
            // juste avant le premier bloc de la montagne, avec /fixwater + /fixlava
            // dans la foulee. Le PostJob post-pose ne fait donc PLUS de terrain.
            // === T99 : EVEREST = PASTE -a PUR, PLUS AUCUN TERRAIN NI LIQUIDE ===
            // Consigne utilisateur (29/09 au soir, sans equivoque) : « pour
            // l'everest dans ce cas la, ARRETE de toucher au terrain et aux
            // liquides ! l'everest doit juste etre paste -a, avoir les coffres
            // supprimes, manish en haut avec des exchanges differents. »
            //
            // Supprimes de ce chemin, definitivement : sealUndergroundGaps (T40,
            // 169,9 s mesures sur ce run), finalTerrainPass (T36) et
            // fixLiquidsPassOnDemand (T38) : la montagne se fiche elle-meme dans
            // le relief par son enfoncement (T28) et son paste a air exclus
            // (= « paste -a » : les blocs d'air du template ne sont JAMAIS poses,
            // voir le filtrage de planEverest -- la structure ne creuse rien,
            // mais n'importe pas de vide non plus). Le reste est conserve :
            // pre-chargement de l'emprise finale (T30/T37, indispensable pour ne
            // pas generer des chunks en pleine pose), pose tranchee, suppression
            // des coffres (cleanupEverestChests), Manish en haut (variante
            // Everest dediee, voir spawnManishNPC(..., true)) et le PostJob de
            // nettoyage (verre flottant + recompenses) qui release les chunks.
            // T157 : preloadEditedTerrain ci-dessus couvre déjà exactement toute
            // l'emprise. Une seconde barrière preloadBox relançait six attentes
            // de 5 s (32,6 s mesurées, 100 % du coût Everest).
            EVEREST_QUEUE.offer(new EverestJob(level, fEverestBlocks, rotatedPos, rotation, Mirror.NONE, t0, nearestPlayer, onComplete, cherryHolder));
            POST_QUEUE.offer(new PostJob(level, min, max, "everest", false));
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-EVEREST] paste -a immédiat après l'unique chargement de zone");
            if (nearestPlayer != null) nearestPlayer.sendSystemMessage(Component.literal("§b§l⛰ Everest en construction"));
                    });   // T30 : fin du pre-chargement de l'emprise finale
        };
        if (EVEREST_DISTANCE > 0) {
            // Preserve the configured distant-site search; only its default direct
            // placement can skip the independent origin probe.
            StructureTerrainPrep.preloadBox(level,
                    origin.offset(-16, 0, -16), origin.offset(16, 0, 16), planEverest);
        } else {
            planEverest.run();
        }
        return true;
    }

    public static boolean buildRainbowJump(ServerLevel l, BlockPos o, RandomSource r) { return spawnCircus(l, o); }
    public static boolean buildInoxtagEverest(ServerLevel l, BlockPos o, RandomSource r) { return spawnEverest(l, o); }
    public static boolean buildAbandonedShipwreck(ServerLevel l, BlockPos o, RandomSource r) { return spawnShipdead(l, o); }
    public static boolean buildAbandonedCircus(ServerLevel l, BlockPos o, RandomSource r) { return spawnCircus(l, o); }

    private static void scheduleEverestManish(ServerLevel level) {
        DELAYED_ACTIONS.add(new DelayedAction(() -> level.getServer().getPlayerList().broadcastSystemMessage(Component.literal("§b§l[Manish] §fAU SECOURRRR !!!"), false), 200));
        DELAYED_ACTIONS.add(new DelayedAction(() -> level.getServer().getPlayerList().broadcastSystemMessage(Component.literal("§b§l[Manish] §fViens m'aider, jte met bien le rho !!"), false), 260));
    }
    private static void spawnManishNPC(ServerLevel level, BlockPos pos) {
        spawnManishNPC(level, pos, true);
    }
    private static void spawnManishNPC(ServerLevel level, BlockPos pos, boolean everestVariant) {
        Villager manish = new Villager(EntityType.VILLAGER, level);
        manish.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        manish.setVillagerData(new VillagerData(VillagerType.SNOW, VillagerProfession.NITWIT, 5));
        // T99 (consigne 29/09 : « manish en haut avec des exchanges differents ») :
        // au SOMMET de l'Everest, Manish porte un profil d'offres DISTINCT du
        // profil historique -- recompenses uniquement haut de gamme (luck 40-100
        // au lieu de 15-100), couts en blocs precieux bien plus frequents
        // (45% au lieu de 10%) et remises vanilla plus presentes (45 % au lieu
        // de 30 %, sur une fenetre 10-30 %). Le profil historique reste
        // disponible via spawnManishNPC(level, pos, false).
        if (everestVariant) {
            manish.setCustomName(Component.literal("§b§lManish §3§l⛰"));
        } else {
            manish.setCustomName(Component.literal("§b§lManish"));
        }
        manish.setCustomNameVisible(true);
        manish.setPersistenceRequired(); manish.setInvulnerable(true); manish.setNoAi(true);
        RandomSource random = level.getRandom();
        int offerCount = everestVariant ? 8 : 7;
        for (int i = 0; i < offerCount; i++) {
            int luck = everestVariant ? 40 + random.nextInt(61) : 15 + random.nextInt(86);
            ItemStack reward;
            try { reward = GenerateLuckyItemProcedure.generate(level, luck, TestProcedure.QualityTier.getQualityTier(luck), random); if (reward == null || reward.isEmpty()) reward = new ItemStack(Items.GOLDEN_APPLE); }
            catch (Exception ex) { reward = new ItemStack(Items.GOLDEN_APPLE); }
            ItemStack cost;
            if (random.nextInt(100) < (everestVariant ? 45 : 10)) {
                int coin = random.nextInt(3); int count = manishPriced(4 + random.nextInt(16), random);
                cost = coin == 0 ? new ItemStack(Items.GOLD_BLOCK, count) : coin == 1 ? new ItemStack(Items.EMERALD_BLOCK, count) : new ItemStack(Items.DIAMOND_BLOCK, count);
            } else {
                cost = luck >= 85 ? new ItemStack(Items.NETHERITE_BLOCK, manishPriced(1 + random.nextInt(5), random)) : luck >= 60 ? new ItemStack(Items.NETHERITE_INGOT, manishPriced(8 + random.nextInt(16), random)) : new ItemStack(Items.NETHERITE_INGOT, manishPriced(2 + random.nextInt(6), random));
            }
            MerchantOffer offer = new MerchantOffer(new ItemCost(cost.getItem(), cost.getCount()), reward, 1, 0, 0f);
            // FIX (demande en jeu : "quand on ouvre la liste des echanges
            // possibles, je veux que le trade avec discount ait un background
            // different sur le bouton, comme une teinte rougeatre, sur la
            // texture vanilla") : pour qu'une teinte de remise ait un sens, il
            // faut d'abord que Manish PROPOSE reellement des remises -- ses
            // offres etaient jusqu'ici toutes au prix plein
            // (specialPriceDiff = 0, donc jamais soldees).
            //
            // ~30% des offres recoivent donc desormais une vraie remise
            // vanilla de 15 a 40% du cout, posee via setSpecialPriceDiff() en
            // NEGATIF (convention vanilla : diff negatif = prix reduit). On
            // passe par le mecanisme officiel plutot qu'en baissant le cout de
            // base, pour que le client affiche nativement le prix barre ET que
            // ManishDiscountTint puisse reperer l'offre soldee.
            int discountChance = everestVariant ? 45 : 30;
            if (random.nextInt(100) < discountChance) {
                int baseCount = cost.getCount();
                float minRebate = everestVariant ? 0.10f : 0.15f;
                float span = everestVariant ? 0.20f : 0.25f;
                int rebate = Math.max(1, Math.round(baseCount * (minRebate + random.nextFloat() * span)));
                // Jamais en dessous de 1 item de cout apres remise.
                rebate = Math.min(rebate, baseCount - 1);
                if (rebate > 0) offer.setSpecialPriceDiff(-rebate);
            }
            manish.getOffers().add(offer);
        }
        level.addFreshEntity(manish);
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] Manish spawn à {} (variante {})", pos.toShortString(), everestVariant ? "Everest" : "historique");
    }
    private static boolean isStrippedCherry(BlockState state) { return state.is(Blocks.STRIPPED_CHERRY_LOG) || state.is(Blocks.STRIPPED_CHERRY_WOOD) || state.is(Blocks.CHERRY_LOG) || state.is(Blocks.CHERRY_WOOD); }
    private static Rotation computeFacingRotation(BlockPos structPos, Player player, Direction nativeFacing) {
        if (player == null) return Rotation.NONE;
        double dx = player.getX() - structPos.getX(); double dz = player.getZ() - structPos.getZ();
        Direction towardPlayer = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        int rotation = (dirToSteps(towardPlayer) - dirToSteps(nativeFacing) + 4) % 4;
        return switch (rotation) { case 1 -> Rotation.CLOCKWISE_90; case 2 -> Rotation.CLOCKWISE_180; case 3 -> Rotation.COUNTERCLOCKWISE_90; default -> Rotation.NONE; };
    }
    private static int dirToSteps(Direction d) { return switch (d) { case SOUTH -> 0; case WEST -> 1; case NORTH -> 2; case EAST -> 3; default -> 0; }; }

    private static Set<Long> detectInteriorAir(List<StructureTemplate.StructureBlockInfo> blocks, Vec3i size) {
        int sx = size.getX(), sy = size.getY(), sz = size.getZ();
        boolean[][][] solid = new boolean[sx][sy][sz];
        for (StructureTemplate.StructureBlockInfo info : blocks) { BlockPos p = info.pos(); int x = p.getX(), y = p.getY(), z = p.getZ(); if (x >= 0 && x < sx && y >= 0 && y < sy && z >= 0 && z < sz) solid[x][y][z] = !info.state().isAir(); }
        boolean[][][] exterior = new boolean[sx][sy][sz]; ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < sx; x++) for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++)
            if ((x == 0 || x == sx - 1 || y == 0 || y == sy - 1 || z == 0 || z == sz - 1) && !solid[x][y][z]) { exterior[x][y][z] = true; queue.add(new int[]{x, y, z}); }
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) { int[] c = queue.poll(); for (int[] d : dirs) { int nx = c[0] + d[0], ny = c[1] + d[1], nz = c[2] + d[2]; if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) continue; if (exterior[nx][ny][nz] || solid[nx][ny][nz]) continue; exterior[nx][ny][nz] = true; queue.add(new int[]{nx, ny, nz}); } }
        Set<Long> result = new HashSet<>();
        for (int x = 0; x < sx; x++) for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) if (!solid[x][y][z] && !exterior[x][y][z]) result.add(encPos(x, y, z));
        return result;
    }
    private static long encPos(int x, int y, int z) { return ((long) (x & 0x7FF) << 22) | ((long) (y & 0x7FF) << 11) | (long) (z & 0x7FF); }

    public static boolean isPlayerChunk(BlockPos candidate, Player player, BlockPos origin) {
        int cx = candidate.getX() >> 4, cz = candidate.getZ() >> 4, ox = origin.getX() >> 4, oz = origin.getZ() >> 4;
        if (cx == ox && cz == oz) return true;
        if (player != null && cx == (player.blockPosition().getX() >> 4) && cz == (player.blockPosition().getZ() >> 4)) return true;
        return false;
    }

    public static BlockPos findSafeOutsideChunkPos(ServerLevel level, BlockPos origin) {
        int[][] offsets = {{32, 0}, {-32, 0}, {0, 32}, {0, -32}, {32, 32}, {-32, -32}, {32, -32}, {-32, 32}};
        int wetSkipped4 = 0;
        for (int[] off : offsets) { int cx = origin.getX() + off[0], cz = origin.getZ() + off[1];
            // FIX T7 : hauteur lue sur un chunk dont la heightmap est initialisee (voir SafeSurface).
            int cy = deepluckyblock.util.SafeSurface.surfaceY(level, cx, cz, origin.getY());
            // T80 : jamais d'ancrage hors-chunk sous l'eau (le Y serait le FOND) :
            // l'offset humide est saute, le suivant essaye.
            if (deepluckyblock.util.SafeSurface.columnWet(level, cx, cz)) { wetSkipped4++; continue; }
            BlockPos cand = new BlockPos(cx, cy, cz); if (!isPlayerChunk(cand, null, origin)) return cand; }
        if (wetSkipped4 > 0)
            LOGGER.warn("[STRUCT4-FLAT] findSafeOutsideChunkPos : {} offset(s) sous l'eau ecartes (T80)", wetSkipped4);
        return origin.offset(32, 0, 32);
    }

    private static BlockPos findFlatArea(ServerLevel level, BlockPos origin, int structW, int structD) {
        long t0 = System.currentTimeMillis();
        Player player = level.getNearestPlayer(origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5, 128, false);
        double lookX = 0, lookZ = 0; BlockPos playerPos = origin;
        if (player != null) { float yaw = player.getYRot(); lookX = -Math.sin(Math.toRadians(yaw)); lookZ = Math.cos(Math.toRadians(yaw)); playerPos = player.blockPosition(); }
        int checkR = Math.max(8, Math.max(structW, structD) / 2);
        int maxDiff = Math.max(3, checkR / 8);
        // FIX CHUNKS FANTOMES (meme bug "giga montagne" que Structures5Procedure.findFlat,
        // voir le commentaire detaille la-bas) : measureFlatnessZone() n'interroge que
        // getHeight() SANS forcer la generation des chunks. Sur un chunk pas encore
        // genere, cette mesure peut etre totalement fausse (terrain "plat" fantome),
        // demasque uniquement une fois le terrain reellement genere par smoothAround/
        // prepZone -> une montagne entiere peut alors se retrouver sous/autour du
        // batiment. On garde donc les meilleurs candidats (mesure rapide, chunks NON
        // forces) puis on verifie le meilleur avec generation forcee + re-mesure,
        // en essayant le suivant si le terrain reel s'avere ne pas etre plat.
        record FlatAreaCandidate(int cx, int cz, int diff, int score) {}
        List<FlatAreaCandidate> goodCandidates = new ArrayList<>();
        int bestDiff = Integer.MAX_VALUE, bdX = 0, bdZ = 0;
        int flat4WetSkipped = 0;   // T80 : compteur de cellules ecartees (centre sous l'eau)
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx += SEARCH_STEP) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz += SEARCH_STEP) {
                int cx = origin.getX() + dx, cz = origin.getZ() + dz;
                // BUG CRITIQUE CORRIGE (voir Structures5Procedure.findFlat pour le
                // detail) : closerThan() calculait une distance 3D entre le VRAI Y
                // du joueur et un Y FICTIF de 64 sur le candidat. En altitude
                // (montagne), cet ecart vertical suffisait a lui seul a "faire
                // semblant" d'etre loin, meme si le candidat etait juste a cote
                // du joueur en horizontal -> la structure pouvait apparaitre au
                // sommet de la montagne au lieu d'aller chercher le terrain plat
                // en contrebas. On compare desormais uniquement X/Z.
                if (player != null) {
                    double dpx = cx - player.blockPosition().getX(), dpz = cz - player.blockPosition().getZ();
                    if ((dpx * dpx + dpz * dpz) < 80.0 * 80.0) continue;
                }
                if (isPlayerChunk(new BlockPos(cx, 64, cz), player, origin)) continue;
                // T80 : JAMAIS de zone dont le centre est sous l'eau. La mesure de
                // planeite traverse l'eau (heightmap) : un fond d'ocean serait note
                // « plat » et gagnerait le classement « moins pentu » -- structure
                // ancree au FOND, eau de la zone videe ensuite.
                if (deepluckyblock.util.SafeSurface.columnWet(level, cx, cz)) { flat4WetSkipped++; continue; }
                int diff = measureFlatnessZone(level, cx, cz, checkR);
                if (diff < 0) continue;
                if (diff < bestDiff) { bestDiff = diff; bdX = cx; bdZ = cz; }
                if (diff <= maxDiff) {
                    int score = scoreCandidate(cx, cz, playerPos, lookX, lookZ);
                    goodCandidates.add(new FlatAreaCandidate(cx, cz, diff, score));
                }
            }
        }
        if (flat4WetSkipped > 0)
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-FLAT] {} cellule(s) ecartee(s) : centre sous l'EAU (T80)", flat4WetSkipped);
        goodCandidates.sort((a, b) -> Integer.compare(b.score(), a.score()));
        int maxVerified = Math.min(goodCandidates.size(), 12);
        // T49 : candidats dont la zone n'etait pas encore en memoire (reexamines
        // apres la boucle, le temps que les demandes en tache de fond aboutissent).
        java.util.List<FlatAreaCandidate> pendingVerif = new java.util.ArrayList<>();
        // FIX (freeze silencieux au paste, meme cause que Structures5Procedure.findFlat
        // -- voir Javadoc la-bas) : budget de temps + log AVANT de forcer la
        // generation de chaque candidat, pour ne plus jamais bloquer le
        // thread principal en silence pendant potentiellement des dizaines
        // de secondes sur un monde au chunk generator lent.
        long verifyStart4 = System.currentTimeMillis();
        for (int i = 0; i < maxVerified; i++) {
            if (i > 0 && System.currentTimeMillis() - verifyStart4 > FIND_FLAT_TIME_BUDGET_MS) {
                LOGGER.warn("[STRUCT4-FLAT] Budget de temps ({}ms) depasse apres {} candidat(s) verifie(s) -- abandon des {} candidats restants",
                        FIND_FLAT_TIME_BUDGET_MS, i, maxVerified - i);
                break;
            }
            FlatAreaCandidate c = goodCandidates.get(i);
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-FLAT] Verification candidat #{}/{} a {},{} (force generation de la zone, checkR={})...",
                    i + 1, maxVerified, c.cx(), c.cz(), checkR);
            int ccx0 = (c.cx() - checkR) >> 4, ccx1 = (c.cx() + checkR) >> 4;
            int ccz0 = (c.cz() - checkR) >> 4, ccz1 = (c.cz() + checkR) >> 4;
            // T49 : la generation SYNCHRONE de toute la zone du candidat (jusqu'a
            // ~300 chunks pour une grosse structure, x12 candidats) etait le plus
            // gros consommateur de temps du mod sur un monde neuf. On demande la
            // zone en tache de fond et on ne verifie le candidat que lorsqu'elle
            // est REELLEMENT en memoire.
            // Load only the selected footprint through the bounded preload queue.
            if (!deepluckyblock.util.SafeSurface.zoneLoaded(level, ccx0, ccz0, ccx1, ccz1)) {
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-FLAT] Candidat #{}/{} a {},{} : zone pas encore en memoire -- verification differee (tache de fond, aucun blocage)",
                        i + 1, maxVerified, c.cx(), c.cz());
                pendingVerif.add(c);
                continue;
            }
            int realDiff = measureFlatnessZone(level, c.cx(), c.cz(), checkR);
            if (realDiff < 0 || realDiff > maxDiff * 2) {
                LOGGER.warn("[STRUCT4-FLAT] Candidat {},{} REJETE : terrain fantome demasque apres generation reelle (diff avant={}, apres={})",
                        c.cx(), c.cz(), c.diff(), realDiff);
                continue;
            }
            // T80 : la mesure de planeite traverse l'eau ; le centre a ete teste sec,
            // on exige ici que la ZONE entiere le soit (riviere/etang dans l'emprise).
            double wet4 = wetRatioArea(level, c.cx(), c.cz(), checkR);
            if (wet4 > 0.10) {
                LOGGER.warn("[STRUCT4-FLAT] Candidat {},{} REJETE : zone en EAU ({} % des colonnes sont liquides) -- plate mais inconstructible (T80)",
                        c.cx(), c.cz(), (int) (wet4 * 100));
                continue;
            }
            int surfY = deepluckyblock.util.SafeSurface.surfaceY(level, c.cx(), c.cz(), origin.getY());
            return new BlockPos(c.cx(), surfY, c.cz());
        }
        // T49 : seconde passe sur les candidats differes (leurs chunks ont ete
        // demandes pendant la premiere passe, ils sont souvent arrives depuis).
        for (FlatAreaCandidate c : pendingVerif) {
            if (System.currentTimeMillis() - verifyStart4 > FIND_FLAT_TIME_BUDGET_MS) break;
            int ccx0 = (c.cx() - checkR) >> 4, ccx1 = (c.cx() + checkR) >> 4;
            int ccz0 = (c.cz() - checkR) >> 4, ccz1 = (c.cz() + checkR) >> 4;
            if (!deepluckyblock.util.SafeSurface.zoneLoaded(level, ccx0, ccz0, ccx1, ccz1)) continue;
            int realDiff2 = measureFlatnessZone(level, c.cx(), c.cz(), checkR);
            if (realDiff2 < 0 || realDiff2 > maxDiff * 2) continue;
            double wet42 = wetRatioArea(level, c.cx(), c.cz(), checkR);   // T80
            if (wet42 > 0.10) {
                LOGGER.warn("[STRUCT4-FLAT] Candidat {},{} REJETE (seconde passe) : zone en EAU ({} % des colonnes, T80)",
                        c.cx(), c.cz(), (int) (wet42 * 100));
                continue;
            }
            int surfY2 = deepluckyblock.util.SafeSurface.surfaceY(level, c.cx(), c.cz(), origin.getY());
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4-FLAT] Zone choisie a {},{} (seconde passe T49, terrain reel verifie, Y reel={})", c.cx(), c.cz(), surfY2);
            return new BlockPos(c.cx(), surfY2, c.cz());
        }
        if (!goodCandidates.isEmpty()) {
            LOGGER.warn("[STRUCT4-FLAT] {} candidats testes, tous rejetes comme terrain fantome -- fallback pente minimale", maxVerified);
        }
        if (bestDiff < Integer.MAX_VALUE) {
            // Meme verification pour le fallback pente-minimale : force la generation
            // puis re-mesure avant de valider definitivement.
            int ccx0 = (bdX - checkR) >> 4, ccx1 = (bdX + checkR) >> 4;
            int ccz0 = (bdZ - checkR) >> 4, ccz1 = (bdZ + checkR) >> 4;
            // T49 : on DEMANDE la zone (tache de fond) sans jamais bloquer ; si elle
            // n'est pas prete, la position mesuree est conservee telle quelle et
            // l'emprise sera de toute facon pre-chargee avant la pose (T43).
            // Load only the selected footprint through the bounded preload queue.
            // T80 : « le moins pentu » ne doit JAMAIS etre un plan d'eau -- la
            // heightmap traverse l'eau, une mer gagnerait toujours ce classement.
            double wetBd = wetRatioArea(level, bdX, bdZ, checkR);
            if (wetBd > 0.10) {
                LOGGER.warn("[STRUCT4-FLAT] Repli « moins pentu » {},{} sous l'EAU ({} % des colonnes) -- refuse (T80), repli hors chunk sec",
                        bdX, bdZ, (int) (wetBd * 100));
                return findSafeOutsideChunkPos(level, origin);
            }
            int realBdY = deepluckyblock.util.SafeSurface.surfaceY(level, bdX, bdZ, origin.getY());
            LOGGER.warn("[STRUCT4-FLAT] Pas assez plat (min diff={}, max={}), utilise le moins pentu (verifie)", bestDiff, maxDiff);
            return new BlockPos(bdX, realBdY, bdZ);
        }
        // FIX T7 : ne jamais renvoyer null (l'appelant retombait alors sur une
        // hauteur fantome). On renvoie une position hors du chunk d'origine avec
        // une hauteur REELLE lue via SafeSurface.
        LOGGER.warn("[STRUCT4-FLAT] Aucune zone valide trouvee ({}ms) -- repli sur une position hors chunk avec hauteur reelle",
                System.currentTimeMillis() - t0);
        return findSafeOutsideChunkPos(level, origin);
    }
    /**
     * T80 : part des colonnes de la zone dont la SURFACE est un liquide (eau/lave),
     * mesuree BLOC PAR BLOC (surfaceScanEx) -- jamais la heightmap : les
     * MOTION_BLOCKING_* TRAVERSENT l'eau et designent le FOND, donc
     * measureFlatnessZone() jugeait une mer « plate et seche » et la choisissait
     * comme « moins pentue ». Renvoie 0 si moins de 30 % des colonnes sont
     * mesurables (chunks absents) : on ne rejette jamais sur de l'inconnu.
     */
    private static double wetRatioArea(ServerLevel level, int cx, int cz, int radius) {
        int step = Math.max(1, radius / 4);
        int total = 0, known = 0, wet = 0;
        boolean[] wetCell = new boolean[1];
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int x = cx + dx, z = cz + dz;
                var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) continue;
                total++;
                int y = deepluckyblock.util.SafeSurface.surfaceScanEx(chunk, level, x, z, wetCell);
                if (y == Integer.MIN_VALUE) continue;
                known++;
                if (wetCell[0]) wet++;
            }
        }
        return known * 10 < total * 3 ? 0 : (double) wet / known;
    }

    private static int measureFlatnessZone(ServerLevel level, int cx, int cz, int radius) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int step = Math.max(1, radius / 3);
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int x = cx + dx, z = cz + dz;
                var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return -1;
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                if (y <= level.getMinBuildHeight()) return -1;
                BlockState state = chunk.getBlockState(p.set(x, y, z));
                if (state.isAir() || !state.getFluidState().isEmpty() || !state.blocksMotion()) return -1;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
            }
        }
        return maxY - minY;
    }
    private static int scoreCandidate(int cx, int cz, BlockPos pp, double lx, double lz) {
        int score = 0; double dx = cx - pp.getX(), dz = cz - pp.getZ(); double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1) return 100; score += Math.max(0, 50 - (int) (Math.abs(dist - 25) * 2));
        if (lx != 0 || lz != 0) { double dot = (dx * lx + dz * lz) / dist; if (dot > Math.cos(VIEW_CONE / 2)) score += 100 + (int) (dot * 50); else if (dot > 0) score += 30; else score -= 20; }
        return score;
    }

    private static boolean isEverestNbt(String nbt) { return "everest".equalsIgnoreCase(nbt); }
    private static boolean isChunkAreaLoaded(ServerLevel level, BlockPos center, int minChunks, Player nearest) {
        int cx = center.getX() >> 4, cz = center.getZ() >> 4, loaded = 0, radius = (int) Math.ceil(Math.sqrt(minChunks));
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) { net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz); if (chunk != null) loaded++; }
        return loaded >= minChunks;
    }
    private static BlockPos findFarthestLoadedPos(ServerLevel level, BlockPos origin, int dist, int minChunksReq) {
        Player pl = level.getNearestPlayer(origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5, 128, false);
        RandomSource r = level.getRandom(); BlockPos best = null; int bestDistSq = -1, attempts = 0;
        while (attempts < 60) { double a = r.nextDouble() * Math.PI * 2; int dx = (int) Math.round(Math.cos(a) * dist), dz = (int) Math.round(Math.sin(a) * dist); int cx = origin.getX() + dx, cz = origin.getZ() + dz; int cy = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 1; BlockPos cand = new BlockPos(cx, cy, cz);
            // T80 : un point lointain au beau milieu d'un ocean ne doit jamais
            // devenir un ancrage (le Y heightmap serait le FOND).
            if (deepluckyblock.util.SafeSurface.columnWet(level, cx, cz)) { attempts++; continue; }
            if (isChunkAreaLoaded(level, cand, minChunksReq, pl)) { int dSq = dx * dx + dz * dz; if (dSq > bestDistSq) { bestDistSq = dSq; best = cand; } } attempts++; }
        return best;
    }

    private interface PasteCallback { void onComplete(BlockPos pastedPos, ServerLevel level, Player player); }

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
        sorted.sort((a, b) -> { int ya = a.pos().getY(), yb = b.pos().getY(); if (ya != yb) return Integer.compare(ya, yb); int xa = a.pos().getX(), xb = b.pos().getX(); if (xa != xb) return Integer.compare(xa, xb); return Integer.compare(a.pos().getZ(), b.pos().getZ()); });
        return sorted;
    }

    private static boolean doPaste(ServerLevel level, BlockPos origin, String nbtName, int offX, int offY, int offZ, int distance, Direction nativeFacing, boolean followSurface, boolean useFlatArea, boolean preserveInterior, boolean isEverest, boolean skipTerrain, PasteCallback callback) {
        long tGlobal = System.currentTimeMillis();
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] === PASTE {} === off=[{},{},{}] dist={} facing={} followSurf={} flatArea={} presInt={}", nbtName, offX, offY, offZ, distance, nativeFacing, followSurface, useFlatArea, preserveInterior);
        // FIX (freeze serveur ~78s au premier spawn de shipdead) : on passe
        // par le cache mod-wide (StructureTemplateCache) au lieu d'appeler
        // directement mgr.get()/.getOrCreate() -- si un précédent paste (ou le
        // préchargement au démarrage serveur) a déjà chargé ce NBT, on évite
        // toute nouvelle désérialisation synchrone couteuse.
        StructureTemplate template = deepluckyblock.util.StructureTemplateCache.get(level, nbtName);
        if (template == null) { LOGGER.error("[STRUCT4] Template vide ou échec chargement pour {}", nbtName); return false; }
        Vec3i size = template.getSize();
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] Taille : {}x{}x{}", size.getX(), size.getY(), size.getZ());
        Player nearestPlayer = level.getNearestPlayer(origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5, 128, false);
        int minChunksReq = isEverest ? MIN_CHUNKS_EVEREST : MIN_CHUNKS_NORMAL;
        BlockPos targetXZ;
        if (useFlatArea) { BlockPos flat = findFlatArea(level, origin, Math.abs(size.getX()), Math.abs(size.getZ())); if (flat != null) targetXZ = flat; else if (distance > 0) { targetXZ = findFarthestLoadedPos(level, origin, distance, minChunksReq); if (targetXZ == null) { if (nearestPlayer != null) nearestPlayer.sendSystemMessage(Component.literal("§c❌ " + nbtName + " annulé : pas assez de chunks chargés")); return false; } } else targetXZ = findSafeOutsideChunkPos(level, origin); }
        else if (distance > 0) { targetXZ = findFarthestLoadedPos(level, origin, distance, minChunksReq); if (targetXZ == null) { if (nearestPlayer != null) nearestPlayer.sendSystemMessage(Component.literal("§c❌ " + nbtName + " annulé : pas assez de chunks chargés")); return false; } } else targetXZ = findSafeOutsideChunkPos(level, origin);
        if (!isEverest) { BlockPos safePos = resolveSafePosition(level, targetXZ, nbtName); if (safePos == null) { if (nearestPlayer != null) nearestPlayer.sendSystemMessage(Component.literal("§c❌ " + nbtName + " annulé : zone dangereuse")); return false; } targetXZ = safePos; if (isPlayerChunk(targetXZ, nearestPlayer, origin)) targetXZ = findSafeOutsideChunkPos(level, origin); }
        // FIX T7 : ancrage sur une hauteur REELLE (voir SafeSurface). C'est ici que
        // circus/shipdead etaient ancres a Y=-65 quand la heightmap du chunk n'etait
        // pas initialisee (structure enterree de 130+ blocs au moment du paste).
        // T29 : garde-fou au point de passage commun. Si la position retenue
        // (zone plate, repli pente minimale, plus-loin-charge, hors chunk joueur)
        // tombe dans l'emprise d'une structure deja posee, on decale en spirale --
        // AVANT la lecture de hauteur, pour que baseY corresponde au nouveau site.
        // (L'everest a son propre chemin : voir spawnEverest.)
        if (!isEverest) targetXZ = deepluckyblock.util.StructureSites.freeOffset(level, targetXZ, 8, 32);
        // Terrain preparation needs a SOLID ground Y, not a tree-top/fluid height.
        // Keep raw placement unchanged: it re-measures ground after its preload below.
        int baseY = followSurface
                ? (skipTerrain
                    ? deepluckyblock.util.SafeSurface.surfaceY(level, targetXZ.getX(), targetXZ.getZ(), origin.getY())
                    : deepluckyblock.util.SafeSurface.groundY(level, targetXZ.getX(), targetXZ.getZ(), origin.getY()))
                : origin.getY();
        if (!skipTerrain && (baseY < level.getMinBuildHeight() || baseY >= level.getMaxBuildHeight())) {
            LOGGER.error("[STRUCT4] {} : selected groundY={} outside build height; terrain placement refused", nbtName, baseY);
            return false;
        }
        // T73 : liste compactee (l'air exterieur n'est plus materialise).
        List<StructureTemplate.StructureBlockInfo> rawBlocks = deepluckyblock.util.StructureTemplateCache.compactBlocks(nbtName);
        if (rawBlocks == null) rawBlocks = extractBlocks(template);
        // Verifier si rawBlocks est vide (fallback direct).
        if (rawBlocks.isEmpty()) { BlockPos fp0 = new BlockPos(targetXZ.getX() + offX, baseY + offY, targetXZ.getZ() + offZ); Rotation rot0 = computeFacingRotation(fp0, nearestPlayer, nativeFacing); BlockPos rp0 = adjustForRotation(fp0, template, rot0); BlockPos min0 = new BlockPos(Math.min(rp0.getX(), rp0.getX() + size.getX()), rp0.getY(), Math.min(rp0.getZ(), rp0.getZ() + size.getZ())); BlockPos max0 = min0.offset(Math.abs(size.getX()), size.getY(), Math.abs(size.getZ())); StructurePlaceSettings fb = new StructurePlaceSettings().setRotation(rot0).setMirror(Mirror.NONE).setIgnoreEntities(true).setFinalizeEntities(false).addProcessor(BlockIgnoreProcessor.AIR); template.placeInWorld(level, rp0, rp0, fb, level.getRandom(), FAST_FLAG); if (callback != null) callback.onComplete(rp0, level, nearestPlayer); StructureTerrainPrep.sweepFloatingNaturalPass(level, min0, max0, () -> StructureTerrainPrep.sealUndergroundGaps(level, min0, max0, () -> StructureTerrainPrep.finalTerrainPass(level, min0, max0, 6, () -> StructureTerrainPrep.fixLiquidsPass(level, min0, max0, () -> StructureTerrainPrep.preloadBox(level, min0, max0, () -> POST_QUEUE.offer(new PostJob(level, min0, max0, nbtName, false))))))); return true; }
        // Filtrer d'abord (verre/laggy supprimes), PUIS minRelY sur la liste filtree.
        // T73 : air interieur en cache (calcule une fois au chargement).
        Set<Long> cachedIntAir = deepluckyblock.util.StructureTemplateCache.interiorAir(nbtName);
        Set<Long> intAir = (isEverest || !preserveInterior) ? Collections.emptySet()
                : (cachedIntAir != null ? cachedIntAir : detectInteriorAir(rawBlocks, size));
        List<StructureTemplate.StructureBlockInfo> filtered = new ArrayList<>(rawBlocks.size());
        int solidCount = 0, airKept = 0, airSkipped = 0, laggySkipped = 0, glassSkipped = 0;
        for (StructureTemplate.StructureBlockInfo info : rawBlocks) {
            BlockState state = info.state();
            if (isLaggyBlock(state)) { laggySkipped++; continue; }
            if (isGlassBlock(state)) { glassSkipped++; continue; }
            // T102 : paste -a strict par defaut (voir PASTE_KEEP_INTERIOR_AIR) --
            // l'air d'interieur n'est retenu que si l'echappatoire est levee.
            if (state.isAir()) { if (PASTE_KEEP_INTERIOR_AIR && !isEverest && preserveInterior) { BlockPos p = info.pos(); if (intAir.contains(p.asLong())) { filtered.add(info); airKept++; } else airSkipped++; } else airSkipped++; }
            else { filtered.add(info); solidCount++; }
        }
        // minRelY sur la liste FILTREE (hors verre) pour ne pas fausser le placement.
        int minRelY = 0;
        if (!filtered.isEmpty()) { minRelY = Integer.MAX_VALUE; for (var b : filtered) { int by = b.pos().getY(); if (by < minRelY) minRelY = by; } }
        BlockPos finalPos = new BlockPos(targetXZ.getX() + offX, baseY + offY - minRelY, targetXZ.getZ() + offZ);
        Rotation rotation = computeFacingRotation(finalPos, nearestPlayer, nativeFacing);
        BlockPos rotatedPos = adjustForRotation(finalPos, template, rotation);
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} -> pos={} (baseY={}, minRelY={}, rot={}, {}ms)", nbtName, rotatedPos.toShortString(), baseY, minRelY, rotation, System.currentTimeMillis() - tGlobal);
        deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} -> {} solides + {} air int = {} total (skip {} air ext, {} laggy, {} verre)", nbtName, solidCount, airKept, filtered.size(), airSkipped, laggySkipped, glassSkipped);
        // Bounding box EXACTE de la structure (positions réellement rotées) : smooth et
        // paste parfaitement alignés (corrige décalage + swap X/Z rotations 90/270).
        StructurePlaceSettings bboxRs = new StructurePlaceSettings().setRotation(rotation).setMirror(Mirror.NONE);
        int bMinX=Integer.MAX_VALUE, bMinY=Integer.MAX_VALUE, bMinZ=Integer.MAX_VALUE;
        int bMaxX=Integer.MIN_VALUE, bMaxY=Integer.MIN_VALUE, bMaxZ=Integer.MIN_VALUE;
        for (StructureTemplate.StructureBlockInfo info : filtered) {
            BlockPos rel = StructureTemplate.calculateRelativePosition(bboxRs, info.pos());
            int bx=rotatedPos.getX()+rel.getX(), by=rotatedPos.getY()+rel.getY(), bz=rotatedPos.getZ()+rel.getZ();
            if (bx<bMinX)bMinX=bx; if(bx>bMaxX)bMaxX=bx;
            if (by<bMinY)bMinY=by; if(by>bMaxY)bMaxY=by;
            if (bz<bMinZ)bMinZ=bz; if(bz>bMaxZ)bMaxZ=bz;
        }
        BlockPos min = new BlockPos(bMinX, bMinY, bMinZ);
        BlockPos max = new BlockPos(bMaxX, bMaxY, bMaxZ);
        // === T83/T104 (Y0) : PLATEFORME >= EAU EXTERIEURE + 1, pose TOUJOURS ===
        // Meme consigne que Structures5/T104 (run citadel 29/09 : abandon
        // silencieux, « rien ne spawn ») : plus de rejet. La plateforme est
        // REMONTEE au plafond Y0 (eau exterieure + 1) et la pose a lieu quoi
        // qu'il arrive -- precedent T88-everest. Fait ICI, avant la creation
        // des lambdas de pose, pour que toutes les valeurs capturees
        // (finalPos, rotatedPos, min/max, baseY) tiennent compte du lift.
        if (!skipTerrain) {
            int y0Water = deepluckyblock.util.SafeSurface.maxWaterLevelOutside(level,
                    min.getX(), min.getZ(), max.getX(), max.getZ(),
                    deepluckyblock.util.SafeSurface.Y0_BAND, deepluckyblock.util.SafeSurface.Y0_STEP);
            if (y0Water != Integer.MIN_VALUE && baseY < y0Water + 1) {
                int lift = (y0Water + 1) - baseY;
                LOGGER.warn("[STRUCT4] {} : plateforme Y={} sous le plafond Y0 (eau exterieure max Y={} "
                                + "a moins de {} blocs hors-emprise) -- PLATEFORME REMONTEE de {} bloc(s) a Y={} "
                                + "et POSE MAINTENUE (T104 : jamais d'abandon silencieux, precedent T88-everest)",
                        nbtName, baseY, y0Water, deepluckyblock.util.SafeSurface.Y0_BAND,
                        lift, baseY + lift);
                baseY += lift;
                finalPos = finalPos.above(lift);
                rotatedPos = adjustForRotation(finalPos, template, rotation);
                min = min.above(lift); max = max.above(lift);
            } else if (y0Water != Integer.MIN_VALUE) {
                deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} : Y0 OK -- plateforme Y={} >= eau exterieure Y={} + 1", nbtName, baseY, y0Water);
            }
        }
        // T14 : groupe par section 16x16x16 + spirale (guide sections 42/43) au
        // lieu du tri « layer par layer ».
        filtered = orderBySectionSpiral(filtered, bboxRs, rotatedPos);
        final BlockPos selectedSite = targetXZ;
        final List<StructureTemplate.StructureBlockInfo> fFiltered = filtered;
        // T104 : le lift Y0 a pu reassigner baseY/rotatedPos/min/max ci-dessus --
        // les lambdas de pose capturent des copies FIGEES post-lift.
        final int fBaseY = baseY;
        final BlockPos fRotatedPos = rotatedPos;
        final BlockPos fMin = min, fMax = max;
        Runnable offerPaste = () -> {
            // Read the real ground after preload, not the fallback used on a cold chunk.
            int dy = skipTerrain && followSurface
                    ? deepluckyblock.util.SafeSurface.groundY(level, selectedSite.getX(), selectedSite.getZ(), fBaseY) - fBaseY
                    : 0;
            BlockPos pasteOrigin = fRotatedPos.offset(0, dy, 0);
            Runnable onComplete = () -> { if (callback != null) callback.onComplete(pasteOrigin, level, nearestPlayer); };
            PASTE_QUEUE.offer(new PasteJob(level, fFiltered, pasteOrigin, rotation, Mirror.NONE,
                    nbtName, tGlobal, nearestPlayer, onComplete, fMin.offset(0, dy, 0), fMax.offset(0, dy, 0), skipTerrain));
        };
        // T27 : emprise enregistree des maintenant (voir StructureSites).
        deepluckyblock.util.StructureSites.register(level, nbtName, min.getX(), min.getZ(), max.getX(), max.getZ());
        if (skipTerrain) {
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} : modif terrain DESACTIVEE (paste brut, ni prep ni carve)", nbtName);
            // Establish and retain the final footprint before any asynchronous placement.
            StructureTerrainPrep.preloadBox(level, min, max, offerPaste);
        } else {
            // Use the same selected plane for clearance and model placement.
            // offY belongs only to the foundation/model anchor, never to clearance.
            // (La garde Y0 est desormais appliquee EN AMONT, cf. T104 : la
            // plateforme a deja ete remontee si besoin et la pose n'est jamais
            // abandonnee.)
            final int selectedGroundY = baseY;
            final int foundationBaseY = selectedGroundY + offY;
            deepluckyblock.util.DebugLog.info(LOGGER, "[STRUCT4] {} : selected solid groundY={}, clearance starts at Y={}, model offsetY={}, foundationBaseY={}",
                    nbtName, selectedGroundY, selectedGroundY + 1, offY, foundationBaseY);
            // No post-smooth re-anchoring on this path (offerPaste uses dy=0).
            // Wait for ALL terrain work, then verify preload before offering paste.
            // (fMin/fMax : copies figees post-lift T104, min/max eux ont pu etre
            // reassignes par le lift et ne sont plus capturables par lambda.)
            StructureTerrainPrep.prepZone(level, fMin, fMax, foundationBaseY, selectedGroundY,
                    () -> StructureTerrainPrep.decorateTerrainOnly(level, fMin, fMax,
                            () -> StructureTerrainPrep.preloadBox(level, fMin, fMax, offerPaste)));
        }
        return true;
    }

    private static BlockPos applyDist(ServerLevel level, BlockPos origin, int dist) { RandomSource rng = level.getRandom(); double angle = rng.nextDouble() * Math.PI * 2; return origin.offset((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist)); }
    @SuppressWarnings("unchecked")
    private static List<StructureTemplate.StructureBlockInfo> extractBlocks(StructureTemplate t) {
        try { for (Field f : StructureTemplate.class.getDeclaredFields()) { f.setAccessible(true); Object val = f.get(t); if (val instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof StructureTemplate.Palette) return ((StructureTemplate.Palette) list.get(0)).blocks(); } }
        catch (Exception ex) { LOGGER.warn("[STRUCT4] Reflection échouée: {}", ex.getMessage()); }
        return Collections.emptyList();
    }
    private static BlockPos adjustForRotation(BlockPos pos, StructureTemplate t, Rotation rot) { Vec3i s = t.getSize(); return switch (rot) { case NONE -> pos; case CLOCKWISE_90 -> pos.offset(s.getZ() - 1, 0, 0); case CLOCKWISE_180 -> pos.offset(s.getX() - 1, 0, s.getZ() - 1); case COUNTERCLOCKWISE_90 -> pos.offset(0, 0, s.getX() - 1); }; }

    /**
     * T88 : emprise relative au point de paste ajuste pour la rotation --
     * {minRelX, minRelZ, maxRelX, maxRelZ}. Une seule formule pour le pre-
     * chargement (T37), le verrou Y0 et l'evaluateur d'anneaux : impossible
     * que les trois chemins soient en desaccord sur la surface couverte.
     */
    private static int[] everestFootprintOffsets(StructurePlaceSettings settings,
                                                 List<StructureTemplate.StructureBlockInfo> blocks) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var block : blocks) {
            BlockPos rel = StructureTemplate.calculateRelativePosition(settings, block.pos());
            minX = Math.min(minX, rel.getX()); maxX = Math.max(maxX, rel.getX());
            minZ = Math.min(minZ, rel.getZ()); maxZ = Math.max(maxZ, rel.getZ());
        }
        return new int[] { minX, minZ, maxX, maxZ };
    }

    /**
     * T88 : vrai si TOUTE la zone [min..max] (echantillonnee un chunk sur 4,
     * suffisant vu les marges) est deja chargee en memoire. getChunkNow ne
     * charge rien : cette garde garantit qu'aucune lecture de hauteur/eau ne
     * peut declencher une generation synchrone sur le thread serveur.
     */
    private static boolean zoneInMemory(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        var source = level.getChunkSource();
        int cx0 = minX >> 4, cx1 = maxX >> 4, cz0 = minZ >> 4, cz1 = maxZ >> 4;
        for (int cx = cx0; cx <= cx1; cx += 4)
            for (int cz = cz0; cz <= cz1; cz += 4)
                if (source.getChunkNow(cx, cz) == null) return false;
        // Les bords tombent rarement sur l'echantillon : verifier les coins.
        return source.getChunkNow(cx1, cz1) != null
                && source.getChunkNow(cx0, cz1) != null
                && source.getChunkNow(cx1, cz0) != null;
    }

    private static void postProcessLayer(ServerLevel level, BlockPos min, BlockPos max, int y) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(); BlockPos.MutableBlockPos chk = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x < max.getX(); x++) for (int z = min.getZ(); z < max.getZ(); z++) {
            pos.set(x, y, z); BlockState s = level.getBlockState(pos); if (s.isAir()) continue;
            if (isGlassBlock(s) && isFloating(level, pos, chk)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FAST_FLAG);
            else if (isCrimson(s)) { level.setBlock(pos, Blocks.AIR.defaultBlockState(), FAST_FLAG); spawnGlowItem(level, pos, makeYTItem(level, level.getRandom())); }
        }
    }
    private static boolean isFloating(ServerLevel level, BlockPos pos, BlockPos.MutableBlockPos chk) {
        for (Direction d : Direction.values()) { chk.set(pos.getX() + d.getStepX(), pos.getY() + d.getStepY(), pos.getZ() + d.getStepZ()); BlockState n = level.getBlockState(chk); if (!n.isAir() && n.blocksMotion()) return false; }
        return true;
    }
    private static boolean isCrimson(BlockState s) { return s.is(Blocks.CRIMSON_HYPHAE) || s.is(Blocks.STRIPPED_CRIMSON_HYPHAE) || s.is(Blocks.CRIMSON_STEM) || s.is(Blocks.STRIPPED_CRIMSON_STEM) || s.is(Blocks.CRIMSON_PLANKS); }
    private static ItemStack makeYTItem(ServerLevel level, RandomSource random) {
        try { int luck = 60 + random.nextInt(31); ItemStack res = GenerateLuckyItemProcedure.generate(level, luck, TestProcedure.QualityTier.getQualityTier(luck), random); if (res != null && !res.isEmpty()) return res; } catch (Exception ignored) {}
        ItemStack a = new ItemStack(Items.GOLDEN_APPLE); a.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§6§l✦ Trésor du Créateur ✦")); return a;
    }
    private static void spawnGlowItem(ServerLevel level, BlockPos pos, ItemStack item) {
        if (!item.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§6§l✦ Récompense Mystique ✦"));
        ItemEntity e = new ItemEntity(level, pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, item);
        e.setGlowingTag(true); e.setNoGravity(true); e.setInvulnerable(true); e.setDeltaMovement(0, 0, 0); e.setPickUpDelay(40);
        CompoundTag nbt = new CompoundTag(); e.save(nbt); nbt.putShort("Age", (short) -32768); nbt.putShort("Health", (short) 32767);
        nbt.putBoolean("Invulnerable", true); nbt.putBoolean("PersistenceRequired", true); nbt.putShort("PickupDelay", (short) 40); nbt.putInt("Lifespan", Integer.MAX_VALUE);
        e.load(nbt); e.setCustomName(Component.literal("§6§l✦ Récompense Mystique ✦")); e.setCustomNameVisible(false);
        level.addFreshEntity(e);
    }
}
