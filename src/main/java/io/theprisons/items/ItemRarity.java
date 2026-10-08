package io.theprisons.items;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Presentation data for Cosmic rarity.  Item classification remains the source of the tier; this class makes every
 * UI use the same canonical spelling, colour and badge instead of carrying a local copy of the palette.
 */
public final class ItemRarity {
    public static final int FALLBACK_RGB = 0x9AA3B5;
    private static final Map<String, Integer> RGB = Map.ofEntries(
            Map.entry("Simple", 0xD8DEE8), Map.entry("Uncommon", 0x5DE86B), Map.entry("Unique", 0x4FE8E0), Map.entry("Elite", 0x4FD8F0),
            Map.entry("Ultimate", 0xFFE04A), Map.entry("Legendary", 0xFF9A2E), Map.entry("Godly", 0xFF8DEB), Map.entry("Mystic", 0xA66CFF),
            Map.entry("Heroic", 0xFF8FB8), Map.entry("Executive", 0xC7CED8));

    private ItemRarity() {
    }

    /** Canonical tier or {@code null}; unknown server text must never be presented as a made-up rarity. */
    public static @Nullable String canonical(@Nullable String tier) {
        return tier == null ? null : ItemTier.canonical(tier);
    }

    /** Palette colour for a known tier, or the neutral UI colour for an item with no recognised Cosmic tier. */
    public static int rgb(@Nullable String tier) {
        String canonical = canonical(tier);
        return canonical == null ? FALLBACK_RGB : RGB.getOrDefault(canonical, FALLBACK_RGB);
    }

    /** Small consistent label for cards and tooltips; blank means that the item has no confirmed tier. */
    public static String badge(@Nullable String tier) {
        String canonical = canonical(tier);
        return canonical == null ? "" : "RARITY  " + canonical.toUpperCase(java.util.Locale.ROOT);
    }
}
