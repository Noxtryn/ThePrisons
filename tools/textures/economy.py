"""
Economy / business items in a colourful cartoon look (32x32): every item mixes its tier colour with bright accents -
gold, cream paper, red wax, green money, candy sprinkles - bold outlines and cel shading.
"""
from cartoon import Canvas, rgb, shade

GOLD = 0xFFC93C
GOLD_DARK = 0xD08A1C
PAPER = 0xFFF4D6
PAPER_DARK = 0xE8D3A6
RED = 0xF0384C
GREEN = 0x4CD964
MONEY = 0x5CC86A
BLUE = 0x3C9CFF
PINK = 0xFF7AC8
PURPLE = 0xA66CFF
CYAN = 0x4FE8E0
BROWN = 0x9A6236
CARDBOARD = 0xD6A060
WHITE = 0xFFFFFF
INK = 0x3A2C4E


def shard(tier):
    c = Canvas()
    c.ellipse([5, 22, 27, 29], 0x8A8090)                                             # rock
    c.poly([(8, 25), (6, 14), (10, 9), (13, 15), (12, 25)], tier)                     # crystals
    c.poly([(19, 25), (20, 12), (24, 8), (27, 13), (24, 25)], tier)
    c.poly([(12, 26), (13, 9), (16, 2), (20, 9), (20, 26)], shade(rgb(tier), 0.08))
    c.line([(15, 6), (14, 20)], shade(rgb(tier), 0.4))
    c.sparkle(25, 4).sparkle(5, 8, rgb(PINK))
    return c.image()


def dust(tier):
    c = Canvas()
    c.poly([(3, 27), (8, 18), (13, 13), (19, 12), (24, 17), (29, 27)], tier)
    for x, y, col in ((10, 21, PINK), (16, 16, GOLD), (21, 22, CYAN), (14, 24, WHITE), (24, 24, PURPLE), (18, 20, PINK)):
        c.pixel(x, y, col)
        c.pixel(x + 1, y, col)
    c.sparkle(8, 9).sparkle(24, 6, rgb(GOLD)).sparkle(16, 4, rgb(CYAN))
    return c.image()


def secret_dust(tier):
    c = Canvas()
    c.ellipse([6, 11, 26, 29], BROWN)                                                 # pouch
    c.poly([(11, 12), (13, 6), (19, 6), (21, 12)], BROWN)
    c.rect([11, 9, 21, 11], GOLD)                                                     # string
    c.ellipse([12, 16, 20, 24], tier)                                                 # glowing rune
    c.line([(16, 17), (16, 23)], WHITE)
    c.line([(13, 20), (19, 20)], WHITE)
    c.sparkle(5, 6, rgb(tier)).sparkle(26, 8, rgb(tier)).sparkle(27, 22)
    return c.image()


def xp_bottle(tier):
    c = Canvas()
    c.rect([13, 2, 19, 6], BROWN)                                                     # cork
    c.rect([12, 6, 20, 10], 0xCDEBFF)                                                 # neck
    c.ellipse([5, 9, 27, 30], 0xCDEBFF)                                               # glass
    c.ellipse([7, 15, 25, 28], tier)                                                  # liquid
    c.rect([10, 18, 22, 23], PAPER)                                                   # label
    c.line([(12, 20), (14, 22)], GREEN)                                               # "XP"
    c.line([(14, 20), (12, 22)], GREEN)
    c.line([(17, 20), (17, 22)], GREEN)
    c.pixel(18, 20, rgb(GREEN))
    c.pixel(18, 21, rgb(GREEN))
    c.sparkle(10, 12).pixel(22, 14, rgb(WHITE))
    return c.image()


def page(tier):
    c = Canvas()
    c.poly([(6, 3), (22, 3), (27, 8), (27, 29), (6, 29)], PAPER)
    c.poly([(22, 3), (27, 8), (22, 8)], PAPER_DARK)
    for y, col, w in ((10, BLUE, 16), (14, RED, 12), (18, GREEN, 15), (22, PURPLE, 9)):
        c.line([(9, y), (9 + w, y)], col)
    c.ellipse([16, 19, 26, 29], tier)                                                 # seal
    c.poly([(18, 27), (17, 31), (20, 29)], tier)
    c.poly([(24, 27), (25, 31), (22, 29)], tier)
    c.sparkle(21, 22)
    return c.image()


