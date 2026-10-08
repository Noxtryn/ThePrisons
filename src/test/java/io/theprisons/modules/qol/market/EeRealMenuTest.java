package io.theprisons.modules.qol.market;

import io.theprisons.items.market.EeAnalysis;
import io.theprisons.items.market.EeMenu;
import io.theprisons.items.market.EeParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The energy market overlay on the REAL "Buy Cosmic Energy" menu of 2026-10-05 ({@link MenuDumps}). */
class EeRealMenuTest {
    private static EeMenu menu() {
        return EeParser.parse(MenuDumps.first(MarketParser.ENERGY).items());
    }

    @Test
    void theHeaderAndTheButtonsOfTheRealMenuAreRead() {
        EeMenu m = menu();
        assertEquals(507_900_000D, m.available(), 1_000_000D);
        assertEquals(2299.0D, m.fromRate(), 1e-9);
        assertEquals(8, m.sellers());
        assertTrue(m.priceRisesInMs() > 23L * 3_600_000L, "23h 40m");
        assertEquals(2299.0D, m.serverLowest(), 1e-9);
        assertEquals(37_622_987.7D, m.serverAvg10k(), 1e-3);
        assertEquals(1_788_270.626D, m.balance(), 1e-3);
    }

    @Test
    void everyRealListingBecomesOneEntryWithItsRate() {
        EeMenu m = menu();
        assertEquals(8, m.listings().size());
        EeMenu.Listing first = m.listings().get(0);
        assertEquals(9, first.slot());
        assertEquals("ImKoby", first.seller());
        assertEquals(0.724D, first.amount(), 1e-9, "a fraction of energy is a real listing");
        assertEquals(2299.0D, first.ratePer1k(), 1e-9);
        EeMenu.Listing big = m.listings().get(2);
        assertEquals(19_000_000D, big.amount(), 1e-3);
        assertEquals(43_700_000D, big.total(), 1e-3);
        assertEquals(2300.0D, big.ratePer1k(), 1e-9);
        assertEquals(100_000_000D, m.listings().get(6).ratePer1k(), 1e-3, "the absurd listing is read as it is printed");
    }

    @Test
    void theAbsurdListingsAreOutliersAndNeverTheCheapestOrTheTypicalRate() {
        EeAnalysis a = EeAnalysis.of(menu(), 0.0D, Double.NaN, Double.NaN);
        assertEquals(2299.0D, a.lowestRate(), 1e-9);
        assertTrue(a.medianRate() < 3000.0D && a.medianRate() > 2200.0D, "typical rate of the sane listings: " + a.medianRate());
        long outliers = a.slots().values().stream().filter(EeAnalysis.SlotInfo::outlier).count();
        assertEquals(2, outliers, "the two 100,000,000 /1k offers");
        assertTrue(a.slots().get(9).cheapest());
        assertFalse(a.slots().get(9).outlier());
        assertEquals(0.0D, a.slots().get(9).premium(), 1e-12);
        assertEquals(2400.0D / 2299.0D - 1.0D, a.slots().get(12).premium(), 1e-9);
    }

    @Test
    void theCostOfBuyingEnergyAddsUpTheCheapestListingsFirst() {
        EeAnalysis a = EeAnalysis.of(menu(), 0.0D, Double.NaN, Double.NaN);
        EeAnalysis.Cost tenK = a.costs().get(0);
        assertTrue(tenK.complete());
        // 0.724 + 0.013 at the first two rates, the rest from the 19,000,000 listing at 2,300 /1k
        double expected = 0.724D * 2299.0D / 1000.0D + 0.013D * 2300.0D / 1000.0D + (10_000D - 0.737D) * 2300.0D / 1000.0D;
        assertEquals(expected, tenK.total(), 1e-6);
        assertEquals(tenK.total() / 10_000D * 1000.0D, tenK.avgRatePer1k(), 1e-9);
        EeAnalysis.Cost hundredM = a.costs().get(4);
        assertTrue(hundredM.complete(), "19M + 34.5M + 62.78M + 372.5M on the page cover 100M");
        assertTrue(hundredM.avgRatePer1k() > 2300.0D && hundredM.avgRatePer1k() < 2500.0D, "average rate " + hundredM.avgRatePer1k());
    }

