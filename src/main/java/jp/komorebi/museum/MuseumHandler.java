package jp.komorebi.museum;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * 村の博物館（村ごと・v0.79.0）。魚・虫・化石・料理・家具を1種類につき1つ寄贈して、展示をうめていく。展示には、寄贈した人の名前が出る。
 * 共有の村の "_museum" = {it:{物のID:[なまえ, 人のID]}, don:{人のID:{n, c}}}。
 * <ul>
 *   <li>{"type":"museum","a":"look"} … 本人に {"type":"museum","st":{it:{ID:なまえ}, top:[{n,c}], n}}</li>
 *   <li>{"type":"museum","a":"give","id","rid"} … 寄贈。本人に {"type":"museum","ok":"give","rid","id","st"}（<b>物は画面がこの返事のあとで減らす</b>）、
 *       村のほかの人に {"type":"museum","ev":"give","id","by"}。もう展示されている種類は ng</li>
 * </ul>
 */
@Component
public class MuseumHandler implements MessageHandler {

    static final String KEY = "_museum";
    static final int MAX_KINDS = 1500, MAX_DONORS = 300, TOP = 10;
    /** 寄贈できる物：魚（fish_・sea_）・虫（bug_）・化石（fossil_）・料理（dish_）・家具（f_） */
    static final Pattern ID = Pattern.compile("(fish|sea|bug|fossil|dish|f)_[^\\u0000-\\u001f<>&\"'\\\\\\s]{1,28}");

    private final SharedWorldService world;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public MuseumHandler(SharedWorldService world) {
        this.world = world;
    }

    @Override
    public String type() {
        return "museum";
    }

    private ObjectNode load(GameSocketHandler hub) {
        JsonNode cur = world.getSystem(KEY);
        ObjectNode d = cur instanceof ObjectNode o ? o.deepCopy() : hub.json().createObjectNode();
        if (!(d.get("it") instanceof ObjectNode)) d.putObject("it");
        if (!(d.get("don") instanceof ObjectNode)) d.putObject("don");
        return d;
    }

    private ObjectNode st(ObjectNode d, GameSocketHandler hub) {
        ObjectNode o = hub.json().createObjectNode(), it = o.putObject("it");
        d.get("it").fields().forEachRemaining(e -> it.put(e.getKey(), e.getValue().path(0).asText("")));
        o.put("n", it.size());
        List<JsonNode> dons = new ArrayList<>();
        d.get("don").forEach(dons::add);
        dons.sort((x, y) -> Integer.compare(y.path("c").asInt(0), x.path("c").asInt(0)));
        ArrayNode top = o.putArray("top");
        for (int i = 0; i < Math.min(TOP, dons.size()); i++) top.addObject().put("n", dons.get(i).path("n").asText("")).put("c", dons.get(i).path("c").asInt(0));
        return o;
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        String a = msg.path("a").asText("");
        String rid = msg.path("rid").asText("");
        if (rid.length() > 16) rid = rid.substring(0, 16);
        if ("look".equals(a)) { synchronized (this) { ObjectNode m = hub.json().createObjectNode().put("type", "museum"); m.set("st", st(load(hub), hub)); hub.send(from, m); } return; }
        if (!"give".equals(a)) return;
        long now = System.currentTimeMillis();
        Long prev = last.put(from.playerId(), now);
        if (prev != null && now - prev < 300) { hub.send(from, hub.json().createObjectNode().put("type", "museum").put("ng", "少し待ってからもう一度").put("rid", rid)); return; }
        give(from, msg.path("id").asText(""), rid, hub);
    }

    private synchronized void give(PlayerSession from, String id, String rid, GameSocketHandler hub) {
        ObjectNode d = load(hub), it = (ObjectNode) d.get("it");
        String ng = !ID.matcher(id).matches() ? "これは寄贈できないみたい" : it.has(id) ? "それは、もう展示されているよ" : it.size() >= MAX_KINDS ? "展示がいっぱいみたい" : null;
        if (ng != null) { hub.send(from, hub.json().createObjectNode().put("type", "museum").put("ng", ng).put("rid", rid)); return; }
        String name = from.name() == null ? "" : from.name();
        it.putArray(id).add(name).add(from.playerId());
        ObjectNode don = (ObjectNode) d.get("don");
        String k = String.valueOf(from.playerId());
        ObjectNode me = don.get(k) instanceof ObjectNode o ? o : don.putObject(k);
        me.put("n", name).put("c", me.path("c").asInt(0) + 1);
        if (don.size() > MAX_DONORS) {
            String low = null; int lowC = Integer.MAX_VALUE;
            var f = don.fields();
            while (f.hasNext()) { var e = f.next(); if (!e.getKey().equals(k) && e.getValue().path("c").asInt(0) < lowC) { lowC = e.getValue().path("c").asInt(0); low = e.getKey(); } }
            if (low != null) don.remove(low);
        }
        world.putSystem(KEY, d);
        ObjectNode ok = hub.json().createObjectNode().put("type", "museum").put("ok", "give").put("rid", rid).put("id", id);
        ok.set("st", st(d, hub));
        hub.send(from, ok);
        hub.broadcast(hub.json().createObjectNode().put("type", "museum").put("ev", "give").put("id", id).put("by", name), from);
    }
}
