package io.theprisons.modules.mining.ore;

import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.NavigationPath;
import io.theprisons.core.nav.PathSearch;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.VoxelView;
import io.theprisons.core.nav.Walkability;
import it.unimi.dsi.fastutil.longs.AbstractLong2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.LongToDoubleFunction;
import java.util.function.Predicate;

/**
 * The long-range route planner (pure logic, worker thread). Works on the whole remembered world
 * ({@code WorldArchive}: stone / deepslate, ore and air only, so the ways follow from the blocks):
 *
 * <ol>
 *     <li>A flood over every walkable floor block up to {@code radius} blocks (guarded area and borders already
 *     forbidden by the {@link Walkability} costs).</li>
 *     <li>The reached floor is cut into {@value #CELL}x{@value #CELL}x{@value #CELL} cells; per cell the floor ores and
 *     the stone / deepslate floor are counted. Cells with at least {@value #POOR_SHARE} stone are poor and never a goal.</li>
 *     <li>The best cells (ore around them per way there) become candidate ways. Each way is rated by the ores the
 *     pickaxe mines along it (the 5 wide strip) per block walked, times "straight on first" (the less it turns away
 *     from the current direction the better) and the learned value of the area.</li>
 *     <li>From the end of the best way the next leg is chosen the same way (up to {@code legs} legs), so the route goes
 *     on over long distances from one rich spot to the next.</li>
 *     <li>The route still being walked competes as well: a new one must be {@value #KEEP_BONUS}x better, so the route
 *     only changes for a clearly better one (and then often only a little - another lane of the same way).</li>
 * </ol>
 */
public final class OrePlanner {
    public static final int CELL = 8;
    private static final int CELL_SHIFT = 3;
    /** A cell whose floor is this share stone / deepslate (or more) is poor. */
    public static final double POOR_SHARE = 0.75D;
    private static final int POOR_MIN_FLOOR = 8;
    private static final int MIN_CELL_ORE = 2;
    static final double KEEP_BONUS = 1.25D;
    private static final int CANDIDATES = 16;
    private static final int LEG_CANDIDATES = 4;
    private static final int LEG_NODES = 60_000;
    /**
     * A route's first goal lies at least this far (way cost) away: the ore close by is mined by the steering on its
     * straight way; a route to it would only end after a few blocks and turn the player somewhere else.
     */
    static final double MIN_GOAL = 10.0D;
    /** Way cost added before dividing, so a short way to a few ores does not beat a long way through many. */
    private static final double COST_OFFSET = 12.0D;
    /** The way's first this-many nodes give its start direction. */
    private static final int HEADING_NODES = 6;
    /** A "go another direction" request: the way may not start within this many degrees of the old heading. */
    static final double AVOID_DEGREES = 60.0D;
    static final double AVOID_FACTOR = 0.3D;
    static final double AVOID_RADIUS = 10.0D;
    /** Path inertia: a way back (more than this far off the heading) needs {@code 1 / BACK_FACTOR} x the score. */
    static final double BACK_DEGREES = 135.0D;
    static final double BACK_FACTOR = 0.5D;
    /** Way cost per block up or down: flat, curvy ways are preferred, stairs only where no flat way leads there. */
    public static final double CLIMB_COST = 1.5D;

    public record Params(int radius, int maxNodes, int legs, int mineHalf) {
        public static final Params DEFAULT = new Params(512, 250_000, 3, TunnelSteer.MINE_HALF);
    }

    /** Planned after "too much stone here": somewhere else, not on in the old direction. */
    public record Avoid(double x, double z, float heading) {
    }

    /**
     * @param nodes     the whole way (feet blocks)
     * @param waypoints its corners {x, y, z}, first = start
     * @param ores      floor ores the pickaxe meets along it
     * @param kept      the route being walked is still the best one
     */
    public record Plan(long[] nodes, int[][] waypoints, int ores, double cost, double score, boolean kept, String reason) {
        static Plan none(String reason) {
            return new Plan(new long[0], new int[0][], 0, 0.0D, 0.0D, false, reason);
        }

