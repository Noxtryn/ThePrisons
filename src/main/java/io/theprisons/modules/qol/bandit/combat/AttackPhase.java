package io.theprisons.modules.qol.bandit.combat;

/** Where the spear attack of the module is (the combat controller only needs to know whether it may move). */
public enum AttackPhase {
    /** No attack running. */
    NONE,
    /** Turning onto the throw solution: the macro may keep orbiting. */
    AIMING,
    /** The throw is being committed (aim locked, key held): the macro stands still for a moment. */
    COMMIT,
    /** The spear is in the air or coming back: the macro may move again. */
    IN_FLIGHT
}
