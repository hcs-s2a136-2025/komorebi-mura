"""キャラの服と顔の 32 ドットの絵（game-src/char32.js）を作る。

使い方: python3 tools/art/make_char32.py
- 服（上・下）：うしろ姿（TOPS_UP）は前だけの描きこみを入れない（ALLOVER の服は前と同じ）。
- 服（上・下）：game-src の 16 の絵（CHAR.TOPS / CHAR.BOTTOMS）を字のまま 2×2 に分けて下書きにし、
  DETAIL の描きこみ（柄・襟・ボタン・しわ・陰）を重ねる。描きこみのない服は、分けた下書きのまま。
- 目・口・ほっぺ：EYES / MOUTH / CHEEK に 32 用の絵を直接書く。
- 絵を直すときは、このファイルを直して作りなおす（char32.js は手で直さない）。新しい服を足したら、作りなおすだけで下書きが入る。

字：上の服 u t v T（服の色の明るい順）・h（さらに明るい）・d（影と線のあいだ）・CHAR.WHITE の字（W X R K P S Y B G O L D）・y（金の糸）・k（黒）・w（白）
    下の服 o p q P（明るい順）・h・d・y・k・w（W X なども使える。P だけは線の色）
"""
import json, os, re, subprocess

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
SRC = os.path.join(ROOT, 'game-src')

JS = r"""
const fs = require('fs'); const d = process.argv[1];
const data = fs.readFileSync(d + '/data.js', 'utf8'), game = fs.readFileSync(d + '/game.js', 'utf8');
const sb = {}; new Function('S', data.replace(/^(const|let|var) (\w+)/mg, 'S.$2') )(sb);
const CHAR = sb.CHAR;
for (const m of game.matchAll(/Object\.assign\(CHAR\.(TOPS|BOTTOMS), (\{[\s\S]*?\n?\})\);/g)) Object.assign(CHAR[m[1]], new Function('return ' + m[2])());
console.log(JSON.stringify({ tops: CHAR.TOPS, bottoms: CHAR.BOTTOMS }));
"""


