package io.theprisons.items.market;

import io.theprisons.items.ItemFacts;
import io.theprisons.items.ItemIdentity;
import io.theprisons.items.ItemRegistry;
import io.theprisons.items.Samples;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The price engine on the situations of a real market: moving prices, old regimes, typos, scams, thin data, duplicate sightings and variants. */
class MarketV2Test {
    private static final long NOW = 2_000_000_000L;
    private static final long MIN = 60_000L;
    private static final long H = 3_600_000L;
    private static final long D = 24 * H;

    private static MarketObservation sale(double unit, long ago) {
        return MarketObservation.of("k", unit, 1, NOW - ago, MarketObservation.Source.SALE, "s" + ago, "b", 0L);
    }

    private static MarketObservation listing(double unit, long ago) {
        return MarketObservation.of("k", unit, 1, NOW - ago, MarketObservation.Source.LISTING, "s" + ago, null, NOW - ago + 10 * H);
    }

    private static List<MarketObservation> sales(double[] units, long firstAgo, long step) {
        List<MarketObservation> out = new ArrayList<>();
        for (int i = 0; i < units.length; i++) {
            out.add(sale(units[i], firstAgo + i * step));
        }
        return out;
    }

    private static double[] around(double centre, int n) {
        double[] u = new double[n];
        for (int i = 0; i < n; i++) {
            u[i] = centre + (i % 3 - 1);          // centre-1, centre, centre+1 ...
        }
        return u;
    }

    private static MarketStats stats(List<MarketObservation> all) {
        return MarketStats.compute(all, NOW, 0L);
    }

