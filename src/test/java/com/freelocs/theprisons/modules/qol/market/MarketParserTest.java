package com.freelocs.theprisons.modules.qol.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** On the real menus of 2026-10-05 ({@link MenuDumps}). */
class MarketParserTest {
    @Test
    void marketListingsWithThePricePerItem() {
        List<MarketParser.Listing> l = MarketParser.listings(MenuDumps.first(MarketParser.MARKET).items());
        assertEquals(10_000_000D, l.get(0).unitPrice());
        assertEquals("booster", l.get(0).customId());
        assertEquals("Legendary Dust (10%)", l.get(2).name());
        assertEquals(125_000D, l.get(2).unitPrice(), "$8,000,000 for 64: the per-item price");
        assertTrue(l.size() <= 45);
    }

    @Test
    void categoriesGiveEveryKindWithItsLowestPrice() {
        List<MarketParser.Kind> k = MarketParser.kinds(MenuDumps.first(MarketParser.CATEGORIES).items());
        MarketParser.Kind door = k.get(0);
        assertEquals("Cell Door Upgrade", door.name());
        assertEquals(8_000_000D, door.lowest());
        assertEquals(15, door.listings());
        assertEquals("Uncommon Cell Door Upgrade", door.contains().get(0));
        assertTrue(k.size() > 30, "a full page of kinds: " + k.size());
        assertEquals(50, MarketParser.nextPageSlot(MenuDumps.first(MarketParser.CATEGORIES).items()));
    }

    @Test
    void historyGivesRealSalesWithTheirTime() {
        List<MarketParser.Sale> s = MarketParser.sales(MenuDumps.first(MarketParser.HISTORY).items());
        MarketParser.Sale sleeve = s.stream().filter(x -> x.name().startsWith("Cosmo-Slot")).findFirst().orElseThrow();
        assertEquals(4_000_000D, sleeve.unitPrice());
        assertEquals(120_000L, sleeve.agoMs());
        assertTrue(s.stream().anyMatch(x -> x.name().equals("Cosmic Energy") && Math.abs(x.unitPrice() - 2.3D) < 1e-9),
                "$9,200,000 for 4,000,000 energy");
    }

    @Test
    void energyRateIsTheCheapestOffer() {
        assertEquals(2.299D, MarketParser.energyRate(MenuDumps.first(MarketParser.ENERGY).items()), 1e-9);
    }

    @Test
    void shopsWithPointsLootAndTheResetClock() {
        MenuDumps.Page gz = MenuDumps.first("Ground Zero Shop");
        List<MarketParser.ShopOffer> offers = MarketParser.shop(gz.items());
        MarketParser.ShopOffer box = offers.stream().filter(o -> o.name().equals("Ground Zero Shop I")).findFirst().orElseThrow();
        assertEquals(1_000_000L, box.points());
        assertEquals(1, box.picks());
        assertEquals(2, offers.stream().filter(o -> o.name().equals("Ground Zero Shop II")).findFirst().orElseThrow().picks());
        assertTrue(box.loot().contains("Random Cell Door Upgrade"));
        assertTrue(offers.stream().anyMatch(o -> o.name().equals("Random Tool Prestige Token I-III") && o.points() == 500_000L));
        assertEquals((42 * 60 + 15) * 1000L, MarketParser.shopResetMs(gz.items()));
        MenuDumps.Page pb = MenuDumps.first("Prison Break Shop");
        assertTrue(MarketParser.shop(pb.items()).stream().anyMatch(o -> o.loot().contains("Weekly Megabox")));
    }
}