    @Test
    void whatThePageCannotCoverIsSaidNotGuessed() {
        EeMenu small = new EeMenu(List.of(new EeMenu.Listing(9, "a", 5_000D, 11.5D, 2300.0D)), Double.NaN, Double.NaN, -1, -1L, Double.NaN, Double.NaN, Double.NaN);
        EeAnalysis a = EeAnalysis.of(small, 0.0D, Double.NaN, Double.NaN);
        assertFalse(a.costs().get(0).complete(), "10k is more than the 5k on the page");
        assertEquals(2300.0D, a.lowestRate(), 1e-9);
        assertTrue(Double.isNaN(a.affordable()), "no balance in the menu: nothing is guessed");
        assertTrue(a.lines().stream().noneMatch(l -> l.startsWith("Can buy")));
        assertTrue(a.panel().sections().stream().noneMatch(sec -> sec.title().equals("BUY COST")), "no cost for sizes the page cannot cover");
    }

    @Test
    void whatTheBalanceBuysAndWhatTheHeldEnergyIsWorth() {
        EeAnalysis a = EeAnalysis.of(menu(), 2_000_000D, Double.NaN, Double.NaN);
        // $1,788,270.626 at about 2,300 /1k buys roughly 777k energy
        assertTrue(a.affordable() > 700_000D && a.affordable() < 800_000D, "affordable " + a.affordable());
        assertEquals(2_000_000D * 2299.0D / 1000.0D, a.heldValue(), 1e-6);
        assertTrue(a.lines().stream().anyMatch(l -> l.startsWith("Hold") && l.contains("2.00M CE")), a.lines().toString());
        assertTrue(a.lines().stream().anyMatch(l -> l.startsWith("Value") && l.contains("$4.60M")), a.lines().toString());
    }

    @Test
    void theWeekAverageComparesTheCheapestOfferWithTheRecentSales() {
        EeAnalysis a = EeAnalysis.of(menu(), 0.0D, 2185.3D, 2200.0D);
        // typical 2,400-ish against a 7-day average of 2,185: the market is dearer than its week
        double vs = a.marketVsWeek();
        assertTrue(vs > 0.05D && vs < 0.15D, "vs week " + vs);
        assertTrue(a.lines().stream().anyMatch(l -> l.startsWith("vs 7-day average") && l.contains("+")), a.lines().toString());
    }

    @Test
    void theHoverTextsOfListings() {
        EeAnalysis a = EeAnalysis.of(menu(), 0.0D, Double.NaN, Double.NaN);
        assertTrue(a.hover(9).stream().anyMatch(l -> l.contains("cheapest offer here")));
        assertTrue(a.hover(12).stream().anyMatch(l -> l.contains("+4.4%")));
        assertTrue(a.hover(15).stream().anyMatch(l -> l.contains("ignored")));
        assertNull(a.hover(30), "a slot without a listing has no hover text");
        assertNotNull(a.hover(14));
    }

    @Test
    void anEmptyMenuIsHandled() {
        EeAnalysis a = EeAnalysis.of(new EeMenu(List.of(), Double.NaN, Double.NaN, -1, -1L, Double.NaN, Double.NaN, Double.NaN), 0.0D, Double.NaN, Double.NaN);
        assertTrue(Double.isNaN(a.lowestRate()));
        assertEquals(List.of("ENERGY MARKET"), a.lines());
    }

