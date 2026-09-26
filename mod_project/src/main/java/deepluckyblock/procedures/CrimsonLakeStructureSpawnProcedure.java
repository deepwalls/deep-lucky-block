package deepluckyblock.procedures;

import deepluckyblock.advancements.DeepLuckyBlockAdvancements;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import deepluckyblock.entity.CrimsonButterflyEntity;
import deepluckyblock.init.DeepLuckyBlockModEntities;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = "deep_lucky_block")
public class CrimsonLakeStructureSpawnProcedure {

    // ACTIVE/DESACTIVE les logs de debug [SLB-DEBUG]. Mettre a false avant publication.
    private static final boolean DEBUG_LOGS = false;

    private static final int BLOCKS_PER_TICK        = 25000;
    /**
     * T48 : budget de temps de la pose par tick (la structure du lac fait
     * plusieurs millions de blocs : laisser la boucle tourner jusqu'a
     * BLOCKS_PER_TICK sans regarder l'horloge tenait le tick trop longtemps).
     */
    private static final long PASTE_TICK_BUDGET_MS  = 40L;
    private static final int FAST_FLAG              = 2 | 16 | 32;
    private static final int SINK_BLOCKS = 10;
    private static final long BUILD_DELAY_TICKS     = 400;
    private static final long BUILD_START_DELAY_TICKS = 200;
    private static final int BUTTERFLY_DELAY_TICKS  = 600;
    private static final int BUTTERFLY_INTERVAL     = 40;
    private static final int BUTTERFLY_MIN          = 150;
    private static final int BUTTERFLY_MAX          = 400;
    private static final int INITIAL_BUTTERFLIES    = 20;
    private static final int INITIAL_BFLY_SPACING   = 60; // 1 papillon toutes les 60 ticks (3s) pendant les 60s

    @SuppressWarnings("removal")
    private static final ResourceLocation TEMPLATE_ID  = ResourceLocation.fromNamespaceAndPath("deep_lucky_block", "crimsonlake");

    /**
     * T50 : NBT reellement utilise. Par defaut « crimsonlake ».
     *
     * <p>La propriete systeme {@code -Ddlb.test.lakeTemplate=<nbt>} permet de
     * rejouer TOUT le pipeline du lac (pre-chauffage, prepZone, ordre T39,
     * blocs differes, finitions) avec une petite structure : indispensable dans
     * un environnement de test ou le lac (3,46 Mo, ~5,9 M blocs) ne tient pas en
     * memoire. Le comportement de production est inchange.
     */
    private static final String NBT_NAME = System.getProperty("dlb.test.lakeTemplate", "crimsonlake");

    private static final Set<Long> TRIGGERED = ConcurrentHashMap.newKeySet();
    private static final List<PendingBuild> PENDING = new ArrayList<>();
    private static final Map<Long, SpawnTask> ACTIVE = new ConcurrentHashMap<>();
    private static final Queue<PasteJob> PASTE_Q = new ArrayDeque<>();
    private static final Random RNG = new Random();

    private static volatile MinecraftServer theServer;
    private static volatile long loginTick = Long.MIN_VALUE;
    private static int tickCounter = 0;

