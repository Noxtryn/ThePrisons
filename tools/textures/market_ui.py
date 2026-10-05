#!/usr/bin/env python3
"""
64x64 GUI sprites of the market screens (/ah, /ee) and the item list: panel, frame, cells, tabs, buttons and the
category / navigation icons - all drawn here (no Cosmic icons). Everything is white (alpha shaped) so the screens tint
it with the design's colours (accent, title, card dark) at draw time.

Written to assets/theprisons/textures/gui/sprites/market/<name>.png, drawn with Ui.sprite(context, "market/<name>", ...).
Run from the repository root:  python3 tools/textures/market_ui.py
"""
import json
import math
import os

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.join("src", "main", "resources", "assets", "theprisons", "textures", "gui", "sprites", "market")
SS = 4            # supersampling
N = 64
W = (255, 255, 255, 255)


def canvas():
    return Image.new("RGBA", (N * SS, N * SS), (0, 0, 0, 0))


def finish(img, name):
    out = img.resize((N, N), Image.LANCZOS)
    os.makedirs(ROOT, exist_ok=True)
    out.save(os.path.join(ROOT, name + ".png"))


def nine(name, border):
    os.makedirs(ROOT, exist_ok=True)
    with open(os.path.join(ROOT, name + ".png.mcmeta"), "w") as f:
        json.dump({"gui": {"scaling": {"type": "nine_slice", "width": N, "height": N, "border": border,
                                       "stretch_inner": True}}}, f, indent=2)


def s(v):
    return int(round(v * SS))


def rrect(d, box, r, fill=None, outline=None, width=1):
    x0, y0, x1, y1 = box
    d.rounded_rectangle([s(x0), s(y0), s(x1) - 1, s(y1) - 1], radius=s(r), fill=fill, outline=outline, width=s(width))


# ── nine-slice parts ─────────────────────────────────────────────────────────

def panel_body():
    img = canvas()
    d = ImageDraw.Draw(img)
    rrect(d, (0, 0, 64, 64), 10, fill=(255, 255, 255, 255))
    # soft top-light: a slightly darker lower half (the tint is multiplied, so this stays subtle)
    grad = Image.new("L", img.size, 0)
    gd = ImageDraw.Draw(grad)
    for y in range(img.size[1]):
        gd.line([(0, y), (img.size[0], y)], fill=int(255 * (0.0 + 0.10 * y / img.size[1])))
    shade = Image.new("RGBA", img.size, (0, 0, 0, 255))
    img = Image.composite(shade, img, Image.composite(grad, Image.new("L", img.size, 0), img.split()[3]))
    finish(img, "panel_body")
    nine("panel_body", 12)


def panel_frame():
    img = canvas()
    d = ImageDraw.Draw(img)
    rrect(d, (0.5, 0.5, 63.5, 63.5), 10, outline=W, width=1.6)
    # corner brackets: small bright ticks inside the four corners
    for cx, cy, dx, dy in ((4, 4, 1, 1), (60, 4, -1, 1), (4, 60, 1, -1), (60, 60, -1, -1)):
        d.line([s(cx), s(cy), s(cx + dx * 7), s(cy)], fill=W, width=s(1.4))
        d.line([s(cx), s(cy), s(cx), s(cy + dy * 7)], fill=W, width=s(1.4))
    finish(img, "panel_frame")
    nine("panel_frame", 12)


def cell():
    img = canvas()
    d = ImageDraw.Draw(img)
    rrect(d, (1, 1, 63, 63), 9, fill=(255, 255, 255, 255))
    # bevel: dark rim bottom-right, light rim top-left (inside)
    inner = canvas()
    idr = ImageDraw.Draw(inner)
    rrect(idr, (1, 1, 63, 63), 9, outline=(0, 0, 0, 90), width=2.4)
    img = Image.alpha_composite(img, inner)
    finish(img, "cell")
    nine("cell", 10)


