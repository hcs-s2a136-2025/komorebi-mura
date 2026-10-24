package jp.komorebi.sports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongPredicate;

/**
 * 村の運動会（管理人が開く）。赤と白の2チームで、かけっこ（連打）・玉入れ（タイミング）・借り物競走（言われた物を村でさがす）。
 * それぞれの競技は、みんなが同じ時間に自分の画面で遊んで、結果をサーバーに送る。チームの点は、その競技の参加者の平均。
 */
public final class SportsDayGame {

    public interface Out {
        void toAll(Map<String, Object> msg);
        void toPlayer(long playerId, Map<String, Object> msg);
    }

    public static final long RECRUIT_MS = 180_000, KAKE_MS = 20_000, TAMA_MS = 36_000, KARI_MS = 95_000, BREAK_MS = 7_000, RESULT_MS = 20_000;
    public static final String[] ROUNDS = { "kake", "tama", "kari" };
    /** 競技ごとの、ひとりの結果の上限と、点への換算 */
    static final int[] CAP = { 150, 40, 12 }, MUL = { 1, 10, 40 };
    public static final long[] REWARD = { 500, 1000, 1500 }; // 負け・引き分け・勝ち
    /** 借り物競走で「借りてくる」物（クライアントのアイテムID。村のあちこちに置かれる） */
    static final String[] THINGS = { "can", "hoe", "axe", "pick", "rod", "shovel", "tomato", "carrot", "cabbage", "egg", "milk", "acorn_normal" };

    static final class P {
        final long id;
        String name;
        final int team;
        final int[] score = { -1, -1, -1 };
        P(long id, String name, int team) { this.id = id; this.name = name; this.team = team; }
    }

    private final Random rnd;
    private final Out out;
    private final Map<Long, P> players = new LinkedHashMap<>();
    private String phase = "idle";
    private int round = -1;
    private long deadline;
    private final int[][] teamPts = new int[3][2];
    private List<Object> tasks = List.of();
    private int winner = -1;

    public SportsDayGame(Random rnd, Out out) {
        this.rnd = rnd;
        this.out = out;
    }

    // ---------- 管理人 ----------

    public synchronized boolean open(long now) {
        if (!"idle".equals(phase)) return false;
        players.clear(); round = -1; winner = -1; for (int[] t : teamPts) { t[0] = t[1] = 0; }
        phase = "recruit"; deadline = now + RECRUIT_MS;
        broadcast(now);
        return true;
    }

    public synchronized String startNow(long now) {
        if (!"recruit".equals(phase)) return "受付中ではありません";
        if (!startable()) return "赤と白に1人ずつ以上の参加が必要です";
        nextRound(now);
        return null;
    }

    public synchronized void stop(long now) {
        if ("idle".equals(phase)) return;
        reset();
        Map<String, Object> m = new LinkedHashMap<>(); m.put("type", "sdNg"); m.put("text", "運動会は中止になりました");
        out.toAll(m);
        broadcast(now);
    }

    // ---------- プレイヤー ----------

    public synchronized void command(long pid, String name, String act, int round, int value, long now) {
        P me = players.get(pid);
        switch (act) {
            case "look" -> out.toPlayer(pid, state(now));
            case "join" -> {
                if (me != null) { out.toPlayer(pid, state(now)); return; }
                if (!"recruit".equals(phase)) { ng(pid, "いまは参加の受付をしていないよ"); return; }
                int[] n = counts();
                int team = n[0] < n[1] ? 0 : n[1] < n[0] ? 1 : rnd.nextInt(2);
                players.put(pid, new P(pid, name, team));
                broadcast(now);
            }
            case "leave" -> { if (me != null && "recruit".equals(phase)) { players.remove(pid); broadcast(now); } }
            case "score" -> {
                if (me == null || !"play".equals(phase) || round != this.round || round < 0 || round > 2) return;
                int v = Math.max(0, Math.min(CAP[round], value));
                if (round == 2) me.score[2] = Math.max(me.score[2], v); else if (me.score[round] < 0) me.score[round] = v; else return;
                broadcast(now);
            }
            default -> { }
        }
    }

