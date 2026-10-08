package io.theprisons.items.market;

import io.theprisons.items.ItemFacts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketTest {
    private static final long NOW = 1_000_000_000L;
    private static final long MIN = 60_000L;

    private static MarketObservation listing(String key, double total, int amount, long agoMs) {
        return MarketObservation.of(key, total, amount, NOW - agoMs, MarketObservation.Source.LISTING);
    }

    private static MarketObservation sale(String key, double total, int amount, long agoMs) {
        return MarketObservation.of(key, total, amount, NOW - agoMs, MarketObservation.Source.SALE);
    }

    private static List<MarketObservation> listings(double... units) {
        List<MarketObservation> out = new ArrayList<>();
        for (int i = 0; i < units.length; i++) {
            out.add(listing("k", units[i] * (i + 1), i + 1, (i + 1) * MIN));     // different totals / amounts: unit price is what counts
        }
        return out;
    }

    // ── unit price ───────────────────────────────────────────────────────────

    @Test
    void theUnitPriceIsTheTotalOverTheAmount() {
        MarketObservation o = listing("k", 2_400_000.0D, 16, 0);
        assertEquals(150_000.0D, o.unit(), 1e-6);
        assertEquals(1, listing("k", 5.0D, 0, 0).amount(), "an amount under 1 counts as 1");
    }

    // ── median / outliers ────────────────────────────────────────────────────

    @Test
    void theMedianMinAndMaxOfTheSamples() {
        MarketStats s = MarketStats.compute(listings(100, 120, 110, 130, 90), NOW, 0L);
        assertEquals(110.0D, s.median(), 1e-9);
        assertEquals(90.0D, s.low(), 1e-9);
        assertEquals(130.0D, s.high(), 1e-9);
        assertEquals(5, s.samples());
        assertEquals("listings", s.basis());
    }

    @Test
    void aScamPriceAndAMistypedPriceDoNotMoveTheMedian() {
        MarketStats s = MarketStats.compute(listings(100, 105, 95, 102, 98, 100, 1.0D, 100_000.0D), NOW, 0L);
        assertEquals(100.0D, s.median(), 3.0D, "the median stays at the real price");
        assertTrue(s.high() < 200.0D && s.low() > 50.0D, "min / max ignore the outliers: " + s.low() + ".." + s.high());
        assertTrue(s.why().get(0).contains("outliers"), s.why().toString());
    }

    @Test
    void withFewSamplesNothingIsThrownAway() {
        MarketStats s = MarketStats.compute(listings(100, 5000), NOW, 0L);
        assertEquals(2, s.samples());
    }

    @Test
    void salesBeatAskingPricesOnceThereAreThree() {
        List<MarketObservation> all = new ArrayList<>(listings(500, 520, 480, 510));
        all.add(sale("k", 100, 1, MIN));
        all.add(sale("k", 110, 1, 2 * MIN));
        MarketStats two = MarketStats.compute(all, NOW, 0L);
        assertEquals("listings", two.basis(), "two sales are not enough");
        all.add(sale("k", 105, 1, 3 * MIN));
        MarketStats three = MarketStats.compute(all, NOW, 0L);
        assertEquals("sales", three.basis());
        assertEquals(105.0D, three.median(), 1e-9);
    }

    @Test
    void theListingBeingJudgedIsLeftOutOfItsOwnBaseline() {
        List<MarketObservation> all = listings(100, 100, 100, 100);
        MarketObservation cheap = listing("k", 50, 1, 0);
        all.add(cheap);
        assertEquals(100.0D, MarketStats.compute(all, NOW, cheap.signature()).median(), 1e-9);
        assertTrue(MarketStats.compute(all, NOW, 0L).median() <= 100.0D);
    }

    // ── confidence ───────────────────────────────────────────────────────────

    @Test
    void noSamplesMeanNoConfidence() {
        assertEquals(MarketConfidence.NONE, MarketStats.compute(List.of(), NOW, 0L).confidence());
        assertFalse(MarketStats.compute(List.of(), NOW, 0L).known());
        assertEquals(MarketConfidence.NONE, MarketStats.compute(List.of(listing("k", 100, 1, MIN)), NOW, 0L).confidence(), "one sample is no estimate");
    }

    @Test
    void twoSamplesAreAtMostLowEvenWhenFreshAndEqual() {
        MarketStats s = MarketStats.compute(List.of(listing("k", 100, 1, MIN), listing("k", 101, 1, 2 * MIN)), NOW, 0L);
        assertEquals(MarketConfidence.LOW, s.confidence());
    }

    @Test
    void manyFreshConsistentSamplesAreHigh() {
        List<MarketObservation> fresh = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            fresh.add(listing("k", 100.0D + (i % 3), 1, (i + 1) * MIN));
        }
        MarketStats s = MarketStats.compute(fresh, NOW, 0L);
        assertEquals(MarketConfidence.HIGH, s.confidence(), s.why() + " score " + s.score());
    }

    @Test
    void oldSamplesLoseConfidence() {
        List<MarketObservation> old = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            old.add(listing("k", 100.0D + (i % 3), 1, 3 * 24 * 3_600_000L + i * MIN));
        }
        MarketStats fresh = MarketStats.compute(freshened(old), NOW, 0L);
        MarketStats stale = MarketStats.compute(old, NOW, 0L);
        assertTrue(stale.score() < fresh.score(), "stale " + stale.score() + " fresh " + fresh.score());
        assertTrue(stale.confidence().ordinal() < MarketConfidence.HIGH.ordinal());
        assertTrue(stale.why().stream().anyMatch(w -> w.contains("d old")), stale.why().toString());
    }

    private static List<MarketObservation> freshened(List<MarketObservation> in) {
        List<MarketObservation> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            out.add(listing("k", in.get(i).total(), in.get(i).amount(), (i + 1) * MIN));
        }
        return out;
    }

    @Test
    void aWideSpreadLowersTheConfidence() {
        List<MarketObservation> tight = new ArrayList<>();
        List<MarketObservation> wide = new ArrayList<>();
        double[] spread = {60, 140, 80, 120, 100, 70, 130, 90, 110, 100};
        for (int i = 0; i < spread.length; i++) {
            tight.add(listing("k", 100.0D + (i % 2), 1, (i + 1) * MIN));
            wide.add(listing("k", spread[i], 1, (i + 1) * MIN));
        }
        assertTrue(MarketStats.compute(tight, NOW, 0L).score() > MarketStats.compute(wide, NOW, 0L).score());
    }

    @Test
    void confidenceIsExplained() {
        MarketStats s = MarketStats.compute(listings(100, 101, 99, 100, 102), NOW, 0L);
        assertEquals(3, s.why().size());
        assertTrue(s.why().get(0).contains("5 samples"));
        assertTrue(s.why().get(1).startsWith("newest"));
        assertTrue(s.why().get(2).startsWith("spread"));
    }

    // ── history + cache ──────────────────────────────────────────────────────

    @Test
    void seeingTheSameListingAgainRefreshesItInsteadOfCountingItTwice() {
        MarketHistory h = new MarketHistory();
        assertTrue(h.observe(listing("k", 100, 1, 10 * MIN)));
        assertFalse(h.observe(listing("k", 100, 1, 0)));
        assertEquals(1, h.size());
        assertEquals(NOW, h.all().get(0).timestampMs(), "the newer sighting replaced the older time");
    }

    @Test
    void theHistoryIsBounded() {
        MarketHistory h = new MarketHistory();
        for (int i = 0; i < MarketHistory.MAX + 30; i++) {
            h.observe(listing("k", 100 + i, 1, i * MIN));
        }
        assertEquals(MarketHistory.MAX, h.size());
    }

    @Test
    void theCacheIsBoundedAndCachesStatsPerVersion() {
        MarketCache cache = new MarketCache();
        for (int i = 0; i < MarketCache.MAX_KEYS + 100; i++) {
            cache.observe("c" + i, listing("key" + i, 100, 1, 0));
        }
        assertEquals(MarketCache.MAX_KEYS, cache.keys());
        assertTrue(cache.evicted() >= 100);
        MarketCache c2 = new MarketCache();
        for (double u : new double[]{100, 101, 99, 100}) {
            c2.observe("cat", listing("k", u, 1, (long) (u * MIN)));
        }
        MarketStats a = c2.stats("k", NOW, 0L);
        MarketStats b = c2.stats("k", NOW + 10_000L, 0L);
        assertTrue(a == b, "the same version and minute: the very same object");
        assertEquals(1, c2.cacheMisses());
        assertEquals(1, c2.cacheHits());
        c2.observe("cat", listing("k", 103, 1, 0));
        c2.stats("k", NOW, 0L);
        assertEquals(2, c2.cacheMisses(), "a new observation invalidates");
    }

    @Test
    void theCatalogStatsSpeakForTheBestDocumentedVariantOfAnEntry() {
        MarketCache cache = new MarketCache();
        for (int i = 0; i < 4; i++) {
            cache.observe("godly shard|", listing("godly shard|", 100 + i, 1, (i + 1) * MIN));
            cache.observe("godly shard|", listing("godly shard|@shard#shard_tier=godly;", 104 + i, 1, (i + 1) * MIN));
        }
        assertEquals(4, cache.statsForCatalog("godly shard|", NOW).samples(), "one variant speaks for the entry, the variants are not mixed");
        for (int i = 4; i < 7; i++) {
            cache.observe("godly shard|", listing("godly shard|@shard#shard_tier=godly;", 110 + i, 1, (i + 1) * MIN));
        }
        assertEquals(7, cache.statsForCatalog("godly shard|", NOW).samples(), "the variant with more evidence wins");
        assertEquals(MarketStats.EMPTY, cache.statsForCatalog("nothing|", NOW));
    }

    // ── analysis ─────────────────────────────────────────────────────────────

    private static ItemFacts shard() {
        return Samples2.facts("Godly Shard");
    }

    private static MarketCache seeded(double unit, int n) {
        MarketCache cache = new MarketCache();
        String key = io.theprisons.items.ItemIdentity.of(shard()).key();
        String cat = io.theprisons.items.ItemIdentity.of(shard()).catalogKey();
        for (int i = 0; i < n; i++) {
            cache.observe(cat, MarketObservation.of(key, unit * 10 + i, 10, NOW - (i + 1) * MIN, MarketObservation.Source.LISTING));
        }
        return cache;
    }

    @Test
    void aCheapListingOnGoodDataIsGreatAndANormalOneIsFair() {
        MarketCache cache = seeded(150_000, 14);
        AhSnapshot snap = AhAnalyzer.analyze(List.of(new AhAnalyzer.ListingInput(0, shard(), 1_000_000.0D, 10), new AhAnalyzer.ListingInput(1, shard(), 1_500_000.0D, 10),
                new AhAnalyzer.ListingInput(2, shard(), 2_400_000.0D, 10), new AhAnalyzer.ListingInput(3, shard(), 1_200_000.0D, 10)), cache, NOW, 1L);
        assertEquals(ListingAnalysis.Rating.GREAT, snap.slots().get(0).analysis().rating(), "-33 % on good data");
        assertEquals(ListingAnalysis.Rating.FAIR, snap.slots().get(1).analysis().rating());
        assertEquals(ListingAnalysis.Rating.OVERPRICED, snap.slots().get(2).analysis().rating());
        assertEquals(ListingAnalysis.Rating.GOOD, snap.slots().get(3).analysis().rating(), "-20 % is good, not great");
        assertEquals(0, snap.slots().get(1).borderArgb(), "a fair price gets no border: the texture stays visible");
        assertTrue(snap.slots().get(0).badge().startsWith("-"), snap.slots().get(0).badge());
    }

    @Test
    void aBigDiscountOnThinDataIsNeverGreat() {
        MarketCache cache = seeded(150_000, 2);
        AhSnapshot snap = AhAnalyzer.analyze(List.of(new AhAnalyzer.ListingInput(0, shard(), 600_000.0D, 10)), cache, NOW, 1L);
        ListingAnalysis a = snap.slots().get(0).analysis();
        assertTrue(a.confidence().ordinal() <= MarketConfidence.LOW.ordinal() + 0, "confidence " + a.confidence());
        assertNotEquals(ListingAnalysis.Rating.GREAT, a.rating());
    }

    @Test
    void anItemNobodyHasSeenIsUnknownNotGuessed() {
        AhSnapshot snap = AhAnalyzer.analyze(List.of(new AhAnalyzer.ListingInput(3, Samples2.facts("Totally Unseen Thing"), 5000.0D, 1)), new MarketCache(), NOW, 1L);
        ListingAnalysis a = snap.slots().get(3).analysis();
        assertEquals(ListingAnalysis.Rating.UNKNOWN, a.rating());
        assertTrue(Double.isNaN(a.estimatedUnit()));
        assertEquals(0, snap.slots().get(3).borderArgb());
        assertTrue(snap.slots().get(3).hover(NOW).stream().anyMatch(l -> l.contains("unknown")));
    }

    @Test
    void reanalysingTheSamePageAddsNoNewSamples() {
        MarketCache cache = new MarketCache();
        List<AhAnalyzer.ListingInput> page = List.of(new AhAnalyzer.ListingInput(0, shard(), 1_500_000.0D, 10), new AhAnalyzer.ListingInput(1, shard(), 1_600_000.0D, 10));
        AhSnapshot first = AhAnalyzer.analyze(page, cache, NOW, 1L);
        AhSnapshot second = AhAnalyzer.analyze(page, cache, NOW + 5000L, 1L);
        assertEquals(2, first.newObservations());
        assertEquals(0, second.newObservations());
        assertEquals(2, cache.history(io.theprisons.items.ItemIdentity.of(shard()).key()).size());
    }

    @Test
    void theHoverBlockHasTheFieldsOfTheSpec() {
        MarketCache cache = seeded(150_000, 14);
        AhSnapshot snap = AhAnalyzer.analyze(List.of(new AhAnalyzer.ListingInput(0, shard(), 1_200_000.0D, 10)), cache, NOW, 1L);
        List<String> hover = snap.slots().get(0).hover(NOW);
        assertEquals("MARKET", hover.get(0));
        for (String field : List.of("Listed", "Fair", "Difference", "Unit", "Samples", "Basis", "Confidence", "Last Seen")) {
            assertTrue(hover.stream().anyMatch(l -> l.startsWith(field)), field + " in " + hover);
        }
        assertTrue(hover.stream().anyMatch(l -> l.startsWith("Listed") && l.contains("$1.20M")), hover.toString());
        assertTrue(hover.stream().anyMatch(l -> l.startsWith("Fair") && l.contains("$1.50M")), hover.toString());
        assertTrue(hover.stream().anyMatch(l -> l.startsWith("Unit") && l.contains("$120K")), hover.toString());
        assertTrue(hover.stream().anyMatch(l -> l.startsWith("Difference") && l.contains("-20.0%")), hover.toString());
    }

    @Test
    void salesPagesOnlyLearn() {
        MarketCache cache = new MarketCache();
        int n = AhAnalyzer.learnSales(List.of(new AhAnalyzer.SaleInput(shard(), 1_500_000.0D, 10, 3 * MIN), new AhAnalyzer.SaleInput(shard(), 1_450_000.0D, 10, 9 * MIN)), cache, NOW);
        assertEquals(2, n);
        assertEquals(0, AhAnalyzer.learnSales(List.of(new AhAnalyzer.SaleInput(shard(), 1_500_000.0D, 10, 4 * MIN)), cache, NOW + MIN), "the same sale again (page refreshed)");
    }

    @Test
    void theMarketMemorySurvivesAFileRoundTripAndDropsOldSamples() throws Exception {
        MarketCache cache = new MarketCache();
        String key = io.theprisons.items.ItemIdentity.of(shard()).key();
        String cat = io.theprisons.items.ItemIdentity.of(shard()).catalogKey();
        for (int i = 0; i < 5; i++) {
            cache.observe(cat, MarketObservation.of(key, 1000 + i, 10, NOW - i * MIN, MarketObservation.Source.LISTING));
        }
        cache.observe(cat, MarketObservation.of(key, 7777, 10, NOW - MarketStore.MAX_AGE_MS - MIN, MarketObservation.Source.LISTING));
        java.nio.file.Path file = java.nio.file.Files.createTempFile("market", ".json");
        MarketStore.save(file, cache, NOW);
        MarketCache loaded = new MarketCache();
        MarketStore.load(file, loaded, NOW);
        assertEquals(5, loaded.history(key).size(), "the day-old samples are back, the two-week-old one is not");
        assertEquals(5, loaded.statsForCatalog(cat, NOW).samples());
        java.nio.file.Files.writeString(file, "{ this is not json");
        MarketCache broken = new MarketCache();
        MarketStore.load(file, broken, NOW);
        assertEquals(0, broken.keys(), "a broken file is an empty memory");
    }

    private static final class Samples2 {
        static ItemFacts facts(String name) {
            return ItemFacts.of("minecraft:prismarine_shard", name, List.of(), null, 1);
        }
    }
}