def cell_active():
    img = canvas()
    d = ImageDraw.Draw(img)
    rrect(d, (1, 1, 63, 63), 9, fill=(255, 255, 255, 255))
    rrect(d, (6, 6, 58, 58), 6, outline=(0, 0, 0, 120), width=2)
    finish(img, "cell_active")
    nine("cell_active", 10)


def tab():
    img = canvas()
    d = ImageDraw.Draw(img)
    rrect(d, (1, 1, 63, 63), 14, fill=(255, 255, 255, 255))
    rrect(d, (1, 1, 63, 63), 14, outline=(0, 0, 0, 70), width=2)
    finish(img, "tab")
    nine("tab", 14)


def button():
    img = canvas()
    d = ImageDraw.Draw(img)
    rrect(d, (1, 1, 63, 63), 16, fill=(255, 255, 255, 255))
    rrect(d, (1, 1, 63, 63), 16, outline=(0, 0, 0, 60), width=2)
    finish(img, "button")
    nine("button", 16)


def glow():
    """A soft round glow behind the active tab (tinted with the accent)."""
    img = canvas()
    d = ImageDraw.Draw(img)
    d.ellipse([s(10), s(10), s(54), s(54)], fill=(255, 255, 255, 255))
    img = img.filter(ImageFilter.GaussianBlur(s(9)))
    finish(img, "glow")


# ── icons (white shapes, 64x64) ──────────────────────────────────────────────

def icon(name, draw_fn):
    img = canvas()
    d = ImageDraw.Draw(img)
    draw_fn(d, img)
    finish(img, name)


def line(d, pts, w=5):
    d.line([(s(x), s(y)) for x, y in pts], fill=W, width=s(w), joint="curve")
    for x, y in (pts[0], pts[-1]):
        d.ellipse([s(x - w / 2), s(y - w / 2), s(x + w / 2), s(y + w / 2)], fill=W)


def poly(d, pts):
    d.polygon([(s(x), s(y)) for x, y in pts], fill=W)


def ic_all(d, img):
    for x, y in ((9, 9), (37, 9), (9, 37), (37, 37)):
        rrect(d, (x, y, x + 18, y + 18), 5, fill=W)


def ic_cosmetics(d, img):
    # a theatre mask: shield outline with two eyes and a smile cut out
    poly(d, [(10, 12), (32, 8), (54, 12), (54, 34), (46, 48), (32, 58), (18, 48), (10, 34)])
    cut = (0, 0, 0, 0)
    d.ellipse([s(17), s(22), s(29), s(31)], fill=cut)
    d.ellipse([s(35), s(22), s(47), s(31)], fill=cut)
    d.arc([s(20), s(32), s(44), s(52)], 20, 160, fill=cut, width=s(3.6))


def ic_upgrades(d, img):
    line(d, [(14, 30), (32, 12), (50, 30)], 6)
    line(d, [(14, 48), (32, 30), (50, 48)], 6)


def ic_mining(d, img):
    line(d, [(10, 56), (40, 26)], 7)                                  # handle
    d.arc([s(14), s(0), s(62), s(48)], 188, 352, fill=W, width=s(8))  # head
    for x, y in ((16, 20), (58, 22)):                                 # the two tips
        d.ellipse([s(x - 4), s(y - 4), s(x + 4), s(y + 4)], fill=W)


def ic_combat(d, img):
    poly(d, [(46, 6), (58, 6), (58, 18), (28, 48), (22, 42)])      # blade
    line(d, [(14, 38), (30, 54)], 6)                                # guard
    line(d, [(24, 40), (14, 50)], 5)                                # grip
    d.ellipse([s(7), s(48), s(15), s(56)], fill=W)                  # pommel


def ic_other(d, img):
    # an open crate
    rrect(d, (8, 24, 56, 56), 5, fill=W)
    rrect(d, (4, 14, 60, 28), 4, fill=W)
    d.rectangle([s(24), s(34), s(40), s(40)], fill=(0, 0, 0, 0))