def key(tier):
    c = Canvas()
    c.ellipse([3, 3, 16, 16], tier)                                                   # bow
    c.ellipse([7, 7, 12, 12], WHITE)
    c.poly([(13, 13), (15, 11), (28, 24), (26, 26)], GOLD)                             # shaft
    c.poly([(22, 22), (24, 20), (27, 23), (25, 25)], GOLD_DARK)                       # teeth
    c.poly([(18, 18), (20, 16), (23, 19), (21, 21)], GOLD_DARK)
    c.sparkle(5, 5).sparkle(27, 14, rgb(GOLD))
    return c.image()


def clue_scroll(tier):
    c = Canvas()
    c.rect([6, 6, 26, 26], PAPER)
    c.rect([4, 4, 28, 8], PAPER_DARK)                                                 # rolls
    c.rect([4, 25, 28, 29], PAPER_DARK)
    # question mark in tier colour
    c.line([(13, 11), (15, 10), (18, 10), (19, 12), (18, 14), (16, 15), (16, 18)], tier, width=2)
    c.rect([15, 20, 17, 22], tier, outline=False)
    c.ellipse([21, 19, 28, 26], RED)                                                  # wax seal
    c.sparkle(8, 12)
    return c.image()


def book(tier):
    c = Canvas()
    c.rect([6, 5, 26, 28], PAPER)                                                     # page block
    c.rect([4, 3, 24, 27], tier)                                                      # cover
    c.rect([4, 3, 7, 27], shade(rgb(tier), -0.18))                                    # spine
    for x, y in ((21, 3), (21, 24), (8, 3), (8, 24)):
        c.rect([x, y, x + 3, y + 3], GOLD, outline=False)                             # corners
    c.ellipse([11, 10, 20, 19], GOLD)
    c.ellipse([13, 12, 18, 17], WHITE)
    c.poly([(18, 27), (21, 27), (21, 31), (19.5, 29.5), (18, 31)], RED)               # bookmark
    c.sparkle(26, 6)
    return c.image()


def book_revealed(tier):
    c = Canvas()
    c.poly([(2, 12), (15, 10), (16, 28), (3, 29)], tier)                              # covers
    c.poly([(30, 12), (17, 10), (16, 28), (29, 29)], tier)
    c.poly([(4, 11), (15, 9), (16, 26), (5, 27)], PAPER)                              # pages
    c.poly([(28, 11), (17, 9), (16, 26), (27, 27)], PAPER)
    for y, col in ((14, RED), (17, BLUE), (20, GREEN)):
        c.line([(6, y), (13, y - 1)], col)
        c.line([(19, y - 1), (26, y)], col)
    c.sparkle(8, 4, rgb(tier)).sparkle(16, 2).sparkle(24, 5, rgb(GOLD))
    return c.image()


def randomization_scroll(tier):
    c = Canvas()
    c.rect([5, 5, 27, 27], PAPER)
    c.rect([3, 3, 29, 7], tier)
    c.rect([3, 25, 29, 29], tier)
    c.rect([8, 10, 16, 18], WHITE)                                                    # dice
    c.rect([16, 14, 24, 22], WHITE)
    for x, y in ((10, 12), (13, 15), (18, 16), (21, 19), (18, 19), (21, 16)):
        c.pixel(x, y, rgb(RED))
    c.sparkle(25, 10, rgb(GOLD))
    return c.image()


def contraband(tier):
    c = Canvas()
    c.poly([(4, 12), (16, 8), (28, 12), (28, 26), (16, 30), (4, 26)], CARDBOARD)
    c.poly([(4, 12), (16, 16), (28, 12), (16, 8)], shade(rgb(CARDBOARD), 0.12))
    c.line([(16, 16), (16, 30)], shade(rgb(CARDBOARD), -0.25))
    c.poly([(9, 10), (13, 9), (25, 13), (21, 14)], tier)                              # tape
    c.ellipse([7, 17, 14, 24], RED)                                                   # "!" sticker
    c.line([(10, 18), (10, 21)], WHITE)
    c.pixel(10, 23, rgb(WHITE))
    c.sparkle(25, 6, rgb(tier))
    return c.image()


