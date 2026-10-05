#!/usr/bin/env python3
"""
Menu icons of the mod's own screens (dashboard tabs, feature tiles, categories, storage overlay) in the colourful
cartoon look of the item textures: 32x32 GUI sprites, drawn at 16x16 (crisp at GUI scale 2+).

Written to assets/theprisons/textures/gui/sprites/icon/<name>.png, drawn with Ui.icon(context, "<name>", ...).
Run from the repository root:  python3 tools/textures/icons.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from cartoon import Canvas, rgb, shade  # noqa: E402

ROOT = os.path.join("src", "main", "resources", "assets", "theprisons", "textures", "gui", "sprites", "icon")

GOLD = 0xFFC93C
WHITE = 0xFFFFFF
RED = 0xF0384C
GREEN = 0x4CD964
BLUE = 0x3C9CFF
PURPLE = 0xA66CFF
PINK = 0xFF7AC8
CYAN = 0x4FE8E0
ORANGE = 0xFF8A2E
BROWN = 0x9A6236
GREY = 0xA8AEB8
DARK = 0x2A2A36
STEEL = 0xE6EAF2


def pickaxe(c, head=CYAN):
    c.line([(7, 26), (22, 11)], BROWN, width=3, outline=True)
    c.poly([(8, 7), (16, 4), (25, 7), (28, 14), (27, 18), (22, 11), (14, 8), (9, 10)], head)


def overview():
    c = Canvas()
    for x, y, col in ((3, 3, CYAN), (17, 3, PINK), (3, 17, GOLD), (17, 17, GREEN)):
        c.rect([x, y, x + 11, y + 11], col)
        c.line([(x + 3, y + 3), (x + 6, y + 3)], WHITE)
    return c.image()


def design():
    c = Canvas()
    c.ellipse([3, 4, 29, 28], 0xF4E4C8)                                              # palette
    c.ellipse([17, 18, 24, 25], DARK)                                                # thumb hole
    for x, y, col in ((9, 10, RED), (15, 7, GOLD), (21, 10, GREEN), (8, 17, BLUE), (13, 22, PURPLE)):
        c.ellipse([x - 3, y - 3, x + 3, y + 3], col)
    return c.image()


def controls():
    c = Canvas()
    c.rect([2, 9, 30, 25], GREY)                                                     # keyboard
    for row, y in enumerate((12, 17)):
        for x in range(5, 27, 5):
            c.rect([x, y, x + 3, y + 3], WHITE)
    c.rect([9, 21, 22, 23], WHITE)                                                   # space bar
    c.sparkle(27, 4, rgb(CYAN))
    return c.image()


def hud():
    c = Canvas()
    c.rect([2, 5, 30, 27], 0x30324A)                                                 # screen
    c.rect([5, 8, 15, 13], CYAN)                                                     # widgets
    c.rect([5, 16, 13, 18], PINK)
    c.rect([5, 21, 18, 23], GOLD)
    c.rect([21, 8, 27, 23], GREEN)
    return c.image()


def storage():
    c = Canvas()
    c.rect([3, 11, 29, 28], BROWN)                                                   # chest
    c.poly([(3, 11), (6, 4), (26, 4), (29, 11)], shade(rgb(BROWN), 0.15))
    c.line([(3, 12), (29, 12)], GOLD, width=2)
    c.rect([13, 10, 19, 17], GOLD)                                                   # lock
    c.pixel(16, 13, rgb(DARK))
    c.sparkle(27, 2, rgb(PINK))
    return c.image()


def item_look():
    c = Canvas()
    c.ellipse([4, 4, 28, 28], PURPLE)                                                # orb
    c.ellipse([8, 8, 24, 24], shade(rgb(PURPLE), 0.3), outline=False)
    c.poly([(16, 8), (19, 14), (25, 16), (19, 18), (16, 24), (13, 18), (7, 16), (13, 14)], GOLD)  # star
    c.sparkle(27, 3).sparkle(4, 27, rgb(CYAN))
    return c.image()


def scoreboard():
    c = Canvas()
    c.rect([4, 3, 28, 29], 0x30324A)
    c.rect([7, 6, 25, 9], GOLD)                                                      # title
    for i, (w, col) in enumerate(((16, CYAN), (11, PINK), (14, GREEN), (8, WHITE))):
        c.rect([7, 12 + i * 4, 7 + w, 13 + i * 4], col)
    return c.image()


def better_tab():
    c = Canvas()
    c.rect([2, 4, 30, 28], 0x30324A)
    for i, col in enumerate((GREEN, CYAN, PINK, GOLD)):
        y = 7 + i * 5
        c.rect([5, y, 8, y + 3], col)                                                # heads
        c.rect([10, y + 1, 26, y + 2], WHITE)
    return c.image()


def session_hud():
    c = Canvas()
    c.rect([3, 4, 29, 28], 0x30324A)
    c.line([(6, 23), (11, 16), (16, 19), (21, 10), (26, 13)], GREEN, width=2)        # rising chart
    c.poly([(23, 7), (27, 8), (25, 12)], GREEN, outline=False)
    return c.image()


def pet_hud():
    c = Canvas()
    c.ellipse([6, 12, 26, 30], 0x6CE05A)                                             # slime pet
    c.ellipse([10, 16, 15, 21], WHITE)
    c.ellipse([18, 16, 23, 21], WHITE)
    c.pixel(13, 19, rgb(DARK))
    c.pixel(20, 19, rgb(DARK))
    c.poly([(13, 3), (16, 9), (19, 3)], PINK)                                        # heart-ish bow
    return c.image()


def cooldowns():
    c = Canvas()
    c.poly([(8, 3), (24, 3), (16, 16)], GOLD)                                        # hourglass
    c.poly([(8, 29), (24, 29), (16, 16)], GOLD)
    c.poly([(11, 26), (21, 26), (16, 19)], ORANGE, outline=False)
    c.line([(6, 2), (26, 2)], BROWN, width=2)
    c.line([(6, 30), (26, 30)], BROWN, width=2)
    return c.image()


def satchel():
    c = Canvas()
    c.poly([(7, 12), (25, 12), (28, 22), (25, 29), (7, 29), (4, 22)], 0xB07A44)
    c.poly([(10, 6), (22, 6), (24, 12), (8, 12)], shade(rgb(0xB07A44), 0.12))
    c.line([(8, 12), (24, 12)], GOLD, width=2)
    c.poly([(11, 22), (14, 16), (19, 15), (22, 20), (19, 26), (13, 26)], 0xFFD23C)
    return c.image()


def armor():
    c = Canvas()
    c.poly([(4, 6), (11, 3), (16, 7), (21, 3), (28, 6), (26, 14), (24, 13), (24, 29), (8, 29), (8, 13), (6, 14)], CYAN)
    c.gem(16, 16, 3, PURPLE)
    c.line([(12, 22), (20, 22)], WHITE)
    return c.image()


def insights():
    c = Canvas()
    c.ellipse([3, 3, 21, 21], 0xBFF4FF)                                              # magnifier
    c.ellipse([7, 7, 17, 17], shade(rgb(0xBFF4FF), 0.2), outline=False)
    c.line([(19, 19), (28, 28)], BROWN, width=3, outline=True)
    c.line([(8, 9), (10, 7)], WHITE)
    return c.image()


def trade():
    c = Canvas()
    c.line([(5, 11), (23, 11)], GREEN, width=3)                                      # swap arrows
    c.poly([(21, 5), (28, 11), (21, 17)], GREEN)
    c.line([(9, 21), (27, 21)], ORANGE, width=3)
    c.poly([(11, 15), (4, 21), (11, 27)], ORANGE)
    return c.image()


def player_cards():
    c = Canvas()
    c.rect([2, 6, 30, 27], 0x30324A)                                                 # id card
    c.rect([5, 10, 13, 18], 0xE0B890)                                                # head
    c.rect([5, 10, 13, 12], BROWN)
    c.pixel(7, 14, rgb(DARK))
    c.pixel(11, 14, rgb(DARK))
    c.rect([16, 10, 27, 11], GOLD)
    c.rect([16, 14, 24, 15], CYAN)
    c.rect([5, 21, 27, 23], 0xFF5E6C)                                                # health bar
    c.rect([5, 21, 20, 23], GREEN)
    return c.image()


def messages():
    c = Canvas()
    c.poly([(3, 5), (29, 5), (29, 21), (14, 21), (8, 27), (9, 21), (3, 21)], WHITE)  # speech bubble
    for x in (9, 15, 21):
        c.ellipse([x, 11, x + 3, 14], PURPLE)
    c.ellipse([24, 1, 31, 8], RED)                                                   # badge
    return c.image()


def peaceful():
    c = Canvas()
    c.ellipse([3, 3, 29, 29], 0x6CE0A0)
    c.poly([(16, 6), (25, 10), (23, 21), (16, 27), (9, 21), (7, 10)], WHITE)         # shield
    c.line([(12, 16), (15, 20), (21, 11)], GREEN, width=2)                           # tick
    return c.image()


def vitals():
    c = Canvas()
    c.ellipse([3, 6, 17, 20], RED)                                                   # heart
    c.ellipse([15, 6, 29, 20], RED)
    c.poly([(4, 15), (16, 29), (28, 15)], RED)
    c.line([(5, 17), (11, 17), (13, 12), (16, 22), (19, 15), (27, 15)], WHITE)       # pulse
    return c.image()


def ready():
    c = Canvas()
    c.poly([(13, 4), (19, 4), (18, 18), (14, 18)], GOLD)                             # bell
    c.poly([(7, 22), (10, 12), (22, 12), (25, 22)], GOLD)
    c.ellipse([13, 23, 19, 29], ORANGE)
    c.line([(4, 6), (7, 9)], CYAN)
    c.line([(28, 6), (25, 9)], CYAN)
    return c.image()


def cache():
    c = Canvas()
    for y, col in ((22, BLUE), (14, CYAN), (6, 0x9AD8FF)):                           # database stack
        c.ellipse([5, y, 27, y + 8], col)
    return c.image()


def update():
    c = Canvas()
    c.ellipse([3, 3, 29, 29], GREEN)
    c.line([(16, 23), (16, 10)], WHITE, width=3)
    c.poly([(9, 14), (16, 6), (23, 14)], WHITE)
    return c.image()


def ore_macro():
    c = Canvas()
    pickaxe(c, CYAN)
    c.ellipse([19, 19, 30, 30], GOLD)                                                # gear
    c.ellipse([22, 22, 27, 27], DARK)
    return c.image()


def waypoints():
    c = Canvas()
    c.ellipse([8, 3, 24, 19], RED)                                                   # map pin
    c.poly([(9, 14), (16, 29), (23, 14)], RED)
    c.ellipse([13, 8, 19, 14], WHITE)
    return c.image()


def meteor():
    c = Canvas()
    c.line([(4, 4), (16, 16)], ORANGE, width=4)                                      # tail
    c.line([(9, 2), (19, 12)], GOLD, width=2)
    c.ellipse([13, 13, 29, 29], 0x6A4A3A)
    c.ellipse([17, 16, 22, 21], shade(rgb(0x6A4A3A), 0.25), outline=False)
    return c.image()


def bandit():
    c = Canvas()
    c.ellipse([6, 6, 26, 28], 0xE0B890)                                              # face
    c.rect([5, 12, 27, 17], DARK)                                                    # bandit mask
    c.pixel(11, 14, rgb(WHITE))
    c.pixel(20, 14, rgb(WHITE))
    c.poly([(4, 8), (16, 1), (28, 8)], RED)                                          # hat
    return c.image()


def spear():
    c = Canvas()
    c.line([(5, 27), (21, 11)], BROWN, width=3, outline=True)                        # shaft
    c.poly([(19, 13), (24, 3), (29, 8), (19, 13)], STEEL)                            # head
    c.poly([(21, 11), (27, 5), (28, 10)], WHITE, outline=False)
    c.line([(10, 22), (14, 26)], RED, width=2)                                       # grip wrap
    c.sparkle(7, 6, rgb(CYAN))
    c.sparkle(26, 24, rgb(GOLD))
    return c.image()


def tunnel():
    c = Canvas()
    c.rect([2, 4, 30, 28], 0x30324A)                                                 # screen
    c.poly([(14, 14), (18, 14), (28, 27), (4, 27)], DARK)                            # road
    for x0, x1, col in ((7, 10, RED), (10, 13, ORANGE), (13, 16, GOLD), (16, 19, GREEN), (19, 22, BLUE), (22, 25, PURPLE)):
        c.poly([(15 + (x0 - 16) * 0.2, 15), (15 + (x1 - 16) * 0.2, 15), (x1, 27), (x0, 27)], col, outline=False)
    c.sparkle(25, 8, rgb(WHITE))
    return c.image()


def pvp():
    c = Canvas()
    c.line([(5, 5), (25, 25)], STEEL, width=3, outline=True)                         # crossed swords
    c.line([(27, 5), (7, 25)], STEEL, width=3, outline=True)
    c.line([(18, 26), (26, 18)], GOLD, width=2)
    c.line([(6, 18), (14, 26)], GOLD, width=2)
    return c.image()


def combat():
    c = Canvas()
    c.poly([(16, 2), (20, 12), (30, 12), (22, 19), (25, 30), (16, 23), (7, 30), (10, 19), (2, 12), (12, 12)], ORANGE)
    c.poly([(16, 9), (18, 15), (16, 19), (14, 15)], WHITE, outline=False)
    return c.image()


def qol():
    c = Canvas()
    for box in ([10, 2, 22, 14], [18, 9, 30, 21], [10, 17, 22, 29], [2, 9, 14, 21]):  # flower
        c.ellipse(box, PINK)
    c.ellipse([11, 10, 21, 20], GOLD)
    return c.image()


def general():
    c = Canvas()
    c.ellipse([3, 3, 29, 29], GREY)                                                  # cog
    for x, y in ((14, 0), (14, 26), (0, 14), (26, 14)):
        c.rect([x, y, x + 5, y + 5], GREY)
    c.ellipse([10, 10, 22, 22], DARK)
    c.ellipse([13, 13, 19, 19], CYAN)
    return c.image()


def mining():
    c = Canvas()
    pickaxe(c, STEEL)
    c.sparkle(26, 24, rgb(CYAN))
    return c.image()


ICONS = {
    # dashboard tabs
    "page_overview": overview, "page_ores": mining, "page_bandits": spear, "page_tunnel": tunnel, "tunnel_vision": tunnel, "spear_helper": spear,
    "page_design": design, "page_controls": controls, "page_hud": hud,
    # features (module ids)
    "storage_overlay": storage, "item_look": item_look, "scoreboard": scoreboard, "better_tab": better_tab,
    "session_hud": session_hud, "pet_hud": pet_hud, "command_cooldowns": cooldowns, "satchel_hud": satchel,
    "armor_hud": armor, "item_insights": insights, "sneak_trade": trade, "player_cards": player_cards, "message_notifications": messages,
    "peaceful_mining": peaceful, "vitals_warnings": vitals, "ready_announcements": ready, "cooldown_cache": cache,
    "update_checker": update, "ore_macro": ore_macro, "waypoint_editor": waypoints,
    # categories (fallback for modules without an own icon)
    "category_mining": mining, "category_meteor_mining": meteor, "category_bandit": bandit, "category_pvp": pvp,
    "category_combat": combat, "category_qol": qol, "category_hud": hud, "category_general": general,
}


def main():
    os.makedirs(ROOT, exist_ok=True)
    for name, draw in ICONS.items():
        draw().save(os.path.join(ROOT, name + ".png"))
    print("wrote", len(ICONS), "menu icons")


if __name__ == "__main__":
    main()
