package io.theprisons.items.market;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The recent observations of ONE item (identity key): bounded, one entry per signature (seeing it again only refreshes its time). */
public final class MarketHistory {
    public static final int MAX = 64;

    private final Map<Long, MarketObservation> bySignature = new LinkedHashMap<>();
    private List<MarketObservation> snapshot = List.of();
    private boolean dirty;
    private long version;

    /** @return true when the observation was new (a refresh of a known one is not) */
    public boolean observe(MarketObservation o) {
        MarketObservation old = bySignature.get(o.signature());
        if (old != null) {
            if (o.timestampMs() > old.timestampMs()) {
                bySignature.remove(o.signature());
                bySignature.put(o.signature(), o);       // moves to the end = newest
                dirty = true;
                version++;
            }
            return false;
        }
        bySignature.put(o.signature(), o);
        while (bySignature.size() > MAX) {
            Long oldest = bySignature.keySet().iterator().next();
            bySignature.remove(oldest);
        }
        dirty = true;
        version++;
        return true;
    }

    public List<MarketObservation> all() {
        if (dirty) {
            snapshot = List.copyOf(new ArrayList<>(bySignature.values()));
            dirty = false;
        }
        return snapshot;
    }

    public int size() {
        return bySignature.size();
    }

    /** Changes with every change: a cached result for this history is valid while the version is the same. */
    public long version() {
        return version;
    }
}