        public boolean found() {
            return waypoints.length >= 2 || kept;
        }
    }

    private OrePlanner() {
    }

    /**
     * @param keep the rest of the route being walked ({@code null} = none)
     */
    public static Plan plan(VoxelView view, IntPredicate isTarget, Walkability walk, long start, float heading,
                            long @Nullable [] keep, @Nullable Avoid avoid, LongToDoubleFunction zoneFactor, Params params,
                            BooleanSupplier cancelled) {
        return plan(view, isTarget, walk, start, heading, keep, avoid, zoneFactor, params, ANY_WAY, cancelled);
    }

    /** Every way may be walked (no guarded area). */
    public static final Predicate<long[]> ANY_WAY = nodes -> true;

    /**
     * @param allowed whether a way (feet blocks) may be walked - the guarded area's excursion rule: no more unguarded
     *                blocks in a row than allowed, and not ending outside ({@link GuardArea#longestOutside})
     */
    public static Plan plan(VoxelView view, IntPredicate isTarget, Walkability walk, long start, float heading,
                            long @Nullable [] keep, @Nullable Avoid avoid, LongToDoubleFunction zoneFactor, Params params,
                            Predicate<long[]> allowed, BooleanSupplier cancelled) {
        PathSearch.Flood flood = PathSearch.flood(walk, start, params.maxNodes(), params.radius(), Double.POSITIVE_INFINITY, 0L, cancelled);
        if (flood == null) {
            return Plan.none("not standing on known ground");
        }
        Cells cells = cells(view, isTarget, flood, start);
        Long2IntOpenHashMap cellOre = cells.ore;
        Long2IntOpenHashMap cellFloor = cells.floor;
        Long2LongOpenHashMap cellNode = cells.node;
        Long2DoubleOpenHashMap cellCost = cells.cost;
        if (cancelled.getAsBoolean()) {
            return Plan.none("cancelled");
        }

        // Candidate goals: ore around the cell per way there.
        List<double[]> candidates = new ArrayList<>();
        int sx = Pos.x(start);
        int sz = Pos.z(start);
        for (long cell : cellNode.keySet()) {
            int ore = cellOre.get(cell);
            if (ore < MIN_CELL_ORE || poor(ore, cellFloor.get(cell))) {
                continue;
            }
            long node = cellNode.get(cell);
            if (cellCost.get(cell) < MIN_GOAL) {
                continue;
            }
            if (avoid != null && Math.hypot(Pos.x(node) + 0.5D - avoid.x(), Pos.z(node) + 0.5D - avoid.z()) < AVOID_RADIUS) {
                continue;
            }
            double density = density(cellOre, cell);
            double pre = density * zoneFactor.applyAsDouble(LanePlanner.zoneOf(node)) / (cellCost.get(cell) + COST_OFFSET);
            if (avoid != null && Math.abs(turn(avoid.heading(), sx, sz, Pos.x(node), Pos.z(node))) < AVOID_DEGREES) {
                pre *= AVOID_FACTOR;
            }
            candidates.add(new double[]{pre, cell});
        }
        candidates.sort((a, b) -> Double.compare(b[0], a[0]));

        Way best = null;
        for (int i = 0; i < Math.min(CANDIDATES, candidates.size()); i++) {
            long cell = (long) candidates.get(i)[1];
            NavigationPath path = flood.pathTo(cellNode.get(cell));
            if (path == null || path.size() < 2 || !allowed.test(path.nodes())) {
                continue;
            }
            Way way = rate(view, isTarget, path.nodes(), path.cost(), density(cellOre, cell), heading, avoid, zoneFactor,
                    params, new LongOpenHashSet());
            if (best == null || way.score > best.score) {
                best = way;
            }
        }

        Way kept = keep == null || keep.length < 2 ? null : rateKept(view, isTarget, walk, keep, heading,
                density(cellOre, cellOf(Pos.x(keep[keep.length - 1]), Pos.y(keep[keep.length - 1]), Pos.z(keep[keep.length - 1]))),
                zoneFactor, params);
        if (best == null) {
            if (kept != null && avoid == null) {
                return new Plan(keep, waypoints(keep), kept.ores, kept.cost, kept.score, true, "route still the best");
            }
            return Plan.none(candidates.isEmpty() ? "no ore known in reach" : "no way to the ore");
        }

        Legs extended = extend(view, isTarget, walk, best, cells, zoneFactor, params, allowed, cancelled);
        long[] all = extended.nodes();
        double cost = extended.cost();
        int legs = extended.legs();
        // The whole new route against the whole rest of the old one, rated alike.
        long last = all[all.length - 1];
        Way route = rate(view, isTarget, all, cost, density(cellOre, cellOf(Pos.x(last), Pos.y(last), Pos.z(last))), heading,
                avoid, zoneFactor, params, new LongOpenHashSet());
        if (kept != null && avoid == null && kept.score * KEEP_BONUS >= route.score) {
            return new Plan(keep, waypoints(keep), kept.ores, kept.cost, kept.score, true, "route still the best");
        }
        return new Plan(all, waypoints(all), route.ores, cost, route.score, false,
                String.format(java.util.Locale.ROOT, "%d legs, %.0f blocks, %d ores", legs, cost, route.ores));
    }

