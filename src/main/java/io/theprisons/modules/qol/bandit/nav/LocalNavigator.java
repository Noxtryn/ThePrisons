package io.theprisons.modules.qol.bandit.nav;

import io.theprisons.modules.qol.bandit.combat.Geo;
import io.theprisons.modules.qol.bandit.combat.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Moves the player through a bandit area: keep going, keep EVERY bandit at least {@code minDistance} away, stay out of crowds, bend around walls
 * before touching them, run through gaps the body fits, jump real steps, never stand still. Pure logic (no Minecraft): world coordinates in, a world
 * direction out; the module turns that into keys with the same {@link NavDrive}.
 *
 * <p>The layers, each testable on its own:
 * <ol>
 *   <li>{@link NavInputs}: perception (player, bandits with velocity, ground, view yaw, body width).</li>
 *   <li>{@link ExecutionModel}: for a desired heading, the route the keys will REALLY walk while the view catches up ({@link ExecutedPath}).</li>
 *   <li>{@link BodyClearance}: the body (centre + both edges) swept along that route: walls, corners, gaps, steps, drops.</li>
 *   <li>{@link ThreatField}: every bandit, predicted, summed along the route: closest approach, crowd density, gap width.</li>
 *   <li>{@link CandidateScorer}: all of it as one explainable score ({@link CandidateScorer.Terms}).</li>
 *   <li>{@link MotionMemory}: momentum / hysteresis, evade enter/exit, left-right flutter, failed headings, stuck (last resort).</li>
 *   <li>{@link AimWindow}: a safe window for the future spear aim; any breach of the minimum distance closes it at once.</li>
 * </ol>
 * This class only orchestrates them.
 */
public final class LocalNavigator {
    private final NavConfig cfg;
    private final AimWindow aimWindow;
    private final MotionMemory memory;
    private long lastJumpMs = Long.MIN_VALUE / 2L;
    private JumpPhase jumpPhase = JumpPhase.NONE;
    private int jumpHoldLeft;
    private int jumpTicksHeld;
    private boolean jumpFailed;
    private long jumpAirSinceMs;
    private double anchorX = Double.NaN;
    private double anchorZ = Double.NaN;
    private NavAction lastAction = NavAction.CONTINUE;
    private double lastHeadingChangeDegrees;
    private double lastCurrentScore;
    private double lastBestScore;

    public LocalNavigator(NavConfig cfg) {
        this.cfg = cfg;
        this.aimWindow = new AimWindow(cfg.requiredStableTicks);
        this.memory = new MotionMemory(cfg);
    }

    public AimWindow aimWindow() {
        return aimWindow;
    }

    public int oscillations() {
        return memory.oscillations();
    }

    public double[] heading() {
        return new double[]{memory.headingX(), memory.headingZ()};
    }

    /** Forget everything (a new run). */
    public void reset() {
        memory.reset();
        anchorX = Double.NaN;
        anchorZ = Double.NaN;
        lastJumpMs = Long.MIN_VALUE / 2L;
        jumpPhase = JumpPhase.NONE;
        jumpHoldLeft = 0;
        jumpTicksHeld = 0;
        jumpFailed = false;
        aimWindow.reset();
    }

    /** The danger of one bandit at distance {@code d}: huge under the minimum distance, fading out over the warning band, 0 beyond. */
    public static double danger(double d, double min, double warning) {
        if (d <= min) {
            return 1000.0D;
        }
        if (d >= min + warning) {
            return 0.0D;
        }
        double t = 1.0D - (d - min) / warning;
        return t * t * 100.0D;
    }

    // ── Planning ─────────────────────────────────────────────────────────────