    private static boolean buildAllowed() {
        if (theServer == null || loginTick == Long.MIN_VALUE) return false;
        return theServer.getTickCount() - loginTick >= BUILD_DELAY_TICKS;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("crimsonlake")
                        .executes(ctx -> { handleCommand(ctx.getSource().getPlayerOrException()); return 1; })
                        .then(Commands.literal("reset")
                                .executes(ctx -> { handleReset(ctx.getSource().getPlayerOrException()); return 1; }))
        );
    }

    /**
     * T50 : rejoue exactement l'evenement (meme anneau, meme position, meme
     * pipeline) pour la commande de test {@code /dlbtest crimsonlake}. Le lac
     * n'etait declenchable que par l'evenement aleatoire (1/500 par minute) ou
     * par /crimsonlake en jeu : impossible a tester en console, et impossible a
     * rejouer rapidement apres une correction.
     */
    public static boolean triggerFromTest(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null) return false;
        try {
            // theServer n'est renseigne que par la connexion d'un joueur : sans
            // ca, buildAllowed() resterait faux et le tick handler ne ferait
            // rien du tout (le pipeline ne demarrerait jamais en console).
            theServer = level.getServer();
            loginTick = theServer.getTickCount() - BUILD_DELAY_TICKS;
            TRIGGERED.clear();
            PENDING.clear();
        } catch (Throwable ignored) { }
        triggerAt(level, player, positionInFront(level, player), "dlbtest crimsonlake");
        return true;
    }

    private static void handleCommand(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        triggerAt(level, player, positionInFront(level, player), "commande /crimsonlake");
    }

    private static void handleReset(ServerPlayer player) {
        ServerLevel overworld = player.getServer().overworld();
        try {
            stateOf(overworld).clear();
            TRIGGERED.clear();
            PENDING.clear();
            player.sendSystemMessage(Component.literal("Crimson Lake state reset -> can trigger again (testing).")
                    .withStyle(ChatFormatting.YELLOW));
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("Reset failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    private static BlockPos positionInFront(ServerLevel level, ServerPlayer player) {
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double yaw = Math.toRadians(player.getYRot());
        double fdx = -Math.sin(yaw), fdz = Math.cos(yaw);
        int sx = 243, sz = 292;
        // FIX T7 : cet appel ne doit JAMAIS declencher le parse du NBT de
        // crimsonlake (3,46 Mo -> ~1,3 s de freeze) : il ne sert qu'a estimer la
        // taille du template pour positionner le centre. Tant que le fichier
        // n'est pas en memoire, on utilise les dimensions par defaut (mesurees
        // une fois pour toutes, elles ne dependent pas du monde).
        if (deepluckyblock.util.StructureTemplateCache.isCached(NBT_NAME)) {
            var tmpl = deepluckyblock.util.StructureTemplateCache.get(level, NBT_NAME);
            if (tmpl != null) { sx = tmpl.getSize().getX(); sz = tmpl.getSize().getZ(); }
        } else {
            deepluckyblock.util.StructureTemplateCache.requestAsync(level, NBT_NAME);
        }
        double halfDepth = Math.abs(fdx) * (sx / 2.0) + Math.abs(fdz) * (sz / 2.0);
        int ox = (int) Math.round(px + fdx * halfDepth);
        int oz = (int) Math.round(pz + fdz * halfDepth);
        return new BlockPos(ox, (int) py, oz);
    }

    private static void triggerAt(ServerLevel level, ServerPlayer player, BlockPos pos, String source) {
        ServerLevel overworld = level.getServer().overworld();
        if (isWorldTriggered(overworld)) return;
        long key = pos.asLong();
        if (!TRIGGERED.add(key)) return;
        markWorldTriggered(overworld);
        if (DEBUG_LOGS) System.out.println("[SLB-DEBUG] CrimsonLake TRIGGER from " + source);

        // 🏆 Achievement : le Crimson Lake (événement ultra rare) a été trouvé.
        if (player != null) {
            DeepLuckyBlockAdvancements.grantCrimsonLake(player);
        }

        // Papillons initiaux : spawns progressifs (1 toutes les 3s) pendant les 60s d'attente.
        BlockPos center = player.blockPosition();
        int serverTick = level.getServer().getTickCount();
        for (int b = 0; b < INITIAL_BUTTERFLIES; b++) {
            final int tickOffset = b * INITIAL_BFLY_SPACING;
            TestProcedure.schedule(level, serverTick + tickOffset, () -> {
                spawnInitialButterflies(level, center, 1);
            });
        }

        long startTick = level.getServer().getTickCount() + BUILD_START_DELAY_TICKS;
        PENDING.add(new PendingBuild(level, pos.getX(), pos.getY(), pos.getZ(), startTick));
        // === T46 : PRE-CHAUFFAGE DES CHUNKS PENDANT LES 60 s D'ANNONCE ===
        // Mesure en jeu du 23/09 : le pre-chargement du lac (406x444 = 783 chunks,
        // monde neuf) a tourne 24 minutes et n'a jamais fini -- lancé APRES les
        // 60 s d'annonce, il les passait a attendre des chunks que personne ne
        // demandait encore. On demande maintenant la zone DES L'ANNONCE : les
        // chunks vierges se generent en parallele pendant que le joueur recule,
        // et le pipeline demarre sur une zone deja prete.
        {
            int wsx = 243, wsz = 292;   // dimensions mesurees du lac (NBT 3,46 Mo)
            try {
                if (deepluckyblock.util.StructureTemplateCache.isCached(NBT_NAME)) {
                    var t = deepluckyblock.util.StructureTemplateCache.get(level, NBT_NAME);
                    if (t != null) { wsx = t.getSize().getX(); wsz = t.getSize().getZ(); }
                }
            } catch (Throwable ignored) { }
            BlockPos wMin = new BlockPos(pos.getX() - wsx / 2, pos.getY(), pos.getZ() - wsz / 2);
            BlockPos wMax = wMin.offset(wsx - 1, 1, wsz - 1);
            StructureTerrainPrep.warmZone(level, wMin, wMax, 80);
        }
        // FIX T7 : les 60 s d'annonce servent de fenetre de chargement pour le
        // NBT (3,46 Mo, ~1,3 s de parse) : il est pret bien avant la pose, donc
        // la construction ne bloque plus le thread serveur.
        deepluckyblock.util.StructureTemplateCache.requestAsync(level, NBT_NAME);

        Component msg = Component.literal(
                "Congratulations! You found the secret event: Crimson Lake. Please step back! (building starts in 10s)")
                .withStyle(ChatFormatting.GOLD);
        for (var p : level.players()) p.sendSystemMessage(msg);
    }

    private static int diceTick;
    private static final int DICE_SIDES = 500;
    private static final int DICE_HIT = 99;

    private static void rollDice() {
        if (theServer == null) return;
        if (isWorldTriggered(theServer.overworld())) return;
        int roll = RNG.nextInt(DICE_SIDES);
        if (roll != DICE_HIT) return;
        for (ServerLevel sl : theServer.getAllLevels()) {
            for (ServerPlayer p : sl.players()) {
                triggerAt(sl, p, positionInFront(sl, p), "dice " + DICE_HIT + "/" + DICE_SIDES);
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        theServer = event.getEntity().getServer();
        loginTick = theServer != null ? theServer.getTickCount() : Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        loginTick = Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!buildAllowed()) return;

        if (diceTick++ % 1200 == 0 && PASTE_Q.isEmpty() && PENDING.isEmpty()) {
            rollDice();
        }

        if (PASTE_Q.isEmpty() && PENDING.isEmpty() && ACTIVE.isEmpty()) return;

        tickCounter++;
        if (DEBUG_LOGS && (tickCounter % 200 == 0 || !PASTE_Q.isEmpty()))
            System.out.println("[SLB-DEBUG] CrimsonLake TICK: PASTE=" + PASTE_Q.size() + " PENDING=" + PENDING.size() + " ACTIVE=" + ACTIVE.size());

        long now = theServer.getTickCount();

        if (!PENDING.isEmpty()) {
            Iterator<PendingBuild> it = PENDING.iterator();
            while (it.hasNext()) {
                PendingBuild pb = it.next();
                if (now < pb.startTick || System.nanoTime() - pb.queuedNanos < 10_000_000_000L) continue;
                // FIX T7 : le NBT de crimsonlake ne doit JAMAIS etre parse sur le
                // thread serveur (3,46 Mo -> ~1,3 s de freeze a la premiere
                // apparition). Tant qu'il n'est pas en memoire, la construction
                // reste en attente : on a lance le chargement de fond et on
                // repasse dans 20 ticks. Aucun bloc de structure n'est perdu.
                if (!deepluckyblock.util.StructureTemplateCache.isCached(NBT_NAME)
                        && !deepluckyblock.util.StructureTemplateCache.hasFailed(NBT_NAME)) {
                    deepluckyblock.util.StructureTemplateCache.requestAsync(pb.level, NBT_NAME);
                    pb.startTick = now + 20;
                    if (pb.startTick % 400L < 20L)
                        System.out.println("[SLB-DEBUG] CrimsonLake : NBT charge en tache de fond, construction reportee (le serveur n'est pas bloque)");
                    continue;
                }
                queueBuild(pb.level, pb.px, pb.py, pb.pz);
                it.remove();
            }
        }

        if (!PASTE_Q.isEmpty()) {
            PasteJob j = PASTE_Q.peek();
            // T48 : la zone reste TENUE pendant la pose (aucune generation synchrone).
            deepluckyblock.util.ChunkKeeper.keep(j.level);
            StructurePlaceSettings rs = new StructurePlaceSettings();
            BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
            long pasteT0 = System.currentTimeMillis();
            if (j.idx < j.blocks.size()) {
                int end = Math.min(j.idx + BLOCKS_PER_TICK, j.blocks.size());
                for (int i = j.idx; i < end; i++) {
                    if ((i & 127) == 0 && System.currentTimeMillis() - pasteT0 > PASTE_TICK_BUDGET_MS) { end = i; break; }
                    // T48 : chunk absent -> DIFFERE (demande async), jamais de
                    // generation synchrone sur le thread serveur.
                    if (!placeCrimsonOne(j, i, rs, bp)) j.defer.add(i);
                }
                j.idx = end;
            } else if (!j.defer.isEmpty()) {
                // Passe de rattrapage : les blocs differes (chunk pas encore en
                // memoire) sont reposes ici, sans jamais bloquer.
                java.util.Iterator<Integer> it = j.defer.iterator();
                while (it.hasNext()) {
                    if (System.currentTimeMillis() - pasteT0 > PASTE_TICK_BUDGET_MS) break;
                    if (placeCrimsonOne(j, it.next(), rs, bp)) it.remove();
                }
            }
            if (j.idx >= j.blocks.size() && !j.defer.isEmpty()) {
                j.waitTicks++;
                if (j.waitTicks % 100 == 0) {
                    System.out.println("[SLB-DEBUG] CrimsonLake PASTE : " + j.defer.size()
                            + " bloc(s) en attente de chunk (tache de fond, aucun blocage)");
                }
                return;
            }
            if (j.idx >= j.blocks.size()) {
                PASTE_Q.poll();
                if (DEBUG_LOGS) System.out.println("[SLB-DEBUG] CrimsonLake PASTE COMPLETE");

                // Passe de finition APRES le paste, comme les autres structures :
                // naturalize (vegetation contre les murs), cleanup de la
                // vegetation flottante, gestion de l'eau rouverte par le paste,
                // decor thematique, puis replant des arbres sauves par prepZone.
                // Sans cela CrimsonLake restait pose sur un terrain nu et non
                // raccorde -- c'est la seconde moitie du passage en v2.
                int jsy = j.blocks.isEmpty() ? 0 : j.sz; // borne Z (sx/sz connus du job)
                BlockPos cMin = j.origin;
                BlockPos cMax = j.origin.offset(j.sx - 1, 0, jsy - 1);
                StructureTerrainPrep.setStructureName(NBT_NAME);
                // T48 : FINITIONS SEULES (decors, vegetation, naturalisation).
                // Plus aucune passe de terrain ne touche la structure posee (T39).
                StructureTerrainPrep.decorateFinish(j.level, cMin, cMax);

                int cap = BUTTERFLY_MIN + RNG.nextInt(BUTTERFLY_MAX - BUTTERFLY_MIN + 1);
                long bflyStart = j.level.getServer().getTickCount() + BUTTERFLY_DELAY_TICKS;
                ACTIVE.put(System.nanoTime(), new SpawnTask(j.level, j.bfly, bflyStart, cap, j.sx, j.sz));
            }
        }

        if (!ACTIVE.isEmpty()) {
            Iterator<Map.Entry<Long, SpawnTask>> it = ACTIVE.entrySet().iterator();
            while (it.hasNext()) {
                SpawnTask task = it.next().getValue();
                long t = task.level.getServer().getTickCount();
                if (t < task.nextTick) continue;
                if (task.spawned >= task.cap) { it.remove(); continue; }
                spawnPack(task);
                task.nextTick = t + BUTTERFLY_INTERVAL;
                if (task.spawned >= task.cap) it.remove();
            }
        }
    }

    private static void spawnInitialButterflies(ServerLevel level, BlockPos center, int count) {
        for (int i = 0; i < count; i++) {
            double angle = RNG.nextDouble() * Math.PI * 2.0;
            double dist = 4.0 + RNG.nextDouble() * 16.0;
            int x = center.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * dist);
            // FIX papillons invisibles (rapport en jeu) : chercher un sol PRES
            // du point de déclenchement (center.getY()), pas la surface absolue
            // du monde. Si le joueur trouve l'event en sous-sol/grotte (ex.
            // y=-60), l'ancien findSurfaceY(level,x,z) scannait depuis le
            // TOIT du monde et renvoyait la vraie surface extérieure (souvent
            // y=70-100+), à 100+ blocs au-dessus/à l'écart du joueur : les
            // papillons apparaissaient bien, mais totalement hors de vue.
            int sy = findSurfaceYNear(level, x, z, center.getY());
            if (sy < 0) sy = center.getY();
            CrimsonButterflyEntity b = new CrimsonButterflyEntity(DeepLuckyBlockModEntities.CRIMSON_BUTTERFLY.get(), level);
            b.moveTo(x + 0.5, sy + 10.0, z + 0.5, RNG.nextFloat() * 360F, 0F);
            level.addFreshEntity(b);
        }
    }

    private static void spawnPack(SpawnTask task) {
        ServerLevel level = task.level;
        int cx = task.center.getX(), cz = task.center.getZ();
        int halfX = task.sx / 2 + 50;
        int halfZ = task.sz / 2 + 50;
        int pack = Math.min(3 + RNG.nextInt(4), task.cap - task.spawned);
        for (int i = 0; i < pack; i++) {
            double ux = RNG.nextDouble() * 2 - 1;
            double bx = Math.signum(ux) * ux * ux;
            double uz = RNG.nextDouble() * 2 - 1;
            double bz = Math.signum(uz) * uz * uz;
            int x = cx + (int) Math.round(bx * halfX);
            int z = cz + (int) Math.round(bz * halfZ);
            // FIX papillons invisibles : idem spawnInitialButterflies, on
            // cherche un sol proche de l'altitude du déclenchement, pas la
            // surface absolue du monde.
            int sy = findSurfaceYNear(level, x, z, task.center.getY());
            if (sy < 0) sy = task.center.getY();
            CrimsonButterflyEntity b = new CrimsonButterflyEntity(DeepLuckyBlockModEntities.CRIMSON_BUTTERFLY.get(), level);
            b.moveTo(x + 0.5, sy + 10.0, z + 0.5, RNG.nextFloat() * 360F, 0F);
            level.addFreshEntity(b);
        }
        task.spawned += pack;
    }

    /**
     * FIX papillons invisibles : cherche le sol le plus proche de refY (position
     * du joueur au moment du déclenchement de l'event), en scannant d'abord
     * VERS LE HAUT puis VERS LE BAS depuis refY, au lieu de toujours partir du
     * plafond du monde (qui renvoie la surface extérieure même si l'event a
     * été déclenché en sous-sol/grotte/mine, faisant apparaître les papillons
     * à 100+ blocs du joueur, donc invisibles).
     */
    private static int findSurfaceYNear(ServerLevel level, int x, int z, int refY) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int range = 40; // suffisant pour sortir d'une grotte/mine sans remonter jusqu'à la surface
        for (int dy = 0; dy <= range; dy++) {
            int yUp = refY + dy;
            if (yUp <= maxY && yUp > minY) {
                BlockState above = level.getBlockState(new BlockPos(x, yUp, z));
                BlockState below = level.getBlockState(new BlockPos(x, yUp - 1, z));
                if (above.isAir() && above.getFluidState().isEmpty()
                        && !below.isAir() && below.getFluidState().isEmpty()) return yUp;
            }
            if (dy == 0) continue;
            int yDown = refY - dy;
            if (yDown <= maxY && yDown > minY) {
                BlockState above = level.getBlockState(new BlockPos(x, yDown, z));
                BlockState below = level.getBlockState(new BlockPos(x, yDown - 1, z));
                if (above.isAir() && above.getFluidState().isEmpty()
                        && !below.isAir() && below.getFluidState().isEmpty()) return yDown;
            }
        }
        // Repli : ancien comportement (surface absolue du monde) si rien de
        // praticable n'a été trouvé près du joueur.
        return findSurfaceY(level, x, z);
    }

    private static int findSurfaceY(ServerLevel level, int x, int z) {
        for (int y = level.getMaxBuildHeight() - 1; y > level.getMinBuildHeight(); y--) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (!s.isAir() && s.getFluidState().isEmpty()) return y + 1;
        }
        return -1;
    }

    private static void queueBuild(ServerLevel level, int px, int py, int pz) {
        if (DEBUG_LOGS) System.out.println("[SLB-DEBUG] CrimsonLake QUEUE BUILD at " + px + "," + py + "," + pz);
        // FIX (freeze serveur au chargement à froid d'un gros NBT) : cache
        // mod-wide partagé (StructureTemplateCache) au lieu d'un appel direct
        // au StructureTemplateManager.
        StructureTemplate tmpl = deepluckyblock.util.StructureTemplateCache.get(level, NBT_NAME);
        if (tmpl == null) return;
        List<StructureTemplate.StructureBlockInfo> raw = exBlocks(tmpl);
        if (raw.isEmpty()) return;

        int sx = tmpl.getSize().getX();
        int sy = tmpl.getSize().getY();
        int sz = tmpl.getSize().getZ();

        BlockPos bfly = new BlockPos(px, py + 10, pz);

        List<StructureTemplate.StructureBlockInfo> filt = new ArrayList<>(raw.size());
        for (var info : raw) if (!info.state().isAir() && !isLaggy(info.state())) filt.add(info);
        if (filt.isEmpty()) return;
        // The volume is cleared explicitly, so template air needs no writes.
        filt = orderByY(filt);
        final int minRelativeY = filt.stream().mapToInt(b -> b.pos().getY()).min().orElse(0);

        Component msg2 = Component.literal(
                "Crimson Lake: loading surrounding chunks and building layer by layer...")
                .withStyle(ChatFormatting.AQUA);
        for (var pl : level.players()) pl.sendSystemMessage(msg2);

        final List<StructureTemplate.StructureBlockInfo> fFilt = filt;
        BlockPos probeMin = new BlockPos(px - sx / 2, py, pz - sz / 2);
        BlockPos probeMax = probeMin.offset(sx - 1, sy - 1, sz - 1);
        StructureTerrainPrep.setTerrainRingScalePercent(100);
        StructureTerrainPrep.setStructureName(NBT_NAME);
        // Read the actual ground only after loading; the player's altitude is not terrain.
        StructureTerrainPrep.preloadBox(level, probeMin, probeMax, () -> {
            if (!deepluckyblock.util.ChunkKeeper.zoneLoaded(level, probeMin, probeMax)) {
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1,
                        () -> queueBuild(level, px, py, pz));
                return;
            }
            int groundY = deepluckyblock.util.SafeSurface.groundY(level, px, pz, py - 1);
            BlockPos origin = lakeOrigin(px, pz, groundY, minRelativeY, sx, sz);
            BlockPos min = origin;
            BlockPos max = origin.offset(sx - 1, sy - 1, sz - 1);
            if (origin.getY() + minRelativeY <= level.getMinBuildHeight()
                    || max.getY() >= level.getMaxBuildHeight()) {
                System.err.println("[DLB-LAKE] ABORT: template outside build height; no blocks placed");
                deepluckyblock.util.ChunkKeeper.release(level);
                Structures5Procedure.notifyGenerationFinished(level);
                return;
            }
            System.out.println("[DLB-LAKE] anchor: ground=" + groundY + ", lowest block="
                    + (origin.getY() + minRelativeY) + ", sink=" + SINK_BLOCKS);
            // All shaping precedes clearing. Nothing may refill the interior before paste.
            StructureTerrainPrep.prepZone(level, min, max, origin.getY() + minRelativeY, () ->
                    StructureTerrainPrep.decorateTerrainOnly(level, min, max, () ->
                            clearBuildVolume(level, origin.offset(0, minRelativeY, 0), max, () ->
                                    PASTE_Q.offer(new PasteJob(level, fFilt, origin, bfly,
                                            System.currentTimeMillis(), sx, sz)))));
        });
    }

    static BlockPos lakeOrigin(int x, int z, int groundY, int minRelativeY, int sx, int sz) {
        // Reference is the first free block above natural ground, not template padding.
        return new BlockPos(x - sx / 2, groundY + 1 - SINK_BLOCKS - minRelativeY, z - sz / 2);
    }

    /** Clear the footprint up to open sky without deleting whole chunks or their metadata. */
    static void clearBuildVolume(ServerLevel level, BlockPos min, BlockPos max, Runnable onReady) {
        new LakeClearJob(level, min, max, onReady).step();
    }

    private static final class LakeClearJob {
        final ServerLevel level;
        final BlockPos min, max;
        final Runnable onReady;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x, z, y, top;
        boolean columnStarted;
        long cleared;

        LakeClearJob(ServerLevel level, BlockPos min, BlockPos max, Runnable onReady) {
            this.level = level; this.min = min; this.max = max; this.onReady = onReady;
            x = min.getX(); z = min.getZ();
        }

        void step() {
            deepluckyblock.util.ChunkKeeper.keep(level);
            long deadline = System.nanoTime() + 20_000_000L;
            while (x <= max.getX() && System.nanoTime() < deadline) {
                var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    deepluckyblock.util.SafeSurface.reRequest(level, x >> 4, z >> 4);
                    break;
                }
                if (!columnStarted) {
                    y = Math.max(min.getY(), level.getMinBuildHeight() + 1);
                    top = Math.min(level.getMaxBuildHeight() - 1,
                            chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x & 15, z & 15));
                    columnStarted = true;
                }
                while (y <= top && System.nanoTime() < deadline) {
                    // Entire empty vertical sections need neither reads nor writes.
                    if (chunk.getSection(chunk.getSectionIndex(y)).hasOnlyAir()) {
                        y = ((y >> 4) + 1) << 4;
                        continue;
                    }
                    BlockState state = chunk.getBlockState(pos.set(x, y++, z));
                    if (!state.isAir() && !state.is(Blocks.BEDROCK)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                        cleared++;
                    }
                }
                if (y > top) {
                    columnStarted = false;
                    if (++z > max.getZ()) { z = min.getZ(); x++; }
                }
            }
            if (x <= max.getX()) {
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::step);
            } else {
                System.out.println("[DLB-LAKE] CLEAR COMPLETE: " + cleared
                        + " blocks removed to open sky; paste may start");
                if (onReady != null) onReady.run();
            }
        }
    }

    /**
     * T48 : pose UN bloc du lac. Ne bloque JAMAIS : si le chunk n'est pas en
     * memoire, on rend false et l'appelant differe le bloc (la demande de
     * chargement part en tache de fond).
     *
     * <p>AVANT : `getChunkSource().getChunk(cx, cz, true)` pour CHAQUE bloc --
     * generation FULL synchrone, ~15 s par chunk mesure en jeu, « Can't keep up!
     * Running 26381 ms ». C'etait la plus grosse source de freeze du mod.
     */
    private static boolean placeCrimsonOne(PasteJob j, int i, StructurePlaceSettings rs, BlockPos.MutableBlockPos bp) {
        var info = j.blocks.get(i);
        BlockPos rel = StructureTemplate.calculateRelativePosition(rs, info.pos());
        bp.set(j.origin.getX() + rel.getX(), j.origin.getY() + rel.getY(), j.origin.getZ() + rel.getZ());
        // T53 : memo de chunk -- la source n'est interrogee qu'au changement de chunk.
        if (!deepluckyblock.util.SafeSurface.present(j.level, bp.getX(), bp.getZ(), j.memo)) {
            deepluckyblock.util.SafeSurface.request(j.level, bp.getX() >> 4, bp.getZ() >> 4);
            j.memo.reset();
            return false;
        }
        j.level.setBlock(bp, info.state(), FAST_FLAG);
        if (info.nbt() != null) {
            var be = j.level.getBlockEntity(bp);
            if (be != null) {
                CompoundTag tag = info.nbt().copy();
                tag.putInt("x", bp.getX()); tag.putInt("y", bp.getY()); tag.putInt("z", bp.getZ());
                be.loadWithComponents(tag, j.level.registryAccess());
            }
        }
        return true;
    }

    private static List<StructureTemplate.StructureBlockInfo> orderByY(List<StructureTemplate.StructureBlockInfo> blocks) {
        List<StructureTemplate.StructureBlockInfo> sorted = new ArrayList<>(blocks);
        sorted.sort(java.util.Comparator
                .comparingInt((StructureTemplate.StructureBlockInfo b) -> b.pos().getX() >> 4)
                .thenComparingInt(b -> b.pos().getZ() >> 4)
                .thenComparingInt(b -> b.pos().getY())
                .thenComparingInt(b -> b.pos().getX())
                .thenComparingInt(b -> b.pos().getZ()));
        return sorted;
    }

    @SuppressWarnings("unchecked")
    private static List<StructureTemplate.StructureBlockInfo> exBlocks(StructureTemplate t) {
        try {
            for (Field f : StructureTemplate.class.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(t);
                if (val instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof StructureTemplate.Palette) {
                    return ((StructureTemplate.Palette) list.get(0)).blocks();
                }
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    private static boolean isLaggy(BlockState s) {
        return s.is(Blocks.TRIPWIRE)
            || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.WHITE_CARPET) || s.is(Blocks.ORANGE_CARPET)
            || s.is(Blocks.MAGENTA_CARPET) || s.is(Blocks.LIGHT_BLUE_CARPET) || s.is(Blocks.YELLOW_CARPET)
            || s.is(Blocks.LIME_CARPET) || s.is(Blocks.PINK_CARPET) || s.is(Blocks.GRAY_CARPET)
            || s.is(Blocks.LIGHT_GRAY_CARPET) || s.is(Blocks.CYAN_CARPET) || s.is(Blocks.PURPLE_CARPET)
            || s.is(Blocks.BLUE_CARPET) || s.is(Blocks.BROWN_CARPET) || s.is(Blocks.GREEN_CARPET)
            || s.is(Blocks.RED_CARPET) || s.is(Blocks.BLACK_CARPET);
    }

    private static boolean isWorldTriggered(ServerLevel overworld) {
        try {
            return stateOf(overworld).triggered;
        } catch (Exception e) { return false; }
    }

    private static void markWorldTriggered(ServerLevel overworld) {
        try { stateOf(overworld).mark(); } catch (Exception ignored) {}
    }

    private static CrimsonLakeState stateOf(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(new SavedData.Factory<>(CrimsonLakeState::new, CrimsonLakeState::load), "crimson_lake_state");
    }

    public static class CrimsonLakeState extends SavedData {
        public boolean triggered = false;
        public void mark() { triggered = true; setDirty(); }
        public void clear() { triggered = false; setDirty(); }
        public static CrimsonLakeState load(CompoundTag tag, HolderLookup.Provider registries) {
            CrimsonLakeState s = new CrimsonLakeState();
            s.triggered = tag.getBoolean("triggered");
            return s;
        }
        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("triggered", triggered);
            return tag;
        }
    }

    private static final class PendingBuild {
        final ServerLevel level;
        final int px, py, pz;
        // FIX T7 : non-final -- le champ est repousse tant que le NBT
        // "crimsonlake" (3,46 Mo) n'est pas charge, au lieu de bloquer le
        // thread serveur le temps du parse (voir la boucle de PENDING).
        long startTick;
        final long queuedNanos = System.nanoTime();
        PendingBuild(ServerLevel l, int x, int y, int z, long t) { level = l; px = x; py = y; pz = z; startTick = t; }
    }

    private static final class PasteJob {
        final ServerLevel level;
        final List<StructureTemplate.StructureBlockInfo> blocks;
        final BlockPos origin;
        final BlockPos bfly;
        final long t0;
        final int total;
        final int sx, sz;
        int idx;
        /** T48 : blocs dont le chunk n'etait pas en memoire (repose au tick suivant). */
        final List<Integer> defer = new ArrayList<>();
        /** T53 : memo de chunk pour la pose (une recherche par chunk au lieu d'une par bloc). */
        final deepluckyblock.util.SafeSurface.ChunkMemo memo = new deepluckyblock.util.SafeSurface.ChunkMemo();
        int waitTicks;
        PasteJob(ServerLevel l, List<StructureTemplate.StructureBlockInfo> b, BlockPos o, BlockPos bf, long t, int sx, int sz) {
            level = l; blocks = b; origin = o; bfly = bf; t0 = t; total = b.size(); this.sx = sx; this.sz = sz; idx = 0;
        }
    }

    private static final class SpawnTask {
        final ServerLevel level;
        final BlockPos center;
        final int sx, sz;
        long nextTick;
        int spawned;
        final int cap;
        SpawnTask(ServerLevel l, BlockPos c, long firstTick, int cap, int sx, int sz) {
            level = l; center = c; nextTick = firstTick; spawned = 0; this.cap = cap; this.sx = sx; this.sz = sz;
        }
    }
}
