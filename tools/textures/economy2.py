"""
More Cosmic items in the colourful cartoon look (32x32): G-Kits (beacons), flares, trinkets, slot bot ticket sleeves
and scraps, inmate rations, showcase rows, expanders, prestige modifiers, executive shards / time extenders,
powerups, rare candies, skill tokens, upgrades (cell doors and the rest), charge orb slots and menu buttons (next /
previous page, back, refresh, close).
"""
from cartoon import Canvas, rgb, shade

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


def gkit(colour, accent):
    c = Canvas()
    c.poly([(6, 27), (26, 27), (24, 22), (8, 22)], GREY)                 # pedestal
    c.rect([9, 18, 23, 22], DARK)
    c.poly([(16, 2), (24, 10), (22, 19), (10, 19), (8, 10)], colour)     # crystal
    c.poly([(16, 2), (16, 19), (10, 19), (8, 10)], shade(rgb(colour), 0.15), outline=False)
    c.line([(16, 4), (16, 17)], WHITE)
    c.ellipse([2, 24, 8, 30], accent).ellipse([24, 24, 30, 30], accent)
    c.sparkle(5, 5).sparkle(27, 7, rgb(accent))
    return c.image()


def flare(colour, rock=None):
    c = Canvas()
    if rock:
        c.ellipse([16, 18, 30, 30], rock)
    c.poly([(5, 25), (17, 13), (21, 17), (9, 29)], colour)               # stick
    c.poly([(5, 25), (8, 22), (12, 26), (9, 29)], WHITE)                 # cap
    c.poly([(17, 13), (21, 17), (26, 8), (22, 4)], ORANGE)               # flame
    c.poly([(19, 12), (22, 14), (25, 7), (22, 6)], GOLD, outline=False)
    c.sparkle(27, 3).sparkle(28, 12, rgb(GOLD)).sparkle(14, 6, rgb(ORANGE))
    return c.image()


def trinket(gem_colour, symbol):
    c = Canvas()
    c.line([(8, 3), (16, 9), (24, 3)], GOLD, width=2)                     # chain
    c.ellipse([7, 8, 25, 26], GOLD)
    c.ellipse([10, 11, 22, 23], gem_colour)
    if symbol == "eye":
        c.ellipse([12, 14, 20, 20], WHITE, outline=False)
        c.ellipse([15, 15, 17, 19], DARK, outline=False, shading=False)
    elif symbol == "heart":
        c.poly([(16, 21), (11, 16), (12, 13), (16, 15), (20, 13), (21, 16)], WHITE, outline=False)
    elif symbol == "shield":
        c.poly([(12, 13), (20, 13), (20, 17), (16, 21), (12, 17)], WHITE, outline=False)
    elif symbol == "cross":
        c.rect([15, 12, 17, 22], WHITE, outline=False).rect([12, 15, 20, 17], WHITE, outline=False)
    else:
        c.line([(14, 13), (17, 13), (18, 15), (16, 17), (16, 19)], WHITE, width=2)
        c.pixel(16, 21, rgb(WHITE))
    c.gem(16, 28, 2, gem_colour)
    c.sparkle(6, 10).sparkle(27, 12, rgb(gem_colour))
    return c.image()


def ticket_sleeve():
    c = Canvas()
    c.rect([4, 6, 28, 28], 0x7A3AC8)                                     # sleeve
    c.rect([7, 3, 25, 12], GOLD)                                        # tickets sticking out
    c.rect([9, 1, 23, 9], 0xFFE08A)
    for x0 in (11, 15, 19):
        c.line([(x0, 3), (x0 + 2, 3), (x0 + 1, 7)], RED)
    c.rect([4, 14, 28, 18], 0x5A2A9A, outline=False)
    c.sparkle(26, 22, rgb(GOLD))
    return c.image()


def ticket_scrap():
    c = Canvas()
    c.poly([(4, 12), (14, 8), (16, 14), (8, 20)], GOLD)
    c.poly([(16, 16), (27, 12), (28, 22), (18, 25)], GOLD)
    c.line([(7, 13), (11, 12)], RED).line([(20, 17), (24, 16)], RED)
    c.sparkle(26, 6, rgb(GOLD))
    return c.image()


def inmate_rations():
    c = Canvas()
    c.rect([13, 2, 19, 6], BROWN)
    c.rect([12, 6, 20, 10], 0xCDEBFF)
    c.ellipse([5, 9, 27, 30], 0xCDEBFF)
    c.ellipse([7, 14, 25, 28], ORANGE)
    for y in (17, 21, 25):
        c.line([(8, y), (24, y)], 0x1A1A20)                              # prison stripes
    c.rect([10, 15, 22, 19], 0xFFF4D6)
    c.pixel(12, 17, rgb(RED)); c.pixel(14, 17, rgb(RED)); c.pixel(16, 17, rgb(RED))
    c.sparkle(9, 12)
    return c.image()


