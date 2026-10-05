package io.theprisons.modules.general.look;

/**
 * Turns any texture into the mod's comic / MMORPG look while it is loaded (nothing of Mojang's art is shipped):
 * <ol>
 *     <li>pixel-art upscale (EPX / Scale2x, once or twice): smoother diagonals, more room for detail;</li>
 *     <li>livelier colours: more saturation for coloured pixels, lightness pulled towards cel bands;</li>
 *     <li>comic ink: a dark line where neighbouring colours differ strongly and around cut-out shapes;</li>
 *     <li>a light rim on the top-left edge of shapes.</li>
 * </ol>
 * Works on ARGB int arrays (row-major), so it is plain Java and testable.
 */
public final class ComicFilter {
    /** Lightness difference (0..1) from which two neighbouring pixels get an ink line between them. */
    static final float INK_CONTRAST = 0.20F;

    private ComicFilter() {
    }

    /** Upscale factor (1, 2 or 4) for a sprite / texture of this size. */
    public static int factor(int width, int height, boolean atlasSprite) {
        int max = Math.max(width, height);
        if (atlasSprite) {
            return max <= 32 ? 4 : 1;
        }
        if (max <= 64) {
            return 4;
        }
        return max <= 256 ? 2 : 1;
    }

    /** Scale2x (EPX): every pixel becomes 2x2, corners take a neighbour's colour where two neighbours agree. */
    public static int[] scale2x(int[] src, int w, int h) {
        int[] out = new int[w * h * 4];
        int ow = w * 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src[y * w + x];
                int a = src[Math.max(0, y - 1) * w + x];
                int b = src[y * w + Math.min(w - 1, x + 1)];
                int c = src[y * w + Math.max(0, x - 1)];
                int d = src[Math.min(h - 1, y + 1) * w + x];
                int e0 = p;
                int e1 = p;
                int e2 = p;
                int e3 = p;
                if (c == a && c != d && a != b) {
                    e0 = a;
                }
                if (a == b && a != c && b != d) {
                    e1 = b;
                }
                if (d == c && d != b && c != a) {
                    e2 = c;
                }
                if (b == d && b != a && d != c) {
                    e3 = d;
                }
                int o = (y * 2) * ow + x * 2;
                out[o] = e0;
                out[o + 1] = e1;
                out[o + ow] = e2;
                out[o + ow + 1] = e3;
            }
        }
        return out;
    }

    /** Upscales by {@code factor} (1, 2 or 4) and applies the comic style. */
    public static int[] process(int[] src, int w, int h, int factor) {
        int[] px = src;
        int cw = w;
        int ch = h;
        for (int f = factor; f > 1; f /= 2) {
            px = scale2x(px, cw, ch);
            cw *= 2;
            ch *= 2;
        }
        return stylize(px, cw, ch, factor);
    }

    /** Colours, ink and rim light on an (already upscaled) image. {@code cell} = size of one source pixel. */
    static int[] stylize(int[] px, int w, int h, int cell) {
        int n = w * h;
        float[] light = new float[n];
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            int a = c >>> 24;
            if (a == 0) {
                out[i] = c;
                continue;
            }
            float[] hsl = hsl(c);
            if (hsl[1] > 0.08F) {
                hsl[1] = Math.min(1.0F, hsl[1] * 1.3F + 0.05F);
            }
            float band = Math.round(hsl[2] * 7.0F) / 7.0F;
            hsl[2] = hsl[2] * 0.45F + band * 0.55F;
            light[i] = hsl[2];
            out[i] = (a << 24) | rgb(hsl);
        }
        int step = Math.max(1, cell / 2);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int a = out[i] >>> 24;
                if (a == 0) {
                    continue;
                }
                boolean edge = transparent(px, w, h, x + 1, y) || transparent(px, w, h, x - 1, y)
                        || transparent(px, w, h, x, y + 1) || transparent(px, w, h, x, y - 1);
                if (edge) {
                    out[i] = (a << 24) | darken(out[i], 0.55F);
                    continue;
                }
                // ink on the darker side of a strong colour step (right / down neighbours)
                boolean ink = false;
                if (x + 1 < w && (px[i + 1] >>> 24) != 0 && light[i] + INK_CONTRAST < light[i + 1]) {
                    ink = true;
                }
                if (x > 0 && (px[i - 1] >>> 24) != 0 && light[i] + INK_CONTRAST < light[i - 1]) {
                    ink = true;
                }
                if (y + 1 < h && (px[i + w] >>> 24) != 0 && light[i] + INK_CONTRAST < light[i + w]) {
                    ink = true;
                }
                if (y > 0 && (px[i - w] >>> 24) != 0 && light[i] + INK_CONTRAST < light[i - w]) {
                    ink = true;
                }
                if (ink) {
                    out[i] = (a << 24) | darken(out[i], 0.40F);
                } else if (transparent(px, w, h, x - step, y - step) || transparent(px, w, h, x, y - step)) {
                    out[i] = (a << 24) | lighten(out[i], 0.22F); // rim light on the top-left of shapes
                }
            }
        }
        return out;
    }

    private static boolean transparent(int[] px, int w, int h, int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h && (px[y * w + x] >>> 24) < 16;
    }

    static int darken(int argb, float amount) {
        int r = Math.round(((argb >> 16) & 0xFF) * (1.0F - amount));
        int g = Math.round(((argb >> 8) & 0xFF) * (1.0F - amount));
        int b = Math.round((argb & 0xFF) * (1.0F - amount));
        return (r << 16) | (g << 8) | b;
    }

    static int lighten(int argb, float amount) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        r += Math.round((255 - r) * amount);
        g += Math.round((255 - g) * amount);
        b += Math.round((255 - b) * amount);
        return (r << 16) | (g << 8) | b;
    }

    private static float[] hsl(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255.0F;
        float g = ((argb >> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2.0F;
        float h = 0;
        float s = 0;
        if (max != min) {
            float d = max - min;
            s = l > 0.5F ? d / (2.0F - max - min) : d / (max + min);
            if (max == r) {
                h = (g - b) / d + (g < b ? 6 : 0);
            } else if (max == g) {
                h = (b - r) / d + 2;
            } else {
                h = (r - g) / d + 4;
            }
            h /= 6.0F;
        }
        return new float[]{h, s, l};
    }

    private static int rgb(float[] hsl) {
        float h = hsl[0];
        float s = hsl[1];
        float l = Math.max(0.0F, Math.min(1.0F, hsl[2]));
        float r;
        float g;
        float b;
        if (s == 0) {
            r = g = b = l;
        } else {
            float q = l < 0.5F ? l * (1 + s) : l + s - l * s;
            float p = 2 * l - q;
            r = hue(p, q, h + 1.0F / 3);
            g = hue(p, q, h);
            b = hue(p, q, h - 1.0F / 3);
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) {
            t += 1;
        }
        if (t > 1) {
            t -= 1;
        }
        if (t < 1.0F / 6) {
            return p + (q - p) * 6 * t;
        }
        if (t < 1.0F / 2) {
            return q;
        }
        if (t < 2.0F / 3) {
            return p + (q - p) * (2.0F / 3 - t) * 6;
        }
        return p;
    }
}