    private record Way(long[] nodes, double cost, int ores, double score, LongSet collected) {
    }

    /** A route is planned at least this long (blocks): long stretches, not a short hop and a new decision. */
    static final double MIN_ROUTE = 32.0D;
    /** Legs added at most to reach {@link #MIN_ROUTE}, beyond {@link Params#legs()}. */
    private static final int MAX_LEGS = 6;

    private record Legs(long[] nodes, double cost, int legs) {
    }

    /**
     * Further legs from the end of {@code first} on to the next rich spot: {@link Params#legs()} of them, more (up to
     * {@value #MAX_LEGS}) while the route is shorter than {@value #MIN_ROUTE} blocks.
     */
    private static Legs extend(VoxelView view, IntPredicate isTarget, Walkability walk, Way first, Cells cells,
                               LongToDoubleFunction zoneFactor, Params params, Predicate<long[]> allowed,
                               BooleanSupplier cancelled) {
        LongArrayList nodes = new LongArrayList(first.nodes);
        LongOpenHashSet collected = new LongOpenHashSet(first.collected);
        LongOpenHashSet usedCells = new LongOpenHashSet();
        markCells(usedCells, first.nodes);
        double cost = first.cost;
        int legs = 1;
        while ((legs < params.legs() || cost < MIN_ROUTE && legs < MAX_LEGS) && !cancelled.getAsBoolean()) {
            long from = nodes.getLong(nodes.size() - 1);
            Way next = nextLeg(view, isTarget, walk, from, cells.ore, cells.floor, cells.node, usedCells, collected,
                    zoneFactor, params, allowed, cancelled);
            if (next == null || next.ores < MIN_CELL_ORE) {
                // Nothing new to mine on the way there: the route ends here.
                break;
            }
            for (int k = 1; k < next.nodes.length; k++) {
                nodes.add(next.nodes[k]);
            }
            collected.addAll(next.collected);
            markCells(usedCells, next.nodes);
            cost += next.cost;
            legs++;
        }
        return new Legs(nodes.toLongArray(), cost, legs);
    }

    /** Way back in: entries this close (way cost) are not taken - it would only step back over the edge. */
    private static final double BACK_IN_MIN = 3.0D;
    private static final int BACK_IN_RADIUS = 64;
    private static final int BACK_IN_NODES = 80_000;

