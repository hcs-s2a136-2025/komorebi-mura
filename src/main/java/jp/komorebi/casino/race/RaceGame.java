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

    // ------------------------------------------------------------------ 主催レース（v0.71.0）
    // 馬主が賞金（purse）を出して「主催レース」を開く。ほかの馬主も自分のどんぐりを出走させられる（1人2粒まで・6粒まで）。
    // 受付は HOST_WAIT_MS。そのあとの最初のレースが主催レースになる（2粒そろわなければ取りやめ・賞金は主催者にもどる）。
    // 賞金は 1着60%・2着25%・3着15%。ふつうのどんぐり（持ち主なし）がその着に入った分は、主催者にもどる。ふつうの馬主の賞金（PRIZE）は出ない
    public static final long HOST_MIN = 10_000, HOST_MAX = 1_000_000;
    /** 主催レースの受付の長さ（模擬サーバーのテストでは短くできる） */
    public static long HOST_WAIT_MS = 180_000;
    public static final int HOST_PER_OWNER = 2;
    static final double[] HOST_SPLIT = { 0.60, 0.25, 0.15 };
    static final java.util.regex.Pattern HOST_TITLE = java.util.regex.Pattern.compile("[^\\p{Cntrl}<>&\"'\\\\]{1,14}");

    static final class Hosted {
        long host, purse, startAt;
        String hostName = "", title = "", cond = "";
        boolean grand;
        int year;
        final List<String> entries = new ArrayList<>();
    }

    // ------------------------------------------------------------------ 主催レースの特殊条件（v0.73.0）
    /** 条件の名前（画面にも出す）。heavy＝重馬場・long＝長距離・sprint＝短距離・handi＝ハンデ戦・debut＝新馬戦 */
    public static final Map<String, String> CONDS = new LinkedHashMap<>();
    static {
        CONDS.put("heavy", "重馬場");
        CONDS.put("long", "長距離");
        CONDS.put("sprint", "短距離");
        CONDS.put("handi", "ハンデ戦");
        CONDS.put("debut", "新馬戦");
    }
    /** 新馬戦に出られるのは、この数より少ない戦数のどんぐり */
    public static final int DEBUT_RUNS = 10;

    // ------------------------------------------------------------------ 年に1度の「こもれび記念」（v0.73.0）
    // 村の1年（4季節×7日＝28日）の、冬の最後の日の 18:00 のあとの最初のレース。前の日（冬の6日目）から出走の登録とファン投票ができる。
    // 出走は1人1粒・16粒まで登録できて、ファン投票の多い順（同じなら賞金の多い順）に6粒が走る。足りない分はふつうの強いどんぐり。
    // 賞金は6着まで（GRAND_PRIZE）。持ち主の unpaid にためる。
    public static final int YEAR_DAYS = 28, GRAND_ENTRY_MAX = 16;
    public static final double GRAND_MIN = 18 * 60;
    public static final long[] GRAND_PRIZE = { 1_000_000, 400_000, 200_000, 100_000, 60_000, 40_000 };
    private int grandDone, grandYearOf;
    private final List<String> grandEntries = new ArrayList<>();
    private final Map<Long, String> grandVotes = new LinkedHashMap<>();
    private final Deque<Map<String, Object>> grandWinners = new ArrayDeque<>();
    private double minute = -1;

    static int yearOf(int day) { return Math.max(0, day - 1) / YEAR_DAYS + 1; }
    static int dayInYear(int day) { return Math.max(0, day - 1) % YEAR_DAYS; }
    /** こもれび記念の登録・投票ができるか（冬の6日目と7日目、まだ走っていない年） */
    boolean grandOpen() { return day > 0 && dayInYear(day) >= YEAR_DAYS - 2 && grandDone < yearOf(day); }

    private Hosted hosted, running;
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
        tick(now, clockDay, -1);
    }

    /** minute＝村の時計（6:00＝360）。わからないときは -1（こもれび記念はその日の最初のレース） */
    public synchronized void tick(long now, int clockDay, double clockMinute) {
        day = clockDay;
        minute = clockMinute;
        int y = yearOf(day);
        if (day > 0 && grandYearOf != y && !grandOpen()) { grandYearOf = y; grandEntries.clear(); grandVotes.clear(); }
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
        running = null;
        if (grandOpen() && dayInYear(day) == YEAR_DAYS - 1 && (minute < 0 || minute >= GRAND_MIN)) {
            Hosted g = new Hosted();
            g.grand = true; g.year = yearOf(day); g.title = "第" + g.year + "回 こもれび記念"; g.hostName = "村";
            List<String> keys = new ArrayList<>(grandEntries);
            keys.removeIf(k -> !stable.containsKey(k));
            Map<String, Long> vc = new HashMap<>();
            for (String k : grandVotes.values()) vc.merge(k, 1L, Long::sum);
            keys.sort((a, b) -> { int c = Long.compare(vc.getOrDefault(b, 0L), vc.getOrDefault(a, 0L)); return c != 0 ? c : Long.compare(stable.get(b).prize, stable.get(a).prize); });
            for (String k : keys) { if (go.size() >= RUNNERS) break; go.add(stable.get(k)); }
            running = g;
            grandDone = g.year; grandYearOf = g.year;
            grandEntries.clear(); grandVotes.clear();
            saveStable();
        }
        if (running == null && hosted != null && now >= hosted.startAt) {
            Hosted h = hosted; hosted = null;
            List<Owned> es = new ArrayList<>();
            for (String k : h.entries) { Owned o = stable.get(k); if (o != null && es.size() < RUNNERS) es.add(o); }
            if (es.size() >= 2) { running = h; go.addAll(es); }
            else {
                unpaid.merge(h.host, h.purse, Long::sum);
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("type", "raceHost"); n.put("ev", "refund"); n.put("purse", h.purse);
                n.put("msg", "主催レース「" + h.title + "」は、出走するどんぐりが2粒そろわなかったので取りやめになった。賞金 " + h.purse + "G はもどるよ");
                out.toPlayer(h.host, n);
            }
            saveStable();
        }
        if (running == null) {
            java.util.Set<Long> owners = new java.util.HashSet<>();
            for (Owned o : cand) { if (go.size() >= OWNED_PER_RACE) break; if (owners.add(o.owner)) go.add(o); }
        }
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
            if (running != null && running.grand && o == null) { r.name = "名馬" + r.name; r.strength = 1.08 + rnd.nextDouble() * 0.2; }
            if (running != null && "handi".equals(running.cond)) r.strength = 1.0 * (0.98 + rnd.nextDouble() * 0.04);
            r.weight = Math.pow(r.strength, power());
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
        double tf = timeFactor();
        int t = (int) Math.round((15_000 + rnd.nextInt(3_000)) * tf);
        for (int pos = 0; pos < RUNNERS; pos++) {
            times[order[pos]] = t;
            t += (int) Math.round((200 + rnd.nextInt(1_100)) * Math.max(0.8, tf));
        }
        seed = rnd.nextInt(1_000_000_000);
        phase = "run";
        phaseEnd = now + t + 1_200;
        out.toAll(state(now));
    }

    /** 強さの重み（大きいほど強いどんぐりが勝ちやすい） */
    private double power() {
        if (running == null) return 6;
        if (running.grand) return 7;
        return switch (running.cond) { case "heavy" -> 3; case "long" -> 9; case "sprint" -> 5; default -> 6; };
    }

    /** 走る時間の倍率 */
    private double timeFactor() {
        if (running == null) return 1;
        if (running.grand) return 1.5;
        return switch (running.cond) { case "heavy" -> 1.15; case "long" -> 1.35; case "sprint" -> 0.7; default -> 1; };
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
            long pz = running != null && running.grand ? (pos < GRAND_PRIZE.length ? GRAND_PRIZE[pos] : 0)
                : running != null ? (pos < HOST_SPLIT.length ? Math.round(running.purse * HOST_SPLIT[pos]) : 0) : pos < PRIZE.length ? PRIZE[pos] : 0;
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
        if (running != null && running.grand) {
            Runner w = runners.get(order[0]);
            Map<String, Object> gw = new LinkedHashMap<>();
            gw.put("year", running.year); gw.put("name", w.name); gw.put("ownerName", w.own != null ? w.own.ownerName : "");
            if (w.own != null) gw.put("owner", w.own.owner);
            grandWinners.addFirst(gw);
            while (grandWinners.size() > 10) grandWinners.removeLast();
            h.put("grand", running.year);
            h.put("hosted", running.title);
            saveStable();
        } else if (running != null) {
            // ふつうのどんぐりが入った着の賞金は、主催者にもどす
            long back = 0;
            for (int pos = 0; pos < Math.min(HOST_SPLIT.length, order.length); pos++) if (runners.get(order[pos]).own == null) back += Math.round(running.purse * HOST_SPLIT[pos]);
            if (back > 0) {
                unpaid.merge(running.host, back, Long::sum);
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("type", "raceHost"); n.put("ev", "refund"); n.put("purse", back);
                n.put("msg", "主催レース「" + running.title + "」で、ふつうのどんぐりが入着した分の賞金 " + back + "G がもどってきた");
                out.toPlayer(running.host, n);
                saveStable();
            }
            h.put("hosted", running.title);
        }
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

    /** 主催レースの操作：book（title・purse。お金は OK が返ってから画面で払う）・cancel（主催者・受付中だけ。賞金は unpaid にもどる）・join／leave（自分のどんぐり k）・look */
    public synchronized void host(long pid, String name, String op, String title, long purse, String key, long now) {
        host(pid, name, op, title, purse, key, "", now);
    }

    /** cond＝主催レースの特殊条件（CONDS のどれか・なしは ""）。こもれび記念の gjoin／gleave／gvote（k）もここ */
    public synchronized void host(long pid, String name, String op, String title, long purse, String key, String cond, long now) {
        if (cond == null || !CONDS.containsKey(cond)) cond = "";
        String msg = null;
        boolean ok = true;
        Owned o = key == null ? null : stable.get(key);
        if (o != null && o.owner != pid) o = null;
        String nm = name == null ? "" : name.length() > 16 ? name.substring(0, 16) : name;
        switch (op == null ? "" : op) {
            case "book" -> {
                String t = title == null ? "" : title.trim();
                if (hosted != null) { ok = false; msg = "いまは「" + hosted.title + "」の受付中。終わってから開けるよ"; }
                else if (!HOST_TITLE.matcher(t).matches()) { ok = false; msg = "レースの名前は1〜14文字で、記号の < > & などは使えないよ"; }
                else if (purse < HOST_MIN || purse > HOST_MAX || purse % 1000 != 0) { ok = false; msg = "賞金は " + HOST_MIN + "〜" + HOST_MAX + "G（1,000Gきざみ）だよ"; }
                else {
                    Hosted h = new Hosted();
                    h.host = pid; h.hostName = nm; h.title = t; h.purse = purse; h.startAt = now + HOST_WAIT_MS; h.cond = cond;
                    hosted = h;
                    msg = "主催レース「" + t + "」" + (cond.isEmpty() ? "" : "（" + CONDS.get(cond) + "）") + "の受付をはじめた！ 馬主のみんなが、どんぐりを出走させられるよ";
                }
            }
            case "cancel" -> {
                if (hosted == null || hosted.host != pid) { ok = false; msg = "とりやめられる主催レースはないよ"; }
                else { unpaid.merge(pid, hosted.purse, Long::sum); msg = "主催レース「" + hosted.title + "」をとりやめた。賞金 " + hosted.purse + "G はもどるよ"; hosted = null; }
            }
            case "join" -> {
                if (hosted == null) { ok = false; msg = "いま受付中の主催レースはないよ"; }
                else if (o == null) { ok = false; msg = "そのどんぐりは見つからなかった"; }
                else if (hosted.entries.contains(o.k)) { ok = false; msg = "もう出走が決まっているよ"; }
                else if ("debut".equals(hosted.cond) && o.runs >= DEBUT_RUNS) { ok = false; msg = "新馬戦に出られるのは、" + DEBUT_RUNS + "戦より少ないどんぐりだけだよ"; }
                else if (hosted.entries.size() >= RUNNERS) { ok = false; msg = "出走は " + RUNNERS + " 粒まで。もういっぱいだよ"; }
                else if (hosted.entries.stream().map(stable::get).filter(x -> x != null && x.owner == pid).count() >= HOST_PER_OWNER) { ok = false; msg = "1人 " + HOST_PER_OWNER + " 粒までだよ"; }
                else { hosted.entries.add(o.k); msg = "「" + o.name + "」を「" + hosted.title + "」に出走させた"; }
            }
            case "leave" -> {
                if (hosted == null || o == null || !hosted.entries.remove(o.k)) { ok = false; msg = "とりけせる出走はないよ"; }
                else msg = "「" + o.name + "」の出走をとりけした";
            }
            case "gjoin" -> {
                if (!grandOpen()) { ok = false; msg = "こもれび記念の登録は、冬の6日目と7日目（レースの前まで）だよ"; }
                else if (o == null) { ok = false; msg = "そのどんぐりは見つからなかった"; }
                else if (grandEntries.contains(o.k)) { ok = false; msg = "もう登録してあるよ"; }
                else if (grandEntries.stream().map(stable::get).anyMatch(x -> x != null && x.owner == pid)) { ok = false; msg = "こもれび記念に登録できるのは、1人1粒までだよ"; }
                else if (grandEntries.size() >= GRAND_ENTRY_MAX) { ok = false; msg = "登録は " + GRAND_ENTRY_MAX + " 粒まで。もういっぱいだよ"; }
                else { grandEntries.add(o.k); msg = "「" + o.name + "」をこもれび記念に登録した。ファン投票の多い6粒が走るよ"; }
            }
            case "gleave" -> {
                if (o == null || !grandEntries.remove(o.k)) { ok = false; msg = "とりけせる登録はないよ"; }
                else { grandVotes.values().removeIf(o.k::equals); msg = "「" + o.name + "」の登録をとりけした"; }
            }
            case "gvote" -> {
                if (!grandOpen()) { ok = false; msg = "いまはファン投票の期間じゃないよ"; }
                else if (key == null || !grandEntries.contains(key)) { ok = false; msg = "そのどんぐりは登録されていないよ"; }
                else { grandVotes.put(pid, key); Owned v = stable.get(key); msg = "「" + (v == null ? "" : v.name) + "」に1票入れた（1人1票。あとから変えられるよ）"; }
            }
            default -> { }
        }
        if (!"look".equals(op)) saveStable();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "raceHost"); r.put("op", op); r.put("ok", ok); if (msg != null) r.put("msg", msg);
        if ("book".equals(op) && ok) r.put("purse", purse);
        r.put("min", HOST_MIN); r.put("max", HOST_MAX); r.put("wait", HOST_WAIT_MS);
        r.put("unpaid", unpaid.getOrDefault(pid, 0L));
        if (grandVotes.containsKey(pid)) r.put("gvote", grandVotes.get(pid));
        Map<String, Object> cs = new LinkedHashMap<>(CONDS);
        r.put("conds", cs);
        out.toPlayer(pid, r);
        if (!"look".equals(op) && ok) out.toAll(state(now));
    }

    private Map<String, Object> hostView(Hosted h, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", h.title); m.put("host", h.host); m.put("hostName", h.hostName); m.put("purse", h.purse);
        if (!h.cond.isEmpty()) { m.put("cond", h.cond); m.put("condName", CONDS.get(h.cond)); }
        m.put("startIn", Math.max(0, h.startAt - now));
        List<Object> es = new ArrayList<>();
        for (String k : h.entries) {
            Owned o = stable.get(k); if (o == null) continue;
            Map<String, Object> e = new LinkedHashMap<>(); e.put("k", o.k); e.put("name", o.name); e.put("owner", o.owner); e.put("ownerName", o.ownerName); e.put("s", Math.round(o.s * 100));
            es.add(e);
        }
        m.put("entries", es);
        return m;
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
        if (hosted != null) {
            Map<String, Object> hz = new LinkedHashMap<>();
            hz.put("host", hosted.host); hz.put("hostName", hosted.hostName); hz.put("title", hosted.title); hz.put("purse", hosted.purse); hz.put("startAt", hosted.startAt); hz.put("entries", new ArrayList<>(hosted.entries));
            hz.put("cond", hosted.cond);
            m.put("hosted", hz);
        }
        Map<String, Object> gz = new LinkedHashMap<>();
        gz.put("done", grandDone); gz.put("year", grandYearOf); gz.put("entries", new ArrayList<>(grandEntries));
        Map<String, Object> vz = new LinkedHashMap<>();
        grandVotes.forEach((k, v) -> vz.put(String.valueOf(k), v));
        gz.put("votes", vz); gz.put("winners", new ArrayList<>(grandWinners));
        m.put("grand", gz);
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
        hosted = null;
        if (m.get("hosted") instanceof Map<?, ?> hz) {
            Hosted h = new Hosted();
            h.host = num(hz.get("host")); h.hostName = str(hz.get("hostName")); h.title = str(hz.get("title")); h.purse = num(hz.get("purse")); h.startAt = num(hz.get("startAt"));
            if (hz.get("entries") instanceof List<?> el) for (Object x : el) if (x != null && stable.containsKey(String.valueOf(x))) h.entries.add(String.valueOf(x));
            h.cond = CONDS.containsKey(str(hz.get("cond"))) ? str(hz.get("cond")) : "";
            if (h.host != 0 && h.purse > 0 && !h.title.isEmpty()) hosted = h;
        }
        grandEntries.clear(); grandVotes.clear(); grandWinners.clear();
        if (m.get("grand") instanceof Map<?, ?> gz) {
            grandDone = (int) num(gz.get("done")); grandYearOf = (int) num(gz.get("year"));
            if (gz.get("entries") instanceof List<?> el) for (Object x : el) if (x != null && stable.containsKey(String.valueOf(x))) grandEntries.add(String.valueOf(x));
            if (gz.get("votes") instanceof Map<?, ?> vz) vz.forEach((k, v) -> { try { if (grandEntries.contains(String.valueOf(v))) grandVotes.put(Long.parseLong(String.valueOf(k)), String.valueOf(v)); } catch (NumberFormatException ex) { } });
            if (gz.get("winners") instanceof List<?> wl) for (Object x : wl) if (x instanceof Map<?, ?> w) { Map<String, Object> c = new LinkedHashMap<>(); w.forEach((k, v) -> c.put(String.valueOf(k), v)); grandWinners.addLast(c); }
        }
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
        if (hosted != null) m.put("host", hostView(hosted, now));
        if (running != null) {
            Map<String, Object> hr = new LinkedHashMap<>(); hr.put("title", running.title); hr.put("hostName", running.hostName); hr.put("purse", running.purse);
            if (running.grand) { hr.put("grand", running.year); hr.put("prize", toListL(GRAND_PRIZE)); }
            if (!running.cond.isEmpty()) { hr.put("cond", running.cond); hr.put("condName", CONDS.get(running.cond)); }
            m.put("hosted", hr);
        }
        // こもれび記念（登録の期間のあいだは登録と票、いつでも最近の勝ち馬）
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("year", yearOf(Math.max(1, day)) + (day > 0 && grandDone >= yearOf(day) ? 1 : 0));
        g.put("open", grandOpen());
        g.put("inYear", day > 0 ? dayInYear(day) + 1 : 0);
        g.put("at", (int) GRAND_MIN);
        g.put("prize", toListL(GRAND_PRIZE));
        if (grandOpen()) {
            Map<String, Long> vc = new HashMap<>();
            for (String k : grandVotes.values()) vc.merge(k, 1L, Long::sum);
            List<Object> ge = new ArrayList<>();
            for (String k : grandEntries) {
                Owned o = stable.get(k); if (o == null) continue;
                Map<String, Object> e = new LinkedHashMap<>(); e.put("k", o.k); e.put("name", o.name); e.put("owner", o.owner); e.put("ownerName", o.ownerName);
                e.put("s", Math.round(o.s * 100)); e.put("prize", o.prize); e.put("votes", vc.getOrDefault(k, 0L));
                ge.add(e);
            }
            g.put("entries", ge);
        }
        g.put("winners", new ArrayList<>(grandWinners));
        m.put("grand", g);
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

    static List<Object> toListL(long[] a) {
        List<Object> l = new ArrayList<>();
        for (long x : a) l.add(x);
        return l;
    }

    static List<Object> toList(int[] a) {
        List<Object> l = new ArrayList<>();
        for (int x : a) l.add(x);
        return l;
    }

    public synchronized int id() { return id; }
    public synchronized String phase() { return phase; }
}
