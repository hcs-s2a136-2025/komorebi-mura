"""村をふやす機能を、にせサーバー（mock.js）と Playwright で確かめる（4人＋見学）。
使い方: (cd tools/mock-server && npm install) のあと python3 game-src/build.py && python3 tools/tests/villages.py [スクショの保存先]
確かめること：新しい村を作る・村の合言葉で入る・村ごとの人とチャットと共有の村・村長の名前変更と合言葉の作り直し・
同じ端末でキャラを切りかえる（新しいキャラはまっさら、戻ると続き）・全部の村の番付・見学（見えない・さわれない・保存しない）"""
import asyncio, sys, subprocess, time, os, json, urllib.request, pathlib, re, tempfile
from playwright.async_api import async_playwright
ROOT = pathlib.Path(__file__).resolve().parents[2]
SP = sys.argv[1] if len(sys.argv) > 1 else tempfile.mkdtemp(); G = "window.__game"; IDX = str(ROOT / "src/main/resources/static/index.html")
PORT = "8136"; B8 = f"http://localhost:{PORT}"; VER = re.search(r'const CLIENT_VERSION = "([^"]+)"', (ROOT / "game-src/game.js").read_text(encoding="utf-8")).group(1)
def get(u): return json.loads(urllib.request.urlopen(B8+u).read())
async def main():
    m = subprocess.Popen(["node", str(ROOT / "tools/mock-server/mock.js"), IDX, PORT, VER]); time.sleep(1.5)
    try:
        async with async_playwright() as p:
            b = await p.chromium.launch(); errs = []
            async def page(name, mode="main", code="", vname="", mobile=False, ctx=None):
                if ctx is None: ctx = await b.new_context(viewport={'width':390,'height':844} if mobile else {'width':1280,'height':900}, is_mobile=mobile, has_touch=mobile)
                pg = await ctx.new_page(); pg.on('pageerror', lambda e: errs.append(name+':'+str(e)[:200]))
                await pg.goto(B8 + '/'); await pg.wait_for_timeout(1500)
                await pg.evaluate("window.__errs=[]; window.__komoErr=(e,w)=>window.__errs.push(`[${w}] ${(e&&e.message)||e}`)")
                await pg.evaluate(f"document.body.classList.remove('menu'); {G}.world.tut.done=true; {G}.world.tips21=true; document.getElementById('tut').hidden=true;")
                if await pg.is_visible('#jCode'):
                    if mode == "new":
                        await join(pg, name + "M", "main"); await found(pg, name, vname)
                    else:
                        await join(pg, name, mode, code, vname)
                return pg, ctx
            async def join(pg, name, mode="main", code="", vname=""):
                await pg.check(f'[name=vj88][value={mode}]')
                if mode=="code":
                    # 友だちの村は、村の合言葉だけ（招待コードの欄は出ない）
                    if await pg.is_visible('#jCode'): errs.append('jCode visible in code mode')
                    await pg.fill('#vj88c', code)
                else: await pg.fill('#jCode','komorebi')
                await pg.fill('#jName',name); await pg.fill('#jPass','pass1234')
                await pg.click('#jGo'); await pg.wait_for_timeout(2200)
                msg = await pg.evaluate("(document.getElementById('jMsg')&&document.getElementById('jMsg').isConnected&&!document.getElementById('modal').hidden)?document.getElementById('jMsg').textContent:''")
                if not msg: await pg.evaluate(f"if (document.getElementById('mtitle').textContent!=='村') window.KOMO.closeModal(); {G}.F7.pending.length=0;")
                return msg
            async def found(pg, name, vname):
                # 新しい村は、こもれび村にいる人が「村」アプリから作る
                await pg.evaluate(f"window.KOMO.closeModal(); {G}.F7.pending.length=0; {G}.F88.open()"); await pg.wait_for_timeout(200)
                await pg.fill('#vn88v', vname); await pg.fill('#vn88n', name); await pg.fill('#vn88p', 'pass1234')
                await pg.click('#vn88go'); await pg.wait_for_timeout(2200)
                return await pg.evaluate("(document.getElementById('vl88m')&&document.getElementById('vl88m').isConnected)?document.getElementById('vl88m').textContent:''")
            T = lambda pg: pg.evaluate("document.getElementById('mtitle').textContent")
            Bd = lambda pg: pg.evaluate("document.getElementById('mbody').innerText.replace(/\\n+/g,' / ')")
            A, actx = await page('りこ')
            print('A village', await A.evaluate(f"JSON.stringify({G}.F88.v)"), await A.evaluate(f"{G}.village2.name"))
            Bp, bctx = await page('じてか', 'new', vname='あおぞら村', mobile=True)
            print('B village', await Bp.evaluate(f"JSON.stringify({G}.F88.v)"), 'modal', await T(Bp))
            print('B modal body', (await Bd(Bp))[:200])
            await Bp.screenshot(path=f"{SP}/v131_newvillage.png")
            code = await Bp.evaluate(f"{G}.F88.v.code"); await Bp.evaluate("window.KOMO.closeModal()")
            C, cctx = await page('ぬん', 'code', code=code.lower())
            print('C village', await C.evaluate(f"JSON.stringify({G}.F88.v)"))
            await C.wait_for_timeout(1500)
            print('remotes A', await A.evaluate(f"{G}.F8.remotes().map(e=>e.name).join(',')"), '| B', await Bp.evaluate(f"{G}.F8.remotes().map(e=>e.name).join(',')"), '| C', await C.evaluate(f"{G}.F8.remotes().map(e=>e.name).join(',')"))
            # チャット
            await Bp.evaluate("""(()=>{ document.getElementById('chatIn').value='やっほー'; document.getElementById('chatbar11').requestSubmit(); })()""")
            await A.wait_for_timeout(900)
            hasChat = lambda pg: pg.evaluate("document.documentElement.innerHTML.includes('やっほー')")
            print('chat C has', await hasChat(C), 'A has', await hasChat(A))
            # 共有の村は別々：A が木を切ったことにして、edits を送る
            await A.evaluate(f"(()=>{{const k=Object.keys({G}.world.trees)[0]; delete {G}.world.trees[k]; window.__cut=k;}})()"); await A.wait_for_timeout(3500)
            k = await A.evaluate("window.__cut")
            w1 = get('/mock/world?v=1'); w2 = get(f"/mock/world?v={await Bp.evaluate(f'{G}.F88.v.id')}")
            print('tree', k, 'in v1', k in (w1.get('trees') or {}), 'in v2', k in (w2.get('trees') or {}), 'v2 has layers', len(w2.get('trees') or {}))
            # 村長：名前を変える
            await Bp.click('#phoneBtn76'); await Bp.click('[data-app76=village]'); await Bp.wait_for_timeout(300)
            print('B app', (await Bd(Bp))[:160])
            await Bp.fill('#vl88n', 'そよかぜ村'); await Bp.click('#vl88nb'); await Bp.wait_for_timeout(800)
            print('B renamed', await Bp.evaluate(f"{G}.village2.name"), 'C sees', await C.evaluate(f"{G}.village2.name"), 'A', await A.evaluate(f"{G}.village2.name"))
            await Bp.click('#vl88c'); await Bp.wait_for_timeout(800); code2 = await Bp.evaluate(f"{G}.F88.v.code")
            print('code', code, '->', code2, 'C knows', await C.evaluate(f"{G}.F88.v.code"))
            await Bp.screenshot(path=f"{SP}/v131_app.png")
            # 古い合言葉では入れない
            D, dctx = await page('ゆうき', 'main')
            await D.evaluate("window.KOMO.closeModal()"); await D.evaluate("window.KOMO.openJoin ? 0 : 0")
            # ふつうの C は村長じゃない
            await C.click('#phoneBtn76'); await C.click('[data-app76=village]'); await C.wait_for_timeout(200)
            print('C app has owner tools', await C.evaluate("!!document.getElementById('vl88nb')"), 'create form', await C.evaluate("!!document.getElementById('vn88go')"))
            r = await C.evaluate("""fetch('/api/village/new',{method:'POST',headers:{'Content-Type':'application/json','X-Player-Token':JSON.parse(localStorage.getItem('komorebi-online')).token},body:JSON.stringify({village:'ずるい村',name:'ぬん',pass:'pass1234'})}).then(r=>r.status)""")
            print('C (friend village) create via API ->', r)
            r = await C.evaluate("""fetch('/api/join',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({inviteCode:'komorebi',name:'ふるい',pass:'pass1234',newVillage:'ふるい村'})}).then(r=>r.status)""")
            print('old client newVillage join ->', r)
            r = await C.evaluate("""fetch('/api/join',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({inviteCode:'',name:'ぬすみ',pass:'pass1234'})}).then(r=>r.status)""")
            print('main village without invite ->', r)
            await C.evaluate("window.KOMO.closeModal()")
            # 同じ端末で村を切りかえ：A のお金を 99999 にして、新しい村で新しいキャラ → まっさら。りこ に戻ると 99999
            await A.evaluate(f"{G}.world.money = 99999; window.KOMO.saveWorld && window.KOMO.saveWorld()"); await A.evaluate(f"{G}.F14.push(true)"); await A.wait_for_timeout(800)
            await A.evaluate("document.body.classList.add('menu')"); await A.evaluate(f"{G}.F88.open()"); await A.wait_for_timeout(200); await A.click('#vl88j'); await A.wait_for_timeout(300)
            await A.evaluate("window.KOMO.closeModal()"); msg = await found(A, 'りこ2', 'ひだまり村')
            await A.wait_for_timeout(1500)
            print('A2', msg, await A.evaluate(f"JSON.stringify({G}.F88.v)"), 'money', await A.evaluate(f"{G}.world.money"))
            await A.evaluate("window.KOMO.closeModal()"); await A.evaluate(f"{G}.F88.open()"); await A.wait_for_timeout(200); await A.click('#vl88j'); await A.wait_for_timeout(300)
            msg = await join(A, 'りこ', 'main'); await A.wait_for_timeout(2500)
            print('A back', msg, await A.evaluate(f"JSON.stringify({G}.F88.v)"), 'money', await A.evaluate(f"{G}.world.money"))
            # 古い合言葉
            await D.evaluate(f"{G}.F88.open()"); await D.wait_for_timeout(200); await D.click('#vl88j'); await D.wait_for_timeout(300)
            msg = await join(D, 'ゆうき', 'code', code=code)
            print('old code', msg)
            msg = await join(D, 'ゆうき', 'code', code=code2); print('new code', msg or 'ok', await D.evaluate(f"JSON.stringify({G}.F88.v)"))
            # 全部の村の番付
            await Bp.evaluate("window.KOMO.closeModal()"); await Bp.evaluate(f"{G}.world.stats.gambleNet = 5000; {G}.F74.flush(true)"); await A.evaluate(f"{G}.world.stats.gambleNet = 8000; {G}.F74.flush(true)"); await A.wait_for_timeout(600)
            await Bp.evaluate(f"{G}.F74.open()"); await Bp.wait_for_timeout(800); print('rank village', (await Bd(Bp)).split('収益ランキング')[1][:80])
            await Bp.click('[data-s74=everywhere]'); await Bp.wait_for_timeout(800); print('rank everywhere', (await Bd(Bp)).split('収益ランキング')[1][:120])
            await Bp.screenshot(path=f"{SP}/v131_rank.png")
            # 見学
            vB = await Bp.evaluate(f"{G}.F88.v.id"); tok = get(f"/mock/watch?village={vB}")['token']
            wctx = await b.new_context(viewport={'width':1280,'height':900}); W = await wctx.new_page(); W.on('pageerror', lambda e: errs.append('W:'+str(e)[:200]))
            await W.goto(B8 + '/?watch=' + tok); await W.wait_for_timeout(300)
            before = await W.evaluate("JSON.stringify([localStorage.getItem('komorebi-world-v4'), localStorage.getItem('komorebi-online')])")
            await W.wait_for_timeout(2700)
            await W.evaluate("document.body.classList.remove('menu')")
            print('watch bar', await W.evaluate("(document.querySelector('.watch88bar')||{}).innerText||'none'"), 'remotes', await W.evaluate(f"{G}.F8.remotes().map(e=>e.name).join(',')"))
            print('B remotes (watcher hidden?)', await Bp.evaluate(f"{G}.F8.remotes().map(e=>e.name).join(',')"))
            await W.keyboard.press('z'); await W.wait_for_timeout(200); print('watch act toast', await W.evaluate("document.getElementById('toastText').textContent"))
            await W.wait_for_timeout(1500)
            after = await W.evaluate("JSON.stringify([localStorage.getItem('komorebi-world-v4'), localStorage.getItem('komorebi-online')])")
            print('localStorage untouched', before == after, before[:60], after[:60])
            await W.screenshot(path=f"{SP}/v131_watch.png")
            for nm, pg in [('A',A),('B',Bp),('C',C),('D',D),('W',W)]:
                k = await pg.evaluate("window.__errs ? [...new Set(window.__errs.filter(x=>x!=='[undefined] null'))] : []")
                if k: print('komoErr', nm, k[:3])
            print('errs', errs); await b.close()
    finally: m.terminate()
asyncio.run(main())
