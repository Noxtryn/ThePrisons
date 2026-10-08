#!/usr/bin/env python3
"""
The main Minecraft blocks in a colourful 64x64 comic / MMORPG look (own art): bold outlines, cel-shaded light and
shadow edges, saturated colours, glossy gems with sparkles. Stone-like blocks are tileable cell patterns computed on a
torus, so neighbouring blocks join without seams. Writes over the vanilla block textures from the mod's resources
(assets/minecraft/textures/block/<name>.png). Grass stays grey where the game tints it by biome.

Run from the repository root:  python3 tools/textures/blocks.py
"""
import colorsys
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw

N = 64
OUT = os.path.join("src", "main", "resources", "resourcepacks", "theprisons_look", "assets", "minecraft", "textures", "block")


def rgb(h):
    return np.array([(h >> 16) & 0xFF, (h >> 8) & 0xFF, h & 0xFF], dtype=float)


def shade(c, dl, ds=0.0):
    c = np.asarray(c, dtype=float)
    h, l, s = colorsys.rgb_to_hls(*(c / 255.0))
    r, g, b = colorsys.hls_to_rgb(h, max(0.02, min(0.98, l + dl)), max(0.0, min(1.0, s + ds)))
    return np.array([r, g, b]) * 255.0


def to_image(arr, alpha=None):
    a = np.clip(arr, 0, 255).astype(np.uint8)
    if alpha is None:
        alpha = np.full((N, N), 255, dtype=np.uint8)
    return Image.fromarray(np.dstack([a, alpha.astype(np.uint8)]), "RGBA")


# ── tileable cells ───────────────────────────────────────────────────────────

def cells(count, seed, sx=1.0, sy=1.0, jitter=1.0):
    """Toroidal Voronoi: index of the nearest point and the edge distance (d2 - d1) per pixel."""
    rnd = random.Random(seed)
    pts = np.array([[rnd.random() * N, rnd.random() * N] for _ in range(count)])
    ys, xs = np.mgrid[0:N, 0:N].astype(float)
    d = np.empty((count, N, N))
    for i, (px, py) in enumerate(pts):
        dx = np.abs(xs - px)
        dy = np.abs(ys - py)
        dx = np.minimum(dx, N - dx) * sx
        dy = np.minimum(dy, N - dy) * sy
        d[i] = np.sqrt(dx * dx + dy * dy)
    order = np.argsort(d, axis=0)
    nearest = order[0]
    d1 = np.take_along_axis(d, order[0:1], axis=0)[0]
    d2 = np.take_along_axis(d, order[1:2], axis=0)[0]
    return nearest, d2 - d1, d1


def rolled(a, dx, dy):
    return np.roll(np.roll(a, dy, axis=0), dx, axis=1)


def cell_texture(base, count, seed, outline_w=1.3, sx=1.0, sy=1.0, variation=0.10, outline=None, bulge=0.10,
                 palette=None):
    """Comic stones: per-cell tone, light top-left / dark bottom-right edges, bold outlines, soft bulge."""
    nearest, edge, d1 = cells(count, seed, sx, sy)
    rnd = random.Random(seed + 1)
    tones = []
    for i in range(count):
        c = rgb(palette[i % len(palette)]) if palette else rgb(base)
        tones.append(shade(c, rnd.uniform(-variation, variation)))
    tones = np.array(tones)
    img = tones[nearest].copy()
    # bulge: lighter towards the cell centre (top-left biased)
    img += (bulge * 255.0) * np.clip(1.0 - d1 / 9.0, 0, 1)[..., None] * 0.35
    # cel edges
    up_left = rolled(nearest, 1, 1) != nearest
    down_right = rolled(nearest, -1, -1) != nearest
    up_left2 = rolled(nearest, 2, 2) != nearest
    down_right2 = rolled(nearest, -2, -2) != nearest
    for mask, dl in ((up_left2, 0.10), (down_right2, -0.12), (up_left, 0.18), (down_right, -0.22)):
        idx = np.where(mask)
        for y, x in zip(*idx):
            img[y, x] = shade(img[y, x], dl)
    oc = rgb(outline) if outline is not None else shade(rgb(base), -0.32)
    img[edge < outline_w] = oc
    return img


