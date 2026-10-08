package io.theprisons.items;

import java.util.List;
import java.util.Locale;

/**
 * The Cosmic tier words, lowest first (one source of truth for the item list, the market and the item look). A tier only exists on the families that
 * have one: pets and masks have none, so nothing here is ever attached to them.
 */
public final class ItemTier {
    public static final List<String> ALL = List.of("Simple", "Uncommon", "Unique", "Elite", "Ultimate", "Legendary", "Godly", "Mystic", "Heroic", "Executive");

    private ItemTier() {
    }

    /** The canonical spelling of a tier word ("godly" becomes "Godly"), null when it is none. */
    public static String canonical(String word) {
        for (String tier : ALL) {
            if (tier.equalsIgnoreCase(word)) {
                return tier;
            }
        }
        return null;
    }

    public static boolean isTier(String word) {
        return canonical(word) != null;
    }

    /** Position, lowest = 0; -1 for a word that is no tier. */
    public static int rank(String word) {
        String c = canonical(word == null ? "" : word.strip());
        return c == null ? -1 : ALL.indexOf(c);
    }

    public static String lower(String tier) {
        return tier.toLowerCase(Locale.ROOT);
    }
}
