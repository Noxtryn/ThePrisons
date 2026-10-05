package io.theprisons.core.control;

/**
 * Continuous-time view motion modelled on how people move a mouse (pure logic, no Minecraft classes).
 *
 * <ul>
 *     <li><b>Shape:</b> every movement is a minimum-jerk trajectory (Flash &amp; Hogan 1985), the smooth bell-shaped
 *     velocity profile of human aiming movements. It is planned as a quintic from the <em>current</em> angle, angular
 *     velocity and acceleration to the target at rest, so a retarget mid-turn continues the running motion with
 *     continuous velocity and acceleration instead of restarting it.</li>
 *     <li><b>Duration:</b> Fitts' law, {@code T = a + b·log2(1 + D/W)}: long turns and small targets take longer,
 *     short corrections are quick. {@code W} is the angular size of what is aimed at.</li>
 *     <li><b>Moving targets:</b> a target that only drifts (e.g. a block while walking past it) keeps the running
 *     schedule; the plan is re-solved from the current state, which behaves like smooth pursuit.</li>
 * </ul>
 * The motion is sampled with the real time of every rendered frame, so it is as smooth as the frame rate and
 * independent of the 20 Hz game tick. It is deterministic: no noise, overshoot or input quantisation is added.
 *
 * <p>Angles are in degrees; yaw is continuous (not wrapped), positive pitch looks down.
 */
public final class HumanRotation {
    /**
     * Tuning.
     *
     * @param intercept      Fitts' {@code a} in seconds (reaction-free movement onset)
     * @param slope          Fitts' {@code b} in seconds per bit
     * @param maxSpeed       peak angular speed cap in °/s
     * @param minDuration    shortest movement in seconds
     * @param maxDuration    longest movement in seconds
     */
    public record Profile(double intercept, double slope, double maxSpeed, double minDuration, double maxDuration) {
        /** A calm but quick player: a 90° turn onto a block ≈ 0.3 s, a 10° correction ≈ 0.12 s. */
        public static final Profile DEFAULT = new Profile(0.050D, 0.085D, 900.0D, 0.060D, 0.600D);

        /** {@code factor} &gt; 1 is faster (shorter movements, higher speed cap). */
        public Profile scaled(double factor) {
            double f = Math.max(0.1D, factor);
            return new Profile(intercept / f, slope / f, maxSpeed * f, minDuration / f, maxDuration / f);
        }

        /** Movement time for an angular distance {@code distance} onto a target of angular width {@code width}. */
        public double duration(double distance, double width) {
            double bits = Math.log(1.0D + distance / Math.max(0.5D, width)) / Math.log(2.0D);
            double fitts = intercept + slope * bits;
            // Rest-to-rest minimum jerk peaks at 1.875·D/T.
            double speedLimited = 1.875D * distance / maxSpeed;
            return Math.max(minDuration, Math.min(maxDuration, Math.max(fitts, speedLimited)));
        }
    }

    /** Shortest movement when the view is already moving towards the target (avoids a hard stop). */
    static final double MIN_PURSUIT = 0.03D;
    private static final double OVERSHOOT_RATIO = 2.4D;

    /** A new target further than this from the previous one starts a new movement; closer ones only drift. */
    static final double NEW_MOVEMENT_DEGREES = 3.0D;
    private static final double SETTLED = 0.02D;

    private final Axis yaw = new Axis();
    private final Axis pitch = new Axis();
    private double start;
    private double duration;
    private double targetYaw;
    private double targetPitch;
    private boolean moving;

    /** Places the view at rest (e.g. after the player or the server moved it). */
    public void reset(float currentYaw, float currentPitch) {
        yaw.rest(currentYaw);
        pitch.rest(currentPitch);
        targetYaw = currentYaw;
        targetPitch = currentPitch;
        moving = false;
    }

    public boolean moving() {
        return moving;
    }

    public double targetYaw() {
        return targetYaw;
    }

    public double targetPitch() {
        return targetPitch;
    }

    /** End of the running movement in seconds (≤ now when settled). */
    public double endTime() {
        return start + duration;
    }

