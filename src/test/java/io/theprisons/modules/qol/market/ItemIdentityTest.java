package io.theprisons.modules.qol.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemIdentityTest {
    private static ItemIdentity.Id id(String name) {
        return ItemIdentity.of(name, null);
    }

    @Test
    void theSameWareIsOneItem() {
        assertEquals(id("Gold Satchel").key(), id("Gold Satchel (0 / 2,304 Ores)").key());
        assertEquals(id("Gold Satchel").key(), id("Gold Satchel (57 / 2,304 Ores)").key());
        assertEquals(id("Iron Boots").key(), id("Iron Boots 0").key());
        assertEquals("Iron Boots", id("Iron Boots 0").name());
        assertEquals(id("Legendary Dust (10%)").key(), id("Legendary Dust (45%)").key());
        assertEquals("Rare Candy", id("II Rare Candy II").family());
        assertEquals("Godly", id("II Rare Candy II").rarity());
    }

    @Test
    void rarityIsSplitFromTheFamily() {
        ItemIdentity.Id godly = id("Godly Contraband");
        assertEquals("Contraband", godly.family());
        assertEquals("Godly", godly.rarity());
        assertNotEquals(godly.key(), id("Elite Contraband").key());
        assertEquals(6, godly.rank());
        assertEquals(-1, id("Wormhole Powerup (Double Tap)").rank());
        assertEquals("Wormhole Powerup (Double Tap)", id("Wormhole Powerup (Double Tap)").family());
    }

    @Test
    void notItemsAreDropped() {
        assertNull(id("Random Cell Door Upgrade"));
        assertNull(id("xx Random G-Kit Beacon xx"));
        assertNull(id("*** COSMIC CRATE: Aether #1 ***"));
        assertNull(id("Iron Boots 17"));
        assertNull(id("Gold Satchel 14 (57 / 32,256 Ores)"));
        assertNull(id("Sludge Sword 9"));
        assertNull(id("Iron Pickaxe 100 V"));
        assertNull(id("Stone Pickaxe 38 II"));
        assertNull(id("Iron Pickaxe III"));
        assertEquals("Diamond Pickaxe", id("Diamond Pickaxe").name());
    }

    @Test
    void satchelsOfOneOreAreOneFamilyInOrder() {
        assertEquals("Gold Satchel", id("Gold Satchel").family());
        assertEquals("Gold Satchel", id("Gold Ore Satchel (0 / 2,304 Ores)").family());
        assertEquals("Gold Satchel", id("Deepslate Gold Ore Satchel").family());
        assertEquals("Gold Satchel", id("Block of Gold Satchel (0 / 2,304 Ores)").family());
        assertTrue(id("Gold Satchel").rank() < id("Gold Ore Satchel").rank());
        assertTrue(id("Gold Ore Satchel").rank() < id("Deepslate Gold Ore Satchel").rank());
        assertTrue(id("Deepslate Gold Ore Satchel").rank() < id("Block of Gold Satchel").rank());
        assertEquals("Deepslate Gold Ore Satchel", id("Deepslate Gold Ore Satchel").name());
        assertNotEquals(id("Gold Satchel").family(), id("Iron Satchel").family());
        assertEquals("Shard Satchel (0 / 640 Drops)".replaceAll("\\s*\\(.*\\)", ""), id("Shard Satchel (0 / 640 Drops)").name());
    }

    @Test
    void gKitsAreOneFamilyInTheMenusOrder() {
        assertEquals("G-Kit", id("Cowboy G-Kit").family());
        assertEquals("G-Kit", id("Starforged G-Kit").family());
        assertEquals("G-Kit Level Up", id("Cowboy G-Kit Level Up").family());
        assertTrue(id("Astronaut G-Kit").rank() < id("Starforged G-Kit").rank());
        ItemIdentity.setGkitOrder(java.util.List.of("Starforged", "Astronaut"));
        try {
            assertTrue(id("Starforged G-Kit").rank() < id("Astronaut G-Kit").rank());
        } finally {
            ItemIdentity.setGkitOrder(ItemIdentity.GKIT_DEFAULT);
        }
    }

    @Test
    void executiveIsTheLastRarityAndPickaxeDustIsDust() {
        assertEquals("Executive", id("Executive Shard").rarity());
        assertEquals("Shard", id("Executive Shard").family());
        assertTrue(id("Executive Shard").rank() > id("Godly Shard").rank());
        assertEquals(id("Legendary Dust (10%)").key(), id("Legendary Pickaxe Dust (10%)").key());
        assertEquals("Dust", id("Pickaxe Dust").family());
    }
}
