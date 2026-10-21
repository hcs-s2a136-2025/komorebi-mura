const MJ = (() => {
  // kinds: 0-8 萬子, 9-17 筒子, 18-26 索子, 27-30 東南西北, 31 白, 32 發, 33 中
  const KANJI = ["一", "二", "三", "四", "五", "六", "七", "八", "九"];
  const CIRC = ["①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨"];
  const HONOR = ["東", "南", "西", "北", "白", "發", "中"];
  const KOKU = [0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33];
  const isHonor = t => t >= 27;
  const isTerm = t => t >= 27 || t % 9 === 0 || t % 9 === 8;
  const suitOf = t => t >= 27 ? 3 : Math.floor(t / 9);
  function name(t) {
    if (t < 9) return KANJI[t] + "萬"; if (t < 18) return (t - 8) + "筒"; if (t < 27) return (t - 17) + "索"; return HONOR[t - 27];
  }
  function counts(tiles) { const c = new Array(34).fill(0); for (const t of tiles) c[t]++; return c; }
  const sum = c => c.reduce((a, b) => a + b, 0);

  function isChiitoi(c) { let p = 0; for (let t = 0; t < 34; t++) { if (c[t] === 2) p++; else if (c[t] !== 0) return false; } return p === 7; }
  function isKokushi(c) {
    if (sum(c) !== 14) return false; let pair = false;
    for (let t = 0; t < 34; t++) { if (KOKU.includes(t)) { if (!c[t]) return false; if (c[t] === 2) pair = true; else if (c[t] > 2) return false; } else if (c[t]) return false; }
    return pair;
  }
  function decompose(c0) {
    const out = []; const c = c0.slice();
    function rec(melds, pair) {
      let i = 0; while (i < 34 && !c[i]) i++;
      if (i === 34) { out.push({ pair, melds: melds.slice() }); return; }
      if (c[i] >= 3) { c[i] -= 3; melds.push({ type: "tri", t: i }); rec(melds, pair); melds.pop(); c[i] += 3; }
      if (i < 27 && i % 9 <= 6 && c[i + 1] && c[i + 2]) {
        c[i]--; c[i + 1]--; c[i + 2]--; melds.push({ type: "seq", t: i }); rec(melds, pair); melds.pop(); c[i]++; c[i + 1]++; c[i + 2]++;
      }
    }
    for (let p = 0; p < 34; p++) if (c[p] >= 2) { c[p] -= 2; rec([], p); c[p] += 2; }
    return out;
  }
  function isWin(c) { return sum(c) === 14 && (isChiitoi(c) || isKokushi(c) || decompose(c).length > 0); }

  function shantenStd(c0) {
    const c = c0.slice(); let best = 8;
    function scan(i, m, t, p) {
      while (i < 34 && !c[i]) i++;
      if (i >= 34) { const tt = Math.min(t, 4 - m); const s = 8 - 2 * m - tt - p; if (s < best) best = s; return; }
      if (c[i] >= 3) { c[i] -= 3; scan(i, m + 1, t, p); c[i] += 3; }
      if (i < 27 && i % 9 <= 6 && c[i + 1] && c[i + 2]) { c[i]--; c[i + 1]--; c[i + 2]--; scan(i, m + 1, t, p); c[i]++; c[i + 1]++; c[i + 2]++; }
      if (c[i] >= 2) { c[i] -= 2; if (!p) scan(i, m, t, 1); scan(i, m, t + 1, p); c[i] += 2; }
      if (i < 27 && i % 9 <= 7 && c[i + 1]) { c[i]--; c[i + 1]--; scan(i, m, t + 1, p); c[i]++; c[i + 1]++; }
      if (i < 27 && i % 9 <= 6 && c[i + 2]) { c[i]--; c[i + 2]--; scan(i, m, t + 1, p); c[i]++; c[i + 2]++; }
      const k = c[i]; c[i] = 0; scan(i + 1, m, t, p); c[i] = k;
    }
    scan(0, 0, 0, 0); return best;
  }
  function shantenChiitoi(c) { let pairs = 0, kinds = 0; for (const n of c) { if (n >= 2) pairs++; if (n >= 1) kinds++; } return 6 - pairs + Math.max(0, 7 - kinds); }
  function shantenKokushi(c) { let kinds = 0, pair = 0; for (const t of KOKU) { if (c[t]) kinds++; if (c[t] >= 2) pair = 1; } return 13 - kinds - pair; }
  function shanten(c) { return Math.min(shantenStd(c), shantenChiitoi(c), shantenKokushi(c)); }
  function waits(c13) {
    const w = [];
    for (let t = 0; t < 34; t++) { if (c13[t] >= 4) continue; c13[t]++; if (isWin(c13)) w.push(t); c13[t]--; }
    return w;
  }

  // ctx: {tsumo, riichi, seat (0=東..3), round (0=東), dora:[kinds], ura:[kinds]}
  function evaluate(c, win, ctx) {
    const yakuman = [];
    const tiles = []; c.forEach((n, t) => { for (let i = 0; i < n; i++) tiles.push(t); });
    const suits = new Set(tiles.filter(t => t < 27).map(suitOf));
    const hasHonor = tiles.some(isHonor);
    if (isKokushi(c)) yakuman.push(["国士無双", 1]);
    if (tiles.every(isHonor)) yakuman.push(["字一色", 1]);
    const common = [];
    if (ctx.riichi) common.push(["立直", 1]);
    if (ctx.tsumo) common.push(["門前清自摸和", 1]);
    if (tiles.every(t => !isTerm(t))) common.push(["断么九", 1]);
    if (suits.size === 1 && hasHonor) common.push(["混一色", 3]);
    if (suits.size === 1 && !hasHonor) common.push(["清一色", 6]);
    const doraN = (ctx.dora || []).reduce((a, d) => a + c[d], 0);
    const uraN = ctx.riichi ? (ctx.ura || []).reduce((a, d) => a + c[d], 0) : 0;
    const roundW = 27 + (ctx.round || 0), seatW = 27 + ctx.seat;
    const cands = [];
    if (isChiitoi(c)) cands.push({ yaku: [...common, ["七対子", 2]], fu: 25 });
    for (const d of decompose(c)) {
      const y = [...common];
      const tris = d.melds.filter(m => m.type === "tri").map(m => m.t), seqs = d.melds.filter(m => m.type === "seq").map(m => m.t);
      for (const t of tris) {
        if (t === 31) y.push(["役牌 白", 1]); if (t === 32) y.push(["役牌 發", 1]); if (t === 33) y.push(["役牌 中", 1]);
        if (t === roundW) y.push(["場風 " + HONOR[t - 27], 1]); if (t === seatW) y.push(["自風 " + HONOR[t - 27], 1]);
      }
      const yakuhaiPair = [31, 32, 33, roundW, seatW].includes(d.pair);
      const ryanmen = seqs.some(s => (win === s && s % 9 !== 6) || (win === s + 2 && s % 9 !== 0));
      const pinfu = seqs.length === 4 && !yakuhaiPair && ryanmen;
      if (pinfu) y.push(["平和", 1]);
      const sc = {}; seqs.forEach(s => sc[s] = (sc[s] || 0) + 1);
      if (Object.values(sc).some(n => n >= 2)) y.push(["一盃口", 1]);
      for (let s = 0; s < 3; s++) if ([0, 3, 6].every(o => seqs.includes(s * 9 + o))) y.push(["一気通貫", 2]);
      for (let n = 0; n < 7; n++) if ([0, 9, 18].every(o => seqs.includes(o + n))) y.push(["三色同順", 2]);
      if (tris.length === 4) y.push(["対々和", 2]);
      let anko = tris.length;
      if (!ctx.tsumo && tris.includes(win) && d.pair !== win && !seqs.some(s => win >= s && win <= s + 2)) anko--;
      if (anko === 4) yakuman.push(["四暗刻", 1]);
      else if (anko === 3) y.push(["三暗刻", 2]);
      if ([31, 32, 33].every(t => tris.includes(t))) yakuman.push(["大三元", 1]);
      const fu = pinfu ? (ctx.tsumo ? 20 : 30) : (ctx.tsumo ? 30 : 40);
      cands.push({ yaku: y, fu });
    }
    if (yakuman.length) {
      const uniq = [...new Map(yakuman.map(y => [y[0], y])).values()];
      return { yaku: uniq.map(([n]) => [n, "役満"]), han: 13 * uniq.length, fu: 0, yakuman: uniq.length, dora: 0 };
    }
    let best = null;
    for (const cd of cands) {
      const han = cd.yaku.reduce((a, y) => a + y[1], 0);
      if (han === 0) continue;
      const total = han + doraN + uraN;
      if (!best || total > best.han || (total === best.han && cd.fu > best.fu)) {
        const yaku = cd.yaku.slice(); if (doraN) yaku.push(["ドラ", doraN]); if (uraN) yaku.push(["裏ドラ", uraN]);
        best = { yaku, han: total, fu: cd.fu, yakuman: 0 };
      }
    }
    return best; // null = no yaku
  }
  function points(res, dealer, tsumo) {
    let base, label = "";
    const h = res.han;
    if (res.yakuman) { base = 8000 * res.yakuman; label = res.yakuman > 1 ? "ダブル役満" : "役満"; }
    else if (h >= 13) { base = 8000; label = "数え役満"; } else if (h >= 11) { base = 6000; label = "三倍満"; }
    else if (h >= 8) { base = 4000; label = "倍満"; } else if (h >= 6) { base = 3000; label = "跳満"; } else if (h >= 5) { base = 2000; label = "満貫"; }
    else { base = res.fu * Math.pow(2, h + 2); if (base >= 2000) { base = 2000; label = "満貫"; } }
    const r = x => Math.ceil(x / 100) * 100;
    if (!tsumo) { const v = r(base * (dealer ? 6 : 4)); return { total: v, ron: v, label }; }
    if (dealer) { const a = r(base * 2); return { total: a * 3, all: a, label }; }
    const d = r(base * 2), o = r(base); return { total: d + o * 2, dealer: d, other: o, label };
  }
  const doraOf = ind => ind < 27 ? Math.floor(ind / 9) * 9 + (ind % 9 + 1) % 9 : ind < 31 ? 27 + (ind - 27 + 1) % 4 : 31 + (ind - 31 + 1) % 3;
  return { name, counts, isWin, isChiitoi, isKokushi, decompose, shanten, waits, evaluate, points, doraOf, isHonor, isTerm, suitOf, KANJI, CIRC, HONOR };
})();
if (typeof module !== "undefined") module.exports = MJ;
