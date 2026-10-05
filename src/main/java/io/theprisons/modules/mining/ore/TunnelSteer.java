package io.theprisons.modules.mining.ore;

import io.theprisons.core.control.RotationMath;
import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.VoxelView;
import io.theprisons.core.nav.Walkability;
import io.theprisons.modules.mining.ore.route.RouteCorridor;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.LongToDoubleFunction;

/**
 * Decides every tick (every block) where to walk next.
 *
 * <p>It may walk anywhere walkable except hand-placed borders (and the edge of another ore's mine). It heads where
 * the most ore is (on the way and in the cone around it), walks as straight as possible and does not turn round while
 * there is still a way ahead.</p>
 */
public final class TunnelSteer {

    static final int RAYS = 24;
    static final double STEP_DEGREES = 360.0D / RAYS;
    static final double RANGE = 32.0D;

    /** How far before the first ore of another package a way ends. */
    private static final double FOREIGN_MARGIN = 2.0D;

    private static final double OPEN_WAY = 8.0D;
    private static final double SAMPLE = 0.5D;
    private static final double HALF_BODY = 0.35D;

    static final double SWITCH_FACTOR = 1.3D;
    /**
     * Free mode, the tunnel is walked to its end: the way held is kept - whatever the other directions promise - while
     * it goes on at least {@value #TUNNEL_MIN_FREE} blocks and its floor strip (5 wide, the next {@value #TUNNEL_CHECK}
     * blocks) has at most {@value #STONE_RATIO}x as much stone / deepslate as ore (= less than 80 % more stone).
     */
    static final double TUNNEL_CHECK = 16.0D;
    static final double TUNNEL_MIN_FREE = 3.0D;
    static final double STONE_RATIO = 1.8D;
    /**
     * Free mode, top rule: while there is ore within this many blocks on the way ahead, it walks on there and mines -
     * no other way is looked at. Only when the next {@value} blocks ahead have no ore the way is picked anew.
     */
    static final double ORE_AHEAD = 5.0D;
    /** Free mode: no ore on the lane for this many blocks → the other lanes of the way are looked at. */
    static final double LANE_EMPTY = 3.0D;
    /** Free mode: another lane is only worth it with at least this many more ores (not for 1-2 blocks more). */
    static final int LANE_MORE_ORE = 3;
    /** Free mode: lanes up to this many blocks beside the current one. */
    static final int LANE_MAX = 5;
    /** Free mode: moved over to the lane (within this many blocks): lanes may be decided again. */
    static final double LANE_ARRIVED = 0.6D;
    /** Free mode: a new way must go on at least this far (or have ore right ahead). */
    static final double WAY_MIN_FREE = 4.0D;
    /** Free mode: the way ends when less than this is left (with ore right ahead: walked up to the wall). */
    static final double WAY_END = 2.5D;
    /** Free mode: the steering aims at the lane line this far ahead, at most {@value #MAX_PURSUIT}° off the way. */
    static final double PURSUIT = 3.0D;
    static final double MAX_PURSUIT = 45.0D;
    /**
     * Free mode: a way counts fully only when it goes on this far - a way along the tunnel beats a slanting one that
     * soon meets the wall.
     */
    static final double WAY_LENGTH = 16.0D;
    /**
     * After a turn of more than {@value #BIG_TURN}° the new way is kept for {@value #COMMIT_BLOCKS} blocks: another
     * direction must be {@value #COMMIT_SWITCH}x better in that time (a blocked way is left at once).
     */
    static final double BIG_TURN = 30.0D;
    static final double COMMIT_BLOCKS = 2.5D;
    static final double COMMIT_SWITCH = 2.0D;
    /**
     * Ore density: every exposed target ore within {@value #RANGE} blocks (3 below to 1 above the feet) is put into
     * {@value #SECTORS} direction sectors; a way also gets the ores in the {@value #SECTOR_HALF}° cone around it that
     * lie at most {@value #SECTOR_REACH} blocks beyond where it ends - so the macro heads where more ore is, not only
     * where ore lies right on its strip.
     */
    static final int SECTORS = 72;
    static final double SECTOR_HALF = 20.0D;
    static final double SECTOR_REACH = 6.0D;
    static final double SECTOR_WEIGHT = 0.5D;
    /** The density map is rebuilt when the player moved to another block or after this long. */
    private static final long DENSITY_MS = 100L;
    private static final double MIN_FREE = 1.5D;
    /** Value of a free block without ore - only a tie-breaker, so an empty long way never beats a short one with ore. */
    private static final double EMPTY_VALUE = 0.005D;
    /** Another way is taken over the one held / a worth-finishing one when it scores this many times as much. */
    static final double MUCH_RICHER = 2.0D;
    /** Ways at most this far apart (°, the next ray beside) count as the same tunnel. */
    static final double SAME_TUNNEL = 20.0D;

    /** Ore this many blocks away counts half. */
    private static final double ORE_HALF_DISTANCE = 16.0D;

    /** One tick's decision. */
    public record Decision(
            float yaw,
            boolean forward,
            boolean jump,
            double free,
            double ore,
            float heading,
            double[] aheadFeet,
            double[] aheadDist,
            boolean deadEnd,
            boolean oreAhead
    ) {
        /** Ore per block of the chosen way. */
        public double value() {
            return ore / (free + 2.0D);
        }
    }

    /**
     * What walking a direction in thought found.
     */
    record Ray(
            double heading,
            double free,
            double ore,
            int rises,
            double overlap,
            double[] feet,
            double[] dist,
            int floorOre,
            int floorStone,
            double firstOre,
            int laneOre
    ) {
        /** Ore within {@link #ORE_AHEAD} blocks on this way (and room to walk there). */
        boolean oreAhead() {
            return free >= MIN_FREE && firstOre <= ORE_AHEAD;
        }

        /** Worth walking on to the end: ore on the floor ahead and less than 80 % more stone than ore. */
        boolean worthFinishing() {
            return free >= TUNNEL_MIN_FREE && floorOre > 0 && floorStone <= STONE_RATIO * floorOre;
        }

        Ray withFree(double newFree) {
            return new Ray(heading, newFree, ore, rises, overlap, feet, dist, floorOre, floorStone, firstOre, laneOre);
        }
    }

    private double held = Double.NaN;
    /** Free mode: the way held - its yaw ({@code NaN} = none yet) and a point on its line. */
    private double axis = Double.NaN;
    private double axisX;
    private double axisZ;
    /** Free mode: the lane = blocks beside the way's line. */
    private double lane;
    /**
     * Free mode: the lane is the tunnel's middle (not a lane beside it for more ore). It is then moved along with the
     * middle once every block, so the macro keeps to the middle when the tunnel shifts sideways.
     */
    private boolean laneMiddle = true;
    private double centreAlong;
    /** Free mode: re-centred when the middle is this far (blocks) off the lane. */
    static final double CENTRE_OFF = 0.75D;
    /** ... while still moving over to the lane. */
    static final double CENTRE_OFF_MOVING = 1.0D;
    /** An ore lane is held at least this many blocks (unless its ore is gone) before the middle takes over again. */
    static final double LANE_HOLD = 3.0D;
    private double laneSince;
    /** Free mode: the middle is measured this far ahead. */
    static final double CENTRE_AHEAD = 3.0D;
    /**
     * Free mode: the way bends with the tunnel. Once every block the tunnel's middle is measured {@value #BEND_NEAR}
     * and {@value #BEND_FAR} blocks ahead; when the line through both is more than {@value #BEND_MIN}° off the way, the
     * way is laid onto it (at most {@value #BEND_MAX}° at once) - so the macro walks the tunnel's middle round curves and
     * slants instead of straight on into the wall.
     */
    static final double BEND_NEAR = 2.0D;
    static final double BEND_FAR = 7.0D;
    static final double BEND_MIN = 8.0D;
    static final double BEND_MAX = 15.0D;
    private double bendAlong;
    /** The side the middle line bent to on the last block (0 = none): a bend is taken only when it repeats. */
    private int bendSign;
    /** Free mode: the tunnel's floor and walls around the player, from the shape of the ground (see {@link TunnelMap}). */
    private final TunnelMap map = new TunnelMap();
    /** Tunnel centring on (the middle between the walls is followed) or off (straight ways from wall to wall). */
    private boolean centring = true;

    public void centring(boolean on) {
        centring = on;
    }
    /** Diagnostics: why the last cast ended, and the last way change (taken by the module for the log). */
    private String castEnd = "";
    private @org.jspecify.annotations.Nullable String wayChange;

    /** The last way change ("why the way ended → the new one"), once; {@code null} = none since the last call. */
    public @org.jspecify.annotations.Nullable String takeWayChange() {
        String w = wayChange;
        wayChange = null;
        return w;
    }
    /** The map is the one of this tick (rays end at its walls); false outside {@link #decide}. */
    private boolean mapLive;
    /**
     * The walls are looked for this far (blocks) to both sides of the lane; no wall on a side within it = a cave (no
     * middle to keep to).
     */
    static final double MIDDLE_SCAN = 16.0D;
    private static final double MIDDLE_STEP = 0.25D;
    /** A lane point on a wall: the floor is looked for this far beside it. */
    private static final double MIDDLE_FIND = 3.0D;
    /**
     * Tunnel or cave: a cave after {@value #CAVE_BLOCKS} blocks in a row without walls on both sides (no middle), a
     * tunnel again after {@value #TUNNEL_BLOCKS} blocks with a middle (hysteresis, no flicker at a niche).
     */
    static final int CAVE_BLOCKS = 3;
    /** A cave has more than this many ways leading out ... */
    static final int CAVE_ENTRANCES = 3;
    /** ... and at least this many blocks between its walls (a tunnel is narrower). */
    static final double CAVE_WIDTH = 14.0D;
    static final int ENTRANCE_RAYS = 72;
    static final double ENTRANCE_REACH = 20.0D;
    /** The last tunnel / cave change for the log ({@code null} = none since the last call). */
    private @org.jspecify.annotations.Nullable String caveChange;

