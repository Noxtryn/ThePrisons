package io.theprisons.modules.qol.bandit.dodge;

import io.theprisons.modules.qol.bandit.combat.Geo;
import io.theprisons.modules.qol.bandit.combat.Terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Moves the player through a bandit area: keep going, keep EVERY bandit at least {@code minDistance} away, stay out of crowds, run through
 * gaps, jump small steps, never stand still. Pure logic (no Minecraft): world coordinates in, a world direction out; the module turns that into
 * keys.
 *
 * <p><b>How a direction is judged.</b> 16 directions around the player plus the current heading are probed on the ground (wall, drop, hazard,
 * unknown, step that needs a jump). Along each walkable probe, every {@code sampleStep} blocks, the player's future place is compared with where
 * EVERY bandit will be (its velocity, a short prediction): the closest approach, and the summed danger of all bandits there. The danger of a
 * bandit rises steeply under {@code minDistance + warningBand} and is huge under {@code minDistance}; summing it makes a crowd far worse than one
 * bandit, so a lane through four bandits loses to a lane past one. The gap of a lane is the narrowest clearance to a bandit ahead. Forward
 * continuity, the size of the turn, a reversal, dead ends and recently failed directions adjust the score.
 *
 * <p><b>Keeping momentum.</b> The current heading is scored like every other direction and only replaced when another one is clearly better
 * (margin) or the current one is unsafe, blocked, the player is stuck or a bandit is under the minimum distance. A left / right / left / right
 * flutter commits to a lane for a moment instead.
 *
 * <p><b>Aim window.</b> {@link AimWindow}: safe for N ticks in a row; any breach of the minimum distance closes it at once.
 */
public final class BanditDodgePlanner {
    private static final double GAP_CAP = 12.0D;
    private static final double NO_BANDIT_DISTANCE = 60.0D;

    private final DodgeConfig cfg;
    private final AimWindow aimWindow;
    private double hx = 0.0D;
    private double hz = 1.0D;
    private boolean hasHeading;
    private long lastJumpMs = Long.MIN_VALUE / 2L;
    private final Map<Integer, Long> failed = new HashMap<>();
    private final ArrayDeque<double[]> poses = new ArrayDeque<>();
    private final ArrayDeque<double[]> turns = new ArrayDeque<>();
    private final ArrayDeque<double[]> headings = new ArrayDeque<>();
    private long committedUntil = Long.MIN_VALUE / 2L;
    private int oscillations;
    private double anchorX = Double.NaN;
    private double anchorZ = Double.NaN;
    private DodgeAction lastAction = DodgeAction.CONTINUE;
    private double lastHeadingChangeDegrees;
    private boolean evading;
    private long headingSinceMs;
    private double previousHeadingDegrees = Double.NaN;
    private double lastCurrentScore;
    private double lastBestScore;

    public BanditDodgePlanner(DodgeConfig cfg) {
        this.cfg = cfg;
        this.aimWindow = new AimWindow(cfg.requiredStableTicks);
    }

    public AimWindow aimWindow() {
        return aimWindow;
    }

    public int oscillations() {
        return oscillations;
    }

    public double[] heading() {
        return new double[]{hx, hz};
    }

