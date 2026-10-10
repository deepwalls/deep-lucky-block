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
    /**
     * Enfoncement du lac dans le terrain naturel (blocs).
     *
     * <p>28/09 : 10 -> 20 (consigne Dev en jeu : « mets crimsonlake encore 10 blocs
     * enfonces dans le sol deja existant »). N'agit que sur lakeOrigin() : tout le lac
     * descend de 10 blocs supplementaires SOUS le terrain naturel ; le volume degage
     * (clearBuildVolume) suit automatiquement puisque son depart est
     * origin.offset(0, minRelativeY, 0). Les logs [DLB-LAKE] (sink=...) le confirment.</p>
     */
    private static final int SINK_BLOCKS = 20;
    private static final long BUILD_DELAY_TICKS     = 400;
    private static final long BUILD_START_DELAY_TICKS = 200;
    private static final int BUTTERFLY_DELAY_TICKS  = 600;
    private static final int BUTTERFLY_INTERVAL     = 40;
    // T110 (consigne dev 30/09 : « triple le nombre de papillons ») : le
    // plafond periodique passe de 150-400 a 450-1200 (x3), et les papillons
    // d'annonce de 20 a 60 (x3). Les packs (3-6 par intervalle) sont inchanges,
    // ils mettent juste ~3x plus de temps a atteindre le plafond.
    private static final int BUTTERFLY_MIN          = 450;
    private static final int BUTTERFLY_MAX          = 1200;
    private static final int INITIAL_BUTTERFLIES_MIN = 20;
    private static final int INITIAL_BUTTERFLIES_MAX = 77;
    private static final int PRE_DISCOVERY_TICKS = 120 * 20;

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
        // T157 : aucun message, avancement ni construction avant le présage complet.
        // 20..77 papillons apparaissent irrégulièrement pendant exactement 120 s,
        // alternativement près du joueur et dans la future emprise du lac.
        int initialCount = INITIAL_BUTTERFLIES_MIN
                + RNG.nextInt(INITIAL_BUTTERFLIES_MAX - INITIAL_BUTTERFLIES_MIN + 1);
        int serverTick = level.getServer().getTickCount();
        for (int b = 0; b < initialCount; b++) {
            final boolean nearPlayer = (b & 1) == 0;
            final BlockPos plannedCenter = pos;
            final int tickOffset = (int) (((long) (b + 1) * PRE_DISCOVERY_TICKS) / initialCount);
            TestProcedure.schedule(level, serverTick + tickOffset, () -> {
                // Le joueur peut se déplacer pendant les 120 s : le trail suit sa
                // position réelle. Les apparitions dans la future zone restent prévues.
                BlockPos spawnCenter = nearPlayer && !player.isRemoved()
                        ? player.blockPosition() : plannedCenter;
                spawnInitialButterflies(level, spawnCenter, 1);
            });
        }
        TestProcedure.schedule(level, serverTick + PRE_DISCOVERY_TICKS, () -> {
            if (player != null) DeepLuckyBlockAdvancements.grantCrimsonLake(player);
            Component msg = Component.literal(
                    "Congratulations! You found the secret event: Crimson Lake. Please step back! Construction is starting.")
                    .withStyle(ChatFormatting.GOLD);
            for (var p : level.players()) p.sendSystemMessage(msg);
        });

        long startTick = level.getServer().getTickCount() + PRE_DISCOVERY_TICKS;
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

    }

    private static int diceTick;
    // ⛔ T227 — recalibrage demande : « Crimson Lake [...] doit etre calibree pour
    //    se declencher en moyenne une fois par partie de 3 h ».
    //    Un de est lance tous les 1200 ticks = 60 s reelles.
    //    3 h = 10 800 s = 180 lancers -> il faut p = 1/180 par lancer.
    //    ANCIENNE valeur : 500 faces -> 1 declenchement toutes les 500 min = 8 h 20.
    //    NOUVELLE valeur : 180 faces -> 1 declenchement toutes les 180 min = 3 h 00.
    //    (DICE_HIT reste 99, qui doit rester STRICTEMENT inferieur a DICE_SIDES.)
    private static final int DICE_SIDES = 180;
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
            if (deepluckyblock.util.DebugLog.ENABLED) System.out.println("[SLB-DEBUG] CrimsonLake TICK: PASTE=" + PASTE_Q.size() + " PENDING=" + PENDING.size() + " ACTIVE=" + ACTIVE.size());

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
                        if (deepluckyblock.util.DebugLog.ENABLED) System.out.println("[SLB-DEBUG] CrimsonLake : NBT charge en tache de fond, construction reportee (le serveur n'est pas bloque)");
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
                    if (deepluckyblock.util.DebugLog.ENABLED) System.out.println("[SLB-DEBUG] CrimsonLake PASTE : " + j.defer.size()
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
                // T164/Circus brut : le NBT Crimson contient déjà eau, berges,
                // végétation et décors. Ne jamais lancer decorateFinish ici :
                // cette passe générique rescannait/replantait les 504 chunks et
                // représentait à elle seule plus de 100 secondes. Le clear puis
                // le paste sont l'état final voulu du lac.
                deepluckyblock.util.ChunkKeeper.release(j.level);
                Structures5Procedure.notifyGenerationFinished(j.level);

                // T111 (constat dev 30/09 : « crimson lake a fini ? je n'ai pas
                // eu le message disant que c'etait apparu avec les coordonnees »)
                // : annonce de DECOUVERTE en fin de pipeline de pose, parite
                // citadel (Structures5Procedure : « ✦ STRUCTURE DISCOVERED ✦ ...
                // Center coordinates »), avec la duree de la pose reelle.
                {
                    int cx = Math.floorDiv(cMin.getX() + cMax.getX(), 2);
                    int cy = Math.floorDiv(cMin.getY() + cMax.getY(), 2);
                    int cz = Math.floorDiv(cMin.getZ() + cMax.getZ(), 2);
                    String dur = String.format(java.util.Locale.ROOT, "%.1f",
                            (System.currentTimeMillis() - j.t0) / 1000.0);
                    Component discovered = Component.literal(
                            "§6§l✦ STRUCTURE DISCOVERED ✦ §r"
                                    + "§e§l" + NBT_NAME
                                    + " §f§lhas spawned! §7Center coordinates: "
                                    + "§b[" + cx + ", " + cy + ", " + cz + "]"
                                    + " §7(built in " + dur + "s)"
                                    + " §6✦");
                    for (var pl : j.level.players()) pl.sendSystemMessage(discovered);
                    if (deepluckyblock.util.DebugLog.ENABLED) System.out.println("[DLB-LAKE] STRUCTURE DISCOVERED (T111): center [" + cx + ", " + cy
                            + ", " + cz + "], built in " + dur + "s");
                }

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

        long filterT0 = System.currentTimeMillis();
        List<StructureTemplate.StructureBlockInfo> filt = new ArrayList<>(raw.size());
        int minRelativeY0 = Integer.MAX_VALUE;
        for (var info : raw) {
            if (info.state().isAir() || isLaggy(info.state())) continue;
            filt.add(info);
            if (info.pos().getY() < minRelativeY0) minRelativeY0 = info.pos().getY();
        }
        if (filt.isEmpty()) return;
        // T175 : NE PLUS TRIER 1 359 489 blocs sur le thread serveur.
        // orderByY créait plusieurs millions de comparaisons et une énorme
        // pression GC juste après l'annonce : le log restait muet pendant plus
        // de cinq minutes avec des retards de 16–18 s. Le clear est terminé
        // avant le paste et la physique est neutralisée; l'ordre Y n'est donc
        // pas une condition de correction. Conserver l'ordre préchargé du NBT.
        final int minRelativeY = minRelativeY0;
        if (deepluckyblock.util.DebugLog.ENABLED) System.out.println("[DLB-LAKE] liste de pose prête sans tri : " + filt.size()
                + " blocs filtrés en " + (System.currentTimeMillis() - filterT0) + "ms");

        Component msg2 = Component.literal(
                "Crimson Lake: loading surrounding chunks and building layer by layer...")
                .withStyle(ChatFormatting.AQUA);
        for (var pl : level.players()) pl.sendSystemMessage(msg2);

        final List<StructureTemplate.StructureBlockInfo> fFilt = filt;
        // T80 : la sonde ne lit QUE groundY(px, pz) (ancrage vertical du lac) --
        // elle n'a donc besoin que du chunk du CENTRE, charge en FULL avec sa
        // heightmap primee (regle T7 inchangee : aucune lecture sur chunk non
        // charge). Avant, TOUTE l'emprise (340 chunks, dont ~23 s de generation
        // en CI, run 36336086415) etait demandee ici pour cette seule lecture,
        // puis le prepZone qui suit rechargeait une zone encore plus large
        // (756 chunks) : deux attentes payees pour le meme terrain. Le
        // prepZone suivant charge toujours l'emprise entiere de son cote :
        // aucun changement de geometrie, d'ancrage ni de pipeline.
        BlockPos probeMin = new BlockPos(px - 8, py, pz - 8);
        BlockPos probeMax = new BlockPos(px + 8, py, pz + 8);
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
            // T157 : adapter l'ancrage vertical AVANT toute annonce/passe au lieu
            // d'abandonner le lac dans les mondes bas ou près du plafond.
            int minOriginY = level.getMinBuildHeight() + 1 - minRelativeY;
            int maxOriginY = level.getMaxBuildHeight() - sy;
            int clampedY = Math.max(minOriginY, Math.min(maxOriginY, origin.getY()));
            if (clampedY != origin.getY()) origin = new BlockPos(origin.getX(), clampedY, origin.getZ());
            final BlockPos finalOrigin = origin;
            BlockPos min = finalOrigin;
            BlockPos max = finalOrigin.offset(sx - 1, sy - 1, sz - 1);

            // T163 : les deux minutes de présage laissaient d'autres structures
            // s'enregistrer dans la future emprise. Revalider AU DERNIER MOMENT,
            // avant le moindre clear, et déplacer tout le lac en cas de conflit.
            int lakeRadius = Math.max(sx, sz) / 2 + 16;
            String conflict = deepluckyblock.util.StructureSites.conflict(level, px, pz, lakeRadius);
            if (conflict != null) {
                BlockPos shifted = deepluckyblock.util.StructureSites.freeOffset(level,
                        new BlockPos(px, py, pz), 12, lakeRadius, lakeRadius);
                String shiftedConflict = deepluckyblock.util.StructureSites.conflict(
                        level, shifted.getX(), shifted.getZ(), lakeRadius);
                deepluckyblock.util.ChunkKeeper.release(level);
                if (shiftedConflict != null || (shifted.getX() == px && shifted.getZ() == pz)) {
                    // Garde absolue anti-boucle : l'ancienne version rappelait
                    // queueBuild éternellement sur le même centre.
                    System.err.println("[DLB-LAKE] ABORT: aucune emprise complete libre trouvee; "
                            + "aucun terrain modifie (conflit '" + shiftedConflict + "')");
                    // T228 : ABORT = notifyGenerationAborted, jamais Finished :
                    // Finished gravait le lac comme « deja apparu » alors qu'il
                    // n'avait rien pose -> il devenait intirable pour toujours.
                    Structures5Procedure.notifyGenerationAborted(level);
                    return;
                }
                if (deepluckyblock.util.DebugLog.ENABLED) System.out.println("[DLB-LAKE] conflit tardif avec '" + conflict
                        + "' : centre déplacé UNE FOIS vers [" + shifted.getX() + "," + shifted.getZ()
                        + "] AVANT terrain/clear");
                queueBuild(level, shifted.getX(), py, shifted.getZ());
                return;
            }
            // Réserver immédiatement l'emprise : aucun autre événement lancé
            // pendant le long paste ne pourra désormais apparaître à l'intérieur.
            deepluckyblock.util.StructureSites.register(level, NBT_NAME,
                    min.getX(), min.getZ(), max.getX(), max.getZ());

            if (finalOrigin.getY() + minRelativeY <= level.getMinBuildHeight()
                    || max.getY() >= level.getMaxBuildHeight()) {
                System.err.println("[DLB-LAKE] ABORT: template outside build height; no blocks placed");
                deepluckyblock.util.ChunkKeeper.release(level);
                // T228 : ABORT = notifyGenerationAborted (voir le correctif
                // ci-dessus) : la structure doit redevenir tirable.
                Structures5Procedure.notifyGenerationAborted(level);
                return;
            }
            if (deepluckyblock.util.DebugLog.ENABLED) System.out.println("[DLB-LAKE] anchor: ground=" + groundY + ", lowest block="
                    + (finalOrigin.getY() + minRelativeY) + ", sink=" + SINK_BLOCKS);
            // All shaping precedes clearing. Nothing may refill the interior before paste.
            // groundY is the SOLID selected ground: shared clearance only removes what
            // stands ABOVE it. The lake's own sink and its interior are written by the
            // paste itself over the preserved ground (T93 : on n'efface plus sous le sol).
            if (deepluckyblock.util.DebugLog.ENABLED) System.out.println("[DLB-LAKE] clearance floor: selected groundY=" + groundY
                    + ", shared clearance starts at Y=" + (groundY + 1)
                    + ", foundation Y=" + (finalOrigin.getY() + minRelativeY)
                    + " (sink=" + SINK_BLOCKS + ", T93 : sous-sol preserve, creuse par le paste)");
            // T164 : Crimson Lake suit désormais le pipeline BRUT de Circus.
            // Le lac contient déjà son eau, ses berges, son fond et ses décors :
            // prepZone + decorateTerrainOnly retraitaient 504 chunks et 1,36 M
            // blocs avant la pose (plus de 100 s), puis les retravaillaient
            // encore après. Une seule opération est nécessaire : clear réel à
            // partir de groundY+1, puis paste -air. Sink/fond, ancrage, présage,
            // contrôle d'emprise et règles propres au lac restent inchangés.
            clearBuildVolume(level, finalOrigin.offset(0, minRelativeY, 0), max,
                    groundY + 1, () -> PASTE_Q.offer(new PasteJob(level, fFilt,
                            finalOrigin, bfly, System.currentTimeMillis(), sx, sz)));
        });
    }

    static BlockPos lakeOrigin(int x, int z, int groundY, int minRelativeY, int sx, int sz) {
        // Reference is the first free block above natural ground, not template padding.
        return new BlockPos(x - sx / 2, groundY + 1 - SINK_BLOCKS - minRelativeY, z - sz / 2);
    }

    /**
     * Clear the footprint up to open sky without deleting whole chunks or their metadata.
     *
     * T93 (consigne dev 29/09 : « tu supprimes meme les parties qui sont sol et
     * en dessous du sol -- efface UNIQUEMENT LE HAUT ») : le volume n'est plus
     * vide depuis le FOND DU TEMPLATE ({@code min}), mais seulement a partir de
     * {@code clearFloorY} (= groundY + 1, le sol selectionne). Le sol et le
     * sous-sol naturels restent intacts. Mesure NBT (crimsonlake, 16 couches
     * sous le sol) : socle 100 % plein y=0..8 et bassin quasi plein au-dela --
     * SEULS ~78 blocs d'air existent sous le sol ; le paste ecrit donc le socle,
     * l'eau et les parois PAR-DESSUS la terre conservee (l'air NBT n'etant pas
     * ecrit), le bassin est creuse par la matiere meme du template.
     */
    static void clearBuildVolume(ServerLevel level, BlockPos min, BlockPos max, int clearFloorY, Runnable onReady) {
        new LakeClearJob(level, min, max, clearFloorY, onReady).step();
    }

    private static final class LakeClearJob {
        final ServerLevel level;
        final BlockPos min, max;
        final int clearFloorY;
        final Runnable onReady;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int cx0, cz0, cx1, cz1;
        int cx, cz;
        long cleared;
        int chunksDone;

        LakeClearJob(ServerLevel level, BlockPos min, BlockPos max, int clearFloorY, Runnable onReady) {
            this.level = level; this.min = min; this.max = max;
            this.clearFloorY = clearFloorY; this.onReady = onReady;
            cx0 = min.getX() >> 4; cz0 = min.getZ() >> 4;
            cx1 = max.getX() >> 4; cz1 = max.getZ() >> 4;
            cx = cx0; cz = cz0;
        }

        /**
         * T101 : CLEAR OPTIMISE (consigne dev 29/09 : « ta technique de clear de
         * chunks n'est PAS DU TOUT optimisee, surtout pour crimsonlake -- vois en
         * ligne le plus opti, pour eviter d'avoir les blocs d'air et etre opti,
         * comprendre tous les autres, non solides, liquides etc, mais pas l'air »).
         *
         * Methode = celle des outils rapides du monde (FAWE/WorldEdit) :
         * on n'appelle PLUS JAMAIS level.setBlock() bloc par bloc. Chaque
         * setBlock declenchait pour CHAQUE bloc : verification de voisins,
         * mise a jour de heightmap (1 scan vertical) et repropagation de
         * lumiere -- soit ~30+ unites de travail par bloc sur un volume de
         * centaines de milliers de blocs. Desormais :
         *   1. on ecrit directement dans le PalettedContainer de la section
         *      (stockage brut du chunk, z = y*256+z*16+x) : AUCUNE notification
         *      de voisin, AUCUN tick de fluide (liquides effaces silencieusement,
         *      exactement ce que demande la consigne : « non solides, liquides
         *      etc »), AUCUNE mise a jour de lumiere par bloc ;
         *   2. « mais pas l'air » : un bloc DEJA air n'est JAMAIS reecrit
         *      (0 cout), et une section entierement vide est saute entierement ;
         *   3. les blocs-entites (coffres, panneaux...) du volume sont d'abord
         *      liberes proprement, sinon ils resteraient en fantome tickants ;
         *   4. UNE FOIS PAR CHUNK (au lieu de par bloc) : recomptage des blocs
         *      par section (recalcBlockCounts), heightmaps re-amorcees
         *      (primeHeightmaps), lumiere re-planifiee (updateSectionStatus +
         *      tryScheduleUpdate) et chunk marque a sauvegarder.
         * Le plancher T93 (clearFloorY = groundY + 1) et la bedrock sont
         * conserves exactement comme avant. Si un chunk de la boite n'est pas un
         * LevelChunk (ProtoChunk en cours de worldgen -- ne devrait pas arriver
         * dans la zone epinglee), on replie sur l'ancien chemin setBlock pour
         * ce chunk-la seulement : securite d'abord, vitesse ensuite.
         */
        void step() {
            deepluckyblock.util.ChunkKeeper.keep(level);
            long deadline = System.nanoTime() + 40_000_000L;   // 40 ms : un chunk ~1-3 ms
            while (cx <= cx1 && System.nanoTime() < deadline) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    deepluckyblock.util.SafeSurface.reRequest(level, cx, cz);
                    break;
                }
                if (chunk instanceof net.minecraft.world.level.chunk.LevelChunk lc) {
                    cleared += clearChunkDirect(lc);
                } else {
                    cleared += clearChunkPerBlock(chunk);
                }
                chunksDone++;
                if (++cz > cz1) { cz = cz0; cx++; }
            }
            if (cx <= cx1) {
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 1, this::step);
            } else {
                if (deepluckyblock.util.DebugLog.ENABLED) System.out.println("[DLB-LAKE] CLEAR COMPLETE (T101 direct-sections): " + cleared
                        + " blocks removed on " + chunksDone + " chunks; paste may start");
                if (onReady != null) onReady.run();
            }
        }

        /** Chemin rapide : ecriture directe de palette, voir T101. Renvoie le nombre de blocs retires. */
        private long clearChunkDirect(net.minecraft.world.level.chunk.LevelChunk chunk) {
            long n = 0;
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            int lx0 = Math.max(0, min.getX() - baseX), lx1 = Math.min(15, max.getX() - baseX);
            int lz0 = Math.max(0, min.getZ() - baseZ), lz1 = Math.min(15, max.getZ() - baseZ);
            if (lx0 > lx1 || lz0 > lz1) return 0;
            int yBot = Math.max(Math.max(min.getY(), clearFloorY), level.getMinBuildHeight() + 1);
            int yTop = level.getMaxBuildHeight() - 2;
            BlockState air = Blocks.AIR.defaultBlockState();
            // 1) blocs-entites du volume : liberes AVANT toute ecriture palette.
            java.util.List<BlockPos> kill = null;
            for (BlockPos be : chunk.getBlockEntities().keySet()) {
                if (be.getY() >= yBot && be.getY() <= yTop
                        && be.getX() >= baseX + lx0 && be.getX() <= baseX + lx1
                        && be.getZ() >= baseZ + lz0 && be.getZ() <= baseZ + lz1) {
                    if (kill == null) kill = new ArrayList<>();
                    kill.add(be.immutable());
                }
            }
            if (kill != null) for (BlockPos bp : kill) level.removeBlockEntity(bp);
            // 2) ecriture palette directe, section par section.
            var sections = chunk.getSections();
            int i0 = chunk.getSectionIndex(yBot), i1 = chunk.getSectionIndex(yTop);
            boolean anyTouched = false;
            for (int i = i0; i <= i1 && i < sections.length; i++) {
                net.minecraft.world.level.chunk.LevelChunkSection sec = sections[i];
                if (sec == null || sec.hasOnlyAir()) continue;      // « eviter d'avoir les blocs d'air »
                int secY = chunk.getSectionYFromSectionIndex(i);
                int sy = secY << 4;
                int ly0 = Math.max(sy, yBot) - sy, ly1 = Math.min(sy + 15, yTop) - sy;
                if (ly0 > ly1) continue;
                var states = sec.getStates();
                boolean touched = false;
                for (int ly = ly0; ly <= ly1; ly++)
                    for (int lz = lz0; lz <= lz1; lz++)
                        for (int lx = lx0; lx <= lx1; lx++) {
                            BlockState prev = states.get(lx, ly, lz);
                            if (prev.isAir() || prev.getBlock() == Blocks.BEDROCK) continue;
                            // T175 : supprimer également le NBT différé des block
                            // entities avant l'écriture palette directe. Sinon un
                            // prochain chargement recrée hopper/comparator sur AIR.
                            if (prev.hasBlockEntity())
                                chunk.removeBlockEntity(new BlockPos(baseX + lx, sy + ly, baseZ + lz));
                            states.set(lx, ly, lz, air);
                            touched = true; n++;
                        }
                if (touched) {
                    anyTouched = true;
                    sec.recalcBlockCounts();
                    // Lumiere : la section a change en contournant setBlock ->
                    // reevaluation FORCEE (sinon zones noires / soleil gele).
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

        /** Repli (ProtoChunk ou acces atypique) : ancien chemin, colonne par colonne, setBlock. */
        private long clearChunkPerBlock(net.minecraft.world.level.chunk.ChunkAccess chunk) {
            long n = 0;
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            int lx0 = Math.max(0, min.getX() - baseX), lx1 = Math.min(15, max.getX() - baseX);
            int lz0 = Math.max(0, min.getZ() - baseZ), lz1 = Math.min(15, max.getZ() - baseZ);
            if (lx0 > lx1 || lz0 > lz1) return 0;
            int yBot = Math.max(Math.max(min.getY(), clearFloorY), level.getMinBuildHeight() + 1);
            for (int lx = lx0; lx <= lx1; lx++)
                for (int lz = lz0; lz <= lz1; lz++) {
                    int top = Math.min(level.getMaxBuildHeight() - 1,
                            chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, lx, lz));
                    for (int y = yBot; y <= top; y++) {
                        BlockState state = chunk.getBlockState(pos.set(baseX + lx, y, baseZ + lz));
                        if (!state.isAir() && !state.is(Blocks.BEDROCK)) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FAST_FLAG);
                            n++;
                        }
                    }
                }
            return n;
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
        BlockState target = info.state();
        if (!target.hasBlockEntity()) {
            int cx = bp.getX() >> 4, cz = bp.getZ() >> 4;
            net.minecraft.world.level.chunk.LevelChunk chunk = j.level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) return false;
            BlockState old = chunk.getBlockState(bp);
            if (old.hasBlockEntity()) chunk.removeBlockEntity(bp);
            chunk.setBlockState(bp, target, false);
            chunk.setUnsaved(true);
            j.level.getChunkSource().blockChanged(bp);
        } else {
            j.level.setBlock(bp, target, FAST_FLAG);
        }
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
