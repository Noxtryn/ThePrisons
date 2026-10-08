package io.theprisons.modules.qol.bandit.combat;

/** The states of the bandit combat controller. See {@link CombatBrain} for what each one does and when it ends. */
public enum CombatState {
    /** The macro does nothing: no keys, no view. */
    IDLE("Idle"),
    /** Looking for bandits (nothing chosen). */
    SCAN("Scanning"),
    /** Choosing a target (one tick). */
    TARGET_SELECT("Choosing a target"),
    /** Walking to a combat position near the target. */
    APPROACH("Approaching"),
    /** The attack is being committed (aim locked, throw): the macro stands still for it. */
    ENGAGE("Engaging"),
    /** Moving sideways around the target at combat distance. */
    ORBIT("Orbiting"),
    /** Short-term danger reduction: away from what is too close. */
    EVADE("Evading"),
    /** Finding a better combat position near the same target. */
    REPOSITION("Repositioning"),
    /** A local movement problem: a few bounded tries to get free. */
    RECOVER("Recovering"),
    /** Breaking off the fight and getting distance. */
    RETREAT("Retreating"),
    /** The target is gone or unusable: the lock is released. */
    LOST_TARGET("Target lost"),
    /** A short pause after retreat / lost target / loop. */
    COOLDOWN("Cooldown"),
    /** The macro ended (terminal). */
    STOPPED("Stopped");

    private final String label;

    CombatState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** States in which nothing is driven. */
    public boolean inactive() {
        return this == IDLE || this == STOPPED;
    }

    /** States in which the macro moves around the target (an attack may be made). */
    public boolean fighting() {
        return this == ORBIT || this == ENGAGE;
    }
}