    /** Forget everything (a new run). */
    public void reset() {
        hasHeading = false;
        failed.clear();
        poses.clear();
        turns.clear();
        headings.clear();
        committedUntil = Long.MIN_VALUE / 2L;
        oscillations = 0;
        anchorX = Double.NaN;
        anchorZ = Double.NaN;
        lastJumpMs = Long.MIN_VALUE / 2L;
        evading = false;
        headingSinceMs = 0L;
        previousHeadingDegrees = Double.NaN;
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

    public DodgeDecision plan(DodgeInputs in) {
        long now = in.nowMs();
        double px = in.x();
        double pz = in.z();
        if (!hasHeading) {
            double speed = Math.hypot(in.vx(), in.vz());
            double[] h = speed > 1.0D ? new double[]{in.vx() / speed, in.vz() / speed} : Geo.forward(in.viewYaw());
            hx = h[0];
            hz = h[1];
            hasHeading = true;
        }
        if (in.area() == SpearAreaState.VALID) {
            anchorX = px;
            anchorZ = pz;
        }

        double nearest = Double.NaN;
        int nearby = 0;
        double reach = cfg.minDistance + cfg.warningBand + cfg.lookahead;
        for (DodgeBandit b : in.bandits()) {
            double d = b.distanceTo(px, pz);
            if (Double.isNaN(nearest) || d < nearest) {
                nearest = d;
            }
            if (d <= reach) {
                nearby++;
            }
        }
        boolean breach = !Double.isNaN(nearest) && nearest < cfg.minDistance;

        // stuck: asked to run but hardly moving
        poses.addLast(new double[]{now, px, pz});
        while (poses.size() > 1 && now - poses.peekFirst()[0] > cfg.stuckWindowMs) {
            poses.pollFirst();
        }
        boolean stuck = false;
        double[] first = poses.peekFirst();
        if (first != null && now - first[0] >= cfg.stuckWindowMs * 0.9D && Math.hypot(px - first[1], pz - first[2]) < cfg.stuckMinMove) {
            stuck = true;
            failed.put(indexOf(hx, hz), now + cfg.failedHeadingMs);
            poses.clear();
        }
        failed.values().removeIf(until -> until <= now);

        // Evade has an enter and an exit threshold: in under the minimum distance, out only beyond minimum + buffer.
        if (breach) {
            evading = true;
        } else if (Double.isNaN(nearest) || nearest > cfg.minDistance + cfg.evadeExitBuffer) {
            evading = false;
        }
        boolean emergency = evading || stuck;
        List<DodgeCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < cfg.directions; i++) {
            double a = Math.toRadians(i * 360.0D / cfg.directions);
            candidates.add(evaluate(in, i, Math.cos(a), Math.sin(a), breach, nearest));
        }
        DodgeCandidate current = evaluate(in, -1, hx, hz, breach, nearest);
        candidates.add(current);

        DodgeCandidate best = null;
        for (DodgeCandidate c : candidates) {
            if (c.index() < 0 || !c.blocked().isEmpty()) {
                continue;
            }
            if (best == null || c.score() > best.score() + 1e-9) {
                best = c;
            }
        }
        String reason;
        DodgeCandidate chosen;
        double margin = Math.max(cfg.switchMargin, Math.abs(current.score()) * cfg.switchShare) * (now < committedUntil ? 2.0D : 1.0D);
        if (best == null) {
            // every direction is blocked: the freest one
            DodgeCandidate freest = current;
            for (DodgeCandidate c : candidates) {
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
                    + fmt(current.nearest()) + ")" : "better lane: score " + fmt(best.score()) + " vs " + fmt(current.score());
        }
        if (in.area() == SpearAreaState.INVALID) {
            reason += " | spear area INVALID" + (Double.isNaN(anchorX) ? ": searching" : ": back towards the last valid spot");
        }

        // heading memory, turns, oscillation
        double angle = Math.toDegrees(Math.acos(clamp(chosen.dirX() * hx + chosen.dirZ() * hz, -1.0D, 1.0D)));
        double sign = Math.signum(hx * chosen.dirZ() - hz * chosen.dirX());
        lastHeadingChangeDegrees = angle;
        if (angle > 35.0D) {
            turns.addLast(new double[]{now, sign});
            while (!turns.isEmpty() && now - turns.peekFirst()[0] > cfg.oscillationWindowMs) {
                turns.pollFirst();
            }
            if (oscillating()) {
                oscillations++;
                committedUntil = now + cfg.commitMs;
                turns.clear();
                reason += " | left-right flutter: committing to this lane";
            }
        }
        if (angle > 5.0D || headingSinceMs == 0L) {
            previousHeadingDegrees = Math.toDegrees(Math.atan2(hz, hx));
            headingSinceMs = now;
        }
        lastCurrentScore = current.score();
        lastBestScore = best == null ? Double.NaN : best.score();
        hx = chosen.dirX();
        hz = chosen.dirZ();
        headings.addLast(new double[]{now, Math.toDegrees(Math.atan2(hz, hx))});
        while (headings.size() > 1 && now - headings.peekFirst()[0] > 400L) {
            headings.pollFirst();
        }

        // jump
        boolean jump = chosen.jumpAt() >= 0.0D && chosen.jumpAt() <= cfg.jumpWithin && in.onGround() && now - lastJumpMs >= cfg.jumpCooldownMs;
        if (jump) {
            lastJumpMs = now;
            reason += " | jump over a step in " + fmt(chosen.jumpAt()) + " blocks";
        }

        // aim window
        double threatHere = threatAt(in, px, pz, 0.3D);
        boolean closingFast = false;
        for (DodgeBandit b : in.bandits()) {
            double d = b.distanceTo(px, pz);
            if (d < cfg.minDistance + cfg.warningBand && d > 0.01D) {
                double closing = -((b.x() - px) * (b.vx() - in.vx()) + (b.z() - pz) * (b.vz() - in.vz())) / d;
                if (closing > cfg.closingSpeed) {
                    closingFast = true;
                }
            }
        }
        double[] oldest = headings.peekFirst();
        double headingDrift = oldest == null ? 0.0D : Math.abs(wrap180(Math.toDegrees(Math.atan2(hz, hx)) - oldest[1]));
        boolean airborne = !in.onGround() || jump || now - lastJumpMs < 400L;
        boolean safeNow = !breach && threatHere <= cfg.aimThreatLimit && chosen.blocked().isEmpty() && chosen.safe() && chosen.free() >= cfg.aimFreeAhead
                && !closingFast && !airborne && headingDrift <= cfg.stableHeadingDegrees && !stuck;
        aimWindow.update(safeNow, breach);

        boolean terrainChange = chosen != current && (!current.blocked().isEmpty() || current.pressure() > 0.0D
                || !current.stop().equals("CLEAR") && current.free() < cfg.wallUrgent);
        DodgeAction action = classify(chosen, angle, sign, breach, stuck, jump);
        lastAction = action;
        return new DodgeDecision(chosen.dirX(), chosen.dirZ(), true, jump, chosen.score(), nearest, chosen.nearest(), threatHere, chosen.free(), safeNow,
                aimWindow.open(), aimWindow.ticks(), breach, nearby, action, Math.toDegrees(Math.atan2(chosen.dirZ(), chosen.dirX())), in.area(), reason,
                candidates, oscillations, stuck, chosen, terrainChange);
    }

    /** @param sign the side of the turn: +1 = to the player's right, -1 = left, 0 = straight on (cross product of the old and the new heading) */
    private DodgeAction classify(DodgeCandidate chosen, double angle, double sign, boolean breach, boolean stuck, boolean jump) {
        if (stuck) {
            return DodgeAction.RECOVER;
        }
        boolean right = sign > 0.0D;
        if (jump) {
            return angle < 50.0D ? DodgeAction.JUMP_FORWARD : right ? DodgeAction.JUMP_RIGHT : DodgeAction.JUMP_LEFT;
        }
        if (angle > 120.0D || breach && angle > 60.0D) {
            return DodgeAction.HARD_EVADE;
        }
        if (angle >= 50.0D) {
            return right ? DodgeAction.STRAFE_RIGHT : DodgeAction.STRAFE_LEFT;
        }
        if (angle >= 12.0D) {
            return DodgeAction.DIAGONAL;
        }
        return chosen.nearest() < cfg.minDistance + cfg.warningBand || lastAction == DodgeAction.RUN ? DodgeAction.CONTINUE : DodgeAction.RUN;
    }

    /** The last four turns alternate between left and right within the window. */
    private boolean oscillating() {
        if (turns.size() < 4) {
            return false;
        }
        double[][] all = turns.toArray(new double[0][]);
        for (int i = all.length - 3; i < all.length; i++) {
            if (all[i][1] == 0.0D || all[i][1] == all[i - 1][1]) {
                return false;
            }
        }
        return true;
    }

    // ── Scoring ──────────────────────────────────────────────────────────────

    /** The three body probes along the executed direction: centre, left edge, right edge. The free distance is the worst of them. */
    private record Body(double free, double center, double left, double right, Terrain.Stop stop, double jumpAt, double heightChange) {
    }

    private Body probe(DodgeInputs in, double ex, double ez) {
        double hw = Math.max(0.0D, in.halfWidth() - cfg.bodyInset);
        double rx = -ez;   // perpendicular to the executed direction (its right side in x/z)
        double rz = ex;
        Terrain.Ray c = in.terrain().cast(in.x(), in.y(), in.z(), ex, ez, cfg.lookahead);
        Terrain.Ray l = in.terrain().cast(in.x() - rx * hw, in.y(), in.z() - rz * hw, ex, ez, cfg.lookahead);
        Terrain.Ray r = in.terrain().cast(in.x() + rx * hw, in.y(), in.z() + rz * hw, ex, ez, cfg.lookahead);
        Terrain.Ray worst = c;
        if (l.free() < worst.free()) {
            worst = l;
        }
        if (r.free() < worst.free()) {
            worst = r;
        }
        // a step up counts only when every body edge climbs it (one edge against a wall corner is a wall, not a step)
        double jumpAt = c.jumpAt();
        if (jumpAt >= 0.0D && (l.jumpAt() < 0.0D && l.free() <= jumpAt + 0.3D || r.jumpAt() < 0.0D && r.free() <= jumpAt + 0.3D)) {
            jumpAt = -1.0D;
        }
        return new Body(worst.free(), c.free(), l.free(), r.free(), worst.stop(), jumpAt, c.heightChange());
    }

    private DodgeCandidate evaluate(DodgeInputs in, int index, double wantX, double wantZ, boolean breach, double nearestNow) {
        double px = in.x();
        double pz = in.z();
        // What Minecraft will really do for this desired heading with the current view yaw: everything below is judged along THAT direction.
        DodgeDrive.ExecutedMove exec = DodgeDrive.resolve(wantX, wantZ, in.viewYaw());
        double dx = exec.dirX();
        double dz = exec.dirZ();
        Body body = probe(in, dx, dz);
        double free = body.free();
        Terrain.Stop stop = body.stop();
        double jumpAt = body.jumpAt();
        String blocked = "";
        if (jumpAt >= 0.0D && free < jumpAt + cfg.jumpLanding && stop != Terrain.Stop.CLEAR) {
            // the "step" leads into a wall / dead end right behind it: not a jumpable step
            blocked = "STEP_NO_LANDING " + fmt(free);
            jumpAt = -1.0D;
        } else if (stop != Terrain.Stop.CLEAR && free < cfg.minFree) {
            blocked = stop + " " + fmt(free);
        }
        boolean jumpTooSoon = jumpAt >= 0.0D && jumpAt <= 1.5D && (!in.onGround() || in.nowMs() - lastJumpMs < cfg.jumpCooldownMs);
        double reach = Math.min(free, cfg.lookahead);
        // Wall pressure: the closer the end of the way (wall / drop / hazard), the worse - long before it is "blocked".
        double pressure = 0.0D;
        if (stop != Terrain.Stop.CLEAR && free < cfg.wallComfort) {
            double t = (cfg.wallComfort - free) / cfg.wallComfort;
            pressure = cfg.wWall * t * t;
        }

        double nearestProj = Double.POSITIVE_INFINITY;
        double sum = 0.0D;
        double peak = 0.0D;
        int n = 0;
        double step = cfg.sampleStep;
        for (double d = step; d <= reach + 1e-9 || n == 0; d += step) {
            double at = Math.min(d, Math.max(reach, 0.5D));
            double t = at / cfg.playerSpeed;
            double danger = 0.0D;
            for (DodgeBandit b : in.bandits()) {
                double tt = Math.min(t, cfg.predictionSeconds);
                double bx = b.x() + speedOk(b.vx(), b.vz()) * b.vx() * tt;
                double bz = b.z() + speedOk(b.vx(), b.vz()) * b.vz() * tt;
                double dist = Math.hypot(px + dx * at - bx, pz + dz * at - bz);
                nearestProj = Math.min(nearestProj, dist);
                danger += danger(dist, cfg.minDistance, cfg.warningBand);
            }
            sum += danger;
            peak = Math.max(peak, danger);
            n++;
            if (at >= reach) {
                break;
            }
        }
        if (in.bandits().isEmpty()) {
            nearestProj = NO_BANDIT_DISTANCE;
        }
        double threatAvg = n == 0 ? 0.0D : sum / n;

        double gap = GAP_CAP;
        for (DodgeBandit b : in.bandits()) {
            double rx = b.x() - px;
            double rz = b.z() - pz;
            double along = rx * dx + rz * dz;
            if (along > 0.0D && along < cfg.lookahead + cfg.minDistance + cfg.warningBand) {
                gap = Math.min(gap, Math.abs(rx * dz - rz * dx));
            }
        }

        double dot = clamp(wantX * hx + wantZ * hz, -1.0D, 1.0D);
        double angle = Math.toDegrees(Math.acos(dot));
        // More distance than half the warning band past the minimum is not worth leaving the run for.
        double sepCap = cfg.minDistance + cfg.warningBand * 0.5D;
        double wSep = cfg.wSeparation * (breach ? 2.0D : 1.0D);
        double wFwd = cfg.wForward * (breach ? 0.3D : 1.0D);
        double wTurn = cfg.wTurn * (breach ? 0.3D : 1.0D);
        double score = cfg.wFree * reach + wSep * Math.min(nearestProj, sepCap) - cfg.wThreat * threatAvg - cfg.wPeak * peak
                + cfg.wGap * Math.min(gap, GAP_CAP) + wFwd * dot - wTurn * angle / 180.0D;
        boolean deadEnd = stop != Terrain.Stop.CLEAR && stop != Terrain.Stop.STEP;
        if (deadEnd) {
            score -= cfg.wDeadEnd * (cfg.lookahead - free);
        }
        if (dot < -0.5D) {
            score -= cfg.wReversal * (in.nowMs() < committedUntil ? 2.0D : 1.0D);
        }
        if (index >= 0 && failed.containsKey(index)) {
            score -= cfg.wFailed;
            if (blocked.isEmpty()) {
                blocked = "";
            }
        }
        score -= pressure + cfg.wExecError * exec.errorDegrees();
        if (jumpAt >= 0.0D) {
            score -= cfg.wJump * (jumpTooSoon ? 4.0D : 1.0D);
        }
        if (in.area() == SpearAreaState.INVALID && !Double.isNaN(anchorX)) {
            double[] to = Geo.unit(anchorX - px, anchorZ - pz);
            score += cfg.wArea * (wantX * to[0] + wantZ * to[1]);
        }
        boolean wallClose = stop != Terrain.Stop.CLEAR && free < cfg.wallUrgent;
        boolean safe = blocked.isEmpty() && !wallClose && nearestProj >= cfg.minDistance * cfg.projectedMarginFraction;
        return new DodgeCandidate(index, wantX, wantZ, dx, dz, exec.errorDegrees(), free, body.center(), body.left(), body.right(), stop.name(), jumpAt,
                pressure, nearestProj, threatAvg, peak, gap, score, blocked, safe);
    }

    /** The danger at the player's own place a short time ahead (the bandits predicted that far). */
    private double threatAt(DodgeInputs in, double x, double z, double seconds) {
        double sum = 0.0D;
        for (DodgeBandit b : in.bandits()) {
            double k = speedOk(b.vx(), b.vz());
            double bx = b.x() + k * b.vx() * seconds;
            double bz = b.z() + k * b.vz() * seconds;
            sum += danger(Math.hypot(x - bx, z - bz), cfg.minDistance, cfg.warningBand);
        }
        return sum;
    }

    /** 1 when the velocity is a believable walking speed, 0 when it is a glitch (a teleport shows up as a huge velocity). */
    private double speedOk(double vx, double vz) {
        return Math.hypot(vx, vz) <= cfg.maxBanditSpeed ? 1.0D : 0.0D;
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

    private static double wrap180(double d) {
        double v = d % 360.0D;
        if (v > 180.0D) {
            v -= 360.0D;
        } else if (v <= -180.0D) {
            v += 360.0D;
        }
        return v;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** For the log: previous heading, ms the current heading has been held, score of the kept heading, best other score. */
    public double previousHeadingDegrees() {
        return previousHeadingDegrees;
    }

    public long headingAgeMs(long now) {
        return headingSinceMs == 0L ? 0L : now - headingSinceMs;
    }

    public double lastCurrentScore() {
        return lastCurrentScore;
    }

    public double lastBestScore() {
        return lastBestScore;
    }

    /** The turn of the last decision in degrees, for the log. */
    public double lastHeadingChangeDegrees() {
        return lastHeadingChangeDegrees;
    }
}
