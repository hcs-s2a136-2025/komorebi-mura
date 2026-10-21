import sys, io, json, base64
sys.argv = [sys.argv[0], "/dev/null"] + sys.argv[1:]
import art
from art import *

SP = sys.argv[2]


def b64(im):
    buf = io.BytesIO(); im.save(buf, "PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def water_tile(seed=0):
    im = Image.new("RGBA", (16, 16), (70, 136, 204, 255))
    rnd = random.Random(seed)
    for _ in range(8):
        px(im, rnd.randrange(16), rnd.randrange(16), (54, 110, 182))
    for x, y in ((3 + seed % 3, 4), (4 + seed % 3, 4), (10, 11 - seed % 2), (11, 11 - seed % 2)):
        px(im, x, y, (150, 200, 238))
    return im


def tree():
    im = new(32, 32)
    rect(im, 13, 20, 6, 10, (130, 86, 56)); rect(im, 13, 20, 2, 10, (164, 114, 74)); rect(im, 17, 20, 2, 10, (96, 62, 44))
    rect(im, 11, 29, 10, 1, (96, 62, 44))
    G = ramp((84, 150, 76))
    for cx, cy, r in ((16, 13, 10), (9, 16, 6), (23, 16, 6), (16, 7, 7)):
        ball(im, cx, cy, r, r * 0.9, G)
    for x, y in ((11, 9), (19, 6), (22, 14), (14, 15)):
        px(im, x, y, G[0])
    for x, y in ((20, 11), (10, 14)):
        px(im, x, y, (236, 96, 90)); px(im, x + 1, y, (250, 150, 130))
    return outline(im)


def sprout():
    im = new(16, 16)
    G = (96, 176, 80)
    for p in ((8, 10), (8, 9), (7, 8), (6, 8), (9, 8), (10, 7), (8, 11)):
        px(im, *p, G)
    px(im, 6, 7, (140, 206, 110)); px(im, 10, 6, (140, 206, 110))
    return outline(im)


def doormat():
    im = new(16, 16)
    rect(im, 2, 5, 12, 8, (176, 96, 80)); rect(im, 3, 6, 10, 6, (206, 130, 104))
    for x in range(4, 13, 2):
        rect(im, x, 7, 1, 4, (176, 96, 80))
    return im


def hoe():
    im = new(16, 16)
    for i in range(10):
        px(im, 3 + i, 13 - i, WOOD[2]); px(im, 4 + i, 13 - i, WOOD[1])
    M = [(220, 224, 234), (170, 176, 190), (110, 114, 130)]
    rect(im, 10, 2, 5, 2, M[1]); rect(im, 10, 2, 5, 1, M[0]); rect(im, 13, 4, 2, 3, M[2]); px(im, 13, 4, M[1])
    return outline(im)


def can():
    im = new(16, 16)
    C = ramp((90, 160, 200))
    rect(im, 5, 6, 8, 7, C[2]); rect(im, 5, 6, 2, 7, C[1]); rect(im, 11, 6, 2, 7, C[3]); rect(im, 5, 6, 8, 1, C[0])
    for i in range(4):
        px(im, 4 - i, 9 - i, C[2]); px(im, 4 - i, 10 - i, C[3])
    rect(im, 0, 5, 2, 2, C[1])
    for x in range(6, 12):
        px(im, x, 3, C[3])
    px(im, 6, 4, C[3]); px(im, 11, 4, C[3]); px(im, 6, 5, C[3]); px(im, 11, 5, C[3])
    return outline(im)


def seed_bag(col):
    im = new(16, 16)
    P = [(236, 214, 170), (212, 182, 132), (170, 136, 92)]
    rect(im, 4, 4, 8, 10, P[0]); rect(im, 10, 4, 2, 10, P[1]); rect(im, 4, 13, 8, 1, P[2])
    rect(im, 4, 3, 8, 1, P[1]); px(im, 5, 2, P[2]); px(im, 8, 2, P[2]); px(im, 10, 2, P[2])
    ball(im, 7.5, 9, 2.6, 2.6, ramp(col), spot=False)
    px(im, 7, 8, mix(col, (255, 255, 255), .6))
    return outline(im)


def young(kind):
    im = new(16, 16)
    G, D = (96, 176, 80), (66, 130, 62)
    if kind == "cabbage":
        ball(im, 8, 10, 3.6, 3, ramp((140, 200, 100)))
        px(im, 5, 7, G); px(im, 11, 7, G)
    else:
        for y in range(6, 13):
            px(im, 8, y, D)
        leaves = [(6, 6), (7, 7), (10, 6), (9, 7), (6, 9), (7, 9), (10, 9), (9, 9), (8, 5)]
        if kind == "carrot":
            leaves += [(5, 5), (11, 5), (6, 4), (10, 4), (8, 4)]
        for p in leaves:
            px(im, *p, G)
        if kind == "tomato":
            for p in ((10, 10), (6, 11)):
                px(im, *p, (150, 200, 90)); px(im, p[0] + 1, p[1], (120, 170, 70))
    return outline(im)


def tilled_wet():
    im = tilled()
    for y in range(16):
        for x in range(16):
            r, g, b, a = im.getpixel((x, y))
            im.putpixel((x, y), (int(r * .72), int(g * .7), int(b * .74), 255))
    return im


METAL = [(226, 230, 240), (176, 182, 196), (116, 120, 136)]


def axe():
    im = new(16, 16)
    for i in range(10):
        px(im, 3 + i, 13 - i, WOOD[2]); px(im, 4 + i, 13 - i, WOOD[1])
    rect(im, 9, 1, 3, 6, METAL[1]); rect(im, 12, 2, 2, 4, METAL[0]); px(im, 13, 1, METAL[0]); px(im, 13, 6, METAL[0])
    rect(im, 9, 1, 1, 6, METAL[2])
    return outline(im)


def pick():
    im = new(16, 16)
    for i in range(10):
        px(im, 3 + i, 13 - i, WOOD[2]); px(im, 4 + i, 13 - i, WOOD[1])
    for x, y in ((6, 3), (7, 2), (8, 2), (9, 2), (10, 2), (11, 2), (12, 3), (13, 4), (14, 6), (5, 4), (4, 5)):
        px(im, x, y, METAL[1])
    for x, y in ((7, 3), (8, 3), (11, 3), (12, 4), (13, 5)):
        px(im, x, y, METAL[2])
    px(im, 9, 1, METAL[0]); px(im, 10, 1, METAL[0])
    return outline(im)


def rock(ore=None):
    im = new(16, 16)
    ball(im, 8, 10, 6.5, 5, ramp((150, 146, 140)))
    ball(im, 5, 11, 3, 2.5, ramp((130, 126, 122)), spot=False)
    if ore:
        for x, y in ((7, 8), (10, 10), (6, 12), (11, 7), (9, 13)):
            px(im, x, y, ore); px(im, x + 1, y, mix(ore, (255, 255, 255), .5))
    return outline(im)


def stump():
    im = new(16, 16)
    rect(im, 4, 9, 8, 5, (130, 86, 56)); rect(im, 4, 9, 2, 5, (164, 114, 74)); rect(im, 10, 9, 2, 5, (96, 62, 44))
    ball(im, 8, 9, 4, 1.8, [(232, 196, 140)] * 4, spot=False)
    px(im, 8, 9, (190, 140, 90)); px(im, 6, 9, (200, 156, 104)); px(im, 10, 9, (200, 156, 104))
    return outline(im)


def wood_item():
    im = new(16, 16)
    rect(im, 3, 6, 9, 6, (150, 100, 62)); rect(im, 3, 6, 9, 1, (186, 132, 84)); rect(im, 3, 11, 9, 1, (110, 70, 46))
    ball(im, 12, 9, 2.5, 3, [(236, 200, 146), (236, 200, 146), (214, 172, 116), (190, 146, 96)], spot=False)
    px(im, 12, 9, (170, 120, 76))
    return outline(im)


def stone_item():
    im = new(16, 16); ball(im, 8, 9, 5, 4, ramp((156, 152, 146))); return outline(im)


def ore_item():
    im = new(16, 16); ball(im, 8, 9, 4.5, 4, ramp((226, 170, 70)))
    px(im, 6, 8, (255, 246, 200)); px(im, 10, 10, (170, 110, 40))
    return outline(im)


def coin():
    im = new(16, 16); ball(im, 8, 8, 5.5, 5.5, ramp((240, 196, 70)))
    for y in range(5, 12):
        px(im, 8, y, (190, 140, 40))
    px(im, 7, 5, (190, 140, 40)); px(im, 9, 11, (190, 140, 40))
    return outline(im)


def seven():
    im = new(16, 16)
    R = (226, 60, 60)
    rect(im, 3, 2, 10, 3, R); rect(im, 3, 2, 10, 1, (255, 140, 130))
    for i in range(9):
        rect(im, 10 - i // 2, 5 + i, 3, 1, R)
    return outline(im)


def cat():
    im = new(16, 16)
    O = ramp((236, 150, 70))
    ball(im, 8, 11.5, 4.5, 3.5, O)  # body
    ball(im, 8, 6.5, 4, 3.4, O)  # head
    for x, y in ((4, 3), (5, 3), (4, 2), (11, 3), (12, 3), (12, 2)):
        px(im, x, y, O[2])
    px(im, 6, 6, (40, 30, 40)); px(im, 10, 6, (40, 30, 40)); px(im, 8, 7, (230, 120, 130))
    for x in (6, 10):
        px(im, x, 9, O[3])
    rect(im, 12, 11, 2, 1, O[2]); rect(im, 13, 9, 1, 2, O[2])  # tail
    rect(im, 6, 14, 1, 1, (255, 240, 220)); rect(im, 9, 14, 1, 1, (255, 240, 220))
    return outline(im)


def stonepath():
    im = grass(9)
    rnd = random.Random(3)
    for cx, cy in ((4, 4), (11, 4), (7, 9), (13, 11), (3, 12), (9, 14)):
        ball(im, cx, cy, 3, 2.4, [(206, 202, 194), (196, 192, 184), (176, 172, 164), (140, 136, 130)], spot=False)
    return im


def minewall():
    im = Image.new("RGBA", (16, 16), (96, 92, 100, 255))
    for row, y in enumerate(range(0, 16, 4)):
        rect(im, 0, y, 16, 1, (70, 66, 76))
        for x in range((row % 2) * 4, 16, 8):
            rect(im, x, y, 1, 4, (70, 66, 76))
        rect(im, 0, y + 1, 16, 1, (120, 116, 124))
    return im


def goldbar():
    im = new(16, 16)
    G = [(255, 240, 160), (244, 200, 80), (206, 150, 50), (140, 92, 36)]
    for j in range(4):
        rect(im, 3 - j // 2 + 1, 6 + j, 10 + j, 1, G[0] if j == 0 else G[1])
    rect(im, 2, 10, 13, 3, G[2]); rect(im, 2, 12, 13, 1, G[3])
    px(im, 6, 7, (255, 255, 240)); px(im, 7, 7, (255, 255, 240))
    return outline(im)


def suit_acorn():
    im = new(16, 16)
    ball(im, 8, 10, 4, 4.5, ramp((196, 128, 66)))
    rect(im, 3, 5, 10, 3, (120, 82, 50)); rect(im, 4, 4, 8, 1, (150, 104, 64))
    for x in range(4, 12, 2):
        px(im, x, 6, (160, 116, 72))
    rect(im, 8, 2, 1, 2, (100, 70, 44))
    return outline(im)


def suit_leaf():
    im = new(16, 16); G = ramp((100, 176, 84))
    for y in range(2, 14):
        hw = int(4.5 * (1 - abs(y - 8) / 6.5) + .5)
        for x in range(8 - hw, 8 + hw + 1):
            px(im, x, y, G[1] if x < 8 else G[2])
    for y in range(3, 15):
        px(im, 8, y, G[3])
    for i in range(3):
        px(im, 7 - i, 7 + i, G[3]); px(im, 9 + i, 6 + i, G[3])
    return outline(im)


def suit_flower():
    im = new(16, 16); P = ramp((240, 120, 160))
    for cx, cy in ((8, 4.5), (11.5, 7.5), (10, 11.5), (6, 11.5), (4.5, 7.5)):
        ball(im, cx, cy, 2.6, 2.6, P, spot=False)
    ball(im, 8, 8.5, 2, 2, ramp((250, 214, 90)), spot=False)
    return outline(im)


def chicken():
    im = new(16, 16); W = ramp((250, 248, 240))
    ball(im, 8, 10, 5, 4, W); ball(im, 5, 6, 2.6, 2.6, W)
    px(im, 4, 6, (30, 24, 30)); rect(im, 2, 7, 2, 1, (240, 170, 50)); rect(im, 4, 3, 2, 2, (226, 60, 60)); px(im, 5, 9, (226, 60, 60))
    rect(im, 12, 7, 2, 3, W[2]); rect(im, 7, 14, 1, 2, (240, 170, 50)); rect(im, 10, 14, 1, 2, (240, 170, 50))
    return outline(im)


def cow():
    im = new(16, 16); W = ramp((246, 244, 238))
    rect(im, 3, 6, 11, 7, W[1]); rect(im, 3, 12, 11, 1, W[3])
    for x, y, w, h in ((5, 7, 3, 2), (10, 9, 3, 3), (4, 11, 2, 1)):
        rect(im, x, y, w, h, (60, 50, 50))
    rect(im, 0, 5, 5, 5, W[1]); rect(im, 0, 8, 4, 2, (240, 180, 180)); px(im, 1, 6, (30, 24, 30)); px(im, 3, 6, (30, 24, 30))
    px(im, 0, 4, (220, 200, 160)); px(im, 4, 4, (220, 200, 160))
    for x in (4, 6, 10, 12):
        rect(im, x, 13, 1, 3, W[3])
    rect(im, 14, 6, 1, 4, (60, 50, 50))
    return outline(im)


def sheep():
    im = new(16, 16); W = ramp((244, 240, 228))
    for cx, cy in ((6, 8), (10, 8), (8, 6), (8, 10), (12, 10), (4, 10)):
        ball(im, cx, cy, 3, 2.8, W, spot=False)
    rect(im, 1, 6, 4, 4, (70, 60, 60)); px(im, 2, 7, (240, 240, 240)); rect(im, 0, 6, 1, 1, (70, 60, 60))
    for x in (5, 7, 10, 12):
        rect(im, x, 13, 1, 3, (70, 60, 60))
    return outline(im)


def wool():
    im = new(16, 16); W = ramp((246, 242, 232))
    for cx, cy in ((6, 8), (10, 8), (8, 6), (8, 10)):
        ball(im, cx, cy, 3.2, 3, W, spot=False)
    return outline(im)


def dog():
    im = new(16, 16); O = ramp((214, 160, 96))
    ball(im, 8, 11.5, 4.5, 3.5, O); ball(im, 8, 6.5, 4, 3.4, O)
    rect(im, 3, 3, 2, 4, O[3]); rect(im, 11, 3, 2, 4, O[3])
    rect(im, 6, 8, 4, 2, (250, 240, 226)); px(im, 6, 6, (30, 24, 30)); px(im, 10, 6, (30, 24, 30)); rect(im, 7, 8, 2, 1, (40, 30, 30))
    rect(im, 13, 9, 2, 1, O[2]); rect(im, 14, 8, 1, 1, O[2])
    return outline(im)


def bird():
    im = new(16, 16); B = ramp((110, 200, 120))
    ball(im, 8, 10, 4, 4, B); ball(im, 8, 5.5, 3, 3, ramp((250, 220, 90)))
    px(im, 7, 5, (30, 24, 30)); rect(im, 9, 6, 2, 1, (240, 150, 60)); rect(im, 3, 9, 2, 3, B[3]); rect(im, 12, 9, 2, 3, B[3])
    rect(im, 7, 14, 1, 2, (200, 140, 60)); rect(im, 9, 14, 1, 2, (200, 140, 60))
    return outline(im)


def slime(col=(110, 190, 120)):
    im = new(16, 16); G = ramp(col)
    for y in range(5, 15):
        hw = int(6 * (1 - ((14 - y) / 10) ** 2) ** .5) if y < 14 else 6
        for x in range(8 - hw, 8 + hw):
            px(im, x, y, G[1] if y < 8 else G[2] if x < 11 else G[3])
    px(im, 5, 7, (255, 255, 255)); px(im, 6, 7, (255, 255, 255))
    rect(im, 6, 10, 1, 2, (30, 30, 40)); rect(im, 10, 10, 1, 2, (30, 30, 40))
    return outline(im)


def bat():
    im = new(16, 16); P = ramp((110, 80, 140))
    ball(im, 8, 8, 3, 3, P)
    for i in range(5):
        rect(im, 1 + i, 6 + (i % 2), 1, 3 - (i % 2), P[2]); rect(im, 14 - i, 6 + (i % 2), 1, 3 - (i % 2), P[2])
    px(im, 7, 7, (255, 80, 80)); px(im, 9, 7, (255, 80, 80)); px(im, 7, 10, (255, 255, 255)); px(im, 9, 10, (255, 255, 255))
    rect(im, 6, 4, 1, 2, P[2]); rect(im, 10, 4, 1, 2, P[2])
    return outline(im)


def golem():
    im = new(32, 32); S = [(170, 160, 150), (140, 132, 124), (110, 104, 98), (74, 70, 68)]
    rect(im, 8, 4, 16, 12, S[1]); rect(im, 8, 4, 16, 2, S[0])
    rect(im, 4, 14, 24, 12, S[1]); rect(im, 4, 14, 24, 2, S[0]); rect(im, 22, 16, 6, 10, S[2])
    rect(im, 1, 15, 4, 12, S[2]); rect(im, 27, 15, 4, 12, S[2]); rect(im, 8, 26, 6, 5, S[2]); rect(im, 18, 26, 6, 5, S[2])
    rect(im, 11, 8, 3, 3, (255, 120, 60)); rect(im, 18, 8, 3, 3, (255, 120, 60)); px(im, 12, 9, (255, 230, 150)); px(im, 19, 9, (255, 230, 150))
    for x, y in ((10, 18), (20, 20), (14, 23), (6, 20)):
        rect(im, x, y, 2, 1, S[3])
    ball(im, 16, 20, 2.5, 2.5, ramp((120, 200, 255)))
    for x, y in ((9, 6), (22, 12), (5, 24)):
        px(im, x, y, (110, 160, 90))
    return outline(im)


def sword(col):
    im = new(16, 16); M = ramp(col)
    for i in range(9):
        px(im, 5 + i, 10 - i, M[1]); px(im, 6 + i, 10 - i, M[2])
    px(im, 14, 1, M[0])
    for i in range(4):
        px(im, 2 + i, 8 + i, (200, 160, 60))
    rect(im, 2, 12, 2, 2, WOOD[3]); px(im, 1, 14, WOOD[2])
    return outline(im)


def armor():
    im = new(16, 16); M = ramp((170, 176, 192))
    rect(im, 3, 3, 10, 10, M[1]); rect(im, 3, 3, 10, 1, M[0]); rect(im, 1, 3, 3, 5, M[2]); rect(im, 12, 3, 3, 5, M[2])
    rect(im, 6, 2, 4, 2, M[3]); rect(im, 7, 5, 2, 7, M[2]); rect(im, 3, 12, 10, 2, M[3])
    return outline(im)


def gem():
    im = new(16, 16); P = ramp((170, 110, 230))
    for y in range(4, 13):
        hw = 5 - abs(y - 7) if y < 8 else 5 - (y - 7)
        for x in range(8 - hw, 8 + hw + 1):
            px(im, x, y, P[0] if x < 7 and y < 7 else P[1] if x < 8 else P[2])
    return outline(im)


def jelly():
    im = new(16, 16); ball(im, 8, 10, 5, 3.5, ramp((120, 210, 140))); return outline(im)


def wing():
    im = new(16, 16); P = ramp((120, 90, 150))
    for i in range(8):
        rect(im, 3 + i, 4 + i // 2, 1, 8 - i // 2, P[1 + i % 2])
    return outline(im)


def core():
    im = new(16, 16); ball(im, 8, 8, 5, 5, ramp((90, 180, 255)))
    px(im, 6, 5, (255, 255, 255)); return outline(im)


def ladder(down=True):
    im = new(16, 16)
    if down:
        rect(im, 2, 2, 12, 12, (24, 18, 22)); rect(im, 3, 3, 10, 10, (12, 8, 10))
    for x in (4, 11):
        rect(im, x, 1, 1, 14, WOOD[2])
    for y in range(3, 15, 3):
        rect(im, 4, y, 8, 1, WOOD[1])
    return outline(im)


def cave_mouth():
    im = minewall()
    ball(im, 8, 12, 6.5, 9, [(20, 14, 20)] * 4, spot=False)
    return im


def feed():
    im = new(16, 16)
    rect(im, 3, 4, 10, 10, (214, 190, 130)); rect(im, 3, 4, 10, 2, (190, 160, 100)); rect(im, 5, 2, 6, 2, (180, 150, 90))
    for x, y in ((6, 8), (9, 9), (7, 11), (10, 7)):
        px(im, x, y, (150, 190, 80))
    return outline(im)


def stall():
    im = new(32, 32)
    for x in range(1, 31):
        col = (226, 80, 80) if (x // 5) % 2 == 0 else (250, 244, 230)
        rect(im, x, 2, 1, 8, col)
        if x % 5 in (1, 2, 3): px(im, x, 10, col)
    rect(im, 2, 10, 2, 16, WOOD[3]); rect(im, 28, 10, 2, 16, WOOD[3])
    rect(im, 1, 18, 30, 4, WOOD[1]); rect(im, 1, 18, 30, 1, WOOD[0]); rect(im, 1, 22, 30, 7, WOOD[2])
    return outline(im)


def board():
    im = new(16, 24)
    rect(im, 2, 13, 2, 10, WOOD[3]); rect(im, 12, 13, 2, 10, WOOD[3])
    rect(im, 1, 2, 14, 13, WOOD[2]); rect(im, 2, 3, 12, 11, (226, 200, 150))
    for x, y, c in ((3, 4, (255, 255, 255)), (8, 5, (255, 240, 200)), (4, 9, (240, 250, 255))):
        rect(im, x, y, 4, 4, c); px(im, x + 1, y, (220, 60, 60))
    return outline(im)


def fountain():
    im = new(32, 32); S = [(214, 210, 202), (186, 182, 174), (150, 146, 140), (110, 106, 100)]
    ball(im, 16, 22, 14, 7, S, spot=False); ball(im, 16, 21, 11.5, 5, ramp((100, 170, 230)), spot=False)
    rect(im, 14, 8, 4, 13, S[1]); rect(im, 14, 8, 1, 13, S[0]); ball(im, 16, 9, 5, 2, S, spot=False)
    return outline(im)


def streetlamp():
    im = new(16, 32)
    rect(im, 7, 8, 2, 22, (60, 60, 70)); rect(im, 5, 29, 6, 2, (60, 60, 70))
    rect(im, 4, 2, 8, 7, (60, 60, 70)); rect(im, 5, 3, 6, 5, (255, 230, 150))
    return outline(im)


def flowerbed():
    im = new(16, 16)
    rect(im, 1, 8, 14, 7, WOOD[2]); rect(im, 2, 9, 12, 5, (110, 76, 50))
    for x, c in ((3, (240, 120, 160)), (6, (250, 220, 90)), (9, (255, 255, 255)), (12, (180, 120, 230))):
        rect(im, x, 6, 2, 2, c); px(im, x, 8, (90, 160, 80)); px(im, x + 1, 9, (90, 160, 80))
    return outline(im)


def busstop():
    im = new(16, 24)
    rect(im, 7, 8, 2, 15, (90, 90, 100)); ball(im, 8, 6, 5.5, 5.5, ramp((70, 130, 210)), spot=False)
    rect(im, 5, 5, 6, 2, (255, 255, 255)); rect(im, 4, 21, 8, 2, (90, 90, 100))
    return outline(im)


def aquarium():
    im = new(32, 24)
    rect(im, 1, 2, 30, 16, (60, 70, 90)); rect(im, 2, 3, 28, 14, (120, 196, 236)); rect(im, 2, 14, 28, 3, (220, 200, 150))
    for x in (6, 22):
        rect(im, x, 8, 1, 6, (80, 170, 90)); rect(im, x + 1, 10, 1, 4, (80, 170, 90))
    px(im, 4, 5, (230, 248, 255)); px(im, 5, 4, (230, 248, 255))
    rect(im, 2, 18, 28, 5, WOOD[2]); rect(im, 2, 18, 28, 1, WOOD[1])
    return outline(im)


def palm():
    im = new(16, 32)
    for y in range(12, 31):
        rect(im, 7 + (1 if y > 22 else 0), y, 3, 1, (170, 120, 70) if y % 3 else (140, 96, 56))
    G = ramp((80, 170, 90))
    for dx, dy in ((-6, 2), (6, 2), (-5, -2), (5, -2), (0, -4)):
        for i in range(7):
            px(im, 8 + dx * i // 6, 12 + dy * i // 6, G[1]); px(im, 8 + dx * i // 6, 13 + dy * i // 6, G[2])
    ball(im, 8, 12, 3, 2.5, G)
    return outline(im)


def sand(seed=1):
    return tile((236, 216, 166), [(220, 198, 146), (246, 232, 190)], seed, 16)


def sea(frame=0):
    im = Image.new("RGBA", (16, 16), (44, 104, 176, 255))
    rnd = random.Random(7 + frame)
    for _ in range(8):
        px(im, rnd.randrange(16), rnd.randrange(16), (36, 88, 156))
    for x in range(16):
        if (x + frame * 3) % 8 < 3:
            px(im, x, 5, (130, 190, 236)); px(im, (x + 5) % 16, 12, (110, 170, 226))
    return im


def pier():
    im = new(16, 16)
    for y in range(0, 16, 4):
        rect(im, 0, y, 16, 3, WOOD[1]); rect(im, 0, y + 3, 16, 1, WOOD[3])
    return im


def snowman():
    im = new(16, 16); W = ramp((250, 252, 255))
    ball(im, 8, 11, 4.5, 4, W); ball(im, 8, 5.5, 3.2, 3, W)
    px(im, 7, 5, (30, 30, 40)); px(im, 9, 5, (30, 30, 40)); rect(im, 8, 6, 2, 1, (240, 140, 50)); rect(im, 5, 8, 6, 1, (220, 60, 60))
    return outline(im)


def wither():
    im = new(16, 16)
    for p in ((8, 12), (8, 11), (7, 10), (9, 10), (6, 9), (10, 9), (8, 13)):
        px(im, *p, (150, 120, 80))
    return outline(im)


def piano():
    im = new(32, 24)
    rect(im, 2, 2, 28, 12, (40, 32, 40)); rect(im, 2, 2, 28, 2, (80, 70, 84))
    rect(im, 2, 12, 28, 4, (250, 250, 246))
    for x in range(3, 30, 2):
        rect(im, x, 12, 1, 2, (30, 26, 32))
    rect(im, 2, 16, 28, 2, (40, 32, 40)); rect(im, 4, 18, 2, 5, (40, 32, 40)); rect(im, 26, 18, 2, 5, (40, 32, 40))
    rect(im, 12, 4, 8, 3, (220, 190, 120))
    return outline(im)


def chandelier():
    im = new(16, 16); G = [(255, 236, 150), (232, 184, 72), (170, 120, 40)]
    rect(im, 7, 0, 2, 4, G[2]); rect(im, 3, 6, 10, 2, G[1]); rect(im, 3, 6, 10, 1, G[0])
    for x in (3, 7, 12):
        rect(im, x, 3, 1, 3, G[2]); px(im, x, 2, (255, 250, 210)); px(im, x, 1, (255, 200, 120))
    for x in (4, 8, 11):
        px(im, x, 9, (200, 230, 255)); px(im, x, 10, (160, 210, 250))
    return outline(im)


def goldstatue():
    im = new(16, 32); G = ramp((236, 190, 70))
    rect(im, 3, 26, 10, 5, (120, 110, 104)); rect(im, 3, 26, 10, 1, (160, 152, 146))
    ball(im, 8, 7, 3.5, 3.5, G); rect(im, 5, 11, 6, 9, G[1]); rect(im, 9, 11, 2, 9, G[3])
    rect(im, 2, 12, 3, 2, G[2]); rect(im, 11, 8, 2, 6, G[2]); px(im, 12, 6, G[0])
    rect(im, 5, 20, 2, 6, G[2]); rect(im, 9, 20, 2, 6, G[3])
    return outline(im)


def trophy():
    im = new(16, 16); G = ramp((236, 190, 70))
    rect(im, 4, 2, 8, 6, G[1]); rect(im, 4, 2, 2, 6, G[0]); rect(im, 10, 2, 2, 6, G[3])
    rect(im, 2, 3, 2, 3, G[2]); rect(im, 12, 3, 2, 3, G[2]); rect(im, 7, 8, 2, 3, G[2])
    rect(im, 5, 11, 6, 2, G[2]); rect(im, 4, 13, 8, 2, (120, 80, 50))
    return outline(im)


def shovel():
    im = new(16, 16)
    for i in range(9):
        px(im, 3 + i, 12 - i, WOOD[2]); px(im, 4 + i, 12 - i, WOOD[1])
    rect(im, 2, 11, 3, 1, WOOD[3])
    for y in range(2, 7):
        rect(im, 10 + (y - 2) // 2, y - 1, 4, 1, METAL[1])
    rect(im, 11, 1, 3, 1, METAL[0]); rect(im, 13, 3, 2, 4, METAL[2])
    return outline(im)


def bridge_item():
    im = new(16, 16)
    for y in range(3, 14, 3):
        rect(im, 2, y, 12, 2, WOOD[1]); rect(im, 2, y + 2, 12, 1, WOOD[3])
    rect(im, 1, 2, 1, 12, WOOD[3]); rect(im, 14, 2, 1, 12, WOOD[3])
    return outline(im)


def bridge_tile():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for x in range(0, 16, 4):
        rect(im, x, 0, 3, 16, WOOD[1]); rect(im, x + 3, 0, 1, 16, WOOD[3]); px(im, x + 1, 5, WOOD[0])
    rect(im, 0, 0, 16, 1, WOOD[3]); rect(im, 0, 15, 16, 1, WOOD[3])
    return im


def sapling():
    im = new(16, 16)
    rect(im, 7, 7, 2, 8, (140, 96, 60)); G = ramp((96, 170, 80))
    ball(im, 8, 6, 4, 3.5, G); ball(im, 5, 8, 2, 2, G, spot=False); ball(im, 11, 8, 2, 2, G, spot=False)
    return outline(im)


def seed_flower():
    return seed_bag((240, 130, 170))


def clocktower():
    im = new(32, 56); S = [(226, 218, 204), (200, 190, 176), (160, 150, 138), (110, 100, 92)]
    R = ramp((180, 80, 70))
    for y in range(2, 14):
        hw = int((y - 2) / 12 * 13)
        rect(im, 16 - hw, y, hw * 2, 1, R[1] if y < 6 else R[2])
    rect(im, 4, 14, 24, 40, S[1]); rect(im, 4, 14, 3, 40, S[0]); rect(im, 25, 14, 3, 40, S[2])
    for y in range(18, 54, 6):
        rect(im, 4, y, 24, 1, S[2])
    ball(im, 16, 23, 7, 7, [(250, 248, 236)] * 4, spot=False)
    px(im, 16, 17, (120, 110, 100)); px(im, 16, 29, (120, 110, 100)); px(im, 10, 23, (120, 110, 100)); px(im, 22, 23, (120, 110, 100))
    rect(im, 12, 40, 8, 14, WOOD[2]); rect(im, 12, 40, 8, 1, WOOD[1]); rect(im, 16, 40, 1, 14, WOOD[3])
    return outline(im)


def prize_counter():
    im = new(32, 22)
    rect(im, 0, 2, 32, 6, (232, 184, 72)); rect(im, 0, 2, 32, 1, (255, 236, 150)); rect(im, 0, 8, 32, 13, (120, 36, 60)); rect(im, 0, 20, 32, 2, (80, 20, 40))
    for x in (4, 14, 24):
        rect(im, x, 11, 5, 6, (200, 60, 90)); rect(im, x + 2, 11, 1, 6, (255, 236, 150))
    rect(im, 3, 0, 4, 3, (250, 250, 240)); rect(im, 24, 0, 5, 3, (170, 110, 230))
    return outline(im)


items = {
    "アジ": fish((150, 176, 206), 5, 2.6), "サケ": fish((226, 128, 116), 6, 3),
    "タイ": fish((240, 116, 120), 5, 4, "spot"), "マグロ": fish((76, 96, 146), 6.5, 3.4),
    "ナマズ": fish((140, 116, 86), 6, 2.8, "cat"), "ネッタイギョ": fish((252, 210, 80), 4.5, 4, "stripe"),
    "ヌシ": fish((244, 188, 60), 12, 5.5, "boss", 32, 16),
}
A = {
    "grass0": grass(1), "grass1": grass(2), "grass2": grass(3), "grass3": grass(4),
    "flowerP": flower_tile(5, (240, 140, 160)), "flowerW": flower_tile(6, (250, 250, 250)),
    "flowerY": flower_tile(7, (250, 210, 90)),
    "path0": path_tile(11), "path1": path_tile(12), "tilled": tilled(),
    "water0": water_tile(0), "water1": water_tile(1), "fence": fence(),
    "floor": floor_tile(), "wall": wall_tile(), "doormat": doormat(),
    "tree": tree(), "sprout": sprout(),
    "house_small": house(4, 4, (200, 84, 76)),
    "house_big": house(6, 5, (80, 120, 184), (236, 230, 214), chimney=True),
    "bed": bed(), "table": table(), "chair": chair(), "bookshelf": bookshelf(), "chest": chest(),
    "stove": stove(), "counter": counter(), "plant": plant(), "lamp": lamp(), "rug": rug(),
    "window": window(), "picture": picture(),
    "tomato": tomato(), "cabbage": cabbage(), "carrot": carrot(),
    "hoe": hoe(), "can": can(), "rod": rod(),
    "seed_tomato": seed_bag((226, 72, 64)), "seed_carrot": seed_bag((240, 140, 50)), "seed_cabbage": seed_bag((120, 190, 90)),
    "young_tomato": young("tomato"), "young_carrot": young("carrot"), "young_cabbage": young("cabbage"),
    "tilled_wet": tilled_wet(),
    "axe": axe(), "pick": pick(), "rock": rock(), "ore_rock": rock((240, 180, 70)), "stump": stump(),
    "wood": wood_item(), "stone": stone_item(), "ore": ore_item(), "coin": coin(), "seven": seven(), "cat": cat(),
    "stonepath": stonepath(), "minewall": minewall(),
    "grilled_fish": grilled_fish(), "salad": salad(), "stew": stew(), "curry": curry(),
    "chicken": chicken(), "cow": cow(), "sheep": sheep(), "wool": wool(), "dog": dog(), "bird": bird(),
    "slime": slime(), "slime2": slime((120, 150, 230)), "bat": bat(), "golem": golem(),
    "sword_wood": sword((200, 150, 90)), "sword_iron": sword((190, 196, 210)), "armor": armor(), "gem": gem(), "jelly": jelly(), "wing": wing(), "core": core(),
    "ladder_down": ladder(True), "ladder_up": ladder(False), "cave_mouth": cave_mouth(), "feed": feed(), "stall": stall(), "board": board(),
    "fountain": fountain(), "streetlamp": streetlamp(), "flowerbed": flowerbed(), "busstop": busstop(), "aquarium": aquarium(), "palm": palm(),
    "sand0": sand(1), "sand1": sand(2), "sea0": sea(0), "sea1": sea(1), "pier": pier(), "snowman": snowman(), "wither": wither(),
    "egg": egg(), "milk": milk(), "cake": cake(),
    "fish_フナ": fish((150, 140, 100), 5, 3), "fish_コイ": fish((230, 120, 60), 6, 3.4, "spot"), "fish_ウナギ": fish((96, 96, 72), 6.5, 1.6),
    "fish_イワナ": fish((120, 134, 112), 5.5, 2.4, "spot"), "fish_ブラックバス": fish((96, 128, 74), 5.5, 3.2), "fish_ワカサギ": fish((200, 212, 224), 4, 1.8),
    "fish_カツオ": fish((70, 92, 144), 6, 3, "stripe"), "fish_ブリ": fish((120, 150, 192), 6.5, 3),
    "piano": piano(), "chandelier": chandelier(), "goldstatue": goldstatue(), "trophy": trophy(), "shovel": shovel(),
    "bridge_item": bridge_item(), "bridge": bridge_tile(), "sapling": sapling(), "seed_flower": seed_flower(), "clocktower": clocktower(), "prizecounter": prize_counter(),
    "goldbar": goldbar(), "suit_acorn": suit_acorn(), "suit_leaf": suit_leaf(), "suit_flower": suit_flower(), "suit_mush": mushroom(),
}
for k, v in items.items():
    A["fish_" + k] = v

CHAR = dict(BODY=BODY, HAIR=HAIR, TOPS={k: {"rows": v[0], "covers": v[1]} for k, v in TOPS.items()},
            BOTTOMS=BOTTOMS, SHOES=SHOES, SKIN=SKIN, HAIRC=HAIRC, CLOTH=CLOTH, WHITE=WHITE)

with open(f"{SP}/data.js", "w") as f:
    f.write("const ASSETS=" + json.dumps({k: b64(v) for k, v in A.items()}) + ";\n")
    f.write("const CHAR=" + json.dumps(CHAR, ensure_ascii=False) + ";\n")
print("ok", len(A))