def speckle(img, seed, count, colours, size=1):
    rnd = random.Random(seed)
    for _ in range(count):
        x, y = rnd.randrange(N), rnd.randrange(N)
        c = rgb(rnd.choice(colours))
        for dy in range(size):
            for dx in range(size):
                img[(y + dy) % N, (x + dx) % N] = c
    return img


# ── gems on ores ─────────────────────────────────────────────────────────────

GEM_SPOTS = [(14, 13), (44, 10), (30, 30), (12, 45), (48, 42), (34, 52)]


def draw_gem(img_pil, cx, cy, r, colour, kind, rnd):
    """A comic gem / nugget: dark outline, cel shading, white specular, sparkle."""
    d = ImageDraw.Draw(img_pil)
    base = tuple(int(v) for v in rgb(colour))
    dark = tuple(int(v) for v in shade(rgb(colour), -0.35))
    light = tuple(int(v) for v in shade(rgb(colour), 0.22))
    if kind == "diamond":
        pts = [(cx, cy - r), (cx + r, cy - r * 0.2), (cx, cy + r), (cx - r, cy - r * 0.2)]
    elif kind == "hex":
        pts = [(cx + r * math.cos(a), cy + r * math.sin(a)) for a in [math.pi / 6 + i * math.pi / 3 for i in range(6)]]
    elif kind == "crystal":
        pts = [(cx - r * 0.5, cy + r), (cx - r * 0.6, cy - r * 0.3), (cx, cy - r * 1.2), (cx + r * 0.6, cy - r * 0.3),
               (cx + r * 0.5, cy + r)]
    else:  # nugget: rough rounded blob
        pts = []
        for i in range(9):
            a = i / 9 * math.tau
            rr = r * rnd.uniform(0.78, 1.08)
            pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    # outline: draw bigger dark shape then the gem
    outline_pts = [(cx + (x - cx) * 1.0 + (1.2 if x > cx else -1.2), cy + (y - cy) * 1.0 + (1.2 if y > cy else -1.2))
                   for x, y in pts]
    d.polygon(outline_pts, fill=dark + (255,))
    d.polygon(pts, fill=base + (255,))
    # light facet (top-left half) and shadow facet
    tl = [(x, y) for x, y in pts if x <= cx + 0.1 and y <= cy + r * 0.2]
    if len(tl) >= 2:
        d.polygon([(cx, cy)] + tl, fill=light + (255,))
    br = [(x, y) for x, y in pts if x >= cx - 0.1 and y >= cy - r * 0.2]
    if len(br) >= 2:
        d.polygon([(cx, cy)] + br, fill=tuple(int(v) for v in shade(rgb(colour), -0.12)) + (255,))
    # specular + sparkle
    sx, sy = int(cx - r * 0.35), int(cy - r * 0.35)
    d.rectangle([sx, sy, sx + 1, sy + 1], fill=(255, 255, 255, 255))
    if rnd.random() < 0.6:
        px, py = int(cx + r * 0.9), int(cy - r * 0.9)
        for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            if 0 <= px + dx < N and 0 <= py + dy < N:
                img_pil.putpixel((px + dx, py + dy), (255, 255, 255, 255 if (dx, dy) == (0, 0) else 190))


def ore(base_img, colour, kind, seed, glow=None, extra=None):
    img = to_image(base_img)
    rnd = random.Random(seed)
    if glow is not None:
        halo = Image.new("RGBA", (N, N), (0, 0, 0, 0))
        hd = ImageDraw.Draw(halo)
        g = tuple(int(v) for v in rgb(glow))
        for cx, cy in GEM_SPOTS:
            hd.ellipse([cx - 9, cy - 9, cx + 9, cy + 9], fill=g + (70,))
        img = Image.alpha_composite(img, halo)
    for i, (cx, cy) in enumerate(GEM_SPOTS):
        jx, jy = rnd.randint(-2, 2), rnd.randint(-2, 2)
        c = extra[i % len(extra)] if extra and i % 3 == 2 else colour
        draw_gem(img, cx + jx, cy + jy, rnd.uniform(4.2, 6.2), c, kind, rnd)
    return img


# ── framed mineral blocks ────────────────────────────────────────────────────

