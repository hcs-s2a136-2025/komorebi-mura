package jp.komorebi.snow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongPredicate;

/**
 * 雪合戦の陣取り（冬だけ・北の丘）。赤と白の2チームで、相手の旗を自分の陣地に持ち帰ると1点。
 * 雪玉が当たると、3秒ひえて自分の陣地にもどされる（持っていた旗は、相手の陣地にもどる）。
 * 進行と得点はサーバーが決める。当たったかどうかは投げた人の画面で決めて知らせてくる。
 */
public final class SnowWarGame {

    public interface Out {
        void toAll(Map<String, Object> msg);
        void toPlayer(long playerId, Map<String, Object> msg);
    }

    public static final long RECRUIT_MS = 45_000, PLAY_MS = 240_000, RESULT_MS = 8_000, FREEZE_MS = 3_000;
    public static final int WIN_CAPS = 3, MAX_PLAYERS = 16;
    /** 陣地（旗の場所）：赤は西、白は東（北の丘） */
    public static final int[][] BASE = { { 6, 53 }, { 57, 53 } };
    static final String[] TEAM = { "赤", "白" };
    public static final long[] REWARD = { 300, 500, 1000 }; // 負け・引き分け・勝ち

    static final class P {
        final long id;
        String name;
        int team;
        long frozenUntil;
        P(long id, String name, int team) { this.id = id; this.name = name; this.team = team; }
    }

    private final Random rnd;
    private final Out out;
    private final Map<Long, P> players = new LinkedHashMap<>();
    private String phase = "idle";
    private long deadline;
    private final int[] score = new int[2];
    /** carrier[i]：チームiの旗を持っている人（0は陣地にある） */
    private final long[] carrier = new long[2];
    private int winner = -1;

    public SnowWarGame(Random rnd, Out out) {
        this.rnd = rnd;
        this.out = out;
    }

    /** x, y は、その人のいまの位置（サーバーが知っている位置）。winter は今が冬か */
    public synchronized void command(long pid, String name, String act, int x, int y, long target, boolean winter, long now) {
        P me = players.get(pid);
        switch (act) {
            case "look" -> out.toPlayer(pid, state(now));
            case "join" -> {
                if (me != null) { out.toPlayer(pid, state(now)); return; }
                if (!winter) { ng(pid, "雪合戦は、冬のあいだだけ遊べるよ"); return; }
                if ("result".equals(phase)) { ng(pid, "いまは結果発表中。少し待ってね"); return; }
                if (players.size() >= MAX_PLAYERS) { ng(pid, "参加できるのは16人までみたい"); return; }
                int[] n = counts();
                int team = n[0] < n[1] ? 0 : n[1] < n[0] ? 1 : rnd.nextInt(2);
                players.put(pid, new P(pid, name, team));
                if ("idle".equals(phase)) { phase = "recruit"; deadline = now + RECRUIT_MS; score[0] = score[1] = 0; carrier[0] = carrier[1] = 0; winner = -1; }
                broadcast(now);
            }
            case "leave" -> {
                if (me == null) return;
                drop(pid);
                players.remove(pid);
                if (players.isEmpty() && !"result".equals(phase)) reset();
                broadcast(now);
            }
            case "start" -> {
                if (me == null || !"recruit".equals(phase)) return;
                if (!startable()) { ng(pid, "赤と白に1人ずつ以上いると始められるよ"); return; }
                start(now);
            }
            case "hit" -> {
                P t = players.get(target);
                if (me == null || t == null || !"play".equals(phase) || me.team == t.team || me.frozenUntil > now || t.frozenUntil > now) return;
                t.frozenUntil = now + FREEZE_MS;
                drop(t.id);
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("type", "swHit"); h.put("by", me.name); h.put("until", FREEZE_MS);
                out.toPlayer(t.id, h);
                broadcast(now);
            }
            case "grab" -> {
                if (me == null || !"play".equals(phase) || me.frozenUntil > now) return;
                int enemy = 1 - me.team;
                if (carrier[enemy] != 0 || !near(x, y, BASE[enemy])) return;
                carrier[enemy] = pid;
                broadcast(now);
            }
            case "cap" -> {
                if (me == null || !"play".equals(phase)) return;
                int enemy = 1 - me.team;
                if (carrier[enemy] != pid || carrier[me.team] != 0 || !near(x, y, BASE[me.team])) return;
                carrier[enemy] = 0;
                score[me.team]++;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("type", "swCap"); c.put("name", me.name); c.put("team", me.team);
                out.toAll(c);
                if (score[me.team] >= WIN_CAPS) finish(now); else broadcast(now);
            }
            default -> { }
        }
    }

