package io.theprisons.modules.qol.bandit.combat;

import io.theprisons.modules.qol.bandit.BanditDanger;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The bandit combat controller: one finite state machine from "perceive" to "next state". Pure logic - it gets {@link CombatInputs}
 * every tick and answers with a {@link Decision}; it never touches the game, the keys or the view.
 *
 * <pre>
 *  IDLE -> SCAN -> TARGET_SELECT -> APPROACH -> ORBIT <-> ENGAGE
 *                        ^             |  \        |  \
 *                        |        REPOSITION  EVADE   RECOVER      (bounded, never endless)
 *  COOLDOWN <- LOST_TARGET / RETREAT / loop reaction          STOPPED (terminal)
 * </pre>
 *
 * Rules that hold everywhere: manual input stops it at once; a target is locked and only leaves for a reason; every distance limit has a
 * dead band; recovery and reposition are counted per time window; a loop (orbit without progress, flipping sides, two states passing
 * the macro back and forth ...) first stabilises and plans once more, the second one within a minute stops the macro.
 * Unknown game values are never invented: the configuration holds our tuning, verified values replace it when they exist.
 */
public final class CombatBrain {
    private final CombatConfig cfg;
    private final DistanceBand band;
    private final TargetSelector selector = new TargetSelector();
    private final OrbitPlanner orbit = new OrbitPlanner();
    private final LoopDetector loops = new LoopDetector();
    private final Consumer<String> info;
    private final Consumer<String> trace;

    private CombatState state = CombatState.IDLE;
    private long enteredMs;
    private String reason = "";
    private @Nullable Foe target;
    private double ring;
    private DistanceBand.State bandState = DistanceBand.State.UNKNOWN;
    private double targetDistance = Double.NaN;
    private ThreatContext threats = new ThreatContext(null, List.of(), Double.NaN, 0);
    private long targetMissingSinceMs = -1L;
    private long sightLostSinceMs = -1L;
    private long lastSelectMs = Long.MIN_VALUE / 2L;
    private final ArrayDeque<Long> recoveries = new ArrayDeque<>();
    private final ArrayDeque<Long> repositions = new ArrayDeque<>();
    private final ArrayDeque<Long> retreats = new ArrayDeque<>();
    private String retreatReason = "";
    private boolean waitHealth;
    private boolean stabilising;
    private boolean hadHurtTarget;
    private int kills;
    private int loopCount;
    private long lastLoopMs = Long.MIN_VALUE / 2L;
    private double goalX = Double.NaN;
    private double goalZ = Double.NaN;
    private long goalAtMs = Long.MIN_VALUE / 2L;
    private int pathFailuresSeen;
    private final Map<String, double[]> lastPlayerDistance = new HashMap<>();
    private Terrain.Ray[] lastRays = new Terrain.Ray[0];
    private String lastSelectionNote = "";
    private double lastScore = Double.NaN;
    private int scanTicks;

