package io.theprisons.items.market;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * One price the auction house showed: what a listing asks or what a sale made (never a guess).
 *
 * <p>Identity of the same listing across refreshes is what the real menus give: a listing page line has the seller and the time it expires ("Expires: 23h 58m
 * 58s" - counted down, so the ABSOLUTE expiry is stable), a history line has seller, buyer, price and "sold 2m ago". Two observations are the same listing when
 * item, price, amount, source, seller and buyer match AND the times are compatible ({@link #sameAs}); with no seller / expiry known the older rule applies
 * (item, price, amount). Seller and buyer are kept as hashes, never as names.
 *
 * @param timestampMs when the price was true: the last sighting of a listing, the sale time of a sale
 * @param expiresAtMs absolute expiry of a listing (0 = not known)
 */
public record MarketObservation(String key, double total, int amount, double unit, long timestampMs, Source source, long signature, int sellerHash,
                                int buyerHash, long expiresAtMs) {
    public enum Source {
        /** A price somebody asks right now. */
        LISTING,
        /** A completed sale. */
        SALE
    }

    /** Listings: the absolute expiry may differ this much between two sightings (the menu counts in seconds). */
    public static final long EXPIRY_TOLERANCE_MS = 3_500L;
    /** Sales: "sold 2m ago" only says the minute, so the computed sale time may drift by up to this much. */
    public static final long SALE_TOLERANCE_MS = 90_000L;

    public static MarketObservation of(String key, double total, int amount, long ts, Source source) {
        return of(key, total, amount, ts, source, null, null, 0L);
    }

    public static MarketObservation of(String key, double total, int amount, long ts, Source source, @Nullable String seller, @Nullable String buyer,
                                       long expiresAtMs) {
        int n = Math.max(1, amount);
        double unit = total / n;
        int sh = hash(seller);
        int bh = hash(buyer);
        long sig = Objects.hash(key, Math.round(total * 100.0D), n, source, sh, bh);
        return new MarketObservation(key, total, n, unit, ts, source, sig, sh, bh, expiresAtMs);
    }

    /** A stored observation read back from the file (seller and buyer exist only as hashes there). */
    public static MarketObservation restore(String key, double total, int amount, long ts, Source source, int sellerHash, int buyerHash, long expiresAtMs) {
        int n = Math.max(1, amount);
        long sig = Objects.hash(key, Math.round(total * 100.0D), n, source, sellerHash, buyerHash);
        return new MarketObservation(key, total, n, total / n, ts, source, sig, sellerHash, buyerHash, expiresAtMs);
    }

    private static int hash(@Nullable String name) {
        return name == null || name.isBlank() || name.equals("???") ? 0 : name.toLowerCase(java.util.Locale.ROOT).hashCode();
    }

    /** True when {@code o} is another sighting of this very listing / sale. */
    public boolean sameAs(MarketObservation o) {
        if (signature != o.signature) {
            return false;
        }
        if (source == Source.SALE) {
            return Math.abs(timestampMs - o.timestampMs) <= SALE_TOLERANCE_MS;
        }
        if (expiresAtMs > 0L && o.expiresAtMs > 0L) {
            return Math.abs(expiresAtMs - o.expiresAtMs) <= EXPIRY_TOLERANCE_MS;
        }
        return true;
    }
}
