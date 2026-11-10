package jp.komorebi.voice;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * 声の通話（v0.74.0）の「つなぎ方」の受けわたし（WebRTC のシグナリング）。声そのものは画面どうしが直接やりとりして、サーバーは通らない。
 * {"type":"rtc","to":相手,"k":"offer"|"answer"|"ice"|"bye","d":中身} を、同じ村でつないでいる相手にだけ {"type":"rtc","from","name","k","d"} で届ける。
 * 中身は 16,000 字まで・1人1秒に60通まで（その前に、1人のメッセージぜんぶで1秒30通までの制限がある。画面は ice の候補を0.25秒ぶんまとめて、配列で送る）。見学の人は使えない。とっておかない。
 */
@Component
public class RtcHandler implements MessageHandler {

    static final Set<String> KINDS = Set.of("offer", "answer", "ice", "bye");
    static final int MAX_D = 16_000, PER_SEC = 60;
    private final Map<Long, long[]> rate = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "rtc";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        long to = msg.path("to").asLong(0);
        String k = msg.path("k").asText("");
        if (to <= 0 || to == from.playerId() || !KINDS.contains(k)) return;
        JsonNode d = msg.path("d");
        if (d.toString().length() > MAX_D) return;
        long now = System.currentTimeMillis();
        long[] r = rate.computeIfAbsent(from.playerId(), x -> new long[] { now, 0 });
        synchronized (r) {
            if (now - r[0] >= 1000) { r[0] = now; r[1] = 0; }
            if (++r[1] > PER_SEC) return;
        }
        if (!hub.onlineHere(to)) return;
        ObjectNode m = hub.json().createObjectNode().put("type", "rtc").put("from", from.playerId()).put("name", from.name() == null ? "" : from.name()).put("k", k);
        if (!d.isMissingNode()) m.set("d", d.deepCopy());
        hub.sendToPlayer(to, m);
    }
}