    public NavDecision plan(NavInputs in) {
        long now = in.nowMs();
        double px = in.x();
        double pz = in.z();
        if (!memory.hasHeading()) {
            double speed = Math.hypot(in.vx(), in.vz());
            double[] h = speed > 1.0D ? new double[]{in.vx() / speed, in.vz() / speed} : Geo.forward(in.viewYaw());
            memory.initHeading(h[0], h[1]);
        }
        if (in.area() == SpearAreaState.VALID) {
            anchorX = px;
            anchorZ = pz;
        }
        double hx = memory.headingX();
        double hz = memory.headingZ();

        // 1. Threat summary
        double nearest = Double.NaN;
        int nearby = 0;
        double reach = cfg.minDistance + cfg.warningBand + cfg.lookahead;
        for (NavBandit b : in.bandits()) {
            double d = b.distanceTo(px, pz);
            if (Double.isNaN(nearest) || d < nearest) {
                nearest = d;
            }
            if (d <= reach) {
                nearby++;
            }
        }
        boolean breach = !Double.isNaN(nearest) && nearest < cfg.minDistance;
        boolean stuck = memory.updateStuck(now, px, pz, indexOf(hx, hz));
        memory.expire(now);
        boolean emergency = memory.updateEvading(breach, nearest) || stuck;

        // 1b. The life of a committed jump: HOLD (key down on the ground) -> AIR (lifted off) -> landed
        if (jumpPhase == JumpPhase.HOLD && !in.onGround()) {
            jumpPhase = JumpPhase.AIR;
            jumpAirSinceMs = now;
        } else if (jumpPhase == JumpPhase.HOLD && jumpHoldLeft <= 0) {
            jumpPhase = JumpPhase.NONE;       // the key was down long enough and the player never lifted off: may be tried again
            jumpFailed = true;
        } else if (jumpPhase == JumpPhase.AIR && (in.onGround() && now - jumpAirSinceMs > 100L || now - jumpAirSinceMs > 1500L)) {
            jumpPhase = JumpPhase.NONE;
        }
        boolean jumpLock = jumpPhase != JumpPhase.NONE && !breach && !stuck;

        // 2. Candidates: every heading (and the current one) as the route the keys will really walk
        List<NavCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < cfg.directions; i++) {
            double a = Math.toRadians(i * 360.0D / cfg.directions);
            candidates.add(evaluate(in, i, Math.cos(a), Math.sin(a), breach));
        }
        NavCandidate current = evaluate(in, -1, hx, hz, breach);
        candidates.add(current);

        // 3. Choice with momentum
        NavCandidate best = null;
        for (NavCandidate c : candidates) {
            if (c.index() >= 0 && c.blocked().isEmpty() && (best == null || c.score() > best.score() + 1e-9)) {
                best = c;
            }
        }
        String reason;
        NavCandidate chosen;
        double margin = Math.max(cfg.switchMargin, Math.abs(current.score()) * cfg.switchShare) * (memory.committed(now) ? 2.0D : 1.0D);
        if (jumpLock && current.blocked().isEmpty()) {
            chosen = current;
            reason = "jump committed (" + jumpPhase + "): course locked";
        } else if (best == null) {
            NavCandidate freest = current;
            for (NavCandidate c : candidates) {
                if (c.free() > freest.free()) {
                    freest = c;
                }
            }
            chosen = freest;
            reason = "cornered: freest way " + fmt(chosen.free()) + " blocks";
        } else if (!emergency && current.blocked().isEmpty() && current.safe() && best.score() <= current.score() + margin) {
            chosen = current;
            reason = Double.isNaN(nearest) ? "no bandits near: exploring forward, free " + fmt(current.free())
                    : "keep heading, nearest " + fmt(nearest) + ", free " + fmt(current.free());
        } else {
            chosen = best;
            reason = stuck ? "stuck: another lane" : breach ? "bandit under " + fmt(cfg.minDistance) + " (" + fmt(nearest) + "): evade"
                    : !current.blocked().isEmpty() ? "heading blocked (" + current.blocked() + ")" : !current.safe() ? "heading unsafe (nearest ahead "
                    + fmt(current.nearest()) + ", free " + fmt(current.free()) + " " + current.stop() + ")" : "better lane: score " + fmt(best.score()) + " vs "
                    + fmt(current.score());
        }
        if (in.area() == SpearAreaState.INVALID) {
            reason += " | spear area INVALID" + (Double.isNaN(anchorX) ? ": searching" : ": back towards the last valid spot");
        }

        // 4. Memory
        double angle = Math.toDegrees(Math.acos(clamp(chosen.dirX() * hx + chosen.dirZ() * hz, -1.0D, 1.0D)));
        double sign = Math.signum(hx * chosen.dirZ() - hz * chosen.dirX());
        lastHeadingChangeDegrees = angle;
        lastCurrentScore = current.score();
        lastBestScore = best == null ? Double.NaN : best.score();
        if (memory.recordHeading(now, chosen.dirX(), chosen.dirZ(), angle, sign)) {
            reason += " | left-right flutter: committing to this lane";
        }

