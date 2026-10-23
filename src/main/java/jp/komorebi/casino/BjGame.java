package jp.komorebi.casino;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongPredicate;

/**
 * みんなで遊ぶ「どんぐりBJ」の卓（ディーラーはCPUのクルミさん）。
 * Spring にも WebSocket にも依存しない、ルールだけのクラス。外とのやりとりは {@link Out} 経由。
 *
 * 流れ：bet（賭け金を決める）→ play（1人ずつ順番に）→ result（精算して少し待つ）→ bet …
 * - 座った全員が賭けるか、最初の人が賭けてから BET_MS たつと配りはじめる
 * - 自分の番で TURN_MS たつと自動でスタンド
 * - お金は各自のセーブにあるので、サーバーは「いくら減らす／増やす」を本人に伝えるだけ（bjCharge / bjSettle）
 */
public final class BjGame {

    public static final int MAX_SEATS = 5;
    public static final long BET_MS = 15_000, TURN_MS = 25_000, RESULT_MS = 6_000, MAX_BET = 1_000_000;
    private static final int DECKS = 6;

    /** 外に知らせる口。toAll は全員へ、toPlayer はその人だけへ */
    public interface Out {
        void toAll(Map<String, Object> msg);

        void toPlayer(long playerId, Map<String, Object> msg);
    }

    static final class Hand {
        final List<Integer> cards = new ArrayList<>();
        long bet;
        boolean doubled, done, splitAce;
        String result = "";
    }

    static final class Seat {
        final long id;
        String name;
        long bet;
        long staked;
        boolean leaving;
        final List<Hand> hands = new ArrayList<>();
        int cur;

