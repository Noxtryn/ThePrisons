package io.theprisons.modules.qol.bandit.combat;

/**
 * Too far / combat range / too close with a dead band, so a bandit standing on a limit does not flip the state every tick. The
 * limits come from the macro's settings (or from verified game knowledge once there is some); the band itself invents nothing.
 */
public final class DistanceBand {
    public enum State { TOO_FAR, COMBAT, TOO_CLOSE, UNKNOWN }

    private final double minSafe;
    private final double maxCombat;
    private final double hysteresis;
    private State state = State.UNKNOWN;

    public DistanceBand(double minSafe, double maxCombat, double hysteresis) {
        this.minSafe = minSafe;
        this.maxCombat = maxCombat;
        this.hysteresis = hysteresis;
    }

    public State state() {
        return state;
    }

    public void reset() {
        state = State.UNKNOWN;
    }

    /** {@code NaN} (unknown distance) gives UNKNOWN and keeps nothing. */
    public State update(double distance) {
        if (Double.isNaN(distance)) {
            state = State.UNKNOWN;
            return state;
        }
        state = switch (state) {
            case TOO_FAR -> distance < maxCombat - hysteresis ? classify(distance, maxCombat - hysteresis, minSafe) : State.TOO_FAR;
            case TOO_CLOSE -> distance > minSafe + hysteresis ? classify(distance, maxCombat, minSafe + hysteresis) : State.TOO_CLOSE;
            case COMBAT, UNKNOWN -> classify(distance, maxCombat, minSafe);
        };
        return state;
    }

    private static State classify(double d, double far, double close) {
        return d > far ? State.TOO_FAR : d < close ? State.TOO_CLOSE : State.COMBAT;
    }

    /** The distance to keep when orbiting: {@code preferred} when given (> 0), else 40 % into the band. */
    public double ring(double preferred) {
        if (preferred > 0.0D) {
            return Math.max(minSafe + 1.0D, Math.min(maxCombat - 1.0D, preferred));
        }
        return minSafe + 0.4D * (maxCombat - minSafe);
    }
}
