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

    /** Stats of one item excluding one observation (the listing being judged). Cached per history version and per minute of age. */
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

    /** All observations of every variant of a catalog entry together (what the item list shows). */
    public MarketStats statsForCatalog(String catalogKey, long now) {
        java.util.Set<String> keys = byCatalog.get(catalogKey);
        if (keys == null || keys.isEmpty()) {
            return MarketStats.EMPTY;
        }
        List<MarketObservation> all = new ArrayList<>();
        for (String k : keys) {
            MarketHistory h = histories.get(k);
            if (h != null) {
                all.addAll(h.all());
            }
        }
        return MarketStats.compute(all, now, 0L);
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
