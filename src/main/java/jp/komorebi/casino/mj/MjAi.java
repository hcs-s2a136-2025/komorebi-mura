package jp.komorebi.casino.mj;

import java.util.HashSet;
import java.util.Set;

import jp.komorebi.casino.mj.MjRules.Meld;

/**
 * CPU の打ち方（と、時間切れ・切断した人の代わり）。
 * 向聴数を下げて受け入れを広くする牌を切り、だれかが立直していて自分の手が遠いときは安全な牌を切る。
 * 役牌は鳴く。立直はテンパイしたらかける。
 */
final class MjAi {

    private MjAi() { }

    /** 鳴き・ロンの返事 */
    static String claim(MjGame.Table t, int seat, MjGame.Claim c, int tile) {
        if (c.ron) return "ron";
        MjGame.Seat s = t.seats[seat];
        int k = MjRules.kind(tile);
        int sh = MjRules.shanten(s.closed(), s.melds.size());
        if (c.pon) {
            boolean yakuhai = k >= 31 || k == 27 + t.wind(seat) || k == 27 + (t.kyoku < 4 ? 0 : 1);
            int[] cc = s.closed();
            cc[k] -= 2;
            int after = bestAfterDiscard(cc, s.melds.size() + 1);
            if (yakuhai && after <= sh) return "pon";
            if (!s.menzen() && after < sh) return "pon";
        }
        if (!c.chi.isEmpty() && !s.menzen()) {
            for (int i = 0; i < c.chi.size(); i++) {
                int[] cc = s.closed();
                cc[MjRules.kind(c.chi.get(i)[0])]--;
                cc[MjRules.kind(c.chi.get(i)[1])]--;
                if (bestAfterDiscard(cc, s.melds.size() + 1) < sh) { c.chiIdx = i; return "chi"; }
            }
        }
        return "pass";
    }

    /** 13枚（鳴いたあとは 3n+2 枚）から1枚切ったときの最小の向聴数 */
    static int bestAfterDiscard(int[] c, int melds) {
        int best = 9;
        for (int k = 0; k < 34; k++) {
            if (c[k] == 0) continue;
            c[k]--;
            best = Math.min(best, MjRules.shanten(c, melds));
            c[k]++;
        }
        return best;
    }

    /** 自分の番の行動 */
    static void turn(MjGame.Table t, int seat, long now) {
        MjGame.Seat s = t.seats[seat];
        if (t.canTsumo(seat)) { t.agari(seat, -1, s.drawn, now); return; }
        if (t.canKyuushu(seat) && MjRules.shantenKokushi(s.closed()) > 3) { t.abort("九種九牌", now); return; }
        if (s.riichi) { t.discard(seat, s.drawn, false, now); return; }
        int sh = MjRules.shanten(s.closed(), s.melds.size());
        for (int k : t.kanOptions(seat)) {
            int[] c = s.closed();
            if (c[k] != 4) continue;
            c[k] = 0;
            if (MjRules.shanten(c, s.melds.size() + 1) <= sh) { t.ankan(seat, k, now); return; }
        }
        int tile = discardChoice(t, seat);
        boolean riichi = false;
        for (int r : t.riichiTiles(seat)) if (MjRules.kind(r) == MjRules.kind(tile)) riichi = true;
        t.discard(seat, tile, riichi, now);
    }

    /** 切る牌をえらぶ */
    static int discardChoice(MjGame.Table t, int seat) {
        MjGame.Seat s = t.seats[seat];
        if (s.riichi && s.drawn >= 0) return s.drawn;
        int[] c = s.closed();
        int[] seen = visible(t, seat);
        Set<Integer> dora = new HashSet<>();
        for (int d : t.doraKinds()) dora.add(d);
        boolean danger = false;
        for (int i = 0; i < 4; i++) if (i != seat && t.seats[i].riichi) danger = true;
        int cur = MjRules.shanten(c, s.melds.size());
        int bestTile = -1;
        double bestScore = -1e9;
        Set<Integer> tried = new HashSet<>();
        for (int tile : s.hand) {
            int k = MjRules.kind(tile);
            if (s.banned.contains(k) || !tried.add(k)) continue;
            c[k]--;
            int sh = MjRules.shanten(c, s.melds.size());
            int uke = 0;
            for (int x = 0; x < 34; x++) {
                if (c[x] >= 4) continue;
                c[x]++;
                if (MjRules.shanten(c, s.melds.size()) < sh) uke += Math.max(0, 4 - seen[x] - c[x] + 1);
                c[x]--;
            }
            c[k]++;
            double score = -sh * 1000 + uke * 4;
            // 字牌・端の孤立牌から切る。ドラと赤は残したい
            boolean isolated = c[k] == 1 && (k >= 27 || ((k % 9 == 0 || c[k - 1] == 0) && (k % 9 == 8 || c[k + 1] == 0)));
            if (isolated) score += k >= 27 ? 30 : MjRules.terminal(k) ? 20 : 8;
            if (dora.contains(k)) score -= 25;
            if (k >= 27 && c[k] >= 2) score -= 15;
            if (danger && cur >= 1) {
                double safe = safety(t, seat, k, seen);
                score = cur >= 2 ? safe * 1000 + score / 100 : score + safe * 300;
            }
            if (score > bestScore) { bestScore = score; bestTile = tile; }
        }
        if (bestTile < 0) bestTile = s.hand.get(s.hand.size() - 1);
        // 赤ではない同じ種類があれば、そちらを切る
        int k = MjRules.kind(bestTile);
        for (int tile : s.hand) if (MjRules.kind(tile) == k && !MjRules.red(tile)) return tile;
        return bestTile;
    }

    /** 立直している人に対する安全さ（大きいほど安全） */
    static double safety(MjGame.Table t, int seat, int k, int[] seen) {
        double min = 9;
        for (int i = 0; i < 4; i++) {
            if (i == seat || !t.seats[i].riichi) continue;
            double v;
            if (t.seats[i].discKinds.contains(k)) v = 9;           // 現物
            else if (k >= 27) v = seen[k] >= 2 ? 7 : 4;
            else {
                int n = k % 9;
                boolean suji = (n >= 3 && t.seats[i].discKinds.contains(k - 3)) || (n <= 5 && t.seats[i].discKinds.contains(k + 3));
                v = suji ? 5 : (n == 0 || n == 8) ? 3 : (n == 1 || n == 7) ? 2 : 1;
            }
            min = Math.min(min, v);
        }
        return min;
    }

    /** 自分から見えている牌の数 */
    static int[] visible(MjGame.Table t, int seat) {
        int[] v = new int[34];
        for (int i = 0; i < 4; i++) {
            MjGame.Seat s = t.seats[i];
            for (MjGame.River r : s.river) if (!r.taken) v[MjRules.kind(r.tile)]++;
            for (Meld m : s.melds) for (int x : m.tiles) v[MjRules.kind(x)]++;
        }
        for (int d : t.indicators(false).stream().mapToInt(o -> (Integer) o).toArray()) v[MjRules.kind(d)]++;
        return v;
    }
}
