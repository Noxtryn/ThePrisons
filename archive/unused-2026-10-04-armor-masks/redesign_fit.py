"""
Fits the flat redesign (archive/redesign-2026-10-04-source) onto the game's models.

The redesign sheets are drawn as a showcase (helmet / chest / leggings panels, arms, boots side by side) - the game
reads worn armour through the player model's fixed UV layout, so the sheets showed as scattered pieces. Here every
face of that layout is painted in the redesign's flat style (dark outline, base plate, highlight bar, accent, light
accent - its five colours): visor helmet, gem chest, padded arms, T-belt leggings, boots with cuffs.

The themed masks' worn textures held only a 15x15 icon in the corner of the 256x128 sheet: the shell model read
transparent faces and the 3D parts (comb, horns, ...) read empty swatches. Now the face is the 64x64 item icon, the
other sides a plate in the frame colour, and the swatches the redesign's colours closest to the old ones (the parts
keep their meaning: a red comb stays red).

Run from the project root: python3 tools/textures/redesign_fit.py
"""
import os

from PIL import Image, ImageDraw

SRC = os.path.join("archive", "redesign-2026-10-04-source")
OLD = os.path.join("src", "main", "resources", "assets.zip")  # the masks before the redesign (old swatches)
TEX = os.path.join("src", "main", "resources", "assets", "theprisons", "textures")
S = 4
CLEAR = (0, 0, 0, 0)

HEAD = {"top": (8, 0, 8, 8), "right": (0, 8, 8, 8), "front": (8, 8, 8, 8), "left": (16, 8, 8, 8),
        "back": (24, 8, 8, 8)}
BODY = {"top": (20, 16, 8, 4), "bottom": (28, 16, 8, 4), "right": (16, 20, 4, 12), "front": (20, 20, 8, 12),
        "left": (28, 20, 4, 12), "back": (32, 20, 8, 12)}
ARM = {"top": (44, 16, 4, 4), "bottom": (48, 16, 4, 4), "right": (40, 20, 4, 12), "front": (44, 20, 4, 12),
       "left": (48, 20, 4, 12), "back": (52, 20, 4, 12)}
LEG = {"top": (4, 16, 4, 4), "bottom": (8, 16, 4, 4), "right": (0, 20, 4, 12), "front": (4, 20, 4, 12),
       "left": (8, 20, 4, 12), "back": (12, 20, 4, 12)}


def px(face):
    x, y, w, h = face
    return x * S, y * S, w * S, h * S


