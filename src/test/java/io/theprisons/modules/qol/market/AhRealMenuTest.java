package io.theprisons.modules.qol.market;

import io.theprisons.items.ItemFacts;
import io.theprisons.items.market.AhAnalyzer;
import io.theprisons.items.market.AhSnapshot;
import io.theprisons.items.market.ListingMeta;
import io.theprisons.items.market.MarketCache;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The auction pipeline on the REAL menus of 2026-10-05 ({@link MenuDumps}): parse, normalise, cache, snapshot. */
class AhRealMenuTest {
    static List<AhAnalyzer.ListingInput> inputs(List<MarketParser.Item> items) {
        List<AhAnalyzer.ListingInput> out = new ArrayList<>();
        for (MarketParser.Item it : items) {
            double total = MarketParser.totalPrice(it);
            if (it.slot() >= MarketParser.LISTING_SLOTS || total <= 0) {
                continue;
            }
            ListingMeta meta = ListingMeta.parse(it.lore());
            out.add(new AhAnalyzer.ListingInput(it.slot(), ItemFacts.of(it.itemId(), it.name(), it.lore(), it.customId(), it.count()), total, it.count(),
                    meta.seller(), meta.expiresInMs()));
        }
        return out;
    }

    @Test
    void aRealMarketPageBecomesASnapshotWithAViewPerListing() {
        List<MarketParser.Item> items = MenuDumps.first(MarketParser.MARKET).items();
        List<AhAnalyzer.ListingInput> in = inputs(items);
        assertTrue(in.size() >= 5, "listings on the page: " + in.size());
        MarketCache cache = new MarketCache();
        AhSnapshot snap = AhAnalyzer.analyze(in, cache, 1_000_000L, 42L);
        assertEquals(in.size(), snap.parsed());
        assertEquals(in.size(), snap.slots().size());
        assertTrue(snap.computeNanos() < 50_000_000L, "analysis took " + snap.computeNanos() / 1000 + " us");
        for (AhAnalyzer.ListingInput l : in) {
            assertTrue(snap.slots().containsKey(l.slot()));
        }
        // the first time nothing is known: no invented ratings
        assertTrue(snap.slots().values().stream().allMatch(v -> v.analysis().rating() == io.theprisons.items.market.ListingAnalysis.Rating.UNKNOWN || v.analysis().samples() > 0));
    }

    @Test
    void theUnitPriceOfARealListingMatchesTheParser() {
        List<MarketParser.Item> items = MenuDumps.first(MarketParser.MARKET).items();
        for (MarketParser.Item it : items) {
            double total = MarketParser.totalPrice(it);
            if (it.slot() < MarketParser.LISTING_SLOTS && total > 0) {
                assertEquals(MarketParser.unitPrice(it), total / Math.max(1, it.count()), Math.max(1e-6, total * 1e-9) + 1e-9, "slot " + it.slot());
            }
        }
    }

    @Test
    void thePageOfSalesIsLearned() {
        List<MarketParser.Sale> sales = MarketParser.sales(MenuDumps.first(MarketParser.HISTORY).items());
        assertTrue(!sales.isEmpty());
        MarketCache cache = new MarketCache();
        List<AhAnalyzer.SaleInput> in = new ArrayList<>();
        for (MarketParser.Sale s : sales) {
            in.add(new AhAnalyzer.SaleInput(ItemFacts.of(s.icon(), s.name(), List.of(), s.customId(), 1), s.unitPrice(), 1, s.agoMs()));
        }
        assertTrue(AhAnalyzer.learnSales(in, cache, 10_000_000_000L) > 0);
        assertTrue(cache.keys() > 0);
    }

    @Test
    void theRealMenuGivesSellerAndExpiryAndTheyTellTwoIdenticalListingsApart() {
        List<MarketParser.Item> items = MenuDumps.first(MarketParser.MARKET).items();
        ListingMeta first = ListingMeta.parse(items.get(0).lore());
        ListingMeta second = ListingMeta.parse(items.get(1).lore());
        assertEquals("CookinTemp", first.seller());
        assertEquals(23L * 3_600_000L + 58L * 60_000L + 58_000L, first.expiresInMs(), "Expires: 23h 58m 58s");
        assertEquals(23L * 3_600_000L + 58L * 60_000L + 54_000L, second.expiresInMs());
        MarketCache cache = new MarketCache();
        long now = 5_000_000_000L;
        AhSnapshot a = AhAnalyzer.analyze(inputs(items), cache, now, 1L);
        // the two XP boosters (slots 0 and 1): same seller, same price, same amount - two real listings
        io.theprisons.items.ItemIdentity xp = io.theprisons.items.ItemIdentity.of(ItemFacts.of(items.get(0).itemId(), items.get(0).name(), items.get(0).lore(), items.get(0).customId(), 1));
        assertTrue(cache.history(xp.key()).size() >= 2, "both identical listings are kept");
        int firstTotal = cache.history(xp.key()).size();
        // the same menu opened again 3 s later: the countdown is lower, the absolute expiry is the same
        List<AhAnalyzer.ListingInput> later = new ArrayList<>();
        for (AhAnalyzer.ListingInput in : inputs(items)) {
            later.add(new AhAnalyzer.ListingInput(in.slot(), in.facts(), in.total(), in.amount(), in.seller(), Math.max(0L, in.expiresInMs() - 3_000L)));
        }
        AhSnapshot b = AhAnalyzer.analyze(later, cache, now + 3_000L, 2L);
        assertEquals(0, b.newObservations(), "reopening the menu adds no samples");
        assertEquals(firstTotal, cache.history(xp.key()).size());
        assertEquals(a.parsed(), b.parsed());
    }

