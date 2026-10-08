package io.theprisons.items.client;

import io.theprisons.items.ItemTier;

import java.util.Map;

/** The colour a tier is drawn in (the mod's own UI choice, matching the item textures where the tier exists there). */
public final class TierColors {
    private static final Map<String, Integer> RGB = Map.ofEntries(
            Map.entry("Simple", 0xD8DEE8), Map.entry("Uncommon", 0x5DE86B), Map.entry("Unique", 0x4FE8E0), Map.entry("Elite", 0x4FD8F0),
            Map.entry("Ultimate", 0xFFE04A), Map.entry("Legendary", 0xFF9A2E), Map.entry("Godly", 0xFF3D6E), Map.entry("Mystic", 0xA66CFF),
            Map.entry("Heroic", 0xFF8FB8), Map.entry("Executive", 0xF2C94C));

    private TierColors() {
    }

    public static int rgb(String tier) {
        Integer c = tier == null ? null : RGB.get(ItemTier.canonical(tier) == null ? tier : ItemTier.canonical(tier));
        return c == null ? 0x9AA3B5 : c;
    }
}
