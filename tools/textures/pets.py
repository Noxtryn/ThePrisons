"""
Pets (32x32 cartoon), each drawn after what it does on Cosmic Prisons:

  anti_xp_tax    "no Guard XP Tax for 30m"  -> green XP orb holding a shield with a crossed-out %
  lucky          "good fortune for 1 minute" -> lucky cat with a raised paw, gold coin and clover
  wormhole       wormhole powerups when enchanting the pickaxe -> portal swirl with a little pickaxe
  shockwave      the Shockwave blast        -> electric ball sending out shock rings
  cleanse        cleanses                    -> water drop with soap bubbles
  signal_jammer  jams signals                -> little robot, crossed-out signal on its screen
  blacksmith     repairs                     -> mole smith with hammer and anvil
  bandit_king    bandits                     -> raccoon with bandit mask, crown and money bag

Unknown pets get one of the GENERIC animals, derived from the name (PrisonsItems.java uses the same lists and order).
The tier shows on the collar and its gem.
"""
from cartoon import Canvas, rgb

WHITE = 0xFFFFFF
BLACK = 0x1A1622
PINK = 0xFF8FB8
GOLD = 0xFFC93C
GREEN = 0x4CD964
RED = 0xF0384C
CYAN = 0x4FE8E0
BLUE = 0x3C9CFF

NAMED = ["anti_xp_tax", "lucky", "wormhole", "shockwave", "cleanse", "signal_jammer", "blacksmith", "bandit_king"]
GENERIC = ["slime", "fox", "rabbit", "owl", "dragon", "pig"]
CREATURES = NAMED + GENERIC


def eyes(c, y, xs=(12, 20), big=False):
    for x in xs:
        if big:
            c.ellipse([x - 3, y - 3, x + 3, y + 3], WHITE)
            c.ellipse([x - 1, y - 1, x + 1, y + 1], BLACK, outline=False, shading=False)
        else:
            c.rect([x - 1, y - 1, x, y + 1], BLACK, outline=False, shading=False)
            c.pixel(x - 1, y - 1, (255, 255, 255))


