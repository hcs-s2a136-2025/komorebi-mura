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
        /** 馬主のどんぐり（厩舎）が変わったとき。保存する係が受けとる */
        default void saveStable(Map<String, Object> stable) { }
    }

    // ------------------------------------------------------------------ 馬主（v0.59.0）
    public static final long OWN_PRICE = 100_000, RETIRE_BACK = 30_000;
    public static final int OWN_MAX = 3, OWN_RACES_PER_DAY = 3, OWNED_PER_RACE = 2;
    public static final long[] PRIZE = { 20_000, 8_000, 3_000 };
    static final java.time.ZoneId JST = java.time.ZoneId.of("Asia/Tokyo");
    static final java.util.regex.Pattern OWN_NAME = java.util.regex.Pattern.compile("[^\\p{Cntrl}<>&\"'\\\\]{1,10}");

    /** 持ち主のいるどんぐり */
    static final class Owned {
        String k, name, ownerName = "", day = "", trainDay = "";
        long owner;
        double s, s0;
        int runs, wins, seconds, thirds, today;
        long prize;
        boolean entry = true;
    }

    private final Map<String, Owned> stable = new LinkedHashMap<>();
    private final Map<Long, Long> unpaid = new HashMap<>();
    private int ownSeq;

    public static final long BET_MS = 50_000, RESULT_MS = 12_000;
    public static final int RUNNERS = 6, MIN_BET = 10, MAX_BET = 50_000, MAX_BETS_PER_RACE = 12;
    static final String[] NAMES = { "クヌギ", "コナラ", "マテバシイ", "スダジイ", "ミズナラ", "カシワ", "ウバメガシ", "アラカシ", "シラカシ", "クリ" };
    static final String[] COLORS = { "#e2404e", "#4a8ae0", "#f0c030", "#4aa860", "#a070e0", "#f08a3a" };
    static final String[] FORM = { "", "不調", "いまいち", "ふつう", "好調", "絶好調" };

    /** 1頭（1粒）ぶん */
    static final class Runner {
        String name;
        Owned own;
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
        // 馬主のどんぐりを2つまで出す（出走登録していて、今日（日本時間）3回まで）
        String today = java.time.LocalDate.now(JST).toString();
        List<Owned> cand = new ArrayList<>();
        for (Owned o : stable.values()) {
            if (!today.equals(o.day)) { o.day = today; o.today = 0; }
            if (o.entry && o.today < OWN_RACES_PER_DAY) cand.add(o);
        }
        java.util.Collections.shuffle(cand, rnd);
        List<Owned> go = new ArrayList<>();
        java.util.Set<Long> owners = new java.util.HashSet<>();
        for (Owned o : cand) { if (go.size() >= OWNED_PER_RACE) break; if (owners.add(o.owner)) go.add(o); }
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < RUNNERS; i++) slots.add(i);
        java.util.Collections.shuffle(slots, rnd);
        Map<Integer, Owned> at = new HashMap<>();
        for (int i = 0; i < go.size(); i++) at.put(slots.get(i), go.get(i));
        for (int i = 0; i < RUNNERS; i++) {
            Runner r = new Runner();
            Owned o = at.get(i);
            r.own = o;
            r.name = o != null ? o.name : pool.remove(rnd.nextInt(pool.size())) + "号";
            r.strength = o != null ? o.s * (0.97 + rnd.nextDouble() * 0.06) : 0.72 + rnd.nextDouble() * 0.56;
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
        boolean changed = false;
        for (Runner r : runners) if (r.own != null) { r.own.today++; r.own.runs++; changed = true; }
        if (changed) saveStable();
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
        // 馬主のどんぐりの賞金（持ち主の「受けとっていない賞金」にためる）
        List<Object> own = new ArrayList<>();
        for (int pos = 0; pos < order.length; pos++) {
            Runner r = runners.get(order[pos]);
            if (r.own == null) continue;
            long pz = pos < PRIZE.length ? PRIZE[pos] : 0;
            if (pos == 0) r.own.wins++; else if (pos == 1) r.own.seconds++; else if (pos == 2) r.own.thirds++;
            if (pz > 0) { r.own.prize += pz; unpaid.merge(r.own.owner, pz, Long::sum); }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("owner", r.own.owner); e.put("ownerName", r.own.ownerName); e.put("name", r.own.name); e.put("pos", pos + 1); e.put("prize", pz);
            own.add(e);
            if (pz > 0) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("type", "raceOwn"); n.put("ev", "prize"); n.put("name", r.own.name); n.put("pos", pos + 1); n.put("prize", pz); n.put("unpaid", unpaid.getOrDefault(r.own.owner, 0L));
                out.toPlayer(r.own.owner, n);
            }
        }
        h.put("own", own);
        if (!own.isEmpty()) saveStable();
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

    /** 馬主の操作：list（一覧）・buy（買う。お金は OK が返ってから画面で払う）・train（1日1回の調教）・entry（出走登録の切りかえ）・retire（引退。30,000G）・claim（賞金を受けとる） */
    public synchronized void own(long pid, String name, String op, String arg, String key) {
        String today = java.time.LocalDate.now(JST).toString();
        String msg = null;
        boolean ok = true;
        long claimed = 0;
        Owned o = key == null ? null : stable.get(key);
        if (o != null && o.owner != pid) o = null;
        switch (op == null ? "" : op) {
            case "buy" -> {
                String nm = arg == null ? "" : arg.trim();
                long mine = stable.values().stream().filter(x -> x.owner == pid).count();
                if (mine >= OWN_MAX) { ok = false; msg = "持てるどんぐりは " + OWN_MAX + " 粒までだよ"; }
                else if (!OWN_NAME.matcher(nm).matches()) { ok = false; msg = "名前は1〜10文字で、記号の < > & などは使えないよ"; }
                else if (stable.values().stream().anyMatch(x -> x.name.equals(nm))) { ok = false; msg = "その名前のどんぐりは、もう村にいるよ"; }
                else {
                    Owned n = new Owned();
                    n.k = "o" + (++ownSeq) + Long.toString(Math.abs(rnd.nextLong()) % 1000, 36);
                    n.owner = pid; n.ownerName = name == null ? "" : name.length() > 16 ? name.substring(0, 16) : name; n.name = nm;
                    n.s0 = n.s = Math.round((0.86 + rnd.nextDouble() * 0.36) * 1000) / 1000.0;
                    stable.put(n.k, n);
                    msg = "「" + nm + "」がやってきた！";
                }
            }
            case "train" -> {
                if (o == null) { ok = false; msg = "そのどんぐりは見つからなかった"; }
                else if (today.equals(o.trainDay)) { ok = false; msg = "今日はもう調教したよ。また明日ね"; }
                else {
                    double cap = Math.min(1.40, o.s0 + 0.18), up = 0.006 + rnd.nextDouble() * 0.009;
                    double before = o.s;
                    o.s = Math.round(Math.min(cap, o.s + up) * 1000) / 1000.0; o.trainDay = today;
                    msg = o.s > before ? "調教した。「" + o.name + "」の走りが、少し力強くなった" : "調教した。「" + o.name + "」はもう、これ以上は強くならないみたい";
                }
            }
            case "entry" -> { if (o == null) { ok = false; msg = "そのどんぐりは見つからなかった"; } else { o.entry = !o.entry; msg = o.entry ? "出走登録した" : "出走登録をやめた（休ませる）"; } }
            case "retire" -> {
                if (o == null) { ok = false; msg = "そのどんぐりは見つからなかった"; }
                else { stable.remove(o.k); unpaid.merge(pid, RETIRE_BACK, Long::sum); msg = "「" + o.name + "」は引退した。おつかれさま（" + RETIRE_BACK + "G を受けとれるよ）"; }
            }
            case "claim" -> { claimed = unpaid.getOrDefault(pid, 0L); unpaid.remove(pid); if (claimed <= 0) { ok = false; msg = "受けとる賞金はないよ"; } }
            default -> { }
        }
        if (name != null) for (Owned x : stable.values()) if (x.owner == pid && !name.equals(x.ownerName)) x.ownerName = name.length() > 16 ? name.substring(0, 16) : name;
        if (!"list".equals(op)) saveStable();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "raceOwn"); r.put("op", op); r.put("ok", ok); if (msg != null) r.put("msg", msg);
        if (claimed > 0) r.put("claimed", claimed);
        r.put("price", OWN_PRICE); r.put("max", OWN_MAX); r.put("perDay", OWN_RACES_PER_DAY);
        r.put("unpaid", unpaid.getOrDefault(pid, 0L));
        List<Object> list = new ArrayList<>();
        for (Owned x : stable.values()) {
            if (x.owner != pid) continue;
            if (!today.equals(x.day)) { x.day = today; x.today = 0; }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("k", x.k); e.put("name", x.name); e.put("s", Math.round(x.s * 100)); e.put("cap", Math.round(Math.min(1.40, x.s0 + 0.18) * 100));
            e.put("runs", x.runs); e.put("wins", x.wins); e.put("seconds", x.seconds); e.put("thirds", x.thirds); e.put("prize", x.prize);
            e.put("today", x.today); e.put("entry", x.entry); e.put("trained", today.equals(x.trainDay));
            list.add(e);
        }
        r.put("list", list);
        // 村の馬主ランキング（賞金の合計）
        List<Object> top = new ArrayList<>();
        stable.values().stream().sorted((a, b) -> Long.compare(b.prize, a.prize)).limit(5).forEach(x -> {
            Map<String, Object> e = new LinkedHashMap<>(); e.put("name", x.name); e.put("ownerName", x.ownerName); e.put("wins", x.wins); e.put("prize", x.prize); top.add(e);
        });
        r.put("top", top);
        out.toPlayer(pid, r);
    }

    private void saveStable() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", ownSeq);
        List<Object> l = new ArrayList<>();
        for (Owned o : stable.values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("k", o.k); e.put("owner", o.owner); e.put("ownerName", o.ownerName); e.put("name", o.name); e.put("s", o.s); e.put("s0", o.s0);
            e.put("runs", o.runs); e.put("wins", o.wins); e.put("seconds", o.seconds); e.put("thirds", o.thirds); e.put("prize", o.prize);
            e.put("day", o.day); e.put("today", o.today); e.put("trainDay", o.trainDay); e.put("entry", o.entry);
            l.add(e);
        }
        m.put("list", l);
        Map<String, Object> up = new LinkedHashMap<>();
        unpaid.forEach((k, v) -> up.put(String.valueOf(k), v));
        m.put("unpaid", up);
        out.saveStable(m);
    }

    /** 保存してあった厩舎を読みこむ（サーバーを起動したとき） */
    public synchronized void loadStable(Map<?, ?> m) {
        if (m == null) return;
        stable.clear(); unpaid.clear();
        ownSeq = m.get("seq") instanceof Number n ? n.intValue() : 0;
        if (m.get("list") instanceof List<?> l) for (Object x : l) {
            if (!(x instanceof Map<?, ?> e)) continue;
            Owned o = new Owned();
            o.k = String.valueOf(e.get("k")); o.owner = num(e.get("owner")); o.ownerName = str(e.get("ownerName")); o.name = str(e.get("name"));
            o.s = dbl(e.get("s")); o.s0 = dbl(e.get("s0")); o.runs = (int) num(e.get("runs")); o.wins = (int) num(e.get("wins"));
            o.seconds = (int) num(e.get("seconds")); o.thirds = (int) num(e.get("thirds")); o.prize = num(e.get("prize"));
            o.day = str(e.get("day")); o.today = (int) num(e.get("today")); o.trainDay = str(e.get("trainDay")); o.entry = !Boolean.FALSE.equals(e.get("entry"));
            if (o.k.isEmpty() || o.name.isEmpty() || o.owner == 0 || o.s <= 0) continue;
            stable.put(o.k, o);
        }
        if (m.get("unpaid") instanceof Map<?, ?> up) up.forEach((k, v) -> { try { unpaid.put(Long.parseLong(String.valueOf(k)), num(v)); } catch (NumberFormatException ex) { } });
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0; }
    private static double dbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

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
            if (r.own != null) { e.put("owner", r.own.owner); e.put("ownerName", r.own.ownerName); }
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
