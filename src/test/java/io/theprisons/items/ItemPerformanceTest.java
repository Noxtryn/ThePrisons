package io.theprisons.items;

import io.theprisons.items.market.AhAnalyzer;
import io.theprisons.items.market.MarketCache;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The budgets of the item platform, measured (printed with -i) and asserted generously so a slow CI machine does not flake. */
class ItemPerformanceTest {
    private static ItemRegistry big() {
        ItemRegistry r = ItemRegistry.seeded();
        for (int i = 0; i < 1500; i++) {
            r.learn(ItemFacts.ofName("minecraft:paper", "Synthetic Item " + i + " of the long list"), "minecraft:paper");
        }
        return r;
    }

    @Test
    void indexBuildSearchAndFilterStayFarBelowAFrame() {
        ItemRegistry r = big();
        assertTrue(r.size() > 1500);
        long t0 = System.nanoTime();
        ItemListModel m = new ItemListModel(r);
        m.view();                                              // builds the index once
        long build = System.nanoTime() - t0;
        long t1 = System.nanoTime();
        int rounds = 400;
        for (int i = 0; i < rounds; i++) {
            m.setQuery(i % 2 == 0 ? "synthetic item " + i : "godly");
            m.view();
        }
        double perChange = (System.nanoTime() - t1) / 1e6D / rounds;
        long t2 = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            m.view();                                          // frames without a change
        }
        double perFrame = (System.nanoTime() - t2) / 1e3D / 100_000;
        System.out.printf("PERF registry %d entries: index+first view %.1f ms, query change %.3f ms, unchanged frame %.3f us%n", r.size(), build / 1e6D, perChange, perFrame);
        assertTrue(build < 500_000_000L, "first build " + build / 1e6D + " ms");
        assertTrue(perChange < 10.0D, "a query change costs " + perChange + " ms");
        assertTrue(perFrame < 5.0D, "an unchanged frame costs " + perFrame + " us");
    }

    @Test
    void anAuctionPageIsAnalysedInMilliseconds() {
        MarketCache cache = new MarketCache();
        List<AhAnalyzer.ListingInput> page = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            page.add(new AhAnalyzer.ListingInput(i, ItemFacts.of("minecraft:paper", "Godly Shard", List.of("Price: $" + (1000 + i)), "shard", 1), 1000.0D + i * 7, 1 + i % 3));
        }
        AhAnalyzer.analyze(page, cache, 1_000_000L, 1L);       // warm up + fill the history
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 50; i++) {
            long t = System.nanoTime();
            AhAnalyzer.analyze(page, cache, 1_000_000L + i * 1000L, 1L + i);
            best = Math.min(best, System.nanoTime() - t);
        }
        System.out.printf("PERF AH page of 45 listings analysed in %.3f ms (best of 50)%n", best / 1e6D);
        assertTrue(best < 20_000_000L, "analysis " + best / 1e6D + " ms");
    }
}
