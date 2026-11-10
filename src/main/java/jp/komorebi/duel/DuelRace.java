package jp.komorebi.duel;

import java.util.Random;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * こもれび闘鶏の果たし状のレース（1対1）。種（seed）から、毎歩の位置・スキルが出たところ・勝ち・写真判定を1回だけ決める。
 * 結果はそのまま両方の画面に配るので、どちらの画面でも同じレースになる（画面は位置をなぞって見せるだけ）。
 * <p>コースは 1000。250・500・750 に柵。序盤は 300 まで・中盤は 700 まで・そのあとが最後の直線。
 * 速さは はやさ、息切れまでの長さは 持久力、柵は 脚力、息切れしたあとのふんばりは 根性、走りのぶれとスキルの出やすさは かしこさ。
 * 作戦（nige・saki・sashi・oi）で、序盤・中盤・直線のペースと息の使い方が変わる。差し・追いこみは、すぐうしろ（60まで）につけると少し速い（風よけ）。
 * 作戦どうしはだいたい4〜6割（追いこみは差しに強く、にげ・先行に弱い）。数を変えたら mock.js の duelRace でたくさん走らせて確かめる。その日の調子（-2〜+2）と少しの運。
 * mock.js の duelRace も同じ決まりで動く（数はちがってよい。画面は配られた結果を見せるだけ）。
 */
final class DuelRace {

    static final int GOAL = 1000, MAX_STEPS = 300;
    static final int[] HURDLES = { 250, 500, 750 };
    static final String[] STRATS = { "nige", "saki", "sashi", "oi" };
    private static final double[][] PACE = { { 1.10, 1.02, .96 }, { 1.05, 1.02, 1.00 }, { .97, 1.01, 1.065 }, { .93, 1.00, 1.115 } };

    /** 走る子：5つの能力・スキル・作戦 */
    record Runner(int spd, int sta, int pow, int gut, int wis, java.util.Set<String> sk, String st) {
        int strat() { for (int i = 0; i < STRATS.length; i++) if (STRATS[i].equals(st)) return i; return 1; }
    }

    private DuelRace() { }

    private static int phase(double x) { return x < 300 ? 0 : x < 700 ? 1 : 2; }