        // 5. Jump: a deliberate commit - the key stays down for several ticks while the player is on the ground, the course is locked until the landing
        boolean jump = false;
        if (jumpPhase == JumpPhase.HOLD) {
            jump = true;
            jumpHoldLeft--;
            jumpTicksHeld++;
        } else if (jumpPhase == JumpPhase.NONE && chosen.jumpAt() >= 0.0D && chosen.jumpAt() <= cfg.jumpWithin && in.onGround()) {
            long since = now - lastJumpMs;
            if (since >= cfg.jumpCooldownMs || jumpFailed && since >= cfg.jumpRetryMs) {
                jumpPhase = JumpPhase.HOLD;
                jumpHoldLeft = cfg.jumpHoldTicks - 1;
                jumpTicksHeld = 1;
                jumpFailed = false;
                lastJumpMs = now;
                jump = true;
            }
        }
        if (jump) {
            reason += " | jump over a step in " + fmt(chosen.jumpAt()) + " blocks (held " + jumpTicksHeld + ")";
        }

        // 6. Aim window
        double threatHere = threatAt(in, px, pz, 0.3D);
        boolean closingFast = closingFast(in);
        boolean airborne = !in.onGround() || jump || now - lastJumpMs < 400L;
        boolean safeNow = !breach && threatHere <= cfg.aimThreatLimit && chosen.blocked().isEmpty() && chosen.safe() && chosen.free() >= cfg.aimFreeAhead
                && !closingFast && !airborne && memory.headingDrift() <= cfg.stableHeadingDegrees && !stuck;
        aimWindow.update(safeNow, breach);

