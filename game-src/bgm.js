const BGM = (() => {
  const NOTE = { C: 0, "C#": 1, Db: 1, D: 2, "D#": 3, Eb: 3, E: 4, F: 5, "F#": 6, Gb: 6, G: 7, "G#": 8, Ab: 8, A: 9, "A#": 10, Bb: 10, B: 11 };
  const midi = n => { const m = n.match(/^([A-G][b#]?)(-?\d)$/); return 12 * (+m[2] + 1) + NOTE[m[1]]; };
  const hz = m => 440 * Math.pow(2, (m - 69) / 12);
  const chord = s => s.split(" ").map(midi);
  const mel = s => s.trim().split(/\s+/);

  const SONGS = {
    village: {
      title: "こもれびのうた", bpm: 92, swing: 0,
      chords: [["F3 A3 C4"], ["E3 G3 C4"], ["D3 F3 A3"], ["Bb2 D3 F3"], ["F3 A3 C4"], ["C3 E3 G3"], ["Bb2 D3 F3", "C3 E3 G3"], ["F3 A3 C4"],
               ["D3 F3 A3"], ["Bb2 D3 F3"], ["F3 A3 C4"], ["C3 E3 G3"], ["D3 F3 A3"], ["G2 Bb2 D3"], ["Bb2 D3 F3", "C3 E3 G3"], ["F3 A3 C4"]],
      melody: [
        "A4 - C5 - F5 - E5 D5", "C5 - - - G4 - A4 -", "F4 - A4 - D5 - C5 A4", "Bb4 - - - D5 - C5 Bb4",
        "A4 - C5 - F5 - G5 A5", "G5 - E5 - C5 - D5 E5", "F5 - D5 Bb4 E5 - C5 G4", "F4 - - - . . . .",
        "D5 - E5 F5 E5 - D5 -", "D5 - C5 - Bb4 - A4 -", "C5 - A4 - F4 - A4 C5", "E5 - - - D5 - C5 -",
        "D5 - F5 - A5 - G5 F5", "G5 - F5 - D5 - Bb4 -", "C5 - D5 - E5 - G5 -", "F5 - - - . . . ."],
      lead: "ocarina", arp: true, bass: "two", hat: "shaker",
    },
    casino: {
      title: "ラッキーナイト", bpm: 138, swing: .62,
      chords: [["C4 Eb4 G4 Bb4", "F3 A3 Eb4 G4"], ["Bb3 D4 F4 A4", "G3 B3 D4 F4"], ["C4 Eb4 G4 Bb4", "F3 A3 Eb4 G4"], ["D4 F4 A4 C5", "G3 B3 D4 F4"],
               ["C4 Eb4 G4 Bb4", "F3 A3 Eb4 G4"], ["Bb3 D4 F4 A4", "G3 B3 D4 F4"], ["C4 Eb4 G4 Bb4", "F3 A3 Eb4 G4"], ["Bb3 D4 F4 G4", "Bb3 D4 F4 G4"]],
      melody: [
        "F5 - D5 Eb5 F5 - . Bb4", "D5 - - C5 Bb4 - G4 -", "Eb5 - C5 D5 Eb5 F5 G5 -", "F5 - - - . D5 E5 F#5",
        "G5 - F5 Eb5 D5 - C5 -", "D5 - Bb4 - G4 - A4 B4", "C5 Eb5 G5 - F5 - Eb5 A4", "Bb4 - - - . . . ."],
      lead: "sax", comp: true, bass: "walk", hat: "ride",
    },
    dark: {
      title: "十日目の夜", bpm: 70, swing: 0,
      chords: [["A2 E3 A3"], ["F2 C3 A3"], ["D3 F3 A3"], ["E2 B2 G#3"], ["A2 E3 A3"], ["F2 C3 A3"], ["D3 F3 A3"], ["E2 B2 E3"]],
      melody: ["E5 - - - C5 - B4 -", "A4 - - - . . . .", "D5 - F5 - E5 - D5 -", "B4 - - - G#4 - - -",
               "E5 - - - F5 - E5 -", "C5 - - - A4 - - -", "D5 - C5 - B4 - A4 -", "G#4 - - - . . . ."],
      lead: "box", pad: true, bass: "pulse", hat: "tick",
    },
  };

  let ac = null, master = null, noise = null, enabled = false, vol = .5;
  let cur = null, curName = null, want = "village", step = 0, bar = 0, nextT = 0, timer = 0;

  function ensure() {
    if (ac) return true;
    const AC = window.AudioContext || window.webkitAudioContext; if (!AC) return false;
    ac = new AC(); master = ac.createGain(); master.gain.value = 0; master.connect(ac.destination);
    const len = ac.sampleRate; const buf = ac.createBuffer(1, len, ac.sampleRate); const d = buf.getChannelData(0);
    for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1; noise = buf;
    return true;
  }
  function env(g, t, a, peak, dec, sus, rel, dur) {
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(peak, t + a);
    g.gain.exponentialRampToValueAtTime(Math.max(.0001, peak * sus), t + a + dec);
    g.gain.setValueAtTime(Math.max(.0001, peak * sus), t + Math.max(a + dec, dur));
    g.gain.exponentialRampToValueAtTime(.0001, t + Math.max(a + dec, dur) + rel);
  }
  function osc(type, f, t, dur, peak, o = {}) {
    const g = ac.createGain(); const s = ac.createOscillator(); s.type = type; s.frequency.value = f;
    let node = s;
    if (o.lp) { const fl = ac.createBiquadFilter(); fl.type = "lowpass"; fl.frequency.value = o.lp; fl.Q.value = o.q || .7; s.connect(fl); node = fl; }
    if (o.vib) { const l = ac.createOscillator(), lg = ac.createGain(); l.frequency.value = 5.2; lg.gain.value = f * o.vib; l.connect(lg); lg.connect(s.frequency); l.start(t + .12); l.stop(t + dur + .6); }
    node.connect(g); g.connect(master);
    env(g, t, o.a ?? .01, peak, o.d ?? .2, o.s ?? .6, o.r ?? .15, dur);
    s.start(t); s.stop(t + dur + (o.r ?? .15) + .3);
  }
  function hit(t, kind) {
    const s = ac.createBufferSource(); s.buffer = noise; const f = ac.createBiquadFilter(); const g = ac.createGain();
    const P = { shaker: ["highpass", 6000, .025, .05], ride: ["bandpass", 8000, .035, .18], tick: ["highpass", 9000, .03, .02], brush: ["bandpass", 2500, .02, .12] }[kind];
    f.type = P[0]; f.frequency.value = P[1]; s.connect(f); f.connect(g); g.connect(master);
    g.gain.setValueAtTime(P[2], t); g.gain.exponentialRampToValueAtTime(.0001, t + P[3]);
    s.start(t, Math.random() * .5); s.stop(t + P[3] + .05);
  }
  function kick(t, v = .25) {
    const s = ac.createOscillator(), g = ac.createGain(); s.frequency.setValueAtTime(110, t); s.frequency.exponentialRampToValueAtTime(42, t + .14);
    g.gain.setValueAtTime(v, t); g.gain.exponentialRampToValueAtTime(.0001, t + .2); s.connect(g); g.connect(master); s.start(t); s.stop(t + .25);
  }
  function lead(kind, f, t, dur) {
    if (kind === "ocarina") { osc("triangle", f, t, dur, .11, { a: .03, d: .15, s: .7, r: .12, vib: .006 }); osc("sine", f * 2, t, dur, .015, { a: .03, r: .1 }); }
    if (kind === "sax") { osc("sawtooth", f, t, dur, .05, { lp: 1700, q: 2, a: .04, d: .1, s: .75, r: .08, vib: .008 }); osc("square", f / 2, t, dur, .012, { lp: 900, a: .04, r: .08 }); }
    if (kind === "box") { osc("sine", f, t, .1, .1, { a: .004, d: .9, s: .01, r: .3 }); osc("triangle", f * 2, t, .1, .02, { a: .004, d: .4, s: .01, r: .2 }); }
  }

  function schedule(song, b, s, t, sd) {
    const ch = song.chords[b % song.chords.length]; const half = s >= 8 ? ch.length - 1 : 0; const notes = chord(ch[half]);
    const nextCh = chord(song.chords[(b + 1) % song.chords.length][0]);
    const bars = song.melody.length; const m = mel(song.melody[b % bars]);
    if (s % 2 === 0) {
      const i = s / 2, tok = m[i];
      if (tok && tok !== "." && tok !== "-") { let len = 1; while (m[i + len] === "-") len++; lead(song.lead, hz(midi(tok)), t, len * sd * 2 * .92); }
    }
    if (song.arp) { const seq = [0, 1, 2, 1]; const n = notes[seq[s % 4]] + 12; osc("sine", hz(n), t, .05, .03, { a: .004, d: .35, s: .02, r: .2 }); }
    if (song.pad && s === 0) for (const n of notes) osc("sawtooth", hz(n), t, sd * 15.5, .012, { lp: 700, a: .6, d: .5, s: .8, r: .8 });
    if (song.comp && (s === 4 || s === 11 || s === 14)) for (const n of notes) { osc("sine", hz(n), t, sd * 1.4, .022, { a: .005, d: .25, s: .3, r: .2 }); osc("sine", hz(n) * 2, t, sd, .006, { a: .005, d: .15, s: .1, r: .1 }); }
    const root = notes[0] % 12 + 36;
    if (song.bass === "two" && (s === 0 || s === 8)) { osc("triangle", hz(root), t, sd * 6, .14, { a: .01, d: .3, s: .5, r: .1 }); }
    if (song.bass === "two" && (s === 6 || s === 14)) { osc("triangle", hz(root + 7), t, sd * 1.5, .08, { a: .01, d: .1, s: .4, r: .1 }); }
    if (song.bass === "walk" && s % 4 === 0) {
      const beat = s / 4; const nroot = nextCh[0] % 12 + 36;
      const pitch = beat === 0 || beat === 2 ? root : beat === 1 ? root + 7 : nroot - 1;
      osc("triangle", hz(pitch), t, sd * 3.3, .16, { a: .005, d: .2, s: .45, r: .06 });
    }
    if (song.bass === "pulse" && s % 4 === 0) osc("sine", hz(root - 12 + 12), t, sd * 2, .12, { a: .01, d: .3, s: .2, r: .2 });
    if (song.hat === "shaker" && s % 4 === 2) hit(t, "shaker");
    if (song.hat === "shaker" && s === 0) kick(t, .12);
    if (song.hat === "ride" && (s % 4 === 0 || s % 8 === 6)) hit(t, "ride");
    if (song.hat === "ride" && (s === 4 || s === 12)) hit(t, "brush");
    if (song.hat === "ride" && s === 0) kick(t, .1);
    if (song.hat === "tick" && s % 4 === 0) hit(t, "tick");
  }
  function tick() {
    if (!ac || !cur) return;
    const song = cur; const sd = 60 / song.bpm / 4;
    while (nextT < ac.currentTime + .15) {
      const swingOff = song.swing && step % 4 === 2 ? sd * (song.swing - .5) * 2 : 0;
      schedule(song, bar, step, nextT + swingOff, sd);
      nextT += sd; step++;
      if (step >= 16) { step = 0; bar++; if (want !== curName) { switchNow(); return; } }
    }
  }
  function switchNow() {
    curName = want; cur = SONGS[want]; bar = 0; step = 0; nextT = ac.currentTime + .08;
  }
  function fadeTo(v, sec = .6) { if (!ac) return; master.gain.cancelScheduledValues(ac.currentTime); master.gain.setValueAtTime(master.gain.value, ac.currentTime); master.gain.linearRampToValueAtTime(v, ac.currentTime + sec); }
  function start() {
    if (!ensure()) return false;
    if (ac.state === "suspended") ac.resume();
    if (!cur) switchNow();
    clearInterval(timer); timer = setInterval(tick, 30);
    fadeTo(vol * .9);
    return true;
  }
  return {
    get enabled() { return enabled; }, get title() { return cur ? cur.title : SONGS[want].title; },
    setEnabled(on) { enabled = on; if (on) return start(); fadeTo(0, .4); setTimeout(() => { if (!enabled) { clearInterval(timer); } }, 450); return true; },
    play(name) {
      if (!SONGS[name] || name === want) return; want = name;
      if (!enabled || !ac) { cur = null; return; }
      fadeTo(0, .35); setTimeout(() => { if (want === name && enabled) { switchNow(); fadeTo(vol * .9, .5); } }, 380);
    },
    setVolume(v) { vol = v; if (enabled && ac) fadeTo(vol * .9, .15); },
    // カラオケ中などに、いったん小さくする（false で元にもどす）
    duck(on) { if (enabled && ac) fadeTo(on ? 0 : vol * .9, .3); },
    SONGS,
  };
})();
