package com.freelocs.theprisons.modules.mining.ore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnZoneTest {
    @Test
    void theSidebarAtSpawnWithCosmicsInvisibleLineEnds() {
        // From the game log (2026-10-03): every line ends with U+0088, U+0089, ...
        assertTrue(OreMacroModule.spawnZone(List.of("M4cL4ren Day 8", "Balance\u0087", "$3,914,562.82",
                "Current Zone\u0088", "Safezone\u0089", "Level\u008a", "75 (10,231,458 XP)")));
        assertTrue(OreMacroModule.spawnZone(List.of("Current Zone: Safezone")));
    }

    @Test
    void theSidebarInTheMine() {
        assertFalse(OreMacroModule.spawnZone(List.of("Account M4cL4ren", "Level", "Guard XP Tax 4%", "Cosmic Energy")));
        assertFalse(OreMacroModule.spawnZone(List.of("Current Zone\u0088", "Gold Mine\u0089")));
    }
}
