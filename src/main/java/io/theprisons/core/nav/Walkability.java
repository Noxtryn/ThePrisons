package io.theprisons.core.nav;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMaps;

/**
 * Player-scale walkability rules on top of a {@link VoxelView}.
 *
 * <p>A node is a block cell {@code (x, y, z)} whose feet height lies in {@code [y, y+1)}. The feet height comes from
 * the real collision shape below the player (full blocks, slabs, fences ...). The player box is
 * {@value #PLAYER_HEIGHT} tall and needs that much free space above the feet; air alone is not enough and a
 * non-empty shape is not automatically a wall.
 *
 * <p>Only stone, deepslate and ores carry the player ({@link VoxelView#mineFloor}): every other block is taboo as
 * ground - except the column the player stands in ({@link #exempt}), so it can always walk off it.
 *
 * <p>{@link #penalties()} add extra cost to nodes the follower got stuck on ({@code +∞} removes a node), which is
 * how the anti-stuck logic steers re-plans around a bad spot without touching the world data.
 */
public final class Walkability {
    public static final double PLAYER_HEIGHT = 1.8D;
    public static final double EYE_HEIGHT = 1.62D;
    public static final double STEP_HEIGHT = 0.6D;
    /** Highest ledge a standing jump reliably clears (vanilla apex is ~1.252). */
    public static final double JUMP_HEIGHT = 1.2D;
    private static final double EPS = 1.0E-3D;
    private static final double SQRT2 = Math.sqrt(2.0D);

    public static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** Receives neighbour edges. */
    public interface EdgeSink {
        void accept(int x, int y, int z, double feet, MoveType type, double cost);
    }

    private final VoxelView view;
    private final int maxDrop;
    private final boolean allowJumps;
    private final Long2DoubleMap penalties;
    /** The player's own column: carries it whatever block it is ({@link Integer#MIN_VALUE} = none). */
    private int exemptX = Integer.MIN_VALUE;
    private int exemptZ = Integer.MIN_VALUE;
    /** Extra cost per block a move goes up or down (0 = none). */
    private double climbCost;

    public Walkability(VoxelView view, int maxDrop) {
        this(view, maxDrop, true, Long2DoubleMaps.EMPTY_MAP);
    }

    /**
     * @param penalties extra cost per packed node; {@link Double#POSITIVE_INFINITY} forbids the node. Must not be
     *                  mutated while a search uses it.
     */
    public Walkability(VoxelView view, int maxDrop, boolean allowJumps, Long2DoubleMap penalties) {
        this.view = view;
        this.maxDrop = Math.max(1, maxDrop);
        this.allowJumps = allowJumps;
        this.penalties = penalties;
    }

    public VoxelView view() {
        return view;
    }

    /**
     * Extra way cost per block up or down, so a flat detour (a curve on the same height) beats a stair up and down
     * again. Returns this.
     */
    public Walkability climbCost(double perBlock) {
        climbCost = perBlock;
        return this;
    }

    /** Column {@code (x, z)} (where the player stands) carries it whatever its floor is. Returns this. */
    public Walkability exempt(int x, int z) {
        exemptX = x;
        exemptZ = z;
        return this;
    }

    public int maxDrop() {
        return maxDrop;
    }

    public Long2DoubleMap penalties() {
        return penalties;
    }

    /**
     * Absolute feet height when the player can stand in this cell, otherwise {@link Double#NaN}. The floor must be
     * stone, deepslate or an ore (see the class comment).
     */
    public double standHeight(int x, int y, int z) {
        double feet = anyFloor(x, y, z);
        if (Double.isNaN(feet) || x == exemptX && z == exemptZ) {
            return feet;
        }
        // The block carrying the feet: a partial block in the cell itself (slab, carpet ...) or the one below.
        int own = view.cell(x, y, z);
        boolean ownCarries = Cell.hasCollision(own) && Cell.minY16(own) == 0 && Cell.maxY16(own) < 16
                && Math.abs(feet - (y + Cell.maxY16(own) / 16.0D)) < EPS;
        return view.mineFloor(x, ownCarries ? y : y - 1, z) ? feet : Double.NaN;
    }

