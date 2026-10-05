package com.freelocs.theprisons.core.movement;

/**
 * Vanilla jump arc: initial vertical velocity 0.42, then per tick {@code y += vy; vy = (vy - 0.08) * 0.98}.
 * The apex is about 1.252 blocks after 6 ticks.
 */
public final class JumpPhysics {
    public static final double JUMP_VELOCITY = 0.42D;
    private static final int MAX_TICKS = 30;

    private JumpPhysics() {
    }

    /** Height above the take-off point after {@code ticks} ticks. */
    public static double height(int ticks) {
        double y = 0.0D;
        double vy = JUMP_VELOCITY;
        for (int t = 0; t < ticks; t++) {
            y += vy;
            vy = (vy - 0.08D) * 0.98D;
        }
        return y;
    }

    /** First tick at which the feet are at least {@code rise} above take-off, or -1 if never. */
    public static int ticksToReach(double rise) {
        double y = 0.0D;
        double vy = JUMP_VELOCITY;
        for (int t = 1; t <= MAX_TICKS; t++) {
            y += vy;
            vy = (vy - 0.08D) * 0.98D;
            if (y >= rise) {
                return t;
            }
        }
        return -1;
    }

    /** Last tick at which the feet are still at least {@code rise} above take-off, or -1 if never reached. */
    public static int lastTickAbove(double rise) {
        double y = 0.0D;
        double vy = JUMP_VELOCITY;
        int last = -1;
        for (int t = 1; t <= MAX_TICKS; t++) {
            y += vy;
            vy = (vy - 0.08D) * 0.98D;
            if (y >= rise) {
                last = t;
            } else if (last >= 0) {
                break;
            }
        }
        return last;
    }

    /**
     * Horizontal distance between the front of the player box and the obstacle at which the jump key should be
     * pressed. Starts at the configured base distance (0.5 by default) and grows with speed so the feet are high
     * enough when the box reaches the obstacle, but never so early that the player comes down before it.
     */
    public static double triggerDistance(double base, double speed, double rise) {
        int rising = ticksToReach(rise + 0.02D);
        int above = lastTickAbove(rise + 0.02D);
        if (rising < 0) {
            return -1.0D;
        }
        double needed = Math.max(base, speed * rising);
        double latest = Math.max(0.1D, speed * above);
        return Math.min(needed, latest);
    }
}