    public CombatBrain(CombatConfig cfg, Consumer<String> info, Consumer<String> trace) {
        this.cfg = cfg;
        this.band = new DistanceBand(cfg.minSafe, cfg.maxCombat, cfg.hysteresis);
        this.info = info;
        this.trace = trace;
        this.ring = cfg.ring(band);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** The macro was switched on. */
    public void start(long nowMs) {
        resetCombat();
        recoveries.clear();
        repositions.clear();
        retreats.clear();
        loopCount = 0;
        lastLoopMs = Long.MIN_VALUE / 2L;
        loops.reset();
        selector.reset();
        kills = 0;
        state = CombatState.IDLE;
        go(CombatState.SCAN, "START", nowMs);
    }

    /** The macro was switched off: every temporary combat state is dropped. */
    public void stop(long nowMs, String why) {
        if (state != CombatState.STOPPED && state != CombatState.IDLE) {
            go(CombatState.STOPPED, why, nowMs);
        }
        resetCombat();
    }

    /** Back to nothing (IDLE): no inputs, no view, all temporary state cleared. */
    public void idle(long nowMs) {
        resetCombat();
        state = CombatState.IDLE;
        enteredMs = nowMs;
        reason = "IDLE";
    }

    /** The world changed or the control layer saw a spin: drop the target and everything built on it, then scan again. */
    public void invalidate(long nowMs, String why) {
        if (state.inactive()) {
            return;
        }
        resetCombat();
        selector.clearLock();
        go(CombatState.COOLDOWN, "INVALIDATED " + why, nowMs);
    }

    private void resetCombat() {
        selector.clearLock();
        target = null;
        targetMissingSinceMs = -1L;
        sightLostSinceMs = -1L;
        band.reset();
        bandState = DistanceBand.State.UNKNOWN;
        targetDistance = Double.NaN;
        orbit.reset();
        goalX = Double.NaN;
        goalZ = Double.NaN;
        pathFailuresSeen = 0;
        waitHealth = false;
        stabilising = false;
        hadHurtTarget = false;
        retreatReason = "";
        lastPlayerDistance.clear();
        threats = new ThreatContext(null, List.of(), Double.NaN, 0);
    }

    // ── Read-outs ────────────────────────────────────────────────────────────

    public CombatState state() {
        return state;
    }

    public String reason() {
        return reason;
    }

    public long elapsed(long nowMs) {
        return nowMs - enteredMs;
    }

    /** The locked target (also while it is out of sight for a moment); null = none. */
    public @Nullable String targetId() {
        return target != null ? target.id() : selector.lockedId();
    }

    public int kills() {
        return kills;
    }

    public int recoveryCount() {
        return recoveries.size();
    }

    public OrbitPlanner.@Nullable Dir orbitDir() {
        return orbit.dir();
    }

    public DistanceBand.State bandState() {
        return bandState;
    }

    public ThreatContext threats() {
        return threats;
    }

    public String retreatReason() {
        return retreatReason;
    }

    public int loops() {
        return loopCount;
    }

    public double targetDistance() {
        return targetDistance;
    }

    public double score() {
        return lastScore;
    }

    public double ring() {
        return ring;
    }

    public CombatConfig config() {
        return cfg;
    }

    /** The 16 ground probes of the last tick (free space around the player), for captures. */
    public Terrain.Ray[] lastRays() {
        return lastRays.clone();
    }

    /** A flat summary for captures and the debug view. */
    public Map<String, String> summary(long nowMs) {
        Map<String, String> m = new TreeMap<>();
        m.put("bandit.state", state.name());
        m.put("bandit.reason", reason);
        m.put("bandit.stateMs", String.valueOf(elapsed(nowMs)));
        m.put("bandit.target", target == null ? "-" : target.id());
        m.put("bandit.targetKind", target == null ? "-" : target.kind());
        m.put("bandit.targetDistance", Double.isNaN(targetDistance) ? "-" : String.format(Locale.ROOT, "%.1f", targetDistance));
        m.put("bandit.targetScore", Double.isNaN(lastScore) ? "-" : String.format(Locale.ROOT, "%.1f", lastScore));
        m.put("bandit.distanceState", bandState.name());
        m.put("bandit.ring", String.format(Locale.ROOT, "%.1f (%s)", ring, cfg.ringSource()));
        m.put("bandit.threats", String.valueOf(threats.count()));
        m.put("bandit.nearestThreat", Double.isNaN(threats.nearestDistance()) ? "-" : String.format(Locale.ROOT, "%.1f", threats.nearestDistance()));
        m.put("bandit.orbitDirection", orbit.dir() == null ? "-" : orbit.dir().name());
        m.put("bandit.orbitFlips", String.valueOf(orbit.flips()));
        m.put("bandit.orbitProgressDeg", String.format(Locale.ROOT, "%.0f", orbit.progressDegrees()));
        m.put("bandit.recoveries", String.valueOf(recoveries.size()));
        m.put("bandit.retreatReason", retreatReason.isEmpty() ? "-" : retreatReason);
        m.put("bandit.loops", String.valueOf(loopCount));
        for (int i = 0; i < lastRays.length; i++) {
            m.put(String.format("bandit.terrain.%02d", i), lastRays[i].stop() + String.format(Locale.ROOT, " %.1f", lastRays[i].free()));
        }
        return m;
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public Decision tick(CombatInputs in) {
        long now = in.nowMs();
        if (state == CombatState.IDLE) {
            return decision(in, Decision.Move.NONE, Decision.Look.NONE, false, "idle");
        }
        if (state == CombatState.STOPPED) {
            return decision(in, Decision.Move.NONE, Decision.Look.NONE, false, "stopped");
        }
        CombatInputs.Me me = in.me();
        if (me.manualInput()) {
            // The human wins: nothing is driven any more.
            return stopNow(in, "MANUAL_INPUT", false);
        }
        if (scanTicks++ % 4 == 0) {
            // 16 ground probes about 5 times a second: corner detection and the capture's terrain summary.
            lastRays = EscapePlanner.scan(in.terrain(), me.x(), me.y(), me.z(), 4.0D);
        }
        locateTarget(in);
        Decision safety = safety(in);
        if (safety != null) {
            return safety;
        }
        LoopDetector.Kind loop = loops.sample(now, state, me.x(), me.z(), me.yaw(), orbit.progressDegrees());
        if (loop != LoopDetector.Kind.NONE) {
            return handleLoop(in, loop);
        }
        return switch (state) {
            case SCAN -> scan(in);
            case TARGET_SELECT -> targetSelect(in);
            case APPROACH -> approach(in);
            case ORBIT -> orbit(in);
            case ENGAGE -> engage(in);
            case EVADE -> evade(in);
            case REPOSITION -> reposition(in);
            case RECOVER -> recover(in);
            case RETREAT -> retreat(in);
            case LOST_TARGET -> lost(in);
            case COOLDOWN -> cooldown(in);
            default -> decision(in, Decision.Move.NONE, Decision.Look.NONE, false, "");
        };
    }

    // ── Perception ───────────────────────────────────────────────────────────

    /** Finds the locked target among this tick's bandits and updates distances, band and the threat picture. */
    private void locateTarget(CombatInputs in) {
        long now = in.nowMs();
        CombatInputs.Me me = in.me();
        String locked = selector.lockedId();
        Foe found = null;
        if (locked != null) {
            for (Foe b : in.bandits()) {
                if (b.id().equals(locked)) {
                    found = b;
                    break;
                }
            }
        }
        if (found != null) {
            target = found;
            targetMissingSinceMs = -1L;
            targetDistance = found.distanceTo(me.x(), me.z());
            if (!Double.isNaN(found.health()) && !Double.isNaN(found.maxHealth()) && found.health() < found.maxHealth() * 0.99D) {
                hadHurtTarget = true;
            }
            ring = cfg.ring(band);
        } else if (locked != null) {
            if (targetMissingSinceMs < 0L) {
                targetMissingSinceMs = now;
            }
            targetDistance = Double.NaN;
            target = null;
        } else {
            target = null;
            targetDistance = Double.NaN;
        }
        threats = ThreatContext.of(in.bandits(), target, me.x(), me.z(), cfg.effectiveThreatRadius());
        for (Foe p : in.players()) {
            double d = p.distanceTo(me.x(), me.z());
            double[] before = lastPlayerDistance.get(p.id());
            lastPlayerDistance.put(p.id(), new double[]{d, now});
            if (before == null) {
                playerClosing.put(p.id(), 0.0D);
            } else {
                double ticks = Math.max(1.0D, (now - before[1]) / 50.0D);
                double speed = (before[0] - d) / ticks;
                playerClosing.merge(p.id(), speed, (old, fresh) -> old * 0.7D + fresh * 0.3D);
            }
        }
        lastPlayerDistance.keySet().removeIf(id -> in.players().stream().noneMatch(p -> p.id().equals(id)));
        playerClosing.keySet().retainAll(lastPlayerDistance.keySet());
    }

    private final Map<String, Double> playerClosing = new HashMap<>();

    // ── Safety ───────────────────────────────────────────────────────────────

    private @Nullable Decision safety(CombatInputs in) {
        long now = in.nowMs();
        CombatInputs.Me me = in.me();
        List<BanditDanger.Contact> contacts = new ArrayList<>();
        for (Foe p : in.players()) {
            contacts.add(new BanditDanger.Contact(p.id(), p.distanceTo(me.x(), me.z()), playerClosing.getOrDefault(p.id(), 0.0D)));
        }
        BanditDanger.Verdict verdict = BanditDanger.evaluate(contacts, cfg.playerSafetyRadius, me.health(), cfg.criticalHealth);
        if (verdict.level() == BanditDanger.Level.CRITICAL) {
            return stopNow(in, verdict.why() + (verdict.name().isEmpty() ? "" : " (" + verdict.name() + ")"), true);
        }
        boolean breakable = state != CombatState.RETREAT && state != CombatState.COOLDOWN && state != CombatState.LOST_TARGET;
        if (!breakable) {
            return null;
        }
        if (verdict.level() == BanditDanger.Level.CAUTION) {
            return startRetreat(in, verdict.why() + (verdict.name().isEmpty() ? "" : " (" + verdict.name() + ")"));
        }
        if (me.health() <= cfg.retreatHealth) {
            if (threats.count() == 0 && !state.fighting() && state != CombatState.APPROACH) {
                // Nothing around to flee from: just wait for the health (this is no retreat and is not counted as one).
                waitHealth = true;
                go(CombatState.COOLDOWN, "LOW_HEALTH_WAIT", now);
                return decision(in, Decision.Move.NONE, Decision.Look.NONE, false, "waiting for health");
            }
            return startRetreat(in, "LOW_HEALTH");
        }
        if (threats.count() > cfg.maxNearbyThreats) {
            return startRetreat(in, "TOO_MANY_THREATS " + threats.count());
        }
        return null;
    }

    private Decision startRetreat(CombatInputs in, String why) {
        long now = in.nowMs();
        trim(retreats, now, cfg.retreatWindowMs);
        retreats.addLast(now);
        if (retreats.size() >= cfg.maxRetreats) {
            return stopNow(in, "Keeps retreating (" + why + ")", true);
        }
        retreatReason = why;
        selector.clearLock();
        target = null;
        orbit.reset();
        go(CombatState.RETREAT, why, now);
        return retreat(in);
    }

    private Decision stopNow(CombatInputs in, String why, boolean afterRecall) {
        long now = in.nowMs();
        info.accept("STOP " + why + " " + describe(in));
        go(CombatState.STOPPED, why, now);
        return new Decision(CombatState.STOPPED, why, null, Decision.Move.NONE, Decision.Look.NONE, false, why, afterRecall, why);
    }

    // ── Loops ────────────────────────────────────────────────────────────────

    private Decision handleLoop(CombatInputs in, LoopDetector.Kind kind) {
        long now = in.nowMs();
        boolean repeated = loopCount > 0 && now - lastLoopMs <= cfg.loopRepeatWindowMs;
        lastLoopMs = now;
        loopCount++;
        loops.reset();
        info.accept("LOOP_DETECTED " + kind + (repeated ? " (again)" : "") + " " + describe(in));
        if (repeated) {
            return stopNow(in, "Combat loop twice: " + kind, true);
        }
        // 1. inputs stop (COOLDOWN drives nothing)  2. stabilise  3. invalidate target + path  4. plan once more (SCAN after the cooldown)
        if (target != null) {
            selector.blacklist(target.id(), now + cfg.blacklistMs);
        }
        selector.clearLock();
        target = null;
        orbit.reset();
        goalX = Double.NaN;
        goalZ = Double.NaN;
        stabilising = true;
        go(CombatState.COOLDOWN, "LOOP " + kind, now);
        return decision(in, Decision.Move.NONE, Decision.Look.NONE, false, "loop " + kind);
    }

    // ── States ───────────────────────────────────────────────────────────────

    private Decision scan(CombatInputs in) {
        CombatInputs.Me me = in.me();
        int valid = 0;
        for (Foe b : in.bandits()) {
            if (selector.valid(b, me.x(), me.z(), in.players(), cfg, in.nowMs())) {
                valid++;
            }
        }
        if (valid > 0) {
            go(CombatState.TARGET_SELECT, "BANDITS_IN_REACH " + valid, in.nowMs());
            return targetSelect(in);
        }
        // Bandits that are out of range, skipped for a while or crowded by players are not worth a state change.
        return decision(in, Decision.Move.NONE, Decision.Look.NONE, false,
                in.bandits().isEmpty() ? "no bandit in reach" : in.bandits().size() + " bandit(s) not usable right now");
    }

    private Decision targetSelect(CombatInputs in) {
        long now = in.nowMs();
        CombatInputs.Me me = in.me();
        TargetSelector.Result result = selector.select(in.bandits(), in.players(), me.x(), me.y(), me.z(), ring, in.terrain(), cfg, band, now);
        lastSelectMs = now;
        if (result.target() == null) {
            go(CombatState.SCAN, "NO_VALID_TARGET", now);
            return decision(in, Decision.Move.NONE, Decision.Look.NONE, false, result.reason());
        }
        adopt(result, in);
        double d = result.target().distanceTo(me.x(), me.z());
        band.reset();
        bandState = band.update(d);
        lastSelectionNote = result.reason();
        trace.accept("TARGET " + result.target().id() + String.format(Locale.ROOT, " %.1fm score %.1f", d, result.score()) + " " + result.reason());
        if (bandState == DistanceBand.State.COMBAT && result.target().sight() != Foe.Sight.NO) {
            go(CombatState.ORBIT, "IN_RANGE", now);
            return orbit(in);
        }
        if (bandState == DistanceBand.State.TOO_CLOSE) {
            go(CombatState.EVADE, "TOO_CLOSE", now);
            return evade(in);
        }
        go(CombatState.APPROACH, bandState == DistanceBand.State.TOO_FAR ? "TOO_FAR" : "NO_SIGHT", now);
        return approach(in);
    }

    private void adopt(TargetSelector.Result result, CombatInputs in) {
        Foe t = result.target();
        if (result.switched()) {
            loops.onTargetChosen(in.nowMs());
            orbit.reset();
            goalX = Double.NaN;
            goalZ = Double.NaN;
            sightLostSinceMs = -1L;
            hadHurtTarget = false;
            pathFailuresSeen = 0;
        }
        target = t;
        targetDistance = t.distanceTo(in.me().x(), in.me().z());
        lastScore = result.score();
        targetMissingSinceMs = -1L;
    }

    /** A re-evaluation every half second while the fight goes on: a much better target may take over (with margin and age). */
    private void reselect(CombatInputs in) {
        long now = in.nowMs();
        if (now - lastSelectMs < 500L) {
            return;
        }
        lastSelectMs = now;
        CombatInputs.Me me = in.me();
        String before = selector.lockedId();
        TargetSelector.Result result = selector.select(in.bandits(), in.players(), me.x(), me.y(), me.z(), ring, in.terrain(), cfg, band, now);
        if (result.target() != null) {
            lastScore = result.score();
            if (before != null && !before.equals(result.target().id())) {
                adopt(result, in);
                trace.accept("TARGET_SWITCH " + before + " -> " + result.target().id() + " " + result.reason());
            }
        }
    }

    private Decision approach(CombatInputs in) {
        long now = in.nowMs();
        CombatInputs.Me me = in.me();
        Foe t = target;
        if (t == null) {
            return targetMissing(in);
        }
        if (elapsed(now) > cfg.approachTimeoutMs) {
            selector.blacklist(t.id(), now + cfg.blacklistMs);
            return lostTarget(in, "APPROACH_TIMEOUT");
        }
        reselect(in);
        t = target == null ? t : target;
        bandState = band.update(targetDistance);
        if (bandState == DistanceBand.State.COMBAT && t.sight() != Foe.Sight.NO) {
            go(CombatState.ORBIT, "IN_RANGE", now);
            return orbit(in);
        }
        if (bandState == DistanceBand.State.TOO_CLOSE) {
            go(CombatState.EVADE, "TOO_CLOSE", now);
            return evade(in);
        }
        if (in.pathFailures() > pathFailuresSeen) {
            pathFailuresSeen = in.pathFailures();
            if (pathFailuresSeen >= 3) {
                // The same way keeps failing: the target cannot be reached from here.
                selector.blacklist(t.id(), now + cfg.blacklistMs);
                loops.reset();
                return lostTarget(in, "UNREACHABLE (no path " + pathFailuresSeen + "x)");
            }
            loops.onPathFailure(now);
            if (pathFailuresSeen >= 2) {
                return toReposition(in, "NO_PATH");
            }
        }
        // goal: a point on the orbit radius, on the side away from other bandits
        if (Double.isNaN(goalX) || now - goalAtMs > 1_500L) {
            chooseApproachGoal(in, t);
        }
        double gd = Math.hypot(goalX - me.x(), goalZ - me.z());
        if (gd < 1.5D) {
            // At the goal but the band / sight do not fit: another position
            return toReposition(in, bandState == DistanceBand.State.COMBAT ? "NO_SIGHT_AT_GOAL" : "GOAL_NOT_IN_RANGE");
        }
        double probe = Math.min(gd, 12.0D);
        Terrain.Ray ray = in.terrain().cast(me.x(), me.y(), me.z(), goalX - me.x(), goalZ - me.z(), probe);
        boolean look = t.sight() != Foe.Sight.NO && targetDistance <= cfg.maxCombat + 6.0D;
        if (ray.free() >= probe - 0.6D) {
            return decision(in, Decision.Move.direction(goalX - me.x(), goalZ - me.z(), gd > 8.0D, false),
                    look ? Decision.Look.TARGET : Decision.Look.MOVEMENT, false, String.format(Locale.ROOT, "direct, %.0fm to goal", gd));
        }
        return decision(in, Decision.Move.pathTo(goalX, goalZ), Decision.Look.MOVEMENT, false,
                String.format(Locale.ROOT, "path (%s), %.0fm to goal", in.path(), gd));
    }

    private void chooseApproachGoal(CombatInputs in, Foe t) {
        CombatInputs.Me me = in.me();
        double[] away = Geo.unit(me.x() - t.x(), me.z() - t.z());
        if (away[0] == 0.0D && away[1] == 0.0D) {
            away = new double[]{1.0D, 0.0D};
        }
        double bestScore = Double.NEGATIVE_INFINITY;
        double bx = t.x() + away[0] * ring;
        double bz = t.z() + away[1] * ring;
        int[] offsets = {0, 40, -40, 80, -80};
        for (int offset : offsets) {
            double[] v = Geo.rotate(away[0], away[1], offset);
            double cx = t.x() + v[0] * ring;
            double cz = t.z() + v[1] * ring;
            double nearestOther = 99.0D;
            for (Foe f : threats.nearby()) {
                nearestOther = Math.min(nearestOther, f.distanceTo(cx, cz));
            }
            for (Foe f : in.players()) {
                nearestOther = Math.min(nearestOther, f.distanceTo(cx, cz) * 0.7D);
            }
            double s = Math.min(nearestOther, 20.0D) - Math.abs(offset) * 0.05D;
            if (s > bestScore + 1e-9) {
                bestScore = s;
                bx = cx;
                bz = cz;
            }
        }
        goalX = bx;
        goalZ = bz;
        goalAtMs = in.nowMs();
    }

    private Decision orbit(CombatInputs in) {
        long now = in.nowMs();
        CombatInputs.Me me = in.me();
        Foe t = target;
        if (t == null) {
            return targetMissing(in);
        }
        reselect(in);
        t = target == null ? t : target;
        bandState = band.update(targetDistance);
        if (bandState == DistanceBand.State.TOO_FAR) {
            go(CombatState.APPROACH, "TOO_FAR", now);
            return approach(in);
        }
        if (bandState == DistanceBand.State.TOO_CLOSE) {
            go(CombatState.EVADE, "TOO_CLOSE", now);
            return evade(in);
        }
        if (t.sight() == Foe.Sight.NO) {
            if (sightLostSinceMs < 0L) {
                sightLostSinceMs = now;
            } else if (now - sightLostSinceMs > cfg.sightLostMs) {
                sightLostSinceMs = -1L;
                return toReposition(in, "NO_SIGHT");
            }
        } else {
            sightLostSinceMs = -1L;
        }
        List<Foe> close = closeOthers(in);
        if (close.size() >= 2) {
            go(CombatState.EVADE, "CROWDED " + (close.size() + 1) + " bandits close", now);
            return evade(in);
        }
        if (in.attack() == AttackPhase.COMMIT) {
            go(CombatState.ENGAGE, "ATTACK_COMMIT", now);
            return engage(in);
        }
        if (EscapePlanner.blockedDirections(lastRays, 2.0D) >= 10) {
            return toReposition(in, "CORNER");
        }
        List<Foe> avoid = new ArrayList<>(threats.nearby());
        avoid.addAll(in.players());
        OrbitPlanner.Step step = orbit.plan(cfg, me.x(), me.y(), me.z(), t.x(), t.z(), ring, avoid, in.terrain(), now);
        if (step.flipped()) {
            loops.onOrbitFlip(now);
            trace.accept("ORBIT_FLIP -> " + step.dir() + " " + step.note());
        }
        if (step.blocked()) {
            return toReposition(in, "NO_ORBIT_SPACE");
        }
        Decision.Move move = step.dx() == 0.0D && step.dz() == 0.0D ? Decision.Move.NONE : Decision.Move.direction(step.dx(), step.dz(), false, false);
        return decision(in, move, Decision.Look.TARGET, true,
                String.format(Locale.ROOT, "orbit %s, %.1fm, ring %.1f", step.dir(), targetDistance, ring));
    }

    /** Other bandits closer than the minimum safe distance. */
    private List<Foe> closeOthers(CombatInputs in) {
        List<Foe> out = new ArrayList<>();
        for (Foe f : threats.nearby()) {
            if (f.distanceTo(in.me().x(), in.me().z()) < cfg.minSafe) {
                out.add(f);
            }
        }
        return out;
    }

    private Decision engage(CombatInputs in) {
        long now = in.nowMs();
        Foe t = target;
        if (t == null) {
            return targetMissing(in);
        }
        bandState = band.update(targetDistance);
        if (bandState == DistanceBand.State.TOO_CLOSE) {
            go(CombatState.EVADE, "TOO_CLOSE", now);
            return evade(in);
        }
        if (in.attack() != AttackPhase.COMMIT) {
            go(CombatState.ORBIT, "ATTACK_DONE", now);
            return orbit(in);
        }
        if (elapsed(now) > cfg.engageMaxMs) {
            go(CombatState.ORBIT, "ENGAGE_TIMEOUT", now);
            return orbit(in);
        }
        return decision(in, Decision.Move.NONE, Decision.Look.TARGET, true, "attack committed, holding still");
    }

    private Decision evade(CombatInputs in) {
        long now = in.nowMs();
        CombatInputs.Me me = in.me();
        Foe t = target;
        if (t == null) {
            return targetMissing(in);
        }
        bandState = band.update(targetDistance);
        boolean crowded = closeOthers(in).size() >= 2;
        if (bandState != DistanceBand.State.TOO_CLOSE && !crowded) {
            go(bandState == DistanceBand.State.TOO_FAR ? CombatState.APPROACH : CombatState.ORBIT, "SAFE_DISTANCE", now);
            return state == CombatState.APPROACH ? approach(in) : orbit(in);
        }
        if (elapsed(now) > cfg.evadeMaxMs) {
            return toReposition(in, "EVADE_TIMEOUT");
        }
        List<Foe> from = new ArrayList<>();
        from.add(t);
        from.addAll(threats.nearby());
        from.addAll(in.players());
        EscapePlanner.Escape escape = EscapePlanner.best(in.terrain(), me.x(), me.y(), me.z(), from, EscapePlanner.LOOK);
        if (escape == null) {
            return toRecover(in, "CORNERED");
        }
        return decision(in, Decision.Move.direction(escape.dx(), escape.dz(), escape.free() >= 4.0D, false), Decision.Look.TARGET, false,
                String.format(Locale.ROOT, "evade, free %.1f (%s)", escape.free(), escape.stop()));
    }

    private Decision toReposition(CombatInputs in, String why) {
        long now = in.nowMs();
        trim(repositions, now, cfg.repositionWindowMs);
        repositions.addLast(now);
        if (repositions.size() > cfg.maxRepositions) {
            if (target != null) {
                selector.blacklist(target.id(), now + cfg.blacklistMs);
            }
            return lostTarget(in, "REPOSITION_LIMIT (" + why + ")");
        }
        goalX = Double.NaN;
        goalZ = Double.NaN;
        orbit.reset();
        go(CombatState.REPOSITION, why, now);
        return reposition(in);
    }

    private Decision reposition(CombatInputs in) {
        long now = in.nowMs();
        CombatInputs.Me me = in.me();
        Foe t = target;
        if (t == null) {
            return targetMissing(in);
        }
        bandState = band.update(targetDistance);
        if (Double.isNaN(goalX)) {
            if (!chooseRepositionGoal(in, t)) {
                return toRecover(in, "NO_GOAL");
            }
        }
        double gd = Math.hypot(goalX - me.x(), goalZ - me.z());
        if (gd < 1.5D) {
            go(bandState == DistanceBand.State.TOO_FAR ? CombatState.APPROACH : bandState == DistanceBand.State.TOO_CLOSE ? CombatState.EVADE
                    : CombatState.ORBIT, "REPOSITIONED", now);
            return switch (state) {
                case APPROACH -> approach(in);
                case EVADE -> evade(in);
                default -> orbit(in);
            };
        }
        if (elapsed(now) > cfg.repositionMaxMs) {
            return toRecover(in, "REPOSITION_TIMEOUT");
        }
        Terrain.Ray ray = in.terrain().cast(me.x(), me.y(), me.z(), goalX - me.x(), goalZ - me.z(), Math.min(gd, 6.0D));
        if (ray.blocked(Math.min(gd, 6.0D) - 0.5D)) {
            // The way to the chosen spot is not open after all: another spot.
            goalX = Double.NaN;
            goalZ = Double.NaN;
            if (elapsed(now) > cfg.repositionMaxMs / 2L) {
                return toRecover(in, "GOAL_BLOCKED");
            }
        }
        return decision(in, Decision.Move.direction(goalX - me.x(), goalZ - me.z(), gd > 6.0D, false),
                t.sight() != Foe.Sight.NO ? Decision.Look.TARGET : Decision.Look.MOVEMENT, false, String.format(Locale.ROOT, "to a better spot, %.1fm", gd));
    }

    /** A spot on the orbit radius that is reachable in a straight line, open around, and away from other bandits and players. */
    private boolean chooseRepositionGoal(CombatInputs in, Foe t) {
        CombatInputs.Me me = in.me();
        double bestScore = Double.NEGATIVE_INFINITY;
        double bx = Double.NaN;
        double bz = Double.NaN;
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30.0D);
            double cx = t.x() + Math.cos(a) * ring;
            double cz = t.z() + Math.sin(a) * ring;
            double travel = Math.hypot(cx - me.x(), cz - me.z());
            if (travel < 2.0D) {
                continue;
            }
            Terrain.Ray way = in.terrain().cast(me.x(), me.y(), me.z(), cx - me.x(), cz - me.z(), travel);
            if (way.free() < travel - 1.0D) {
                continue;
            }
            double open = 0.0D;
            for (int k = 0; k < 4; k++) {
                double b = Math.toRadians(k * 90.0D);
                Terrain.Ray around = in.terrain().cast(cx, me.y(), cz, Math.cos(b), Math.sin(b), 4.0D);
                open += Math.min(around.free(), 4.0D);
            }
            double nearestOther = 30.0D;
            for (Foe f : threats.nearby()) {
                nearestOther = Math.min(nearestOther, f.distanceTo(cx, cz));
            }
            for (Foe f : in.players()) {
                nearestOther = Math.min(nearestOther, f.distanceTo(cx, cz));
            }
            if (nearestOther < 3.0D) {
                continue;
            }
            double score = open * 2.0D + Math.min(nearestOther, 20.0D) - travel * 0.3D;
            if (score > bestScore + 1e-9) {
                bestScore = score;
                bx = cx;
                bz = cz;
            }
        }
        if (Double.isNaN(bx)) {
            return false;
        }
        goalX = bx;
        goalZ = bz;
        goalAtMs = in.nowMs();
        return true;
    }

