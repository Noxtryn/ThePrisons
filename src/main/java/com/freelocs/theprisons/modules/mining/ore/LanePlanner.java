package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.control.RotationMath;
import com.freelocs.theprisons.core.nav.Cell;
import com.freelocs.theprisons.core.nav.MoveType;
import com.freelocs.theprisons.core.nav.NavigationPath;
import com.freelocs.theprisons.core.nav.PathSearch;
import com.freelocs.theprisons.core.nav.PathStraightener;
import com.freelocs.theprisons.core.nav.Pos;
import com.freelocs.theprisons.core.nav.VoxelView;
import com.freelocs.theprisons.core.nav.Walkability;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.LongToDoubleFunction;

/**
 * Plans straight lanes over the mining surface of an ore cave (pure logic on cached block data).
 *
 * <p>The cave floor is uneven (several levels) and mined ores do not disappear: the server turns them into stone
 * and they respawn as ore later. So nothing is ever dug away; the macro walks over the surface and "flips" the ores
 * it passes. A <em>surface ore</em> is a target block with open space above it (the floor the player walks on, at
 * any height); walls and the ceiling are never targets.
 *
 * <p><b>Lanes.</b> For each of the 8 directions the planner walks straight over the surface (vanilla moves:
 * step, jump up one block, drop up to {@code maxDrop}) straight on until a wall (it ends one block before it); the
 * next decision is only made there. With no ore on any way the longest way ahead is walked anyway. It collects the surface ores in reach of a band of {@code 2·halfWidth + 1} blocks along the lane. Ores in the
 * pickaxe's 3-wide cut (lateral ≤ 1) count fully, the rim half. Lanes run on the middle line of the tunnel (a
 * parallel line 2-5 blocks aside only when it is at least twice as good). The lane with the most ore over its whole
 * stretch wins; turning costs a lot ({@link #turnFactor}), so the macro walks long stretches and only turns where the
 * way does not go on. Only the ores of the chosen lane are mined while walking it; everything else is ignored until the next decision.
 *
 * <p><b>Travel.</b> When no lane from here has at least {@code minOres}, a flood over the walkable surface finds the
 * spot with the best ore in reach per path cost (the "ore scanner") and the path there is walked without mining.
 */
public final class LanePlanner {
    /**
     * @param halfWidth  lateral half width of a lane's band in blocks (2 = 5 wide)
     * @param maxLength  longest lane in blocks
     * @param radius     search radius (blocks)
     * @param minOres    a lane needs at least this many ores, otherwise travel
     * @param maxDrop    highest drop a lane / path may use
     * @param reach      mining reach from the eye
     */
    public record Params(int halfWidth, int maxLength, int radius, int minOres, int maxDrop, double reach) {
        public static final Params DEFAULT = new Params(2, 96, 40, 3, 3, 4.4D);
    }

    public enum Kind { LANE, TRAVEL, NONE }

    /** A decision: the path to walk and, for a lane, the ores to mine on it. */
    public record Plan(Kind kind, @org.jspecify.annotations.Nullable NavigationPath path, long[] ores, double score, String reason) {
        static Plan none(String reason) {
            return new Plan(Kind.NONE, null, new long[0], 0.0D, reason);
        }
    }

    /** Value of an empty block of way: with no ore anywhere the longest straight way ahead wins (keep walking). */
    static final double EMPTY_WAY_VALUE = 0.2D;
    private static final int[][] DIRECTIONS = {{0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}};
    /** Surface ores are searched from 3 below to 1 above the lane's feet level. */
    private static final int BELOW = 3;
    private static final int ABOVE = 1;
    private static final int TRAVEL_NODES = 12_000;

    private LanePlanner() {
    }

    /** True for a target block with open space above it (part of the surface one walks and mines on). */
    public static boolean surfaceOre(VoxelView view, IntPredicate isTarget, int x, int y, int z) {
        int key = view.ore(x, y, z);
        if (key == 0 || !isTarget.test(key)) {
            return false;
        }
        int above = view.cell(x, y + 1, z);
        return above != Cell.UNKNOWN && !Cell.hasCollision(above) && !Cell.isLiquid(above);
    }

