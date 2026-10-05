package com.freelocs.theprisons.modules.qol.storage;

/**
 * Finds how many private vaults the player owns with as few {@code /pv <n>} probes as possible. Access is monotone
 * (vaults 1..N open, N+1.. are locked), so the search gallops upwards (1, 2, 4, 8 ...) until a page is locked and then
 * bisects between the highest open and the lowest locked page: about 2 log2(N) probes instead of N.
 */
final class VaultScan {
    /** Never probe beyond this page, whatever the server answers. */
    static final int MAX_PAGE = 256;

    private int highestOpen;
    private int lowestLocked;

    /** @param knownOpen highest page already seen open (0 when none) */
    VaultScan(int knownOpen) {
        this.highestOpen = Math.max(0, knownOpen);
        this.lowestLocked = 0;
    }

    /** The next page to probe, or 0 when the count is known ({@link #result()}). */
    int next() {
        if (lowestLocked == 0) {
            int page = highestOpen == 0 ? 1 : highestOpen * 2;
            return page > MAX_PAGE ? (highestOpen >= MAX_PAGE ? 0 : MAX_PAGE) : page;
        }
        if (lowestLocked - highestOpen <= 1) {
            return 0;
        }
        return (highestOpen + lowestLocked) >>> 1;
    }

    void record(int page, boolean open) {
        if (open) {
            highestOpen = Math.max(highestOpen, page);
            if (lowestLocked != 0 && lowestLocked <= highestOpen) {
                lowestLocked = highestOpen + 1;
            }
        } else if (lowestLocked == 0 || page < lowestLocked) {
            lowestLocked = Math.max(page, highestOpen + 1);
        }
    }

    boolean done() {
        return next() == 0;
    }

    /** Pages known to exist so far (the final vault count once {@link #done()}). */
    int result() {
        return highestOpen;
    }
}