    private Decision toRecover(CombatInputs in, String why) {
        long now = in.nowMs();
        trim(recoveries, now, cfg.recoveryWindowMs);
        recoveries.addLast(now);
        if (recoveries.size() > cfg.maxRecoveries) {
            // Out of recoveries: the target is not workable from here - no RECOVER -> PATH -> RECOVER forever.
            if (target != null) {
                selector.blacklist(target.id(), now + cfg.blacklistMs);
            }
            return lostTarget(in, "RECOVERY_LIMIT (" + why + ")");
        }
        go(CombatState.RECOVER, why, now);
        return recover(in);
    }

    /** Bounded local tries to get free: away from the target, a sidestep each way, a jump forward. Four steps, then back to the fight. */
    private Decision recover(CombatInputs in) {
        long now = in.nowMs();
        CombatInputs.Me me = in.me();
        Foe t = target;
        if (t == null) {
            return targetMissing(in);
        }
        int step = (int) (elapsed(now) / cfg.recoverStepMs);
        if (step >= 4) {
            bandState = band.update(targetDistance);
            go(bandState == DistanceBand.State.TOO_FAR ? CombatState.APPROACH : bandState == DistanceBand.State.TOO_CLOSE ? CombatState.EVADE
                    : CombatState.ORBIT, "RECOVERED", now);
            return switch (state) {
                case APPROACH -> approach(in);
                case EVADE -> evade(in);
                default -> orbit(in);
            };
        }
        double[] away = Geo.unit(me.x() - t.x(), me.z() - t.z());
        if (away[0] == 0.0D && away[1] == 0.0D) {
            away = new double[]{1.0D, 0.0D};
        }
        double[] dir = switch (step) {
            case 0 -> away;
            case 1 -> new double[]{-away[1], away[0]};
            case 2 -> new double[]{away[1], -away[0]};
            default -> new double[]{-away[0], -away[1]};
        };
        Terrain.Ray ray = in.terrain().cast(me.x(), me.y(), me.z(), dir[0], dir[1], 2.0D);
        if (ray.free() < 0.8D && step < 3) {
            // This try is blocked: spend no time on it.
            return decision(in, Decision.Move.NONE, Decision.Look.TARGET, false, "recover step " + step + " blocked");
        }
        return decision(in, Decision.Move.direction(dir[0], dir[1], false, step == 3 && me.onGround()), Decision.Look.TARGET, false,
                "recover step " + step + "/3");
    }

