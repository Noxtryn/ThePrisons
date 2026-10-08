package io.theprisons.items.energy;

import org.jspecify.annotations.Nullable;

/**
 * The data of the energy view, free of any drawing: how much, how big (optional), what it is doing, when it was last read and where from.
 * {@code revision} changes only when something visible changed, so a model built for it stays valid.
 *
 * @param rateMin energy per minute, derived from observed changes; null = not known (needs readings over at least twenty seconds)
 */
public record EnergyExtractorState(long revision, String subject, long current, @Nullable Long capacity, EnergyOperation operation, @Nullable Double rateMin,
                                   long lastUpdateMs, EnergySource source) {
    /** A state not refreshed for this long is stale: shown dimmed, with no rate and no ETA. */
    public static final long STALE_MS = 30_000L;

    public boolean stale(long now) {
        return now - lastUpdateMs > STALE_MS;
    }
}
