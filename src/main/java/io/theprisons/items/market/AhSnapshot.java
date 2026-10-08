package io.theprisons.items.market;

import java.util.Map;

/**
 * The immutable result of analysing one auction page: slot to prepared view, plus what it cost (dev profile). The renderer reads this and nothing else.
 *
 * @param pageSignature identifies the page content the snapshot was built for
 */
public record AhSnapshot(long pageSignature, long builtAtMs, Map<Integer, SlotView> slots, int parsed, int newObservations, long computeNanos) {
    public static final AhSnapshot EMPTY = new AhSnapshot(0L, 0L, Map.of(), 0, 0, 0L);

    public AhSnapshot {
        slots = Map.copyOf(slots);
    }
}
