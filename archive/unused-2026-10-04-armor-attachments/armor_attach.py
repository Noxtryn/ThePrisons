"""
3D attachments that break the boxy armour silhouette (MMORPG look): layered round pauldrons, a raised chest emblem
with wings, flared tassets over the hips, boots with toe caps and ankle wings, a helmet crest with cheek fins.

Geometry is written in the player model's space relative to the body part's pivot (x as the model, y DOWN, front =
-z); ArmorAttachmentFeature renders it at the part with scale(1, -1, -1). The item model space is 8 + x, 8 - y, 8 - z.
Texture per material: 64x16, four shaded swatches (base, trim, gem, dark).
"""
import json
import os

from PIL import Image, ImageDraw

from gear import MATERIALS

ROOT = os.path.join("src", "main", "resources", "assets", "theprisons")
BASE, TRIM, GEM, DARK = 0, 1, 2, 3


def rgb(h):
    return ((h >> 16) & 0xFF, (h >> 8) & 0xFF, h & 0xFF)


def shade(c, dl):
    import colorsys
    h, l, s = colorsys.rgb_to_hls(*(v / 255.0 for v in c))
    r, g, b = colorsys.hls_to_rgb(h, max(0.03, min(0.97, l + dl)), s)
    return (round(r * 255), round(g * 255), round(b * 255), 255)


