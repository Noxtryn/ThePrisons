"""
MMORPG-style gear icons (32x32): swords, pickaxes, spears and the four armour pieces per material - ornate guards and
trims, a gem per material, a lighter fuller / rim on blades and a glow aura for gold, diamond and netherite.
"""
from cartoon import Canvas, diag, rgb, shade

# material: base, trim, gem, glow (None = no aura)
MATERIALS = {
    "wooden": (0xB07A42, 0x6E4A2A, 0x7CF05C, None),
    "stone": (0x9CA0A8, 0x5E626C, 0xFFB347, None),
    "copper": (0xE38452, 0x4FC8A4, 0x4FC8A4, None),
    "iron": (0xE4E8F0, 0x8C94A4, 0x4C8CFF, None),
    "golden": (0xFFD23C, 0xE0801C, 0xFF3D6E, 0xFFE07A),
    "diamond": (0x4FE8E0, 0xFFC93C, 0x3C6CFF, 0x8AF8FF),
    "netherite": (0x5A4468, 0xFF8A2E, 0xFF5C2E, 0xC08BFF),
    "leather": (0xA86A3C, 0xFFC93C, 0x4FC8A4, None),
    "chainmail": (0xA8AEB8, 0x5E626C, 0x4C8CFF, None),
}
GRIP = 0x4A2E22
GRIP_WRAP = 0xC89A6A
WOOD_SHAFT = 0x9A6236


def sword(material):
    base, trim, gem, glow = MATERIALS[material]
    p = diag((4.0, 28.0))
    c = Canvas()
    # blade: leaf-shaped, widest a third up, long tip
    blade = [p(9.5, -2.6), p(18, -3.4), p(30, -2.4), p(36.5, 0), p(30, 2.4), p(18, 3.4), p(9.5, 2.6)]
    c.poly(blade, base)
    c.line([p(11, 0), p(31, 0)], shade(rgb(base), 0.28), width=1)          # fuller
    c.line([p(12, -2.4), p(29, -1.8)], shade(rgb(base), 0.4), width=1)     # rim light
    # guard: wings curving up towards the blade
    guard = [p(8, -2), p(9.5, -6.5), p(12.5, -7.5), p(10.8, -4.2), p(10.8, 4.2), p(12.5, 7.5), p(9.5, 6.5), p(8, 2)]
    c.poly(guard, trim)
    c.gem(*map(round, p(9.4, 0)), 2, gem)
    # grip with wraps, pommel gem
    c.poly([p(2.5, -1.3), p(8, -1.3), p(8, 1.3), p(2.5, 1.3)], GRIP)
    for t in (3.8, 5.6, 7.2):
        c.line([p(t, -1.2), p(t, 1.2)], GRIP_WRAP)
    c.ellipse([p(1, 0)[0] - 2.4, p(1, 0)[1] - 2.4, p(1, 0)[0] + 2.4, p(1, 0)[1] + 2.4], trim)
    c.gem(*map(round, p(1, 0)), 1, gem)
    c.sparkle(*map(round, p(32, -0.6)))
    if glow:
        c.glow(glow)
    return c.image()


def pickaxe(material):
    base, trim, gem, glow = MATERIALS[material]
    p = diag((5.5, 27.0))
    c = Canvas()
    c.poly([p(0, -1.3), p(21, -1.3), p(21, 1.3), p(0, 1.3)], WOOD_SHAFT)            # shaft
    for t in (2.5, 4.5):
        c.line([p(t, -1.2), p(t, 1.2)], GRIP_WRAP)
    head = [p(25.5, 0), p(24.6, -6), p(22, -11), p(17, -14.5), p(19.4, -10.5), p(20.8, -6), p(21.4, 0),
            p(20.8, 6), p(19.4, 10.5), p(17, 14.5), p(22, 11), p(24.6, 6)]
    c.poly(head, base)
    c.line([p(24, -5.5), p(21.5, -10.5)], shade(rgb(base), 0.35))                  # edge light
    c.line([p(24, 5.5), p(21.5, 10.5)], shade(rgb(base), 0.35))
    c.poly([p(20.2, -2.6), p(25, -2.6), p(25, 2.6), p(20.2, 2.6)], trim)           # socket band
    c.gem(*map(round, p(22.6, 0)), 2, gem)
    c.sparkle(*map(round, p(18.5, -13)))
    if glow:
        c.glow(glow)
    return c.image()