        Seat(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final Random rnd;
    private final Out out;
    private final List<Seat> seats = new ArrayList<>();
    private final List<Integer> shoe = new ArrayList<>();
    private final List<Integer> dealer = new ArrayList<>();
    private String phase = "bet";
    private long deadline;
    private int turn = -1;
    private boolean reveal;
    private String msg = "";
    private boolean split;

    public BjGame(Random rnd, Out out) {
        this.rnd = rnd;
        this.out = out;
        refill();
    }

    // ---------- カード ----------

    /** カードは 0〜51：rank = c % 13（0=A … 12=K）、suit = c / 13 */
    static int value(int c) {
        int r = c % 13;
        return r == 0 ? 11 : Math.min(10, r + 1);
    }

    static int total(List<Integer> cards) {
        int v = 0, aces = 0;
        for (int c : cards) {
            v += value(c);
            if (c % 13 == 0) aces++;
        }
        while (v > 21 && aces > 0) {
            v -= 10;
            aces--;
        }
        return v;
    }

    static boolean natural(List<Integer> cards) {
        return cards.size() == 2 && total(cards) == 21;
    }

    private void refill() {
        shoe.clear();
        for (int d = 0; d < DECKS; d++) for (int c = 0; c < 52; c++) shoe.add(c);
        for (int i = shoe.size() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = shoe.get(i);
            shoe.set(i, shoe.get(j));
            shoe.set(j, t);
        }
    }

    private int draw() {
        if (shoe.isEmpty()) refill();
        return shoe.remove(shoe.size() - 1);
    }

    // ---------- 入口 ----------

    /** act: look / sit / leave / bet(amount) / hit / stand / double / split */
    public synchronized void command(long playerId, String name, String act, long amount, long now) {
        Seat s = seat(playerId);
        if (s != null && name != null && !name.isBlank()) s.name = name;
        switch (act == null ? "" : act) {
            case "look" -> out.toPlayer(playerId, state(now));
            case "sit" -> sit(playerId, name, now);
            case "leave" -> leave(s, now);
            case "bet" -> bet(s, amount, now);
            case "hit", "stand", "double", "split" -> play(s, act, now);
            default -> { }
        }
    }

    /** 0.5秒ごとくらいに呼ぶ。online はその人がいまつないでいるか */
    /** 卓についている人（本人がいなければ空） */
    public synchronized List<Long> mates(long pid) {
        List<Long> out = new ArrayList<>();
        if (seats.stream().noneMatch(x -> x.id == pid)) return out;
        for (Seat s : seats) out.add(s.id);
        return out;
    }

    public synchronized void tick(long now, LongPredicate online) {
        boolean changed = false;
        for (Seat s : new ArrayList<>(seats)) {
            if (online.test(s.id)) continue;
            if (!phase.equals("bet")) {
                // ラウンド中は席の順番を変えない（精算のあとで立たせる）
                if (!s.leaving) { s.leaving = true; changed = true; }
                if (current() == s) { standCurrent(now); changed = false; }
            } else {
                seats.remove(s);
                changed = true;
            }
        }
        switch (phase) {
            case "bet" -> {
                if (deadline > 0 && now >= deadline) { start(now); return; }
            }
            case "play" -> {
                if (now >= deadline) { msg = "時間切れでスタンド"; standCurrent(now); return; }
            }
            case "result" -> {
                if (now >= deadline) { nextRound(now); return; }
            }
            default -> { }
        }
        if (changed) broadcast(now);
    }

    // ---------- 座る・立つ・賭ける ----------

    private void sit(long id, String name, long now) {
        if (seat(id) != null) { out.toPlayer(id, state(now)); return; }
        if (seats.size() >= MAX_SEATS) { ng(id, "満席みたい。少し待ってね"); out.toPlayer(id, state(now)); return; }
        Seat s = new Seat(id, name == null || name.isBlank() ? "だれか" : name);
        seats.add(s);
        msg = s.name + (phase.equals("bet") ? "が座った" : "が座った（次のラウンドから）");
        broadcast(now);
    }

    private void leave(Seat s, long now) {
        if (s == null) return;
        if (phase.equals("bet")) {
            if (s.bet > 0) charge(s.id, -s.bet, "賭け金を返してもらった");
            seats.remove(s);
            msg = s.name + "が席を立った";
            if (seats.stream().noneMatch(x -> x.bet > 0)) deadline = 0;
            else if (allBet()) { start(now); return; }
        } else if (phase.equals("play")) {
            s.leaving = true;
            msg = s.name + (s.hands.isEmpty() ? "が席を立つ" : "は精算のあと席を立つ");
            if (current() == s) { while (current() == s) standCurrent(now); return; }
        } else {
            seats.remove(s);
            msg = s.name + "が席を立った";
        }
        broadcast(now);
    }

    private void bet(Seat s, long amount, long now) {
        if (s == null || !phase.equals("bet")) return;
        if (s.bet > 0) { ng(s.id, "このラウンドはもう賭けたよ"); return; }
        if (amount < 1 || amount > MAX_BET) { ng(s.id, "賭け金は1〜1,000,000Gだよ"); return; }
        s.bet = amount;
        charge(s.id, amount, "賭け金");
        if (deadline == 0) deadline = now + BET_MS;
        msg = s.name + "が " + amount + "G 賭けた";
        if (allBet()) { start(now); return; }
        broadcast(now);
    }

    private boolean allBet() {
        return !seats.isEmpty() && seats.stream().allMatch(x -> x.bet > 0);
    }

    // ---------- ラウンド ----------

    private void start(long now) {
        if (seats.stream().noneMatch(x -> x.bet > 0)) { deadline = 0; broadcast(now); return; }
        if (shoe.size() < 78) refill();
        dealer.clear();
        reveal = false;
        split = false;
        for (Seat s : seats) {
            s.hands.clear();
            s.cur = 0;
            s.staked = s.bet;
            if (s.bet <= 0) continue;
            Hand h = new Hand();
            h.bet = s.bet;
            s.hands.add(h);
        }
        for (int round = 0; round < 2; round++) {
            for (Seat s : seats) for (Hand h : s.hands) h.cards.add(draw());
            dealer.add(draw());
        }
        int up = value(dealer.get(0));
        if ((up == 10 || up == 11) && natural(dealer)) {
            msg = "ディーラーはブラックジャック！";
            settle(now);
            return;
        }
        for (Seat s : seats) for (Hand h : s.hands) if (natural(h.cards)) h.done = true;
        phase = "play";
        msg = "配りました";
        turn = -1;
        advance(now);
    }

    private Seat current() {
        return phase.equals("play") && turn >= 0 && turn < seats.size() ? seats.get(turn) : null;
    }

    /** いまの番が終わっていたら、次の手・次の人へ。だれも残っていなければディーラーの番 */
    private void advance(long now) {
        if (turn < 0) turn = 0;
        while (turn < seats.size()) {
            Seat s = seats.get(turn);
            while (s.cur < s.hands.size() && s.hands.get(s.cur).done) s.cur++;
            if (s.cur < s.hands.size()) {
                deadline = now + TURN_MS;
                broadcast(now);
                return;
            }
            turn++;
        }
        dealerPlay(now);
    }

    private void play(Seat s, String act, long now) {
        if (s == null || current() != s) return;
        Hand h = s.hands.get(s.cur);
        switch (act) {
            case "hit" -> {
                h.cards.add(draw());
                int v = total(h.cards);
                msg = s.name + "：ヒット（" + v + "）";
                if (v >= 21) h.done = true;
            }
            case "stand" -> {
                h.done = true;
                msg = s.name + "：スタンド（" + total(h.cards) + "）";
            }
            case "double" -> {
                if (h.cards.size() != 2 || h.splitAce) return;
                charge(s.id, h.bet, "ダブルダウン");
                s.staked += h.bet;
                h.bet *= 2;
                h.doubled = true;
                h.cards.add(draw());
                h.done = true;
                msg = s.name + "：ダブル（" + total(h.cards) + "）";
            }
            case "split" -> {
                if (s.hands.size() != 1 || h.cards.size() != 2 || h.cards.get(0) % 13 != h.cards.get(1) % 13) return;
                charge(s.id, h.bet, "スプリット");
                s.staked += h.bet;
                Hand b = new Hand();
                b.bet = h.bet;
                b.cards.add(h.cards.remove(1));
                boolean ace = h.cards.get(0) % 13 == 0;
                h.cards.add(draw());
                b.cards.add(draw());
                h.splitAce = b.splitAce = ace;
                if (ace) { h.done = true; b.done = true; }
                s.hands.add(b);
                split = true;
                msg = s.name + "：スプリット";
            }
            default -> { return; }
        }
        advance(now);
    }

    private void standCurrent(long now) {
        Seat s = current();
        if (s == null) return;
        s.hands.get(s.cur).done = true;
        advance(now);
    }

    private void dealerPlay(long now) {
        reveal = true;
        boolean anyAlive = false;
        for (Seat s : seats) for (Hand h : s.hands) if (total(h.cards) <= 21 && !(natural(h.cards) && s.hands.size() == 1)) anyAlive = true;
        if (anyAlive) while (total(dealer) < 17) dealer.add(draw());
        int dv = total(dealer);
        msg = dv > 21 ? "ディーラーは " + dv + " でバースト！" : "ディーラーは " + dv;
        settle(now);
    }

    private void settle(long now) {
        reveal = true;
        int dv = total(dealer);
        boolean dBJ = natural(dealer);
        for (Seat s : seats) {
            if (s.hands.isEmpty()) continue;
            long payout = 0;
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < s.hands.size(); i++) {
                Hand h = s.hands.get(i);
                int v = total(h.cards);
                boolean bj = natural(h.cards) && s.hands.size() == 1;
                if (v > 21) h.result = "バースト";
                else if (bj && !dBJ) { h.result = "ブラックジャック！"; payout += h.bet * 5 / 2; }
                else if (dBJ && !bj) h.result = "負け";
                else if (dBJ) { h.result = "引き分け"; payout += h.bet; }
                else if (dv > 21 || v > dv) { h.result = "勝ち"; payout += h.bet * 2; }
                else if (v == dv) { h.result = "引き分け"; payout += h.bet; }
                else h.result = "負け";
                h.done = true;
                lines.add((s.hands.size() > 1 ? "ハンド" + (i + 1) : "あなた") + "：" + h.result + "（賭け " + h.bet + "G" + (h.doubled ? "・ダブル" : "") + "）");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "bjSettle");
            m.put("staked", s.staked);
            m.put("payout", payout);
            m.put("lines", lines);
            m.put("dealer", dv);
            m.put("dealerBJ", dBJ);
            out.toPlayer(s.id, m);
        }
        phase = "result";
        turn = -1;
        deadline = now + RESULT_MS;
        broadcast(now);
    }

