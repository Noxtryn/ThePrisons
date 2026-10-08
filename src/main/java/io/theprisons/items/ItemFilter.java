package io.theprisons.items;

import org.jspecify.annotations.Nullable;

import java.util.Set;

/** The filter state of the item list: the search text, a category (null = all) and a set of tiers (empty = all). Immutable; a change makes a new one. */
public record ItemFilter(String query, @Nullable ItemCategory category, Set<String> tiers) {
    public static final ItemFilter NONE = new ItemFilter("", null, Set.of());

    public ItemFilter {
        tiers = Set.copyOf(tiers);
    }

    public ItemFilter withQuery(String q) {
        return new ItemFilter(q, category, tiers);
    }

    public ItemFilter withCategory(@Nullable ItemCategory c) {
        return new ItemFilter(query, c, c == category ? tiers : Set.of());
    }

    public ItemFilter withTiers(Set<String> t) {
        return new ItemFilter(query, category, t);
    }

    boolean accepts(ItemEntry e) {
        if (category != null && e.category() != category) {
            return false;
        }
        return tiers.isEmpty() || e.tier() != null && tiers.contains(e.tier());
    }
}
