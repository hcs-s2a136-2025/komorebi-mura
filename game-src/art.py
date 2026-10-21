import sys, random
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
CLEAR = (0, 0, 0, 0)
FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Medium.ttc"
BG = (236, 228, 208, 255)
INK = (70, 52, 46)


def font(size):
    return ImageFont.truetype(FONT, size, index=0)


# ---------------- primitives ----------------
def new(w, h):
    return Image.new("RGBA", (w, h), CLEAR)


def px(im, x, y, c):
    x, y = int(x), int(y)
    if 0 <= x < im.width and 0 <= y < im.height:
        im.putpixel((x, y), tuple(c[:3]) + ((c[3],) if len(c) > 3 else (255,)))


def rect(im, x, y, w, h, c):
    for j in range(y, y + h):
        for i in range(x, x + w):
            px(im, i, j, c)


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def ramp(base):
    """highlight, light, mid, shadow (shadow shifts toward purple)"""
    return [mix(base, (255, 252, 236), 0.55), mix(base, (255, 252, 236), 0.22),
            tuple(base), mix(base, (46, 26, 70), 0.38)]


def outline(im, tint=(40, 22, 48), k=0.62):
    """selective outline: each edge pixel takes a darkened version of its neighbour"""
    src = im.copy()
    W, H = im.size
    for y in range(H):
        for x in range(W):
            if src.getpixel((x, y))[3] == 0:
                for dx, dy in ((0, 1), (1, 0), (-1, 0), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < W and 0 <= ny < H and src.getpixel((nx, ny))[3] > 0:
                        im.putpixel((x, y), mix(src.getpixel((nx, ny)), tint, k) + (255,))
                        break
    return im


def ball(im, cx, cy, rx, ry, r4, spot=True):
    for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
        for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
            dx = (x + 0.5 - cx) / rx
            dy = (y + 0.5 - cy) / ry
            if dx * dx + dy * dy <= 1.0:
                l = -(dx * 0.6 + dy * 0.8)
                i = 1 if l > 0.4 else 2 if l > -0.45 else 3
                if spot and (dx + 0.38) ** 2 + (dy + 0.45) ** 2 < 0.06:
                    i = 0
                px(im, x, y, r4[i])


def up(im, k):
    return im.resize((im.width * k, im.height * k), Image.NEAREST)


def shadow(scene, x, y, w=10):
    sh = new(scene.width, scene.height)
    for i in range(w):
        px(sh, x + i, y, (30, 40, 30, 70))
    for i in range(1, w - 1):
        px(sh, x + i, y + 1, (30, 40, 30, 50))
    scene.alpha_composite(sh)


# ================= 1. CHARACTER (layered, recolourable) =================
BODY = [
    "................",
    "................",
    "................",
    "....oooooooo....",
    "...o11111111o...",
    "...o11111111o...",
    "...o1e1111e1o...",
    "...o1e1111e1o...",
    "...o2r1111r2o...",
    "....oo2222oo....",
    ".....o1111o.....",
    "....o111111o....",
    "...o11111111o...",
    "...o1o1111o1o...",
    ".....o1oo1o.....",
    ".....oo..oo.....",
]
_TOP = [".....AAAAAA.....", "....AabbaaaA....", "...AabbaaaaaA...", "...AaaaaaaacA..."]
HAIR = {
    "ショート": ["................"] + _TOP + [
        "...Aac....caA...", "...A........A...", "...A........A..."],
    "ロング": ["................"] + _TOP + [
        "..AAac....caAA..", "..Aa........aA..", "..Aa........aA..", "..Aa........aA..",
        "..Aac......caA..", "..Aac......caA..", "...AA......AA..."],
    "ボブ": ["................", ".....AAAAAA.....", "....AabbaaaA....", "...AabbaaaaaA...",
            "..AaaaaaaaaacA..", "..AaaccaaccaaA..", "..Aa........aA..", "..Aa........aA..",
            "..Aac......caA..", "...AA......AA..."],
    "おだんご": ["................", ".....AAAAAA.....", ".AA.AabbaaaA.AA.", "AbaAabbaaaaaAabA",
              "AacAaaaaaaacAcaA", ".AAAac....caAAA.", "...A........A...", "...A........A..."],
}
TOPS = {  # rows 10..14 ; covers_bottom flag
    "Tシャツ": ([".....TuuuvT.....", "....TuttttvT....", "...TuttttttvT..."], False),
    "ジャケット": ([".....TWWWWT.....", "....TutWWtvT....", "...TuttWXttvT..."], False),
    "ワンピース": ([".....TuuuvT.....", "....TuttttvT....", "...TuttttttvT...",
                ".....TtttvT.....", "....TuttttvT...."], True),
    "オーバーオール": ([".....TWWWWT.....", "....TWtWWtWT....", "...TWWttttWWT...",
                   ".....TttttT.....", ".....TtTTtT....."], True),
}
BOTTOMS = {
    "パンツ": [".....PpppqP.....", ".....PpPPqP....."],
    "スカート": ["....PpppppqP....", "................"],
    "ショートパンツ": [".....PpppqP.....", "................"],
}
SHOES = ".....ZZ..ZZ....."

SKIN = {
    "色白": [(255, 226, 198), (238, 188, 156), (166, 98, 82)],
    "やや明るめ": [(242, 196, 154), (216, 156, 118), (134, 78, 60)],
    "小麦色": [(206, 148, 106), (172, 114, 82), (102, 60, 46)],
    "褐色": [(150, 100, 72), (120, 76, 54), (68, 42, 34)],
}
HAIRC = {  # light, mid, dark, outline
    "ブラウン": [(204, 132, 78), (156, 92, 52), (116, 64, 42), (70, 38, 32)],
    "黒": [(96, 94, 124), (58, 56, 80), (40, 38, 58), (22, 20, 34)],
    "金": [(255, 236, 150), (238, 198, 92), (202, 152, 62), (132, 90, 42)],
    "ピンク": [(255, 196, 214), (238, 142, 172), (202, 102, 142), (122, 56, 88)],
    "銀": [(246, 246, 252), (204, 208, 224), (164, 168, 194), (98, 100, 126)],
    "ミント": [(150, 228, 206), (80, 176, 164), (50, 128, 128), (30, 72, 78)],
    "赤": [(244, 128, 96), (204, 76, 58), (152, 48, 48), (88, 28, 36)],
}
CLOTH = {  # light, mid, dark, outline
    "青": [(134, 190, 242), (74, 143, 214), (48, 104, 166), (34, 58, 98)],
    "赤": [(252, 146, 134), (222, 86, 86), (170, 54, 64), (96, 30, 46)],
    "緑": [(164, 216, 124), (104, 170, 84), (70, 124, 66), (40, 72, 46)],
    "黄": [(255, 232, 136), (240, 196, 80), (200, 146, 56), (120, 80, 40)],
    "紫": [(204, 164, 232), (150, 110, 200), (108, 76, 156), (60, 42, 92)],
    "生成り": [(255, 250, 238), (234, 224, 202), (194, 178, 154), (118, 102, 88)],
    "茶": [(204, 154, 104), (160, 110, 70), (120, 80, 52), (70, 46, 34)],
}
WHITE = {"W": (246, 244, 236), "X": (206, 200, 192)}


def draw_grid(im, rows, y0, cmap):
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch in cmap:
                px(im, i, y0 + j, cmap[ch])


def character(skin="やや明るめ", hair="ショート", hairc="ブラウン", top="Tシャツ",
              topc="青", bottom="パンツ", bottomc="茶", shoes=(96, 64, 48)):
    im = new(16, 16)
    s = SKIN[skin]
    draw_grid(im, BODY, 0, {"1": s[0], "2": s[1], "o": s[2], "e": (44, 32, 48), "r": (246, 150, 140)})
    rows, covers = TOPS[top]
    if not covers:
        c = CLOTH[bottomc]
        draw_grid(im, BOTTOMS[bottom], 13, {"p": c[1], "q": c[2], "P": c[3]})
    draw_grid(im, [SHOES], 15, {"Z": shoes})
    c = CLOTH[topc]
    draw_grid(im, rows, 10, {"u": c[0], "t": c[1], "v": c[2], "T": c[3], **WHITE})
    h = HAIRC[hairc]
    draw_grid(im, HAIR[hair], 0, {"b": h[0], "a": h[1], "c": h[2], "A": h[3]})
    return im


PLAYERS = [
    dict(),
    dict(skin="色白", hair="ロング", hairc="ピンク", top="ワンピース", topc="生成り"),
    dict(skin="小麦色", hair="ショート", hairc="黒", top="オーバーオール", topc="青"),
    dict(skin="色白", hair="おだんご", hairc="金", top="Tシャツ", topc="赤", bottom="スカート", bottomc="紫"),
    dict(skin="褐色", hair="ボブ", hairc="銀", top="ジャケット", topc="緑", bottomc="茶"),
    dict(skin="やや明るめ", hair="ロング", hairc="ミント", top="Tシャツ", topc="黄", bottom="ショートパンツ", bottomc="青"),
    dict(skin="小麦色", hair="おだんご", hairc="赤", top="オーバーオール", topc="茶"),
    dict(skin="色白", hair="ボブ", hairc="黒", top="ワンピース", topc="紫"),
]


def dressup_sheet():
    K = 5
    cell = 16 * K + 64
    rows = [
        ("髪型", [(n, character(hair=n)) for n in HAIR]),
        ("髪の色", [(n, character(hairc=n)) for n in HAIRC]),
        ("服", [(n, character(top=n)) for n in TOPS]),
        ("ボトムス", [(n, character(top="Tシャツ", bottom=n, topc="生成り", bottomc="青")) for n in BOTTOMS]),
        ("服の色", [(n, character(topc=n)) for n in CLOTH]),
        ("肌の色", [(n, character(skin=n)) for n in SKIN]),
        ("組み合わせ", [(f"P{i+1}", character(**p)) for i, p in enumerate(PLAYERS)]),
    ]
    W = 200 + cell * 8
    H = 40 + len(rows) * (cell + 40)
    sheet = Image.new("RGBA", (W, H), BG)
    d = ImageDraw.Draw(sheet)
    f1, f2 = font(24), font(17)
    y = 30
    for title, items in rows:
        d.text((24, y + cell // 2 - 30), title, font=f1, fill=INK)
        for i, (name, im) in enumerate(items):
            x = 200 + i * cell
            d.rounded_rectangle((x, y, x + cell - 12, y + cell - 12), 10, fill=(250, 245, 232))
            sheet.alpha_composite(up(im, K), (x + (cell - 12 - 16 * K) // 2, y + 2))
            tw = d.textlength(name, font=f2)
            d.text((x + (cell - 12 - tw) / 2, y + cell - 8), name, font=f2, fill=INK)
        y += cell + 40
    sheet.save(f"{OUT}/1_dressup.png")


# ================= 2. HOUSE & FURNITURE =================
WOOD = [(238, 194, 134), (208, 152, 94), (166, 110, 66), (112, 70, 46)]


def floor_tile():
    im = new(16, 16)
    for row in range(4):
        y = row * 4
        rect(im, 0, y, 16, 4, WOOD[1])
        rect(im, 0, y, 16, 1, WOOD[0])
        rect(im, 0, y + 3, 16, 1, WOOD[2])
        seam = (row * 7 + 3) % 16
        rect(im, seam, y, 1, 3, WOOD[2])
    return im


def wall_tile():
    im = new(16, 16)
    rect(im, 0, 0, 16, 16, (238, 222, 196))
    for x in range(0, 16, 4):
        rect(im, x, 0, 1, 13, (226, 206, 178))
    for x in range(2, 16, 4):
        px(im, x, 5, (214, 160, 150)); px(im, x, 9, (150, 186, 150))
    rect(im, 0, 13, 16, 1, WOOD[1]); rect(im, 0, 14, 16, 2, WOOD[2]); rect(im, 0, 15, 16, 1, WOOD[3])
    return im


def bed(col=(222, 120, 120)):
    im = new(16, 32); C = ramp(col)
    rect(im, 1, 1, 14, 6, WOOD[2]); rect(im, 1, 1, 14, 1, WOOD[0]); rect(im, 2, 2, 12, 1, WOOD[1])
    rect(im, 1, 6, 14, 1, WOOD[3])
    rect(im, 1, 7, 14, 21, (242, 238, 228))
    rect(im, 3, 8, 10, 4, (255, 255, 250)); rect(im, 3, 11, 10, 1, (214, 208, 200))
    rect(im, 1, 13, 14, 15, C[2]); rect(im, 1, 13, 14, 2, C[1]); rect(im, 2, 13, 12, 1, C[0])
    for y in range(17, 27, 4):
        for x in range(3, 14, 4):
            px(im, x, y, C[1]); px(im, x + 1, y + 2, C[3])
    rect(im, 1, 15, 1, 13, C[3]); rect(im, 14, 15, 1, 13, C[3])
    rect(im, 1, 27, 14, 3, WOOD[2]); rect(im, 1, 27, 14, 1, WOOD[1])
    rect(im, 1, 30, 2, 1, WOOD[3]); rect(im, 13, 30, 2, 1, WOOD[3])
    return outline(im)


def table():
    im = new(32, 16)
    rect(im, 1, 2, 30, 8, WOOD[1]); rect(im, 1, 2, 30, 1, WOOD[0])
    for x in (8, 16, 24):
        rect(im, x, 3, 1, 7, (196, 140, 86))
    rect(im, 1, 10, 30, 2, WOOD[2]); rect(im, 1, 11, 30, 1, WOOD[3])
    rect(im, 2, 12, 2, 3, WOOD[3]); rect(im, 28, 12, 2, 3, WOOD[3])
    # small vase with a flower
    rect(im, 14, 5, 3, 3, (120, 170, 210)); px(im, 14, 5, (180, 216, 240))
    px(im, 15, 4, (90, 150, 80)); px(im, 15, 3, (240, 140, 160)); px(im, 14, 3, (250, 190, 200))
    return outline(im)


def chair():
    im = new(16, 16)
    rect(im, 4, 1, 8, 6, WOOD[2]); rect(im, 4, 1, 8, 1, WOOD[0])
    for x in (6, 9):
        rect(im, x, 2, 1, 5, WOOD[3])
    rect(im, 3, 7, 10, 4, WOOD[1]); rect(im, 3, 7, 10, 1, WOOD[0])
    rect(im, 3, 11, 10, 1, WOOD[2])
    rect(im, 4, 12, 1, 3, WOOD[3]); rect(im, 11, 12, 1, 3, WOOD[3])
    return outline(im)


def bookshelf():
    im = new(16, 32); rnd = random.Random(4)
    rect(im, 1, 1, 14, 30, WOOD[2]); rect(im, 1, 1, 14, 2, WOOD[1]); rect(im, 1, 1, 14, 1, WOOD[0])
    books = [(200, 80, 80), (80, 130, 200), (230, 190, 90), (110, 170, 110), (170, 110, 190), (240, 236, 220)]
    for top in (3, 12, 21):
        rect(im, 2, top, 12, 8, WOOD[3])
        x = 2
        while x < 14:
            w = rnd.choice([1, 2, 2])
            h = rnd.choice([5, 6, 7, 8])
            c = rnd.choice(books)
            if x + w > 14:
                break
            rect(im, x, top + 8 - h, w, h, c)
            px(im, x, top + 8 - h, mix(c, (255, 255, 255), 0.4))
            x += w
        rect(im, 1, top + 8, 14, 1, WOOD[1])
    return outline(im)


def chest():
    im = new(16, 16)
    rect(im, 1, 3, 14, 4, WOOD[1]); rect(im, 1, 3, 14, 1, WOOD[0])
    rect(im, 1, 7, 14, 1, WOOD[3])
    rect(im, 1, 8, 14, 6, WOOD[2]); rect(im, 1, 13, 14, 1, WOOD[3])
    gold = [(255, 230, 140), (226, 180, 70), (160, 112, 40)]
    for x in (3, 12):
        rect(im, x, 3, 1, 11, gold[2])
    rect(im, 7, 6, 2, 3, gold[1]); px(im, 7, 6, gold[0])
    return outline(im)


def stove():
    im = new(16, 16)
    G = [(170, 170, 184), (124, 124, 140), (88, 88, 104), (56, 56, 70)]
    rect(im, 1, 2, 14, 5, G[1]); rect(im, 1, 2, 14, 1, G[0])
    for cx in (4, 11):
        rect(im, cx - 2, 3, 4, 3, G[3]); rect(im, cx - 1, 4, 2, 1, (240, 120, 60))
    rect(im, 1, 7, 14, 7, G[2]); rect(im, 1, 7, 14, 1, G[1])
    rect(im, 4, 9, 8, 4, G[3]); rect(im, 5, 10, 6, 2, (250, 150, 70)); px(im, 5, 10, (255, 220, 140))
    rect(im, 2, 14, 2, 1, G[3]); rect(im, 12, 14, 2, 1, G[3])
    # pot on burner
    rect(im, 9, 0, 5, 4, (200, 90, 80)); rect(im, 9, 0, 5, 1, (240, 150, 130))
    return outline(im)


def counter():
    im = new(16, 16)
    rect(im, 1, 2, 14, 5, (232, 226, 214)); rect(im, 1, 2, 14, 1, (250, 248, 240))
    rect(im, 3, 3, 7, 3, WOOD[1]); rect(im, 3, 3, 7, 1, WOOD[0])  # cutting board
    rect(im, 11, 4, 3, 1, (200, 204, 214)); px(im, 10, 4, WOOD[3])  # knife
    rect(im, 1, 7, 14, 7, WOOD[2]); rect(im, 1, 7, 14, 1, WOOD[1])
    rect(im, 1, 10, 14, 1, WOOD[3]); rect(im, 7, 8, 2, 1, (226, 180, 70)); rect(im, 7, 11, 2, 1, (226, 180, 70))
    rect(im, 1, 13, 14, 1, WOOD[3])
    return outline(im)


def plant():
    im = new(16, 16)
    T = [(232, 140, 96), (200, 104, 70), (150, 70, 52)]
    for j, y in enumerate(range(10, 15)):
        rect(im, 4 + j // 2, y, 8 - j, 1, T[1])
    rect(im, 3, 9, 10, 2, T[0]); rect(im, 3, 10, 10, 1, T[2])
    L = ramp((96, 168, 84))
    for cx, cy, r in ((8, 5, 3.4), (5, 7, 2.4), (11, 7, 2.4), (8, 2.5, 2)):
        ball(im, cx, cy, r, r, L)
    return outline(im)


def lamp():
    im = new(16, 16)
    S = [(255, 246, 200), (250, 226, 150), (226, 186, 110)]
    for j in range(5):
        rect(im, 5 - j // 2, 1 + j, 6 + (j // 2) * 2, 1, S[0] if j < 2 else S[1])
    rect(im, 4, 5, 8, 1, S[2])
    rect(im, 7, 6, 2, 6, WOOD[3]); px(im, 7, 6, WOOD[1])
    rect(im, 5, 12, 6, 2, WOOD[2]); rect(im, 5, 12, 6, 1, WOOD[1])
    return outline(im)


def rug():
    im = new(32, 32)
    cols = [(196, 92, 84), (240, 214, 160), (110, 150, 190), (240, 214, 160), (196, 92, 84)]
    for k, c in enumerate(cols):
        rx, ry = 15 - k * 3, 11 - k * 2.2
        for y in range(32):
            for x in range(32):
                dx, dy = (x + 0.5 - 16) / rx, (y + 0.5 - 16) / ry
                if dx * dx + dy * dy <= 1:
                    px(im, x, y, c)
    return outline(im)


def window():
    im = new(16, 16)
    rect(im, 2, 2, 12, 11, WOOD[2])
    rect(im, 3, 3, 10, 9, (150, 206, 236))
    for i in range(4):
        px(im, 4 + i, 7 - i, (220, 242, 252)); px(im, 9 + i, 10 - i, (200, 232, 248))
    rect(im, 7, 3, 2, 9, WOOD[1]); rect(im, 3, 7, 10, 1, WOOD[1])
    C = ramp((236, 150, 150))
    rect(im, 1, 1, 3, 12, C[1]); rect(im, 12, 1, 3, 12, C[1])
    rect(im, 1, 1, 14, 2, C[2]); rect(im, 3, 3, 1, 9, C[3]); rect(im, 12, 3, 1, 9, C[3])
    rect(im, 2, 13, 12, 1, WOOD[1])
    return outline(im)


def picture():
    im = new(16, 16)
    rect(im, 3, 3, 10, 8, (226, 180, 70))
    rect(im, 4, 4, 8, 6, (170, 214, 236))
    rect(im, 4, 8, 8, 2, (110, 176, 90)); px(im, 6, 7, (110, 176, 90)); px(im, 9, 6, (250, 240, 170))
    return outline(im)


def house(wt, ht, roofc, wallc=(240, 226, 196), chimney=False):
    W, H = wt * 16, ht * 16
    im = new(W, H)
    R, Wl = ramp(roofc), ramp(wallc)
    rh = (H * 11) // 20
    if chimney:
        rect(im, W - 14, 1, 6, 8, (170, 110, 96)); rect(im, W - 15, 1, 8, 2, (130, 80, 72))
    rect(im, 1, 3, W - 2, rh - 3, R[2])
    for row, y in enumerate(range(3, rh, 3)):
        rect(im, 1, y, W - 2, 1, R[1])
        for x in range(1 + (row % 2) * 3, W - 1, 6):
            rect(im, x, y + 1, 1, 2, R[3])
    rect(im, 1, 3, W - 2, 1, R[0])
    rect(im, 1, rh, W - 2, 2, R[3])
    wy = rh + 2
    rect(im, 3, wy, W - 6, H - wy - 1, Wl[2])
    for y in range(wy + 3, H - 1, 3):
        rect(im, 3, y, W - 6, 1, Wl[1])
    rect(im, 3, wy, W - 6, 1, Wl[3])
    rect(im, 3, wy, 1, H - wy - 1, Wl[3]); rect(im, W - 4, wy, 1, H - wy - 1, Wl[3])
    # door
    dw, dh = 10, min(14, H - wy - 2)
    dx = W // 2 - dw // 2
    rect(im, dx - 1, H - 1 - dh - 1, dw + 2, dh + 1, WOOD[3])
    rect(im, dx, H - 1 - dh, dw, dh, WOOD[2])
    rect(im, dx, H - 1 - dh, dw, 1, WOOD[1])
    rect(im, dx + dw // 2, H - 1 - dh, 1, dh, WOOD[3])
    px(im, dx + dw - 3, H - 1 - dh // 2, (240, 200, 90)); px(im, dx + 2, H - 1 - dh // 2, (240, 200, 90))
    # windows on each side of the door
    wy2 = wy + max(3, (H - wy - 12) // 2)
    spots = [W // 2 + sgn * off for off in (18, 38, 58) for sgn in (-1, 1)]
    for cx in spots:
        if cx < 10 or cx > W - 10:
            continue
        rect(im, cx - 5, wy2 - 1, 10, 9, WOOD[3])
        rect(im, cx - 4, wy2, 8, 7, (150, 206, 236))
        px(im, cx - 3, wy2 + 1, (230, 246, 252)); px(im, cx - 2, wy2 + 2, (230, 246, 252))
        rect(im, cx, wy2, 1, 7, WOOD[2]); rect(im, cx - 4, wy2 + 3, 8, 1, WOOD[2])
        rect(im, cx - 5, wy2 + 8, 10, 1, (130, 180, 90))  # flower box
        px(im, cx - 3, wy2 + 7, (240, 120, 140)); px(im, cx + 2, wy2 + 7, (250, 220, 100))
    return outline(im)


FURNITURE = [("ベッド", lambda: bed()), ("ベッド(青)", lambda: bed((110, 150, 210))),
             ("テーブル", table), ("いす", chair), ("本棚", bookshelf), ("収納箱", chest),
             ("かまど", stove), ("調理台", counter), ("観葉植物", plant), ("ランプ", lamp),
             ("ラグ", rug), ("窓", window), ("絵", picture), ("床", floor_tile), ("壁", wall_tile)]


def labeled_sheet(items, cols, K, path, title=None, cell=None):
    cell = cell or 32 * K + 30
    rows = (len(items) + cols - 1) // cols
    top = 70 if title else 20
    sheet = Image.new("RGBA", (cols * cell + 40, top + rows * (cell + 34) + 10), BG)
    d = ImageDraw.Draw(sheet)
    f = font(20)
    if title:
        d.text((24, 18), title, font=font(30), fill=INK)
    for i, (name, im) in enumerate(items):
        cx = 20 + (i % cols) * cell
        cy = top + (i // cols) * (cell + 34)
        d.rounded_rectangle((cx, cy, cx + cell - 10, cy + cell - 10), 10, fill=(250, 245, 232))
        big = up(im, K)
        sheet.alpha_composite(big, (cx + (cell - 10 - big.width) // 2, cy + (cell - 10 - big.height) // 2))
        tw = d.textlength(name, font=f)
        d.text((cx + (cell - 10 - tw) / 2, cy + cell - 6), name, font=f, fill=INK)
    sheet.save(path)


# ================= 3. FISH / INGREDIENTS / DISHES =================
def fish(base, rx, ry, kind=None, w=16, h=16):
    im = new(w, h); r = ramp(base)
    cx, cy = w / 2 - 1.5, h / 2
    tx = int(cx + rx) - 1
    tl = 4 if w > 16 else 3
    for i in range(tl):
        for j in range(-i - 1, i + 2):
            px(im, tx + i, int(cy) + j, r[2] if abs(j) <= i else r[3])
    for i in range(3 if w == 16 else 6):
        px(im, int(cx) - 1 + i, int(cy - ry) - (1 if i else 0), r[3])
    ball(im, cx, cy, rx, ry, r)
    belly = mix(base, (255, 255, 250), 0.6)
    for x in range(int(cx - rx) + 2, int(cx + rx) - 1):
        px(im, x, int(cy + ry) - 1, belly)
    if kind == "stripe":
        for x in range(int(cx - rx) + 3, int(cx + rx), 3):
            for y in range(int(cy - ry) + 1, int(cy + ry)):
                px(im, x, y, (60, 60, 90))
    if kind == "spot":
        for x, y in ((cx, cy - 1), (cx + 2, cy + 1), (cx - 1, cy + 1), (cx + 3, cy - 1)):
            px(im, x, y, r[0])
    ex, ey = int(cx - rx) + 2, int(cy) - 1
    px(im, ex, ey, (250, 250, 250)); px(im, ex, ey + 1 if w > 16 else ey, (34, 26, 40))
    if w > 16:
        px(im, ex + 1, ey, (34, 26, 40))
    gx = ex + (3 if w > 16 else 2)
    for y in range(int(cy) - 1, int(cy) + 2):
        px(im, gx, y, r[3])
    if kind == "cat":
        for i in range(3):
            px(im, ex - 2 - i, ey + 2 + i // 2, (60, 44, 34))
    im = outline(im)
    if kind == "boss":
        for x, y in ((2, 2), (26, 1), (29, 13), (4, 13)):
            px(im, x, y, (255, 250, 200)); px(im, x + 1, y, (255, 230, 120)); px(im, x - 1, y, (255, 230, 120))
            px(im, x, y + 1, (255, 230, 120)); px(im, x, y - 1, (255, 230, 120))
    return im


def rod():
    im = new(16, 16)
    for i in range(11):
        px(im, 2 + i, 13 - i, WOOD[2] if i > 2 else (70, 50, 40))
        px(im, 3 + i, 13 - i, WOOD[1] if i > 2 else (110, 80, 60))
    rect(im, 4, 10, 2, 2, (180, 184, 196))
    for y in range(3, 10):
        px(im, 14, y, (230, 230, 240))
    px(im, 14, 10, (230, 70, 70)); px(im, 14, 11, (250, 250, 250))
    return outline(im)


def tomato():
    im = new(16, 16); ball(im, 8, 9.5, 5.5, 4.5, ramp((226, 72, 64)))
    for x, y in ((8, 5), (7, 5), (9, 5), (6, 4), (10, 4), (8, 4), (8, 3)):
        px(im, x, y, (80, 150, 70))
    return outline(im)


def carrot():
    im = new(16, 16); O = ramp((240, 140, 50))
    for y in range(5, 15):
        hw = max(0, int((15 - y) * 0.35))
        for x in range(8 - hw - 1, 8 + hw + 1):
            px(im, x, y, O[1] if x < 8 - hw + 1 else O[2] if x < 8 + hw else O[3])
    for y in (7, 10):
        px(im, 8, y, O[3])
    for x, y in ((7, 4), (8, 4), (6, 3), (8, 3), (9, 3), (5, 2), (8, 2), (10, 2), (8, 1)):
        px(im, x, y, (90, 166, 76))
    return outline(im)


def potato():
    im = new(16, 16); ball(im, 8, 9, 5.5, 4, ramp((196, 154, 100)))
    for x, y in ((6, 9), (10, 8), (9, 11)):
        px(im, x, y, (140, 100, 64))
    return outline(im)


def cabbage():
    im = new(16, 16); G = ramp((140, 200, 100)); ball(im, 8, 8.5, 6, 5.5, G)
    for y in range(5, 13):
        px(im, 8, y, G[0])
    for i in range(4):
        px(im, 7 - i, 8 + i // 2, G[0]); px(im, 9 + i, 8 + i // 2, G[0])
    return outline(im)


def egg():
    im = new(16, 16); ball(im, 8, 9, 4, 5, ramp((250, 238, 218)))
    return outline(im)


def milk():
    im = new(16, 16)
    rect(im, 5, 5, 6, 9, (250, 250, 248)); rect(im, 9, 5, 2, 9, (220, 222, 230))
    rect(im, 6, 3, 4, 2, (250, 250, 248)); rect(im, 6, 2, 4, 1, (90, 140, 210))
    rect(im, 5, 8, 6, 3, (110, 160, 220)); px(im, 6, 9, (250, 250, 250))
    px(im, 6, 6, (255, 255, 255))
    return outline(im)


def wheat():
    im = new(16, 16)
    Y = [(255, 230, 140), (232, 190, 90), (190, 140, 60)]
    for sx in (5, 8, 11):
        for y in range(7, 15):
            px(im, sx, y, (200, 160, 80))
        for y in range(2, 8, 2):
            px(im, sx - 1, y, Y[1]); px(im, sx + 1, y, Y[2]); px(im, sx, y - 1, Y[0])
            px(im, sx, y, Y[1])
    rect(im, 4, 11, 9, 1, (180, 90, 70))
    return outline(im)


def mushroom():
    im = new(16, 16)
    rect(im, 6, 9, 4, 5, (246, 236, 214)); rect(im, 9, 9, 1, 5, (214, 200, 176))
    R = ramp((214, 74, 66))
    for y in range(3, 10):
        for x in range(2, 15):
            dx, dy = (x + 0.5 - 8) / 6, (y + 0.5 - 9) / 6
            if dx * dx + dy * dy <= 1 and y < 9:
                l = -(dx * 0.6 + dy * 0.8)
                px(im, x, y, R[1] if l > 0.45 else R[2] if l > -0.3 else R[3])
    for x, y in ((6, 5), (10, 6), (8, 4), (4, 7), (12, 8)):
        px(im, x, y, (255, 250, 240))
    return outline(im)


PLATE = [(255, 255, 255), (248, 246, 240), (228, 224, 216), (190, 184, 176)]


def plate(im, cx=8, cy=10.5, rx=7, ry=3.6):
    ball(im, cx, cy, rx, ry, PLATE, spot=False)
    ball(im, cx, cy - 0.3, rx - 2, ry - 1.3, [PLATE[1]] * 4, spot=False)


def bowl(im, col, inner, cx=8):
    C = ramp(col)
    for y in range(8, 14):
        hw = int(6 - (y - 8) * 0.7)
        for x in range(cx - hw, cx + hw):
            px(im, x, y, C[1] if x < cx - hw + 2 else C[2] if x < cx + hw - 2 else C[3])
    ball(im, cx, 8, 6, 2.2, [inner] * 4, spot=False)
    for x in range(cx - 6, cx + 6):
        px(im, x, 9, C[0] if abs(x - cx) > 4 else C[1])


def grilled_fish():
    im = new(16, 16); plate(im)
    ball(im, 7.5, 9.5, 4.2, 1.8, ramp((206, 136, 74)))
    for x in (9, 10, 11, 12):
        px(im, x + 1, 9 + (x % 2), (206, 136, 74))
    for x in (6, 8, 10):
        px(im, x, 9, (120, 70, 40)); px(im, x + 1, 10, (120, 70, 40))
    px(im, 4, 9, (40, 30, 30)); px(im, 3, 11, (250, 226, 90)); px(im, 4, 11, (250, 226, 90))
    return outline(im)


def salad():
    im = new(16, 16); bowl(im, WOOD[1], (90, 160, 80))
    for cx, cy in ((6, 7), (9, 6.5), (11, 7.5), (5, 8.5)):
        ball(im, cx, cy, 2, 1.6, ramp((120, 196, 96)), spot=False)
    for x, y in ((8, 7), (10, 8)):
        px(im, x, y, (226, 72, 64)); px(im, x + 1, y, (240, 120, 100))
    px(im, 7, 6, (250, 220, 110))
    return outline(im)


def stew():
    im = new(16, 16); bowl(im, (240, 236, 226), (248, 226, 180))
    for x, y in ((6, 8), (9, 7), (11, 8)):
        px(im, x, y, (240, 140, 50))
    for x, y in ((7, 7), (10, 8)):
        px(im, x, y, (150, 96, 64))
    px(im, 8, 8, (110, 170, 80))
    im = outline(im)
    for i in range(3):
        px(im, 6 + i * 2, 3 - (i % 2), (255, 255, 255, 170)); px(im, 6 + i * 2, 4 + (i % 2), (255, 255, 255, 120))
    return im


def curry():
    im = new(16, 16); plate(im)
    ball(im, 6, 9.5, 3.2, 2.2, [(255, 255, 255), (252, 252, 248), (236, 234, 228), (210, 206, 200)], spot=False)
    ball(im, 10.5, 10, 3.6, 2.4, ramp((196, 130, 50)), spot=False)
    for x, y in ((10, 9), (12, 10)):
        px(im, x, y, (240, 150, 60))
    px(im, 11, 11, (150, 100, 60))
    return outline(im)


def bread():
    im = new(16, 16); ball(im, 8, 9, 6, 4, ramp((222, 158, 86)))
    for i in range(3):
        x = 5 + i * 3
        px(im, x, 8, (250, 220, 160)); px(im, x + 1, 7, (250, 220, 160))
    return outline(im)


def cake():
    im = new(16, 16)
    ball(im, 8, 12, 6.5, 2.4, PLATE, spot=False)
    rect(im, 4, 5, 8, 2, (255, 250, 242))
    rect(im, 4, 7, 8, 5, (244, 214, 140))
    rect(im, 4, 9, 8, 1, (255, 250, 242)); rect(im, 4, 8, 8, 1, (240, 140, 150))
    rect(im, 11, 7, 1, 5, (214, 180, 110))
    ball(im, 8, 4, 1.8, 1.8, ramp((232, 70, 80)))
    px(im, 8, 2, (90, 160, 80))
    return outline(im)


def items_sheet():
    fishes = [("アジ", fish((150, 176, 206), 5, 2.6)),
              ("サケ", fish((226, 128, 116), 6, 3)),
              ("タイ", fish((240, 116, 120), 5, 4, "spot")),
              ("マグロ", fish((76, 96, 146), 6.5, 3.4)),
              ("ナマズ", fish((140, 116, 86), 6, 2.8, "cat")),
              ("ネッタイギョ", fish((252, 210, 80), 4.5, 4, "stripe")),
              ("釣り竿", rod())]
    boss = fish((244, 188, 60), 12, 5.5, "boss", 32, 16)
    ingr = [("トマト", tomato()), ("ニンジン", carrot()), ("ジャガイモ", potato()), ("キャベツ", cabbage()),
            ("タマゴ", egg()), ("ミルク", milk()), ("小麦", wheat()), ("キノコ", mushroom())]
    dish = [("焼き魚", grilled_fish()), ("サラダ", salad()), ("シチュー", stew()),
            ("カレー", curry()), ("パン", bread()), ("ショートケーキ", cake())]
    K = 6; cell = 16 * K + 40
    secs = [("釣り", fishes + [("ヌシ（大物）", boss)]), ("食材", ingr), ("料理", dish)]
    W = 40 + cell * 8 + cell
    H = 30 + sum(70 + cell + 40 for _ in secs)
    sheet = Image.new("RGBA", (W, H), BG)
    d = ImageDraw.Draw(sheet); f = font(20)
    y = 20
    for title, items in secs:
        d.text((24, y), title, font=font(30), fill=INK)
        y += 56
        x = 20
        for name, im in items:
            cw = cell * (2 if im.width > 16 else 1)
            d.rounded_rectangle((x, y, x + cw - 12, y + cell - 12), 10, fill=(250, 245, 232))
            big = up(im, K)
            sheet.alpha_composite(big, (x + (cw - 12 - big.width) // 2, y + (cell - 12 - big.height) // 2))
            tw = d.textlength(name, font=f)
            d.text((x + (cw - 12 - tw) / 2, y + cell - 6), name, font=f, fill=INK)
            x += cw
        y += cell + 40
    sheet.save(f"{OUT}/3_items.png")
    return dict(fishes + [("boss", boss)] + ingr + dish)


# ================= ground tiles (from the first sample) =================
def tile(base, dots, seed, n=14):
    rnd = random.Random(seed)
    im = Image.new("RGBA", (16, 16), base + (255,))
    for _ in range(n):
        im.putpixel((rnd.randrange(16), rnd.randrange(16)), rnd.choice(dots) + (255,))
    return im


def grass(seed=1):
    im = tile((104, 170, 84), [(84, 146, 70), (130, 192, 100)], seed)
    rnd = random.Random(seed + 100)
    for _ in range(2):
        x, y = rnd.randrange(1, 15), rnd.randrange(2, 15)
        im.putpixel((x, y), (84, 146, 70, 255)); im.putpixel((x, y - 1), (84, 146, 70, 255))
    return im


def path_tile(seed=11):
    return tile((206, 186, 146), [(176, 156, 118), (156, 146, 134)], seed, 20)


def tilled():
    im = Image.new("RGBA", (16, 16), (110, 76, 50, 255))
    for y in range(16):
        for x in range(16):
            if y % 4 == 1:
                im.putpixel((x, y), (84, 56, 38, 255))
            elif y % 4 == 2 and x % 3 != 0:
                im.putpixel((x, y), (130, 92, 62, 255))
    return im


def fence():
    im = new(16, 16)
    W_, D = (178, 126, 74), (122, 82, 50)
    for y in (6, 10):
        rect(im, 0, y, 16, 2, W_); rect(im, 0, y + 2, 16, 1, D)
    rect(im, 6, 3, 3, 12, W_); rect(im, 8, 3, 1, 12, D); px(im, 6, 3, (220, 170, 110))
    return im


def flower_tile(seed, c):
    im = grass(seed)
    for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
        px(im, 8 + dx, 8 + dy, c)
    px(im, 8, 8, (250, 220, 110))
    return im


# ================= scenes =================
def interior_scene():
    TW, TH = 12, 9
    sc = new(TW * 16, TH * 16)
    for x in range(TW):
        sc.alpha_composite(wall_tile(), (x * 16, 0))
        sc.alpha_composite(Image.new("RGBA", (16, 16), (238, 222, 196, 255)), (x * 16, 0))
        sc.alpha_composite(wall_tile(), (x * 16, 16))
        for y in range(2, TH):
            sc.alpha_composite(floor_tile(), (x * 16, y * 16))
    rect(sc, 0, 0, TW * 16, 3, (196, 150, 110))
    sc.alpha_composite(window(), (5 * 16, 4)); sc.alpha_composite(window(), (8 * 16, 4))
    sc.alpha_composite(picture(), (3 * 16 + 8, 4))
    sc.alpha_composite(rug(), (6 * 16, 5 * 16 - 4))
    objs = [
        (bed(), 16, 26), (bookshelf(), 3 * 16, 6), (plant(), 4 * 16 + 2, 24),
        (counter(), 9 * 16, 24), (stove(), 10 * 16, 24), (chest(), 11 * 16, 24),
        (chair(), 6 * 16 + 2, 50), (chair(), 7 * 16 + 14, 50), (table(), 6 * 16 + 8, 60),
        (lamp(), 16, 4 * 16 + 14),
    ]
    p1, p2 = character(**PLAYERS[0]), character(**PLAYERS[1])
    objs += [(p1, 9 * 16 + 4, 4 * 16 + 4), (p2, 4 * 16, 6 * 16)]
    for im, x, y in sorted(objs, key=lambda o: o[2] + o[0].height):
        if im in (p1, p2):
            shadow(sc, x + 3, y + 15)
        sc.alpha_composite(im, (x, y))
    return sc


def exterior_scene(items):
    TW, TH = 20, 12
    sc = new(TW * 16, TH * 16)
    for y in range(TH):
        for x in range(TW):
            sc.alpha_composite(grass(1 + (x * 7 + y * 3) % 4), (x * 16, y * 16))
    for x, y, c in ((1, 9, (240, 140, 160)), (13, 1, (250, 250, 250)), (17, 10, (250, 210, 90)), (8, 10, (240, 140, 160))):
        sc.alpha_composite(flower_tile(x + y, c), (x * 16, y * 16))
    for y in range(5, TH):
        sc.alpha_composite(path_tile(y), (9 * 16, y * 16))
    for x in range(1, 9):
        sc.alpha_composite(path_tile(x + 30), (x * 16, 5 * 16))
    for x in range(10, 19):
        sc.alpha_composite(path_tile(x + 50), (x * 16, 7 * 16))
    for y in range(8, 11):
        for x in range(2, 7):
            sc.alpha_composite(tilled(), (x * 16, y * 16))
    from_crop = [(2, 8), (3, 8), (4, 9), (5, 9), (6, 10), (3, 10)]
    objs = []
    for x, y in from_crop:
        objs.append((items["トマト"], x * 16, y * 16 - 4))
    objs.append((items["キャベツ"], 5 * 16, 8 * 16 - 3))
    objs.append((items["ニンジン"], 2 * 16, 10 * 16 - 3))
    for x in range(1, 8):
        objs.append((fence(), x * 16, 11 * 16 - 4))
    objs.append((house(4, 4, (200, 84, 76)), 2 * 16, 1 * 16))
    objs.append((house(6, 5, (80, 120, 184), (236, 230, 214), chimney=True), 12 * 16, 0))
    for i, (pl, x, y) in enumerate(((PLAYERS[2], 8 * 16, 4 * 16 + 8), (PLAYERS[3], 10 * 16 + 4, 8 * 16),
                                     (PLAYERS[4], 13 * 16, 8 * 16 + 4), (PLAYERS[5], 7 * 16, 8 * 16 + 6))):
        objs.append((character(**pl), x, y))
    for im, x, y in sorted(objs, key=lambda o: o[2] + o[0].height):
        if im.width == 16 and im.height == 16 and im.getpixel((5, 6))[:3] in [tuple(s[0]) for s in SKIN.values()]:
            shadow(sc, x + 3, y + 15)
        sc.alpha_composite(im, (x, y))
    return sc


if __name__ == "__main__":
    dressup_sheet()
    labeled_sheet([(n, f()) for n, f in FURNITURE], 5, 5, f"{OUT}/2_furniture.png")
    houses = [("小さな家 4×4", house(4, 4, (200, 84, 76))),
              ("中くらいの家 6×5", house(6, 5, (80, 120, 184), (236, 230, 214), chimney=True)),
              ("大きな家 8×5", house(8, 5, (96, 160, 100), (232, 210, 176), chimney=True))]
    hs = Image.new("RGBA", (1600, 520), BG)
    d = ImageDraw.Draw(hs)
    d.text((24, 16), "家は好きな大きさで建てられる（タイル数を指定して自動で組み立て）", font=font(28), fill=INK)
    x = 30
    for name, im in houses:
        big = up(im, 4)
        hs.alpha_composite(big, (x, 470 - big.height))
        d.text((x, 478), name, font=font(20), fill=INK)
        x += big.width + 50
    hs = hs.crop((0, 0, x, 520)); hs.save(f"{OUT}/2_houses.png")
    items = items_sheet()
    up(interior_scene(), 5).save(f"{OUT}/4_room.png")
    up(exterior_scene(items), 4).save(f"{OUT}/4_village.png")
    print("ok")