    static ObjectNode run(long seed, Runner a, Runner b, ObjectMapper om) {
        Random rng = new Random(seed);
        Runner[] R = { a, b };
        double[] x = new double[2], e = new double[2], cm = new double[2], fin = { -1, -1 };
        int[] cond = new int[2], boostLeft = new int[2];
        double[] boost = { 1, 1 };
        boolean[][] used = new boolean[2][8];
        ObjectNode out = om.createObjectNode();
        ArrayNode ev = out.putArray("e");
        ArrayNode[] p = { om.createArrayNode(), om.createArrayNode() };
        String[] names = { "senko", "kaze", "nobi", "oikomi", "nebari", "saku", "ochi", "kokoro" };
        for (int i = 0; i < 2; i++) {
            cond[i] = rng.nextInt(5) - 2;
            e[i] = 100 + R[i].sta() * .6;
            cm[i] = 1 + cond[i] * .03;
            if (cond[i] < 0 && R[i].sk().contains("ochi")) { cm[i] = 1; used[i][6] = true; ev.addArray().add(0).add(i).add("ochi"); }
            if (R[i].sk().contains("kokoro")) { used[i][7] = true; ev.addArray().add(0).add(i).add("kokoro"); }
            p[i].add(0);
        }
        int step = 0;
        while ((fin[0] < 0 || fin[1] < 0) && step < MAX_STEPS) {
            step++;
            double[] sx = x.clone();   // 判断は、この歩のはじめの位置で（先に動く側が有利にならないように）
            for (int i = 0; i < 2; i++) {
                if (fin[i] >= 0) continue;
                Runner r = R[i];
                int o = 1 - i, ph = phase(x[i]);
                double chance = Math.min(.95, .55 + r.wis() / 800.0);
                // スキル（1回ずつ。きっかけが来たときに、かしこさで出るか決まる）
                int k = -1;
                if (step == 2 && !used[i][0] && r.sk().contains("senko")) k = 0;
                else if (x[i] >= 400 && !used[i][1] && r.sk().contains("kaze")) k = 1;
                else if (x[i] >= 750 && !used[i][2] && r.sk().contains("nobi")) k = 2;
                else if (x[i] >= 700 && x[i] < sx[o] && !used[i][3] && r.sk().contains("oikomi")) k = 3;
                else if (ph == 2 && Math.abs(x[i] - sx[o]) < 25 && !used[i][4] && r.sk().contains("nebari")) k = 4;
                if (k >= 0) {
                    used[i][k] = true;
                    if (rng.nextDouble() < chance) {
                        double[] mul = { 1.12, 1.10, 1.12, 1.15, 1.08 };
                        int[] len = { 8, 8, 10, 10, 8 };
                        boost[i] = mul[k]; boostLeft[i] = len[k];
                        ev.addArray().add(step).add(i).add(names[k]);
                    }
                }
                double pace = PACE[r.strat()][ph], m = pace * cm[i];
                double amp = (.05 - r.wis() / 400.0 * .03) * (used[i][7] ? .5 : 1);
                double noise = 1 + (rng.nextDouble() * 2 - 1) * amp;
                if ((r.st().equals("sashi") || r.st().equals("oi")) && x[i] < sx[o] && sx[o] - x[i] < 60) m *= 1.01;   // うしろにつけて風よけ
                if (e[i] <= 0) m *= .80 + r.gut() / 400.0 * .15;
                double v0 = 8 + r.spd() * .02, dx = v0 * m * noise * (boostLeft[i] > 0 ? boost[i] : 1);
                for (int h : HURDLES) {
                    if (x[i] < h && x[i] + dx >= h) {
                        if (r.sk().contains("saku")) { if (!used[i][5]) { used[i][5] = true; ev.addArray().add(step).add(i).add("saku"); } }
                        else dx *= .72 + .28 * Math.min(1, r.pow() / 260.0);
                    }
                }
                e[i] -= pace * pace + (boostLeft[i] > 0 ? .5 : 0);
                if (boostLeft[i] > 0) boostLeft[i]--;
                double nx = x[i] + dx;
                if (nx >= GOAL) { fin[i] = step - 1 + (GOAL - x[i]) / dx; nx = GOAL; }
                x[i] = nx;
            }
            for (int i = 0; i < 2; i++) p[i].add((int) Math.round(x[i]));
        }
        for (int i = 0; i < 2; i++) if (fin[i] < 0) fin[i] = MAX_STEPS + (GOAL - x[i]);
        int w = fin[0] < fin[1] ? 0 : fin[1] < fin[0] ? 1 : rng.nextInt(2);
        out.put("w", w).put("ph", Math.abs(fin[0] - fin[1]) < .35);
        out.put("d", Math.round(Math.abs(fin[0] - fin[1]) * 100) / 100.0);
        ArrayNode pp = out.putArray("p"); pp.add(p[0]); pp.add(p[1]);
        out.putArray("c").add(cond[0]).add(cond[1]);
        return out;
    }

