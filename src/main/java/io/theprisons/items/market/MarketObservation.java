package io.theprisons.items.market;

/**
 * One price the auction house showed: what a listing asks or what a sale made (never a guess). {@code signature} identifies the same listing across
 * refreshes of the same page, so looking at a page again refreshes the sample instead of counting it twice.
 */
public record MarketObservation(String key, double total, int amount, double unit, long timestampMs, Source source, long signature) {
    public enum Source {
        /** A price somebody asks right now. */
        LISTING,
        /** A completed sale. */
        SALE
    }

    public static MarketObservation of(String key, double total, int amount, long ts, Source source) {
        int n = Math.max(1, amount);
        double unit = total / n;
        long sig = java.util.Objects.hash(key, Math.round(total * 100.0D), n, source);
        return new MarketObservation(key, total, n, unit, ts, source, sig);
    }
}
