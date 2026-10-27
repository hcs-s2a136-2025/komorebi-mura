package jp.komorebi.casino;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * {"type":"crank","total":12345,"add":-300} … カジノの収支を知らせる（total は全期間、add は前に送ってからの分）
 * {"type":"crank","get":true} … 番付をもらう。{"type":"crank","board":{...}} が本人に返る
 */
@Component
public class CasinoRankHandler implements MessageHandler {

    private final CasinoRankService ranks;
    private final Map<Long, Long> lastPut = new ConcurrentHashMap<>(), lastGet = new ConcurrentHashMap<>();

    public CasinoRankHandler(CasinoRankService ranks) {
        this.ranks = ranks;
    }

    @Override
    public String type() {
        return "crank";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        Long id = from.playerId();
        if (id == null) return;
        long now = System.currentTimeMillis();
        if (msg.has("total") || msg.has("add")) {
            Long prev = lastPut.get(id);
            JsonNode t = msg.get("total");
            long add = msg.path("add").asLong(0);
            // 送りすぎは、全期間だけ受けて、足し算の分は落とさないよう次に回してもらう（クライアントは送れた分だけ消す）
            if (prev == null || now - prev >= 1500) {
                lastPut.put(id, now);
                ranks.record(id, t != null && t.isNumber() ? t.asLong() : null, add);
                hub.send(from, hub.json().createObjectNode().put("type", "crank").put("ok", msg.path("rid").asText("")));
            }
        }
        if (msg.path("get").asBoolean(false)) {
            Long prev = lastGet.get(id);
            if (prev != null && now - prev < 1000) return;
            lastGet.put(id, now);
            var out = hub.json().createObjectNode().put("type", "crank");
            out.set("board", ranks.board(id));
            hub.send(from, out);
        }
    }
}
