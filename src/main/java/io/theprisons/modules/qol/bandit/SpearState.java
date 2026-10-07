package io.theprisons.modules.qol.bandit;

/** Where the spear is (pure logic). */
public enum SpearState {
    /** Not in the hotbar and not in the air. */
    MISSING,
    /** In the hotbar but not in the hand. */
    AVAILABLE,
    /** In the hand: can be thrown. */
    READY,
    /** Flying out. */
    THROWN,
    /** Recalled, on its way back. */
    RETURNING;

    public static SpearState classify(boolean inHand, boolean inHotbar, boolean projectileInAir, boolean recallSent) {
        if (projectileInAir) {
            return recallSent ? RETURNING : THROWN;
        }
        if (inHand) {
            return READY;
        }
        return inHotbar ? AVAILABLE : MISSING;
    }
}
