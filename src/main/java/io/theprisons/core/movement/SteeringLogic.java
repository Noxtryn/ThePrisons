package io.theprisons.core.movement;

import io.theprisons.core.nav.MoveType;
import io.theprisons.core.nav.NavigationPath;
import io.theprisons.core.nav.Walkability;
import io.theprisons.core.control.RotationMode;
import io.theprisons.core.control.RotationMath;

/**
 * Decides WASD, jump, sprint and the desired view for following a {@link NavigationPath}. Pure logic: the
 * Minecraft side feeds the real player state and applies the result through the normal movement keys, so
 * vanilla physics and collision handle the actual motion.
 *
 * <p>Following uses a pure-pursuit lookahead on the path polyline: the player steers towards the point
 * {@code L} blocks ahead of its projection on the path. {@code L} grows with speed on straight runs and
 * shrinks before turns, jumps and drops. The movement direction is converted to keys relative to the current
 * yaw, so looking elsewhere (e.g. at an ore while approaching it) produces strafing instead of a detour.
 */
public final class SteeringLogic {
    public static final double HALF_WIDTH = 0.3D;
    private static final int STUCK_TICKS = 30;
    private static final double TURN_ANGLE = 30.0D;

    public record Settings(double lookahead, double jumpTriggerDistance, boolean autoJump, boolean allowStrafe, boolean sprint) {
        /** What a normal player does: strafe, jump ledges in time and sprint on long straight runs. */
        public static final Settings DEFAULT = new Settings(1.6D, 0.5D, true, true, true);
    }

    public record PlayerState(double x, double y, double z, float yaw, float pitch, double vx, double vz,
                              boolean onGround, boolean horizontalCollision) {
        double speed() {
            return Math.sqrt(vx * vx + vz * vz);
        }
    }

    public enum Status { FOLLOWING, ARRIVED, OFF_PATH, STUCK, JUMP_FAILED }

    public record Output(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sprint,
                         float yaw, float pitch, RotationMode mode, Status status, int index, double crossTrack,
                         double moveYaw, double jumpX, double jumpZ, double lookX, double lookZ) {
        public String keys() {
            return (forward ? "W" : "-") + (left ? "A" : "-") + (back ? "S" : "-") + (right ? "D" : "-") + (jump ? " JUMP" : "") + (sprint ? " SPRINT" : "");
        }
    }

    private NavigationPath path;
    private double[] cumulative = new double[0];
    private int index;
    private double progress;
    private double bestProgress;
    private int ticksWithoutProgress;
    private boolean airborne;
    private int airTicks;
    private int jumpTarget = -1;
    private double takeOffY;
    /** Straight run being walked: from node {@code runStart} to node {@code runTarget} (-1 = none). */
    private int runStart = -1;
    private int runTarget = -1;

    public void setPath(NavigationPath path) {
        this.path = path;
        this.cumulative = new double[path.size()];
        for (int i = 1; i < path.size(); i++) {
            double dx = path.x(i) - path.x(i - 1);
            double dz = path.z(i) - path.z(i - 1);
            cumulative[i] = cumulative[i - 1] + Math.sqrt(dx * dx + dz * dz);
        }
        index = 0;
        progress = 0.0D;
        bestProgress = -1.0D;
        ticksWithoutProgress = 0;
        airborne = false;
        airTicks = 0;
        jumpTarget = -1;
        runStart = -1;
        runTarget = -1;
    }

    public NavigationPath path() {
        return path;
    }

    public int index() {
        return index;
    }

    public double remaining() {
        return path == null ? 0.0D : cumulative[cumulative.length - 1] - progress;
    }

