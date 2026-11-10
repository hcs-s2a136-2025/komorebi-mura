"""見た目の点検：smoke.py と同じ順に画面を回して、(1) 文字と背景のコントラスト比が低い所、(2) 画面の横にはみ出している所 を一覧にする。
使い方：python3 tools/tests/look.py [しきい値（はじめ 4.5）]　　※ 直す所をさがす道具。合否は出さない（終了コードはいつも 0）
コントラスト比は WCAG の式。背景は、親をたどって最初に見つかった色（半透明は下の色とまぜる・グラデーションは色の平均）。キャンバスの上の文字は測れないので出さない。"""
import asyncio, re, sys, os
from playwright.async_api import async_playwright
HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, "smoke.py"), encoding="utf-8").read()
ns = {"__file__": os.path.join(HERE, "smoke.py")}; exec(src[:src.index("async def main")], ns)
STEPS, HTML, G = ns["STEPS"], ns["HTML"], ns["G"]
TH = float(next((a for a in sys.argv[1:] if a[0].isdigit()), 4.5))
JS = r"""(th) => {
  const parse = c => { const m = String(c).match(/rgba?\(([^)]+)\)/); if (!m) return null; const p = m[1].split(/[,\s\/]+/).filter(Boolean).map(Number); return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1]; };
  const lum = c => { const f = v => { v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }; return .2126 * f(c[0]) + .7152 * f(c[1]) + .0722 * f(c[2]); };
  const over = (a, b) => { const t = a[3]; return [a[0] * t + b[0] * (1 - t), a[1] * t + b[1] * (1 - t), a[2] * t + b[2] * (1 - t), 1]; };
  const bgOf = el => {
    const stack = []; let e = el, unknown = false;
    while (e && e.nodeType === 1) {
      const s = getComputedStyle(e);
      if (s.backgroundImage && s.backgroundImage !== "none") {
        const cs = [...s.backgroundImage.matchAll(/rgba?\([^)]+\)/g)].map(m => parse(m[0])).filter(c => c && c[3] > .5);
        if (cs.length) { stack.push([cs.reduce((a, c) => a + c[0], 0) / cs.length, cs.reduce((a, c) => a + c[1], 0) / cs.length, cs.reduce((a, c) => a + c[2], 0) / cs.length, 1]); break; }
        if (/url\(/.test(s.backgroundImage)) { unknown = true; break; }
      }
      const c = parse(s.backgroundColor); if (c && c[3] > 0) { stack.push(c); if (c[3] >= .999) break; }
      if (e.tagName === "CANVAS") { unknown = true; break; }
      e = e.parentElement;
    }
    if (unknown) return null;
    if (!stack.length || stack[stack.length - 1][3] < .999) { if (!el.closest("#mbox, .mbox, #menu, .panel, #phone76, .ph76, .ph76m")) return null; stack.push([255, 255, 255, 1]); }
    let b = stack.pop(); while (stack.length) b = over(stack.pop(), b); return b;
  };
  const out = [], W = innerWidth, seen = new Set(), wide = []; let meas = 0, skip = 0; const skipM = new Set();
  for (const el of document.querySelectorAll("body *")) {
    if (el.closest("#tut, script, style")) continue;
    const r = el.getBoundingClientRect(); if (r.width < 2 || r.height < 2 || r.bottom < 0 || r.top > innerHeight) continue;
    const s = getComputedStyle(el); if (s.visibility === "hidden" || s.display === "none" || +s.opacity < .3) continue;
    let hid = false; for (let p = el; p; p = p.parentElement) { const q = getComputedStyle(p); if (q.display === "none" || q.visibility === "hidden" || +q.opacity < .3) { hid = true; break; } } if (hid) continue;
    const sig = el.tagName.toLowerCase() + (el.id ? "#" + el.id.replace(/\d+$/, "") : "") + (typeof el.className === "string" && el.className ? "." + el.className.trim().split(/\s+/).slice(0, 3).join(".") : "");
    if (r.right > W + 3 && r.left < W && el.closest("#mbox, .mbox") && !wide.some(x => x[0] === sig) ) {
      let clipped = false; for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) { const q = getComputedStyle(p); if (/(auto|scroll|hidden)/.test(q.overflowX) && p.getBoundingClientRect().right <= W + 3) { clipped = true; break; } }
      if (!clipped) wide.push([sig, Math.round(r.right - W)]);
    }
    const txt = [...el.childNodes].filter(n => n.nodeType === 3 && n.textContent.trim()).map(n => n.textContent.trim()).join(" "); if (!txt) continue;
    const fg0 = parse(s.color); if (!fg0) continue; const bg = bgOf(el); if (!bg) { skip++; if (el.closest("#mbox, .mbox")) skipM.add(sig); continue; } meas++;
    const fg = over(fg0, bg), L1 = lum(fg), L2 = lum(bg), ratio = (Math.max(L1, L2) + .05) / (Math.min(L1, L2) + .05);
    const px = parseFloat(s.fontSize), big = px >= 24 || (px >= 18.6 && +s.fontWeight >= 700);
    if (ratio >= (big ? Math.min(th, 3) : th)) continue;
    const par = el.parentElement, psig = par ? par.tagName.toLowerCase() + (typeof par.className === "string" && par.className ? "." + par.className.trim().split(/\s+/).slice(0, 2).join(".") : "") : "";
    const hx = c => "#" + c.slice(0, 3).map(v => Math.round(v).toString(16).padStart(2, "0")).join("");
    const key = psig + " > " + sig + "|" + hx(fg) + "|" + hx(bg); if (seen.has(key)) continue; seen.add(key);
    out.push({ sig: psig + " > " + sig, fg: hx(fg), bg: hx(bg), ratio: Math.round(ratio * 100) / 100, px, dis: !!el.closest("[disabled]"), txt: txt.slice(0, 24) });
  }
  return { out, wide, meas, skip, skipM: [...skipM].slice(0, 6) };
}"""
async def main():
    found = {}; wides = {}
    async with async_playwright() as p:
        b = await p.chromium.launch()
        for w, h, mobile, name in [(390, 844, True, "スマホ縦"), (844, 390, True, "スマホ横"), (1280, 900, False, "PC")]:
            ctx = await b.new_context(viewport={"width": w, "height": h}, is_mobile=mobile, has_touch=mobile)
            pg = await ctx.new_page(); await pg.goto(HTML); await pg.wait_for_timeout(1500)
            await pg.evaluate("window.__komoErr = () => {}")
            await pg.evaluate(f"document.body.classList.remove('menu'); {G}.world.tut.done = true; document.getElementById('tut').hidden = true; window.KOMO.closeModal(); {G}.F7.pending.length = 0;")
            extra = [("メニュー（きせかえ）", "window.KOMO.closeModal(); document.body.classList.add('menu')")] + [(f"メニュー（{t}）", f"document.getElementById('tab-{t}') && document.getElementById('tab-{t}').click()") for t in ["build", "craft", "village", "log", "settings"]] + [("メニューを閉じる", "document.body.classList.remove('menu')")]
            for label, js in STEPS + extra:
                try: await pg.evaluate(js)
                except Exception: pass
                await pg.wait_for_timeout(400)
                try:
                    r = await pg.evaluate(JS, TH)
                    # 画面の下のほうも見る（モーダルとメニューをいちばん下までスクロールして、もう一度）
                    moved = await pg.evaluate("(() => { let m = 0; for (const e of document.querySelectorAll('#mbody, .mbody, #mbox, .panel, .tabpanel, #settingsBody')) { if (e.scrollHeight > e.clientHeight + 40) { e.scrollTop = e.scrollHeight; m++; } } return m; })()")
                    if moved:
                        r2 = await pg.evaluate(JS, TH); r["out"] += r2["out"]; r["wide"] += r2["wide"]
                except Exception as e: print("  (測れなかった)", label, str(e)[:80]); continue
                if "-v" in sys.argv: print(f'  {name} {label}: 測れた {r["meas"]}・測れない {r["skip"]} {r["skipM"]}')
                if name != "スマホ横":
                    for o in r["out"]:
                        k = (o["sig"], o["fg"], o["bg"])
                        if k not in found: found[k] = dict(o, at=label, vp=name)
                for sig, px in r["wide"]: wides.setdefault((name, label, sig), px)
            await ctx.close()
        await b.close()
    rows = sorted(found.values(), key=lambda o: o["ratio"])
    print(f"== コントラスト比が {TH} より低い文字：{len(rows)} 種類（低い順）==")
    for o in rows: print(f'{o["ratio"]:5.2f}  {o["fg"]} on {o["bg"]}  {o["px"]:.0f}px{" (押せないボタン)" if o["dis"] else ""}  [{o["vp"]}・{o["at"]}]  {o["sig"]}  「{o["txt"]}」')
    print(f"== 横にはみ出している所：{len(wides)} ==")
    for (vp, label, sig), px in wides.items(): print(f"  [{vp}・{label}] {sig}  +{px}px")
asyncio.run(main())
