package io.theprisons.modules.qol.bandit;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Which bandit to fight (pure logic). Every candidate gets a score from its distance to the macro's combat distance,
 * a free line of sight, and the other bandits and players around it. The target already chosen keeps a bonus for a
 * while so two similar bandits do not make the macro flip between them.
 */
public final class BanditTargeting {
    /** @param crowd other real players within a few blocks of the bandit; @param neighbours bandits close to it */
    public record Candidate(String id, double x, double y, double z, double distance, boolean sight, int crowd, int neighbours) {
    }

    /** @param minSafe closer than this is too dangerous; @param maxCombat farther than this the macro walks closer;
     *               @param range farther than this a bandit is not a target at all */
    public record Params(double minSafe, double maxCombat, double range, double stickiness) {
    }

    private BanditTargeting() {
    }

    /** Whether the bandit may be fought at all. */
    public static boolean valid(Candidate c, Params p) {
        return c.distance() <= p.range() && c.crowd() == 0;
    }

    public static double score(Candidate c, Params p) {
        double ideal = (p.minSafe() + p.maxCombat()) / 2.0D;
        double score = 100.0D - Math.abs(c.distance() - ideal) * 1.5D;
        if (c.distance() < p.minSafe()) {
            score -= 30.0D;
        }
        if (c.sight()) {
            score += 25.0D;
        }
        score += Math.min(3, c.neighbours()) * 4.0D;
        score -= c.crowd() * 60.0D;
        return score;
    }

    /**
     * @param current   the id of the target so far ({@code null} = none)
     * @param heldMs    how long it has been the target
     * @param stickyMs  the bonus lasts this long
     * @return the candidate to fight, or null when none is valid
     */
    public static @Nullable Candidate select(List<Candidate> candidates, @Nullable String current, long heldMs, long stickyMs, Params p) {
        Candidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Candidate c : candidates) {
            if (!valid(c, p)) {
                continue;
            }
            double s = score(c, p);
            if (c.id().equals(current) && heldMs <= stickyMs) {
                s += p.stickiness();
            }
            if (s > bestScore) {
                bestScore = s;
                best = c;
            }
        }
        return best;
    }
}