    /** The last tunnel / cave change ("cave at x z: n entrances, w blocks between the walls"), once. */
    public @org.jspecify.annotations.Nullable String takeCaveChange() {
        String c = caveChange;
        caveChange = null;
        return c;
    }
    static final int TUNNEL_BLOCKS = 2;
    private boolean cave;
    private int caveRun;
    private double caveAlong;
    /**
     * Ore lanes stay close to the middle: at most this far (blocks) beside it - the macro only swerves slightly for
     * ore still standing beside the mined middle.
     */
    static final double LANE_ASIDE = 1.0D;
    /** The tunnel's middle as last measured, in lane offsets of the held way ({@code NaN} = not known). */
    private double lastMiddle = Double.NaN;
    /** The 6-block rule's look aside: lanes up to this far beside the current one. */
    static final int ASIDE = 3;
    /**
     * Pocket guard: {@value #POCKET_TURNS} turns of more than {@value #POCKET_TURN}° within {@value #POCKET_RADIUS}
     * blocks and {@value #POCKET_MS} ms = trapped (walking back and forth between two way ends) - no way on is reported.
     */
    static final int POCKET_TURNS = 3;
    static final double POCKET_TURN = 135.0D;
    static final double POCKET_RADIUS = 3.0D;
    static final long POCKET_MS = 15_000L;
    private final java.util.ArrayDeque<double[]> pocketTurns = new java.util.ArrayDeque<>();
    private long now;
    /**
     * Planned route: the direction of its current leg ({@code NaN} = no route). The free mode walks it as its way -
     * in the middle, lanes beside it only for ore - instead of looking for a way of its own.
     */
    private double planned = Double.NaN;
    private boolean plannedFresh;
    /** Where the route's direction was last tried while the way held is off it. */
    private double planX = Double.NaN;
    private double planZ = Double.NaN;
    /**
     * Planned route: the way is turned onto the route's direction when it is this far (°) off it - a branch, not the
     * same tunnel slanting (that one is followed in its middle, see {@link #bend}).
     */
    static final double PLAN_OFF = 45.0D;
    /** Free mode: the player got stuck - pick another way. */
    private boolean forceWay;
    /**
     * Free mode: the direction the player really walked over the last {@value #TRAVEL_BLOCKS} blocks ({@code NaN} =
     * not known yet). A new way more than {@value #TRAVEL_TURN}° off it counts as back - so several ways picked one
     * after the other cannot turn it round step by step.
     */
    private double travelDir = Double.NaN;
    private double anchorX = Double.NaN;
    private double anchorZ = Double.NaN;
    static final double TRAVEL_BLOCKS = 3.0D;
    static final double TRAVEL_TURN = 100.0D;

    private double blockedHeading = Double.NaN;
    private long blockedUntil;
    /** Where the last big turn was made ({@code NaN} = none yet): the new way is kept for a few blocks. */
    private double turnX = Double.NaN;
    private double turnZ = Double.NaN;
    /** Ore density per sector and distance ({@link #SECTORS} x {@code RANGE + 1}, summed up over the distance). */
    private double[][] density = new double[SECTORS][(int) RANGE + 1];
    private long densityKey = Long.MIN_VALUE;
    private long densityAt = Long.MIN_VALUE;
    private BorderZones borders = new BorderZones();
    /** The guarded area: ways end where they would leave it (see {@link GuardArea}). */
    private GuardArea guards = new GuardArea();
    /** Route guide: the segment from the last waypoint to the next one ({@code null} = free tunnel mode). */
    private int @org.jspecify.annotations.Nullable [] guideFrom;
    private int @org.jspecify.annotations.Nullable [] guideTo;
    /** Directions this far off the route line's direction count half. */
    static final double GUIDE_DEGREES = 40.0D;
    /**
     * The lane (sideways offset from the route line) the macro walks on. It changes only after the player really moved
     * over by more than {@value #LANE_SWITCH} blocks, and ways that leave it count less - so it stays on one lane
     * (middle, or e.g. 2 blocks left because the middle is empty) instead of weaving left and right.
     */
    private double guideLane = Double.NaN;
    static final double LANE_SWITCH = 1.5D;
    /** Route mode: directions more than this far off the route line's direction would walk back - never taken. */
    static final double BACK_DEGREES = 90.0D;
    /** A way ending this far off the current lane (4 blocks ahead) counts half. */
    static final double LANE_KEEP = 2.0D;
    private static final double LANE_LOOKAHEAD = 4.0D;
    /**
     * Route mode, lane change: the strip the pickaxe mines (the block plus {@value #MINE_HALF} left and right = 5 wide)
     * is checked {@value #LANE_AHEAD} blocks ahead, live every tick, for every lane inside the corridor ({@link
     * RouteCorridor#NEAR} blocks beside the route line; {@link RouteCorridor#WIDTH} while the current lane has fewer than
     * {@value #FEW_ORE} ores). Each lane gets its ores per block walked: its ore count
     * over the way there (straight over to it while walking {@value #LANE_AHEAD} blocks on). As soon as a lane promises
     * more ore per second than the current one - at least {@value #LANE_GAIN} ores and {@value #LANE_GAIN_RATIO}x more
     * over the strip, so equal lanes do not make it weave - the macro turns straight over to it at once. Only lanes whose
     * 5 floor columns are all walkable (2 blocks of floor to the wall) count.
     */
    static final int LANE_AHEAD = 10;
    static final int MINE_HALF = 2;
    static final double LANE_GAIN = 2.0D;
    static final double LANE_GAIN_RATIO = 1.1D;
    /** Fewer ores than this in the current lane's strip = little ore: lanes up to {@link RouteCorridor#WIDTH} count. */
    static final int FEW_ORE = 3;
    /** The lane the macro is moving over to ({@code NaN} = none). */
    private double targetLane = Double.NaN;

    /** Floor of one lane's mining strip ahead: stone / deepslate blocks, ore blocks, and whether it is all floor. */
    record Strip(int stone, int ore, boolean walkable) {
    }

    /**
     * Route mode: the waypoints are a guide, the steering stays the same (straight, in the middle, mining on the
     * move). Directions towards {@code to} are preferred, and ways end where they would leave the corridor of
     * {@link RouteCorridor#WIDTH} blocks beside the line {@code from → to}, so the macro only moves aside when there is
     * ore. {@code null} switches the guide off.
     */
    public void guide(int @org.jspecify.annotations.Nullable [] from, int @org.jspecify.annotations.Nullable [] to) {
        if (!Arrays.equals(from, guideFrom) || !Arrays.equals(to, guideTo)) {
            // New segment: the lane is taken over from where the player is on it.
            guideLane = Double.NaN;
            targetLane = Double.NaN;
        }
        guideFrom = from;
        guideTo = to;
    }

    /**
     * A planned route's leg: walk this direction (free mode, see {@link #planned}); {@code NaN} = no route. A new
     * direction starts a new way from the player at once.
     */
    public void direct(double yaw) {
        if (Double.isNaN(yaw)) {
            planned = Double.NaN;
            plannedFresh = false;
            return;
        }
        if (Double.isNaN(planned) || Math.abs(RotationMath.wrap((float) (yaw - planned))) > 1.0D) {
            planned = RotationMath.wrap((float) yaw);
            plannedFresh = true;
        }
    }

    /** Diagnostics: the held way, lane and mode in one short line. */
    public String debugState() {
        return String.format(java.util.Locale.ROOT, "way %s lane %.1f%s %s%s", Double.isNaN(axis) ? "-" : String.format(java.util.Locale.ROOT, "%.0f°", axis),
                lane, laneMiddle ? " (middle)" : " (ore lane)", cave ? "cave" : "tunnel",
                Double.isNaN(planned) ? "" : String.format(java.util.Locale.ROOT, " planned %.0f°", planned));
    }

    /** In a cave (the walls opened up; see {@link #CAVE_BLOCKS}): the module looks for the best tunnel then. */
    public boolean inCave() {
        return cave;
    }

    /**
     * The 6-block rule's look aside: before the tunnel is left for lack of ore, the lanes up to {@value #ASIDE} blocks
     * left and right of the current one are checked; one with ore within {@value #ORE_AHEAD} blocks (inside the tunnel's
     * walls) becomes the lane - the nearer one, more ore on a tie. True when one was found.
     */
    public boolean lookAside(VoxelView view, IntPredicate isTarget, IntPredicate isForeign, double x, double feetY, double z,
                             LongSet walked) {
        if (Double.isNaN(axis)) {
            return false;
        }
        Walkability walk = new Walkability(view, 3).exempt((int) Math.floor(x), (int) Math.floor(z));
        double rad = Math.toRadians(axis);
        double along = along(x, z);
        for (int k = 1; k <= ASIDE; k++) {
            double best = Double.NaN;
            int bestOre = 0;
            for (int sign = -1; sign <= 1; sign += 2) {
                double candidate = lane + sign * k;
                double px = axisX - Math.sin(rad) * along + Math.cos(rad) * candidate;
                double pz = axisZ + Math.cos(rad) * along + Math.sin(rad) * candidate;
                if (!map.way(px, pz)) {
                    continue;
                }
                Ray r = laneRay(view, walk, isTarget, isForeign, x, feetY, z, candidate, walked);
                if (r.free() >= MIN_FREE && r.oreAhead() && r.laneOre() > bestOre) {
                    best = candidate;
                    bestOre = r.laneOre();
                }
            }
            if (!Double.isNaN(best)) {
                lane = best;
                laneMiddle = false;
                return true;
            }
        }
        return false;
    }

    /** Hand-placed borders: ways end at their edge (see {@link BorderMarks}). */
    public void borders(BorderZones zones) {
        borders = zones;
    }

    /** The guarded area (an empty one = no limit): ways end at its edge. */
    public void guards(GuardArea area) {
        guards = area;
    }

    public void reset() {
        held = Double.NaN;
        blockedHeading = Double.NaN;
        blockedUntil = 0L;
        turnX = Double.NaN;
        turnZ = Double.NaN;
        densityKey = Long.MIN_VALUE;
        axis = Double.NaN;
        lane = 0.0D;
        laneMiddle = true;
        forceWay = false;
        plannedFresh = !Double.isNaN(planned);
        travelDir = Double.NaN;
        anchorX = Double.NaN;
        anchorZ = Double.NaN;
        cave = false;
        caveRun = 0;
        caveAlong = 0.0D;
    }

