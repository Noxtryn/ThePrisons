#!/usr/bin/env python3
"""
Generated graphics of the repository (banner, feature cards, animated changelog card) in the mod's look.

THESE ARE GRAPHICS, NOT SCREENSHOTS. They are drawn by this script from the mod's own icons and text; the cards carry
the note "illustration". Real in-game pictures go to docs/raw/ and are processed by scripts/media.sh.

  python3 scripts/make_graphics.py banner
  python3 scripts/make_graphics.py cards
  python3 scripts/make_graphics.py changelog 1.0.0      # docs/media/changelog-1.0.0.gif from CHANGELOG.md
  python3 scripts/make_graphics.py all 1.0.0
"""
import math
import os
import random
import re
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICONS = os.path.join(ROOT, "src", "main", "resources", "assets", "theprisons", "textures", "gui", "sprites", "icon")
LOGO = os.path.join(ROOT, "src", "main", "resources", "assets", "theprisons", "icon.png")
OUT = os.path.join(ROOT, "docs", "media")

BG_TOP = (14, 10, 34)
BG_BOTTOM = (36, 16, 82)
CYAN = (79, 232, 224)
PINK = (255, 122, 200)
VIOLET = (123, 63, 255)
GOLD = (255, 201, 60)
GREEN = (76, 217, 100)
BLUE = (60, 156, 255)
WHITE = (240, 238, 255)
MUTED = (160, 160, 196)

FONT_DIRS = ["/usr/share/fonts/TTF", "/usr/share/fonts/truetype/dejavu", "/usr/share/fonts/dejavu", "/Library/Fonts"]


def font(size, bold=False, mono=False):
    names = ["DejaVuSansMono-Bold.ttf" if bold else "DejaVuSansMono.ttf"] if mono else \
        ["DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"]
    for d in FONT_DIRS:
        for n in names:
            p = os.path.join(d, n)
            if os.path.exists(p):
                return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def gradient(w, h, top=BG_TOP, bottom=BG_BOTTOM):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    return img


def stars(img, seed, count, t=0.0):
    rnd = random.Random(seed)
    d = ImageDraw.Draw(img, "RGBA")
    for _ in range(count):
        x, y = rnd.randrange(img.width), rnd.randrange(img.height)
        speed = 0.5 + rnd.random() * 1.5
        a = int(60 + 170 * (0.5 + 0.5 * math.sin(t * speed * 2 * math.pi + rnd.random() * 6.28)))
        r = 1 if rnd.random() < 0.8 else 2
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, a))
    return img


def rainbow_bar(img, y, height=6, t=0.0):
    d = ImageDraw.Draw(img)
    w = img.width
    for x in range(w):
        import colorsys
        r, g, b = colorsys.hsv_to_rgb(((x / w) + t) % 1.0, 0.55, 1.0)
        d.line([(x, y), (x, y + height)], fill=(int(r * 255), int(g * 255), int(b * 255)))


def icon(name, size):
    p = os.path.join(ICONS, name + ".png")
    im = Image.open(p).convert("RGBA")
    return im.resize((size, size), Image.NEAREST)


def banner():
    w, h = 1280, 320
    img = gradient(w, h)
    stars(img, 5, 140)
    d = ImageDraw.Draw(img, "RGBA")
    d.ellipse([w - 520, -200, w + 120, 300], fill=(123, 63, 255, 40))
    logo = Image.open(LOGO).convert("RGBA").resize((200, 200), Image.LANCZOS)
    img.paste(logo, (70, 60), logo)
    d.text((310, 78), "THEPRISONS", font=font(86, True), fill=WHITE)
    d.text((314, 178), "Client-side Fabric mod for Cosmic Prisons", font=font(30), fill=CYAN)
    d.text((314, 222), "Minecraft 1.21.11  ·  dashboard, HUDs, session stats, storage overlay, Tunnel Vision", font=font(22), fill=MUTED)
    rainbow_bar(img, h - 8, 8)
    save(img, "banner.png")


CARDS = [
    ("dashboard", "page_overview", "Dashboard", "Animated pages for design, controls, HUD,\nmining, bandits and tunnel vision."),
    ("ore-macro", "ore_macro", "Ore Macro", "Own pathfinder, guarded-zone logic, breaks,\nfailsafes and human view motion."),
    ("item-sorter", "storage_overlay", "Item Sorter", "Trips to the vaults for shards, contrabands,\nenergy and money."),
    ("market", "satchel_hud", "Auction House & /ee", "Prices read in the background, own screens;\nsearch reads every page of the auction house."),
    ("shops", "cooldown_cache", "Shop Overlays", "The /gz and /pb shops in the mod's design."),
    ("item-list", "item_look", "Item List", "Search every known item with tiers,\nrarities and prices."),
    ("guard-zones", "waypoint_editor", "Guard Zones", "Stay in the guarded area, look ahead,\nrun to a guard when attacked."),
    ("hud", "session_hud", "HUD Widgets", "Session stats, pets, cooldowns, satchels,\narmour, notifications."),
    ("spear-helper", "spear_helper", "Spear Helper", "Shooter crosshair, sight point, aim assist\non L and recall timing."),
    ("bandit-macro", "category_bandit", "Bandit Macro (WIP)", "Hunts bandits with the spear on key J.\nWork in progress: about 2 % done."),
    ("tunnel-vision", "tunnel_vision", "Tunnel Vision", "F5 + V: your player in 3D on a rainbow road\nover a backdrop of your choice."),
]


