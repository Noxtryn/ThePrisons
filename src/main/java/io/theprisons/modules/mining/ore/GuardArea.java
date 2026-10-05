package io.theprisons.modules.mining.ore;

import io.theprisons.core.nav.Pos;
import it.unimi.dsi.fastutil.longs.AbstractLong2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import java.util.ArrayList;
import java.util.List;

/**
 * The guarded area (pure logic): every guard guards the ground within {@code radius} blocks (3D, feet to feet; 15 on
 * Cosmic) around it, cut to {@link #GUARD_BELOW} below and {@link #GUARD_ABOVE} above its feet (under a guard's floor
 * there is no tax). The circles of all guards overlap to one area - the macro stays inside it. Ground the sidebar showed
 * to be untaxed is outside whatever the circles say: the server's answer beats the model.
 *
 * <p>Guards are found up to {@value #SCAN_RANGE} blocks away and remembered for the whole run (and saved for the next
 * one), so the macro also knows guards that are out of sight. A remembered guard close to the player
 * ({@value #TRACK_RANGE} blocks, where the client surely sees it) that has not been seen for {@value #FORGET_MS} ms is
 * gone (died / walked off) and is forgotten.</p>
 *
 * <p>Two circles with a gap of at most {@value #GAP} blocks between them are joined: the strip between them (an
 * ellipse round both guards, see {@link #bridged}) may be walked, so the macro crosses a short unguarded stretch to the
 * next guard instead of turning back. A wider gap is not crossed.</p>
 *
 * <p>Like a border the other way round: a step may go anywhere inside the area; outside it only closer to a guard, so a
 * player that is outside (start, teleport, knocked away) can still walk back in.</p>
 *
 * <p>Without any guard known the sidebar's guard XP tax stands in (tax mode): taxed = inside. Every block where the
 * tax was on is remembered as inside, every block where it went off as outside (saved per world), ways end
 * {@value #OUTSIDE_MARGIN} block before an outside block, and planned paths only lead over ground known to be taxed.
 * As soon as a guard is known, the guards decide again.</p>
 */
public final class GuardArea {
    /** Guards are searched this far away. */
    public static final double SCAN_RANGE = 128.0D;
    /** Every guard guards this far around it. */
    public static final double DEFAULT_RADIUS = 15.0D;
    /**
     * The zone reaches only this far below a guard's feet (the learned tax, game 2026-10-05: at (638, 181) the tax was
     * on 0 below the guard at (639.5, 93, 191.5) and gone 13 below; over all 13 800 learned blocks a sphere cut to
     * -6 / +8 classifies 84 % of the untaxed ones right, the plain sphere 77 %).
     */
    public static final double GUARD_BELOW = 6.0D;
    /** And this far above it. */
    public static final double GUARD_ABOVE = 8.0D;
    /**
     * The macro's own ways stay this far inside the edge, so sprinting / a step too far never carries it out of the
     * area. Between this and the radius nothing happens - so it never goes back and forth at the edge.
     */
    public static final double EDGE = 1.5D;
    /** Ways end this far inside the guards' circles ({@link #EDGE} unless set: the "guard look-ahead"). */
    private double edge = EDGE;
    /** Circles this close (blocks between their edges) are joined by a walkable strip. */
    public static final double GAP = 5.0D;
    /**
     * The strip between two joined guards: points whose distances to both add up to at most their distance plus this
     * (an ellipse round both; half as wide as about sqrt(distance x slack / 2) in the middle - 5-6 blocks).
     */
    static final double BRIDGE_SLACK = 2.0D;
    /** The strip counts this much wider for "is the player outside" than for its own ways (no back and forth). */
    static final double BRIDGE_INSIDE = 1.5D;
    /**
     * Guards stand still: one is only forgotten when the player is this close and has not seen it for {@value
     * #FORGET_MS} ms (further away the client often just does not get the NPC - forgetting it there made the area
     * flicker and the ways end and come back all the time).
     */
    static final double TRACK_RANGE = 16.0D;
    static final long FORGET_MS = 15_000L;
    /** The same guard (it walks a little between two scans). */
    private static final double SAME = 4.0D;