    /**
     * The player got stuck walking this way: avoid directions within 30°
     * of it until {@code untilMs}.
     */
    public void block(
            double heading,
            long untilMs
    ) {
        blockedHeading = heading;
        blockedUntil = untilMs;
        held = Double.NaN;
        forceWay = true;
    }

    public Decision decide(
            VoxelView view,
            IntPredicate isTarget,
            double x,
            double feetY,
            double z,
            float yaw,
            boolean onGround,
            LongSet walked,
            LongToDoubleFunction zoneFactor,
            long nowMs
    ) {
        return decide(
                view,
                isTarget,
                key -> false,
                x,
                feetY,
                z,
                yaw,
                onGround,
                walked,
                zoneFactor,
                new double[0][],
                nowMs
        );
    }

    /**
     * @param isForeign ores of packages that are not selected
     * @param wardens positions {x, y, z} of guard NPCs
     *
     * <p>Wardens are NOT obstacles and their 15-block radius is not a
     * hard movement boundary. Their distance only influences steering.</p>
     */
    public Decision decide(
            VoxelView view,
            IntPredicate isTarget,
            IntPredicate isForeign,
            double x,
            double feetY,
            double z,
            float yaw,
            boolean onGround,
            LongSet walked,
            LongToDoubleFunction zoneFactor,
            double[][] wardens,
            long nowMs
    ) {
        boolean avoid =
                !Double.isNaN(blockedHeading)
                        && nowMs < blockedUntil;

        Walkability walk =
                new Walkability(view, 3).exempt((int) Math.floor(x), (int) Math.floor(z));

        updateDensity(view, isTarget, x, feetY, z, nowMs);
        map.update(walk, (int) Math.floor(x), (int) Math.floor(feetY + 0.01D), (int) Math.floor(z));
        // Free mode only: a recorded route (e.g. a spiral stair) is walked as recorded, whatever the ground's shape -
        // and a planned route too: the planner checked its way (a slope the map calls wall may be its way up).
        mapLive = guideTo == null && Double.isNaN(planned);
        now = nowMs;
        if (guideTo == null) {
            return decideFree(view, walk, isTarget, isForeign, x, feetY, z, yaw, onGround, walked, zoneFactor, avoid);
        }
        updateLane(x, z);
        chooseLane(view, walk, isTarget, x, feetY, z);

        double reference =
                Double.isNaN(held)
                        ? yaw
                        : held;

        Ray best = null;
        double bestScore = -Double.MAX_VALUE;

        Ray kept = null;
        double keptScore = -Double.MAX_VALUE;

        boolean forwardOpen = false;

        for (int i = 0; i < RAYS; i++) {
            double heading =
                    reference
                            + i * STEP_DEGREES;

            if (goesBack(heading)) {
                // Route mode: never a step back along the route line - only straight on or aside.
                continue;
            }

            Ray ray =
                    cast(
                            view,
                            walk,
                            isTarget,
                            isForeign,
                            x,
                            feetY,
                            z,
                            heading,
                            walked
                    );

            if (ray.free() < MIN_FREE) {
                continue;
            }

            /*
             * Wardens influence steering scores only. Their 15-block
             * circles are not hard movement boundaries, so do not clip
             * or reject rays when they cross a circle edge.
             */
            double turn =
                    Math.abs(
                            RotationMath.wrap(
                                    (float)
                                            (heading - reference)
                            )
                    );

            if (turn <= 90.0D) {
                forwardOpen = true;
            }

            int ex =
                    (int) Math.floor(
                            x
                                    - Math.sin(
                                    Math.toRadians(
                                            heading
                                    )
                            )
                                    * Math.min(
                                    ray.free(),
                                    4.0D
                            )
                    );

            int ez =
                    (int) Math.floor(
                            z
                                    + Math.cos(
                                    Math.toRadians(
                                            heading
                                    )
                            )
                                    * Math.min(
                                    ray.free(),
                                    4.0D
                            )
                    );

            double score =
                    Math.min(
                            ray.free(),
                            OPEN_WAY
                    )
                            / OPEN_WAY
                            * (
                            ray.ore()
                                    + SECTOR_WEIGHT * sectorOre(heading, ray.free())
                                    + EMPTY_VALUE
                                    * ray.free()
                    )
                            * turnFactor(turn)
                            // Route mode: a stair (e.g. a spiral one) is simply walked up, no detour (no rise factor).
                            * (
                            1.0D
                                    - LanePlanner.OVERLAP_PENALTY
                                    * ray.overlap()
                    )
                            * zoneFactor.applyAsDouble(
                            LanePlanner.zoneOf(
                                    Pos.pack(
                                            ex,
                                            (int) Math.floor(
                                                    feetY
                                            ),
                                            ez
                                    )
                            )
                    );

            score *= directionFactor(heading);

            if (avoid
                    && Math.abs(
                    RotationMath.wrap(
                            (float)
                                    (heading
                                            - blockedHeading)
                    )
            ) < 30.0D) {
                score *= 0.05D;
            }

            score *= laneFactor(heading, x, z);

            if (score > bestScore) {
                bestScore = score;
                best = ray;
            }

            if (turn < STEP_DEGREES * 0.5D
                    && !Double.isNaN(held)) {

                kept = ray;
                keptScore = score;
            }
        }

        if (best == null) {
            return new Decision(
                    yaw,
                    false,
                    false,
                    0.0D,
                    0.0D,
                    yaw,
                    new double[0],
                    new double[0],
                    true,
                    false
            );
        }

        // Straight on first: the way held is only left for a clearly better one - right after a turn even more so.
        boolean committed = !Double.isNaN(turnX) && Math.hypot(x - turnX, z - turnZ) < COMMIT_BLOCKS;
        Ray chosen =
                kept != null
                        && keptScore * (committed ? COMMIT_SWITCH : SWITCH_FACTOR) >= bestScore
                        ? kept
                        : best;

        if (Double.isNaN(held)
                || Math.abs(RotationMath.wrap((float) (chosen.heading() - held))) > BIG_TURN) {
            turnX = x;
            turnZ = z;
        }

        held =
                RotationMath.wrap(
                        (float) chosen.heading()
                );

        // Route mode: the lane (at least 2 blocks of floor to the wall) positions the macro - centring on top of it
        // would pull it back to the middle and make it weave.
        float steer = RotationMath.wrap((float) chosen.heading());

        boolean jump =
                onGround
                        && chosen.feet().length > 1
                        && chosen.feet()[0]
                        > feetY + 0.5D
                        && chosen.dist()[0]
                        < 1.2D
                        ||
                        onGround
                                && chosen.feet().length > 2
                                && chosen.feet()[1]
                                > feetY + 0.5D
                                && chosen.dist()[1]
                                < 1.2D;

        return new Decision(
                steer,
                true,
                jump,
                chosen.free(),
                chosen.ore(),
                (float) held,
                chosen.feet(),
                chosen.dist(),
                !forwardOpen,
                chosen.oreAhead()
        );
    }

    // ── Free tunnel mode: a way (straight line) and a lane on it ─────────────

