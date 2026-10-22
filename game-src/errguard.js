// 起動できなかったときに、真っ暗なままにしないで、何が起きたかを画面に出す（管理人に送ることもできる）
(() => {
  const VER = "/*VER*/";
  const errs = [];
  let shown = false, burst = [];
  const fmtErr = e => {
    const s = (e.stack || "").split("\n").slice(0, 6).join("\n");
    return `${e.message || e}${e.line ? `\n場所：${e.line}行目 ${e.col || 0}文字目` : ""}${s ? "\n" + s : ""}`;
  };
  function details() {
    return [`バージョン：${VER}`, `ページ：${location.href.split("?")[0]}`, `ブラウザ：${navigator.userAgent}`, `画面：${innerWidth}×${innerHeight}`, "", ...errs.slice(0, 5).map(fmtErr)].join("\n").slice(0, 1900);
  }
  function show(kind) {
    if (shown) return; shown = true;
    const box = document.createElement("div");
    box.id = "errguard";
    box.style.cssText = "position:fixed;inset:0;z-index:2147483000;background:rgba(18,24,20,.92);display:flex;align-items:center;justify-content:center;padding:16px;font-family:system-ui,sans-serif";
    const tok = (() => { try { return (JSON.parse(localStorage.getItem("komorebi-online") || "null") || {}).token || ""; } catch (e) { return ""; } })();
    const canSend = !!tok && /^https?:$/.test(location.protocol);
    box.innerHTML = `<div style="background:#f3e6c6;color:#3b2a21;border-radius:12px;max-width:560px;width:100%;padding:18px 18px 14px;box-shadow:0 10px 40px rgba(0,0,0,.5)">
      <h2 style="margin:0 0 8px;font-size:1.1rem">${kind === "stuck" ? "こもれび村の動きが止まってしまいました" : kind === "slow" ? "こもれび村の読み込みに時間がかかっています" : "こもれび村がうまく起動できませんでした"}</h2>
      <p style="margin:0 0 10px;font-size:.9rem;line-height:1.6">ごめんなさい。下の内容を管理人に送ってもらえると、原因を直せます。もう一度読み込むと動くこともあります。</p>
      <pre id="egText" style="background:#fffaf0;border:1px solid #d8c8a8;border-radius:8px;padding:8px;max-height:220px;overflow:auto;font-size:.72rem;white-space:pre-wrap;word-break:break-all;margin:0 0 10px"></pre>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <button type="button" id="egReload" style="padding:8px 14px;border-radius:8px;border:0;background:#e8a83a;color:#3b2a21;font-weight:600">もう一度読み込む</button>
        ${canSend ? `<button type="button" id="egSend" style="padding:8px 14px;border-radius:8px;border:0;background:#3f6a4a;color:#fff">管理人に送る</button>` : ""}
        <button type="button" id="egCopy" style="padding:8px 14px;border-radius:8px;border:1px solid #b8a888;background:#fffaf0;color:#3b2a21">内容をコピー</button>
        <button type="button" id="egClose" style="padding:8px 14px;border-radius:8px;border:0;background:transparent;color:#6a5a4a">閉じる</button>
      </div><p id="egMsg" style="margin:8px 0 0;font-size:.8rem"></p></div>`;
    document.body.appendChild(box);
    const $e = id => box.querySelector("#" + id), msg = t => { $e("egMsg").textContent = t; };
    $e("egText").textContent = details();
    $e("egReload").onclick = () => location.reload();
    $e("egClose").onclick = () => box.remove();
    $e("egCopy").onclick = async () => { try { await navigator.clipboard.writeText(details()); msg("コピーしました"); } catch (e) { msg("コピーできなかったので、画面をスクリーンショットしてね"); } };
    if (canSend) $e("egSend").onclick = async () => {
      $e("egSend").disabled = true; msg("送っています…");
      try {
        const r = await fetch("/api/feedback", { method: "POST", headers: { "Content-Type": "application/json", "X-Player-Token": tok }, body: JSON.stringify({ category: "BUG", title: kind === "stuck" ? "動きが止まった（自動のエラー報告）" : kind === "slow" ? "読み込みが終わらない（自動の報告）" : "起動できない（自動のエラー報告）", body: details(), context: "errguard" }) });
        msg(r.ok ? "送りました。ありがとう！" : "送れませんでした（" + r.status + "）。スクリーンショットで送ってね");
      } catch (e) { msg("送れませんでした。スクリーンショットで送ってね"); }
    };
  }
  addEventListener("error", ev => {
    if (!ev.error && !ev.message) return;            // 画像の読み込み失敗などは数えない
    errs.push({ message: ev.message, line: ev.lineno, col: ev.colno, stack: ev.error && ev.error.stack });
    if (!window.__game) setTimeout(() => { if (!window.__game) show("start"); }, 300);
    else { const now = Date.now(); burst = burst.filter(t => now - t < 5000); burst.push(now); if (burst.length > 40) show("stuck"); }
  });
  addEventListener("unhandledrejection", ev => { const r = ev.reason || {}; errs.push({ message: "（非同期）" + (r.message || r), stack: r.stack }); });
  // 読み込みがとちゅうで止まっている（電波が弱い・サーバーが目ざめる途中など）ときも、知らせる
  setTimeout(() => { if (!window.__game && !shown) { errs.push({ message: "ページの読み込みが、とちゅうで止まっているみたいです（電波やサーバーの目ざめ待ちかも）" }); show("slow"); } }, 30000);
  // 読み込みが終わっても始まっていなければ、知らせる
  addEventListener("load", () => setTimeout(() => { if (!window.__game && !shown) { if (!errs.length) errs.push({ message: "エラーは出ていないのに、ゲームが始まりませんでした" }); show("start"); } }, 8000));
})();
