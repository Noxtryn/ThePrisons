package com.freelocs.theprisons.modules.general.tunnel;

import net.minecraft.client.gui.DrawContext;

/**
 * Shapes and particles of the tunnel (GUI pixels): a pooled particle system without allocations per frame, convex polygon
 * fill by scanlines, the pink crystals, the light-blue asteroids and the shot's bolt.
 */
final class TunnelFx {
    static final int KIND_SPARK = 0;
    static final int KIND_SHARD = 1;
    static final int KIND_DUST = 2;
    static final int KIND_FLASH = 3;

    private static final int MAX = 260;
    private final float[] px = new float[MAX];
    private final float[] py = new float[MAX];
    private final float[] vx = new float[MAX];
    private final float[] vy = new float[MAX];
    private final float[] age = new float[MAX];
    private final float[] life = new float[MAX];
    private final float[] size = new float[MAX];
    private final int[] rgb = new int[MAX];
    private final byte[] kind = new byte[MAX];
    private int n;
    /** Scale of the amount of particles (quality). */
    float density = 1.0F;

    void clear() {
        n = 0;
    }

    int count() {
        return n;
    }

    void add(float x, float y, float velX, float velY, float lifeSeconds, float s, int colour, int k) {
        if (n >= MAX) {
            return;
        }
        px[n] = x;
        py[n] = y;
        vx[n] = velX;
        vy[n] = velY;
        age[n] = 0.0F;
        life[n] = lifeSeconds;
        size[n] = s;
        rgb[n] = colour & 0xFFFFFF;
        kind[n] = (byte) k;
        n++;
    }

    void update(float dt) {
        for (int i = 0; i < n; ) {
            age[i] += dt;
            if (age[i] >= life[i]) {
                n--;
                px[i] = px[n]; py[i] = py[n]; vx[i] = vx[n]; vy[i] = vy[n]; age[i] = age[n]; life[i] = life[n];
                size[i] = size[n]; rgb[i] = rgb[n]; kind[i] = kind[n];
                continue;
            }
            if (kind[i] == KIND_SHARD) {
                vy[i] += 70.0F * dt;
            } else if (kind[i] == KIND_DUST) {
                vx[i] *= 1.0F - 1.6F * dt;
                vy[i] *= 1.0F - 1.6F * dt;
            }
            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            i++;
        }
    }

    void draw(DrawContext c) {
        for (int i = 0; i < n; i++) {
            float f = age[i] / life[i];
            int a = Math.round(235.0F * (1.0F - f) * Math.min(1.0F, f * 10.0F + 0.2F));
            if (a < 6) {
                continue;
            }
            int x = Math.round(px[i]);
            int y = Math.round(py[i]);
            int s = Math.max(1, Math.round(size[i]));
            if (kind[i] == KIND_FLASH) {
                int d = Math.round(size[i] * (0.5F + f));
                c.fill(px_(i) - d, py_(i) - 1, px_(i) + d + 1, py_(i) + 2, ((a / 2) << 24) | rgb[i]);
                c.fill(px_(i) - 1, py_(i) - d, px_(i) + 2, py_(i) + d + 1, ((a / 2) << 24) | rgb[i]);
                c.fill(px_(i) - d / 2, py_(i) - d / 2, px_(i) + d / 2 + 1, py_(i) + d / 2 + 1, ((a / 3) << 24) | rgb[i]);
            } else if (kind[i] == KIND_DUST) {
                int d = Math.round(s * (1.0F + f * 1.6F));
                c.fill(x - d, y - d, x + d, y + d, ((a / 4) << 24) | rgb[i]);
            } else if (kind[i] == KIND_SHARD) {
                c.fill(x, y, x + s + 1, y + Math.max(1, s * 2 / 3), (a << 24) | rgb[i]);
            } else {
                c.fill(x, y, x + s, y + s, (a << 24) | rgb[i]);
            }
        }
    }

    private int px_(int i) {
        return Math.round(px[i]);
    }

    private int py_(int i) {
        return Math.round(py[i]);
    }

    // ── Shapes ───────────────────────────────────────────────────────────────

    private static final float[] TX = new float[16];
    private static final float[] TY = new float[16];

