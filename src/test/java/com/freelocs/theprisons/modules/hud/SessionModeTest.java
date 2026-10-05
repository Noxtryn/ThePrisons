package com.freelocs.theprisons.modules.hud;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionModeTest {
    @Test
    void theMacroAlwaysMeansOreMining() {
        assertEquals(SessionMode.ORE, SessionMode.of(SessionMode.BANDIT, true));
        assertEquals(SessionMode.BANDIT, SessionMode.of(SessionMode.BANDIT, false));
        assertEquals(SessionMode.ORE, SessionMode.of(SessionMode.ORE, false));
    }

    @Test
    void banditKillsComeFromTheSidebarCounter() {
        // The real sidebar on a bandit moon (game log 2026-10-04).
        List<String> side = List.of("M4cL4ren Day 10", "", "Bandit Level", "3", "Rankup", "60 kills to Level 4",
                "Bandits Killed", "80");
        assertEquals(80L, SessionMode.banditsKilled(side));
        assertEquals(-1L, SessionMode.banditsKilled(List.of("Level", "82 (17,243,224 XP)", "Guard XP Tax 3%")));
        assertEquals(2L, SessionMode.killStep(80L, 82L));
        assertEquals(0L, SessionMode.killStep(-1L, 80L), "first reading: no kills yet");
        assertEquals(0L, SessionMode.killStep(80L, 6_703L), "another counter, not kills");
    }

    @Test
    void ownKillLinesOnly() {
        assertEquals("Payney", SessionMode.ownKill("× [M4cL4ren head]M4cL4ren killed Payney with Iron Sword 30", "M4cL4ren"));
        assertNull(SessionMode.ownKill("× [Payney head]Payney threw M4cL4ren off the runaway train using Iron Sword 30.", "M4cL4ren"));
        assertNull(SessionMode.ownKill("× Icecloak killed FiksimForkse_ with Iron Sword 30", "M4cL4ren"));
    }

    @Test
    void oldNamesMigrate() {
        assertEquals(SessionMode.BANDIT, SessionMode.migrate("Gold Bandits"));
        assertEquals(SessionMode.BANDIT, SessionMode.migrate("bandits"));
        assertEquals(SessionMode.ORE, SessionMode.migrate("Redstone"));
        assertEquals(SessionMode.ORE, SessionMode.migrate("Meteor Mining"));
    }
}
