package io.theprisons.items.market;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * What the observations of one item say about its price: a fair price with a range, a quick-sell price, a trend and an honest confidence.
 *
 * <p><b>Which observations count.</b> Completed sales beat asking prices, and recent beats old, in this order: sales of the last 24 h (at least 3), else sales
 * of the last 7 days (at least 3), else listings seen in the last 12 h (at least 3), else whatever older data exists (low confidence at best). A market that moved
 * two weeks ago therefore does not decide today's price. Inside the window every sample is weighted by its age (half-life: a half of the window) and the fair
 * price is the weighted MEDIAN - never the average.
 *
 * <p><b>Outliers.</b> From five samples on, a sample more than 3.5 median-absolute-deviations (at least 5 % of the median) from the median is left out; from three
 * samples on, one that is more than five times above or below it is. A single typo (1) or scam (100000) next to a market of ~100 moves nothing.
 *
 * <p><b>Confidence</b> is a score out of 100, explained in {@link #why()}: sample count (30), recency (25), spread (20), basis - sales 15 / listings 6 (15 max),
 * agreement of the 24 h and the 7 day price (10). High needs 8 samples, a newest sample under 6 h old and 75 points (listings alone: 12 samples); under 2
 * samples (or two that disagree by more than five times) there is none, under 3 at most Low, under 5 at most Medium, old data at most Low.
 *
 * @param fair      weighted median unit price of the chosen window (NaN = unknown)
 * @param quickSell the price a quarter of the samples were at or below (weighted 25th percentile; NaN under five samples)
 * @param low       cheapest / dearest sample that counts (outliers excluded)
 * @param trendPercent fair 24 h over fair 7 days minus one (NaN when the trend is unknown)
 */
public record MarketStats(double fair, double quickSell, double low, double high, int samples, MarketConfidence confidence, int score, long lastSeenMs,
                          String basis, String window, double fair24h, double fair7d, Trend trend, double trendPercent, List<String> why) {
    public enum Trend { UNKNOWN, RISING, STABLE, FALLING }

    public static final MarketStats EMPTY = new MarketStats(Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, MarketConfidence.NONE, 0, 0L, "none", "none",
            Double.NaN, Double.NaN, Trend.UNKNOWN, Double.NaN, List.of("no samples"));

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24L * HOUR;
    public static final int MIN_WINDOW = 3;

    public MarketStats {
        why = List.copyOf(why);
    }

    public boolean known() {
        return samples > 0 && !Double.isNaN(fair);
    }

    /** The fair price under its old name (kept for callers that only need "the price"). */
    public double median() {
        return fair;
    }

    // ── entry points ─────────────────────────────────────────────────────────

    public static MarketStats compute(List<MarketObservation> all, long now, long excludeSignature) {
        List<MarketObservation> use = new ArrayList<>(all.size());
        for (MarketObservation o : all) {
            if (o.signature() != excludeSignature) {
                use.add(o);
            }
        }
        return build(use, now);
    }

    /** Without the observation that is being judged (and without other sightings of that very listing). */
    public static MarketStats compute(List<MarketObservation> all, long now, @Nullable MarketObservation exclude) {
        if (exclude == null) {
            return build(all, now);
        }
        List<MarketObservation> use = new ArrayList<>(all.size());
        for (MarketObservation o : all) {
            if (!o.sameAs(exclude)) {
                use.add(o);
            }
        }
        return build(use, now);
    }

    // ── the engine ───────────────────────────────────────────────────────────

    private record Sample(double unit, long ts, MarketObservation.Source source) {
    }

    private record Estimate(double fair, double quickSell, double low, double high, int kept, int dropped, double spread, long newest) {
    }

    private static MarketStats build(List<MarketObservation> obs, long now) {
        List<Sample> sales = new ArrayList<>();
        List<Sample> listings = new ArrayList<>();
        for (MarketObservation o : obs) {
            (o.source() == MarketObservation.Source.SALE ? sales : listings).add(new Sample(o.unit(), o.timestampMs(), o.source()));
        }
        List<Sample> chosen;
        String basis;
        String window;
        long halfLife;
        List<Sample> s24 = within(sales, now, DAY);
        List<Sample> s7 = within(sales, now, 7 * DAY);
        List<Sample> l12 = within(listings, now, 12 * HOUR);
        if (s24.size() >= MIN_WINDOW) {
            chosen = s24;
            basis = "sales";
            window = "24h sales";
            halfLife = 12 * HOUR;
        } else if (s7.size() >= MIN_WINDOW) {
            chosen = s7;
            basis = "sales";
            window = "7d sales";
            halfLife = 48 * HOUR;
        } else if (l12.size() >= MIN_WINDOW) {
            chosen = l12;
            basis = "listings";
            window = "12h listings";
            halfLife = 4 * HOUR;
        } else {
            chosen = new ArrayList<>(sales);
            chosen.addAll(listings);
            basis = "older";
            window = "older data";
            halfLife = 72 * HOUR;
        }
        if (chosen.isEmpty()) {
            return EMPTY;
        }
        Estimate e = estimate(chosen, now, halfLife);

        // trend from the same kind of samples as the basis: 24 h against 7 days
        double f24 = Double.NaN;
        double f7 = Double.NaN;
        List<Sample> kind = basis.equals("sales") ? sales : basis.equals("listings") ? listings : List.of();
        List<Sample> k24 = within(kind, now, DAY);
        List<Sample> k7 = within(kind, now, 7 * DAY);
        if (k24.size() >= MIN_WINDOW) {
            f24 = estimate(k24, now, 12 * HOUR).fair();
        }
        if (k7.size() >= 5) {
            f7 = estimate(k7, now, 1000 * DAY).fair();      // not weighted by age: it is the week's level the trend is measured against
        }

        int n = e.kept();
        List<String> why = new ArrayList<>();
        int score = Math.round(Math.min(n, 12) / 12.0F * 30.0F);
        why.add(n + (n == 1 ? " sample" : " samples") + " (" + window + ")" + (e.dropped() > 0 ? ", " + e.dropped() + " outliers left out" : ""));
        long age = Math.max(0L, now - e.newest());
        score += age < 10 * 60_000L ? 25 : age < HOUR ? 20 : age < 6 * HOUR ? 14 : age < DAY ? 8 : age < 7 * DAY ? 3 : 0;
        why.add("newest " + age(age) + " old");
        score += e.spread() < 0.10D ? 20 : e.spread() < 0.25D ? 12 : e.spread() < 0.50D ? 5 : 0;
        why.add("spread " + Math.round(e.spread() * 100) + " %");
        score += basis.equals("sales") ? 15 : basis.equals("listings") ? 6 : 0;
        double trendPct = Double.NaN;
        if (!Double.isNaN(f24) && !Double.isNaN(f7) && f7 > 0.0D) {
            trendPct = f24 / f7 - 1.0D;
            score += Math.abs(trendPct) < 0.10D ? 10 : Math.abs(trendPct) < 0.25D ? 5 : 0;
        }

        MarketConfidence level;
        if (n < 2) {
            level = MarketConfidence.NONE;
        } else if (n == 2 && Math.max(e.low(), e.high()) > 5.0D * Math.min(e.low(), e.high())) {
            level = MarketConfidence.NONE;
            why.add("two samples that disagree by more than 5x: no estimate");
        } else {
            level = score >= 75 && n >= 8 && age < 6 * HOUR && !(basis.equals("listings") && n < 12) ? MarketConfidence.HIGH
                    : score >= 45 ? MarketConfidence.MEDIUM : MarketConfidence.LOW;
            if (n < 3 && level.ordinal() > MarketConfidence.LOW.ordinal()) {
                level = MarketConfidence.LOW;
                why.add("under 3 samples: capped at Low");
            } else if (n < 5 && level == MarketConfidence.HIGH) {
                level = MarketConfidence.MEDIUM;
                why.add("under 5 samples: capped at Medium");
            }
            if (basis.equals("older") && level.ordinal() > MarketConfidence.LOW.ordinal()) {
                level = MarketConfidence.LOW;
                why.add("only old data: capped at Low");
            }
        }
        Trend trend = Trend.UNKNOWN;
        if (!Double.isNaN(trendPct) && level.ordinal() >= MarketConfidence.MEDIUM.ordinal()) {
            trend = trendPct > 0.05D ? Trend.RISING : trendPct < -0.05D ? Trend.FALLING : Trend.STABLE;
        } else {
            trendPct = Double.NaN;
        }
        return new MarketStats(e.fair(), e.quickSell(), e.low(), e.high(), n, level, score, e.newest(), basis, window, f24, f7, trend, trendPct, why);
    }

    private static List<Sample> within(List<Sample> in, long now, long window) {
        List<Sample> out = new ArrayList<>();
        for (Sample s : in) {
            if (now - s.ts() <= window) {
                out.add(s);
            }
        }
        return out;
    }

    private static Estimate estimate(List<Sample> in, long now, long halfLife) {
        List<Sample> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparingDouble(Sample::unit));
        double m = weightedQuantile(sorted, now, halfLife, 0.5D);
        List<Sample> kept = sorted;
        if (sorted.size() >= 5) {
            List<Double> dev = new ArrayList<>();
            for (Sample s : sorted) {
                dev.add(Math.abs(s.unit() - m));
            }
            Collections.sort(dev);
            double mad = median(dev);
            double limit = 3.5D * Math.max(mad, 0.05D * m);
            kept = new ArrayList<>();
            for (Sample s : sorted) {
                if (Math.abs(s.unit() - m) <= limit && s.unit() <= 10.0D * m && s.unit() >= m / 10.0D) {
                    kept.add(s);
                }
            }
        } else if (sorted.size() >= 3) {
            kept = new ArrayList<>();
            for (Sample s : sorted) {
                if (s.unit() <= 5.0D * m && s.unit() >= m / 5.0D) {
                    kept.add(s);
                }
            }
        }
        if (kept.isEmpty()) {
            kept = sorted;
        }
        double fair = weightedQuantile(kept, now, halfLife, 0.5D);
        double quick = kept.size() >= 5 ? weightedQuantile(kept, now, halfLife, 0.25D) : Double.NaN;
        List<Double> dev = new ArrayList<>();
        long newest = 0L;
        for (Sample s : kept) {
            dev.add(Math.abs(s.unit() - fair));
            newest = Math.max(newest, s.ts());
        }
        Collections.sort(dev);
        double spread = fair <= 0.0D ? 1.0D : median(dev) / fair;
        return new Estimate(fair, quick, kept.get(0).unit(), kept.get(kept.size() - 1).unit(), kept.size(), sorted.size() - kept.size(), spread, newest);
    }

    /** Weighted quantile of samples sorted by unit price; the weight of a sample halves every {@code halfLife}. */
    private static double weightedQuantile(List<Sample> sorted, long now, long halfLife, double q) {
        double total = 0.0D;
        double[] w = new double[sorted.size()];
        for (int i = 0; i < w.length; i++) {
            double age = Math.max(0L, now - sorted.get(i).ts());
            w[i] = Math.pow(0.5D, age / halfLife);
            total += w[i];
        }
        double target = total * q;
        double acc = 0.0D;
        for (int i = 0; i < w.length; i++) {
            acc += w[i];
            if (acc >= target - 1e-12) {
                if (q == 0.5D && Math.abs(acc - target) < 1e-9 && i + 1 < w.length) {
                    return (sorted.get(i).unit() + sorted.get(i + 1).unit()) / 2.0D;     // an even split: the middle of the two
                }
                return sorted.get(i).unit();
            }
        }
        return sorted.get(sorted.size() - 1).unit();
    }

    static double median(List<Double> sorted) {
        int n = sorted.size();
        return n == 0 ? Double.NaN : n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0D;
    }

    static String age(long ms) {
        long s = ms / 1000L;
        return s < 90 ? s + "s" : s < 5400 ? (s / 60) + "m" : s < 172_800 ? (s / 3600) + "h" : (s / 86_400) + "d";
    }
}
