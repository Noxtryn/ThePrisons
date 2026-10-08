package io.theprisons.modules.qol.bandit.dodge;

/** What the movement is doing, for the HUD, the log and the tests. */
public enum DodgeAction {
    /** Keep going the same way. */
    CONTINUE,
    /** Straight on through open ground. */
    RUN,
    /** A mild change of direction (under about 50 degrees). */
    DIAGONAL,
    /** A clear turn to the left / right (about 50 to 120 degrees). */
    STRAFE_LEFT,
    STRAFE_RIGHT,
    /** Jump over a step on the way (forward / to a side). */
    JUMP_FORWARD,
    JUMP_LEFT,
    JUMP_RIGHT,
    /** A sharp turn away from danger (over 120 degrees), or a minimum-distance breach. */
    HARD_EVADE,
    /** Stuck or cornered: another direction at once. */
    RECOVER
}
