package io.theprisons.modules.qol.bandit;

import net.minecraft.client.gui.DrawContext;

/** The sight point (visor) drawn where the spear has to be aimed at the target. */
public enum SightStyle {
    BRACKETS("Brackets"),
    DIAMOND("Diamond"),
    RETICLE("Reticle (ring + ticks)"),
    TRIANGLE("Triangle lock");

    private final String label;

    SightStyle(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** @param t animation time in seconds (lock-on pulse); @param lock 0..1 how close the view already is */
    public void draw(DrawContext c, int x, int y, int color, double t, float lock) {
        double pulse = 1.0D + 0.18D * Math.sin(t * 7.0D) * (1.0F - lock);
        int r = (int) Math.round(8 * pulse);
        int col = lock > 0.92F ? 0xFF55FF55 : color;
        switch (this) {
            case BRACKETS -> {
                int l = Math.max(3, r / 2);
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sy = -1; sy <= 1; sy += 2) {
                        int bx = x + sx * r;
                        int by = y + sy * r;
                        c.fill(Math.min(bx, bx - sx * l), by, Math.max(bx, bx - sx * l) + 1, by + 1, col);
                        c.fill(bx, Math.min(by, by - sy * l), bx + 1, Math.max(by, by - sy * l) + 1, col);
                    }
                }
            }
            case DIAMOND -> {
                for (int i = 0; i <= r; i++) {
                    c.fill(x - r + i, y - i, x - r + i + 1, y - i + 1, col);
                    c.fill(x + r - i, y - i, x + r - i + 1, y - i + 1, col);
                    c.fill(x - r + i, y + i, x - r + i + 1, y + i + 1, col);
                    c.fill(x + r - i, y + i, x + r - i + 1, y + i + 1, col);
                }
            }
            case RETICLE -> {
                CrosshairPreset.ring(c, x, y, r, col, false);
                c.fill(x - r - 4, y, x - r + 1, y + 1, col);
                c.fill(x + r, y, x + r + 5, y + 1, col);
                c.fill(x, y - r - 4, x + 1, y - r + 1, col);
                c.fill(x, y + r, x + 1, y + r + 5, col);
            }
            case TRIANGLE -> {
                for (int i = 0; i <= r; i++) {
                    c.fill(x - i, y - r + i * 2 - r / 2, x - i + 1, y - r + i * 2 - r / 2 + 1, col);
                    c.fill(x + i, y - r + i * 2 - r / 2, x + i + 1, y - r + i * 2 - r / 2 + 1, col);
                }
                c.fill(x - r, y + r + r / 2, x + r + 1, y + r + r / 2 + 1, col);
            }
        }
        c.fill(x, y, x + 1, y + 1, col);
    }
}
