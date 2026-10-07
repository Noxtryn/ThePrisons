package io.theprisons.modules.mining.ore;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Finds the player who keeps following the macro (pure logic). Somebody within {@value #NEAR} blocks for
 * {@value #CONTINUOUS_MS} ms in a row (gaps up to {@value #GAP_MS} ms do not break it), or mostly there for
 * {@value #PRESENCE_MS} ms in total, is a follower: first {@link Step#ASK} (a message "pls stop follow me"),
 * and if he is still there {@value #ESCALATE_MS} ms later {@link Step#ESCAPE} (far away: /spawn and /warp back).
 * Somebody who just walks past is never reported.
 */
public final class FollowWatch {
    public enum Step { NONE, ASK, ESCAPE }

    public record Result(Step step, String name) {
        static final Result NONE = new Result(Step.NONE, "");
    }

    public static final double NEAR = 14.0D;
    public static final long CONTINUOUS_MS = 45_000L;
    public static final long GAP_MS = 4_000L;
    public static final long PRESENCE_MS = 120_000L;
    public static final long ESCALATE_MS = 30_000L;
    public static final long ASK_COOLDOWN_MS = 10 * 60_000L;
    public static final long ESCAPE_COOLDOWN_MS = 3 * 60_000L;
    /** A player not seen for this long is forgotten. */
    private static final long FORGET_MS = 5 * 60_000L;

    private static final class Track {
        long since;
        long lastNear;
        long presence;
        long lastUpdate;
        long askedAt;
        long escapedAt;
    }

    private final Map<String, Track> tracks = new HashMap<>();

    public void reset() {
        tracks.clear();
    }

    /** @param distances real players (friends left out) and their distance in blocks; call every few ticks */
    public Result update(long now, Map<String, Double> distances) {
        Result result = Result.NONE;
        for (Map.Entry<String, Double> entry : distances.entrySet()) {
            if (entry.getValue() > NEAR) {
                continue;
            }
            Track t = tracks.computeIfAbsent(entry.getKey(), k -> new Track());
            if (t.lastNear == 0L || now - t.lastNear > GAP_MS) {
                t.since = now;
            }
            long dt = t.lastUpdate == 0L ? 0L : Math.min(now - t.lastUpdate, GAP_MS);
            t.presence += dt;
            t.lastUpdate = now;
            t.lastNear = now;
            Result r = judge(entry.getKey(), t, now);
            if (r.step() == Step.ESCAPE || result.step() == Step.NONE) {
                result = r.step() == Step.NONE ? result : r;
            }
        }
        Iterator<Map.Entry<String, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Track> e = it.next();
            Track t = e.getValue();
            if (now - t.lastNear > GAP_MS && t.lastUpdate != 0L) {
                // Away: the presence runs down at half speed.
                long dt = now - t.lastUpdate;
                t.presence = Math.max(0L, t.presence - dt / 2);
                t.lastUpdate = now;
            }
            if (now - t.lastNear > FORGET_MS) {
                it.remove();
            }
        }
        return result;
    }

    private Result judge(String name, Track t, long now) {
        boolean follower = now - t.since >= CONTINUOUS_MS || t.presence >= PRESENCE_MS;
        if (!follower) {
            return Result.NONE;
        }
        boolean asked = t.askedAt != 0L && now - t.askedAt < ASK_COOLDOWN_MS;
        if (!asked) {
            t.askedAt = now;
            return new Result(Step.ASK, name);
        }
        if (now - t.askedAt >= ESCALATE_MS && (t.escapedAt == 0L || now - t.escapedAt >= ESCAPE_COOLDOWN_MS)) {
            t.escapedAt = now;
            return new Result(Step.ESCAPE, name);
        }
        return Result.NONE;
    }
}
