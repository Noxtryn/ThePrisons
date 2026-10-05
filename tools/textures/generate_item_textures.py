#!/usr/bin/env python3
"""
ThePrisons item textures for Cosmic Prisons - own art, inspired by the Cosmic Textures mod but drawn from scratch:

  gear.py     swords, pickaxes, spears, armour in an MMORPG look (ornate trims, gems, glow for rare materials)
  economy.py  every other Cosmic item in a colourful cartoon / economy look, coloured per tier
  pets.py     a different animal per pet (chosen from the pet's name), tier on the collar
  masks.py    themed masks (Turkey = rooster, Nitro = flames ...): icon + 3D model worn on the head

Writes into src/main/resources/assets/theprisons:
  textures/item/prisons/<family>/<name>.png   32x32 item textures (masks also their 32x32 worn textures)
  models/item/prisons/<family>/<name>.json    models
  items/prisons/<family>/<name>.json          item model definitions (resolved by PrisonsItems.java)
  textures/gui/sprites/tier_frame/tint.png    18x18 white slot frame, tinted in the item's rarity / texture colour

Run from the repository root:  python3 tools/textures/generate_item_textures.py
"""
import json
import os
import shutil
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import armor_skin  # noqa: E402
import economy  # noqa: E402
import economy2  # noqa: E402
import economy3  # noqa: E402
import gear  # noqa: E402
import masks_hd  # noqa: E402
import pets  # noqa: E402

ROOT = os.path.join("src", "main", "resources", "assets", "theprisons")

TIERS = {
    # Cosmic's tier colours (white, green, aqua, yellow, gold, red) - ItemLookModule.TIER_RGB uses the same values
    "simple": 0xD8DEE8,
    "uncommon": 0x5DE86B,
    "elite": 0x4FD8F0,
    "ultimate": 0xFFE04A,
    "legendary": 0xFF9A2E,
    "godly": 0xFF3D6E,
}


def tier_frame():
    """18x18 white slot frame: soft glow towards the edges and short corner brackets (tinted when drawn)."""
    img = Image.new("RGBA", (18, 18), (0, 0, 0, 0))
    for y in range(18):
        for x in range(18):
            d = min(x, y, 17 - x, 17 - y)
            alpha = {0: 0, 1: 70, 2: 42, 3: 22, 4: 10}.get(d, 0)
            if alpha:
                img.putpixel((x, y), (255, 255, 255, alpha))
    for (cx, cy, sx, sy) in ((1, 1, 1, 1), (16, 1, -1, 1), (1, 16, 1, -1), (16, 16, -1, -1)):
        for i in range(4):
            img.putpixel((cx + sx * i, cy), (255, 255, 255, 235))
            img.putpixel((cx, cy + sy * i), (255, 255, 255, 235))
    return img


def write(path, data=None, image=None):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if image is not None:
        image.save(path)
    else:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
            f.write("\n")


def emit(name, image, parent="minecraft:item/generated"):
    """name like 'shard/elite' -> texture, model and item model definition."""
    write(os.path.join(ROOT, "textures", "item", "prisons", name + ".png"), image=image)
    write(os.path.join(ROOT, "models", "item", "prisons", name + ".json"),
          {"parent": parent, "textures": {"layer0": "theprisons:item/prisons/" + name}})
    write(os.path.join(ROOT, "items", "prisons", name + ".json"),
          {"model": {"type": "minecraft:model", "model": "theprisons:item/prisons/" + name}})


def main():
    # Start clean so renamed / removed textures do not linger.
    for sub in (("textures", "item", "prisons"), ("models", "item", "prisons"), ("items", "prisons"),
                ("textures", "gui", "sprites", "tier_frame")):
        shutil.rmtree(os.path.join(ROOT, *sub), ignore_errors=True)
    count = 0
    # Economy items per tier, pets per animal and tier.
    for family, draw in economy.TIERED.items():
        if family == "pet":
            continue
        for tier, colour in TIERS.items():
            emit(family + "/" + tier, draw(colour))
            count += 1
    for creature in pets.CREATURES:
        for tier, colour in TIERS.items():
            emit("pet/" + creature + "_" + tier, pets.DRAW[creature](colour))
            count += 1
    for name, draw in economy.FIXED.items():
        emit(name, draw())
        count += 1
    for name, draw in economy2.FIXED.items():
        emit(name, draw())
        count += 1
    for name, draw in economy3.FIXED.items():
        emit(name, draw())
        count += 1
    for stage, colour in enumerate(economy.ORB_STAGES, 1):
        emit("charge_orb/stage_" + str(stage), economy.charge_orb(stage, colour))
        count += 1
    for level, colour in enumerate(economy.TOKEN_COLOURS, 1):
        emit("prestige_token/level_" + str(level), economy.prestige_token(level, colour))
        count += 1
    # MMORPG gear (tools and weapons are held like vanilla tools).
    for material in gear.TOOLS:
        for kind, draw in (("sword", gear.sword), ("pickaxe", gear.pickaxe), ("spear", gear.spear)):
            emit(kind + "/" + material, draw(material), parent="minecraft:item/handheld")
            count += 1
    # Armour icons are cut from the worn armour texture, so they look exactly like the armour on the player.
    for asset, material in armor_skin.ASSETS.items():
        for piece, image in armor_skin.icons(asset).items():
            emit("armor/" + material + "_" + piece, image)
            count += 1
    # Masks: full fantasy helms (masks_hd): the worn 3D model and the icon (the helm's front).
    for name, design in masks_hd.THEMES.items():
        faces, swatches, parts = design()
        masks_hd.emit(ROOT, name, faces, swatches, parts)
        emit("mask/" + name, masks_hd.icon(faces))
        count += 1
    for tier, colour in TIERS.items():
        faces, swatches, parts = masks_hd.tier(colour, tier in ("legendary", "godly"))
        masks_hd.emit(ROOT, tier, faces, swatches, parts)
        emit("mask/" + tier, masks_hd.icon(faces))
        count += 1
    write(os.path.join(ROOT, "textures", "gui", "sprites", "tier_frame", "tint.png"), image=tier_frame())
    print("wrote", count, "item textures and the slot frame")


if __name__ == "__main__":
    main()
