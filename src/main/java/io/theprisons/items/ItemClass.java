package io.theprisons.items;

import org.jspecify.annotations.Nullable;

/**
 * What an item is: the user-facing category, a finer technical subcategory, the family ("Shard"), the tier where the family has one, a variant that is
 * no tier (a satchel form, a G-Kit name), the clean display name and how sure the recognition is.
 *
 * @param trackable false for an upgraded single piece ("Iron Pickaxe 17 III") or a shop description: it is not a ware with one price
 */
public record ItemClass(ItemCategory category, String subcategory, String family, @Nullable String tier, @Nullable String variant, String displayName,
                        ItemConfidence confidence, boolean trackable, @Nullable String catalogKey) {
}