        boolean terrainChange = chosen != current && (!current.blocked().isEmpty() || current.pressure() > 0.0D
                || !current.stop().equals("CLEAR") && current.free() < cfg.wallUrgent);
        NavAction action = classify(chosen, angle, sign, breach, stuck, jump);
        lastAction = action;
        return new NavDecision(chosen.dirX(), chosen.dirZ(), true, jump, chosen.score(), nearest, chosen.nearest(), threatHere, chosen.free(), safeNow,
                aimWindow.open(), aimWindow.ticks(), breach, nearby, action, Math.toDegrees(Math.atan2(chosen.dirZ(), chosen.dirX())), in.area(), reason,
                candidates, memory.oscillations(), stuck, chosen, terrainChange, jumpPhase, jumpTicksHeld,
                Math.max(0L, cfg.jumpCooldownMs - (now - lastJumpMs)));
    }

    private boolean closingFast(NavInputs in) {
        for (NavBandit b : in.bandits()) {
            double d = b.distanceTo(in.x(), in.z());
            if (d < cfg.minDistance + cfg.warningBand && d > 0.01D) {
                double closing = -((b.x() - in.x()) * (b.vx() - in.vx()) + (b.z() - in.z()) * (b.vz() - in.vz())) / d;
                if (closing > cfg.closingSpeed) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @param sign the side of the turn: +1 = to the player's right, -1 = left, 0 = straight on (cross product of the old and the new heading) */
    private NavAction classify(NavCandidate chosen, double angle, double sign, boolean breach, boolean stuck, boolean jump) {
        if (stuck) {
            return NavAction.RECOVER;
        }
        boolean right = sign > 0.0D;
        if (jump) {
            return angle < 50.0D ? NavAction.JUMP_FORWARD : right ? NavAction.JUMP_RIGHT : NavAction.JUMP_LEFT;
        }
        if (angle > 120.0D || breach && angle > 60.0D) {
            return NavAction.HARD_EVADE;
        }
        if (angle >= 50.0D) {
            return right ? NavAction.STRAFE_RIGHT : NavAction.STRAFE_LEFT;
        }
        if (angle >= 12.0D) {
            return NavAction.DIAGONAL;
        }
        return chosen.nearest() < cfg.minDistance + cfg.warningBand || lastAction == NavAction.RUN ? NavAction.CONTINUE : NavAction.RUN;
    }

    // ── One candidate: execution → body → threat → score ─────────────────────

    private NavCandidate evaluate(NavInputs in, int index, double wantX, double wantZ, boolean breach) {
        double px = in.x();
        double pz = in.z();
        ExecutedPath path = ExecutionModel.simulate(px, pz, in.viewYaw(), wantX, wantZ, cfg.pathTicks);
        BodyClearance body = BodyClearance.sweep(in.terrain(), in.y(), path, in.halfWidth(), cfg.bodyInset);
        ExecutedPath.Leg first = path.first();
        ExecutedPath.Leg last = path.legs().get(path.legs().size() - 1);

        Terrain.Stop stop = body.stop();
        double free = Math.min(body.free(), cfg.lookahead);
        if (body.free() >= cfg.lookahead) {
            stop = Terrain.Stop.CLEAR;     // the obstacle (if any) lies beyond what is planned
        }
        double jumpAt = body.jumpAt();
        String blocked = "";
        if (jumpAt >= 0.0D && free < jumpAt + cfg.jumpLanding && stop != Terrain.Stop.CLEAR) {
            blocked = "STEP_NO_LANDING " + fmt(free);   // the "step" leads straight into a wall: not a jumpable step
            jumpAt = -1.0D;
        } else if (stop != Terrain.Stop.CLEAR && free < cfg.minFree) {
            blocked = stop + " " + fmt(free);
        }
        boolean jumpTooSoon = jumpPhase == JumpPhase.NONE && jumpAt >= 0.0D && jumpAt <= 1.5D
                && (!in.onGround() || in.nowMs() - lastJumpMs < cfg.jumpCooldownMs && !jumpFailed);
        double pressure = 0.0D;     // wall pressure: the closer the end of the way, the worse - long before it is "blocked"
        if (stop != Terrain.Stop.CLEAR && free < cfg.wallComfort) {
            double t = (cfg.wallComfort - free) / cfg.wallComfort;
            pressure = cfg.wWall * t * t;
        }
        ThreatField threat = ThreatField.along(in.bandits(), path, free, cfg, px, pz);

        double hx = memory.headingX();
        double hz = memory.headingZ();
        double dot = clamp(wantX * hx + wantZ * hz, -1.0D, 1.0D);
        double areaPull = 0.0D;
        if (in.area() == SpearAreaState.INVALID && !Double.isNaN(anchorX)) {
            double[] to = Geo.unit(anchorX - px, anchorZ - pz);
            areaPull = wantX * to[0] + wantZ * to[1];
        }
        // where the route ends up is what the desired heading really gets: the error of the last leg (the first legs bend while the view turns)
        double finalError = Geo.angleBetween(last.dirX(), last.dirZ(), wantX, wantZ);
        boolean endsInObstacle = stop != Terrain.Stop.CLEAR && stop != Terrain.Stop.STEP;
        CandidateScorer.Terms terms = CandidateScorer.score(cfg, new CandidateScorer.Facts(free, endsInObstacle, free, pressure, threat.nearest(),
                threat.average(), threat.peak(), threat.gap(), dot, Math.toDegrees(Math.acos(dot)), finalError, jumpAt >= 0.0D, jumpTooSoon,
                index >= 0 && memory.failedRecently(index), memory.committed(in.nowMs()), areaPull, body.sideLeft(), body.sideRight(), breach));
        boolean wallClose = stop != Terrain.Stop.CLEAR && free < cfg.wallUrgent;
        boolean safe = blocked.isEmpty() && !wallClose && threat.nearest() >= cfg.minDistance * cfg.projectedMarginFraction;
        return new NavCandidate(index, wantX, wantZ, first.dirX(), first.dirZ(), Geo.angleBetween(first.dirX(), first.dirZ(), wantX, wantZ), free,
                body.center(), body.left(), body.right(), stop.name(), jumpAt, pressure, threat.nearest(), threat.average(), threat.peak(), threat.gap(),
                terms.total(), blocked, safe, terms, body.sideLeft(), body.sideRight(), path.legs().size());
    }

    /** The danger at the player's own place a short time ahead (the bandits predicted that far). */
    private double threatAt(NavInputs in, double x, double z, double seconds) {
        double sum = 0.0D;
        for (NavBandit b : in.bandits()) {
            double k = Math.hypot(b.vx(), b.vz()) <= cfg.maxBanditSpeed ? 1.0D : 0.0D;
            sum += danger(Math.hypot(x - (b.x() + k * b.vx() * seconds), z - (b.z() + k * b.vz() * seconds)), cfg.minDistance, cfg.warningBand);
        }
        return sum;
    }

    private int indexOf(double dx, double dz) {
        double a = Math.toDegrees(Math.atan2(dz, dx));
        if (a < 0.0D) {
            a += 360.0D;
        }
        return (int) Math.round(a / (360.0D / cfg.directions)) % cfg.directions;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ── For the log ──────────────────────────────────────────────────────────

    /** The turn of the last decision in degrees. */
    public double lastHeadingChangeDegrees() {
        return lastHeadingChangeDegrees;
    }

    public double previousHeadingDegrees() {
        return memory.previousHeadingDegrees();
    }

    public long headingAgeMs(long now) {
        return memory.headingAgeMs(now);
    }

    public double lastCurrentScore() {
        return lastCurrentScore;
    }

    public double lastBestScore() {
        return lastBestScore;
    }
}
