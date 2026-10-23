package jp.komorebi.casino.race;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 競どんぐり（どんぐりのレースに賭ける）。進行はサーバーが決めて、みんなに同じレースを配る。
 * <ul>
 * <li>bet（賭けの受付）→ run（レース）→ result（結果）をくり返す</li>
 * <li>オッズは出走時に決まる固定オッズ。単勝（1着）と複勝（2着まで）</li>
 * <li>お金のやりとりは各自の画面で行う（賭けたときに引いて、結果を見て払い戻す）。サーバーは賭けの記録と結果だけを持つ</li>
 * <li>走り方の絵は、各自の画面で seed と着順・タイムから同じように描く</li>
 * </ul>
 * Spring に依存しないので、模擬サーバー（tools/mock-server）からもそのまま動かせる。
 */
public class RaceGame {

    public interface Out {
        void toAll(Map<String, Object> msg);
        void toPlayer(long playerId, Map<String, Object> msg);
    }

    public static final long BET_MS = 50_000, RESULT_MS = 12_000;
    public static final int RUNNERS = 6, MIN_BET = 10, MAX_BET = 50_000, MAX_BETS_PER_RACE = 12;
    static final String[] NAMES = { "クヌギ", "コナラ", "マテバシイ", "スダジイ", "ミズナラ", "カシワ", "ウバメガシ", "アラカシ", "シラカシ", "クリ" };
    static final String[] COLORS = { "#e2404e", "#4a8ae0", "#f0c030", "#4aa860", "#a070e0", "#f08a3a" };
    static final String[] FORM = { "", "不調", "いまいち", "ふつう", "好調", "絶好調" };

    /** 1頭（1粒）ぶん */
    static final class Runner {
        String name;
        double strength, weight;
        int form;
        double win, place;
    }

    record Bet(long pid, String name, int runner, String kind, int amount, double odds) { }

    private final Random rnd;
    private final Out out;
    private int id;
    private String phase = "bet";
    private long phaseEnd;
    private int day;
    private final List<Runner> runners = new ArrayList<>();
    private int[] order = new int[0];
    private int[] times = new int[0];
    private int seed;
    private final List<Bet> bets = new ArrayList<>();
    private final Deque<Map<String, Object>> history = new ArrayDeque<>();

    public RaceGame(Random rnd, Out out) {
        this.rnd = rnd;
        this.out = out;
    }

    // ------------------------------------------------------------------ 進行

    public synchronized void tick(long now, int clockDay) {
        day = clockDay;
        if (id == 0) { newRace(now); return; }
        if (now < phaseEnd) return;
        switch (phase) {
            case "bet" -> startRun(now);
            case "run" -> finish(now);
            default -> newRace(now);
        }
    }

    private void newRace(long now) {
        id++;
        phase = "bet";
        phaseEnd = now + BET_MS;
        bets.clear();
        order = new int[0];
        times = new int[0];
        runners.clear();
        List<String> pool = new ArrayList<>(List.of(NAMES));
        double total = 0;
        for (int i = 0; i < RUNNERS; i++) {
            Runner r = new Runner();
            r.name = pool.remove(rnd.nextInt(pool.size())) + "号";
            r.strength = 0.72 + rnd.nextDouble() * 0.56;
            r.weight = Math.pow(r.strength, 6);
            r.form = Math.max(1, Math.min(5, (int) Math.floor((r.strength - 0.72) / 0.56 * 5) + 1));
            total += r.weight;
            runners.add(r);
        }
        for (Runner r : runners) {
            double p = r.weight / total, p2 = p;
            for (Runner o : runners) if (o != r) p2 += (o.weight / total) * r.weight / (total - o.weight);
            r.win = clamp(Math.round(0.86 / p * 10) / 10.0, 1.3, 80);
            r.place = clamp(Math.round(0.86 / p2 * 10) / 10.0, 1.1, 20);
        }
        out.toAll(state(now));
    }