    /**
     * The way back into the guarded zone after an excursion: every reached guarded floor node ({@code guarded}) is a
     * possible entry, rated by the ore around it per way there and "straight on first" (the less it turns from
     * {@code heading} the better - not back the way it came); the best ones are rated by the ore the pickaxe mines on
     * the way ({@link #rate}), the best allowed one wins. From there on the route goes on to the next rich spots like
     * any route ({@link #extend}, at least {@value #MIN_ROUTE} blocks).
     */
    public static Plan backIn(VoxelView view, IntPredicate isTarget, Walkability walk, long start, float heading,
                              java.util.function.LongPredicate guarded, LongToDoubleFunction zoneFactor, Params params,
                              Predicate<long[]> allowed, BooleanSupplier cancelled) {
        PathSearch.Flood flood = PathSearch.flood(walk, start, BACK_IN_NODES, BACK_IN_RADIUS, Double.POSITIVE_INFINITY, 0L, cancelled);
        if (flood == null) {
            return Plan.none("not standing on known ground");
        }
        Cells cells = cells(view, isTarget, flood, start);
        int sx = Pos.x(start);
        int sz = Pos.z(start);
        List<Entrance> entries = new ArrayList<>();
        flood.forEachReached((pos, cost, feet) -> {
            if (cost < BACK_IN_MIN || !guarded.test(pos)) {
                return;
            }
            int x = Pos.x(pos);
            int z = Pos.z(pos);
            double ore = density(cells.ore, cellOf(x, Pos.y(pos), z)) + 1.0D;
            double score = ore * zoneFactor.applyAsDouble(LanePlanner.zoneOf(pos)) / (cost + COST_OFFSET)
                    * straightFactor(turn(heading, sx, sz, x, z));
            entries.add(new Entrance(score, pos));
        });
        if (cancelled.getAsBoolean()) {
            return Plan.none("cancelled");
        }
        if (entries.isEmpty()) {
            return Plan.none("no guarded ground in reach");
        }
        entries.sort((p, q) -> Double.compare(q.score(), p.score()));
        Way best = null;
        for (int i = 0; i < Math.min(CANDIDATES * 2, entries.size()); i++) {
            NavigationPath path = flood.pathTo(entries.get(i).pos());
            if (path == null || path.size() < 2 || !allowed.test(path.nodes())) {
                continue;
            }
            long end = entries.get(i).pos();
            Way way = rate(view, isTarget, path.nodes(), path.cost(),
                    density(cells.ore, cellOf(Pos.x(end), Pos.y(end), Pos.z(end))), heading, null, zoneFactor, params,
                    new LongOpenHashSet());
            if (best == null || way.score > best.score) {
                best = way;
            }
        }
        if (best == null) {
            return Plan.none("no allowed way into the zone");
        }
        Legs extended = extend(view, isTarget, walk, best, cells, zoneFactor, params, allowed, cancelled);
        long[] all = extended.nodes();
        LongOpenHashSet collected = strip(view, isTarget, all, params.mineHalf(), new LongOpenHashSet());
        return new Plan(all, waypoints(all), collected.size(), extended.cost(), best.score, false,
                String.format(java.util.Locale.ROOT, "back in, %d legs, %.0f blocks, %d ores", extended.legs(),
                        extended.cost(), collected.size()));
    }

    /** Per cell: floor ores, floor blocks, its nearest floor ore and the way cost there. */
    private record Cells(Long2IntOpenHashMap ore, Long2IntOpenHashMap floor, Long2LongOpenHashMap node, Long2DoubleOpenHashMap cost) {
    }

    private static Cells cells(VoxelView view, IntPredicate isTarget, PathSearch.Flood flood, long start) {
        Long2IntOpenHashMap cellOre = new Long2IntOpenHashMap();
        Long2IntOpenHashMap cellFloor = new Long2IntOpenHashMap();
        Long2LongOpenHashMap cellNode = new Long2LongOpenHashMap();
        Long2DoubleOpenHashMap cellCost = new Long2DoubleOpenHashMap();
        cellCost.defaultReturnValue(Double.POSITIVE_INFINITY);
        flood.forEachReached((pos, cost, feet) -> {
            int x = Pos.x(pos);
            int y = Pos.y(pos);
            int z = Pos.z(pos);
            long cell = cellOf(x, y, z);
            cellFloor.addTo(cell, 1);
            boolean ore = LanePlanner.surfaceOre(view, isTarget, x, y - 1, z);
            if (ore) {
                cellOre.addTo(cell, 1);
            }
            // The cell's goal: its nearest floor ore.
            if (ore && pos != start && cost < cellCost.get(cell)) {
                cellNode.put(cell, pos);
                cellCost.put(cell, cost);
            }
        });
        return new Cells(cellOre, cellFloor, cellNode, cellCost);
    }

