package com.freelocs.theprisons.modules.qol.bandit;

/**
 * When to recall the thrown spear (F). The way back hits everything between the spear and the player, and the player
 * keeps moving and looking, so the number of bandits on that line changes all the time. Fire when
 * <ul>
 *   <li>the line holds every bandit that could be on it (cannot get better), or</li>
 *   <li>it holds enough and is about to get worse (the peak), or</li>
 *   <li>the deadline is reached (the spear must come back).</li>
 * </ul>
 * Pure logic.
 */
public final class RecallPlanner {
    private final long deadlineMs;
    private final int minEarly;
    private final long minFlightMs;
    private long startMs = -1L;
    private boolean fired;
    private int best;

    public RecallPlanner(long deadlineMs, int minEarly, long minFlightMs) {
        this.deadlineMs = deadlineMs;
        this.minEarly = minEarly;
        this.minFlightMs = minFlightMs;
    }

    public void start(long nowMs) {
        startMs = nowMs;
        fired = false;
        best = 0;
    }

    public void reset() {
        startMs = -1L;
        fired = false;
        best = 0;
    }

    public boolean running() {
        return startMs >= 0L && !fired;
    }

    public long elapsed(long nowMs) {
        return startMs < 0L ? 0L : nowMs - startMs;
    }

    public int best() {
        return best;
    }

    /**
     * @param current    bandits on the way back now
     * @param predicted  the same a moment ahead (the press takes a moment to arrive)
     * @param candidates every bandit that could be on the line
     * @return true = press F now (once per throw)
     */
    public boolean update(long nowMs, int current, int predicted, int candidates) {
        if (!running()) {
            return false;
        }
        long elapsed = nowMs - startMs;
        if (elapsed < minFlightMs) {
            return false;
        }
        best = Math.max(best, current);
        boolean fire = elapsed >= deadlineMs
                || (current >= minEarly && (current >= candidates || (current >= best && predicted < current)));
        if (fire) {
            fired = true;
        }
        return fire;
    }
}
