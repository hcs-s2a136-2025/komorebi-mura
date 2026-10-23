package jp.komorebi.casino.mj;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongPredicate;

import jp.komorebi.casino.mj.MjRules.Meld;
import jp.komorebi.casino.mj.MjRules.Result;

/**
 * みんなで打つ麻雀の卓（いくつでも）。Spring にも WebSocket にも依存しない、ルールと進行だけのクラス。
 *
 * - 卓を立てた人が「東風戦／半荘戦」と「レート（ノーレート・点1・点5・テンピン・デカピン）」を決める
 * - 4人そろわないときは CPU が座る。途中で抜けた人の席も CPU（代打ち）が引き継ぐ
 * - 25000点持ちの30000点返し、ウマ10-20。精算は {@link Out#pay} で本人に伝える（お金は各自のセーブにあるため）
 * - 赤ドラ各1枚、喰いタン・後付けあり、頭ハネ、途中流局は九種九牌・四風連打・四家立直
 */
public final class MjGame {

    public interface Out {
        void toAll(Map<String, Object> msg);

        void toPlayer(long playerId, Map<String, Object> msg);

        /** 精算。money はプラスなら受け取り、マイナスなら払い */
        void pay(long playerId, long money, String text);
    }

    public static final int MAX_TABLES = 6;
    public static final long TURN_MS = 20_000, CLAIM_MS = 9_000, RESULT_MS = 15_000, END_MS = 40_000, CPU_MS = 650, RIICHI_AUTO_MS = 900, WAIT_OFFLINE_MS = 20_000, ASSIST_MS = 1_300;
    public static final long[] RATES = { 0, 10, 50, 100, 500 };
    public static final String[] RATE_NAMES = { "ノーレート", "点1（1000点=10G）", "点5（1000点=50G）", "テンピン（1000点=100G）", "デカピン（1000点=500G）" };
    private static final String[] CPU_NAMES = { "銀次", "タツ", "ミドリ", "ゲン", "ハル", "ミナミ" };
    private static final int[] UMA = { 20, 10, -10, -20 };

    private final Random rnd;
    private final Out out;
    private final Map<Integer, Table> tables = new LinkedHashMap<>();
    private final Map<Long, Integer> where = new HashMap<>();
    private int nextId = 1;

    public MjGame(Random rnd, Out out) {
        this.rnd = rnd;
        this.out = out;
    }

    // =====================================================================
    //  外からの入口
    // =====================================================================

    public synchronized void command(long pid, String name, String act, int a, int b, long now) {
        Integer tid = where.get(pid);
        Table t = tid == null ? null : tables.get(tid);
        switch (act) {
            case "list" -> { sendLobby(pid); if (t != null) t.sendTo(pid, now); }
            case "create" -> {
                if (t != null && "wait".equals(t.phase)) { t.leave(pid, now); t = null; }
                if (t != null) { busyElsewhere(pid, t, now); return; }
                if (tables.size() >= MAX_TABLES) { ng(pid, "いまは卓がいっぱい。空くまで少し待ってね"); return; }
                Table nt = new Table(nextId++, pid, a == 1, Math.max(0, Math.min(RATES.length - 1, b)));
                tables.put(nt.id, nt);
                nt.seats[0] = Seat.human(pid, name);
                where.put(pid, nt.id);
                lobbyChanged();
                nt.broadcast(now);
            }
            case "sit" -> {
                if (t != null && t.id == a) { t.sendTo(pid, now); return; }
                if (t != null && "wait".equals(t.phase)) { t.leave(pid, now); t = null; }
                if (t != null) { busyElsewhere(pid, t, now); return; }
                Table nt = tables.get(a);
                if (nt == null) { ng(pid, "その卓は見つからなかった"); return; }
                if (!"wait".equals(nt.phase)) { ng(pid, "その卓はもう始まっているみたい"); return; }
                int free = -1;
                for (int i = 0; i < 4; i++) if (nt.seats[i] == null) { free = i; break; }
                if (free < 0) { ng(pid, "満席みたい"); return; }
                nt.seats[free] = Seat.human(pid, name);
                where.put(pid, nt.id);
                lobbyChanged();
                if (nt.humans() == 4) nt.start(now);
                else nt.broadcast(now);
            }
            case "start" -> { if (t != null && "wait".equals(t.phase)) t.start(now); }
            case "leave" -> { if (t != null) t.leave(pid, now); else sendLobby(pid); }
            case "look" -> { if (t != null) t.sendTo(pid, now); else sendLobby(pid); }
            default -> { if (t != null) t.act(pid, act, a, b, now); }
        }
    }

    public synchronized void tick(long now, LongPredicate online) {
        for (Table t : new ArrayList<>(tables.values())) {
            try {
                t.tick(now, online);
            } catch (RuntimeException e) {
                // ひとつの卓がおかしくなっても、ほかの卓を止めない。おかしくなった卓は片付けて、座っていた人は一覧に戻す
                tables.remove(t.id);
                for (Seat s : t.seats) if (s != null && !s.cpu) { where.remove(s.id); ng(s.id, "卓の進行がおかしくなったので、いったん片付けたよ。ごめんね"); sendLobby(s.id); }
                lobbyChanged();
            }
        }
    }

    private void busyElsewhere(long pid, Table t, long now) {
        ng(pid, "卓" + t.id + "の対局がまだ続いてるよ。終わるか、卓を離れてから座ってね");
        t.sendTo(pid, now);
    }

    /** 管理人室用：卓の数と座っている人数 */
    public synchronized int[] stats() {
        int people = 0;
        for (Table t : tables.values()) people += t.humans();
        return new int[] { tables.size(), people };
    }