class Flat:
    """The redesign's five colours, read from its sheet: outline, base, highlight, accent, light accent."""

    def __init__(self, sheet):
        counts = sorted(sheet.getcolors(1 << 16), reverse=True)
        o, b, h, a, l = [c for _, c in counts if c[3] > 0][:5]
        self.o, self.b, self.h, self.a, self.l = o, b, h, a, l
        self.img = Image.new("RGBA", (64 * S, 32 * S), CLEAR)
        self.d = ImageDraw.Draw(self.img)

    def rect(self, x, y, w, h, c):
        if w > 0 and h > 0:
            self.d.rectangle([x, y, x + w - 1, y + h - 1], fill=c)

    def plate(self, x, y, w, h, edge=3):
        self.rect(x, y, w, h, self.o)
        self.rect(x + edge, y + edge, w - 2 * edge, h - 2 * edge, self.b)

    def bar(self, x, y, w, h, top, height=3, colour=None):
        """A highlight (or accent) bar across a plate, 5 px in from its sides."""
        inset = 5 if w > 20 else 4
        self.rect(x + inset, y + top, w - 2 * inset, height, colour or self.h)

    def gem(self, cx, cy, r):
        self.d.polygon([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)], fill=self.o)
        self.rect(cx - r // 2, cy - r // 2, r, r, self.a)
        self.rect(cx - r // 4, cy - r // 4, max(2, r // 2), max(2, r // 2), self.l)


def helmet(f):
    x, y, w, h = px(HEAD["front"])
    f.plate(x, y, w, h)
    f.bar(x, y, w, h, 5, 4)
    # visor: dark frame with the glowing slit, like the redesign's helmet panel
    f.rect(x + 4, y + 12, w - 8, 12, f.o)
    f.rect(x + 7, y + 14, w - 14, 4, f.a)
    f.rect(x + 10, y + 18, w - 20, 3, f.l)
    f.rect(x + 4, y + 24, 3, 4, f.o)
    f.rect(x + w - 7, y + 24, 3, 4, f.o)
    for side in ("right", "left"):
        x, y, w, h = px(HEAD[side])
        f.plate(x, y, w, h)
        f.bar(x, y, w, h, 5, 4)
        f.rect(x + w // 2 - 3, y + 15, 6, 6, f.o)
        f.rect(x + w // 2 - 2, y + 16, 4, 4, f.a)
    x, y, w, h = px(HEAD["back"])
    f.plate(x, y, w, h)
    f.bar(x, y, w, h, 5, 4)
    f.rect(x + 5, y + 20, w - 10, 2, f.o)
    x, y, w, h = px(HEAD["top"])
    f.plate(x, y, w, h)
    f.rect(x + w // 2 - 2, y + 3, 4, h - 6, f.h)  # crest ridge, front to back


def chestplate(f):
    x, y, w, h = px(BODY["front"])
    f.plate(x, y, w, h)
    f.bar(x, y, w, h, 5)
    f.rect(x + 8, y + 12, w - 16, 3, f.o)
    f.gem(x + w // 2, y + 25, 10)
    f.rect(x + 5, y + h - 9, w - 10, 2, f.o)
    x, y, w, h = px(BODY["back"])
    f.plate(x, y, w, h)
    f.bar(x, y, w, h, 5)
    f.rect(x + w // 2 - 1, y + 12, 2, h - 20, f.o)
    for side in ("right", "left"):
        x, y, w, h = px(BODY[side])
        f.plate(x, y, w, h)
        f.bar(x, y, w, h, 5)
    for side in ("top", "bottom"):
        f.plate(*px(BODY[side]))
    # arms: a pauldron (highlight + accent, like the redesign's arm panels) over a plain sleeve
    for side in ("right", "front", "left", "back"):
        x, y, w, h = px(ARM[side])
        f.plate(x, y + 26, w, h - 26)
        f.plate(x, y, w, 28)
        f.bar(x, y, w, 28, 6, 4)
        f.bar(x, y, w, 28, 15, 3, f.a)
    x, y, w, h = px(ARM["top"])
    f.plate(x, y, w, h)
    f.bar(x, y, w, h, 6, 4)
    f.plate(*px(ARM["bottom"]))


def boots(f):
    for side in ("right", "front", "left", "back"):
        x, y, w, h = px(LEG[side])
        top = h - 22
        f.plate(x, y + top, w, 22)
        f.bar(x, y + top, w, 22, 4, 4)
        f.bar(x, y + top, w, 22, 13, 3, f.a)
    x, y, w, h = px(LEG["bottom"])
    f.rect(x, y, w, h, f.o)


def leggings(f):
    # belt on the lower body with the redesign's T: highlight across, accent down the middle
    belt = 20
    for side in ("front", "back", "right", "left"):
        x, y, w, h = px(BODY[side])
        f.plate(x, y + h - belt, w, belt)
        f.bar(x, y + h - belt, w, belt, 4, 4)
    x, y, w, h = px(BODY["front"])
    f.rect(x + w // 2 - 2, y + h - belt + 8, 4, belt - 11, f.a)
    for side in ("right", "front", "left", "back"):
        x, y, w, h = px(LEG[side])
        f.plate(x, y, w, h - 4)
        f.bar(x, y, w, h, 5, 6)
        if side == "front":
            f.rect(x + w // 2 - 2, y + 18, 4, 5, f.a)
        f.rect(x + 3, y + 30, w - 6, 2, f.o)  # knee line


def armour():
    for material in ("chainmail", "iron", "gold", "diamond"):
        sheet = Image.open(os.path.join(SRC, "humanoid", material + ".png")).convert("RGBA")
        worn = Flat(sheet)
        helmet(worn)
        chestplate(worn)
        boots(worn)
        worn.img.save(os.path.join(TEX, "entity", "equipment", "humanoid", material + ".png"))
        legs = Flat(sheet)
        leggings(legs)
        legs.img.save(os.path.join(TEX, "entity", "equipment", "humanoid_leggings", material + ".png"))
        print("armour", material)


# ── masks ───────────────────────────────────────────────────────────────────

MASKS = ("anonymous", "clue", "leprechaun", "nitro", "outpost", "prisoner", "sentinel", "turkey", "valor")


def old_swatches(name):
    import io
    import zipfile
    with zipfile.ZipFile(OLD) as z:
        data = z.read("assets/theprisons/textures/item/prisons/mask_worn/" + name + ".png")
    old = Image.open(io.BytesIO(data)).convert("RGBA")
    return [old.getpixel((128 + 16 * i + 8, 64 + 8)) for i in range(8)]


def nearest(colour, palette):
    return min(palette, key=lambda p: sum((a - b) ** 2 for a, b in zip(p[:3], colour[:3])))


def mask(name):
    icon = Image.open(os.path.join(TEX, "item", "prisons", "mask", name + ".png")).convert("RGBA")
    frame = icon.getpixel((32, 2))
    inside = icon.getpixel((8, 8))
    out = Image.new("RGBA", (256, 128), CLEAR)
    # front: the icon, its rounded corners filled (no holes in the shell)
    front = Image.new("RGBA", (64, 64), frame)
    front.alpha_composite(icon)
    out.paste(front, (0, 0))
    side = Image.new("RGBA", (64, 64), frame)
    d = ImageDraw.Draw(side)
    d.rectangle([4, 4, 59, 59], fill=inside)
    d.rectangle([8, 8, 55, 55], outline=tuple(int(v * 0.6) for v in frame[:3]) + (255,))
    for x, y in ((0, 64), (128, 0), (192, 0), (64, 0)):  # top, right, left, back
        out.paste(side, (x, y))
    bottom = Image.new("RGBA", (64, 64), inside)
    out.paste(bottom, (64, 64))
    palette = [c for _, c in sorted(icon.getcolors(1 << 16), reverse=True)[:16] if c[3] == 255]
    for i, old in enumerate(old_swatches(name)):
        ImageDraw.Draw(out).rectangle([128 + 16 * i, 64, 128 + 16 * i + 15, 79], fill=nearest(old, palette))
    out.save(os.path.join(TEX, "item", "prisons", "mask_worn", name + ".png"))
    print("mask", name)


if __name__ == "__main__":
    armour()
    for m in MASKS:
        mask(m)
