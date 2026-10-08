package io.theprisons.modules.qol.bandit.dodge;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * What the movement remembers between ticks: the heading and how long it has been held, the headings that just failed, the recent turns (to see a
 * left-right flutter and commit to a lane), where the player was (stuck is a last resort, not navigation) and the evade state with its
 * enter / exit thresholds.
 */
final class MotionMemory {
    private final DodgeConfig cfg;
    private double hx;
    private double hz = 1.0D;
    private boolean hasHeading;
    private long headingSinceMs;
    private double previousHeadingDegrees = Double.NaN;
    private long committedUntil = Long.MIN_VALUE / 2L;
    private int oscillations;
    private boolean evading;
    private final Map<Integer, Long> failed = new HashMap<>();
    private final ArrayDeque<double[]> poses = new ArrayDeque<>();
    private final ArrayDeque<double[]> turns = new ArrayDeque<>();
    private final ArrayDeque<double[]> headings = new ArrayDeque<>();

    MotionMemory(DodgeConfig cfg) {
        this.cfg = cfg;
    }

    void reset() {
        hasHeading = false;
        headingSinceMs = 0L;
        previousHeadingDegrees = Double.NaN;
        committedUntil = Long.MIN_VALUE / 2L;
        oscillations = 0;
        evading = false;
        failed.clear();
        poses.clear();
        turns.clear();
        headings.clear();
    }

    boolean hasHeading() {
        return hasHeading;
    }

    void initHeading(double x, double z) {
        hx = x;
        hz = z;
        hasHeading = true;
    }

    double headingX() {
        return hx;
    }

    double headingZ() {
        return hz;
    }

    int oscillations() {
        return oscillations;
    }

    boolean committed(long now) {
        return now < committedUntil;
    }

    double previousHeadingDegrees() {
        return previousHeadingDegrees;
    }

    long headingAgeMs(long now) {
        return headingSinceMs == 0L ? 0L : now - headingSinceMs;
    }

    /** Evade starts under the minimum distance and ends only beyond minimum + buffer. */
    boolean updateEvading(boolean breach, double nearest) {
        if (breach) {
            evading = true;
        } else if (Double.isNaN(nearest) || nearest > cfg.minDistance + cfg.evadeExitBuffer) {
            evading = false;
        }
        return evading;
    }

    boolean failedRecently(int index) {
        return failed.containsKey(index);
    }

    void markFailed(int index, long now) {
        failed.put(index, now + cfg.failedHeadingMs);
    }

    void expire(long now) {
        failed.values().removeIf(until -> until <= now);
    }

    /** Asked to run but hardly moving for a while. Last resort: the heading is marked failed. */
    boolean updateStuck(long now, double x, double z, int headingIndex) {
        poses.addLast(new double[]{now, x, z});
        while (poses.size() > 1 && now - poses.peekFirst()[0] > cfg.stuckWindowMs) {
            poses.pollFirst();
        }
        double[] first = poses.peekFirst();
        if (first != null && now - first[0] >= cfg.stuckWindowMs * 0.9D && Math.hypot(x - first[1], z - first[2]) < cfg.stuckMinMove) {
            markFailed(headingIndex, now);
            poses.clear();
            return true;
        }
        return false;
    }

    /** Records the turn towards the new heading; true when the last turns alternate left / right (then the lane is committed to for a while). */
    boolean recordHeading(long now, double newX, double newZ, double angleDegrees, double sign) {
        boolean flutter = false;
        if (angleDegrees > 35.0D) {
            turns.addLast(new double[]{now, sign});
            while (!turns.isEmpty() && now - turns.peekFirst()[0] > cfg.oscillationWindowMs) {
                turns.pollFirst();
            }
            if (oscillating()) {
                oscillations++;
                committedUntil = now + cfg.commitMs;
                turns.clear();
                flutter = true;
            }
        }
        if (angleDegrees > 5.0D || headingSinceMs == 0L) {
            previousHeadingDegrees = Math.toDegrees(Math.atan2(hz, hx));
            headingSinceMs = now;
        }
        hx = newX;
        hz = newZ;
        headings.addLast(new double[]{now, Math.toDegrees(Math.atan2(hz, hx))});
        while (headings.size() > 1 && now - headings.peekFirst()[0] > 400L) {
            headings.pollFirst();
        }
        return flutter;
    }

    /** How far the heading moved over the last 400 ms. */
    double headingDrift() {
        double[] oldest = headings.peekFirst();
        return oldest == null ? 0.0D : Math.abs(wrap180(Math.toDegrees(Math.atan2(hz, hx)) - oldest[1]));
    }

    private boolean oscillating() {
        if (turns.size() < 4) {
            return false;
        }
        double[][] all = turns.toArray(new double[0][]);
        for (int i = all.length - 3; i < all.length; i++) {
            if (all[i][1] == 0.0D || all[i][1] == all[i - 1][1]) {
                return false;
            }
        }
        return true;
    }

    static double wrap180(double d) {
        double v = d % 360.0D;
        if (v > 180.0D) {
            v -= 360.0D;
        } else if (v <= -180.0D) {
            v += 360.0D;
        }
        return v;
    }
}
