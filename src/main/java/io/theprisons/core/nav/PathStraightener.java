package io.theprisons.core.nav;

/**
 * "String pulling" for walking: grid paths are made of 8-direction steps, so a path towards a target at 20° is a
 * staircase of straight and diagonal steps. Walking it literally zig-zags. For every node this computes the farthest
 * later node that can be walked to in a <b>straight line</b>: the corridor of the player's width (0.6) along the line
 * must be walkable at the same height (steps up to the vanilla step height are fine, no jumps / drops in between).
 * The follower then heads straight for that node instead of the next grid step.
 *
 * <p>Pure function; runs on the worker together with the search. Cost: O(nodes × {@value #MAX_SPAN} × samples).
 */
public final class PathStraightener {
    /** Farthest look-ahead in nodes. */
    public static final int MAX_SPAN = 24;
    private static final double SAMPLE = 0.25D;
    private static final double HALF_WIDTH = 0.3D;

    private PathStraightener() {
    }

    public static NavigationPath apply(NavigationPath path, Walkability walk) {
        return path.withStraight(farthest(path, walk));
    }

    public static int[] farthest(NavigationPath path, Walkability walk) {
        int size = path.size();
        int[] farthest = new int[size];
        for (int i = 0; i < size; i++) {
            int best = Math.min(size - 1, i + 1);
            for (int j = i + 2; j < Math.min(size, i + MAX_SPAN + 1); j++) {
                MoveType move = path.moves()[j];
                if (move == MoveType.JUMP || move == MoveType.DROP) {
                    break;
                }
                if (!corridorClear(path, i, j, walk)) {
                    break;
                }
                best = j;
            }
            farthest[i] = best;
        }
        return farthest;
    }

    /** Straight corridor from node i to node j: every sample (centre and both shoulders) stands on the same level. */
    static boolean corridorClear(NavigationPath path, int i, int j, Walkability walk) {
        double x0 = path.x(i);
        double z0 = path.z(i);
        double x1 = path.x(j);
        double z1 = path.z(j);
        double dx = x1 - x0;
        double dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6D) {
            return true;
        }
        double nx = -dz / length * HALF_WIDTH;
        double nz = dx / length * HALF_WIDTH;
        double lowFeet = Math.min(path.feet()[i], path.feet()[j]);
        double highFeet = Math.max(path.feet()[i], path.feet()[j]);
        int steps = (int) Math.ceil(length / SAMPLE);
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double cx = x0 + dx * t;
            double cz = z0 + dz * t;
            for (int side = -1; side <= 1; side++) {
                if (!standsOnLevel(walk, cx + nx * side, cz + nz * side, lowFeet, highFeet)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Some cell of the column under (x, z) carries the player within the height range of the segment. */
    private static boolean standsOnLevel(Walkability walk, double x, double z, double lowFeet, double highFeet) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int from = (int) Math.floor(lowFeet);
        int to = (int) Math.floor(highFeet);
        for (int y = from; y <= to; y++) {
            double feet = walk.standHeight(bx, y, bz);
            if (!Double.isNaN(feet) && feet >= lowFeet - Walkability.STEP_HEIGHT && feet <= highFeet + Walkability.STEP_HEIGHT) {
                return true;
            }
        }
        return false;
    }
}
