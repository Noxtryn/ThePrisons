package com.freelocs.theprisons.core.control;

/**
 * Continuous view following for a target that moves all the time (steering along a path, pitch following the
 * ground): each axis is a critically damped spring, integrated in closed form with the real time of every rendered
 * frame. Soft start, soft stop, never overshoots, and exactly the same motion at 30 or 300 fps. Pure logic.
 *
 * <p>Unlike {@link HumanRotation} (one aimed movement per target, for looking at a block) there is no "movement" to
 * re-plan when the target changes: the spring simply keeps pulling towards the current target.
 */
public final class FollowMotion {
    private double yaw;
    private double pitch;
    private double yawSpeed;
    private double pitchSpeed;

    public void reset(float currentYaw, float currentPitch) {
        yaw = currentYaw;
        pitch = currentPitch;
        yawSpeed = 0.0D;
        pitchSpeed = 0.0D;
    }

    public float yaw() {
        return (float) yaw;
    }

    public float pitch() {
        return (float) pitch;
    }

    /** Angular speed in °/s. */
    public double speed() {
        return Math.hypot(yawSpeed, pitchSpeed);
    }

    /**
     * Advances by {@code seconds}.
     *
     * @param yawOmega   stiffness of the yaw spring in 1/s (settles in about 4.7 / omega seconds)
     * @param pitchOmega stiffness of the pitch spring in 1/s
     */
    public void step(float targetYaw, float targetPitch, double seconds, double yawOmega, double pitchOmega) {
        if (seconds <= 0.0D) {
            return;
        }
        double dt = Math.min(seconds, 0.25D);
        // Yaw: the target is taken the short way round, the view stays continuous (not wrapped).
        double yawError = -RotationMath.wrap((float) (targetYaw - yaw));
        double[] y = spring(yawError, yawSpeed, yawOmega, dt);
        yaw += y[0] - yawError;
        yawSpeed = y[1];
        double pitchError = pitch - Math.max(-90.0D, Math.min(90.0D, targetPitch));
        double[] p = spring(pitchError, pitchSpeed, pitchOmega, dt);
        pitch = Math.max(-90.0D, Math.min(90.0D, pitch + p[0] - pitchError));
        pitchSpeed = p[1];
    }

    /** Exact critically damped response of an error {@code e} with speed {@code v} after {@code dt}: {e, v}. */
    static double[] spring(double e, double v, double omega, double dt) {
        double decay = Math.exp(-omega * dt);
        double c = v + omega * e;
        return new double[]{(e + c * dt) * decay, (v - omega * c * dt) * decay};
    }
}
