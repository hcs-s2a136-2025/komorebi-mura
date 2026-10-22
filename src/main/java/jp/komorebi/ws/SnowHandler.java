package jp.komorebi.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {"type":"snow","x":10,"y":8,"dir":"left","m":"village","to":12} … 雪玉を投げた。
 * 同じ場所にいる人に、投げたことを知らせる（to は当たった相手。当たったかどうかは投げた人の画面で決める）。
 */
@Component
public class SnowHandler implements MessageHandler {
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "snow";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        Long id = from.playerId();
        if (id == null) return;
        long now = System.currentTimeMillis();
        Long prev = last.get(id);
        if (prev != null && now - prev < 250) return; // 連打しすぎない
        last.put(id, now);
        int x = msg.path("x").asInt(-1), y = msg.path("y").asInt(-1);
        if (x < 0 || y < 0 || x > 200 || y > 200) return;
        String m = msg.path("m").asText("");
        if (m.length() > 32) return;
        ObjectNode out = hub.json().createObjectNode().put("type", "snow").put("id", id).put("x", x).put("y", y)
                .put("dir", Sanitize.dir(msg.get("dir"))).put("m", m);
        long to = msg.path("to").asLong(0);
        if (to > 0 && to != id) out.put("to", to);
        hub.broadcast(out, from);
    }
}
