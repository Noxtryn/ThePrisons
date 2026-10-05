package io.theprisons.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThePrisonsTrackerTest {
    private static boolean pet(String name) {
        return ThePrisonsTracker.isPet(ThePrisonsTracker.normalize(name));
    }

    private static boolean trinket(String name) {
        return ThePrisonsTracker.isTrinket(ThePrisonsTracker.normalize(name));
    }

    @Test
    void onlyRealPetsAndTrinkets() {
        assertTrue(pet("Anti XP Tax Pet [LVL 3]"));
        assertTrue(pet("Wormhole Powerup Pet"));
        assertFalse(pet("Pet Leash"));
        assertFalse(pet("Red Carpet"));
        assertFalse(pet("Godly Enchant Orb"));
        assertTrue(trinket("Blink Trinket"));
        assertTrue(trinket("II OP Blink Trinket II"));
        assertFalse(trinket("Random Trinket"));
        assertFalse(trinket("Godly Enchant Orb"));
        assertFalse(trinket("Charge Orb"));
        assertFalse(trinket("Fishing Hook"));
    }

    @Test
    void durations() {
        assertEquals(13 * 60_000L + 45_000L, ThePrisonsTracker.parseDuration("13m 45s"));
        assertEquals(3_600_000L + 60_000L, ThePrisonsTracker.parseDuration("1h 1m"));
        assertEquals(60_000L, ThePrisonsTracker.parseDuration("1 minute"));
        assertEquals(30 * 60_000L, ThePrisonsTracker.parseDuration("30m"));
    }
}
