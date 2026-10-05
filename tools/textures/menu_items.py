#!/usr/bin/env python3
"""
64x64 item icons of the mod's menu items: minecraft:ah, minecraft:ee, minecraft:tinker, minecraft:skilltree (the logo
item minecraft:theprisons is the mod icon itself). Colourful, drawn supersampled with a dark outline like the other
item textures.

Written to assets/minecraft/textures/item/<name>.png (+ item definition and model). Run from the repository root:
    python3 tools/textures/menu_items.py
"""
import json
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

BASE = os.path.join("src", "main", "resources", "assets", "minecraft")
SS = 8
N = 64


def canvas():
    return Image.new("RGBA", (N * SS, N * SS), (0, 0, 0, 0))


def s(v):
    return int(round(v * SS))


def outline_shape(img, draw_fn, fill, edge=(18, 14, 38, 255), width=2.4):
    """Draws a shape with a dark outline and a light top-left / dark bottom-right band."""
    mask = Image.new("L", img.size, 0)
    draw_fn(ImageDraw.Draw(mask))
    grown = mask.filter(ImageFilter.MaxFilter(s(width) | 1))
    edge_layer = Image.new("RGBA", img.size, edge)
    img.paste(edge_layer, (0, 0), grown)
    body = Image.new("RGBA", img.size, fill)
    # soft vertical shading inside the body
    shade = Image.new("L", img.size, 0)
    sd = ImageDraw.Draw(shade)
    for y in range(img.size[1]):
        sd.line([(0, y), (img.size[0], y)], fill=int(70 * y / img.size[1]))
    dark = Image.new("RGBA", img.size, (0, 0, 0, 255))
    body = Image.composite(dark, body, shade)
    img.paste(body, (0, 0), mask)
    light = mask.copy().filter(ImageFilter.GaussianBlur(s(1.2)))
    hi = Image.new("RGBA", img.size, (255, 255, 255, 255))
    ring = Image.new("L", img.size, 0)
    ring.paste(mask, (s(1), s(1)))
    top_left = Image.composite(Image.new("L", img.size, 0), mask, ring)
    img.paste(hi, (0, 0), top_left.point(lambda v: int(v * 0.45)))


def save(img, name):
    out = img.resize((N, N), Image.LANCZOS)
    os.makedirs(os.path.join(BASE, "textures", "item"), exist_ok=True)
    out.save(os.path.join(BASE, "textures", "item", name + ".png"))
    os.makedirs(os.path.join(BASE, "items"), exist_ok=True)
    os.makedirs(os.path.join(BASE, "models", "item"), exist_ok=True)
    with open(os.path.join(BASE, "items", name + ".json"), "w") as f:
        json.dump({"model": {"type": "minecraft:model", "model": "minecraft:item/" + name}}, f, indent=2)
    with open(os.path.join(BASE, "models", "item", name + ".json"), "w") as f:
        json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/" + name}}, f, indent=2)


def badge(img, colour):
    """A round coloured plate behind the symbol."""
    outline_shape(img, lambda d: d.ellipse([s(3), s(3), s(61), s(61)], fill=255), colour)


def font(size):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


def ah():
    img = canvas()
    badge(img, (255, 122, 200, 255))
    # a price tag with a hole and a dollar sign
    outline_shape(img, lambda d: d.polygon([(s(14), s(32)), (s(34), s(12)), (s(52), s(12)), (s(52), s(30)), (s(32), s(50))], fill=255),
                  (255, 243, 214, 255))
    d = ImageDraw.Draw(img)
    d.ellipse([s(41), s(17), s(48), s(24)], fill=(18, 14, 38, 255))
    d.text((s(30), s(31)), "$", font=font(s(22)), fill=(34, 150, 74, 255), anchor="mm")
    return img


