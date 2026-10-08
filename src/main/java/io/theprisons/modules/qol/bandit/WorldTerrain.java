package io.theprisons.modules.qol.bandit;

import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.VoxelView;
import io.theprisons.core.nav.Walkability;
import io.theprisons.modules.qol.bandit.combat.Terrain;

/**
 * {@link Terrain} over the live world: short probes on the ground using the same walkability rules as the path finder (floor, headroom,
 * step height, drops). Bounded by construction: a probe is at most a few blocks long and the callers make a handful per tick. Client
 * thread only (the view reads the live client world).
 */
public final class WorldTerrain implements Terrain {
    /** A step of 0.25 blocks: columns change at most every 4th sample. */
    private static final double STEP = 0.25D;
    private static final double MAX_UP = 1.1D;
    /** Up to a slab (0.5) a player walks on; above it the jump is needed (a jump reaches about 1.25). */
    private static final double STEP_WITHOUT_JUMP = 0.6D;
    private static final double MAX_DROP = 2.0D;

    private final Walkability walk;
    private final VoxelView view;

    public WorldTerrain(VoxelView live) {
        this.view = new OpenView(live);
        this.walk = new Walkability(view, 3);
    }

    @Override
    public Ray cast(double x, double y, double z, double dirX, double dirZ, double maxDist) {
        double len = Math.hypot(dirX, dirZ);
        if (len < 1e-9 || maxDist <= 0.0D) {
            return new Ray(0.0D, Stop.CLEAR, 0.0D);
        }
        double ux = dirX / len;
        double uz = dirZ / len;
        int startX = (int) Math.floor(x);
        int startZ = (int) Math.floor(z);
        double feet = y;
        double jumpAt = -1.0D;
        int lastX = startX;
        int lastZ = startZ;
        double walked = 0.0D;
        while (walked < maxDist - 1e-9) {
            double next = Math.min(maxDist, walked + STEP);
            int bx = (int) Math.floor(x + ux * next);
            int bz = (int) Math.floor(z + uz * next);
            if (bx != lastX || bz != lastZ) {
                double ground = groundAt(bx, bz, feet);
                if (Double.isNaN(ground)) {
                    return new Ray(walked, whyBlocked(bx, bz, feet), feet - y, jumpAt);
                }
                double change = ground - feet;
                if (change > MAX_UP) {
                    return new Ray(walked, Stop.STEP, feet - y, jumpAt);
                }
                if (change < -MAX_DROP) {
                    return new Ray(walked, Stop.DROP, feet - y, jumpAt);
                }
                if (change > STEP_WITHOUT_JUMP && jumpAt < 0.0D) {
                    jumpAt = walked; // a real step up: the player has to jump here (the landing floor was found)
                }
                feet = ground;
                lastX = bx;
                lastZ = bz;
            }
            walked = next;
        }
        return new Ray(maxDist, Stop.CLEAR, feet - y, jumpAt);
    }

    /** The feet height at which a player can stand in column (bx, bz) near {@code feet}: NaN when there is none. */
    private double groundAt(int bx, int bz, double feet) {
        int top = (int) Math.floor(feet) + 1;
        for (int by = top; by >= top - 4; by--) {
            double h = walk.standHeight(bx, by, bz);
            if (!Double.isNaN(h)) {
                return h;
            }
        }
        return Double.NaN;
    }

    private Stop whyBlocked(int bx, int bz, double feet) {
        int by = (int) Math.floor(feet);
        boolean unknown = false;
        for (int dy = -1; dy <= 2; dy++) {
            int cell = view.cell(bx, by + dy, bz);
            if (cell == Cell.UNKNOWN) {
                unknown = true;
            } else if (Cell.isLiquid(cell) || Cell.isDanger(cell)) {
                return Stop.HAZARD;
            }
        }
        if (unknown) {
            return Stop.UNKNOWN;
        }
        if (!walk.isClear(bx, bz, feet + 0.05D, feet + 1.0D)) {
            return Stop.WALL;
        }
        if (!walk.isClear(bx, bz, feet + 1.0D, feet + 1.8D)) {
            return Stop.HEADROOM;
        }
        return Stop.DROP;
    }
}
