package jp.komorebi.phone;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * {"type":"grank","game":"blk","score":12345} … スマホのゲームのスコアを送る
 * {"type":"grank","get":"blk"} … ランキングをもらう。{"type":"grank","board":{...}} が本人に返る
 */
@Component
public class GameRankHandler implements MessageHandler {

    private final GameRankService ranks;
    private final Map<Long, Long> lastPut = new ConcurrentHashMap<>(), lastGet = new ConcurrentHashMap<>();

    public GameRankHandler(GameRankService ranks) {
        this.ranks = ranks;
    }

    @Override
    public String type() {
        return "grank";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        Long id = from.playerId();
        if (id == null) return;
        long now = System.currentTimeMillis();
        String game = msg.path("game").asText("");
        if (msg.has("score") && GameRankService.validGame(game)) {
            Long prev = lastPut.get(id);
            if (prev == null || now - prev >= 500) {
                lastPut.put(id, now);
                ranks.record(game, id, msg.path("score").asLong(0));
            }
        }
        String get = msg.path("get").asText("");
        if (GameRankService.validGame(get)) {
            Long prev = lastGet.get(id);
            if (prev != null && now - prev < 800) return;
            lastGet.put(id, now);
            var out = hub.json().createObjectNode().put("type", "grank");
            out.set("board", ranks.board(get, id));
            hub.send(from, out);
        }
    }
}
