package com.freelocs.theprisons.core.world;

import com.freelocs.theprisons.core.nav.Cell;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.EmptyBlockView;

import java.util.Set;

/**
 * Converts BlockStates into {@link Cell} values (collision interval in the player's column, outline, full cube,
 * danger, fluid, breakable) and target keys. Results are cached per state in primitive maps; states with a
 * position-dependent model offset are computed per position. Client thread only.
 */
public final class BlockClassifier {
    private static final double COLUMN_MIN = 0.2D;
    private static final double COLUMN_MAX = 0.8D;
    private static final int NOT_CACHED = Integer.MIN_VALUE;
    private static final Set<Block> DANGER = Set.of(
            Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.POWDER_SNOW, Blocks.COBWEB, Blocks.POINTED_DRIPSTONE,
            Blocks.LAVA);

    private final TargetRegistry targets;
    private final Reference2IntOpenHashMap<BlockState> cellCache = new Reference2IntOpenHashMap<>();
    private final Reference2IntOpenHashMap<BlockState> targetCache = new Reference2IntOpenHashMap<>();
    private final Reference2IntOpenHashMap<BlockState> floorCache = new Reference2IntOpenHashMap<>();
    private int targetsVersion = -1;

    public BlockClassifier(TargetRegistry targets) {
        this.targets = targets;
        cellCache.defaultReturnValue(NOT_CACHED);
        targetCache.defaultReturnValue(NOT_CACHED);
        floorCache.defaultReturnValue(NOT_CACHED);
    }

    /** Drops the target cache when the registry changed; returns true if it did. */
    public boolean refreshTargets() {
        int current = targets.version();
        if (current == targetsVersion) {
            return false;
        }
        targetsVersion = current;
        targetCache.clear();
        floorCache.clear();
        return true;
    }

    public int cell(BlockState state, BlockView world, BlockPos pos) {
        if (state.isAir()) {
            return Cell.AIR;
        }
        if (state.hasModelOffset()) {
            return encode(state, world, pos);
        }
        int cached = cellCache.getInt(state);
        if (cached == NOT_CACHED) {
            cached = encode(state, EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            cellCache.put(state, cached);
        }
        return cached;
    }

    /** Target key (see {@link BlockKeys}) or 0. */
    public int target(BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        int cached = targetCache.getInt(state);
        if (cached == NOT_CACHED) {
            cached = targets.keyOf(state);
            targetCache.put(state, cached);
        }
        return cached;
    }

    /** What the world cache stores per block: the target key plus {@link SectionSnapshot#FLOOR} for mine ground. */
    public int stored(BlockState state) {
        return target(state) | (mineFloor(state) ? SectionSnapshot.FLOOR : 0);
    }

    /** Stone, deepslate or any ore (also ores of packages not picked): the only ground the macro walks on. */
    public boolean mineFloor(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        int cached = floorCache.getInt(state);
        if (cached == NOT_CACHED) {
            cached = isMineFloor(state.getBlock()) || target(state) != 0 ? 1 : 0;
            floorCache.put(state, cached);
        }
        return cached == 1;
    }

    /** Stone, deepslate or a block whose id ends in "_ore". */
    public static boolean isMineFloor(Block block) {
        return block == Blocks.STONE || block == Blocks.DEEPSLATE
                || net.minecraft.registry.Registries.BLOCK.getId(block).getPath().endsWith("_ore");
    }

    private static int encode(BlockState state, BlockView world, BlockPos pos) {
        int flags = 0;
        int min16 = 0;
        int max16 = 0;
        VoxelShape collision = state.getCollisionShape(world, pos);
        if (!collision.isEmpty()) {
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (Box box : collision.getBoundingBoxes()) {
                if (box.maxX > COLUMN_MIN && box.minX < COLUMN_MAX && box.maxZ > COLUMN_MIN && box.minZ < COLUMN_MAX) {
                    minY = Math.min(minY, box.minY);
                    maxY = Math.max(maxY, box.maxY);
                }
            }
            if (minY <= maxY) {
                flags |= Cell.COLLISION;
                min16 = (int) Math.floor(Math.max(0.0D, minY) * 16.0D + 1.0E-6D);
                max16 = (int) Math.ceil(maxY * 16.0D - 1.0E-6D);
            }
        }
        if (!state.getOutlineShape(world, pos).isEmpty()) {
            flags |= Cell.OUTLINE;
        }
        if (state.isFullCube(world, pos)) {
            flags |= Cell.FULL;
        }
        if (!state.getFluidState().isEmpty()) {
            flags |= Cell.LIQUID;
        }
        if (DANGER.contains(state.getBlock()) || state.getFluidState().isIn(FluidTags.LAVA)) {
            flags |= Cell.DANGER;
        }
        if (state.getHardness(world, pos) >= 0.0F) {
            flags |= Cell.BREAKABLE;
        }
        return Cell.encode(min16, max16, flags);
    }
}