def ee():
    """The energy extractor: a glass tank with a bolt of energy inside, a metal cap and a spigot with a drop."""
    img = canvas()
    badge(img, (120, 196, 255, 255))
    d = ImageDraw.Draw(img)
    # spigot first (behind the tank): a pipe to the right that bends down, ending in a drop
    for pts in (((40, 38), (51, 38)), ((51, 38), (51, 46))):
        d.line([s(pts[0][0]), s(pts[0][1]), s(pts[1][0]), s(pts[1][1])], fill=(18, 14, 38, 255), width=s(6.4))
    for pts in (((40, 38), (51, 38)), ((51, 38), (51, 46))):
        d.line([s(pts[0][0]), s(pts[0][1]), s(pts[1][0]), s(pts[1][1])], fill=(168, 174, 190, 255), width=s(3.4))
    outline_shape(img, lambda dd: (dd.ellipse([s(47.5), s(46), s(54.5), s(53)], fill=255),
                                   dd.polygon([(s(51), s(41)), (s(47.8), s(48.5)), (s(54.2), s(48.5))], fill=255)),
                  (255, 224, 70, 255), width=1.4)
    # the tank: glass body, energy inside, cap
    outline_shape(img, lambda dd: dd.rounded_rectangle([s(17), s(19), s(41), s(53)], radius=s(7), fill=255), (214, 247, 255, 255))
    outline_shape(img, lambda dd: dd.rounded_rectangle([s(20), s(31), s(38), s(50)], radius=s(5), fill=255), (255, 190, 60, 255), width=1.2)
    outline_shape(img, lambda dd: dd.rounded_rectangle([s(14), s(11), s(44), s(21)], radius=s(3), fill=255), (192, 198, 212, 255))
    outline_shape(img, lambda dd: dd.polygon([(s(31), s(24)), (s(23), s(38)), (s(29), s(38)), (s(27), s(49)), (s(36), s(34)), (s(30), s(34)), (s(33), s(24))], fill=255),
                  (255, 247, 150, 255), width=1.2)
    return img


def tinker():
    img = canvas()
    badge(img, (166, 108, 255, 255))
    # an anvil: horn, face, waist, base
    outline_shape(img, lambda d: (d.polygon([(s(10), s(22)), (s(24), s(22)), (s(24), s(28)), (s(14), s(28))], fill=255),
                                  d.rounded_rectangle([s(18), s(20), s(52), s(31)], radius=s(3), fill=255),
                                  d.polygon([(s(26), s(31)), (s(44), s(31)), (s(41), s(42)), (s(29), s(42))], fill=255),
                                  d.rounded_rectangle([s(21), s(42), s(49), s(51)], radius=s(3), fill=255)),
                  (192, 198, 212, 255))
    d = ImageDraw.Draw(img)
    for x1, y1, x2, y2 in ((47, 10, 51, 15), (53, 14, 57, 17), (41, 9, 43, 14)):
        d.line([s(x1), s(y1), s(x2), s(y2)], fill=(255, 224, 70, 255), width=s(2.2))
    return img


def skilltree():
    img = canvas()
    badge(img, (92, 232, 107, 255))
    d = ImageDraw.Draw(img)
    nodes = [(32, 48), (20, 32), (44, 32), (14, 17), (28, 17), (40, 17), (52, 17)]
    for a, b in ((0, 1), (0, 2), (1, 3), (1, 4), (2, 5), (2, 6)):
        d.line([s(nodes[a][0]), s(nodes[a][1]), s(nodes[b][0]), s(nodes[b][1])], fill=(18, 14, 38, 255), width=s(3.6))
        d.line([s(nodes[a][0]), s(nodes[a][1]), s(nodes[b][0]), s(nodes[b][1])], fill=(255, 255, 255, 255), width=s(1.6))
    for i, (x, y) in enumerate(nodes):
        r = 5.5 if i == 0 else 4.4
        outline_shape(img, lambda dd, x=x, y=y, r=r: dd.ellipse([s(x - r), s(y - r), s(x + r), s(y + r)], fill=255),
                      (255, 201, 60, 255) if i == 0 else (255, 255, 255, 255), width=1.6)
    return img


def main():
    for name, fn in (("ah", ah), ("ee", ee), ("tinker", tinker), ("skilltree", skilltree)):
        save(fn(), name)
    print("wrote ah, ee, tinker, skilltree to", BASE)


if __name__ == "__main__":
    main()
