package jp.komorebi.casino;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** みんなのどんぐりBJのルールだけを確かめる（Springなしで動く） */
class BjGameTest {

    final Map<Long, Long> money = new HashMap<>();
    final Map<Long, Map<String, Object>> settled = new HashMap<>();
    final List<Map<String, Object>> states = new ArrayList<>();

    final BjGame.Out out = new BjGame.Out() {
        @Override
        public void toAll(Map<String, Object> msg) {
            states.add(msg);
        }

        @Override
        public void toPlayer(long id, Map<String, Object> m) {
            if ("bjCharge".equals(m.get("type"))) money.merge(id, -((Number) m.get("amount")).longValue(), Long::sum);
            if ("bjSettle".equals(m.get("type"))) { money.merge(id, ((Number) m.get("payout")).longValue(), Long::sum); settled.put(id, m); }
        }
    };

    Map<String, Object> last() {
        return states.get(states.size() - 1);
    }

    @Test
    void twoPlayersTakeTurnsAndSettle() {
        BjGame g = new BjGame(new Random(1), out);
        g.command(1, "りこ", "sit", 0, 0);
        g.command(2, "じてか", "sit", 0, 0);
        g.stack(9, 9, 9, 8, 5, 6, 9); // りこ 10+9、じてか 10+6、ディーラー 10+7
        g.command(1, "りこ", "bet", 100, 0);
        assertThat(g.phase()).isEqualTo("bet");
        g.command(2, "じてか", "bet", 200, 0);
        assertThat(g.phase()).isEqualTo("play");
        assertThat(last().get("turn")).isEqualTo(1L);
        g.command(2, "じてか", "hit", 0, 0); // 順番じゃないので何もしない
        g.command(1, "りこ", "stand", 0, 0);
        assertThat(last().get("turn")).isEqualTo(2L);
        g.command(2, "じてか", "hit", 0, 0); // 16 + 10 でバースト
        assertThat(g.phase()).isEqualTo("result");
        assertThat(money).containsEntry(1L, 100L).containsEntry(2L, -200L);
        g.tick(BjGame.RESULT_MS + 1, id -> true);
        assertThat(g.phase()).isEqualTo("bet");
    }

    @Test
    void splitDoubleAndBlackjackPay() {
        BjGame g = new BjGame(new Random(2), out);
        g.command(5, "A", "sit", 0, 0);
        g.stack(7, 9, 7, 9, 0, 2, 9); // 8,8 をスプリット、ディーラー 20
        g.command(5, "A", "bet", 100, 0);
        g.command(5, "A", "split", 0, 0);
        g.command(5, "A", "stand", 0, 0);
        g.command(5, "A", "double", 0, 0);
        assertThat(settled.get(5L)).containsEntry("staked", 300L).containsEntry("payout", 400L);

        BjGame b = new BjGame(new Random(3), out);
        b.command(6, "B", "sit", 0, 0);
        b.stack(0, 9, 9, 7); // A と 10 でブラックジャック
        b.command(6, "B", "bet", 100, 0);
        assertThat(settled.get(6L)).containsEntry("payout", 250L);
    }

    @Test
    void timeoutAndDisconnectDoNotStopTheTable() {
        BjGame g = new BjGame(new Random(4), out);
        g.command(1, "X", "sit", 0, 0);
        g.command(2, "Y", "sit", 0, 0);
        g.command(1, "X", "bet", 10, 0);
        g.tick(BjGame.BET_MS + 1, id -> true); // Y は賭けずに時間切れ → X だけで配る
        assertThat(g.phase()).isIn("play", "result");
        g.tick(BjGame.BET_MS + BjGame.TURN_MS + 10, id -> id != 1); // X がいなくなっても自動でスタンド
        g.tick(BjGame.BET_MS + BjGame.TURN_MS + 20, id -> id != 1);
        assertThat(g.phase()).isIn("result", "bet");
    }
}