    public Output tick(PlayerState p, Settings s) {
        int last = path.size() - 1;
        double feetY = p.y();

        // ── 1. Progress along the path ─────────────────────────────────────
        double cross = Double.POSITIVE_INFINITY;
        double cx = path.x(0);
        double cz = path.z(0);
        if (last == 0) {
            cross = Math.hypot(p.x() - cx, p.z() - cz);
        }
        int bestSegment = index;
        for (int i = index; i < Math.min(last, index + 5); i++) {
            double ax = path.x(i);
            double az = path.z(i);
            double bx = path.x(i + 1);
            double bz = path.z(i + 1);
            double sx = bx - ax;
            double sz = bz - az;
            double lengthSq = sx * sx + sz * sz;
            double t = lengthSq == 0.0D ? 0.0D : ((p.x() - ax) * sx + (p.z() - az) * sz) / lengthSq;
            t = Math.max(0.0D, Math.min(1.0D, t));
            double px = ax + sx * t;
            double pz = az + sz * t;
            double segmentFeet = path.feet()[i] + (path.feet()[i + 1] - path.feet()[i]) * t;
            double distance = Math.hypot(p.x() - px, p.z() - pz) + (Math.abs(feetY - segmentFeet) > 1.6D ? 2.0D : 0.0D);
            if (distance < cross - 1.0E-6D) {
                cross = distance;
                bestSegment = i;
                cx = px;
                cz = pz;
                progress = cumulative[i] + Math.sqrt(lengthSq) * t;
            }
        }
        index = bestSegment;

        // ── 2. Arrival ─────────────────────────────────────────────────────
        double endDx = path.x(last) - p.x();
        double endDz = path.z(last) - p.z();
        double endDistance = Math.hypot(endDx, endDz);
        if (endDistance < 0.35D && Math.abs(feetY - path.feet()[last]) < 0.6D && p.onGround()) {
            return idle(p, Status.ARRIVED);
        }

        // ── 3. Jump monitoring ─────────────────────────────────────────────
        Status status = Status.FOLLOWING;
        if (!p.onGround()) {
            airborne = true;
            airTicks++;
        } else if (airborne) {
            airborne = false;
            if (jumpTarget >= 0 && airTicks >= 2) {
                boolean landedHigh = feetY > takeOffY + 0.5D;
                if (!landedHigh || Math.abs(feetY - path.feet()[jumpTarget]) > 0.6D) {
                    status = Status.JUMP_FAILED;
                }
            }
            jumpTarget = -1;
            airTicks = 0;
        }

        // ── 4. Lookahead distance, shortened before features ───────────────
        double speed = p.speed();
        double lookahead = Math.max(0.6D, Math.min(3.5D, s.lookahead() + speed * 3.0D));
        int featureIndex = -1;
        boolean turnAhead = false;
        int turnAfter = -1;
        int[] straight = path.straight();
        for (int j = index + 1; j <= last; j++) {
            double distance = cumulative[j] - progress;
            if (distance > lookahead + 2.5D) {
                break;
            }
            boolean jumpOrDrop = path.moves()[j] == MoveType.JUMP || path.moves()[j] == MoveType.DROP;
            // With straight runs, grid zig-zags are no turns; real turns are detected at the end of a run below.
            boolean turn = straight == null && j < last && turnAngle(j) > TURN_ANGLE;
            if (jumpOrDrop || turn) {
                featureIndex = j;
                turnAhead = turn;
                lookahead = Math.max(0.5D, Math.min(lookahead, distance));
                break;
            }
        }
        double[] target;
        double[] far;
        int runEnd = -1;
        if (straight != null) {
            // Commit to a run's end until it is (almost) reached, so the direction does not twitch with every grid step.
            if (runTarget <= index || runTarget > last || Math.hypot(path.x(runTarget) - p.x(), path.z(runTarget) - p.z()) < 1.5D) {
                runStart = index;
                runTarget = straight[Math.min(index, last)];
            }
            runEnd = runTarget;
        }
        if (runEnd > index && featureIndex < 0) {
            // Straight ahead: head for the farthest node reachable in a straight line, not the next grid step.
            target = new double[]{path.x(runEnd), path.z(runEnd), path.feet()[runEnd]};
            far = target;
            int nextEnd = straight[runEnd];
            if (runEnd < last && nextEnd > runEnd && cumulative[runEnd] - progress < 2.5D) {
                float here = RotationMath.yawOf(path.x(runEnd) - p.x(), path.z(runEnd) - p.z());
                float then = RotationMath.yawOf(path.x(nextEnd) - path.x(runEnd), path.z(nextEnd) - path.z(runEnd));
                if (Math.abs(RotationMath.wrap(then - here)) > TURN_ANGLE) {
                    turnAhead = true;
                    featureIndex = runEnd;
                    turnAfter = nextEnd;
                }
            }
        } else {
            target = pointAt(progress + lookahead);
            far = pointAt(progress + lookahead + 1.0D);
        }

        // Steering by the view (no strafe) rounds corners wider: allow more distance before heading back to the line.
        boolean offPath = cross > (s.allowStrafe() ? 1.2D : 2.2D);
        if (offPath && runEnd > index && runStart >= 0) {
            // On a straight shortcut the grid staircase is not the reference: the straight segment is.
            offPath = segmentDistance(p.x(), p.z(), path.x(runStart), path.z(runStart), path.x(runEnd), path.z(runEnd)) > 0.8D;
        }
        if (offPath) {
            // Head back to the nearest path point first.
            target = new double[]{cx + (target[0] - cx) * 0.3D, cz + (target[1] - cz) * 0.3D, target[2]};
            status = status == Status.FOLLOWING ? Status.OFF_PATH : status;
        }
        float moveYaw = RotationMath.yawOf(target[0] - p.x(), target[1] - p.z());

        // ── 5. Where to look ───────────────────────────────────────────────
        RotationMode mode = RotationMode.NAVIGATION;
        float headYaw = RotationMath.yawOf(far[0] - p.x(), far[1] - p.z());
        double lookX = far[0];
        double lookZ = far[1];
        double lookY = far[2] + 1.35D;
        if (turnAhead && featureIndex >= 0 && cumulative[featureIndex] - progress < 2.5D) {
            // Anticipate the curve: turn the head into the next segment before the feet get there.
            mode = RotationMode.TURNING;
            int after = turnAfter >= 0 ? turnAfter : Math.min(last, featureIndex + 1);
            headYaw = RotationMath.yawOf(path.x(after) - p.x(), path.z(after) - p.z());
            lookX = path.x(after);
            lookZ = path.z(after);
            lookY = path.feet()[after] + 1.35D;
        }
        if (offPath) {
            mode = RotationMode.RECOVERY;
            headYaw = moveYaw;
        }

        // ── 6. Jump decision ───────────────────────────────────────────────
        boolean jump = false;
        double jumpX = Double.NaN;
        double jumpZ = Double.NaN;
        int jumpIndex = nextJump();
        if (jumpIndex > 0) {
            int from = jumpIndex - 1;
            int dx = (int) Math.signum(path.x(jumpIndex) - path.x(from));
            int dz = (int) Math.signum(path.z(jumpIndex) - path.z(from));
            jumpX = path.x(from) + dx * 0.5D;
            jumpZ = path.z(from) + dz * 0.5D;
            double along = (jumpX - p.x()) * dx + (jumpZ - p.z()) * dz - HALF_WIDTH;
            double lateral = Math.abs((p.x() - path.x(from)) * dz - (p.z() - path.z(from)) * dx);
            double rise = path.feet()[jumpIndex] - feetY;
            if (along < 1.5D && mode == RotationMode.NAVIGATION) {
                mode = RotationMode.OBSTACLE_CHECK;
                lookX = jumpX + dx * 0.5D;
                lookZ = jumpZ + dz * 0.5D;
                lookY = path.feet()[jumpIndex];
                headYaw = RotationMath.yawOf(dx, dz);
            }
            if (s.autoJump() && p.onGround() && !airborne && rise > Walkability.STEP_HEIGHT && lateral < 0.45D) {
                double speedAlong = Math.max(0.0D, p.vx() * dx + p.vz() * dz);
                double trigger = JumpPhysics.triggerDistance(s.jumpTriggerDistance(), speedAlong, rise);
                boolean facing = Math.abs(RotationMath.wrap(moveYaw - RotationMath.yawOf(dx, dz))) < 50.0F;
                if (trigger > 0 && facing && (along <= trigger || (p.horizontalCollision() && along < 0.15D))) {
                    jump = true;
                    jumpTarget = jumpIndex;
                    takeOffY = feetY;
                }
            }
        }
        if (airborne && jumpTarget >= 0) {
            mode = RotationMode.JUMPING;
            headYaw = moveYaw;
        }

        // ── 7. Keys relative to the current yaw ────────────────────────────
        float rel = RotationMath.wrap(moveYaw - p.yaw());
        boolean forward;
        boolean back;
        boolean left;
        boolean right;
        boolean straightApproach = jumpIndex > 0 && featureIndex == jumpIndex;
        if (s.allowStrafe()) {
            forward = Math.abs(rel) < 67.5F;
            back = Math.abs(rel) > 112.5F;
            left = rel < -22.5F && rel > -157.5F;
            right = rel > 22.5F && rel < 157.5F;
            if (straightApproach && Math.abs(rel) < 35.0F) {
                // Line up for the jump instead of drifting diagonally into the obstacle edge.
                left = false;
                right = false;
            }
        } else {
            // Steering by the view only: keep walking through moderate turns instead of stopping to turn.
            forward = Math.abs(rel) < 60.0F;
            back = false;
            left = false;
            right = false;
        }
        // Brake before the end so we do not overshoot the mining spot.
        if (remaining() < speed * 2.5D && endDistance < 0.9D && !airborne) {
            forward &= Math.abs(rel) < 20.0F && speed < 0.12D;
        }
        boolean sprint = s.sprint() && forward && !left && !right && Math.abs(rel) < 12.0F && featureIndex < 0 && remaining() > 4.0D;

        // ── 8. Stuck detection ─────────────────────────────────────────────
        if (progress > bestProgress + 0.1D) {
            bestProgress = progress;
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > STUCK_TICKS && status == Status.FOLLOWING) {
            status = Status.STUCK;
        }
        if (ticksWithoutProgress > STUCK_TICKS && offPath) {
            status = Status.STUCK;
        }

        double dxLook = lookX - p.x();
        double dzLook = lookZ - p.z();
        float pitch = RotationMath.pitchOf(dxLook, lookY - (feetY + Walkability.EYE_HEIGHT), dzLook);
        pitch = Math.max(-35.0F, Math.min(mode == RotationMode.OBSTACLE_CHECK ? 45.0F : 30.0F, pitch));
        return new Output(forward, back, left, right, jump, sprint, headYaw, pitch, mode, status, index, cross, moveYaw,
                jumpX, jumpZ, lookX, lookZ);
    }