    private Decision retreat(CombatInputs in) {
        long now = in.nowMs();
        CombatInputs.Me me = in.me();
        List<Foe> from = new ArrayList<>(in.bandits());
        from.addAll(in.players());
        double clear = Math.max(cfg.effectiveThreatRadius() * 1.5D, 18.0D);
        double clearPlayers = cfg.playerSafetyRadius * 1.2D;
        double nearest = Double.NaN;
        boolean calm = true;
        for (Foe f : from) {
            double d = f.distanceTo(me.x(), me.z());
            if (Double.isNaN(nearest) || d < nearest) {
                nearest = d;
            }
            if (d < (f.player() ? clearPlayers : clear)) {
                calm = false;
            }
        }
        if (elapsed(now) >= cfg.retreatMinMs && calm) {
            waitHealth = me.health() <= cfg.retreatHealth + cfg.resumeHealthMargin && retreatReason.startsWith("LOW_HEALTH");
            go(CombatState.COOLDOWN, "SAFE_AGAIN", now);
            return decision(in, Decision.Move.NONE, Decision.Look.NONE, false, "safe again");
        }
        if (elapsed(now) > cfg.retreatMaxMs) {
            waitHealth = me.health() <= cfg.retreatHealth + cfg.resumeHealthMargin && retreatReason.startsWith("LOW_HEALTH");
            go(CombatState.COOLDOWN, "RETREAT_TIMEOUT", now);
            return decision(in, Decision.Move.NONE, Decision.Look.NONE, false, "retreat timeout");
        }
        EscapePlanner.Escape escape = EscapePlanner.best(in.terrain(), me.x(), me.y(), me.z(), from, EscapePlanner.LOOK);
        if (escape == null) {
            return decision(in, Decision.Move.NONE, Decision.Look.HOLD, false, "retreat: cornered");
        }
        return decision(in, Decision.Move.direction(escape.dx(), escape.dz(), true, false), Decision.Look.MOVEMENT, false,
                String.format(Locale.ROOT, "retreat (%s), nearest %.0fm", retreatReason, Double.isNaN(nearest) ? -1.0D : nearest));
    }

