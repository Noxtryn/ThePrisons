package io.theprisons.modules.mining.ore;

import io.theprisons.core.nav.Pos;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The learned-route cache in RAM - the HUD and the planner only ever read this, never the disk ({@link RouteIo} writes
 * it in the background, {@link RouteCodec} is the file format, {@link RoutePath} the compression).
 *
 * <p><b>What is kept:</b> planned / tunnel routes walked to their end inside the guarded area the whole way, as
 * compressed waypoints plus the tunnel's width radius. The same route again (start and end within {@value #SAME} blocks)
 * replaces the old entry: runs + 1, ores and blocks averaged.</p>
 *
 * <p><b>Thread safety:</b> routes are immutable; the map is a {@link ConcurrentHashMap} changed only on the client
 * thread ({@link #record}, {@link #replaceAll}); a save takes {@link #snapshot()} (the current immutable routes) on the
 * client thread, so the writer thread never sees a route half changed, and a later change never alters a snapshot
 * already queued. The spatial index is rebuilt on change and published as one immutable object.</p>
 *
 * <p><b>Lookup:</b> every route has an axis-aligned bounding box (its waypoints, grown by its width radius plus
 * {@value #NEAR} blocks, ±{@value #BOX_HEIGHT} high). The index maps {@value #GRID}-block columns to the routes whose
 * box touches them; a query looks at the player's column only, then the exact box range check, and only then the path
 * itself (the expanded line). Hundreds of routes cost a map lookup and a handful of comparisons.</p>
 *
 * <p><b>Reuse:</b> a route is usable when it gave {@value #MIN_YIELD} ores per block or more, rested
 * {@value #REST_MS} ms (ores respawn) and the caller says it is guarded. Standing on its line (a node within
 * {@value #NEAR} blocks, ≥{@value #MIN_REST_NODES} nodes ahead) its rest is taken at once; elsewhere the planner prefers
 * ways over the lines ({@link #preferredColumns}).</p>
 */
public final class RouteMemory {
    static final double SAME = 4.0D;
    static final double NEAR = 6.0D;
    static final int BOX_HEIGHT = 4;
    static final int GRID = 32;
    static final int MIN_REST_NODES = 8;
    static final double MIN_YIELD = 0.3D;
    static final long REST_MS = 10L * 60_000L;
    static final int MAX_ROUTES = 500;

    /** One learned route (immutable). */
    public static final class Route {
        public final int id;
        public final String kind;
        public final String mineZone;
        public final int[][] waypoints;
        public final double widthRadius;
        public final int ores;
        public final double blocks;
        public final int runs;
        public final long lastRunMs;
        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;
        /** The expanded line, made on first use (deterministic, so a race only computes it twice). */
        private volatile long @Nullable [] expanded;

        public Route(int id, String kind, String mineZone, int[][] waypoints, double widthRadius, int ores, double blocks,
                     int runs, long lastRunMs) {
            this.id = id;
            this.kind = kind;
            this.mineZone = mineZone;
            this.waypoints = waypoints;
            this.widthRadius = widthRadius;
            this.ores = ores;
            this.blocks = blocks;
            this.runs = runs;
            this.lastRunMs = lastRunMs;
            int grow = (int) Math.ceil(widthRadius + NEAR);
            int x0 = Integer.MAX_VALUE;
            int y0 = Integer.MAX_VALUE;
            int z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE;
            int y1 = Integer.MIN_VALUE;
            int z1 = Integer.MIN_VALUE;
            for (int[] w : waypoints) {
                x0 = Math.min(x0, w[0]);
                y0 = Math.min(y0, w[1]);
                z0 = Math.min(z0, w[2]);
                x1 = Math.max(x1, w[0]);
                y1 = Math.max(y1, w[1]);
                z1 = Math.max(z1, w[2]);
            }
            minX = x0 - grow;
            minY = y0 - BOX_HEIGHT;
            minZ = z0 - grow;
            maxX = x1 + grow;
            maxY = y1 + BOX_HEIGHT;
            maxZ = z1 + grow;
        }

        /** Ores per block walked. */
        public double yield() {
            return blocks <= 0.0D ? 0.0D : ores / blocks;
        }

        /** Ores per 100 blocks walked (the JSON's {@code efficiency_score}). */
        public double efficiency() {
            return this.yield() * 100.0D;
        }

        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX + 1 && y >= minY && y <= maxY + 1 && z >= minZ && z <= maxZ + 1;
        }

        public long[] nodes() {
            long[] e = expanded;
            if (e == null) {
                e = RoutePath.expand(waypoints);
                expanded = e;
            }
            return e;
        }

        public int[] start() {
            return waypoints[0];
        }

        public int[] end() {
            return waypoints[waypoints.length - 1];
        }
    }

    /** A route to take, from node {@code from} of its expanded line on. */
    public record Match(Route route, int from) {
        public long[] nodes() {
            long[] all = route.nodes();
            return java.util.Arrays.copyOfRange(all, from, all.length);
        }
    }

    private final Map<Integer, Route> routes = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile Long2ObjectOpenHashMap<Route[]> index = new Long2ObjectOpenHashMap<>();

    public int size() {
        return routes.size();
    }

    /** The current routes (immutable objects; the list is a copy - hand it to the writer thread as it is). */
    public List<Route> snapshot() {
        return List.copyOf(routes.values());
    }

    /** Client thread: the routes loaded from a file (replacing everything). */
    public void replaceAll(Collection<Route> loaded) {
        routes.clear();
        int max = 0;
        for (Route r : loaded) {
            routes.put(r.id, r);
            max = Math.max(max, r.id);
        }
        nextId.set(max + 1);
        reindex();
    }

    /** Client thread: a route walked to its end inside the guarded area. Returns the stored (new or merged) route. */
    public @Nullable Route record(String kind, String mineZone, long[] nodes, double widthRadius, int ores, double blocks,
                                  long nowMs) {
        if (nodes.length < 2) {
            return null;
        }
        int[][] waypoints = RoutePath.compress(nodes);
        int[] start = waypoints[0];
        int[] end = waypoints[waypoints.length - 1];
        for (Route old : candidates(start[0], start[2])) {
            if (dist(old.start(), start) <= SAME && dist(old.end(), end) <= SAME) {
                // The same route again: the newest line and width, ores / blocks averaged over the runs.
                Route merged = new Route(old.id, kind, mineZone, waypoints,
                        (old.widthRadius * old.runs + widthRadius) / (old.runs + 1),
                        (int) Math.round((old.ores * (double) old.runs + ores) / (old.runs + 1)),
                        (old.blocks * old.runs + blocks) / (old.runs + 1), old.runs + 1, nowMs);
                routes.put(merged.id, merged);
                reindex();
                return merged;
            }
        }
        Route route = new Route(nextId.getAndIncrement(), kind, mineZone, waypoints, widthRadius, ores, blocks, 1, nowMs);
        routes.put(route.id, route);
        if (routes.size() > MAX_ROUTES) {
            // Drop the poorest.
            Route poorest = null;
            for (Route r : routes.values()) {
                if (poorest == null || r.yield() * (1 + r.runs) < poorest.yield() * (1 + poorest.runs)) {
                    poorest = r;
                }
            }
            if (poorest != null) {
                routes.remove(poorest.id);
            }
        }
        reindex();
        return route;
    }

    /** The best route to take from here ({@code null} = none; see the class comment). */
    public @Nullable Match best(double x, double y, double z, long nowMs, Predicate<Route> guarded) {
        Match best = null;
        double bestScore = 0.0D;
        for (Route r : candidates((int) Math.floor(x), (int) Math.floor(z))) {
            // Box first (a few comparisons), the path itself only for routes around the player.
            if (!r.contains(x, y, z) || !usable(r, nowMs)) {
                continue;
            }
            double score = r.yield() * (1.0D + r.runs / 10.0D);
            if (score <= bestScore) {
                continue;
            }
            int from = nearest(r, x, y, z);
            if (from < 0 || r.nodes().length - from < MIN_REST_NODES || !guarded.test(r)) {
                continue;
            }
            bestScore = score;
            best = new Match(r, from);
        }
        return best;
    }

    /**
     * Columns ({@code Pos.pack(x, 0, z)}) on and 1 block beside the lines of usable routes whose box lies within
     * {@code radius} blocks of (x, z): the planner's ways over them cost less.
     */
    public LongOpenHashSet preferredColumns(double x, double z, int radius, long nowMs, Predicate<Route> guarded) {
        LongOpenHashSet columns = new LongOpenHashSet();
        for (Route r : routes.values()) {
            if (r.maxX < x - radius || r.minX > x + radius || r.maxZ < z - radius || r.minZ > z + radius
                    || !usable(r, nowMs) || !guarded.test(r)) {
                continue;
            }
            for (long n : r.nodes()) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        columns.add(Pos.pack(Pos.x(n) + dx, 0, Pos.z(n) + dz));
                    }
                }
            }
        }
        return columns;
    }

    private static boolean usable(Route r, long nowMs) {
        return r.yield() >= MIN_YIELD && nowMs - r.lastRunMs >= REST_MS && r.waypoints.length >= 2;
    }

    /** The routes whose box touches the grid column of (x, z). */
    private Route[] candidates(int x, int z) {
        Route[] found = index.get(cell(Math.floorDiv(x, GRID), Math.floorDiv(z, GRID)));
        return found == null ? new Route[0] : found;
    }

    private void reindex() {
        Long2ObjectOpenHashMap<List<Route>> cells = new Long2ObjectOpenHashMap<>();
        for (Route r : routes.values()) {
            for (int cx = Math.floorDiv(r.minX, GRID); cx <= Math.floorDiv(r.maxX, GRID); cx++) {
                for (int cz = Math.floorDiv(r.minZ, GRID); cz <= Math.floorDiv(r.maxZ, GRID); cz++) {
                    cells.computeIfAbsent(cell(cx, cz), k -> new ArrayList<>()).add(r);
                }
            }
        }
        Long2ObjectOpenHashMap<Route[]> next = new Long2ObjectOpenHashMap<>(cells.size());
        for (Long2ObjectOpenHashMap.Entry<List<Route>> e : cells.long2ObjectEntrySet()) {
            next.put(e.getLongKey(), e.getValue().toArray(new Route[0]));
        }
        index = next;
    }

    private static long cell(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** The expanded line's node nearest to the player within {@value #NEAR} blocks (and 3 high); -1 = none. */
    private static int nearest(Route r, double x, double y, double z) {
        long[] nodes = r.nodes();
        int best = -1;
        double bestD = NEAR;
        for (int i = 0; i < nodes.length; i++) {
            if (Math.abs(Pos.y(nodes[i]) - y) > 3.0D) {
                continue;
            }
            double d = Math.hypot(Pos.x(nodes[i]) + 0.5D - x, Pos.z(nodes[i]) + 0.5D - z);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static double dist(int[] a, int[] b) {
        return Math.hypot(a[0] - b[0], a[2] - b[2]) + Math.abs(a[1] - b[1]);
    }
}
