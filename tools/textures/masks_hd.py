"""
Masks as full fantasy helms (MMORPG look): a shell around the whole head - a bit larger than the head and than a
worn helmet - with 64x64 art on every side, plus big 3D parts (comb, plume, top hat, antennae, tail fan ...).

Texture sheet 256x128 per mask: front (0,0), back (64,0), right (128,0), left (192,0), top (0,64), bottom (64,64),
shaded colour swatches for the 3D parts in the rest (16x16 each). Model space: the head is 1.6 .. 14.4 on every
axis, north (-z) is the face; the shell reaches -0.6 .. 16.6.
"""
import math
import os
import json

from PIL import Image, ImageDraw

from cartoon import Canvas, rgb, shade

T = 64


def tone(c, dl, a=255):
    return shade(rgb(c) if isinstance(c, int) else c, dl) + (a,)


# ── painting helpers (64x64 face canvases) ──────────────────────────────────

def base_face(colour, dl_top=0.10, dl_bottom=-0.14):
    img = Image.new("RGBA", (T, T), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(T):
        t = y / (T - 1)
        d.line([(0, y), (T - 1, y)], fill=tone(colour, dl_top + (dl_bottom - dl_top) * t))
    # rounded look: darker towards the edges
    px = img.load()
    for y in range(T):
        for x in range(T):
            e = min(x, y, T - 1 - x, T - 1 - y)
            if e < 6:
                r, g, b, a = px[x, y]
                f = 0.72 + 0.28 * e / 6
                px[x, y] = (int(r * f), int(g * f), int(b * f), a)
    d.rectangle([0, 0, T - 1, T - 1], outline=tone(colour, -0.42))
    return img, d


def rivets(d, colour, points):
    for x, y in points:
        d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=tone(colour, -0.3))
        d.ellipse([x - 1, y - 2, x + 1, y], fill=tone(colour, 0.3))