    /** {@link #standHeight} without the floor rule: where a way starts (the player stands there already). */
    public double startHeight(int x, int y, int z) {
        return anyFloor(x, y, z);
    }

    private double anyFloor(int x, int y, int z) {
        int own = view.cell(x, y, z);
        int below = view.cell(x, y - 1, z);
        if (own == Cell.UNKNOWN || below == Cell.UNKNOWN || Cell.isLiquid(own) || Cell.isDanger(own)) {
            return Double.NaN;
        }
        double floor = Double.NaN;
        if (Cell.hasCollision(below) && Cell.maxY16(below) >= 16) {
            if (Cell.isDanger(below) || Cell.isLiquid(below)) {
                return Double.NaN;
            }
            floor = y + (Cell.maxY16(below) - 16) / 16.0D;
        }
        if (Cell.hasCollision(own)) {
            if (Cell.minY16(own) != 0 || Cell.maxY16(own) >= 16) {
                // Collision starting above the floor (top slab) or a full block: nothing to stand in.
                if (Double.isNaN(floor) || Cell.minY16(own) / 16.0D + y < floor + STEP_HEIGHT) {
                    return Double.NaN;
                }
            } else {
                double top = y + Cell.maxY16(own) / 16.0D;
                floor = Double.isNaN(floor) ? top : Math.max(floor, top);
            }
        }
        if (Double.isNaN(floor) || floor >= y + 1) {
            return Double.NaN;
        }
        return isClear(x, z, floor, floor + PLAYER_HEIGHT) ? floor : Double.NaN;
    }

    public boolean isStandable(int x, int y, int z) {
        return !Double.isNaN(standHeight(x, y, z));
    }

    /**
     * True when no collision, fluid, danger or unknown block of column {@code (x, z)} intersects the open height
     * interval {@code (from, to)}. Touching the interval ends (standing on a floor) is fine.
     */
    public boolean isClear(int x, int z, double from, double to) {
        int first = (int) Math.floor(from) - 1;
        int last = (int) Math.floor(to - EPS);
        for (int y = first; y <= last; y++) {
            int cell = view.cell(x, y, z);
            if (cell == Cell.UNKNOWN) {
                if (y >= Math.floor(from)) {
                    return false;
                }
                continue;
            }
            if (y >= Math.floor(from) && (Cell.isLiquid(cell) || Cell.isDanger(cell))) {
                return false;
            }
            if (!Cell.hasCollision(cell)) {
                continue;
            }
            double lo = y + Cell.minY16(cell) / 16.0D;
            double hi = y + Cell.maxY16(cell) / 16.0D;
            if (hi > from + EPS && lo < to - EPS) {
                return false;
            }
        }
        return true;
    }