def spear(material):
    base, trim, gem, glow = MATERIALS[material]
    p = diag((3.5, 28.5))
    c = Canvas()
    c.poly([p(0, -1.0), p(26, -1.0), p(26, 1.0), p(0, 1.0)], WOOD_SHAFT)
    c.poly([p(22.5, 1.0), p(21.5, 3.5), p(18.5, 6.5), p(19.5, 3.0), p(21.2, 1.0)], 0xE0243A)   # ribbons
    c.poly([p(23.5, 1.0), p(23.5, 4.0), p(21.5, 7.5), p(22.0, 3.5)], 0xFFC93C)
    c.poly([p(23.5, -2.2), p(26.5, -2.2), p(26.5, 2.2), p(23.5, 2.2)], trim)       # collar
    head = [p(26.5, -2.6), p(30, -3.6), p(36.5, 0), p(30, 3.6), p(26.5, 2.6)]
    c.poly(head, base)
    c.line([p(27.5, 0), p(34.5, 0)], shade(rgb(base), 0.3))
    c.gem(*map(round, p(25, 0)), 1, gem)
    c.sparkle(*map(round, p(33, -1.2)))
    if glow:
        c.glow(glow)
    return c.image()


def helmet(material):
    base, trim, gem, glow = MATERIALS[material]
    c = Canvas()
    c.poly([(15, 2), (17, 2), (19, 6), (13, 6)], trim)                               # crest
    c.ellipse([6, 5, 25, 25], base)                                                  # dome
    c.rect([6, 15, 25, 24], base)
    c.poly([(3, 10), (8, 13), (8, 18), (4, 16)], trim)                               # wings
    c.poly([(28, 10), (23, 13), (23, 18), (27, 16)], trim)
    c.rect([9, 16, 22, 18], 0x16141E, shading=False)                                 # visor slit
    c.rect([15, 18, 16, 24], 0x16141E, shading=False)
    c.rect([6, 24, 25, 26], trim)                                                    # rim
    c.gem(15, 11, 2, gem)
    c.sparkle(10, 8)
    if glow:
        c.glow(glow)
    return c.image()


def chestplate(material):
    base, trim, gem, glow = MATERIALS[material]
    c = Canvas()
    c.poly([(7, 6), (12, 4), (15, 9), (16, 9), (19, 4), (24, 6), (25, 27), (6, 27)], base)   # torso
    c.ellipse([2, 4, 11, 12], trim)                                                   # pauldrons
    c.ellipse([20, 4, 29, 12], trim)
    c.line([(8, 20), (23, 20)], shade(rgb(base), -0.25))                              # plate lines
    c.line([(15, 12), (15, 26)], shade(rgb(base), -0.25))
    c.rect([6, 25, 25, 27], trim)                                                     # belt
    c.ellipse([12, 11, 19, 18], trim)                                                 # emblem
    c.gem(15, 14, 2, gem)
    c.sparkle(9, 9)
    if glow:
        c.glow(glow)
    return c.image()


def leggings(material):
    base, trim, gem, glow = MATERIALS[material]
    c = Canvas()
    c.rect([7, 4, 24, 8], trim)                                                       # belt
    c.poly([(7, 8), (15, 8), (14, 28), (8, 28)], base)                                # legs
    c.poly([(16, 8), (24, 8), (23, 28), (17, 28)], base)
    c.ellipse([7, 15, 13, 21], trim)                                                  # knee guards
    c.ellipse([18, 15, 24, 21], trim)
    c.gem(15, 6, 1, gem)
    c.sparkle(10, 11)
    if glow:
        c.glow(glow)
    return c.image()


def boots(material):
    base, trim, gem, glow = MATERIALS[material]
    c = Canvas()
    for x0 in (3, 17):
        c.poly([(x0 + 2, 8), (x0 + 9, 8), (x0 + 9, 20), (x0 + 12, 22), (x0 + 12, 26), (x0 + 1, 26), (x0 + 1, 18)], base)
        c.rect([x0 + 1, 8, x0 + 10, 11], trim)                                        # cuff
        c.poly([(x0 + 1, 12), (x0 - 1, 9), (x0 + 1, 15)], trim)                        # little wing
        c.rect([x0 + 1, 24, x0 + 12, 26], trim)                                       # sole
        c.gem(x0 + 5, 16, 1, gem)
    c.sparkle(7, 13)
    if glow:
        c.glow(glow)
    return c.image()


SWORDS = ["wooden", "stone", "copper", "iron", "golden", "diamond", "netherite"]
TOOLS = SWORDS
ARMOUR = ["leather", "chainmail", "copper", "iron", "golden", "diamond", "netherite"]
PIECES = {"helmet": helmet, "chestplate": chestplate, "leggings": leggings, "boots": boots}