    private void startRun(long now) {
        // 着順は重みつきで1頭ずつ決める（強いほど前に来やすい）
        List<Integer> left = new ArrayList<>();
        for (int i = 0; i < RUNNERS; i++) left.add(i);
        order = new int[RUNNERS];
        for (int pos = 0; pos < RUNNERS; pos++) {
            double sum = 0;
            for (int i : left) sum += runners.get(i).weight;
            double x = rnd.nextDouble() * sum;
            int pick = left.get(left.size() - 1);
            for (int i : left) { x -= runners.get(i).weight; if (x < 0) { pick = i; break; } }
            order[pos] = pick;
            left.remove(Integer.valueOf(pick));
        }
        times = new int[RUNNERS];
        int t = 15_000 + rnd.nextInt(3_000);
        for (int pos = 0; pos < RUNNERS; pos++) {
            times[order[pos]] = t;
            t += 200 + rnd.nextInt(1_100);
        }
        seed = rnd.nextInt(1_000_000_000);
        phase = "run";
        phaseEnd = now + t + 1_200;
        out.toAll(state(now));
    }

    private void finish(long now) {
        phase = "result";
        phaseEnd = now + RESULT_MS;
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", id);
        h.put("day", day);
        h.put("order", toList(order));
        List<Object> names = new ArrayList<>();
        for (int i : order) names.add(runners.get(i).name);
        h.put("names", names);
        h.put("winOdds", runners.get(order[0]).win);
        // いちばんの大当たり（新聞用）
        Map<Long, Long> paid = new HashMap<>();
        Map<Long, String> who = new HashMap<>();
        for (Bet b : bets) {
            long p = payout(b);
            if (p > 0) { paid.merge(b.pid(), p, Long::sum); who.put(b.pid(), b.name()); }
        }
        List<Map<String, Object>> wins = new ArrayList<>();
        paid.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(3).forEach(e -> {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("name", who.get(e.getKey()));
            w.put("payout", e.getValue());
            wins.add(w);
        });
        h.put("wins", wins);
        h.put("bettors", bets.stream().map(Bet::pid).distinct().count());
        history.addFirst(h);
        while (history.size() > 40) history.removeLast();
        out.toAll(state(now));
    }

    long payout(Bet b) {
        if (order.length < 2) return 0;
        boolean hit = "win".equals(b.kind()) ? order[0] == b.runner() : order[0] == b.runner() || order[1] == b.runner();
        return hit ? (long) Math.floor(b.amount() * b.odds()) : 0;
    }

    // ------------------------------------------------------------------ 操作

    /** 賭ける。成功したら raceOk（id・オッズつき）、だめなら raceNg を本人へ */
    public synchronized void bet(long pid, String name, int runner, String kind, int amount, long now) {
        if (!"bet".equals(phase) || now >= phaseEnd - 500) { ng(pid, "いまは賭けを受け付けていないよ。次のレースを待ってね"); return; }
        if (runner < 0 || runner >= runners.size()) { ng(pid, "そのどんぐりは走らないみたい"); return; }
        if (!"win".equals(kind) && !"place".equals(kind)) { ng(pid, "賭け方がわからなかった"); return; }
        if (amount < MIN_BET || amount > MAX_BET) { ng(pid, "賭け金は " + MIN_BET + "〜" + MAX_BET + "G だよ"); return; }
        long mine = bets.stream().filter(b -> b.pid() == pid).count();
        if (mine >= MAX_BETS_PER_RACE) { ng(pid, "1レースに賭けられるのは " + MAX_BETS_PER_RACE + " 回までだよ"); return; }
        Runner r = runners.get(runner);
        double odds = "win".equals(kind) ? r.win : r.place;
        String nm = name == null ? "" : name.length() > 16 ? name.substring(0, 16) : name;
        bets.add(new Bet(pid, nm, runner, kind, amount, odds));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("type", "raceOk");
        ok.put("id", id);
        ok.put("runner", runner);
        ok.put("kind", kind);
        ok.put("amount", amount);
        ok.put("odds", odds);
        out.toPlayer(pid, ok);
        out.toAll(state(now));
    }

