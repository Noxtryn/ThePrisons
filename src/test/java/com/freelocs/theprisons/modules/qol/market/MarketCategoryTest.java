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
        is(MarketCategory.UPGRADES, "Gear Enchant Books", "Godly Gear Enchant Book");
        is(MarketCategory.MINING, "Tools", "Diamond Pickaxe");
        is(MarketCategory.MINING, "Satchels", "Diamond Ore Satchel");
        is(MarketCategory.MINING, "Satchels", "Clue Scroll Satchel (0 / 320 Drops)");
        is(MarketCategory.COMBAT, "Armor", "Chain Chestplate 0");
        is(MarketCategory.COMBAT, "Armor", "Golden Boots 0");
        is(MarketCategory.COMBAT, "Armor", "Outlaw Golden Boots 0");
        is(MarketCategory.OTHER, "NPC Items", "Diamond Spear");
        is(MarketCategory.COMBAT, "G-Kits", "Gkit Beacon:astronaut");
        is(MarketCategory.COMBAT, "Bandits", "Random Overworld Boss Egg");
        is(MarketCategory.OTHER, "Crates", "Elite Contraband");
        is(MarketCategory.OTHER, "Crates", "Bandit Box");
        is(MarketCategory.OTHER, "Slot Bot", "Cosmo-Slot Bot Ticket Sleeve (1x)");
        is(MarketCategory.OTHER, "Misc", "Mystery Clue Scroll");
    }

    @Test
    void itemsThatWereOtherOnTheRealServer() {
        is(MarketCategory.UPGRADES, "Gear Enchants", "Frenzy IV (100%)");
        is(MarketCategory.UPGRADES, "Gear Enchants", "Cactus II (77%)");
        is(MarketCategory.UPGRADES, "Tool Enchants", "Absolute Efficiency XI (75%)");
        is(MarketCategory.UPGRADES, "Pages", "Legendary Page (3%)");
        is(MarketCategory.UPGRADES, "Scrolls", "Eraser (15)");
        is(MarketCategory.UPGRADES, "XP Bottles", "XP Bottle (8,000,000)");
        is(MarketCategory.OTHER, "Consumables", "Inmate Rations (Right Click) (120m)");
        is(MarketCategory.COSMETICS, "Cosmetics", "Item Nametag");
        is(MarketCategory.UPGRADES, "Dust", "Legendary Dust (10%)");
        is(MarketCategory.UPGRADES, "Scrolls", "Black Scroll (75%)");
    }

    @Test
    void enchantsByWhatTheyAre() {
        is(MarketCategory.UPGRADES, "Tool Enchant Orbs", "Mystery Godly Tool Enchant Orb");
        is(MarketCategory.UPGRADES, "Gear Enchant Books", "Godly Gear Enchant Book");
        is(MarketCategory.UPGRADES, "Mystery Enchants", "Mystery Godly Enchant");
        is(MarketCategory.UPGRADES, "Mystery Enchants", "Mystery Energy Enchant");
        is(MarketCategory.UPGRADES, "Tool Enchants", "Absolute Efficiency XI (75%)");
        is(MarketCategory.UPGRADES, "Tool Enchants", "Cosmic Luck XI (100%)");
        is(MarketCategory.UPGRADES, "Gear Enchants", "Frenzy IV (100%)");
        is(MarketCategory.UPGRADES, "Gear Enchants", "Cactus II (77%)");
    }
}
