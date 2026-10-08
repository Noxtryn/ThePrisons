package io.theprisons.items.market;

import java.util.List;

/**
 * The verdict on ONE listing, computed when the page changed and read by the renderer as plain values.
 *
 * @param estimatedUnit the median unit price of the OTHER observations of this item (NaN = unknown)
 * @param diff          (listed - estimated) / estimated; negative = cheaper than usual; NaN when unknown
 */
public record ListingAnalysis(int slot, String key, double listedTotal, int amount, double listedUnit, double estimatedUnit, double diff, int samples,
                              MarketConfidence confidence, Rating rating, long lastSeenMs, List<String> why) {
    public enum Rating {
        UNKNOWN, OVERPRICED, FAIR, GOOD, GREAT
    }

    public static ListingAnalysis of(int slot, MarketObservation listing, MarketStats base) {
        if (!base.known() || base.confidence() == MarketConfidence.NONE) {
            return new ListingAnalysis(slot, listing.key(), listing.total(), listing.amount(), listing.unit(), Double.NaN, Double.NaN, base.samples(),
                    MarketConfidence.NONE, Rating.UNKNOWN, base.lastSeenMs(), base.why());
        }
        double diff = (listing.unit() - base.median()) / base.median();
        Rating rating;
        if (diff >= 0.15D) {
            rating = Rating.OVERPRICED;
        } else if (diff <= -0.25D && base.confidence().ordinal() >= MarketConfidence.MEDIUM.ordinal()) {
            rating = Rating.GREAT;
        } else if (diff <= -0.10D) {
            rating = Rating.GOOD;       // also a big discount on thin data: only "good", never "great"
        } else {
            rating = Rating.FAIR;
        }
        return new ListingAnalysis(slot, listing.key(), listing.total(), listing.amount(), listing.unit(), base.median(), diff, base.samples(),
                base.confidence(), rating, base.lastSeenMs(), base.why());
    }
}
