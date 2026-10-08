package io.theprisons.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualGridTest {
    // 80 x 64 cards, 6 px gap, 2 rows of overscan
    private final VirtualGrid grid = new VirtualGrid(80, 64, 6, 2);

    @Test
    void theColumnsFollowTheWidth() {
        assertEquals(1, grid.columns(40));
        assertEquals(1, grid.columns(80));
        assertEquals(2, grid.columns(166));
        assertEquals(5, grid.columns(500));
        assertEquals(6, grid.columns(520));
    }

    @Test
    void onlyTheVisibleRowsPlusOverscanAreSelectedOutOfEightHundredItems() {
        int items = 800;
        VirtualGrid.Range r = grid.visible(items, 520, 400, 0);       // 6 columns, 400 px = 6 rows + 2 overscan
        assertEquals(0, r.first());
        assertTrue(r.count() <= 6 * 9, "drawn cards " + r.count());
        assertTrue(r.count() < items / 10, "far fewer than all 800: " + r.count());
        assertEquals(r.firstRow(), 0);
    }

    @Test
    void scrollingMovesTheWindow() {
        int items = 800;
        VirtualGrid.Range top = grid.visible(items, 520, 400, 0);
        VirtualGrid.Range mid = grid.visible(items, 520, 400, 70 * 20);          // 20 rows down
        assertTrue(mid.first() > top.last());
        assertTrue(mid.first() <= 6 * 20 && mid.last() >= 6 * 20 + 5, "the row at the scroll offset is inside: " + mid);
        assertTrue(mid.count() <= 6 * 10);
    }

    @Test
    void theScrollIsClampedAndTheLastRowIsReachable() {
        int items = 800;
        int max = grid.maxScroll(items, 520, 400);
        assertEquals(max, grid.clampScroll(1_000_000, items, 520, 400));
        assertEquals(0, grid.clampScroll(-50, items, 520, 400));
        VirtualGrid.Range end = grid.visible(items, 520, 400, max);
        assertEquals(items - 1, end.last());
    }

    @Test
    void overscanKeepsAFastScrollFilled() {
        VirtualGrid.Range noOverscan = new VirtualGrid(80, 64, 6, 0).visible(800, 520, 400, 700);
        VirtualGrid.Range withOverscan = grid.visible(800, 520, 400, 700);
        assertTrue(withOverscan.first() < noOverscan.first());
        assertTrue(withOverscan.last() > noOverscan.last());
    }

    @Test
    void aResizeChangesTheColumnsAndKeepsTheRangeValid() {
        VirtualGrid.Range wide = grid.visible(800, 520, 400, 500);
        VirtualGrid.Range narrow = grid.visible(800, 170, 400, 500);
        assertEquals(2, grid.columns(170));
        assertTrue(narrow.count() <= 2 * 11, "narrow " + narrow);
        assertTrue(narrow.last() < 800 && wide.last() < 800);
        VirtualGrid.Range tiny = grid.visible(800, 520, 0, 0);
        assertEquals(0, tiny.count());
    }

    @Test
    void fewItemsAndNoItems() {
        assertEquals(0, grid.visible(0, 520, 400, 0).count());
        VirtualGrid.Range few = grid.visible(4, 520, 400, 0);
        assertEquals(0, few.first());
        assertEquals(3, few.last());
        assertEquals(0, grid.maxScroll(4, 520, 400));
    }

    @Test
    void thePointToCardLookupMatchesTheLayout() {
        assertEquals(0, grid.indexAt(5, 5, 100, 520, 0));
        assertEquals(1, grid.indexAt(86 + 5, 5, 100, 520, 0));
        assertEquals(6, grid.indexAt(5, 70 + 5, 100, 520, 0));
        assertEquals(-1, grid.indexAt(82, 5, 100, 520, 0), "the gap between two cards");
        assertEquals(12, grid.indexAt(5, 70 * 2 + 5, 100, 520, 0));
        assertEquals(12, grid.indexAt(5, 5, 100, 520, 140), "scrolled by two rows");
        assertEquals(-1, grid.indexAt(5, 70 * 40, 100, 520, 0), "past the last item");
    }
}
