package jp.komorebi.aqua;

import java.util.ArrayList;
import java.util.List;
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
import jp.komorebi.ws.WelcomeContributor;
import jp.komorebi.world.SharedWorldService;

/**
 * みなと町の「みんなの水族館」（村ごと）。村のみんなの募金で建てて、魚を寄贈して水槽をうめていく。
 * 共有の村の "_aqua" = {fund, fish:{魚のID:{n, by}}, don:{人のID:{n, g, f}}}。
 * <ul>
 *   <li>{"type":"aqua","a":"look"} … 本人に {"type":"aqua","st":{...}}</li>
 *   <li>{"type":"aqua","a":"give","g","rid"} … 募金（1〜1,000,000,000G）。本人に {"type":"aqua","ok":"give","rid","g","st"}（お金は画面がこの返事のあとで引く）、
 *       村のほかの人に st。段階が上がったら、みんなに {"type":"aqua","up":段階,"by":なまえ,"st"}</li>
 *   <li>{"type":"aqua","a":"fish","id","n","rid"} … 建ってから、魚を寄贈（1回99ひきまで）。本人に {"type":"aqua","ok":"fish","rid","id","n","first"}
 *       （first＝この村ではじめての種類。魚は画面がこの返事のあとで減らす）、村のほかの人に st</li>
 * </ul>
 * だめなときは本人に {"type":"aqua","ng":理由,"rid"}。段階は募金の合計で決まる（GOALS）。
 */
@Component
public class AquaHandler implements MessageHandler, WelcomeContributor {

    static final String KEY = "_aqua";
    /** 段階1（建つ）・段階2（大水槽）・段階3（深海の間）に必要な、募金の合計 */
    public static final long[] GOALS = { 100_000_000L, 400_000_000L, 1_000_000_000L };
    static final long MAX_GIVE = 1_000_000_000L, MAX_FUND = 1_000_000_000_000L;
    static final int MAX_KINDS = 400, MAX_DONORS = 300, TOP = 10;
    static final Pattern ID = Pattern.compile("(fish|sea)_[^\\u0000-\\u001f<>&\"'\\\\\\s]{1,24}");

    private final SharedWorldService world;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public AquaHandler(SharedWorldService world) {
        this.world = world;
    }

    @Override
    public String type() {
        return "aqua";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        String a = msg.path("a").asText("");
        String rid = msg.path("rid").asText("");
        if (rid.length() > 16) rid = rid.substring(0, 16);
        if (!"look".equals(a)) {
            long now = System.currentTimeMillis();
            Long prev = last.put(from.playerId(), now);
            if (prev != null && now - prev < 300) {
                hub.send(from, hub.json().createObjectNode().put("type", "aqua").put("ng", "少し待ってからもう一度").put("rid", rid));
                return;
            }
        }
        switch (a) {
            case "look" -> hub.send(from, withSt(hub.json().createObjectNode().put("type", "aqua"), load(hub), hub));
            case "give" -> give(from, msg.path("g").asLong(0), rid, hub);
            case "fish" -> fish(from, msg.path("id").asText(""), msg.path("n").asInt(0), rid, hub);
            default -> { }
        }
    }

    public static int levelOf(long fund) {
        int lv = 0;
        for (long g : GOALS) if (fund >= g) lv++;
        return lv;
    }

    private ObjectNode load(GameSocketHandler hub) {
        JsonNode cur = world.getSystem(KEY);
        ObjectNode d = cur instanceof ObjectNode o ? o.deepCopy() : hub.json().createObjectNode();
        if (!d.has("fund")) d.put("fund", 0);
        if (!(d.get("fish") instanceof ObjectNode)) d.putObject("fish");
        if (!(d.get("don") instanceof ObjectNode)) d.putObject("don");
        return d;
    }

    /** 画面に見せる形：{fund, lv, goals, fish:{ID:[n, by]}, top:[{n,g,f}]} */
    ObjectNode st(ObjectNode d, GameSocketHandler hub) {
        ObjectNode o = hub.json().createObjectNode();
        long fund = d.path("fund").asLong(0);
        o.put("fund", fund).put("lv", levelOf(fund));
        ArrayNode goals = o.putArray("goals");
        for (long g : GOALS) goals.add(g);
        ObjectNode fish = o.putObject("fish");
        d.get("fish").fields().forEachRemaining(e -> fish.putArray(e.getKey()).add(e.getValue().path("n").asLong(0)).add(e.getValue().path("by").asText("")));
        List<JsonNode> dons = new ArrayList<>();
        d.get("don").forEach(dons::add);
        dons.sort((x, y) -> Long.compare(y.path("g").asLong(0), x.path("g").asLong(0)));
        ArrayNode top = o.putArray("top");
        for (int i = 0; i < Math.min(TOP, dons.size()); i++) {
            JsonNode x = dons.get(i);
            if (x.path("g").asLong(0) <= 0 && x.path("f").asLong(0) <= 0) continue;
            top.addObject().put("n", x.path("n").asText("")).put("g", x.path("g").asLong(0)).put("f", x.path("f").asLong(0));
        }
        return o;
    }

