// =================== SFX: synthesized sound effects (slot machine etc.) ===================
const SFX = (() => {
  const KEY = "komorebi-se";
  let pref = { on: true, vol: .8 };
  try { Object.assign(pref, JSON.parse(localStorage.getItem(KEY) || "{}")); } catch (e) {}
  let ac = null, out = null, noiseBuf = null, spinT = 0, spinRate = 16, spinStopAt = 0;
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(pref)); } catch (e) {} };
  function ctx() {
    if (!pref.on) return null;
    if (!ac) {
      const AC = window.AudioContext || window.webkitAudioContext; if (!AC) return null;
      ac = new AC();
      const comp = ac.createDynamicsCompressor(); comp.threshold.value = -12; comp.knee.value = 6; comp.ratio.value = 10; comp.attack.value = .003; comp.release.value = .2;
      out = ac.createGain(); out.gain.value = pref.vol * 2; out.connect(comp); comp.connect(ac.destination);
      noiseBuf = ac.createBuffer(1, ac.sampleRate, ac.sampleRate); const d = noiseBuf.getChannelData(0); for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
    }
    if (ac.state === "suspended") ac.resume();
    return ac;
  }
  const hz = m => 440 * Math.pow(2, (m - 69) / 12);
  // one oscillator note with an envelope
  function tone(t, f, dur, { type = "square", vol = .3, f2 = null, att = .004, filt = 0 } = {}) {
    const o = ac.createOscillator(), g = ac.createGain(); o.type = type; o.frequency.setValueAtTime(f, t);
    if (f2) o.frequency.exponentialRampToValueAtTime(f2, t + dur);
    g.gain.setValueAtTime(.0001, t); g.gain.exponentialRampToValueAtTime(vol, t + att); g.gain.exponentialRampToValueAtTime(.0001, t + dur);
    let node = o; if (filt) { const lp = ac.createBiquadFilter(); lp.type = "lowpass"; lp.frequency.value = filt; o.connect(lp); node = lp; }
    node.connect(g); g.connect(out); o.start(t); o.stop(t + dur + .02);
  }
  function noise(t, dur, { vol = .3, hp = 0, lp = 0 } = {}) {
    const s = ac.createBufferSource(), g = ac.createGain(); s.buffer = noiseBuf; let node = s;
    if (hp) { const f = ac.createBiquadFilter(); f.type = "highpass"; f.frequency.value = hp; node.connect(f); node = f; }
    if (lp) { const f = ac.createBiquadFilter(); f.type = "lowpass"; f.frequency.value = lp; node.connect(f); node = f; }
    g.gain.setValueAtTime(vol, t); g.gain.exponentialRampToValueAtTime(.0001, t + dur); node.connect(g); g.connect(out); s.start(t); s.stop(t + dur + .02);
  }
  const coin = t => { tone(t, 2637, .09, { type: "square", vol: .12 }); tone(t + .05, 3520, .22, { type: "square", vol: .1 }); };
  const bell = (t, m, dur = .5, vol = .18) => { tone(t, hz(m), dur, { type: "triangle", vol }); tone(t, hz(m + 12), dur * .6, { type: "sine", vol: vol * .5 }); };
  const brass = (t, m, dur, vol = .16) => { tone(t, hz(m), dur, { type: "sawtooth", vol, filt: 2600, att: .02 }); tone(t, hz(m) * 1.004, dur, { type: "square", vol: vol * .4, filt: 1800, att: .02 }); };

  // reels spinning: a ticking "カラカラ" plus a low motor hum
  let hum = null;
  function spinLoop() {
    if (!ac || !spinT) return;
    const now = ac.currentTime;
    if (now > spinStopAt) { api.spinStop(); return; }
    tone(now, 1500 + Math.random() * 200, .03, { type: "square", vol: .14 });
    noise(now, .02, { vol: .05, hp: 3000 });
    spinT = setTimeout(spinLoop, 1000 / spinRate);
  }

  const api = {
    get on() { return pref.on; }, get vol() { return pref.vol; },
    setOn(v) { pref.on = !!v; save(); if (!pref.on) api.spinStop(); },
    setVol(v) { pref.vol = Math.max(0, Math.min(1, +v)); save(); if (out) out.gain.value = pref.vol * 2; },
    test() { api.win(); },
    lever() {
      if (!ctx()) return; const t = ac.currentTime;
      noise(t, .08, { vol: .9, lp: 2200 }); tone(t, 180, .16, { type: "square", vol: .8, f2: 60 });
      for (let i = 0; i < 4; i++) tone(t + .05 + i * .03, 900 - i * 120, .03, { type: "square", vol: .3 });
    },
    spinStart() {
      if (!ctx()) return; api.spinStop(); spinRate = 16; spinStopAt = ac.currentTime + 12;
      hum = ac.createOscillator(); const g = ac.createGain(), lp = ac.createBiquadFilter();
      hum.type = "sawtooth"; hum.frequency.value = 58; lp.type = "lowpass"; lp.frequency.value = 260; g.gain.value = .0001;
      g.gain.exponentialRampToValueAtTime(.12, ac.currentTime + .15); hum.connect(lp); lp.connect(g); g.connect(out); hum.start(); hum._g = g;
      spinT = setTimeout(spinLoop, 60);
    },
    spinSlow() { spinRate = 7; if (hum) hum.frequency.setTargetAtTime(44, ac.currentTime, .2); },
    spinStop() {
      clearTimeout(spinT); spinT = 0;
      if (hum && ac) { const h = hum; h._g.gain.setTargetAtTime(.0001, ac.currentTime, .05); setTimeout(() => { try { h.stop(); } catch (e) {} }, 300); }
      hum = null;
    },
    reelStop(i) {
      if (!ctx()) return; const t = ac.currentTime;
      tone(t, 150 + i * 30, .2, { type: "square", vol: .9, f2: 55 }); noise(t, .07, { vol: .8, lp: 2500 });
      tone(t + .01, 700 + i * 120, .07, { type: "triangle", vol: .35 });
    },
    reach() {
      if (!ctx()) return; const t = ac.currentTime;
      for (let i = 0; i < 6; i++) { tone(t + i * .16, i % 2 ? 660 : 990, .15, { type: "square", vol: .22 }); tone(t + i * .16, i % 2 ? 330 : 495, .15, { type: "triangle", vol: .16 }); }
      for (let i = 0; i < 12; i++) noise(t + 1 + i * .06, .05, { vol: .08 + i * .01, hp: 800 });
    },
    miss() { if (!ctx()) return; const t = ac.currentTime; tone(t, 440, .25, { type: "triangle", vol: .3, f2: 220 }); tone(t + .12, 330, .28, { type: "triangle", vol: .24, f2: 165 }); },
    small() { if (!ctx()) return; const t = ac.currentTime; bell(t, 84, .3, .28); coin(t + .08); coin(t + .17); },
    win(n = 6) {
      if (!ctx()) return; const t = ac.currentTime;
      [72, 76, 79, 84].forEach((m, i) => bell(t + i * .08, m, .45, .2));
      for (let i = 0; i < n; i++) coin(t + .35 + i * .09);
    },
    big() {
      if (!ctx()) return; const t = ac.currentTime;
      [[0, 72, .14], [.15, 72, .14], [.3, 72, .14], [.45, 76, .5], [.95, 74, .14], [1.1, 76, .14], [1.25, 79, .9]].forEach(([d, m, l]) => { brass(t + d, m, l); brass(t + d, m - 12, l, .1); });
      [60, 64, 67, 72].forEach(m => tone(t + 1.25, hz(m), 1.1, { type: "triangle", vol: .1 }));
      for (let i = 0; i < 6; i++) noise(t + i * .15, .08, { vol: .25, lp: 1200 });
      for (let i = 0; i < 24; i++) coin(t + .5 + i * .07);
    },
    // 丁半：壺の中でサイコロが転がる音
    cupShake(dur = .85) {
      if (!ctx()) return; const t = ac.currentTime;
      for (let i = 0; i < 26; i++) { const d = t + i * dur / 26 + Math.random() * .02; noise(d, .025, { vol: .28, lp: 1600 }); tone(d, 1400 + Math.random() * 900, .03, { type: "triangle", vol: .12 }); }
    },
    // 丁半：壺をたたみに伏せる「タンッ」
    cupSlam() { if (!ctx()) return; const t = ac.currentTime; tone(t, 110, .22, { type: "sine", vol: .8, f2: 45 }); noise(t, .09, { vol: .6, lp: 700 }); tone(t, 320, .06, { type: "triangle", vol: .25 }); },
    // チンチロ：どんぶりの中で3つのサイコロが跳ねる「チリンチリン…カラン」
    diceRoll(dur = 1.1) {
      if (!ctx()) return; const t = ac.currentTime; let d = t;
      for (let i = 0; d < t + dur; i++) {
        const gap = .035 + (i / 22) * .12 * Math.random() + i * .004; d += gap;
        const f = 2300 + Math.random() * 1700;
        tone(d, f, .09, { type: "triangle", vol: .22 }); tone(d, f * 1.51, .14, { type: "sine", vol: .1 }); noise(d, .012, { vol: .2, hp: 2500 });
      }
      tone(t + dur + .02, 2900, .45, { type: "sine", vol: .18 }); tone(t + dur + .02, 4400, .3, { type: "sine", vol: .07 });
    },
    // サイコロが丼から飛び出した（ションベン）
    diceOut() { if (!ctx()) return; const t = ac.currentTime; tone(t, 2600, .25, { type: "triangle", vol: .2, f2: 900 }); noise(t + .25, .08, { vol: .35, lp: 900 }); tone(t + .25, 140, .15, { type: "sine", vol: .5, f2: 70 }); },
    // チャットの「ぽこん」
    chat() { if (!ctx()) return; const t = ac.currentTime; tone(t, 880, .08, { type: "sine", vol: .16, f2: 1320 }); },
    jackpot() {
      if (!ctx()) return; const t = ac.currentTime;
      for (let i = 0; i < 16; i++) noise(t + i * .05, .045, { vol: .12 + i * .012, lp: 1500 });
      const mel = [[.8, 72, .18], [1, 76, .18], [1.2, 79, .18], [1.4, 84, .5], [1.95, 83, .18], [2.15, 84, .18], [2.35, 86, .18], [2.55, 88, 1.3]];
      mel.forEach(([d, m, l]) => { brass(t + d, m, l, .18); brass(t + d, m - 12, l, .1); bell(t + d, m + 12, l, .08); });
      [[1.4, [60, 64, 67]], [2.55, [62, 67, 71, 74]]].forEach(([d, ch]) => ch.forEach(m => tone(t + d, hz(m), 1.2, { type: "triangle", vol: .1 })));
      for (let i = 0; i < 4; i++) { tone(t + 2.55 + i * .3, 60, .25, { type: "sine", vol: .4, f2: 40 }); noise(t + 2.55 + i * .3, .1, { vol: .25, hp: 4000 }); }
      for (let i = 0; i < 60; i++) coin(t + 1.4 + i * .045 + Math.random() * .02);
    },
  };
  return api;
})();
