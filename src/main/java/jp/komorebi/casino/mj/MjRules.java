package jp.komorebi.casino.mj;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * リーチ麻雀のルール（和了の形・役・符・点数・向聴数）。Spring には依存しない。
 *
 * 牌は 0〜135 の番号で持つ。種類 = 番号 / 4（0〜8 萬子、9〜17 筒子、18〜26 索子、27〜30 東南西北、31 白、32 發、33 中）。
 * 赤ドラは 16（赤5萬）・52（赤5筒）・88（赤5索）。喰いタン・後付けあり。
 */
public final class MjRules {

    private MjRules() { }

    public static final String[] HONOR = { "東", "南", "西", "北", "白", "發", "中" };

    public static int kind(int id) { return id >> 2; }
    public static boolean red(int id) { return id == 16 || id == 52 || id == 88; }
    static boolean honor(int k) { return k >= 27; }
    static boolean terminal(int k) { return k < 27 && (k % 9 == 0 || k % 9 == 8); }
    static boolean yaochu(int k) { return honor(k) || terminal(k); }
    static boolean dragon(int k) { return k >= 31; }
    static int suit(int k) { return k >= 27 ? 3 : k / 9; }

    /** ドラ表示牌の種類 → ドラの種類 */
    public static int doraOf(int ind) {
        if (ind < 27) return ind / 9 * 9 + (ind % 9 + 1) % 9;
        if (ind < 31) return 27 + (ind - 27 + 1) % 4;
        return 31 + (ind - 31 + 1) % 3;
    }

    public static String name(int k) {
        if (k < 9) return (k + 1) + "萬";
        if (k < 18) return (k - 8) + "筒";
        if (k < 27) return (k - 17) + "索";
        return HONOR[k - 27];
    }

    // ---------- 鳴き ----------

    public static final class Meld {
        /** chi / pon / minkan / ankan / kakan */
        public final String type;
        /** この面子の牌（番号）。チーは小さい順 */
        public final int[] tiles;
        /** 鳴いた相手の席（暗槓は -1） */
        public final int from;
        /** 鳴いた牌（暗槓は -1） */
        public final int called;

        public Meld(String type, int[] tiles, int from, int called) {
            this.type = type;
            this.tiles = tiles.clone();
            this.from = from;
            this.called = called;
        }

        public int kind() {
            int k = 99;
            for (int t : tiles) k = Math.min(k, MjRules.kind(t));
            return k;
        }
        public boolean open() { return !"ankan".equals(type); }
        public boolean kan() { return type.endsWith("kan"); }
        public boolean seq() { return "chi".equals(type); }
    }

    // ---------- 和了の判定 ----------

    /** 和了のときの状況 */
    public static final class Ctx {
        public int seatWind, roundWind;
        public boolean tsumo, riichi, doubleRiichi, ippatsu, haitei, houtei, rinshan, chankan, tenhou, chiihou;
        public int[] dora = new int[0], ura = new int[0];
        public int reds;
    }

    public static final class Yaku {
        public final String name;
        public final int han;
        public final boolean yakuman;

        Yaku(String name, int han, boolean yakuman) {
            this.name = name;
            this.han = han;
            this.yakuman = yakuman;
        }
    }

    public static final class Result {
        public final List<Yaku> yaku = new ArrayList<>();
        public int han, fu, yakuman;
        /** 親か子か・ツモかロンかで変わる点数は {@link #pay} で計算する */
        public int base() {
            if (yakuman > 0) return 8000 * yakuman;
            if (han >= 13) return 8000;
            if (han >= 11) return 6000;
            if (han >= 8) return 4000;
            if (han >= 6) return 3000;
            if (han >= 5) return 2000;
            return Math.min(2000, fu * (1 << (han + 2)));
        }
        public String label() {
            if (yakuman > 1) return yakuman == 2 ? "ダブル役満" : yakuman + "倍役満";
            if (yakuman == 1) return "役満";
            if (han >= 13) return "数え役満";
            if (han >= 11) return "三倍満";
            if (han >= 8) return "倍満";
            if (han >= 6) return "跳満";
            if (base() >= 2000) return "満貫";
            return "";
        }
    }

