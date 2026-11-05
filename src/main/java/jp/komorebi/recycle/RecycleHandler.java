package jp.komorebi.recycle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.world.SharedWorldService;

/**
 * よろず屋のリサイクル品の棚（村ごと）。共有の村の "_recycle" = {seq, items:[{u, id, p, o, n}]}（u＝棚の番号・p＝値段・o＝売った人のID・n＝なまえ）。
 * <ul>
 *   <li>{"type":"recycle","a":"list"} … 本人に {"type":"recycle","items":[...]}</li>
 *   <li>{"type":"recycle","a":"put","k","items":[{id,p}]} … 道具を棚に出す（1回30こまで・棚は80こまで、あふれたら古い物から消える）。本人に {"type":"recycle","ok":"put","k","items"}、村のほかの人に棚</li>
 *   <li>{"type":"recycle","a":"buy","u"} … 棚から外せたら本人に {"type":"recycleOk","u","id","p"}（お金は画面が、この返事のあとで引く）、なければ {"type":"recycleNg","text"}</li>
 * </ul>
 */
@Component
public class RecycleHandler implements MessageHandler {

    static final String KEY = "_recycle";
    static final int MAX_ITEMS = 80, MAX_PUT = 30;
    static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");

    private final SharedWorldService world;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public RecycleHandler(SharedWorldService world) {
        this.world = world;
    }

    @Override
    public String type() {
        return "recycle";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        String a = msg.path("a").asText("");
        long now = System.currentTimeMillis();
        if (!"list".equals(a)) {
            Long prev = last.put(from.playerId(), now);
            if (prev != null && now - prev < 250) {
                if ("buy".equals(a)) hub.send(from, hub.json().createObjectNode().put("type", "recycleNg").put("text", "少し待ってから買ってね"));
                return;
            }
        }
        switch (a) {
            case "list" -> hub.send(from, view(load(hub), hub));
            case "put" -> put(from, msg, hub);
            case "buy" -> buy(from, msg.path("u").asLong(0), hub);
            default -> { }
        }
    }

    private ObjectNode load(GameSocketHandler hub) {
        JsonNode cur = world.getSystem(KEY);
        ObjectNode d = cur instanceof ObjectNode o ? o.deepCopy() : hub.json().createObjectNode();
        if (!d.path("items").isArray()) d.putArray("items");
        if (!d.has("seq")) d.put("seq", 0);
        return d;
    }

    private ObjectNode view(ObjectNode d, GameSocketHandler hub) {
        ObjectNode o = hub.json().createObjectNode().put("type", "recycle");
        o.set("items", d.get("items").deepCopy());
        return o;
    }

    private synchronized void put(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        JsonNode in = msg.path("items");
        String k = msg.path("k").asText("");
        if (k.length() > 20) k = k.substring(0, 20);
        ObjectNode d = load(hub);
        ArrayNode items = (ArrayNode) d.get("items");
        long seq = d.path("seq").asLong(0);
        int n = 0;
        if (in.isArray()) for (JsonNode it : in) {
            if (n >= MAX_PUT) break;
            String id = it.path("id").asText("");
            long p = it.path("p").asLong(0);
            if (!ID.matcher(id).matches() || p < 1 || p > 10_000_000) continue;
            seq++;
            items.addObject().put("u", seq).put("id", id).put("p", p).put("o", from.playerId()).put("n", from.name());
            n++;
        }
        while (items.size() > MAX_ITEMS) items.remove(0);
        d.put("seq", seq);
        if (n > 0) world.putSystem(KEY, d);
        ObjectNode mine = view(d, hub).put("ok", "put").put("k", k);
        hub.send(from, mine);
        if (n > 0) hub.broadcast(view(d, hub), from);
    }

    private synchronized void buy(PlayerSession from, long u, GameSocketHandler hub) {
        ObjectNode d = load(hub);
        ArrayNode items = (ArrayNode) d.get("items");
        for (int i = 0; i < items.size(); i++) {
            JsonNode it = items.get(i);
            if (it.path("u").asLong() != u) continue;
            items.remove(i);
            world.putSystem(KEY, d);
            hub.send(from, hub.json().createObjectNode().put("type", "recycleOk").put("u", u).put("id", it.path("id").asText()).put("p", it.path("p").asLong()));
            hub.broadcast(view(d, hub), null);
            return;
        }
        hub.send(from, hub.json().createObjectNode().put("type", "recycleNg").put("text", "その道具は、もうだれかが買ったみたい"));
        hub.send(from, view(d, hub));
    }
}
