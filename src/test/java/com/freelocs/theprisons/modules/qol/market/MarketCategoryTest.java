package com.freelocs.theprisons.modules.qol.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketCategoryTest {
    private static void is(MarketCategory group, String kind, String name) {
        MarketCategory.Sorted s = MarketCategory.of(name, null);
        assertEquals(group, s.group(), name);
        assertEquals(kind, s.kind(), name);
    }

    @Test
    void realItemKindsFromTheMarket() {
        is(MarketCategory.COSMETICS, "Cell", "Cell Door Upgrade");
        is(MarketCategory.COSMETICS, "Masks", "Lucky Leprechaun Mask");
        is(MarketCategory.COSMETICS, "Cosmetics", "Item Lore Crystal");
        is(MarketCategory.UPGRADES, "Prestige", "Pickaxe Prestige Modifier");
        is(MarketCategory.UPGRADES, "Boosters", "Bandit_energy Booster");
        is(MarketCategory.UPGRADES, "Pets", "Anti XP Tax Pet [LVL 1]");
        is(MarketCategory.UPGRADES, "Charge Orbs", "Charge Orb Slot");
        is(MarketCategory.UPGRADES, "Dust", "Pickaxe Dust");
        is(MarketCategory.UPGRADES, "Enchant Books", "Godly Gear Enchant Book");
        is(MarketCategory.MINING, "Tools", "Diamond Pickaxe");
        is(MarketCategory.MINING, "Satchels", "Diamond Ore Satchel");
        is(MarketCategory.MINING, "Satchels", "Clue Scroll Satchel (0 / 320 Drops)");
        is(MarketCategory.COMBAT, "Armor", "Chain Chestplate 0");
        is(MarketCategory.COMBAT, "Weapons", "Diamond Spear");
        is(MarketCategory.COMBAT, "G-Kits", "Gkit Beacon:astronaut");
        is(MarketCategory.COMBAT, "Bandits", "Random Overworld Boss Egg");
        is(MarketCategory.OTHER, "Crates", "Elite Contraband");
        is(MarketCategory.OTHER, "Crates", "Bandit Box");
        is(MarketCategory.OTHER, "Slot Bot", "Cosmo-Slot Bot Ticket Sleeve (1x)");
        is(MarketCategory.OTHER, "Misc", "Mystery Clue Scroll");
    }
}