def mineral(colour, emblem, accent):
    base = rgb(colour)
    img = np.zeros((N, N, 3))
    ys, xs = np.mgrid[0:N, 0:N]
    img[:] = base
    img += (np.sin((xs + ys) / 5.0) * 10.0)[..., None]                # sheen stripes
    for b in range(5):                                                   # bevel
        img[b, b:N - b] = shade(base, 0.25 - b * 0.03)
        img[b:N - b, b] = shade(base, 0.22 - b * 0.03)
        img[N - 1 - b, b:N - b] = shade(base, -0.25 + b * 0.03)
        img[b:N - b, N - 1 - b] = shade(base, -0.22 + b * 0.03)
    oc = shade(base, -0.40)
    img[0, :] = oc
    img[N - 1, :] = oc
    img[:, 0] = oc
    img[:, N - 1] = oc
    img[5, 5:N - 5] = oc
    img[N - 6, 5:N - 5] = oc
    img[5:N - 5, 5] = oc
    img[5:N - 5, N - 6] = oc
    pil = to_image(img)
    d = ImageDraw.Draw(pil)
    for x, y in ((9, 9), (N - 11, 9), (9, N - 11), (N - 11, N - 11)):  # rivets
        d.ellipse([x, y, x + 2, y + 2], fill=tuple(int(v) for v in shade(base, -0.3)) + (255,))
        pil.putpixel((x, y), (255, 255, 255, 255))
    rnd = random.Random(colour)
    if emblem == "ingot":
        pts = [(18, 40), (24, 26), (40, 26), (46, 40)]
        d.polygon([(p[0] - 1, p[1] + 1) for p in pts], fill=tuple(int(v) for v in shade(base, -0.4)) + (255,))
        d.polygon(pts, fill=tuple(int(v) for v in shade(base, 0.12)) + (255,))
        d.polygon([(24, 26), (40, 26), (38, 30), (26, 30)], fill=tuple(int(v) for v in shade(base, 0.3)) + (255,))
        d.rectangle([26, 28, 28, 29], fill=(255, 255, 255, 255))
    elif emblem == "rune":
        a = tuple(int(v) for v in rgb(accent))
        d.ellipse([16, 16, 48, 48], outline=a + (255,), width=3)
        d.polygon([(32, 20), (40, 32), (32, 44), (24, 32)], fill=a + (255,))
        d.polygon([(32, 24), (36, 32), (32, 40), (28, 32)], fill=(255, 255, 255, 255))
    else:
        draw_gem(pil, 32, 32, 13, accent, emblem, rnd)
    return pil


# ── natural blocks ───────────────────────────────────────────────────────────

def dirt(seed=11):
    img = cell_texture(0x8A5A34, 16, seed, outline_w=0.0, variation=0.08, bulge=0.06)
    speckle(img, seed, 26, [0x6E4426, 0xA8744A], 2)
    pil = to_image(img)
    d = ImageDraw.Draw(pil)
    rnd = random.Random(seed)
    for _ in range(9):                                                 # pebbles
        x, y = rnd.randrange(4, 58), rnd.randrange(4, 58)
        d.ellipse([x - 1, y - 1, x + 3, y + 2], fill=(60, 44, 34, 255))
        d.ellipse([x, y, x + 2, y + 1], fill=(168, 160, 150, 255))
    return pil


