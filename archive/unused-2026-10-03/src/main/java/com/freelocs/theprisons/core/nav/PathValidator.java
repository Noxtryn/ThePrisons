package com.freelocs.theprisons.core.nav;

import org.jspecify.annotations.Nullable;

/**
 * Re-checks a planned path against current block data. Cached or older plans are never trusted blindly: every
 * waypoint in the window must still be standable at the planned height and the move into it (ceiling, landing,
 * step height) must still exist.
 */
public final class PathValidator {
    private PathValidator() {
    }

    /**
     * @param index  the path index the player is at
     * @param window how many waypoints ahead to check
     * @return a human readable problem, or {@code null} when the window is still walkable
     */
    public static @Nullable String firstProblem(NavigationPath path, Walkability walk, int index, int window) {
        for (int j = Math.max(1, index + 1); j <= Math.min(path.size() - 1, index + window); j++) {
            long node = path.nodes()[j];
            int x = Pos.x(node);
            int y = Pos.y(node);
            int z = Pos.z(node);
            if (walk.view().cell(x, y, z) == Cell.UNKNOWN || walk.view().cell(x, y - 1, z) == Cell.UNKNOWN) {
                return "path enters unloaded / unknown blocks at " + Pos.toString(node);
            }
            double feet = walk.standHeight(x, y, z);
            if (Double.isNaN(feet) || Math.abs(feet - path.feet()[j]) > 0.07D) {
                return "path blocked at " + Pos.toString(node);
            }
            long prev = path.nodes()[j - 1];
            double prevFeet = walk.standHeight(Pos.x(prev), Pos.y(prev), Pos.z(prev));
            if (Double.isNaN(prevFeet)) {
                // The previous node may be where the player is standing mid-step; only the destination matters.
                continue;
            }
            boolean[] found = new boolean[1];
            walk.neighbours(Pos.x(prev), Pos.y(prev), Pos.z(prev), prevFeet, (nx, ny, nz, nFeet, type, cost) -> {
                if (nx == x && ny == y && nz == z) {
                    found[0] = true;
                }
            });
            if (!found[0]) {
                return "move to " + Pos.toString(node) + " is no longer possible";
            }
        }
        return null;
    }
}
