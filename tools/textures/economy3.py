"""
The last Cosmic items found in the logs, in the colourful cartoon look (32x32): ore satchels per ore (ore, deepslate,
block and random), lucky charm, rabbit's foot, item nametag, lore crystal, flip credit, pet leash, boss egg,
skill tree reset, ancient altar pillar, pet incubator, kill message / title vouchers, rare pickaxe enchant and flowers.
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
LEATHER = 0xB07A44
GREY = 0xA8AEB8
DARK = 0x2A2A36
PAPER = 0xFFF4D6

# ore colours of the satchels (Cosmic: "Gold Ore Satchel", "Deepslate Gold Ore Satchel", "Block of Gold Satchel")
ORES = {"coal": 0x3A3A44, "iron": 0xE8C8A8, "lapis": 0x2E5CE0, "redstone": 0xF02A2A, "gold": 0xFFD23C,
        "diamond": 0x5CF0E8, "emerald": 0x3CE06A, "prismarine": 0x6CC8B4, "quartz": 0xF4EEE6, "amethyst": 0xB06CFF}


def satchel(ore, kind):
    """A leather sack tied with a gold cord; its contents show the ore (chunk, deepslate chunk, block or "?")."""
    c = Canvas()
    sack = 0x5A4A5A if kind == "deepslate" else LEATHER
    c.poly([(7, 12), (25, 12), (28, 22), (25, 29), (7, 29), (4, 22)], sack)          # bag
    c.poly([(10, 6), (22, 6), (24, 12), (8, 12)], shade(rgb(sack), 0.12))            # neck
    c.line([(8, 12), (24, 12)], GOLD, width=2)                                       # cord
    c.ellipse([22, 10, 27, 15], GOLD)                                                # knot
    colour = ORES.get(ore, PINK)
    if kind == "block":
        c.rect([10, 16, 21, 26], colour)                                              # a block of the ore
        c.line([(10, 21), (21, 21)], shade(rgb(colour), -0.25))
        c.line([(15, 16), (15, 26)], shade(rgb(colour), -0.25))
    elif kind == "random":
        c.ellipse([10, 15, 22, 27], PINK)
        c.line([(14, 18), (16, 17), (18, 18), (18, 20), (16, 21), (16, 23)], WHITE)    # "?"
        c.pixel(16, 25, rgb(WHITE))
    else:
        c.poly([(11, 22), (14, 16), (19, 15), (22, 20), (19, 26), (13, 26)], colour)  # ore chunk
        c.pixel(15, 18, rgb(WHITE))
        c.pixel(19, 21, rgb(WHITE), 180)
    c.sparkle(5, 5, rgb(colour)).sparkle(28, 4)
    return c.image()


def lucky_charm():
    c = Canvas()
    for box in ([6, 4, 16, 14], [16, 4, 26, 14], [6, 14, 16, 24], [16, 14, 26, 24]):  # four-leaf clover
        c.ellipse(box, GREEN)
    c.ellipse([13, 11, 19, 17], shade(rgb(GREEN), 0.25))
    c.line([(16, 22), (18, 29)], 0x2EA84A, width=2)
    c.sparkle(4, 26, rgb(GOLD)).sparkle(28, 3)
    return c.image()


def rabbits_foot():
    c = Canvas()
    c.ellipse([9, 4, 23, 24], 0xE8D8C8)                                              # foot
    c.ellipse([12, 18, 20, 26], 0xFFF0E8)
    for x in (10, 14, 18):
        c.ellipse([x, 2, x + 5, 8], 0xF4E4D4)                                        # toes
    c.rect([12, 24, 20, 29], GOLD)                                                   # gold cap
    c.ellipse([14, 28, 18, 31], GOLD)
    c.sparkle(27, 6, rgb(GOLD)).sparkle(4, 14, rgb(PINK))
    return c.image()


def nametag():
    c = Canvas()
    c.poly([(3, 13), (9, 7), (29, 7), (29, 25), (9, 25), (3, 19)], PAPER)             # tag
    c.ellipse([6, 14, 10, 18], DARK)                                                 # hole
    c.line([(1, 9), (7, 15)], GREY)                                                  # string
    for y in (12, 16, 20):
        c.line([(13, y), (26, y)], 0x8C94A4)
    c.line([(13, 12), (22, 12)], PURPLE)
    c.sparkle(27, 3, rgb(GOLD))
    return c.image()


def lore_crystal():
    c = Canvas()
    c.poly([(16, 2), (24, 12), (16, 30), (8, 12)], CYAN)                              # crystal
    c.poly([(16, 2), (16, 30), (8, 12)], shade(rgb(CYAN), 0.18), outline=False)
    c.line([(16, 4), (16, 28)], WHITE)
    for y in (13, 17, 21):                                                           # lore lines inside
        c.line([(13, y), (19, y)], PURPLE)
    c.sparkle(4, 4).sparkle(27, 24, rgb(PINK))
    return c.image()


def flip_credit():
    c = Canvas()
    c.ellipse([4, 4, 28, 28], GOLD)                                                  # coin
    c.ellipse([7, 7, 25, 25], shade(rgb(GOLD), 0.12))
    c.line([(10, 13), (21, 13)], BLUE, width=2)                                      # flip arrows
    c.poly([(19, 10), (23, 13), (19, 16)], BLUE, outline=False)
    c.line([(11, 19), (22, 19)], ORANGE, width=2)
    c.poly([(13, 16), (9, 19), (13, 22)], ORANGE, outline=False)
    c.sparkle(27, 3)
    return c.image()


def pet_leash():
    c = Canvas()
    c.line([(5, 26), (10, 18), (18, 14), (24, 8)], LEATHER, width=2, outline=True)    # leash
    c.ellipse([20, 3, 30, 13], RED)                                                  # collar ring
    c.ellipse([2, 23, 10, 31], GOLD)                                                 # handle loop
    c.ellipse([24, 15, 30, 21], PINK)                                                # paw tag
    c.sparkle(6, 6, rgb(PINK))
    return c.image()


def boss_egg():
    c = Canvas()
    c.ellipse([7, 3, 25, 29], 0x6A3A8A)                                              # egg
    c.ellipse([10, 6, 22, 16], shade(rgb(0x6A3A8A), 0.2), outline=False)
    for x, y in ((11, 18), (19, 12), (17, 23)):                                      # spots
        c.ellipse([x - 2, y - 2, x + 2, y + 2], ORANGE)
    c.poly([(12, 3), (14, 0), (16, 3), (18, 0), (20, 3)], GOLD)                       # crown
    c.sparkle(4, 6, rgb(ORANGE)).sparkle(28, 26, rgb(PINK))
    return c.image()


def skill_tree_reset():
    c = Canvas()
    c.line([(16, 28), (16, 14)], BROWN, width=2, outline=True)                       # trunk
    c.line([(16, 18), (10, 12)], BROWN, width=1, outline=True)
    c.line([(16, 16), (22, 10)], BROWN, width=1, outline=True)
    for x, y in ((10, 10), (22, 8), (16, 8)):                                        # skill nodes
        c.ellipse([x - 3, y - 3, x + 3, y + 3], CYAN)
    c.line([(4, 22), (4, 27), (9, 27)], ORANGE, width=2)                             # reset arrow
    c.poly([(1, 23), (4, 19), (7, 23)], ORANGE, outline=False)
    c.sparkle(28, 26, rgb(GOLD))
    return c.image()


def altar_pillar():
    c = Canvas()
    c.rect([8, 26, 24, 30], GREY)                                                    # base
    c.rect([11, 6, 21, 26], 0xD8D0C0)                                                # column
    for x in (13, 16, 19):
        c.line([(x, 8), (x, 24)], shade(rgb(0xD8D0C0), -0.18))
    c.rect([8, 3, 24, 7], GREY)                                                      # capital
    c.gem(16, 15, 3, PURPLE)
    c.sparkle(4, 4, rgb(PURPLE)).sparkle(28, 12)
    return c.image()


def pet_incubator():
    c = Canvas()
    c.rect([6, 22, 26, 29], GREY)                                                    # base
    c.ellipse([7, 4, 25, 26], 0xBFF4FF)                                              # glass dome
    c.ellipse([11, 10, 21, 24], 0xFFF0D8)                                            # egg inside
    c.ellipse([13, 13, 16, 16], PINK, outline=False)
    c.line([(9, 9), (11, 7)], WHITE)
    c.rect([10, 24, 13, 26], GREEN).rect([19, 24, 22, 26], RED)                       # lights
    c.sparkle(28, 4, rgb(PINK))
    return c.image()


def voucher(colour, glyph):
    """Kill message / title vouchers: a ribbon scroll with a skull or a crown."""
    c = Canvas()
    c.rect([4, 9, 28, 23], PAPER)
    c.rect([2, 7, 6, 25], colour).rect([26, 7, 30, 25], colour)                       # rolled ends
    if glyph == "skull":
        c.ellipse([11, 10, 21, 19], WHITE)
        c.rect([13, 18, 19, 22], WHITE)
        c.pixel(14, 14, rgb(DARK))
        c.pixel(18, 14, rgb(DARK))
    else:
        c.poly([(10, 20), (10, 12), (13, 15), (16, 10), (19, 15), (22, 12), (22, 20)], GOLD)
    c.sparkle(28, 3, rgb(colour))
    return c.image()


def rare_pickaxe_enchant():
    c = Canvas()
    c.ellipse([3, 3, 29, 29], PURPLE)                                                # glowing rune circle
    c.ellipse([6, 6, 26, 26], shade(rgb(PURPLE), 0.25), outline=False)
    c.line([(11, 22), (21, 12)], BROWN, width=2, outline=True)
    c.poly([(12, 9), (17, 9), (24, 12), (25, 17), (21, 13), (17, 12), (11, 12)], 0xE6EAF2)
    c.sparkle(5, 5, rgb(GOLD)).sparkle(27, 26, rgb(CYAN))
    return c.image()


def flower(petal, centre):
    c = Canvas()
    c.line([(16, 30), (16, 16)], 0x2EA84A, width=2)
    c.ellipse([18, 21, 26, 26], GREEN)                                               # leaf
    for box in ([10, 4, 18, 12], [16, 6, 24, 14], [8, 10, 16, 18], [16, 11, 24, 19], [12, 13, 20, 21]):
        c.ellipse(box, petal)
    c.ellipse([13, 9, 19, 15], centre)
    c.sparkle(4, 5, rgb(petal)).sparkle(27, 26)
    return c.image()


FIXED = {
    "misc/lucky_charm": lucky_charm, "misc/rabbits_foot": rabbits_foot, "misc/nametag": nametag,
    "misc/lore_crystal": lore_crystal, "misc/flip_credit": flip_credit, "misc/pet_leash": pet_leash,
    "misc/boss_egg": boss_egg, "misc/skill_tree_reset": skill_tree_reset, "misc/altar_pillar": altar_pillar,
    "misc/pet_incubator": pet_incubator, "misc/kill_message": lambda: voucher(RED, "skull"),
    "misc/title": lambda: voucher(GOLD, "crown"), "misc/rare_pickaxe_enchant": rare_pickaxe_enchant,
    "misc/aether_bloom": lambda: flower(0x9AD8FF, WHITE), "misc/red_roses": lambda: flower(RED, 0xFFD23C),
    "satchel/random": lambda: satchel("random", "random"),
}
for _ore in ORES:
    for _kind in ("ore", "deepslate", "block"):
        FIXED["satchel/" + _ore + ("" if _kind == "ore" else "_" + _kind)] = (lambda o, k: (lambda: satchel(o, k)))(_ore, _kind)