    /**
     * Free tunnel mode ("Tunnelblick"). The macro holds a <b>way</b> - a straight line - and a <b>lane</b> on it
     * (left / middle / right: blocks beside the line). It is not re-decided every tick; only these events change it:
     * <ol>
     *     <li>Ore within {@value #ORE_AHEAD} blocks on the lane: walk on and mine, nothing else is looked at.</li>
     *     <li>No ore on the lane for the next {@value #LANE_EMPTY} blocks: another lane of the same way is taken if it
     *     has ore within {@value #ORE_AHEAD} blocks and at least {@value #LANE_MORE_ORE} more ores over the next
     *     {@value #LANE_AHEAD} blocks (not for 1-2 blocks more). While moving over nothing is re-decided.</li>
     *     <li>No ore ahead and no better lane: the way is walked on (direction lock) - it is left only where it ends or
     *     by the module's 6-block rule.</li>
     *     <li>The way ends (wall, border, guard zone edge, another ore's mine): the best way at most 90° aside, back only
     *     at a dead end.</li>
     * </ol>
     * Steering follows the lane line (pure pursuit), so curves and lane changes are smooth, not 15° steps.
     */
    private Decision decideFree(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign, double x,
                                double feetY, double z, float yaw, boolean onGround, LongSet walked,
                                LongToDoubleFunction zoneFactor, boolean avoid) {
        if (Double.isNaN(anchorX)) {
            anchorX = x;
            anchorZ = z;
        } else if (Math.hypot(x - anchorX, z - anchorZ) >= TRAVEL_BLOCKS) {
            travelDir = RotationMath.yawOf(x - anchorX, z - anchorZ);
            anchorX = x;
            anchorZ = z;
        }
        boolean deadEnd = false;
        if (plannedFresh) {
            // A planned route's (new) leg: its direction is the way from here, if it can be walked (else the way held
            // stays and the route's direction is tried again every block).
            plannedFresh = false;
            followPlanned(view, walk, isTarget, isForeign, x, feetY, z, walked);
        } else if (!Double.isNaN(planned) && !Double.isNaN(axis) && Math.abs(RotationMath.wrap((float) (axis - planned))) > PLAN_OFF
                && (Double.isNaN(planX) || Math.hypot(x - planX, z - planZ) >= 1.0D)) {
            planX = x;
            planZ = z;
            if (cast(view, walk, isTarget, isForeign, x, feetY, z, planned, walked).free() >= OPEN_WAY) {
                followPlanned(view, walk, isTarget, isForeign, x, feetY, z, walked);
            }
        }
        if (Double.isNaN(axis)) {
            // Start: straight on in the view direction - round an obstacle right ahead on a lane beside it - unless
            // there is no ore right ahead, then the best way.
            axis = RotationMath.wrap(yaw);
            axisX = x;
            axisZ = z;
            centreAlong = 0.0D;
        bendAlong = 0.0D;
        caveAlong = 0.0D;
            lane = 0.0D;
            Ray straight = laneRay(view, walk, isTarget, isForeign, x, feetY, z, 0.0D, walked);
            double detour = straight.free() < WAY_MIN_FREE
                    ? detourLane(view, walk, isTarget, isForeign, x, feetY, z, walked) : Double.NaN;
            if (!Double.isNaN(detour)) {
                lane = detour;
            } else {
                Way way = newWay(view, walk, isTarget, isForeign, x, feetY, z, yaw, walked, zoneFactor, avoid, Pick.START);
                if (way == null) {
                    axis = Double.NaN;
                    return stop(yaw);
                }
                deadEnd = way.turnedRound();
            }
        }

        double along = along(x, z);
        Ray onLane = laneRay(view, walk, isTarget, isForeign, x, feetY, z, lane, walked);
        boolean moving = Math.abs(side(x, z) - lane) > LANE_ARRIVED;

        boolean stuck = avoid && forceWay;
        forceWay = false;
        if (stuck || onLane.free() < (onLane.oreAhead() ? MIN_FREE : WAY_END)) {
            // The lane ends here (the last ore before the wall is still mined) or the player got stuck: round the
            // obstacle on another lane of the same way, else the best way on from here.
            double detour = stuck ? Double.NaN : detourLane(view, walk, isTarget, isForeign, x, feetY, z, walked);
            if (!Double.isNaN(detour)) {
                lane = detour;
            } else {
                double before = axis;
                Ray ended = laneRay(view, walk, isTarget, isForeign, x, feetY, z, lane, walked);
                String why = stuck ? "stuck" : castEnd;
                double endedFree = ended.free();
                Way way = newWay(view, walk, isTarget, isForeign, x, feetY, z, axis, walked, zoneFactor, avoid, Pick.BLOCKED);
                wayChange = String.format(java.util.Locale.ROOT, "way %.0f° ended after %.1f free (%s) at %.1f %.0f %.1f -> %s",
                        before, endedFree, why, x, feetY, z, way == null ? "no way on"
                                : String.format(java.util.Locale.ROOT, "new way %.0f° (%.1f free, %s)%s", axis, way.ray().free(),
                                castEndOf(view, walk, isTarget, isForeign, x, feetY, z, axis, walked), way.turnedRound() ? " turned round" : ""));
                if (way == null) {
                    return stop(yaw);
                }
                deadEnd = way.turnedRound();
                if (Math.abs(RotationMath.wrap((float) (axis - before))) > POCKET_TURN && inPocket(x, z)) {
                    // Turned round again and again on the same few blocks: no way on from here - the module plans one.
                    axis = Double.NaN;
                    return stop(yaw);
                }
                along = along(x, z);
            }
            onLane = laneRay(view, walk, isTarget, isForeign, x, feetY, z, lane, walked);
            moving = Math.abs(side(x, z) - lane) > LANE_ARRIVED;
        } else if (!moving && onLane.firstOre() > LANE_EMPTY) {
            // Nothing on this lane for the next blocks: a clearly richer lane of the same way?
            double richer = richerLane(view, walk, isTarget, isForeign, x, feetY, z, onLane, walked);
            if (!Double.isNaN(richer)) {
                lane = richer;
                laneMiddle = false;
                laneSince = along;
                onLane = laneRay(view, walk, isTarget, isForeign, x, feetY, z, lane, walked);
                moving = Math.abs(side(x, z) - lane) > LANE_ARRIVED;
            }
            // Direction lock: the way held is not swapped for one that scores a little better. It is left only where it
            // ends (wall, guard zone edge - above) or by the module's 6-block rule (6 blocks on stone / deepslate, the
            // lanes 3 blocks aside looked at first - see lookAside), which plans a new route.
        }
        updateCave(x, z);
        // On the middle lane the middle is followed also while still moving over to it (else a stale lane is chased);
        // a move over to an ore lane beside it is not re-decided.
        boolean follow = centring && (!moving || laneMiddle);
        if (follow && bend(view, walk, isTarget, isForeign, x, feetY, z, walked)) {
            onLane = laneRay(view, walk, isTarget, isForeign, x, feetY, z, lane, walked);
            moving = Math.abs(side(x, z) - lane) > LANE_ARRIVED;
        }
        if (follow && recentre(view, walk, isTarget, isForeign, x, feetY, z, onLane, walked, moving)) {
            onLane = laneRay(view, walk, isTarget, isForeign, x, feetY, z, lane, walked);
            moving = Math.abs(side(x, z) - lane) > LANE_ARRIVED;
        }

        float steer = pursue(x, z);
        Ray path = cast(view, walk, isTarget, isForeign, x, feetY, z, steer, walked);
        if (path.free() < MIN_FREE && moving) {
            // Something stands between the player and the lane: stay on the lane where the player is.
            lane = side(x, z);
            steer = pursue(x, z);
            path = cast(view, walk, isTarget, isForeign, x, feetY, z, steer, walked);
        }
        held = axis;

        boolean jump = onGround && (
                path.feet().length > 1 && path.feet()[0] > feetY + 0.5D && path.dist()[0] < 1.2D
                        || path.feet().length > 2 && path.feet()[1] > feetY + 0.5D && path.dist()[1] < 1.2D);
        return new Decision(
                steer,
                true,
                jump,
                onLane.free(),
                onLane.ore() + SECTOR_WEIGHT * sectorOre(axis, onLane.free()),
                (float) axis,
                path.feet(),
                path.dist(),
                deadEnd,
                onLane.oreAhead()
        );
    }

    /**
     * Takes the planned route's direction as the way from here, lane in the tunnel's middle (or richer beside it).
     * False when that way cannot be walked (the way held stays).
     */
    private boolean followPlanned(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign,
                                  double x, double feetY, double z, LongSet walked) {
        Ray ray = cast(view, walk, isTarget, isForeign, x, feetY, z, planned, walked);
        if (ray.free() < WAY_MIN_FREE) {
            return false;
        }
        axis = planned;
        axisX = x;
        axisZ = z;
        centreAlong = 0.0D;
        bendAlong = 0.0D;
        caveAlong = 0.0D;
        held = axis;
        lane = freshLane(view, walk, isTarget, isForeign, x, feetY, z, walked);
        return true;
    }

    /**
     * Keeps the lane in the tunnel's middle: once every block the middle {@value #CENTRE_AHEAD} blocks ahead is
     * measured; when it lies {@value #CENTRE_OFF}+ blocks beside the lane, the lane moves there - if the lane is the
     * middle one (and the middle has about as much ore), or if the lane beside it has run out of ore and the middle has
     * ore ahead. The middle is the point furthest from the walls (see {@link #middle}); in an open room (no wall found
     * to either side) nothing changes.
     */
    private boolean recentre(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign, double x,
                             double feetY, double z, Ray onLane, LongSet walked, boolean moving) {
        double along = along(x, z);
        if (along < centreAlong) {
            return false;
        }
        centreAlong = along + 1.0D;
        double[] mid = middle(along + CENTRE_AHEAD, lane);
        if (mid == null) {
            return false;
        }
        double target = Math.round(mid[2] * 2.0D) / 2.0D;
        lastMiddle = mid[2];
        // Still moving over to the lane: only a clearly different middle moves it again (no half-block flip-flop).
        if (Math.abs(target - lane) < (moving ? CENTRE_OFF_MOVING : CENTRE_OFF)) {
            if (!laneMiddle && Math.abs(target - lane) < 0.5D) {
                laneMiddle = true;
            }
            return false;
        }
        Ray middle = laneRay(view, walk, isTarget, isForeign, x, feetY, z, target, walked);
        if (middle.free() < Math.min(WAY_MIN_FREE, onLane.free())) {
            return false;
        }
        // The middle lane always follows the middle; a lane beside it (taken for at least LANE_MORE_ORE more ores)
        // goes back to the middle as soon as its ore ahead is mined or the middle has as much ore again.
        boolean worth = laneMiddle || onLane.firstOre() > LANE_EMPTY
                || along - laneSince >= LANE_HOLD && middle.oreAhead() && middle.laneOre() >= onLane.laneOre();
        if (!worth) {
            return false;
        }
        lane = target;
        laneMiddle = true;
        return true;
    }

    /** Records a turn round here; true when it is the {@value #POCKET_TURNS}th close by in a short time. */
    private boolean inPocket(double x, double z) {
        pocketTurns.removeIf(p -> now - (long) p[2] > POCKET_MS || Math.hypot(p[0] - x, p[1] - z) > POCKET_RADIUS);
        pocketTurns.addLast(new double[]{x, z, now});
        if (pocketTurns.size() >= POCKET_TURNS) {
            pocketTurns.clear();
            return true;
        }
        return false;
    }

    /** Once per block: tunnel (a middle between two walls here or 3 blocks ahead) or cave, with hysteresis. */
    private void updateCave(double x, double z) {
        double along = along(x, z);
        if (along < caveAlong && along > caveAlong - 3.0D) {
            // Not a block further yet (a much smaller value: the way was laid anew).
            return;
        }
        caveAlong = along + 1.0D;
        double width = crossWidth(x, z);
        int entrances = entrances(x, z);
        // A cave: much wider than a tunnel AND more than CAVE_ENTRANCES ways leading out (an ore field or a junction
        // is not one).
        boolean isCave = width >= CAVE_WIDTH && entrances > CAVE_ENTRANCES;
        if (isCave == cave) {
            caveRun = 0;
            return;
        }
        if (++caveRun >= (cave ? TUNNEL_BLOCKS : CAVE_BLOCKS)) {
            cave = !cave;
            caveRun = 0;
            caveChange = String.format(java.util.Locale.ROOT, "%s at %.0f %.0f: %s entrances, %s blocks between the walls",
                    cave ? "cave" : "tunnel", x, z, entrances == Integer.MAX_VALUE ? "open all round" : String.valueOf(entrances),
                    Double.isInfinite(width) ? ">" + (int) (2 * MIDDLE_SCAN) : String.format(java.util.Locale.ROOT, "%.0f", width));
        }
    }

