package io.theprisons.modules.qol.bandit;

import net.minecraft.client.gui.DrawContext;

/** Shooter-style crosshairs for the spear. */
public enum CrosshairPreset {
    SHOOTER("Shooter (gap + dot)"),
    DOT("Dot"),
    CROSS("Cross"),
    T_SHAPE("T-shape"),
    CIRCLE("Circle + dot"),
    CHEVRON("Chevron"),
    SNIPER("Sniper"),
    SPEAR("Spear tip");

    private final String label;

    CrosshairPreset(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * @param gap     free space around the centre in px (already including spread / recoil)
     * @param thick   line thickness
     * @param outline black 1px outline
     */
    public void draw(DrawContext c, int cx, int cy, int size, int gap, int thick, int color, boolean outline, boolean dot) {
        int lo = -(thick / 2);
        int hi = lo + thick;
        switch (this) {
            case SHOOTER -> {
                bar(c, cx - gap - size, cy + lo, cx - gap, cy + hi, color, outline);
                bar(c, cx + gap, cy + lo, cx + gap + size, cy + hi, color, outline);
                bar(c, cx + lo, cy - gap - size, cx + hi, cy - gap, color, outline);
                bar(c, cx + lo, cy + gap, cx + hi, cy + gap + size, color, outline);
            }
            case DOT -> dot = true;
            case CROSS -> {
                bar(c, cx - size, cy + lo, cx + size + 1, cy + hi, color, outline);
                bar(c, cx + lo, cy - size, cx + hi, cy + size + 1, color, outline);
            }
            case T_SHAPE -> {
                bar(c, cx - gap - size, cy + lo, cx - gap, cy + hi, color, outline);
                bar(c, cx + gap, cy + lo, cx + gap + size, cy + hi, color, outline);
                bar(c, cx + lo, cy + gap, cx + hi, cy + gap + size, color, outline);
            }
            case CIRCLE -> ring(c, cx, cy, size + gap, color, outline);
            case CHEVRON -> {
                for (int i = 0; i <= size; i++) {
                    bar(c, cx - i - thick / 2, cy + gap + i, cx - i - thick / 2 + thick, cy + gap + i + thick, color, outline);
                    bar(c, cx + i - thick / 2, cy + gap + i, cx + i - thick / 2 + thick, cy + gap + i + thick, color, outline);
                }
            }
            case SNIPER -> {
                bar(c, cx - size * 3, cy, cx - gap, cy + 1, color, outline);
                bar(c, cx + gap, cy, cx + size * 3, cy + 1, color, outline);
                bar(c, cx, cy - size * 3, cx + 1, cy - gap, color, outline);
                bar(c, cx, cy + gap, cx + 1, cy + size * 3, color, outline);
                ring(c, cx, cy, size + gap + 3, color, false);
            }
            case SPEAR -> {
                bar(c, cx + lo, cy - gap, cx + hi, cy + gap + size * 2, color, outline);
                for (int i = 0; i <= size; i++) {
                    bar(c, cx - i, cy - gap - size + i, cx + i + 1, cy - gap - size + i + 1, color, outline);
                }
                bar(c, cx - size, cy + gap + size, cx + size + 1, cy + gap + size + 1, color, outline);
            }
        }
        if (dot) {
            bar(c, cx + lo, cy + lo, cx + hi, cy + hi, color, outline);
        }
    }

    static void bar(DrawContext c, int x0, int y0, int x1, int y1, int color, boolean outline) {
        if (outline) {
            c.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xAA000000);
        }
        c.fill(x0, y0, x1, y1, color);
    }

    static void ring(DrawContext c, int cx, int cy, int r, int color, boolean outline) {
        int n = Math.max(24, r * 6);
        for (int i = 0; i < n; i++) {
            double a = i * (2 * Math.PI / n);
            int px = cx + (int) Math.round(Math.cos(a) * r);
            int py = cy + (int) Math.round(Math.sin(a) * r);
            bar(c, px, py, px + 1, py + 1, color, outline);
        }
    }
}