    // ── Cave mode: the best tunnel entrance ──────────────────────────────────

    /** A tunnel: walls on both sides of one axis, at most this wide. */
    static final int TUNNEL_MAX_WIDTH = 12;
    /** Walls are looked for this far to each side. */
    private static final int TUNNEL_HALF = 8;
    /** Only every this-many'th column (in x and z) is checked as an entrance. */
    private static final int ENTRANCE_GRID = 2;

    /**
     * Cave mode: the walls opened up into a cave, so the way on is the best tunnel leaving it. Every floor node reached
     * (up to {@code radius}; nodes outside the guarded area are not reached - their costs are infinite) that lies in a
     * tunnel (see {@link #tunnelWidth}) and in a cell that is not poor is rated
     * <pre>score = ores / distance x guarded (0 / 1) x 1 / (1 + vertical difference)</pre>
     * (ores = floor ores of the cell and its neighbours, distance = way cost there + {@value #COST_OFFSET}); the best
     * one is the goal. Way costs already prefer flat, curvy ways over climbing (see {@link Walkability#climbCost}).
     */
    public static Plan tunnel(VoxelView view, IntPredicate isTarget, Walkability walk, long start, float heading,
                              LongToDoubleFunction zoneFactor, Params params, BooleanSupplier cancelled) {
        return tunnel(view, isTarget, walk, start, heading, zoneFactor, params, ANY_WAY, cancelled);
    }

    /** @param allowed whether a way may be walked (see {@link #plan}); the best entrance with an allowed way wins */
    public static Plan tunnel(VoxelView view, IntPredicate isTarget, Walkability walk, long start, float heading,
                              LongToDoubleFunction zoneFactor, Params params, Predicate<long[]> allowed,
                              BooleanSupplier cancelled) {
        PathSearch.Flood flood = PathSearch.flood(walk, start, params.maxNodes(), params.radius(), Double.POSITIVE_INFINITY, 0L, cancelled);
        if (flood == null) {
            return Plan.none("not standing on known ground");
        }
        Cells cells = cells(view, isTarget, flood, start);
        int sy = Pos.y(start);
        List<Entrance> entrances = new ArrayList<>();
        flood.forEachReached((pos, cost, feet) -> {
            int x = Pos.x(pos);
            int y = Pos.y(pos);
            int z = Pos.z(pos);
            if (cost < MIN_GOAL || Math.floorMod(x, ENTRANCE_GRID) != 0 || Math.floorMod(z, ENTRANCE_GRID) != 0) {
                return;
            }
            long cell = cellOf(x, y, z);
            int ore = cells.ore.get(cell);
            if (ore < MIN_CELL_ORE || poor(ore, cells.floor.get(cell))) {
                return;
            }
            double score = density(cells.ore, cell) / (cost + COST_OFFSET) / (1.0D + Math.abs(y - sy))
                    * zoneFactor.applyAsDouble(LanePlanner.zoneOf(pos))
                    * straightFactor(turn(heading, Pos.x(start), Pos.z(start), x, z) / 2.0D);
            if (score > 0.0D && tunnelWidth(walk, x, y, z) <= TUNNEL_MAX_WIDTH) {
                entrances.add(new Entrance(score, pos));
            }
        });
        if (cancelled.getAsBoolean()) {
            return Plan.none("cancelled");
        }
        if (entrances.isEmpty()) {
            return Plan.none("no tunnel with ore in reach");
        }
        // The best entrance whose way may be walked (an excursion out of the guarded area no longer than allowed).
        entrances.sort((p, q) -> Double.compare(q.score(), p.score()));
        NavigationPath path = null;
        double bestScore = 0.0D;
        for (int i = 0; i < Math.min(CANDIDATES, entrances.size()) && path == null; i++) {
            NavigationPath way = flood.pathTo(entrances.get(i).pos());
            if (way != null && way.size() >= 2 && allowed.test(way.nodes())) {
                path = way;
                bestScore = entrances.get(i).score();
            }
        }
        if (path == null) {
            return Plan.none("no way to a tunnel");
        }
        long[] nodes = path.nodes();
        LongOpenHashSet collected = strip(view, isTarget, nodes, params.mineHalf(), new LongOpenHashSet());
        return new Plan(nodes, waypoints(nodes), collected.size(), path.cost(), bestScore, false,
                String.format(java.util.Locale.ROOT, "tunnel %d blocks away, %d ores on the way", Math.round(path.cost()), collected.size()));
    }

