#!/usr/bin/env python3
"""
Animated trailer of the mod (docs/media/trailer.mp4): an ILLUSTRATION drawn by this script from the mod's icons and
text - not a recording of the game. Needs Pillow and ffmpeg.

  python3 scripts/make_trailer.py [version]
"""
import colorsys
import math
import os
import random
import subprocess
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_graphics as g  # noqa: E402

W, H, FPS = 1280, 720, 30
SCENE = 2.6  # seconds per scene
FADE = 0.45
BG = g.gradient(W, H)


def ease(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def hsv(h, s=0.6, v=1.0):
    r, gg, b = colorsys.hsv_to_rgb(h % 1.0, s, v)
    return int(r * 255), int(gg * 255), int(b * 255)


def card(d, box, alpha=200, accent=g.VIOLET):
    d.rounded_rectangle(box, 14, fill=(14, 12, 30, alpha))
    d.rounded_rectangle([box[0], box[1], box[0] + 5, box[3]], 3, fill=accent + (255,))


def head(img, d, t, icon_name, title, lines):
    a = ease(t / 0.5)
    ic = g.icon(icon_name, 96)
    img.paste(ic, (70 - int((1 - a) * 60), 120), ic)
    d.text((190 - int((1 - a) * 60), 128), title, font=g.font(54, True), fill=g.WHITE + (int(255 * a),))
    for i, line in enumerate(lines):
        la = ease((t - 0.25 - i * 0.18) / 0.4)
        d.text((78 + int((1 - la) * 50), 270 + i * 46), "▸ " + line, font=g.font(26), fill=g.CYAN + (int(255 * la),))


# ── the panels on the right ──────────────────────────────────────────────────

def panel_dashboard(img, d, t):
    card(d, (640, 110, 1210, 600))
    tabs = ["Overview", "Mining", "Bandits", "Tunnel", "Design", "Controls", "HUD"]
    for i, name in enumerate(tabs):
        on = int(t * 2.2) % len(tabs) == i
        d.rounded_rectangle((665, 135 + i * 52, 800, 177 + i * 52), 8, fill=(g.VIOLET + (230,)) if on else (40, 36, 70, 200))
        d.text((680, 145 + i * 52), name, font=g.font(18, True), fill=g.WHITE)
    for i in range(5):
        y = 140 + i * 90
        d.text((830, y), ["Auto sprint", "Guard radius", "Ore packages", "Boxy font", "Animations"][i], font=g.font(20), fill=g.WHITE)
        d.rounded_rectangle((830, y + 36, 1180, y + 48), 6, fill=(60, 56, 100, 255))
        d.rounded_rectangle((830, y + 36, 830 + int(350 * (0.3 + 0.6 * abs(math.sin(t * 1.3 + i)))), y + 48), 6, fill=hsv(i / 6 + t * 0.1) + (255,))


def panel_macro(img, d, t):
    card(d, (640, 110, 1210, 600), accent=g.GOLD)
    rnd = random.Random(3)
    for _ in range(60):  # ores
        x, y = rnd.randrange(665, 1185), rnd.randrange(135, 575)
        d.rectangle((x, y, x + 12, y + 12), fill=(g.GOLD if rnd.random() < 0.5 else g.CYAN) + (200,))
    pts = []
    for i in range(90):
        x = 665 + i * 5.8
        y = 355 + 120 * math.sin(i * 0.12 + 0.4) * math.cos(i * 0.03)
        pts.append((x, y))
    n = int(len(pts) * ease((t % 2.4) / 2.0))
    for i in range(1, n):
        d.line([pts[i - 1], pts[i]], fill=g.GREEN + (255,), width=5)
    if n:
        x, y = pts[n - 1]
        d.ellipse((x - 11, y - 11, x + 11, y + 11), fill=g.WHITE + (255,), outline=g.PINK + (255,), width=4)
    d.text((665, 560), "illustration · route + ores", font=g.font(15), fill=g.MUTED)


def panel_guard(img, d, t):
    card(d, (640, 110, 1210, 600), accent=g.GREEN)
    cx, cy = 925, 355
    for r, a in ((200, 40), (140, 55), (80, 70)):
        d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=g.GREEN + (150,), fill=g.GREEN + (a // 3,), width=3)
    d.rectangle((cx - 9, cy - 9, cx + 9, cy + 9), fill=g.GOLD + (255,))
    ang = t * 1.1
    px, py = cx + math.cos(ang) * 150, cy + math.sin(ang) * 150
    d.ellipse((px - 10, py - 10, px + 10, py + 10), fill=g.WHITE + (255,))
    d.text((cx - 60, cy + 215), "guarded zone", font=g.font(20, True), fill=g.GREEN)
    d.text((670, 560), "tax 10 % inside  ·  turns back outside", font=g.font(18), fill=g.MUTED)


def panel_sorter(img, d, t):
    card(d, (640, 110, 1210, 600), accent=g.PINK)
    items = ["Godly shards", "Contraband", "Energy", "Money", "Satchels", "Whitescrolls"]
    for i, name in enumerate(items):
        a = ease(t * 1.4 - i * 0.3)
        y = 140 + i * 72
        d.rounded_rectangle((670, y, 1180, y + 56), 10, fill=(40, 36, 70, int(230 * a)))
        d.text((700, y + 14), name, font=g.font(22, True), fill=g.WHITE + (int(255 * a),))
        d.rounded_rectangle((1000, y + 20, 1150, y + 34), 6, fill=g.GREEN + (int(255 * a),))


def panel_market(img, d, t):
    card(d, (640, 110, 1210, 600), accent=g.GOLD)
    d.rounded_rectangle((665, 135, 1185, 180), 10, fill=(40, 36, 70, 255))
    text = "gold pickaxe"[:int(t * 8) % 14]
    d.text((685, 145), "🔍 " + text if False else "search: " + text, font=g.font(22), fill=g.WHITE)
    for i in range(5):
        y = 205 + i * 72
        d.rounded_rectangle((665, y, 1185, y + 60), 10, fill=(30, 28, 60, 255))
        d.rectangle((680, y + 14, 712, y + 46), fill=hsv(i / 5) + (255,))
        d.text((730, y + 8), ["Gold Pickaxe", "Energy Booster", "Shard Pack", "White Scroll", "Absorber"][i], font=g.font(20, True), fill=g.WHITE)
        d.text((730, y + 34), f"lowest ${(i + 3) * 12_340:,}", font=g.font(15), fill=g.CYAN)


def panel_hud(img, d, t):
    card(d, (640, 110, 1210, 600), accent=g.CYAN)
    stats = [("Energy/h", "6.88M"), ("XP/h", "4.05M"), ("Ores/s", "7.4"), ("Uptime", "01:24:10")]
    for i, (k, v) in enumerate(stats):
        a = ease(t * 1.5 - i * 0.25)
        x, y = 665 + (i % 2) * 265, 140 + (i // 2) * 120
        d.rounded_rectangle((x, y, x + 245, y + 100), 12, fill=(40, 36, 70, int(240 * a)))
        d.text((x + 16, y + 12), k, font=g.font(18), fill=g.MUTED + (int(255 * a),))
        d.text((x + 16, y + 42), v, font=g.font(34, True), fill=g.WHITE + (int(255 * a),))
    bx = 665 + int(math.sin(t * 2) * 8)
    d.rounded_rectangle((bx, 400, bx + 520, 450), 10, fill=(14, 12, 30, 230), outline=g.VIOLET + (255,), width=2)
    d.text((bx + 16, 413), "+63.8 XP (67.5k/min)  +124.6 CE (114.7k/min)", font=g.font(20), fill=g.WHITE)
    d.text((665, 470), "drag · scale · snap - the HUD editor", font=g.font(22, True), fill=g.CYAN)
    d.text((665, 510), "action bar as a movable element in Tunnel Vision", font=g.font(18), fill=g.MUTED)


def panel_storage(img, d, t):
    card(d, (640, 110, 1210, 600), accent=g.BLUE)
    for p in range(3):
        x0 = 665 + p * 180
        a = ease(t * 1.5 - p * 0.3)
        d.rounded_rectangle((x0, 140, x0 + 165, 430), 10, fill=(30, 28, 60, int(240 * a)))
        d.text((x0 + 12, 148), f"Vault #{p + 1}", font=g.font(16, True), fill=g.WHITE + (int(255 * a),))
        for i in range(24):
            xx, yy = x0 + 12 + (i % 6) * 24, 180 + (i // 6) * 36
            d.rectangle((xx, yy, xx + 20, yy + 28), fill=hsv(i / 24 + p * 0.2, 0.5, 0.9) + (int(220 * a),))
    for i in range(9):
        d.rounded_rectangle((665 + i * 58, 480, 665 + i * 58 + 52, 532), 6, fill=(40, 36, 70, 255))


def panel_players(img, d, t):
    card(d, (640, 110, 1210, 600), accent=g.PINK)
    d.rounded_rectangle((680, 140, 1170, 330), 14, fill=(30, 28, 60, 255))
    d.ellipse((700, 160, 780, 240), fill=g.PINK + (255,))
    d.text((800, 160), "Player card", font=g.font(28, True), fill=g.WHITE)
    d.text((800, 205), "rank · gang · health · gear", font=g.font(18), fill=g.CYAN)
    d.text((700, 270), "right-click a player", font=g.font(18), fill=g.MUTED)
    blue = int(t * 2) % 2 == 0
    for i in range(4):
        y = 360 + i * 54
        d.rounded_rectangle((680, y, 1170, y + 44), 8, fill=(40, 36, 70, 255))
        d.text((700, y + 9), ["Friend", "Gang", "Neutral", "Friend"][i], font=g.font(20, True), fill=(g.BLUE if i in (0, 3) else g.PINK if i == 1 else g.WHITE) + (255,))
    d.text((680, 575), "/trade on sneak + click" + (" ▮" if blue else ""), font=g.font(16), fill=g.MUTED)


def panel_bandit(img, d, t):
    card(d, (640, 110, 1210, 600), accent=g.GOLD)
    cx, cy = 925, 340
    for r in (30, 80, 140):
        d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=g.GOLD + (160,), width=2)
    d.line((cx - 170, cy, cx + 170, cy), fill=g.GOLD + (200,), width=2)
    d.line((cx, cy - 170, cx, cy + 170), fill=g.GOLD + (200,), width=2)
    tx, ty = cx + math.cos(t * 1.4) * 110, cy + math.sin(t * 1.9) * 70
    d.rectangle((tx - 10, ty - 10, tx + 10, ty + 10), fill=g.PINK + (255,))
    d.line((tx, ty, cx, cy), fill=g.CYAN + (230,), width=3)
    d.text((665, 540), "spear helper · aim assist (L) · recall timing", font=g.font(20, True), fill=g.CYAN)


def panel_tunnel(img, d, t):
    card(d, (640, 110, 1210, 600), accent=g.VIOLET)
    vx, vy = 925, 250
    for k in range(40):  # road rows towards the horizon
        z = ((k / 40.0) + t * 0.5) % 1.0
        y = vy + (590 - vy) * z * z
        half = 20 + 260 * z * z
        d.line((vx - half, y, vx + half, y), fill=hsv(k / 40 + t * 0.3) + (int(80 + 150 * z),), width=3)
    for side in (-1, 1):
        d.line((vx, vy, vx + side * 280, 590), fill=g.WHITE + (120,), width=2)
    py = 470 + int(math.sin(t * 5) * 6)
    d.rectangle((vx - 22, py - 90, vx + 22, py), fill=g.WHITE + (255,))
    d.rectangle((vx - 22, py - 90, vx + 22, py - 60), fill=g.PINK + (255,))
    sx = vx + int(math.sin(t * 2.2) * 120)
    d.polygon([(sx, 300), (sx + 14, 322), (sx, 344), (sx - 14, 322)], fill=g.PINK + (255,))
    d.text((665, 565), "player size 80 %", font=g.font(16), fill=g.MUTED)


SCENES = [
    ("page_overview", "Dashboard", ["Animated pages, every setting a control", "Themes, cards, boxy font, comic textures", "Opens with /prisons or I"], panel_dashboard),
    ("ore_macro", "Ore Macro", ["Own pathfinder, tunnel centring", "Planned routes from world memory", "Human view motion, failsafes"], panel_macro),
    ("waypoint_editor", "Guard Zones", ["Stays in the guarded area", "Strict outside rules near players", "Runs to a guard, recovers after death"], panel_guard),
    ("storage_overlay", "Item Sorter", ["Trips to spawn and the vaults", "Shards, contraband, energy, money", "Exact whitescrolls + absorbers"], panel_sorter),
    ("satchel_hud", "Market", ["Auction house, history, /ee, shops", "Own screens with search", "Lowest prices and worth in energy"], panel_market),
    ("session_hud", "HUD", ["Session stats, pets, cooldowns, satchels", "Energy/h and XP/h from the action bar", "HUD editor: drag, scale, snap"], panel_hud),
    ("storage_overlay", "Storage Overlay", ["/pv shows all vaults as cards", "Pages stay fully usable"], panel_storage),
    ("player_cards", "Players", ["Friends and gang colours", "Player cards, Shift + Tab list", "Sneak Trade"], panel_players),
    ("spear_helper", "Bandits", ["Spear helper crosshair + lead and drop", "Aim assist on bandits", "Recall timing"], panel_bandit),
    ("tunnel_vision", "Tunnel Vision", ["F5 + V: your player on a rainbow road", "Magic carpet, shootable targets", "Action bar and stats stay with you"], panel_tunnel),
]


def intro(t, version):
    img = BG.copy()
    g.stars(img, 7, 160, t)
    d = ImageDraw.Draw(img, "RGBA")
    a = ease(t / 0.8)
    logo = Image.open(g.LOGO).convert("RGBA").resize((220, 220), Image.LANCZOS)
    img.paste(logo, (W // 2 - 110, 130 - int((1 - a) * 40)), logo)
    d.text((W // 2, 420), "THEPRISONS", font=g.font(96, True), fill=g.WHITE + (int(255 * a),), anchor="mm")
    b = ease((t - 0.6) / 0.6)
    d.text((W // 2, 505), "Client-side Fabric mod for Cosmic Prisons", font=g.font(32), fill=g.CYAN + (int(255 * b),), anchor="mm")
    d.text((W // 2, 555), f"v{version}  ·  Minecraft 1.21.11", font=g.font(22), fill=g.MUTED + (int(255 * b),), anchor="mm")
    return img


def outro(t, version):
    img = BG.copy()
    g.stars(img, 9, 160, t)
    d = ImageDraw.Draw(img, "RGBA")
    a = ease(t / 0.6)
    d.text((W // 2, 280), "Mine smarter.", font=g.font(84, True), fill=g.WHITE + (int(255 * a),), anchor="mm")
    d.text((W // 2, 380), f"ThePrisons v{version}", font=g.font(44, True), fill=g.CYAN + (int(255 * a),), anchor="mm")
    d.text((W // 2, 450), "github.com/olb-freelocs/ThePrisons", font=g.font(30), fill=g.WHITE + (int(255 * a),), anchor="mm")
    d.text((W // 2, 650), "trailer illustration - not a game recording", font=g.font(16), fill=g.MUTED, anchor="mm")
    return img


def scene_frame(i, t):
    icon_name, title, lines, panel = SCENES[i]
    img = BG.copy()
    g.stars(img, 11 + i, 90, t)
    d = ImageDraw.Draw(img, "RGBA")
    head(img, d, t, icon_name, title, lines)
    a = ease(t / 0.5)
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer, "RGBA")
    panel(layer, ld, t)
    layer = layer.transform(layer.size, Image.AFFINE, (1, 0, -(1 - a) * 90, 0, 1, 0))
    layer.putalpha(layer.getchannel("A").point(lambda v: int(v * a)))
    img.paste(layer, (0, 0), layer)
    g.rainbow_bar(img, H - 8, 8, t * 0.2)
    return img


def frame_at(sec, version):
    intro_len, outro_len = 3.0, 3.2
    if sec < intro_len:
        return intro(sec, version)
    s = sec - intro_len
    idx = int(s // SCENE)
    if idx >= len(SCENES):
        return outro(sec - intro_len - SCENE * len(SCENES), version)
    return scene_frame(idx, s - idx * SCENE)


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else "1.1.2"
    total = 3.0 + SCENE * len(SCENES) + 3.2
    out = os.path.join(g.OUT, "trailer.mp4")
    os.makedirs(g.OUT, exist_ok=True)
    ff = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
                           "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20",
                           "-movflags", "+faststart", out], stdin=subprocess.PIPE)
    n = int(total * FPS)
    prev_boundary = [3.0 + SCENE * k for k in range(len(SCENES) + 1)]
    for f in range(n):
        sec = f / FPS
        img = frame_at(sec, version)
        # crossfade into the next scene during its first FADE seconds
        for b in [3.0] + prev_boundary[1:]:
            if b <= sec < b + FADE and b > 0:
                old = frame_at(b - 0.01, version)
                img = Image.blend(old.convert("RGB"), img.convert("RGB"), ease((sec - b) / FADE))
                break
        ff.stdin.write(img.convert("RGB").tobytes())
    ff.stdin.close()
    ff.wait()
    print("wrote", os.path.relpath(out, g.ROOT), f"{total:.1f}s")


if __name__ == "__main__":
    main()
