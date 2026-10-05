package com.freelocs.theprisons.modules.general.tunnel;

import net.minecraft.client.gui.DrawContext;

/** The magic carpet under the player's feet: a woven rug in perspective with a wave in the cloth, a medallion and fringe. */
final class TunnelCarpet {
    private static final int GOLD = 0xFFC93C;
    private static final int TEAL = 0x2CC6B8;

    private TunnelCarpet() {
    }

    static int rows(int h) {
        return Math.max(10, Math.round(h * 0.125F));
    }

    /**
     * @param cx       centre x (fixed to the character)
     * @param footY    y of the feet (the carpet's bobbing is already in it)
     * @param dissolve rows from the back up to this fraction are gone (0 = whole carpet)
     * @param scale    1 = full size (grows from 0 while it appears)
     */
    static void draw(DrawContext c, int cx, int footY, int pw, int h, double t, float dissolve, float scale, double steer) {
        if (scale <= 0.02F) {
            return;
        }
        int rows = rows(h);
        int top = footY - Math.round(h * 0.064F);
        int alpha = Math.round(255 * Math.min(1.0F, scale * 1.4F));
        for (int k = 0; k < rows; k++) {
            float r = k / (float) (rows - 1);
            if (r < dissolve) {
                continue;
            }
            float half = (pw * 0.62F + (pw * 1.05F - pw * 0.62F) * r) * scale;
            double wave = Math.sin(t * 2.4 + r * 5.0) * 2.0 * (0.4 + r);
            double tilt = steer * (r - 0.5) * pw * 0.25;
            int xc = (int) Math.round(cx + wave + tilt);
            int y = footY + Math.round((top + k - footY) * scale);
            int x0 = Math.round(xc - half);
            int x1 = Math.round(xc + half);
            int inset = Math.max(2, Math.round(half * 0.09F));
            int shade = Math.round(18 * r);
            int field = (0xB3 + shade) << 16 | (0x26 + shade / 2) << 8 | (0x3E + shade / 2);
            c.fill(x0, y, x1, y + 1, (alpha << 24) | GOLD);
            c.fill(x0 + inset, y, x1 - inset, y + 1, (alpha << 24) | field);
            c.fill(x0 + inset, y, x0 + inset + 1, y + 1, (alpha << 24) | TEAL);
            c.fill(x1 - inset - 1, y, x1 - inset, y + 1, (alpha << 24) | TEAL);
            float hd = half * 0.30F * (1.0F - Math.abs(r - 0.5F) * 1.7F);
            if (hd > 1.0F) {
                c.fill(Math.round(xc - hd), y, Math.round(xc + hd), y + 1, (Math.round(alpha * 0.9F) << 24) | GOLD);
                c.fill(Math.round(xc - hd * 0.45F), y, Math.round(xc + hd * 0.45F), y + 1, (alpha << 24) | TEAL);
            }
            if (k == rows - 1) {
                for (int i = 0; i <= 12; i++) {
                    int tx = x0 + (x1 - x0) * i / 12;
                    c.fill(tx, y + 1, tx + 1, y + 3 + (i & 1) + Math.round(Math.max(0.0F, (float) Math.sin(t * 5.0 + i)) * 1.5F), (alpha << 24) | GOLD);
                }
            }
        }
        // a soft glow below the carpet: it floats
        int gy = footY + Math.round(h * 0.06F);
        for (int i = 0; i < 4; i++) {
            int half = Math.round(pw * (0.95F - i * 0.14F) * scale);
            c.fill(cx - half, gy + 4 + i * 2, cx + half, gy + 6 + i * 2, (Math.round((26 - i * 5) * scale) << 24) | 0xFFB0F0);
        }
    }
}
