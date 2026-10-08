package io.theprisons.items.energy;

import org.jspecify.annotations.Nullable;

/** One reading of an energy value from the game: what, how much, how big (when the source says so) and where from. */
public record EnergyReading(String subject, long current, @Nullable Long capacity, EnergySource source) {
}