    static int up100(int x) { return (x + 99) / 100 * 100; }

    /**
     * 点数の払い。dealer=親が和了、tsumo=ツモ。
     * @return ロン：{合計}、子のツモ：{親の払い, 子の払い}、親のツモ：{子それぞれの払い}
     */
    public static int[] pay(Result r, boolean dealer, boolean tsumo) {
        int b = r.base();
        if (!tsumo) return new int[] { up100(b * (dealer ? 6 : 4)) };
        if (dealer) return new int[] { up100(b * 2) };
        return new int[] { up100(b * 2), up100(b) };
    }

    /** 面子（判定用） */
    private record Set(int kind, boolean seq, boolean kan, boolean open, boolean fromMeld) { }

    private static final int[] KOKU = { 0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33 };
    private static final int[] GREEN = { 19, 20, 21, 23, 25, 32 };

    /**
     * 和了できるか判定して、いちばん高い取り方を返す。役が無い・形になっていないときは null。
     * @param closed 手の中の牌の種類ごとの数（和了牌を含む）
     * @param melds 鳴いた面子（暗槓を含む）
     * @param win 和了牌の種類
     */
    public static Result evaluate(int[] closed, List<Meld> melds, int win, Ctx ctx) {
        int need = 4 - melds.size();
        int[] all = closed.clone();
        for (Meld m : melds) for (int t : m.tiles) all[kind(t)]++;
        boolean menzen = melds.stream().noneMatch(Meld::open);
        int dora = 0;
        for (int d : ctx.dora) dora += all[d];
        int ura = 0;
        if (ctx.riichi || ctx.doubleRiichi) for (int d : ctx.ura) ura += all[d];

        Result best = null;
        if (melds.isEmpty() && sum(closed) == 14) {
            if (kokushi(closed)) {
                Result r = new Result();
                if (ctx.tenhou) addY(r, "天和", 1, true);
                if (ctx.chiihou) addY(r, "地和", 1, true);
                addY(r, "国士無双", 1, true);
                return finish(r, 0, 0, 0, 0);
            }
            if (chiitoi(closed)) {
                Result r = new Result();
                yakumanChiitoi(r, all, ctx);
                if (r.yakuman == 0) {
                    situational(r, ctx, true);
                    if (allSimple(all)) addY(r, "断么九", 1, false);
                    flush(r, all, true);
                    if (allYaochu(all)) addY(r, "混老頭", 2, false);
                    addY(r, "七対子", 2, false);
                }
                best = better(best, finish(r, 25, dora, ctx.reds, ura));
            }
        }
        for (Decomp d : decompose(closed, need)) {
            // 和了牌がどの面子・雀頭に入ったかで、待ちと暗刻の数が変わる
            for (int pos = -1; pos < d.sets.size(); pos++) {
                String wait;
                if (pos < 0) { if (d.pair != win) continue; wait = "tanki"; }
                else {
                    Set s = d.sets.get(pos);
                    if (s.seq) {
                        if (win < s.kind || win > s.kind + 2) continue;
                        if (win == s.kind + 1) wait = "kanchan";
                        else if ((win == s.kind + 2 && s.kind % 9 == 0) || (win == s.kind && s.kind % 9 == 6)) wait = "penchan";
                        else wait = "ryanmen";
                    } else {
                        if (s.kind != win) continue;
                        wait = "shanpon";
                    }
                }
                List<Set> sets = new ArrayList<>();
                for (int i = 0; i < d.sets.size(); i++) {
                    Set s = d.sets.get(i);
                    // ロンで完成した刻子は明刻あつかい
                    if (i == pos && !s.seq && !ctx.tsumo) s = new Set(s.kind, false, false, true, false);
                    sets.add(s);
                }
                for (Meld m : melds) sets.add(new Set(m.kind(), m.seq(), m.kan(), m.open(), true));
                best = better(best, scoreStd(sets, d.pair, win, wait, menzen, all, closed, ctx, dora, ura));
            }
        }
        return best;
    }