    private record Entrance(double score, long pos) {
    }

    /**
     * The narrowest cross-section through a floor node along x and z: the walkable run (steps of 1 up / down) between
     * the walls; {@link Integer#MAX_VALUE} when no axis has a wall on both sides within {@value #TUNNEL_HALF} blocks.
     */
    static int tunnelWidth(Walkability walk, int x, int y, int z) {
        int best = Integer.MAX_VALUE;
        for (int axis = 0; axis < 2; axis++) {
            int width = 1;
            boolean walled = true;
            for (int sign = -1; sign <= 1 && walled; sign += 2) {
                int h = y;
                int k = 1;
                for (; k <= TUNNEL_HALF; k++) {
                    int cx = x + (axis == 0 ? sign * k : 0);
                    int cz = z + (axis == 1 ? sign * k : 0);
                    int found = Integer.MIN_VALUE;
                    for (int dy = 0; dy >= -1 && found == Integer.MIN_VALUE; dy--) {
                        if (walk.isStandable(cx, h + dy, cz)) {
                            found = h + dy;
                        }
                    }
                    if (found == Integer.MIN_VALUE && walk.isStandable(cx, h + 1, cz)) {
                        found = h + 1;
                    }
                    if (found == Integer.MIN_VALUE) {
                        break;
                    }
                    h = found;
                }
                if (k > TUNNEL_HALF) {
                    walled = false;
                } else {
                    width += k - 1;
                }
            }
            if (walled) {
                best = Math.min(best, width);
            }
        }
        return best;
    }

    private static @Nullable Way nextLeg(VoxelView view, IntPredicate isTarget, Walkability walk, long from,
                                         Long2IntOpenHashMap cellOre, Long2IntOpenHashMap cellFloor, Long2LongOpenHashMap cellNode,
                                         LongSet usedCells, LongSet collected, LongToDoubleFunction zoneFactor, Params params,
                                         Predicate<long[]> allowed, BooleanSupplier cancelled) {
        List<double[]> candidates = new ArrayList<>();
        for (long cell : cellNode.keySet()) {
            int ore = cellOre.get(cell);
            if (ore < MIN_CELL_ORE || usedCells.contains(cell) || poor(ore, cellFloor.get(cell))) {
                continue;
            }
            long node = cellNode.get(cell);
            double distance = Math.hypot(Pos.x(node) - Pos.x(from), Pos.z(node) - Pos.z(from)) + Math.abs(Pos.y(node) - Pos.y(from));
            candidates.add(new double[]{density(cellOre, cell) * zoneFactor.applyAsDouble(LanePlanner.zoneOf(node))
                    / (distance + COST_OFFSET), cell});
        }
        candidates.sort((a, b) -> Double.compare(b[0], a[0]));
        Way best = null;
        for (int i = 0; i < Math.min(LEG_CANDIDATES, candidates.size()) && !cancelled.getAsBoolean(); i++) {
            long cell = (long) candidates.get(i)[1];
            PathSearch.Result result = PathSearch.findPath(walk, from, LongList.of(cellNode.get(cell)), LEG_NODES,
                    params.radius(), 0L, cancelled);
            NavigationPath path = result.found() ? result.path() : null;
            if (path == null || path.size() < 2 || !allowed.test(path.nodes())) {
                continue;
            }
            Way way = rate(view, isTarget, path.nodes(), path.cost(), density(cellOre, cell), Float.NaN, null, zoneFactor,
                    params, collected);
            if (best == null || way.score > best.score) {
                best = way;
            }
        }
        return best;
    }

