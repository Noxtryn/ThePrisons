"""
Worn armour in the MMORPG look, 4x resolution (256x128 equipment textures - the model's UVs are relative, so the game
draws them in full detail): bevelled plates with engraved borders, layered pauldrons with rivets, a winged chest
emblem with a faceted gem, abdominal plates, belt and buckle, knee cops, plated boots with cuffs and toe caps, a
T-visor helmet (the skin's eyes and mouth stay visible) with crest, forehead gem and ear plates.

The item icons are cut from the same texture (helmet front; chest with both arms; belt with both legs; both boots), so
an icon looks exactly like the armour on the player.

Writes:
  assets/theprisons/textures/entity/equipment/humanoid(_leggings)/<material>.png   (worn textures)
  resourcepacks/theprisons_look/assets/minecraft/equipment/<material>.json          (vanilla definitions -> ours)
Leather stays dyeable: a grey base the game tints plus an undyed overlay with trims, rivets and gems.
"""
import colorsys
import json
import os

from PIL import Image, ImageDraw

from gear import MATERIALS

ROOT = os.path.join("src", "main", "resources", "assets")
# vanilla overrides live in the built-in pack that is always on top (ThePrisonsResourcePackManagerMixin)
PACK = os.path.join("src", "main", "resources", "resourcepacks", "theprisons_look", "assets")
S = 4
CLEAR = (0, 0, 0, 0)
ASSETS = {"leather": "leather", "chainmail": "chainmail", "copper": "copper", "iron": "iron", "gold": "golden",
          "diamond": "diamond", "netherite": "netherite"}
LEATHER_TINT = 0xA06540

HEAD = {"top": (8, 0, 8, 8), "bottom": (16, 0, 8, 8), "right": (0, 8, 8, 8), "front": (8, 8, 8, 8),
        "left": (16, 8, 8, 8), "back": (24, 8, 8, 8)}
BODY = {"top": (20, 16, 8, 4), "right": (16, 20, 4, 12), "front": (20, 20, 8, 12), "left": (28, 20, 4, 12),
        "back": (32, 20, 8, 12)}
ARM = {"top": (44, 16, 4, 4), "bottom": (48, 16, 4, 4), "right": (40, 20, 4, 12), "front": (44, 20, 4, 12),
       "left": (48, 20, 4, 12), "back": (52, 20, 4, 12)}
LEG = {"top": (4, 16, 4, 4), "bottom": (8, 16, 4, 4), "right": (0, 20, 4, 12), "front": (4, 20, 4, 12),
       "left": (8, 20, 4, 12), "back": (12, 20, 4, 12)}


def rgb(h):
    return ((h >> 16) & 0xFF, (h >> 8) & 0xFF, h & 0xFF)


def tone(c, dl, a=255):
    h, l, s = colorsys.rgb_to_hls(*(v / 255.0 for v in c[:3]))
    r, g, b = colorsys.hls_to_rgb(h, max(0.03, min(0.97, l + dl)), s)
    return (round(r * 255), round(g * 255), round(b * 255), a)


