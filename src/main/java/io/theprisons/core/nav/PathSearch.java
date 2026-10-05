package io.theprisons.core.nav;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/**
 * Graph searches over the implicit navigation graph defined by {@link Walkability}. Pure functions of immutable
 * snapshot data; they run on the worker thread.
 *
 * <ul>
 *     <li>{@link #findPath}: A* to the cheapest of several goal nodes. The heuristic
 *     {@code max(octile(dx, dz), 0.7·(|dy| - 1))} (minimum over the goals) never overestimates: every edge costs at
 *     least its horizontal octile length and height changes cost extra. When the node budget runs out, or the goal
 *     is cut off, the path to the explored node closest to the goal is returned as {@link Status#PARTIAL} (walk
 *     there, search again), like Baritone's segmented paths.</li>
 *     <li>{@link #flood}: bounded Dijkstra from the player. One run yields the true travel cost to every reachable
 *     node, which is what target scoring needs (hundreds of candidates, one search).</li>
 * </ul>
 */
public final class PathSearch {
    private static final double SQRT2 = Math.sqrt(2.0D);
    /** A partial path must bring the player at least this much closer (heuristic units) to be worth walking. */
    private static final double MIN_PARTIAL_GAIN = 4.0D;

    private PathSearch() {
    }

    public enum Status { FOUND, PARTIAL, NO_PATH, START_INVALID, LIMIT, CANCELLED }

    public record Result(Status status, @Nullable NavigationPath path, int expanded, String reason) {
        public boolean found() {
            return status == Status.FOUND && path != null;
        }

        /** A path that can be walked: complete, or partial towards the goal. */
        public boolean usable() {
            return (status == Status.FOUND || status == Status.PARTIAL) && path != null;
        }
    }

    static final class Node implements Comparable<Node> {
        final long pos;
        double feet;
        double g;
        double f;
        @Nullable Node parent;
        MoveType move = MoveType.START;
        boolean closed;

        Node(long pos) {
            this.pos = pos;
        }

        @Override
        public int compareTo(Node other) {
            int byF = Double.compare(f, other.f);
            return byF != 0 ? byF : Double.compare(other.g, g);
        }
    }

    public static Result findPath(Walkability walk, long start, LongCollection goals, int maxNodes, int maxRadius,
                                  long worldVersion, BooleanSupplier cancelled) {
        if (goals.isEmpty()) {
            return new Result(Status.NO_PATH, null, 0, "no goal nodes");
        }
        double startFeet = walk.startHeight(Pos.x(start), Pos.y(start), Pos.z(start));
        if (Double.isNaN(startFeet)) {
            return new Result(Status.START_INVALID, null, 0, "start is not standable");
        }
        LongSet goalSet = new LongOpenHashSet(goals);
        long[] goalArray = goalSet.toLongArray();

        Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        Node first = new Node(start);
        first.feet = startFeet;
        first.f = heuristic(start, goalArray);
        nodes.put(start, first);
        open.add(first);

        int sx = Pos.x(start);
        int sz = Pos.z(start);
        int expanded = 0;
        Node closest = first;
        double closestH = first.f;
        Node[] current = new Node[1];
        while (!open.isEmpty()) {
            Node node = open.poll();
            if (node.closed || nodes.get(node.pos) != node) {
                continue;
            }
            node.closed = true;
            if (goalSet.contains(node.pos)) {
                return new Result(Status.FOUND, rebuild(node, worldVersion), expanded, "");
            }
            double h = node.f - node.g;
            if (h < closestH) {
                closestH = h;
                closest = node;
            }
            if (++expanded > maxNodes) {
                return partial(first, closest, closestH, worldVersion, expanded, Status.LIMIT, "search limit of " + maxNodes + " nodes reached");
            }
            if ((expanded & 255) == 0 && cancelled.getAsBoolean()) {
                return new Result(Status.CANCELLED, null, expanded, "cancelled");
            }
            current[0] = node;
            walk.neighbours(Pos.x(node.pos), Pos.y(node.pos), Pos.z(node.pos), node.feet, (x, y, z, feet, type, cost) -> {
                if (Math.abs(x - sx) > maxRadius || Math.abs(z - sz) > maxRadius) {
                    return;
                }
                long key = Pos.pack(x, y, z);
                Node parent = current[0];
                double g = parent.g + cost;
                Node existing = nodes.get(key);
                if (existing != null && (existing.closed || existing.g <= g)) {
                    return;
                }
                // A node in the heap is never re-prioritised; its stale entry is skipped when polled.
                Node next = new Node(key);
                nodes.put(key, next);
                next.feet = feet;
                next.g = g;
                next.f = g + heuristic(key, goalArray);
                next.parent = parent;
                next.move = type;
                open.add(next);
            });
        }
        return partial(first, closest, closestH, worldVersion, expanded, Status.NO_PATH, "no walkable connection");
    }

    private static Result partial(Node first, Node closest, double closestH, long worldVersion, int expanded, Status fallback, String reason) {
        if (closest != first && first.f - closestH >= MIN_PARTIAL_GAIN) {
            return new Result(Status.PARTIAL, rebuild(closest, worldVersion), expanded, reason + " - walking closer");
        }
        return new Result(fallback, null, expanded, reason);
    }

