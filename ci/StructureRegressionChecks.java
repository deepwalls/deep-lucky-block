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
                        loot(level);
                        basin(level);
                    } catch (Throwable error) { fail(error); }
                    return 1;
                }));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void fail(Throwable error) { LOG.error("[DLBVERIFY] FAIL: " + error, error); }

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
        StructureTerrainPrep.fixLiquidsPass(level, min, max, () -> {
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
                LOG.info("[DLBVERIFY] PASS water: {} source blocks, chunk borders, partial column and dry footprint verified", restored);
                for (int x = -44; x <= 44; x++)
                    for (int z = -44; z <= 44; z++)
                        for (int y = 240; y <= 246; y++)
                            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
                deepluckyblock.util.ChunkKeeper.release(level);
                smooth(level);
            } catch (Throwable error) { fail(error); }
        });
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
                        LOG.info("[DLBVERIFY] ALL PASS");
                    } catch (Throwable error) { fail(error); }
                });
    }
}
