package io.theprisons.core.control;

/**
 * What a system wants the view to do this tick: turn to a direction ({@link #aimed}) or keep following a moving direction
 * ({@link #following}). {@link ControlService#submit(RotationIntent)} keeps the highest {@link IntentPriority}; among equal ones the
 * higher {@link RotationMode} priority, then the later request. Only that single intent reaches the view.
 */
public record RotationIntent(IntentPriority priority, String source, RotationMode mode, boolean follow, float yaw, float pitch, float width,
                             float yawOmega, float pitchOmega) {
    /** A movement of the view onto a target {@code width} degrees wide (Fitts' law). */
    public static RotationIntent aimed(IntentPriority priority, String source, RotationMode mode, float yaw, float pitch, float width) {
        return new RotationIntent(priority, source, mode, false, yaw, pitch, width, 0.0F, 0.0F);
    }

    /** Spring-damped continuous following of a direction that moves (orbiting a bandit, walking a path). */
    public static RotationIntent following(IntentPriority priority, String source, float yaw, float pitch, float yawOmega, float pitchOmega) {
        return new RotationIntent(priority, source, RotationMode.NAVIGATION, true, yaw, pitch, RotationController.DEFAULT_WIDTH, yawOmega,
                pitchOmega);
    }
}