    /**
     * にわとりグランプリ（v0.73.0）：何羽でも一斉に走る。1対1と同じ決まりで、「相手」はいちばん近い前の子（いなければいちばん近い子）。
     * 返すのは {p:[[毎歩の位置]...], e:[[歩, 子, スキル]], rank:[着順の子の番号], fin:[ゴールした歩], c:[調子], ph:1着と2着が写真判定か}
     */
    static ObjectNode runMany(long seed, Runner[] R, ObjectMapper om) {
        Random rng = new Random(seed);
        int N = R.length;
        double[] x = new double[N], e = new double[N], cm = new double[N], fin = new double[N], boost = new double[N];
        int[] cond = new int[N], boostLeft = new int[N];
        boolean[][] used = new boolean[N][8];
        java.util.Arrays.fill(fin, -1);
        java.util.Arrays.fill(boost, 1);
        ObjectNode out = om.createObjectNode();
        ArrayNode ev = out.putArray("e");
        ArrayNode[] p = new ArrayNode[N];
        String[] names = { "senko", "kaze", "nobi", "oikomi", "nebari", "saku", "ochi", "kokoro" };
        for (int i = 0; i < N; i++) {
            p[i] = om.createArrayNode();
            cond[i] = rng.nextInt(5) - 2;
            e[i] = 100 + R[i].sta() * .6;
            cm[i] = 1 + cond[i] * .03;
            if (cond[i] < 0 && R[i].sk().contains("ochi")) { cm[i] = 1; used[i][6] = true; ev.addArray().add(0).add(i).add("ochi"); }
            if (R[i].sk().contains("kokoro")) { used[i][7] = true; ev.addArray().add(0).add(i).add("kokoro"); }
            p[i].add(0);
        }
        int step = 0, left = N;
        while (left > 0 && step < MAX_STEPS) {
            step++;
            double[] sx = x.clone();
            for (int i = 0; i < N; i++) {
                if (fin[i] >= 0) continue;
                Runner r = R[i];
                int ph = phase(x[i]);
                // いちばん近い前の子（いなければ、いちばん近い子）
                double ahead = Double.MAX_VALUE, near = Double.MAX_VALUE, lead = 0;
                for (int j = 0; j < N; j++) {
                    if (j == i) continue;
                    double dd = sx[j] - x[i];
                    if (dd > 0 && dd < ahead) ahead = dd;
                    near = Math.min(near, Math.abs(dd));
                    lead = Math.max(lead, sx[j]);
                }
                double chance = Math.min(.95, .55 + r.wis() / 800.0);
                int k = -1;
                if (step == 2 && !used[i][0] && r.sk().contains("senko")) k = 0;
                else if (x[i] >= 400 && !used[i][1] && r.sk().contains("kaze")) k = 1;
                else if (x[i] >= 750 && !used[i][2] && r.sk().contains("nobi")) k = 2;
                else if (x[i] >= 700 && x[i] < lead && !used[i][3] && r.sk().contains("oikomi")) k = 3;
                else if (ph == 2 && near < 25 && !used[i][4] && r.sk().contains("nebari")) k = 4;
                if (k >= 0) {
                    used[i][k] = true;
                    if (rng.nextDouble() < chance) {
                        double[] mul = { 1.12, 1.10, 1.12, 1.15, 1.08 };
                        int[] len = { 8, 8, 10, 10, 8 };
                        boost[i] = mul[k]; boostLeft[i] = len[k];
                        ev.addArray().add(step).add(i).add(names[k]);
                    }
                }
                double pace = PACE[r.strat()][ph], m = pace * cm[i];
                double amp = (.05 - r.wis() / 400.0 * .03) * (used[i][7] ? .5 : 1);
                double noise = 1 + (rng.nextDouble() * 2 - 1) * amp;
                if ((r.st().equals("sashi") || r.st().equals("oi")) && ahead < 60) m *= 1.01;
                if (e[i] <= 0) m *= .80 + r.gut() / 400.0 * .15;
                double v0 = 8 + r.spd() * .02, dx = v0 * m * noise * (boostLeft[i] > 0 ? boost[i] : 1);
                for (int h : HURDLES) {
                    if (x[i] < h && x[i] + dx >= h) {
                        if (r.sk().contains("saku")) { if (!used[i][5]) { used[i][5] = true; ev.addArray().add(step).add(i).add("saku"); } }
                        else dx *= .72 + .28 * Math.min(1, r.pow() / 260.0);
                    }
                }
                e[i] -= pace * pace + (boostLeft[i] > 0 ? .5 : 0);
                if (boostLeft[i] > 0) boostLeft[i]--;
                double nx = x[i] + dx;
                if (nx >= GOAL) { fin[i] = step - 1 + (GOAL - x[i]) / dx; nx = GOAL; left--; }
                x[i] = nx;
            }
            for (int i = 0; i < N; i++) p[i].add((int) Math.round(x[i]));
        }
        for (int i = 0; i < N; i++) if (fin[i] < 0) fin[i] = MAX_STEPS + (GOAL - x[i]);
        Integer[] ord = new Integer[N];
        for (int i = 0; i < N; i++) ord[i] = i;
        double[] tie = new double[N];
        for (int i = 0; i < N; i++) tie[i] = rng.nextDouble();
        java.util.Arrays.sort(ord, (a, b) -> fin[a] != fin[b] ? Double.compare(fin[a], fin[b]) : Double.compare(tie[a], tie[b]));
        ArrayNode rank = out.putArray("rank");
        for (int i : ord) rank.add(i);
        ArrayNode fa = out.putArray("fin");
        for (int i = 0; i < N; i++) fa.add(Math.round(fin[i] * 100) / 100.0);
        out.put("ph", N > 1 && Math.abs(fin[ord[0]] - fin[ord[1]]) < .35);
        ArrayNode pp = out.putArray("p");
        for (int i = 0; i < N; i++) pp.add(p[i]);
        ArrayNode cc = out.putArray("c");
        for (int i = 0; i < N; i++) cc.add(cond[i]);
        return out;
    }
}
