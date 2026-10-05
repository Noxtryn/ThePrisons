package com.freelocs.theprisons.modules.mining.ore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BorderZonesTest {
    @Test
    void keepsFiveBlocksAwayFromABorder() {
        BorderZones zones = new BorderZones();
        zones.add(0.5D, 64.0D, 0.5D, BorderMarks.RADIUS);
        assertTrue(zones.blocks(4.0D, 64.0D, 0.5D, 7.0D, 0.5D), "a step into the area");
        assertFalse(zones.blocks(7.0D, 64.0D, 2.0D, 9.0D, 2.0D), "a step past it");
        assertTrue(zones.inside(4.5D, 64.0D, 0.5D));
        assertFalse(zones.inside(6.0D, 64.0D, 0.5D));
        assertFalse(zones.inside(0.5D, 80.0D, 0.5D), "another floor of the cave");
    }

    @Test
    void insideAnAreaOnlyTheWayOutIsFree() {
        BorderZones zones = new BorderZones();
        zones.add(0.5D, 64.0D, 0.5D, BorderMarks.RADIUS);
        assertFalse(zones.blocks(4.0D, 64.0D, 0.5D, 2.0D, 0.5D), "walking away");
        assertTrue(zones.blocks(1.0D, 64.0D, 0.5D, 2.0D, 0.5D), "walking deeper in");
    }
}