    @Test
    void detailLinesExposeTheRealSourceConfidenceAndObservationAge() {
        MarketStats s = stats(sales(around(100, 8), 2 * 60_000L, 60_000L));
        List<String> lines = ItemPrices.detailLines(s, NOW);
        assertTrue(lines.getFirst().contains("Fair") && lines.getFirst().contains(s.confidence().label()));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Source  Sales · 24h sales")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Updated ")));
    }

    // 1. stable
    @Test
    void aStableMarketGivesAHighConfidenceFairPriceAndAStableTrend() {
        MarketStats s = stats(sales(around(100, 16), 10 * MIN, 60 * MIN));
        assertEquals(100.0D, s.fair(), 1.0D);
        assertEquals(MarketConfidence.HIGH, s.confidence(), s.why() + " score " + s.score());
        assertEquals(MarketStats.Trend.STABLE, s.trend());
        assertEquals("24h sales", s.window());
        assertTrue(!Double.isNaN(s.quickSell()) && s.quickSell() <= s.fair());
    }

    // 2. / 3. moves
    @Test
    void aPriceThatMovedUpRecentlyIsFollowedAndTheTrendIsRising() {
        List<MarketObservation> all = new ArrayList<>(sales(around(100, 14), 3 * D, 4 * H));      // 3 to 5 days ago
        all.addAll(sales(around(130, 8), 30 * MIN, 90 * MIN));                                     // the last day
        MarketStats s = stats(all);
        assertEquals(130.0D, s.fair(), 2.0D, "the last 24 h decide");
        assertEquals(MarketStats.Trend.RISING, s.trend(), "24h " + s.fair24h() + " vs 7d " + s.fair7d());
        assertTrue(s.trendPercent() > 0.15D);
    }

    @Test
    void aPriceThatMovedDownRecentlyIsFollowedAndTheTrendIsFalling() {
        List<MarketObservation> all = new ArrayList<>(sales(around(200, 14), 3 * D, 4 * H));
        all.addAll(sales(around(150, 8), 30 * MIN, 90 * MIN));
        MarketStats s = stats(all);
        assertEquals(150.0D, s.fair(), 2.0D);
        assertEquals(MarketStats.Trend.FALLING, s.trend());
        assertTrue(s.trendPercent() < -0.15D);
    }

    // 4. old history vs a fresh market
    @Test
    void oldHistoryDoesNotControlAFreshMarket() {
        List<MarketObservation> all = new ArrayList<>(sales(around(100, 30), 9 * D, 3 * H));      // 9+ days old: the old regime
        all.addAll(sales(around(200, 4), 20 * MIN, 30 * MIN));                                     // four fresh sales
        MarketStats s = stats(all);
        assertEquals(200.0D, s.fair(), 2.0D);
        assertEquals("24h sales", s.window());
    }

    @Test
    void withoutFreshSalesTheRecentListingsDecideNotTheOldSales() {
        List<MarketObservation> all = new ArrayList<>(sales(around(100, 12), 9 * D, 2 * H));      // sales older than 7 days
        for (double u : new double[]{200, 202, 198, 201}) {
            all.add(listing(u, 2 * H));
        }
        MarketStats s = stats(all);
        assertEquals("listings", s.basis());
        assertEquals(200.0D, s.fair(), 3.0D);
    }

    // 11. / 12. sales beat listings, stale sales fall back
    @Test
    void salesBeatListingsAndThreeDayOldSalesStillBeatFreshListings() {
        List<MarketObservation> all = new ArrayList<>(sales(new double[]{120, 121, 119}, 5 * H, H));
        for (int i = 0; i < 10; i++) {
            all.add(listing(200 + i % 3, 30 * MIN));
        }
        MarketStats s = stats(all);
        assertEquals("sales", s.basis());
        assertEquals(120.0D, s.fair(), 1.5D);
        List<MarketObservation> threeDays = new ArrayList<>(sales(new double[]{120, 121, 119}, 3 * D, H));
        threeDays.addAll(all.subList(3, all.size()));
        assertEquals("7d sales", stats(threeDays).window());
    }

    @Test
    void staleSalesFallBackToTheCurrentListings() {
        List<MarketObservation> all = new ArrayList<>(sales(new double[]{50, 51, 49, 50}, 8 * D, H));
        for (int i = 0; i < 6; i++) {
            all.add(listing(300 + i % 2, 20 * MIN + i * MIN));
        }
        MarketStats s = stats(all);
        assertEquals("listings", s.basis());
        assertEquals(300.0D, s.fair(), 2.0D);
    }

    // 8. / 9. typo and scam
    @Test
    void aLowTypoAndAHighScamDoNotMoveTheFairPrice() {
        List<MarketObservation> base = sales(new double[]{100, 102, 98, 105, 101}, 2 * H, 30 * MIN);
        double clean = stats(base).fair();
        List<MarketObservation> typo = new ArrayList<>(base);
        typo.add(sale(1.0D, H));
        List<MarketObservation> scam = new ArrayList<>(base);
        scam.add(sale(100_000.0D, H));
        List<MarketObservation> both = new ArrayList<>(typo);
        both.add(sale(100_000.0D, 90 * MIN));
        assertEquals(clean, stats(typo).fair(), 2.0D);
        assertEquals(clean, stats(scam).fair(), 2.0D);
        assertEquals(clean, stats(both).fair(), 3.0D);
        assertTrue(stats(both).low() > 90.0D && stats(both).high() < 110.0D, "range " + stats(both).low() + ".." + stats(both).high());
    }

    @Test
    void aSingleExtremeSampleOfThreeDoesNotDefineTheFairPrice() {
        MarketStats s = stats(sales(new double[]{100, 101, 1}, H, 10 * MIN));
        assertEquals(100.5D, s.fair(), 1.0D, "the typo of three is dropped, not averaged in");
        assertEquals(2, s.samples());
    }

    // 7. a real cheap listing is still a bargain (and the typo is highlighted, flagged)
    @Test
    void aRealCheapListingIsHighlightedAsABargainWithoutSpoilingItsOwnBaseline() {
        MarketCache cache = new MarketCache();
        String key = "item|x";
        for (int i = 0; i < 10; i++) {
            cache.observe("item|", MarketObservation.of(key, 100 + i % 3, 1, NOW - (i + 1) * 20 * MIN, MarketObservation.Source.SALE, "s" + i, "b", 0L));
        }
        MarketObservation cheap = MarketObservation.of(key, 60.0D, 1, NOW, MarketObservation.Source.LISTING, "cheapseller", null, NOW + 20 * H);
        cache.observe("item|", cheap);
        MarketStats base = cache.stats(key, NOW, cheap);
        assertEquals(101.0D, base.fair(), 1.5D, "the cheap listing is not in its own baseline");
        ListingAnalysis a = ListingAnalysis.of(0, cheap, base);
        assertEquals(ListingAnalysis.Rating.GREAT, a.rating());
        assertFalse(a.suspicious());
        MarketObservation typo = MarketObservation.of(key, 1.0D, 1, NOW, MarketObservation.Source.LISTING, "typo", null, NOW + 20 * H);
        cache.observe("item|", typo);
        ListingAnalysis t = ListingAnalysis.of(1, typo, cache.stats(key, NOW, typo));
        assertEquals(ListingAnalysis.Rating.GREAT, t.rating(), "a real bargain is still shown");
        assertTrue(t.suspicious(), "but flagged: check the item");
        assertTrue(SlotView.of(t).hover(NOW).stream().anyMatch(l -> l.contains("check the item")));
    }

    // 10. thin data
    @Test
    void twoSamplesNeverGiveAGreatListing() {
        MarketStats s = stats(sales(new double[]{100, 104}, H, 10 * MIN));
        assertEquals(MarketConfidence.LOW, s.confidence());
        ListingAnalysis a = ListingAnalysis.of(0, MarketObservation.of("k", 40.0D, 1, NOW, MarketObservation.Source.LISTING), s);
        assertNotEquals(ListingAnalysis.Rating.GREAT, a.rating());
        assertEquals(ListingAnalysis.Rating.GOOD, a.rating());
    }

    @Test
    void twoSamplesThatDisagreeByMoreThanFiveTimesAreNoEstimate() {
        MarketStats s = stats(sales(new double[]{10, 100}, H, 10 * MIN));
        assertEquals(MarketConfidence.NONE, s.confidence());
        assertEquals(ListingAnalysis.Rating.UNKNOWN, ListingAnalysis.of(0, MarketObservation.of("k", 5.0D, 1, NOW, MarketObservation.Source.LISTING), s).rating());
    }

    @Test
    void highConfidenceNeedsRealEvidence() {
        // five fresh, consistent sales: good, but not High
        MarketStats five = stats(sales(around(100, 5), 10 * MIN, 20 * MIN));
        assertNotEquals(MarketConfidence.HIGH, five.confidence());
        // many fresh consistent listings only (no sale): not High under twelve
        List<MarketObservation> asks = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            asks.add(listing(100 + i % 3, (i + 1) * 5 * MIN));
        }
        assertNotEquals(MarketConfidence.HIGH, stats(asks).confidence());
        // sixteen sales of the last day: High
        assertEquals(MarketConfidence.HIGH, stats(sales(around(100, 16), 10 * MIN, 50 * MIN)).confidence());
    }

    @Test
    void anOnlyOldDatasetCapsAtLowConfidence() {
        MarketStats s = stats(sales(around(100, 20), 10 * D, 2 * H));
        assertEquals(MarketConfidence.LOW, s.confidence());
        assertEquals("older", s.basis());
    }

    @Test
    void theAgreementOfTheWindowsRaisesTheScoreAndADisagreementDoesNot() {
        List<MarketObservation> agree = new ArrayList<>(sales(around(100, 8), 3 * D, 5 * H));
        agree.addAll(sales(around(100, 6), 30 * MIN, 60 * MIN));
        List<MarketObservation> disagree = new ArrayList<>(sales(around(100, 8), 3 * D, 5 * H));
        disagree.addAll(sales(around(160, 6), 30 * MIN, 60 * MIN));
        assertTrue(stats(agree).score() > stats(disagree).score(), stats(agree).score() + " vs " + stats(disagree).score());
    }

    // 5. / 6. identity of listings
    @Test
    void aRefreshedPageDoesNotInflateTheSamples() {
        MarketHistory h = new MarketHistory();
        long expiry = NOW + 23 * H;
        // the same listing seen three times, the expiry second jitters by a second
        assertTrue(h.observe(MarketObservation.of("k", 1000, 1, NOW, MarketObservation.Source.LISTING, "seller", null, expiry)));
        assertFalse(h.observe(MarketObservation.of("k", 1000, 1, NOW + 5_000L, MarketObservation.Source.LISTING, "seller", null, expiry + 1_000L)));
        assertFalse(h.observe(MarketObservation.of("k", 1000, 1, NOW + 60_000L, MarketObservation.Source.LISTING, "seller", null, expiry - 1_000L)));
        assertEquals(1, h.size());
        assertEquals(NOW + 60_000L, h.all().get(0).timestampMs(), "the latest sighting");
    }

    @Test
    void twoRealListingsWithTheSameSellerPriceAndAmountStayTwoWhenTheyExpireAtDifferentTimes() {
        MarketHistory h = new MarketHistory();
        // the real menu: two XP boosters of one seller, expiring 23h 58m 58s and 23h 58m 54s from the same moment
        long a = NOW + (23 * H + 58 * MIN + 58_000L);
        long b = NOW + (23 * H + 58 * MIN + 54_000L);
        assertTrue(h.observe(MarketObservation.of("xp", 10_000_000, 1, NOW, MarketObservation.Source.LISTING, "CookinTemp", null, a)));
        assertTrue(h.observe(MarketObservation.of("xp", 10_000_000, 1, NOW, MarketObservation.Source.LISTING, "CookinTemp", null, b)));
        assertEquals(2, h.size());
        // seen again two seconds later (the countdown moved, the absolute expiry did not)
        assertFalse(h.observe(MarketObservation.of("xp", 10_000_000, 1, NOW + 2_000L, MarketObservation.Source.LISTING, "CookinTemp", null, a)));
        assertFalse(h.observe(MarketObservation.of("xp", 10_000_000, 1, NOW + 2_000L, MarketObservation.Source.LISTING, "CookinTemp", null, b)));
        assertEquals(2, h.size());
    }

    @Test
    void theSameSaleOnARefreshedHistoryPageIsOneSampleButAnotherSaleIsAnother() {
        MarketHistory h = new MarketHistory();
        assertTrue(h.observe(MarketObservation.of("k", 500, 1, NOW - 2 * MIN, MarketObservation.Source.SALE, "seller", "buyer", 0L)));
        // 40 s later the page still says "sold 2m ago": the computed time drifts by 40 s
        assertFalse(h.observe(MarketObservation.of("k", 500, 1, NOW - 2 * MIN + 40_000L, MarketObservation.Source.SALE, "seller", "buyer", 0L)));
        // another buyer, same price: another sale
        assertTrue(h.observe(MarketObservation.of("k", 500, 1, NOW - 2 * MIN, MarketObservation.Source.SALE, "seller", "other", 0L)));
        // the same pair an hour later: another sale
        assertTrue(h.observe(MarketObservation.of("k", 500, 1, NOW - 3 * H, MarketObservation.Source.SALE, "seller", "buyer", 0L)));
        assertEquals(3, h.size());
    }

    @Test
    void salesAndAskingPricesStayTwoConcepts() {
        MarketHistory h = new MarketHistory();
        assertTrue(h.observe(MarketObservation.of("k", 500, 1, NOW, MarketObservation.Source.SALE)));
        assertTrue(h.observe(MarketObservation.of("k", 500, 1, NOW, MarketObservation.Source.LISTING)));
        assertEquals(2, h.size());
    }

    // 13. / 14. / 15. variants and enchants
    @Test
    void variantsOfAnItemKeepSeparateHistoriesButGroupInTheCatalog() {
        ItemFacts low = Samples.facts("Charge Orb", "minecraft:magma_cream", "charge_orb", Map.of("charge_orb_percent", "5"));
        ItemFacts high = Samples.facts("Charge Orb", "minecraft:magma_cream", "charge_orb", Map.of("charge_orb_percent", "100"));
        ItemIdentity a = ItemIdentity.of(low);
        ItemIdentity b = ItemIdentity.of(high);
        assertNotEquals(a.key(), b.key());
        assertEquals(a.catalogKey(), b.catalogKey(), "one entry in the item list");
        MarketCache cache = new MarketCache();
        for (int i = 0; i < 5; i++) {
            cache.observe(a.catalogKey(), MarketObservation.of(a.key(), 100 + i, 1, NOW - (i + 1) * 10 * MIN, MarketObservation.Source.SALE, "s" + i, "b", 0L));
            cache.observe(b.catalogKey(), MarketObservation.of(b.key(), 900 + i, 1, NOW - (i + 1) * 10 * MIN, MarketObservation.Source.SALE, "t" + i, "b", 0L));
        }
        assertEquals(102.0D, cache.stats(a.key(), NOW, 0L).fair(), 2.0D, "the 5 % orb is not priced by the 100 % ones");
        assertEquals(902.0D, cache.stats(b.key(), NOW, 0L).fair(), 2.0D);
        MarketStats entry = cache.statsForCatalog(a.catalogKey(), NOW);
        assertEquals(5, entry.samples(), "the entry is priced by ONE variant, never by a mix of the two");
        assertTrue(Math.abs(entry.fair() - 102.0D) < 2.0D || Math.abs(entry.fair() - 902.0D) < 2.0D, "fair " + entry.fair());
    }

    @Test
    void differentEnchantsAreDifferentItemsInTheMarket() {
        ItemFacts sharp = new ItemFacts("minecraft:diamond_sword", "Diamond Sword", List.of(), Map.of(), List.of("sharpness 5"), 1);
        ItemFacts plain = new ItemFacts("minecraft:diamond_sword", "Diamond Sword", List.of(), Map.of(), List.of(), 1);
        assertNotEquals(ItemIdentity.of(sharp).key(), ItemIdentity.of(plain).key());
        MarketCache cache = new MarketCache();
        for (int i = 0; i < 4; i++) {
            cache.observe("c", MarketObservation.of(ItemIdentity.of(sharp).key(), 5000 + i, 1, NOW - (i + 1) * MIN, MarketObservation.Source.SALE, "s" + i, "b", 0L));
        }
        assertFalse(cache.stats(ItemIdentity.of(plain).key(), NOW, 0L).known(), "a plain sword has no price from the enchanted ones");
    }

    @Test
    void theItemListGroupsTheVariantsOfOneCatalogEntryAndPricesThemFromOneMemory() {
        ItemRegistry registry = new ItemRegistry();
        ItemFacts one = new ItemFacts("minecraft:paper", "Totally New Voucher", List.of(), Map.of("voucher_level", "1"), List.of(), 1);
        ItemFacts two = new ItemFacts("minecraft:paper", "Totally New Voucher", List.of(), Map.of("voucher_level", "2"), List.of(), 1);
        assertTrue(registry.learn(one, "minecraft:paper"));
        assertFalse(registry.learn(two, "minecraft:paper"), "the same catalog entry");
        MarketCache cache = new MarketCache();
        for (ItemFacts f : List.of(one, two)) {
            ItemIdentity id = ItemIdentity.of(f);
            for (int i = 0; i < 3; i++) {
                cache.observe(id.catalogKey(), MarketObservation.of(id.key(), 100 + i, 1, NOW - (i + 1) * MIN, MarketObservation.Source.SALE, f.values().get("voucher_level") + i, "b", 0L));
            }
        }
        ItemPrices prices = new ItemPrices();
        assertTrue(prices.refresh(cache, NOW));
        ItemPrices.Card card = prices.card(ItemIdentity.of(one).catalogKey());
        assertTrue(card != null && card.price().startsWith("$1"), "card " + card);
        assertFalse(prices.refresh(cache, NOW + 1000L), "nothing changed, nothing rebuilt");
    }

    @Test
    void thePriceFormatHasThreeSignificantDigits() {
        assertEquals("2.30K", PriceFormat.compact(2300));
        assertEquals("231K", PriceFormat.compact(231_000));
        assertEquals("14.8M", PriceFormat.compact(14_800_000));
        assertEquals("1.48M", PriceFormat.compact(1_480_000));
        assertEquals("0.43", PriceFormat.compact(0.43));
        assertEquals("$1.20M", PriceFormat.money(1_200_000));
        assertEquals("+8.4%", PriceFormat.percent(0.084));
    }
}