    /** Resets the no-progress timer after a recovery manoeuvre. */
    public void resetStuck() {
        bestProgress = progress;
        ticksWithoutProgress = 0;
    }

    private Output idle(PlayerState p, Status status) {
        return new Output(false, false, false, false, false, false, p.yaw(), p.pitch(), RotationMode.NAVIGATION, status,
                index, 0.0D, p.yaw(), Double.NaN, Double.NaN, p.x(), p.z());
    }

    private int nextJump() {
        for (int j = index + 1; j <= Math.min(path.size() - 1, index + 2); j++) {
            if (path.moves()[j] == MoveType.JUMP) {
                return j;
            }
        }
        return -1;
    }

    private static double segmentDistance(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq == 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D, ((px - ax) * dx + (pz - az) * dz) / lengthSq));
        return Math.hypot(px - (ax + dx * t), pz - (az + dz * t));
    }

    /** Heading change at waypoint j in degrees. */
    private double turnAngle(int j) {
        double ax = path.x(j) - path.x(j - 1);
        double az = path.z(j) - path.z(j - 1);
        double bx = path.x(j + 1) - path.x(j);
        double bz = path.z(j + 1) - path.z(j);
        if ((ax == 0 && az == 0) || (bx == 0 && bz == 0)) {
            return 0.0D;
        }
        return Math.abs(RotationMath.wrap(RotationMath.yawOf(bx, bz) - RotationMath.yawOf(ax, az)));
    }

    /** {x, z, feet} at the given arc length, clamped to the path. */
    private double[] pointAt(double arc) {
        int last = path.size() - 1;
        if (arc >= cumulative[last]) {
            return new double[]{path.x(last), path.z(last), path.feet()[last]};
        }
        for (int i = Math.max(1, index); i <= last; i++) {
            if (cumulative[i] >= arc) {
                double length = cumulative[i] - cumulative[i - 1];
                double t = length == 0.0D ? 1.0D : (arc - cumulative[i - 1]) / length;
                return new double[]{
                        path.x(i - 1) + (path.x(i) - path.x(i - 1)) * t,
                        path.z(i - 1) + (path.z(i) - path.z(i - 1)) * t,
                        path.feet()[i - 1] + (path.feet()[i] - path.feet()[i - 1]) * t
                };
            }
        }
        return new double[]{path.x(last), path.z(last), path.feet()[last]};
    }
}
