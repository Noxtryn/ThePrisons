package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.nav.Walkability;
import com.freelocs.theprisons.testing.TestMine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelMapTest {
    /** A tunnel east whose floor (top block y) per z is {@code floor[z + 8]}; 3 blocks of air above it. */
    private static TestMine profile(int[] floor) {
        TestMine mine = new TestMine(-4, -3, -10, 40, 12, 10);
        for (int z = -8; z <= 8; z++) {
            int f = floor[z + 8];
            if (f != Integer.MIN_VALUE) {
                mine.carve(-2, f + 1, z, 35, f + 3, z);
            }
        }
        return mine;
    }

    private static final int W = Integer.MIN_VALUE;

    @Test
    void theSlopeOfABowlShapedTunnelIsWall() {
        // Flat middle z -3..3 (floor 0); the sides rise 1 block per block up to 3, then rock: a bowl. The slope and its
        // foot (z ±3, where the ground rises 2 within 2) are wall, the flat middle is the way.
        TestMine mine = profile(new int[]{W, W, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, W, W});
        TunnelMap map = new TunnelMap();
        map.update(new Walkability(mine, 3), 10, 1, 0);
        assertTrue(map.way(10.5D, 0.5D));
        assertTrue(map.way(10.5D, -1.5D));
        assertTrue(map.way(10.5D, 1.5D));
        assertFalse(map.way(10.5D, -2.5D), "foot of the north slope");
        assertFalse(map.way(10.5D, -3.5D), "north slope");
        assertFalse(map.way(10.5D, 4.5D), "south slope");
    }

    @Test
    void aStairOntoAFlatTerraceIsAWay() {
        // From the flat floor (z -3..0) the ground goes up 2 steps and then on flat for 5 blocks: a terrace, not a wall.
        TestMine mine = profile(new int[]{W, W, W, W, W, 0, 0, 0, 0, 1, 2, 2, 2, 2, 2, 2, W});
        TunnelMap map = new TunnelMap();
        map.update(new Walkability(mine, 3), 10, 1, -2);
        assertTrue(map.way(10.5D, -2.5D));
        assertTrue(map.way(10.5D, 0.5D), "the foot of the stair");
        assertTrue(map.way(10.5D, 1.5D), "the first step");
        assertTrue(map.way(10.5D, 4.5D), "the terrace");
    }

    @Test
    void aVerticalRockIsWall() {
        TestMine mine = new TestMine(-4, -3, -10, 40, 8, 10).carve(-2, 1, -2, 35, 3, 2);
        TunnelMap map = new TunnelMap();
        map.update(new Walkability(mine, 3), 10, 1, 0);
        assertTrue(map.way(10.5D, 2.5D));
        assertFalse(map.way(10.5D, 3.5D));
        assertFalse(map.way(10.5D, -3.5D));
    }
}
