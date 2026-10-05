package com.freelocs.theprisons.core.nav;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered waypoints from the start node to a goal node.
 *
 * @param nodes        packed node cells
 * @param feet         absolute feet height per node
 * @param moves        how each node is entered ({@link MoveType#START} for the first)
 * @param cost         total edge cost
 * @param storeVersion world version the path was computed against
 * @param straight     per node the farthest later node reachable in a straight line ({@link PathStraightener}),
 *                     or {@code null} when not computed
 */
public record NavigationPath(long[] nodes, double[] feet, MoveType[] moves, double cost, long storeVersion,
                             int @org.jspecify.annotations.Nullable [] straight) {
    public NavigationPath(long[] nodes, double[] feet, MoveType[] moves, double cost, long storeVersion) {
        this(nodes, feet, moves, cost, storeVersion, null);
    }

    public NavigationPath withStraight(int[] farthest) {
        return new NavigationPath(nodes, feet, moves, cost, storeVersion, farthest);
    }

    public int size() {
        return nodes.length;
    }

    public long goal() {
        return nodes[nodes.length - 1];
    }

    public double x(int i) {
        return Pos.x(nodes[i]) + 0.5D;
    }

    public double z(int i) {
        return Pos.z(nodes[i]) + 0.5D;
    }

    /** A straight run of waypoints sharing one direction and level. */
    public record Segment(int from, int to, int dx, int dz, boolean level) {
        public int length() {
            return to - from;
        }
    }

    /**
     * Compresses the path into straight runs. A long straight tunnel becomes one segment; every turn, level
     * change or jump starts a new one.
     */
    public List<Segment> segments() {
        List<Segment> segments = new ArrayList<>();
        if (nodes.length < 2) {
            return segments;
        }
        int start = 0;
        int dx = Integer.signum(Pos.x(nodes[1]) - Pos.x(nodes[0]));
        int dz = Integer.signum(Pos.z(nodes[1]) - Pos.z(nodes[0]));
        boolean level = Math.abs(feet[1] - feet[0]) < 0.01D && moves[1] != MoveType.JUMP && moves[1] != MoveType.DROP;
        for (int i = 2; i < nodes.length; i++) {
            int ndx = Integer.signum(Pos.x(nodes[i]) - Pos.x(nodes[i - 1]));
            int ndz = Integer.signum(Pos.z(nodes[i]) - Pos.z(nodes[i - 1]));
            boolean nLevel = Math.abs(feet[i] - feet[i - 1]) < 0.01D && moves[i] != MoveType.JUMP && moves[i] != MoveType.DROP;
            if (ndx != dx || ndz != dz || nLevel != level || !nLevel) {
                segments.add(new Segment(start, i - 1, dx, dz, level));
                start = i - 1;
                dx = ndx;
                dz = ndz;
                level = nLevel;
            }
        }
        segments.add(new Segment(start, nodes.length - 1, dx, dz, level));
        return segments;
    }

    /** Number of direction changes along the path (0 for a straight line). */
    public int turns() {
        return Math.max(0, segments().size() - 1);
    }
}
