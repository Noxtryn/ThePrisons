package io.theprisons.items;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The stable identity of a Cosmic item. Two diamond swords are only the same item when what makes them different is the same: the family, the tier, a
 * variant (satchel form, G-Kit), the server's item id, the level / stage, the charge, the enchants. Volatile data - uuids, timestamps, owners, serials, the
 * amount - never enters it, so the same item always gets the same key and different items never fall together.
 *
 * <ul>
 *   <li>{@link #catalogKey()} = family and tier / variant: the entry of the item list and the registry.</li>
 *   <li>{@link #key()} = catalog key + server id + the identity-relevant attributes: one item as the market and the caches see it.</li>
 * </ul>
 *
 * @param attributes the identity-relevant attributes, sorted (a TreeMap), already normalised text
 */
public record ItemIdentity(String catalogKey, String key, String family, @Nullable String tier, @Nullable String variant, @Nullable String customId,
                           ItemConfidence confidence, Map<String, String> attributes) {
    /** Values of {@code cosmicprisons:*} that describe WHAT the item is (levels, tiers, types, charge ...). */
    private static final Pattern RELEVANT = Pattern.compile("(?i)(tier|level|stage|type|kind|variant|rarity|pet|mask|enchant|prestige|charge|energy|percent|power|model|chestid)");
    /** Values that change per piece or per moment, or are a quantity. */
    private static final Pattern VOLATILE = Pattern.compile("(?i)(uuid|stamp|time|date|owner|serial|nonce|seed|created|expire|droppable|movable|keep_on_death|extractor|amount|count|signature|hash)");

    public static ItemIdentity of(ItemFacts facts) {
        return of(facts, ItemClassifier.classify(facts));
    }

    public static ItemIdentity of(ItemFacts facts, ItemClass cls) {
        Map<String, String> attrs = new TreeMap<>();
        for (Map.Entry<String, String> e : facts.values().entrySet()) {
            String k = e.getKey();
            if (k.equals("custom_item_id") || VOLATILE.matcher(k).find() || !RELEVANT.matcher(k).find()) {
                continue;
            }
            attrs.put(k, SearchText.normalize(e.getValue()).isEmpty() ? e.getValue() : e.getValue().toLowerCase(java.util.Locale.ROOT));
        }
        if (!facts.enchants().isEmpty()) {
            attrs.put("enchants", String.join(",", facts.enchants().stream().map(String::toLowerCase).sorted().toList()));
        } else if (ItemClassifier.carriesEnchants(cls)) {
            var lore = LoreEnchants.parse(facts.lore());
            if (!lore.isEmpty()) {
                attrs.put("enchants", String.join(",", lore.stream().sorted().toList()));
            }
        }
        if (!cls.trackable()) {
            // An upgraded single piece: its level / prestige is part of what it is.
            java.util.regex.Matcher m = Pattern.compile("^(.*?)\\s+(\\d+)(?:\\s+([IVXL]+))?$").matcher(facts.name().strip());
            if (m.matches()) {
                attrs.put("level", m.group(2));
                if (m.group(3) != null) {
                    attrs.put("prestige", m.group(3));
                }
            }
        }
        String catalog = cls.catalogKey() != null ? cls.catalogKey()
                : (cls.family() + "|" + (cls.tier() != null ? cls.tier() : cls.variant() != null ? cls.variant() : "")).toLowerCase(java.util.Locale.ROOT);
        StringBuilder key = new StringBuilder(catalog);
        if (facts.customId() != null) {
            key.append('@').append(facts.customId());
        }
        if (!attrs.isEmpty()) {
            key.append('#');
            attrs.forEach((k, v) -> key.append(k).append('=').append(v).append(';'));
        }
        return new ItemIdentity(catalog, key.toString(), cls.family(), cls.tier(), cls.variant(), facts.customId(), cls.confidence(), Map.copyOf(attrs));
    }
}
