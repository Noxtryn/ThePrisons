package io.theprisons.modules.qol.bandit.combat;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which bandit to fight. A score from how close it is to the orbit radius, whether there is a free line, whether the way to it is open,
 * whether it is already hurt, and how many other bandits and players are around it. The chosen target is LOCKED: it stays until it is
 * gone or unusable, and a challenger must beat it by a margin after the lock is a little old - small score changes never switch.
 */
public final class TargetSelector {
    /** @param score the final score; @param why the parts, for the log */
    public record Scored(Foe foe, double score, String why) {
    }

    /** @param target null = nothing valid; @param switched a different target than before was taken */
    public record Result(@Nullable Foe target, double score, boolean switched, String reason) {
    }

    private @Nullable String lockedId;
    private long lockedAtMs;
    private final Map<String, Long> blacklist = new HashMap<>();
    private double lastScore = Double.NaN;

    public @Nullable String lockedId() {
        return lockedId;
    }

    public long lockedAtMs() {
        return lockedAtMs;
    }

    public double lastScore() {
        return lastScore;
    }

    public void clearLock() {
        lockedId = null;
        lastScore = Double.NaN;
    }

    public void blacklist(String id, long untilMs) {
        blacklist.put(id, untilMs);
    }

    public boolean blacklisted(String id, long nowMs) {
        Long until = blacklist.get(id);
        return until != null && until > nowMs;
    }

    public void reset() {
        lockedId = null;
        lastScore = Double.NaN;
        blacklist.clear();
    }

    /** Players near the foe make it a bad target: the existing "crowd" rule. */
    static int crowd(Foe foe, List<Foe> players, double radius) {
        int n = 0;
        for (Foe p : players) {
            if (p.distanceTo(foe.x(), foe.z()) <= radius) {
                n++;
            }
        }
        return n;
    }

    /** Whether the foe may be fought at all. */
    public boolean valid(Foe foe, double px, double pz, List<Foe> players, CombatConfig cfg, long nowMs) {
        return foe.distanceTo(px, pz) <= cfg.targetRange && !blacklisted(foe.id(), nowMs) && crowd(foe, players, cfg.crowdRadius) == 0;
    }

    /**
     * The score. The terrain probe (is the straight way open?) is only made for the few best candidates, hence {@code probeTerrain}.
     */
    Scored score(Foe foe, double px, double py, double pz, double ring, List<Foe> bandits, @Nullable Terrain terrain, boolean probeTerrain,
                 CombatConfig cfg, DistanceBand band, long nowMs) {
        double d = foe.distanceTo(px, pz);
        StringBuilder why = new StringBuilder();
        double s = 100.0D - 1.2D * Math.abs(d - ring);
        why.append(String.format(java.util.Locale.ROOT, "dist%.0f", s - 100.0D));
        if (d < cfg.minSafe) {
            s -= 25.0D;
            why.append(" close-25");
        }
        switch (foe.sight()) {
            case YES -> {
                s += 20.0D;
                why.append(" sight+20");
            }
            case NO -> {
                s -= 10.0D;
                why.append(" nosight-10");
            }
            default -> {
            }
        }
        if (probeTerrain && terrain != null && d > 0.5D) {
            Terrain.Ray ray = terrain.cast(px, py, pz, foe.x() - px, foe.z() - pz, d);
            if (ray.free() >= d - 1.5D) {
                s += 8.0D;
                why.append(" open+8");
            }
        }
        int others = 0;
        for (Foe b : bandits) {
            if (!b.id().equals(foe.id()) && b.distanceTo(foe.x(), foe.z()) <= cfg.effectiveThreatRadius()) {
                others++;
            }
        }
        if (others > 0) {
            double penalty = Math.min(24.0D, others * 6.0D);
            s -= penalty;
            why.append(String.format(java.util.Locale.ROOT, " crowd-%.0f", penalty));
        }
        if (!Double.isNaN(foe.health()) && !Double.isNaN(foe.maxHealth()) && foe.maxHealth() > 0.0D && foe.health() < foe.maxHealth() * 0.99D) {
            s += 10.0D;
            why.append(" hurt+10");
        }
        return new Scored(foe, s, why.toString());
    }

    /**
     * @param bandits every candidate (already filtered by kind); @param players real players around
     */
    public Result select(List<Foe> bandits, List<Foe> players, double px, double py, double pz, double ring, @Nullable Terrain terrain,
                         CombatConfig cfg, DistanceBand band, long nowMs) {
        List<Foe> usable = new ArrayList<>();
        for (Foe b : bandits) {
            if (valid(b, px, pz, players, cfg, nowMs)) {
                usable.add(b);
            }
        }
        if (usable.isEmpty()) {
            return new Result(null, Double.NaN, false, "no valid bandit");
        }
        // First pass without the terrain probe; the three best get the probe.
        List<Scored> first = new ArrayList<>();
        for (Foe b : usable) {
            first.add(score(b, px, py, pz, ring, bandits, terrain, false, cfg, band, nowMs));
        }
        first.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < first.size(); i++) {
            Foe foe = first.get(i).foe();
            scored.add(i < 3 ? score(foe, px, py, pz, ring, bandits, terrain, true, cfg, band, nowMs) : first.get(i));
        }
        Scored locked = null;
        Scored best = null;
        for (Scored sc : scored) {
            double effective = sc.score();
            boolean isLocked = sc.foe().id().equals(lockedId);
            if (isLocked) {
                locked = sc;
                effective += nowMs - lockedAtMs <= cfg.lockBonusMs ? cfg.lockBonus : cfg.lockBonus / 2.0D;
            }
            sc = new Scored(sc.foe(), effective, sc.why());
            if (best == null || sc.score() > best.score()) {
                best = sc;
            }
            if (isLocked) {
                locked = sc;
            }
        }
        if (locked != null) {
            boolean challenger = best != null && !best.foe().id().equals(lockedId);
            boolean mayLeave = nowMs - lockedAtMs >= cfg.lockMinMs;
            if (!challenger || !mayLeave || best.score() <= locked.score() + cfg.switchMargin) {
                lastScore = locked.score();
                return new Result(locked.foe(), locked.score(), false, "locked (" + locked.why() + ")");
            }
        }
        String previous = lockedId;
        lockedId = best.foe().id();
        lockedAtMs = nowMs;
        lastScore = best.score();
        boolean switched = previous != null && !previous.equals(lockedId);
        return new Result(best.foe(), best.score(), switched || previous == null, (previous == null ? "chosen" : "switched from " + previous) + " (" + best.why() + ")");
    }
}
