package com.freelocs.theprisons.core.movement;

import com.freelocs.theprisons.core.control.ControlService;
import com.freelocs.theprisons.core.nav.NavigationPath;
import com.freelocs.theprisons.core.nav.PathSearch;
import com.freelocs.theprisons.core.nav.Pos;
import com.freelocs.theprisons.core.nav.Walkability;
import com.freelocs.theprisons.core.concurrent.Worker;
import com.freelocs.theprisons.core.world.LiveWorldView;
import com.freelocs.theprisons.core.world.WorldCache;
import com.freelocs.theprisons.core.world.WorldSnapshot;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Navigation for one owner (a module). The owner asks for a destination ({@link #goTo}) or hands over a path it
 * already planned ({@link #follow}); the navigator then searches, walks, validates, recovers and re-plans on its own
 * and reports {@link State#ARRIVED} or {@link State#FAILED}.
 *
 * <p>Searches run exclusively on the shared {@link Worker} over a {@link WorldSnapshot}; there is no fallback search
 * on the client thread. Far goals are reached with partial paths. Nodes the player got stuck on are penalised for
 * {@value #PENALTY_MS} ms (a second failure forbids them), so re-plans route around the bad spot.
 */
public final class Navigator {
    public enum State { IDLE, SEARCHING, WALKING, ARRIVED, FAILED }

    /** Options of a navigation request. */
    /**
     * @param strafe false = steer like a car: only walk forward and turn the view (no sideways keys); the owner must
     *               then keep the view along the walking direction
     */
    public record Options(boolean sprint, boolean allowJumps, int maxDrop, int maxNodes, boolean strafe) {
        public static final Options DEFAULT = new Options(true, true, PathFollower.MAX_DROP, 60_000, true);

        public Options(boolean sprint, boolean allowJumps, int maxDrop, int maxNodes) {
            this(sprint, allowJumps, maxDrop, maxNodes, true);
        }
    }

    private static final int MAX_REPLANS = 5;
    private static final int SEARCH_TIMEOUT_TICKS = 100;
    private static final int START_WAIT_TICKS = 60;
    private static final long PENALTY_MS = 30_000L;
    private static final double STUCK_PENALTY = 8.0D;

    private final Object owner;
    private final String key;
    private final Worker worker;
    private final WorldCache world;
    private final ControlService control;
    private final PathFollower follower = new PathFollower();
    private final Long2DoubleOpenHashMap penalties = new Long2DoubleOpenHashMap();
    private final Long2LongOpenHashMap penaltyExpiry = new Long2LongOpenHashMap();

    private State state = State.IDLE;
    private LongSet goals = new LongOpenHashSet();
    private String label = "";
    private Options options = Options.DEFAULT;
    private Worker.@Nullable Job job;
    private PathSearch.@Nullable Result result;
    private boolean partial;
    private int replans;
    private int stateTicks;
    private String failure = "";

    public Navigator(Object owner, String key, Worker worker, WorldCache world, ControlService control) {
        this.owner = owner;
        this.key = key;
        this.worker = worker;
        this.world = world;
        this.control = control;
    }

    // ── Requests ─────────────────────────────────────────────────────────────

    /** Walks to any of the given standing nodes (packed feet cells). */
    public void goTo(LongSet goalNodes, String newLabel, Options newOptions) {
        this.goals = new LongOpenHashSet(goalNodes);
        this.label = newLabel;
        this.options = newOptions;
        this.replans = 0;
        this.failure = "";
        search();
    }

    /** Walks next to a block position (any standable cell within one block). */
    public void goNear(int x, int y, int z, String newLabel, Options newOptions) {
        LongOpenHashSet near = new LongOpenHashSet();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    near.add(Pos.pack(x + dx, y + dy, z + dz));
                }
            }
        }
        goTo(near, newLabel, newOptions);
    }

    /** Follows a path the caller already computed; re-plans towards its end when it becomes invalid. */
    public void follow(NavigationPath path, String newLabel, Options newOptions) {
        this.goals = new LongOpenHashSet(new long[]{path.goal()});
        this.label = newLabel;
        this.options = newOptions;
        this.replans = 0;
        this.failure = "";
        this.partial = false;
        cancelJob();
        follower.start(path, steering(), options.maxDrop());
        setState(State.WALKING);
    }

    public void cancel() {
        cancelJob();
        follower.stop(control.input());
        setState(State.IDLE);
    }

    // ── State ────────────────────────────────────────────────────────────────

    public State state() {
        return state;
    }

    public boolean active() {
        return state == State.SEARCHING || state == State.WALKING;
    }

    public String failure() {
        return failure;
    }

    public String label() {
        return label;
    }

    public @Nullable NavigationPath path() {
        return state == State.WALKING ? follower.path() : null;
    }

    public PathFollower follower() {
        return follower;
    }

    public double remaining() {
        return state == State.WALKING ? follower.remaining() : 0.0D;
    }

    /** Current penalties (read-only view for planners that should avoid the same spots). */
    public Long2DoubleOpenHashMap penalties() {
        return penalties;
    }

    public String status() {
        return switch (state) {
            case IDLE -> "Idle";
            case SEARCHING -> "Searching path" + (label.isEmpty() ? "" : " to " + label);
            case WALKING -> String.format(Locale.ROOT, "Walking%s (%.0fm%s)", label.isEmpty() ? "" : " to " + label,
                    follower.remaining(), partial ? ", partial" : "");
            case ARRIVED -> "Arrived";
            case FAILED -> "Path failed: " + failure;
        };
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    /** Advances the request by one tick; call once per tick while the owner holds the control lease. */
    public State tick(MinecraftClient client, ClientPlayerEntity player) {
        stateTicks++;
        expirePenalties();
        switch (state) {
            case SEARCHING -> tickSearching(player);
            case WALKING -> tickWalking(client, player);
            default -> {
            }
        }
        return state;
    }

    private void tickSearching(ClientPlayerEntity player) {
        PathSearch.Result done = result;
        if (done != null) {
            result = null;
            begin(done);
            return;
        }
        if (job == null) {
            // The cache may not have the player's cell yet: retry a few times, never search on this thread.
            if (stateTicks >= START_WAIT_TICKS) {
                fail("not standing on known walkable ground");
            } else if (stateTicks % 5 == 1) {
                submit(player);
            }
            return;
        }
        if (stateTicks > SEARCH_TIMEOUT_TICKS) {
            fail("path search timed out");
        }
    }

    private void begin(PathSearch.Result found) {
        if (!found.usable() || found.path() == null) {
            fail(found.reason());
            return;
        }
        partial = found.status() == PathSearch.Status.PARTIAL;
        if (found.path().size() <= 1) {
            follower.stop(control.input());
            if (partial) {
                fail(found.reason());
            } else {
                setState(State.ARRIVED);
            }
            return;
        }
        follower.start(found.path(), steering(), options.maxDrop());
        setState(State.WALKING);
    }

    private void tickWalking(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) {
            return;
        }
        PathFollower.Result step = follower.tick(player, control.input(), control.rotation(),
                new LiveWorldView(client.world, world.classifier()));
        switch (step) {
            case ARRIVED -> {
                if (partial) {
                    replan("partial path finished");
                } else {
                    follower.stop(control.input());
                    setState(State.ARRIVED);
                }
            }
            case REPLAN -> {
                penalise();
                replan(follower.reason());
            }
            case FAILED -> {
                penalise();
                fail(follower.reason());
            }
            default -> {
            }
        }
    }

    private void replan(String reason) {
        if (++replans > MAX_REPLANS) {
            fail(reason);
            return;
        }
        follower.stop(control.input());
        failure = reason;
        search();
    }

    private void search() {
        cancelJob();
        partial = false;
        result = null;
        setState(State.SEARCHING);
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            submit(player);
        }
    }

    /** Starts the A* job; leaves {@link #job} empty when the player's cell is not known yet. */
    private void submit(ClientPlayerEntity player) {
        int radius = searchRadius(player);
        WorldSnapshot snapshot = world.snapshot(player, radius + 16, 48);
        Walkability probe = new Walkability(snapshot.copyView(), options.maxDrop());
        long start = probe.settle(player.getX(), player.getY(), player.getZ());
        if (start == Long.MIN_VALUE) {
            return;
        }
        long[] targets = goals.toLongArray();
        Long2DoubleOpenHashMap penaltyCopy = new Long2DoubleOpenHashMap(penalties);
        Options requestOptions = options;
        job = worker.submit(owner, key, cancel -> {
            Walkability walk = new Walkability(snapshot, requestOptions.maxDrop(), requestOptions.allowJumps(), penaltyCopy);
            PathSearch.Result found = PathSearch.findPath(walk, start, new LongOpenHashSet(targets), requestOptions.maxNodes(), radius,
                    snapshot.version(), cancel::cancelled);
            if (found.path() == null) {
                return found;
            }
            // Straight runs: the follower walks straight lines instead of the grid staircase.
            return new PathSearch.Result(found.status(), com.freelocs.theprisons.core.nav.PathStraightener.apply(found.path(), walk),
                    found.expanded(), found.reason());
        }, found -> {
            job = null;
            if (state == State.SEARCHING) {
                result = found;
            }
        });
    }

    private int searchRadius(ClientPlayerEntity player) {
        int radius = 16;
        for (long goal : goals) {
            radius = Math.max(radius, Math.max(Math.abs(Pos.x(goal) - player.getBlockX()), Math.abs(Pos.z(goal) - player.getBlockZ())) + 16);
        }
        return Math.min(radius, 160);
    }

    private void penalise() {
        long expires = System.currentTimeMillis() + PENALTY_MS;
        for (long node : follower.stuckNodes()) {
            double before = penalties.get(node);
            // A spot that failed twice is avoided completely for a while.
            penalties.put(node, before > 0.0D ? Double.POSITIVE_INFINITY : STUCK_PENALTY);
            penaltyExpiry.put(node, expires);
        }
        follower.stuckNodes().clear();
    }

    private void expirePenalties() {
        if (penaltyExpiry.isEmpty() || stateTicks % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        penaltyExpiry.long2LongEntrySet().removeIf(entry -> {
            if (entry.getLongValue() < now) {
                penalties.remove(entry.getLongKey());
                return true;
            }
            return false;
        });
    }

    private SteeringLogic.Settings steering() {
        SteeringLogic.Settings base = SteeringLogic.Settings.DEFAULT;
        return new SteeringLogic.Settings(base.lookahead(), base.jumpTriggerDistance(), options.allowJumps(),
                base.allowStrafe() && options.strafe(), options.sprint());
    }

    private void cancelJob() {
        Worker.Job current = job;
        if (current != null) {
            current.cancel();
        }
        job = null;
        result = null;
    }

    private void fail(String reason) {
        cancelJob();
        follower.stop(control.input());
        failure = reason;
        setState(State.FAILED);
    }

    private void setState(State next) {
        state = next;
        stateTicks = 0;
    }
}
