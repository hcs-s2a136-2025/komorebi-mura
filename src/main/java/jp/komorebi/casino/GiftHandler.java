package jp.komorebi.casino;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * {"type":"gift","to":3,"amount":500} … 友達にお小遣いを渡す。
 * 相手がつないでいるときだけ届けて、渡した人には giftOk（届いた）か giftNg（届かなかった）を返す。
 * お金は各自のセーブにあるので、渡した側は giftOk を受け取ってから自分のお金を減らす。
 */
@Component
public class GiftHandler implements MessageHandler {

    public static final long MAX = 100_000;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "gift";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        long to = msg.path("to").asLong(-1), amount = msg.path("amount").asLong(0);
        String reqId = msg.path("rid").asText("");
        if (reqId.length() > 20) reqId = reqId.substring(0, 20);
        if (to < 0 || to == from.playerId() || amount <= 0 || amount > MAX) {
            hub.send(from, hub.json().createObjectNode().put("type", "giftNg").put("rid", reqId).put("text", "渡せる額は1〜100,000Gだよ"));
            return;
        }
        long now = System.currentTimeMillis();
        Long prev = last.put(from.playerId(), now);
        if (prev != null && now - prev < 2000) {
            hub.send(from, hub.json().createObjectNode().put("type", "giftNg").put("rid", reqId).put("text", "少し待ってから渡してね"));
            return;
        }
        if (!hub.onlineHere(to)) {
            hub.send(from, hub.json().createObjectNode().put("type", "giftNg").put("rid", reqId).put("text", "相手がいまつないでいないみたい"));
            return;
        }
        hub.sendToPlayer(to, hub.json().createObjectNode().put("type", "gift").put("from", from.playerId()).put("name", from.name()).put("amount", amount));
        hub.send(from, hub.json().createObjectNode().put("type", "giftOk").put("rid", reqId).put("to", to).put("amount", amount));
    }
}