def _orb_shell(c, tier):
    """A glass crystal ball on a small gold stand, glowing in the tier colour inside (not a book: round, see-through)."""
    c.poly([(10, 27), (22, 27), (24, 30), (8, 30)], GOLD)                             # stand
    c.rect([12, 25, 20, 27], GOLD_DARK)
    c.ellipse([4, 2, 28, 26], shade(rgb(tier), -0.35))                                # dark glass rim
    c.ellipse([6, 4, 26, 24], tier, outline=False)                                    # inner glow
    c.ellipse([10, 8, 22, 20], shade(rgb(tier), 0.35), outline=False, shading=False)  # bright core


def _orb_shine(c):
    c.line([(9, 8), (11, 6), (14, 5)], WHITE)                                         # glass highlight
    c.pixel(8, 10, rgb(WHITE))
    c.pixel(22, 21, rgb(WHITE), 160)
    c.sparkle(27, 3, rgb(GOLD)).sparkle(3, 22, rgb(PINK))


def enchant_orb(tier):
    """Tool / pickaxe enchant orb: a pickaxe floating in the crystal ball."""
    c = Canvas()
    _orb_shell(c, tier)
    c.line([(12, 19), (20, 11)], BROWN, width=2, outline=True)               # handle
    c.poly([(13, 8), (17, 8), (23, 11), (24, 15), (20, 12), (16, 11), (12, 11)], 0xE6EAF2)   # head
    _orb_shine(c)
    return c.image()


def spear_orb(tier):
    """Spear enchant orb: a spear across the crystal ball."""
    c = Canvas()
    _orb_shell(c, tier)
    c.line([(9, 20), (19, 10)], BROWN, width=1, outline=True)                # shaft
    c.poly([(18, 8), (24, 6), (22, 12)], 0xE6EAF2)                                    # blade
    c.line([(16, 12), (20, 14)], GOLD)                                                # guard
    _orb_shine(c)
    return c.image()


def reroll(tier):
    c = Canvas()
    c.ellipse([3, 3, 29, 29], GOLD)                                                   # token
    c.ellipse([6, 6, 26, 26], tier)
    # two circular arrows
    c.line([(10, 18), (10, 13), (13, 10), (19, 10)], WHITE, width=2)
    c.poly([(18, 7), (22, 10), (18, 13)], WHITE, outline=False)
    c.line([(22, 14), (22, 19), (19, 22), (13, 22)], WHITE, width=2)
    c.poly([(14, 19), (10, 22), (14, 25)], WHITE, outline=False)
    c.sparkle(26, 4)
    return c.image()


def pet(tier):
    c = Canvas()
    c.ellipse([5, 6, 27, 30], tier)                                                   # egg
    c.ellipse([10, 14, 22, 26], WHITE)                                                # paw
    for x, y in ((9, 10), (14, 8), (19, 8), (23, 11)):
        c.ellipse([x - 2, y - 2, x + 2, y + 2], WHITE)
    c.ellipse([13, 17, 19, 23], PINK)
    c.sparkle(7, 4, rgb(GOLD)).sparkle(26, 6)
    return c.image()


def charge_orb(stage, colour):
    c = Canvas()
    c.poly([(9, 27), (23, 27), (21, 23), (11, 23)], GOLD)                             # stand
    c.ellipse([5, 3, 27, 25], 0xE6E0FF)                                               # glass
    r = 4 + stage * 2
    c.ellipse([16 - r, 14 - r, 16 + r, 14 + r], colour)                               # energy
    c.line([(12, 10), (16, 14), (20, 11)], WHITE)
    c.pixel(9, 7, rgb(WHITE))
    if stage == 4:
        c.sparkle(4, 4, rgb(PINK)).sparkle(28, 6)
    return c.image()