    private void ng(long pid, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "mjNg");
        m.put("text", text);
        out.toPlayer(pid, m);
    }

    private Map<String, Object> lobby() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "mjLobby");
        List<Object> list = new ArrayList<>();
        for (Table t : tables.values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", t.id);
            e.put("south", t.south);
            e.put("rate", t.rate);
            e.put("rateName", RATE_NAMES[t.rate]);
            e.put("phase", t.phase);
            List<Object> names = new ArrayList<>();
            for (Seat s : t.seats) names.add(s == null ? null : s.name + (s.cpu ? "（CPU）" : ""));
            e.put("seats", names);
            list.add(e);
        }
        m.put("tables", list);
        return m;
    }

    private void sendLobby(long pid) { out.toPlayer(pid, lobby()); }
    private void lobbyChanged() { out.toAll(lobby()); }

    // =====================================================================
    //  席
    // =====================================================================

    static final class River {
        final int tile;
        boolean riichi, taken;
        River(int tile) { this.tile = tile; }
    }

    static final class Seat {
        long id;
        String name;
        boolean cpu, auto;
        /** おまかせ打ち（ノーレートの卓だけ。CPUが代わりに打つのを、本人が選んだ） */
        boolean assist;
        /** ヒントを見る */
        boolean hint;
        int timeouts;
        int score = 25000;
        final List<Integer> hand = new ArrayList<>();
        final List<Meld> melds = new ArrayList<>();
        final List<River> river = new ArrayList<>();
        final Set<Integer> discKinds = new HashSet<>();
        boolean riichi, doubleRiichi, ippatsu, pendingRiichi, pendingDouble, furitenTemp, furitenRiichi, discarded, ready;
        /** 終局のあと「卓に戻る」を自分で押した */
        boolean back;
        /** 待ち卓で、つながらなくなった時刻（0はつながっている） */
        long offSince;
        int drawn = -1;
        final Set<Integer> banned = new HashSet<>();
        long actAt;

        static Seat human(long id, String name) {
            Seat s = new Seat();
            s.id = id;
            s.name = name;
            return s;
        }

        static Seat cpu(String name) {
            Seat s = new Seat();
            s.cpu = true;
            s.name = name;
            return s;
        }

        boolean bot() { return cpu || auto || assist; }
        /** 時間切れ・切断の代わり打ち。ツモ切りと見送りだけで、和了・鳴き・立直はしない */
        boolean weak() { return auto && !cpu && !assist; }

        void resetRound() {
            hand.clear(); melds.clear(); river.clear(); discKinds.clear(); banned.clear();
            riichi = doubleRiichi = ippatsu = pendingRiichi = pendingDouble = furitenTemp = furitenRiichi = discarded = ready = false;
            drawn = -1;
        }

        int[] closed() { return MjRules.counts(hand); }
        boolean menzen() { return melds.stream().noneMatch(Meld::open); }
    }

    /** ひとりぶんの「鳴ける・和了れる」 */
    static final class Claim {
        boolean ron, pon, minkan;
        final List<int[]> chi = new ArrayList<>();
        String answer;
        int chiIdx;
        boolean any() { return ron || pon || minkan || !chi.isEmpty(); }
    }

    // =====================================================================
    //  卓
    // =====================================================================

    final class Table {
        final int id;
        long owner;
        final boolean south;
        final int rate;
        String phase = "wait";
        final Seat[] seats = new Seat[4];
        long deadline;
        String msg = "";

        int kyoku, honba, sticks;
        final List<Integer> live = new ArrayList<>();
        final int[] dead = new int[14];
        int drawPos, liveEnd, kans;
        int turn;
        String sub = "turn";
        boolean afterCall, rinshan, noCalls, chankanClaim;
        int lastTile = -1, lastFrom = -1;
        final Map<Integer, Claim> claims = new HashMap<>();
        int discards;
        final List<Integer> firstWinds = new ArrayList<>();
        Map<String, Object> result;
        final List<Integer> kanBy = new ArrayList<>();

        Table(int id, long owner, boolean south, int rate) {
            this.id = id;
            this.owner = owner;
            this.south = south;
            this.rate = rate;
        }

        int humans() {
            int n = 0;
            for (Seat s : seats) if (s != null && !s.cpu) n++;
            return n;
        }

        int dealer() { return kyoku % 4; }
        int wind(int seat) { return (seat - dealer() + 4) % 4; }
        int remaining() { return liveEnd - drawPos; }
        int lastKyoku() { return south ? 7 : 3; }

        int seatOf(long pid) {
            for (int i = 0; i < 4; i++) if (seats[i] != null && !seats[i].cpu && seats[i].id == pid) return i;
            return -1;
        }

        // ---------- 始める・終わる ----------

        void start(long now) {
            List<String> pool = new ArrayList<>(List.of(CPU_NAMES));
            Collections.shuffle(pool, rnd);
            for (int i = 0; i < 4; i++) if (seats[i] == null) seats[i] = Seat.cpu(pool.remove(0));
            // 起家をくじで決める（席をずらす）
            int r = rnd.nextInt(4);
            Seat[] tmp = seats.clone();
            for (int i = 0; i < 4; i++) seats[i] = tmp[(i + r) % 4];
            for (Seat s : seats) { s.score = 25000; s.timeouts = 0; s.auto = false; }
            kyoku = 0; honba = 0; sticks = 0;
            phase = "play";
            lobbyChanged();
            startRound(now);
        }

        void startRound(long now) {
            List<Integer> wall = new ArrayList<>();
            for (int i = 0; i < 136; i++) wall.add(i);
            Collections.shuffle(wall, rnd);
            for (int i = 0; i < 14; i++) dead[i] = wall.get(122 + i);
            live.clear();
            live.addAll(wall.subList(0, 122));
            drawPos = 0; liveEnd = 122; kans = 0; kanBy.clear();
            for (Seat s : seats) s.resetRound();
            for (int r = 0; r < 13; r++) for (int i = 0; i < 4; i++) seats[(dealer() + i) % 4].hand.add(live.get(drawPos++));
            noCalls = true; discards = 0; firstWinds.clear(); result = null;
            lastTile = -1; lastFrom = -1; claims.clear(); chankanClaim = false;
            msg = (kyoku < 4 ? "東" : "南") + (kyoku % 4 + 1) + "局" + (honba > 0 ? " " + honba + "本場" : "");
            turn = dealer();
            draw(turn, false, now);
        }

        int[] doraKinds() {
            int[] d = new int[1 + kans];
            for (int i = 0; i <= kans; i++) d[i] = MjRules.doraOf(MjRules.kind(dead[4 + i]));
            return d;
        }

        int[] uraKinds() {
            int[] d = new int[1 + kans];
            for (int i = 0; i <= kans; i++) d[i] = MjRules.doraOf(MjRules.kind(dead[9 + i]));
            return d;
        }

        // ---------- ツモ ----------

        void draw(int seat, boolean fromDead, long now) {
            Seat s = seats[seat];
            int tile;
            if (fromDead) { tile = dead[kans - 1]; liveEnd--; rinshan = true; }
            else { tile = live.get(drawPos++); rinshan = false; }
            s.hand.add(tile);
            s.drawn = tile;
            turn = seat;
            sub = "turn";
            afterCall = false;
            s.banned.clear();
            boolean onlyDiscard = s.riichi && !canTsumo(seat) && kanOptions(seat).isEmpty();
            deadline = now + (onlyDiscard ? RIICHI_AUTO_MS : TURN_MS);
            s.actAt = now + (s.assist ? ASSIST_MS : CPU_MS);
            broadcast(now);
        }

        MjRules.Ctx ctx(int seat, boolean tsumo, int winTile) {
            Seat s = seats[seat];
            MjRules.Ctx c = new MjRules.Ctx();
            c.seatWind = wind(seat);
            c.roundWind = kyoku < 4 ? 0 : 1;
            c.tsumo = tsumo;
            c.riichi = s.riichi;
            c.doubleRiichi = s.doubleRiichi;
            c.ippatsu = s.ippatsu;
            c.dora = doraKinds();
            c.ura = uraKinds();
            int reds = 0;
            for (int t : s.hand) if (MjRules.red(t)) reds++;
            for (Meld m : s.melds) for (int t : m.tiles) if (MjRules.red(t)) reds++;
            if (!tsumo && MjRules.red(winTile)) reds++;
            c.reds = reds;
            if (tsumo) {
                c.rinshan = rinshan;
                c.haitei = !rinshan && remaining() == 0;
                boolean first = noCalls && !s.discarded;
                c.tenhou = first && seat == dealer();
                c.chiihou = first && seat != dealer();
            } else {
                c.houtei = remaining() == 0 && !chankanClaim;
                c.chankan = chankanClaim;
            }
            return c;
        }

        Result evalTsumo(int seat) {
            Seat s = seats[seat];
            if (s.drawn < 0 || afterCall) return null;
            return MjRules.evaluate(s.closed(), s.melds, MjRules.kind(s.drawn), ctx(seat, true, s.drawn));
        }

        boolean canTsumo(int seat) { return evalTsumo(seat) != null; }

        Result evalRon(int seat, int tile) {
            Seat s = seats[seat];
            int[] c = s.closed();
            c[MjRules.kind(tile)]++;
            return MjRules.evaluate(c, s.melds, MjRules.kind(tile), ctx(seat, false, tile));
        }

        boolean furiten(int seat) {
            Seat s = seats[seat];
            if (s.furitenTemp || s.furitenRiichi) return true;
            for (int w : MjRules.waits(s.closed(), s.melds.size())) if (s.discKinds.contains(w)) return true;
            return false;
        }

        /** 立直できるときの、切れる牌（なければ空） */
        List<Integer> riichiTiles(int seat) {
            Seat s = seats[seat];
            List<Integer> out = new ArrayList<>();
            if (s.riichi || afterCall || !s.menzen() || s.score < 1000 || remaining() < 4 || s.hand.size() % 3 != 2) return out;
            Set<Integer> seen = new HashSet<>();
            for (int t : s.hand) {
                int k = MjRules.kind(t);
                if (!seen.add(k * 2 + (MjRules.red(t) ? 1 : 0))) continue;
                int[] c = s.closed();
                c[k]--;
                if (!MjRules.waits(c, s.melds.size()).isEmpty()) out.add(t);
            }
            return out;
        }

        /** 暗槓・加槓できる種類 */
        List<Integer> kanOptions(int seat) {
            Seat s = seats[seat];
            List<Integer> out = new ArrayList<>();
            if (afterCall || kans >= 4 || remaining() <= 0 || s.hand.size() % 3 != 2) return out;
            int[] c = s.closed();
            for (int k = 0; k < 34; k++) {
                if (c[k] == 4) {
                    if (s.riichi) {
                        // 立直中は、ツモってきた牌で、待ちが変わらないときだけ
                        if (MjRules.kind(s.drawn) != k) continue;
                        int[] before = c.clone(); before[k]--;
                        int[] after = c.clone(); after[k] = 0;
                        if (!MjRules.waits(before, s.melds.size()).equals(MjRules.waits(after, s.melds.size() + 1))) continue;
                    }
                    out.add(k);
                }
            }
            if (!s.riichi) for (Meld m : s.melds) if ("pon".equals(m.type) && c[m.kind()] >= 1) out.add(m.kind());
            return out;
        }

        boolean canKyuushu(int seat) {
            Seat s = seats[seat];
            if (!noCalls || s.discarded || afterCall) return false;
            int[] c = s.closed();
            int kinds = 0;
            for (int k = 0; k < 34; k++) if (c[k] > 0 && MjRules.yaochu(k)) kinds++;
            return kinds >= 9;
        }

        // ---------- 打牌 ----------

        void discard(int seat, int tile, boolean declare, long now) {
            Seat s = seats[seat];
            s.hand.remove(Integer.valueOf(tile));
            River r = new River(tile);
            s.river.add(r);
            s.discKinds.add(MjRules.kind(tile));
            s.furitenTemp = false;
            s.ippatsu = false;
            if (declare) { s.pendingRiichi = true; s.pendingDouble = noCalls && !s.discarded; r.riichi = true; }
            s.discarded = true;
            s.drawn = -1;
            s.banned.clear();
            discards++;
            if (noCalls && discards <= 4) firstWinds.add(MjRules.kind(tile));
            lastTile = tile;
            lastFrom = seat;
            openClaims(seat, tile, false, now);
        }

        /** 捨て牌（または加槓の牌）に、ほかの人が鳴けるか・和了れるか */
        void openClaims(int from, int tile, boolean chankan, long now) {
            claims.clear();
            chankanClaim = chankan;
            int k = MjRules.kind(tile);
            for (int d = 1; d <= 3; d++) {
                int o = (from + d) % 4;
                Seat s = seats[o];
                Claim c = new Claim();
                if (!furiten(o) && evalRon(o, tile) != null) c.ron = true;
                else if (MjRules.waits(s.closed(), s.melds.size()).contains(k)) {
                    // 役がなくて和了れないときも、見逃したことになる
                    s.furitenTemp = true;
                    if (s.riichi) s.furitenRiichi = true;
                }
                if (!chankan && remaining() > 0 && !s.riichi) {
                    int n = 0;
                    for (int t : s.hand) if (MjRules.kind(t) == k) n++;
                    if (n >= 2) c.pon = true;
                    if (n >= 3 && kans < 4) c.minkan = true;
                    if (d == 1 && k < 27) c.chi.addAll(chiOptions(s, tile));
                }
                if (c.any()) claims.put(o, c);
            }
            if (claims.isEmpty()) { if (chankan) finishKakan(now); else afterDiscard(now); return; }
            sub = "claim";
            deadline = now + CLAIM_MS;
            for (Map.Entry<Integer, Claim> e : claims.entrySet()) {
                Seat s = seats[e.getKey()];
                if (s.bot()) e.getValue().answer = MjAi.claim(this, e.getKey(), e.getValue(), tile);
            }
            if (allAnswered()) resolve(now);
            else broadcast(now);
        }

        List<int[]> chiOptions(Seat s, int tile) {
            int k = MjRules.kind(tile), n = k % 9;
            List<int[]> out = new ArrayList<>();
            int[][] pats = { { -2, -1 }, { -1, 1 }, { 1, 2 } };
            for (int[] p : pats) {
                int a = k + p[0], b = k + p[1];
                if (n + p[0] < 0 || n + p[1] > 8 || n + p[0] > 8 || n + p[1] < 0) continue;
                int ta = pick(s.hand, a, -1), tb = pick(s.hand, b, ta);
                if (ta < 0 || tb < 0) continue;
                // 喰い替え：鳴いたあとに切れる牌が残らないなら鳴けない
                Set<Integer> ban = kuikae(k, a, b);
                long rest = s.hand.stream().filter(x -> x != ta && x != tb && !ban.contains(MjRules.kind(x))).count();
                if (rest == 0) continue;
                out.add(new int[] { ta, tb });
            }
            return out;
        }

        Set<Integer> kuikae(int called, int a, int b) {
            Set<Integer> ban = new HashSet<>();
            ban.add(called);
            int lo = Math.min(called, Math.min(a, b)), hi = Math.max(called, Math.max(a, b));
            if (called == lo && hi % 9 < 8) ban.add(hi + 1);
            if (called == hi && lo % 9 > 0) ban.add(lo - 1);
            return ban;
        }

        /** 手からその種類の牌を1枚えらぶ（赤は残す方を優先） */
        int pick(List<Integer> hand, int kind, int not) {
            int found = -1;
            for (int t : hand) {
                if (t == not || MjRules.kind(t) != kind) continue;
                if (found < 0 || (MjRules.red(found) && !MjRules.red(t))) found = t;
            }
            return found;
        }

        boolean allAnswered() {
            for (Claim c : claims.values()) if (c.answer == null) return false;
            return true;
        }

        void resolve(long now) {
            int from = lastFrom, tile = lastTile;
            // ロン（頭ハネ：打った人から近い順）
            for (int d = 1; d <= 3; d++) {
                int o = (from + d) % 4;
                Claim c = claims.get(o);
                if (c != null && c.ron && "ron".equals(c.answer)) { agari(o, from, tile, now); return; }
            }
            for (Map.Entry<Integer, Claim> e : claims.entrySet()) {
                Claim c = e.getValue();
                if (c.ron && !"ron".equals(c.answer)) {
                    Seat s = seats[e.getKey()];
                    s.furitenTemp = true;
                    if (s.riichi) s.furitenRiichi = true;
                }
            }
            if (chankanClaim) { claims.clear(); finishKakan(now); return; }
            establishRiichi(from);
            if (riichiAbort(now)) return;
            for (Map.Entry<Integer, Claim> e : claims.entrySet()) {
                Claim c = e.getValue();
                if ("pon".equals(c.answer) && c.pon) { call(e.getKey(), "pon", null, now); return; }
                if ("minkan".equals(c.answer) && c.minkan) { call(e.getKey(), "minkan", null, now); return; }
            }
            for (Map.Entry<Integer, Claim> e : claims.entrySet()) {
                Claim c = e.getValue();
                if ("chi".equals(c.answer) && c.chiIdx >= 0 && c.chiIdx < c.chi.size()) { call(e.getKey(), "chi", c.chi.get(c.chiIdx), now); return; }
            }
            claims.clear();
            afterDiscard(now);
        }

        void establishRiichi(int seat) {
            Seat s = seats[seat];
            if (!s.pendingRiichi) return;
            s.pendingRiichi = false;
            s.riichi = true;
            s.doubleRiichi = s.pendingDouble;
            s.ippatsu = true;
            s.score -= 1000;
            sticks++;
        }

        boolean riichiAbort(long now) {
            for (Seat s : seats) if (!s.riichi) return false;
            abort("四家立直", now);
            return true;
        }

        void afterDiscard(long now) {
            claims.clear();
            establishRiichi(lastFrom);
            if (riichiAbort(now)) return;
            if (noCalls && firstWinds.size() == 4 && firstWinds.get(0) >= 27 && firstWinds.get(0) <= 30 && firstWinds.stream().allMatch(w -> w.equals(firstWinds.get(0)))) {
                abort("四風連打", now);
                return;
            }
            if (remaining() <= 0) { exhaustive(now); return; }
            draw((lastFrom + 1) % 4, false, now);
        }

        void call(int seat, String type, int[] chiTiles, long now) {
            Seat s = seats[seat];
            int k = MjRules.kind(lastTile);
            int[] tiles;
            if ("chi".equals(type)) {
                s.hand.remove(Integer.valueOf(chiTiles[0]));
                s.hand.remove(Integer.valueOf(chiTiles[1]));
                tiles = new int[] { chiTiles[0], chiTiles[1], lastTile };
                java.util.Arrays.sort(tiles);
                s.banned.addAll(kuikae(k, MjRules.kind(chiTiles[0]), MjRules.kind(chiTiles[1])));
            } else {
                int n = "pon".equals(type) ? 2 : 3;
                tiles = new int[n + 1];
                for (int i = 0; i < n; i++) { int t = pick(s.hand, k, -1); s.hand.remove(Integer.valueOf(t)); tiles[i] = t; }
                tiles[n] = lastTile;
                if ("pon".equals(type)) s.banned.add(k);
            }
            seats[lastFrom].river.get(seats[lastFrom].river.size() - 1).taken = true;
            s.melds.add(new Meld(type, tiles, lastFrom, lastTile));
            noCalls = false;
            for (Seat x : seats) x.ippatsu = false;
            claims.clear();
            turn = seat;
            if ("minkan".equals(type)) {
                kans++;
                kanBy.add(seat);
                draw(seat, true, now);
                return;
            }
            sub = "turn";
            afterCall = true;
            s.drawn = -1;
            deadline = now + TURN_MS;
            s.actAt = now + CPU_MS;
            broadcast(now);
        }

        void ankan(int seat, int k, long now) {
            Seat s = seats[seat];
            int[] tiles = new int[4];
            int i = 0;
            for (int t : new ArrayList<>(s.hand)) if (MjRules.kind(t) == k && i < 4) { tiles[i++] = t; s.hand.remove(Integer.valueOf(t)); }
            s.melds.add(new Meld("ankan", tiles, -1, -1));
            noCalls = false;
            for (Seat x : seats) x.ippatsu = false;
            kans++;
            kanBy.add(seat);
            draw(seat, true, now);
        }

        int kakanSeat = -1;

        void kakan(int seat, int k, long now) {
            Seat s = seats[seat];
            int t = pick(s.hand, k, -1);
            for (int i = 0; i < s.melds.size(); i++) {
                Meld m = s.melds.get(i);
                if ("pon".equals(m.type) && m.kind() == k) {
                    int[] tiles = java.util.Arrays.copyOf(m.tiles, 4);
                    tiles[3] = t;
                    s.melds.set(i, new Meld("kakan", tiles, m.from, m.called));
                }
            }
            s.hand.remove(Integer.valueOf(t));
            s.drawn = -1;
            kakanSeat = seat;
            lastTile = t;
            lastFrom = seat;
            openClaims(seat, t, true, now); // 槍槓
        }

        void finishKakan(long now) {
            chankanClaim = false;
            noCalls = false;
            for (Seat x : seats) x.ippatsu = false;
            kans++;
            kanBy.add(kakanSeat);
            draw(kakanSeat, true, now);
        }

        // ---------- 和了・流局 ----------

        void agari(int winner, int from, int tile, long now) {
            Seat w = seats[winner];
            boolean tsumo = from < 0;
            Result r = tsumo ? evalTsumo(winner) : evalRon(winner, tile);
            if (r == null) return; // 念のため
            boolean dealerWin = winner == dealer();
            int[] delta = new int[4];
            int[] p = MjRules.pay(r, dealerWin, tsumo);
            if (!tsumo) {
                int v = p[0] + 300 * honba;
                delta[from] -= v;
                delta[winner] += v;
            } else {
                for (int i = 0; i < 4; i++) {
                    if (i == winner) continue;
                    int v = (dealerWin ? p[0] : (i == dealer() ? p[0] : p[1])) + 100 * honba;
                    delta[i] -= v;
                    delta[winner] += v;
                }
            }
            delta[winner] += sticks * 1000;
            sticks = 0;
            for (int i = 0; i < 4; i++) seats[i].score += delta[i];
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("kind", "agari");
            res.put("winner", winner);
            res.put("from", from);
            res.put("tsumo", tsumo);
            res.put("tile", tile);
            List<Object> yaku = new ArrayList<>();
            for (MjRules.Yaku y : r.yaku) yaku.add(List.of(y.name, y.yakuman ? "役満" : y.han + "飜"));
            res.put("yaku", yaku);
            res.put("han", r.han);
            res.put("fu", r.fu);
            res.put("label", r.label());
            res.put("points", delta[winner]);
            res.put("value", tsumo ? (dealerWin ? p[0] * 3 : p[0] + p[1] * 2) : p[0]);
            res.put("delta", toList(delta));
            List<Integer> hand = new ArrayList<>(w.hand);
            if (!tsumo) hand.add(tile);
            res.put("hand", hand);
            res.put("melds", meldsView(w));
            if (w.riichi) res.put("ura", indicators(true));
            result = res;
            boolean renchan = dealerWin;
            honba = renchan ? honba + 1 : 0;
            if (!renchan) kyoku++;
            String who = w.name;
            msg = tsumo ? who + "のツモ和了" : who + "のロン和了（" + seats[from].name + "から）";
            toResult(now, renchan);
        }

        void exhaustive(long now) {
            boolean[] tenpai = new boolean[4];
            int n = 0;
            for (int i = 0; i < 4; i++) if (!MjRules.waits(seats[i].closed(), seats[i].melds.size()).isEmpty()) { tenpai[i] = true; n++; }
            int[] delta = new int[4];
            if (n > 0 && n < 4) for (int i = 0; i < 4; i++) delta[i] = tenpai[i] ? 3000 / n : -3000 / (4 - n);
            for (int i = 0; i < 4; i++) seats[i].score += delta[i];
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("kind", "draw");
            res.put("name", "流局");
            List<Object> tp = new ArrayList<>();
            List<Object> hands = new ArrayList<>();
            for (int i = 0; i < 4; i++) { tp.add(tenpai[i]); hands.add(tenpai[i] ? new ArrayList<>(seats[i].hand) : null); }
            res.put("tenpai", tp);
            res.put("hands", hands);
            res.put("delta", toList(delta));
            result = res;
            boolean renchan = tenpai[dealer()];
            honba++;
            if (!renchan) kyoku++;
            msg = "流局";
            toResult(now, renchan);
        }

        void abort(String name, long now) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("kind", "draw");
            res.put("name", name);
            res.put("delta", toList(new int[4]));
            result = res;
            honba++;
            msg = "途中流局（" + name + "）";
            toResult(now, true);
        }

        boolean lastRenchan;

        void toResult(long now, boolean renchan) {
            lastRenchan = renchan;
            phase = "result";
            sub = "";
            claims.clear();
            for (Seat s : seats) s.ready = s.bot();
            deadline = now + RESULT_MS;
            broadcast(now);
        }

        void next(long now) {
            boolean bust = false;
            for (Seat s : seats) if (s.score < 0) bust = true;
            boolean over = bust || kyoku > lastKyoku();
            // オーラスで親が連荘して、親がトップならそこで終わる（和了りやめ）
            if (!over && lastRenchan && kyoku == lastKyoku()) {
                int d = dealer();
                boolean top = true;
                for (int i = 0; i < 4; i++) if (i != d && seats[i].score >= seats[d].score) top = false;
                if (top && seats[d].score >= 30000) over = true;
            }
            if (over) { end(now); return; }
            phase = "play";
            startRound(now);
        }

        void end(long now) {
            Integer[] order = { 0, 1, 2, 3 };
            java.util.Arrays.sort(order, (x, y) -> seats[y].score != seats[x].score ? seats[y].score - seats[x].score : x - y);
            List<Object> rank = new ArrayList<>();
            for (int r = 0; r < 4; r++) {
                Seat s = seats[order[r]];
                double pt = (s.score - 30000) / 1000.0 + UMA[r] + (r == 0 ? 20 : 0);
                pt = Math.round(pt * 10) / 10.0;
                long money = Math.round(pt * RATES[rate]);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("seat", order[r]);
                e.put("name", s.name);
                e.put("score", s.score);
                e.put("pt", pt);
                e.put("money", s.cpu ? 0 : money);
                e.put("cpu", s.cpu);
                rank.add(e);
                if (!s.cpu) out.pay(s.id, money, "麻雀 " + (r + 1) + "位（" + (pt >= 0 ? "+" : "") + pt + "）");
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("kind", "end");
            res.put("rank", rank);
            result = res;
            phase = "end";
            sub = "";
            msg = "対局終了";
            deadline = now + END_MS;
            for (Seat s : seats) s.ready = s.bot();
            broadcast(now);
        }

        /** 終局のあと、人だけ残して卓を待ち状態に戻す */
        void reset(long now) {
            List<Long> gone = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Seat s = seats[i];
                if (s == null) continue;
                if (s.cpu) { seats[i] = null; continue; }
                // 「卓に戻る」を押さなかった人（閉じた・つながっていない人）は席を空ける。そうしないと幽霊の席で4人目が座れない
                if (!s.back) { seats[i] = null; where.remove(s.id); gone.add(s.id); continue; }
                s.back = false; s.ready = false; s.offSince = 0;
            }
            phase = "wait";
            result = null;
            msg = "";
            for (long g : gone) { sendLobby(g); sendTo(g, now); }
            if (humans() == 0) { tables.remove(id); lobbyChanged(); return; }
            if (seatOf(owner) < 0) for (Seat s : seats) if (s != null) { owner = s.id; break; }
            lobbyChanged();
            broadcast(now);
        }

        void leave(long pid, long now) {
            int i = seatOf(pid);
            where.remove(pid);
            if (i < 0) { sendLobby(pid); return; }
            Seat s = seats[i];
            if ("play".equals(phase) || "result".equals(phase)) {
                // 途中で抜けたら、ラス扱い（素点のプラスは無し）で精算して、CPU が代わりに打つ
                double pt = Math.min(0, (s.score - 30000) / 1000.0) + UMA[3];
                pt = Math.round(pt * 10) / 10.0;
                out.pay(pid, Math.round(pt * RATES[rate]), "麻雀 途中で抜けた（ラス扱い " + pt + "）");
                s.cpu = true;
                s.id = 0;
                s.auto = false;
                s.name = s.name + "（代打ち）";
                if (humans() == 0) { tables.remove(id); lobbyChanged(); return; }
            } else if ("end".equals(phase)) {
                // 精算はもう済んでいる。席に穴をあけると進行が止まるので、終局の画面のあいだはCPUに座っていてもらう
                s.cpu = true;
                s.id = 0;
                s.auto = false;
                s.ready = true;
                if (humans() == 0) { tables.remove(id); lobbyChanged(); sendLobby(pid); return; }
                checkReady(now);
                lobbyChanged();
                sendLobby(pid);
                return;
            } else {
                seats[i] = null;
                if (humans() == 0) { tables.remove(id); lobbyChanged(); sendLobby(pid); return; }
                if (owner == pid) for (Seat x : seats) if (x != null && !x.cpu) { owner = x.id; break; }
            }
            lobbyChanged();
            broadcast(now);
            sendLobby(pid);
        }

        // ---------- プレイヤーの操作 ----------

        void act(long pid, String act, int a, int b, long now) {
            int i = seatOf(pid);
            if (i < 0) return;
            Seat s = seats[i];
            s.timeouts = 0;
            s.auto = false;
            if ("assist".equals(act)) {
                if (rate != 0 && a == 1) { ng(pid, "おまかせ打ちは、ノーレートの卓だけで使えるよ"); sendTo(pid, now); return; }
                s.assist = a == 1;
                s.actAt = now + ASSIST_MS;
                broadcast(now);
                return;
            }
            if ("hint".equals(act)) { s.hint = a == 1; sendTo(pid, now); return; }
            if ("next".equals(act)) {
                if ("result".equals(phase) || "end".equals(phase)) { s.ready = true; if ("end".equals(phase)) s.back = true; checkReady(now); }
                return;
            }
            if (!"play".equals(phase)) return;
            if ("claim".equals(sub)) {
                Claim c = claims.get(i);
                if (c == null || c.answer != null) return;
                switch (act) {
                    case "ron" -> { if (c.ron) c.answer = "ron"; }
                    case "pon" -> { if (c.pon) c.answer = "pon"; }
                    case "minkan" -> { if (c.minkan) c.answer = "minkan"; }
                    case "chi" -> { if (a >= 0 && a < c.chi.size()) { c.answer = "chi"; c.chiIdx = a; } }
                    case "pass" -> c.answer = "pass";
                    default -> { }
                }
                if (allAnswered()) resolve(now);
                else broadcast(now);
                return;
            }
            if (turn != i || !"turn".equals(sub)) return;
            switch (act) {
                case "discard" -> tryDiscard(i, a, b == 1, now);
                case "tsumo" -> { if (canTsumo(i)) agari(i, -1, s.drawn, now); }
                case "kan" -> doKan(i, a, now);
                case "kyuushu" -> { if (canKyuushu(i)) abort("九種九牌", now); }
                default -> { }
            }
        }

        void tryDiscard(int i, int tile, boolean declare, long now) {
            Seat s = seats[i];
            if (!s.hand.contains(tile)) return;
            if (s.riichi && tile != s.drawn) return;
            if (s.banned.contains(MjRules.kind(tile))) return;
            if (declare && !riichiTiles(i).stream().anyMatch(t -> MjRules.kind(t) == MjRules.kind(tile))) return;
            discard(i, tile, declare, now);
        }

        void doKan(int i, int k, long now) {
            if (!kanOptions(i).contains(k)) return;
            Seat s = seats[i];
            int n = 0;
            for (int t : s.hand) if (MjRules.kind(t) == k) n++;
            if (n == 4) ankan(i, k, now);
            else kakan(i, k, now);
        }

        void checkReady(long now) {
            for (Seat s : seats) if (!s.ready) { broadcast(now); return; }
            if ("result".equals(phase)) next(now);
            else if ("end".equals(phase)) reset(now);
        }

        // ---------- 時間 ----------

        void tick(long now, LongPredicate online) {
            for (Seat s : seats) if (s != null && !s.cpu) s.auto = !online.test(s.id) || s.timeouts >= 2;
            if ("wait".equals(phase)) {
                // だれもつないでいない待ち卓は片付ける
                boolean any = false;
                for (Seat s : seats) if (s != null && online.test(s.id)) any = true;
                if (!any) { for (Seat s : seats) if (s != null) where.remove(s.id); tables.remove(id); lobbyChanged(); return; }
                // しばらくつながっていない人の席は空ける（幽霊の席で、ほかの人が座れなくならないように）
                boolean changed = false;
                for (int i = 0; i < 4; i++) {
                    Seat s = seats[i];
                    if (s == null || s.cpu) continue;
                    if (online.test(s.id)) { s.offSince = 0; continue; }
                    if (s.offSince == 0) { s.offSince = now; continue; }
                    if (now - s.offSince < WAIT_OFFLINE_MS) continue;
                    where.remove(s.id);
                    seats[i] = null;
                    changed = true;
                }
                if (changed) {
                    if (seatOf(owner) < 0) for (Seat s : seats) if (s != null && !s.cpu) { owner = s.id; break; }
                    lobbyChanged();
                    broadcast(now);
                }
                return;
            }
            if ("result".equals(phase) || "end".equals(phase)) {
                boolean allBots = true;
                for (Seat s : seats) if (!s.bot()) allBots = false;
                if (now >= deadline || allBots) { for (Seat s : seats) s.ready = true; checkReady(now); }
                return;
            }
            if ("claim".equals(sub)) {
                boolean changed = false;
                for (Map.Entry<Integer, Claim> e : claims.entrySet()) {
                    Seat s = seats[e.getKey()];
                    Claim c = e.getValue();
                    if (c.answer != null) continue;
                    if (s.bot()) { c.answer = s.weak() ? "pass" : MjAi.claim(this, e.getKey(), c, lastTile); changed = true; }
                    else if (now >= deadline) { c.answer = "pass"; s.timeouts++; changed = true; }
                }
                if (changed && allAnswered()) resolve(now);
                return;
            }
            Seat s = seats[turn];
            if (s.bot() ? now >= s.actAt : now >= deadline) {
                if (!s.bot() || s.weak()) {
                    // 時間切れ・代わり打ち：ツモ切りだけ（和了・立直・カンはしない。和了は自分で押す）
                    // 立直中の「ツモ切りしかできない番」は自動で進むだけなので、時間切れには数えない
                    boolean only = s.riichi && !canTsumo(turn) && kanOptions(turn).isEmpty();
                    if (!s.bot() && !only) s.timeouts++;
                    int t = s.drawn >= 0 ? s.drawn : MjAi.discardChoice(this, turn);
                    if (s.banned.contains(MjRules.kind(t))) t = MjAi.discardChoice(this, turn);
                    discard(turn, t, false, now);
                    return;
                }
                MjAi.turn(this, turn, now);
            }
        }

        // ---------- 見せ方 ----------

        void broadcast(long now) {
            for (Seat s : seats) if (s != null && !s.cpu) sendTo(s.id, now);
        }

        List<Object> indicators(boolean ura) {
            List<Object> l = new ArrayList<>();
            for (int i = 0; i <= kans; i++) l.add(dead[(ura ? 9 : 4) + i]);
            return l;
        }

        List<Object> meldsView(Seat s) {
            List<Object> l = new ArrayList<>();
            for (Meld m : s.melds) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("type", m.type);
                List<Integer> t = new ArrayList<>();
                for (int x : m.tiles) t.add(x);
                e.put("tiles", t);
                e.put("from", m.from);
                e.put("called", m.called);
                l.add(e);
            }
            return l;
        }

        void sendTo(long pid, long now) {
            int me = seatOf(pid);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "mjTable");
            m.put("id", id);
            m.put("phase", phase);
            m.put("south", south);
            m.put("rate", rate);
            m.put("rateName", RATE_NAMES[rate]);
            m.put("owner", owner == pid);
            m.put("me", me);
            m.put("msg", msg);
            List<Object> seatList = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Seat s = seats[i];
                if (s == null) { seatList.add(null); continue; }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", s.name);
                e.put("cpu", s.cpu);
                e.put("auto", s.auto && !s.cpu);
                e.put("assist", s.assist && !s.cpu);
                if (i == me) e.put("hint", s.hint);
                e.put("score", s.score);
                if (!"wait".equals(phase)) {
                    e.put("wind", wind(i));
                    e.put("riichi", s.riichi || s.pendingRiichi);
                    List<Object> rv = new ArrayList<>();
                    for (River r : s.river) rv.add(List.of(r.tile, (r.riichi ? 1 : 0) + (r.taken ? 2 : 0)));
                    e.put("river", rv);
                    e.put("melds", meldsView(s));
                    e.put("count", s.hand.size());
                    if (i == me) {
                        List<Integer> h = new ArrayList<>(s.hand);
                        if (s.drawn >= 0) h.remove(Integer.valueOf(s.drawn));
                        Collections.sort(h);
                        e.put("hand", h);
                        e.put("drawn", s.drawn);
                        e.put("furiten", "play".equals(phase) && s.hand.size() % 3 == 1 && furiten(i));
                    }
                    e.put("ready", s.ready);
                }
                seatList.add(e);
            }
            m.put("seats", seatList);
            if (!"wait".equals(phase)) {
                m.put("kyoku", kyoku);
                m.put("honba", honba);
                m.put("sticks", sticks);
                m.put("left", remaining());
                m.put("dora", indicators(false));
                m.put("dealer", dealer());
                m.put("turn", turn);
                m.put("sub", sub);
                m.put("last", lastTile);
                m.put("lastFrom", lastFrom);
                m.put("ms", Math.max(0, deadline - now));
            }
            if (result != null) m.put("result", result);
            if (me >= 0 && "play".equals(phase)) m.put("actions", actions(me));
            if (me >= 0 && "play".equals(phase) && seats[me].hint) m.put("hint", hint(me));
            out.toPlayer(pid, m);
        }

        /** ヒント：おすすめの牌・テンパイの待ち・役があるか。初めての人のための説明つき */
        Map<String, Object> hint(int me) {
            Map<String, Object> h = new LinkedHashMap<>();
            Seat s = seats[me];
            int melds = s.melds.size();
            if ("claim".equals(sub)) {
                Claim c = claims.get(me);
                if (c == null || c.answer != null) return h;
                if (c.ron) { h.put("rec", "ron"); h.put("msg", "ロンで和了れるよ！「ロン」を押そう"); return h; }
                int keep = c.chiIdx;
                String r = MjAi.claim(this, me, c, lastTile);
                int idx = c.chiIdx;
                c.chiIdx = keep;
                h.put("rec", r);
                if ("chi".equals(r)) h.put("chi", idx);
                int k = MjRules.kind(lastTile);
                boolean yakuhai = k >= 31 || k == 27 + wind(me) || k == 27 + (kyoku < 4 ? 0 : 1);
                h.put("msg", switch (r) {
                    case "pon" -> yakuhai ? "おすすめはポン。役牌（三元牌・自分の風・場の風）を3枚そろえると、それだけで役がつくよ" : "おすすめはポン。手が早く進むよ";
                    case "chi" -> "おすすめはチー。手が早く進むよ（チーは左の人＝上家が捨てた牌だけ）";
                    default -> s.menzen() ? "おすすめは見送り（パス）。鳴くと門前でなくなって、リーチができなくなるよ" : "おすすめは見送り（パス）。鳴いても手が進まないみたい";
                });
                return h;
            }
            if (turn == me && "turn".equals(sub)) {
                if (canTsumo(me)) { h.put("rec", "tsumo"); h.put("msg", "ツモで和了れるよ！「ツモ」を押そう"); return h; }
                if (s.riichi) { h.put("msg", "リーチ中は、引いた牌をそのまま切るだけ。和了れる牌が来たら「ツモ」、ほかの人が捨てたら「ロン」が出るよ"); return h; }
                int rec = MjAi.discardChoice(this, me);
                h.put("rec", rec);
                Map<String, Object> ten = new LinkedHashMap<>();
                Set<Integer> done = new HashSet<>();
                for (int t : s.hand) {
                    int k = MjRules.kind(t);
                    if (!done.add(k)) continue;
                    int[] c = s.closed();
                    c[k]--;
                    List<Integer> w = MjRules.waits(c, melds);
                    if (!w.isEmpty()) ten.put(String.valueOf(k), w);
                }
                if (!ten.isEmpty()) h.put("tenpai", ten);
                int rk = MjRules.kind(rec);
                int[] after = s.closed();
                after[rk]--;
                List<Integer> w = MjRules.waits(after, melds);
                boolean danger = false;
                for (int i = 0; i < 4; i++) if (i != me && seats[i].riichi) danger = true;
                StringBuilder msg = new StringBuilder("おすすめは「" + MjRules.name(rk) + "」を切ること。");
                if (!w.isEmpty()) {
                    msg.append("切るとテンパイ（").append(names(w)).append("待ち）。");
                    if (!riichiTiles(me).isEmpty()) msg.append("「リーチ」をかけると役がつくよ。");
                    else if (!hasYaku(me, after, w)) msg.append(s.menzen() ? "役がないので、リーチかツモで和了ろう。" : "ただ、役がないので、このままだと和了れないよ。");
                } else {
                    int sh = MjRules.shanten(after, melds);
                    msg.append("あと").append(sh).append("回、いい牌が来ればテンパイ（あと1枚で和了れる形）。");
                }
                if (danger && w.isEmpty()) msg.append("リーチしている人がいるので、その人が捨てた牌など安全そうな牌を選んでいるよ。");
                h.put("msg", msg.toString());
                return h;
            }
            if (s.hand.size() % 3 == 1) {
                List<Integer> w = MjRules.waits(s.closed(), melds);
                if (!w.isEmpty()) {
                    String m = "テンパイ中（" + names(w) + "待ち）。";
                    if (furiten(me)) m += "フリテン（自分で捨てた牌で待っている）なので、ロンはできないよ。ツモなら和了れる";
                    else if (!s.riichi && !hasYaku(me, s.closed(), w)) m += s.menzen() ? "役がないので、ロンするにはリーチが必要。ツモなら和了れるよ" : "役がないので、このままだと和了れないよ";
                    h.put("msg", m);
                    h.put("waits", w);
                }
            }
            return h;
        }

        String names(List<Integer> kinds) {
            StringBuilder b = new StringBuilder();
            for (int k : kinds) { if (b.length() > 0) b.append("・"); b.append(MjRules.name(k)); }
            return b.toString();
        }

        /** その待ちのどれかで、ロンでも和了れる役があるか */
        boolean hasYaku(int me, int[] closed13, List<Integer> waits) {
            Seat s = seats[me];
            for (int w : waits) {
                int[] c = closed13.clone();
                c[w]++;
                if (MjRules.evaluate(c, s.melds, w, ctx(me, false, w * 4 + 1)) != null) return true;
            }
            return false;
        }

        Map<String, Object> actions(int me) {
            Map<String, Object> a = new LinkedHashMap<>();
            Seat s = seats[me];
            if ("claim".equals(sub)) {
                Claim c = claims.get(me);
                if (c != null && c.answer == null) {
                    if (c.ron) a.put("ron", true);
                    if (c.pon) a.put("pon", true);
                    if (c.minkan) a.put("minkan", true);
                    if (!c.chi.isEmpty()) {
                        List<Object> l = new ArrayList<>();
                        for (int[] x : c.chi) l.add(List.of(x[0], x[1]));
                        a.put("chi", l);
                    }
                    a.put("pass", true);
                    a.put("tile", lastTile);
                }
            } else if (turn == me && "turn".equals(sub)) {
                a.put("discard", true);
                if (canTsumo(me)) a.put("tsumo", true);
                List<Integer> rt = riichiTiles(me);
                if (!rt.isEmpty()) a.put("riichi", rt);
                List<Integer> kk = kanOptions(me);
                if (!kk.isEmpty()) a.put("kan", kk);
                if (canKyuushu(me)) a.put("kyuushu", true);
                if (!s.banned.isEmpty()) a.put("banned", new ArrayList<>(s.banned));
                if (s.riichi) a.put("riichiOnly", s.drawn);
            }
            return a;
        }
    }

    static List<Object> toList(int[] a) {
        List<Object> l = new ArrayList<>();
        for (int x : a) l.add(x);
        return l;
    }
}
