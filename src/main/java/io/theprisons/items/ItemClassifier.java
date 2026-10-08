package io.theprisons.items;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The single classifier: {@link ItemFacts} to {@link ItemClass}. The name parsing (family / tier of a market name) is the existing
 * {@link io.theprisons.modules.qol.market.ItemIdentity}; this class adds the category and the confidence on top. The categories are the mod's own
 * grouping for the item list, not a Cosmic taxonomy.
 */
public final class ItemClassifier {
    private record Rule(Pattern pattern, ItemCategory category, String sub) {
        Rule(String regex, ItemCategory category, String sub) {
            this(Pattern.compile(regex), category, sub);
        }
    }

    /** First match wins, so the specific rules sit before the general ones ("Pickaxe Prestige Token" is progression, not mining). */
    private static final List<Rule> RULES = List.of(
            new Rule("\\bmask\\b", ItemCategory.MASKS, "Mask"),
            new Rule("\\bpet\\b|pet leash|pet incubator", ItemCategory.PETS, "Pet"),
            new Rule("booster|powerup|power-up|trinket|wormhole", ItemCategory.POWERUPS, "Boost"),
            new Rule("charge orb", ItemCategory.MINING, "Charge Orb"),
            new Rule("prestige|skill token|skill tree|xp bottle|rare candy|level up|cosmic energy|\\benergy\\b|cosmic coin", ItemCategory.PROGRESSION, "Progress"),
            new Rule("clue scroll|contraband|\\bshard\\b|crate|box key|bandit box|mystery chest|\\bloot\\b|lootbox", ItemCategory.LOOT, "Loot"),
            new Rule("enchant orb|spear orb|\\borb\\b", ItemCategory.ENCHANTS, "Orb"),
            new Rule("enchant book|enchantment book|\\bbook\\b|enchanted_book", ItemCategory.ENCHANTS, "Book"),
            new Rule("white scroll|black scroll|randomization scroll|prestige protection|absorber|eraser|re-?roll", ItemCategory.ENCHANTS, "Scroll"),
            new Rule("\\bpage\\b", ItemCategory.ENCHANTS, "Page"),
            new Rule("\\bdust\\b", ItemCategory.ENCHANTS, "Dust"),
            new Rule("satchel", ItemCategory.MINING, "Satchel"),
            new Rule("pickaxe|gen breaker|\\bore\\b", ItemCategory.MINING, "Tool"),
            new Rule("g-?kit|flare|beacon", ItemCategory.COMBAT, "G-Kit"),
            new Rule("sword|spear|\\baxe\\b|\\bbow\\b|trident|\\bmace\\b|helmet|chestplate|leggings|boots|armou?r|shield", ItemCategory.COMBAT, "Gear"),
            new Rule("nametag|lore crystal|kill message|\\btitle\\b|altar|\\brow\\b|\\+1 home|time extender|flip credit|boss egg|ticket|rations|\\brose|aether",
                    ItemCategory.SPECIAL, "Special"));

    private static final Pattern TRAILING_LEVEL = Pattern.compile("^(.*?)\\s+(\\d+)(?:\\s+([IVXL]+))?$");

    private ItemClassifier() {
    }

    public static ItemClass classify(ItemFacts facts) {
        String name = facts.name().strip();
        String customId = facts.customId();
        io.theprisons.modules.qol.market.ItemIdentity.Id id = name.isEmpty() ? null : io.theprisons.modules.qol.market.ItemIdentity.of(name, customId);
        String family;
        String tier = null;
        String variant = null;
        String display;
        boolean trackable = id != null;
        String catalogKey = null;
        if (id != null) {
            family = id.family();
            String rarity = id.rarity();
            if (rarity != null) {
                tier = ItemTier.canonical(rarity);
                if (tier == null) {
                    variant = rarity;
                }
            }
            display = id.name();
            catalogKey = id.key();
        } else if (!name.isEmpty()) {
            java.util.regex.Matcher m = TRAILING_LEVEL.matcher(name);
            family = m.matches() ? m.group(1).strip() : name;
            display = name;
        } else {
            family = pretty(facts.vanillaId());
            display = family;
        }
        ItemConfidence confidence = customId != null ? ItemConfidence.ID : !name.isEmpty() ? ItemConfidence.NAME : ItemConfidence.VANILLA;
        String haystack = (family + " " + display + " " + (customId == null ? "" : customId.replace('_', ' ')) + " " + facts.vanillaId().replace("minecraft:", "")
                .replace('_', ' ')).toLowerCase(Locale.ROOT);
        ItemCategory category = ItemCategory.MISC;
        String sub = "Other";
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(haystack).find()) {
                category = rule.category();
                sub = rule.sub();
                break;
            }
        }
        return new ItemClass(category, sub, family, tier, variant, display, confidence, trackable, catalogKey);
    }

    static String pretty(String vanillaId) {
        String path = vanillaId.contains(":") ? vanillaId.substring(vanillaId.indexOf(':') + 1) : vanillaId;
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("_")) {
            if (!part.isEmpty()) {
                sb.append(sb.length() == 0 ? "" : " ").append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    /** True when an item of this class can carry enchants in its lore (so the lore enchants count for its identity). */
    static boolean carriesEnchants(@Nullable ItemClass c) {
        return c != null && (c.category() == ItemCategory.MINING && c.subcategory().equals("Tool") || c.category() == ItemCategory.COMBAT && c.subcategory().equals("Gear"));
    }
}
