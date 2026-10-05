package com.freelocs.theprisons.core.world;

import com.freelocs.theprisons.core.nav.Cell;
import com.freelocs.theprisons.core.nav.VoxelView;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/**
 * {@link VoxelView} straight on the client world, used for tiny checks that must see the blocks as they are right
 * now (the next few path edges). Never touches unloaded chunks (reports them as unknown). Client thread only; never
 * use it for searches.
 */
public final class LiveWorldView implements VoxelView {
    private final ClientWorld world;
    private final BlockClassifier classifier;
    private final BlockPos.Mutable mutable = new BlockPos.Mutable();

    public LiveWorldView(ClientWorld world, BlockClassifier classifier) {
        this.world = world;
        this.classifier = classifier;
    }

    @Override
    public int cell(int x, int y, int z) {
        if (world.isOutOfHeightLimit(y)) {
            return y < world.getBottomY() ? Cell.SOLID : Cell.AIR;
        }
        if (world.getChunkManager().getWorldChunk(x >> 4, z >> 4, false) == null) {
            return Cell.UNKNOWN;
        }
        mutable.set(x, y, z);
        return classifier.cell(world.getBlockState(mutable), world, mutable);
    }

    @Override
    public int ore(int x, int y, int z) {
        if (world.isOutOfHeightLimit(y) || world.getChunkManager().getWorldChunk(x >> 4, z >> 4, false) == null) {
            return 0;
        }
        mutable.set(x, y, z);
        return classifier.target(world.getBlockState(mutable));
    }

    @Override
    public boolean mineFloor(int x, int y, int z) {
        if (world.isOutOfHeightLimit(y) || world.getChunkManager().getWorldChunk(x >> 4, z >> 4, false) == null) {
            return false;
        }
        mutable.set(x, y, z);
        return classifier.mineFloor(world.getBlockState(mutable));
    }
}
