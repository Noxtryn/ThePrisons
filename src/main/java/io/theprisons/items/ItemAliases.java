package io.theprisons.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Other words players use for an item family. Added to the search text once, when the entry is built. */
public final class ItemAliases {
    private static final Map<String, List<String>> BY_FAMILY = Map.ofEntries(
            Map.entry("g-kit", List.of("gkit", "kit")),
            Map.entry("g-kit level up", List.of("gkit level", "kit level up")),
            Map.entry("xp bottle", List.of("xp", "exp bottle", "experience bottle")),
            Map.entry("enchant orb", List.of("orb", "tool orb")),
            Map.entry("enchant book", List.of("book", "ebook")),
            Map.entry("mystery clue scroll", List.of("clue", "mystery clue")),
            Map.entry("clue scroll", List.of("clue")),
            Map.entry("randomization scroll", List.of("rand scroll", "random scroll")),
            Map.entry("bandit box key", List.of("box key", "key")),
            Map.entry("secret dust", List.of("sdust")),
            Map.entry("dust", List.of("pickaxe dust", "enchant dust")),
            Map.entry("cosmic energy", List.of("energy", "ce")),
            Map.entry("rare candy", List.of("candy")),
            Map.entry("white scroll", List.of("ws")),
            Map.entry("black scroll", List.of("bs")));

    private ItemAliases() {
    }

    /** The aliases of a family: the table above plus the family without spaces and dashes ("gkit", "xpbottle"). */
    public static List<String> of(String family) {
        String lower = family.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(BY_FAMILY.getOrDefault(lower, List.of()));
        String squashed = lower.replaceAll("[^a-z0-9]", "");
        if (!squashed.equals(lower) && !squashed.isEmpty()) {
            out.add(squashed);
        }
        return out;
    }
}
