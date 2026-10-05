package com.freelocs.theprisons.modules.mining.ore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestonesTest {
    @Test
    void readsLevelAndPrestigeFromTheName() {
        assertEquals(new Milestones.Reading(81, 2), Milestones.read("Diamond Pickaxe 81 II", List.of()));
        assertEquals(new Milestones.Reading(75, 0), Milestones.read("Diamond Pickaxe 75", List.of()));
        assertEquals(new Milestones.Reading(10, 14), Milestones.read("Pickaxe 10 XIV", List.of()));
        assertEquals(-1, Milestones.read("Stick", List.of()).level());
    }

    @Test
    void everyFifthLevelAndPrestigeIsAStepButNotTheStart() {
        Milestones m = new Milestones();
        assertFalse(m.crossed(new Milestones.Reading(80, 0)), "the first reading only sets the start");
        assertFalse(m.crossed(new Milestones.Reading(80, 0)));
        assertFalse(m.crossed(new Milestones.Reading(81, 0)));
        assertFalse(m.crossed(new Milestones.Reading(84, 0)));
        assertTrue(m.crossed(new Milestones.Reading(85, 0)));
        assertFalse(m.crossed(new Milestones.Reading(85, 0)));
        assertFalse(m.crossed(new Milestones.Reading(1, 4)), "prestige 4 is no step");
        assertTrue(m.crossed(new Milestones.Reading(1, 5)), "prestige 5 is");
        assertFalse(m.crossed(new Milestones.Reading(2, 5)));
        assertTrue(m.crossed(new Milestones.Reading(5, 5)));
    }
}
