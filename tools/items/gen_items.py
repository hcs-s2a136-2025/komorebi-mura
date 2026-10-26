"""ゲームを読みこんで、アイテムの一覧（ID と名前）を src/main/resources/admin-items.json に書き出す。
管理人室の「物をプレゼント」で、名前から選べるようにするため。アイテムを足したら、build.py のあとにこれも動かす。
使い方: python3 tools/items/gen_items.py game-src/komorebi.html"""
import asyncio, json, sys, pathlib
from playwright.async_api import async_playwright
async def main():
    html = pathlib.Path(sys.argv[1]).resolve()
    async with async_playwright() as p:
        b = await p.chromium.launch(); pg = await b.new_page()
        await pg.goto(html.as_uri()); await pg.wait_for_timeout(1500)
        items = await pg.evaluate("""(()=>{ const I = window.__game.ITEMS; return Object.entries(I).map(([id, it]) => [id, it.name, it.tool ? 'tool' : it.furn ? 'furn' : it.seed ? 'seed' : it.fish ? 'fish' : it.decor ? 'decor' : '']); })()""")
        await b.close()
    out = pathlib.Path(__file__).resolve().parents[2] / "src/main/resources/admin-items.json"
    out.write_text(json.dumps(items, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(len(items), "items ->", out)
asyncio.run(main())