class Painter:
    """Two layers: plates (dyed for leather) and details (trims, rivets, gems - never dyed)."""

    def __init__(self, base, trim, gem):
        self.base_img = Image.new("RGBA", (64 * S, 32 * S), CLEAR)
        self.detail_img = Image.new("RGBA", (64 * S, 32 * S), CLEAR)
        self.b = rgb(base)
        self.t = rgb(trim)
        self.g = rgb(gem)
        self.bd = ImageDraw.Draw(self.base_img)
        self.dd = ImageDraw.Draw(self.detail_img)

    # ── primitives (coordinates in final pixels) ──
    def plate(self, x, y, w, h, colour=None, engraved=True):
        c = colour or self.b
        for row in range(h):
            t = row / max(1, h - 1)
            self.bd.line([(x, y + row), (x + w - 1, y + row)], fill=tone(c, 0.10 - 0.22 * t))
        self.bd.line([(x + 1, y + 1), (x + w - 2, y + 1)], fill=tone(c, 0.24))       # bevel
        self.bd.line([(x + 1, y + 1), (x + 1, y + h - 2)], fill=tone(c, 0.18))
        self.bd.line([(x + 1, y + h - 2), (x + w - 2, y + h - 2)], fill=tone(c, -0.24))
        self.bd.line([(x + w - 2, y + 1), (x + w - 2, y + h - 2)], fill=tone(c, -0.20))
        self.bd.rectangle([x, y, x + w - 1, y + h - 1], outline=tone(c, -0.40))
        if engraved and w >= 12 and h >= 12:
            self.dd.rectangle([x + 3, y + 3, x + w - 4, y + h - 4], outline=tone(self.t, 0.0, 150))
            for cx, cy in ((x + 3, y + 3), (x + w - 4, y + 3), (x + 3, y + h - 4), (x + w - 4, y + h - 4)):
                self.dd.point((cx, cy), fill=tone(self.t, 0.25))

    def band(self, x, y, w, h):
        """A trim band (gold edge etc.) with a light top and dark bottom line."""
        for row in range(h):
            t = row / max(1, h - 1)
            self.dd.line([(x, y + row), (x + w - 1, y + row)], fill=tone(self.t, 0.12 - 0.22 * t))
        self.dd.line([(x, y), (x + w - 1, y)], fill=tone(self.t, 0.28))
        self.dd.line([(x, y + h - 1), (x + w - 1, y + h - 1)], fill=tone(self.t, -0.35))

    def rivet(self, x, y):
        self.dd.rectangle([x, y, x + 1, y + 1], fill=tone(self.t, -0.2))
        self.dd.point((x, y), fill=(255, 255, 255, 255))

    def seam(self, x, y, w):
        self.bd.line([(x, y), (x + w - 1, y)], fill=tone(self.b, -0.32))
        self.bd.line([(x, y + 1), (x + w - 1, y + 1)], fill=tone(self.b, 0.18))

    def gem(self, cx, cy, r):
        """Faceted gem in a trim mount."""
        self.dd.ellipse([cx - r - 1, cy - r - 1, cx + r + 1, cy + r + 1], fill=tone(self.t, -0.1))
        pts = [(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)]
        self.dd.polygon(pts, fill=self.g + (255,))
        self.dd.polygon([(cx, cy - r), (cx, cy), (cx - r, cy)], fill=tone(self.g, 0.22))
        self.dd.polygon([(cx, cy), (cx + r, cy), (cx, cy + r)], fill=tone(self.g, -0.18))
        self.dd.point((cx - r // 2, cy - r // 2), fill=(255, 255, 255, 255))

    def clear(self, x, y, w, h):
        for img in (self.base_img, self.detail_img):
            ImageDraw.Draw(img).rectangle([x, y, x + w - 1, y + h - 1], fill=CLEAR)

    def face(self, box, rows=None):
        """A face (u, v, w, h in model pixels), optionally only some rows; returns its pixel box."""
        u, v, w, h = box
        r0, r1 = (0, h) if rows is None else rows
        x, y, pw, ph = u * S, (v + r0) * S, w * S, (r1 - r0) * S
        return x, y, pw, ph

    def result(self, leather=False):
        if leather:
            return self.base_img, self.detail_img
        out = self.base_img.copy()
        out.alpha_composite(self.detail_img)
        return out, None


def humanoid(base, trim, gem, leather=False):
    p = Painter(base, trim, gem)
    # ── helmet ──
    for name, box in HEAD.items():
        if name == "bottom":
            continue
        x, y, w, h = p.face(box)
        p.plate(x, y, w, h)
    x, y, w, h = p.face(HEAD["front"])
    p.band(x, y, w, 4)                                                        # brow band
    p.gem(x + w // 2, y + 7, 3)                                               # forehead gem
    p.clear(x + 4, y + 12, w - 8, 7)                                          # eye slit
    p.clear(x + 9, y + 19, w - 18, 13)                                        # mouth opening
    p.band(x + w // 2 - 2, y + 11, 4, 13)                                     # nose guard
    p.band(x + 3, y + 19, 6, 2)
    p.band(x + w - 9, y + 19, 6, 2)
    for rx, ry in ((x + 4, y + 24), (x + w - 6, y + 24), (x + 4, y + 29), (x + w - 6, y + 29)):
        p.rivet(rx, ry)                                                       # cheek guards
    x, y, w, h = p.face(HEAD["top"])
    p.band(x + w // 2 - 3, y, 6, h)                                           # crest ridge
    for ry in range(y + 4, y + h - 2, 7):
        p.rivet(x + w // 2 - 1, ry)
    for side in ("right", "left"):
        x, y, w, h = p.face(HEAD[side])
        p.dd.ellipse([x + 9, y + 9, x + w - 10, y + h - 10], outline=tone(p.t, 0.1), width=2)   # ear plate
        p.gem(x + w // 2, y + h // 2, 2)
        p.band(x, y + h - 4, w, 4)
    x, y, w, h = p.face(HEAD["back"])
    for sy in range(y + 8, y + h - 4, 8):
        p.seam(x + 2, sy, w - 4)
    p.band(x, y + h - 5, w, 5)                                                # neck guard
    # ── chestplate ──
    for name, box in BODY.items():
        x, y, w, h = p.face(box)
        p.plate(x, y, w, h, engraved=name != "top")
    x, y, w, h = p.face(BODY["front"])
    p.band(x, y, w, 4)                                                        # collar
    for rx in range(x + 3, x + w - 2, 6):
        p.rivet(rx, y + 1)
    for sy in (y + 28, y + 34):
        p.seam(x + 3, sy, w - 6)                                              # abdominal plates
    cx, cy = x + w // 2, y + 16
    p.dd.polygon([(cx - 13, cy - 6), (cx - 4, cy - 2), (cx - 4, cy + 4), (cx - 11, cy + 2)], fill=tone(p.t, 0.05))   # wings
    p.dd.polygon([(cx + 13, cy - 6), (cx + 4, cy - 2), (cx + 4, cy + 4), (cx + 11, cy + 2)], fill=tone(p.t, -0.05))
    p.gem(cx, cy, 5)
    p.band(x, y + h - 8, w, 6)                                                # belt
    p.gem(cx, y + h - 5, 2)
    x, y, w, h = p.face(BODY["back"])
    p.dd.line([(x + 2, y + 4), (x + w - 3, y + h - 10)], fill=tone(p.t, 0.0), width=3)   # crossed straps
    p.dd.line([(x + w - 3, y + 4), (x + 2, y + h - 10)], fill=tone(p.t, -0.1), width=3)
    p.band(x, y + h - 8, w, 6)
    for side in ("right", "left"):
        x, y, w, h = p.face(BODY[side])
        p.seam(x + 1, y + 20, w - 2)
        p.band(x, y + h - 8, w, 6)
    # sleeves: layered pauldrons, bracers
    for name, box in ARM.items():
        if name == "bottom":
            continue
        x, y, w, h = p.face(box)
        p.plate(x, y, w, h, engraved=False)
        if name == "top":
            p.band(x, y, w, h)
            p.rivet(x + w // 2 - 1, y + h // 2 - 1)
            continue
        for k in range(3):                                                     # three pauldron layers
            p.band(x, y + k * 5, w, 5)
            p.rivet(x + 2, y + k * 5 + 2)
            p.rivet(x + w - 4, y + k * 5 + 2)
        p.seam(x + 1, y + 26, w - 2)
        p.band(x, y + 36, w, 8)                                                # bracer
        if name == "front":
            p.gem(x + w // 2, y + 40, 2)
    # ── boots ──
    for name in ("right", "front", "left", "back"):
        x, y, w, h = p.face(LEG[name], rows=(6, 12))
        p.plate(x, y, w, h, engraved=False)
        p.band(x, y, w, 6)                                                     # cuff
        p.rivet(x + 2, y + 2)
        p.rivet(x + w - 4, y + 2)
        p.seam(x + 1, y + 13, w - 2)
        if name == "front":
            p.band(x, y + h - 7, w, 7)                                         # toe cap
            p.gem(x + w // 2, y + 10, 2)
    x, y, w, h = p.face(LEG["bottom"])
    p.plate(x, y, w, h, colour=tone(p.b, -0.25)[:3], engraved=False)          # sole
    return p.result(leather)


def leggings(base, trim, gem, leather=False):
    p = Painter(base, trim, gem)
    for name in ("right", "front", "left", "back"):
        x, y, w, h = p.face(BODY[name], rows=(7, 12))
        p.plate(x, y, w, h, engraved=False)
        p.band(x, y, w, 8)                                                     # belt
        for sx in range(x + 4, x + w - 2, 8):                                  # tassets
            p.bd.line([(sx, y + 9), (sx, y + h - 2)], fill=tone(p.b, -0.3))
    x, y, w, h = p.face(BODY["front"], rows=(7, 12))
    p.dd.rectangle([x + w // 2 - 4, y + 1, x + w // 2 + 3, y + 6], fill=tone(p.t, 0.2))   # buckle
    p.gem(x + w // 2, y + 4, 2)
    for name in ("right", "front", "left", "back"):
        x, y, w, h = p.face(LEG[name], rows=(0, 8))
        p.plate(x, y, w, h, engraved=False)
        p.seam(x + 1, y + 10, w - 2)
        p.seam(x + 1, y + 26, w - 2)
        if name in ("front", "right", "left"):
            p.dd.ellipse([x + 2, y + 15, x + w - 3, y + 26], fill=tone(p.t, 0.0))    # knee cop
            p.dd.ellipse([x + 4, y + 17, x + w - 5, y + 24], fill=tone(p.t, 0.18))
            if name == "front":
                p.gem(x + w // 2, y + 20, 2)
    x, y, w, h = p.face(LEG["top"])
    p.plate(x, y, w, h, engraved=False)
    return p.result(leather)


# ── icons cut from the worn texture ──────────────────────────────────────────

def _crop(img, box, rows=None):
    u, v, w, h = box
    r0, r1 = (0, h) if rows is None else rows
    return img.crop((u * S, (v + r0) * S, (u + w) * S, (v + r1) * S))


def _outline(icon, glow=None):
    """Comic outline (and glow for rare materials) around the icon's shape."""
    w, h = icon.size
    src = icon.load()
    out = Image.new("RGBA", (w, h), CLEAR)
    o = out.load()
    for y in range(h):
        for x in range(w):
            if src[x, y][3] > 0:
                continue
            near = [src[x + dx, y + dy] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1), (1, -1), (-1, 1))
                    if 0 <= x + dx < w and 0 <= y + dy < h and src[x + dx, y + dy][3] > 0]
            if near:
                o[x, y] = (20, 18, 28, 255)
            elif glow:
                for dx in range(-3, 4):
                    for dy in range(-3, 4):
                        if 0 <= x + dx < w and 0 <= y + dy < h and src[x + dx, y + dy][3] > 0:
                            o[x, y] = glow + (60,)
                            break
    out.alpha_composite(icon)
    return out


def icon(worn, legs, piece, glow=None):
    canvas = Image.new("RGBA", (64, 64), CLEAR)
    if piece == "helmet":
        face = _crop(worn, HEAD["front"])
        visor = Image.new("RGBA", face.size, (22, 20, 30, 255))
        visor.alpha_composite(face)
        canvas.alpha_composite(visor.resize((48, 48), Image.NEAREST), (8, 8))
    elif piece == "chestplate":
        arm = _crop(worn, ARM["front"])
        body = _crop(worn, BODY["front"])
        canvas.alpha_composite(arm, (0, 8))
        canvas.alpha_composite(body, (16, 8))
        canvas.alpha_composite(arm.transpose(Image.FLIP_LEFT_RIGHT), (48, 8))
    elif piece == "leggings":
        waist = _crop(legs, BODY["front"], rows=(7, 12))
        leg = _crop(legs, LEG["front"], rows=(0, 8))
        canvas.alpha_composite(waist, (16, 6))
        canvas.alpha_composite(leg, (16, 26))
        canvas.alpha_composite(leg.transpose(Image.FLIP_LEFT_RIGHT), (32, 26))
    else:
        boot = _crop(worn, LEG["front"], rows=(6, 12))
        boot = boot.resize((20, 30), Image.NEAREST)
        canvas.alpha_composite(boot, (9, 18))
        canvas.alpha_composite(boot.transpose(Image.FLIP_LEFT_RIGHT), (35, 18))
    return _outline(canvas, glow)


def _tinted(base, overlay, colour):
    tint = rgb(colour)
    b = base.copy()
    px = b.load()
    for y in range(b.size[1]):
        for x in range(b.size[0]):
            r, g, bl, a = px[x, y]
            if a:
                px[x, y] = (r * tint[0] // 255, g * tint[1] // 255, bl * tint[2] // 255, a)
    b.alpha_composite(overlay)
    return b


def textures(asset):
    """(worn humanoid, worn leggings) as they look in game (leather: tinted with its default colour)."""
    material = ASSETS[asset]
    base, trim, gem, _glow = MATERIALS[material]
    if asset == "leather":
        grey = 0xC8C8C8
        hb, hd = humanoid(grey, trim, gem, leather=True)
        lb, ld = leggings(grey, trim, gem, leather=True)
        return _tinted(hb, hd, LEATHER_TINT), _tinted(lb, ld, LEATHER_TINT)
    return humanoid(base, trim, gem)[0], leggings(base, trim, gem)[0]


def icons(asset):
    material = ASSETS[asset]
    glow = MATERIALS[material][3]
    worn, legs = textures(asset)
    return {piece: icon(worn, legs, piece, rgb(glow) if glow else None)
            for piece in ("helmet", "chestplate", "leggings", "boots")}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def save(img, layer, name):
    path = os.path.join(ROOT, "theprisons", "textures", "entity", "equipment", layer, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def main():
    for asset, material in ASSETS.items():
        base, trim, gem, _glow = MATERIALS[material]
        if asset == "leather":
            grey = 0xC8C8C8
            hb, hd = humanoid(grey, trim, gem, leather=True)
            lb, ld = leggings(grey, trim, gem, leather=True)
            save(hb, "humanoid", "leather")
            save(hd, "humanoid", "leather_overlay")
            save(lb, "humanoid_leggings", "leather")
            save(ld, "humanoid_leggings", "leather_overlay")
            layers = {layer: [{"dyeable": {"color_when_undyed": -6265536}, "texture": "theprisons:leather"},
                              {"texture": "theprisons:leather_overlay"}] for layer in ("humanoid", "humanoid_leggings")}
            horse = [{"dyeable": {"color_when_undyed": -6265536}, "texture": "minecraft:leather"},
                     {"texture": "minecraft:leather_overlay"}]
            write_json(os.path.join(PACK, "minecraft", "equipment", "leather.json"), {"layers": {"horse_body": horse, **layers}})
            continue
        save(humanoid(base, trim, gem)[0], "humanoid", asset)
        save(leggings(base, trim, gem)[0], "humanoid_leggings", asset)
        definition = {"layers": {"humanoid": [{"texture": "theprisons:" + asset}],
                                 "humanoid_leggings": [{"texture": "theprisons:" + asset}]}}
        if asset != "chainmail":
            definition["layers"]["horse_body"] = [{"texture": "minecraft:" + asset}]
            definition["layers"]["nautilus_body"] = [{"texture": "minecraft:" + asset}]
        write_json(os.path.join(PACK, "minecraft", "equipment", asset + ".json"), definition)
    print("wrote worn armour for", len(ASSETS), "materials")


if __name__ == "__main__":
    main()