def ic_prev(d, img):
    line(d, [(36, 12), (16, 32), (36, 52)], 7)
    line(d, [(18, 32), (52, 32)], 6)


def ic_next(d, img):
    line(d, [(28, 12), (48, 32), (28, 52)], 7)
    line(d, [(46, 32), (12, 32)], 6)


def ic_refresh(d, img):
    d.arc([s(10), s(10), s(54), s(54)], 40, 330, fill=W, width=s(6))
    poly(d, [(48, 4), (58, 22), (38, 24)])


def ic_search(d, img):
    d.ellipse([s(8), s(8), s(42), s(42)], outline=W, width=s(6))
    line(d, [(38, 38), (56, 56)], 8)


def ic_history(d, img):
    d.ellipse([s(8), s(8), s(56), s(56)], outline=W, width=s(6))
    line(d, [(32, 18), (32, 32), (43, 38)], 5)


def ic_listings(d, img):
    poly(d, [(6, 28), (30, 6), (58, 6), (58, 34), (34, 58)])
    d.ellipse([s(40), s(14), s(50), s(24)], fill=(0, 0, 0, 0))


def ic_bin(d, img):
    rrect(d, (6, 30, 58, 58), 5, fill=W)             # body
    rrect(d, (6, 8, 58, 26), 5, fill=W)              # lid
    d.rectangle([s(27), s(24), s(37), s(38)], fill=(0, 0, 0, 0))
    rrect(d, (28.5, 28, 35.5, 40), 2, fill=W)        # latch


def ic_categories(d, img):
    for y in (14, 32, 50):
        d.ellipse([s(6), s(y - 4), s(14), s(y + 4)], fill=W)
        line(d, [(22, y), (58, y)], 6)


def ic_filter(d, img):
    poly(d, [(6, 10), (58, 10), (38, 34), (38, 56), (26, 50), (26, 34)])


def ic_guide(d, img):
    d.ellipse([s(6), s(6), s(58), s(58)], outline=W, width=s(5))
    d.arc([s(22), s(16), s(42), s(36)], 180, 360 + 40, fill=W, width=s(5))
    line(d, [(38, 30), (32, 38), (32, 42)], 5)
    d.ellipse([s(29.5), s(46), s(35.5), s(52)], fill=W)


def ic_energy(d, img):
    poly(d, [(36, 4), (14, 36), (29, 36), (24, 60), (50, 26), (34, 26)])


def ic_analytics(d, img):
    rrect(d, (8, 36, 20, 56), 3, fill=W)
    rrect(d, (26, 24, 38, 56), 3, fill=W)
    rrect(d, (44, 8, 56, 56), 3, fill=W)


def ic_back(d, img):
    line(d, [(24, 12), (10, 28), (24, 44)], 6)
    d.arc([s(8), s(14), s(54), s(60)], 270, 60, fill=W, width=s(6))
    line(d, [(10, 28), (34, 28)], 6)


ICONS = {
    "cat_all": ic_all, "cat_cosmetics": ic_cosmetics, "cat_upgrades": ic_upgrades, "cat_mining": ic_mining,
    "cat_combat": ic_combat, "cat_other": ic_other,
    "nav_prev": ic_prev, "nav_next": ic_next, "nav_refresh": ic_refresh, "nav_search": ic_search,
    "nav_history": ic_history, "nav_listings": ic_listings, "nav_bin": ic_bin, "nav_categories": ic_categories,
    "nav_filter": ic_filter, "nav_guide": ic_guide, "nav_energy": ic_energy, "nav_analytics": ic_analytics,
    "nav_back": ic_back,
}


def main():
    panel_body()
    panel_frame()
    cell()
    cell_active()
    tab()
    button()
    glow()
    for name, fn in ICONS.items():
        icon(name, fn)
    print("wrote", len(ICONS) + 7, "sprites to", ROOT)


if __name__ == "__main__":
    main()
