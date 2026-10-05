#!/usr/bin/env python3
"""
Tooltips in the fantasy MMORPG look (own art): a deep violet background with a soft gradient, an inner light edge and
faint sparkles, and an ornate frame - dark outline, a gold rim with bevel, a thin coloured inner line and corner
flourishes with gems. 200x200 nine-slice sprites declared as 100x100 (twice the detail).

  resourcepacks/theprisons_look/assets/minecraft/textures/gui/sprites/tooltip/{background,frame}.png   every tooltip
  assets/theprisons/textures/gui/sprites/tooltip/tier_<tier>_{background,frame}.png                    items by rarity

Run from the repository root:  python3 tools/textures/tooltip.py
"""
import json
import math
import os
import random

from PIL import Image, ImageDraw

SIZE = 200
PACK = os.path.join("src", "main", "resources", "resourcepacks", "theprisons_look", "assets", "minecraft", "textures",
                    "gui", "sprites", "tooltip")
OWN = os.path.join("src", "main", "resources", "assets", "theprisons", "textures", "gui", "sprites", "tooltip")
TIERS = {"simple": 0xD8DEE8, "uncommon": 0x5DE86B, "elite": 0x4FD8F0, "ultimate": 0xFFE04A, "legendary": 0xFF9A2E,
         "godly": 0xFF3D6E}
GOLD = (0xF2, 0xC2, 0x4E)
GOLD_DARK = (0x8A, 0x5A, 0x1C)
GOLD_LIGHT = (0xFF, 0xEC, 0xA8)


def rgb(h):
    return ((h >> 16) & 0xFF, (h >> 8) & 0xFF, h & 0xFF)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def background(tint=(0x6A, 0x3A, 0xC8)):
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    top = (0x1C, 0x12, 0x34)
    bottom = (0x0C, 0x08, 0x18)
    for y in range(6, SIZE - 6):
        c = mix(top, bottom, (y - 6) / (SIZE - 12))
        d.line([(6, y), (SIZE - 7, y)], fill=c + (238,))
    # rounded corners: cut the outermost corner pixels
    for (cx, cy) in ((6, 6), (SIZE - 7, 6), (6, SIZE - 7), (SIZE - 7, SIZE - 7)):
        for dx in range(3):
            for dy in range(3 - dx):
                x = cx + (dx if cx < SIZE / 2 else -dx)
                y = cy + (dy if cy < SIZE / 2 else -dy)
                img.putpixel((x, y), (0, 0, 0, 0))
    # inner light edge in the tint colour
    edge = mix(tint, (0xFF, 0xFF, 0xFF), 0.15)
    d.rectangle([9, 9, SIZE - 10, SIZE - 10], outline=edge + (90,))
    d.line([(10, 10), (SIZE - 11, 10)], fill=edge + (140,))
    # faint sparkles
    rnd = random.Random(9)
    for _ in range(26):
        x, y = rnd.randrange(16, SIZE - 16), rnd.randrange(16, SIZE - 16)
        a = rnd.randrange(30, 80)
        img.putpixel((x, y), (255, 255, 255, a))
        if rnd.random() < 0.3:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                img.putpixel((x + dx, y + dy), (255, 255, 255, a // 3))
    return img


def gem(d, cx, cy, r, colour):
    c = rgb(colour) if isinstance(colour, int) else colour
    dark = mix(c, (0, 0, 0), 0.45)
    light = mix(c, (255, 255, 255), 0.45)
    d.polygon([(cx, cy - r - 1), (cx + r + 1, cy), (cx, cy + r + 1), (cx - r - 1, cy)], fill=(20, 12, 8, 255))
    d.polygon([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)], fill=c + (255,))
    d.polygon([(cx, cy - r), (cx, cy), (cx - r, cy)], fill=light + (255,))
    d.polygon([(cx, cy), (cx + r, cy), (cx, cy + r)], fill=dark + (255,))
    d.point((cx - r // 2, cy - r // 2), fill=(255, 255, 255, 255))


def frame(accent):
    acc = rgb(accent)
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # dark outline
    d.rectangle([2, 2, SIZE - 3, SIZE - 3], outline=(10, 6, 16, 255), width=2)
    # gold rim with bevel (light top-left, dark bottom-right)
    for i in range(3):
        c = mix(GOLD_LIGHT, GOLD, i / 2)
        d.line([(4 + i, 4 + i), (SIZE - 5 - i, 4 + i)], fill=c + (255,))
        d.line([(4 + i, 4 + i), (4 + i, SIZE - 5 - i)], fill=c + (255,))
        c = mix(GOLD, GOLD_DARK, i / 2)
        d.line([(4 + i, SIZE - 5 - i), (SIZE - 5 - i, SIZE - 5 - i)], fill=c + (255,))
        d.line([(SIZE - 5 - i, 4 + i), (SIZE - 5 - i, SIZE - 5 - i)], fill=c + (255,))
    d.rectangle([7, 7, SIZE - 8, SIZE - 8], outline=GOLD_DARK + (255,))
    # thin coloured inner line
    d.rectangle([12, 12, SIZE - 13, SIZE - 13], outline=acc + (210,))
    # corner flourishes: an L of gold with a curl and a gem
    for sx, sy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
        cx = 10 if sx > 0 else SIZE - 11
        cy = 10 if sy > 0 else SIZE - 11
        d.ellipse([cx - 9, cy - 9, cx + 9, cy + 9], fill=GOLD_DARK + (255,))
        d.ellipse([cx - 8, cy - 8, cx + 8, cy + 8], fill=GOLD + (255,))
        d.ellipse([cx - 6, cy - 6, cx + 6, cy + 6], fill=mix(GOLD, GOLD_LIGHT, 0.4) + (255,))
        for k in range(3):                                     # little curls along both edges
            ax = cx + sx * (12 + k * 3)
            ay = cy + sy * (12 + k * 3)
            d.point((ax, cy - sy * 1), fill=GOLD_LIGHT + (255,))
            d.point((cx - sx * 1, ay), fill=GOLD_LIGHT + (255,))
        gem(d, cx, cy, 5, acc)
    return img


def meta(border, stretch=False):
    scaling = {"type": "nine_slice", "width": 100, "height": 100, "border": border}
    if stretch:
        scaling["stretch_inner"] = True
    return {"gui": {"scaling": scaling}}


def save(folder, name, image, data):
    os.makedirs(folder, exist_ok=True)
    image.save(os.path.join(folder, name + ".png"))
    with open(os.path.join(folder, name + ".png.mcmeta"), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def main():
    save(PACK, "background", background(), meta(9))
    save(PACK, "frame", frame(0xB46CFF), meta(10, True))
    for tier, colour in TIERS.items():
        save(OWN, "tier_" + tier + "_background", background(rgb(colour)), meta(9))
        save(OWN, "tier_" + tier + "_frame", frame(colour), meta(10, True))
    print("wrote tooltip sprites")


if __name__ == "__main__":
    main()
