package io.theprisons.items;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryListLayoutAndInputTest {
    // ── layout ───────────────────────────────────────────────────────────────

    @Test
    void theBarSitsAboveTheHotbarBetweenInventoryAndHotbarWhenThereIsRoom() {
        InventoryListLayout l = InventoryListLayout.of(640, 360);       // gui scale 3 on 1080p
        int invBottom = (360 - 166) / 2 + 166;
        assertTrue(l.barY() >= invBottom + 2, "below the inventory: " + l.barY() + " vs " + invBottom);
        assertTrue(l.barY() + l.barH() <= 360 - 22 - 4, "above the hotbar");
        assertEquals(640 / 2 - l.barW() / 2, l.barX());
    }

    @Test
    void whenThereIsNoRoomBelowTheBarMovesDirectlyAboveTheInventory() {
        InventoryListLayout l = InventoryListLayout.of(480, 270);       // gui scale 4: inventory nearly fills the height
        int invTop = (270 - 166) / 2;
        int invBottom = invTop + 166;
        boolean below = l.barY() >= invBottom;
        boolean above = l.barY() + l.barH() <= invTop;
        assertTrue(below || above, "never over the inventory: bar " + l.barY() + ".." + (l.barY() + l.barH()) + " inventory " + invTop + ".." + invBottom);
    }

    @Test
    void thePanelNeverOverlapsTheInventory() {
        for (int[] size : new int[][]{{640, 360}, {854, 480}, {960, 540}, {1280, 720}, {480, 270}, {400, 240}, {1920, 1080}}) {
            InventoryListLayout l = InventoryListLayout.of(size[0], size[1]);
            int left = (size[0] - 176) / 2;
            int right = left + 176;
            if (l.panelFits()) {
                assertTrue(l.panelX() >= right + 1 || l.panelX() + l.panelW() <= left - 1, size[0] + "x" + size[1] + ": panel " + l.panelX() + ".." + (l.panelX() + l.panelW()));
                assertTrue(l.panelX() >= 0 && l.panelX() + l.panelW() <= size[0], "inside the window");
            }
        }
    }

    @Test
    void cardsShrinkToFitAndKeepAtLeastTwoColumns() {
        InventoryListLayout narrow = InventoryListLayout.of(640, 360);
        InventoryListLayout wide = InventoryListLayout.of(1920, 1080);
        assertTrue(narrow.cols() >= 2);
        assertTrue(narrow.gridW() <= narrow.panelW() - InventoryListLayout.PAD * 2 + 1, "grid " + narrow.gridW() + " in panel " + narrow.panelW());
        assertTrue(wide.cols() > narrow.cols());
        assertTrue(narrow.cardW() >= InventoryListLayout.MIN_CARD_W && narrow.cardW() <= InventoryListLayout.MAX_CARD_W);
        assertTrue(wide.cardW() <= InventoryListLayout.MAX_CARD_W);
    }

    @Test
    void aTooNarrowWindowHidesThePanelInsteadOfOverlappingTheInventory() {
        InventoryListLayout l = InventoryListLayout.of(240, 240);
        assertFalse(l.panelFits());
        assertFalse(l.overPanel(10, 10));
    }

    @Test
    void hitTestsFollowTheRectangles() {
        InventoryListLayout l = InventoryListLayout.of(640, 360);
        assertTrue(l.overBar(l.barX() + 5, l.barY() + 5));
        assertFalse(l.overBar(l.barX() - 1, l.barY() + 5));
        assertTrue(l.overPanel(l.panelX() + 3, 20));
        assertFalse(l.overPanel(l.panelX() - 3, 20));
    }

    // ── typing ───────────────────────────────────────────────────────────────

    @Test
    void typingOpensTheListAndEscapeClearsItWithoutConsumingTheKey() {
        SearchInput s = new SearchInput();
        assertFalse(s.open());
        assertTrue(s.charTyped('g'));
        assertTrue(s.charTyped('o'));
        assertEquals("go", s.text());
        assertTrue(s.open());
        assertTrue(s.takeChanged());
        assertFalse(s.takeChanged());
        assertFalse(s.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, false), "Esc is not consumed: the inventory closes with it");
        assertEquals("", s.text());
        assertFalse(s.open());
    }

    @Test
    void backspaceDeletesOneCharacterAndCtrlBackspaceAll() {
        SearchInput s = new SearchInput();
        "shard".chars().forEach(c -> s.charTyped((char) c));
        assertTrue(s.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, false));
        assertEquals("shar", s.text());
        assertTrue(s.keyPressed(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_MOD_CONTROL, false));
        assertEquals("", s.text());
    }

    @Test
    void digitsOverASlotAndShortcutsStayWithTheGame() {
        SearchInput s = new SearchInput();
        assertFalse(s.keyPressed(GLFW.GLFW_KEY_3, 0, true), "a digit over a slot swaps the hotbar");
        assertTrue(s.keyPressed(GLFW.GLFW_KEY_3, 0, false), "a digit elsewhere is typed");
        assertFalse(s.keyPressed(GLFW.GLFW_KEY_A, GLFW.GLFW_MOD_CONTROL, false));
        assertTrue(s.keyPressed(GLFW.GLFW_KEY_E, 0, false), "E does not close the inventory while the bar owns the keys");
        assertFalse(s.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT, 0, false));
    }

    @Test
    void theTextIsBoundedAndUnusableCharactersAreDropped() {
        SearchInput s = new SearchInput();
        for (int i = 0; i < 100; i++) {
            s.charTyped('x');
        }
        assertEquals(SearchInput.MAX_LENGTH, s.text().length());
        SearchInput t = new SearchInput();
        assertTrue(t.charTyped('§'));
        assertTrue(t.charTyped('\n'));
        assertEquals("", t.text());
    }

    @Test
    void showAllOpensTheListWithoutAQuery() {
        SearchInput s = new SearchInput();
        s.toggleShowAll();
        assertTrue(s.open());
        assertTrue(s.takeChanged());
        s.reset();
        assertFalse(s.open());
    }
}