def prestige_token(level, colour):
    c = Canvas()
    c.poly([(9, 18), (6, 30), (11, 27), (14, 30), (14, 20)], RED)                     # ribbons
    c.poly([(23, 18), (26, 30), (21, 27), (18, 30), (18, 20)], BLUE)
    c.ellipse([4, 2, 28, 26], colour)                                                 # medal
    c.ellipse([8, 6, 24, 22], shade(rgb(colour), 0.15))
    star = [(16, 7), (18, 12), (23, 12), (19, 15), (21, 20), (16, 17), (11, 20), (13, 15), (9, 12), (14, 12)]
    c.poly(star, WHITE)
    for i in range(min(level, 10)):
        c.pixel(7 + i * 2, 24, rgb(GOLD) if i % 2 == 0 else rgb(WHITE))
    if level >= 8:
        c.sparkle(27, 3)
    return c.image()


def booster(kind):
    colour, accent = {"xp": (GREEN, 0x2E9A3C), "energy": (CYAN, BLUE), "gp": (0xFFA03C, RED)}[kind]
    c = Canvas()
    c.ellipse([3, 3, 29, 29], GOLD)
    c.ellipse([6, 6, 26, 26], colour)
    if kind == "xp":
        c.poly([(16, 8), (23, 16), (19, 16), (19, 24), (13, 24), (13, 16), (9, 16)], WHITE)
    elif kind == "energy":
        c.poly([(18, 7), (10, 17), (15, 17), (13, 25), (22, 14), (17, 14)], WHITE)
    else:
        c.poly([(11, 11), (21, 11), (24, 15), (16, 24), (8, 15)], WHITE)
        c.line([(11, 15), (21, 15)], accent)
    c.ellipse([22, 20, 30, 28], accent)                                               # "x2" bubble
    c.pixel(25, 23, rgb(WHITE))
    c.pixel(27, 25, rgb(WHITE))
    c.pixel(26, 24, rgb(WHITE))
    c.sparkle(6, 5)
    return c.image()


def absorber():
    c = Canvas()
    c.ellipse([4, 4, 28, 28], 0x2A4E9C)
    for r, col in ((10, BLUE), (7, CYAN), (4, WHITE)):
        c.ellipse([16 - r, 16 - r, 16 + r, 16 + r], col, outline=False)
    c.line([(6, 12), (12, 6)], PURPLE)
    c.line([(26, 20), (20, 26)], PURPLE)
    c.sparkle(26, 5, rgb(CYAN))
    return c.image()


def scroll(paper, glyph, ribbon):
    c = Canvas()
    c.rect([6, 6, 26, 26], paper)
    c.rect([4, 3, 28, 7], shade(rgb(paper), -0.12))
    c.rect([4, 25, 28, 29], shade(rgb(paper), -0.12))
    c.line([(11, 12), (21, 12)], glyph)
    c.line([(11, 16), (19, 16)], glyph)
    c.line([(11, 20), (17, 20)], glyph)
    c.ellipse([19, 17, 26, 24], ribbon)
    c.sparkle(26, 10, rgb(GOLD))
    return c.image()


def eraser():
    c = Canvas()
    c.poly([(4, 18), (16, 8), (22, 14), (10, 24)], PINK)
    c.poly([(16, 8), (24, 2), (30, 8), (22, 14)], BLUE)
    c.line([(19, 6), (25, 12)], WHITE)
    c.sparkle(6, 8)
    return c.image()


def coins():
    c = Canvas()
    for y in (24, 20, 16):
        c.ellipse([5, y, 23, y + 6], GOLD)
    c.ellipse([12, 4, 29, 21], GOLD)
    c.line([(20, 7), (20, 18)], GOLD_DARK)                                            # "$"
    c.line([(23, 9), (18, 9), (18, 12), (23, 12), (23, 15), (17, 15)], GOLD_DARK)
    c.sparkle(14, 6).sparkle(4, 14, rgb(WHITE))
    return c.image()


def cosmic_energy():
    c = Canvas()
    c.poly([(16, 2), (24, 10), (21, 28), (11, 28), (8, 10)], CYAN)
    c.poly([(18, 7), (12, 17), (16, 17), (14, 25), (21, 14), (17, 14)], WHITE)
    c.sparkle(5, 5, rgb(PINK)).sparkle(27, 18, rgb(GOLD))
    return c.image()


