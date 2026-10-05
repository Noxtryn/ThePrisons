package io.theprisons.modules.qol.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopValueTest {
    private static PriceBook book() {
        PriceBook b = new PriceBook();
        b.seen("1", "Uncommon Cell Door Upgrade", "", 8e6, 0L, "ah", true);
        b.seen("2", "Godly Cell Door Upgrade", "", 40e6, 0L, "ah", true);
        b.seen("3", "Rabbit's Foot", "", 2e6, 0L, "ah", true);
        b.seen("4", "XP Booster (Right Click)", "", 10e6, 0L, "ah", true);
        b.seen("5", "Sentinel Mask", "", 10e6, 0L, "ah", true);
        b.seen("6", "Pirate Mask", "", 30e6, 0L, "ah", true);
        return b;
    }

    @Test
    void itemsRandomItemsAndStacks() {
        PriceBook b = book();
        assertEquals(24e6, ShopValue.item("Random Cell Door Upgrade", b), "average of every cell door upgrade");
        assertEquals(20e6, ShopValue.item("Random Mask", b));
        assertEquals(6e6, ShopValue.item("3x Rabbit's Foot", b));
        assertEquals(10e6, ShopValue.item("XP Booster", b), "with or without (Right Click)");
        assertEquals(-1D, ShopValue.item("Skill Token", b));
    }

    @Test
    void lootboxIsTheAverageLootTimesItsItems() {
        MarketParser.ShopOffer box = new MarketParser.ShopOffer("Ground Zero Shop II", "minecraft:chest", 2_000_000L,
                List.of("Rabbit's Foot", "XP Booster (Right Click)", "Skill Token"), 2);
        assertEquals(12e6, ShopValue.offer(box, book()), "(2m + 10m) / 2 known x 2 items");
        assertEquals(6.0D, ShopValue.perPoint(box, book()));
    }
}
