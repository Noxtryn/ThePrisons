package io.theprisons.core.control;

/**
 * Why a view direction is requested. When several requests arrive in one tick the highest priority wins, so e.g.
 * aiming at a block is never overridden by the path follower looking ahead.
 */
public enum RotationMode {
    NAVIGATION(10),
    TURNING(20),
    OBSTACLE_CHECK(30),
    RECOVERY(40),
    JUMPING(50),
    MINING(60);

    private final int priority;

    RotationMode(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    /**
     * The system-wide category of this request, for arbitration with other systems and for the telemetry. The order of the
     * modes among themselves ({@link #priority()}) is unchanged: aiming at a block still wins over the path follower looking
     * ahead, as it always did inside a macro.
     */
    public IntentPriority intent() {
        return switch (this) {
            case NAVIGATION, TURNING, OBSTACLE_CHECK, JUMPING -> IntentPriority.PATHFINDING;
            case RECOVERY -> IntentPriority.UNSTUCK;
            case MINING -> IntentPriority.TARGET_LOOK;
        };
    }
}