def showcase_row():
    c = Canvas()
    c.rect([2, 18, 30, 24], BROWN)                                       # shelf
    c.rect([2, 24, 30, 26], shade(rgb(BROWN), -0.2))
    for x, col in ((4, CYAN), (13, GOLD), (22, PINK)):
        c.rect([x, 6, x + 7, 17], 0xE6F4FF)                              # glass cases
        c.gem(x + 3, 12, 2, col)
    c.sparkle(28, 4)
    return c.image()


def expander(colour, kind):
    c = Canvas()
    if kind == "home":
        c.poly([(4, 15), (16, 4), (28, 15)], RED)
        c.rect([7, 15, 25, 28], 0xFFE6B0)
        c.rect([13, 20, 19, 28], BROWN)
    else:
        c.rect([4, 10, 28, 28], colour)
        c.rect([3, 7, 29, 12], shade(rgb(colour), 0.12))
        c.rect([14, 10, 18, 16], GOLD)
    c.ellipse([19, 1, 31, 13], GREEN)                                    # "+" badge
    c.rect([24, 3, 26, 11], WHITE, outline=False).rect([21, 6, 29, 8], WHITE, outline=False)
    return c.image()


def prestige_modifier(colour):
    c = Canvas()
    c.poly([(16, 2), (29, 16), (16, 30), (3, 16)], colour)               # rune stone
    c.poly([(16, 6), (25, 16), (16, 26), (7, 16)], shade(rgb(colour), 0.15), outline=False)
    c.ellipse([11, 11, 21, 21], GOLD)                                    # gear
    for x, y in ((16, 9), (16, 23), (9, 16), (23, 16)):
        c.rect([x - 1, y - 1, x + 1, y + 1], GOLD, outline=False)
    c.ellipse([14, 14, 18, 18], DARK, outline=False)
    c.sparkle(5, 5).sparkle(27, 26, rgb(GOLD))
    return c.image()


def executive_shard():
    c = Canvas()
    c.poly([(16, 1), (24, 10), (21, 29), (11, 29), (8, 10)], 0x1E1A2A)
    c.poly([(16, 1), (16, 29), (11, 29), (8, 10)], 0x3A3050, outline=False)
    c.line([(16, 3), (13, 14), (18, 20), (15, 27)], GOLD, width=1)
    c.sparkle(26, 4, rgb(GOLD)).sparkle(5, 20, rgb(PURPLE))
    return c.image()


def time_extender():
    c = Canvas()
    c.rect([7, 2, 25, 5], GOLD).rect([7, 27, 25, 30], GOLD)
    c.poly([(9, 5), (23, 5), (17, 16), (23, 27), (9, 27), (15, 16)], 0xCDEBFF)
    c.poly([(11, 7), (21, 7), (16, 14)], 0xFFD23C, outline=False)
    c.poly([(16, 18), (21, 25), (11, 25)], 0xFFD23C, outline=False)
    c.sparkle(27, 10)
    return c.image()


def powerup(colour, symbol):
    c = Canvas()
    c.rect([9, 3, 23, 6], GREY)
    c.ellipse([5, 5, 27, 29], colour)
    c.ellipse([9, 9, 23, 25], shade(rgb(colour), 0.2), outline=False)
    if symbol == "bolt":
        c.poly([(18, 8), (11, 18), (16, 18), (14, 26), (21, 15), (16, 15)], WHITE)
    elif symbol == "two":
        c.line([(12, 11), (19, 11), (19, 16), (12, 16), (12, 22), (20, 22)], WHITE, width=2)
    elif symbol == "plus":
        c.rect([15, 10, 17, 24], WHITE, outline=False).rect([9, 16, 23, 18], WHITE, outline=False)
    else:
        c.line([(13, 12), (17, 11), (19, 14), (16, 17), (16, 20)], WHITE, width=2)
        c.pixel(16, 23, rgb(WHITE))
    c.sparkle(4, 4, rgb(GOLD)).sparkle(28, 26)
    return c.image()


def candy(colour):
    c = Canvas()
    c.poly([(2, 10), (8, 14), (8, 18), (2, 22)], shade(rgb(colour), -0.1))    # wrapper ends
    c.poly([(30, 10), (24, 14), (24, 18), (30, 22)], shade(rgb(colour), -0.1))
    c.ellipse([7, 8, 25, 24], colour)
    for x in (11, 16, 21):
        c.line([(x, 9), (x - 3, 23)], WHITE)                                  # swirl stripes
    c.sparkle(16, 4).sparkle(27, 26, rgb(GOLD))
    return c.image()


def skill_token():
    c = Canvas()
    c.ellipse([3, 3, 29, 29], GOLD)
    c.ellipse([6, 6, 26, 26], PURPLE)
    c.poly([(9, 12), (16, 9), (23, 12), (23, 22), (16, 19), (9, 22)], WHITE)   # open book
    c.line([(16, 9), (16, 19)], PURPLE)
    c.sparkle(16, 2).sparkle(27, 25, rgb(GOLD))
    return c.image()


