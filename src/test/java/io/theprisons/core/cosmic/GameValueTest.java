package io.theprisons.core.cosmic;

import io.theprisons.core.cosmic.value.Confidence;
import io.theprisons.core.cosmic.value.GameValue;
import io.theprisons.core.cosmic.value.UnknownValueException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameValueTest {
    @Test
    void unknownCarriesNoValueAndIsNeverZero() {
        GameValue<Integer> unknown = GameValue.unknown("owner");
        assertNull(unknown.value());
        assertFalse(unknown.isKnown());
        assertEquals(Confidence.UNKNOWN, unknown.confidence());
        assertTrue(unknown.known().isEmpty());
        assertTrue(unknown.asDouble(Confidence.OBSERVED).isEmpty(), "unknown must not read as 0.0");
        assertEquals(-1, unknown.orElse(-1), "the fallback is the caller's explicit choice");
    }

    @Test
    void aValueWithUnknownConfidenceBecomesUnknown() {
        GameValue<Integer> v = GameValue.of(7, Confidence.UNKNOWN, "x", 0, "", null);
        assertNull(v.value());
        assertEquals(Confidence.UNKNOWN, v.confidence());
    }

    @Test
    void aMissingValueBecomesUnknownWhateverTheConfidence() {
        GameValue<Integer> v = GameValue.of(null, Confidence.VERIFIED_OFFICIAL, "x", 0, "", null);
        assertEquals(Confidence.UNKNOWN, v.confidence());
    }

    @Test
    void confidenceOrderAndAtLeast() {
        assertTrue(Confidence.VERIFIED_OFFICIAL.atLeast(Confidence.VERIFIED_LIVE));
        assertTrue(Confidence.VERIFIED_LIVE.atLeast(Confidence.OBSERVED));
        assertFalse(Confidence.OBSERVED.atLeast(Confidence.VERIFIED_LIVE));
        assertFalse(Confidence.UNKNOWN.atLeast(Confidence.OBSERVED));
        GameValue<Double> observed = GameValue.observed(32.0D, "measured", 1L, null);
        assertEquals(32.0D, observed.asDouble(Confidence.OBSERVED).getAsDouble());
        assertTrue(observed.asDouble(Confidence.VERIFIED_LIVE).isEmpty(), "too weak for a feature that wants a verified value");
        assertTrue(observed.atLeast(Confidence.VERIFIED_LIVE).isEmpty());
    }

    @Test
    void requireThrowsForUnknown() {
        assertThrows(UnknownValueException.class, () -> GameValue.unknown("owner").require());
        assertEquals(5, GameValue.live(5, "sidebar", 1L).require());
    }

    @Test
    void mapKeepsConfidenceAndUnknownStaysUnknown() {
        GameValue<Integer> live = GameValue.live(5, "sidebar", 10L);
        GameValue<String> mapped = live.map(i -> "n" + i);
        assertEquals("n5", mapped.value());
        assertEquals(Confidence.VERIFIED_LIVE, mapped.confidence());
        assertEquals(Confidence.UNKNOWN, GameValue.<Integer>unknown("x").map(i -> "n" + i).confidence());
    }

    @Test
    void staleness() {
        GameValue<Integer> v = GameValue.live(1, "sidebar", 1_000L);
        assertFalse(v.isStale(2_000L, 5_000L));
        assertTrue(v.isStale(10_000L, 5_000L));
        assertFalse(GameValue.of(1, Confidence.OBSERVED, "registry", 0L, "", null).isStale(1L << 40, 1L), "no time = never stale");
    }

    @Test
    void parseConfidenceRejectsGuesses() {
        assertEquals(Confidence.OBSERVED, Confidence.parse(" observed "));
        assertThrows(IllegalArgumentException.class, () -> Confidence.parse("probably"));
    }

    @Test
    void contextBinding() {
        GameValue<Integer> v = GameValue.live(1, "x", 1L).inContext("mc1.21.11 season:3");
        assertEquals("mc1.21.11 season:3", v.context());
    }
}
