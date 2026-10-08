package io.theprisons.items.market;

/**
 * The verdict on ONE listing, computed when the page changed and read by the renderer as plain values. The baseline is every OTHER observation of the item
 * ({@link MarketStats}), never the listing itself.
 *
 * <p>Rating: at least +15 % over the fair price is overpriced; at least -25 % under it is great - but only with Medium confidence or better and at least five
 * samples (weak data never gives "great"); at least -10 % is good; else fair. A listing that is 80 % or more under the fair price is flagged
 * {@code suspicious}: it is still highlighted (it may be a real bargain) but the tooltip asks you to check the item.
 *
 * @param diff (listed unit - fair) / fair; negative = cheaper than usual; NaN when unknown
 */
public record ListingAnalysis(int slot, String key, double listedTotal, int amount, double listedUnit, MarketStats stats, double diff, Rating rating,
                              boolean suspicious) {
    public enum Rating {
        UNKNOWN, OVERPRICED, FAIR, GOOD, GREAT
    }

    public static ListingAnalysis of(int slot, MarketObservation listing, MarketStats base) {
        if (!base.known() || base.confidence() == MarketConfidence.NONE) {
            return new ListingAnalysis(slot, listing.key(), listing.total(), listing.amount(), listing.unit(), base, Double.NaN, Rating.UNKNOWN, false);
        }
        double diff = (listing.unit() - base.fair()) / base.fair();
        Rating rating;
        if (diff >= 0.15D) {
            rating = Rating.OVERPRICED;
        } else if (diff <= -0.25D && base.confidence().ordinal() >= MarketConfidence.MEDIUM.ordinal() && base.samples() >= 5) {
            rating = Rating.GREAT;
        } else if (diff <= -0.10D) {
            rating = Rating.GOOD;        // also a big discount on thin data: only "good", never "great"
        } else {
            rating = Rating.FAIR;
        }
        return new ListingAnalysis(slot, listing.key(), listing.total(), listing.amount(), listing.unit(), base, diff, rating, diff <= -0.80D);
    }

    // what the older callers asked for
    public double estimatedUnit() {
        return stats.fair();
    }

    public int samples() {
        return stats.samples();
    }

    public MarketConfidence confidence() {
        return stats.confidence();
    }

    public long lastSeenMs() {
        return stats.lastSeenMs();
    }

    public java.util.List<String> why() {
        return stats.why();
    }
}