    private static Result scoreStd(List<Set> sets, int pair, int win, String wait, boolean menzen, int[] all, int[] closed, Ctx ctx, int dora, int ura) {
        Result r = new Result();
        int rw = 27 + ctx.roundWind, sw = 27 + ctx.seatWind;
        long seqs = sets.stream().filter(Set::seq).count();
        List<Set> tris = sets.stream().filter(s -> !s.seq).toList();
        int concealedTri = (int) tris.stream().filter(s -> !s.open).count();
        int kans = (int) sets.stream().filter(Set::kan).count();

        // ---- 役満 ----
        if (ctx.tenhou) addY(r, "天和", 1, true);
        if (ctx.chiihou) addY(r, "地和", 1, true);
        if (concealedTri == 4) addY(r, "四暗刻", 1, true);
        if (tris.stream().filter(s -> dragon(s.kind)).count() == 3) addY(r, "大三元", 1, true);
        long winds = tris.stream().filter(s -> s.kind >= 27 && s.kind <= 30).count();
        if (winds == 4) addY(r, "大四喜", 1, true);
        else if (winds == 3 && pair >= 27 && pair <= 30) addY(r, "小四喜", 1, true);
        if (allHonor(all)) addY(r, "字一色", 1, true);
        if (allGreen(all)) addY(r, "緑一色", 1, true);
        if (allTerminal(all)) addY(r, "清老頭", 1, true);
        if (kans == 4) addY(r, "四槓子", 1, true);
        if (menzen && sets.stream().noneMatch(Set::kan) && chuuren(closed)) addY(r, "九蓮宝燈", 1, true);
        if (r.yakuman > 0) return finish(r, 0, 0, 0, 0);

        // ---- ふつうの役 ----
        situational(r, ctx, menzen);
        boolean pinfu = menzen && seqs == 4 && "ryanmen".equals(wait) && !(dragon(pair) || pair == rw || pair == sw);
        if (pinfu) addY(r, "平和", 1, false);
        if (allSimple(all)) addY(r, "断么九", 1, false);
        if (menzen) {
            int pairsOfSame = 0;
            List<Integer> sk = new ArrayList<>();
            for (Set s : sets) if (s.seq) sk.add(s.kind);
            sk.sort(null);
            for (int i = 0; i + 1 < sk.size(); i++) if (sk.get(i).equals(sk.get(i + 1))) { pairsOfSame++; i++; }
            if (pairsOfSame == 2) addY(r, "二盃口", 3, false);
            else if (pairsOfSame == 1) addY(r, "一盃口", 1, false);
        }
        for (Set s : tris) {
            if (s.kind == 31) addY(r, "役牌 白", 1, false);
            if (s.kind == 32) addY(r, "役牌 發", 1, false);
            if (s.kind == 33) addY(r, "役牌 中", 1, false);
            if (s.kind == rw) addY(r, "場風 " + HONOR[rw - 27], 1, false);
            if (s.kind == sw) addY(r, "自風 " + HONOR[sw - 27], 1, false);
        }
        for (int n = 0; n < 7; n++) { final int nn = n; if (hasSeq(sets, nn) && hasSeq(sets, nn + 9) && hasSeq(sets, nn + 18)) { addY(r, "三色同順", menzen ? 2 : 1, false); break; } }
        for (int s = 0; s < 3; s++) if (hasSeq(sets, s * 9) && hasSeq(sets, s * 9 + 3) && hasSeq(sets, s * 9 + 6)) { addY(r, "一気通貫", menzen ? 2 : 1, false); break; }
        boolean everyYaochu = yaochu(pair) && sets.stream().allMatch(s -> s.seq ? (s.kind % 9 == 0 || s.kind % 9 == 6) : yaochu(s.kind));
        boolean anyHonor = honor(pair) || sets.stream().anyMatch(s -> !s.seq && honor(s.kind));
        if (everyYaochu && seqs > 0) {
            if (anyHonor) addY(r, "混全帯么九", menzen ? 2 : 1, false);
            else addY(r, "純全帯么九", menzen ? 3 : 2, false);
        }
        if (tris.size() == 4) addY(r, "対々和", 2, false);
        if (concealedTri == 3) addY(r, "三暗刻", 2, false);
        for (int n = 0; n < 9; n++) { final int nn = n; if (hasTri(tris, nn) && hasTri(tris, nn + 9) && hasTri(tris, nn + 18)) { addY(r, "三色同刻", 2, false); break; } }
        if (kans == 3) addY(r, "三槓子", 2, false);
        if (tris.stream().filter(s -> dragon(s.kind)).count() == 2 && dragon(pair)) addY(r, "小三元", 2, false);
        if (allYaochu(all)) addY(r, "混老頭", 2, false);
        flush(r, all, menzen);

        // ---- 符 ----
        int fu;
        if (pinfu && ctx.tsumo) fu = 20;
        else {
            fu = 20;
            if (menzen && !ctx.tsumo) fu += 10;
            if (ctx.tsumo && !pinfu) fu += 2;
            for (Set s : tris) {
                int f = s.kan ? (s.open ? 8 : 16) : (s.open ? 2 : 4);
                if (yaochu(s.kind)) f *= 2;
                fu += f;
            }
            if (dragon(pair)) fu += 2;
            if (pair == rw) fu += 2;
            if (pair == sw) fu += 2;
            if (!"ryanmen".equals(wait) && !"shanpon".equals(wait)) fu += 2;
            if (fu == 20) fu = 30; // 喰い平和の形
            fu = (fu + 9) / 10 * 10;
        }
        return finish(r, fu, dora, ctx.reds, ura);
    }

