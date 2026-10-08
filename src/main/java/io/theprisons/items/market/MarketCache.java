package io.theprisons.items.market;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The market memory of this client: one bounded {@link MarketHistory} per item key, least recently used ones dropped first. Stats are cached per
 * (key, history version): asking again without new observations is a map lookup. Client thread only.
 */
public final class MarketCache {
    public static final int MAX_KEYS = 1500;

    private final Map<String, MarketHistory> histories = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MarketHistory> eldest) {
            if (size() > MAX_KEYS) {
                evicted++;
                return true;
            }
            return false;
        }
    };
    private final Map<String, java.util.Set<String>> byCatalog = new HashMap<>();
    private record Cached(long version, long bucket, MarketStats stats) {
    }

    private final Map<String, Cached> statsCache = new HashMap<>();
    private long revision;
    private long evicted;
    private int hits;
    private int misses;

    public long revision() {
        return revision;
    }

    public int keys() {
        return histories.size();
    }

    public long evicted() {
        return evicted;
    }

    public int cacheHits() {
        return hits;
    }

    public int cacheMisses() {
        return misses;
    }

    /** @return true when the observation is NEW (a sighting of a known listing only refreshes its time) */
    public boolean observe(String catalogKey, MarketObservation o) {
        MarketHistory h = histories.computeIfAbsent(o.key(), k -> new MarketHistory());
        byCatalog.computeIfAbsent(catalogKey, k -> new java.util.HashSet<>()).add(o.key());
        long before = h.version();
        boolean isNew = h.observe(o);
        if (h.version() != before) {
            revision++;
        }
        return isNew;
    }

    public MarketHistory history(String key) {
        return histories.get(key);
    }

    /** Stats of one item without the listing being judged (and without other sightings of it). Cached per history version and per minute. */
    public MarketStats stats(String key, long now, MarketObservation exclude) {
        MarketHistory h = histories.get(key);
        if (h == null) {
            return MarketStats.EMPTY;
        }
        String ck = key + "|" + exclude.signature() + "|" + exclude.expiresAtMs() / 4000L;
        long bucket = now / 60_000L;
        Cached c = statsCache.get(ck);
        if (c != null && c.version() == h.version() && c.bucket() == bucket) {
            hits++;
            return c.stats();
        }
        misses++;
        MarketStats s = MarketStats.compute(h.all(), now, exclude);
        statsCache.put(ck, new Cached(h.version(), bucket, s));
        if (statsCache.size() > MAX_KEYS * 4) {
            statsCache.clear();
        }
        return s;
    }

    /** Same, excluding by signature only (no listing identity at hand). */
    public MarketStats stats(String key, long now, long excludeSignature) {
        MarketHistory h = histories.get(key);
        if (h == null) {
            return MarketStats.EMPTY;
        }
        String ck = key + "|" + excludeSignature;
        long bucket = now / 60_000L;
        Cached c = statsCache.get(ck);
        if (c != null && c.version() == h.version() && c.bucket() == bucket) {
            hits++;
            return c.stats();
        }
        misses++;
        MarketStats s = MarketStats.compute(h.all(), now, excludeSignature);
        statsCache.put(ck, new Cached(h.version(), bucket, s));
        if (statsCache.size() > MAX_KEYS * 4) {
            statsCache.clear();          // never grows without bound; the next questions refill what is needed
        }
        return s;
    }

    /**
     * The price of a catalog entry (what the item list shows): the best documented variant of it. Variants of one entry can be priced very differently (a charge
     * orb at 5 % and at 100 %), so they are never mixed into one median; the variant with the most evidence (confidence, then samples) speaks for the entry.
     */
    public MarketStats statsForCatalog(String catalogKey, long now) {
        java.util.Set<String> keys = byCatalog.get(catalogKey);
        if (keys == null || keys.isEmpty()) {
            return MarketStats.EMPTY;
        }
        MarketStats best = MarketStats.EMPTY;
        for (String k : keys) {
            MarketStats s = stats(k, now, 0L);
            if (s.confidence().ordinal() > best.confidence().ordinal()
                    || s.confidence() == best.confidence() && s.samples() > best.samples()) {
                best = s;
            }
        }
        return best;
    }

    /** The catalog keys that have observations. */
    public java.util.List<String> catalogKeys() {
        return new ArrayList<>(byCatalog.keySet());
    }

    /** Everything, for saving. */
    public Map<String, MarketHistory> all() {
        return histories;
    }

    public java.util.Set<String> catalogKeysOf(String key) {
        java.util.Set<String> out = new java.util.HashSet<>();
        byCatalog.forEach((cat, keys) -> {
            if (keys.contains(key)) {
                out.add(cat);
            }
        });
        return out;
    }
}