    /**
     * Enumerates the moves possible from a standable node. The cost is roughly "blocks walked" plus penalties for
     * jumps, drops, tight spaces and penalised nodes.
     */
    public void neighbours(int x, int y, int z, double feet, EdgeSink sink) {
        for (int[] dir : DIRECTIONS) {
            int dx = dir[0];
            int dz = dir[1];
            boolean diagonal = dx != 0 && dz != 0;
            int nx = x + dx;
            int nz = z + dz;

            // Same level, step / jump up, or drop down - the destination column decides.
            for (int dy = 1; dy >= -maxDrop - 1; dy--) {
                int ny = y + dy;
                double target = standHeight(nx, ny, nz);
                if (Double.isNaN(target)) {
                    continue;
                }
                double rise = target - feet;
                if (rise > JUMP_HEIGHT + EPS || -rise > maxDrop + EPS) {
                    break;
                }
                MoveType type;
                if (rise > STEP_HEIGHT + EPS) {
                    type = MoveType.JUMP;
                } else if (rise < -STEP_HEIGHT - EPS) {
                    type = MoveType.DROP;
                } else {
                    type = diagonal ? MoveType.DIAGONAL : MoveType.WALK;
                }
                if ((diagonal && type != MoveType.DIAGONAL) || (type == MoveType.JUMP && !allowJumps)) {
                    break;
                }
                double top = Math.max(feet, target) + PLAYER_HEIGHT;
                if (!isClear(x, z, feet, top) || !isClear(nx, nz, target, top)) {
                    break;
                }
                if (diagonal && (!isClear(nx, z, Math.max(feet, target), top) || !isClear(x, nz, Math.max(feet, target), top))) {
                    break;
                }
                double cost = cost(type, diagonal, rise, nx, nz, target);
                if (!penalties.isEmpty()) {
                    double penalty = penalties.get(Pos.pack(nx, ny, nz));
                    if (penalty == Double.POSITIVE_INFINITY) {
                        break;
                    }
                    cost += penalty;
                }
                sink.accept(nx, ny, nz, target, type, cost);
                break;
            }
        }
    }

    private double cost(MoveType type, boolean diagonal, double rise, int nx, int nz, double target) {
        double cost = diagonal ? SQRT2 : 1.0D;
        switch (type) {
            case JUMP -> cost += 1.5D;
            case DROP -> cost += 0.4D * -rise;
            default -> {
                if (rise > EPS) {
                    cost += 0.2D;
                }
            }
        }
        if (isTight(nx, nz, target)) {
            cost += 0.15D;
        }
        if (climbCost > 0.0D && Math.abs(rise) > EPS) {
            cost += climbCost * Math.abs(rise);
        }
        return cost;
    }

    /** A 1-wide spot: both sides blocked on one horizontal axis at body height. */
    public boolean isTight(int x, int z, double feet) {
        boolean xBlocked = !isClear(x + 1, z, feet + 0.1D, feet + 1.7D) && !isClear(x - 1, z, feet + 0.1D, feet + 1.7D);
        if (xBlocked) {
            return true;
        }
        return !isClear(x, z + 1, feet + 0.1D, feet + 1.7D) && !isClear(x, z - 1, feet + 0.1D, feet + 1.7D);
    }

    /**
     * Finds the node the player occupies: the cell containing the feet, or the first standable cell a few blocks
     * below (mid-jump / falling). {@link Long#MIN_VALUE} when none is known.
     */
    public long settle(double px, double py, double pz) {
        int x = (int) Math.floor(px);
        int z = (int) Math.floor(pz);
        int y = (int) Math.floor(py + 0.01D);
        // Where the player stands now always counts, whatever the floor (it has to be able to walk off it).
        for (int dy = 0; dy >= -maxDrop - 2; dy--) {
            if (!Double.isNaN(anyFloor(x, y + dy, z))) {
                return Pos.pack(x, y + dy, z);
            }
        }
        if (isStandable(x, y + 1, z)) {
            return Pos.pack(x, y + 1, z);
        }
        // Standing on an edge: the box overlaps a neighbour column that carries it.
        double fx = px - x;
        double fz = pz - z;
        int ox = fx < 0.3D ? -1 : fx > 0.7D ? 1 : 0;
        int oz = fz < 0.3D ? -1 : fz > 0.7D ? 1 : 0;
        for (int[] offset : new int[][]{{ox, 0}, {0, oz}, {ox, oz}}) {
            if (offset[0] == 0 && offset[1] == 0) {
                continue;
            }
            for (int dy = 0; dy >= -1; dy--) {
                if (isStandable(x + offset[0], y + dy, z + offset[1])) {
                    return Pos.pack(x + offset[0], y + dy, z + offset[1]);
                }
            }
        }
        return Long.MIN_VALUE;
    }
}