    private static void situational(Result r, Ctx c, boolean menzen) {
        if (c.doubleRiichi) addY(r, "ダブル立直", 2, false);
        else if (c.riichi) addY(r, "立直", 1, false);
        if (c.ippatsu) addY(r, "一発", 1, false);
        if (c.tsumo && menzen) addY(r, "門前清自摸和", 1, false);
        if (c.rinshan) addY(r, "嶺上開花", 1, false);
        if (c.chankan) addY(r, "槍槓", 1, false);
        if (c.haitei) addY(r, "海底摸月", 1, false);
        if (c.houtei) addY(r, "河底撈魚", 1, false);
    }

    private static void yakumanChiitoi(Result r, int[] all, Ctx ctx) {
        if (ctx.tenhou) addY(r, "天和", 1, true);
        if (ctx.chiihou) addY(r, "地和", 1, true);
        if (allHonor(all)) addY(r, "字一色", 1, true);
    }

    private static void flush(Result r, int[] all, boolean menzen) {
        int suits = 0, s = -1;
        boolean hon = false;
        for (int k = 0; k < 34; k++) {
            if (all[k] == 0) continue;
            if (honor(k)) hon = true;
            else if (suit(k) != s) { s = suit(k); suits++; }
        }
        if (suits == 1 && hon) addY(r, "混一色", menzen ? 3 : 2, false);
        if (suits == 1 && !hon) addY(r, "清一色", menzen ? 6 : 5, false);
    }

    private static Result finish(Result r, int fu, int dora, int reds, int ura) {
        if (r.yakuman > 0) {
            r.yaku.removeIf(y -> !y.yakuman);
            r.han = 13 * r.yakuman;
            r.fu = 0;
            return r;
        }
        int han = r.yaku.stream().mapToInt(y -> y.han).sum();
        if (han == 0) return null; // 役なし（ドラだけでは和了れない）
        if (dora > 0) r.yaku.add(new Yaku("ドラ", dora, false));
        if (reds > 0) r.yaku.add(new Yaku("赤ドラ", reds, false));
        if (ura > 0) r.yaku.add(new Yaku("裏ドラ", ura, false));
        r.han = han + dora + reds + ura;
        r.fu = fu;
        return r;
    }

