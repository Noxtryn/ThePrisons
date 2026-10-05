package io.theprisons.modules.mining.ore;

/**
 * Watchdog for the way back into the guarded zone (pure logic): outside it, the macro must get closer to its goal -
 * otherwise it steps up, one stage after the other. Game 2026-10-05: without it the macro stood outside for 52
 * minutes, replanning the same unreachable goal every tick, until a player killed it.
 *
 * <ol>
 *     <li>{@link Step#NEW_WAY}: {@value #NO_PROGRESS_MS} ms without getting a block closer → that goal is dropped, a
 *     new way (again every {@value #NO_PROGRESS_MS} ms).</li>
 *     <li>{@link Step#WIDE}: {@value #WIDE_MS} ms outside → the search reaches farther.</li>
 *     <li>{@link Step#ESCAPE}: {@value #ESCAPE_MS} ms outside - only {@value #ESCAPE_LOCKED_MS} ms with a player near
 *     (the user's rule: never outside with a player within 32 blocks) - and not getting closer for
 *     {@value #STALLED_MS} ms (a way in that is being walked is not given up) → /spawn and /warp back.</li>
 *     <li>{@link Step#GIVE_UP}: {@value #GIVE_UP_MS} ms outside (the escape failed too, e.g. in combat) → stop.</li>
 * </ol>
 */
public final class OutsideWatch {
    public enum Step { NONE, NEW_WAY, WIDE, ESCAPE, GIVE_UP }

    public static final long NO_PROGRESS_MS = 3_000L;
    public static final long WIDE_MS = 10_000L;
    public static final long ESCAPE_MS = 20_000L;
    public static final long ESCAPE_LOCKED_MS = 5_000L;
    public static final long GIVE_UP_MS = 45_000L;
    /** The escape waits while the way in still brings the player closer within this long. */
    public static final long STALLED_MS = 2_000L;
    /** Getting this much closer (blocks, sideways + up / down) counts as progress. */
    static final double PROGRESS = 1.0D;

    private boolean active;
    private long since;
    private long progressAt;
    /** The last real progress (not reset by a new way). */
    private long movedAt;
    private double best = Double.POSITIVE_INFINITY;
    private boolean wide;
    private boolean escaped;

    /** Walked out of the zone now. */
    public void start(long now) {
        active = true;
        since = now;
        progressAt = now;
        movedAt = now;
        best = Double.POSITIVE_INFINITY;
        wide = false;
        escaped = false;
    }

    /** Back in (or stopped): nothing to watch. */
    public void reset() {
        active = false;
        since = 0L;
        best = Double.POSITIVE_INFINITY;
        wide = false;
        escaped = false;
    }

    /** Since when the macro is outside (see {@link #active()}). */
    public long since() {
        return since;
    }

    /** Outside and watched (between {@link #start} and {@link #reset}). */
    public boolean active() {
        return active;
    }

    /** A new goal: its distance is measured from scratch (the time without progress starts again). */
    public void target(long now) {
        best = Double.POSITIVE_INFINITY;
        progressAt = now;
    }

    /**
     * @param toGoal distance to the current goal ({@code +∞} = no goal / no way yet)
     * @param playerNear another player within the safety range
     * @return what to do now ({@link Step#NONE} most ticks; every other step once)
     */
    public Step update(long now, double toGoal, boolean playerNear) {
        if (!active) {
            start(now);
        }
        long outside = now - since;
        if (outside >= GIVE_UP_MS) {
            since = now;
            return Step.GIVE_UP;
        }
        if (best != Double.POSITIVE_INFINITY && toGoal < best - PROGRESS) {
            // Really closer to the same goal (a new goal found is no progress yet).
            movedAt = now;
        }
        if (!escaped && outside >= (playerNear ? ESCAPE_LOCKED_MS : ESCAPE_MS) && now - movedAt >= STALLED_MS) {
            escaped = true;
            return Step.ESCAPE;
        }
        if (!wide && outside >= WIDE_MS) {
            wide = true;
            progressAt = now;
            return Step.WIDE;
        }
        if (toGoal < best - PROGRESS) {
            best = toGoal;
            progressAt = now;
            return Step.NONE;
        }
        if (now - progressAt >= NO_PROGRESS_MS) {
            progressAt = now;
            best = Double.POSITIVE_INFINITY;
            return Step.NEW_WAY;
        }
        return Step.NONE;
    }
}