    /** Fills a convex polygon (scanlines). */
    static void poly(DrawContext c, float[] xs, float[] ys, int count, int argb) {
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        for (int y = (int) Math.ceil(minY); y <= (int) Math.floor(maxY); y++) {
            float xl = Float.MAX_VALUE;
            float xr = -Float.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                int j = (i + 1) % count;
                float y0 = ys[i];
                float y1 = ys[j];
                if (y0 == y1 || y < Math.min(y0, y1) || y > Math.max(y0, y1)) {
                    continue;
                }
                float x = xs[i] + (y - y0) * (xs[j] - xs[i]) / (y1 - y0);
                xl = Math.min(xl, x);
                xr = Math.max(xr, x);
            }
            if (xr >= xl) {
                c.fill(Math.round(xl), y, Math.round(xr) + 1, y + 1, argb);
            }
        }
    }

    private static void quad(DrawContext c, float x, float y, float s, float[] rx, float[] ry, int n, int argb) {
        for (int i = 0; i < n; i++) {
            TX[i] = x + rx[i] * s;
            TY[i] = y + ry[i] * s;
        }
        poly(c, TX, TY, n, argb);
    }

    private static final float[] PRISM_L_X = {0.0F, -0.34F, -0.34F, 0.0F};
    private static final float[] PRISM_R_X = {0.0F, 0.34F, 0.34F, 0.0F};
    private static final float[] PRISM_Y = {-1.0F, -0.62F, 0.0F, 0.16F};
    private static final float[] SHINE_X = {0.0F, 0.12F, 0.0F, -0.12F};
    private static final float[] SHINE_Y = {-1.0F, -0.72F, -0.52F, -0.72F};

    /** A small pink crystal structure standing at (x, baseY): one big prism and two small ones. {@code a} = opacity 0..1. */
    static void crystal(DrawContext c, float x, float baseY, float s, float a, double t) {
        if (a <= 0.02F || s < 2.0F) {
            return;
        }
        int al = Math.round(255 * Math.min(1.0F, a));
        float pulse = (float) (0.5 + 0.5 * Math.sin(t * 4.0));
        int glow = Math.round((34 + 26 * pulse) * a);
        c.fill(Math.round(x - s * 0.75F), Math.round(baseY - s * 0.95F), Math.round(x + s * 0.75F), Math.round(baseY + s * 0.2F), (glow << 24) | 0xFF4FB8);
        float[][] cluster = {{0.0F, 0.0F, 1.0F}, {-0.52F, 0.1F, 0.58F}, {0.5F, 0.12F, 0.46F}};
        for (float[] p : cluster) {
            float cx = x + p[0] * s;
            float by = baseY + p[1] * s;
            float cs = s * p[2];
            quad(c, cx, by, cs, PRISM_L_X, PRISM_Y, 4, (al << 24) | 0xC4208A);
            quad(c, cx, by, cs, PRISM_R_X, PRISM_Y, 4, (al << 24) | 0xFF6FC9);
            quad(c, cx, by, cs, SHINE_X, SHINE_Y, 4, (Math.round(al * 0.9F) << 24) | 0xFFD6F0);
        }
    }

    private static final int ROCK_POINTS = 11;
    private static final float[] RX = new float[ROCK_POINTS];
    private static final float[] RY = new float[ROCK_POINTS];

    private static float hash(int seed, int i) {
        int h = seed * 374761393 + i * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0F;
    }

    /** A light-blue asteroid: lumpy rock with a lit and a shaded side, craters and a faint halo. */
    static void asteroid(DrawContext c, float x, float y, float r, double rot, int seed, float a) {
        if (a <= 0.02F || r < 2.0F) {
            return;
        }
        int al = Math.round(255 * Math.min(1.0F, a));
        for (int i = 0; i < ROCK_POINTS; i++) {
            double ang = rot + i * (Math.PI * 2 / ROCK_POINTS);
            float rr = r * (0.80F + 0.34F * hash(seed, i));
            RX[i] = x + (float) Math.cos(ang) * rr;
            RY[i] = y + (float) Math.sin(ang) * rr;
        }
        // halo
        for (int i = 0; i < ROCK_POINTS; i++) {
            TX[i] = x + (RX[i] - x) * 1.35F;
            TY[i] = y + (RY[i] - y) * 1.35F;
        }
        poly(c, TX, TY, ROCK_POINTS, (Math.round(34 * a) << 24) | 0x8FD8FF);
        poly(c, RX, RY, ROCK_POINTS, (al << 24) | 0x74C4EE);
        // shaded side: the part facing down-right
        float[] sx = new float[]{x, 0, 0, 0, 0, 0};
        float[] sy = new float[]{y, 0, 0, 0, 0, 0};
        int found = 1;
        for (int i = 0; i < ROCK_POINTS && found < 6; i++) {
            float dx = RX[i] - x;
            float dy = RY[i] - y;
            if (dx + dy > r * 0.35F) {
                sx[found] = x + dx;
                sy[found] = y + dy;
                found++;
            }
        }
        if (found >= 3) {
            poly(c, sx, sy, found, (al << 24) | 0x3F86C4);
        }
        // lit side: the part facing up-left
        float[] lx = new float[]{x, 0, 0, 0, 0, 0};
        float[] ly = new float[]{y, 0, 0, 0, 0, 0};
        int lit = 1;
        for (int i = 0; i < ROCK_POINTS && lit < 6; i++) {
            float dx = RX[i] - x;
            float dy = RY[i] - y;
            if (dx + dy < -r * 0.55F) {
                lx[lit] = x + dx * 0.92F;
                ly[lit] = y + dy * 0.92F;
                lit++;
            }
        }
        if (lit >= 3) {
            poly(c, lx, ly, lit, (Math.round(al * 0.85F) << 24) | 0xC6EEFF);
        }
        // craters
        for (int k = 0; k < 3; k++) {
            double ca = rot * 0.6 + k * 2.1 + hash(seed, 20 + k) * 3.0;
            float cr = r * (0.13F + 0.07F * hash(seed, 30 + k));
            float cx = x + (float) Math.cos(ca) * r * 0.42F;
            float cy = y + (float) Math.sin(ca) * r * 0.42F;
            for (int i = 0; i < 6; i++) {
                TX[i] = cx + (float) Math.cos(i * Math.PI / 3) * cr;
                TY[i] = cy + (float) Math.sin(i * Math.PI / 3) * cr * 0.8F;
            }
            poly(c, TX, TY, 6, (Math.round(al * 0.55F) << 24) | 0x2E6FA8);
        }
    }

    /** The shot: a short bright streak ending at (x, y) coming from direction (dx, dy) (normalised). */
    static void bolt(DrawContext c, float x, float y, float dx, float dy, int length, int rgb) {
        for (int i = 0; i < length; i += 2) {
            float f = 1.0F - i / (float) length;
            int px = Math.round(x - dx * i);
            int py = Math.round(y - dy * i);
            int a = Math.round(240 * f);
            c.fill(px - 1, py - 1, px + 2, py + 2, ((a / 3) << 24) | rgb);
            c.fill(px, py, px + 1, py + 1, (a << 24) | 0xFFFFFF);
        }
    }
}