    @Test
    void theRealPanelHasTheSectionsOfTheDashboardAndNoAbsurdRate() {
        EeAnalysis a = EeAnalysis.of(menu(), 840_000D, 2500D, 2450D);
        EeAnalysis.Panel p = a.panel();
        assertEquals("ENERGY MARKET", p.title());
        List<String> titles = p.sections().stream().map(EeAnalysis.Section::title).toList();
        assertEquals(List.of("RATES", "MARKET", "BUY COST", "YOU"), titles);
        EeAnalysis.Section rates = p.sections().get(0);
        assertTrue(rates.metrics());
        assertEquals("CHEAPEST", rates.rows().get(0).label());
        assertEquals("$2.30K / 1k", rates.rows().get(0).value());
        assertEquals("TYPICAL", rates.rows().get(1).label());
        assertFalse(rates.rows().get(1).value().contains("100"), "the 100,000,000 /1k offers are not in the typical rate: " + rates.rows().get(1).value());
        assertEquals("2 extreme listings ignored", p.note());
        EeAnalysis.Section you = p.sections().get(3);
        assertTrue(you.rows().stream().anyMatch(r -> r.label().equals("Balance") && r.value().equals("$1.79M")), you.rows().toString());
        assertTrue(you.rows().stream().anyMatch(r -> r.label().equals("Hold") && r.value().equals("840K CE")), you.rows().toString());
    }

    @Test
    void aFarBelowListingStaysTheCheapestAndOnlyHighSideScamsAreIgnored() {
        EeMenu m = new EeMenu(List.of(new EeMenu.Listing(9, "typo", 1_000_000D, 230D, 0.23D), new EeMenu.Listing(10, "a", 1_000_000D, 2300D, 2300D),
                new EeMenu.Listing(11, "b", 1_000_000D, 2400D, 2400D), new EeMenu.Listing(12, "c", 1_000_000D, 2500D, 2500D)),
                Double.NaN, Double.NaN, -1, -1L, Double.NaN, Double.NaN, Double.NaN);
        EeAnalysis a = EeAnalysis.of(m, 0.0D, Double.NaN, Double.NaN);
        assertEquals(0.23D, a.lowestRate(), 1e-9, "a real, very cheap offer is still the cheapest");
        assertFalse(a.slots().get(9).outlier());
    }

    @Test
    void theTypicalRateIgnoresDustListings() {
        EeMenu m = new EeMenu(List.of(new EeMenu.Listing(9, "dust1", 0.5D, 1.0D, 2000D), new EeMenu.Listing(10, "dust2", 0.4D, 1.0D, 2100D),
                new EeMenu.Listing(11, "dust3", 0.3D, 1.0D, 2200D), new EeMenu.Listing(12, "big", 50_000_000D, 140_000D, 2800D)),
                Double.NaN, Double.NaN, -1, -1L, Double.NaN, Double.NaN, Double.NaN);
        EeAnalysis a = EeAnalysis.of(m, 0.0D, Double.NaN, Double.NaN);
        assertEquals(2800D, a.medianRate(), 1e-9, "listings under one energy say nothing about the market");
        assertEquals(2000D, a.lowestRate(), 1e-9);
    }

    @Test
    void malformedQuotesCannotPoisonRatesOrTheBuyCalculation() {
        EeMenu m = new EeMenu(List.of(new EeMenu.Listing(9, "zero", 1_000D, 1D, 0.0D),
                        new EeMenu.Listing(10, "nan", Double.NaN, 1D, 2300D),
                        new EeMenu.Listing(11, "valid", 20_000D, 46_000D, 2300D)),
                Double.NaN, Double.NaN, -1, -1L, Double.NaN, Double.NaN, 100D);
        EeAnalysis a = EeAnalysis.of(m, 0.0D, Double.NaN, Double.NaN);
        assertEquals(2300D, a.lowestRate(), 1e-9);
        assertEquals(2300D, a.medianRate(), 1e-9);
        assertTrue(Double.isFinite(a.affordable()));
        assertNull(a.hover(9), "invalid offer is not presented as a real quote");
    }
}
