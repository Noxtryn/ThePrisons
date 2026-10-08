package io.theprisons.items;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One item of the registry, fully prepared: everything the list, the search and the tooltips need is computed when the entry is built, never again.
 *
 * @param searchText the normalised search string: name, internal name, category, subcategory, tier and aliases
 * @param nameText   the normalised display name alone (for ranking: a name match beats an alias match)
 */
public record ItemEntry(ItemIdentity identity, String displayName, String internalName, ItemCategory category, String subcategory, String family,
                        @Nullable String tier, @Nullable String variant, List<String> aliases, String searchText, String nameText, ItemRenderData render,
                        ItemMetadata meta) {
    public ItemEntry {
        aliases = List.copyOf(aliases);
    }

    public String key() {
        return identity.catalogKey();
    }

    /** The rank of the tier (lowest = 0), -1 when the entry has none; used for sorting and the tier filter. */
    public int tierRank() {
        return tier == null ? -1 : ItemTier.rank(tier);
    }
}
