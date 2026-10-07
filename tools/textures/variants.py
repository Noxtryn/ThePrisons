#!/usr/bin/env python3
"""
Adds two sets of item textures on top of what generate_item_textures.py wrote (run from the repository root, it only
adds / overwrites its own files and never deletes anything):

  powerup/<kind>_<tier>     the Wormhole powerups (Double Tap, Overdrive, BOGO, generic) in the colour of the rarity
                            the enchant has at the wormhole, like every other tiered item
  <family>/random_<name>    "Random ..." items: the black version of the item they stand for (a book stays a book,
                            a page a page ...) with a small "?" in the corner
  powerup/random, trinket/random, misc/prestige_modifier_random, satchel/random   the same, replacing the pink ones

Java side: PrisonsItems.RANDOM_FAMILIES lists the families that have random_ variants.
"""
import json
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cartoon import rgb, shade  # noqa: E402
import economy2  # noqa: E402

ROOT = os.path.join("src", "main", "resources", "assets", "theprisons")
TEXTURES = os.path.join(ROOT, "textures", "item", "prisons")
TIERS = {"simple": 0xD8DEE8, "uncommon": 0x5DE86B, "elite": 0x4FD8F0, "ultimate": 0xFFE04A, "legendary": 0xFF9A2E,
         "godly": 0xFF3D6E}
POWERUPS = {"overdrive": "bolt", "bogo": "plus", "double_tap": "two", "generic": "bolt"}
# Families that get a random_ variant of every texture (keep in sync with PrisonsItems.RANDOM_FAMILIES).
RANDOM_FAMILIES = ["book", "book_revealed", "page", "key", "shard", "dust", "secret_dust", "xp_bottle", "clue_scroll",
                   "randomization_scroll", "contraband", "enchant_orb", "spear_orb", "reroll", "candy", "upgrade",
                   "gkit", "prestige_token"]
# Fixed random items (pink "?" before): black version of a sibling texture.
FIXED_RANDOM = {"powerup/random": "powerup/generic", "trinket/random": "trinket/generic",
                "misc/prestige_modifier_random": "misc/prestige_modifier", "satchel/random": "satchel/iron",
                "misc/boss_egg_random": "misc/boss_egg"}
QUESTION = [(0, 0), (1, 0), (2, 0), (2, 1), (1, 2), (1, 3), (1, 5)]   # a 3x6 "?" (the dot is the last pixel)


def blacken(img):
    """Same silhouette in near-black (the light parts a bit lighter so the shading stays), a grey rim so it reads
    on a dark slot, and a small "?" at the bottom right."""
    img = img.convert("RGBA")
    w, h = img.size
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    src, dst = img.load(), out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = src[x, y]
            if a:
                lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                v = round(10 + 46 * lum)
                dst[x, y] = (v, v, v + 4, a)
    rim = (118, 118, 132, 255)
    for y in range(h):
        for x in range(w):
            if src[x, y][3]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and src[nx, ny][3]:
                    dst[x, y] = rim
                    break
    ox, oy = w - 6, h - 8
    for px, py in QUESTION:
        dst[ox + px, oy + py] = (190, 190, 205, 255)
    dst[ox + 1, oy + 5] = (190, 190, 205, 255)
    return out


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def emit(name, image):
    path = os.path.join(TEXTURES, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path)
    write_json(os.path.join(ROOT, "models", "item", "prisons", name + ".json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": "theprisons:item/prisons/" + name}})
    write_json(os.path.join(ROOT, "items", "prisons", name + ".json"),
               {"model": {"type": "minecraft:model", "model": "theprisons:item/prisons/" + name}})


def main():
    count = 0
    for kind, symbol in POWERUPS.items():
        for tier, colour in TIERS.items():
            body = colour
            if tier == "simple":
                r, g, b = shade(rgb(colour), -0.15)
                body = (r << 16) | (g << 8) | b
            emit("powerup/%s_%s" % (kind, tier), economy2.powerup(body, symbol))
            count += 1
    for family in RANDOM_FAMILIES:
        folder = os.path.join(TEXTURES, family)
        for file in sorted(os.listdir(folder)):
            if file.endswith(".png") and not file.startswith("random_"):
                emit("%s/random_%s" % (family, file[:-4]), blacken(Image.open(os.path.join(folder, file))))
                count += 1
    for name, base in FIXED_RANDOM.items():
        emit(name, blacken(Image.open(os.path.join(TEXTURES, base + ".png"))))
        count += 1
    print("wrote", count, "textures")


if __name__ == "__main__":
    main()
