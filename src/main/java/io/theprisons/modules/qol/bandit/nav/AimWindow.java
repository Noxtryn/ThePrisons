package io.theprisons.modules.qol.bandit.nav;

/**
 * Counts how many ticks in a row the movement has been safe enough to aim. Any breach of the minimum distance closes it on the spot and
 * resets the counter - there is no "finish the aim first". The future spear aim reads {@link #open()} and must stop the moment it is false.
 */
public final class AimWindow {
    private final int required;
    private int ticks;

    public AimWindow(int requiredStableTicks) {
        this.required = requiredStableTicks;
    }

    /** One tick. A breach or an unsafe tick resets the counter. */
    public void update(boolean safeNow, boolean breach) {
        ticks = safeNow && !breach ? ticks + 1 : 0;
    }

    public boolean open() {
        return ticks >= required;
    }

    public int ticks() {
        return ticks;
    }

    public void reset() {
        ticks = 0;
    }
}