    private void ng(long pid, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "raceNg");
        m.put("text", text);
        out.toPlayer(pid, m);
    }

    // ------------------------------------------------------------------ 見せ方

    public synchronized Map<String, Object> state(long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "race");
        m.put("id", id);
        m.put("phase", phase);
        m.put("ms", Math.max(0, phaseEnd - now));
        List<Object> rs = new ArrayList<>();
        double[] pool = new double[RUNNERS];
        for (Bet b : bets) pool[b.runner()] += b.amount();
        for (int i = 0; i < runners.size(); i++) {
            Runner r = runners.get(i);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", r.name);
            e.put("color", COLORS[i]);
            e.put("form", r.form);
            e.put("formName", FORM[r.form]);
            e.put("win", r.win);
            e.put("place", r.place);
            e.put("pool", (long) pool[i]);
            rs.add(e);
        }
        m.put("runners", rs);
        if (!"bet".equals(phase)) {
            m.put("order", toList(order));
            m.put("times", toList(times));
            m.put("seed", seed);
            m.put("elapsed", "run".equals(phase) ? Math.max(0, now - (phaseEnd - (maxTime() + 1_200))) : maxTime() + 1_200);
        }
        List<Object> recent = new ArrayList<>();
        for (int i = Math.max(0, bets.size() - 10); i < bets.size(); i++) {
            Bet b = bets.get(i);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", b.name());
            e.put("runner", b.runner());
            e.put("kind", b.kind());
            e.put("amount", b.amount());
            recent.add(e);
        }
        m.put("bets", recent);
        List<Object> hist = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> h : history) { if (n++ >= 6) break; hist.add(h); }
        m.put("hist", hist);
        return m;
    }

    private int maxTime() { int t = 0; for (int x : times) t = Math.max(t, x); return t; }

    /** 精算用：最近のレースの id と着順（つなぎ直した人が、留守のあいだの賭けを精算できるように） */
    public synchronized List<Object> results() {
        List<Object> l = new ArrayList<>();
        for (Map<String, Object> h : history) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", h.get("id"));
            e.put("order", h.get("order"));
            l.add(e);
        }
        return l;
    }

    /** 新聞用：その日のレースのまとめ */
    public synchronized Map<String, Object> daySummary(int d) {
        int races = 0;
        Map<String, Object> best = null;
        long bestPay = 0;
        Map<String, Object> longshot = null;
        double bestOdds = 0;
        for (Map<String, Object> h : history) {
            if (!Integer.valueOf(d).equals(h.get("day"))) continue;
            races++;
            @SuppressWarnings("unchecked") List<Map<String, Object>> wins = (List<Map<String, Object>>) h.get("wins");
            if (!wins.isEmpty() && ((Number) wins.get(0).get("payout")).longValue() > bestPay) {
                bestPay = ((Number) wins.get(0).get("payout")).longValue();
                best = new LinkedHashMap<>(wins.get(0));
                @SuppressWarnings("unchecked") List<Object> names = (List<Object>) h.get("names");
                best.put("winner", names.get(0));
            }
            double o = ((Number) h.get("winOdds")).doubleValue();
            if (o > bestOdds) {
                bestOdds = o;
                longshot = new LinkedHashMap<>();
                @SuppressWarnings("unchecked") List<Object> names = (List<Object>) h.get("names");
                longshot.put("winner", names.get(0));
                longshot.put("odds", o);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("races", races);
        if (best != null) m.put("best", best);
        if (longshot != null) m.put("longshot", longshot);
        return m;
    }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    static List<Object> toList(int[] a) {
        List<Object> l = new ArrayList<>();
        for (int x : a) l.add(x);
        return l;
    }

    public synchronized int id() { return id; }
    public synchronized String phase() { return phase; }
}
