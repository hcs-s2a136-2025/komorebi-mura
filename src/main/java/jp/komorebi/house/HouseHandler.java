package jp.komorebi.house;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * {"type":"house","act":"get","key":"48,21"} … 家の中身をもらう
 * {"type":"house","act":"put","key":"48,21","layout":{...},"seed":{"storage":{...},"priv":{...}}} … 持ち主が家具の並びを送る
 * {"type":"house","act":"returns"} … 共有の村のリセットで消えた家の箱の中身を受け取る → {"type":"houseReturn","items":[{id,n}]}
 * 返事と、同じ家の中にいる人へのお知らせは {"type":"house","key":...,"layout":...,"storage":[...]}（持ち主には "priv" も）
 */
@Component
public class HouseHandler implements MessageHandler {

    private final HouseService houses;

    public HouseHandler(HouseService houses) {
        this.houses = houses;
    }

    @Override
    public String type() {
        return "house";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) throws Exception {
        long me = from.playerId();
        if ("returns".equals(msg.path("act").asText(""))) {
            Map<String, Long> items = houses.takeReturns(me);
            if (items.isEmpty()) return;
            ObjectNode r = hub.json().createObjectNode().put("type", "houseReturn");
            var list = r.putArray("items");
            items.forEach((id, n) -> list.addObject().put("id", id).put("n", n));
            hub.send(from, r);
            return;
        }
        String key = msg.path("key").asText("");
        if (!HouseBook.validKey(key)) return;
        if (!houses.exists(key)) { hub.send(from, hub.json().createObjectNode().put("type", "house").put("key", key).put("missing", true)); return; }
        boolean owner = houses.isOwner(key, me);
        switch (msg.path("act").asText("")) {
            case "get" -> {
                if (!houses.canEnter(key, me)) { hub.send(from, hub.json().createObjectNode().put("type", "house").put("key", key).put("locked", true)); return; }
                hub.send(from, reply(hub, key, owner));
            }
            case "put" -> {
                if (!owner) return;
                JsonNode layout = msg.get("layout");
                if (layout == null || !layout.isObject()) return;
                String text = layout.toString();
                if (text.length() > HouseBook.MAX_LAYOUT) return;
                Map<String, Long> seedShared = counts(msg.path("seed").path("storage")), seedPriv = counts(msg.path("seed").path("priv"));
                if (!houses.book().putLayout(key, text, seedShared, seedPriv)) return;
                houses.save(key);
                hub.send(from, reply(hub, key, true));
                hub.sendToMap("house:" + key, reply(hub, key, false), from);
            }
            default -> { }
        }
    }

    private ObjectNode reply(GameSocketHandler hub, String key, boolean owner) {
        ObjectNode r = hub.json().createObjectNode().put("type", "house").put("key", key).put("owner", owner).put("open", houses.isOpen(key));
        r.setAll(houses.view(key, owner));
        return r;
    }

    static Map<String, Long> counts(JsonNode n) {
        Map<String, Long> m = new LinkedHashMap<>();
        if (n == null || !n.isObject()) return m;
        n.fields().forEachRemaining(e -> { if (m.size() < HouseBook.MAX_KINDS && e.getValue().canConvertToLong()) m.put(e.getKey(), Math.max(0, Math.min(HouseBook.MAX_COUNT, e.getValue().asLong()))); });
        return m;
    }
}
