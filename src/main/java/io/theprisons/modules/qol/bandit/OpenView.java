package io.theprisons.modules.qol.bandit;

import io.theprisons.core.nav.VoxelView;

/** The world as the path finder sees it - but every block carries the player (the ore macro only walks on stone, bandit land is not stone). */
record OpenView(VoxelView base) implements VoxelView {
    @Override
    public int cell(int x, int y, int z) {
        return base.cell(x, y, z);
    }

    @Override
    public int ore(int x, int y, int z) {
        return base.ore(x, y, z);
    }

    @Override
    public boolean mineFloor(int x, int y, int z) {
        return true;
    }
}