def cards():
    os.makedirs(os.path.join(OUT, "cards"), exist_ok=True)
    for slug, ic, title, text in CARDS:
        w, h = 640, 360
        img = gradient(w, h)
        stars(img, hash(slug) & 0xFFFF, 60)
        d = ImageDraw.Draw(img, "RGBA")
        d.rounded_rectangle([14, 14, w - 14, h - 14], radius=18, outline=(255, 255, 255, 40), width=2)
        big = icon(ic, 224)
        d.ellipse([30, 70, 270, 310], fill=(123, 63, 255, 50))
        img.paste(big, (38, 78), big)
        d.text((300, 90), title, font=font(38, True), fill=WHITE)
        d.text((300, 150), text, font=font(20), fill=CYAN, spacing=8)
        d.text((300, 300), "illustration - not a screenshot", font=font(14), fill=MUTED)
        rainbow_bar(img, h - 14, 6)
        save(img, "cards/%s.png" % slug)


def parse_changelog(version):
    text = subprocess.run([os.path.join(ROOT, "scripts", "changelog-section.sh"), version],
                          capture_output=True, text=True, check=True, cwd=ROOT).stdout
    groups, current = {}, None
    for line in text.splitlines():
        m = re.match(r"^### (.+)$", line)
        if m:
            current = m.group(1)
            continue
        m = re.match(r"^\s*[-*] (.+)$", line)
        if m and current:
            item = re.sub(r"\*\*|`", "", m.group(1))
            item = item.split(":")[0] if len(item) > 70 and ":" in item[:70] else item
            groups.setdefault(current, []).append(item)
    return groups


def wrap(draw, text, fnt, width):
    words, lines, line = text.split(), [], ""
    for wd in words:
        trial = (line + " " + wd).strip()
        if draw.textlength(trial, font=fnt) <= width:
            line = trial
        else:
            lines.append(line)
            line = wd
    if line:
        lines.append(line)
    return lines


def changelog(version):
    groups = parse_changelog(version)
    wip = "work in progress" in subprocess.run([os.path.join(ROOT, "scripts", "changelog-section.sh"), version],
                                               capture_output=True, text=True, cwd=ROOT).stdout.lower()
    labels = [("Added", "NEW", CYAN), ("Changed", "IMPROVED", GOLD), ("Fixed", "FIXED", GREEN)]
    w, h = 800, 450
    frames = []
    total = 40
    lines_by_group = {}
    probe = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    for key, _, _ in labels:
        out = []
        for item in groups.get(key, [])[:7]:
            lines = wrap(probe, "• " + item, font(13), 232)
            if len(lines) > 2:
                lines = [lines[0], lines[1].rstrip(" ,;:") + "…"]
            out.extend(lines)
        lines_by_group[key] = out[:12]
    for f in range(total):
        t = f / total
        img = gradient(w, h)
        stars(img, 9, 90, t)
        d = ImageDraw.Draw(img, "RGBA")
        d.ellipse([w - 360, -180, w + 80, 220], fill=(123, 63, 255, 36))
        d.text((32, 24), "THEPRISONS", font=font(20, True), fill=MUTED)
        d.text((32, 50), "v" + version, font=font(54, True), fill=WHITE)
        d.text((w - 250, 36), "Minecraft 1.21.11 · Fabric", font=font(15), fill=CYAN)
        if wip:
            d.rounded_rectangle([w - 330, 62, w - 32, 92], radius=8, fill=(255, 193, 60, 230))
            d.text((w - 181, 77), "Bandit Macro: work in progress", font=font(14, True), fill=(20, 14, 40), anchor="mm")
        for i, (key, label, colour) in enumerate(labels):
            x = 32 + i * 252
            reveal = min(1.0, max(0.0, (f - i * 4) / 10.0))
            if reveal <= 0:
                continue
            y0 = 130 - int((1 - reveal) * 14)
            a = int(255 * reveal)
            d.rounded_rectangle([x - 8, y0 - 12, x + 232 + 8, h - 52], radius=12, fill=(10, 8, 28, int(150 * reveal)),
                                outline=colour + (int(120 * reveal),), width=1)
            d.text((x, y0), label, font=font(19, True), fill=colour + (a,))
            d.line([(x, y0 + 28), (x + 60, y0 + 28)], fill=colour + (a,), width=2)
            for n, line in enumerate(lines_by_group.get(key, [])):
                line_reveal = min(1.0, max(0.0, (f - i * 4 - n) / 6.0))
                if line_reveal > 0:
                    d.text((x, y0 + 40 + n * 19), line, font=font(13), fill=WHITE + (int(255 * line_reveal),))
        rainbow_bar(img, h - 12, 8, t)
        d = ImageDraw.Draw(img)
        d.text((32, h - 40), "github.com/Noxtryn/ThePrisons", font=font(13), fill=MUTED)
        d.text((w - 200, h - 40), "changelog card (graphic)", font=font(12), fill=MUTED)
        frames.append(img.convert("P", palette=Image.ADAPTIVE, colors=96))
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "changelog-%s.gif" % version)
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=[90] * (total - 1) + [1800], loop=0, optimize=True)
    print("wrote", os.path.relpath(path, ROOT), "%d KB" % (os.path.getsize(path) // 1024))


def save(img, name):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", os.path.relpath(path, ROOT))


def main():
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    version = sys.argv[2] if len(sys.argv) > 2 else None
    if what in ("banner", "all"):
        banner()
    if what in ("cards", "all"):
        cards()
    if what in ("changelog", "all"):
        if not version:
            sys.exit("usage: make_graphics.py changelog <version>")
        changelog(version)


if __name__ == "__main__":
    main()
