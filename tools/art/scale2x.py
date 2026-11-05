"""いまの 16 ドットの絵を、1ドット＝2×2 の 32 ドットに分ける（Scale2x：ななめのギザギザだけ少しなめらかにする）。

使い方: python3 tools/art/scale2x.py 入力.png 出力.png
分けたあとの描きこみ（目の光・髪の流れ・服の陰など）は、手で足す（docs/方針_ドットを細かく.md）。
"""
import sys
from PIL import Image


def scale2x(im):
    im = im.convert("RGBA")
    w, h = im.size
    a = im.load()
    o = Image.new("RGBA", (w * 2, h * 2))
    b = o.load()
    g = lambda x, y: a[min(max(x, 0), w - 1), min(max(y, 0), h - 1)]
    for y in range(h):
        for x in range(w):
            P, A, B, C, D = g(x, y), g(x, y - 1), g(x + 1, y), g(x - 1, y), g(x, y + 1)
            b[2 * x, 2 * y] = A if (C == A and C != D and A != B) else P
            b[2 * x + 1, 2 * y] = B if (A == B and A != C and B != D) else P
            b[2 * x, 2 * y + 1] = C if (D == C and D != B and C != A) else P
            b[2 * x + 1, 2 * y + 1] = D if (B == D and B != A and D != C) else P
    return o


if __name__ == "__main__":
    scale2x(Image.open(sys.argv[1])).save(sys.argv[2])