    private static void addY(Result r, String name, int han, boolean yakuman) {
        for (Yaku y : r.yaku) if (y.name.equals(name)) return;
        r.yaku.add(new Yaku(name, han, yakuman));
        if (yakuman) r.yakuman += han;
    }

    private static Result better(Result a, Result b) {
        if (b == null) return a;
        if (a == null) return b;
        int pa = a.base(), pb = b.base();
        if (pb != pa) return pb > pa ? b : a;
        if (b.han != a.han) return b.han > a.han ? b : a;
        return b.fu > a.fu ? b : a;
    }

    private static boolean hasSeq(List<Set> sets, int k) {
        for (Set s : sets) if (s.seq && s.kind == k) return true;
        return false;
    }

    private static boolean hasTri(List<Set> tris, int k) {
        for (Set s : tris) if (s.kind == k) return true;
        return false;
    }

    static boolean allSimple(int[] c) { for (int k = 0; k < 34; k++) if (c[k] > 0 && yaochu(k)) return false; return true; }
    static boolean allYaochu(int[] c) { for (int k = 0; k < 34; k++) if (c[k] > 0 && !yaochu(k)) return false; return true; }
    static boolean allHonor(int[] c) { for (int k = 0; k < 27; k++) if (c[k] > 0) return false; return true; }
    static boolean allTerminal(int[] c) { for (int k = 0; k < 34; k++) if (c[k] > 0 && !terminal(k)) return false; return true; }
    static boolean allGreen(int[] c) {
        for (int k = 0; k < 34; k++) { final int kk = k; if (c[k] > 0 && Arrays.stream(GREEN).noneMatch(g -> g == kk)) return false; }
        return true;
    }

    static boolean chuuren(int[] c) {
        int s = -1;
        for (int k = 0; k < 34; k++) if (c[k] > 0) { if (honor(k)) return false; if (s < 0) s = suit(k); else if (suit(k) != s) return false; }
        if (s < 0) return false;
        int[] need = { 3, 1, 1, 1, 1, 1, 1, 1, 3 };
        int extra = 0;
        for (int i = 0; i < 9; i++) { int d = c[s * 9 + i] - need[i]; if (d < 0) return false; extra += d; }
        return extra == 1;
    }

    static int sum(int[] c) { int s = 0; for (int x : c) s += x; return s; }

    static boolean chiitoi(int[] c) {
        int p = 0;
        for (int x : c) { if (x == 2) p++; else if (x != 0) return false; }
        return p == 7;
    }

    static boolean kokushi(int[] c) {
        boolean pair = false;
        for (int k = 0; k < 34; k++) {
            boolean y = yaochu(k);
            if (!y && c[k] > 0) return false;
            if (y) { if (c[k] == 0) return false; if (c[k] == 2) { if (pair) return false; pair = true; } if (c[k] > 2) return false; }
        }
        return pair;
    }

    private record Decomp(int pair, List<Set> sets) { }

    /** 雀頭1つと面子 need 個に分ける方法をすべて */
    private static List<Decomp> decompose(int[] c0, int need) {
        List<Decomp> out = new ArrayList<>();
        if (sum(c0) != need * 3 + 2) return out;
        int[] c = c0.clone();
        for (int p = 0; p < 34; p++) {
            if (c[p] < 2) continue;
            c[p] -= 2;
            List<Set> cur = new ArrayList<>();
            rec(c, 0, cur, p, out);
            c[p] += 2;
        }
        return out;
    }