def upgrade(colour):
    c = Canvas()
    c.rect([6, 4, 22, 30], GREY)                                         # cell door
    for x in (9, 13, 17):
        c.rect([x, 6, x + 2, 28], DARK, outline=False)
    c.ellipse([16, 12, 31, 27], colour)                                  # up arrow badge
    c.poly([(23, 14), (28, 20), (25, 20), (25, 25), (21, 25), (21, 20), (18, 20)], WHITE)
    return c.image()


def charge_orb_slot():
    c = Canvas()
    c.rect([4, 4, 28, 28], 0x2A2A36)
    c.rect([7, 7, 25, 25], 0x4A3A6A)
    c.ellipse([10, 10, 22, 22], PURPLE)
    c.ellipse([20, 1, 31, 12], GREEN)
    c.rect([25, 3, 26, 10], WHITE, outline=False).rect([22, 6, 29, 7], WHITE, outline=False)
    return c.image()


def menu_arrow(direction, colour):
    c = Canvas()
    c.ellipse([2, 2, 30, 30], colour)
    if direction == "right":
        c.poly([(10, 10), (18, 10), (18, 6), (27, 16), (18, 26), (18, 22), (10, 22)], WHITE)
    else:
        c.poly([(22, 10), (14, 10), (14, 6), (5, 16), (14, 26), (14, 22), (22, 22)], WHITE)
    c.sparkle(7, 6)
    return c.image()


def menu_refresh():
    c = Canvas()
    c.ellipse([2, 2, 30, 30], BLUE)
    c.line([(9, 18), (9, 12), (12, 9), (20, 9)], WHITE, width=2)
    c.poly([(19, 5), (24, 9), (19, 13)], WHITE, outline=False)
    c.line([(23, 14), (23, 20), (20, 23), (12, 23)], WHITE, width=2)
    c.poly([(13, 19), (8, 23), (13, 27)], WHITE, outline=False)
    return c.image()


def menu_close():
    c = Canvas()
    c.ellipse([2, 2, 30, 30], RED)
    c.line([(10, 10), (22, 22)], WHITE, width=3)
    c.line([(22, 10), (10, 22)], WHITE, width=3)
    return c.image()


TIERS = {"simple": 0xD8DEE8, "uncommon": 0x5DE86B, "elite": 0x4FD8F0, "ultimate": 0xFFE04A, "legendary": 0xFF9A2E,
         "godly": 0xFF3D6E}

FIXED = {
    "gkit/sludge": lambda: gkit(0x6CE05A, 0x2EB84A), "gkit/astronaut": lambda: gkit(0xE6F4FF, BLUE),
    "gkit/starforged": lambda: gkit(0xFFC93C, PURPLE), "gkit/slasher": lambda: gkit(0xE0243A, DARK),
    "gkit/generic": lambda: gkit(CYAN, GOLD),
    "flare/meteor": lambda: flare(0xC8501A, rock=0x6A4A3A), "flare/gkit": lambda: flare(PURPLE),
    "flare/fractured": lambda: flare(CYAN, rock=0x8AD8FF), "flare/basic": lambda: flare(RED),
    "trinket/blink": lambda: trinket(PURPLE, "eye"), "trinket/healing": lambda: trinket(RED, "heart"),
    "trinket/absorption": lambda: trinket(GOLD, "shield"), "trinket/resistance": lambda: trinket(GREY, "cross"),
    "trinket/random": lambda: trinket(PINK, "?"), "trinket/generic": lambda: trinket(CYAN, "shield"),
    "misc/ticket_sleeve": ticket_sleeve, "misc/ticket_scrap": ticket_scrap, "misc/inmate_rations": inmate_rations,
    "misc/showcase_row": showcase_row, "misc/pv_expander": lambda: expander(PURPLE, "vault"),
    "misc/home_expander": lambda: expander(RED, "home"), "misc/expander": lambda: expander(BLUE, "vault"),
    "misc/prestige_modifier": lambda: prestige_modifier(0x4FD8F0),
    "misc/prestige_modifier_random": lambda: prestige_modifier(PINK),
    "misc/executive_shard": executive_shard, "misc/time_extender": time_extender,
    "misc/skill_token": skill_token, "misc/charge_orb_slot": charge_orb_slot,
    "powerup/overdrive": lambda: powerup(RED, "bolt"), "powerup/bogo": lambda: powerup(GREEN, "plus"),
    "powerup/double_tap": lambda: powerup(BLUE, "two"), "powerup/random": lambda: powerup(PINK, "?"),
    "powerup/generic": lambda: powerup(ORANGE, "bolt"),
    "menu/next": lambda: menu_arrow("right", GREEN), "menu/previous": lambda: menu_arrow("left", ORANGE),
    "menu/back": lambda: menu_arrow("left", RED), "menu/refresh": menu_refresh, "menu/close": menu_close,
}
for _tier, _colour in TIERS.items():
    FIXED["candy/" + _tier] = (lambda col: (lambda: candy(col)))(_colour)
    FIXED["upgrade/" + _tier] = (lambda col: (lambda: upgrade(col)))(_colour)
