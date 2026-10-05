"""
Small cartoon renderer for 32x32 item textures: every part is a filled shape that gets cel shading (a light band on
the top-left edge, a dark band on the bottom-right edge, a soft top-to-bottom gradient), a dark outline drawn over the
parts below it (the cartoon inner lines) and optional white specular dots. An optional glow halo goes around the whole
item (MMORPG gear of rare materials).
"""
import colorsys
import math

from PIL import Image, ImageDraw

SIZE = 32


def rgb(hex_value):
    return ((hex_value >> 16) & 0xFF, (hex_value >> 8) & 0xFF, hex_value & 0xFF)


def shade(colour, dl, ds=0.0):
    h, l, s = colorsys.rgb_to_hls(*(c / 255.0 for c in colour[:3]))
    r, g, b = colorsys.hls_to_rgb(h, max(0.03, min(0.97, l + dl)), max(0.0, min(1.0, s + ds)))
    return (round(r * 255), round(g * 255), round(b * 255))


def outline_of(colour):
    """Cartoon outline: a very dark, slightly saturated version of the colour (not flat black)."""
    h, l, s = colorsys.rgb_to_hls(*(c / 255.0 for c in colour[:3]))
    r, g, b = colorsys.hls_to_rgb(h, 0.09, min(1.0, s * 0.6 + 0.1))
    return (round(r * 255), round(g * 255), round(b * 255))


class Canvas:
    def __init__(self, size=SIZE):
        self.size = size
        self.img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        self.union = Image.new("L", (size, size), 0)

    # ── shapes ──────────────────────────────────────────────────────────────

    def part(self, draw_fn, colour, outline=True, shading=True, outline_colour=None, light=0.16, dark=-0.20):
        mask = Image.new("L", (self.size, self.size), 0)
        draw_fn(ImageDraw.Draw(mask))
        m = mask.load()
        px = self.img.load()
        n = self.size
        base = rgb(colour) if isinstance(colour, int) else colour
        hi = shade(base, light)
        lo = shade(base, dark)
        inside = lambda x, y: 0 <= x < n and 0 <= y < n and m[x, y] > 0
        ys = [y for y in range(n) for x in range(n) if m[x, y]]
        if not ys:
            return self
        top, bottom = min(ys), max(ys)
        for y in range(n):
            for x in range(n):
                if not m[x, y]:
                    continue
                c = base
                if shading:
                    t = (y - top) / max(1, bottom - top)
                    c = shade(base, 0.06 - 0.12 * t)
                    if not inside(x - 1, y - 1) or not inside(x - 1, y):
                        c = hi
                    elif not inside(x + 1, y + 1) or not inside(x + 1, y):
                        c = lo
                px[x, y] = c + (255,)
        if outline:
            oc = outline_colour if outline_colour is not None else outline_of(base)
            oc = rgb(oc) if isinstance(oc, int) else oc
            for y in range(n):
                for x in range(n):
                    if m[x, y]:
                        continue
                    if inside(x - 1, y) or inside(x + 1, y) or inside(x, y - 1) or inside(x, y + 1):
                        px[x, y] = oc + (255,)
        u = self.union.load()
        for y in range(n):
            for x in range(n):
                if m[x, y] or px[x, y][3]:
                    u[x, y] = 255
        return self

    def poly(self, points, colour, **kw):
        return self.part(lambda d: d.polygon([tuple(map(round, p)) for p in points], fill=255), colour, **kw)

    def ellipse(self, box, colour, **kw):
        return self.part(lambda d: d.ellipse([round(v) for v in box], fill=255), colour, **kw)

    def rect(self, box, colour, **kw):
        return self.part(lambda d: d.rectangle([round(v) for v in box], fill=255), colour, **kw)

    def line(self, points, colour, width=1, **kw):
        kw.setdefault("outline", False)
        kw.setdefault("shading", False)
        return self.part(lambda d: d.line([tuple(map(round, p)) for p in points], fill=255, width=width), colour, **kw)

    def pixel(self, x, y, colour, alpha=255):
        c = rgb(colour) if isinstance(colour, int) else colour
        if 0 <= x < self.size and 0 <= y < self.size:
            self.img.putpixel((int(x), int(y)), c + (alpha,))

    def sparkle(self, x, y, colour=(255, 255, 255)):
        """A small 4-point star (plus shape)."""
        for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            self.pixel(x + dx, y + dy, colour, 255 if (dx, dy) == (0, 0) else 200)
        return self

    def gem(self, cx, cy, r, colour):
        """Faceted gem: shaded disc, white highlight top-left, darker facet bottom-right."""
        self.ellipse([cx - r, cy - r, cx + r, cy + r], colour)
        self.pixel(cx - max(1, r // 2), cy - max(1, r // 2), (255, 255, 255))
        if r >= 2:
            self.pixel(cx - max(1, r // 2) + 1, cy - max(1, r // 2), shade(rgb(colour), 0.35))
        return self

    # ── finishing ───────────────────────────────────────────────────────────

    def glow(self, colour, strength=110):
        """Soft halo around the item (rare gear)."""
        c = rgb(colour) if isinstance(colour, int) else colour
        u = self.union.load()
        px = self.img.load()
        n = self.size
        halo = []
        for y in range(n):
            for x in range(n):
                if u[x, y]:
                    continue
                d = 99
                for dy in range(-2, 3):
                    for dx in range(-2, 3):
                        xx, yy = x + dx, y + dy
                        if 0 <= xx < n and 0 <= yy < n and u[xx, yy]:
                            d = min(d, max(abs(dx), abs(dy)))
                if d <= 2:
                    halo.append((x, y, strength if d == 1 else strength // 3))
        for x, y, a in halo:
            px[x, y] = c + (a,)
        return self

    def image(self):
        return self.img


# ── helpers for diagonal items (handle bottom-left, tip top-right) ──────────

def diag(origin=(4.5, 27.5)):
    """P(t, w): t along the item axis (towards the top-right), w across it (positive = towards the bottom-right)."""
    ox, oy = origin
    ux, uy = 1 / math.sqrt(2), -1 / math.sqrt(2)
    nx, ny = 1 / math.sqrt(2), 1 / math.sqrt(2)

    def p(t, w=0.0):
        return (ox + t * ux + w * nx, oy + t * uy + w * ny)
    return p
