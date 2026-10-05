package com.freelocs.theprisons.modules.mining.ore.route;

import java.util.List;

/**
 * Where a new waypoint goes in a route, and which waypoint the player looks at. Waypoints are named by their position
 * in the route (1, 2, 3, ...), so inserting one renumbers the ones after it.
 */
public final class WaypointMath {
    /** A waypoint counts as looked at when the view passes this close (blocks) to its centre. */
    static final double LOOK_TOLERANCE = 1.0D;

    private WaypointMath() {
    }

    /**
     * The index the new point gets: between the two waypoints where it makes the smallest detour, before the first
     * one or after the last one when it lies beyond the start or the end of the route (in the route's direction).
     */
    public static int insertionIndex(List<int[]> route, int[] point) {
        if (route.isEmpty()) {
            return 0;
        }
        int best = route.size();
        double bestCost = dist(route.get(route.size() - 1), point);
        double before = dist(point, route.get(0));
        if (before < bestCost) {
            best = 0;
            bestCost = before;
        }
        for (int i = 0; i + 1 < route.size(); i++) {
            int[] a = route.get(i);
            int[] b = route.get(i + 1);
            double detour = dist(a, point) + dist(point, b) - dist(a, b);
            if (detour <= bestCost) {
                best = i + 1;
                bestCost = detour;
            }
        }
        return best;
    }

    /**
     * The waypoint the view ray (eye position, unit direction) points at: the one nearest to the ray within
     * {@value #LOOK_TOLERANCE} blocks, closer ones first; -1 when none.
     */
    public static int lookedAt(List<int[]> route, double ex, double ey, double ez, double dx, double dy, double dz, double reach) {
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            int[] p = route.get(i);
            // Centre of the marker: the waypoint block (feet) plus half a block, i.e. the outline and the post above it.
            double cx = p[0] + 0.5D - ex;
            double cy = p[1] + 0.5D - ey;
            double cz = p[2] + 0.5D - ez;
            double t = cx * dx + cy * dy + cz * dz;
            if (t < 0.0D || t > reach) {
                continue;
            }
            double off = Math.sqrt(Math.max(0.0D, cx * cx + cy * cy + cz * cz - t * t));
            if (off <= LOOK_TOLERANCE && off + t * 0.01D < bestScore) {
                best = i;
                bestScore = off + t * 0.01D;
            }
        }
        return best;
    }

    private static double dist(int[] a, int[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