    /** Ore along the way (5 wide strip, not counted before) per block, straight on first. */
    private static Way rate(VoxelView view, IntPredicate isTarget, long[] nodes, double cost, double goalDensity, float heading,
                            @Nullable Avoid avoid, LongToDoubleFunction zoneFactor, Params params, LongSet already) {
        LongOpenHashSet collected = strip(view, isTarget, nodes, params.mineHalf(), already);
        double value = collected.size() + goalDensity;
        double score = value * zoneFactor.applyAsDouble(LanePlanner.zoneOf(nodes[nodes.length - 1])) / (cost + COST_OFFSET);
        if (!Float.isNaN(heading)) {
            long towards = nodes[Math.min(HEADING_NODES, nodes.length - 1)];
            double turn = turn(heading, Pos.x(nodes[0]), Pos.z(nodes[0]), Pos.x(towards), Pos.z(towards));
            if (avoid == null) {
                score *= straightFactor(turn);
            } else if (turn < AVOID_DEGREES) {
                // Too much stone this way: another direction (no "straight on first" then).
                score *= AVOID_FACTOR;
            } else if (turn > BACK_DEGREES) {
                // Path inertia: straight back only for a clearly better way, not a slightly better one behind.
                score *= BACK_FACTOR;
            }
        }
        return new Way(nodes, cost, collected.size(), score, collected);
    }

    /** The route being walked, rated like a new way (null when a node can no longer be walked). */
    private static @Nullable Way rateKept(VoxelView view, IntPredicate isTarget, Walkability walk, long[] keep, float heading,
                                          double goalDensity, LongToDoubleFunction zoneFactor, Params params) {
        double cost = 0.0D;
        for (int i = 0; i < keep.length; i++) {
            long node = keep[i];
            if (Double.isNaN(walk.standHeight(Pos.x(node), Pos.y(node), Pos.z(node)))
                    || walk.penalties().get(node) == Double.POSITIVE_INFINITY) {
                return null;
            }
            if (i > 0) {
                long prev = keep[i - 1];
                cost += Math.hypot(Pos.x(node) - Pos.x(prev), Pos.z(node) - Pos.z(prev)) + Math.abs(Pos.y(node) - Pos.y(prev));
            }
        }
        return rate(view, isTarget, keep, cost, goalDensity, heading, null, zoneFactor, params, new LongOpenHashSet());
    }