    /**
     * Aims at a new direction.
     *
     * @param now        time in seconds
     * @param wantYaw    target yaw (any wrap; the short way is taken)
     * @param wantPitch  target pitch, clamped to ±90
     * @param width      angular size of the target in degrees (Fitts' W)
     */
    public void retarget(double now, float wantYaw, float wantPitch, double width, Profile profile) {
        double y0 = yaw.position(now, start);
        double p0 = pitch.position(now, start);
        double newYaw = y0 + RotationMath.wrap((float) (wantYaw - y0));
        double newPitch = Math.max(-90.0D, Math.min(90.0D, wantPitch));
        double shift = Math.hypot(RotationMath.wrap((float) (newYaw - targetYaw)), newPitch - targetPitch);
        double distance = Math.hypot(newYaw - y0, newPitch - p0);
        if (moving && shift < 1.0E-4D) {
            return;
        }
        double velocity = Math.hypot(yaw.velocity(now, start), pitch.velocity(now, start));
        if (distance < SETTLED && velocity < 1.0D) {
            yaw.rest(newYaw);
            pitch.rest(newPitch);
            targetYaw = newYaw;
            targetPitch = newPitch;
            moving = false;
            return;
        }
        double remainingTime = moving ? endTime() - now : 0.0D;
        double fitts = profile.duration(distance, width);
        // A drifting target keeps the running schedule (pursuit); a new target is a new aimed movement.
        double time = moving && shift < NEW_MOVEMENT_DEGREES ? Math.max(remainingTime, profile.minDuration()) : fitts;
        time = Math.max(time, profile.minDuration());
        // No overshoot: a minimum-jerk move that starts with speed v towards the target overshoots (and comes back -
        // a visible wobble) when it lasts longer than ~2.6·distance/v. Keep it below that on both axes.
        time = Math.min(time, Math.max(MIN_PURSUIT, yaw.noOvershootTime(now, start, newYaw)));
        time = Math.min(time, Math.max(MIN_PURSUIT, pitch.noOvershootTime(now, start, newPitch)));
        yaw.plan(now, start, newYaw, time);
        pitch.plan(now, start, newPitch, time);
        start = now;
        duration = time;
        targetYaw = newYaw;
        targetPitch = newPitch;
        moving = true;
    }

    /** View at time {@code now}: {yaw, pitch}. Settles exactly on the target when the movement ends. */
    public float[] sample(double now) {
        if (moving && now >= endTime()) {
            yaw.rest(targetYaw);
            pitch.rest(targetPitch);
            moving = false;
        }
        if (!moving) {
            return new float[]{(float) targetYaw, (float) targetPitch};
        }
        return new float[]{(float) yaw.position(now, start), (float) Math.max(-90.0D, Math.min(90.0D, pitch.position(now, start)))};
    }

    /** Angular speed in °/s at {@code now}. */
    public double speed(double now) {
        return moving ? Math.hypot(yaw.velocity(now, start), pitch.velocity(now, start)) : 0.0D;
    }

    /** One axis: p(t) = c0 + c1·t + … + c5·t⁵ with t = now − start. */
    private static final class Axis {
        private double c0;
        private double c1;
        private double c2;
        private double c3;
        private double c4;
        private double c5;
        private double length;

        void rest(double value) {
            c0 = value;
            c1 = c2 = c3 = c4 = c5 = 0.0D;
            length = 0.0D;
        }

        private double t(double now, double start) {
            return Math.max(0.0D, Math.min(length, now - start));
        }

        double position(double now, double start) {
            double t = t(now, start);
            return c0 + t * (c1 + t * (c2 + t * (c3 + t * (c4 + t * c5))));
        }

        double velocity(double now, double start) {
            double t = t(now, start);
            return c1 + t * (2 * c2 + t * (3 * c3 + t * (4 * c4 + t * 5 * c5)));
        }

        double acceleration(double now, double start) {
            double t = t(now, start);
            return 2 * c2 + t * (6 * c3 + t * (12 * c4 + t * 20 * c5));
        }

        /** Longest duration that reaches {@code target} without overshooting, given the current speed. */
        double noOvershootTime(double now, double start, double target) {
            double d = target - position(now, start);
            double v = velocity(now, start);
            if (Math.abs(d) < 1.0E-6D || Math.abs(v) < 1.0E-6D || Math.signum(d) != Math.signum(v)) {
                return Double.POSITIVE_INFINITY;
            }
            return OVERSHOOT_RATIO * Math.abs(d) / Math.abs(v);
        }

        /** Minimum-jerk quintic from the current state to {@code target} at rest after {@code time} seconds. */
        void plan(double now, double start, double target, double time) {
            double p0 = position(now, start);
            double v0 = velocity(now, start);
            double a0 = acceleration(now, start);
            double d = target - p0;
            double t2 = time * time;
            double t3 = t2 * time;
            c0 = p0;
            c1 = v0;
            c2 = a0 / 2.0D;
            c3 = (20.0D * d - 12.0D * v0 * time - 3.0D * a0 * t2) / (2.0D * t3);
            c4 = (-30.0D * d + 16.0D * v0 * time + 3.0D * a0 * t2) / (2.0D * t3 * time);
            c5 = (12.0D * d - 6.0D * v0 * time - a0 * t2) / (2.0D * t3 * t2);
            length = time;
        }
    }
}
