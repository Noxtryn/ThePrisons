package io.theprisons.items;

import java.util.List;

/**
 * The derived list the screen draws: immutable, with the revision it was computed for and what it cost (shown in the dev profile).
 *
 * @param tiers the tiers that exist among the entries of the CURRENT category (lowest first) - empty when none of them has a tier, then the screen shows no rarity filter at all
 */
public record ItemView(List<ItemEntry> entries, List<String> tiers, long registryRevision, long filterRevision, long searchNanos, long filterNanos,
                       int totalItems) {
    public ItemView {
        entries = List.copyOf(entries);
        tiers = List.copyOf(tiers);
    }
}
