package io.theprisons.items.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the observations of one item say about its price, with an honest confidence.
 *
 * <p>Sales beat asking prices: with at least three completed sales they are the basis, otherwise the listings are (and the basis says so). Outliers
 * (more than 3.5 median-absolute-deviations from the median, once there are five samples) are left out of the median / min / max but still count as seen.
 *
 * <p>Confidence is a score out of 100 that is explained: how many samples (up to 40 points), how fresh the newest is (up to 40) and how consistent they are
 * (up to 20). Under two samples there is none; under three it is at most Low; under five at most Medium.
 */
public record MarketStats(double median, double min, double max, int samples, MarketConfidence confidence, int score, long lastSeenMs, String basis,
                          List<String> why) {
    public static final MarketStats EMPTY = new MarketStats(Double.NaN, Double.NaN, Double.NaN, 0, MarketConfidence.NONE, 0, 0L, "none", List.of("no samples"));

    public boolean known() {
        return samples > 0 && !Double.isNaN(median);
    }

    public static MarketStats compute(List<MarketObservation> all, long now, long excludeSignature) {
        List<MarketObservation> sales = new ArrayList<>();
        List<MarketObservation> listings = new ArrayList<>();
        for (MarketObservation o : all) {
            if (o.signature() == excludeSignature) {
                continue;
            }
            (o.source() == MarketObservation.Source.SALE ? sales : listings).add(o);
        }
        boolean useSales = sales.size() >= 3 || listings.isEmpty();
        List<MarketObservation> basis = useSales ? sales : listings;
        if (basis.isEmpty()) {
            return EMPTY;
        }
        List<Double> units = new ArrayList<>(basis.size());
        long newest = 0L;
        for (MarketObservation o : basis) {
            units.add(o.unit());
            newest = Math.max(newest, o.timestampMs());
        }
        Collections.sort(units);
        double median = median(units);
        double mad = mad(units, median);
        List<Double> kept = units;
        if (units.size() >= 5) {
            double limit = 3.5D * Math.max(mad, 0.05D * median);
            kept = new ArrayList<>();
            for (double u : units) {
                if (Math.abs(u - median) <= limit) {
                    kept.add(u);
                }
            }
            median = median(kept);
            mad = mad(kept, median);
        }
        int n = kept.size();
        double min = kept.get(0);
        double max = kept.get(n - 1);

        List<String> why = new ArrayList<>();
        int score = Math.round(Math.min(n, 12) / 12.0F * 40.0F);
        why.add(n + (n == 1 ? " sample" : " samples") + (useSales ? " (sales)" : " (asking prices)") + (units.size() > n ? ", " + (units.size() - n) + " outliers left out" : ""));
        long age = Math.max(0L, now - newest);
        int fresh = age < 10 * 60_000L ? 40 : age < 3_600_000L ? 30 : age < 6 * 3_600_000L ? 18 : age < 24 * 3_600_000L ? 8 : 0;
        score += fresh;
        why.add("newest " + age(age) + " old");
        double spread = median <= 0 ? 1.0D : mad / median;
        int consistency = spread < 0.10D ? 20 : spread < 0.25D ? 12 : spread < 0.50D ? 5 : 0;
        score += consistency;
        why.add("spread " + Math.round(spread * 100) + " %");
        MarketConfidence level = n < 2 ? MarketConfidence.NONE : score >= 75 ? MarketConfidence.HIGH : score >= 45 ? MarketConfidence.MEDIUM : MarketConfidence.LOW;
        if (n < 3 && level.ordinal() > MarketConfidence.LOW.ordinal()) {
            level = MarketConfidence.LOW;
            why.add("under 3 samples: capped at Low");
        } else if (n < 5 && level == MarketConfidence.HIGH) {
            level = MarketConfidence.MEDIUM;
            why.add("under 5 samples: capped at Medium");
        }
        return new MarketStats(median, min, max, n, level, score, newest, useSales ? "sales" : "listings", List.copyOf(why));
    }

    static double median(List<Double> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0D;
    }

    private static double mad(List<Double> sorted, double median) {
        List<Double> dev = new ArrayList<>(sorted.size());
        for (double u : sorted) {
            dev.add(Math.abs(u - median));
        }
        Collections.sort(dev);
        return median(dev);
    }

    static String age(long ms) {
        long s = ms / 1000L;
        return s < 90 ? s + "s" : s < 5400 ? (s / 60) + "m" : s < 172_800 ? (s / 3600) + "h" : (s / 86_400) + "d";
    }
}
