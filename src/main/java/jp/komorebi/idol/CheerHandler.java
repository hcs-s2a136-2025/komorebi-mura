package jp.komorebi.idol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * アイドルのライブへの応援コール（v0.73.0）。見ている人が {"type":"cheer","to":ライブ中の人,"k":0〜3} を送ると、
 * 同じ村でつないでいる相手にだけ {"type":"cheer","from","name","k"} を届ける。1人 1秒に4回まで。とっておかない（いないときは捨てる）。
 */
@Component
public class CheerHandler implements MessageHandler {

    private final Map<Long, long[]> rate = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "cheer";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        long to = msg.path("to").asLong(0);
        int k = msg.path("k").asInt(-1);
        if (to <= 0 || to == from.playerId() || k < 0 || k > 3) return;
        long now = System.currentTimeMillis();
        long[] r = rate.computeIfAbsent(from.playerId(), x -> new long[] { now, 0 });
        synchronized (r) {
            if (now - r[0] >= 1000) { r[0] = now; r[1] = 0; }
            if (++r[1] > 4) return;
        }
        if (!hub.onlineHere(to)) return;
        hub.sendToPlayer(to, hub.json().createObjectNode().put("type", "cheer").put("from", from.playerId()).put("name", from.name() == null ? "" : from.name()).put("k", k));
    }
}
