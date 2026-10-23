(() => {
const K = window.KOMO;
const { $, fmt, esc } = K;
const rnd = n => Math.floor(Math.random() * n);
const S = { busy: false };
K.casinoBusy = () => S.busy;

function moneyLine() { return `所持金 <b>${fmt(K.world.money)}</b>　ジャックポット <b>${fmt(K.world.jackpot)}</b>　メダル <b>${K.world.medals || 0}</b>`; }
function refreshMoney(body) { const m = body.querySelector(".cz-money"); if (m) m.innerHTML = moneyLine(); }
function betRow(id = "bet", label = "賭け金", def) {
  const v = def ?? Math.max(1, Math.min(S.lastBet || 100, K.world.money || 1));
  return `<div class="betrow"><label class="lbl" for="${id}" style="margin:0">${label}</label><input id="${id}" type="number" min="0" value="${v}">
    <button class="chip" type="button" data-bet="${id}:10">10</button><button class="chip" type="button" data-bet="${id}:100">100</button>
    <button class="chip" type="button" data-bet="${id}:1000">1000</button><button class="chip" type="button" data-bet="${id}:half">半分</button><button class="chip" type="button" data-bet="${id}:all">オールイン</button></div>`;
}
function wireBets(body) {
  body.querySelectorAll("[data-bet]").forEach(b => b.onclick = () => {
    const [id, v] = b.dataset.bet.split(":"); const inp = body.querySelector("#" + id);
    inp.value = v === "half" ? Math.max(1, Math.floor(K.world.money / 2)) : v === "all" ? K.world.money : v;
  });
}
const readBet = (body, id = "bet", min = 1) => { const v = Math.floor(Number(body.querySelector("#" + id)?.value)); return v >= min ? v : (min === 0 && v === 0 ? 0 : -1); };
function warnIfBroke(body) {
  if (K.world.money > 0) return;
  body.insertAdjacentHTML("beforeend", `<div class="warn">所持金がない…。働いて稼ぐか、信用金庫や十日屋で借りることもできます。</div>`);
}

// ======================= casino shell =======================
const TABS = [["slot", "スロット"], ["chohan", "丁半"], ["chinchiro", "チンチロ"], ["bj", "どんぐりBJ"], ["mahjong", "賭け麻雀"], ["race", "競どんぐり"]];
const ZASHIKI = [["chohan", "丁半"], ["chinchiro", "チンチロ"]];
const GAME_TITLE = { slot: "スロット台", chohan: "丁半の座敷", chinchiro: "チンチロの座敷", bj: "どんぐりBJの卓", mahjong: "雀卓", race: "競どんぐり" };
function openCasino(tab = "slot", single) {
  S.single = !!single; S.zashiki = single === "zashiki";
  const tabList = S.zashiki ? ZASHIKI : TABS;
  K.openModal(S.zashiki ? "賭場の座敷（銀次）" : single ? GAME_TITLE[tab] : "カジノ こもれびパレス", "dark wide", body => {
    body.innerHTML = `${single && !S.zashiki ? "" : `<div class="mtabs">${tabList.map(([k, l]) => `<button class="chip" type="button" data-ct="${k}" aria-pressed="${k === tab}">${l}</button>`).join("")}</div>`}
      <p class="mtext cz-money">${moneyLine()}</p><div id="czbody" class="czbody"></div>`;
    body.querySelectorAll("[data-ct]").forEach(b => b.onclick = () => { if (S.busy) return; const z = S.zashiki; K.closeModal(); openCasino(b.dataset.ct, z ? "zashiki" : undefined); });
    const cb = body.querySelector("#czbody");
    ({ slot: setupSlot, chohan: setupChohan, chinchiro: setupChinchiro, bj: setupBJ, mahjong: setupMahjongLobby, bjc: setupBJChoice, race: setupRace })[tab === "bj" ? "bjc" : tab](cb, body);
  });
}
K.openCasino = openCasino;

// ======================= SLOT =======================
// symbols: 7, B(金塊), L(ベル=コイン), F(タイ), M(スイカ=キャベツ), C(チェリー=トマト)
const SYM = { "7": "seven", B: "goldbar", L: "coin", F: "fish_タイ", M: "cabbage", C: "tomato" };
const SYM_NAME = { "7": "7", B: "金塊", L: "コイン", F: "タイ", M: "キャベツ", C: "トマト" };
function buildStrip(cnt, seed) {
  const arr = []; for (const [s, n] of Object.entries(cnt)) for (let i = 0; i < n; i++) arr.push(s);
  let x = seed; const r = () => (x = (x * 1103515245 + 12345) % 2147483648) / 2147483648;
  for (let i = arr.length - 1; i > 0; i--) { const j = Math.floor(r() * (i + 1)); [arr[i], arr[j]] = [arr[j], arr[i]]; }
  for (let k = 0; k < 50; k++) for (let i = 0; i < arr.length; i++) if (arr[i] === arr[(i + 1) % arr.length]) { const j = (i + 3 + k) % arr.length; [arr[(i + 1) % arr.length], arr[j]] = [arr[j], arr[(i + 1) % arr.length]]; }
  return arr;
}
const STRIPS = [
  buildStrip({ "7": 1, B: 2, L: 3, F: 4, M: 5, C: 6 }, 11),
  buildStrip({ "7": 1, B: 2, L: 4, F: 4, M: 6, C: 4 }, 23),
  buildStrip({ "7": 1, B: 2, L: 4, F: 5, M: 6, C: 3 }, 37),
];
const PAY3 = { "7": 300, B: 100, L: 20, F: 15, M: 8 };
const CHERRY = [0, 1, 3, 10]; // cherries from the left on a line
const LINES = [[1, 1, 1], [0, 0, 0], [2, 2, 2], [0, 1, 2], [2, 1, 0]]; // row index per reel
const LINE_NAMES = ["中段", "上段", "下段", "右下がり", "右上がり"];
const LINE_COLORS = ["#f0bd4f", "#ff7aa8", "#7ad0ff", "#9bf07a", "#c49bff"];
function lineWin(sy) {
  if (sy[0] === sy[1] && sy[1] === sy[2] && PAY3[sy[0]]) return PAY3[sy[0]];
  let n = 0; while (n < 3 && sy[n] === "C") n++;
  return CHERRY[n];
}
K.SLOT = { STRIPS, PAY3, CHERRY, LINES, lineWin };
const slot = { pos: [0, 7, 14], lines: 5 };
function symAt(r, top, row) { return STRIPS[r][(top + row) % 21]; }
function setupSlot(cb, body) {
  cb.innerHTML = `<div class="slotrow12"><div class="slotbox"><canvas id="slotcv" width="60" height="58"></canvas><div class="reach" id="reach" hidden>リーチ！</div></div><button class="btn spin12" type="button" id="spin2" aria-label="スピン">スピン</button></div>
    <div class="row2 stops"><button class="btn light" type="button" data-stop="0">STOP</button><button class="btn light" type="button" data-stop="1">STOP</button><button class="btn light" type="button" data-stop="2">STOP</button></div>
    <div class="result" id="res">ラインと賭け金を決めてスピン</div>
    <div class="betrow"><span class="lbl" style="margin:0">ライン</span>${[1, 3, 5].map(n => `<button class="chip" type="button" data-lines="${n}" aria-pressed="${slot.lines === n}">${n}ライン</button>`).join("")}
      <label class="chk"><input type="checkbox" id="meoshi"> 目押しする</label></div>
    ${betRow("bet", "1ラインの賭け金", Math.max(1, Math.min(S.lastBet || 20, K.world.money || 1)))}
    <div class="row2"><button class="btn" type="button" id="spin">スピン</button><span class="mtext" id="total"></span><button class="chip sechip" type="button" id="seBtn" aria-pressed="${SFX.on}">効果音 ${SFX.on ? "ON" : "OFF"}</button></div>
    <details class="paytable"><summary>配当表とルール</summary>
      <div class="pay">${Object.entries(PAY3).map(([s, m]) => `<span><img src="${K.ASSETS[SYM[s]]}" alt="">${SYM_NAME[s]}×3　${m}倍</span>`).join("")}
      <span><img src="${K.ASSETS.tomato}" alt="">トマト左から1つ　1倍</span><span><img src="${K.ASSETS.tomato}" alt="">左から2つ　3倍</span><span><img src="${K.ASSETS.tomato}" alt="">3つ　10倍</span></div>
      <p class="mtext">5ラインで中段に7が3つそろうとジャックポットも獲得。賭け金の3%がジャックポットに積み立てられます。各リールは21コマで、理論上の還元率は約95.6%。止まる位置はスピンした瞬間に決まり、目押しは止めるタイミングの演出です。</p></details>`;
  wireBets(body); warnIfBroke(cb);
  cb.querySelector("#seBtn").onclick = e => { SFX.setOn(!SFX.on); e.target.setAttribute("aria-pressed", String(SFX.on)); e.target.textContent = `効果音 ${SFX.on ? "ON" : "OFF"}`; if (SFX.on) SFX.small(); };
  const cv = cb.querySelector("#slotcv"), ctx = cv.getContext("2d");
  const reels = [0, 1, 2].map(i => ({ pos: slot.pos[i], spinning: false, target: 0, remain: -1, speed: 16 }));
  let wins = [], coins = [], flash = 0, raf = 0;
  const totalEl = cb.querySelector("#total");
  const updTotal = () => { const b = readBet(cb); totalEl.textContent = b > 0 ? `合計 ${fmt(b * slot.lines)}` : ""; };
  cb.querySelector("#bet").addEventListener("input", updTotal); updTotal();
  cb.querySelectorAll("[data-lines]").forEach(b => b.onclick = () => { if (S.busy) return; slot.lines = +b.dataset.lines; cb.querySelectorAll("[data-lines]").forEach(x => x.setAttribute("aria-pressed", String(x === b))); updTotal(); draw(performance.now()); });
  function draw(now) {
    ctx.imageSmoothingEnabled = false;
    ctx.fillStyle = "#120b18"; ctx.fillRect(0, 0, 60, 58);
    for (let r = 0; r < 3; r++) {
      const x = 2 + r * 19;
      ctx.fillStyle = "#fff8e8"; ctx.fillRect(x, 2, 18, 54);
      const p = reels[r].pos, base = Math.floor(p), frac = p - base;
      ctx.save(); ctx.beginPath(); ctx.rect(x, 2, 18, 54); ctx.clip();
      for (let row = -1; row < 4; row++) {
        const s = STRIPS[r][((base + row) % 21 + 21) % 21];
        const y = 3 + Math.round((row - frac) * 18);
        ctx.drawImage(K.IMG[SYM[s]], x + 1, y);
      }
      ctx.restore();
      if (reels[r].spinning) { ctx.fillStyle = "rgba(255,248,232,.25)"; ctx.fillRect(x, 2, 18, 54); }
    }
    // active line marks
    for (let i = 0; i < slot.lines; i++) { ctx.fillStyle = LINE_COLORS[i]; const ys = LINES[i].map(r => 3 + r * 18 + 8); ctx.fillRect(0, ys[0] - 1, 2, 2); ctx.fillRect(58, ys[2] - 1, 2, 2); }
    for (const w of wins) {
      ctx.strokeStyle = LINE_COLORS[w]; ctx.lineWidth = 1.2; ctx.globalAlpha = .6 + .4 * Math.sin(now / 90);
      ctx.beginPath(); LINES[w].forEach((r, i) => { const xx = 2 + i * 19 + 9, yy = 3 + r * 18 + 8; i ? ctx.lineTo(xx, yy) : ctx.moveTo(xx - 8, yy); }); ctx.lineTo(57, 3 + LINES[w][2] * 18 + 8); ctx.stroke();
      ctx.globalAlpha = 1;
    }
    for (const c of coins) ctx.drawImage(K.IMG.coin, 0, 0, 16, 16, Math.round(c.x), Math.round(c.y), 8, 8);
    if (flash > 0) { ctx.fillStyle = `rgba(255,240,180,${flash * .5})`; ctx.fillRect(0, 0, 60, 58); }
  }
  let last = performance.now();
  function loop(now) {
    const dt = Math.min(.05, (now - last) / 1000); last = now;
    for (const r of reels) if (r.spinning) {
      let mv = r.speed * dt;
      if (r.remain >= 0) { mv = Math.min(mv, r.remain); r.remain -= mv; }
      r.pos = (r.pos + mv) % 21;
      if (r.remain === 0) { r.spinning = false; r.pos = r.target; r.remain = -1; onStop(); }
    }
    for (let i = coins.length - 1; i >= 0; i--) { const c = coins[i]; c.x += c.vx * dt; c.y += c.vy * dt; c.vy += 60 * dt; if (c.y > 60) coins.splice(i, 1); }
    flash = Math.max(0, flash - dt * 2);
    draw(now);
    if (!cv.isConnected) return;
    raf = requestAnimationFrame(loop);
  }
  raf = requestAnimationFrame(loop);
  const stopReel = i => {
    const r = reels[i]; if (!r.spinning || r.remain >= 0) return;
    let d = ((r.target - r.pos) % 21 + 21) % 21; if (d < 1.5) d += 21; r.remain = d;
  };
  let autoTimers = [];
  function onStop() {
    const stopped = reels.filter(r => !r.spinning).length;
    SFX.reelStop(stopped - 1);
    if (stopped === 2 && reels[2].spinning) {
      const reach = LINES.slice(0, slot.lines).some(l => symAt(0, reels[0].target, l[0]) === "7" && symAt(1, reels[1].target, l[1]) === "7");
      if (reach) { cb.querySelector("#reach").hidden = false; reels[2].speed = 7; SFX.reach(); SFX.spinSlow(); }
    }
    if (stopped === 3) finish();
  }
  let cur = null;
  cb.querySelectorAll("[data-stop]").forEach(b => b.onclick = () => { if (cur && cb.querySelector("#meoshi").checked) stopReel(+b.dataset.stop); });
  cb.querySelector("#spin2").onclick = () => cb.querySelector("#spin").click();
  cb.querySelector("#spin").onclick = () => {
    if (S.busy) return;
    const per = readBet(cb); const res = cb.querySelector("#res");
    const total = per * slot.lines;
    if (per <= 0 || total > K.world.money) { res.textContent = "賭け金を確かめてね"; res.className = "result"; return; }
    if (K.betAllowed && !K.betAllowed(total)) { res.textContent = K.betMsg(); res.className = "result"; return; }
    S.lastBet = per; S.busy = true; wins = []; res.className = "result"; res.textContent = "……";
    cb.querySelector("#reach").hidden = true;
    K.addMoney(-total); K.world.jackpot += Math.ceil(total * .03); if (K.jpAdd) K.jpAdd(Math.ceil(total * .03)); refreshMoney(body);
    cur = { per, total }; SFX.lever(); SFX.spinStart();
    reels.forEach((r, i) => { r.target = rnd(21); r.spinning = true; r.remain = -1; r.speed = 16; });
    if (!cb.querySelector("#meoshi").checked) autoTimers = [700, 1100, 1500].map((t, i) => setTimeout(() => stopReel(i), t));
    else autoTimers = [setTimeout(() => [0, 1, 2].forEach(stopReel), 8000)];
  };
  function finish() {
    autoTimers.forEach(clearTimeout);
    reels.forEach((r, i) => slot.pos[i] = r.target);
    const { per, total } = cur; cur = null;
    let payout = 0; const parts = [];
    LINES.slice(0, slot.lines).forEach((l, i) => {
      const sy = l.map((row, r) => symAt(r, reels[r].target, row));
      const m = lineWin(sy);
      if (m) { payout += per * m; wins.push(i); parts.push(`${LINE_NAMES[i]} ${m}倍`); }
    });
    let msg = payout ? `${parts.join("・")}　+${fmt(payout)}` : "はずれ…";
    let big = payout >= total * 20;
    const center = LINES[0].map((row, r) => symAt(r, reels[r].target, row));
    if (slot.lines === 5 && center.every(s => s === "7")) {
      const jpAmt = K.world.jackpot; payout += jpAmt; msg = `ジャックポット！！ +${fmt(payout)}`; K.world.stats.jackpots++; K.world.jackpot = 3000; big = true; if (K.jpWon) K.jpWon(jpAmt);
    }
    K.gambleResult(total, payout, true);
    SFX.spinStop();
    const jp = msg.startsWith("ジャックポット"), wonN = Math.min(14, Math.max(3, Math.round(payout / Math.max(1, total)) * 2));
    setTimeout(() => { if (jp) SFX.jackpot(); else if (big) SFX.big(); else if (payout > total) SFX.win(wonN); else if (payout > 0) SFX.small(); else SFX.miss(); }, 160);
    const res = cb.querySelector("#res"); res.textContent = msg; res.className = "result" + (payout > total ? " win" : "") + (big ? " big" : "");
    cb.querySelector("#reach").hidden = true;
    if (payout > total) { flash = big ? 1 : .4; for (let i = 0; i < (big ? 40 : 12); i++) coins.push({ x: 30, y: 30, vx: (Math.random() - .5) * 70, vy: -40 - Math.random() * 40 }); }
    refreshMoney(body); S.busy = false; updTotal();
  }
  draw(performance.now());
}

// ======================= CHOHAN =======================
const ch = { history: [] };
const CH_BETS = [
  ["cho", "丁（偶数）", "1.95倍"], ["han", "半（奇数）", "1.95倍"], ["zoro", "ゾロ目", "5.5倍"], ["pin", "ピンゾロ（1・1）", "33倍"], ["sum", "出目の合計", "表を見てね"],
];
const SUM_ODDS = s => Math.floor(36 / (6 - Math.abs(s - 7)) * .95 * 10) / 10;
function setupChohan(cb, body) {
  ch.sel = ch.sel || "cho"; ch.sum = ch.sum || 7;
  cb.innerHTML = `<div class="chbox"><canvas id="chcv" width="120" height="64"></canvas><div class="dealer" id="dl">「入ります」</div></div>
    <div class="mtabs" id="chsel">${CH_BETS.map(([k, l, o]) => `<button class="chip" type="button" data-cb="${k}" aria-pressed="${ch.sel === k}">${l}<small> ${o}</small></button>`).join("")}</div>
    <div class="betrow" id="sumrow" ${ch.sel === "sum" ? "" : "hidden"}><span class="lbl" style="margin:0">合計</span>${[2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12].map(s => `<button class="chip" type="button" data-sum="${s}" aria-pressed="${ch.sum === s}">${s}<small>×${SUM_ODDS(s)}</small></button>`).join("")}</div>
    ${betRow()}
    <div class="row2"><button class="btn" type="button" id="shobu">勝負！</button><button class="chip sechip" type="button" id="seBtn" aria-pressed="${SFX.on}">効果音 ${SFX.on ? "ON" : "OFF"}</button></div>
    <div class="result" id="res"></div>
    <div class="hist" id="hist"></div>
    <p class="mtext small">丁半は当たると賭け金の2倍から寺銭5%を引いた額。出目の合計は「36÷出る組み合わせ数×0.95」倍です。</p>`;
  wireBets(body); warnIfBroke(cb);
  { const se = cb.querySelector("#seBtn"); se.onclick = () => { SFX.setOn(!SFX.on); se.setAttribute("aria-pressed", String(SFX.on)); se.textContent = `効果音 ${SFX.on ? "ON" : "OFF"}`; }; }
  const cv = cb.querySelector("#chcv"), ctx = cv.getContext("2d");
  let dice = [3, 4], phase = "idle", t = 0;
  const renderHist = () => cb.querySelector("#hist").innerHTML = ch.history.map(h => `<span class="${h.even ? "even" : "odd"}">${h.a}・${h.b}<b>${h.a + h.b}${h.even ? "丁" : "半"}</b></span>`).join("");
  renderHist();
  cb.querySelectorAll("[data-cb]").forEach(b => b.onclick = () => { if (S.busy) return; ch.sel = b.dataset.cb; cb.querySelectorAll("[data-cb]").forEach(x => x.setAttribute("aria-pressed", String(x === b))); cb.querySelector("#sumrow").hidden = ch.sel !== "sum"; });
  cb.querySelectorAll("[data-sum]").forEach(b => b.onclick = () => { ch.sum = +b.dataset.sum; cb.querySelectorAll("[data-sum]").forEach(x => x.setAttribute("aria-pressed", String(x === b))); });
  const PIPS = { 1: [[1, 1]], 2: [[0, 0], [2, 2]], 3: [[0, 0], [1, 1], [2, 2]], 4: [[0, 0], [2, 0], [0, 2], [2, 2]], 5: [[0, 0], [2, 0], [1, 1], [0, 2], [2, 2]], 6: [[0, 0], [0, 1], [0, 2], [2, 0], [2, 1], [2, 2]] };
  function die(x, y, n) {
    ctx.fillStyle = "#5a3a28"; ctx.fillRect(x - 1, y - 1, 14, 14);
    ctx.fillStyle = "#fffaf0"; ctx.fillRect(x, y, 12, 12); ctx.fillStyle = "#e9dcc0"; ctx.fillRect(x, y + 10, 12, 2);
    for (const [px, py] of PIPS[n]) { ctx.fillStyle = n === 1 ? "#d83a3a" : "#2a1d33"; const s = n === 1 ? 3 : 2; ctx.fillRect(x + 2 + px * 3.5 - (s === 3 ? .5 : 0), y + 2 + py * 3.5 - (s === 3 ? .5 : 0), s, s); }
  }
  function cup(x, y) {
    ctx.fillStyle = "#3a2216"; ctx.beginPath(); ctx.ellipse(x, y + 18, 20, 5, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = "#6b4228"; ctx.beginPath(); ctx.moveTo(x - 20, y + 18); ctx.lineTo(x - 15, y); ctx.lineTo(x + 15, y); ctx.lineTo(x + 20, y + 18); ctx.fill();
    ctx.fillStyle = "#8b5b3c"; ctx.fillRect(x - 14, y, 6, 17); ctx.fillStyle = "#a8774f"; ctx.fillRect(x - 15, y - 2, 30, 3);
  }
  let last = performance.now();
  function loop(now) {
    const dt = Math.min(.05, (now - last) / 1000); last = now; t += dt;
    ctx.fillStyle = "#2c4a2f"; ctx.fillRect(0, 0, 120, 64);
    for (let x = 0; x < 120; x += 4) { ctx.fillStyle = x % 8 ? "#355a38" : "#2f5133"; ctx.fillRect(x, 0, 2, 64); }
    ctx.fillStyle = "#f3e6c6"; ctx.fillRect(10, 8, 100, 48); ctx.fillStyle = "#e7d5ad"; ctx.fillRect(10, 52, 100, 4);
    ctx.fillStyle = "#c43a3a"; ctx.fillRect(10, 8, 100, 2);
    const cx = 60, cy = 22;
    if (phase === "idle" || phase === "lift" || phase === "done") { die(cx - 15, 30, dice[0]); die(cx + 3, 30, dice[1]); }
    if (phase === "shake") { const sx = Math.sin(t * 40) * 8, sy = Math.abs(Math.sin(t * 20)) * -6; cup(cx + sx, cy + sy - 6); }
    else if (phase === "down") cup(cx, cy);
    else if (phase === "lift") { const k = Math.min(1, t / .45); cup(cx, cy - k * 40); }
    else if (phase === "idle" && !ch.history.length) cup(cx, cy);
    if (cv.isConnected) requestAnimationFrame(loop);
  }
  requestAnimationFrame(loop);
  const dl = cb.querySelector("#dl");
  cb.querySelector("#shobu").onclick = () => {
    if (S.busy) return;
    const bet = readBet(cb); const res = cb.querySelector("#res");
    if (bet <= 0 || bet > K.world.money) { res.textContent = "賭け金を確かめてね"; return; }
    if (K.betAllowed && !K.betAllowed(bet)) { res.textContent = K.betMsg(); return; }
    S.lastBet = bet; S.busy = true; K.addMoney(-bet); refreshMoney(body); res.textContent = ""; res.className = "result";
    const a = rnd(6) + 1, b = rnd(6) + 1;
    phase = "shake"; t = 0; dl.textContent = "「入ります！」"; SFX.cupShake();
    setTimeout(() => { phase = "down"; t = 0; SFX.cupSlam(); dl.textContent = "「丁方ないか、半方ないか……揃いました」"; }, 900);
    setTimeout(() => {
      dice = [a, b]; phase = "lift"; t = 0; dl.textContent = "「勝負！」";
      const even = (a + b) % 2 === 0; let mult = 0;
      if (ch.sel === "cho" && even) mult = 1.95; if (ch.sel === "han" && !even) mult = 1.95;
      if (ch.sel === "zoro" && a === b) mult = 5.5; if (ch.sel === "pin" && a === 1 && b === 1) mult = 33;
      if (ch.sel === "sum" && a + b === ch.sum) mult = SUM_ODDS(ch.sum);
      const payout = Math.floor(bet * mult);
      setTimeout(() => {
        phase = "done";
        dl.textContent = `「${a}・${b}の${even ? "丁" : "半"}！」`;
        K.gambleResult(bet, payout, true);
        res.textContent = payout ? `当たり！ +${fmt(payout)}` : "はずれ…"; if (mult >= 5) SFX.big(); else if (payout) SFX.win(5); else SFX.miss(); res.className = "result" + (payout ? " win" : "") + (mult >= 5 ? " big" : "");
        ch.history.unshift({ a, b, even }); ch.history = ch.history.slice(0, 12); renderHist();
        refreshMoney(body); S.busy = false;
      }, 500);
    }, 1600);
  };
}


// ======================= CHINCHIRO =======================
const cc = { history: [] };
const sleep11 = ms => new Promise(r => setTimeout(r, window.__mjFast ? 5 : ms));
function ccRole(d) {
  const s = [...d].sort((a, b) => a - b);
  if (s[0] === s[2]) return s[0] === 1 ? { k: "pin", name: "ピンゾロ", mult: 5 } : { k: "zoro", name: `${s[0]}のゾロ目（アラシ）`, mult: 3 };
  if (s[0] === 4 && s[1] === 5 && s[2] === 6) return { k: "456", name: "シゴロ", mult: 2 };
  if (s[0] === 1 && s[1] === 2 && s[2] === 3) return { k: "123", name: "ヒフミ", mult: -2 };
  if (s[0] === s[1]) return { k: "me", name: `${s[2]}の目`, n: s[2] };
  if (s[1] === s[2]) return { k: "me", name: `${s[0]}の目`, n: s[0] };
  return null;
}
const CC_PIPS = { 1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8], 6: [0, 2, 3, 5, 6, 8] };
const ccDie = n => `<div class="die11${n === 1 ? " one" : ""}">${Array.from({ length: 9 }, (_, i) => `<i${CC_PIPS[n].includes(i) ? ' class="on"' : ""}></i>`).join("")}</div>`;
function setupChinchiro(cb, body) {
  cb.innerHTML = `<div class="ccbox"><div class="bowl11" id="ccBowl"></div><div class="dealer" id="ccDl">「さあ、張った張った。親はこの銀次だ」</div></div>
    <div class="ccrow"><span>親（銀次）</span><b id="ccD">―</b><span>あなた</span><b id="ccP">―</b></div>
    <div class="result" id="res">3つのサイコロを丼に振って、出た「役」で親と勝負</div>
    ${betRow("bet", "賭け金", Math.max(1, Math.min(S.lastBet || 100, Math.floor(K.world.money / 5) || 1)))}
    <p class="mtext small" id="ccMax"></p>
    <div class="row2"><button class="btn" type="button" id="ccGo">勝負！</button><button class="btn" type="button" id="ccRoll" hidden>サイコロを振る</button><button class="chip sechip" type="button" id="seBtn" aria-pressed="${SFX.on}">効果音 ${SFX.on ? "ON" : "OFF"}</button></div>
    <div class="hist" id="ccHist"></div>
    <details class="paytable"><summary>役と配当</summary><p class="mtext">親が先に振り、役が出るまで3回まで振れます。<br>
      <b>ピンゾロ</b>（1・1・1）5倍／<b>ゾロ目</b>（同じ数字3つ）3倍／<b>シゴロ</b>（4・5・6）2倍／<b>目</b>（2つ同じ＋1つ。残った1つの数字が強さ）1倍／<b>ヒフミ</b>（1・2・3）は2倍払い／3回振って役なしの<b>目なし</b>と、丼から飛び出す<b>ションベン</b>は負け。<br>
      親がピンゾロ・ゾロ目・シゴロならその倍率で払い、ヒフミなら2倍もらえ、目なしなら1倍もらえます。親が目のときは、あなたが振って目の強さで勝負（同じなら引き分け）。<br>
      負けると賭け金の最大5倍を払うことがあるので、賭けられるのは所持金の5分の1までです。</p></details>`;
  wireBets(body); warnIfBroke(cb);
  const bowl = cb.querySelector("#ccBowl"), dl = cb.querySelector("#ccDl"), res = cb.querySelector("#res");
  const se = cb.querySelector("#seBtn"); se.onclick = () => { SFX.setOn(!SFX.on); se.setAttribute("aria-pressed", String(SFX.on)); se.textContent = `効果音 ${SFX.on ? "ON" : "OFF"}`; };
  const maxBet = () => Math.floor(K.world.money / 5);
  const updMax = () => cb.querySelector("#ccMax").textContent = `賭けられるのは ${fmt(maxBet())} まで（所持金の5分の1）`;
  updMax();
  const place = (dice, out) => {
    bowl.innerHTML = dice.map((n, i) => { const a = (i / 3) * Math.PI * 2 + Math.random() * 1.2, r = 18 + Math.random() * 14; const x = out === i ? 118 : 50 + Math.cos(a) * r, y = out === i ? -6 : 50 + Math.sin(a) * r * .55;
      return `<div class="dpos" style="left:${x}%;top:${y}%;transform:translate(-50%,-50%) rotate(${Math.random() * 60 - 30}deg)">${ccDie(n)}</div>`; }).join("");
  };
  place([1, 2, 3]);
  const renderHist = () => cb.querySelector("#ccHist").innerHTML = cc.history.map(h => `<span class="${h.m > 0 ? "even" : h.m < 0 ? "odd" : ""}">${esc(h.p)}<b>${h.m > 0 ? "+" : ""}${h.m}倍</b></span>`).join("");
  renderHist();
  async function roll(who) {
    const out = Math.random() < .04 ? rnd(3) : -1;
    SFX.diceRoll(); bowl.classList.add("rolling");
    for (let i = 0; i < 12; i++) { place([rnd(6) + 1, rnd(6) + 1, rnd(6) + 1]); await sleep11(80); }
    bowl.classList.remove("rolling");
    const d = [rnd(6) + 1, rnd(6) + 1, rnd(6) + 1];
    if (out >= 0) { place(d, out); SFX.diceOut(); return { k: "out", name: "ションベン（丼から出た）" }; }
    place(d); return ccRole(d) || { k: "none", name: `${d.join("・")}（役なし）`, dice: d };
  }
  async function turn(who, label) {
    let r;
    for (let i = 1; i <= 3; i++) {
      dl.textContent = who === "dealer" ? `「親、${i}投目……」` : `あなたの${i}投目`;
      r = await roll(who); cb.querySelector(who === "dealer" ? "#ccD" : "#ccP").textContent = r.name;
      if (r.k !== "none") break;
      if (who === "player" && i < 3) { await waitClick(3 - i); } else await sleep11(500);
    }
    if (r.k === "none") r = { k: "none", name: "目なし" };
    cb.querySelector(who === "dealer" ? "#ccD" : "#ccP").textContent = r.name;
    return r;
  }
  const rollBtn = cb.querySelector("#ccRoll");
  const waitClick = left => new Promise(ok => { rollBtn.hidden = false; rollBtn.textContent = `サイコロを振る（あと${left}回）`; rollBtn.onclick = () => { rollBtn.hidden = true; ok(); }; });
  cb.querySelector("#ccGo").onclick = async () => {
    if (S.busy) return;
    const bet = readBet(cb);
    if (bet <= 0 || bet > maxBet()) { res.textContent = `賭け金は ${fmt(maxBet())} までにしてね`; res.className = "result"; return; }
    if (K.betAllowed && !K.betAllowed(bet * 5)) { res.textContent = K.betMsg(); res.className = "result"; return; }
    S.lastBet = bet; S.busy = true; K.addMoney(-bet); refreshMoney(body); res.className = "result"; res.textContent = "……";
    cb.querySelector("#ccD").textContent = "―"; cb.querySelector("#ccP").textContent = "―"; cb.querySelector("#ccGo").hidden = true;
    const d = await turn("dealer");
    let m, why;
    if (d.k === "pin" || d.k === "zoro" || d.k === "456") { m = -d.mult; why = `親が${d.name}`; }
    else if (d.k === "123") { m = 2; why = "親がヒフミ"; }
    else if (d.k === "none" || d.k === "out") { m = 1; why = `親が${d.name}`; }
    else {
      dl.textContent = `「${d.name}だ。さあ、振ってみな」`;
      await waitClick(3);
      const p = await turn("player");
      if (p.k === "pin" || p.k === "zoro" || p.k === "456") { m = p.mult; why = `あなたの${p.name}`; }
      else if (p.k === "123") { m = -2; why = "あなたがヒフミ"; }
      else if (p.k === "none" || p.k === "out") { m = -1; why = `あなたが${p.name}`; }
      else { m = p.n > d.n ? 1 : p.n < d.n ? -1 : 0; why = `${p.name} 対 ${d.name}`; }
    }
    let staked = bet, payout = 0;
    if (m > 0) payout = bet * (1 + m); else if (m === 0) payout = bet; else { const extra = bet * (-m - 1); if (extra) K.addMoney(-extra); staked = bet + extra; }
    K.gambleResult(staked, payout, true);
    const net = payout - staked;
    res.innerHTML = `${esc(why)}。${m > 0 ? `${m}倍の勝ち！ +${fmt(net)}` : m < 0 ? `${-m}倍の負け… ${fmt(net)}` : "引き分け。賭け金はそのまま戻る"}`;
    res.className = "result" + (m > 0 ? " win" : "") + (m >= 3 ? " big" : "");
    dl.textContent = m > 0 ? "「ちっ、持ってけ」" : m < 0 ? "「いただきだ」" : "「仕切り直しだな」";
    setTimeout(() => { if (m >= 3) SFX.big(); else if (m > 0) SFX.win(4 + m * 2); else if (m < 0) SFX.miss(); }, 150);
    cc.history.unshift({ p: why, m }); cc.history = cc.history.slice(0, 10); renderHist();
    refreshMoney(body); updMax(); S.busy = false; cb.querySelector("#ccGo").hidden = false; rollBtn.hidden = true;
  };
}

// ======================= DONGURI BLACKJACK =======================
const SUITS = [["acorn", "どんぐり"], ["leaf", "はっぱ"], ["mush", "きのこ"], ["flower", "おはな"]];
const RANKS = ["A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"];
const bjS = { shoe: [], hand: null };
function newShoe() { const s = []; for (let d = 0; d < 6; d++) for (const [su] of SUITS) for (const r of RANKS) s.push({ r, su }); for (let i = s.length - 1; i > 0; i--) { const j = rnd(i + 1); [s[i], s[j]] = [s[j], s[i]]; } return s; }
const cval = c => c.r === "A" ? 11 : ["J", "Q", "K", "10"].includes(c.r) ? 10 : +c.r;
function hv(cards) { let v = 0, a = 0; for (const c of cards) { v += cval(c); if (c.r === "A") a++; } while (v > 21 && a) { v -= 10; a--; } return { v, soft: a > 0 }; }
const isBJ = cards => cards.length === 2 && hv(cards).v === 21;
function setupBJ(cb, body) {
  if (bjS.shoe.length < 78) { bjS.shoe = newShoe(); bjS.shuffled = true; }
  const draw = () => bjS.shoe.pop();
  const cardHtml = (c, hide) => hide ? `<div class="card back"></div>` :
    `<div class="card s-${c.su}"><span class="cr">${c.r}</span><img src="${K.ASSETS["suit_" + c.su]}" alt="${SUITS.find(s => s[0] === c.su)[1]}"></div>`;
  let g = null;
  function render(msg, cls = "") {
    const dealerHidden = g && g.phase !== "done" && g.phase !== "dealer";
    cb.innerHTML = `<div class="bjtable">
      <div class="hand-l">ディーラー（リスのクルミさん）${g && !dealerHidden ? "　" + hv(g.dealer).v : ""}</div>
      <div class="cards">${g ? g.dealer.map((c, i) => cardHtml(c, i === 1 && dealerHidden)).join("") : ""}</div>
      ${g ? g.hands.map((h, i) => `<div class="hand-l">${esc(K.myName())}${g.hands.length > 1 ? ` ハンド${i + 1}` : ""}　${hv(h.cards).v}${h.doubled ? "（ダブル）" : ""}${h.result ? "　" + h.result : ""}${g.phase === "play" && i === g.cur ? " ◀" : ""}</div>
        <div class="cards">${h.cards.map(c => cardHtml(c)).join("")}</div>`).join("") : `<div class="hand-l">${esc(K.myName())}</div><div class="cards"></div>`}
      <div class="shoe">シュー残り ${bjS.shoe.length}枚${bjS.shuffled ? "（シャッフルしました）" : ""}</div></div>
      <div class="result ${cls}" id="res">${msg || "21に近い方が勝ち。ブラックジャックは配当2.5倍（3:2）"}</div><div id="ctl"></div>
      <details class="paytable"><summary>ルール</summary><p class="mtext">6デッキ。ディーラーは17以上で止まります。最初の2枚ならダブル、同じ数字ならスプリット（1回まで）。ディーラーの表がAなら保険（賭け金の半分、当たれば2:1）。サイド賭け「どんぐりペア」は最初の2枚が同じ数字で11倍、同じ数字かつ同じ模様で20倍。</p></details>`;
    bjS.shuffled = false;
    const ctl = cb.querySelector("#ctl");
    if (!g || g.phase === "done") {
      ctl.innerHTML = betRow("bet", "賭け金") + betRow("side", "どんぐりペア", 0) + `<div class="row2"><button class="btn" type="button" id="deal">配る</button></div>`;
      wireBets(body); warnIfBroke(cb);
      ctl.querySelector("#deal").onclick = deal;
    } else if (g.phase === "ins") {
      ctl.innerHTML = `<p class="mtext">ディーラーの表はA。保険をかけますか？（${fmt(Math.floor(g.hands[0].bet / 2))}）</p><div class="row2"><button class="btn" type="button" id="insY" ${K.world.money < Math.floor(g.hands[0].bet / 2) ? "disabled" : ""}>かける</button><button class="btn light" type="button" id="insN">かけない</button></div>`;
      ctl.querySelector("#insY").onclick = () => { g.ins = Math.floor(g.hands[0].bet / 2); K.addMoney(-g.ins); refreshMoney(body); peek(); };
      ctl.querySelector("#insN").onclick = peek;
    } else if (g.phase === "play") {
      const h = g.hands[g.cur];
      const canDouble = h.cards.length === 2 && K.world.money >= h.bet && !h.splitAce;
      const canSplit = h.cards.length === 2 && g.hands.length === 1 && h.cards[0].r === h.cards[1].r && K.world.money >= h.bet;
      ctl.innerHTML = `<div class="row2"><button class="btn" type="button" id="hit">ヒット</button><button class="btn light" type="button" id="stand">スタンド</button>
        ${canDouble ? `<button class="btn light" type="button" id="dbl">ダブル</button>` : ""}${canSplit ? `<button class="btn light" type="button" id="spl">スプリット</button>` : ""}</div>`;
      ctl.querySelector("#hit").onclick = () => { h.cards.push(draw()); if (hv(h.cards).v >= 21) nextHand(); else render(`${hv(h.cards).v}`); };
      ctl.querySelector("#stand").onclick = nextHand;
      const d = ctl.querySelector("#dbl"); if (d) d.onclick = () => { K.addMoney(-h.bet); h.bet *= 2; h.doubled = true; h.cards.push(draw()); refreshMoney(body); nextHand(); };
      const s = ctl.querySelector("#spl"); if (s) s.onclick = () => {
        K.addMoney(-h.bet); refreshMoney(body);
        const ace = h.cards[0].r === "A";
        g.hands = [{ cards: [h.cards[0], draw()], bet: h.bet, splitAce: ace }, { cards: [h.cards[1], draw()], bet: h.bet, splitAce: ace }];
        g.split = true;
        if (ace) { g.cur = 1; nextHand(); } else render("スプリットしました");
      };
    }
  }
  function deal() {
    const bet = readBet(cb, "bet"), side = Math.max(0, readBet(cb, "side", 0));
    if (bet <= 0 || bet + side > K.world.money) { cb.querySelector("#res").textContent = "賭け金を確かめてね"; return; }
    if (K.betAllowed && !K.betAllowed(bet + side)) { cb.querySelector("#res").textContent = K.betMsg(); return; }
    S.lastBet = bet; S.busy = true;
    if (bjS.shoe.length < 78) { bjS.shoe = newShoe(); bjS.shuffled = true; }
    K.addMoney(-(bet + side)); refreshMoney(body);
    g = { hands: [{ cards: [draw(), draw()], bet }], dealer: [draw(), draw()], cur: 0, phase: "play", ins: 0, side, bet0: bet + side, extra: 0, sideWin: 0 };
    const p = g.hands[0].cards;
    if (side) {
      if (p[0].r === p[1].r && p[0].su === p[1].su) g.sideWin = side * 20; else if (p[0].r === p[1].r) g.sideWin = side * 11;
    }
    if (g.dealer[0].r === "A") { g.phase = "ins"; render(g.sideWin ? `どんぐりペア！ +${fmt(g.sideWin)}` : ""); return; }
    peek();
  }
  function peek() {
    const up = cval(g.dealer[0]);
    if ((up === 11 || up === 10) && isBJ(g.dealer)) { finish(true); return; }
    if (g.ins) { render("ディーラーはブラックジャックではありません。保険は没収"); }
    if (isBJ(g.hands[0].cards)) { finish(); return; }
    g.phase = "play"; render(g.sideWin ? `どんぐりペア！ +${fmt(g.sideWin)}` : `${hv(g.hands[0].cards).v}`);
  }
  function nextHand() {
    if (g.cur < g.hands.length - 1) { g.cur++; if (g.hands[g.cur].splitAce) { nextHand(); return; } render(`ハンド${g.cur + 1}`); return; }
    finish();
  }
  function finish(dealerBJ) {
    g.phase = "dealer";
    const allBust = g.hands.every(h => hv(h.cards).v > 21);
    if (!dealerBJ && !allBust && !(g.hands.length === 1 && isBJ(g.hands[0].cards) && !g.split)) while (hv(g.dealer).v < 17) g.dealer.push(draw());
    const dv = hv(g.dealer).v, dBJ = isBJ(g.dealer);
    let payout = g.sideWin;
    if (g.ins && dBJ) payout += g.ins * 3;
    for (const h of g.hands) {
      const v = hv(h.cards).v, bj = isBJ(h.cards) && !g.split;
      if (v > 21) h.result = "バースト";
      else if (bj && !dBJ) { h.result = "ブラックジャック！"; payout += Math.floor(h.bet * 2.5); }
      else if (dBJ && !bj) h.result = "負け";
      else if (dBJ && bj) { h.result = "引き分け"; payout += h.bet; }
      else if (dv > 21 || v > dv) { h.result = "勝ち"; payout += h.bet * 2; }
      else if (v === dv) { h.result = "引き分け"; payout += h.bet; }
      else h.result = "負け";
    }
    const staked = g.hands.reduce((a, h) => a + h.bet, 0) + g.side + g.ins;
    K.gambleResult(staked, payout, true);
    g.phase = "done"; S.busy = false; refreshMoney(body);
    const net = payout - staked; bjS.session = (bjS.session || 0) + net;
    const parts = g.hands.map((h, i) => `${g.hands.length > 1 ? `ハンド${i + 1}` : "あなた"}：${h.result}（賭け ${fmt(h.bet)}${h.doubled ? "・ダブル" : ""}）`);
    if (g.side) parts.push(`どんぐりペア：${g.sideWin ? "当たり" : "はずれ"}（賭け ${fmt(g.side)}）`);
    if (g.ins) parts.push(`保険：${dBJ ? "当たり" : "没収"}（${fmt(g.ins)}）`);
    render(`${dBJ ? "ディーラーはブラックジャック。" : `ディーラー ${dv}${dv > 21 ? "でバースト" : ""}。`}${net > 0 ? `+${fmt(net)}` : net < 0 ? `${fmt(net)}` : "プラスマイナスゼロ"}
      <span class="bjsum">${parts.join("<br>")}<br>賭けた合計 ${fmt(staked)} → 戻ってきた額 ${fmt(payout)}<br>この卓での収支 <b>${bjS.session >= 0 ? "+" : ""}${fmt(bjS.session)}</b></span>`, net > 0 ? "win" : "");
  }
  render();
}

// ======================= DONGURI BLACKJACK (みんなの卓) =======================
const bjM = { st: null, settle: null, deadline: 0, timer: 0, seated: false, cb: null, body: null, err: "" };
function setupBJChoice(cb, body) {
  if (!(K.net && K.net.connected())) { setupBJ(cb, body); return; }
  if (S.bjMode === "solo") { setupBJ(cb, body); return; }
  if (S.bjMode === "multi") { setupBJMulti(cb, body); return; }
  cb.innerHTML = `<div class="bjtable"><div class="hand-l">どんぐりBJの卓</div><p class="mtext" style="color:#e8f3dd">オンラインの友達と同じ卓で、ディーラーのクルミさんに挑めます（最大5人）。ひとりでじっくり遊ぶこともできるよ。</p></div>
    <div class="row2"><button class="btn" type="button" id="bjMulti">みんなの卓に行く</button><button class="btn light" type="button" id="bjSolo">ひとりで遊ぶ</button></div>`;
  cb.querySelector("#bjMulti").onclick = () => { S.bjMode = "multi"; setupBJMulti(cb, body); };
  cb.querySelector("#bjSolo").onclick = () => { S.bjMode = "solo"; setupBJ(cb, body); };
}
const cardOf = c => c < 0 ? null : { r: RANKS[c % 13], su: SUITS[Math.floor(c / 13) % 4][0] };
function setupBJMulti(cb, body) {
  bjM.cb = cb; bjM.body = body; bjM.err = "";
  K.net.onBj = m => {
    if (m.type === "bjState") { bjM.st = m; bjM.deadline = performance.now() + (m.left || 0); if (m.phase !== "result") bjM.settle = null; }
    else if (m.type === "bjSettle") bjM.settle = m;
    else if (m.type === "bjNg") bjM.err = String(m.text || "").slice(0, 60);
    else if (m.type === "bjCharge") { refreshMoney(body); return; }
    renderBJM();
  };
  K.net.send({ type: "bj", act: "look" });
  cb.innerHTML = `<p class="mtext">卓につないでいます…</p>`;
  clearInterval(bjM.timer);
  bjM.timer = setInterval(() => {
    if (!cb.isConnected) { clearInterval(bjM.timer); if (mySeat() && K.net.connected()) K.net.send({ type: "bj", act: "leave" }); K.net.onBj = null; return; }
    const t = cb.querySelector("#bjLeft"); if (t) t.textContent = Math.max(0, Math.ceil((bjM.deadline - performance.now()) / 1000));
  }, 250);
}
// いま、みんなの卓（BJ・麻雀）についているか。卓の中だけのチャットで使う
K.tableNow = () => {
  if (bjM.cb && bjM.cb.isConnected && bjM.st && K.net && K.net.connected() && mySeat()) return "bj";
  if (mjM.cb && mjM.cb.isConnected && mjM.st) return "mj";
  return null;
};
function mySeat() { const st = bjM.st; return st ? st.seats.find(s => s.id === K.net.myId()) : null; }
function renderBJM() {
  const cb = bjM.cb, body = bjM.body, st = bjM.st; if (!cb || !cb.isConnected || !st) return;
  refreshMoney(body);
  const me = mySeat(), myTurn = st.phase === "play" && st.turn === K.net.myId();
  const cardHtml = c => { const o = cardOf(c); return !o ? `<div class="card back"></div>` : `<div class="card s-${o.su}"><span class="cr">${o.r}</span><img src="${K.ASSETS["suit_" + o.su]}" alt=""></div>`; };
  const left = `<b id="bjLeft">${Math.max(0, Math.ceil((bjM.deadline - performance.now()) / 1000))}</b>秒`;
  const seats = st.seats.map(s => {
    const mine = s.id === K.net.myId(), turnHere = st.phase === "play" && st.turn === s.id;
    const hands = s.hands.length ? s.hands.map((h, i) => `<div class="hand-l">${s.hands.length > 1 ? `ハンド${i + 1}　` : ""}${h.total}${h.doubled ? "（ダブル）" : ""}${h.result ? "　" + esc(h.result) : ""}${turnHere && i === s.cur ? " ◀" : ""}</div><div class="cards">${h.cards.map(cardHtml).join("")}</div>`).join("")
      : `<div class="hand-l">${st.phase === "bet" ? (s.bet ? `${fmt(s.bet)} 賭けた` : "賭け金を考え中…") : "このラウンドは見学"}</div>`;
    return `<div class="bjseat${mine ? " me" : ""}${turnHere ? " turn" : ""}"><div class="bjname">${esc(s.name)}${mine ? "（あなた）" : ""}${s.bet ? `　<span>賭け ${fmt(s.bet)}</span>` : ""}${s.leaving ? "　<span>もうすぐ席を立つ</span>" : ""}</div>${hands}</div>`;
  }).join("");
  const dealerShown = st.dealer.length ? `<div class="cards">${st.dealer.map(cardHtml).join("")}</div>` : `<div class="cards"></div>`;
  let ctl = "", info = st.msg ? esc(st.msg) : "";
  if (!me) {
    ctl = st.seats.length >= 5 ? `<p class="mtext">満席です。空いたら座れるよ</p>` : `<div class="row2"><button class="btn" type="button" id="bjSit">座る</button><button class="btn light" type="button" id="bjBack">ひとりで遊ぶ</button></div>`;
  } else if (st.phase === "bet") {
    if (!me.bet) ctl = betRow("bet", "賭け金") + `<div class="row2"><button class="btn" type="button" id="bjBet">賭ける</button><button class="btn light" type="button" id="bjLeave">席を立つ</button></div>`;
    else ctl = `<p class="mtext">ほかの人を待っています${st.left ? `（あと ${left}で配ります）` : ""}</p><div class="row2"><button class="btn light" type="button" id="bjLeave">席を立つ（賭け金は戻る）</button></div>`;
  } else if (st.phase === "play") {
    if (myTurn) {
      const h = me.hands[me.cur];
      const canD = h && h.cards.length === 2 && K.world.money >= h.bet, canS = h && me.hands.length === 1 && h.cards.length === 2 && h.cards[0] % 13 === h.cards[1] % 13 && K.world.money >= h.bet;
      ctl = `<p class="mtext"><b>あなたの番！</b>（残り ${left}）</p><div class="row2"><button class="btn" type="button" data-bj="hit">ヒット</button><button class="btn light" type="button" data-bj="stand">スタンド</button>${canD ? `<button class="btn light" type="button" data-bj="double">ダブル</button>` : ""}${canS ? `<button class="btn light" type="button" data-bj="split">スプリット</button>` : ""}</div>`;
    } else {
      const cur = st.seats.find(s => s.id === st.turn);
      ctl = `<p class="mtext">${cur ? `${esc(cur.name)}の番（残り ${left}）` : "…"}</p>`;
    }
  } else if (st.phase === "result") {
    const s = bjM.settle;
    if (s && me.hands.length) {
      const net = s.payout - s.staked;
      info = `${s.dealerBJ ? "ディーラーはブラックジャック。" : `ディーラー ${s.dealer}${s.dealer > 21 ? "でバースト" : ""}。`}${net > 0 ? `+${fmt(net)}` : net < 0 ? fmt(net) : "プラスマイナスゼロ"}<span class="bjsum">${s.lines.map(esc).join("<br>")}<br>賭けた合計 ${fmt(s.staked)} → 戻ってきた額 ${fmt(s.payout)}</span>`;
    }
    ctl = `<p class="mtext">次のラウンドまで ${left}</p>`;
  }
  cb.innerHTML = `<div class="bjtable">
      <div class="hand-l">ディーラー（リスのクルミさん）${st.dealer.length ? "　" + st.dealerTotal : ""}</div>${dealerShown}
      <div class="bjseats">${seats || `<p class="mtext" style="color:#e8f3dd">まだだれも座っていません</p>`}</div>
      <div class="shoe">シュー残り ${st.shoe}枚・座っている人 ${st.seats.length}/5</div></div>
    <div class="result ${bjM.settle && st.phase === "result" && bjM.settle.payout > bjM.settle.staked ? "win" : ""}" id="res">${info || "21に近い方が勝ち。ブラックジャックは配当2.5倍（3:2）"}</div>
    ${bjM.err ? `<div class="warn">${esc(bjM.err)}</div>` : ""}<div id="ctl">${ctl}</div>
    <details class="paytable"><summary>みんなの卓のルール</summary><p class="mtext">6デッキ。ディーラーは17以上で止まります。最初の2枚ならダブル、同じ数字ならスプリット（1回まで）。座った全員が賭けるか、最初の人が賭けてから15秒で配ります。自分の番で25秒たつと自動でスタンド。途中で卓を閉じると、そのラウンドはスタンド扱いで、次から席を立ちます。保険とどんぐりペアは、ひとりの卓だけ。</p></details>`;
  const q = s => cb.querySelector(s);
  if (q("#bjSit")) q("#bjSit").onclick = () => { bjM.err = ""; K.net.send({ type: "bj", act: "sit" }); };
  if (q("#bjBack")) q("#bjBack").onclick = () => { S.bjMode = "solo"; clearInterval(bjM.timer); K.net.onBj = null; setupBJ(cb, body); };
  if (q("#bjLeave")) q("#bjLeave").onclick = () => K.net.send({ type: "bj", act: "leave" });
  if (q("#bjBet")) { wireBets(body); q("#bjBet").onclick = () => {
    const v = readBet(cb, "bet");
    if (v <= 0 || v > K.world.money) { bjM.err = "賭け金を確かめてね"; renderBJM(); return; }
    if (K.betAllowed && !K.betAllowed(v)) { bjM.err = K.betMsg(); renderBJM(); return; }
    S.lastBet = v; bjM.err = ""; bjM.settle = null; K.net.send({ type: "bj", act: "bet", amount: v });
  }; }
  cb.querySelectorAll("[data-bj]").forEach(b => b.onclick = () => { b.disabled = true; K.net.send({ type: "bj", act: b.dataset.bj }); });
}

// ======================= 競どんぐり =======================
// オンライン：サーバーが進行を決めて、みんなで同じレースを見る。オフライン：自分で「出走！」を押す
const RACE_COLORS = ["#e2404e", "#4a8ae0", "#f0c030", "#4aa860", "#a070e0", "#f08a3a"];
const RACE_NAMES = ["クヌギ", "コナラ", "マテバシイ", "スダジイ", "ミズナラ", "カシワ", "ウバメガシ", "アラカシ", "シラカシ", "クリ"];
const FORM_NAME = ["", "不調", "いまいち", "ふつう", "好調", "絶好調"];
const RC = { st: null, at: 0, local: null, ui: null, lastSettled: 0, tab: "win" };
const raceBets = () => { const w = K.world; if (!w.race41 || typeof w.race41 !== "object") w.race41 = { bets: [] }; if (!Array.isArray(w.race41.bets)) w.race41.bets = []; return w.race41; };
// 走り方：seed と着順・タイムから、だれの画面でも同じ動きになる
function mulberry(a) { return () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
function raceCurves(st) {
  if (st._curves && st._curves.seed === st.seed) return st._curves;
  const c = { seed: st.seed, r: st.times.map((T, i) => { const R = mulberry((st.seed | 0) + i * 7919); return { T, a: .12 + R() * .1, f: .5 + R() * 1, ph: R() * Math.PI * 2, b: R() * .05 }; }) };
  st._curves = c; return c;
}
function raceProg(st, i, t) {
  const k = raceCurves(st).r[i]; const u = Math.max(0, Math.min(1, t / k.T));
  return Math.max(0, Math.min(1, u + k.a * u * (1 - u) * Math.sin(Math.PI * 2 * k.f * u + k.ph)));
}
// オフラインのレース（サーバーの RaceGame と同じ決め方）
function localRace(id) {
  const pool = RACE_NAMES.slice(), runners = []; let total = 0;
  for (let i = 0; i < 6; i++) { const s = .72 + Math.random() * .56, w = Math.pow(s, 6); total += w; runners.push({ name: pool.splice(rnd(pool.length), 1)[0] + "号", color: RACE_COLORS[i], w, form: Math.max(1, Math.min(5, Math.floor((s - .72) / .56 * 5) + 1)), pool: 0 }); }
  for (const r of runners) { const p = r.w / total; let p2 = p; for (const o of runners) if (o !== r) p2 += (o.w / total) * r.w / (total - o.w); r.win = Math.max(1.3, Math.min(80, Math.round(.86 / p * 10) / 10)); r.place = Math.max(1.1, Math.min(20, Math.round(.86 / p2 * 10) / 10)); r.formName = FORM_NAME[r.form]; }
  return { type: "race", id, phase: "bet", ms: 0, runners, bets: [], hist: (RC.local && RC.local.hist) || [], local: true };
}
function localRun(st) {
  const left = [0, 1, 2, 3, 4, 5], order = [];
  while (left.length) { let x = Math.random() * left.reduce((a, i) => a + st.runners[i].w, 0), pick = left[left.length - 1]; for (const i of left) { x -= st.runners[i].w; if (x < 0) { pick = i; break; } } order.push(pick); left.splice(left.indexOf(pick), 1); }
  const times = []; let t = 15000 + rnd(3000); for (const i of order) { times[i] = t; t += 200 + rnd(1100); }
  Object.assign(st, { phase: "run", order, times, seed: rnd(1e9), elapsed: 0, ms: t + 1200 }); RC.at = performance.now(); delete st._curves;
}
const payoutOf = (b, order) => { const hit = b.kind === "win" ? order[0] === b.runner : order[0] === b.runner || order[1] === b.runner; return hit ? Math.floor(b.amount * b.odds) : 0; };
// 結果が出たら、自分の賭けを精算する（カジノにいなくても）
function settleRace(id, order, local) {
  const R = raceBets(), mine = R.bets.filter(b => b.id === id && !!b.local === !!local);
  if (!mine.length) return null;
  R.bets = R.bets.filter(b => !mine.includes(b));
  const staked = mine.reduce((a, b) => a + b.amount, 0), paid = mine.reduce((a, b) => a + payoutOf(b, order), 0);
  K.gambleResult(staked, paid);
  R.stats = R.stats || { races: 0, best: 0 }; R.stats.races++; if (paid - staked > R.stats.best) R.stats.best = paid - staked;
  if (mine.some(b => b.kind === "win" && b.odds >= 20 && payoutOf(b, order) > 0)) R.stats.longshot = true;
  if (paid > 0 && K.news41) { const st = RC.st && RC.st.id === id ? RC.st : null, nm = st ? st.runners[order[0]].name : ""; if (paid >= 3000 || paid >= staked * 8) K.news41.add("race", `競どんぐりで${nm ? nm + "に賭けて" : ""}${fmt(paid)}の払い戻し`, Math.min(90, 30 + Math.round(paid / 1000))); }
  K.saveWorld();
  return { staked, paid };
}
function settleOld(results) {
  const R = raceBets(); if (!R.bets.length) return;
  for (const r of results || []) if (r && Array.isArray(r.order) && r.order.length >= 2) { const res = settleRace(r.id, r.order, false); if (res && res.paid) K.toast("coin", `留守のあいだの競どんぐり：払い戻し +${fmt(res.paid)}`); }
  // 10分たっても結果がわからない賭け（サーバーが入れかわったなど）は返す
  const now = Date.now(), old = R.bets.filter(b => !b.local && now - (b.t || 0) > 10 * 60e3);
  if (old.length) { const back = old.reduce((a, b) => a + b.amount, 0); R.bets = R.bets.filter(b => !old.includes(b)); K.addMoney(back); K.toast("coin", `取り消しになった競どんぐりの賭け金 ${fmt(back)} を返したよ`); K.saveWorld(); }
}
K.raceIn = m => {
  if (m.type === "race") {
    const prev = RC.st; RC.st = m; RC.at = performance.now();
    if (m.phase === "result" && Array.isArray(m.order) && (!prev || prev.id !== m.id || prev.phase !== "result")) {
      const res = settleRace(m.id, m.order, false);
      if (res && !(RC.ui && RC.ui.cb.isConnected)) K.toast("coin", res.paid ? `競どんぐり：${m.runners[m.order[0]].name}が1着！ 払い戻し +${fmt(res.paid)}` : `競どんぐり：${m.runners[m.order[0]].name}が1着。はずれ…`);
      if (res) RC.lastRes = { id: m.id, ...res };
    }
  } else if (m.type === "raceOk") {
    const R = raceBets(); K.addMoney(-m.amount); R.bets.push({ id: m.id, runner: m.runner, kind: m.kind, amount: m.amount, odds: m.odds, t: Date.now() }); K.saveWorld();
    if (RC.ui) { RC.ui.msg = `${m.runner + 1}番に${m.kind === "win" ? "単勝" : "複勝"} ${fmt(m.amount)}（${m.odds}倍）賭けた`; RC.ui.dirty = true; }
  } else if (m.type === "raceNg") { if (RC.ui) { RC.ui.msg = String(m.text || "").slice(0, 60); RC.ui.dirty = true; } }
  else if (m.type === "raceWelcome") { if (m.race) { RC.st = m.race; RC.at = performance.now(); } settleOld(m.raceResults); }
  if (RC.ui) RC.ui.dirty = true;
};
const raceOnline = () => K.net && K.net.connected();
function curRace() { if (raceOnline() && RC.st) return RC.st; if (!RC.local) RC.local = localRace(1); return RC.local; }
const msLeft = st => Math.max(0, (st.ms || 0) - (performance.now() - RC.at));
function setupRace(cb, body) {
  if (raceOnline()) K.net.send({ type: "race", act: "look" });
  RC.ui = { cb, body, msg: "", dirty: true, sig: "" };
  cb.innerHTML = `<div class="race41">
    <canvas id="raceCv" width="480" height="210"></canvas>
    <div class="race-cap" id="raceCap"></div>
    <div id="raceBody"></div>
    <details class="paytable"><summary>競どんぐりのあそびかた</summary><p class="mtext small">6粒のどんぐりが走るレースに賭けます。<b>単勝</b>は1着を当てる賭け、<b>複勝</b>は2着までに入るかを当てる賭けで、当たると「賭け金×オッズ」がもどってきます。オッズはレースごとに決まっていて、調子がいいどんぐりほど低め（当たりやすいけど配当は少なめ）。${raceOnline() ? "オンラインでは、みんなで同じレースを見ます。受付は約50秒、1レースに12回まで賭けられます。カジノを出ていても、結果が出たら自動で精算されます。" : "ひとりのときは、賭けたあと「出走！」を押すとスタートします。"}</p></details></div>`;
  const cv = cb.querySelector("#raceCv"), ctx = cv.getContext("2d"); ctx.imageSmoothingEnabled = false;
  let lastLeader = -1, comment = "";
  function draw() {
    if (!cb.isConnected) {
      RC.ui = null;
      // ひとりのレースの途中で閉じたら：走っていれば結果で精算、まだなら賭け金を返す
      const L = RC.local; if (L) { const loc = raceBets().bets.filter(b => b.local && b.id === L.id);
        if (loc.length && L.order) { settleRace(L.id, L.order, true); L.phase = "result"; }
        else if (loc.length) { const back = loc.reduce((a, b) => a + b.amount, 0); raceBets().bets = raceBets().bets.filter(b => !loc.includes(b)); K.addMoney(back); K.saveWorld(); } }
      return;
    }
    const st = curRace(), now = performance.now();
    // 背景：芝と土のコース
    ctx.fillStyle = "#5aa850"; ctx.fillRect(0, 0, 480, 210);
    ctx.fillStyle = "#c89a62"; ctx.fillRect(0, 18, 480, 180);
    for (let i = 0; i <= 6; i++) { ctx.fillStyle = "rgba(255,255,255,.55)"; ctx.fillRect(0, 18 + i * 30, 480, 1); }
    ctx.fillStyle = "#fff"; for (let y = 18; y < 198; y += 6) { ctx.fillRect(36, y, 2, 3); }
    for (let y = 18; y < 198; y += 6) for (let k = 0; k < 2; k++) { ctx.fillStyle = (Math.floor(y / 6) + k) % 2 ? "#222" : "#fff"; ctx.fillRect(446 + k * 3, y, 3, 6); }
    ctx.fillStyle = "#3a7a3a"; for (let x = 0; x < 480; x += 24) { ctx.fillRect(x + 4, 6, 6, 6); ctx.fillRect(x + 14, 202, 6, 5); }
    const running = st.phase === "run" || st.phase === "result";
    const t = running ? (st.local ? now - RC.at : (st.elapsed || 0) + (now - RC.at)) : 0;
    let leader = -1, best = -1;
    st.runners.forEach((r, i) => {
      const p = running && st.times ? raceProg(st, i, t) : 0;
      if (p > best) { best = p; leader = i; }
      const x = 40 + p * 400, y = 24 + i * 30, moving = running && p < 1, bob = moving ? Math.abs(Math.sin(now / 70 + i)) * 2 : 0;
      acorn(ctx, Math.round(x - 12), Math.round(y + 2 - bob), r.color || RACE_COLORS[i], i + 1, moving, now + i * 40);
      if (st.phase === "result" && st.order) { const pl = st.order.indexOf(i); if (pl >= 0 && pl < 3) { ctx.fillStyle = ["#f0c030", "#c8ccd8", "#d8905a"][pl]; ctx.fillRect(Math.round(x + 12), y + 6, 14, 12); ctx.fillStyle = "#222"; ctx.font = "10px sans-serif"; ctx.fillText(pl + 1, Math.round(x + 16), y + 16); } }
    });
    // 実況
    if (st.phase === "bet") comment = st.local ? "賭けたら「出走！」を押してね" : `受付中　あと ${Math.ceil(msLeft(st) / 1000)} 秒`;
    else if (st.phase === "run") {
      const done = st.times && st.order && t >= st.times[st.order[0]];
      if (done) comment = `ゴール！ 1着は ${st.order[0] + 1}番 ${st.runners[st.order[0]].name}！`;
      else if (t < 1200) comment = "各どんぐり、いっせいにスタート！";
      else if (leader !== lastLeader && leader >= 0) comment = `${leader + 1}番 ${st.runners[leader].name}が先頭に！`;
      else if (best > .8 && !/最後/.test(comment)) comment = `最後の直線！ ${leader + 1}番 ${st.runners[leader].name}、逃げきれるか！`;
      lastLeader = leader;
      if (st.local && st.times && t > Math.max(...st.times) + 1200) { st.phase = "result"; const res = settleRace(st.id, st.order, true); if (res) RC.lastRes = { id: st.id, ...res }; RC.local.hist = [{ id: st.id, names: st.order.map(i => st.runners[i].name), winOdds: st.runners[st.order[0]].win }, ...(RC.local.hist || [])].slice(0, 6); st.hist = RC.local.hist; RC.ui.dirty = true; }
    } else if (st.phase === "result" && st.order) comment = `1着 ${st.order[0] + 1}番 ${st.runners[st.order[0]].name}（単勝 ${st.runners[st.order[0]].win}倍）　2着 ${st.order[1] + 1}番 ${st.runners[st.order[1]].name}`;
    const cap = cb.querySelector("#raceCap"); if (cap && cap.textContent !== comment) cap.textContent = comment;
    const sig = [st.id, st.phase, (st.bets || []).length, raceBets().bets.length, RC.ui && RC.ui.msg, K.world.money, st.phase === "bet" && !st.local ? Math.ceil(msLeft(st) / 1000) > 0 : 0].join("|");
    if (RC.ui && (RC.ui.dirty || RC.ui.sig !== sig)) { RC.ui.dirty = false; RC.ui.sig = sig; renderRaceBody(cb, body, st); }
    requestAnimationFrame(draw);
  }
  requestAnimationFrame(draw);
}
function acorn(ctx, x, y, col, n, moving, now) {
  const P = (px, py, w, h, c) => { ctx.fillStyle = c; ctx.fillRect(x + px, y + py, w, h); };
  // 足
  const lp = moving ? Math.floor(now / 90) % 2 : 0;
  P(7 + lp * 2, 21, 2, 4, "#5a3a22"); P(14 - lp * 2, 21, 2, 4, "#5a3a22");
  // 実
  P(5, 9, 14, 11, "#b8742e"); P(6, 20, 12, 2, "#9a5e24"); P(7, 22, 10, 1, "#7a4a1c"); P(6, 10, 3, 6, "#d89a52");
  // 帽子
  P(3, 4, 18, 6, "#6a4626"); P(4, 3, 16, 1, "#7e5632"); for (let i = 5; i < 20; i += 3) P(i, 5, 1, 4, "#52341c"); P(11, 0, 2, 3, "#52341c");
  // 目
  P(9, 12, 2, 2, "#2a1a12"); P(14, 12, 2, 2, "#2a1a12");
  // ゼッケン
  P(7, 15, 10, 6, col); ctx.fillStyle = "#fff"; ctx.font = "bold 7px sans-serif"; ctx.fillText(n, x + 10, y + 21);
}
function renderRaceBody(cb, body, st) {
  const el = cb.querySelector("#raceBody"); if (!el) return;
  refreshMoney(body);
  const keepBet = el.querySelector("#rbet")?.value;
  const R = raceBets(), mine = R.bets.filter(b => b.id === st.id && !!b.local === !!st.local);
  const open = st.phase === "bet" && (st.local || msLeft(st) > 500);
  const rows = st.runners.map((r, i) => `<div class="rrow"><span class="rnum" style="background:${r.color}">${i + 1}</span><span class="rname">${esc(r.name)}<small>調子：${esc(r.formName || FORM_NAME[r.form] || "")}${st.local ? "" : `・みんなの賭け ${fmt(r.pool || 0)}`}</small></span>
      <span class="rodds">単勝 <b>${r.win}</b>倍<br>複勝 <b>${r.place}</b>倍</span>
      <span class="racts">${open ? `<button class="btn" type="button" data-rb="${i}:win">単勝</button><button class="btn light" type="button" data-rb="${i}:place">複勝</button>` : ""}</span></div>`).join("");
  const myList = mine.length ? `<div class="mybets"><b>あなたの賭け</b>（合計 ${fmt(mine.reduce((a, b) => a + b.amount, 0))}）：${mine.map(b => `${b.runner + 1}番${b.kind === "win" ? "単勝" : "複勝"} ${fmt(b.amount)}`).join("・")}</div>` : "";
  const last = RC.lastRes && (RC.lastRes.id === st.id || st.phase === "bet") ? `<div class="result ${RC.lastRes.paid ? "win" : ""}">${RC.lastRes.paid ? `当たり！ 払い戻し +${fmt(RC.lastRes.paid)}` : "はずれ…"}</div>` : "";
  const others = !st.local && (st.bets || []).length ? `<p class="mtext small">みんなの賭け：${st.bets.slice(-6).map(b => `${esc(b.name)}が${b.runner + 1}番に${fmt(b.amount)}`).join("、")}</p>` : "";
  const hist = (st.hist || []).length ? `<p class="mtext small">これまでの1着：${st.hist.slice(0, 6).map(h => `${esc((h.names || [])[0] || "")}（${h.winOdds}倍）`).join("・")}</p>` : "";
  el.innerHTML = `<p class="mtext small">レース ${st.id}　${st.phase === "bet" ? "受付中" : st.phase === "run" ? "レース中" : "結果"}</p>
    ${open ? betRow("rbet", "賭け金", Math.max(10, Math.min(S.lastBet || 100, K.world.money || 10))) : ""}
    <div class="rlist">${rows}</div>${myList}${RC.ui && RC.ui.msg ? `<p class="mtext small">${esc(RC.ui.msg)}</p>` : ""}${last}${others}${hist}
    ${st.local ? `<div class="row2">${st.phase === "bet" ? `<button class="btn" type="button" id="rGo">出走！</button>` : st.phase === "result" ? `<button class="btn" type="button" id="rNext">次のレースへ</button>` : ""}</div>` : ""}`;
  wireBets(el);
  if (keepBet && el.querySelector("#rbet")) el.querySelector("#rbet").value = keepBet;
  el.querySelectorAll("[data-rb]").forEach(b => b.onclick = () => {
    const [i, kind] = b.dataset.rb.split(":"), amt = readBet(el, "rbet", 10), r = st.runners[+i];
    if (amt < 10 || amt > 50000) { RC.ui.msg = "賭け金は10〜50,000Gだよ"; RC.ui.dirty = true; return; }
    if (amt > K.world.money) { RC.ui.msg = "所持金が足りないよ"; RC.ui.dirty = true; return; }
    if (K.betAllowed && !K.betAllowed(amt)) { RC.ui.msg = K.betMsg(); RC.ui.dirty = true; return; }
    if (mine.length >= 12) { RC.ui.msg = "1レースに賭けられるのは12回までだよ"; RC.ui.dirty = true; return; }
    S.lastBet = amt;
    if (st.local) { K.addMoney(-amt); R.bets.push({ id: st.id, runner: +i, kind, amount: amt, odds: kind === "win" ? r.win : r.place, local: true, t: Date.now() }); K.saveWorld(); RC.ui.msg = `${+i + 1}番に${kind === "win" ? "単勝" : "複勝"} ${fmt(amt)} 賭けた`; RC.ui.dirty = true; }
    else K.net.send({ type: "race", act: "bet", runner: +i, kind, amount: amt });
  });
  const go = el.querySelector("#rGo"); if (go) go.onclick = () => { if (st.phase !== "bet") return; localRun(st); RC.ui.dirty = true; if (typeof SFX !== "undefined" && SFX.lever) SFX.lever(); };
  const nx = el.querySelector("#rNext"); if (nx) nx.onclick = () => { RC.local = localRace(st.id + 1); RC.lastRes = null; RC.ui.msg = ""; RC.ui.dirty = true; };
}
// ひとりのときの賭けが残ったまま（レース中に閉じた・読みこみ直した）なら返す
setTimeout(() => { try { const R = raceBets(), loc = R.bets.filter(b => b.local); if (loc.length) { const back = loc.reduce((a, b) => a + b.amount, 0); R.bets = R.bets.filter(b => !b.local); K.addMoney(back); K.saveWorld(); } } catch {} }, 1500);

// ======================= MAHJONG =======================
const RATES = [[10, "点1（1000点=10G）"], [50, "点5（1000点=50G）"], [100, "テンピン（1000点=100G）"], [500, "デカピン（1000点=500G）"]];
const UMA = [20, 10, -10, -20];
const CPU = ["タツ", "ミドリ", "ゲン"];
let mj = null;
function tileHtml(t, cls = "", attrs = "") {
  let inner, k;
  if (t < 9) { inner = `<b>${MJ.KANJI[t]}</b><i>萬</i>`; k = "m"; }
  else if (t < 18) { inner = `<b>${MJ.CIRC[t - 9]}</b>`; k = "p"; }
  else if (t < 27) { inner = `<b>${t - 17}</b><i>索</i>`; k = "s"; }
  else { inner = `<b>${MJ.HONOR[t - 27]}</b>`; k = ["z", "z", "z", "z", "zw", "zg", "zr"][t - 27]; }
  return `<span class="tl ${k} ${cls}" ${attrs}>${inner}</span>`;
}
function setupMahjongLobby(cb, body) {
  if (mj && mj.alive) { renderMJ(); return; }
  if (K.net && K.net.connected() && S.mjMode !== "solo") { if (S.mjMode === "multi") setupMJMulti(cb, body); else mjChoice(cb, body); return; }
  S.mjRate = S.mjRate || 50;
  cb.innerHTML = `<p class="mtext">常連の3人（${CPU.join("・")}）と東風戦。門前限定（ポン・チー・カンなし）、立直・ツモ・ロンあり、ウマ10-20。最終の点数とウマに応じてお金をやり取りします。払いきれない負け分は、十日屋の貸せる上限までが借金になります。途中で卓を離れるとラス扱いで精算します。</p>
    <div class="mtabs" id="rates">${RATES.map(([r, l]) => `<button class="chip" type="button" data-rate="${r}" aria-pressed="${S.mjRate === r}">${l}</button>`).join("")}</div>
    <p class="mtext small" id="rateinfo"></p>
    <div class="row2"><button class="btn" type="button" id="sit">卓につく</button>${K.net && K.net.connected() ? `<button class="btn light" type="button" id="mjToMulti">みんなの卓に行く</button>` : ""}</div>
    <details class="paytable"><summary>役と点数（この卓のルール）</summary><p class="mtext small">役：立直、門前清自摸和、断么九、平和、一盃口、役牌、一気通貫、三色同順、三暗刻、対々和、七対子、混一色、清一色、ドラ・裏ドラ。役満：国士無双、四暗刻、大三元、字一色。符は簡略化して平和ツモ20符・平和ロン30符・七対子25符・それ以外はツモ30符／ロン40符。親は局ごとに交代（連荘なし）。流局はテンパイ料3000点。フリテンの人はロンできません。</p></details>`;
  const info = () => { const r = S.mjRate; cb.querySelector("#rateinfo").textContent = `目安：トップで+${fmt((15 + 20) * r)}前後、ラスで${fmt((-15 - 20) * r)}前後（素点±15000の場合）`; };
  info();
  const toMulti = cb.querySelector("#mjToMulti"); if (toMulti) toMulti.onclick = () => { S.mjMode = "multi"; setupMJMulti(cb, body); };
  cb.querySelectorAll("[data-rate]").forEach(b => b.onclick = () => { S.mjRate = +b.dataset.rate; cb.querySelectorAll("[data-rate]").forEach(x => x.setAttribute("aria-pressed", String(x === b))); info(); });
  cb.querySelector("#sit").onclick = () => {
    const need = S.mjRate * 35, room = K.loanRoom ? K.loanRoom("yami") : 0;
    if (K.world.money + room < need) { cb.querySelector("#rateinfo").textContent = `このレートだと、ラスのとき ${fmt(need)} くらい払うことになるよ。所持金と十日屋の借入枠（あと ${fmt(room)}）を合わせても足りないので、レートを下げてね`; return; }
    if (K.betAllowed && !K.betAllowed(S.mjRate * 35)) { cb.querySelector("#rateinfo").textContent = K.betMsg() + "（このレートだと負けが大きくなるかも）"; return; } startGame(S.mjRate); };
  function startGame(rate) {
    mj = { alive: true, rate, cb, body, scores: [25000, 25000, 25000, 25000], kyoku: 0, sticks: 0, log: [], timers: [] };
    S.busy = true; startKyoku();
  }
}
// ---------- みんなの麻雀（オンライン。ルールと進行はサーバー） ----------
const MJ_RATES = [[0, "ノーレート"], [10, "点1"], [50, "点5"], [100, "テンピン"], [500, "デカピン"]];
const mjM = { cb: null, body: null, lobby: null, st: null, err: "", deadline: 0, timer: 0, sel: false, leaveArmed: false, len: 0, rate: 0 };
const REDS = new Set([16, 52, 88]);
const tid = (id, cls = "", attrs = "") => tileHtml(id >> 2, cls + (REDS.has(id) ? " aka" : ""), attrs);
const WIND = ["東", "南", "西", "北"];
function mjChoice(cb, body) {
  cb.innerHTML = `<p class="mtext">オンラインの友達と同じ卓で、正式なルールの麻雀が打てます（ポン・チー・カン、赤ドラあり）。4人そろわないときはCPUが入ります。ひとりでCPUと気軽に打つこともできるよ。</p>
    <div class="row2"><button class="btn" type="button" id="mjMulti">みんなの卓に行く</button><button class="btn light" type="button" id="mjSolo">ひとりで打つ（門前のみ）</button></div>`;
  cb.querySelector("#mjMulti").onclick = () => { S.mjMode = "multi"; setupMJMulti(cb, body); };
  cb.querySelector("#mjSolo").onclick = () => { S.mjMode = "solo"; setupMahjongLobby(cb, body); };
}
function setupMJMulti(cb, body) {
  Object.assign(mjM, { cb, body, err: "", sel: false, leaveArmed: false });
  K.net.onMj = m => {
    if (m.type === "mjLobby") mjM.lobby = m;
    else if (m.type === "mjTable") { if (!mjM.st || mjM.st.sub !== m.sub || mjM.st.turn !== m.turn) mjM.sel = false; mjM.st = m; mjM.deadline = performance.now() + (m.ms || 0); }
    else if (m.type === "mjNg") mjM.err = String(m.text || "").slice(0, 60);
    else if (m.type === "mjPay") { refreshMoney(body); return; }
    renderMJM();
  };
  mjM.st = null;
  K.net.send({ type: "mj", act: "list" });
  cb.innerHTML = `<p class="mtext">雀荘につないでいます…</p>`;
  clearInterval(mjM.timer);
  mjM.timer = setInterval(() => {
    if (!cb.isConnected) {
      clearInterval(mjM.timer);
      // 始まる前の卓なら席を立つ。対局中は閉じても続く（CPUがおまかせで打つ）
      if (mjM.st && mjM.st.phase === "wait" && K.net.connected()) K.net.send({ type: "mj", act: "leave" });
      K.net.onMj = null; mjM.st = null; return;
    }
    const t = cb.querySelector("#mjLeft"); if (t) t.textContent = Math.max(0, Math.ceil((mjM.deadline - performance.now()) / 1000));
  }, 250);
}
function mjCanAfford(rateG) {
  if (!rateG) return true;
  const need = rateG * 45, room = K.loanRoom ? K.loanRoom("yami") : 0;
  if (K.world.money + room < need) { mjM.err = `このレートだと、ラスのとき ${fmt(need)} くらい払うことがあるよ。所持金と十日屋の借入枠（あと ${fmt(room)}）を合わせても足りないので、レートを下げてね`; return false; }
  if (K.betAllowed && !K.betAllowed(need)) { mjM.err = K.betMsg() + "（このレートだと負けが大きくなるかも）"; return false; }
  return true;
}
const mjGuide = `<details class="paytable mjguide"><summary>はじめての人へ（かんたんな遊び方）</summary><div class="mtext small">
  <p><b>目標</b>：手の牌14枚で「3枚のまとまり×4つ」と「同じ牌2枚×1つ」を作ると和了（あがり）。3枚のまとまりは、同じ牌3枚（例：東東東）か、同じ種類の続き番号（例：三四五萬）。</p>
  <p><b>流れ</b>：自分の番に1枚引いて、いらない牌を1枚捨てる。これをくり返します。あと1枚で和了れる形を「テンパイ」といいます。</p>
  <p><b>役がないと和了れない</b>：かんたんな役は、<b>リーチ</b>（鳴かずにテンパイしたら「リーチ」を押す）、<b>ツモ</b>（鳴かずに自分で引いて和了る）、<b>タンヤオ</b>（2〜8の数字だけで作る）、<b>役牌</b>（白・發・中や、自分の風・場の風を3枚）。</p>
  <p><b>ツモとロン</b>：和了れる牌を自分で引いたら「ツモ」、ほかの人が捨てたら「ロン」。ただし自分で捨てたことのある牌で待っていると<b>フリテン</b>で、ロンはできません（ツモはOK）。</p>
  <p><b>鳴き</b>：ほかの人の捨て牌をもらう。同じ牌2枚＋1枚で「ポン」、左の人（上家）の牌で続き番号を作る「チー」。鳴くと早くそろうけど、リーチはできなくなります。</p>
  <p><b>こまったら</b>：「ヒント」をオンにすると、おすすめの牌が光って、テンパイの待ちや役があるかを教えてくれます。ノーレートの卓なら「おまかせ打ち」で、CPUが打つのを見て覚えることもできるよ。</p></div></details>`;
const mjRules = `<details class="paytable"><summary>みんなの卓のルール</summary><p class="mtext small">25000点持ちの30000点返し、ウマ10-20（トップにオカ+20）。赤ドラ各1枚、喰いタン・後付けあり、ダブロンなし（頭ハネ）。途中流局は九種九牌・四風連打・四家立直。オーラスは親がトップなら和了りやめ、だれかの点数がマイナスになったら終了。<br>持ち時間は自分の番20秒・鳴き9秒。時間切れはツモ切り・見送りになります（和了れるときも自動では和了らないので、ツモ・ロンは自分で押してね）。時間切れが2回続くか切断すると、代わりにツモ切りと見送りだけで打ちます（何か操作すると戻ります。リーチ中の自動のツモ切りは時間切れに数えません）。ノーレートの卓では、CPUに打ってもらう「おまかせ打ち」が使えます。対局中に卓を離れると、ラス扱い（素点のプラスは無し）で精算して、CPUが代わりに打ちます。<br>役：立直・一発・門前清自摸和・断么九・平和・一盃口・役牌・嶺上開花・槍槓・海底・河底・ダブル立直・七対子・三色同順・一気通貫・混全帯么九・対々和・三暗刻・三色同刻・三槓子・小三元・混老頭・二盃口・純全帯么九・混一色・清一色。役満：天和・地和・国士無双・四暗刻・大三元・字一色・小四喜・大四喜・緑一色・清老頭・九蓮宝燈・四槓子。</p></details>`;
function mjToggles(st) {
  const my = st.seats[st.me] || {};
  return `<div class="row2 mjtog"><button class="btn ${my.hint ? "" : "light"}" type="button" data-mjt="hint" data-on="${my.hint ? 0 : 1}">ヒント：${my.hint ? "オン" : "オフ"}</button>${st.rate === 0 ? `<button class="btn ${my.assist ? "" : "light"}" type="button" data-mjt="assist" data-on="${my.assist ? 0 : 1}">おまかせ打ち：${my.assist ? "オン" : "オフ"}</button>` : ""}</div>`;
}
function wireMjToggles(cb) { cb.querySelectorAll("[data-mjt]").forEach(b => b.onclick = () => { b.disabled = true; mjM.err = ""; K.net.send({ type: "mj", act: b.dataset.mjt, a: +b.dataset.on, b: 0 }); }); }
function renderMJM() {
  const cb = mjM.cb, body = mjM.body, st = mjM.st; if (!cb || !cb.isConnected) return;
  refreshMoney(body);
  const err = mjM.err ? `<div class="warn">${esc(mjM.err)}</div>` : "";
  if (!st || st.me < 0) { renderMJLobby(cb, err); return; }
  if (st.phase === "wait") {
    cb.innerHTML = `<div class="mjlobby"><h3 class="lbl">卓${st.id}　${st.south ? "半荘戦" : "東風戦"}・${esc(st.rateName)}</h3>
      <div class="list">${st.seats.map((s, i) => `<div class="li"><span class="nm">${s ? esc(s.name) + (i === st.me ? "（あなた）" : "") : "空席"}</span></div>`).join("")}</div>
      <p class="mtext small">4人そろうと始まります。${st.owner ? "そろう前でも、空いた席にCPUを入れて始められるよ。" : "卓を立てた人が、CPUを入れて始めることもできます。"}</p>
      <p class="mtext small">麻雀にまだ自信がなければ、「ヒント」をオンにしておくと安心だよ。${st.rate === 0 ? "ノーレートの卓なので、最初から「おまかせ打ち」（CPUが代わりに打つ）にしておくこともできます。" : "おまかせ打ちは、ノーレートの卓だけで使えます。"}</p>
      ${mjToggles(st)}
      ${err}<div class="row2">${st.owner ? `<button class="btn" type="button" id="mjStart">CPUを入れて始める</button>` : ""}<button class="btn light" type="button" id="mjLeaveW">卓を離れる</button></div>${mjGuide}${mjRules}</div>`;
    wireMjToggles(cb);
    const q = s => cb.querySelector(s);
    if (q("#mjStart")) q("#mjStart").onclick = () => K.net.send({ type: "mj", act: "start" });
    q("#mjLeaveW").onclick = () => { mjM.st = null; K.net.send({ type: "mj", act: "leave" }); };
    return;
  }
  const me = st.me, my = st.seats[me], ac = st.actions || {};
  const left = `<b id="mjLeft">${Math.max(0, Math.ceil((mjM.deadline - performance.now()) / 1000))}</b>秒`;
  const meldHtml = s => (s.melds || []).map(m => `<span class="mjmeld">${m.tiles.map((t, j) => m.type === "ankan" && (j === 0 || j === 3) ? `<span class="tl sm back"></span>` : tid(t, "sm" + (t === m.called ? " called" : ""))).join("")}</span>`).join("");
  const riverHtml = s => (s.river || []).map(([t, f]) => tid(t, "sm" + (f & 1 ? " rot" : "") + (f & 2 ? " taken" : "") + (st.last === t && st.sub === "claim" ? " mjhi" : ""))).join("");
  const nameLine = (s, i, pos) => `<div class="mj-name"><span class="wind">${WIND[s.wind]}</span>${pos ? `<span class="pos">${pos}</span>` : ""}${esc(s.name)}${i === me ? "（あなた）" : ""}　<b>${s.score}</b>${s.riichi ? `<span class="rb">リーチ</span>` : ""}${s.auto ? `<span class="rb auto">代わり打ち中</span>` : ""}${s.assist ? `<span class="rb auto">おまかせ打ち</span>` : ""}${i === st.dealer ? `<span class="rb oya">親</span>` : ""}${st.turn === i && st.phase === "play" && st.sub === "turn" ? `<span class="thinking">◀ 番</span>` : ""}${i === me && my.furiten ? `<span class="rb">フリテン</span>` : ""}</div>`;
  const opp = (i, pos) => { const s = st.seats[i]; return `<div class="mj-opp${st.turn === i && st.sub === "turn" ? " turn" : ""}">${nameLine(s, i, pos)}<div class="mjrow"><span class="backs">${"<i></i>".repeat(Math.min(14, s.count))}</span>${meldHtml(s)}</div><div class="mj-river">${riverHtml(s)}</div></div>`; };
  // 手牌
  const canDiscard = !!ac.discard;
  const banned = new Set(ac.banned || []);
  const rset = mjM.sel && ac.riichi ? new Set(ac.riichi.map(t => t >> 2)) : null;
  const okTile = t => canDiscard && !banned.has(t >> 2) && (ac.riichiOnly === undefined || t === ac.riichiOnly) && (!rset || rset.has(t >> 2));
  const hint = st.hint || null, recT = hint && typeof hint.rec === "number" ? hint.rec : -1, tenK = hint && hint.tenpai ? hint.tenpai : null;
  const hcls = t => (t === recT ? " mjrec" : "") + (tenK && tenK[t >> 2] ? " mjten" : "");
  const handTiles = (my.hand || []).map(t => tid(t, (okTile(t) ? "can" + (rset ? " mjhi" : "") : "") + hcls(t), okTile(t) ? `data-t="${t}" role="button" tabindex="0"` : "")).join("")
    + (my.drawn >= 0 ? tid(my.drawn, "drawn" + (okTile(my.drawn) ? " can" + (rset ? " mjhi" : "") : "") + hcls(my.drawn), okTile(my.drawn) ? `data-t="${my.drawn}" role="button" tabindex="0"` : "") : "");
  const hintHtml = hint && hint.msg ? `<div class="mj-hint"><b>ヒント</b>　${esc(hint.msg)}${tenK ? `<span class="small">（「聴」の印の牌は、切るとテンパイになる牌）</span>` : ""}</div>` : "";
  let ctl = "", info = "";
  if (st.phase === "play") {
    if (ac.pass) {
      const t = ac.tile;
      info = `${esc(st.seats[st.lastFrom]?.name || "")}が ${tid(t, "sm")} を${st.sub === "claim" && st.seats[st.lastFrom] && (st.seats[st.lastFrom].melds || []).some(m => m.type === "kakan" && m.tiles.includes(t)) ? "加槓" : "捨てた"}（残り ${left}）`;
      const rc = (k, i) => hint && hint.rec === k && (k !== "chi" || hint.chi === i) ? " mjrecbtn" : "";
      if (ac.ron) ctl += `<button class="btn${rc("ron")}" type="button" data-mj="ron">ロン</button>`;
      if (ac.pon) ctl += `<button class="btn${rc("pon")}" type="button" data-mj="pon">ポン</button>`;
      if (ac.minkan) ctl += `<button class="btn" type="button" data-mj="minkan">カン</button>`;
      (ac.chi || []).forEach((o, i) => { ctl += `<button class="btn${rc("chi", i)}" type="button" data-mj="chi" data-a="${i}">チー ${[...o, t].sort((a, b) => a - b).map(x => tid(x, "sm")).join("")}</button>`; });
      ctl += `<button class="btn light${rc("pass")}" type="button" data-mj="pass">パス</button>`;
    } else if (canDiscard) {
      info = mjM.sel ? `リーチ：捨てる牌をえらんでね（残り ${left}）` : ac.riichiOnly !== undefined ? `ツモ切りか、ツモ・カンをえらんでね（残り ${left}）` : `あなたの番。切る牌をえらんでね（残り ${left}）`;
      if (ac.tsumo) ctl += `<button class="btn${hint && hint.rec === "tsumo" ? " mjrecbtn" : ""}" type="button" data-mj="tsumo">ツモ</button>`;
      if (ac.riichi) ctl += `<button class="btn ${mjM.sel ? "" : "light"}" type="button" id="mjRiichi">${mjM.sel ? "リーチをやめる" : "リーチ"}</button>`;
      (ac.kan || []).forEach(k => { ctl += `<button class="btn light" type="button" data-mj="kan" data-a="${k}">カン ${tileHtml(k, "sm")}</button>`; });
      if (ac.kyuushu) ctl += `<button class="btn light" type="button" data-mj="kyuushu">九種九牌で流す</button>`;
      if (ac.riichiOnly !== undefined) ctl += `<button class="btn light" type="button" data-mj="discard" data-a="${ac.riichiOnly}">ツモ切り</button>`;
    } else {
      const cur = st.seats[st.turn];
      info = st.sub === "claim" ? `鳴きを待っています（残り ${left}）` : `${esc(cur?.name || "")}の番`;
    }
  }
  let overlay = "";
  const r = st.result;
  if ((st.phase === "result" || st.phase === "end") && r) {
    const names = st.seats.map(s => s.name);
    const ready = `<p class="mtext small">${st.phase === "end" ? "" : `次の局まで ${left}`}${my.ready ? "　ほかの人を待っています…" : ""}</p>`;
    if (r.kind === "agari") {
      overlay = `<div class="mj-res"><h3>${esc(names[r.winner])}の${r.tsumo ? "ツモ" : "ロン"}${r.tsumo ? "" : `（${esc(names[r.from])}から）`}</h3>
        <div class="mj-hand small">${r.hand.slice().sort((a, b) => a - b).map(t => tid(t, "sm" + (t === r.tile ? " mjhi" : ""))).join("")}${(r.melds || []).map(m => `<span class="mjmeld">${m.tiles.map(t => tid(t, "sm")).join("")}</span>`).join("")}</div>
        <div class="yakulist">${r.yaku.map(([n, h]) => `<span>${esc(n)}<b>${esc(h)}</b></span>`).join("")}</div>
        <p class="mtext">${r.label === "役満" || /役満/.test(r.label) ? "" : `${r.han}飜${r.fu}符 `}${r.label ? `<b>${esc(r.label)}</b> ` : ""}${r.value ?? r.points}点${r.value !== undefined && r.points !== r.value ? `<span class="mtext small">（本場・供託こみで +${r.points}）</span>` : ""}</p>
        ${r.ura ? `<p class="mtext small">裏ドラ表示 ${r.ura.map(t => tid(t, "sm")).join("")}</p>` : ""}
        <div class="mj-delta">${r.delta.map((d, i) => `<span>${esc(names[i])}<b class="${d > 0 ? "up" : d < 0 ? "down" : ""}">${d > 0 ? "+" : ""}${d}</b></span>`).join("")}</div>
        ${ready}<button class="btn" type="button" data-mj="next" ${my.ready ? "disabled" : ""}>次へ</button></div>`;
    } else if (r.kind === "draw") {
      const tp = r.tenpai ? r.tenpai.map((v, i) => v ? names[i] : null).filter(Boolean) : null;
      overlay = `<div class="mj-res"><h3>${esc(r.name)}</h3>${tp ? `<p class="mtext">テンパイ：${tp.map(esc).join("・") || "なし"}</p>` : ""}
        ${r.hands ? r.hands.map((h, i) => h ? `<div class="mj-hand small"><span class="mtext small">${esc(names[i])}</span>${h.slice().sort((a, b) => a - b).map(t => tid(t, "sm")).join("")}</div>` : "").join("") : ""}
        <div class="mj-delta">${r.delta.map((d, i) => `<span>${esc(names[i])}<b class="${d > 0 ? "up" : d < 0 ? "down" : ""}">${d > 0 ? "+" : ""}${d}</b></span>`).join("")}</div>
        ${ready}<button class="btn" type="button" data-mj="next" ${my.ready ? "disabled" : ""}>次へ</button></div>`;
    } else if (r.kind === "end") {
      const mine = r.rank.find(x => x.seat === me);
      overlay = `<div class="mj-res"><h3>対局終了　${r.rank.indexOf(mine) + 1}位</h3>
        <div class="mjrank">${r.rank.map((x, i) => `<div class="${x.seat === me ? "me" : ""}"><span>${i + 1}位</span><span>${esc(x.name)}</span><b>${x.score}</b><b class="${x.pt > 0 ? "up" : x.pt < 0 ? "down" : ""}">${x.pt > 0 ? "+" : ""}${x.pt.toFixed(1)}</b></div>`).join("")}</div>
        <div class="result ${mine.money > 0 ? "win" : ""}">${st.rate ? `${mine.money >= 0 ? "+" : ""}${fmt(mine.money)}` : "ノーレート（お金のやりとりなし）"}</div>
        <p class="mtext small">素点（30000点返し）＋ウマ10-20、トップはオカ+20。${st.rate ? "払いきれない負けは、十日屋が上限まで立て替えます。" : ""}</p>
        <div class="row2"><button class="btn" type="button" data-mj="next" ${my.ready ? "disabled" : ""}>${my.ready ? "ほかの人を待っています…" : "卓に戻る"}</button><button class="btn light" type="button" id="mjOut">卓を離れる</button></div></div>`;
    }
  }
  const pos = { 1: "下家", 2: "対面", 3: "上家" };
  cb.innerHTML = `<div class="mj">
    ${[3, 2, 1].map(d => opp((me + d) % 4, pos[d])).join("")}
    <div class="mj-center"><span>${st.kyoku < 4 ? "東" : "南"}${st.kyoku % 4 + 1}局 ${st.honba}本場</span><span>残り ${st.left}</span><span>ドラ表示 ${(st.dora || []).map(t => tid(t, "sm")).join("")}</span><span>供託 ${st.sticks}本</span><span>${st.south ? "半荘戦" : "東風戦"}・${esc(MJ_RATES[st.rate]?.[1] || "")}</span></div>
    <div class="mj-me${st.turn === me && st.sub === "turn" ? " turn" : ""}">${nameLine(my, me, "")}
      <div class="mj-river">${riverHtml(my)}</div>
      <div class="mj-hand">${handTiles}${my.melds && my.melds.length ? `<span class="mjmelds">${meldHtml(my)}</span>` : ""}</div>
      <div class="mj-msg">${info}</div><div class="row2 mjctl">${ctl}</div>${hintHtml}${my.assist ? `<div class="mj-hint"><b>おまかせ打ち中</b>　CPUが代わりに打っています。打ち方を見て覚えてね。自分で打つときは「おまかせ打ち：オン」を押してオフに</div>` : ""}</div>
    ${err}${overlay}
    ${mjToggles(st)}
    <div class="row2"><button class="btn ghost" type="button" id="mjLeave">${mjM.leaveArmed ? "もう一度押すと離れる（ラス扱いで精算）" : "卓を離れる"}</button></div>${mjGuide}${mjRules}</div>`;
  wireMjToggles(cb);
  const send = (act, a = 0, b = 0) => { mjM.err = ""; K.net.send({ type: "mj", act, a, b }); };
  cb.querySelectorAll("[data-mj]").forEach(el => el.onclick = () => { el.disabled = true; send(el.dataset.mj, +(el.dataset.a || 0)); });
  cb.querySelectorAll(".tl[data-t]").forEach(el => {
    const go = () => { const t = +el.dataset.t, rr = mjM.sel; mjM.sel = false; send("discard", t, rr ? 1 : 0); };
    el.onclick = go; el.onkeydown = e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); go(); } };
  });
  const q = s => cb.querySelector(s);
  if (q("#mjRiichi")) q("#mjRiichi").onclick = () => { mjM.sel = !mjM.sel; renderMJM(); };
  if (q("#mjOut")) q("#mjOut").onclick = () => { mjM.st = null; send("leave"); };
  q("#mjLeave").onclick = () => {
    if (st.phase !== "play" && st.phase !== "result") { mjM.st = null; send("leave"); return; }
    if (!mjM.leaveArmed) { mjM.leaveArmed = true; renderMJM(); setTimeout(() => { mjM.leaveArmed = false; renderMJM(); }, 4000); return; }
    mjM.leaveArmed = false; mjM.st = null; send("leave");
  };
}
function renderMJLobby(cb, err) {
  const L = mjM.lobby, tables = L ? L.tables : [];
  cb.innerHTML = `<div class="mjlobby">
    <h3 class="lbl">卓の一覧</h3>
    <div class="list">${tables.length ? tables.map(t => { const free = t.seats.some(s => !s); return `<div class="li"><span class="nm">卓${t.id}　${t.south ? "半荘戦" : "東風戦"}・${esc(MJ_RATES[t.rate]?.[1] || "")}
        <span class="sub2">${t.seats.map(s => s ? esc(s) : "空席").join("・")}　${t.phase === "wait" ? "メンバー待ち" : "対局中"}</span></span>
        <span class="acts">${t.phase === "wait" && free ? `<button class="btn" type="button" data-sit="${t.id}" data-rate="${t.rate}">座る</button>` : ""}</span></div>`; }).join("") : `<p class="mtext">いまは卓がありません。卓を立ててみよう。</p>`}</div>
    <h3 class="lbl">卓を立てる</h3>
    <div class="mtabs">${["東風戦", "半荘戦"].map((l, i) => `<button class="chip" type="button" data-len="${i}" aria-pressed="${mjM.len === i}">${l}</button>`).join("")}</div>
    <div class="mtabs">${MJ_RATES.map(([g, l], i) => `<button class="chip" type="button" data-rt="${i}" aria-pressed="${mjM.rate === i}">${l}${g ? `（1000点=${g}G）` : ""}</button>`).join("")}</div>
    <p class="mtext small">麻雀がはじめてなら、ノーレートの卓がおすすめ。卓では「ヒント」（おすすめの牌やテンパイを教えてくれる）と「おまかせ打ち」（CPUが代わりに打つ）が使えます。</p>
    ${err}<div class="row2"><button class="btn" type="button" id="mjCreate">卓を立てる</button><button class="btn light" type="button" id="mjSoloGo">ひとりで打つ</button></div>${mjGuide}${mjRules}</div>`;
  cb.querySelectorAll("[data-len]").forEach(b => b.onclick = () => { mjM.len = +b.dataset.len; renderMJM(); });
  cb.querySelectorAll("[data-rt]").forEach(b => b.onclick = () => { mjM.rate = +b.dataset.rt; renderMJM(); });
  cb.querySelectorAll("[data-sit]").forEach(b => b.onclick = () => { mjM.err = ""; if (!mjCanAfford(MJ_RATES[+b.dataset.rate][0])) { renderMJM(); return; } K.net.send({ type: "mj", act: "sit", a: +b.dataset.sit }); });
  cb.querySelector("#mjCreate").onclick = () => { mjM.err = ""; if (!mjCanAfford(MJ_RATES[mjM.rate][0])) { renderMJM(); return; } K.net.send({ type: "mj", act: "create", a: mjM.len, b: mjM.rate }); };
  cb.querySelector("#mjSoloGo").onclick = () => { S.mjMode = "solo"; clearInterval(mjM.timer); K.net.onMj = null; setupMahjongLobby(cb, mjM.body); };
}
function later(fn, ms) { const id = setTimeout(() => { if (mj && mj.alive) fn(); }, window.__mjFast ? 3 : ms); mj.timers.push(id); }
const seatName = p => p === 0 ? K.myName() : CPU[p - 1];
const windOf = p => (p - mj.kyoku + 4) % 4;
function startKyoku() {
  const wall = []; for (let t = 0; t < 34; t++) for (let i = 0; i < 4; i++) wall.push(t);
  for (let i = wall.length - 1; i > 0; i--) { const j = rnd(i + 1); [wall[i], wall[j]] = [wall[j], wall[i]]; }
  const dead = wall.splice(0, 14);
  Object.assign(mj, {
    wall, dead, doraInd: dead[4], uraInd: dead[5], hands: [[], [], [], []], rivers: [[], [], [], []], riichi: [false, false, false, false],
    riichiTurn: [-1, -1, -1, -1], phase: "deal", turn: mj.kyoku, drawn: null, msg: `東${mj.kyoku + 1}局　親は${seatName(mj.kyoku)}`, result: null, sel: false, pendingRon: null,
  });
  for (let r = 0; r < 13; r++) for (let p = 0; p < 4; p++) mj.hands[p].push(wall.pop());
  mj.hands[0].sort((a, b) => a - b);
  renderMJ();
  later(() => drawTile(mj.turn), 700);
}
const ctxFor = (p, tsumo) => ({ tsumo, riichi: mj.riichi[p], seat: windOf(p), round: 0, dora: [MJ.doraOf(mj.doraInd)], ura: [MJ.doraOf(mj.uraInd)] });
function winCheck(p, hand14, win, tsumo) {
  const c = MJ.counts(hand14); if (!MJ.isWin(c)) return null;
  return MJ.evaluate(c, win, ctxFor(p, tsumo));
}
function furiten(p) {
  const w = MJ.waits(MJ.counts(mj.hands[p])); const mine = new Set(mj.rivers[p].map(r => r.t));
  return w.some(t => mine.has(t));
}
function drawTile(p) {
  if (!mj.wall.length) { ryuukyoku(); return; }
  mj.turn = p; const t = mj.wall.pop(); mj.hands[p].push(t); mj.drawn = t;
  if (p === 0) {
    mj.phase = "mine";
    mj.canTsumo = !!winCheck(0, mj.hands[0], t, true);
    mj.canRiichi = !mj.riichi[0] && mj.scores[0] >= 1000 && mj.wall.length >= 4 && riichiDiscards().length > 0;
    mj.msg = mj.riichi[0] ? (mj.canTsumo ? "ツモれます！" : "リーチ中：ツモ切りします") : (mj.canTsumo ? "ツモれます！" : "捨てる牌を選んでね");
    renderMJ();
    if (mj.riichi[0] && !mj.canTsumo) later(() => discard(0, mj.hands[0].length - 1), 650);
  } else {
    mj.phase = "cpu"; renderMJ();
    later(() => cpuTurn(p), 420);
  }
}
function riichiDiscards() {
  const h = mj.hands[0]; const out = [];
  h.forEach((t, i) => { const c = MJ.counts(h); c[t]--; if (MJ.shanten(c) === 0) out.push(i); });
  return out;
}
function cpuTurn(p) {
  const h = mj.hands[p];
  const res = winCheck(p, h, mj.drawn, true);
  if (res) { win(p, p, mj.drawn, res, true); return; }
  let idx;
  if (mj.riichi[p]) idx = h.length - 1;
  else {
    const c = MJ.counts(h);
    const threats = [1, 2, 3, 0].filter(q => q !== p && mj.riichi[q]);
    let cur = MJ.shanten(c);
    let best = null;
    const seen = new Set();
    h.forEach((t, i) => {
      if (seen.has(t)) return; seen.add(t);
      c[t]--; const s = MJ.shanten(c); c[t]++;
      let v = s * 1000;
      const yakuhai = [31, 32, 33, 27, 27 + windOf(p)].includes(t);
      if (t >= 27) v += yakuhai && c[t] >= 2 ? 60 : 0;
      else { const n = (c[t - 1] && t % 9 > 0 ? 1 : 0) + (c[t + 1] && t % 9 < 8 ? 1 : 0) + (c[t - 2] && t % 9 > 1 ? 1 : 0) + (c[t + 2] && t % 9 < 7 ? 1 : 0); v += (MJ.isTerm(t) ? 10 : 25) + n * 8 + (c[t] - 1) * 15; }
      if (threats.length && cur >= 1) { const safe = threats.every(q => mj.rivers[q].some(r => r.t === t)); if (safe) v -= cur >= 2 ? 2500 : 900; }
      if (!best || v < best.v) best = { v, i, s };
    });
    idx = best.i;
    const c2 = MJ.counts(h); c2[h[idx]]--;
    if (MJ.shanten(c2) === 0 && mj.scores[p] >= 1000 && mj.wall.length >= 4 && Math.random() < .85) { discard(p, idx, true); return; }
  }
  discard(p, idx);
}
function discard(p, idx, declareRiichi) {
  const h = mj.hands[p]; const t = h.splice(idx, 1)[0];
  if (p === 0) h.sort((a, b) => a - b);
  if (declareRiichi) { mj.riichi[p] = true; mj.scores[p] -= 1000; mj.sticks++; mj.msg = `${seatName(p)}：リーチ！`; }
  mj.rivers[p].push({ t, r: !!declareRiichi });
  mj.drawn = null; mj.sel = false; mj.phase = "check"; renderMJ();
  checkRon(p, t, 1);
}
function checkRon(from, t, k) {
  if (k > 3) { later(() => drawTile((from + 1) % 4), 260); return; }
  const q = (from + k) % 4;
  const hand14 = [...mj.hands[q], t];
  const res = winCheck(q, hand14, t, false);
  if (res && !furiten(q)) {
    if (q === 0) { mj.phase = "ron"; mj.pendingRon = { from, t, k, res }; mj.msg = `${seatName(from)}の${MJ.name(t)}でロンできます！`; renderMJ(); return; }
    win(q, from, t, res, false); return;
  }
  checkRon(from, t, k + 1);
}
function win(p, from, t, res, tsumo) {
  if (!tsumo) mj.hands[p].push(t);
  const dealer = p === mj.kyoku;
  const pt = MJ.points(res, dealer, tsumo);
  const delta = [0, 0, 0, 0];
  if (!tsumo) { delta[from] -= pt.ron; delta[p] += pt.ron; }
  else for (let q = 0; q < 4; q++) if (q !== p) { const pay = dealer ? pt.all : (q === mj.kyoku ? pt.dealer : pt.other); delta[q] -= pay; delta[p] += pay; }
  delta[p] += mj.sticks * 1000; mj.sticks = 0;
  for (let q = 0; q < 4; q++) mj.scores[q] += delta[q];
  mj.phase = "result";
  mj.result = { title: `${seatName(p)}の${tsumo ? "ツモ" : "ロン"}${!tsumo ? `（${seatName(from)}から）` : ""}`, hand: mj.hands[p].slice().sort((a, b) => a - b), win: t,
    yaku: res.yaku, han: res.han, fu: res.fu, label: pt.label, total: pt.total, delta, yakuman: res.yakuman };
  if (p === 0 && res.yakuman) K.world.stats.mjYakuman = (K.world.stats.mjYakuman || 0) + 1;
  mj.msg = ""; renderMJ();
}
function ryuukyoku() {
  const tenpai = [0, 1, 2, 3].map(p => MJ.shanten(MJ.counts(mj.hands[p])) === 0);
  const n = tenpai.filter(Boolean).length; const delta = [0, 0, 0, 0];
  if (n > 0 && n < 4) for (let p = 0; p < 4; p++) delta[p] = tenpai[p] ? 3000 / n : -3000 / (4 - n);
  for (let p = 0; p < 4; p++) mj.scores[p] += delta[p];
  mj.phase = "result";
  mj.result = { title: "流局", ryu: true, tenpai, delta };
  renderMJ();
}
function nextKyoku() {
  mj.kyoku++;
  if (mj.kyoku >= 4 || mj.scores.some(s => s < 0)) { endGame(); return; }
  startKyoku();
}
function endGame(left) {
  const order = [0, 1, 2, 3].sort((a, b) => mj.scores[b] - mj.scores[a] || a - b);
  const rank = left ? 3 : order.indexOf(0);
  if (mj.sticks) { mj.scores[order[0]] += mj.sticks * 1000; mj.sticks = 0; }
  const raw = left ? Math.min(0, (mj.scores[0] - 25000) / 1000) : (mj.scores[0] - 25000) / 1000;
  const pts = raw + UMA[rank];
  const money = Math.round(pts * mj.rate);
  let note = "";
  if (money >= 0) K.addMoney(money);
  else {
    const loss = -money; const pay = Math.min(loss, K.world.money); K.addMoney(-pay);
    const short = loss - pay, room = K.loanRoom ? K.loanRoom("yami") : short, lend = Math.min(short, room);
    if (lend > 0) { K.takeLoan("yami", lend); note = `払いきれない ${fmt(lend)} は十日屋が立て替えた（10日で5割）`; }
    if (short > lend) note += `${note ? "。" : ""}十日屋の貸せる上限をこえた ${fmt(short - lend)} は、雀荘のマスターが「次はほどほどにな」とまけてくれた`;
  }
  K.world.stats.gambleNet += money; if (K.gambleTrack) K.gambleTrack(money); K.world.stats.lastGamble = K.world.day; if (money > K.world.stats.bigWin) K.world.stats.bigWin = money;
  K.world.stats.mjGames = (K.world.stats.mjGames || 0) + 1; if (rank === 0) K.world.stats.mjTop = (K.world.stats.mjTop || 0) + 1;
  K.saveWorld();
  mj.phase = "end"; mj.final = { order, rank, pts, raw, money, note, left };
  renderMJ();
}
function leaveTable() {
  if (!mj || !mj.alive) return;
  if (mj.phase === "end") { mj.timers.forEach(clearTimeout); mj.alive = false; S.busy = false; K.closeModal(); openCasino("mahjong", S.single); return; }
  mj.timers.forEach(clearTimeout); mj.timers = [];
  endGame(true);
}
function renderMJ() {
  const cb = mj.cb; if (!cb.isConnected) return;
  const body = mj.body;
  refreshMoney(body);
  const oppRow = p => `<div class="mj-opp"><div class="mj-name"><span class="wind">${MJ.HONOR[windOf(p)]}</span>${esc(seatName(p))}　<b>${mj.scores[p]}</b>${mj.riichi[p] ? `<span class="rb">リーチ</span>` : ""}${mj.turn === p && mj.phase === "cpu" ? `<span class="thinking">考え中…</span>` : ""}
      <span class="backs">${"<i></i>".repeat(Math.min(14, mj.hands[p].length))}</span></div>
      <div class="mj-river">${mj.rivers[p].map(r => tileHtml(r.t, "sm" + (r.r ? " rot" : ""))).join("")}</div></div>`;
  const isMine = mj.phase === "mine";
  const rset = isMine && mj.sel ? new Set(riichiDiscards()) : null;
  const hand = mj.hands[0];
  const handTiles = hand.map((t, i) => {
    const isDrawn = isMine && i === hand.length - 1 && mj.drawn !== null;
    const can = isMine && !mj.riichi[0] && (!rset || rset.has(i));
    return tileHtml(t, (isDrawn ? "drawn " : "") + (can ? "can" : "") + (rset && rset.has(i) ? " mjhi" : ""), can ? `data-i="${i}" role="button" tabindex="0"` : "");
  }).join("");
  let ctl = "";
  if (isMine) {
    if (mj.canTsumo) ctl += `<button class="btn" type="button" id="mjTsumo">ツモ</button>`;
    if (mj.canRiichi && !mj.riichi[0]) ctl += `<button class="btn ${mj.sel ? "" : "light"}" type="button" id="mjRiichi">${mj.sel ? "リーチ：捨てる牌を選ぶ" : "リーチ"}</button>`;
    if (mj.riichi[0] && mj.canTsumo) ctl += `<button class="btn light" type="button" id="mjSkip">ツモ切り</button>`;
  }
  if (mj.phase === "ron") ctl += `<button class="btn" type="button" id="mjRon">ロン</button><button class="btn light" type="button" id="mjPass">見逃す</button>`;
  let overlay = "";
  if (mj.phase === "result" && mj.result) {
    const r = mj.result;
    overlay = `<div class="mj-res"><h3>${esc(r.title)}</h3>${r.ryu ? `<p class="mtext">テンパイ：${[0, 1, 2, 3].filter(p => r.tenpai[p]).map(seatName).map(esc).join("・") || "なし"}</p>` :
      `<div class="mj-hand small">${r.hand.map(t => tileHtml(t, "sm" + (t === r.win ? " mjhi" : ""))).join("")}</div>
       <div class="yakulist">${r.yaku.map(([n, h]) => `<span>${esc(n)}<b>${h === "役満" ? "役満" : h + "翻"}</b></span>`).join("")}</div>
       <p class="mtext">${r.yakuman ? "" : `${r.han}翻${r.fu}符 `}${r.label ? `<b>${r.label}</b> ` : ""}${r.total}点</p>`}
      <div class="mj-delta">${[0, 1, 2, 3].map(p => `<span>${esc(seatName(p))}<b class="${r.delta[p] > 0 ? "up" : r.delta[p] < 0 ? "down" : ""}">${r.delta[p] > 0 ? "+" : ""}${r.delta[p]}</b></span>`).join("")}</div>
      <button class="btn" type="button" id="mjNext">${mj.kyoku >= 3 || mj.scores.some(s => s < 0) ? "精算へ" : "次の局へ"}</button></div>`;
  }
  if (mj.phase === "end") {
    const f = mj.final;
    overlay = `<div class="mj-res"><h3>${f.left ? "途中で卓を離れた" : "対局終了"}　${f.rank + 1}位</h3>
      <div class="mj-delta">${f.order.map((p, i) => `<span>${i + 1}位 ${esc(seatName(p))}<b>${mj.scores[p]}</b></span>`).join("")}</div>
      ${f.left ? `<p class="mtext small">途中で卓を離れたので、ラス扱い（素点のプラスは無し）で精算</p>` : ""}<p class="mtext">素点 ${f.raw >= 0 ? "+" : ""}${f.raw}　ウマ ${UMA[f.rank] > 0 ? "+" : ""}${UMA[f.rank]}　→ ${f.pts >= 0 ? "+" : ""}${f.pts.toFixed(1)} × ${fmt(mj.rate)}</p>
      <div class="result ${f.money > 0 ? "win" : ""}">${f.money >= 0 ? "+" : ""}${fmt(f.money)}</div>${f.note ? `<div class="warn">${esc(f.note)}</div>` : ""}
      <button class="btn" type="button" id="mjEnd">卓を立つ</button></div>`;
  }
  cb.innerHTML = `<div class="mj">
    ${[3, 2, 1].map(oppRow).join("")}
    <div class="mj-center"><span>東${Math.min(4, mj.kyoku + 1)}局</span><span>残り ${mj.wall.length}</span><span>ドラ表示 ${tileHtml(mj.doraInd, "sm")}</span><span>供託 ${mj.sticks}本</span><span>あなたは${MJ.HONOR[windOf(0)]}家</span></div>
    <div class="mj-me"><div class="mj-name"><span class="wind">${MJ.HONOR[windOf(0)]}</span>${esc(K.myName())}　<b>${mj.scores[0]}</b>${mj.riichi[0] ? `<span class="rb">リーチ</span>` : ""}${mj.phase !== "result" && mj.phase !== "end" && furiten(0) && mj.hands[0].length === 13 ? `<span class="rb">フリテン</span>` : ""}</div>
      <div class="mj-river">${mj.rivers[0].map(r => tileHtml(r.t, "sm" + (r.r ? " rot" : ""))).join("")}</div>
      <div class="mj-hand">${handTiles}</div>
      <div class="mj-msg">${esc(mj.msg || "")}</div><div class="row2">${ctl}</div></div>
    ${overlay}
    <div class="row2"><button class="btn ghost" type="button" id="mjLeave">${mj.phase === "end" ? "戻る" : "卓を離れる（いまの点数で精算）"}</button></div></div>`;
  cb.querySelectorAll(".tl[data-i]").forEach(el => {
    const go = () => { if (mj.phase !== "mine") return; const i = +el.dataset.i; const r = mj.sel; mj.sel = false; discard(0, i, r); };
    el.onclick = go; el.onkeydown = e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); go(); } };
  });
  const on = (id, fn) => { const el = cb.querySelector("#" + id); if (el) el.onclick = fn; };
  on("mjTsumo", () => { const res = winCheck(0, mj.hands[0], mj.drawn, true); if (res) win(0, 0, mj.drawn, res, true); });
  on("mjRiichi", () => { mj.sel = !mj.sel; renderMJ(); });
  on("mjSkip", () => discard(0, mj.hands[0].length - 1));
  on("mjRon", () => { const pr = mj.pendingRon; mj.pendingRon = null; win(0, pr.from, pr.t, pr.res, false); });
  on("mjPass", () => { const pr = mj.pendingRon; mj.pendingRon = null; mj.phase = "check"; checkRon(pr.from, pr.t, pr.k + 1); });
  on("mjNext", nextKyoku);
  on("mjEnd", leaveTable);
  on("mjLeave", leaveTable);
}
})();
