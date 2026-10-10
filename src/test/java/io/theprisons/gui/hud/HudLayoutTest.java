package io.theprisons.gui.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudLayoutTest {
    @Test
    void snapsToTheNearestGridLine() {
        assertEquals(16, HudLayout.snapToGrid(19, 8));
        assertEquals(24, HudLayout.snapToGrid(20, 8));
        assertEquals(30, HudLayout.snapToGrid(31, 6));
        assertEquals(0, HudLayout.snapToGrid(2, 16));
        // the grid setting reaches the result
        assertEquals(32, HudLayout.snapToGrid(37, 32));
    }

    @Test
    void snapsToTheScreenEdgesAndTheCentre() {
        boolean[] guide = new boolean[1];
        // 150 wide box on a 640 screen: left edge, right edge (490), centre (245)
        assertEquals(0, HudLayout.snapAxis(3, 150, 640, 16, 4, guide));
        assertFalse(guide[0]);
        assertEquals(490, HudLayout.snapAxis(487, 150, 640, 16, 4, guide));
        assertEquals(245, HudLayout.snapAxis(247, 150, 640, 16, 4, guide));
        assertTrue(guide[0]);
        // elsewhere: the grid
        assertEquals(96, HudLayout.snapAxis(100, 150, 640, 16, 4, guide));
        assertFalse(guide[0]);
    }

    @Test
    void neverLeavesTheScreen() {
        boolean[] guide = new boolean[1];
        assertEquals(0, HudLayout.snapAxis(-40, 100, 400, 8, 4, guide));
        assertEquals(300, HudLayout.snapAxis(900, 100, 400, 8, 4, guide));
        // a box wider than the screen sits at 0
        assertEquals(0, HudLayout.snapAxis(50, 500, 400, 8, 4, guide));
    }
}