def gang_points():
    c = Canvas()
    c.rect([5, 2, 7, 30], BROWN)
    c.poly([(8, 4), (28, 4), (24, 10), (28, 16), (8, 16)], RED)
    star = [(15, 6), (16, 9), (19, 9), (17, 11), (18, 14), (15, 12), (12, 14), (13, 11), (11, 9), (14, 9)]
    c.poly(star, GOLD, outline=False)
    c.sparkle(26, 22, rgb(GOLD))
    return c.image()


def gen_breaker():
    c = Canvas()
    c.poly([(5, 27), (19, 13), (22, 16), (8, 30)], BROWN)
    c.poly([(13, 4), (25, 2), (30, 13), (20, 18)], 0xA8AEB8)
    c.poly([(16, 10), (24, 8), (25, 12), (18, 14)], RED)
    c.sparkle(27, 4, rgb(GOLD)).sparkle(4, 10, rgb(GOLD))
    return c.image()


def money_note():
    c = Canvas()
    c.rect([3, 14, 27, 27], 0x3E9A4E)
    c.rect([5, 10, 29, 23], MONEY)
    c.ellipse([13, 12, 21, 21], 0x9CF0A6)
    c.line([(17, 13), (17, 20)], 0x2E7A3C)
    c.line([(19, 14), (15, 14), (15, 16), (19, 16), (19, 18), (15, 18)], 0x2E7A3C)
    c.sparkle(26, 7, rgb(GOLD))
    return c.image()


def cosmic_crate():
    c = Canvas()
    c.rect([4, 10, 28, 29], PURPLE)
    c.rect([3, 6, 29, 12], shade(rgb(PURPLE), 0.12))
    c.rect([14, 6, 18, 29], GOLD, outline=False)
    star = [(16, 14), (18, 18), (22, 18), (19, 21), (20, 25), (16, 23), (12, 25), (13, 21), (10, 18), (14, 18)]
    c.poly(star, GOLD)
    c.sparkle(26, 3).sparkle(5, 3, rgb(PINK))
    return c.image()


def slot_bot_ticket():
    c = Canvas()
    c.poly([(2, 9), (30, 9), (30, 14), (28, 16), (30, 18), (30, 23), (2, 23), (2, 18), (4, 16), (2, 14)], GOLD)
    for x0 in (8, 14, 20):                                                            # "777"
        c.line([(x0, 13), (x0 + 3, 13), (x0 + 1, 19)], RED, width=1)
    c.sparkle(27, 5).sparkle(4, 26, rgb(PINK))
    return c.image()


TIERED = {"shard": shard, "dust": dust, "secret_dust": secret_dust, "xp_bottle": xp_bottle, "page": page,
          "key": key, "clue_scroll": clue_scroll, "book": book, "book_revealed": book_revealed,
          "randomization_scroll": randomization_scroll, "contraband": contraband, "enchant_orb": enchant_orb, "spear_orb": spear_orb,
          "reroll": reroll, "pet": pet}
FIXED = {
    "booster/xp": lambda: booster("xp"), "booster/energy": lambda: booster("energy"), "booster/gp": lambda: booster("gp"),
    "enchant/absorber": absorber,
    "enchant/white_scroll": lambda: scroll(0xF8F6F0, 0x8C94A4, GOLD),
    "enchant/black_scroll": lambda: scroll(0x3A3446, PURPLE, PURPLE),
    "enchant/eraser": eraser,
    "misc/cosmic_coins": coins, "misc/cosmic_energy": cosmic_energy, "misc/gang_points": gang_points,
    "misc/gen_breaker": gen_breaker, "misc/money_note": money_note, "misc/cosmic_crate": cosmic_crate,
    "misc/slot_bot_ticket": slot_bot_ticket,
}
ORB_STAGES = [0x9A5CF0, 0xB86CFF, 0xE07AFF, 0xFF7AC8]
TOKEN_COLOURS = [0xC0743C, 0xD08A4C, 0xC8CED8, 0xE6EAF2, 0xFFD23C, 0xFFB02E, 0x5CE89C, 0x4FD8F0, 0xC08BFF, 0xFF5CC0]
