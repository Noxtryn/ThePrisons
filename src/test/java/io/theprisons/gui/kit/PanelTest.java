package io.theprisons.gui.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelTest {
    @Test
    void sitsRightOfTheMenuWhenThereIsRoom() {
        // 427x240 is GUI scale 3 at 1280x720; the 176x166 menu is centred
        int[] at = Panel.besideMenu(960, 540, 176, 166, 150, 100, 10, 6);
        assertArrayEquals(new int[]{960 / 2 + 88 + 10, 540 / 2 - 83}, at);
    }

    @Test
    void movesLeftOfTheMenuWhenTheRightIsTooNarrow() {
        int[] at = Panel.besideMenu(427, 240, 176, 166, 130, 100, 10, 6);
        assertArrayEquals(new int[]{6, 240 / 2 - 83}, at); // no room on either side: the margin, as before
    }

    @Test
    void staysOnScreenWhateverTheSize() {
        for (int[] screen : new int[][]{{320, 180}, {427, 240}, {640, 360}, {960, 540}}) {
            for (int panelH : new int[]{60, 150, 230}) {
                int[] at = Panel.besideMenu(screen[0], screen[1], 176, 166, 140, panelH, 10, 6);
                assertTrue(at[0] >= 0 && at[1] >= 0, screen[0] + "x" + screen[1]);
                assertTrue(at[1] + Math.min(panelH, screen[1] - 12) <= screen[1] - 6 + 1 || panelH > screen[1] - 12);
            }
        }
    }

    @Test
    void cornersKeepTheMargin() {
        assertArrayEquals(new int[]{960 - 132 - 8, 540 - 40 - 8}, Panel.corner(960, 540, 132, 40, true, true, 8));
        assertArrayEquals(new int[]{8, 8}, Panel.corner(960, 540, 132, 40, false, false, 8));
        assertArrayEquals(new int[]{0, 0}, Panel.corner(100, 30, 132, 40, true, true, 8));
    }
}
