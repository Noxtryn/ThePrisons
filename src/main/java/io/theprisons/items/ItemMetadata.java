package io.theprisons.items;

import java.util.List;

/**
 * Static facts about an entry, filled once when it is registered.
 *
 * @param tiers     the tiers the family is known to have, lowest first; EMPTY when the family has none (pets, masks ...) - nothing is invented for it
 * @param variants  variants that are no tier (satchel forms, G-Kit names)
 * @param source    where the entry came from (DIRECTORY = the mod's known families, SEEN = learned from items the game showed)
 */
public record ItemMetadata(List<String> tiers, List<String> variants, String source) {
    public ItemMetadata {
        tiers = List.copyOf(tiers);
        variants = List.copyOf(variants);
    }

    public boolean hasTiers() {
        return !tiers.isEmpty();
    }
}
