package io.theprisons.items.market;

import java.util.ArrayList;
import java.util.List;

/**
 * The recent observations of ONE item (identity key): bounded to {@link #MAX}, newest kept. Seeing a listing / sale again ({@link MarketObservation#sameAs})
 * refreshes it instead of counting it twice; two real listings that look alike but expire at different times stay two.
 */
public final class MarketHistory {
    public static final int MAX = 64;

    private final List<MarketObservation> list = new ArrayList<>();
    private List<MarketObservation> snapshot = List.of();
    private boolean dirty;
    private long version;

    /** @return true when the observation was new (a refresh of a known one is not) */
    public boolean observe(MarketObservation o) {
        for (int i = 0; i < list.size(); i++) {
            MarketObservation e = list.get(i);
            if (e.sameAs(o)) {
                if (o.source() == MarketObservation.Source.LISTING && o.timestampMs() > e.timestampMs()) {
                    list.set(i, o);                 // the latest sighting (and expiry) of the listing
                    dirty = true;
                    version++;
                }
                return false;                       // a sale keeps its first computed time: no drift
            }
        }
        list.add(o);
        if (list.size() > MAX) {
            int oldest = 0;
            for (int i = 1; i < list.size(); i++) {
                if (list.get(i).timestampMs() < list.get(oldest).timestampMs()) {
                    oldest = i;
                }
            }
            list.remove(oldest);
        }
        dirty = true;
        version++;
        return true;
    }

    public List<MarketObservation> all() {
        if (dirty) {
            snapshot = List.copyOf(list);
            dirty = false;
        }
        return snapshot;
    }

    public int size() {
        return list.size();
    }

    /** Changes with every change: a cached result for this history is valid while the version is the same. */
    public long version() {
        return version;
    }
}
