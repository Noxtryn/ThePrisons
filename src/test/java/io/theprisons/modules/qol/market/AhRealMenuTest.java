package io.theprisons.modules.qol.market;

import io.theprisons.items.ItemFacts;
import io.theprisons.items.market.AhAnalyzer;
import io.theprisons.items.market.AhSnapshot;
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
            out.add(new AhAnalyzer.ListingInput(it.slot(), ItemFacts.of(it.itemId(), it.name(), it.lore(), it.customId(), it.count()), total, it.count()));
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
}