    /** Tax mode: ways stay this far (blocks, sideways) from a block known to be outside. */
    static final int OUTSIDE_MARGIN = 1;
    private static final int OUTSIDE_HEIGHT = 2;
    /** Planner, tax mode: ground this far above / below a known guard counts as near it. */
    static final double GUARD_HEIGHT = 8.0D;
    /** Walled in while taxed: blocks marked outside within this many blocks (sideways) are cleared. */
    static final int TAXED_CLEAR = 2;
    /** Tax mode: ground this close (sideways / up and down) to a block known to be taxed counts as taxed. */
    static final int INSIDE_MARGIN = 2;
    /** Tax mode: when the tax goes off, the edge is marked this many blocks to each side. */
    static final int EDGE_ACROSS = 6;
    /** How deep beyond the edge (in the walking direction) the ground counts as outside. */
    static final int EDGE_DEPTH = 8;

    /** {x, y, z, last seen ms} per guard. */
    private final List<double[]> guards = new ArrayList<>();
    private double radius = DEFAULT_RADIUS;
    /** A guard XP tax line was seen in the sidebar this run. */
    private boolean taxSeen;
    /**
     * Excursions: planned ways may cross up to this many unguarded blocks in a row (user: 3 with a player near, 5-7
     * alone), so more ways lead from one guarded spot to the next and ore just outside is mined too. 0 = strict.
     */
    private int outsideBudget;
    /**
     * Unguarded nodes of a planned way cost this much more than guarded ones: a little, so of two equal ways the guarded
     * one wins, but the ore decides (user: not so strict).
     */
    static final double OUTSIDE_COST = 0.5D;
    /**
     * Free steering may still walk on over unguarded ground (the module counts the blocks walked outside in a row and
     * ends this once the budget is used up): up to {@link #outsideReach()} from guarded ground counts as inside, also
     * for the steering's rays (they see the ore out there and cross gaps).
     */
    private boolean roam;
    /**
     * The route being walked (feet blocks, widened by {@value #CORRIDOR_HALF} to each side - the steering walks the
     * richest lane up to 5 blocks beside a route's line): the steering may follow it out of the area.
     */
    private final LongOpenHashSet corridor = new LongOpenHashSet();

    /** Unguarded blocks in a row a planned way may cross (0 = never leaves the area). */
    public void outsideBudget(int blocks) {
        int next = Math.max(0, blocks);
        this.outsideBudget = next;
        // A hard safety transition to zero invalidates any previously prepared route corridor.
        if (next == 0) {
            corridor.clear();
            roam = false;
        }
    }

    public int outsideBudget() {
        return outsideBudget;
    }

    /**
     * How far (blocks, sideways) from guarded ground ways may lead: the whole budget (user: 8 blocks out alone), +2 -
     * the model's edge lies a block inside the real one, and a player at the end of the budget must still be able to
     * plan from where it stands.
     */
    int outsideReach() {
        return outsideBudget == 0 ? 0 : outsideBudget + 2;
    }

    /**
     * The most unguarded blocks in a row a planned way may have: the budget out and the way back in again (a gap of
     * up to twice the budget is crossed, a dip goes the budget deep and back).
     */
    /** Lane width beside a route's line (see {@link ClassicSteer}: lanes up to 5 blocks aside). */
    static final int CORRIDOR_HALF = 5;

    /**
     * Well inside: guarded, and so is the ground {@code margin} blocks to each side (the way back in ends there, not at
     * the edge where the guard tax may not show yet).
     */
    public boolean deepNode(long key, int margin) {
        int x = Pos.x(key);
        int y = Pos.y(key);
        int z = Pos.z(key);
        double nan = Double.NaN;
        return guardedNode(key, nan, nan, nan)
                && guardedNode(Pos.pack(x + margin, y, z), nan, nan, nan) && guardedNode(Pos.pack(x - margin, y, z), nan, nan, nan)
                && guardedNode(Pos.pack(x, y, z + margin), nan, nan, nan) && guardedNode(Pos.pack(x, y, z - margin), nan, nan, nan);
    }

    public int maxOutsideRun() {
        return 2 * outsideBudget;
    }

