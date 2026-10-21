// Spring Boot サーバーと同じ約束（API・WebSocket）で動く、テスト用の模擬サーバー
// 使い方: npm install && node mock.js ../../src/main/resources/static/index.html 8099 [版] [時間の速さ]
// /mock/threads お便り一覧、/mock/reply?id=1&body=... 管理人の返事のまね、/mock/world 共有の村、/mock/reset 共有の村のリセット、/mock/saves セーブ一覧、/mock/jackpot ジャックポット、/mock/rooms 家の中と箱
// みんなのBJを試すとき: javac -d bj/out ../../src/main/java/jp/komorebi/casino/BjGame.java bj/BjBridge.java（Javaが要ります。なければBJの卓だけ動きません）
const http = require("http"), fs = require("fs"), crypto = require("crypto"), { WebSocketServer } = require("ws");
const INDEX = process.argv[2], PORT = +process.argv[3] || 8099, VERSION = process.argv[4] || "0.8.0";
const players = new Map(), threads = []; let pid = 1, tid = 1;
const accounts = []; const saves = new Map(); let jackpot = 3000;
// 建てた家の中身（HouseBook.java と同じ約束）
const rooms = new Map(); const KEYRE = /^\d{1,3},\d{1,3}$/, ITEMRE = /^[\p{L}\p{N}_:\-]{1,32}$/u;
const houseOf = k => (shared.houses || {})[k] || null;
const isOwner = (k, id) => { const h = houseOf(k); if (!h) return false; const o = h.ownerId == null ? -1 : +h.ownerId; return o < 0 || o === id; };
const canEnter = (k, id) => { const h = houseOf(k); return !!h && (isOwner(k, id) || !!h.open); };
const toList = m => Object.entries(m).map(([id, n]) => ({ id, n }));
const hview = (k, owner) => { const r = rooms.get(k); const v = { layout: r && r.layout ? JSON.parse(r.layout) : null, storage: toList(r ? r.shared : {}) }; if (owner) v.priv = toList(r ? r.priv : {}); return v; };
const addTo = (box, id, n) => { if (!ITEMRE.test(id) || !(n >= 1)) return -1; if (!(id in box) && Object.keys(box).length >= 60) return -1; box[id] = Math.min(99999, (box[id] || 0) + n); return box[id]; };
const toMap = (map, msg, ex) => { const t = JSON.stringify(msg); for (const o of sessions) if (o !== ex && o.pl && o.map === map) o.send(t); };
// みんなのBJ：本物と同じ BjGame.java を小さな橋渡し（bj/BjBridge.java）で動かす。BJ_CP にコンパイル先を入れる
const path = require("path"), { spawn } = require("child_process");
const BJ_CP = process.env.BJ_CP || path.join(__dirname, "bj", "out");
let bj = null;
if (fs.existsSync(BJ_CP)) {
  bj = spawn("java", ["-cp", BJ_CP, "BjBridge"], { stdio: ["pipe", "pipe", "ignore"] });
  let buf = "";
  bj.stdout.on("data", d => { buf += d.toString("utf8"); let i; while ((i = buf.indexOf("\n")) >= 0) { const line = buf.slice(0, i); buf = buf.slice(i + 1); const f = line.split("\t");
    if (f[0] === "A") { for (const o of sessions) if (o.pl) o.send(f[1]); } else if (f[0] === "P") { for (const o of sessions) if (o.pl && o.pl.id === +f[1]) o.send(f[2]); } } });
  setInterval(() => bj.stdin.write("T\t" + [...sessions].filter(o => o.pl).map(o => o.pl.id).join(",") + "\n"), 500);
  process.on("exit", () => bj.kill()); process.on("SIGTERM", () => { bj.kill(); process.exit(0); }); process.on("SIGINT", () => { bj.kill(); process.exit(0); });
}
const RATE = +process.argv[5] || 1;
const clock = { day: 1, start: Date.now(), weather: "sun" };
const ctime = () => 360 + (Date.now() - clock.start) / 1000 * RATE;
const cstate = () => ({ day: clock.day, time: Math.min(1439.9, ctime()), rate: RATE, weather: clock.weather });
const sessions = new Set(); let shared = {}; let epoch = 0; const returns = new Map(); const chat = []; const board = []; let bid = 1;
const json = (res, code, obj) => { res.writeHead(code, { "Content-Type": "application/json" }); res.end(obj === undefined ? "" : JSON.stringify(obj)); };
const body = req => new Promise(r => { let d = ""; req.on("data", c => d += c); req.on("end", () => { try { r(JSON.parse(d || "{}")); } catch (e) { r({}); } }); });
const who = req => players.get(req.headers["x-player-token"]);
const view = t => ({ ...t, playerName: t.player.name, playerId: t.player.id, player: undefined });
const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, "http://x"); const p = u.pathname;
  if (p === "/" ) { res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" }); return res.end(fs.readFileSync(INDEX)); }
  if (p === "/api/version") return json(res, 200, { app: "komorebi", version: VERSION, online: sessions.size });
  if (p === "/api/join" && req.method === "POST") {
    const b = await body(req); if (b.inviteCode !== "komorebi") return json(res, 403, { message: "招待コードがちがいます" });
    const name = (String(b.name || "").trim() || "たびびと").slice(0, 8), pass = String(b.pass || "").trim();
    if ([...pass].length < 4 || [...pass].length > 32) return json(res, 400, { message: "合言葉は4〜32文字で決めてね" });
    const same = accounts.filter(a => a.name === name); let pl = same.find(a => a.pass === pass), created = false;
    if (!pl) { if (same.some(a => a.pass)) return json(res, 409, { message: "このなまえはもう使われているか、合言葉がちがいます" }); pl = same[0]; if (!pl) { pl = { id: pid++, name }; accounts.push(pl); created = true; } pl.pass = pass; }
    for (const [t, a] of players) if (a === pl) players.delete(t);
    const token = crypto.randomBytes(18).toString("base64url"); players.set(token, pl); return json(res, 200, { token, id: pl.id, name: pl.name, created });
  }
  if (p === "/mock/saves") return json(res, 200, Object.fromEntries([...saves].map(([k, v]) => [k, { rev: v.rev, size: v.doc.length }])));
  if (p === "/mock/jackpot") return json(res, 200, { jackpot });
  if (p.startsWith("/mock/reply")) { const t = threads.find(t => t.id === +u.searchParams.get("id")); t.messages.push({ sender: "ADMIN", body: u.searchParams.get("body"), createdAt: new Date().toISOString() }); t.playerUnread = true; t.status = "WIP"; for (const s of sessions) if (s.pl && s.pl.id === t.player.id) s.send(JSON.stringify({ type: "feedbackReply", threadId: t.id, title: t.title })); return json(res, 200, {}); }
  if (p === "/mock/threads") return json(res, 200, threads.map(view));
  if (p === "/mock/world") return json(res, 200, shared);
  if (p === "/mock/rooms") return json(res, 200, Object.fromEntries([...rooms].map(([k, r]) => [k, r])));
  if (p === "/mock/reset") { for (const [k, r] of rooms) { const o = (houseOf(k) || {}).ownerId; if (!(o > 0)) continue; const to = returns.get(o) || {}; for (const [id, n] of [...Object.entries(r.shared), ...Object.entries(r.priv)]) to[id] = (to[id] || 0) + n; returns.set(o, to); } shared = {}; rooms.clear(); epoch++; for (const s of sessions) s.pl && s.send(JSON.stringify({ type: "worldReset" })); return json(res, 200, {}); }
  if (p === "/mock/notice") { for (const s of sessions) s.pl && s.send(JSON.stringify({ type: "notice", text: u.searchParams.get("t") })); return json(res, 200, {}); }
  const pl = who(req); if (p.startsWith("/api/") && !pl) return json(res, 401, { message: "もう一度参加してください" });
  if (p === "/api/session") return json(res, 200, { id: pl.id, name: pl.name, version: VERSION, unread: threads.filter(t => t.player === pl && t.playerUnread).length, hasPass: !!pl.pass });
  if (p === "/api/save" && req.method === "GET") { const v = saves.get(pl.id); return v ? json(res, 200, v) : json(res, 204); }
  if (p === "/api/save" && req.method === "PUT") { const b = await body(req); if (typeof b.doc !== "string" || !b.doc.trim().startsWith("{") || b.doc.length > 900000) return json(res, 400, {}); saves.set(pl.id, { doc: b.doc, rev: +b.rev || 0, savedAt: new Date().toISOString() }); return json(res, 200, { savedAt: new Date().toISOString(), rev: +b.rev || 0 }); }
  if (p === "/api/save" && req.method === "DELETE") { saves.delete(pl.id); return json(res, 204); }
  if (p === "/api/me/pass") { const b = await body(req); const pw = String(b.pass || "").trim(); if ([...pw].length < 4) return json(res, 400, { message: "合言葉は4〜32文字で決めてね" }); pl.pass = pw; return json(res, 204); }
  if (p === "/api/me/name") { const b = await body(req); pl.name = String(b.name).slice(0, 8); return json(res, 200, { id: pl.id, name: pl.name }); }
  if (p === "/api/feedback" && req.method === "GET") return json(res, 200, threads.filter(t => t.player === pl).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).map(view));
  if (p === "/api/feedback" && req.method === "POST") { const b = await body(req); if (!["BUG", "IDEA", "OTHER"].includes(b.category) || !b.title || !b.body) return json(res, 400, {}); const now = new Date().toISOString(); const t = { id: tid++, player: pl, category: b.category, title: b.title, status: "OPEN", context: b.context, playerUnread: false, adminUnread: true, createdAt: now, updatedAt: now, messages: [{ sender: "PLAYER", body: b.body, createdAt: now }] }; threads.push(t); return json(res, 201, view(t)); }
  if (p === "/api/board" && req.method === "GET") return json(res, 200, board.filter(b => !b.deleted).slice().reverse());
  if (p === "/api/board" && req.method === "POST") { const b = await body(req); const post = { id: bid++, playerId: pl.id, name: pl.name, body: String(b.body || "").slice(0, 200), createdAt: new Date().toISOString() }; board.push(post); for (const s of sessions) s.pl && s.send(JSON.stringify({ type: "board", id: post.id, name: post.name })); return json(res, 201, post); }
  let bd = p.match(/^\/api\/board\/(\d+)\/delete$/); if (bd) { const b = board.find(b => b.id === +bd[1] && b.playerId === pl.id); if (b) b.deleted = true; return json(res, 204); }
  let m = p.match(/^\/api\/feedback\/(\d+)\/(messages|read)$/);
  if (m) { const t = threads.find(t => t.id === +m[1] && t.player === pl); if (!t) return json(res, 404, {}); if (m[2] === "read") { t.playerUnread = false; return json(res, 204); } const b = await body(req); t.messages.push({ sender: "PLAYER", body: b.body, createdAt: new Date().toISOString() }); t.adminUnread = true; t.updatedAt = new Date().toISOString(); return json(res, 200, view(t)); }
  json(res, 404, {});
});
function advance(reason, pl) { clock.day++; clock.start = Date.now(); clock.weather = ["sun", "rain", "sun"][clock.day % 3]; const msg = { type: "clock", ...cstate(), reason, ...(pl ? { by: pl.id, byName: pl.name } : {}) }; for (const o of sessions) o.pl && o.send(JSON.stringify(msg)); }
setInterval(() => { if (ctime() >= 1440) advance("midnight"); }, 500);
server.listen(PORT, () => console.log("mock on", PORT));
const wss = new WebSocketServer({ server, path: "/ws" });
const desc = s => ({ id: s.pl.id, name: s.pl.name, map: s.map, x: s.x, y: s.y, dir: s.dir, cfg: s.cfg || {} });
const bc = (m, ex) => { const t = JSON.stringify(m); for (const o of sessions) if (o !== ex && o.pl) o.send(t); };
wss.on("connection", s => {
  sessions.add(s);
  s.on("message", raw => {
    let m; try { m = JSON.parse(raw); } catch (e) { return; }
    if (!s.pl) {
      if (m.type !== "hello") return;
      const pl = players.get(m.token); if (!pl) { s.send(JSON.stringify({ type: "error", code: "auth" })); return s.close(); }
      s.pl = pl; Object.assign(s, { map: m.map, x: m.x, y: m.y, dir: m.dir, cfg: m.cfg });
      s.send(JSON.stringify({ type: "welcome", version: VERSION, you: { id: pl.id, name: pl.name }, players: [...sessions].filter(o => o !== s && o.pl).map(desc), world: shared, worldEpoch: epoch, chat: chat.slice(-30), clock: cstate(), jackpot }));
      return bc({ type: "join", player: desc(s) }, s);
    }
    if (m.type === "move") { Object.assign(s, { map: m.map, x: m.x, y: m.y, dir: m.dir }); bc({ type: "move", id: s.pl.id, map: m.map, x: m.x, y: m.y, dir: m.dir }, s); }
    if (m.type === "cfg") { s.cfg = m.cfg; bc({ type: "cfg", id: s.pl.id, cfg: m.cfg }, s); }
    if (m.type === "emote") bc({ type: "emote", id: s.pl.id, e: m.e }, s);
    if (m.type === "ping") s.send(JSON.stringify({ type: "pong" }));
    if (m.type === "sleep") { if (Date.now() - clock.start < 3000) return s.send(JSON.stringify({ type: "notice", text: "さっき朝になったばかり" })); advance("sleep", s.pl); }
    if (m.type === "edits") { const out = []; for (const e of (m.list || []).slice(0, 300)) { if (!e || typeof e.k !== "string") continue; shared[e.l] = shared[e.l] || {}; if (e.v == null) { if (!(e.k in shared[e.l])) continue; delete shared[e.l][e.k]; } else shared[e.l][e.k] = e.v; out.push({ l: e.l, k: e.k, v: e.v == null ? null : e.v }); } if (out.length) bc({ type: "edits", by: s.pl.id, list: out }, s); }
    if (m.type === "jp") { const n = +m.add || 0; if (n > 0 && n <= 30000) { jackpot += n; bc({ type: "jp", pot: jackpot }); } }
    if (m.type === "jpWin") { const won = jackpot; jackpot = 3000; bc({ type: "jp", pot: jackpot, won, by: s.pl.id, winner: s.pl.name }); }
    if (m.type === "gift") { const to = +m.to, amt = +m.amount, rid = String(m.rid || "").slice(0, 20); const t = [...sessions].find(o => o.pl && o.pl.id === to);
      if (!(amt > 0 && amt <= 100000) || to === s.pl.id) s.send(JSON.stringify({ type: "giftNg", rid, text: "渡せる額は1〜100,000Gだよ" }));
      else if (!t) s.send(JSON.stringify({ type: "giftNg", rid, text: "相手がいまつないでいないみたい" }));
      else { t.send(JSON.stringify({ type: "gift", from: s.pl.id, name: s.pl.name, amount: amt })); s.send(JSON.stringify({ type: "giftOk", rid, to, amount: amt })); } }
    if (m.type === "bj" && bj) bj.stdin.write(["C", s.pl.id, String(s.pl.name).replace(/\s/g, ""), String(m.act || "").replace(/\W/g, ""), Math.floor(+m.amount || 0)].join("\t") + "\n");
    if (m.type === "house" && m.act === "returns") { const it = returns.get(s.pl.id); returns.delete(s.pl.id); if (it && Object.keys(it).length) s.send(JSON.stringify({ type: "houseReturn", items: Object.entries(it).map(([id, n]) => ({ id, n })) })); return; }
    if (m.type === "house") { const k = String(m.key || ""); if (!KEYRE.test(k)) return; const me = s.pl.id;
      if (!houseOf(k)) return s.send(JSON.stringify({ type: "house", key: k, missing: true }));
      const owner = isOwner(k, me), rep = o => ({ type: "house", key: k, owner: o, open: !!houseOf(k).open, ...hview(k, o) });
      if (m.act === "get") { if (!canEnter(k, me)) return s.send(JSON.stringify({ type: "house", key: k, locked: true })); return s.send(JSON.stringify(rep(owner))); }
      if (m.act === "put" && owner && m.layout && typeof m.layout === "object") { const text = JSON.stringify(m.layout); if (text.length > 30000) return;
        let r = rooms.get(k); const fresh = !r; if (fresh) { r = { layout: null, shared: {}, priv: {} }; rooms.set(k, r); } r.layout = text;
        if (fresh && m.seed) { for (const [id, n] of Object.entries(m.seed.storage || {})) addTo(r.shared, id, +n); for (const [id, n] of Object.entries(m.seed.priv || {})) addTo(r.priv, id, +n); }
        s.send(JSON.stringify(rep(true))); toMap("house:" + k, rep(false), s); } }
    if (m.type === "box") { const k = String(m.key || ""), id = String(m.id || ""), n = Math.floor(+m.n || 0), rid = String(m.rid || "").slice(0, 20), me = s.pl.id, priv = m.box === "priv";
      const ng = t => s.send(JSON.stringify({ type: "boxNg", rid, text: t }));
      if (!KEYRE.test(k) || !houseOf(k)) return ng("その家は見つからなかった");
      const owner = isOwner(k, me); if (priv ? !owner : !canEnter(k, me)) return ng(priv ? "鍵がかかっていて開かない" : "この家の箱は使えないみたい");
      if (n < 1 || n > 9999 || !ITEMRE.test(id)) return ng("その数は入れられないよ");
      let r = rooms.get(k); if (!r) { r = { layout: null, shared: {}, priv: {} }; rooms.set(k, r); } const box = priv ? r.priv : r.shared;
      if (m.act === "put") { if (addTo(box, id, n) < 0) return ng("箱がいっぱいみたい"); }
      else if (m.act === "take") { if ((box[id] || 0) < n) return ng("箱にそれだけ入っていないみたい"); box[id] -= n; if (!box[id]) delete box[id]; } else return;
      s.send(JSON.stringify({ type: "boxOk", rid, act: m.act, id, n }));
      const mine = { type: "houseBox", key: k, ...hview(k, owner) }; delete mine.layout; s.send(JSON.stringify(mine));
      const oth = { type: "houseBox", key: k, ...hview(k, false) }; delete oth.layout; toMap("house:" + k, oth, s); }
    if (m.type === "chat") { const t = String(m.text || "").slice(0, 60).trim(); if (!t) return; chat.push({ name: s.pl.name, text: t, at: Date.now() }); bc({ type: "chat", id: s.pl.id, name: s.pl.name, text: t, at: Date.now() }, s); }
  });
  s.on("close", () => { sessions.delete(s); if (s.pl) bc({ type: "leave", id: s.pl.id }); });
});