    /**
     * Next decision from a standing node.
     *
     * @param heading current walking direction (turns away from it cost a little)
     * @param skip    ores to ignore (failed recently, or already assigned to the lane being finished)
     */
    public static Plan plan(VoxelView view, IntPredicate isTarget, long start, float heading, LongSet skip, Params params,
                            BooleanSupplier cancelled) {
        return plan(view, isTarget, start, heading, skip, params, zone -> 1.0D, cancelled);
    }

    /**
     * @param zoneFactor learned value of an area ({@link #zoneOf}): multiplies the score of lanes / spots there
     *                   (1 = no knowledge)
     */
    public static Plan plan(VoxelView view, IntPredicate isTarget, long start, float heading, LongSet skip, Params params,
                            LongToDoubleFunction zoneFactor, BooleanSupplier cancelled) {
        return plan(view, isTarget, start, heading, skip, params, zoneFactor, it.unimi.dsi.fastutil.longs.Long2DoubleMaps.EMPTY_MAP, cancelled);
    }

    /** @param blocked nodes not to walk on (where the player got stuck recently): {@code +∞} forbids a node */
    public static Plan plan(VoxelView view, IntPredicate isTarget, long start, float heading, LongSet skip, Params params,
                            LongToDoubleFunction zoneFactor, it.unimi.dsi.fastutil.longs.Long2DoubleMap blocked, BooleanSupplier cancelled) {
        return plan(view, isTarget, start, heading, skip, params, zoneFactor, blocked, LongSet.of(), cancelled);
    }

    /** @param walked nodes walked just before: ways back over them are avoided (no overlapping routes) */
    public static Plan plan(VoxelView view, IntPredicate isTarget, long start, float heading, LongSet skip, Params params,
                            LongToDoubleFunction zoneFactor, it.unimi.dsi.fastutil.longs.Long2DoubleMap blocked, LongSet walked,
                            BooleanSupplier cancelled) {
        Walkability walk = new Walkability(view, params.maxDrop(), true, blocked);
        if (Double.isNaN(walk.startHeight(Pos.x(start), Pos.y(start), Pos.z(start)))) {
            return Plan.none("not standing on known ground");
        }
        Lane best = null;
        for (int[] direction : DIRECTIONS) {
            int dx = direction[0];
            int dz = direction[1];
            // The lane runs along the middle of the tunnel in this direction.
            int[] room = corridor(walk, start, -dz, dx);
            boolean open = room[0] >= CORRIDOR_SCAN && room[1] >= CORRIDOR_SCAN;
            // Re-centre only when clearly off the middle (no lane changes for one block).
            int centre = open ? 0 : (room[1] - room[0]) / 2;
            if (Math.abs(centre) < 2) {
                centre = 0;
            }
            Lane middle = lane(view, isTarget, walk, start, dx, dz, centre, heading, skip, params, zoneFactor, walked);
            Lane choice = middle;
            if (middle == null || middle.weight < fewOres(params)) {
                // Few ores on the middle line: scan parallel lanes 2-5 blocks to the side, in the same tunnel, and move
                // over only when one is clearly better.
                // No way at all on the middle line (e.g. a side passage whose entrance is one block further): also 1.
                for (int shift = middle == null ? 1 : 2; shift <= 5; shift++) {
                    for (int side = -1; side <= 1; side += 2) {
                        int offset = centre + side * shift;
                        if (offset < -room[0] || offset > room[1]) {
                            continue;
                        }
                        Lane parallel = lane(view, isTarget, walk, start, dx, dz, offset, heading, skip, params, zoneFactor, walked);
                        double bar = choice == null ? 0.0D : choice.score * (choice == middle ? PARALLEL_FACTOR : 1.0D);
                        if (parallel != null && parallel.score > bar) {
                            choice = parallel;
                        }
                    }
                }
            }
            if (choice != null && (best == null || choice.score > best.score)) {
                best = choice;
            }
        }
        if (best != null && best.weight > 0.0D) {
            return new Plan(Kind.LANE, PathStraightener.apply(best.path, walk), best.ores, best.score, "lane");
        }
        if (cancelled.getAsBoolean()) {
            return Plan.none("cancelled");
        }
        // No ore on any way from here: go to where the scanner sees ore, otherwise just keep walking the way.
        Plan travel = travel(view, isTarget, walk, start, skip, params, zoneFactor, cancelled);
        if (travel.kind() == Kind.TRAVEL) {
            return travel;
        }
        if (best != null) {
            return new Plan(Kind.LANE, PathStraightener.apply(best.path, walk), best.ores, best.score, "following the way");
        }
        return travel;
    }

