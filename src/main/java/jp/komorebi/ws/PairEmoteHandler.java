package jp.komorebi.ws;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * ふたりでするエモート（ハイタッチ・手をつなぐ・乾杯）。
 * {"type":"pair","act":"ask","to":相手のid,"e":"hifive|hand|cheers"} … 近くにいる人に申しこむ（相手にだけ届く）
 * {"type":"pair","act":"ok|no","to":申しこんだ人のid} … 返事。OK なら同じ場所の全員に {"act":"do","a":申しこんだ人,"b":相手,"e":…} を送る
 * {"type":"pair","act":"end","to":相手のid} … 手をはなす（同じ場所の全員に {"act":"end","a","b"}）
 * 申しこみは20秒で消える。OK は、本当に申しこまれていたときだけ通す（勝手にポーズをさせられないように）。
 */
@Component
public class PairEmoteHandler implements MessageHandler {
    static final Set<String> KINDS = Set.of("hifive", "hand", "cheers");
    static final long ASK_MS = 20_000;
    static final int NEAR = 3;

    record Ask(long to, String e, long at) {
    }

    private final Map<Long, Ask> asks = new ConcurrentHashMap<>();
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "pair";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        Long me = from.playerId();
        if (me == null || from.map() == null) return;
        String act = msg.path("act").asText("");
        long to = msg.path("to").asLong(0);
        if (to <= 0 || to == me) return;
        long now = System.currentTimeMillis();
        GameSocketHandler.OnlinePlayer other = hub.onlinePlayers().stream().filter(p -> p.id() != null && p.id() == to).findFirst().orElse(null);
        switch (act) {
            case "ask" -> {
                String e = msg.path("e").asText("");
                if (!KINDS.contains(e)) return;
                Long prev = last.put(me, now);
                if (prev != null && now - prev < 1500) return;
                if (other == null || !from.map().equals(other.map()) || Math.abs(other.x() - from.x()) + Math.abs(other.y() - from.y()) > NEAR) {
                    hub.send(from, hub.json().createObjectNode().put("type", "pair").put("act", "far").put("from", to));
                    return;
                }
                asks.put(me, new Ask(to, e, now));
                hub.sendToPlayer(to, hub.json().createObjectNode().put("type", "pair").put("act", "ask").put("from", me).put("name", from.name()).put("e", e));
            }
            case "ok", "no" -> {
                Ask a = asks.get(to);
                if (a == null || a.to() != me || now - a.at() > ASK_MS) return;
                asks.remove(to);
                if ("no".equals(act)) {
                    hub.sendToPlayer(to, hub.json().createObjectNode().put("type", "pair").put("act", "no").put("from", me).put("name", from.name()));
                    return;
                }
                if (other == null || !from.map().equals(other.map())) return;
                ObjectNode out = hub.json().createObjectNode().put("type", "pair").put("act", "do").put("a", to).put("b", me).put("e", a.e());
                hub.sendToMap(from.map(), out, null);
            }
            case "end" -> {
                ObjectNode out = hub.json().createObjectNode().put("type", "pair").put("act", "end").put("a", me).put("b", to);
                hub.sendToMap(from.map(), out, null);
                hub.sendToPlayer(to, out);
            }
            default -> { }
        }
    }
}