def load():
    out = subprocess.run(['node', '-e', JS, SRC], capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def sc2x(rows):
    """字のまま Scale2x（ななめのギザギザだけなめらかに）"""
    h, w = len(rows), 16
    g = lambda x, y: rows[min(max(y, 0), h - 1)][min(max(x, 0), w - 1)]
    o = [[' '] * 32 for _ in range(h * 2)]
    for y in range(h):
        for x in range(w):
            P, A, B, C, D = g(x, y), g(x, y - 1), g(x + 1, y), g(x - 1, y), g(x, y + 1)
            o[2 * y][2 * x] = A if (C == A and C != D and A != B) else P
            o[2 * y][2 * x + 1] = B if (A == B and A != C and B != D) else P
            o[2 * y + 1][2 * x] = C if (D == C and D != B and C != A) else P
            o[2 * y + 1][2 * x + 1] = D if (B == D and B != A and D != C) else P
    return o


# ---- 描きこみの道具（g は 32 幅の字の表。x は 0〜31、y は服の上から） ----
def put(g, x, y, ch):
    if 0 <= y < len(g) and 0 <= x < 32 and g[y][x] != '.':
        g[y][x] = ch


def putf(g, x, y, ch):  # 空いた所にも描く
    if 0 <= y < len(g) and 0 <= x < 32:
        g[y][x] = ch


def body(g, y, chs='utvhd'):
    """その行の、服の中身（ふちの線ではない所）の x の一覧"""
    return [x for x in range(32) if g[y][x] in chs]


def rows_where(g, f):
    for y in range(len(g)):
        for x in range(32):
            if f(x, y, g[y][x]):
                yield x, y


def shade(g, outline='T', mid='t', dark='v', light='u'):
    """ふちの内側に1ドットの陰（右と下）と、左上に明るい筋。のっぺりを減らす"""
    h = len(g)
    src = [r[:] for r in g]
    for y in range(h):
        for x in range(32):
            if src[y][x] != mid:
                continue
            r = src[y][x + 1] if x < 31 else '.'
            dn = src[y + 1][x] if y + 1 < h else '.'
            l = src[y][x - 1] if x > 0 else '.'
            if r == outline:
                g[y][x] = dark
            elif dn == outline or dn == '.':
                g[y][x] = dark
            elif l == outline and y < h - 1:
                g[y][x] = light


def mirror_x(x):
    return 31 - x


# ---- 服ごとの描きこみ ----
def d_tshirt(g):
    shade(g)
    for x in range(14, 18): put(g, x, 1, 'v')  # えりぐりの影


def d_hoodie(g):  # パーカー：フードのひも・ポケット
    shade(g)
    put(g, 13, 2, 'w'); put(g, 13, 3, 'w'); put(g, 18, 2, 'w'); put(g, 18, 3, 'w')
    for x in range(12, 20): put(g, x, 4, 'v')
    put(g, 11, 4, 'd'); put(g, 20, 4, 'd')


def d_sweater(g):  # セーター：ケーブル編み
    for y in range(1, len(g)):
        for x in body(g, y):
            if (x + y) % 4 == 0: g[y][x] = 'v'
            elif (x - y) % 4 == 0: g[y][x] = 'u'
    for x in range(12, 20): put(g, x, 0, 'v')


def d_suit(g):  # スーツ：えり（ラペル）・ボタン
    shade(g)
    for y in range(0, 6):
        put(g, 13 - (y // 2), y, 'v'); put(g, 18 + (y // 2), y, 'v')
    put(g, 19, 3, 'k'); put(g, 19, 5, 'k')
    put(g, 15, 2, 'D'); put(g, 16, 2, 'D')  # ネクタイの結び目


def d_itaT(g):  # 痛T：胸の絵を細かく
    shade(g)
    for x, y, ch in [(13, 2, 'P'), (14, 2, 'P'), (17, 2, 'P'), (18, 2, 'P'), (13, 3, 'K'), (14, 3, 'S'), (15, 3, 'S'), (16, 3, 'S'), (17, 3, 'S'), (18, 3, 'K'),
                     (14, 4, 'S'), (15, 4, 'K'), (16, 4, 'K'), (17, 4, 'S'), (15, 5, 'P'), (16, 5, 'P')]:
        put(g, x, y, ch)


def d_dress(g):  # ドレス：ウエストのリボン・すそのひだ
    shade(g)
    for x in body(g, 5): g[5][x] = 'v'
    put(g, 15, 5, 'W'); put(g, 16, 5, 'W'); put(g, 14, 4, 'W'); put(g, 17, 4, 'W')
    for y in range(7, len(g)):
        for x in body(g, y):
            if x % 4 == 0: g[y][x] = 'v'
    for x in body(g, len(g) - 1): g[-1][x] = 'u' if x % 2 else 'h'


def d_maid(g):  # メイド服：エプロンのフリルと胸のリボン
    for x in range(14, 18): put(g, x, 0, 'R')
    put(g, 15, 1, 'R'); put(g, 16, 1, 'R')
    for y in range(2, len(g)):
        xs = [x for x in range(32) if g[y][x] == 'W']
        if xs:
            put(g, xs[0], y, 'X'); put(g, xs[-1], y, 'X')
    for x in range(32):
        if g[-1][x] == 'W' and x % 2: g[-1][x] = 'X'
    shade(g)


def d_sailor(g):  # セーラー服：襟の白線・スカーフ
    for y in range(0, 4):
        for x in range(32):
            if g[y][x] == 'W' and (x in (10, 11, 20, 21) or y == 3): g[y][x] = 'u'
    for x, y in [(14, 2), (15, 2), (16, 2), (17, 2), (14, 3), (17, 3), (15, 4), (16, 4)]: put(g, x, y, 'R')
    put(g, 15, 3, 'W'); put(g, 16, 3, 'W')
    shade(g)


def d_border(g):  # ボーダー：細いしま
    for y in range(len(g)):
        for x in range(32):
            if g[y][x] in 'Wtu' and y >= 1:
                g[y][x] = 'W' if y % 2 == 0 else 't'
    for x in range(32):
        if g[0][x] == 'u': g[0][x] = 'W'


def d_vest(g):  # ニットベスト：V首とダイヤ柄
    for y in range(1, len(g)):
        for x in range(32):
            if g[y][x] in 'tv':
                g[y][x] = 'v' if (x + y) % 4 in (0,) or (x - y) % 4 == 0 else 't'
    for y in range(0, 4):
        put(g, 14 - y // 2, y, 'W'); put(g, 17 + y // 2, y, 'W')


def d_kimono(g):  # 着物：合わせ・帯・帯じめ・柄
    for y in range(len(g)):
        for x in body(g, y, 'tuv'):
            if (x * 7 + y * 5) % 11 == 0: g[y][x] = 'h'
    for x in range(32):
        if g[4][x] == 'R': g[4][x] = 'R'
        if g[5][x] == 'R': g[5][x] = 'D'
    for x in range(10, 23): put(g, x, 4, 'R'); put(g, x, 5, 'R')
    for x in range(11, 22): put(g, x, 5, 'D' if x % 2 else 'R')
    put(g, 16, 4, 'Y'); put(g, 16, 5, 'Y')
    shade(g)


def d_yukata(g):  # 浴衣：帯・朝顔の柄
    for y in range(len(g)):
        for x in body(g, y, 'tuv'):
            if (x + y * 3) % 7 == 0: g[y][x] = 'W'
            elif (x + y * 3) % 7 == 1: g[y][x] = 'u'
    for x in range(10, 23): put(g, x, 4, 'Y'); put(g, x, 5, 'O' if x % 3 == 0 else 'Y')
    shade(g)


def d_santa(g):  # サンタ服：白いふわふわ・ベルトのバックル
    for y in (0, 1, len(g) - 2, len(g) - 1):
        for x in range(32):
            if g[y][x] == 'W' and (x + y) % 3 == 0: g[y][x] = 'X'
    for x in range(14, 18): put(g, x, 4, 'Y'); put(g, x, 5, 'Y')
    put(g, 15, 4, 'K'); put(g, 16, 5, 'K')
    shade(g)


def d_witch(g):  # 魔女のローブ：星のししゅう・えり
    shade(g)
    for x, y in [(12, 4), (19, 6), (14, 8), (21, 9), (10, 8)]:
        put(g, x, y, 'Y')
    put(g, 15, 1, 'Y'); put(g, 16, 1, 'Y')


def d_bunny(g):  # うさぎパーカー：胸のうさぎの顔
    shade(g)
    for x, y, ch in [(13, 2, 'W'), (14, 2, 'W'), (17, 2, 'W'), (18, 2, 'W'), (14, 3, 'P'), (17, 3, 'P'),
                     (13, 4, 'W'), (14, 4, 'W'), (15, 4, 'W'), (16, 4, 'W'), (17, 4, 'W'), (18, 4, 'W'), (14, 5, 'k'), (17, 5, 'k'), (15, 5, 'W'), (16, 5, 'P')]:
        put(g, x, y, ch)


def d_trench(g):  # トレンチコート：ダブルのボタン・ベルト
    shade(g)
    for y in (4, 6):
        put(g, 13, y, 'K'); put(g, 18, y, 'K')
    for x in range(32):
        if g[6][x] == 'K' or g[7][x] == 'K': pass
    put(g, 16, 7, 'Y')


def d_tweed(g):  # ツイード：ざらっとした織り・金ボタン
    for y in range(1, len(g)):
        for x in body(g, y, 'tv'):
            g[y][x] = 'tvuv'[(x * 3 + y * 5) % 4]
    for y in (2, 4): put(g, 16, y, 'Y')


def d_blouse(g):  # シルクのブラウス：つやとリボン
    for y in range(len(g)):
        for x in body(g, y, 'u'):
            if (x + y) % 5 == 0: g[y][x] = 'h'
    for x, y in [(14, 1), (17, 1), (15, 2), (16, 2), (14, 3), (17, 3)]: put(g, x, y, 'W')


def d_cami(g):  # キャミソール：レースのふち
    for x in body(g, 2):
        if x % 2: g[2][x] = 'W'
    shade(g)


def d_offshoulder(g):  # オフショルダー：フリル
    for x in range(32):
        if g[2][x] == 'W': g[2][x] = 'X' if x % 2 else 'W'
        if g[3][x] == 'W': g[3][x] = 'W' if x % 2 else 'X'
    shade(g)


def d_frill(g):  # フリルブラウス：えりと袖のフリル・ボタン
    for x in range(32):
        if g[0][x] == 'W' and x % 2: g[0][x] = 'X'
    for y in (2, 3, 4): put(g, 16, y, 'X')
    for y in range(4, 6):
        for x in range(32):
            if g[y][x] == 'W': g[y][x] = 'W' if (x + y) % 2 else 'X'


def d_cardigan(g):  # カーディガン：ボタンとリブ
    shade(g)
    for y in (2, 4): put(g, 13, y, 'Y'); put(g, 18, y, 'Y')
    for x in range(32):
        if g[-1][x] in 'tuv' and x % 2: g[-1][x] = 'v'


def d_check(g):  # チェックシャツ：細かいチェック
    for y in range(len(g)):
        for x in body(g, y, 'uv'):
            a, b = (x // 2) % 2, (y // 2) % 2
            g[y][x] = 'v' if a and b else 'u' if not a and not b else 't'
    for x in range(14, 18): put(g, x, 0, 'W')
    for y in (2, 4): put(g, 16, y, 'W')


def d_jersey(g):  # ジャージ：ファスナーと2本線
    shade(g)
    for y in range(0, len(g)): put(g, 16, y, 'X')
    for y in range(1, len(g)):
        for x in range(32):
            if g[y][x] == 'W': g[y][x] = 'W'
    put(g, 16, 1, 'Y')


def d_skull(g):  # スカルT
    shade(g)
    for x, y, ch in [(13, 2, 'W'), (14, 2, 'W'), (15, 2, 'W'), (16, 2, 'W'), (17, 2, 'W'), (18, 2, 'W'),
                     (13, 3, 'W'), (14, 3, 'K'), (15, 3, 'W'), (16, 3, 'W'), (17, 3, 'K'), (18, 3, 'W'),
                     (14, 4, 'W'), (15, 4, 'K'), (16, 4, 'K'), (17, 4, 'W'), (14, 5, 'X'), (15, 5, 'W'), (16, 5, 'X'), (17, 5, 'W')]:
        put(g, x, y, ch)


def d_riders(g):  # ライダース：ななめのファスナー・つや
    for y in range(len(g)):
        for x in range(32):
            if g[y][x] == 'Y': g[y][x] = 'K'
    for y in range(0, 6): put(g, 15 + y // 2, y, 'Y')
    for y in range(1, 6):
        xs = [x for x in range(32) if g[y][x] == 'K']
        if len(xs) > 4: g[y][xs[2]] = 'X'  # 左がわのつや


def d_armor(g):  # よろい：横の板・つなぎ目・びょう
    for y in range(len(g)):
        for x in range(32):
            if g[y][x] in 'WX':
                g[y][x] = 'D' if y in (2, 4) else 'W' if y in (0, 3) else 'X'
    for x, y in [(12, 1), (19, 1), (12, 3), (19, 3), (16, 5)]: put(g, x, y, 'Y')


def d_ninja(g):  # 忍者装束：合わせとくさりかたびら
    for y in range(len(g)):
        for x in body(g, y, 't'):
            if (x + y) % 2 == 0 and y > 1: g[y][x] = 'v'
    for y in range(0, 6): put(g, 13 + y, y, 'K')


def d_lolita(g):  # ロリータドレス：段々フリルとリボン
    for y in range(len(g)):
        for x in body(g, y, 't'):
            if y in (6, 7) and x % 2: g[y][x] = 'W'
    for x in range(32):
        if g[-1][x] == 'W': g[-1][x] = 'W' if x % 2 else 'X'
        if g[-2][x] == 'W': g[-2][x] = 'X' if x % 2 else 'W'
    for x, y in [(14, 4), (17, 4), (15, 5), (16, 5)]: put(g, x, y, 'P')
    shade(g)


def d_jsk(g):  # ジャンパースカート：肩ひもとボタン
    shade(g)
    for y in (2, 3): put(g, 12, y, 'Y'); put(g, 19, y, 'Y')
    for y in range(6, len(g)):
        for x in body(g, y):
            if x % 4 == 0: g[y][x] = 'v'


def d_bear(g):  # くまの着ぐるみ：おなか・ぬいめ
    for y in range(len(g)):
        for x in body(g, y, 'u'):
            if (x + y) % 5 == 0: g[y][x] = 'h'
    for y in range(1, 8): put(g, 16, y, 'v')
    shade(g)


def d_pierrot(g):  # ピエロ服：水玉とえりのフリル
    for x in range(32):
        if g[0][x] == 'W' and x % 2: g[0][x] = 'X'
        if g[-1][x] == 'W' and x % 2: g[-1][x] = 'X'
    for y in (3, 6): put(g, 16, y, 'K')


def d_space(g):  # 宇宙服：パネル・ランプ
    for y in range(len(g)):
        for x in range(32):
            if g[y][x] == 'W' and (x + y) % 6 == 0: g[y][x] = 'X'
    for x, y, ch in [(13, 2, 'R'), (14, 2, 'G'), (15, 2, 'Y'), (17, 3, 'B'), (18, 3, 'B')]:
        put(g, x, y, ch)


def d_labcoat(g):  # 白衣：えり・ポケット・ペン
    for y in range(len(g)):
        for x in range(32):
            if g[y][x] == 'W' and (x in (11, 20)): g[y][x] = 'X'
    for x in range(10, 14): put(g, x, 6, 'X')
    put(g, 19, 3, 'B'); put(g, 19, 4, 'B'); put(g, 20, 3, 'R')


DETAIL = {
    'Tシャツ': d_tshirt, 'パーカー': d_hoodie, 'セーター': d_sweater, 'スーツ': d_suit, '痛T': d_itaT, 'ドレス': d_dress, 'メイド服': d_maid,
    'セーラー服': d_sailor, 'ボーダー': d_border, 'ニットベスト': d_vest, '着物': d_kimono, '浴衣': d_yukata, 'サンタ服': d_santa, '魔女のローブ': d_witch,
    'うさぎパーカー': d_bunny, 'トレンチコート': d_trench, 'ツイードジャケット': d_tweed, 'シルクのブラウス': d_blouse,
    'キャミソール': d_cami, 'オフショルダー': d_offshoulder, 'フリルブラウス': d_frill, 'カーディガン': d_cardigan, 'チェックシャツ': d_check,
    'ジャージ': d_jersey, 'スカルT': d_skull, 'ライダース': d_riders, 'よろい': d_armor, '忍者装束': d_ninja, 'ロリータドレス': d_lolita,
    'ジャンパースカート': d_jsk, 'くまの着ぐるみ': d_bear, 'ピエロ服': d_pierrot, '宇宙服': d_space, '白衣': d_labcoat,
}


# ---- 下の服 ----
def b_shade(g):
    shade(g, outline='P', mid='p', dark='q', light='o')


def b_pants(g):
    b_shade(g)
    put(g, 15, 1, 'q'); put(g, 16, 1, 'q')


def b_skirt(g):
    for x in range(32):
        if g[0][x] == 'p' and x % 3 == 0: g[0][x] = 'q'
        if g[1][x] == 'p' and x % 3 == 0: g[1][x] = 'q'
    b_shade(g)


def b_pleats(g):
    for y in range(len(g)):
        for x in range(32):
            if g[y][x] in 'pq': g[y][x] = 'q' if x % 2 else 'p'


def b_jeans(g):
    b_shade(g)
    for y in range(len(g)):
        for x in range(32):
            if g[y][x] in 'pq' and x in (12, 19): g[y][x] = 'o'  # ひざの色落ち
    put(g, 12, 0, 'y'); put(g, 19, 0, 'y'); put(g, 16, 0, 'y')


def b_frill(g):
    for x in range(32):
        if g[-1][x] == 'q': g[-1][x] = 'h'
        if g[-2][x] in 'pq' and x % 2: g[-2][x] = 'o'
    b_shade(g)


def b_tutu(g):
    for y in range(len(g)):
        for x in range(32):
            if g[y][x] == 'p': g[y][x] = 'o' if (x + y) % 2 else 'h'


BDETAIL = {'パンツ': b_pants, 'スカート': b_skirt, 'ショートパンツ': b_pants, 'プリーツスカート': b_pleats, 'ロングスカート': b_skirt,
           'ワイドパンツ': b_pants, 'フリルスカート': b_frill, 'チュチュ': b_tutu, 'ジーンズ': b_jeans, 'ハーフパンツ': b_pants,
           'サルエル': b_pants, 'キュロット': b_skirt}

# ---- 目（左の目を描く。外がわ＝左。6×7、上の行が 32 の y=10。右の目は左右反転） ----
# E 目の色・e 目の色の暗い所・L 目の色の明るい所・H 白い光・K まつげ・W 白・S 肌の陰・P/p ピンク・Y/y 黄色
EYES = {
    'ふつう':  ['......', '.KKK..', '..KH..', '..EE..', '..eE..', '..LL..', '......'],
    'キラキラ': ['......', 'KKKKK.', 'KeHEK.', '.EeHE.', '.EeE..', '.LLL..', '......'],
    'まんまる': ['......', '.KKKK.', '.KHeE.', '.EeeE.', '.EeeE.', '..LL..', '......'],
    'にっこり': ['......', '......', '......', '..KK..', '.K..K.', '......', '......'],
    'ジト目':  ['......', '......', '.SSSS.', '.KKKK.', '..eE..', '..LL..', '......'],
    'たれ目':  ['......', '..KK..', '..KH..', '..EE..', '.eEE..', 'KLL...', '......'],
    'つり目':  ['K.....', '.KKK..', '..KH..', '..EE..', '..eE..', '..L...', '......'],
    'ほそ目':  ['......', '......', '......', '.KKKK.', '..ee..', '......', '......'],
    'ハート目': ['......', 'PP.PP.', 'PpPPP.', 'PPPPP.', '.PPP..', '..P...', '......'],
    '星の目':  ['..Y...', '..Y...', 'YYyYY.', '.YyY..', '.Y.Y..', '......', '......'],
    'ぐるぐる': ['.KKKK.', 'K....K', 'K.KK.K', 'K.K..K', 'K..KK.', '.K....', '......'],
    '×の目':  ['......', '.K..K.', '..KK..', '..KK..', '.K..K.', '......', '......'],
    '白目':   ['......', '.SSS..', '.WWW..', '.WWW..', '.WWW..', '..S...', '......'],
}
# 口（8×4、左上が 32 の (12,16)。正面だけ）
MOUTH = {
    'なし': [],
    'にこっ': ['........', '..M..M..', '...MM...', '........'],
    'ぽかん': ['...mm...', '..mDDm..', '..mDTm..', '...mm...'],
    'ねこ口': ['........', '..M.M.M.', '...M.M..', '........'],
    'べー':  ['........', '..MMMM..', '....TT..', '....tT..'],
    'キバ':  ['........', '..MMMM..', '....W...', '........'],
    'ちゅー': ['........', '....tT..', '....TT..', '........'],
}
# ほっぺ（4×2、体の絵の r の所。左のほっぺを描く）
CHEEK = {
    'ふつう': ['bBBb'],
    'なし': None,
    'しっかり': ['bRRb', '.bb.'],
    'そばかす': ['F..F', '.F..'],
}


ALLOVER = {'セーター', 'ボーダー', 'ニットベスト', '着物', '浴衣', 'チェックシャツ', 'ツイードジャケット', '忍者装束', 'くまの着ぐるみ', 'よろい'}


def main():
    src = load()
    tops, tops_up, bottoms = {}, {}, {}
    for name, t in src['tops'].items():
        g = sc2x(t['rows'])
        if name in DETAIL:
            DETAIL[name](g)
        tops[name] = [''.join(r) for r in g]
        if name not in ALLOVER:
            b = sc2x(t['rows']); shade(b)
            tops_up[name] = [''.join(r) for r in b]
    for name, rows in src['bottoms'].items():
        g = sc2x(rows)
        if name in BDETAIL:
            BDETAIL[name](g)
        bottoms[name] = [''.join(r) for r in g]
    for k, v in EYES.items():
        assert len(v) == 7 and all(len(r) == 6 for r in v), k
    out = ['// キャラの服と顔の 32 ドットの絵。tools/art/make_char32.py が作る（手で直さない）。',
           'const CHAR32 = ' + json.dumps({'TOPS': tops, 'TOPS_UP': tops_up, 'BOTTOMS': bottoms, 'EYES': EYES, 'MOUTH': MOUTH, 'CHEEK': CHEEK}, ensure_ascii=False, indent=0).replace('\n', '') + ';', '']
    open(os.path.join(SRC, 'char32.js'), 'w', encoding='utf-8').write('\n'.join(out))
    print('char32.js', len(tops), 'tops', len(bottoms), 'bottoms')


if __name__ == '__main__':
    main()
