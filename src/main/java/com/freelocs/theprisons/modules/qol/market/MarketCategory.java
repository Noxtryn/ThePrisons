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

    public static Sorted of(String name, @Nullable String customId) {
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
