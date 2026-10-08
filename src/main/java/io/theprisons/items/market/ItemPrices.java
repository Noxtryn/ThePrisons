package io.theprisons.items.market;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The prices the item list shows, prepared OFF the render path from the same {@link MarketStats} the auction overlay uses (there is no second price
 * calculation). {@link #refresh} is called from a tick when the market memory changed (or every half minute: the age of the samples moves on); the renderer
 * only reads the immutable map.
 */
public final class ItemPrices {
    /** What a card shows. */
    public record Card(String price, MarketConfidence confidence) {
    }

    private volatile Map<String, Card> cards = Map.of();
    private long builtRevision = -1L;
    private long builtMs;

    /** Rebuilds the cards when the cache changed or the data is older than 30 s; cheap otherwise. @return true when it rebuilt */
    public boolean refresh(MarketCache cache, long now) {
        if (cache.revision() == builtRevision && now - builtMs < 30_000L) {
            return false;
        }
        Map<String, Card> next = new HashMap<>();
        for (String key : cache.catalogKeys()) {
            MarketStats s = cache.statsForCatalog(key, now);
            if (s.known() && s.confidence() != MarketConfidence.NONE) {
                next.put(key, new Card(PriceFormat.money(s.fair()), s.confidence()));
            }
        }
        cards = Map.copyOf(next);
        builtRevision = cache.revision();
        builtMs = now;
        return true;
    }

    public Card card(String catalogKey) {
        return cards.get(catalogKey);
    }

    public int size() {
        return cards.size();
    }

    /** The lines of the item detail: fair price, range, trend, source and age (only what the real observations establish). */
    public static List<String> detailLines(MarketStats s) {
        return detailLines(s, System.currentTimeMillis());
    }

    /** Same detail formatting with an explicit clock for UI callers and deterministic tests. */
    public static List<String> detailLines(MarketStats s, long now) {
        List<String> out = new ArrayList<>();
        if (!s.known()) {
            return out;
        }
        out.add(String.format(Locale.ROOT, "Fair    %s (%s)", PriceFormat.money(s.fair()), s.confidence().label()));
        if (!Double.isNaN(s.low()) && s.samples() >= 3) {
            out.add(String.format(Locale.ROOT, "Range   %s - %s", PriceFormat.money(s.low()), PriceFormat.money(s.high())));
        }
        if (s.trend() != MarketStats.Trend.UNKNOWN) {
            out.add(String.format(Locale.ROOT, "Trend   %s %s", PriceFormat.percent(s.trendPercent()), s.trend().name().toLowerCase(Locale.ROOT)));
        }
        String source = switch (s.basis()) {
            case "sales" -> "Sales · " + s.window();
            case "listings" -> "Listings · " + s.window();
            default -> "Older observations";
        };
        out.add("Source  " + source);
        out.add("Samples " + s.samples());
        if (s.lastSeenMs() > 0L) {
            out.add("Updated " + MarketStats.age(Math.max(0L, now - s.lastSeenMs())) + " ago");
        }
        return out;
    }
}
