package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.nav.Pos;
import com.freelocs.theprisons.core.nav.Walkability;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.List;

/**
 * Compression of a route's middle line (pure math, no I/O, no threads).
 *
 * <ul>
 *     <li><b>Compress:</b> Douglas-Peucker on the planner's nodes - a node is kept only where the line bends more than
 *     {@value #SIDE_TOLERANCE} blocks sideways or {@value #HEIGHT_TOLERANCE} block in height from the straight line
 *     between the kept neighbours. A straight tunnel section is just its start and end; only bends (and the top / foot of
 *     a stair) add a waypoint. A 300 node route usually shrinks to 5-15 waypoints.</li>
 *     <li><b>Width:</b> the tunnel's mean width along the route ({@link OrePlanner#tunnelWidth} at every
 *     {@value #WIDTH_STEP}th node), stored as a radius - the corridor the line stands for.</li>
 *     <li><b>Expand:</b> back to one node per block by stepping along each segment (x / z by the longer axis, y linear),
 *     for following the route and for the planner's preference.</li>
 * </ul>
 */
public final class RoutePath {
    static final double SIDE_TOLERANCE = 1.5D;
    static final double HEIGHT_TOLERANCE = 1.0D;
    static final int WIDTH_STEP = 8;
    /** Width used where no tunnel walls are found (a cave): radius {@value}. */
    static final double OPEN_RADIUS = 8.0D;

    private RoutePath() {
    }

    /** The waypoints {x, y, z} of a node line (first and last always kept). */
    public static int[][] compress(long[] nodes) {
        if (nodes.length == 0) {
            return new int[0][];
        }
        boolean[] keep = new boolean[nodes.length];
        keep[0] = true;
        keep[nodes.length - 1] = true;
        // Iterative (an explicit stack): long routes never overflow the call stack.
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{0, nodes.length - 1});
        while (!stack.isEmpty()) {
            int[] span = stack.pop();
            int from = span[0];
            int to = span[1];
            if (to - from < 2) {
                continue;
            }
            int far = farthest(nodes, from, to);
            if (far >= 0) {
                keep[far] = true;
                stack.push(new int[]{from, far});
                stack.push(new int[]{far, to});
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < nodes.length; i++) {
            if (keep[i]) {
                out.add(new int[]{Pos.x(nodes[i]), Pos.y(nodes[i]), Pos.z(nodes[i])});
            }
        }
        return out.toArray(new int[0][]);
    }

    /** The node between {@code from} and {@code to} furthest off their straight line beyond the tolerances; -1 = none. */
    private static int farthest(long[] nodes, int from, int to) {
        double ax = Pos.x(nodes[from]);
        double ay = Pos.y(nodes[from]);
        double az = Pos.z(nodes[from]);
        double bx = Pos.x(nodes[to]);
        double by = Pos.y(nodes[to]);
        double bz = Pos.z(nodes[to]);
        double len = Math.hypot(bx - ax, bz - az);
        int far = -1;
        double worst = 1.0D;
        for (int i = from + 1; i < to; i++) {
            double px = Pos.x(nodes[i]);
            double pz = Pos.z(nodes[i]);
            double side = len < 1.0E-6D ? Math.hypot(px - ax, pz - az)
                    : Math.abs((bx - ax) * (az - pz) - (ax - px) * (bz - az)) / len;
            // Height off the line, by how far along it the node is.
            double t = len < 1.0E-6D ? 0.0D : Math.max(0.0D, Math.min(1.0D, ((px - ax) * (bx - ax) + (pz - az) * (bz - az)) / (len * len)));
            double height = Math.abs(Pos.y(nodes[i]) - (ay + (by - ay) * t));
            // Measured in tolerances: > 1 = must be kept.
            double off = Math.max(side / SIDE_TOLERANCE, height / HEIGHT_TOLERANCE);
            if (off > worst) {
                worst = off;
                far = i;
            }
        }
        return far;
    }

    /** One node per block along the waypoints (the first waypoint first, the last one last). */
    public static long[] expand(int[][] waypoints) {
        LongArrayList out = new LongArrayList();
        if (waypoints.length == 0) {
            return new long[0];
        }
        out.add(Pos.pack(waypoints[0][0], waypoints[0][1], waypoints[0][2]));
        for (int k = 1; k < waypoints.length; k++) {
            int[] a = waypoints[k - 1];
            int[] b = waypoints[k];
            int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[2] - a[2]));
            for (int s = 1; s <= steps; s++) {
                double t = s / (double) steps;
                out.add(Pos.pack((int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t),
                        (int) Math.round(a[2] + (b[2] - a[2]) * t)));
            }
            if (steps == 0 && a[1] != b[1]) {
                out.add(Pos.pack(b[0], b[1], b[2]));
            }
        }
        return out.toLongArray();
    }

    /** The tunnel's mean half width along the nodes ({@link #OPEN_RADIUS} where no walls are found). */
    public static double widthRadius(Walkability walk, long[] nodes) {
        double sum = 0.0D;
        int n = 0;
        for (int i = 0; i < nodes.length; i += WIDTH_STEP) {
            int width = OrePlanner.tunnelWidth(walk, Pos.x(nodes[i]), Pos.y(nodes[i]), Pos.z(nodes[i]));
            sum += width == Integer.MAX_VALUE ? OPEN_RADIUS : width / 2.0D;
            n++;
        }
        return n == 0 ? OPEN_RADIUS : sum / n;
    }

    /** Length (blocks, horizontal plus vertical) of a waypoint line. */
    public static double length(int[][] waypoints) {
        double len = 0.0D;
        for (int k = 1; k < waypoints.length; k++) {
            len += Math.hypot(waypoints[k][0] - waypoints[k - 1][0], waypoints[k][2] - waypoints[k - 1][2])
                    + Math.abs(waypoints[k][1] - waypoints[k - 1][1]);
        }
        return len;
    }
}