    /** The route being walked: the steering may follow it over unguarded ground ({@code null} = no route). */
    public void corridor(long @org.jspecify.annotations.Nullable [] nodes) {
        corridor.clear();
        if (nodes == null || outsideBudget == 0) {
            return;
        }
        for (long node : nodes) {
            for (int dx = -CORRIDOR_HALF; dx <= CORRIDOR_HALF; dx++) {
                for (int dz = -CORRIDOR_HALF; dz <= CORRIDOR_HALF; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        corridor.add(Pos.pack(Pos.x(node) + dx, Pos.y(node) + dy, Pos.z(node) + dz));
                    }
                }
            }
        }
    }

    private boolean inCorridor(double x, double y, double z) {
        long key = Pos.pack((int) Math.floor(x), (int) Math.floor(y + 0.01D), (int) Math.floor(z));
        return !corridor.isEmpty() && corridor.contains(key)
                || roam && outsideBudget > 0 && !isEmpty() && withinReach(key);
    }

    /** Whether the free steering may walk on over unguarded ground (budget left this excursion). */
    public void roam(boolean roam) {
        this.roam = roam;
    }

    /** Really in the guarded area now (the guard status / the circles), whether or not a route leads out of it. */
    public boolean guardedHere(double x, double y, double z) {
        return scoreboard() ? taxed : within(x, y, z, 0.0D, BRIDGE_SLACK + BRIDGE_INSIDE);
    }
    /** The player is taxed now. */
    private boolean taxed;
    /** Feet blocks where the tax was gone. */
    private final LongOpenHashSet outside = new LongOpenHashSet();
    /** Feet blocks where the tax was on, and that ground widened by {@value #INSIDE_MARGIN}. */
    private final LongOpenHashSet inside = new LongOpenHashSet();
    private final LongOpenHashSet insideNear = new LongOpenHashSet();
    /** The last feet block where the player was taxed ({@code null} = none yet). */
    private int @org.jspecify.annotations.Nullable [] lastInside;

    public GuardArea() {
    }

    private GuardArea(GuardArea other) {
        for (double[] g : other.guards) {
            guards.add(g.clone());
        }
        radius = other.radius;
        taxSeen = other.taxSeen;
        outsideBudget = other.outsideBudget;
        roam = other.roam;
        corridor.addAll(other.corridor);
        taxed = other.taxed;
        outside.addAll(other.outside);
        inside.addAll(other.inside);
        insideNear.addAll(other.insideNear);
        edge = other.edge;
        lastInside = other.lastInside == null ? null : other.lastInside.clone();
    }

    /** A frozen copy for the pathfinder thread. */
    public GuardArea copy() {
        return new GuardArea(this);
    }

    public void radius(double radius) {
        this.radius = radius;
    }

    /** Ways end this many blocks before the edge of the guarded area. */
    public void edge(double blocks) {
        this.edge = Math.max(0.5D, blocks);
    }

    public double edge() {
        return edge;
    }

    public double radius() {
        return radius;
    }

    public void clear() {
        guards.clear();
        taxSeen = false;
        taxed = false;
        outside.clear();
        inside.clear();
        insideNear.clear();
        corridor.clear();
        lastInside = null;
    }

    /** No guard known and no taxed zone either: nowhere is guarded. */
    public boolean isEmpty() {
        return guards.isEmpty() && !(taxSeen && (taxed || lastInside != null));
    }

    /** Tax mode: no guard known, the sidebar's guard XP tax decides. */
    /**
     * Tax mode: the sidebar's guard tax decides where the macro may go (user rule: stay where the guard tax is on - the
     * guards' circles are only a model and are used only while no tax line was seen this run).
     */
    public boolean scoreboard() {
        return taxSeen;
    }

    /** Tax mode: the last feet block where the player was in the zone ({@code null} = none yet). */
    public int @org.jspecify.annotations.Nullable [] lastInside() {
        return lastInside;
    }

    /**
     * Taxed but no way on: the sidebar shows the tax late, so blocks marked outside on the way back in were inside after
     * all and wall the macro in (game 22:46). They are cleared around the player. Returns whether it was taxed.
     */
    public boolean clearOutsideNear(int x, int y, int z) {
        if (!taxSeen || !taxed) {
            return false;
        }
        for (int dx = -TAXED_CLEAR; dx <= TAXED_CLEAR; dx++) {
            for (int dz = -TAXED_CLEAR; dz <= TAXED_CLEAR; dz++) {
                for (int dy = -OUTSIDE_HEIGHT; dy <= OUTSIDE_HEIGHT; dy++) {
                    outside.remove(Pos.pack(x + dx, y + dy, z + dz));
                }
            }
        }
        return true;
    }

    /** Blocks learned to be outside the taxed zone. */
    public int outsideBlocks() {
        return outside.size();
    }

    /** Blocks learned to be inside the taxed zone. */
    public int insideBlocks() {
        return inside.size();
    }

    /**
     * The sidebar's guard XP tax at the player's feet block: {@code null} = no sidebar / unknown (nothing changes),
     * true = taxed (in the zone), false = no tax (outside, once a tax line was seen this run).
     */
    public void tax(@org.jspecify.annotations.Nullable Boolean tax, int x, int y, int z) {
        if (tax == null || !tax && !taxSeen) {
            return;
        }
        taxSeen = true;
        taxed = tax;
        long cell = Pos.pack(x, y, z);
        if (tax) {
            outside.remove(cell);
            addInside(cell);
            lastInside = new int[]{x, y, z};
        } else {
            outside.add(cell);
            // Untaxed here now: no longer a target for the way back.
            inside.remove(cell);
        }
    }

    /**
     * The tax just went off while walking {@code yaw}: the zone's edge is taken to run across the way here, so the
     * blocks up to {@value #EDGE_ACROSS} beside the player (square to the walking direction) are outside too. A zone
     * is a circle of 15 blocks around a guard, so over that width its edge bends less than half a block. Blocks known
     * to be taxed stay inside.
     */
    public void edge(int x, int y, int z, float yaw) {
        if (!taxSeen || taxed || outsideBudget > 0) {
            // With excursions allowed the band would wall off the ground beyond the exit for good (it is saved): only
            // the blocks really walked without the tax count as outside then.
            return;
        }
        double rad = Math.toRadians(yaw);
        double sx = Math.cos(rad);
        double sz = Math.sin(rad);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        // A band beyond the edge, not just a line: the macro must not walk in again a few blocks beside (where the
        // ore draws it) - better a way over stone. Ground known to be taxed stays inside.
        for (int depth = 0; depth <= EDGE_DEPTH; depth++) {
            for (int k = -EDGE_ACROSS; k <= EDGE_ACROSS; k++) {
                long cell = Pos.pack((int) Math.floor(x + 0.5D + sx * k + fx * depth), y,
                        (int) Math.floor(z + 0.5D + sz * k + fz * depth));
                // On a route's planned excursion the band would make the rest of it look longer outside than it is
                // (the route would be dropped half way): there only the blocks really walked untaxed count.
                if (!inside.contains(cell) && !corridor.contains(cell)) {
                    outside.add(cell);
                }
            }
        }
    }

    private void addInside(long cell) {
        if (!inside.add(cell)) {
            return;
        }
        int x = Pos.x(cell);
        int y = Pos.y(cell);
        int z = Pos.z(cell);
        for (int dx = -INSIDE_MARGIN; dx <= INSIDE_MARGIN; dx++) {
            for (int dz = -INSIDE_MARGIN; dz <= INSIDE_MARGIN; dz++) {
                for (int dy = -INSIDE_MARGIN; dy <= INSIDE_MARGIN; dy++) {
                    insideNear.add(Pos.pack(x + dx, y + dy, z + dz));
                }
            }
        }
    }

    /** Taxed and untaxed blocks from an earlier run (they only count once a tax line is seen in this run). */
    public void restore(long[] taxedBlocks, long[] untaxedBlocks) {
        for (long cell : taxedBlocks) {
            if (!outside.contains(cell)) {
                addInside(cell);
            }
        }
        for (long cell : untaxedBlocks) {
            if (!inside.contains(cell)) {
                outside.add(cell);
            }
        }
    }

    /** Blocks known to be taxed (inside) - to be saved. */
    public long[] taxedBlocks() {
        return inside.toLongArray();
    }

    /** Blocks known to be untaxed (outside) - to be saved. */
    public long[] untaxedBlocks() {
        return outside.toLongArray();
    }

    /** Within {@link #OUTSIDE_MARGIN} of a block known to be outside. */
    private boolean nearOutside(double x, double y, double z) {
        return nearOutside(x, y, z, OUTSIDE_MARGIN);
    }

    /** Within {@code margin} blocks (sideways) of a block known to be outside. */
    private boolean nearOutside(double x, double y, double z, int margin) {
        if (outside.isEmpty()) {
            return false;
        }
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y + 0.01D);
        int bz = (int) Math.floor(z);
        for (int dx = -margin; dx <= margin; dx++) {
            for (int dz = -margin; dz <= margin; dz++) {
                for (int dy = -OUTSIDE_HEIGHT; dy <= OUTSIDE_HEIGHT; dy++) {
                    if (outside.contains(Pos.pack(bx + dx, by + dy, bz + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Tax mode, the way back: the known taxed block that lies deepest in the zone (farthest from every block known to
     * be outside, up to 6) among the nearest ones within 32 blocks - not the edge block next to the player.
     * {@code null} = none known.
     */
    public int @org.jspecify.annotations.Nullable [] taxedTarget(double px, double py, double pz) {
        int[] best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        it.unimi.dsi.fastutil.longs.LongIterator it = inside.iterator();
        while (it.hasNext()) {
            long cell = it.nextLong();
            double cx = Pos.x(cell) + 0.5D;
            double cz = Pos.z(cell) + 0.5D;
            double d = Math.hypot(cx - px, cz - pz) + Math.abs(Pos.y(cell) - py);
            if (d > 32.0D) {
                continue;
            }
            double deep = 6.0D;
            it.unimi.dsi.fastutil.longs.LongIterator out = outside.iterator();
            while (out.hasNext() && deep > 0.0D) {
                long o = out.nextLong();
                deep = Math.min(deep, Math.hypot(Pos.x(o) + 0.5D - cx, Pos.z(o) + 0.5D - cz) + Math.abs(Pos.y(o) - Pos.y(cell)));
            }
            double score = d - 3.0D * deep;
            if (score < bestScore) {
                bestScore = score;
                best = new int[]{Pos.x(cell), Pos.y(cell), Pos.z(cell)};
            }
        }
        return best;
    }

    /**
     * The fastest way back in: the nearest known taxed block that is at least 2 blocks from every block known to be
     * outside (safely inside) and not right where the player stands. {@code null} = none known.
     */
    public int @org.jspecify.annotations.Nullable [] nearestTaxed(double px, double py, double pz) {
        int[] best = null;
        double bestD = Double.POSITIVE_INFINITY;
        it.unimi.dsi.fastutil.longs.LongIterator it = inside.iterator();
        while (it.hasNext()) {
            long cell = it.nextLong();
            double cx = Pos.x(cell) + 0.5D;
            double cz = Pos.z(cell) + 0.5D;
            double d = Math.hypot(cx - px, cz - pz) + 2.0D * Math.abs(Pos.y(cell) - py);
            if (d < 2.0D || d >= bestD || nearOutside(cx, Pos.y(cell), cz, 2)) {
                continue;
            }
            bestD = d;
            best = new int[]{Pos.x(cell), Pos.y(cell), Pos.z(cell)};
        }
        return best;
    }

    /**
     * Within a guard's zone (radius less a block, 3D feet to feet - the user's rule; a cylinder of {@value #GUARD_HEIGHT}
     * up / down took ground far above or below a guard for guarded) of any known guard.
     */
    private boolean nearGuard(double x, double y, double z) {
        double r = Math.max(1.0D, radius - 1.0D);
        for (double[] g : guards) {
            double dx = g[0] - x;
            double dy = g[1] - y;
            double dz = g[2] - z;
            if (dx * dx + dy * dy + dz * dz <= r * r && inBand(g, y)) {
                return true;
            }
        }
        return false;
    }

    private boolean nearTaxed(double x, double y, double z) {
        return insideNear.contains(Pos.pack((int) Math.floor(x), (int) Math.floor(y + 0.01D), (int) Math.floor(z)));
    }

    public int size() {
        return guards.size();
    }

    /** {x, y, z, last seen ms} per guard. */
    public List<double[]> guards() {
        return guards;
    }

    /** Adds / moves the guards in sight ({x, y, z} each) and forgets the ones that should be in sight but are not. */
    public void update(double[][] seen, double px, double py, double pz, long now) {
        for (double[] s : seen) {
            double[] match = null;
            for (double[] g : guards) {
                if (Math.abs(g[0] - s[0]) < SAME && Math.abs(g[1] - s[1]) < SAME && Math.abs(g[2] - s[2]) < SAME) {
                    match = g;
                    break;
                }
            }
            if (match == null) {
                guards.add(new double[]{s[0], s[1], s[2], now});
            } else {
                match[0] = s[0];
                match[1] = s[1];
                match[2] = s[2];
                match[3] = now;
            }
        }
        guards.removeIf(g -> now - g[3] > FORGET_MS && dist(g, px, py, pz) <= TRACK_RANGE);
    }

    /**
     * A guard from an earlier run: known at once (area, planning), as if last seen long ago - so it is forgotten as soon
     * as the player comes within {@value #TRACK_RANGE} blocks and it is not there.
     */
    public void remember(double x, double y, double z) {
        for (double[] g : guards) {
            if (Math.abs(g[0] - x) < SAME && Math.abs(g[1] - y) < SAME && Math.abs(g[2] - z) < SAME) {
                return;
            }
        }
        guards.add(new double[]{x, y, z, 0.0D});
    }

    private static double dist(double[] g, double x, double y, double z) {
        double dx = g[0] - x;
        double dy = g[1] - y;
        double dz = g[2] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Distance to the nearest guard ({@code +∞} without one). */
    public double distance(double x, double y, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (double[] g : guards) {
            best = Math.min(best, zoneDist(g, x, y, z));
        }
        return best;
    }

    /**
     * How far {@code (x, y, z)} is from guard {@code g} for its zone: the distance feet to feet within the zone's height
     * ({@link #GUARD_BELOW} below to {@link #GUARD_ABOVE} above the guard), beyond it never inside (more than the radius,
     * growing with every block too high / too low - so climbing back counts as getting closer).
     */
    private double zoneDist(double[] g, double x, double y, double z) {
        double d = dist(g, x, y, z);
        double dy = y - g[1];
        double excess = dy < -GUARD_BELOW ? -GUARD_BELOW - dy : dy > GUARD_ABOVE ? dy - GUARD_ABOVE : 0.0D;
        return excess == 0.0D ? d : Math.max(d, radius) + 2.0D * excess;
    }

    /** Within the zone's height around guard {@code g}. */
    private static boolean inBand(double[] g, double y) {
        double dy = y - g[1];
        return dy >= -GUARD_BELOW && dy <= GUARD_ABOVE;
    }

    /** Within the radius of a guard (or on the strip to a guard nearby). Tax mode: the player is taxed now. */
    public boolean inside(double x, double y, double z) {
        if (inCorridor(x, y, z)) {
            return true;
        }
        return scoreboard() ? taxed : within(x, y, z, 0.0D, BRIDGE_SLACK + BRIDGE_INSIDE);
    }

    /**
     * Look-ahead: will a point ahead still be guarded? Guards known: inside their circles (or a strip between two).
     * Tax mode: not on or next to a block learned to be outside. Nothing known: yes.
     */
    public boolean predictInside(double x, double y, double z) {
        if (inCorridor(x, y, z)) {
            return true;
        }
        if (!guards.isEmpty()) {
            return within(x, y, z, 0.0D, BRIDGE_SLACK + BRIDGE_INSIDE);
        }
        if (scoreboard()) {
            return !nearOutside(x, y, z, 1);
        }
        return true;
    }

    /** Well inside: at least {@code margin} blocks within a guard's circle, or well on a strip between two. */
    public boolean insideBy(double x, double y, double z, double margin) {
        if (inCorridor(x, y, z)) {
            return true;
        }
        return scoreboard() ? taxed : within(x, y, z, margin, Math.max(0.5D, BRIDGE_SLACK - margin / 2.0D));
    }

    /**
     * Within {@code radius - margin} of a guard, on the strip between two joined guards ({@code slack}) - or on ground
     * the sidebar's guard tax showed to be guarded (learned, also in earlier runs) and not next to ground it showed to be
     * outside. The circles are only a model (a guard standing higher makes them smaller there); the tax is the server's
     * own answer.
     */
    private boolean within(double x, double y, double z, double margin, double slack) {
        if (learnedOutside(x, y, z)) {
            // The server said "no tax" here: the model (circles, strips) never overrules it.
            return false;
        }
        return distance(x, y, z) <= radius - margin || bridged(x, y, z, slack) || learnedInside(x, y, z);
    }

    /** On or next to (1 block) ground learned to be untaxed, and not itself learned taxed. */
    private boolean learnedOutside(double x, double y, double z) {
        if (outside.isEmpty()) {
            return false;
        }
        long cell = Pos.pack((int) Math.floor(x), (int) Math.floor(y + 0.01D), (int) Math.floor(z));
        return !inside.contains(cell) && nearOutside(x, y, z, 1);
    }

    /** Ground learned to be taxed (with its margin) and not next to ground learned to be outside. */
    private boolean learnedInside(double x, double y, double z) {
        return !insideNear.isEmpty() && nearTaxed(x, y, z) && !nearOutside(x, y, z, 1);
    }

    /**
     * On the strip between two guards whose circles are at most {@value #GAP} blocks apart: the distances to both
     * add up to at most their distance plus {@code slack}.
     */
    boolean bridged(double x, double y, double z, double slack) {
        int n = guards.size();
        for (int i = 0; i < n; i++) {
            double[] a = guards.get(i);
            double da = zoneDist(a, x, y, z);
            if (da > 2.0D * radius + GAP) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                double[] b = guards.get(j);
                double apart = dist(b, a[0], a[1], a[2]);
                if (apart <= 2.0D * radius + GAP && da + zoneDist(b, x, y, z) <= apart + slack) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How far from a guard the macro's own ways may lead: the radius less {@link #EDGE}. */
    public double limit() {
        return Math.max(1.0D, radius - edge);
    }

    /**
     * Whether a step to {@code (x, y, z)} leaves the guarded area (its {@link #limit()}): outside it and not closer to a
     * guard than {@code (fromX, fromY, fromZ)}. Without any guard (and no tax) nothing is blocked.
     */
    public boolean blocks(double x, double y, double z, double fromX, double fromY, double fromZ) {
        if (inCorridor(x, y, z)) {
            return false;
        }
        if (scoreboard()) {
            // Not next to a block known to be outside (from outside: anything, the way back is planned). Standing
            // next to one already: only not onto it, so it can still walk away from the edge.
            return taxed && nearOutside(x, y, z, nearOutside(fromX, fromY, fromZ) ? 0 : OUTSIDE_MARGIN);
        }
        if (guards.isEmpty() || within(x, y, z, edge, BRIDGE_SLACK)) {
            return false;
        }
        return distance(x, y, z) >= distance(fromX, fromY, fromZ);
    }

    /** The nearest guard {x, y, z, last seen} ({@code null} without one). */
    public double @org.jspecify.annotations.Nullable [] nearest(double x, double y, double z) {
        double[] best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (double[] g : guards) {
            double d = dist(g, x, y, z);
            if (d < bestD) {
                bestD = d;
                best = g;
            }
        }
        return best;
    }

    /**
     * Whether a planned way may go over node {@code key} without leaving the area: inside it ({@link #limit()}), or
     * outside but closer to a guard than the player at {@code (px, py, pz)} within 3 blocks of the player (the way
     * back in). Tax mode: not next to a block known to be outside, and over ground known to be taxed or near a known
     * guard (user: as much of the mine as possible, not only where it has walked before) - again except within 3
     * blocks of the player. Without anything known: everywhere.
     */
    boolean guardedNode(long key, double px, double py, double pz) {
        double cx = Pos.x(key) + 0.5D;
        double cz = Pos.z(key) + 0.5D;
        int y = Pos.y(key);
        boolean nearPlayer = Math.abs(cx - px) <= 3.0D && Math.abs(cz - pz) <= 3.0D && Math.abs(y - py) <= 3.0D;
        if (scoreboard()) {
            return nearPlayer || !nearOutside(cx, y, cz) && (nearTaxed(cx, y, cz) || nearGuard(cx, y, cz));
        }
        if (guards.isEmpty() || within(cx, y, cz, edge, BRIDGE_SLACK)) {
            return true;
        }
        // Outside: only the few blocks back in from where the player stands. A way out and round outside would be
        // refused by the steering further on (it compares with where the player is then).
        return nearPlayer && distance(cx, y, cz) < distance(px, py, pz);
    }

    /**
     * Excursions: an unguarded node at most {@link #outsideReach()} blocks (sideways) from guarded ground. Radius mode:
     * the circles / strips that much wider. Tax mode: known taxed ground (with its margin) that near, or a guard's circle
     * that much wider - ground learned to be outside counts too, that is what an excursion crosses.
     */
    private boolean withinReach(long key) {
        int reach = outsideReach();
        double cx = Pos.x(key) + 0.5D;
        double cz = Pos.z(key) + 0.5D;
        int y = Pos.y(key);
        if (!scoreboard()) {
            return distance(cx, y, cz) <= limit() + reach || bridged(cx, y, cz, BRIDGE_SLACK + 2.0D * reach);
        }
        double r = Math.max(1.0D, radius - 1.0D) + reach;
        for (double[] g : guards) {
            double dx = g[0] - cx;
            double dy = g[1] - y;
            double dz = g[2] - cz;
            // Excursions go sideways: never below / above the zone's height (under a guard there is no tax).
            if (dx * dx + dy * dy + dz * dz <= r * r && inBand(g, y)) {
                return true;
            }
        }
        // insideNear already holds taxed ground widened by INSIDE_MARGIN.
        int scan = Math.max(0, reach - INSIDE_MARGIN);
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                if (insideNear.contains(Pos.pack(Pos.x(key) + dx, y, Pos.z(key) + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The most unguarded nodes in a row along {@code nodes} ({@link #guardedNode}, player at {@code (px, py, pz)});
     * a way that ends unguarded counts as {@link Integer#MAX_VALUE} (the free steering would go on out there).
     */
    public int longestOutside(long[] nodes, double px, double py, double pz) {
        if (isEmpty()) {
            return 0;
        }
        int run = 0;
        int longest = 0;
        for (long node : nodes) {
            run = guardedNode(node, px, py, pz) ? 0 : run + 1;
            longest = Math.max(longest, run);
        }
        return run > 0 ? Integer.MAX_VALUE : longest;
    }

    /**
     * The unguarded nodes at the start of {@code nodes} before the first guarded one ({@link #guardedNode}, no
     * exception near the player); {@link Integer#MAX_VALUE} = never guarded. The way back in after an excursion may
     * only lead this far through unguarded ground - never deeper out first.
     */
    public int leadingOutside(long[] nodes) {
        if (isEmpty()) {
            return 0;
        }
        double nan = Double.NaN;
        for (int i = 0; i < nodes.length; i++) {
            if (guardedNode(nodes[i], nan, nan, nan)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * A goal for the way back into the zone: well inside ({@link #deepNode}, 2 blocks to each side) and, with
     * {@code learnedOnly}, next to ground where the server showed the tax (tax mode) - not only a model's circle.
     */
    public boolean safeNode(long key, boolean learnedOnly) {
        if (!deepNode(key, 2)) {
            return false;
        }
        if (!learnedOnly || !scoreboard()) {
            return true;
        }
        return nearTaxed(Pos.x(key) + 0.5D, Pos.y(key), Pos.z(key) + 0.5D);
    }

    /**
     * Path costs for the pathfinder: {@code base}, every node off the area ({@link #guardedNode}) forbidden
     * ({@code +∞}) - or, with an {@link #outsideBudget}, allowed within {@link #outsideReach()} of guarded ground at
     * {@value #OUTSIDE_COST} more (the planner checks the whole way with {@link #longestOutside}). Node = feet block.
     * Call it on a {@link #copy()} when the pathfinder runs on another thread.
     */
    public Long2DoubleMap penalties(Long2DoubleMap base, double px, double py, double pz) {
        GuardArea area = this;
        if (area.isEmpty()) {
            return base;
        }
        it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap reachCache = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
        return new AbstractLong2DoubleMap() {
            @Override
            public double get(long key) {
                double cost = base.get(key);
                if (cost == Double.POSITIVE_INFINITY || area.guardedNode(key, px, py, pz)) {
                    return cost;
                }
                if (area.outsideBudget == 0) {
                    return Double.POSITIVE_INFINITY;
                }
                byte known = reachCache.get(key);
                if (known == 0) {
                    known = area.withinReach(key) ? (byte) 1 : (byte) 2;
                    reachCache.put(key, known);
                }
                return known == 1 ? cost + OUTSIDE_COST : Double.POSITIVE_INFINITY;
            }

            @Override
            public int size() {
                // Never "empty": every node has to be asked.
                return Math.max(1, base.size());
            }

            @Override
            public boolean containsKey(long key) {
                return get(key) != 0.0D;
            }

            @Override
            public ObjectSet<Long2DoubleMap.Entry> long2DoubleEntrySet() {
                throw new UnsupportedOperationException("computed per node");
            }
        };
    }
}