    /** Blocks between the left and the right wall square to the way through the player ({@code +∞}: no wall in reach). */
    private double crossWidth(double x, double z) {
        double rad = Math.toRadians(axis);
        double sx = Math.cos(rad);
        double sz = Math.sin(rad);
        double width = 0.0D;
        for (int side = -1; side <= 1; side += 2) {
            double o = MIDDLE_STEP;
            for (; o <= MIDDLE_SCAN; o += MIDDLE_STEP) {
                double px = x + sx * side * o;
                double pz = z + sz * side * o;
                if (!map.known(px, pz) || !map.way(px, pz)) {
                    break;
                }
            }
            if (o > MIDDLE_SCAN) {
                return Double.POSITIVE_INFINITY;
            }
            width += o;
        }
        return width;
    }

    /**
     * Ways leading out from here: {@value #ENTRANCE_RAYS} directions are followed over the floor; one that gets
     * {@value #ENTRANCE_REACH} blocks far without a wall is open, neighbouring open directions are one way out (a single
     * closed direction between them does not split it). Open all round = {@link Integer#MAX_VALUE} (a hall).
     */
    private int entrances(double x, double z) {
        boolean[] open = new boolean[ENTRANCE_RAYS];
        int openCount = 0;
        for (int i = 0; i < ENTRANCE_RAYS; i++) {
            double rad = Math.toRadians(i * 360.0D / ENTRANCE_RAYS);
            double dx = -Math.sin(rad);
            double dz = Math.cos(rad);
            boolean ok = true;
            for (double d = 0.5D; d <= ENTRANCE_REACH && ok; d += 0.5D) {
                ok = map.way(x + dx * d, z + dz * d);
            }
            open[i] = ok;
            openCount += ok ? 1 : 0;
        }
        if (openCount == ENTRANCE_RAYS) {
            return Integer.MAX_VALUE;
        }
        // Close single-ray gaps, then count the runs of open directions round the circle.
        boolean[] merged = open.clone();
        for (int i = 0; i < ENTRANCE_RAYS; i++) {
            if (!open[i] && open[(i + ENTRANCE_RAYS - 1) % ENTRANCE_RAYS] && open[(i + 1) % ENTRANCE_RAYS]) {
                merged[i] = true;
            }
        }
        int runs = 0;
        for (int i = 0; i < ENTRANCE_RAYS; i++) {
            if (merged[i] && !merged[(i + ENTRANCE_RAYS - 1) % ENTRANCE_RAYS]) {
                runs++;
            }
        }
        return runs;
    }

    /**
     * Lays the way onto the tunnel's middle line (see {@link #BEND_NEAR}): measured once per block, only where a middle
     * is found at both cross-sections (see {@link #middle}) and the new line can be walked. The lane keeps its offset from the middle. True when the way changed.
     */
    private boolean bend(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign, double x,
                         double feetY, double z, LongSet walked) {
        double along = along(x, z);
        if (along < bendAlong) {
            return false;
        }
        bendAlong = along + 1.0D;
        double[] near = middle(along + BEND_NEAR, lane);
        double[] far = near == null ? null : middle(along + BEND_FAR, near[2]);
        if (near == null || far == null) {
            return false;
        }
        double dir = RotationMath.yawOf(far[0] - near[0], far[1] - near[1]);
        double turn = RotationMath.wrap((float) (dir - axis));
        if (Math.abs(turn) <= BEND_MIN) {
            bendSign = 0;
            return false;
        }
        // Hysteresis: the middle line must bend the same way on two blocks in a row (a niche or bump does not).
        int sign = turn > 0.0D ? 1 : -1;
        if (sign != bendSign) {
            bendSign = sign;
            return false;
        }
        dir = RotationMath.wrap((float) (axis + Math.max(-BEND_MAX, Math.min(BEND_MAX, turn))));
        double offset = laneMiddle ? 0.0D : lane - near[2];
        double rad = Math.toRadians(dir);
        // The new line runs through the near middle; its start is abeam the player (along = 0 there).
        double t = -(x - near[0]) * Math.sin(rad) + (z - near[1]) * Math.cos(rad);
        double ax = near[0] - Math.sin(rad) * t;
        double az = near[1] + Math.cos(rad) * t;
        double oldAxis = axis;
        double oldX = axisX;
        double oldZ = axisZ;
        double oldLane = lane;
        axis = dir;
        axisX = ax;
        axisZ = az;
        lane = offset;
        Ray ray = laneRay(view, walk, isTarget, isForeign, x, feetY, z, lane, walked);
        if (ray.free() < WAY_MIN_FREE) {
            axis = oldAxis;
            axisX = oldX;
            axisZ = oldZ;
            lane = oldLane;
            return false;
        }
        held = axis;
        // The new line runs through the middle.
        lastMiddle = 0.0D;
        bendAlong = 1.0D;
        centreAlong = 1.0D;
        return true;
    }

