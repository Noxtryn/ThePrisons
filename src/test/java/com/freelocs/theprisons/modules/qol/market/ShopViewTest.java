package com.freelocs.theprisons.modules.qol.market;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The /gz and /pb overlays on the real menus recorded in the game (2026-10-05), and the one-Esc fix of the item list. */
class ShopViewTest {
    private static PriceBook book() {
        PriceBook book = new PriceBook();
        long now = 1_000_000L;
        // A few real prices: the Rare Candy is worth a lot per point, the lootboxes' loot a little.
        book.seen(PriceBook.key(null, "II Rare Candy II"), "II Rare Candy II", "Other/Other", 2_000_000.0D, now, "ah", true);
        book.seen(PriceBook.key(null, "Rabbit's Foot"), "Rabbit's Foot", "Other/Other", 100_000.0D, now, "ah", true);
        book.seen(PriceBook.key(null, "Lucky Charm"), "Lucky Charm", "Other/Other", 150_000.0D, now, "ah", true);
        return book;
    }

    @Test
    void handlesTheEventMenusAndTheirShops() {
        assertTrue(ShopView.handles("Ground Zero"));
        assertTrue(ShopView.handles("Prison Break"));
        assertTrue(ShopView.handles("Ground Zero Shop"));
        assertTrue(ShopView.handles("Prison Break Shop"));
        assertFalse(ShopView.handles("Market"));
        assertFalse(ShopView.handles("Tinkerer"));
        assertTrue(ShopView.isShop("Prison Break Shop"));
        assertFalse(ShopView.isShop("Prison Break"));
    }

    @Test
    void groundZeroShopShowsPointsResetClockAndWhatOffersAreWorth() {
        MenuDumps.Page page = MenuDumps.first("Ground Zero Shop");
        ShopView.Model m = ShopView.build(page.title(), page.items(), book());
        assertTrue(m.shop());
        assertEquals("Points: 0/10,000,000", m.balance());
        assertEquals((42 * 60 + 15) * 1000L, m.resetMs());
        ShopView.Entry candy = m.bySlot().get(22);
        assertEquals("II Rare Candy II", candy.name());
        assertEquals(500_000L, candy.points());
        assertEquals("1 Available", candy.stock());
        assertEquals(2_000_000.0D, candy.worth(), 1e-6);
        assertEquals(4.0D, candy.perPoint(), 1e-9, "$2M for 500k points");
        assertTrue(candy.best());
        assertFalse(m.bestName().isEmpty(), "the best offer by money per point is named");
        // An unlimited lootbox is listed with its stock, and an offer nothing is known about has no worth but is listed.
        assertEquals("Unlimited", m.bySlot().get(12).stock());
        assertEquals(-1.0D, m.bySlot().get(24).worth(), 1e-9);
        assertFalse(m.bySlot().get(24).best());
    }

    @Test
    void theHubPagesShowTheirPointsAndNoOffers() {
        MenuDumps.Page gz = MenuDumps.first("Ground Zero");
        ShopView.Model m = ShopView.build(gz.title(), gz.items(), book());
        assertFalse(m.shop());
        assertEquals("Points: 0/10,000,000", m.balance());
        assertTrue(m.bySlot().isEmpty());
        MenuDumps.Page pb = MenuDumps.first("Prison Break");
        assertEquals("Points: 0/5,000,000", ShopView.build(pb.title(), pb.items(), book()).balance());
    }

    @Test
    void decorationPanesAreHidden() {
        assertTrue(ShopView.decoration("minecraft:black_stained_glass_pane", ""));
        assertFalse(ShopView.decoration("minecraft:black_stained_glass_pane", "Info"));
        assertFalse(ShopView.decoration("minecraft:sunflower", ""));
    }

    @Test
    void resetClockReadsLikeTheServers() {
        assertEquals("42m 15s", ShopView.clock((42 * 60 + 15) * 1000L));
        assertEquals("1h 05m", ShopView.clock(65 * 60_000L));
        assertEquals("0m 00s", ShopView.clock(-5L));
    }

    @Test
    void snapshotIsIndependentOfTheBookItWasTakenFrom() {
        PriceBook live = book();
        PriceBook copy = live.snapshot();
        int size = copy.all().size();
        live.seen(PriceBook.key(null, "Absorber"), "Absorber", "Other/Other", 5_000.0D, 2_000_000L, "ah", true);
        assertEquals(size, copy.all().size(), "later changes do not reach the copy");
        assertEquals(live.version() - 1, copy.version());
    }

    @Test
    void oneEscClosesTheItemListAndTheInventory() {
        MarketSearch.charTyped('x');
        assertTrue(MarketSearch.open());
        // Not consumed: the game closes the inventory with the same key press, and the list is reset.
        assertFalse(MarketSearch.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, false));
        assertFalse(MarketSearch.open());
        assertEquals("", MarketSearch.query());
    }
}
