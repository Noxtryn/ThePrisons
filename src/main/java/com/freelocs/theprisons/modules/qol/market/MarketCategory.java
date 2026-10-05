package com.freelocs.theprisons.modules.qol.market;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Which group an item belongs to (the user's five) and its finer kind, from Cosmic's own item id and name (pure logic).
 * Groups: 1 Cosmetics (cell doors, masks, lore crystals, custom blocks ...), 2 Upgrades (boosters, enchants, dust,
 * scrolls, prestige, pets, wormhole powerups ...), 3 Mining (pickaxes, satchels, energy), 4 Combat (armor, swords,
 * spears, g-kits, trinkets, charms, bandit gear), 5 Other (crates, contraband, boxes, flares, tickets ...).
 */
public enum MarketCategory {
    COSMETICS("Cosmetics"), UPGRADES("Upgrades"), MINING("Mining"), COMBAT("Combat"), OTHER("Other");

    public final String label;

    MarketCategory(String label) {
        this.label = label;
    }

    /** {group, kind}: kind = "Armor", "Tools", "Swords", "Shards", "Cell", "Masks", "Pets" ... */
    public record Sorted(MarketCategory group, String kind) {
    }

    private static final String[][] RULES = {
            // {words (any), group, kind} - the first match wins.
            {"cell door,cell defense", "COSMETICS", "Cell"},
            {"nametag,showcase expander,showcase row", "COSMETICS", "Cosmetics"},
            {"ration", "OTHER", "Consumables"},
            {"kill message", "COSMETICS", "Cosmetics"},
            {"1 home,home expander,time extender,gen breaker,aether bloom,red rose,money note,gang point", "OTHER", "Misc"},
            {"xp bottle", "UPGRADES", "XP Bottles"},
            {"eraser,absorber", "UPGRADES", "Scrolls"},
            {"page", "UPGRADES", "Pages"},
            {"home expander,credit,ground zero shop", "OTHER", "Misc"},
            {"mask", "COSMETICS", "Masks"},
            {"lore crystal,custom block,rename,title,cosmetic,trail,altar pillar", "COSMETICS", "Cosmetics"},
            {"pet", "UPGRADES", "Pets"},
            {"booster", "UPGRADES", "Boosters"},
            {"enchant book,gear enchant", "UPGRADES", "Enchant Books"},
            {"charge orb", "UPGRADES", "Charge Orbs"},
            {"satchel", "MINING", "Satchels"},
            {"clue scroll", "OTHER", "Misc"},
            {"enchant orb,enchant,orb", "UPGRADES", "Enchants"},
            {"dust", "UPGRADES", "Dust"},
            {"black scroll,white scroll,blackscroll,scroll", "UPGRADES", "Scrolls"},
            {"prestige", "UPGRADES", "Prestige"},
            {"wormhole", "UPGRADES", "Wormhole"},
            {"skill token,skill tree,rare candy,level up", "UPGRADES", "Skills"},
            {"shard", "UPGRADES", "Shards"},
            {"pickaxe,drill", "MINING", "Tools"},
            {"cosmic energy,energy", "MINING", "Energy"},
            {"ore,meteor", "MINING", "Mining"},
            {"helmet,chestplate,leggings,boots,armor,greed piece", "COMBAT", "Armor"},
            {"sword,spear,axe,bow,mace", "COMBAT", "Weapons"},
            {"gkit,g-kit", "COMBAT", "G-Kits"},
            {"trinket,charm,rabbit's foot,heroes never die", "COMBAT", "Trinkets"},
            {"crate,contraband,box,lootbox,megabox", "OTHER", "Crates"},
            {"bandit,boss egg", "COMBAT", "Bandits"},
            {"flare", "OTHER", "Flares"},
            {"ticket,sleeve,slot bot", "OTHER", "Slot Bot"},
            {"coins,pv row", "OTHER", "Misc"},
    };

    /** An enchant book / enchant: "Frenzy IV (100%)", "Cactus II (77%)" - a roman level and its success rate. */
    private static final java.util.regex.Pattern ENCHANT = java.util.regex.Pattern.compile("^.+\\s[IVXL]+\\s\\(\\d+%\\)$");

    /** Plain armour / weapon pieces of vanilla materials: sold to the NPC, an own kind ("Gold Boots", not "Gold Boots 3"). */
    private static final java.util.regex.Pattern NPC_GEAR = java.util.regex.Pattern.compile(
            "(?i)^(golden|gold|iron|diamond|wooden|stone|netherite)\\s+(sword|spear)(\\s+\\d+)?$");

    /** Revealed enchants of pickaxes ("Absolute Efficiency XI (75%)"); the other revealed ones are gear enchants. */
    private static final java.util.Set<String> TOOL_ENCHANTS = java.util.Set.of("absolute efficiency", "efficiency", "magnet",
            "ore magnet", "whistle", "momentum", "flurry", "jackhammer", "fortune", "cosmic luck", "meteor hunter", "detonate",
            "warp miner", "ore miner", "shard discoverer", "treasure hunter", "skilled excavation", "big bank", "energy collector");
    private static final java.util.regex.Pattern REVEALED = java.util.regex.Pattern.compile("^(.+?)\\s+[IVXL]+(?:\\s*\\(\\d+%\\))?$");

    public static Sorted of(String name, @Nullable String customId) {
        String plain = name.strip();
        String lower = plain.toLowerCase(Locale.ROOT);
        if (NPC_GEAR.matcher(plain).matches()) {
            return new Sorted(OTHER, "NPC Items");
        }
        // Enchants by what they are: tool orbs, tool enchants, mystery enchants, gear books, revealed gear enchants.
        if (lower.contains("tool enchant orb") || lower.contains("pickaxe orb")) {
            return new Sorted(UPGRADES, "Tool Enchant Orbs");
        }
        if (lower.contains("gear enchant book")) {
            return new Sorted(UPGRADES, "Gear Enchant Books");
        }
        if (lower.contains("mystery") && lower.contains("enchant") && !lower.contains("orb")) {
            return new Sorted(UPGRADES, "Mystery Enchants");
        }
        java.util.regex.Matcher rev = REVEALED.matcher(plain);
        if (ENCHANT.matcher(plain).matches() || (rev.matches() && TOOL_ENCHANTS.contains(rev.group(1).toLowerCase(Locale.ROOT)))) {
            String base = rev.matches() ? rev.group(1).toLowerCase(Locale.ROOT) : "";
            return new Sorted(UPGRADES, TOOL_ENCHANTS.contains(base) ? "Tool Enchants" : "Gear Enchants");
        }
        if (ENCHANT.matcher(name.strip()).matches()) {
            return new Sorted(UPGRADES, "Enchant Books");
        }
        String text = (name + " " + (customId == null ? "" : customId.replace('_', ' '))).toLowerCase(Locale.ROOT);
        for (String[] rule : RULES) {
            for (String word : rule[0].split(",")) {
                // Whole words ("ore" is not in "more", "pet" not in "carpet"); a plural s is fine.
                if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(word) + "s?\\b").matcher(text).find()) {
                    return new Sorted(MarketCategory.valueOf(rule[1]), rule[2]);
                }
            }
        }
        return new Sorted(OTHER, "Other");
    }
}
