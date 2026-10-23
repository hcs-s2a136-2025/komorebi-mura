// Spring Boot サーバーと同じ約束（API・WebSocket）で動く、テスト用の模擬サーバー
// 使い方: npm install && node mock.js ../../src/main/resources/static/index.html 8099 [版] [時間の速さ]
// /mock/threads お便り一覧、/mock/reply?id=1&body=... 管理人の返事のまね、/mock/world 共有の村、/mock/reset 共有の村のリセット、/mock/saves セーブ一覧、/mock/jackpot ジャックポット、/mock/rooms 家の中と箱
// みんなのBJ・麻雀・競どんぐりを試すとき: javac -d bj/out ../../src/main/java/jp/komorebi/casino/BjGame.java ../../src/main/java/jp/komorebi/casino/mj/MjRules.java ../../src/main/java/jp/komorebi/casino/mj/MjAi.java ../../src/main/java/jp/komorebi/casino/mj/MjGame.java ../../src/main/java/jp/komorebi/casino/race/RaceGame.java bj/BjBridge.java bj/MjBridge.java bj/RaceBridge.java（Javaが要ります。なければBJ・麻雀・競どんぐりだけ動きません）
const http = require("http"), fs = require("fs"), crypto = require("crypto"), { WebSocketServer } = require("ws");
const INDEX = process.argv[2], PORT = +process.argv[3] || 8099, VERSION = process.argv[4] || "0.8.0";
const players = new Map(), threads = []; let pid = 1, tid = 1;
const accounts = []; const saves = new Map(); let jackpot = 3000; const deletedTokens = new Set(); const farms = new Map();
// 建てた家の中身（HouseBook.java と同じ約束）
const rooms = new Map(); const KEYRE = /^(\d{1,3},\d{1,3}|home)$/, ITEMRE = /^[\p{L}\p{N}_:\-]{1,32}$/u;
const houseOf = k => k === "home" ? { open: true, ownerId: -1 } : (shared.houses || {})[k] || null;
const isOwner = (k, id) => { const h = houseOf(k); if (!h) return false; const o = h.ownerId == null ? -1 : +h.ownerId; return o < 0 || o === id; };
const canEnter = (k, id) => { const h = houseOf(k); return !!h && (isOwner(k, id) || !!h.open); };
const toList = m => Object.entries(m).map(([id, n]) => ({ id, n }));
const hview = (k, owner, me) => { const r = rooms.get(k); const v = { layout: r && r.layout ? JSON.parse(r.layout) : null, storage: toList(r ? r.shared : {}) }; if (owner) { const pr = k === "home" ? rooms.get("me:" + me) : r; v.priv = toList(pr ? pr.priv : {}); } return v; };
const addTo = (box, id, n) => { if (!ITEMRE.test(id) || !(n >= 1)) return -1; if (!(id in box) && Object.keys(box).length >= 60) return -1; box[id] = Math.min(99999, (box[id] || 0) + n); return box[id]; };
const toMap = (map, msg, ex) => { const t = JSON.stringify(msg); for (const o of sessions) if (o !== ex && o.pl && o.map === map) o.send(t); };
// みんなのBJ：本物と同じ BjGame.java を小さな橋渡し（bj/BjBridge.java）で動かす。BJ_CP にコンパイル先を入れる
const path = require("path"), { spawn } = require("child_process");
const BJ_CP = process.env.BJ_CP || path.join(__dirname, "bj", "out");
let bj = null;
// 卓の中だけのチャット（本物は ws/TableChatHandler.java）。同じ卓の人は、麻雀とBJの橋渡しに聞く
const tReq = new Map(); let tSeq = 1;
function tchatDone(rid, ids, k) {
  const r = tReq.get(rid); if (!r) return; r.left--;
  const list = String(ids || "").split(",").filter(Boolean).map(Number);
  if (!r.done && list.includes(r.from)) { r.done = true; for (const o of sessions) if (o.pl && o.pl.id !== r.from && list.includes(o.pl.id)) o.send(JSON.stringify({ ...r.msg, k })); }
  if (r.left <= 0) { tReq.delete(rid); if (!r.done) r.s.send(JSON.stringify({ type: "tchatNg" })); }
}
if (fs.existsSync(BJ_CP)) {
  bj = spawn("java", ["-cp", BJ_CP, "BjBridge"], { stdio: ["pipe", "pipe", "ignore"] });
  let buf = "";
  bj.stdout.on("data", d => { buf += d.toString("utf8"); let i; while ((i = buf.indexOf("\n")) >= 0) { const line = buf.slice(0, i); buf = buf.slice(i + 1); const f = line.split("\t");
    if (f[0] === "A") { for (const o of sessions) if (o.pl) o.send(f[1]); } else if (f[0] === "P") { for (const o of sessions) if (o.pl && o.pl.id === +f[1]) o.send(f[2]); } else if (f[0] === "M") tchatDone(f[1], f[2], "bj"); } });
  setInterval(() => bj.stdin.write("T\t" + [...sessions].filter(o => o.pl).map(o => o.pl.id).join(",") + "\n"), 500);
  process.on("exit", () => bj.kill()); process.on("SIGTERM", () => { bj.kill(); process.exit(0); }); process.on("SIGINT", () => { bj.kill(); process.exit(0); });
}
// みんなの麻雀：本物と同じ casino/mj/*.java を bj/MjBridge.java で動かす。留守の人の精算は mjPending に取っておいて、次の welcome で渡す
let mj = null; const mjPending = new Map(), adminPending = new Map();
// イベント（本物は EventService.java。ここでは同じ約束をメモリだけで）
let ev = null; let evNext = 1; const evRewards = new Map(); // playerId -> [{id,name,rank,total,reward}]
const evAct = e => ["easter", "halloween", "christmas"].includes(e.kind) ? "collect" : e.kind === "fishing" ? "fish" : e.scoring === "collect" ? "collect" : e.scoring === "none" ? "none" : "fish";
const evVal = (e, sc) => ({ count: sc.count, kinds: sc.kinds.length, biggest: sc.best, collect: sc.collected, none: 0 })[e.scoring] ?? sc.points;
const evTop = e => [...e.scores.entries()].map(([id, sc]) => ({ id, name: sc.name, score: evVal(e, sc) })).sort((a, b) => b.score - a.score);
const evNode = (e, me) => { const sc = e.scores.get(me); return { id: e.id, name: e.name, kind: e.kind, kindName: e.kind, scoring: e.scoring, scoringName: e.scoring, activity: evAct(e), rules: e.rules, startsAt: e.startsAt, endsAt: e.endsAt, now: Date.now(), joinFee: e.joinFee, minLevel: e.minLevel, rewards: e.rewards, joined: !!sc, ...(sc ? { score: evVal(e, sc), count: sc.count, points: sc.points, kinds: sc.kinds.length, best: sc.best, collected: sc.collected } : {}), top: evTop(e).slice(0, 10).map(r => ({ name: r.name, score: r.score })), people: e.scores.size }; };
function evFinish() {
  const e = ev; if (!e) return; ev = null; const top = evTop(e), rw = e.rewards, goal = +rw.goalScore || 0;
  top.forEach((r, i) => { const spec = [i < 3 && e.scoring !== "none" ? rw["r" + (i + 1)] : "", rw.all, goal && r.score >= goal ? rw.goal : ""].filter(Boolean).join(",");
    const msg = { type: "evReward", id: e.id, name: e.name, rank: e.scoring === "none" ? 0 : i + 1, total: top.length, reward: spec };
    const on = [...sessions].filter(o => o.pl && o.pl.id === r.id); if (on.length) on.forEach(o => o.send(JSON.stringify(msg))); evRewards.set(r.id, [...(evRewards.get(r.id) || []).filter(x => x.id !== e.id), msg]); });
  for (const o of sessions) if (o.pl) { o.send(JSON.stringify({ type: "evState", event: null })); o.send(JSON.stringify({ type: "evEnd", id: e.id, name: e.name, top: top.slice(0, 3).map((r, i) => ({ rank: i + 1, name: r.name, score: r.score })) })); }
}
setInterval(() => { if (ev && Date.now() >= ev.endsAt) evFinish(); if (ev && ev.dirty) { ev.dirty = false; for (const o of sessions) if (o.pl) o.send(JSON.stringify({ type: "evBoard", id: ev.id, people: ev.scores.size, top: evTop(ev).slice(0, 10).map(r => ({ name: r.name, score: r.score })) })); } }, 1000);
if (fs.existsSync(path.join(BJ_CP, "MjBridge.class"))) {
  mj = spawn("java", ["-cp", BJ_CP, "MjBridge"], { stdio: ["pipe", "pipe", "ignore"] });
  let buf = "";
  mj.stdout.on("data", d => { buf += d.toString("utf8"); let i; while ((i = buf.indexOf("\n")) >= 0) { const line = buf.slice(0, i); buf = buf.slice(i + 1); const f = line.split("\t");
    if (f[0] === "A") { for (const o of sessions) if (o.pl) o.send(f[1]); } else if (f[0] === "P") { for (const o of sessions) if (o.pl && o.pl.id === +f[1]) o.send(f[2]); }
    else if (f[0] === "M") tchatDone(f[1], f[2], "mj");
    else if (f[0] === "Y") { const id = +f[1], money = +f[2], on = [...sessions].filter(o => o.pl && o.pl.id === id); if (on.length) on.forEach(o => o.send(JSON.stringify({ type: "mjPay", money, text: f[3] }))); else if (money) mjPending.set(id, (mjPending.get(id) || 0) + money); } } });
  setInterval(() => mj.stdin.write("T\t" + [...sessions].filter(o => o.pl).map(o => o.pl.id).join(",") + "\n"), 400);
  process.on("exit", () => mj.kill());
}
// 競どんぐり：本物と同じ casino/race/RaceGame.java を bj/RaceBridge.java で動かす。村の新聞（本物は NewsService.java）はここでメモリだけで
let race = null, raceLast = null; const raceResults = [], news = {}, newsWait = new Map();
if (fs.existsSync(path.join(BJ_CP, "RaceBridge.class"))) {
  race = spawn("java", ["-cp", BJ_CP, "RaceBridge"], { stdio: ["pipe", "pipe", "ignore"] });
  let buf = "";
  race.stdout.on("data", d => { buf += d.toString("utf8"); let i; while ((i = buf.indexOf("\n")) >= 0) { const line = buf.slice(0, i); buf = buf.slice(i + 1); const f = line.split("\t");
    if (f[0] === "A") { try { const m = JSON.parse(f[1]); if (m.type === "race") { raceLast = m; if (m.phase === "result" && !raceResults.some(r => r.id === m.id)) { raceResults.unshift({ id: m.id, order: m.order }); raceResults.length = Math.min(raceResults.length, 40); } } } catch (e) {} for (const o of sessions) if (o.pl) o.send(f[1]); }
    else if (f[0] === "P") { for (const o of sessions) if (o.pl && o.pl.id === +f[1]) o.send(f[2]); }
    else if (f[0] === "S") { const w = newsWait.get(f[1] + ":" + f[2]); newsWait.delete(f[1] + ":" + f[2]); if (w) { let sum = { races: 0 }; try { sum = JSON.parse(f[3]); } catch (e) {} w.s.send(JSON.stringify({ type: "news", day: +f[2], reports: w.reps, race: sum })); } } } });
  setInterval(() => race.stdin.write("T\t" + clock.day + "\n"), 500);
  process.on("exit", () => race.kill());
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
    const same = accounts.filter(a => a.name === name && !a.deleted); let pl = same.find(a => a.pass === pass), created = false;
    if (!pl) { if (same.some(a => a.pass)) return json(res, 409, { message: "このなまえはもう使われているか、合言葉がちがいます" }); pl = same[0]; if (!pl) { pl = { id: pid++, name }; accounts.push(pl); created = true; } pl.pass = pass; }
    for (const [t, a] of players) if (a === pl) players.delete(t);
    const token = crypto.randomBytes(18).toString("base64url"); players.set(token, pl); return json(res, 200, { token, id: pl.id, name: pl.name, created });
  }
  if (p === "/mock/delplayer") { const id = +u.searchParams.get("id"); const a = accounts.find(x => x.id === id && !x.deleted); if (!a) return json(res, 404, {}); a.deleted = true; a.pass = null; saves.delete(id);
    for (const [t, x] of [...players]) if (x === a) { players.delete(t); deletedTokens.add(t); }
    for (const o of sessions) if (o.pl && o.pl.id === id) { o.send(JSON.stringify({ type: "error", code: "deleted", text: "このキャラクターは削除されました" })); o.pl = null; o.close(); }
    return json(res, 200, { ok: true }); }
  if (p === "/mock/players") return json(res, 200, accounts.filter(a => !a.deleted).map(a => ({ id: a.id, name: a.name })));
  if (p === "/mock/saves") return json(res, 200, Object.fromEntries([...saves].map(([k, v]) => [k, { rev: v.rev, size: v.doc.length }])));
  if (p === "/mock/jackpot") return json(res, 200, { jackpot });
  if (p.startsWith("/mock/reply")) { const t = threads.find(t => t.id === +u.searchParams.get("id")); t.messages.push({ sender: "ADMIN", body: u.searchParams.get("body"), createdAt: new Date().toISOString() }); t.playerUnread = true; t.status = "WIP"; for (const s of sessions) if (s.pl && s.pl.id === t.player.id) s.send(JSON.stringify({ type: "feedbackReply", threadId: t.id, title: t.title })); return json(res, 200, {}); }
  if (p === "/mock/threads") return json(res, 200, threads.map(view));
  if (p === "/mock/world") return json(res, 200, shared);
  if (p === "/mock/event/start") { const q = u.searchParams; ev = { id: evNext++, name: q.get("name") || "テスト大会", kind: q.get("kind") || "fishing", scoring: q.get("scoring") || "points", rules: q.get("rules") || "", startsAt: Date.now(), endsAt: Date.now() + (+q.get("secs") || 60) * 1000, joinFee: +q.get("fee") || 0, minLevel: +q.get("lv") || 0, rewards: { r1: q.get("r1") || "", r2: q.get("r2") || "", r3: q.get("r3") || "", all: q.get("all") || "", goal: q.get("goal") || "", goalScore: q.get("goalScore") || "0" }, scores: new Map() };
    for (const o of sessions) if (o.pl) o.send(JSON.stringify({ type: "evState", event: evNode(ev, -1) })); return json(res, 200, { id: ev.id }); }
  if (p === "/mock/event/end") { evFinish(); return json(res, 200, {}); }
  if (p === "/mock/adminpay") { const id = +u.searchParams.get("id"), money = +u.searchParams.get("money") || 0, text = u.searchParams.get("text") || ""; const on = [...sessions].filter(o => o.pl && o.pl.id === id); if (on.length) on.forEach(o => o.send(JSON.stringify({ type: "adminPay", money, text }))); else { const e = adminPending.get(id) || { money: 0 }; e.money += money; if (text) e.text = text; adminPending.set(id, e); } return json(res, 200, { now: on.length > 0 }); }
  if (p === "/mock/event") return json(res, 200, ev ? { ...ev, scores: Object.fromEntries(ev.scores) } : null);
  if (p === "/mock/event/rewards") return json(res, 200, Object.fromEntries(evRewards));
  if (p === "/mock/rooms") return json(res, 200, Object.fromEntries([...rooms].map(([k, r]) => [k, r])));
  if (p === "/mock/reset") { for (const [k, r] of rooms) { if (!/,/.test(k)) continue; const o = (houseOf(k) || {}).ownerId; if (!(o > 0)) continue; const to = returns.get(o) || {}; for (const [id, n] of [...Object.entries(r.shared), ...Object.entries(r.priv)]) to[id] = (to[id] || 0) + n; returns.set(o, to); } shared = {}; for (const k of [...rooms.keys()]) if (/,/.test(k)) rooms.delete(k); epoch++; for (const s of sessions) s.pl && s.send(JSON.stringify({ type: "worldReset" })); return json(res, 200, {}); }
  if (p === "/mock/notice") { for (const s of sessions) s.pl && s.send(JSON.stringify({ type: "notice", text: u.searchParams.get("t") })); return json(res, 200, {}); }
  const pl = who(req); if (p.startsWith("/api/") && !pl) return deletedTokens.has(req.headers["x-player-token"]) ? json(res, 410, { message: "このキャラクターは削除されました" }) : json(res, 401, { message: "もう一度参加してください" });
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
      const pl = players.get(m.token); if (!pl) { s.send(JSON.stringify({ type: "error", code: deletedTokens.has(m.token) ? "deleted" : "auth" })); return s.close(); }
      s.pl = pl; Object.assign(s, { map: m.map, x: m.x, y: m.y, dir: m.dir, cfg: m.cfg });
      s.send(JSON.stringify({ type: "welcome", version: VERSION, you: { id: pl.id, name: pl.name }, players: [...sessions].filter(o => o !== s && o.pl).map(desc), world: shared, worldEpoch: epoch, ...(mjPending.has(pl.id) ? { mjPay: mjPending.get(pl.id) } : {}), ...(adminPending.has(pl.id) ? { adminPay: adminPending.get(pl.id) } : {}), ...(ev ? { event: evNode(ev, pl.id) } : {}), evRewards: evRewards.get(pl.id) || [], chat: chat.slice(-30), clock: cstate(), jackpot, ...(raceLast ? { race: raceLast } : {}), raceResults, farms: [...farms].filter(([id]) => id !== pl.id).map(([, f]) => f) }));
      mjPending.delete(pl.id); adminPending.delete(pl.id);
      return bc({ type: "join", player: desc(s) }, s);
    }
    if (m.type === "move") { Object.assign(s, { map: m.map, x: m.x, y: m.y, dir: m.dir }); bc({ type: "move", id: s.pl.id, map: m.map, x: m.x, y: m.y, dir: m.dir }, s); }
    if (m.type === "cfg") { s.cfg = m.cfg; bc({ type: "cfg", id: s.pl.id, cfg: m.cfg }, s); }
    if (m.type === "emote") bc({ type: "emote", id: s.pl.id, e: m.e }, s);
    if (m.type === "snow") { const o = { type: "snow", id: s.pl.id, x: m.x | 0, y: m.y | 0, dir: m.dir, m: String(m.m || "").slice(0, 32) }; if (m.to && m.to !== s.pl.id) o.to = m.to; bc(o, s); }
    if (m.type === "ping") s.send(JSON.stringify({ type: "pong" }));
    if (m.type === "sleep") { if (Date.now() - clock.start < 3000) return s.send(JSON.stringify({ type: "notice", text: "さっき朝になったばかり" })); advance("sleep", s.pl); }
    if (m.type === "edits") { const out = [], back = []; for (const e of (m.list || []).slice(0, 300)) { if (!e || typeof e.k !== "string") continue; shared[e.l] = shared[e.l] || {};
      if (e.l === "decor") { const cur = shared.decor[e.k]; const ok = !cur || !cur.o || cur.o === s.pl.id; if (!ok || (e.v != null && !/^[a-z0-9_]{1,32}$/.test(String(e.v.id || "")))) { back.push({ l: e.l, k: e.k, v: cur ?? null }); continue; } if (e.v != null) e.v = { id: e.v.id, o: s.pl.id, n: s.pl.name }; } if (e.v == null) { if (!(e.k in shared[e.l])) continue; delete shared[e.l][e.k]; } else shared[e.l][e.k] = e.v; out.push({ l: e.l, k: e.k, v: e.v == null ? null : e.v }); } if (back.length) s.send(JSON.stringify({ type: "edits", by: 0, list: back })); if (out.length) bc({ type: "edits", by: s.pl.id, list: out }, s); }
    if (m.type === "jp") { const n = +m.add || 0; if (n > 0 && n <= 30000) { jackpot += n; bc({ type: "jp", pot: jackpot }); } }
    if (m.type === "jpWin") { const won = jackpot; jackpot = 3000; bc({ type: "jp", pot: jackpot, won, by: s.pl.id, winner: s.pl.name }); }
    if (m.type === "gift") { const to = +m.to, amt = +m.amount, rid = String(m.rid || "").slice(0, 20); const t = [...sessions].find(o => o.pl && o.pl.id === to);
      if (!(amt > 0 && amt <= 100000) || to === s.pl.id) s.send(JSON.stringify({ type: "giftNg", rid, text: "渡せる額は1〜100,000Gだよ" }));
      else if (!t) s.send(JSON.stringify({ type: "giftNg", rid, text: "相手がいまつないでいないみたい" }));
      else { t.send(JSON.stringify({ type: "gift", from: s.pl.id, name: s.pl.name, amount: amt })); s.send(JSON.stringify({ type: "giftOk", rid, to, amount: amt })); } }
    if (m.type === "ev") { const me = s.pl.id, send = o => s.send(JSON.stringify(o));
      if (m.act === "claim") { evRewards.set(me, (evRewards.get(me) || []).filter(x => x.id !== +m.id)); return; }
      if (m.act === "look") return send({ type: "evMe", event: ev ? evNode(ev, me) : null });
      if (!ev) return send({ type: "evNg", text: "いまは開催中のイベントがありません" });
      if (m.act === "join") { if (ev.scores.has(me)) return send({ type: "evMe", event: evNode(ev, me) }); if ((+m.level || 0) < ev.minLevel) return send({ type: "evNg", text: "評判レベルが足りない" });
        ev.scores.set(me, { name: s.pl.name, points: 0, count: 0, best: 0, collected: 0, kinds: [], last: 0 }); ev.dirty = true; return send({ type: "evMe", event: evNode(ev, me), joined: true, fee: ev.joinFee }); }
      const sc = ev.scores.get(me); if (!sc) return; const now = Date.now(), act = evAct(ev);
      if (m.act === "fish" && act === "fish" && now - sc.last >= 2500) { sc.last = now; const size = Math.floor(+m.size || 0); if (size < 1 || size > 210) return; sc.points += 50; sc.count++; if (!sc.kinds.includes(m.fish)) sc.kinds.push(String(m.fish).slice(0, 16)); sc.best = Math.max(sc.best, size); ev.dirty = true; return send({ type: "evMe", event: evNode(ev, me) }); }
      if (m.act === "collect" && act === "collect" && now - sc.last >= 700) { sc.last = now; sc.collected++; ev.dirty = true; return send({ type: "evMe", event: evNode(ev, me) }); }
      return; }
    if (m.type === "motion") { const ok = ["hoe", "axe", "pick", "can", "sword", "shovel", "plant", "fish", "catch", "status"]; if (!ok.includes(m.k)) return; const o = { type: "motion", id: s.pl.id, k: m.k }; if (m.k === "catch") o.f = String(m.f || "").slice(0, 16); if (m.k === "status") o.s = String(m.s || "").slice(0, 12); toMap(s.map, o, s); }
    if (m.type === "farm") { const f = { type: "farm", id: s.pl.id, name: s.pl.name, crops: (Array.isArray(m.crops) ? m.crops : []).slice(0, 600), animals: (Array.isArray(m.animals) ? m.animals : []).slice(0, 40), pets: (Array.isArray(m.pets) ? m.pets : []).slice(0, 20) }; farms.set(s.pl.id, f); bc(f, s); }
    if (m.type === "race" && race) { if (m.act === "bet") race.stdin.write(["B", s.pl.id, String(s.pl.name).replace(/\s/g, ""), Math.floor(+m.runner || 0), String(m.kind || "").replace(/\W/g, ""), Math.floor(+m.amount || 0)].join("\t") + "\n"); else if (m.act === "look") race.stdin.write("L\t" + s.pl.id + "\n"); }
    if (m.type === "news") { const day = Math.floor(+m.day || 0);
      if (m.act === "put" && day >= clock.day - 2 && day <= clock.day && day >= 1) { (news[day] = news[day] || {})[s.pl.id] = { name: s.pl.name, items: (Array.isArray(m.items) ? m.items : []).slice(0, 12).map(it => ({ k: String(it.k || "").slice(0, 12), t: String(it.t || "").slice(0, 90), s: Math.max(0, Math.min(999, it.s | 0)) })).filter(it => it.t), sum: m.sum && typeof m.sum === "object" ? m.sum : {} }; }
      else if (m.act === "get") { const reps = Object.entries(news[day] || {}).map(([id, r]) => ({ ...r, id })); if (race) { newsWait.set(s.pl.id + ":" + day, { s, reps }); race.stdin.write("S\t" + s.pl.id + "\t" + day + "\n"); } else s.send(JSON.stringify({ type: "news", day, reports: reps, race: { races: 0 } })); } }
    if (m.type === "mj" && mj) mj.stdin.write(["C", s.pl.id, String(s.pl.name).replace(/\s/g, ""), String(m.act || "").replace(/\W/g, ""), Math.floor(+m.a || 0), Math.floor(+m.b || 0)].join("\t") + "\n");
    if (m.type === "bj" && bj) bj.stdin.write(["C", s.pl.id, String(s.pl.name).replace(/\s/g, ""), String(m.act || "").replace(/\W/g, ""), Math.floor(+m.amount || 0)].join("\t") + "\n");
    if (m.type === "house" && m.act === "returns") { const it = returns.get(s.pl.id); returns.delete(s.pl.id); if (it && Object.keys(it).length) s.send(JSON.stringify({ type: "houseReturn", items: Object.entries(it).map(([id, n]) => ({ id, n })) })); return; }
    if (m.type === "house") { const k = String(m.key || ""); if (!KEYRE.test(k)) return; const me = s.pl.id;
      if (!houseOf(k)) return s.send(JSON.stringify({ type: "house", key: k, missing: true }));
      const owner = isOwner(k, me), rep = o => ({ type: "house", key: k, owner: o, open: !!houseOf(k).open, ...hview(k, o, me) });
      if (m.act === "import" && k === "home") { const key = "me:" + me; let r = rooms.get(key); if (!r) { r = { layout: null, shared: {}, priv: {} }; rooms.set(key, r); } const ok = {};
        for (const [id, n] of Object.entries(m.items || {})) { const v = Math.min(9999, Math.floor(+n || 0)); if (v > 0 && addTo(r.priv, id, v) >= 0) ok[id] = v; }
        s.send(JSON.stringify({ type: "houseImport", rid: String(m.rid || "").slice(0, 20), ok })); const b = { type: "houseBox", key: k, ...hview(k, true, me) }; delete b.layout; s.send(JSON.stringify(b)); return; }
      if (m.act === "get") { if (!canEnter(k, me)) return s.send(JSON.stringify({ type: "house", key: k, locked: true })); return s.send(JSON.stringify(rep(owner))); }
      if (m.act === "put" && owner && m.layout && typeof m.layout === "object") { const text = JSON.stringify(m.layout); if (text.length > 30000) return;
        let r = rooms.get(k); const fresh = !r; if (fresh) { r = { layout: null, shared: {}, priv: {} }; rooms.set(k, r); } r.layout = text;
        if (fresh && m.seed && k !== "home") { for (const [id, n] of Object.entries(m.seed.storage || {})) addTo(r.shared, id, +n); for (const [id, n] of Object.entries(m.seed.priv || {})) addTo(r.priv, id, +n); }
        s.send(JSON.stringify(rep(true))); toMap("house:" + k, rep(false), s); } }
    if (m.type === "box") { const k = String(m.key || ""), id = String(m.id || ""), n = Math.floor(+m.n || 0), rid = String(m.rid || "").slice(0, 20), me = s.pl.id, priv = m.box === "priv";
      const ng = t => s.send(JSON.stringify({ type: "boxNg", rid, text: t }));
      if (!KEYRE.test(k) || !houseOf(k)) return ng("その家は見つからなかった");
      const owner = isOwner(k, me); if (priv ? !owner : !canEnter(k, me)) return ng(priv ? "鍵がかかっていて開かない" : "この家の箱は使えないみたい");
      if (n < 1 || n > 9999 || !ITEMRE.test(id)) return ng("その数は入れられないよ");
      const bk = priv && k === "home" ? "me:" + me : k;
      let r = rooms.get(bk); if (!r) { r = { layout: null, shared: {}, priv: {} }; rooms.set(bk, r); } const box = priv ? r.priv : r.shared;
      if (m.act === "put") { if (addTo(box, id, n) < 0) return ng("箱がいっぱいみたい"); }
      else if (m.act === "take") { if ((box[id] || 0) < n) return ng("箱にそれだけ入っていないみたい"); box[id] -= n; if (!box[id]) delete box[id]; } else return;
      s.send(JSON.stringify({ type: "boxOk", rid, act: m.act, id, n }));
      const mine = { type: "houseBox", key: k, ...hview(k, owner, me) }; delete mine.layout; s.send(JSON.stringify(mine));
      if (bk !== k) return;
      const oth = { type: "houseBox", key: k, ...hview(k, false) }; delete oth.layout; toMap("house:" + k, oth, s); }
    if (m.type === "tchat") { const t = String(m.text || "").slice(0, 60).trim(); if (!t) return; const rid = String(tSeq++), bridges = [mj, bj].filter(Boolean);
      if (!bridges.length) { s.send(JSON.stringify({ type: "tchatNg" })); return; }
      tReq.set(rid, { from: s.pl.id, s, left: bridges.length, msg: { type: "tchat", id: s.pl.id, name: s.pl.name, text: t, at: Date.now() } });
      for (const br of bridges) br.stdin.write(["M", s.pl.id, rid].join("\t") + "\n"); return; }
    if (m.type === "chat") { const t = String(m.text || "").slice(0, 60).trim(); if (!t) return; chat.push({ name: s.pl.name, text: t, at: Date.now() }); bc({ type: "chat", id: s.pl.id, name: s.pl.name, text: t, at: Date.now() }, s); }
  });
  s.on("close", () => { sessions.delete(s); if (s.pl) bc({ type: "leave", id: s.pl.id }); });
});
