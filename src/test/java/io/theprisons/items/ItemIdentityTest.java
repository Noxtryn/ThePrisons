package io.theprisons.items;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemIdentityTest {
    private static ItemIdentity id(ItemFacts f) {
        return ItemIdentity.of(f);
    }

    @Test
    void theSameItemWithDifferentVolatileDataIsTheSameIdentity() {
        ItemFacts a = Samples.facts("Godly Shard", "minecraft:prismarine_shard", "shard", Map.of("shard_tier", "GODLY", "custom_item_uuid", "aaa", "auto_update_stamp", "123",
                "amount", "5", "movable", "1"));
        ItemFacts b = Samples.facts("Godly Shard", "minecraft:prismarine_shard", "shard", Map.of("shard_tier", "GODLY", "custom_item_uuid", "bbb", "auto_update_stamp", "999",
                "amount", "64", "movable", "0"));
        assertEquals(id(a).key(), id(b).key());
        assertEquals("shard", id(a).family().toLowerCase().replace("godly ", ""));
    }

    @Test
    void differentEnchantsGiveADifferentIdentity() {
        ItemFacts plain = new ItemFacts("minecraft:diamond_sword", "Diamond Sword", List.of(), Map.of(), List.of("sharpness 5"), 1);
        ItemFacts other = new ItemFacts("minecraft:diamond_sword", "Diamond Sword", List.of(), Map.of(), List.of("sharpness 3"), 1);
        ItemFacts none = new ItemFacts("minecraft:diamond_sword", "Diamond Sword", List.of(), Map.of(), List.of(), 1);
        assertNotEquals(id(plain).key(), id(other).key());
        assertNotEquals(id(plain).key(), id(none).key());
        assertEquals(id(plain).catalogKey(), id(other).catalogKey(), "the catalog entry is the same item, only the piece differs");
    }

    @Test
    void enchantOrderDoesNotMatter() {
        ItemFacts a = new ItemFacts("minecraft:diamond_sword", "Diamond Sword", List.of(), Map.of(), List.of("sharpness 5", "looting 3"), 1);
        ItemFacts b = new ItemFacts("minecraft:diamond_sword", "Diamond Sword", List.of(), Map.of(), List.of("looting 3", "sharpness 5"), 1);
        assertEquals(id(a).key(), id(b).key());
    }

    @Test
    void twoItemsWithTheSameVanillaMaterialButDifferentServerIdsStayApart() {
        ItemFacts a = Samples.facts("Diamond Sword", "minecraft:diamond_sword", "outlaw_sword", Map.of());
        ItemFacts b = Samples.facts("Diamond Sword", "minecraft:diamond_sword", "slasher_sword", Map.of());
        assertNotEquals(id(a).key(), id(b).key());
    }

    @Test
    void levelStageAndChargeAreRelevantAttributes() {
        ItemFacts low = Samples.facts("Charge Orb", "minecraft:magma_cream", "charge_orb", Map.of("charge_orb_percent", "5"));
        ItemFacts high = Samples.facts("Charge Orb", "minecraft:magma_cream", "charge_orb", Map.of("charge_orb_percent", "100"));
        assertNotEquals(id(low).key(), id(high).key());
        ItemFacts lvl1 = Samples.facts("Tool Prestige Token", "minecraft:nether_star", "pickaxe_prestige_token", Map.of("prestige_token_level", "1"));
        ItemFacts lvl5 = Samples.facts("Tool Prestige Token", "minecraft:nether_star", "pickaxe_prestige_token", Map.of("prestige_token_level", "5"));
        assertNotEquals(id(lvl1).key(), id(lvl5).key());
    }

    @Test
    void anUpgradedPieceKeepsItsLevelInTheIdentityAndIsNotTrackable() {
        ItemFacts p17 = ItemFacts.ofName("minecraft:iron_pickaxe", "Iron Pickaxe 17 III");
        ItemFacts p3 = ItemFacts.ofName("minecraft:iron_pickaxe", "Iron Pickaxe 3");
        assertTrue(!ItemClassifier.classify(p17).trackable());
        assertNotEquals(id(p17).key(), id(p3).key());
        assertEquals("17", id(p17).attributes().get("level"));
        assertEquals("III", id(p17).attributes().get("prestige"));
    }

    @Test
    void loreEnchantsOfAToolCountButProseDoesNot() {
        ItemFacts a = new ItemFacts("minecraft:diamond_pickaxe", "Diamond Pickaxe", List.of("Absolute Efficiency IV", "Right-Click to receive a thing!", "Sellers: 8"), Map.of(), List.of(), 1);
        ItemFacts b = new ItemFacts("minecraft:diamond_pickaxe", "Diamond Pickaxe", List.of("Absolute Efficiency IV", "Some other prose line here"), Map.of(), List.of(), 1);
        assertEquals(id(a).key(), id(b).key());
        assertEquals("absolute efficiency iv", id(a).attributes().get("enchants"));
    }

    @Test
    void theConfidenceSaysHowTheItemWasRecognised() {
        assertEquals(ItemConfidence.ID, id(Samples.facts("Godly Shard", "minecraft:prismarine_shard", "shard", Map.of())).confidence());
        assertEquals(ItemConfidence.NAME, id(ItemFacts.ofName("minecraft:paper", "White Scroll")).confidence());
        assertEquals(ItemConfidence.VANILLA, id(ItemFacts.ofName("minecraft:dirt", "")).confidence());
    }

    // ── the real catalog ─────────────────────────────────────────────────────

    @Test
    void everyRealItemGetsAStableIdentityAndTheKeysAreUnique() {
        Map<String, ItemFacts> all = Samples.catalog();
        assertTrue(all.size() >= 35);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Map.Entry<String, ItemFacts> e : all.entrySet()) {
            ItemIdentity a = ItemIdentity.of(e.getValue());
            ItemIdentity b = ItemIdentity.of(e.getValue());
            assertEquals(a.key(), b.key(), e.getKey());
            assertTrue(keys.add(a.key()), "two different catalog items share the key " + a.key());
            assertTrue(!a.key().contains("uuid") && !a.key().contains("stamp"), a.key());
        }
    }

    @Test
    void realItemsLandInTheirCategories() {
        Map<String, ItemFacts> all = Samples.catalog();
        Map<String, ItemCategory> expected = Map.ofEntries(
                Map.entry("anonymous mask|", ItemCategory.MASKS), Map.entry("sentinel mask|", ItemCategory.MASKS),
                Map.entry("cleanse pet|", ItemCategory.PETS), Map.entry("g-kit|sludge", ItemCategory.COMBAT),
                Map.entry("diamond satchel|block", ItemCategory.MINING), Map.entry("shard|executive", ItemCategory.LOOT),
                Map.entry("contraband|legendary", ItemCategory.LOOT), Map.entry("tool prestige token vii|", ItemCategory.PROGRESSION),
                Map.entry("wormhole powerup (double tap)|", ItemCategory.POWERUPS), Map.entry("blink trinket|", ItemCategory.POWERUPS),
                Map.entry("absolute efficiency xi|", ItemCategory.ENCHANTS), Map.entry("outbreak i|", ItemCategory.ENCHANTS),
                Map.entry("black scroll|godly", ItemCategory.ENCHANTS), Map.entry("page|legendary", ItemCategory.ENCHANTS),
                Map.entry("cosmic energy|", ItemCategory.PROGRESSION), Map.entry("energy booster|", ItemCategory.POWERUPS),
                Map.entry("item nametag|", ItemCategory.SPECIAL), Map.entry("outlaw golden chestplate|", ItemCategory.COMBAT));
        for (Map.Entry<String, ItemCategory> e : expected.entrySet()) {
            ItemFacts f = all.get(e.getKey());
            assertTrue(f != null, "sample missing " + e.getKey());
            assertEquals(e.getValue(), ItemClassifier.classify(f).category(), e.getKey() + " (" + f.name() + ")");
        }
    }

    @Test
    void petsAndMasksNeverGetATier() {
        for (ItemFacts f : Samples.catalog().values()) {
            ItemClass c = ItemClassifier.classify(f);
            if (c.category() == ItemCategory.PETS || c.category() == ItemCategory.MASKS) {
                assertNull(c.tier(), f.name());
            }
        }
    }

    @Test
    void theServerIdAndTheRealValuesAreReadFromTheCustomData() {
        ItemFacts energy = Samples.catalog().get("cosmic energy|");
        assertEquals("cosmic_energy", energy.customId());
        assertTrue(energy.values().containsKey("custom_item_uuid"));
        ItemFacts satchel = Samples.catalog().get("diamond satchel|block");
        assertEquals("13200", satchel.values().get("max_cosmic_energy"), "a satchel states how much energy it holds");
        String key = ItemIdentity.of(satchel).key();
        assertTrue(key.contains("max_cosmic_energy=13200"), key);
        assertTrue(!key.contains("uuid") && !key.contains("auto_update"), key);
    }

    @Test
    void anAmountIsAQuantityNotAnIdentity() {
        ItemFacts few = Samples.facts("Cosmic Energy", "minecraft:light_blue_dye", "cosmic_energy", Map.of("amount", "5.07874299736976E8"));
        ItemFacts one = Samples.facts("Cosmic Energy", "minecraft:light_blue_dye", "cosmic_energy", Map.of("amount", "1.0"));
        assertEquals(id(few).key(), id(one).key());
    }
}