    public synchronized void tick(long now, LongPredicate online) {
        if ("idle".equals(phase)) return;
        if (now < deadline) return;
        switch (phase) {
            case "recruit" -> {
                if (startable()) nextRound(now);
                else { for (P p : players.values()) ng(p.id, "人数がそろわなかったので、運動会は中止になった"); reset(); broadcast(now); }
            }
            case "play" -> { scoreRound(); if (round >= 2) finish(now); else { phase = "break"; deadline = now + BREAK_MS; broadcast(now); } }
            case "break" -> nextRound(now);
            case "result" -> { reset(); broadcast(now); }
            default -> { }
        }
    }

    private void nextRound(long now) {
        round++;
        phase = "play";
        deadline = now + (round == 0 ? KAKE_MS : round == 1 ? TAMA_MS : KARI_MS);
        if (round == 2) {
            List<Object> t = new ArrayList<>();
            int prev = -1;
            for (int i = 0; i < CAP[2]; i++) { int k; do { k = rnd.nextInt(THINGS.length); } while (k == prev); t.add(THINGS[k]); prev = k; }
            tasks = t;
        }
        broadcast(now);
    }

    private void scoreRound() {
        for (int team = 0; team < 2; team++) {
            int sum = 0, n = 0;
            for (P p : players.values()) if (p.team == team) { sum += Math.max(0, p.score[round]) * MUL[round]; n++; }
            teamPts[round][team] = n == 0 ? 0 : Math.round((float) sum / n);
        }
    }

    private void finish(long now) {
        phase = "result"; deadline = now + RESULT_MS;
        int a = total(0), b = total(1);
        winner = a > b ? 0 : b > a ? 1 : 2;
        for (P p : players.values()) {
            int r = winner == 2 ? 1 : winner == p.team ? 2 : 0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "sdEnd"); m.put("result", r); m.put("money", REWARD[r]); m.put("team", p.team); m.put("total", List.of(a, b));
            out.toPlayer(p.id, m);
        }
        broadcast(now);
    }

    private int total(int team) { int s = 0; for (int[] t : teamPts) s += t[team]; return s; }

    private void reset() { phase = "idle"; round = -1; deadline = 0; players.clear(); tasks = List.of(); winner = -1; for (int[] t : teamPts) { t[0] = t[1] = 0; } }

    private int[] counts() { int[] n = new int[2]; for (P p : players.values()) n[p.team]++; return n; }

    private boolean startable() { int[] n = counts(); return n[0] >= 1 && n[1] >= 1; }

    private void ng(long pid, String text) { Map<String, Object> m = new LinkedHashMap<>(); m.put("type", "sdNg"); m.put("text", text); out.toPlayer(pid, m); }

    private void broadcast(long now) { out.toAll(state(now)); }

    public synchronized Map<String, Object> state(long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "sd");
        m.put("phase", phase);
        m.put("round", round);
        m.put("left", deadline > 0 ? Math.max(0, deadline - now) : 0);
        List<Object> ps = new ArrayList<>();
        for (P p : players.values()) { Map<String, Object> e = new LinkedHashMap<>(); e.put("id", p.id); e.put("name", p.name); e.put("team", p.team); e.put("score", List.of(p.score[0], p.score[1], p.score[2])); ps.add(e); }
        m.put("players", ps);
        List<Object> pts = new ArrayList<>(); for (int[] t : teamPts) pts.add(List.of(t[0], t[1]));
        m.put("points", pts);
        m.put("total", List.of(total(0), total(1)));
        if (round == 2) m.put("tasks", tasks);
        m.put("winner", winner);
        return m;
    }

    public synchronized boolean active() { return !"idle".equals(phase); }

    /** 管理人室用：{状態, 参加人数, 赤の点, 白の点} */
    public synchronized Object[] summary() { return new Object[] { phase, players.size(), total(0), total(1), round }; }
}
