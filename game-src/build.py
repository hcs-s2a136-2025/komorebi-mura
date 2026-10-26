# ゲーム本体（1枚のHTML）を組み立てるスクリプト
#   python game-src/build.py
# → src/main/resources/static/index.html（サーバーが配るゲーム）と game-src/komorebi.html（ひとり用・1ファイル版）を作ります
import os
D = os.path.dirname(os.path.abspath(__file__))
r = lambda f: open(os.path.join(D, f), encoding='utf-8').read()
t = r('template2.html'); head = t[:t.index('</style>')]
css = ''.join(r(f) for f in ['extra3.css', 'extra4.css', 'extra5.css', 'extra6.css', 'extra7.css', 'extra8.css', 'extra9.css', 'extra11.css', 'extra13.css'])
import re
ver = re.search(r'const CLIENT_VERSION = "([^"]+)"', r('game.js')).group(1)
out = (head + css + '</style>\n\n' + r('body.html')
       + '\n<script>\n' + r('errguard.js').replace('/*VER*/', ver) + '\n</script>'
       + '\n<script>\n' + r('data.js') + '\n</script>\n<script>\n' + r('mj_core.js') + '\n' + r('bgm.js') + '\n' + r('sfx.js')
       + '\n</script>\n<script>\n' + r('game.js') + '\n</script>\n<script>\n' + r('casino.js') + '\n</script>\n')
page = '<!doctype html><html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">' + out + '</html>'
open(os.path.join(D, '..', 'src', 'main', 'resources', 'static', 'index.html'), 'w', encoding='utf-8').write(page)
open(os.path.join(D, 'komorebi.html'), 'w', encoding='utf-8').write(page)
print('built', len(page), 'bytes')