    private ObjectNode withSt(ObjectNode msg, ObjectNode d, GameSocketHandler hub) {
        msg.set("st", st(d, hub));
        return msg;
    }

    private ObjectNode donor(ObjectNode d, PlayerSession from) {
        ObjectNode don = (ObjectNode) d.get("don");
        String k = String.valueOf(from.playerId());
        ObjectNode me = don.get(k) instanceof ObjectNode o ? o : don.putObject(k);
        me.put("n", from.name() == null ? "" : from.name());
        if (don.size() > MAX_DONORS) {
            // いちばん少ない人から消す（本人は消さない）
            String low = null;
            long lowG = Long.MAX_VALUE;
            var it = don.fields();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getKey().equals(k)) continue;
                long g = e.getValue().path("g").asLong(0) + e.getValue().path("f").asLong(0) * 1000;
                if (g < lowG) { lowG = g; low = e.getKey(); }
            }
            if (low != null) don.remove(low);
        }
        return me;
    }

    private synchronized void give(PlayerSession from, long g, String rid, GameSocketHandler hub) {
        if (g < 1 || g > MAX_GIVE) {
            hub.send(from, hub.json().createObjectNode().put("type", "aqua").put("ng", "募金は 1〜1,000,000,000G で").put("rid", rid));
            return;
        }
        ObjectNode d = load(hub);
        long before = d.path("fund").asLong(0);
        long after = Math.min(MAX_FUND, before + g);
        d.put("fund", after);
        ObjectNode me = donor(d, from);
        me.put("g", me.path("g").asLong(0) + g);
        world.putSystem(KEY, d);
        int lv0 = levelOf(before), lv1 = levelOf(after);
        hub.send(from, withSt(hub.json().createObjectNode().put("type", "aqua").put("ok", "give").put("rid", rid).put("g", g), d, hub));
        if (lv1 > lv0) hub.broadcast(withSt(hub.json().createObjectNode().put("type", "aqua").put("up", lv1).put("by", from.name()), d, hub), null);
        else hub.broadcast(withSt(hub.json().createObjectNode().put("type", "aqua"), d, hub), from);
    }

    private synchronized void fish(PlayerSession from, String id, int n, String rid, GameSocketHandler hub) {
        ObjectNode d = load(hub);
        if (levelOf(d.path("fund").asLong(0)) < 1) {
            hub.send(from, hub.json().createObjectNode().put("type", "aqua").put("ng", "水族館がまだ建っていないよ").put("rid", rid));
            return;
        }
        if (!ID.matcher(id).matches() || n < 1 || n > 99) {
            hub.send(from, hub.json().createObjectNode().put("type", "aqua").put("ng", "その魚は寄贈できないよ").put("rid", rid));
            return;
        }
        ObjectNode fish = (ObjectNode) d.get("fish");
        boolean first = !fish.has(id);
        if (first && fish.size() >= MAX_KINDS) {
            hub.send(from, hub.json().createObjectNode().put("type", "aqua").put("ng", "水槽がいっぱいだよ").put("rid", rid));
            return;
        }
        ObjectNode f = fish.get(id) instanceof ObjectNode o ? o : fish.putObject(id);
        f.put("n", Math.min(1_000_000L, f.path("n").asLong(0) + n));
        if (first) f.put("by", from.name() == null ? "" : from.name());
        ObjectNode me = donor(d, from);
        me.put("f", me.path("f").asLong(0) + n);
        world.putSystem(KEY, d);
        hub.send(from, withSt(hub.json().createObjectNode().put("type", "aqua").put("ok", "fish").put("rid", rid).put("id", id).put("n", n).put("first", first), d, hub));
        hub.broadcast(withSt(hub.json().createObjectNode().put("type", "aqua"), d, hub), from);
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        if (session.playerId() == null || session.playerId() <= 0) return;
        JsonNode cur = world.getSystem(KEY);
        if (!(cur instanceof ObjectNode o) || o.path("fund").asLong(0) <= 0) return;
        ObjectNode d = o.deepCopy();
        if (!(d.get("fish") instanceof ObjectNode)) d.putObject("fish");
        if (!(d.get("don") instanceof ObjectNode)) d.putObject("don");
        ObjectNode w = welcome.objectNode();
        // st() は hub がいらない形で作る
        long fund = d.path("fund").asLong(0);
        w.put("fund", fund).put("lv", levelOf(fund));
        ArrayNode goals = w.putArray("goals");
        for (long g : GOALS) goals.add(g);
        ObjectNode fish = w.putObject("fish");
        d.get("fish").fields().forEachRemaining(e -> fish.putArray(e.getKey()).add(e.getValue().path("n").asLong(0)).add(e.getValue().path("by").asText("")));
        welcome.set("aqua", w);
    }
}
