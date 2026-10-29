package jp.komorebi.farm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * {"type":"farm","crops":[[x,y,"tomato",2]],"animals":[["cow","ハナ",x,y]],"pets":[["cat",3,"こむぎ","h3k0"]]} … 自分の畑・家畜・ペットのようす（ペットの4つ目は着せ替えの服。なくてもいい）
 * {"type":"farm","act":"steal","owner":id,"x":10,"y":20} … ほかの人の収穫どきの作物を盗む（そばにいるときだけ）
 *   → 盗んだ人に {"type":"farmSteal","ok":true,"t":"tomato",...}、持ち主に {"type":"farmStolen","items":[[x,y,"tomato","名前"]]}
 */
@Component
public class FarmHandler implements MessageHandler {

    private final FarmService farms;
    private final jp.komorebi.village.VillageService villages;
    private final Map<Long, Long> last = new ConcurrentHashMap<>(), lastSteal = new ConcurrentHashMap<>();

    public FarmHandler(FarmService farms, jp.komorebi.village.VillageService villages) {
        this.farms = farms;
        this.villages = villages;
    }

    @Override
    public String type() {
        return "farm";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null) return;
        long now = System.currentTimeMillis();
        if ("steal".equals(msg.path("act").asText(""))) {
            long me = from.playerId(), owner = msg.path("owner").asLong(0);
            int x = msg.path("x").asInt(-1), y = msg.path("y").asInt(-1);
            Long ps = lastSteal.put(me, now);
            if (owner <= 0 || owner == me || (ps != null && now - ps < 700) || !villages.same(me, owner)) return;
            ObjectNode ng = hub.json().createObjectNode().put("type", "farmSteal").put("ok", false);
            if (!"village".equals(from.map()) || Math.abs(from.x() - x) + Math.abs(from.y() - y) > 2) { hub.send(from, ng.put("text", "はなれていて、手がとどかない")); return; }
            String t = farms.steal(owner, x, y, from.name());
            if (t == null) { hub.send(from, ng.put("text", "もう収穫されちゃったみたい")); return; }
            ObjectNode f = farms.get(owner);
            hub.send(from, hub.json().createObjectNode().put("type", "farmSteal").put("ok", true).put("t", t).put("owner", owner).put("name", f == null ? "" : f.path("name").asText("")).put("x", x).put("y", y));
            if (f != null) hub.broadcast(f, null);
            ObjectNode note = hub.json().createObjectNode().put("type", "farmStolen");
            ArrayNode items = note.putArray("items"); ArrayNode e = items.addArray(); e.add(x); e.add(y); e.add(t); e.add(from.name());
            hub.sendToPlayer(owner, note);
            return;
        }
        Long prev = last.put(from.playerId(), now);
        if (prev != null && now - prev < 1500) return;
        hub.broadcast(farms.put(from.playerId(), from.name(), msg), from);
    }
}