    private Decision lostTarget(CombatInputs in, String why) {
        long now = in.nowMs();
        if (why.startsWith("TARGET_GONE") && hadHurtTarget) {
            kills++;
        }
        selector.clearLock();
        target = null;
        orbit.reset();
        goalX = Double.NaN;
        goalZ = Double.NaN;
        sightLostSinceMs = -1L;
        hadHurtTarget = false;
        go(CombatState.LOST_TARGET, why, now);
        return lost(in);
    }

    private Decision lost(CombatInputs in) {
        go(CombatState.COOLDOWN, "AFTER_LOST_TARGET", in.nowMs());
        return decision(in, Decision.Move.NONE, Decision.Look.NONE, false, "target lost");
    }

    private Decision cooldown(CombatInputs in) {
        long now = in.nowMs();
        long wait = stabilising ? cfg.stabiliseMs : cfg.cooldownMs;
        if (waitHealth) {
            if (in.me().health() >= cfg.retreatHealth + cfg.resumeHealthMargin) {
                waitHealth = false;
            } else if (elapsed(now) > cfg.healthWaitMaxMs) {
                return stopNow(in, "Health did not recover", false);
            } else {
                return decision(in, Decision.Move.NONE, Decision.Look.NONE, false,
                        String.format(Locale.ROOT, "waiting for health %.0f/%.0f", in.me().health(), cfg.retreatHealth + cfg.resumeHealthMargin));
            }
        }
        if (elapsed(now) >= wait) {
            stabilising = false;
            go(CombatState.SCAN, "COOLED_DOWN", now);
        }
        return decision(in, Decision.Move.NONE, Decision.Look.NONE, false, "cooling down");
    }