    /**
     * The tunnel's middle {@code ahead} blocks along the held way: across the way (square to it) the distance to the
     * left wall D_left and to the right wall D_right is measured on the {@link TunnelMap} floor, the middle is half way
     * between them. Averaged over the cross-sections 1 block before and after (single bumps and niches do not pull it
     * about). {x, z, lane offset}; {@code null} = a cave (no wall within {@value #MIDDLE_SCAN} blocks on a side).
     */
    private double @org.jspecify.annotations.Nullable [] middle(double ahead, double around) {
        double sum = 0.0D;
        int n = 0;
        for (double a = ahead - 1.0D; a <= ahead + 1.0D; a += 1.0D) {
            double m = crossMiddle(a, around);
            if (!Double.isNaN(m)) {
                sum += m;
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        double offset = sum / n;
        double rad = Math.toRadians(axis);
        double bx = axisX - Math.sin(rad) * ahead;
        double bz = axisZ + Math.cos(rad) * ahead;
        return new double[]{bx + Math.cos(rad) * offset, bz + Math.sin(rad) * offset, offset};
    }

    /** One cross-section's middle (lane offset) between D_left and D_right; {@code NaN} = no wall on a side / no floor. */
    private double crossMiddle(double ahead, double around) {
        double rad = Math.toRadians(axis);
        double bx = axisX - Math.sin(rad) * ahead;
        double bz = axisZ + Math.cos(rad) * ahead;
        double sx = Math.cos(rad);
        double sz = Math.sin(rad);
        // The lane point itself may lie on a wall's foot: the nearest floor beside it.
        double start = Double.NaN;
        for (double o = 0.0D; o <= MIDDLE_FIND && Double.isNaN(start); o += MIDDLE_STEP) {
            if (map.way(bx + sx * (around + o), bz + sz * (around + o))) {
                start = around + o;
            } else if (map.way(bx + sx * (around - o), bz + sz * (around - o))) {
                start = around - o;
            }
        }
        if (Double.isNaN(start)) {
            return Double.NaN;
        }
        double[] wall = new double[2];
        for (int side = 0; side < 2; side++) {
            double sign = side == 0 ? -1.0D : 1.0D;
            double o = MIDDLE_STEP;
            for (; o <= MIDDLE_SCAN; o += MIDDLE_STEP) {
                double px = bx + sx * (start + sign * o);
                double pz = bz + sz * (start + sign * o);
                if (!map.known(px, pz)) {
                    return Double.NaN;
                }
                if (!map.way(px, pz)) {
                    break;
                }
            }
            if (o > MIDDLE_SCAN) {
                return Double.NaN;
            }
            wall[side] = start + sign * o;
        }
        return (wall[0] + wall[1]) / 2.0D;
    }

    /** Why a cast in this direction from here ends (diagnostics). */
    private String castEndOf(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign, double x,
                             double feetY, double z, double heading, LongSet walked) {
        cast(view, walk, isTarget, isForeign, x, feetY, z, heading, walked);
        return castEnd;
    }

    /** Why a column has no floor to stand on: not scanned yet (cache), or wall / step / drop. */
    private static String noFloorReason(VoxelView view, int cx, int height, int cz) {
        for (int y = height - 3; y <= height + 2; y++) {
            if (view.cell(cx, y, cz) == Cell.UNKNOWN) {
                return "block not in the world cache (unknown)";
            }
        }
        return "wall / no floor";
    }

    private static Decision stop(float yaw) {
        return new Decision(yaw, false, false, 0.0D, 0.0D, yaw, new double[0], new double[0], true, false);
    }

    /** Why a way is picked. */
    private enum Pick {
        /** No way yet: the view direction if there is ore within 5 blocks, else the best way at most 90° aside. */
        START,
        /** The way ends / the player got stuck: the best way at most 90° aside, back only when nothing else. */
        BLOCKED
    }

    private record Way(Ray ray, boolean turnedRound) {
    }

    /**
     * Picks the way (see {@link Pick}) and sets it: the line starts at the player, the lane is chosen fresh (see
     * {@link #freshLane}). {@code null} = no way at all.
     */
    private @org.jspecify.annotations.Nullable Way newWay(VoxelView view, Walkability walk, IntPredicate isTarget,
                                                          IntPredicate isForeign, double x, double feetY, double z,
                                                          double reference, LongSet walked, LongToDoubleFunction zoneFactor,
                                                          boolean avoid, Pick pick) {
        Ray ahead = null;
        double aheadScore = 0.0D;
        Ray best = null;
        double bestScore = -Double.MAX_VALUE;
        Ray bestWorth = null;
        double bestWorthScore = -Double.MAX_VALUE;
        Ray back = null;
        double backScore = -Double.MAX_VALUE;
        Ray stub = null;
        double stubScore = -Double.MAX_VALUE;
        for (int i = 0; i < RAYS; i++) {
            double heading = RotationMath.wrap((float) (reference + i * STEP_DEGREES));
            double turn = Math.abs(RotationMath.wrap((float) (heading - reference)));
            Ray ray = cast(view, walk, isTarget, isForeign, x, feetY, z, heading, walked);
            if (ray.free() < MIN_FREE) {
                continue;
            }
            double score = wayScore(ray, turn, x, feetY, z, zoneFactor);
            if (avoid && Math.abs(RotationMath.wrap((float) (heading - blockedHeading))) < 30.0D) {
                score *= 0.05D;
            }
            if (i == 0) {
                ahead = ray;
                aheadScore = score;
            }
            if (ray.free() < (pick == Pick.BLOCKED ? OPEN_WAY : WAY_MIN_FREE)) {
                // Only a short stub (e.g. a corner at the tunnel's end; after a way ended: shorter than OPEN_WAY - it
                // would end again at once): the very last choice.
                if (score > stubScore) {
                    stubScore = score;
                    stub = ray;
                }
                continue;
            }
            if (turn > 90.0D || !Double.isNaN(travelDir)
                    && Math.abs(RotationMath.wrap((float) (heading - travelDir))) > TRAVEL_TURN) {
                // Back (off the held way or off where the player really came from): taken only at a dead end.
                if (score > backScore) {
                    backScore = score;
                    back = ray;
                }
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                best = ray;
            }
            if (ray.worthFinishing() && score > bestWorthScore) {
                bestWorthScore = score;
                bestWorth = ray;
            }
        }

        Ray chosen;
        switch (pick) {
            case START -> {
                chosen = richest(bestWorth, bestWorthScore, best, bestScore, back, stub);
                if (ahead != null && ahead.free() >= WAY_MIN_FREE && (ahead.oreAhead()
                        || ahead.ore() > 0.0D && chosen != null && sameTunnel(chosen.heading(), ahead.heading()))) {
                    // Straight on through the tunnel ahead; its ore aside is reached by a lane change.
                    chosen = ahead;
                }
            }
            default -> chosen = richest(bestWorth, bestWorthScore, best, bestScore, back, stub);
        }
        if (chosen == null) {
            return null;
        }
        if (!Double.isNaN(axis) && Math.abs(RotationMath.wrap((float) (chosen.heading() - axis))) > 30.0D) {
        }
        axis = chosen.heading();
        axisX = x;
        axisZ = z;
        centreAlong = 0.0D;
        bendAlong = 0.0D;
        caveAlong = 0.0D;
        lane = freshLane(view, walk, isTarget, isForeign, x, feetY, z, walked);
        return new Way(chosen, chosen == back || chosen == stub);
    }

    /**
     * The way with the most ore: the best one worth walking to its end - unless another (not back) way has {@value
     * #MUCH_RICHER}x its score. Back only at a dead end.
     */
    private static Ray richest(@org.jspecify.annotations.Nullable Ray bestWorth, double bestWorthScore,
                               @org.jspecify.annotations.Nullable Ray best, double bestScore,
                               @org.jspecify.annotations.Nullable Ray back, @org.jspecify.annotations.Nullable Ray stub) {
        if (bestWorth != null && (best == null || bestScore < bestWorthScore * MUCH_RICHER
                || sameTunnel(best.heading(), bestWorth.heading()))) {
            return bestWorth;
        }
        return best != null ? best : back != null ? back : stub;
    }

    /** Two ways this close (°) lead through the same tunnel - its ore aside is taken by a lane change, not a slant. */
    private static boolean sameTunnel(double a, double b) {
        return Math.abs(RotationMath.wrap((float) (a - b))) <= SAME_TUNNEL;
    }

    /** How good a way is: ore on it and in the cone around it, room, a small turn, flat, not walked yet, area yield. */
    private double wayScore(Ray ray, double turn, double x, double feetY, double z, LongToDoubleFunction zoneFactor) {
        double rad = Math.toRadians(ray.heading());
        int ex = (int) Math.floor(x - Math.sin(rad) * Math.min(ray.free(), 4.0D));
        int ez = (int) Math.floor(z + Math.cos(rad) * Math.min(ray.free(), 4.0D));
        return Math.min(ray.free(), WAY_LENGTH) / WAY_LENGTH
                * (ray.ore() + SECTOR_WEIGHT * sectorOre(ray.heading(), ray.free()) + EMPTY_VALUE * ray.free())
                * turnFactor(turn)
                * Math.pow(LanePlanner.RISE_FACTOR, ray.rises())
                * (1.0D - LanePlanner.OVERLAP_PENALTY * ray.overlap())
                * zoneFactor.applyAsDouble(LanePlanner.zoneOf(Pos.pack(ex, (int) Math.floor(feetY), ez)));
    }

    /**
     * The lane on a new way: the one nearest the tunnel's middle - unless another lane has at least {@value
     * #LANE_MORE_ORE} more ores over the next {@value #LANE_AHEAD} blocks. With ore right ahead of the player only lanes
     * that also have ore within {@value #LANE_EMPTY} blocks and at least as much count (that ore is mined first).
     */
    private double freshLane(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign, double x,
                             double feetY, double z, LongSet walked) {
        // Ore right ahead where the player is: that is mined first - only lanes just as good count then.
        Ray here = laneRay(view, walk, isTarget, isForeign, x, feetY, z, 0.0D, walked);
        boolean oreHere = here.free() >= MIN_FREE && here.firstOre() <= LANE_EMPTY;
        // The tunnel's middle 2 blocks ahead, in lane offsets (side vector (cos, sin) of the way's yaw).
        double[] mid = centring ? middle(along(x, z) + 2.0D, 0.0D) : null;
        double middle = mid == null ? 0.0D : mid[2];
        lastMiddle = mid == null ? Double.NaN : mid[2];

        double baseLane = Double.NaN;
        int baseOre = 0;
        double bestLane = Double.NaN;
        double bestYield = -1.0D;
        int bestOre = 0;
        for (int k = -LANE_MAX; k <= LANE_MAX; k++) {
            Ray r = laneRay(view, walk, isTarget, isForeign, x, feetY, z, k, walked);
            // The player's own lane with ore right ahead may be short (the last ore before a wall); the others not.
            if (r.free() < (k == 0 && oreHere ? MIN_FREE : WAY_MIN_FREE)
                    || oreHere && k != 0 && (r.firstOre() > LANE_EMPTY || r.laneOre() < here.laneOre())) {
                continue;
            }
            if (Double.isNaN(baseLane) || Math.abs(k - middle) < Math.abs(baseLane - middle)) {
                baseLane = k;
                baseOre = r.laneOre();
            }
            double y = laneYield(r, k);
            if (mid != null && Math.abs(k - middle) > LANE_ASIDE) {
                continue;
            }
            if (y > bestYield || y == bestYield && Math.abs(k) < Math.abs(bestLane)) {
                bestYield = y;
                bestLane = k;
                bestOre = r.laneOre();
            }
        }
        if (Double.isNaN(baseLane)) {
            laneMiddle = true;
            return 0.0D;
        }
        boolean richer = !oreHere && bestOre >= baseOre + LANE_MORE_ORE;
        laneMiddle = !richer;
        return richer ? bestLane : baseLane;
    }

    /**
     * A lane of the held way with ore within {@value #ORE_AHEAD} blocks and at least {@value #LANE_MORE_ORE} more ores
     * than the current one over the next {@value #LANE_AHEAD} blocks (the most ore per second wins, the nearer on a tie);
     * {@code NaN} = none.
     */
    private double richerLane(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign, double x,
                              double feetY, double z, Ray current, LongSet walked) {
        double bestLane = Double.NaN;
        double bestYield = -1.0D;
        for (int k = -LANE_MAX; k <= LANE_MAX; k++) {
            if (k == 0) {
                continue;
            }
            double candidate = lane + k;
            if (!Double.isNaN(lastMiddle) && Math.abs(candidate - lastMiddle) > LANE_ASIDE) {
                continue;
            }
            Ray r = laneRay(view, walk, isTarget, isForeign, x, feetY, z, candidate, walked);
            // As long as a fresh way must be (OPEN_WAY): a short lane would end at once and its way be re-chosen.
            // Ore within LANE_EMPTY (not ORE_AHEAD): the same bound that sends the lane back to the middle once its ore is
            // further away - with two bounds a lane whose ore is 4-5 blocks ahead flipped back and forth.
            if (r.free() < OPEN_WAY || r.firstOre() > LANE_EMPTY
                    || r.laneOre() < current.laneOre() + LANE_MORE_ORE) {
                continue;
            }
            double y = laneYield(r, k);
            if (y > bestYield || y == bestYield && Math.abs(candidate - lane) < Math.abs(bestLane - lane)) {
                bestYield = y;
                bestLane = candidate;
            }
        }
        return bestLane;
    }

    /**
     * The lane ends at an obstacle (a pillar, a bump) while the way goes on beside it: the nearest lane of the same way
     * that goes on at least {@value #OPEN_WAY} blocks and can be walked over to (more ore on a tie); {@code NaN} = none.
     */
    private double detourLane(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign, double x,
                              double feetY, double z, LongSet walked) {
        for (int k = 1; k <= LANE_MAX; k++) {
            double best = Double.NaN;
            int bestOre = -1;
            for (int sign = -1; sign <= 1; sign += 2) {
                double candidate = lane + sign * k;
                Ray r = laneRay(view, walk, isTarget, isForeign, x, feetY, z, candidate, walked);
                if (r.free() < OPEN_WAY || r.laneOre() <= bestOre || !reachable(view, walk, isForeign, x, feetY, z, candidate)) {
                    continue;
                }
                best = candidate;
                bestOre = r.laneOre();
            }
            if (!Double.isNaN(best)) {
                return best;
            }
        }
        return Double.NaN;
    }

    /** The player can walk straight over to lane {@code offset} ({@value #PURSUIT} blocks ahead on it). */
    private boolean reachable(VoxelView view, Walkability walk, IntPredicate isForeign, double x, double feetY, double z,
                              double offset) {
        double rad = Math.toRadians(axis);
        double ahead = along(x, z) + PURSUIT;
        double tx = axisX - Math.sin(rad) * ahead + Math.cos(rad) * offset;
        double tz = axisZ + Math.cos(rad) * ahead + Math.sin(rad) * offset;
        double distance = Math.hypot(tx - x, tz - z);
        Ray r = cast(view, walk, key -> false, isForeign, x, feetY, z, RotationMath.yawOf(tx - x, tz - z), LongSet.of());
        return r.free() >= distance - SAMPLE;
    }

    /** Ores a lane gives per {@value #LANE_AHEAD} blocks walked, counting the diagonal way over to it. */
    private static double laneYield(Ray lane, double shift) {
        return lane.laneOre() * LANE_AHEAD / Math.hypot(LANE_AHEAD, shift);
    }

    /** Walks lane {@code offset} of the held way in thought, from abeam the player on. */
    private Ray laneRay(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign, double x,
                        double feetY, double z, double offset, LongSet walked) {
        double rad = Math.toRadians(axis);
        double along = along(x, z);
        double px = axisX - Math.sin(rad) * along + Math.cos(rad) * offset;
        double pz = axisZ + Math.cos(rad) * along + Math.sin(rad) * offset;
        if (Math.abs(offset - side(x, z)) > 0.5D && guards.blocks(px, feetY, pz, x, feetY, z)) {
            return cast(view, walk, isTarget, isForeign, px, feetY, pz, axis, walked).withFree(0.0D);
        }
        return cast(view, walk, isTarget, isForeign, px, feetY, pz, axis, walked);
    }

    /** How far the player is along the held way from its start. */
    private double along(double x, double z) {
        double rad = Math.toRadians(axis);
        return -(x - axisX) * Math.sin(rad) + (z - axisZ) * Math.cos(rad);
    }

    /** How far the player is beside the held way's line (side vector (cos, sin) of the way's yaw). */
    private double side(double x, double z) {
        double rad = Math.toRadians(axis);
        return (x - axisX) * Math.cos(rad) + (z - axisZ) * Math.sin(rad);
    }

    /** The yaw towards the lane line {@value #PURSUIT} blocks ahead (at most {@value #MAX_PURSUIT}° off the way). */
    private float pursue(double x, double z) {
        double rad = Math.toRadians(axis);
        double ahead = along(x, z) + PURSUIT;
        double tx = axisX - Math.sin(rad) * ahead + Math.cos(rad) * lane;
        double tz = axisZ + Math.cos(rad) * ahead + Math.sin(rad) * lane;
        float off = RotationMath.wrap(RotationMath.yawOf(tx - x, tz - z) - (float) axis);
        off = (float) Math.max(-MAX_PURSUIT, Math.min(MAX_PURSUIT, off));
        return RotationMath.wrap((float) axis + off);
    }

    /** A step that goes further than the corridor allows (and further out than the player is now). */
    private boolean leavesGuide(double px, double pz, double x, double z) {
        int[] from = guideFrom;
        int[] to = guideTo;
        if (from == null || to == null) {
            return false;
        }
        double side = RouteCorridor.project(from, to, px, pz)[1];
        return side > RouteCorridor.WIDTH && side > RouteCorridor.project(from, to, x, z)[1] + 0.05D;
    }

    private void updateLane(double x, double z) {
        int[] from = guideFrom;
        int[] to = guideTo;
        if (from == null || to == null) {
            return;
        }
        double side = RouteCorridor.side(from, to, x, z);
        if (Math.abs(side) > RouteCorridor.WIDTH) {
            // Outside the corridor (e.g. after an emergency path): the lane is its edge, so the way leads back in.
            guideLane = Math.signum(side) * (RouteCorridor.WIDTH - 1.0D);
        } else if (Math.abs(side) <= LANE_SWITCH && (Double.isNaN(guideLane) || Math.abs(guideLane) <= LANE_SWITCH)) {
            // Near the line: the line itself is the lane - with all ores still there it walks the lines straight.
            // (A lane chosen beside it is kept, no drifting back to the middle.)
            guideLane = 0.0D;
        } else if (Double.isNaN(guideLane) || Math.abs(side - guideLane) > LANE_SWITCH) {
            guideLane = side;
        }
    }

    /** Route mode: the direction turns back against the route line (more than {@value #BACK_DEGREES}° off). */
    private boolean goesBack(double heading) {
        int[] from = guideFrom;
        int[] to = guideTo;
        if (from == null || to == null) {
            return false;
        }
        double along = RotationMath.yawOf(to[0] - from[0], to[2] - from[2]);
        return Math.abs(RotationMath.wrap((float) (heading - along))) > BACK_DEGREES;
    }

    /**
     * Along the route line's direction = 1, less the further a direction turns away from it (back: almost 0). Not
     * towards the waypoint point: that would pull every lane back to the middle.
     */
    private double directionFactor(double heading) {
        int[] from = guideFrom;
        int[] to = guideTo;
        if (from == null || to == null) {
            return 1.0D;
        }
        double along = RotationMath.yawOf(to[0] - from[0], to[2] - from[2]);
        double f = Math.abs(RotationMath.wrap((float) (heading - along))) / GUIDE_DEGREES;
        return 1.0D / (1.0D + f * f);
    }

    /** How well the way stays on the current lane (1 = on it, less the further it ends beside it). */
    private double laneFactor(double heading, double x, double z) {
        int[] from = guideFrom;
        int[] to = guideTo;
        if (from == null || to == null) {
            return 1.0D;
        }
        double rad = Math.toRadians(heading);
        double ahead = RouteCorridor.side(from, to, x - Math.sin(rad) * LANE_LOOKAHEAD, z + Math.cos(rad) * LANE_LOOKAHEAD);
        double lane = !Double.isNaN(targetLane) ? targetLane : Double.isNaN(guideLane) ? 0.0D : guideLane;
        double g = (ahead - lane) / LANE_KEEP;
        return 1.0D / (1.0D + g * g);
    }

    /**
     * Lane change (see {@link #LANE_AHEAD}): every tick, even while still moving over, the lane with the most ore per
     * block walked is taken as soon as it clearly beats the current (or targeted) one.
     */
    private void chooseLane(VoxelView view, Walkability walk, IntPredicate isTarget, double x, double feetY, double z) {
        int[] from = guideFrom;
        int[] to = guideTo;
        if (from == null || to == null) {
            return;
        }
        double side = RouteCorridor.side(from, to, x, z);
        double progress = RouteCorridor.project(from, to, x, z)[0];
        if (!Double.isNaN(targetLane) && Math.abs(side - targetLane) <= LANE_SWITCH) {
            // Arrived: the lane is where the player walks now (not the strip's middle - that would pull it half a
            // block and let it drift back).
            guideLane = side;
            targetLane = Double.NaN;
        }
        int current = (int) Math.rint(Double.isNaN(targetLane) ? side : targetLane);
        Strip here = strip(view, walk, isTarget, from, to, progress, current, feetY);
        double hereYield = here.walkable() ? oreYield(here, current - side) : 0.0D;
        // Little or no ore on the way: lanes up to the full corridor width (10 blocks) count, else the near ones (5).
        int max = (int) (here.walkable() && here.ore() >= FEW_ORE ? RouteCorridor.NEAR : RouteCorridor.WIDTH);
        double bestYield = hereYield;
        int best = current;
        for (int lane = -max; lane <= max; lane++) {
            if (lane == current) {
                continue;
            }
            if (laneLeavesGuards(from, to, progress, lane, x, feetY, z)) {
                continue;
            }
            Strip s = strip(view, walk, isTarget, from, to, progress, lane, feetY);
            if (!s.walkable()) {
                continue;
            }
            double y = oreYield(s, lane - side);
            if (y > bestYield || y == bestYield && best != current && Math.abs(lane - side) < Math.abs(best - side)) {
                bestYield = y;
                best = lane;
            }
        }
        if (best != current && bestYield >= hereYield + LANE_GAIN && bestYield >= hereYield * LANE_GAIN_RATIO) {
            targetLane = best;
        }
    }

    /** A lane whose start or strip end ({@value #LANE_AHEAD} blocks ahead) lies outside the guarded area is not taken. */
    private boolean laneLeavesGuards(int[] from, int[] to, double progress, int lane, double x, double feetY, double z) {
        if (guards.isEmpty()) {
            return false;
        }
        double ax = from[0] + 0.5D;
        double az = from[2] + 0.5D;
        double dx = to[0] + 0.5D - ax;
        double dz = to[2] + 0.5D - az;
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-6D) {
            return false;
        }
        // Where the lane is joined and where its strip ends (lanes up to 10 blocks aside may cut out of the area).
        for (double along : new double[]{progress + 1.0D, progress + LANE_AHEAD}) {
            double px = ax + dx / len * along + dz / len * lane;
            double pz = az + dz / len * along - dx / len * lane;
            if (guards.blocks(px, feetY, pz, x, feetY, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ores a lane's strip gives per {@value #LANE_AHEAD} blocks walked, counting the diagonal way over to it
     * ({@code shift} blocks sideways) - proportional to ore per second at walking speed.
     */
    static double oreYield(Strip strip, double shift) {
        return strip.ore() * LANE_AHEAD / Math.hypot(LANE_AHEAD, shift);
    }

    /**
     * The floor of the strip the pickaxe mines on lane {@code lane} (blocks beside the route line): 5 columns wide
     * ({@value #MINE_HALF} left and right of the lane), the next {@value #LANE_AHEAD} blocks ahead. A floor block that
     * is not a selected ore counts as stone / deepslate (in the mine a mined ore turns into stone). A column without
     * floor (wall, deep hole) makes the strip not walkable - the lane would be less than 2 blocks from the wall.
     */
    static Strip strip(VoxelView view, Walkability walk, IntPredicate isTarget, int[] from, int[] to, double progress, int lane,
                       double feetY) {
        double ax = from[0] + 0.5D;
        double az = from[2] + 0.5D;
        double dx = to[0] + 0.5D - ax;
        double dz = to[2] + 0.5D - az;
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-6D) {
            return new Strip(0, 0, false);
        }
        double ux = dx / len;
        double uz = dz / len;
        int[] height = new int[2 * MINE_HALF + 1];
        Arrays.fill(height, (int) Math.floor(feetY + 0.01D));
        int stone = 0;
        int ore = 0;
        boolean walkable = true;
        for (int t = 1; t <= LANE_AHEAD; t++) {
            double along = progress + t;
            for (int w = -MINE_HALF; w <= MINE_HALF; w++) {
                // Left of the walking direction = positive side (see RouteCorridor.side).
                double s = lane + w;
                int bx = (int) Math.floor(ax + ux * along + uz * s);
                int bz = (int) Math.floor(az + uz * along - ux * s);
                int found = standY(walk, bx, height[w + MINE_HALF], bz);
                if (found == Integer.MIN_VALUE) {
                    walkable = false;
                    continue;
                }
                height[w + MINE_HALF] = found;
                int key = view.ore(bx, found - 1, bz);
                if (key != 0 && isTarget.test(key)) {
                    ore++;
                } else {
                    stone++;
                }
            }
        }
        return new Strip(stone, ore, walkable);
    }

    Ray cast(
            VoxelView view,
            Walkability walk,
            IntPredicate isTarget,
            double x,
            double feetY,
            double z,
            double heading,
            LongSet walked
    ) {
        return cast(
                view,
                walk,
                isTarget,
                key -> false,
                x,
                feetY,
                z,
                heading,
                walked
        );
    }

    Ray cast(
            VoxelView view,
            Walkability walk,
            IntPredicate isTarget,
            IntPredicate isForeign,
            double x,
            double feetY,
            double z,
            double heading,
            LongSet walked
    ) {
        double foreignAt =
                Double.POSITIVE_INFINITY;
        castEnd = "range end";

        double rad =
                Math.toRadians(
                        heading
                );

        double fx =
                -Math.sin(rad);

        double fz =
                Math.cos(rad);

        double lx =
                -fz;

        double lz =
                fx;

        int height =
                (int) Math.floor(
                        feetY + 0.01D
                );

        int lastX =
                (int) Math.floor(x);

        int lastZ =
                (int) Math.floor(z);

        double free = 0.0D;

        int rises = 0;
        int overlap = 0;
        int columns = 0;

        LongOpenHashSet counted =
                new LongOpenHashSet();

        double ore = 0.0D;
        double firstOre = Double.POSITIVE_INFINITY;
        int floorOre = 0;
        int floorStone = 0;
        int laneOre = 0;

        int samples =
                (int) (
                        RANGE / SAMPLE
                );

        double[] feet =
                new double[samples];

        double[] dist =
                new double[samples];

        int n = 0;

        for (int s = 1; s <= samples; s++) {
            double d =
                    s * SAMPLE;

            double px =
                    x + fx * d;

            double pz =
                    z + fz * d;

            int cx =
                    (int) Math.floor(px);

            int cz =
                    (int) Math.floor(pz);

            if (borders.blocks(px, feetY, pz, x, z) || leavesGuide(px, pz, x, z)) {
                castEnd = "border";
                break;
            }

            int found =
                    standY(
                            walk,
                            cx,
                            height,
                            cz
                    );

            if (found == Integer.MIN_VALUE
                    || guards.blocks(px, found, pz, x, feetY, z)
                    || !bodyFits(
                    walk,
                    px,
                    pz,
                    lx,
                    lz,
                    found
            )) {
                castEnd = found == Integer.MIN_VALUE ? noFloorReason(view, cx, height, cz)
                        : guards.blocks(px, found, pz, x, feetY, z) ? "guard zone edge" : "body does not fit";
                break;
            }
            if (mapLive && (cx != lastX || cz != lastZ) && found >= height && map.known(px, pz) && !map.way(px, pz)) {
                // A tunnel wall by the shape of the ground (the foot of a fast rise, or the slope above it) - only going
                // up or level: a slope walked down (off a ledge or hill) is a way.
                castEnd = "tunnel wall (slope)";
                break;
            }

            if (cx != lastX
                    || cz != lastZ) {

                if (found > height) {
                    // Every walkable step up is a way (only hand-placed borders limit where the macro goes).
                    rises++;
                }

                columns++;

                if (walked.contains(
                        Pos.pack(
                                cx,
                                found,
                                cz
                        )
                )) {
                    overlap++;
                }

                if (seesForeign(
                        view,
                        isForeign,
                        px,
                        pz,
                        lx,
                        lz,
                        found
                )) {
                    foreignAt = d;
                    castEnd = "unselected ore (mine border)";
                    break;
                }

                for (int w = -2; w <= 2; w++) {
                    int bx =
                            (int) Math.floor(
                                    px + lx * w
                            );

                    int bz =
                            (int) Math.floor(
                                    pz + lz * w
                            );

                    if (d <= TUNNEL_CHECK && !Cell.isFull(view.cell(bx, found, bz))) {
                        // The floor of the mined strip (not the foot of a wall): a selected ore, or stone / deepslate.
                        int key = view.ore(bx, found - 1, bz);
                        if (key != 0 && isTarget.test(key)) {
                            floorOre++;
                        } else if (Cell.isFull(view.cell(bx, found - 1, bz))) {
                            floorStone++;
                        }
                    }

                    for (
                            int by = found - 3;
                            by <= found;
                            by++
                    ) {
                        long pos =
                                Pos.pack(
                                        bx,
                                        by,
                                        bz
                                );

                        if (!counted.contains(pos)
                                && LanePlanner.surfaceOre(
                                view,
                                isTarget,
                                bx,
                                by,
                                bz
                        )) {
                            counted.add(pos);
                            if (Math.abs(w) <= 1) {
                                // In the 3 wide way the pickaxe mines (not ore beside it, e.g. a side tunnel's floor).
                                firstOre = Math.min(firstOre, d);
                                if (d <= LANE_AHEAD) {
                                    laneOre++;
                                }
                            }

                            ore +=
                                    LanePlanner.lateralWeight(w)
                                            / (
                                            1.0D
                                                    + d
                                                    / ORE_HALF_DISTANCE
                                    );
                        }
                    }
                }

                lastX = cx;
                lastZ = cz;
                height = found;
            }

            feet[n] =
                    walk.standHeight(
                            cx,
                            found,
                            cz
                    );

            dist[n] = d;
            n++;

            free = d;
        }

        if (foreignAt < Double.POSITIVE_INFINITY) {
            free =
                    Math.max(
                            0.0D,
                            Math.min(
                                    free,
                                    foreignAt
                                            - FOREIGN_MARGIN
                            )
                    );

            int keep = 0;

            while (
                    keep < n
                            && dist[keep] <= free
            ) {
                keep++;
            }

            n = keep;
        }

        return new Ray(
                heading,
                free,
                ore,
                rises,
                columns == 0
                        ? 0.0D
                        : overlap / (double) columns,
                Arrays.copyOf(
                        feet,
                        n
                ),
                Arrays.copyOf(
                        dist,
                        n
                ),
                floorOre,
                floorStone,
                firstOre,
                laneOre
        );
    }

    private static boolean seesForeign(
            VoxelView view,
            IntPredicate isForeign,
            double px,
            double pz,
            double lx,
            double lz,
            int feet
    ) {
        for (int w = -2; w <= 2; w++) {
            int bx =
                    (int) Math.floor(
                            px + lx * w
                    );

            int bz =
                    (int) Math.floor(
                            pz + lz * w
                    );

            for (
                    int by = feet - 3;
                    by <= feet + 3;
                    by++
            ) {
                int key =
                        view.ore(
                                bx,
                                by,
                                bz
                        );

                if (key != 0
                        && isForeign.test(key)
                        && view.exposed(
                        bx,
                        by,
                        bz
                )) {
                    return true;
                }
            }
        }

        return false;
    }

    static int standY(
            Walkability walk,
            int x,
            int height,
            int z
    ) {
        for (int dy :
                new int[]{
                        0,
                        1,
                        -1,
                        -2,
                        -3
                }) {

            if (walk.isStandable(
                    x,
                    height + dy,
                    z
            )) {
                return height + dy;
            }
        }

        return Integer.MIN_VALUE;
    }

    private static boolean bodyFits(
            Walkability walk,
            double px,
            double pz,
            double lx,
            double lz,
            int height
    ) {
        for (double side :
                new double[]{
                        -HALF_BODY,
                        HALF_BODY
                }) {

            int sx =
                    (int) Math.floor(
                            px + lx * side
                    );

            int sz =
                    (int) Math.floor(
                            pz + lz * side
                    );

            if (standY(
                    walk,
                    sx,
                    height,
                    sz
            ) == Integer.MIN_VALUE) {
                return false;
            }
        }

        return true;
    }

    /**
     * Share of a way's value kept after turning by {@code degrees}: small curves are almost free (30° ≈ 0.97,
     * 45° ≈ 0.85), sharp turns cost a lot (90° ≈ 0.27) and turning back almost everything (180° ≈ 0.02) - it walks on
     * straight and only turns round when nothing lies ahead.
     */
    static double turnFactor(
            double degrees
    ) {
        double t =
                degrees / 70.0D;

        return 1.0D
                / (
                1.0D
                        + t * t * t * t
        );
    }

    /**
     * Rebuilds the ore density map around the player (see {@link #SECTORS}) when the player moved to another block or
     * after {@value #DENSITY_MS} ms.
     */
    private void updateDensity(VoxelView view, IntPredicate isTarget, double x, double feetY, double z, long nowMs) {
        int px = (int) Math.floor(x);
        int py = (int) Math.floor(feetY + 0.01D);
        int pz = (int) Math.floor(z);
        long key = Pos.pack(px, py, pz);
        if (key == densityKey && nowMs >= densityAt && nowMs - densityAt < DENSITY_MS) {
            return;
        }
        densityKey = key;
        densityAt = nowMs;
        int range = (int) RANGE;
        double[][] map = new double[SECTORS][range + 1];
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                double d = Math.hypot(dx, dz);
                if (d < 1.0D || d > RANGE) {
                    continue;
                }
                for (int by = py - 3; by <= py + 1; by++) {
                    if (LanePlanner.surfaceOre(view, isTarget, px + dx, by, pz + dz)) {
                        map[sectorOf(RotationMath.yawOf(dx, dz))][(int) d] += 1.0D / (1.0D + d / ORE_HALF_DISTANCE);
                    }
                }
            }
        }
        for (double[] row : map) {
            for (int d = 1; d <= range; d++) {
                row[d] += row[d - 1];
            }
        }
        density = map;
    }

    private static int sectorOf(double yaw) {
        return (int) (((yaw % 360.0D) + 360.0D) % 360.0D / (360.0D / SECTORS)) % SECTORS;
    }

    /** Ores in the cone around {@code heading} up to {@value #SECTOR_REACH} blocks beyond the way's end. */
    private double sectorOre(double heading, double free) {
        int reach = (int) Math.min(RANGE, free + SECTOR_REACH);
        int half = (int) Math.round(SECTOR_HALF / (360.0D / SECTORS));
        int centre = sectorOf(heading);
        double sum = 0.0D;
        for (int k = -half; k <= half; k++) {
            sum += density[Math.floorMod(centre + k, SECTORS)][reach];
        }
        return sum;
    }
}
