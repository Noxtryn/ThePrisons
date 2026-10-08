package io.theprisons.items;

/**
 * Where the search bar and the item panel go on the player inventory screen, from the window size only (pure, so it is tested).
 *
 * <p>The bar sits just above the hotbar, between the inventory and the hotbar; when there is no room there (a tall gui scale) it moves directly above the
 * inventory. The panel takes the free space beside the inventory - the wider side - and never overlaps it. Cards shrink to fit the panel (at least two columns).
 */
public record InventoryListLayout(int barX, int barY, int barW, int barH, int panelX, int panelY, int panelW, int panelH, int cardW, int cardH, int cols,
                                  boolean panelFits) {
    public static final int INVENTORY_W = 176;
    public static final int INVENTORY_H = 166;
    public static final int BAR_W = 170;
    public static final int BAR_H = 18;
    public static final int HOTBAR_H = 22;
    public static final int GAP = 14;
    public static final int MARGIN = 6;
    public static final int PAD = 8;
    public static final int MIN_PANEL_W = 90;
    public static final int CARD_GAP = 4;
    public static final int MIN_CARD_W = 52;
    public static final int MAX_CARD_W = 78;

    public static InventoryListLayout of(int screenW, int screenH) {
        int left = (screenW - INVENTORY_W) / 2;
        int right = left + INVENTORY_W;
        int top = (screenH - INVENTORY_H) / 2;
        int bottom = top + INVENTORY_H;
        int barX = screenW / 2 - BAR_W / 2;
        int aboveHotbar = screenH - HOTBAR_H - 4 - BAR_H - 4;
        int barY = aboveHotbar >= bottom + 2 ? aboveHotbar : Math.max(2, top - BAR_H - 4);

        int rightW = screenW - MARGIN - (right + GAP);
        int leftW = left - GAP - MARGIN;
        boolean useRight = rightW >= leftW;
        int panelW = Math.max(useRight ? rightW : leftW, 0);
        int panelX = useRight ? right + GAP : MARGIN;
        boolean fits = panelW >= MIN_PANEL_W;
        int usable = Math.max(0, panelW - PAD * 2);
        int cols = Math.max(2, (usable + CARD_GAP) / (MIN_CARD_W + CARD_GAP));
        int cardW = Math.min(MAX_CARD_W, Math.max(MIN_CARD_W, (usable - (cols - 1) * CARD_GAP) / cols));
        int cardH = cardW + 24;      // icon, two name lines, the price line, the tier line
        return new InventoryListLayout(barX, barY, BAR_W, BAR_H, panelX, MARGIN, panelW, Math.max(0, screenH - MARGIN * 2), cardW, cardH, cols, fits);
    }

    /** The grid area inside the panel, below {@code headerH} and above {@code footerH}. */
    public int gridX() {
        return panelX + (panelW - cols * cardW - (cols - 1) * CARD_GAP) / 2;
    }

    public int gridW() {
        return cols * cardW + (cols - 1) * CARD_GAP;
    }

    public boolean overBar(double mx, double my) {
        return mx >= barX && mx < barX + barW && my >= barY && my < barY + barH;
    }

    public boolean overPanel(double mx, double my) {
        return panelFits && mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + panelH;
    }
}
