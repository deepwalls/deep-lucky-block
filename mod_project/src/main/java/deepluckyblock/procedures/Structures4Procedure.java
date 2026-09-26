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
        if (killed > 0) LOGGER.info("[STRUCT4] {} items laggy au sol nettoyés", killed);
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
                    if (!analysis.isUnsafe()) { LOGGER.info("[STRUCT4-SAFETY] Zone sûre à {} (r={}, {}ms)", candidate.toShortString(), radius, System.currentTimeMillis() - t0); return candidate; }
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
        if (!analysis.isUnsafe()) { LOGGER.info("[STRUCT4-SAFETY] Zone OK"); return targetXZ; }
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
            LOGGER.info("[STRUCT4] '{}' : NBT charge en TACHE DE FOND -- generation retenue quelques ticks, "
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
                LOGGER.info("[STRUCT4] '{}' : NBT pret -- generation de la structure ID={} qui avait ete mise en attente", ds.nbtName(), ds.id());
                generate(ds.level(), ds.origin(), ds.id());
            }
        }
    }
    private static final Queue<EverestJob> EVEREST_QUEUE = new ArrayDeque<>();
    static { PASTE_QUEUE.clear(); CARVE_QUEUE.clear(); POST_QUEUE.clear(); EVEREST_QUEUE.clear(); }

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
        // T73 : ne JAMAIS reposer de l'air sur de l'air (les entrees d'air
        // interieur servent a creuser l'interieur ; si c'est deja vide, rien a faire).
        if (tState.isAir() && job.level.getBlockState(bp).isAir()) return true;
        job.level.setBlock(bp, tState, FAST_FLAG);
        if (tState.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) job.grav++;
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
    private static void retryDeferredEverest(EverestJob job, StructurePlaceSettings rotSettings, BlockPos.MutableBlockPos bp) {
        job.waitTicks++;
        boolean force = job.waitTicks > 600;
        if (force && job.waitTicks == 601) {
            LOGGER.warn("[STRUCT4-EVEREST] everest : {} blocs en attente depuis 30 s -- chargement force des chunks restants", job.defer.size());
        }
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
            LOGGER.info("[STRUCT4-EVEREST] everest : {} bloc(s) reposes apres chargement de la zone ({} restants, {} ticks d'attente)",
                    placed, job.defer.size(), job.waitTicks);
        }
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
            LOGGER.info("[STRUCT4] {} : {} bloc(s) reposes apres chargement de la zone ({} restants, {} ticks d'attente)", job.name, placed, job.defer.size(), job.waitTicks);
        }
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
                PASTE_QUEUE.poll();
                long elapsed = System.currentTimeMillis() - job.startTime;
                LOGGER.info("[STRUCT4] {} finie en {}ms ({} blocs, {} a gravite -- ticks de chute neutralises par DLB-CLAMP)", job.name, elapsed, job.blocks.size(), job.grav);
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
                    LOGGER.info("[STRUCT4] {} : modif terrain desactivee, nettoyage de la vegetation "
                            + "autour de la structure ({} colonnes concernees)", job.name, veg.columns.size());
                } else {
                    CarveJob carve = buildCarveJob(job);
                    CARVE_QUEUE.offer(carve);
                    LOGGER.info("[STRUCT4] carve {} programmée ({} colonnes, {} blocs occupés, bottomY={})", job.name, carve.columns.size(), carve.occupied.size(), carve.bottomY);
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
                    LOGGER.info("[STRUCT4] {} : vegetation nettoyee autour de la structure : {} bloc(s) retires",
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
                LOGGER.info("[STRUCT4] {} terminée : {} blocs supprimés, {} blocs de motte (bottomY={})", cj.name, cj.carved, cj.grounded, cj.bottomY);
                // Transmet le nom pour que le decor pose par decorate() soit
                // thematise (sinon le catalogue GENERIC_SET s'applique par
                // defaut, ce qui reste correct mais moins cible).
                StructureTerrainPrep.setStructureName(cj.name);
                StructureTerrainPrep.decorateFinish(cj.level, cj.min, cj.max);   // T39 : finitions post-pose
                POST_QUEUE.offer(new PostJob(cj.level, cj.min, cj.max, cj.name));
                LOGGER.info("[STRUCT4] decorate + post de {} terminée", cj.name);
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
            if (job.ticksElapsed % PROGRESS_LOG_INTERVAL == 0) {
                int pct = (int) ((job.index * 100L) / Math.max(1, job.blocks.size()));
                LOGGER.info("[STRUCT4-EVEREST] {}% ({}/{})", pct, job.index, job.blocks.size());
            }
            if (job.index >= job.blocks.size() && job.defer.isEmpty()) {
                EVEREST_QUEUE.poll();
                long elapsed = System.currentTimeMillis() - job.startTime;
                LOGGER.info("[STRUCT4-EVEREST] EVEREST TERMINÉ en {}s ({} blocs)", elapsed / 1000.0, job.blocks.size());
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
                LOGGER.info("[STRUCT4] Post-process de {} terminé", pj.name);
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
        LOGGER.info("[STRUCT4] generate() ID={}, pos={}", structureId, origin.toShortString());
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
        LOGGER.info("[STRUCT4] === SPAWN CIRCUS ===");
        if (!templateReadyOrDefer(level, origin, 3, CIRCUS_NBT)) return true;
        return doPaste(level, origin, CIRCUS_NBT, CIRCUS_OFFSET_X, CIRCUS_OFFSET_Y, CIRCUS_OFFSET_Z, CIRCUS_DISTANCE, CIRCUS_NATIVE_FACING, CIRCUS_FOLLOW_SURFACE, CIRCUS_USE_FLAT_AREA, CIRCUS_PRESERVE_INTERIOR, false, true, null);
    }
    public static boolean spawnShipdead(ServerLevel level, BlockPos origin) {
        LOGGER.info("[STRUCT4] === SPAWN SHIPDEAD ===");
        if (!templateReadyOrDefer(level, origin, 4, SHIPDEAD_NBT)) return true;
        return doPaste(level, origin, SHIPDEAD_NBT, SHIPDEAD_OFFSET_X, SHIPDEAD_OFFSET_Y, SHIPDEAD_OFFSET_Z, SHIPDEAD_DISTANCE, SHIPDEAD_NATIVE_FACING, SHIPDEAD_FOLLOW_SURFACE, SHIPDEAD_USE_FLAT_AREA, SHIPDEAD_PRESERVE_INTERIOR, false, true, null);
    }
    public static boolean spawnEverest(ServerLevel level, BlockPos origin) {
        LOGGER.info("[STRUCT4-EVEREST] === SPAWN EVEREST ===");
        if (level == null || origin == null) return false;
        if (!templateReadyOrDefer(level, origin, 5, EVEREST_NBT)) return true;
        long t0 = System.currentTimeMillis();
        StructureTerrainPrep.setStructureName(EVEREST_NBT);
        // FIX (freeze serveur au chargement à froid d'un gros NBT) : cache
        // mod-wide partagé (voir StructureTemplateCache) au lieu d'un appel
        // direct au StructureTemplateManager.
        StructureTemplate template = deepluckyblock.util.StructureTemplateCache.get(level, EVEREST_NBT);
        if (template == null) { LOGGER.error("[STRUCT4-EVEREST] Template vide ou échec chargement"); return false; }
        Vec3i size = template.getSize();
        int totalVolume = size.getX() * size.getY() * size.getZ();
        LOGGER.info("[STRUCT4-EVEREST] Taille : {}x{}x{} = {}", size.getX(), size.getY(), size.getZ(), totalVolume);
        if (totalVolume > MAX_EVEREST_BLOCKS) { LOGGER.error("[STRUCT4-EVEREST] TROP GROS"); return false; }
        Player nearestPlayer = level.getNearestPlayer(origin.getX() + .5, origin.getY() + .5, origin.getZ() + .5, 128, false);
        // ==================================================================
        // T11 : PRE-CHARGEMENT DE LA ZONE DE RECHERCHE (ANTI-GEL MESURE)
        // ==================================================================
        // MESURE (test 20/09, everest a 4000,90,4000, monde neuf, zone jamais
        // generee) : entre « Taille : 174x123x278 » et « -> pos=... », le scan
        // de placement prenait 2 435 ms DANS UN SEUL TICK
        // (« Can't keep up! Running 2387ms or 47 ticks behind »).
        // CAUSE : findFlatArea() mesure tous les candidats sans forcer la
        // generation, puis VERIFIE le meilleur en forcant la generation du
        // chunk (SafeSurface.primeChunkAbs). Sur une zone neuve, cette
        // verification genere un chunk complet sur le thread serveur : c'est
        // exactement le mecanisme du gel de 19,2 s corrige par T9, mais en
        // amont du pipeline (hors de la zone que prepZone pre-chargeait).
        // CORRECTIF : la zone de recherche est pre-chargee par paquets AVANT
        // le scan (aucune generation dans un tick). Le scan retrouve alors des
        // chunks deja en memoire : MEMES choix de placement, plus de gel. La
        // zone reste tenue en memoire pendant tout le pipeline (T9) et est
        // rendue a la fin du post-traitement.
        // Boite = emprise de la structure + 16 blocs de marge, PLAFONNEE a 88
        // blocs de rayon (=> 11x11 = 121 chunks au maximum, l'emprise reelle
        // d'une grosse structure). Mesure du 20/09 : pre-charger le rayon de
        // RECHERCHE complet (±179 blocs = 576 chunks) demandait la generation de
        // 576 chunks neufs pour une structure qui n'en occupe que ~120 : trois
        // fois trop de travail, et le serveur prenait 38 s de retard accumule
        // (« Running 38576ms or 771 ticks behind »). On ne pre-charge donc que
        // ce que la structure va reellement occuper (elle est posee a l'origine
        // demandee dans le cas normal) ; le reste de la zone est etendu par le
        // prepZone()/ChunkKeeper des que la position finale est connue.
        // === T32 : l'everest pre-charge TOUTE son emprise, pas seulement 88 blocs ===
        // Mesure du 22/09 00:01 : apres le T30, le paste de l'everest gelait encore le
        // serveur 4 211 ms, toujours pendant la phase etiquetee « pre-chargement des
        // chunks ». Cause : le plafond de 88 blocs (T11) a ete choisi pour la zone de
        // RECHERCHE des structures generiques, or l'everest mesure 192 x 296 blocs : les
        // chunks au-dela de +/-88 n'etaient pas epingles et se generaient PENDANT la
        // pose (getChunk(..., require=true), bloc par bloc). L'everest pre-charge donc
        // le carre couvrant sa plus grande dimension : 296/2 + 16 = 164 blocs de rayon
        // (plafond volontaire 176, soit ~529 chunks epingles -- tres en dessous du
        // MAX_PINNED de ChunkKeeper).
        // Probe the anchor first; the exact rotated footprint is loaded below.
        final int preRing = 16;
        StructureTerrainPrep.preloadBox(level,
                new BlockPos(origin.getX() - preRing, level.getMinBuildHeight(), origin.getZ() - preRing),
                new BlockPos(origin.getX() + preRing, level.getMaxBuildHeight() - 1, origin.getZ() + preRing),
                () -> {
            BlockPos targetXZ;
            if (EVEREST_DISTANCE > 0) {
                targetXZ = findFarthestLoadedPos(level, origin, EVEREST_DISTANCE, MIN_CHUNKS_EVEREST);
                if (targetXZ == null) { if (nearestPlayer != null) nearestPlayer.sendSystemMessage(Component.literal("§c❌ Everest annulé : pas assez de chunks chargés")); return; }
            } else targetXZ = origin;
            // FIX T7 : EVEREST a ete vu pose a Y=-90 en jeu (baseY=-65 = heightmap non
            // initialisee). La hauteur est desormais lue via SafeSurface (chunk force
            // en FULL + heightmap initialisee si besoin) : Y reel garanti.
            // T29 : l'everest non plus ne doit pas arriver DANS une autre structure.
            // Deplacement borne (6 anneaux de 48 blocs = 288 blocs max) AVANT la
            // lecture de hauteur, pour que l'ancrage suive le site reel.
            targetXZ = deepluckyblock.util.StructureSites.freeOffset(level, targetXZ, 6, 48);
            int baseY = EVEREST_FOLLOW_SURFACE
                    ? deepluckyblock.util.SafeSurface.surfaceY(level, targetXZ.getX(), targetXZ.getZ(), origin.getY())
                    : origin.getY();
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
            final BlockPos everestSpot = targetXZ;
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
            final BlockPos finalPos = new BlockPos(everestSpot.getX() + EVEREST_OFFSET_X,
                    baseY + EVEREST_OFFSET_Y - fMinRelY - EVEREST_SINK_BLOCKS, everestSpot.getZ() + EVEREST_OFFSET_Z);
            final Rotation rotation = computeFacingRotation(finalPos, nearestPlayer, EVEREST_NATIVE_FACING);
            final BlockPos rotatedPos = adjustForRotation(finalPos, template, rotation);
            StructurePlaceSettings footprintSettings = new StructurePlaceSettings().setRotation(rotation);
            int ex0 = Integer.MAX_VALUE, ez0 = Integer.MAX_VALUE;
            int ex1 = Integer.MIN_VALUE, ez1 = Integer.MIN_VALUE;
            for (var block : fEverestBlocks) {
                BlockPos rel = StructureTemplate.calculateRelativePosition(footprintSettings, block.pos());
                ex0 = Math.min(ex0, rel.getX()); ex1 = Math.max(ex1, rel.getX());
                ez0 = Math.min(ez0, rel.getZ()); ez1 = Math.max(ez1, rel.getZ());
            }
            final BlockPos eMin = rotatedPos.offset(ex0, 0, ez0);
            final BlockPos eMax = rotatedPos.offset(ex1, size.getY() - 1, ez1);
            StructureTerrainPrep.preloadTerrainAndLiquids(level,
                    new BlockPos(eMin.getX(), level.getMinBuildHeight(), eMin.getZ()),
                    new BlockPos(eMax.getX(), level.getMaxBuildHeight() - 1, eMax.getZ()),
                    () -> {
            // === T28 : EVEREST ENFONCE DANS LE TERRAIN (consigne du 20/09 : « l'everest
            // etait sencé s'enfoncer dans le terrain ») : on retire EVEREST_SINK_BLOCKS
            // de plus que l'offset de base, pour que la montagne entre dans le relief.
            LOGGER.info("[STRUCT4-EVEREST] enfoncement de {} bloc(s) dans le terrain (T28)", EVEREST_SINK_BLOCKS);
            deepluckyblock.util.DebugLog.setPhase("paste everest (blocs)");
            LOGGER.info("[STRUCT4-EVEREST] Position : {} (minRelY={})", rotatedPos.toShortString(), fMinRelY);
            // T27 : emprise de l'Everest (192x296 au sol, cf. NBT) pour que les autres
            // structures ne viennent pas s'y poser.
            deepluckyblock.util.StructureSites.register(level, EVEREST_NBT,
                    rotatedPos.getX(), rotatedPos.getZ(), rotatedPos.getX() + 192, rotatedPos.getZ() + 296);
            LOGGER.info("[STRUCT4-EVEREST] Filtrage : {} solides, air skip={}, verre skip={}", fSolid, fAirSkip, fGlassSkip);
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
            StructureTerrainPrep.sweepFloatingNaturalPass(level, min, max, () ->   // T41
            StructureTerrainPrep.sealUndergroundGaps(level, min, max, () ->   // T40
            StructureTerrainPrep.finalTerrainPass(level, min, max, 6, () ->
                StructureTerrainPrep.fixLiquidsPass(level, min, max, () -> {   // T38
                    // T43 : re-scellement de l'emprise juste avant la pose (les passes
                    // de terrain ont travaille plusieurs dizaines de secondes depuis le
                    // pre-chargement T37 : sans ca, la pose reclamait des chunks).
                    StructureTerrainPrep.preloadBox(level, min, max, () -> {
                    EVEREST_QUEUE.offer(new EverestJob(level, fEverestBlocks, rotatedPos, rotation, Mirror.NONE, t0, nearestPlayer, onComplete, cherryHolder));
                    POST_QUEUE.offer(new PostJob(level, min, max, "everest", false));
                    LOGGER.info("[STRUCT4-EVEREST] terrain fige (passe finale + fixwater fait AVANT le paste, T39)");
                    if (nearestPlayer != null) nearestPlayer.sendSystemMessage(Component.literal("§b§l⛰ Everest en construction"));
                    });
                }))));
                    });   // T30 : fin du pre-chargement de l'emprise finale
            return;
                });
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
        Villager manish = new Villager(EntityType.VILLAGER, level);
        manish.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        manish.setVillagerData(new VillagerData(VillagerType.SNOW, VillagerProfession.NITWIT, 5));
        manish.setCustomName(Component.literal("§b§lManish")); manish.setCustomNameVisible(true);
        manish.setPersistenceRequired(); manish.setInvulnerable(true); manish.setNoAi(true);
        RandomSource random = level.getRandom();
        for (int i = 0; i < 7; i++) {
            int luck = 15 + random.nextInt(86);
            ItemStack reward;
            try { reward = GenerateLuckyItemProcedure.generate(level, luck, TestProcedure.QualityTier.getQualityTier(luck), random); if (reward == null || reward.isEmpty()) reward = new ItemStack(Items.GOLDEN_APPLE); }
            catch (Exception ex) { reward = new ItemStack(Items.GOLDEN_APPLE); }
            ItemStack cost;
            if (random.nextInt(100) < 10) {
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
            if (random.nextInt(100) < 30) {
                int baseCount = cost.getCount();
                int rebate = Math.max(1, Math.round(baseCount * (0.15f + random.nextFloat() * 0.25f)));
                // Jamais en dessous de 1 item de cout apres remise.
                rebate = Math.min(rebate, baseCount - 1);
                if (rebate > 0) offer.setSpecialPriceDiff(-rebate);
            }
            manish.getOffers().add(offer);
        }
        level.addFreshEntity(manish);
        LOGGER.info("[STRUCT4] Manish spawn à {}", pos.toShortString());
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
        for (int[] off : offsets) { int cx = origin.getX() + off[0], cz = origin.getZ() + off[1];
            // FIX T7 : hauteur lue sur un chunk dont la heightmap est initialisee (voir SafeSurface).
            int cy = deepluckyblock.util.SafeSurface.surfaceY(level, cx, cz, origin.getY());
            BlockPos cand = new BlockPos(cx, cy, cz); if (!isPlayerChunk(cand, null, origin)) return cand; }
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
                int diff = measureFlatnessZone(level, cx, cz, checkR);
                if (diff < 0) continue;
                if (diff < bestDiff) { bestDiff = diff; bdX = cx; bdZ = cz; }
                if (diff <= maxDiff) {
                    int score = scoreCandidate(cx, cz, playerPos, lookX, lookZ);
                    goodCandidates.add(new FlatAreaCandidate(cx, cz, diff, score));
                }
            }
        }
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
            LOGGER.info("[STRUCT4-FLAT] Verification candidat #{}/{} a {},{} (force generation de la zone, checkR={})...",
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
                LOGGER.info("[STRUCT4-FLAT] Candidat #{}/{} a {},{} : zone pas encore en memoire -- verification differee (tache de fond, aucun blocage)",
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
            int surfY2 = deepluckyblock.util.SafeSurface.surfaceY(level, c.cx(), c.cz(), origin.getY());
            LOGGER.info("[STRUCT4-FLAT] Zone choisie a {},{} (seconde passe T49, terrain reel verifie, Y reel={})", c.cx(), c.cz(), surfY2);
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
        while (attempts < 60) { double a = r.nextDouble() * Math.PI * 2; int dx = (int) Math.round(Math.cos(a) * dist), dz = (int) Math.round(Math.sin(a) * dist); int cx = origin.getX() + dx, cz = origin.getZ() + dz; int cy = deepluckyblock.util.SafeSurface.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 1; BlockPos cand = new BlockPos(cx, cy, cz); if (isChunkAreaLoaded(level, cand, minChunksReq, pl)) { int dSq = dx * dx + dz * dz; if (dSq > bestDistSq) { bestDistSq = dSq; best = cand; } } attempts++; }
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
        LOGGER.info("[STRUCT4] === PASTE {} === off=[{},{},{}] dist={} facing={} followSurf={} flatArea={} presInt={}", nbtName, offX, offY, offZ, distance, nativeFacing, followSurface, useFlatArea, preserveInterior);
        // FIX (freeze serveur ~78s au premier spawn de shipdead) : on passe
        // par le cache mod-wide (StructureTemplateCache) au lieu d'appeler
        // directement mgr.get()/.getOrCreate() -- si un précédent paste (ou le
        // préchargement au démarrage serveur) a déjà chargé ce NBT, on évite
        // toute nouvelle désérialisation synchrone couteuse.
        StructureTemplate template = deepluckyblock.util.StructureTemplateCache.get(level, nbtName);
        if (template == null) { LOGGER.error("[STRUCT4] Template vide ou échec chargement pour {}", nbtName); return false; }
        Vec3i size = template.getSize();
        LOGGER.info("[STRUCT4] Taille : {}x{}x{}", size.getX(), size.getY(), size.getZ());
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
        int baseY = followSurface
                ? deepluckyblock.util.SafeSurface.surfaceY(level, targetXZ.getX(), targetXZ.getZ(), origin.getY())
                : origin.getY();
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
            if (state.isAir()) { if (!isEverest && preserveInterior) { BlockPos p = info.pos(); if (intAir.contains(p.asLong())) { filtered.add(info); airKept++; } else airSkipped++; } else airSkipped++; }
            else { filtered.add(info); solidCount++; }
        }
        // minRelY sur la liste FILTREE (hors verre) pour ne pas fausser le placement.
        int minRelY = 0;
        if (!filtered.isEmpty()) { minRelY = Integer.MAX_VALUE; for (var b : filtered) { int by = b.pos().getY(); if (by < minRelY) minRelY = by; } }
        BlockPos finalPos = new BlockPos(targetXZ.getX() + offX, baseY + offY - minRelY, targetXZ.getZ() + offZ);
        Rotation rotation = computeFacingRotation(finalPos, nearestPlayer, nativeFacing);
        BlockPos rotatedPos = adjustForRotation(finalPos, template, rotation);
        LOGGER.info("[STRUCT4] {} -> pos={} (baseY={}, minRelY={}, rot={}, {}ms)", nbtName, rotatedPos.toShortString(), baseY, minRelY, rotation, System.currentTimeMillis() - tGlobal);
        LOGGER.info("[STRUCT4] {} -> {} solides + {} air int = {} total (skip {} air ext, {} laggy, {} verre)", nbtName, solidCount, airKept, filtered.size(), airSkipped, laggySkipped, glassSkipped);
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
        // T14 : groupe par section 16x16x16 + spirale (guide sections 42/43) au
        // lieu du tri « layer par layer ».
        filtered = orderBySectionSpiral(filtered, bboxRs, rotatedPos);
        final BlockPos selectedSite = targetXZ;
        final List<StructureTemplate.StructureBlockInfo> fFiltered = filtered;
        Runnable offerPaste = () -> {
            // Read the real ground after preload, not the fallback used on a cold chunk.
            int dy = skipTerrain && followSurface
                    ? deepluckyblock.util.SafeSurface.groundY(level, selectedSite.getX(), selectedSite.getZ(), baseY) - baseY
                    : 0;
            BlockPos pasteOrigin = rotatedPos.offset(0, dy, 0);
            Runnable onComplete = () -> { if (callback != null) callback.onComplete(pasteOrigin, level, nearestPlayer); };
            PASTE_QUEUE.offer(new PasteJob(level, fFiltered, pasteOrigin, rotation, Mirror.NONE,
                    nbtName, tGlobal, nearestPlayer, onComplete, min.offset(0, dy, 0), max.offset(0, dy, 0), skipTerrain));
        };
        // T27 : emprise enregistree des maintenant (voir StructureSites).
        deepluckyblock.util.StructureSites.register(level, nbtName, min.getX(), min.getZ(), max.getX(), max.getZ());
        if (skipTerrain) {
            LOGGER.info("[STRUCT4] {} : modif terrain DESACTIVEE (paste brut, ni prep ni carve)", nbtName);
            // Establish and retain the final footprint before any asynchronous placement.
            StructureTerrainPrep.preloadBox(level, min, max, offerPaste);
        } else {
            // T39 : le paste n'est offert qu'a la fin de la phase TERRAIN.
            StructureTerrainPrep.prepZone(level, min, max, baseY + offY,
                    () -> StructureTerrainPrep.decorateTerrainOnly(level, min, max, offerPaste));
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
