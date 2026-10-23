package jp.komorebi.farm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * みんなの畑・家畜・ペットのようす（見るだけ）。それぞれの人が自分のぶんを送ってきて、ほかの人の画面に出す。
 * 最後に送られてきたものをメモリに持っておき、あとから来た人にも welcome で渡す（サーバーを再起動すると、また送られてくるまで空）。
 */
@Service
public class FarmService implements WelcomeContributor {

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,24}");
    private final ObjectMapper om;
    private final Map<Long, ObjectNode> farms = new ConcurrentHashMap<>();

    public FarmService(ObjectMapper om) {
        this.om = om;
    }

    /** 受け取ったものを、決まった形・大きさにそろえて取っておく。そろえたものを返す */
    public ObjectNode put(long playerId, String name, JsonNode msg) {
        ObjectNode f = om.createObjectNode();
        f.put("type", "farm");
        f.put("id", playerId);
        f.put("name", clean(name, 16));
        ArrayNode crops = f.putArray("crops");
        for (JsonNode c : msg.path("crops")) {
            if (crops.size() >= 600) break;
            int x = c.path(0).asInt(-1), y = c.path(1).asInt(-1), st = c.path(3).asInt(0);
            String t = c.path(2).asText("");
            if (x < 0 || y < 0 || x > 200 || y > 200 || !ID.matcher(t).matches()) continue;
            ArrayNode a = crops.addArray(); a.add(x); a.add(y); a.add(t); a.add(Math.max(0, Math.min(2, st)));
        }
        ArrayNode animals = f.putArray("animals");
        for (JsonNode a : msg.path("animals")) {
            if (animals.size() >= 40) break;
            String t = a.path(0).asText("");
            int x = a.path(2).asInt(-1), y = a.path(3).asInt(-1);
            if (!ID.matcher(t).matches() || x < 0 || y < 0 || x > 200 || y > 200) continue;
            ArrayNode o = animals.addArray(); o.add(t); o.add(clean(a.path(1).asText(""), 12)); o.add(x); o.add(y);
        }
        ArrayNode pets = f.putArray("pets");
        for (JsonNode p : msg.path("pets")) {
            if (pets.size() >= 8) break;
            String b = p.path(0).asText("");
            if (!ID.matcher(b).matches()) continue;
            ArrayNode o = pets.addArray(); o.add(b); o.add(Math.max(0, Math.min(20, p.path(1).asInt(0)))); o.add(clean(p.path(2).asText(""), 12));
        }
        farms.put(playerId, f);
        return f;
    }

    public void forget(long playerId) {
        farms.remove(playerId);
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        ArrayNode list = welcome.putArray("farms");
        for (Map.Entry<Long, ObjectNode> e : farms.entrySet()) if (!e.getKey().equals(session.playerId())) list.add(e.getValue());
    }

    static String clean(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}<>]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