    /** Lateral cells scanned on each side to find the tunnel walls; more free cells than this = open area. */
    static final int CORRIDOR_SCAN = 8;
    /** A parallel lane must be this much better than the middle one to leave the middle of the tunnel. */
    static final double PARALLEL_FACTOR = 2.0D;

    /** "Few ores" on a lane: below this weighted ore count the planner also looks at parallel lanes. */
    static double fewOres(Params params) {
        return Math.max(6.0D, params.minOres() * 2.0D);
    }

    /** Area key used for learned values (8³ blocks). */
    public static long zoneOf(long pos) {
        return Pos.pack(Pos.x(pos) >> 3, Pos.y(pos) >> 3, Pos.z(pos) >> 3);
    }

    /**
     * True when a wall stands next to a node: the ground rises 3 or more blocks within 3 blocks in some direction
     * (steeper than 45°, e.g. a stepped cave wall). Single steps, terraces and gentle slopes are walkable floor. Such
     * nodes are wall, not a way: lanes stop before them, ores on them are not floor, travel paths do not use them.
     */
    static boolean steep(Walkability walk, long node) {
        int x = Pos.x(node);
        int y = Pos.y(node);
        int z = Pos.z(node);
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            // Follow the ground outwards for up to 3 blocks, one walkable rise at a time.
            int height = y;
            for (int k = 1; k <= WALL_RUN; k++) {
                int cx = x + d[0] * k;
                int cz = z + d[1] * k;
                int found = Integer.MIN_VALUE;
                for (int dy = 1; dy >= -1; dy--) {
                    if (walk.isStandable(cx, height + dy, cz)) {
                        found = height + dy;
                        break;
                    }
                }
                if (found == Integer.MIN_VALUE) {
                    break;
                }
                height = found;
                if (height - y >= WALL_RISE) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Flat blocks that must follow a climb (in the walking direction) before it counts as a way up. */
    static final int FLAT_TOP = 3;
    /** Longest stair (single steps in a row) that is climbed, if it leads onto flat ground. */
    static final int MAX_STAIR = 4;

    /**
     * Going up is only worth it onto a real upper floor: from the first step {@code top} (feet {@code feet}), walking on
     * in direction ({@code dx}, {@code dz}), further single steps up are allowed (a stair, at most {@value #MAX_STAIR}),
     * then {@value #FLAT_TOP} blocks must follow at the same height. A stair that ends in a wall, a ledge, or ground
     * that only continues back over the way it came (an overhang / the ceiling) is not a way up.
     */
    static boolean climbLeadsOn(Walkability walk, long top, double feet, int dx, int dz) {
        long node = top;
        double height = feet;
        int flat = 0;
        int stair = 1;
        long[] next = new long[1];
        double[] nextFeet = new double[1];
        for (int step = 0; step < MAX_STAIR + FLAT_TOP + 2 && flat < FLAT_TOP; step++) {
            int cx = Pos.x(node);
            int cz = Pos.z(node);
            next[0] = Long.MIN_VALUE;
            walk.neighbours(cx, Pos.y(node), cz, height, (nx, ny, nz, f, type, cost) -> {
                if (nx == cx + dx && nz == cz + dz) {
                    next[0] = Pos.pack(nx, ny, nz);
                    nextFeet[0] = f;
                }
            });
            if (next[0] == Long.MIN_VALUE) {
                return false;
            }
            double rise = nextFeet[0] - height;
            if (rise > 0.5D) {
                if (flat > 0 || ++stair > MAX_STAIR) {
                    return false;
                }
            } else if (rise < -0.5D) {
                // Down again right after: a bump, not an upper floor.
                return false;
            } else {
                flat++;
            }
            node = next[0];
            height = nextFeet[0];
        }
        return flat >= FLAT_TOP;
    }

    /** A wall: {@value #WALL_RISE} blocks up within {@value #WALL_RUN} blocks. */
    static final int WALL_RISE = 3;
    static final int WALL_RUN = 3;

    /**
     * Free walkable cells to the left (index 0, along -lateral) and right (index 1, along +lateral) of a node at
     * about the same height, up to {@value #CORRIDOR_SCAN}: the tunnel walls in that direction.
     */
    static int[] corridor(Walkability walk, long start, int lx, int lz) {
        int[] room = new int[2];
        for (int side = 0; side < 2; side++) {
            int sign = side == 0 ? -1 : 1;
            int y = Pos.y(start);
            int count = 0;
            for (int k = 1; k <= CORRIDOR_SCAN; k++) {
                int x = Pos.x(start) + lx * k * sign;
                int z = Pos.z(start) + lz * k * sign;
                int found = Integer.MIN_VALUE;
                for (int dy : new int[]{0, 1, -1}) {
                    if (!Double.isNaN(walk.standHeight(x, y + dy, z))) {
                        found = y + dy;
                        break;
                    }
                }
                if (found == Integer.MIN_VALUE || steep(walk, Pos.pack(x, found, z))) {
                    break;
                }
                y = found;
                count++;
            }
            room[side] = count;
        }
        return room;
    }

    record Lane(NavigationPath path, long[] ores, double score, double weight) {
    }

    /** Weight of an ore by lateral distance from the lane line: the pickaxe's 3-wide cut counts fully, the rim half. */
    static double lateralWeight(int w) {
        return Math.abs(w) <= 1 ? 1.0D : 0.5D;
    }

    /**
     * Share of a lane's value that is kept after turning by {@code degrees}: straight on 1, 45° ≈ 0.64, 90° ≈ 0.31,
     * turning back ≈ 0.1 - the macro walks long stretches and only turns when the way does not go on.
     */
    /** Value kept per step up on a lane. */
    static final double RISE_FACTOR = 0.8D;
    /** Share of a lane's value lost when all of it runs over blocks walked just before. */
    static final double OVERLAP_PENALTY = 0.8D;

    static double turnFactor(double degrees) {
        double t = degrees / 60.0D;
        return 1.0D / (1.0D + t * t);
    }

    static Lane lane(VoxelView view, IntPredicate isTarget, Walkability walk, long start, int dx, int dz, int offset, float heading,
                     LongSet skip, Params params, LongToDoubleFunction zoneFactor) {
        return lane(view, isTarget, walk, start, dx, dz, offset, heading, skip, params, zoneFactor, LongSet.of());
    }

    static Lane lane(VoxelView view, IntPredicate isTarget, Walkability walk, long start, int dx, int dz, int offset, float heading,
                     LongSet skip, Params params, LongToDoubleFunction zoneFactor, LongSet walkedBefore) {
        LongArrayList nodes = new LongArrayList();
        DoubleArrayList feet = new DoubleArrayList();
        List<MoveType> moves = new ArrayList<>();
        nodes.add(start);
        feet.add(walk.startHeight(Pos.x(start), Pos.y(start), Pos.z(start)));
        moves.add(MoveType.START);
        LongLinkedOpenHashSet ores = new LongLinkedOpenHashSet();
        double weight = 0.0D;
        int jumps = 0;
        int lx = -dz;
        int lz = dx;
        double reachSq = params.reach() * params.reach();
        long[] next = new long[1];
        double[] nextFeet = new double[1];
        MoveType[] nextMove = new MoveType[1];
        boolean blocked = false;
        int prefix = Math.abs(offset);
        int sign = Integer.signum(offset);
        for (int k = 0; k < prefix; k++) {
            // Sideways to the lane line first (the middle of the tunnel, or a parallel lane), then straight.
            long current = nodes.getLong(nodes.size() - 1);
            int sx = Pos.x(current) + lx * sign;
            int sz = Pos.z(current) + lz * sign;
            next[0] = Long.MIN_VALUE;
            walk.neighbours(Pos.x(current), Pos.y(current), Pos.z(current), feet.getDouble(feet.size() - 1), (nx, ny, nz, f, type, cost) -> {
                if (nx == sx && nz == sz) {
                    next[0] = Pos.pack(nx, ny, nz);
                    nextFeet[0] = f;
                    nextMove[0] = type;
                }
            });
            if (next[0] == Long.MIN_VALUE) {
                return null;
            }
            nodes.add(next[0]);
            feet.add(nextFeet[0]);
            moves.add(nextMove[0]);
        }
        for (int step = 1; step <= params.maxLength(); step++) {
            long current = nodes.getLong(nodes.size() - 1);
            int cx = Pos.x(current);
            int cy = Pos.y(current);
            int cz = Pos.z(current);
            next[0] = Long.MIN_VALUE;
            walk.neighbours(cx, cy, cz, feet.getDouble(feet.size() - 1), (nx, ny, nz, f, type, cost) -> {
                if (nx == cx + dx && nz == cz + dz) {
                    next[0] = Pos.pack(nx, ny, nz);
                    nextFeet[0] = f;
                    nextMove[0] = type;
                }
            });
            if (next[0] == Long.MIN_VALUE || steep(walk, next[0])
                    || nextFeet[0] > feet.getDouble(feet.size() - 1) + 0.5D && !climbLeadsOn(walk, next[0], nextFeet[0], dx, dz)) {
                // A wall - or a step / stair that does not lead onto flat ground going on ahead: the lane ends here.
                blocked = true;
                break;
            }
            nodes.add(next[0]);
            feet.add(nextFeet[0]);
            moves.add(nextMove[0]);
            if (nextMove[0] == MoveType.JUMP) {
                jumps++;
            }
            int nx = Pos.x(next[0]);
            int nz = Pos.z(next[0]);
            double eyeY = nextFeet[0] + Walkability.EYE_HEIGHT;
            int footY = (int) Math.floor(nextFeet[0] + 0.01D);
            for (int w = -params.halfWidth(); w <= params.halfWidth(); w++) {
                for (int fill = 0; fill <= (dx != 0 && dz != 0 ? 1 : 0); fill++) {
                    int bx = nx + lx * w + dx * fill;
                    int bz = nz + lz * w;
                    for (int by = footY - BELOW; by <= footY + ABOVE; by++) {
                        long pos = Pos.pack(bx, by, bz);
                        if (skip.contains(pos) || ores.contains(pos) || !surfaceOre(view, isTarget, bx, by, bz)
                                || steep(walk, Pos.pack(bx, by + 1, bz))) {
                            continue;
                        }
                        double ddx = bx + 0.5D - (nx + 0.5D);
                        double ddy = by + 1.0D - eyeY;
                        double ddz = bz + 0.5D - (nz + 0.5D);
                        if (ddx * ddx + ddy * ddy + ddz * ddz <= reachSq) {
                            ores.add(pos);
                            weight += lateralWeight(w);
                        }
                    }
                }
            }
        }
        // The lane goes straight on until a wall; decisions are only made there.
        int length = nodes.size() - 1 - prefix;
        if (length < 2) {
            return null;
        }
        int size = length + 1 + prefix;
        MoveType[] moveArray = moves.subList(0, size).toArray(new MoveType[0]);
        NavigationPath path = new NavigationPath(java.util.Arrays.copyOf(nodes.toLongArray(), size),
                java.util.Arrays.copyOf(feet.toDoubleArray(), size), moveArray, length + prefix, 0L);
        float yaw = RotationMath.yawOf(dx, dz);
        double turn = Math.abs(RotationMath.wrap(yaw - heading));
        double walked = length * (dx != 0 && dz != 0 ? Math.sqrt(2.0D) : 1.0D);
        // All the ore of the stretch counts (long stretches are good); turning is what costs.
        int overlap = 0;
        for (long node : path.nodes()) {
            if (walkedBefore.contains(node)) {
                overlap++;
            }
        }
        // Going up is avoided where possible; walking over the way just walked again is avoided even more.
        double score = (weight + EMPTY_WAY_VALUE * walked) * turnFactor(turn) * Math.pow(RISE_FACTOR, jumps)
                * (1.0D - OVERLAP_PENALTY * overlap / (double) path.size())
                / (1.0D + 0.1D * prefix)
                * zoneFactor.applyAsDouble(zoneOf(path.nodes()[path.size() / 2]));
        return new Lane(path, ores.toLongArray(), score, weight);
    }

    /**
     * The ore scanner: every surface ore in the radius, bucketed by column; each walkable node reached by a flood is
     * scored by the ores in reach of it divided by the path cost there. Returns the path to the best node.
     */
    static Plan travel(VoxelView view, IntPredicate isTarget, Walkability walk, long start, LongSet skip, Params params,
                       LongToDoubleFunction zoneFactor, BooleanSupplier cancelled) {
        PathSearch.Flood flood = PathSearch.flood(walk, start, TRAVEL_NODES, params.radius(), Double.POSITIVE_INFINITY, 0L, cancelled);
        if (flood == null) {
            return Plan.none("not standing on known ground");
        }
        List<double[]> candidates = new ArrayList<>();
        int reach = (int) Math.ceil(params.reach());
        double reachSq = params.reach() * params.reach();
        flood.forEachReached((pos, cost, footHeight) -> {
            if (pos == start || cost < 3.0D) {
                return;
            }
            int x = Pos.x(pos);
            int z = Pos.z(pos);
            int footY = (int) Math.floor(footHeight + 0.01D);
            double eyeY = footHeight + Walkability.EYE_HEIGHT;
            int count = 0;
            for (int bx = x - reach; bx <= x + reach; bx++) {
                for (int bz = z - reach; bz <= z + reach; bz++) {
                    for (int by = footY - BELOW; by <= footY + ABOVE; by++) {
                        double ddx = bx - x;
                        double ddy = by + 1.0D - eyeY;
                        double ddz = bz - z;
                        if (ddx * ddx + ddy * ddy + ddz * ddz <= reachSq && !skip.contains(Pos.pack(bx, by, bz))
                                && surfaceOre(view, isTarget, bx, by, bz)) {
                            count++;
                        }
                    }
                }
            }
            if (count >= params.minOres()) {
                // Most ores for the shortest way, weighted by what this area yielded before.
                candidates.add(new double[]{count / (cost + 6.0D) * zoneFactor.applyAsDouble(zoneOf(pos)), pos});
            }
        });
        candidates.sort((a, b) -> Double.compare(b[0], a[0]));
        for (int i = 0; i < Math.min(8, candidates.size()); i++) {
            long node = (long) candidates.get(i)[1];
            NavigationPath path = flood.pathTo(node);
            if (path == null || path.size() <= 1) {
                continue;
            }
            return new Plan(Kind.TRAVEL, PathStraightener.apply(path, walk), new long[0], candidates.get(i)[0], "to the richest spot");
        }
        return Plan.none("no ore reachable");
    }
}
