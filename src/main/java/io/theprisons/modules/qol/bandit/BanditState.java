package io.theprisons.modules.qol.bandit;

/** The one state of the bandit macro (see {@link BanditFsm} for the allowed moves). */
public enum BanditState {
    IDLE("Idle"),
    SEARCHING("Searching"),
    TARGET_ACQUIRED("Target acquired"),
    POSITIONING("Positioning"),
    AIMING("Aiming"),
    READY_TO_THROW("Ready"),
    THROWING("Throwing"),
    WAITING_FOR_RETURN("Spear in the air"),
    TARGET_RECHECK("Rechecking"),
    RETREATING("Retreating"),
    RECALL_REQUIRED("Recall due"),
    RECALLING("Recalling"),
    RECOVERING("Recovering"),
    STOPPED("Stopped"),
    FAILSAFE("Failsafe");

    private final String label;

    BanditState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Nothing is driven in these states. */
    public boolean inactive() {
        return this == IDLE || this == STOPPED || this == FAILSAFE;
    }

    /** A spear of ours may be out in the world in these states. */
    public boolean spearOut() {
        return this == WAITING_FOR_RETURN || this == RECALL_REQUIRED || this == RECALLING;
    }
}