    /**
     * The locked target is not among this tick's bandits. For {@code lostGraceMs} the lock stays and the macro holds still (a bandit can drop
     * out of one sample); then it is lost.
     */
    private Decision targetMissing(CombatInputs in) {
        long now = in.nowMs();
        if (targetMissingSinceMs >= 0L && now - targetMissingSinceMs < cfg.lostGraceMs && selector.lockedId() != null) {
            return decision(in, Decision.Move.NONE, Decision.Look.HOLD, false, "target not seen for " + (now - targetMissingSinceMs) + " ms");
        }
        return lostTarget(in, "TARGET_GONE");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Decision decision(CombatInputs in, Decision.Move move, Decision.Look look, boolean wantAttack, String detail) {
        boolean allowed = wantAttack && state.fighting() && bandState == DistanceBand.State.COMBAT && target != null
                && target.sight() != Foe.Sight.NO && in.spear() != SpearStatus.KNOWN_COOLDOWN;
        return new Decision(state, reason, target == null ? null : target.id(), move, look, allowed, null, false, detail);
    }

    private void go(CombatState next, String why, long now) {
        if (next == state) {
            return;
        }
        CombatState from = state;
        state = next;
        reason = why;
        enteredMs = now;
        if (next != CombatState.REPOSITION && next != CombatState.APPROACH) {
            goalX = Double.NaN;
            goalZ = Double.NaN;
        }
        loops.onState(now, from, next);
        trace.accept("STATE " + from + " -> " + next + " reason=" + why);
    }

    private static void trim(ArrayDeque<Long> times, long now, long window) {
        while (!times.isEmpty() && now - times.peekFirst() > window) {
            times.pollFirst();
        }
    }

    /** The compact context line of the developer log. */
    public String describe(CombatInputs in) {
        return String.format(Locale.ROOT, "BANDIT_STATE=%s TARGET=%s TARGET_DISTANCE=%s TARGET_SCORE=%s THREAT_COUNT=%d DISTANCE_STATE=%s "
                        + "ORBIT_DIRECTION=%s RECOVERY_COUNT=%d RETREAT_REASON=%s PATH_STATUS=%s", state, target == null ? "-" : target.id(),
                Double.isNaN(targetDistance) ? "-" : String.format(Locale.ROOT, "%.1f", targetDistance),
                Double.isNaN(lastScore) ? "-" : String.format(Locale.ROOT, "%.1f", lastScore), threats.count(), bandState,
                orbit.dir() == null ? "-" : orbit.dir(), recoveries.size(), retreatReason.isEmpty() ? "-" : retreatReason, in.path());
    }

    /** The last selection note (why the target was chosen), for the debug view. */
    public String lastSelectionNote() {
        return lastSelectionNote;
    }
}