    @Test
    void theRealHistoryGivesSellerBuyerAndAgeOfASale() {
        List<MarketParser.Item> items = MenuDumps.first(MarketParser.HISTORY).items();
        ListingMeta meta = ListingMeta.parse(items.get(1).lore());
        assertEquals("ImKoby", meta.seller());
        assertEquals("BandoBackpack", meta.buyer());
        assertEquals(2 * 60_000L, meta.soldAgoMs());
        ListingMeta anonymous = ListingMeta.parse(items.get(0).lore());
        assertEquals("???", anonymous.seller(), "an anonymous seller is printed as ???");
    }

    @Test
    void salesPageReopenedDoesNotInflateTheSamples() {
        List<MarketParser.Item> items = MenuDumps.first(MarketParser.HISTORY).items();
        List<AhAnalyzer.SaleInput> in = new ArrayList<>();
        for (MarketParser.Item it : items) {
            double total = MarketParser.totalPrice(it);
            if (total > 0 && it.slot() < MarketParser.LISTING_SLOTS) {
                ListingMeta m = ListingMeta.parse(it.lore());
                in.add(new AhAnalyzer.SaleInput(ItemFacts.of(it.itemId(), it.name(), it.lore(), it.customId(), 1), total, it.count(), m.soldAgoMs(), m.seller(), m.buyer()));
            }
        }
        assertTrue(in.size() > 10);
        MarketCache cache = new MarketCache();
        long now = 9_000_000_000L;
        int first = AhAnalyzer.learnSales(in, cache, now);
        List<AhAnalyzer.SaleInput> again = new ArrayList<>();
        for (AhAnalyzer.SaleInput s : in) {
            again.add(new AhAnalyzer.SaleInput(s.facts(), s.total(), s.amount(), s.agoMs() + 40_000L, s.seller(), s.buyer()));   // 40 s later: "sold 2m ago" has not changed
        }
        // the page shows the same minute, so the age text is the same while 40 s passed
        int second = AhAnalyzer.learnSales(in, cache, now + 40_000L);
        assertTrue(first > 10);
        assertEquals(0, second, "the same page again is not new data");
    }

    @Test
    void aSaleUsesTheQuantityOfItsLoreNotTheStackCount() {
        List<MarketParser.Item> items = MenuDumps.first(MarketParser.HISTORY).items();
        MarketParser.Item energy = items.get(1);       // "Price: $45,979.54 (19,999.796x)" on a single item stack
        AhAnalyzer.SaleInput sale = AhAnalyzer.saleOf(energy, ItemFacts.of(energy.itemId(), energy.name(), energy.lore(), energy.customId(), energy.count()));
        assertTrue(sale != null);
        assertEquals(45_979.54D / 19_999.796D, sale.total() / sale.amount(), 1e-9, "unit price per energy, not per stack");
        assertEquals("ImKoby", sale.seller());
        assertEquals("BandoBackpack", sale.buyer());
    }

    @Test
    void onlyASlotThatIsItselfASaleCounts() {
        List<MarketParser.Item> items = MenuDumps.first(MarketParser.HISTORY).items();
        int sales = 0;
        int others = 0;
        for (MarketParser.Item it : items) {
            AhAnalyzer.SaleInput s = AhAnalyzer.saleOf(it, ItemFacts.of(it.itemId(), it.name(), it.lore(), it.customId(), it.count()));
            boolean sold = it.lore().stream().anyMatch(l -> l.strip().startsWith("Item sold "));
            if (s != null) {
                sales++;
                assertTrue(sold, "slot " + it.slot() + " counted as a sale without an 'Item sold' line");
            } else if (!sold) {
                others++;
            }
        }
        assertTrue(sales > 10, "sales " + sales);
        assertTrue(others > 0, "the page's buttons and panes are not sales");
    }
}
