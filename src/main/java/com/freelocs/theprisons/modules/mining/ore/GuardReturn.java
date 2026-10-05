package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.nav.NavigationPath;
import com.freelocs.theprisons.core.nav.PathSearch;
import com.freelocs.theprisons.core.nav.Walkability;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

/**
 * The way back into the guarded zone (pure logic): the safe ground nearest <b>by walking</b>, not by air. A Cosmic mine
 * never changes shape (a mined ore turns into stone and respawns - no air is ever made), so only ways through the air
 * there are lead back in. Game 2026-10-05: the old way picked the taxed block nearest by air - 13 blocks up behind a
 * ceiling of ore, unreachable - and walked under it for 52 minutes, while 24 steps led onto taxed ground beside a guard.
 *
 * <p>One flood from the player (every way cost), then the cheapest reached node that is safe wins.</p>
 */
public final class GuardReturn {
    /** Safe ground this close (way cost) is not taken: it would only step onto the edge. */
    static final double MIN_COST = 1.0D;

    private GuardReturn() {
    }

    /**
     * @param path {@code null} = no safe ground reachable
     * @param reached nodes the flood reached
     */
    public record Result(@Nullable NavigationPath path, int reached, String reason) {
        public boolean found() {
            return path != null;
        }
    }

    public static Result plan(Walkability walk, long start, LongPredicate safe, int maxNodes, int radius,
                              BooleanSupplier cancelled) {
        PathSearch.Flood flood = PathSearch.flood(walk, start, maxNodes, radius, Double.POSITIVE_INFINITY, 0L, cancelled);
        if (flood == null) {
            return new Result(null, 0, cancelled.getAsBoolean() ? "cancelled" : "not standing on known ground");
        }
        long[] best = {Long.MIN_VALUE};
        double[] bestCost = {Double.POSITIVE_INFINITY};
        flood.forEachReached((pos, cost, feet) -> {
            if (cost >= MIN_COST && cost < bestCost[0] && safe.test(pos)) {
                bestCost[0] = cost;
                best[0] = pos;
            }
        });
        if (best[0] == Long.MIN_VALUE) {
            return new Result(null, flood.size(), "no safe ground within " + flood.size() + " reached blocks"
                    + (flood.complete() ? "" : " (search limit)"));
        }
        NavigationPath path = flood.pathTo(best[0]);
        return path == null || path.size() < 2
                ? new Result(null, flood.size(), "no way")
                : new Result(path, flood.size(), String.format(java.util.Locale.ROOT, "%.0f blocks", bestCost[0]));
    }
}
