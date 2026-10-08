package io.theprisons.items;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the item domain needs to know about a stack, read ONCE from the game and then immutable: the vanilla item, the plain name and lore, the
 * server's {@code cosmicprisons:*} values (prefix cut off, plain text) and the enchantments. Classification, identity, search and the market work on
 * this and never touch Minecraft again, so they are unit-testable and nothing re-parses a lore in a render frame.
 */
public record ItemFacts(String vanillaId, String name, List<String> lore, Map<String, String> values, List<String> enchants, int count) {
    public ItemFacts {
        lore = List.copyOf(lore);
        values = Map.copyOf(values);
        enchants = List.copyOf(enchants);
    }

    /** A name-only fact set (a directory entry, a menu line). */
    public static ItemFacts ofName(String vanillaId, String name) {
        return new ItemFacts(vanillaId, name, List.of(), Map.of(), List.of(), 1);
    }

    public static ItemFacts of(String vanillaId, String name, List<String> lore, @Nullable String customId, int count) {
        return new ItemFacts(vanillaId, name, lore, customId == null ? Map.of() : Map.of("custom_item_id", customId), List.of(), count);
    }

    /** The server's item id ({@code custom_item_id}), null when the item has none. */
    public @Nullable String customId() {
        String id = values.get("custom_item_id");
        return id == null || id.isEmpty() ? null : id;
    }

    /** A value as plain text with the NBT suffix cut off: {@code 5.0E8d} stays a number, {@code "x"} loses its quotes. */
    public static String plain(String snbt) {
        String s = snbt.strip();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        if (s.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?[bBsSlLfFdD]")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public String toString() {
        return "ItemFacts[" + vanillaId + " '" + name + "' " + (customId() == null ? "" : customId()) + "]";
    }

    /** Lower-case key helper used by the classifier. */
    String lowerName() {
        return name.toLowerCase(Locale.ROOT);
    }
}