    private void nextRound(long now) {
        seats.removeIf(s -> s.leaving);
        for (Seat s : seats) {
            s.bet = 0;
            s.staked = 0;
            s.hands.clear();
            s.cur = 0;
        }
        dealer.clear();
        reveal = false;
        phase = "bet";
        deadline = 0;
        turn = -1;
        msg = seats.isEmpty() ? "" : "次のラウンド。賭け金を決めてね";
        broadcast(now);
    }

    // ---------- 道具 ----------

    private Seat seat(long id) {
        for (Seat s : seats) if (s.id == id) return s;
        return null;
    }

    private void charge(long id, long amount, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "bjCharge");
        m.put("amount", amount);
        m.put("reason", reason);
        out.toPlayer(id, m);
    }

    private void ng(long id, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "bjNg");
        m.put("text", text);
        out.toPlayer(id, m);
    }

    private void broadcast(long now) {
        out.toAll(state(now));
    }

    public synchronized Map<String, Object> state(long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "bjState");
        m.put("phase", phase);
        m.put("left", deadline > 0 ? Math.max(0, deadline - now) : 0);
        m.put("turn", current() == null ? -1 : current().id);
        m.put("msg", msg);
        List<Integer> d = new ArrayList<>(dealer);
        if (!reveal && d.size() > 1) d.set(1, -1);
        m.put("dealer", d);
        m.put("dealerTotal", reveal ? total(dealer) : dealer.isEmpty() ? 0 : value(dealer.get(0)));
        m.put("shoe", shoe.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Seat s : seats) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", s.id);
            x.put("name", s.name);
            x.put("bet", s.bet);
            x.put("leaving", s.leaving);
            x.put("cur", s.cur);
            List<Map<String, Object>> hs = new ArrayList<>();
            for (Hand h : s.hands) {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("cards", new ArrayList<>(h.cards));
                y.put("total", total(h.cards));
                y.put("bet", h.bet);
                y.put("doubled", h.doubled);
                y.put("done", h.done);
                y.put("result", h.result);
                hs.add(y);
            }
            x.put("hands", hs);
            list.add(x);
        }
        m.put("seats", list);
        return m;
    }

    /** テスト用：シューの一番上（次に配られるカード）を決める */
    synchronized void stack(int... topFirst) {
        for (int i = topFirst.length - 1; i >= 0; i--) shoe.add(topFirst[i]);
    }

    synchronized String phase() {
        return phase;
    }
}