    /** Result of a bounded Dijkstra flood. Immutable once returned. */
    public static final class Flood {
        /** Visitor for reached nodes. */
        public interface NodeVisitor {
            void visit(long pos, double cost, double feet);
        }

        private final Long2ObjectOpenHashMap<Node> nodes;
        private final long start;
        private final long worldVersion;
        private final boolean complete;
        private final int reached;

        private Flood(Long2ObjectOpenHashMap<Node> nodes, long start, long worldVersion, boolean complete, int reached) {
            this.nodes = nodes;
            this.start = start;
            this.worldVersion = worldVersion;
            this.complete = complete;
            this.reached = reached;
        }

        public long start() {
            return start;
        }

        public boolean reachable(long pos) {
            Node node = nodes.get(pos);
            return node != null && node.closed;
        }

        /** Path cost to the node, or {@link Double#POSITIVE_INFINITY} when unreachable. */
        public double cost(long pos) {
            Node node = nodes.get(pos);
            return node != null && node.closed ? node.g : Double.POSITIVE_INFINITY;
        }

        public double feet(long pos) {
            Node node = nodes.get(pos);
            return node == null ? Double.NaN : node.feet;
        }

        /** Number of reached (closed) nodes. */
        public int size() {
            return reached;
        }

        /** False when the node budget ran out before the whole radius was explored. */
        public boolean complete() {
            return complete;
        }

        public void forEachReached(NodeVisitor visitor) {
            for (Node node : nodes.values()) {
                if (node.closed) {
                    visitor.visit(node.pos, node.g, node.feet);
                }
            }
        }

        public @Nullable NavigationPath pathTo(long pos) {
            Node node = nodes.get(pos);
            return node == null || !node.closed ? null : rebuild(node, worldVersion);
        }
    }

    /**
     * @param maxCost stop expanding beyond this path cost ({@link Double#POSITIVE_INFINITY} for no limit)
     * @return {@code null} when the start is not standable or the job was cancelled
     */
    public static @Nullable Flood flood(Walkability walk, long start, int maxNodes, int maxRadius, double maxCost,
                                        long worldVersion, BooleanSupplier cancelled) {
        double startFeet = walk.startHeight(Pos.x(start), Pos.y(start), Pos.z(start));
        if (Double.isNaN(startFeet)) {
            return null;
        }
        Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        Node first = new Node(start);
        first.feet = startFeet;
        nodes.put(start, first);
        open.add(first);
        int sx = Pos.x(start);
        int sz = Pos.z(start);
        int expanded = 0;
        boolean complete = true;
        Node[] current = new Node[1];
        while (!open.isEmpty()) {
            Node node = open.poll();
            if (node.closed || nodes.get(node.pos) != node) {
                continue;
            }
            if (node.g > maxCost) {
                break;
            }
            if (expanded >= maxNodes) {
                complete = false;
                break;
            }
            node.closed = true;
            expanded++;
            if ((expanded & 255) == 0 && cancelled.getAsBoolean()) {
                return null;
            }
            current[0] = node;
            walk.neighbours(Pos.x(node.pos), Pos.y(node.pos), Pos.z(node.pos), node.feet, (x, y, z, feet, type, cost) -> {
                if (Math.abs(x - sx) > maxRadius || Math.abs(z - sz) > maxRadius) {
                    return;
                }
                long key = Pos.pack(x, y, z);
                Node parent = current[0];
                double g = parent.g + cost;
                Node existing = nodes.get(key);
                if (existing != null && (existing.closed || existing.g <= g)) {
                    return;
                }
                Node next = new Node(key);
                nodes.put(key, next);
                next.feet = feet;
                next.g = g;
                next.f = g;
                next.parent = parent;
                next.move = type;
                open.add(next);
            });
        }
        return new Flood(nodes, start, worldVersion, complete, expanded);
    }

    static double heuristic(long from, long[] goals) {
        double best = Double.POSITIVE_INFINITY;
        int fx = Pos.x(from);
        int fy = Pos.y(from);
        int fz = Pos.z(from);
        for (long goal : goals) {
            int dx = Math.abs(fx - Pos.x(goal));
            int dz = Math.abs(fz - Pos.z(goal));
            int dy = Math.abs(fy - Pos.y(goal));
            double octile = Math.max(dx, dz) + (SQRT2 - 1.0D) * Math.min(dx, dz);
            best = Math.min(best, Math.max(octile, 0.7D * Math.max(0, dy - 1)));
        }
        return best;
    }

    private static NavigationPath rebuild(Node end, long worldVersion) {
        List<Node> chain = new ArrayList<>();
        for (Node node = end; node != null; node = node.parent) {
            chain.add(node);
        }
        Collections.reverse(chain);
        long[] positions = new long[chain.size()];
        double[] feet = new double[chain.size()];
        MoveType[] moves = new MoveType[chain.size()];
        for (int i = 0; i < chain.size(); i++) {
            Node node = chain.get(i);
            positions[i] = node.pos;
            feet[i] = node.feet;
            moves[i] = i == 0 ? MoveType.START : node.move;
        }
        return new NavigationPath(positions, feet, moves, end.g, worldVersion);
    }
}
