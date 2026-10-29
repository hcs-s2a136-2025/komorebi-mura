"""ひとり用の1ファイル版を開いて、主な場所と画面をひととおり回り、エラーが出ないか確かめる。
ゲームの中でつかまえて続けたエラー（絵のエラーなど。画面は止まらないが、毎フレーム出る）も window.__komoErr で拾う。
使い方: python3 game-src/build.py && python3 tools/tests/smoke.py   （PC・スマホ縦・スマホ横の3つで回す）
新しい場所や画面を足したら、STEPS に1行足す。"""
import asyncio, pathlib, sys
from playwright.async_api import async_playwright
HTML = (pathlib.Path(__file__).resolve().parents[2] / "game-src" / "komorebi.html").as_uri()
G = "window.__game"
STEPS = [
    ("村", f"{G}.enter({G}.village2, 8, 10, 'down')"),
    ("家", f"{G}.enterRoom('home', [7, 9])"),
    ("カジノ", f"{G}.enter({G}.makeInterior('casino'), 8, 10, 'up')"),
    ("VIPルーム", f"{G}.enter({G}.makeInterior('vip'), 7, 9, 'up')"),
    ("VIPの卓", f"{G}.F90.table()"),
    ("VIPの支配人", f"window.KOMO.closeModal(); {G}.F90.manager()"),
    ("VIPのルーレット", f"window.KOMO.closeModal(); {G}.F91.roulette()"),
    ("VIPのバカラ", f"window.KOMO.closeModal(); {G}.F91.baccarat()"),
    ("VIPのルナ", f"window.KOMO.closeModal(); {G}.F91.luna()"),
    ("VIPの景品交換所", f"window.KOMO.closeModal(); {G}.F91.exchange()"),
    ("VIPスロット（えらぶ）", f"window.KOMO.closeModal(); {G}.F95.state().mode = ''; {G}.F95.open()"),
    ("VIPスロット", f"window.KOMO.closeModal(); {G}.F95.state().mode = 'meoshi'; {G}.F95.open()"),
    ("ゲームの選び", f"window.KOMO.closeModal(); {G}.F92.hub()"),
    ("村のビンゴ", f"window.KOMO.closeModal(); {G}.F92.bingo()"),
    ("つなぎ", f"window.KOMO.closeModal(); {G}.F92.tsu()"),
    ("マージ", f"window.KOMO.closeModal(); {G}.F92.mrg()"),
    ("にわとり", f"window.KOMO.closeModal(); {G}.F92.jmp()"),
    ("求人の掲示板", f"window.KOMO.closeModal(); {G}.F93.jobs('village')"),
    ("新聞配達", f"window.KOMO.closeModal(); {G}.F93.news()"),
    ("釣り船", f"window.KOMO.closeModal(); {G}.F93.boat()"),
    ("灯台守", f"window.KOMO.closeModal(); {G}.world.time = 1100; {G}.F93.light()"),
    ("散歩代行", f"window.KOMO.closeModal(); {G}.F97.play('walk')"),
    ("品出し", f"window.KOMO.closeModal(); {G}.F97.play('shelf')"),
    ("雪かき", f"window.KOMO.closeModal(); {G}.F97.play('snow')"),
    ("落ち葉はき", f"window.KOMO.closeModal(); {G}.F97.play('leaf')"),
    ("コイン数え", f"window.KOMO.closeModal(); {G}.F97.play('coin')"),
    ("グラスみがき", f"window.KOMO.closeModal(); {G}.F97.play('glass')"),
    ("クローク係", f"window.KOMO.closeModal(); {G}.F97.play('cloak')"),
    ("ペンキぬり", f"window.KOMO.closeModal(); {G}.F97.play('paint')"),
    ("よろず屋", f"window.KOMO.closeModal(); {G}.enter({G}.makeInterior('shop'), 6, 7, 'up')"),
    ("信用金庫", f"{G}.enter({G}.makeInterior('bank'), 5, 6, 'up')"),
    ("十日屋", f"{G}.enter({G}.makeInterior('yami'), 5, 6, 'up')"),
    ("裏の求人", f"{G}.F96.ura()"),
    ("差し入れ", f"window.KOMO.closeModal(); {G}.F96.playSneak(() => 0)"),
    ("みなと町", f"window.KOMO.closeModal(); {G}.enter({G}.makePort(), 12, 5, 'down')"),
    ("村にもどる", f"{G}.enter({G}.village2, 20, 9, 'down')"),
    ("スマホ", "document.getElementById('phoneBtn76').click()"),
    ("番付", f"window.KOMO.closeModal(); {G}.F74.open()"),
    ("荷積み", f"window.KOMO.closeModal(); {G}.F75.play()"),
    ("こもれびブロック", f"window.KOMO.closeModal(); {G}.F77.play()"),
    ("財布", f"window.KOMO.closeModal(); {G}.F78.openWallet()"),
    ("現金の窓口", f"window.KOMO.closeModal(); {G}.F78.desk()"),
    ("巡査", f"window.KOMO.closeModal(); {G}.F78.openPolice()"),
    ("台車", f"window.KOMO.closeModal(); {G}.F80.cartDemo()"),
    ("2・3階", f"window.KOMO.closeModal(); {G}.F80.demo()"),
    ("建てた家の階", f"window.KOMO.closeModal(); {G}.F82.demo(); window.KOMO.closeModal(); {G}.F82.open('11,9')"),
    ("船着き場", f"window.KOMO.closeModal(); {G}.enter({G}.makePort(), 18, 12, 'right'); {G}.F81.openBoat()"),
]
async def main():
    bad = 0
    async with async_playwright() as p:
        b = await p.chromium.launch()
        for w, h, mobile, name in [(1280, 900, False, "PC"), (390, 844, True, "スマホ縦"), (844, 390, True, "スマホ横")]:
            ctx = await b.new_context(viewport={"width": w, "height": h}, is_mobile=mobile, has_touch=mobile)
            pg = await ctx.new_page(); errs = []
            pg.on("pageerror", lambda e: errs.append("pageerror: " + str(e)[:200]))
            await pg.goto(HTML); await pg.wait_for_timeout(1500)
            await pg.evaluate("window.__errs = []; window.__komoErr = (e, w) => window.__errs.push(`[${w}] ${(e && e.message) || e}`)")
            await pg.evaluate(f"document.body.classList.remove('menu'); {G}.world.tut.done = true; document.getElementById('tut').hidden = true; window.KOMO.closeModal(); {G}.F7.pending.length = 0;")
            for label, js in STEPS:
                try: await pg.evaluate(js)
                except Exception as e: errs.append(f"{label}: {str(e)[:160]}")
                await pg.wait_for_timeout(500)
                k = await pg.evaluate("[...new Set(window.__errs.filter(x => x !== '[undefined] null'))]")
                if k: errs.append(f"{label}: " + " / ".join(k[:3])); await pg.evaluate("window.__errs = []")
            print(name, "OK" if not errs else "NG"); [print("  ", e) for e in errs]; bad += len(errs)
            await ctx.close()
        await b.close()
    sys.exit(1 if bad else 0)
asyncio.run(main())