def grass_top():
    rnd = random.Random(5)
    img = np.full((N, N, 3), 150.0)
    for _ in range(170):                                               # tufts (grey: tinted by biome)
        x, y = rnd.randrange(N), rnd.randrange(N)
        v = rnd.choice([110, 125, 175, 195, 210])
        for k in range(4):
            img[(y - k) % N, (x + (k if rnd.random() < 0.5 else -k) // 2) % N] = v
    return to_image(img)


def grass_side_overlay():
    rnd = random.Random(6)
    alpha = np.zeros((N, N))
    img = np.full((N, N, 3), 160.0)
    depth = [14 + int(5 * math.sin(x / 64 * math.tau * 3) + rnd.randint(-1, 2)) for x in range(N)]
    for x in range(N):
        for y in range(depth[x]):
            alpha[y, x] = 255
            img[y, x] = 190 if y < 3 else (175 if (x + y) % 7 else 205)
        img[depth[x] - 1, x] = 95                                     # comic outline at the edge
        if depth[x] < N:
            alpha[depth[x], x] = 255
            img[depth[x], x] = 70
    return to_image(img, alpha)


def sand():
    ys, xs = np.mgrid[0:N, 0:N].astype(float)
    base = rgb(0xF2D48A)
    wave = np.sin((xs / N) * math.tau * 2 + (ys / N) * math.tau * 4)
    img = np.empty((N, N, 3))
    img[:] = base
    img[wave > 0.86] = shade(base, 0.10)
    img[wave < -0.86] = shade(base, -0.12)
    speckle(img, 7, 60, [0xE0B868, 0xFFF0C0, 0xC89A5A], 1)
    return to_image(img)


def planks():
    img = np.empty((N, N, 3))
    base = rgb(0xC8964E)
    rnd = random.Random(3)
    for board in range(4):
        y0 = board * 16
        tone = shade(base, rnd.uniform(-0.05, 0.05))
        img[y0:y0 + 16] = tone
        for gy in (y0 + 5, y0 + 10):                                    # grain
            off = rnd.randrange(N)
            for x in range(N):
                if (x + off) % 23 < 15:
                    img[gy, x] = shade(tone, -0.08)
        img[y0, :] = shade(tone, 0.15)
        img[y0 + 15, :] = shade(base, -0.38)
        seam = (rnd.randrange(8, 56) + board * 16) % N
        img[y0:y0 + 16, seam] = shade(base, -0.38)
    pil = to_image(img)
    for board in range(4):                                             # nails
        y = board * 16 + 7
        for x in (4, 59):
            pil.putpixel((x, y), (70, 60, 55, 255))
            pil.putpixel((x, y + 1), (40, 32, 28, 255))
    return pil


def log_side():
    return to_image(cell_texture(0x8A5C3A, 10, 21, outline_w=0.9, sx=1.0, sy=0.3, variation=0.10, bulge=0.08,
                                 outline=0x4A3020))


def log_top():
    ys, xs = np.mgrid[0:N, 0:N].astype(float)
    r = np.sqrt((xs - 31.5) ** 2 + (ys - 31.5) ** 2)
    base = rgb(0xD8A860)
    img = np.empty((N, N, 3))
    img[:] = base
    ring = (np.floor(r / 4.0) % 2 == 0)
    img[ring] = shade(base, -0.07)
    img[np.abs(r % 8 - 0.5) < 0.6] = shade(base, -0.22)
    bark = np.maximum(np.abs(xs - 31.5), np.abs(ys - 31.5)) > 26
    img[bark] = rgb(0x7A5034)
    edge = np.abs(np.maximum(np.abs(xs - 31.5), np.abs(ys - 31.5)) - 26.5) < 0.6
    img[edge] = rgb(0x3A2414)
    return to_image(img)


def stone_bricks():
    img = cell_texture(0x8C93A3, 40, 31, outline_w=0.0, variation=0.05, bulge=0.04)
    mortar = shade(rgb(0x8C93A3), -0.35)
    for row in range(4):
        y = row * 16
        img[y:y + 2, :] = mortar
        offset = 0 if row % 2 == 0 else 16
        for x in (offset, offset + 32):
            img[y:y + 16, x % N:(x % N) + 2] = mortar
        img[y + 2, :] = shade(rgb(0x8C93A3), 0.18)
    return to_image(img)


# ── catalogue ────────────────────────────────────────────────────────────────

STONE = 0x8C93A3
DEEP = 0x4A4E62
NETHER = 0xA8343C


def stone_base(seed=1):
    return cell_texture(STONE, 14, seed)


def deep_base(seed=2):
    return cell_texture(DEEP, 14, seed, sx=1.0, sy=1.8, outline=0x1E2028)


def nether_base(seed=4):
    return cell_texture(NETHER, 16, seed, outline=0x4A1418, variation=0.14)


ORES = {
    # name: (colour, gem kind, glow, extra colours)
    "coal": (0x2E2E38, "nugget", None, [0x50505E]),
    "iron": (0xE8B48A, "nugget", None, [0xF6D0B0]),
    "copper": (0xE38452, "nugget", None, [0x4FC8A4]),
    "gold": (0xFFD23C, "nugget", 0xFFE88A, [0xFFF2A0]),
    "redstone": (0xFF2A3A, "crystal", 0xFF5C6A, [0xFF7A5C]),
    "lapis": (0x2E5CE8, "nugget", None, [0x6C9CFF]),
    "diamond": (0x4FE8E0, "diamond", 0x9AF8F4, [0xB8FFFA]),
    "emerald": (0x2EE86B, "hex", 0x8AFFAE, [0x9AFFB8]),
}
MINERALS = {
    # block: (colour, emblem, accent)
    "coal_block": (0x34343E, "nugget", 0x5A5A6A),
    "iron_block": (0xDCE0E8, "ingot", 0xFFFFFF),
    "gold_block": (0xFFC93C, "ingot", 0xFFF2A0),
    "copper_block": (0xE38452, "ingot", 0x4FC8A4),
    "redstone_block": (0xC8202E, "rune", 0xFF8A8A),
    "lapis_block": (0x2648C8, "hex", 0x8AB8FF),
    "diamond_block": (0x3CD8D0, "diamond", 0xE0FFFF),
    "emerald_block": (0x22C85A, "hex", 0xC8FFD8),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    out = {}
    out["stone"] = to_image(stone_base())
    out["cobblestone"] = to_image(cell_texture(0x8A8F9A, 9, 12, outline_w=2.2, bulge=0.22, variation=0.12))
    out["deepslate"] = to_image(deep_base())
    out["deepslate_top"] = to_image(cell_texture(DEEP, 12, 3, outline=0x1E2028))
    out["cobbled_deepslate"] = to_image(cell_texture(DEEP, 9, 13, outline_w=2.2, bulge=0.2, outline=0x16181E))
    out["andesite"] = to_image(cell_texture(0x9EA09E, 16, 41, variation=0.08))
    out["granite"] = to_image(cell_texture(0xC88A70, 16, 42, palette=[0xC88A70, 0xD8A088, 0xA86A58], variation=0.06))
    out["diorite"] = to_image(cell_texture(0xE2E2E6, 16, 43, palette=[0xE2E2E6, 0xC8C8D0, 0xF4F4F8], variation=0.04))
    out["tuff"] = to_image(cell_texture(0x74786A, 18, 44, variation=0.08))
    out["smooth_stone"] = mineral(0xA8AEBA, "rune", 0xA8AEBA)
    out["stone_bricks"] = stone_bricks()
    out["dirt"] = dirt()
    out["grass_block_side"] = dirt()
    out["grass_block_top"] = grass_top()
    out["grass_block_side_overlay"] = grass_side_overlay()
    out["sand"] = sand()
    out["gravel"] = to_image(cell_texture(0x8C8680, 22, 51, outline_w=1.5, bulge=0.25, variation=0.14,
                                          palette=[0x8C8680, 0x9C8A78, 0x7A7E86, 0xA8A098]))
    out["oak_planks"] = planks()
    out["oak_log"] = log_side()
    out["oak_log_top"] = log_top()
    out["bedrock"] = to_image(cell_texture(0x5A5A60, 14, 61, outline=0x101014, variation=0.25, bulge=0.15))
    obsidian = cell_texture(0x2A1A40, 12, 62, outline=0x0A0614, variation=0.12, bulge=0.12)
    speckle(obsidian, 63, 18, [0xC86CFF, 0x8A4CF0], 1)
    out["obsidian"] = to_image(obsidian)
    out["netherrack"] = to_image(nether_base())
    for name, (colour, kind, glow, extra) in ORES.items():
        out[name + "_ore"] = ore(stone_base(100 + len(name)), colour, kind, 200 + len(name), glow, extra)
        out["deepslate_" + name + "_ore"] = ore(deep_base(110 + len(name)), colour, kind, 210 + len(name), glow, extra)
    out["nether_quartz_ore"] = ore(nether_base(70), 0xF4F0E8, "crystal", 71, 0xFFFFFF, [0xE0D8D0])
    out["nether_gold_ore"] = ore(nether_base(72), 0xFFD23C, "nugget", 73, None, [0xFFF2A0])
    for name, (colour, emblem, accent) in MINERALS.items():
        out[name] = mineral(colour, emblem, accent)
    out["quartz_block_side"] = mineral(0xEEEAE2, "rune", 0xC8C0B4)
    out["quartz_block_top"] = out["quartz_block_side"]
    out["quartz_block_bottom"] = out["quartz_block_side"]
    for name, image in out.items():
        image.save(os.path.join(OUT, name + ".png"))
    print("wrote", len(out), "block textures")


if __name__ == "__main__":
    main()