    public synchronized void tick(long now, LongPredicate online) {
        boolean changed = false;
        for (P p : new ArrayList<>(players.values())) if (!online.test(p.id)) { drop(p.id); players.remove(p.id); changed = true; }
        switch (phase) {
            case "recruit" -> {
                if (players.isEmpty()) { reset(); changed = true; }
                else if (now >= deadline) {
                    if (startable()) { start(now); return; }
                    for (P p : players.values()) ng(p.id, "人数がそろわなかったので、雪合戦は中止になった");
                    reset(); changed = true;
                }
            }
            case "play" -> {
                if (players.isEmpty()) { reset(); changed = true; }
                else if (now >= deadline) { finish(now); return; }
            }
            case "result" -> { if (now >= deadline) { reset(); changed = true; } }
            default -> { }
        }
        if (changed) broadcast(now);
    }

    private void start(long now) {
        phase = "play"; deadline = now + PLAY_MS; score[0] = score[1] = 0; carrier[0] = carrier[1] = 0; winner = -1;
        for (P p : players.values()) p.frozenUntil = 0;
        broadcast(now);
    }

    private void finish(long now) {
        phase = "result"; deadline = now + RESULT_MS;
        winner = score[0] > score[1] ? 0 : score[1] > score[0] ? 1 : 2;
        carrier[0] = carrier[1] = 0;
        for (P p : players.values()) {
            int r = winner == 2 ? 1 : winner == p.team ? 2 : 0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "swEnd"); m.put("result", r); m.put("money", REWARD[r]); m.put("score", List.of(score[0], score[1])); m.put("team", p.team);
            out.toPlayer(p.id, m);
        }
        broadcast(now);
    }

    private void reset() {
        phase = "idle"; deadline = 0; players.clear(); score[0] = score[1] = 0; carrier[0] = carrier[1] = 0; winner = -1;
    }

    private void drop(long pid) { for (int i = 0; i < 2; i++) if (carrier[i] == pid) carrier[i] = 0; }

    private int[] counts() { int[] n = new int[2]; for (P p : players.values()) n[p.team]++; return n; }

    private boolean startable() { int[] n = counts(); return n[0] >= 1 && n[1] >= 1; }

    private static boolean near(int x, int y, int[] b) { return Math.abs(x - b[0]) <= 1 && Math.abs(y - b[1]) <= 1; }

    private void ng(long pid, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "swNg"); m.put("text", text);
        out.toPlayer(pid, m);
    }

    private void broadcast(long now) { out.toAll(state(now)); }

    public synchronized Map<String, Object> state(long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "sw");
        m.put("phase", phase);
        m.put("left", deadline > 0 ? Math.max(0, deadline - now) : 0);
        List<Object> ps = new ArrayList<>();
        for (P p : players.values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", p.id); e.put("name", p.name); e.put("team", p.team); e.put("frozen", Math.max(0, p.frozenUntil - now));
            ps.add(e);
        }
        m.put("players", ps);
        m.put("score", List.of(score[0], score[1]));
        m.put("carrier", List.of(carrier[0], carrier[1]));
        m.put("winner", winner);
        return m;
    }

    public synchronized boolean active() { return !"idle".equals(phase); }
}
