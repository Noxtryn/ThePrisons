package io.theprisons.items.energy;

import io.theprisons.items.ItemFacts;
import io.theprisons.items.Samples;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnergyTest {
    private static final long T0 = 1_000_000_000L;

    private static EnergyReading reading(long current, Long capacity) {
        return new EnergyReading("pickaxe-1", current, capacity, EnergySource.ITEM_LORE);
    }

    // ── reading the real items ───────────────────────────────────────────────

    @Test
    void theEnergySectionOfARealSatchelIsRead() {
        ItemFacts satchel = Samples.catalog().get("diamond satchel|block");
        EnergyReading r = EnergyReader.read(satchel);
        assertNotNull(r);
        assertEquals(0L, r.current());
        assertEquals(13_200L, r.capacity());
        assertEquals(EnergySource.ITEM_LORE, r.source());
    }

    @Test
    void theAmountOfACosmicEnergyItemIsReadWithoutACapacity() {
        ItemFacts energy = Samples.facts("Cosmic Energy", "minecraft:light_blue_dye", "cosmic_energy", Map.of("amount", "5.07874299736976E8"));
        EnergyReading r = EnergyReader.read(energy);
        assertNotNull(r);
        assertEquals(507_874_300L, r.current());
        assertNull(r.capacity());
        assertEquals(EnergySource.ITEM_VALUE, r.source());
    }

    @Test
    void anItemWithoutAnEnergySectionIsNoReading() {
        assertNull(EnergyReader.read(ItemFacts.ofName("minecraft:paper", "White Scroll")));
        assertNull(EnergyReader.read(Samples.catalog().get("shard|executive")));
        assertNull(EnergyReader.read(Samples.facts("Cosmic Energy", "minecraft:light_blue_dye", "cosmic_energy", Map.of())), "no amount stored: nothing to read");
    }

    @Test
    void theExtractorWordHeuristic() {
        assertTrue(EnergyReader.mentionsExtractor("Energy Extractor"));
        assertTrue(EnergyReader.mentionsExtractor("cosmic ENERGY EXTRACTOR menu"));
        assertFalse(EnergyReader.mentionsExtractor("Market"));
        assertFalse(EnergyReader.mentionsExtractor(null));
    }

    // ── partial data ─────────────────────────────────────────────────────────

    @Test
    void withACapacityTheViewHasAProgressBarAndAPercent() {
        EnergyTracker t = new EnergyTracker();
        EnergyOverlayModel m = EnergyOverlayModel.of(t.update(reading(7_420_000L, 10_000_000L), T0), T0);
        assertEquals(0.742F, m.progress(), 1e-4F);
        assertEquals("74%", m.percent());
        assertEquals("7.42M", m.stored());
        assertEquals("10.00M", m.capacity());
        assertTrue(m.detailedLines().contains("Capacity  10.00M"), m.detailedLines().toString());
    }

    @Test
    void anUnknownCapacityHasNoPercentNoBarNoEta() {
        EnergyTracker t = new EnergyTracker();
        EnergyOverlayModel m = EnergyOverlayModel.of(t.update(reading(7_420_000L, null), T0), T0);
        assertNull(m.progress());
        assertNull(m.percent());
        assertNull(m.capacity());
        assertNull(m.eta());
        assertTrue(m.unknown().contains("capacity"));
        assertTrue(m.compactLines().stream().noneMatch(l -> l.contains("%") || l.contains("█")), m.compactLines().toString());
        assertEquals("⚡ 7.42M", m.compactLines().get(0));
        assertTrue(m.detailedLines().stream().noneMatch(l -> l.startsWith("Capacity") || l.startsWith("ETA")), m.detailedLines().toString());
    }

    @Test
    void anUnknownRateLeavesTheRateLineOut() {
        EnergyTracker t = new EnergyTracker();
        EnergyOverlayModel m = EnergyOverlayModel.of(t.update(reading(1000L, 5000L), T0), T0);
        assertNull(m.rate());
        assertTrue(m.detailedLines().stream().noneMatch(l -> l.startsWith("Rate")));
        assertTrue(m.unknown().contains("rate"));
        // two readings but less than twenty seconds: the direction is known, the rate is not
        EnergyOverlayModel quick = EnergyOverlayModel.of(t.update(reading(1500L, 5000L), T0 + 5_000L), T0 + 5_000L);
        assertNull(quick.rate());
        assertEquals("GAINING", quick.status());
    }

    // ── rate, eta, stale, revision ───────────────────────────────────────────

    @Test
    void theRateAndTheEtaComeFromObservedChanges() {
        EnergyTracker t = new EnergyTracker();
        t.update(reading(1_000_000L, 10_000_000L), T0);
        t.update(reading(1_500_000L, 10_000_000L), T0 + 60_000L);
        EnergyExtractorState s = t.update(reading(2_000_000L, 10_000_000L), T0 + 120_000L);
        assertEquals(EnergyOperation.GAINING, s.operation());
        assertEquals(500_000.0D, s.rateMin(), 1.0D);
        EnergyOverlayModel m = EnergyOverlayModel.of(s, T0 + 120_000L);
        assertEquals("+500K/min", m.rate());
        assertEquals("16m 00s", m.eta(), "8M left at 500K per minute");
    }

    @Test
    void aDrainingValueHasNoEta() {
        EnergyTracker t = new EnergyTracker();
        t.update(reading(5_000_000L, 10_000_000L), T0);
        EnergyExtractorState s = t.update(reading(4_000_000L, 10_000_000L), T0 + 60_000L);
        assertEquals(EnergyOperation.DRAINING, s.operation());
        EnergyOverlayModel m = EnergyOverlayModel.of(s, T0 + 60_000L);
        assertEquals("-1M/min", m.rate());
        assertNull(m.eta());
    }

    @Test
    void aStaleStateShowsNoRateAndIsFlagged() {
        EnergyTracker t = new EnergyTracker();
        t.update(reading(1_000_000L, 10_000_000L), T0);
        EnergyExtractorState s = t.update(reading(2_000_000L, 10_000_000L), T0 + 60_000L);
        EnergyOverlayModel fresh = EnergyOverlayModel.of(s, T0 + 60_000L);
        EnergyOverlayModel stale = EnergyOverlayModel.of(s, T0 + 60_000L + EnergyExtractorState.STALE_MS + 1);
        assertFalse(fresh.stale());
        assertTrue(stale.stale());
        assertNull(stale.rate());
        assertNull(stale.eta());
        assertEquals("STALE", stale.status());
        assertEquals("2.00M", stale.stored(), "the last known value stays visible");
    }

    @Test
    void theRevisionChangesOnlyWhenSomethingVisibleChanged() {
        EnergyTracker t = new EnergyTracker();
        long r1 = t.update(reading(1000L, 5000L), T0).revision();
        long r2 = t.update(reading(1000L, 5000L), T0 + 2_000L).revision();
        assertEquals(r1, r2, "the same reading again: nothing to redraw");
        long r3 = t.update(reading(1200L, 5000L), T0 + 4_000L).revision();
        assertTrue(r3 > r2);
        long r4 = t.update(reading(1200L, 8000L), T0 + 6_000L).revision();
        assertTrue(r4 > r3, "a new capacity is a change");
    }

    @Test
    void aNewSubjectStartsFreshAndOldReadingsExpire() {
        EnergyTracker t = new EnergyTracker();
        t.update(reading(1000L, 5000L), T0);
        t.update(reading(2000L, 5000L), T0 + 30_000L);
        EnergyExtractorState other = t.update(new EnergyReading("satchel-9", 50L, 100L, EnergySource.ITEM_LORE), T0 + 31_000L);
        assertNull(other.rateMin());
        assertEquals(EnergyOperation.UNKNOWN, other.operation());
        // the same subject long after: the old readings are gone, no rate from a ten minute old sample
        EnergyExtractorState later = t.update(new EnergyReading("satchel-9", 80L, 100L, EnergySource.ITEM_LORE), T0 + 31_000L + 10 * 60_000L);
        assertNull(later.rateMin());
    }

    @Test
    void theReadingsAreBounded() {
        EnergyTracker t = new EnergyTracker();
        for (int i = 0; i < 500; i++) {
            t.update(reading(1000L + i * 10L, 100_000L), T0 + i * 1000L);
        }
        assertNotNull(t.state());
        assertTrue(t.state().rateMin() != null);
    }

    @Test
    void formatting() {
        assertEquals("7.42M", EnergyFormat.compact(7_420_000));
        assertEquals("184.0K", EnergyFormat.compact(184_000));
        assertEquals("950", EnergyFormat.compact(950));
        assertEquals("10M", EnergyFormat.shortForm(10_000_000));
        assertEquals("+184K/min", EnergyFormat.rate(184_000));
        assertEquals("14m 02s", EnergyFormat.duration(842));
        assertEquals("1h 05m", EnergyFormat.duration(3900));
        assertEquals("45s", EnergyFormat.duration(45));
        assertEquals(16, EnergyOverlayModel.bar(0.5F).length());
        assertEquals("████████░░░░░░░░", EnergyOverlayModel.bar(0.5F));
    }

    @Test
    void theCompactModeShowsTheKnownThree() {
        EnergyTracker t = new EnergyTracker();
        t.update(reading(1_000_000L, 10_000_000L), T0);
        EnergyExtractorState s = t.update(reading(1_184_000L, 10_000_000L), T0 + 60_000L);
        List<String> lines = EnergyOverlayModel.of(s, T0 + 60_000L).compactLines();
        assertEquals("⚡ 1.18M / 10M", lines.get(0));
        assertTrue(lines.get(1).endsWith("12%"), lines.get(1));
        assertEquals("+184K/min", lines.get(2));
    }
}