def collar(c, tier, y=25, x0=8, x1=24):
    c.rect([x0, y, x1, y + 2], tier)
    c.gem((x0 + x1) // 2, y + 3, 2, tier)


# ── named pets ───────────────────────────────────────────────────────────────

def anti_xp_tax(tier):
    c = Canvas()
    c.ellipse([2, 4, 22, 26], 0x7CF05C)                                              # XP orb body
    c.ellipse([6, 8, 18, 20], 0xB8FF8C, outline=False)
    eyes(c, 13, xs=(9, 15))
    c.poly([(16, 12), (29, 12), (29, 22), (22.5, 29), (16, 22)], BLUE)               # shield
    c.ellipse([19, 15, 22, 18], WHITE, outline=False, shading=False)                  # "%"
    c.ellipse([23, 21, 26, 24], WHITE, outline=False, shading=False)
    c.line([(25, 15), (20, 24)], WHITE)
    c.line([(17, 13), (28, 26)], RED, width=2)                                        # crossed out
    collar(c, tier, y=24, x0=4, x1=16)
    c.sparkle(4, 3, rgb(GOLD))
    return c.image()


def lucky(tier):
    c = Canvas()
    c.ellipse([22, 2, 30, 12], 0xFFF6E8)                                              # raised paw
    c.poly([(5, 3), (11, 9), (5, 12)], 0xFFF6E8).poly([(23, 6), (19, 10), (24, 13)], 0xFFF6E8)
    c.ellipse([3, 6, 26, 28], 0xFFF6E8)
    c.ellipse([5, 6, 11, 11], 0xFFA040, outline=False)                                # patches
    c.ellipse([18, 18, 24, 24], 0xFFA040, outline=False)
    eyes(c, 14, xs=(10, 18))
    c.pixel(14, 18, (255, 120, 150))
    c.rect([7, 23, 22, 25], RED)                                                      # collar + coin bell
    c.gem(14, 27, 3, GOLD)
    c.poly([(2, 2), (4, 0), (6, 2), (4, 4)], GREEN, outline=False)                    # clover
    c.gem(26, 25, 2, tier)                                                            # tier gem
    c.sparkle(29, 15, rgb(GOLD))
    return c.image()


def wormhole(tier):
    c = Canvas()
    c.ellipse([3, 4, 29, 30], 0x2A1650)                                               # portal
    for r, col in ((11, 0x6A3AC8), (8, 0x8A4CF0), (5, 0xC08BFF)):
        c.ellipse([16 - r, 17 - r, 16 + r, 17 + r], col, outline=False)
    eyes(c, 17, xs=(16,), big=True)
    c.poly([(22, 3), (30, 1), (29, 4)], 0xA8AEB8)                                     # little pickaxe
    c.line([(21, 9), (26, 3)], 0x9A6236, width=2)
    c.sparkle(4, 4, rgb(CYAN)).sparkle(28, 26, rgb(GOLD))
    collar(c, tier, y=27, x0=11, x1=21)
    return c.image()


def shockwave(tier):
    c = Canvas()
    for box in ([0, 0, 31, 31], [3, 3, 28, 28]):                                      # shock rings
        c.part(lambda d, b=box: d.ellipse(b, outline=255, width=1), 0xBFF4FF, outline=False, shading=False)
    c.ellipse([7, 7, 25, 27], 0x3CC8FF)                                               # body
    for x, y in ((9, 7), (16, 4), (23, 7), (6, 14), (26, 14)):
        c.poly([(x - 2, y + 3), (x, y - 1), (x + 2, y + 3)], 0xFFE04A)                # spark spikes
    eyes(c, 15, xs=(13, 19))
    c.line([(14, 21), (16, 19), (18, 21)], BLACK)
    collar(c, tier, y=24, x0=10, x1=22)
    return c.image()


def cleanse(tier):
    c = Canvas()
    c.poly([(16, 2), (24, 14), (25, 22), (16, 29), (7, 22), (8, 14)], 0x6CC8FF)     # drop
    c.ellipse([10, 13, 22, 27], 0x9CDCFF, outline=False)
    eyes(c, 18, xs=(13, 19))
    c.line([(15, 23), (17, 23)], BLACK)
    for x, y, r in ((4, 6, 2), (27, 9, 3), (26, 25, 2)):                              # bubbles
        c.ellipse([x - r, y - r, x + r, y + r], 0xE8F8FF)
    c.rect([4, 23, 6, 29], WHITE, outline=False).rect([2, 25, 8, 27], WHITE, outline=False)   # plus
    collar(c, tier, y=26, x0=11, x1=21)
    return c.image()


def signal_jammer(tier):
    c = Canvas()
    c.line([(16, 6), (16, 1)], 0x5E626C)
    c.ellipse([14, 0, 18, 4], RED)                                                    # antenna
    c.rect([4, 6, 28, 26], 0xA8AEB8)                                                  # robot head
    c.rect([7, 9, 25, 21], 0x16141E)                                                  # screen
    for r in (3, 6):                                                                  # signal waves
        c.part(lambda d, r=r: d.arc([16 - r, 18 - r, 16 + r, 18 + r], 200, 340, fill=255), 0x4CD964,
               outline=False, shading=False)
    c.pixel(16, 17, (76, 217, 100))
    c.line([(9, 11), (23, 20)], RED, width=2)                                         # jammed
    c.rect([2, 12, 4, 18], 0x5E626C).rect([28, 12, 30, 18], 0x5E626C)                # ears
    collar(c, tier, y=26)
    return c.image()


def blacksmith(tier):
    c = Canvas()
    c.poly([(3, 26), (29, 26), (26, 30), (6, 30)], 0x5E626C)                          # anvil
    c.ellipse([6, 6, 24, 26], 0x7A5038)                                               # mole
    c.rect([9, 16, 21, 26], 0x9A6236)                                                 # apron
    c.ellipse([12, 13, 18, 18], 0xFF9AB8)
    eyes(c, 11, xs=(12, 18))
    c.line([(23, 22), (28, 8)], 0x9A6236, width=2)                                    # hammer
    c.rect([24, 3, 31, 9], 0xA8AEB8)
    collar(c, tier, y=20, x0=9, x1=21)
    c.sparkle(4, 4, rgb(0xFF8A2E)).sparkle(30, 14, rgb(GOLD))
    return c.image()


def bandit_king(tier):
    c = Canvas()
    c.poly([(8, 7), (10, 1), (13, 5), (16, 0), (19, 5), (22, 1), (24, 7)], GOLD)      # crown
    c.poly([(4, 6), (10, 10), (5, 13)], 0x8C8C9C).poly([(28, 6), (22, 10), (27, 13)], 0x8C8C9C)
    c.ellipse([4, 7, 28, 28], 0x8C8C9C)
    c.rect([6, 12, 26, 16], BLACK, outline=False, shading=False)                      # bandit mask
    c.pixel(11, 14, (255, 255, 255)); c.pixel(21, 14, (255, 255, 255))
    c.ellipse([11, 18, 21, 26], 0xE6E6EE)
    c.ellipse([14, 19, 18, 22], BLACK, outline=False)
    c.ellipse([22, 20, 31, 30], 0xC8A060)                                             # money bag
    c.line([(25, 25), (28, 25)], 0x3E9A4E)
    c.gem(12, 3, 1, RED)
    collar(c, tier, y=26, x0=8, x1=20)
    return c.image()


# ── generic animals for unknown pets ─────────────────────────────────────────

def slime(tier):
    c = Canvas()
    c.rect([4, 7, 28, 28], 0x6CE05A)
    c.rect([8, 11, 24, 24], 0x4CB83C, outline=False)
    eyes(c, 15, xs=(11, 21))
    c.rect([14, 20, 18, 21], 0x1A3A12, outline=False, shading=False)
    collar(c, tier, y=26)
    return c.image()


def fox(tier):
    c = Canvas()
    c.poly([(4, 2), (12, 8), (5, 13)], 0xFF7A2E).poly([(28, 2), (20, 8), (27, 13)], 0xFF7A2E)
    c.poly([(4, 8), (16, 5), (28, 8), (24, 22), (16, 28), (8, 22)], 0xFF7A2E)
    c.poly([(8, 16), (16, 19), (24, 16), (20, 24), (16, 27), (12, 24)], 0xFFF4E6)
    c.ellipse([14, 23, 18, 26], BLACK, outline=False)
    eyes(c, 13)
    collar(c, tier, y=27)
    return c.image()


def rabbit(tier):
    c = Canvas()
    c.ellipse([7, 0, 13, 15], 0xF4F4FA).ellipse([19, 0, 25, 15], 0xF4F4FA)
    c.ellipse([9, 2, 11, 12], 0xFFB0CC, outline=False).ellipse([21, 2, 23, 12], 0xFFB0CC, outline=False)
    c.ellipse([5, 9, 27, 29], 0xF4F4FA)
    c.pixel(16, 19, (255, 120, 150))
    eyes(c, 16)
    collar(c, tier, y=26)
    return c.image()


def owl(tier):
    c = Canvas()
    c.poly([(5, 3), (10, 8), (5, 10)], 0x8A5A3A).poly([(27, 3), (22, 8), (27, 10)], 0x8A5A3A)
    c.ellipse([4, 5, 28, 29], 0x8A5A3A)
    c.ellipse([8, 17, 24, 29], 0xD8B890)
    eyes(c, 12, xs=(11, 21), big=True)
    c.poly([(14, 16), (18, 16), (16, 20)], GOLD)
    collar(c, tier, y=26)
    return c.image()


def dragon(tier):
    c = Canvas()
    c.poly([(6, 1), (10, 9), (5, 9)], 0xFFE6A0).poly([(26, 1), (22, 9), (27, 9)], 0xFFE6A0)
    c.ellipse([4, 5, 28, 26], 0x3CB89C)
    c.ellipse([9, 16, 23, 27], 0x5CE0BC)
    eyes(c, 11)
    collar(c, tier, y=26)
    return c.image()


def pig(tier):
    c = Canvas()
    c.ellipse([5, 3, 11, 10], PINK).ellipse([21, 3, 27, 10], PINK)
    c.ellipse([4, 5, 28, 28], PINK)
    c.ellipse([11, 15, 21, 22], 0xFFB0CC)
    c.pixel(14, 18, (138, 74, 96)); c.pixel(18, 18, (138, 74, 96))
    eyes(c, 12)
    collar(c, tier)
    return c.image()


DRAW = {"anti_xp_tax": anti_xp_tax, "lucky": lucky, "wormhole": wormhole, "shockwave": shockwave,
        "cleanse": cleanse, "signal_jammer": signal_jammer, "blacksmith": blacksmith, "bandit_king": bandit_king,
        "slime": slime, "fox": fox, "rabbit": rabbit, "owl": owl, "dragon": dragon, "pig": pig}