def texture(material):
    base, trim, gem, _ = MATERIALS[material]
    cols = [rgb(base), rgb(trim), rgb(gem), shade(rgb(base), -0.3)[:3]]
    img = Image.new("RGBA", (64, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for i, c in enumerate(cols):
        x = i * 16
        for y in range(16):
            d.line([(x, y), (x + 15, y)], fill=shade(c, 0.16 - 0.34 * y / 15))
        d.rectangle([x, 0, x + 15, 15], outline=shade(c, -0.42))
        d.line([(x + 1, 1), (x + 14, 1)], fill=shade(c, 0.3))
        if i == GEM:
            d.rectangle([x + 3, 3, x + 6, 6], fill=(255, 255, 255, 255))
    return img


def uv(swatch):
    return [swatch * 4 + 0.25, 0.25, swatch * 4 + 3.75, 15.75]


def box(x0, y0, z0, x1, y1, z1, swatch, rot=None):
    """Box in model space (y down, front -z) -> item model element."""
    frm = [8 + min(x0, x1), 8 - max(y0, y1), 8 - max(z0, z1)]
    to = [8 + max(x0, x1), 8 - min(y0, y1), 8 - min(z0, z1)]
    e = {"from": frm, "to": to, "faces": {f: {"uv": uv(swatch), "texture": "#t"}
                                          for f in ("north", "south", "east", "west", "up", "down")}}
    if rot:
        axis, ox, oy, oz, angle = rot
        # model -> item axes: y and z are flipped, so rotations about x / z flip sign, about y too
        e["rotation"] = {"origin": [8 + ox, 8 - oy, 8 - oz], "axis": axis, "angle": -angle if axis != "x" else angle}
    return e


def mirror(elements):
    """The same attachment for the other side (x -> -x)."""
    out = []
    for e in elements:
        f, t = e["from"], e["to"]
        m = dict(e)
        m["from"] = [16 - t[0], f[1], f[2]]
        m["to"] = [16 - f[0], t[1], t[2]]
        if "rotation" in e:
            r = dict(e["rotation"])
            r["origin"] = [16 - r["origin"][0], r["origin"][1], r["origin"][2]]
            if r["axis"] != "x":
                r["angle"] = -r["angle"]
            m["rotation"] = r
        out.append(m)
    return out


def pauldron_right():
    # right arm pivot: the armour cube is x -4..2, y -3..11, z -3..3
    return [
        box(-5.6, -4.2, -3.6, 2.2, -2.2, 3.6, BASE),               # base dome
        box(-5.0, -5.4, -3.0, 1.4, -4.2, 3.0, TRIM),               # second layer
        box(-4.0, -6.2, -2.2, 0.4, -5.4, 2.2, BASE),               # crown
        box(-6.4, -2.6, -3.4, -4.6, 2.4, 3.4, BASE, ("z", -5.0, -2.6, 0, 22.5)),   # flared drop
        box(-6.6, 1.6, -3.6, -4.8, 2.6, 3.6, TRIM, ("z", -5.0, -2.6, 0, 22.5)),    # gold edge
        box(-7.0, -2.0, -0.8, -6.3, -0.4, 0.8, GEM),                 # gem
        box(-2.0, -6.8, -0.6, -1.0, -6.2, 0.6, TRIM),               # spike tip
    ]


def emblem():
    return [
        box(-2.2, 1.6, -3.9, 2.2, 6.2, -3.1, TRIM),                 # mount
        box(-1.2, 2.6, -4.5, 1.2, 5.2, -3.9, GEM),                  # gem
        box(-4.6, 2.8, -3.6, -2.2, 4.4, -3.1, TRIM, ("z", -2.2, 3.6, -3.4, -22.5)),   # wings
        box(2.2, 2.8, -3.6, 4.6, 4.4, -3.1, TRIM, ("z", 2.2, 3.6, -3.4, 22.5)),
        box(-5.2, -1.2, -3.4, 5.2, 0.4, 3.4, DARK),                 # collar ring (front/back/sides)
    ]


def tassets():
    # body pivot: the leggings cube around the hips is x -4.5..4.5, y 6.5..12.5, z -2.5..2.5
    return [
        box(-4.4, 10.0, -3.4, -0.4, 14.5, -2.8, BASE, ("x", 0, 10.0, -3.1, -22.5)),
        box(0.4, 10.0, -3.4, 4.4, 14.5, -2.8, BASE, ("x", 0, 10.0, -3.1, -22.5)),
        box(-4.4, 9.4, -3.6, 4.4, 10.4, -2.8, TRIM),                # belt plate
        box(-4.4, 10.0, 2.8, 4.4, 14.0, 3.4, BASE, ("x", 0, 10.0, 3.1, 22.5)),
        box(-5.4, 10.0, -2.4, -4.8, 13.5, 2.4, BASE, ("z", -5.1, 10.0, 0, 22.5)),
        box(4.8, 10.0, -2.4, 5.4, 13.5, 2.4, BASE, ("z", 5.1, 10.0, 0, -22.5)),
        box(-0.8, 9.6, -4.0, 0.8, 11.0, -3.4, GEM),                 # buckle gem
    ]


def boot_right():
    # right leg pivot: the boot cube is x -3..3, y 5..13, z -3..3
    return [
        box(-2.8, 10.6, -4.2, 2.8, 13.2, -2.8, TRIM),               # toe cap
        box(-3.8, 6.0, -1.2, -3.0, 9.6, 2.8, BASE, ("x", -3.4, 7.0, 0.8, 22.5)),   # ankle wing
        box(-4.2, 5.6, -0.4, -3.2, 7.6, 3.6, TRIM, ("x", -3.4, 7.0, 0.8, 22.5)),
        box(-3.6, 7.2, -0.6, -3.0, 8.6, 0.6, GEM),
        box(-3.2, 4.8, -3.2, 3.2, 6.0, 3.2, TRIM),                  # cuff ring
    ]


def crest():
    # head pivot: the helmet cube is x -5..5, y -9..1, z -5..5
    return [
        box(-0.9, -12.0, -5.2, 0.9, -9.0, 4.6, TRIM),               # crest ridge
        box(-0.6, -13.0, -3.0, 0.6, -12.0, 3.0, GEM),
        box(-6.2, -6.0, -3.2, -5.0, -1.0, 1.4, BASE, ("y", -5.6, -3.5, -1.0, 22.5)),   # cheek fins
        box(5.0, -6.0, -3.2, 6.2, -1.0, 1.4, BASE, ("y", 5.6, -3.5, -1.0, -22.5)),
        box(-1.2, -9.4, -5.8, 1.2, -6.8, -5.0, GEM),                # brow gem
    ]


PARTS = {
    "pauldron_right": pauldron_right(),
    "pauldron_left": None,
    "emblem": emblem(),
    "tassets": tassets(),
    "boot_right": boot_right(),
    "boot_left": None,
    "crest": crest(),
}
PARTS["pauldron_left"] = mirror(PARTS["pauldron_right"])
PARTS["boot_left"] = mirror(PARTS["boot_right"])

MATS = ["leather", "chainmail", "copper", "iron", "golden", "diamond", "netherite"]


def main():
    for material in MATS:
        tex = os.path.join(ROOT, "textures", "item", "prisons", "armor_attach", material + ".png")
        os.makedirs(os.path.dirname(tex), exist_ok=True)
        texture(material).save(tex)
        for part, elements in PARTS.items():
            name = material + "_" + part
            model = {"textures": {"t": "theprisons:item/prisons/armor_attach/" + material,
                                  "particle": "theprisons:item/prisons/armor_attach/" + material},
                     "elements": elements}
            for path, data in ((os.path.join(ROOT, "models", "item", "prisons", "armor_attach", name + ".json"), model),
                               (os.path.join(ROOT, "items", "prisons", "armor_attach", name + ".json"),
                                {"model": {"type": "minecraft:model",
                                           "model": "theprisons:item/prisons/armor_attach/" + name}})):
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(data, f, indent=2)
                    f.write("\n")
    print("wrote armour attachments for", len(MATS), "materials")


if __name__ == "__main__":
    main()
