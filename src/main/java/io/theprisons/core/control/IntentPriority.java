package io.theprisons.core.control;

/**
 * Who may drive the player when several systems want something in the same tick. Higher wins. Modules only <em>request</em>;
 * {@link InputController} (keys) and {@link RotationController} (view) apply the single winner at the end of the tick, and the
 * winner's source is kept for the telemetry.
 *
 * <pre>
 *  MANUAL        the human's own keys (the safety monitor stops a macro on them; nothing automatic outranks the human)
 *  EMERGENCY     safety stops, relocation handling, spin-loop stabilisation
 *  UNSTUCK       getting out of a wall / hole
 *  COMBAT_EVADE  running to a guard, dodging
 *  PATHFINDING   following a path / steering through a tunnel
 *  TARGET_LOOK   aiming at an ore or a block
 *  IDLE          nothing wanted
 * </pre>
 */
public enum IntentPriority {
    IDLE(0), TARGET_LOOK(10), PATHFINDING(20), COMBAT_EVADE(30), UNSTUCK(40), EMERGENCY(50), MANUAL(60);

    private final int rank;

    IntentPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