    private static void rec(int[] c, int i, List<Set> cur, int pair, List<Decomp> out) {
        while (i < 34 && c[i] == 0) i++;
        if (i == 34) { out.add(new Decomp(pair, new ArrayList<>(cur))); return; }
        if (c[i] >= 3) {
            c[i] -= 3; cur.add(new Set(i, false, false, false, false));
            rec(c, i, cur, pair, out);
            cur.remove(cur.size() - 1); c[i] += 3;
        }
        if (i < 27 && i % 9 <= 6 && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 1]--; c[i + 2]--; cur.add(new Set(i, true, false, false, false));
            rec(c, i, cur, pair, out);
            cur.remove(cur.size() - 1); c[i]++; c[i + 1]++; c[i + 2]++;
        }
    }

    /** 役は気にせず、和了の形になっているか */
    public static boolean isShape(int[] closed, int meldCount) {
        int need = 4 - meldCount;
        if (sum(closed) != need * 3 + 2) return false;
        if (meldCount == 0 && (chiitoi(closed) || kokushi(closed))) return true;
        return !decompose(closed, need).isEmpty();
    }

    /** 待ちの種類（テンパイでなければ空） */
    public static List<Integer> waits(int[] closed13, int meldCount) {
        List<Integer> w = new ArrayList<>();
        for (int k = 0; k < 34; k++) {
            if (closed13[k] >= 4) continue;
            closed13[k]++;
            if (isShape(closed13, meldCount)) w.add(k);
            closed13[k]--;
        }
        return w;
    }

    // ---------- 向聴数（CPU用） ----------

    public static int shanten(int[] c, int meldCount) {
        int s = shantenStd(c, meldCount);
        if (meldCount == 0) {
            s = Math.min(s, shantenChiitoi(c));
            s = Math.min(s, shantenKokushi(c));
        }
        return s;
    }

    static int shantenChiitoi(int[] c) {
        int pairs = 0, kinds = 0;
        for (int x : c) { if (x >= 2) pairs++; if (x >= 1) kinds++; }
        return 6 - pairs + Math.max(0, 7 - kinds);
    }

    static int shantenKokushi(int[] c) {
        int kinds = 0, pair = 0;
        for (int k : KOKU) { if (c[k] > 0) kinds++; if (c[k] >= 2) pair = 1; }
        return 13 - kinds - pair;
    }

    static int shantenStd(int[] c0, int meldCount) {
        int[] c = c0.clone();
        int[] best = { 8 };
        scan(c, 0, meldCount, 0, 0, best);
        return best[0];
    }

    private static void scan(int[] c, int i, int m, int t, int p, int[] best) {
        while (i < 34 && c[i] == 0) i++;
        if (i >= 34) {
            int tt = Math.min(t, 4 - m);
            int s = 8 - 2 * m - tt - p;
            if (s < best[0]) best[0] = s;
            return;
        }
        if (c[i] >= 3) { c[i] -= 3; scan(c, i, m + 1, t, p, best); c[i] += 3; }
        if (i < 27 && i % 9 <= 6 && c[i + 1] > 0 && c[i + 2] > 0) { c[i]--; c[i + 1]--; c[i + 2]--; scan(c, i, m + 1, t, p, best); c[i]++; c[i + 1]++; c[i + 2]++; }
        if (c[i] >= 2) { c[i] -= 2; if (p == 0) scan(c, i, m, t, 1, best); scan(c, i, m, t + 1, p, best); c[i] += 2; }
        if (i < 27 && i % 9 <= 7 && c[i + 1] > 0) { c[i]--; c[i + 1]--; scan(c, i, m, t + 1, p, best); c[i]++; c[i + 1]++; }
        if (i < 27 && i % 9 <= 6 && c[i + 2] > 0) { c[i]--; c[i + 2]--; scan(c, i, m, t + 1, p, best); c[i]++; c[i + 2]++; }
        int k = c[i]; c[i] = 0; scan(c, i + 1, m, t, p, best); c[i] = k;
    }

    public static int[] counts(List<Integer> ids) {
        int[] c = new int[34];
        for (int t : ids) c[kind(t)]++;
        return c;
    }
}