def feathers(d, colours, x0=0, y0=0, w=T, h=T, size=10):
    """Overlapping feather scales, row by row."""
    for row, y in enumerate(range(y0 - size // 2, y0 + h, size // 2 + 1)):
        off = (row % 2) * size // 2
        for x in range(x0 - size + off, x0 + w, size):
            c = colours[(row + x // size) % len(colours)]
            d.pieslice([x, y - size // 2, x + size, y + size], 0, 180, fill=tone(c, 0.0), outline=tone(c, -0.35))
            d.line([(x + size // 2, y), (x + size // 2, y + size // 2)], fill=tone(c, 0.25))


def eye(d, cx, cy, r, iris, angry=False):
    d.ellipse([cx - r - 1, cy - r - 1, cx + r + 1, cy + r + 1], fill=(20, 16, 24, 255))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(250, 248, 240, 255))
    ir = max(2, r * 2 // 3)
    d.ellipse([cx - ir, cy - ir, cx + ir, cy + ir], fill=tone(iris, 0.0))
    d.ellipse([cx - ir // 2, cy - ir // 2, cx + ir // 2, cy + ir // 2], fill=(16, 12, 20, 255))
    d.rectangle([cx - ir + 1, cy - ir + 1, cx - ir + 2, cy - ir + 2], fill=(255, 255, 255, 255))
    if angry:
        d.polygon([(cx - r - 2, cy - r - 3), (cx + r + 2, cy - r + 1), (cx + r + 2, cy - r - 2)], fill=(20, 16, 24, 255))


def glow_line(d, points, colour, width=2):
    d.line(points, fill=tone(colour, -0.1, 120), width=width + 2)
    d.line(points, fill=tone(colour, 0.15), width=width)
    d.line(points, fill=tone(colour, 0.4), width=1)


def flame(d, x, y, h, colours=(0xFF6A1E, 0xFFB02E, 0xFFE84A)):
    for i, c in enumerate(colours):
        s = 1.0 - i * 0.3
        d.polygon([(x, y), (x + 4 * s, y - h * 0.5 * s), (x + 1 * s, y - h * s), (x + 9 * s, y - h * 0.45 * s),
                   (x + 14 * s, y)], fill=tone(c, 0.0))


def checks(d, a, b, size=8):
    for y in range(0, T, size):
        for x in range(0, T, size):
            if (x // size + y // size) % 2:
                d.rectangle([x, y, x + size - 1, y + size - 1], fill=tone(b, 0.0, 120))
    for y in range(0, T, size // 2):
        d.line([(0, y), (T - 1, y)], fill=tone(a, -0.15, 60))


def stripes(d, a, b, size=8):
    for y in range(0, T, size * 2):
        d.rectangle([0, y + size, T - 1, y + size * 2 - 1], fill=tone(b, 0.0))


def runes(d, colour, seed=1):
    import random
    rnd = random.Random(seed)
    for _ in range(6):
        x, y = rnd.randrange(8, 52), rnd.randrange(8, 52)
        pts = [(x, y), (x + rnd.choice([-4, 4]), y + 4), (x, y + 8), (x + rnd.choice([-3, 3]), y + 12)]
        glow_line(d, pts, colour, 1)


# ── themes ───────────────────────────────────────────────────────────────────

def turkey():
    brown, tan, orange, gold, red = 0x8A552E, 0xC08A5A, 0xE8823A, 0xFFC93C, 0xE8283A
    faces = {}
    img, d = base_face(brown)
    feathers(d, [brown, tan, orange], 0, 0, T, 26, 10)
    d.rounded_rectangle([8, 18, 56, 60], 10, fill=tone(tan, 0.05))
    eye(d, 21, 32, 7, gold, angry=True)
    eye(d, 43, 32, 7, gold, angry=True)
    d.polygon([(12, 20), (28, 24), (12, 24)], fill=tone(red, 0.0))
    d.polygon([(52, 20), (36, 24), (52, 24)], fill=tone(red, 0.0))
    faces["front"] = img
    for side in ("back", "right", "left", "top"):
        img, d = base_face(brown)
        feathers(d, [brown, tan, orange, gold], size=12 if side != "top" else 10)
        faces[side] = img
    swatches = [brown, tan, orange, gold, red, 0xFFE84A, 0xFFFFFF, 0x5A3A22]
    # parts: (from, to, swatch index, rotation)
    parts = [
        ([5.5, 5.0, -5.6], [10.5, 9.0, -0.6], 3, None), ([6.5, 5.5, -8.0], [9.5, 8.0, -5.6], 2, None),   # beak
        ([7.0, 0.5, -3.0], [9.0, 5.0, -0.6], 4, None), ([7.3, -1.5, -2.6], [8.7, 0.5, -1.0], 4, None),  # wattle
        ([6.5, 16.6, 1.0], [9.5, 20.0, 4.0], 4, None), ([6.5, 16.6, 4.5], [9.5, 22.0, 8.0], 4, None),   # comb
        ([6.5, 16.6, 8.5], [9.5, 20.5, 11.5], 4, None),
    ]
    colours = [2, 3, 1, 3, 2]
    for i, angle in enumerate((-45, -22.5, 0, 22.5, 45)):                                           # tail fan
        parts.append(([6.5, 8.0, 16.2], [9.5, 27.0, 17.4], colours[i], (8, 8, 16.6, "z", angle)))
        parts.append(([7.0, 24.0, 16.0], [9.0, 27.5, 17.6], 6, (8, 8, 16.6, "z", angle)))
    return faces, swatches, parts


def nitro():
    navy, cyan, orange, yellow, steel = 0x1E2A4A, 0x3CE8FF, 0xFF6A1E, 0xFFE84A, 0x8C94A4
    faces = {}
    img, d = base_face(navy)
    d.rounded_rectangle([4, 14, 60, 34], 8, fill=tone(cyan, -0.1))
    for i in range(4):
        d.line([(10 + i * 12, 16), (4 + i * 12, 32)], fill=tone(cyan, 0.45), width=2)
    d.rounded_rectangle([4, 14, 60, 34], 8, outline=tone(steel, 0.2), width=2)
    for x in range(14, 52, 6):
        d.rectangle([x, 44, x + 3, 56], fill=tone(navy, -0.3))
    faces["front"] = img
    for side in ("right", "left"):
        img, d = base_face(navy)
        for k in range(3):
            flame(d, 6 + k * 4, 58, 34 - k * 6)
        for y in (12, 18, 24):
            d.line([(30, y), (60, y)], fill=tone(cyan, 0.2), width=1)
        faces[side] = img
    img, d = base_face(navy)
    for x in (16, 28, 40):
        d.rounded_rectangle([x, 30, x + 8, 54], 3, fill=tone(navy, -0.35))
        d.rectangle([x + 2, 50, x + 6, 54], fill=tone(orange, 0.1))
    faces["back"] = img
    img, d = base_face(navy)
    d.rectangle([26, 0, 38, 63], fill=tone(orange, 0.0))
    d.rectangle([30, 0, 34, 63], fill=tone(yellow, 0.0))
    faces["top"] = img
    swatches = [navy, cyan, orange, yellow, steel, 0x0A0E1A, 0xFFFFFF, 0x2E3E66]
    parts = [
        ([7.0, 16.6, 4.0], [9.0, 21.0, 15.0], 2, ("x", 8, 16.6, 10, -22.5)),                         # fin
        ([-2.0, 6.0, 5.0], [-0.6, 10.0, 12.0], 4, None), ([16.6, 6.0, 5.0], [18.0, 10.0, 12.0], 4, None),  # thrusters
        ([-2.2, 6.5, 12.0], [-0.4, 9.5, 13.4], 2, None), ([16.4, 6.5, 12.0], [18.2, 9.5, 13.4], 2, None),
        ([0.5, 7.5, -1.4], [15.5, 11.0, -0.6], 1, None),                                              # visor
    ]
    return faces, swatches, parts


def outpost():
    olive, dark, brass, amber, strap = 0x5E6E4A, 0x2E3626, 0xD8A84A, 0xFFB83C, 0x4A3A2A
    faces = {}
    img, d = base_face(olive)
    for cx in (19, 45):
        d.ellipse([cx - 13, 12, cx + 13, 38], fill=tone(brass, 0.0))
        d.ellipse([cx - 10, 15, cx + 10, 35], fill=tone(amber, -0.1))
        d.ellipse([cx - 7, 17, cx - 1, 23], fill=tone(amber, 0.4))
    d.rounded_rectangle([22, 40, 42, 60], 6, fill=tone(dark, 0.0))
    for y in (44, 49, 54):
        d.line([(25, y), (39, y)], fill=tone(olive, 0.1))
    rivets(d, olive, [(6, 6), (58, 6), (6, 58), (58, 58)])
    faces["front"] = img
    for side in ("right", "left", "back"):
        img, d = base_face(olive)
        d.rectangle([0, 26, 63, 34], fill=tone(strap, 0.0))
        if side != "back":
            d.ellipse([20, 18, 44, 42], fill=tone(dark, 0.05), outline=tone(brass, 0.0), width=2)
        else:
            d.rectangle([26, 24, 38, 36], fill=tone(brass, 0.0))
        rivets(d, olive, [(8, 10), (56, 10), (8, 54), (56, 54)])
        faces[side] = img
    img, d = base_face(olive)
    d.ellipse([6, 6, 58, 58], outline=tone(dark, 0.0), width=3)
    rivets(d, olive, [(32, 8), (32, 56), (8, 32), (56, 32)])
    faces["top"] = img
    swatches = [olive, dark, brass, amber, strap, 0xE8283A, 0x9CA0A8, 0x1E2418]
    parts = [
        ([5.0, -1.0, -5.0], [11.0, 5.0, -0.6], 1, None), ([5.8, -0.2, -6.2], [10.2, 4.2, -5.0], 6, None),   # filter
        ([1.6, 7.5, -1.6], [7.6, 13.5, -0.6], 2, None), ([8.4, 7.5, -1.6], [14.4, 13.5, -0.6], 2, None),     # lens rims
        ([12.0, 16.6, 10.0], [13.0, 25.0, 11.0], 6, None), ([11.6, 25.0, 9.6], [13.4, 26.8, 11.4], 5, None),  # antenna
        ([-1.4, 15.6, -1.4], [17.4, 16.8, 17.4], 0, None),                                                  # brim
    ]
    return faces, swatches, parts


def leprechaun():
    skin, cheek, ginger, green, gold = 0xF2C49C, 0xFF8A8A, 0xE0702A, 0x2EB84A, 0xFFD23C
    faces = {}
    img, d = base_face(skin)
    eye(d, 21, 26, 5, 0x2EB84A)
    eye(d, 43, 26, 5, 0x2EB84A)
    d.line([(14, 17), (27, 15)], fill=tone(ginger, -0.1), width=3)
    d.line([(37, 15), (50, 17)], fill=tone(ginger, -0.1), width=3)
    d.ellipse([10, 33, 20, 40], fill=tone(cheek, 0.0, 170))
    d.ellipse([44, 33, 54, 40], fill=tone(cheek, 0.0, 170))
    d.ellipse([27, 30, 37, 39], fill=tone(skin, -0.12))
    d.arc([22, 36, 42, 48], 20, 160, fill=(90, 30, 30, 255), width=2)
    faces["front"] = img
    for side in ("right", "left", "back"):
        img, d = base_face(ginger)
        for x in range(2, T, 6):
            d.line([(x, 0), (x + 3, 63)], fill=tone(ginger, 0.15), width=2)
        faces[side] = img
    img, d = base_face(green)
    faces["top"] = img
    swatches = [green, 0x1C7A30, gold, ginger, 0xB8501A, 0x14141A, skin, 0x9AFFB8]
    parts = [
        ([-1.6, 16.6, -1.6], [17.6, 17.8, 17.6], 1, None),                                         # hat brim
        ([2.0, 17.8, 2.0], [14.0, 27.0, 14.0], 0, None),                                           # hat crown
        ([1.8, 17.8, 1.8], [14.2, 19.6, 14.2], 5, None),                                           # band
        ([6.0, 17.6, 1.2], [10.0, 20.0, 1.8], 2, None),                                            # buckle
        ([10.5, 21.0, 1.2], [13.0, 23.5, 1.8], 7, None),                                           # clover pin
        ([0.4, -6.0, -2.2], [15.6, 6.0, 4.0], 3, None),                                            # beard
        ([3.0, -9.0, -1.6], [13.0, -6.0, 3.0], 4, None), ([5.5, -11.0, -1.0], [10.5, -9.0, 2.0], 3, None),
    ]
    return faces, swatches, parts


def valor():
    steel, gold, dark, red = 0xC8CED8, 0xFFC93C, 0x16141E, 0xD8203A
    faces = {}
    img, d = base_face(steel)
    d.rectangle([6, 22, 58, 27], fill=tone(dark, 0.0))
    d.rectangle([29, 10, 34, 58], fill=tone(dark, 0.0))
    d.rectangle([5, 21, 59, 28], outline=tone(gold, 0.0))
    d.rectangle([28, 9, 35, 59], outline=tone(gold, 0.0))
    for x in range(10, 56, 6):
        for y in (40, 46, 52):
            if not 26 <= x <= 37:
                d.ellipse([x, y, x + 2, y + 2], fill=tone(dark, 0.0))
    d.arc([2, 2, 30, 30], 180, 270, fill=tone(gold, 0.0), width=2)
    d.arc([34, 2, 62, 30], 270, 360, fill=tone(gold, 0.0), width=2)
    faces["front"] = img
    for side in ("right", "left", "back", "top"):
        img, d = base_face(steel)
        d.rectangle([3, 3, 60, 60], outline=tone(gold, 0.0), width=2)
        for k in range(3):
            d.arc([12 + k * 6, 12 + k * 6, 52 - k * 6, 52 - k * 6], 0, 300, fill=tone(gold, -0.05), width=1)
        if side == "top":
            d.rectangle([28, 0, 36, 63], fill=tone(gold, 0.0))
        faces[side] = img
    swatches = [steel, gold, dark, red, 0x9A1424, 0xFFFFFF, 0x8C94A4, 0xFF5C6A]
    parts = [([7.0, 16.6, 0.0], [9.0, 18.4, 16.0], 1, None)]                                       # crest ridge
    for i, (z0, h) in enumerate(((2, 9), (6, 11), (10, 9))):                                      # plume
        parts.append(([6.0, 18.0, z0], [10.0, 18.0 + h, z0 + 4.0], 3 if i != 1 else 7, ("x", 8, 18, z0 + 2, -22.5)))
    for x0, x1, a in ((-4.0, -0.6, 22.5), (16.6, 20.0, -22.5)):                                   # wings
        parts.append(([x0, 9.0, 6.0], [x1, 15.0, 12.0], 1, ("z", (x0 + x1) / 2, 12, 9, a)))
        parts.append(([x0, 13.0, 7.0], [x1, 18.0, 11.0], 5, ("z", (x0 + x1) / 2, 12, 9, a)))
    return faces, swatches, parts


def clue():
    skin, tweed, tweed2, gold, brown = 0xF2C49C, 0xB08A5A, 0x7A5A38, 0xFFD23C, 0x5A3A22
    faces = {}
    img, d = base_face(skin)
    eye(d, 21, 26, 5, 0x3C7CFF)
    eye(d, 43, 26, 5, 0x3C7CFF)
    d.ellipse([34, 17, 52, 35], outline=tone(gold, 0.0), width=3)
    d.polygon([(14, 40), (32, 36), (50, 40), (44, 46), (32, 42), (20, 46)], fill=tone(brown, 0.0))
    d.line([(14, 18), (27, 16)], fill=tone(brown, 0.0), width=3)
    faces["front"] = img
    for side in ("right", "left", "back", "top"):
        img, d = base_face(tweed)
        checks(d, tweed, tweed2, 8)
        faces[side] = img
    swatches = [tweed, tweed2, gold, brown, 0x2A1A10, skin, 0xFFFFFF, 0x8A5A3A]
    parts = [
        ([-0.8, 16.6, -0.8], [16.8, 19.0, 16.8], 0, None), ([1.5, 19.0, 1.5], [14.5, 21.0, 14.5], 1, None),  # cap
        ([4.0, 15.6, -5.0], [12.0, 17.0, -0.6], 1, None), ([4.0, 15.6, 16.6], [12.0, 17.0, 21.0], 1, None),  # brims
        ([7.0, 20.5, 6.5], [9.0, 22.5, 9.5], 3, None),                                                      # bow
        ([9.5, 6.0, -2.2], [14.5, 11.0, -1.0], 2, None),                                                   # monocle
        ([3.0, 2.0, -6.0], [5.0, 4.0, -0.6], 3, None), ([2.5, 2.0, -8.0], [5.5, 6.0, -6.0], 4, None),       # pipe
    ]
    return faces, swatches, parts


def sentinel():
    metal, dark, red, steel = 0x3A4256, 0x1A1E2A, 0xFF3A4C, 0x8C94A4
    faces = {}
    img, d = base_face(metal)
    d.rounded_rectangle([4, 18, 60, 30], 5, fill=tone(dark, 0.0))
    glow_line(d, [(8, 24), (56, 24)], red, 4)
    for x in range(12, 54, 6):
        d.rectangle([x, 40, x + 3, 54], fill=tone(dark, 0.0))
    runes(d, red, 3)
    faces["front"] = img
    for side in ("right", "left", "back", "top"):
        img, d = base_face(metal)
        d.line([(8, 8), (8, 30), (30, 30), (30, 52)], fill=tone(red, 0.1), width=2)
        d.line([(56, 12), (40, 12), (40, 44)], fill=tone(red, 0.1), width=2)
        if side in ("right", "left"):
            d.ellipse([18, 18, 46, 46], fill=tone(dark, 0.0), outline=tone(steel, 0.0), width=2)
            d.ellipse([27, 27, 37, 37], fill=tone(red, 0.2))
        faces[side] = img
    swatches = [metal, dark, red, steel, 0xFFA0A8, 0x5A6278, 0xFFFFFF, 0x2A3040]
    parts = [
        ([0.5, 6.5, -1.6], [15.5, 9.5, -0.6], 2, None),                                             # visor band
        ([2.0, 16.6, 6.0], [3.0, 26.0, 7.0], 3, ("z", 2.5, 16.6, 6.5, 22.5)),                        # antennae
        ([13.0, 16.6, 6.0], [14.0, 26.0, 7.0], 3, ("z", 13.5, 16.6, 6.5, -22.5)),
        ([-0.8, 23.5, 5.5], [1.6, 25.5, 7.5], 2, None), ([14.4, 23.5, 5.5], [16.8, 25.5, 7.5], 2, None),
        ([-1.8, 4.0, 4.0], [-0.6, 12.0, 12.0], 1, None), ([16.6, 4.0, 4.0], [17.8, 12.0, 12.0], 1, None),  # ear discs
        ([7.2, 16.6, 1.0], [8.8, 19.0, 15.0], 3, None),                                               # crest blade
    ]
    return faces, swatches, parts


def anonymous():
    porcelain, hood, black, rose = 0xF4F0E6, 0x22222C, 0x14141A, 0xE87A8A
    faces = {}
    img, d = base_face(porcelain, 0.08, -0.08)
    d.arc([8, 14, 28, 26], 200, 340, fill=tone(black, 0.0), width=3)
    d.arc([36, 14, 56, 26], 200, 340, fill=tone(black, 0.0), width=3)
    d.ellipse([12, 22, 26, 28], fill=tone(black, 0.0))
    d.ellipse([38, 22, 52, 28], fill=tone(black, 0.0))
    d.ellipse([8, 30, 18, 36], fill=tone(rose, 0.0, 170))
    d.ellipse([46, 30, 56, 36], fill=tone(rose, 0.0, 170))
    d.polygon([(10, 42), (22, 38), (32, 41), (42, 38), (54, 42), (46, 40), (32, 44), (18, 40)], fill=tone(black, 0.0))
    d.arc([18, 40, 46, 52], 20, 160, fill=tone(black, 0.0), width=2)
    d.polygon([(29, 52), (35, 52), (32, 62)], fill=tone(black, 0.0))
    faces["front"] = img
    for side in ("right", "left", "back", "top"):
        img, d = base_face(hood)
        for x in range(4, T, 10):
            d.line([(x, 0), (x - 4, 63)], fill=tone(hood, 0.12), width=2)
        faces[side] = img
    swatches = [hood, 0x16161E, porcelain, black, rose, 0x3A3A48, 0xFFFFFF, 0x2A2A36]
    parts = [
        ([-1.6, 10.0, -3.0], [17.6, 18.0, 1.0], 0, None),                                           # hood peak
        ([-1.6, -1.0, -1.0], [-0.6, 16.0, 17.0], 1, None), ([16.6, -1.0, -1.0], [17.6, 16.0, 17.0], 1, None),
        ([0.0, -2.0, 15.0], [16.0, 17.0, 18.0], 0, None),                                          # hood back
    ]
    return faces, swatches, parts


def prisoner():
    skin, orange, black, white, steel = 0xD8A07A, 0xFF8A1E, 0x1A1A20, 0xF4F2EC, 0x9CA0A8
    faces = {}
    img, d = base_face(skin)
    eye(d, 21, 26, 5, 0x5A3A22, angry=True)
    eye(d, 43, 26, 5, 0x5A3A22, angry=True)
    d.line([(44, 14), (52, 34)], fill=tone(skin, -0.3), width=2)                                    # scar
    for x in range(10, 56, 3):
        for y in range(40, 58, 3):
            if (x + y) % 2:
                d.point((x, y), fill=tone(skin, -0.25))
    d.line([(24, 46), (40, 46)], fill=(90, 40, 30, 255), width=2)
    faces["front"] = img
    for side in ("right", "left", "back"):
        img, d = base_face(white)
        stripes(d, white, black, 8)
        faces[side] = img
    img, d = base_face(orange)
    faces["top"] = img
    swatches = [orange, 0xC85A10, white, black, steel, 0x6E727A, 0xFFE84A, 0xFFFFFF]
    parts = [
        ([-0.8, 14.0, -0.8], [16.8, 19.0, 16.8], 0, None), ([1.5, 19.0, 1.5], [14.5, 21.0, 14.5], 1, None),   # beanie
        ([6.5, 21.0, 6.5], [9.5, 24.0, 9.5], 0, None),                                                      # pompom
        ([3.0, -3.5, -2.6], [13.0, 0.5, -1.0], 2, None), ([4.0, -2.8, -2.8], [12.0, -0.2, -2.6], 3, None),  # number plate
        ([-0.6, -1.5, -0.6], [16.6, 0.0, 16.6], 4, None),                                                   # chain collar
    ]
    return faces, swatches, parts


def tier(colour, horns):
    faces = {}
    img, d = base_face(colour)
    d.rounded_rectangle([6, 18, 58, 30], 5, fill=(18, 14, 26, 255))
    glow_line(d, [(10, 24), (54, 24)], colour, 3)
    runes(d, colour, 7)
    faces["front"] = img
    for side in ("right", "left", "back", "top"):
        img, d = base_face(colour)
        runes(d, colour, hash(side) % 50)
        d.rectangle([3, 3, 60, 60], outline=tone(colour, 0.3), width=1)
        faces[side] = img
    swatches = [colour, shade(rgb(colour), -0.3), shade(rgb(colour), 0.3), 0xFFFFFF, 0x18141E, 0xFFC93C, 0x8C94A4, 0x000000]
    parts = [([7.2, 16.6, 2.0], [8.8, 19.0, 14.0], 2, None)]
    if horns:
        parts += [([0.0, 13.0, 4.0], [3.0, 24.0, 7.0], 1, ("z", 1.5, 13, 5.5, 22.5)),
                  ([13.0, 13.0, 4.0], [16.0, 24.0, 7.0], 1, ("z", 14.5, 13, 5.5, -22.5)),
                  ([-1.0, 22.0, 4.5], [1.6, 27.0, 6.5], 3, ("z", 1.5, 13, 5.5, 22.5)),
                  ([14.4, 22.0, 4.5], [17.0, 27.0, 6.5], 3, ("z", 14.5, 13, 5.5, -22.5))]
    return faces, swatches, parts


THEMES = {"turkey": turkey, "nitro": nitro, "outpost": outpost, "leprechaun": leprechaun, "valor": valor,
          "clue": clue, "sentinel": sentinel, "anonymous": anonymous, "prisoner": prisoner}


# ── sheet + model ────────────────────────────────────────────────────────────

TILE = {"front": (0, 0), "back": (64, 0), "right": (128, 0), "left": (192, 0), "top": (0, 64), "bottom": (64, 64)}


def sheet(faces, swatches):
    img = Image.new("RGBA", (256, 128), (0, 0, 0, 0))
    for name, (x, y) in TILE.items():
        face = faces.get(name)
        if face is None:
            face, _ = base_face(0x1A1620)
        img.alpha_composite(face, (x, y))
    d = ImageDraw.Draw(img)
    for i, c in enumerate(swatches):
        x, y = 128 + (i % 8) * 16, 64 + (i // 8) * 16
        cc = rgb(c) if isinstance(c, int) else c
        for row in range(16):
            d.line([(x, y + row), (x + 15, y + row)], fill=shade(cc, 0.16 - 0.32 * row / 15) + (255,))
        d.rectangle([x, y, x + 15, y + 15], outline=shade(cc, -0.4) + (255,))
    return img


def uv_tile(name):
    x, y = TILE[name]
    return [x / 16, y / 8, (x + 64) / 16, (y + 64) / 8]


def uv_swatch(i):
    x, y = 128 + (i % 8) * 16, 64 + (i // 8) * 16
    return [(x + 1) / 16, (y + 1) / 8, (x + 15) / 16, (y + 15) / 8]


def element(frm, to, faces, rotation=None):
    e = {"from": frm, "to": to, "faces": {d: {"uv": uv, "texture": "#mask"} for d, uv in faces.items()}}
    if rotation:
        if isinstance(rotation[0], str):
            axis, ox, oy, oz, angle = rotation
        else:
            ox, oy, oz, axis, angle = rotation
        e["rotation"] = {"origin": [ox, oy, oz], "axis": axis, "angle": angle}
    return e


def model(name, parts):
    lo, hi = -0.6, 16.6
    shell = element([lo, lo, lo], [hi, hi, hi], {"north": uv_tile("front"), "south": uv_tile("back"),
                                                "east": uv_tile("right"), "west": uv_tile("left"),
                                                "up": uv_tile("top"), "down": uv_tile("bottom")})
    elements = [shell]
    for frm, to, swatch, rot in parts:
        uv = uv_swatch(swatch)
        elements.append(element(frm, to, {d: uv for d in ("north", "south", "east", "west", "up", "down")}, rot))
    return {"textures": {"mask": "theprisons:item/prisons/mask_worn/" + name,
                         "particle": "theprisons:item/prisons/mask_worn/" + name}, "elements": elements}


def icon(faces):
    """Inventory icon: the front of the helm (64x64) with an outline."""
    face = faces["front"].copy()
    out = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    out.alpha_composite(face.resize((56, 56), Image.LANCZOS), (4, 4))
    d = ImageDraw.Draw(out)
    d.rounded_rectangle([3, 3, 60, 60], 6, outline=(20, 16, 26, 255), width=2)
    return out


def emit(root, name, faces, swatches, parts):
    tex = os.path.join(root, "textures", "item", "prisons", "mask_worn", name + ".png")
    os.makedirs(os.path.dirname(tex), exist_ok=True)
    sheet(faces, swatches).save(tex)
    for path, data in ((os.path.join(root, "models", "item", "prisons", "mask_worn", name + ".json"), model(name, parts)),
                       (os.path.join(root, "items", "prisons", "mask_worn", name + ".json"),
                        {"model": {"type": "minecraft:model", "model": "theprisons:item/prisons/mask_worn/" + name}})):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
            f.write("\n")
