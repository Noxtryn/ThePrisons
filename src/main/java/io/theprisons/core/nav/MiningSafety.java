package io.theprisons.core.nav;

/**
 * Rules that keep a mining module from destroying its own way back. Shared by every planner / selector that decides
 * which block to break.
 *
 * <p>Breaking a <em>floor</em> block (one with open space above it) turns it into a hole. A hole one block deep is
 * harmless: the player can drop in and jump out again. A deeper hole, or one that opens into empty space below, can
 * cut a narrow passage for good (the player cannot climb more than {@value Walkability#JUMP_HEIGHT} blocks). Such
 * blocks are left alone.
 */
public final class MiningSafety {
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private MiningSafety() {
    }

    /** True when breaking the block at (x, y, z) leaves a walkable surface the player can get out of. */
    public static boolean keepsFloor(VoxelView view, int x, int y, int z) {
        int above = view.cell(x, y + 1, z);
        if (above == Cell.UNKNOWN || Cell.hasCollision(above)) {
            // Not a floor (wall / ceiling block, or buried): breaking it cannot open a pit.
            return true;
        }
        int below = view.cell(x, y - 1, z);
        if (below == Cell.UNKNOWN || !Cell.hasCollision(below) || Cell.isLiquid(below) || Cell.isDanger(below)) {
            // Would open into empty space / fluid below: an unbounded drop.
            return false;
        }
        // The new floor is at y. It is fine when a side column has a floor at y (walk out) or at y + 1 (jump out).
        for (int[] side : SIDES) {
            int sx = x + side[0];
            int sz = z + side[1];
            if (Cell.hasCollision(view.cell(sx, y - 1, sz)) && open(view.cell(sx, y, sz)) && open(view.cell(sx, y + 1, sz))) {
                return true;
            }
            if (Cell.hasCollision(view.cell(sx, y, sz)) && open(view.cell(sx, y + 1, sz)) && open(view.cell(sx, y + 2, sz))) {
                return true;
            }
        }
        return false;
    }

    /** Nodes a local two-way flood must still reach after the break (capped: it only has to stay a real area). */
    public static final int CONNECTED_NODES = 48;

    /**
     * True when breaking {@code block} does not cut the standing node off: a small two-way flood from the node must
     * reach as many nodes afterwards as before, up to {@value #CONNECTED_NODES}. Only floor blocks can change
     * walkability, so for anything else this is free. Cost: two floods of at most {@value #CONNECTED_NODES} nodes.
     */
    public static boolean keepsConnection(VoxelView view, long block, long standNode) {
        int bx = Pos.x(block);
        int by = Pos.y(block);
        int bz = Pos.z(block);
        int above = view.cell(bx, by + 1, bz);
        if (above == Cell.UNKNOWN || Cell.hasCollision(above)) {
            return true;
        }
        int before = reach(view, standNode);
        if (before == 0) {
            return true;
        }
        VoxelView after = new VoxelView() {
            @Override
            public int cell(int x, int y, int z) {
                return x == bx && y == by && z == bz ? Cell.AIR : view.cell(x, y, z);
            }

            @Override
            public int ore(int x, int y, int z) {
                return x == bx && y == by && z == bz ? 0 : view.ore(x, y, z);
            }
        };
        return reach(after, standNode) >= Math.min(before, CONNECTED_NODES);
    }

    private static int reach(VoxelView view, long start) {
        PathSearch.Flood flood = PathSearch.flood(new Walkability(view, 1), start, CONNECTED_NODES, 16, Double.POSITIVE_INFINITY, 0L, () -> false);
        return flood == null ? 0 : flood.size();
    }

    private static boolean open(int cell) {
        return cell != Cell.UNKNOWN && !Cell.hasCollision(cell) && !Cell.isLiquid(cell) && !Cell.isDanger(cell);
    }
}