    /** Floor ores within {@code half} blocks beside the way (the blocks the pickaxe mines while walking it). */
    static LongOpenHashSet strip(VoxelView view, IntPredicate isTarget, long[] nodes, int half, LongSet already) {
        LongOpenHashSet collected = new LongOpenHashSet();
        for (long node : nodes) {
            int x = Pos.x(node);
            int y = Pos.y(node) - 1;
            int z = Pos.z(node);
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    long pos = Pos.pack(x + dx, y, z + dz);
                    if (!already.contains(pos) && LanePlanner.surfaceOre(view, isTarget, x + dx, y, z + dz)) {
                        collected.add(pos);
                    }
                }
            }
        }
        return collected;
    }

    /** Extra cost per wall block (body height) right beside a node / 2 blocks beside it: ways run in the middle. */
    static final double WALL_NEAR = 0.4D;
    static final double WALL_FAR = 0.15D;

    /**
     * Path costs that make the ways run in the middle of the tunnel: {@code base} plus a cost for every wall block
     * within 2 blocks. Computed per node; use it on the job's own view.
     */
    public static Long2DoubleMap centred(VoxelView view, Long2DoubleMap base) {
        return new NodeCosts(base) {
            @Override
            public double get(long key) {
                double cost = base.get(key);
                if (cost == Double.POSITIVE_INFINITY) {
                    return cost;
                }
                int x = Pos.x(key);
                int y = Pos.y(key);
                int z = Pos.z(key);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if ((dx != 0 || dz != 0) && (wall(view.cell(x + dx, y, z + dz)) || wall(view.cell(x + dx, y + 1, z + dz)))) {
                            cost += Math.max(Math.abs(dx), Math.abs(dz)) == 1 ? WALL_NEAR : WALL_FAR;
                        }
                    }
                }
                return cost;
            }

        };
    }

    /** Path costs computed per node on top of {@code base} (never "empty": the pathfinder asks every node). */
    private abstract static class NodeCosts extends AbstractLong2DoubleMap {
        final Long2DoubleMap base;

        NodeCosts(Long2DoubleMap base) {
            this.base = base;
        }

        @Override
        public int size() {
            return Math.max(1, base.size());
        }

        @Override
        public boolean containsKey(long key) {
            return true;
        }

        @Override
        public ObjectSet<Long2DoubleMap.Entry> long2DoubleEntrySet() {
            throw new UnsupportedOperationException("computed per node");
        }
    }

    /** Way cost taken off per block on a learned route's middle line (a move costs at least 1 - this). */
    static final double KNOWN_DISCOUNT = 0.3D;

    /**
     * Learned routes in the utility weighting: nodes on the middle lines of remembered, guarded, rich routes
     * ({@code columns} = {@code Pos.pack(x, 0, z)}) cost {@value #KNOWN_DISCOUNT} less, so the planner's flood reaches
     * goals along them cheaper and its ways follow them where they lead to the ore anyway. Forbidden nodes stay forbidden.
     */
    public static Long2DoubleMap preferring(Long2DoubleMap base, it.unimi.dsi.fastutil.longs.LongSet columns) {
        if (columns.isEmpty()) {
            return base;
        }
        return new NodeCosts(base) {
            @Override
            public double get(long key) {
                double cost = base.get(key);
                if (cost == Double.POSITIVE_INFINITY || !columns.contains(Pos.pack(Pos.x(key), 0, Pos.z(key)))) {
                    return cost;
                }
                return cost - KNOWN_DISCOUNT;
            }

        };
    }

    private static boolean wall(int cell) {
        return cell == Cell.UNKNOWN || Cell.hasCollision(cell);
    }

    static boolean poor(int ore, int floor) {
        return floor >= POOR_MIN_FLOOR && floor - ore >= POOR_SHARE * floor;
    }

    /** "Straight on first": 1 straight on, 0.5 at 90°, almost 0 back. */
    static double straightFactor(double turnDegrees) {
        double t = turnDegrees / 90.0D;
        return 1.0D / (1.0D + t * t * t * t);
    }

    private static double turn(float heading, int fromX, int fromZ, int toX, int toZ) {
        if (fromX == toX && fromZ == toZ) {
            return 0.0D;
        }
        // Minecraft yaw: 0 = +z, 90 = -x.
        double yaw = Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
        double d = (yaw - heading) % 360.0D;
        if (d > 180.0D) {
            d -= 360.0D;
        } else if (d < -180.0D) {
            d += 360.0D;
        }
        return Math.abs(d);
    }

    private static double density(Long2IntOpenHashMap cellOre, long cell) {
        int cx = Pos.x(cell);
        int cy = Pos.y(cell);
        int cz = Pos.z(cell);
        double total = cellOre.get(cell);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    total += 0.5D * cellOre.get(Pos.pack(cx + dx, cy, cz + dz));
                }
            }
        }
        return total;
    }

    static long cellOf(int x, int y, int z) {
        return Pos.pack(x >> CELL_SHIFT, y >> CELL_SHIFT, z >> CELL_SHIFT);
    }

    private static void markCells(LongSet cells, long[] nodes) {
        for (long node : nodes) {
            cells.add(cellOf(Pos.x(node), Pos.y(node), Pos.z(node)));
        }
    }

    /** The corners of the way (the route compression of {@link RoutePath#compress}, first and last node included). */
    static int[][] waypoints(long[] nodes) {
        return RoutePath.compress(nodes);
    }

}
