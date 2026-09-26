package deepluckyblock.procedures;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** CI-only fixture checks, copied into the temporary runner project, never shipped. */
@EventBusSubscriber(modid = "deep_lucky_block")
public final class StructureRegressionChecks {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final int FLAGS = 2 | 16 | 32;

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dlbverify").requires(s -> s.hasPermission(2))
                .executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    try {
                        asyncChunks(level);
                    } catch (Throwable error) { fail(error); }
                    return 1;
                }));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void fail(Throwable error) { LOG.error("[DLBVERIFY] FAIL: " + error, error); }

    private static void asyncChunks(ServerLevel level) {
        boolean[] returned = {false};
        int[] completed = {0};
        long started = System.nanoTime();
        for (int i = 0; i < 8; i++) {
            int cx = 600 + i, cz = 600;
            deepluckyblock.util.SafeSurface.requestThen(level, cx, cz, () -> {
                try {
                    require(returned[0], "Chunk request waited inline on server thread");
                    require(level.getServer().isSameThread(), "Chunk callback outside server thread");
                    require(level.getChunkSource().getChunkNow(cx, cz) != null, "Completed chunk not available");
                    if (++completed[0] == 8) {
                        LOG.info("[DLBVERIFY] PASS async: eight cold chunks, no inline wait, callbacks on server thread");
                        loot(level);
                        basin(level);
                    }
                } catch (Throwable error) { fail(error); }
            });
        }
        returned[0] = true;
        LOG.info("[DLBVERIFY] async request dispatch took {} ms", (System.nanoTime() - started) / 1_000_000.0);
    }

    private static void loot(ServerLevel level) throws Exception {
        BlockPos source = new BlockPos(0, 280, 0);
        var existing = ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.withDefaultNamespace("chests/village/village_plains_house"));
        for (int x = 0; x < 4; x++)
            level.setBlock(source.offset(x, 0, 0), (x == 3 ? Blocks.TRAPPED_CHEST : Blocks.CHEST).defaultBlockState(), FLAGS);
        ((ChestBlockEntity) level.getBlockEntity(source.offset(1, 0, 0))).setItem(0, new ItemStack(Items.DIAMOND, 3));
        ((ChestBlockEntity) level.getBlockEntity(source.offset(2, 0, 0))).setLootTable(existing, 42L);
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, source, new Vec3i(4, 1, 1), false, Blocks.STRUCTURE_VOID);
        var populate = StructureScatterDecor.class.getDeclaredMethod("populateVillageLootChests",
                ServerLevel.class, StructureTemplate.class, BlockPos.class, StructurePlaceSettings.class,
                net.minecraft.util.RandomSource.class);
        populate.setAccessible(true);
        int variants = 0;
        for (Rotation rotation : Rotation.values()) {
            for (Mirror mirror : new Mirror[]{Mirror.NONE, Mirror.FRONT_BACK}) {
                BlockPos bounds = source.offset(12 + variants * 6, 0, 12);
                BlockPos at = template.getZeroPositionWithTransform(bounds, mirror, rotation);
                var settings = new StructurePlaceSettings().setRotation(rotation).setMirror(mirror).setIgnoreEntities(true);
                require(template.placeInWorld(level, at, at, settings, level.getRandom(), FLAGS), "Template placement failed");
                java.util.Map<BlockPos, Long> existingSeeds = new java.util.HashMap<>();
                for (var info : template.filterBlocks(at, settings, Blocks.CHEST)) {
                    var chest = (ChestBlockEntity) level.getBlockEntity(info.pos());
                    if (chest.getLootTable() != null) existingSeeds.put(info.pos(), chest.getLootTableSeed());
                }
                int assigned = (Integer) populate.invoke(null, level, template, at, settings, level.getRandom());
                require(assigned == 2, "Expected two empty chests, got " + assigned);
                int occupied = 0, preserved = 0, generated = 0;
                for (var block : new net.minecraft.world.level.block.Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST}) {
                    for (var info : template.filterBlocks(at, settings, block)) {
                        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(info.pos());
                        require(chest != null, "Missing transformed chest");
                        require(info.pos().getX() >= bounds.getX() && info.pos().getZ() >= bounds.getZ(), "Negative footprint");
                        if (chest.getLootTable() == null) {
                            require(chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 3, "Existing items changed");
                            occupied++;
                        } else {
                            String path = chest.getLootTable().location().getPath();
                            require(path.startsWith("chests/village/"), "Non-village loot table: " + path);
                            if (existingSeeds.containsKey(info.pos())) {
                                require(chest.getLootTableSeed() == existingSeeds.get(info.pos()), "Existing seed changed");
                                require(chest.getLootTable().equals(existing), "Existing table changed");
                                preserved++;
                            } else {
                                chest.unpackLootTable(null);
                                require(!chest.isEmpty(), "Village loot table produced no items");
                                generated++;
                            }
                        }
                        level.setBlock(info.pos(), Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
                require(occupied == 1 && preserved == 1 && generated == 2, "Loot preservation counts differ");
                variants++;
            }
        }
        for (int x = 0; x < 4; x++) level.setBlock(source.offset(x, 0, 0), Blocks.AIR.defaultBlockState(), FLAGS);
        LOG.info("[DLBVERIFY] PASS loot: 8 rotation/mirror variants, 16 generated inventories, existing items/tables preserved");
    }

    private static void basin(ServerLevel level) {
        basin(level, false);
    }

    private static void basin(ServerLevel level, boolean onDemand) {
        // 87x87x6 interior = 45,414 blocks: exceeds the previous 40,000 cutoff.
        // It crosses both positive and negative chunk boundaries.
        for (int x = -44; x <= 44; x++) {
            for (int z = -44; z <= 44; z++) {
                for (int y = 240; y <= 246; y++) {
                    boolean wall = y == 240 || Math.abs(x) == 44 || Math.abs(z) == 44
                            || (Math.max(Math.abs(x), Math.abs(z)) == 3);
                    level.setBlock(new BlockPos(x, y, z), (wall ? Blocks.STONE : Blocks.AIR).defaultBlockState(), FLAGS);
                }
            }
        }
        for (int y = 241; y <= 246; y++) level.setBlock(new BlockPos(-43, y, -43), Blocks.WATER.defaultBlockState(), FLAGS);
        // An existing lower water surface must not stop refill at its old level.
        level.setBlock(new BlockPos(-10, 241, -10), Blocks.WATER.defaultBlockState(), FLAGS);
        BlockPos min = new BlockPos(-2, 240, -2), max = new BlockPos(2, 246, 2);
        Runnable done = () -> {
            try {
                int restored = 0;
                for (int x = -43; x <= 43; x++) {
                    for (int z = -43; z <= 43; z++) {
                        boolean protectedColumn = Math.abs(x) <= 2 && Math.abs(z) <= 2;
                        for (int y = 241; y <= 246; y++) {
                            var state = level.getBlockState(new BlockPos(x, y, z));
                            if (protectedColumn) require(state.isAir(), "Footprint flooded at " + x + "," + y + "," + z);
                            else if (Math.max(Math.abs(x), Math.abs(z)) == 3) {
                                require(state.is(Blocks.STONE), "Island wall overwritten");
                            } else {
                                require(state.is(Blocks.WATER) && state.getFluidState().isSource(), "Unfilled basin at " + x + "," + y + "," + z);
                                restored++;
                            }
                        }
                    }
                }
                require(restored == 45120, "Unexpected basin volume " + restored);
                LOG.info("[DLBVERIFY] PASS water (onDemand={}): {} source blocks, chunk borders, partial column and dry footprint verified", onDemand, restored);
                for (int x = -44; x <= 44; x++)
                    for (int z = -44; z <= 44; z++)
                        for (int y = 240; y <= 246; y++)
                            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
                deepluckyblock.util.ChunkKeeper.release(level);
                if (onDemand) lazyLiquidBoundary(level);
                else basin(level, true);
            } catch (Throwable error) { fail(error); }
        };
        if (onDemand) StructureTerrainPrep.fixLiquidsPassOnDemand(level, min, max, done);
        else StructureTerrainPrep.fixLiquidsPass(level, min, max, done);
    }

    private static void lazyLiquidBoundary(ServerLevel level) {
        final int cx = 1300, cz = 1300;
        deepluckyblock.util.SafeSurface.requestThen(level, cx, cz, () ->
            deepluckyblock.util.SafeSurface.requestThen(level, cx + 2, cz, () -> {
            try {
                require(level.getChunkSource().getChunkNow(cx + 1, cz) == null,
                        "Liquid frontier fixture is not cold");
                BlockPos seed = new BlockPos((cx << 4) + 15, 241, (cz << 4) + 8);
                BlockPos footprint = new BlockPos((cx << 4) + 1, 240, (cz << 4) + 1);
                BlockPos padding = new BlockPos(((cx + 2) << 4) + 15, 241, (cz << 4) + 8);
                require(level.getChunkSource().getChunkNow(cx + 3, cz) == null, "Padding frontier is not cold");
                // Default ring 80 * 25% + 16 = 36. The second source is 46 blocks
                // from the footprint: inside the 48-block repair halo, outside the
                // edited terrain border, in the last column of a loaded chunk.
                StructureTerrainPrep.setTerrainRingScalePercent(25);
                deepluckyblock.util.ChunkKeeper.track(level, seed, seed);
                // Freeze the fixture water too; native ticks must not load the
                // padding frontier and invalidate the cold-chunk assertions.
                deepluckyblock.util.TerrainEditClamp.start(level, cx << 4, 230, cz << 4,
                        ((cx + 2) << 4) + 15, 250, (cz << 4) + 15);
                level.setBlock(seed.below(), Blocks.STONE.defaultBlockState(), FLAGS);
                level.setBlock(seed, Blocks.WATER.defaultBlockState(), FLAGS);
                // A channel crosses the seed boundary at x=footprint+36. Refill
                // must reach its end in the padding, but stop at the intact source.
                for (int x = (cx + 2) << 4; x <= padding.getX(); x++) {
                    level.setBlock(new BlockPos(x, 240, seed.getZ()), Blocks.STONE.defaultBlockState(), FLAGS);
                    level.setBlock(new BlockPos(x, 241, seed.getZ() - 1), Blocks.STONE.defaultBlockState(), FLAGS);
                    level.setBlock(new BlockPos(x, 241, seed.getZ() + 1), Blocks.STONE.defaultBlockState(), FLAGS);
                }
                level.setBlock(new BlockPos((cx + 2) << 4, 241, seed.getZ()), Blocks.STONE.defaultBlockState(), FLAGS);
                level.setBlock(new BlockPos(((cx + 2) << 4) + 1, 241, seed.getZ()), Blocks.WATER.defaultBlockState(), FLAGS);
                level.setBlock(padding, Blocks.WATER.defaultBlockState(), FLAGS);
                StructureTerrainPrep.fixLiquidsPassOnDemand(level, footprint, footprint, () -> {
                    try {
                        require(level.getChunkSource().getChunkNow(cx + 1, cz) != null,
                                "Water repair skipped cold frontier chunk");
                        require(deepluckyblock.util.ChunkKeeper.zoneComplete(level),
                                "Water repair finished before frontier pinning");
                        require(level.getChunkSource().getChunkNow(cx, cz - 2) == null,
                                "Water repair generated unrelated dry halo");
                        require(level.getChunkSource().getChunkNow(cx + 3, cz) == null,
                                "Chunk-rounded padding seeded an unrelated frontier");
                        require(level.getBlockState(seed).getFluidState().isSource(), "Lost original source");
                        require(level.getBlockState(padding).getFluidState().isSource(), "Changed untouched padding source");
                        for (int x = ((cx + 2) << 4) + 1; x <= padding.getX(); x++)
                            require(level.getBlockState(new BlockPos(x, 241, seed.getZ())).getFluidState().isSource(),
                                    "Refill stopped at seed boundary: " + x);
                        for (int x = (cx + 2) << 4; x <= padding.getX(); x++)
                            for (int y = 240; y <= 241; y++)
                                for (int z = seed.getZ() - 1; z <= seed.getZ() + 1; z++)
                                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
                        StructureTerrainPrep.setTerrainRingScalePercent(100);
                        level.setBlock(seed, Blocks.AIR.defaultBlockState(), FLAGS);
                        level.setBlock(seed.below(), Blocks.AIR.defaultBlockState(), FLAGS);
                        deepluckyblock.util.ChunkKeeper.release(level);
                        LOG.info("[DLBVERIFY] PASS lazy water: cold frontier pinned, refill beyond seed bounds, unrelated padding frontier untouched");
                        smooth(level);
                    } catch (Throwable error) { fail(error); }
                });
            } catch (Throwable error) { fail(error); }
        }));
    }

    private static void smooth(ServerLevel level) throws Exception {
        int size = 29, x0 = -14, z0 = -14;
        for (int x = x0; x < x0 + size; x++)
            for (int z = z0; z < z0 + size; z++) {
                int height = Math.max(Math.abs(x), Math.abs(z)) <= 8 ? 240 : 236;
                for (int y = 236; y <= 240; y++)
                    level.setBlock(new BlockPos(x, y, z), (y <= height ? Blocks.DIRT : Blocks.AIR).defaultBlockState(), FLAGS);
            }
        StructureTerrainPrep.resetNaturalReference();
        var smooth = StructureTerrainPrep.class.getDeclaredMethod("smoothPassAfterPreload", ServerLevel.class,
                BlockPos.class, BlockPos.class, int.class, int.class, int.class, int.class,
                int.class, int.class, int.class, int.class, Runnable.class);
        smooth.setAccessible(true);
        smooth.invoke(null, level, new BlockPos(-2, 240, -2), new BlockPos(2, 240, 2),
                7, 50, 3, 12, x0, z0, size, size, (Runnable) () -> {
                    try {
                        for (int x = -8; x <= 8; x++)
                            for (int z = -8; z <= 8; z++)
                                require(!level.getBlockState(new BlockPos(x, 240, z)).isAir(), "Near-footprint excavation at " + x + "," + z);
                        for (int x = -2; x <= 2; x++)
                            for (int z = -2; z <= 2; z++)
                                require(level.getBlockState(new BlockPos(x, 241, z)).isAir(), "Footprint raised");
                        for (int x = x0; x < x0 + size; x++)
                            for (int z = z0; z < z0 + size; z++)
                                for (int y = 236; y <= 250; y++)
                                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
                        StructureTerrainPrep.resetNaturalReference();
                        LOG.info("[DLBVERIFY] PASS smooth: footprint unchanged and six-block surroundings not excavated");
                        lakeClear(level);
                    } catch (Throwable error) { fail(error); }
                });
    }
    private static void lakeClear(ServerLevel level) {
        BlockPos origin = CrimsonLakeStructureSpawnProcedure.lakeOrigin(0, 0, 251, 7, 5, 5);
        require(origin.getY() + 7 == 242, "Lake must sink ten blocks below first free ground level");
        BlockPos min = new BlockPos(-2, 242, -2), max = new BlockPos(2, 250, 2);
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++)
                for (int y = 241; y <= 270; y++)
                    level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), FLAGS);
        CrimsonLakeStructureSpawnProcedure.clearBuildVolume(level, min, max, () -> {
            try {
                for (int x = -3; x <= 3; x++)
                    for (int z = -3; z <= 3; z++)
                        for (int y = 241; y <= 270; y++) {
                            boolean interior = Math.abs(x) <= 2 && Math.abs(z) <= 2 && y >= 242;
                            var state = level.getBlockState(new BlockPos(x, y, z));
                            require(interior ? state.isAir() : state.is(Blocks.STONE),
                                    "Lake clearance changed wrong cell: " + x + "," + y + "," + z);
                        }
                for (int x = -3; x <= 3; x++)
                    for (int z = -3; z <= 3; z++)
                        for (int y = 241; y <= 270; y++)
                            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
                LOG.info("[DLBVERIFY] PASS lake: sink=10 with template padding; mountain above roof cleared; sides/floor preserved");
                ticketRefresh(level);
            } catch (Throwable error) { fail(error); }
        });
    }

    private static net.minecraft.server.level.Ticket<?> portalTicket(ServerLevel level, long key) throws Exception {
        var managerField = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("distanceManager");
        managerField.setAccessible(true);
        var ticketsField = net.minecraft.server.level.DistanceManager.class.getDeclaredField("tickets");
        ticketsField.setAccessible(true);
        var tickets = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>) ticketsField.get(managerField.get(level.getChunkSource()));
        var bucket = (Iterable<?>) tickets.get(key);
        if (bucket != null) for (Object value : bucket) {
            var ticket = (net.minecraft.server.level.Ticket<?>) value;
            if (ticket.getType() == net.minecraft.server.level.TicketType.PORTAL) return ticket;
        }
        return null;
    }

    private static void ticketRefresh(ServerLevel level) {
        deepluckyblock.util.ChunkKeeper.release(level);
        final int cx = 1450, cz = 1450;
        deepluckyblock.util.SafeSurface.requestThen(level, cx, cz, () -> {
            try {
                var cp = new net.minecraft.world.level.ChunkPos(cx, cz);
                deepluckyblock.util.ChunkKeeper.track(level, cp.getWorldPosition(), cp.getWorldPosition());
                deepluckyblock.util.ChunkKeeper.keep(level);
                var original = portalTicket(level, cp.toLong());
                require(original != null, "Missing retention ticket");
                var created = net.minecraft.server.level.Ticket.class.getDeclaredField("createdTick");
                created.setAccessible(true);
                long stamp = created.getLong(original);
                TestProcedure.schedule(level, TestProcedure.currentTick(level) + 360, () -> {
                    try {
                        var current = portalTicket(level, cp.toLong());
                        require(current == original, "Retention ticket removed/replaced instead of refreshed");
                        require(created.getLong(current) > stamp, "Retention ticket age not refreshed");
                        require(level.getChunkSource().getChunkNow(cx, cz) != null,
                                "Chunk unloaded after the original 300-tick ticket lifetime");
                        deepluckyblock.util.ChunkKeeper.release(level);
                        require(portalTicket(level, cp.toLong()) == null, "Retention ticket leaked after release");
                        LOG.info("[DLBVERIFY] PASS tickets: same ticket renewed in place, chunk held past 300 ticks, release verified");
                        LOG.info("[DLBVERIFY] ALL PASS");
                    } catch (Throwable error) { fail(error); }
                });
            } catch (Throwable error) { fail(error); }
        });
    }

}
