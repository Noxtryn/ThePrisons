package io.theprisons.items;

/**
 * The layout of a scrolling grid of equally sized cards, and which of them are visible - computed from numbers only (no widgets), so 800 items still
 * cost the draw of the ~40 on screen. The columns follow the width; the visible rows follow the scroll offset and the height, plus some overscan rows so a
 * fast scroll never shows an empty edge.
 */
public final class VirtualGrid {
    /** @param first first card index to draw, @param last last card index to draw (inclusive), -1 / -1 when nothing is visible */
    public record Range(int first, int last, int firstRow, int lastRow) {
        public int count() {
            return first < 0 || last < first ? 0 : last - first + 1;
        }
    }

    private final int cardW;
    private final int cardH;
    private final int gap;
    private final int overscanRows;

    public VirtualGrid(int cardW, int cardH, int gap, int overscanRows) {
        this.cardW = cardW;
        this.cardH = cardH;
        this.gap = gap;
        this.overscanRows = overscanRows;
    }

    public int columns(int viewportWidth) {
        return Math.max(1, (viewportWidth + gap) / (cardW + gap));
    }

    public int rows(int itemCount, int viewportWidth) {
        int cols = columns(viewportWidth);
        return (itemCount + cols - 1) / cols;
    }

    public int contentHeight(int itemCount, int viewportWidth) {
        int rows = rows(itemCount, viewportWidth);
        return rows == 0 ? 0 : rows * (cardH + gap) - gap;
    }

    /** The largest useful scroll offset. */
    public int maxScroll(int itemCount, int viewportWidth, int viewportHeight) {
        return Math.max(0, contentHeight(itemCount, viewportWidth) - viewportHeight);
    }

    public int clampScroll(int scroll, int itemCount, int viewportWidth, int viewportHeight) {
        return Math.max(0, Math.min(scroll, maxScroll(itemCount, viewportWidth, viewportHeight)));
    }

    public Range visible(int itemCount, int viewportWidth, int viewportHeight, int scroll) {
        if (itemCount <= 0 || viewportHeight <= 0) {
            return new Range(-1, -1, -1, -1);
        }
        int cols = columns(viewportWidth);
        int rows = rows(itemCount, viewportWidth);
        int s = clampScroll(scroll, itemCount, viewportWidth, viewportHeight);
        int rowH = cardH + gap;
        int firstRow = Math.max(0, s / rowH - overscanRows);
        int lastRow = Math.min(rows - 1, (s + viewportHeight - 1) / rowH + overscanRows);
        int first = firstRow * cols;
        int last = Math.min(itemCount - 1, (lastRow + 1) * cols - 1);
        return new Range(first, last, firstRow, lastRow);
    }

    /** Left edge of the card with this index relative to the viewport, ignoring scroll. */
    public int x(int index, int viewportWidth) {
        return (index % columns(viewportWidth)) * (cardW + gap);
    }

    /** Top edge of the card relative to the viewport content (subtract the scroll to get the screen position). */
    public int y(int index, int viewportWidth) {
        return (index / columns(viewportWidth)) * (cardH + gap);
    }

    /** The card under a point of the viewport, or -1. */
    public int indexAt(int px, int py, int itemCount, int viewportWidth, int scroll) {
        if (px < 0 || py < 0) {
            return -1;
        }
        int cols = columns(viewportWidth);
        int col = px / (cardW + gap);
        int row = (py + scroll) / (cardH + gap);
        if (col >= cols || px % (cardW + gap) >= cardW || (py + scroll) % (cardH + gap) >= cardH) {
            return -1;
        }
        int idx = row * cols + col;
        return idx < itemCount ? idx : -1;
    }
}
