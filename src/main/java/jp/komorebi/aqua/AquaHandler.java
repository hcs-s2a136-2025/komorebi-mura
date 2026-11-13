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
 * 共有の村の "_aqua" = {fund, fish:{魚のID:{n, by}}, don:{人のID:{n, g, f}}, gave:{人のID:{魚のID:数}}}。
 * <ul>
 *   <li>{"type":"aqua","a":"look"} … 本人に {"type":"aqua","st":{...}}</li>
 *   <li>{"type":"aqua","a":"give","g","rid"} … 募金（1〜1,000,000,000G）。本人に {"type":"aqua","ok":"give","rid","g","st"}（お金は画面がこの返事のあとで引く）、
 *       村のほかの人に st。段階が上がったら、みんなに {"type":"aqua","up":段階,"by":なまえ,"st"}</li>
 *   <li>{"type":"aqua","a":"fish","id","n","rid"} … 建ってから、魚を寄贈（1回99ひきまで）。本人に {"type":"aqua","ok":"fish","rid","id","n","first"}
 *       （first＝この村ではじめての種類。魚は画面がこの返事のあとで減らす）、村のほかの人に st</li>
 *   <li>{"type":"aqua","a":"back","id","n","rid"} … 寄贈した魚を引きとる（1回99ひきまで）。本人に {"type":"aqua","ok":"back","rid","id","n","my"}（魚は画面がこの返事のあとで増やす）、
 *       村のほかの人に st。だれがどの魚を入れたかは gave:{人のID:{魚のID:数}} に持つ。記録をつける前（v0.80.0 より前）に寄贈された分は、
 *       その人が寄贈した数（don の f − gave の合計）まで、記録のない魚から好きな種類を引きとれる。look・fish・back の返事には本人だけの my（{q, g, u}）</li>
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
            case "look" -> { ObjectNode d = load(hub); hub.send(from, withMy(withSt(hub.json().createObjectNode().put("type", "aqua"), d, hub), d, from)); }
            case "back" -> back(from, msg.path("id").asText(""), msg.path("n").asInt(0), rid, hub);
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
        if (!(d.get("gave") instanceof ObjectNode)) d.putObject("gave");
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
        d.get("fish").fields().forEachRemaining(e -> { if (e.getValue().path("n").asLong(0) > 0) fish.putArray(e.getKey()).add(e.getValue().path("n").asLong(0)).add(e.getValue().path("by").asText("")); });
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

    /** その魚のうち、だれが入れたかの記録がない分（記録をつける前に寄贈された分） */
    private long loose(ObjectNode d, String id) {
        long n = d.get("fish").path(id).path("n").asLong(0);
        for (JsonNode g : d.get("gave")) n -= g.path(id).asLong(0);
        return Math.max(0, n);
    }

    /** 記録をつける前に、その人が寄贈した数のうち、まだ引きとっていない分 */
    private long quota(ObjectNode d, String pid) {
        long q = d.get("don").path(pid).path("f").asLong(0);
        for (JsonNode n : d.get("gave").path(pid)) q -= n.asLong(0);
        return Math.max(0, q);
    }

    /** 本人にだけ見せる：{q: 記録のない分から引きとれる数, g:{魚:自分が入れた数}, u:{魚:記録のない数}} */
    private ObjectNode withMy(ObjectNode msg, ObjectNode d, PlayerSession from) {
        String pid = String.valueOf(from.playerId());
        ObjectNode my = msg.putObject("my");
        long q = quota(d, pid);
        my.put("q", q);
        ObjectNode g = my.putObject("g");
        d.get("gave").path(pid).fields().forEachRemaining(e -> { if (e.getValue().asLong(0) > 0) g.put(e.getKey(), e.getValue().asLong(0)); });
        ObjectNode u = my.putObject("u");
        if (q > 0) d.get("fish").fieldNames().forEachRemaining(id -> { long n = loose(d, id); if (n > 0) u.put(id, n); });
        return msg;
    }

    /** 寄贈した魚を引きとる。自分が入れたと記録のある分が先、足りなければ記録のない分から（自分が寄贈した数まで） */
    private synchronized void back(PlayerSession from, String id, int n, String rid, GameSocketHandler hub) {
        ObjectNode d = load(hub);
        String pid = String.valueOf(from.playerId());
        if (!ID.matcher(id).matches() || n < 1 || n > 99 || !(d.get("fish").get(id) instanceof ObjectNode f)) {
            hub.send(from, hub.json().createObjectNode().put("type", "aqua").put("ng", "その魚は引きとれないよ").put("rid", rid));
            return;
        }
        ObjectNode gave = (ObjectNode) d.get("gave");
        long own = gave.path(pid).path(id).asLong(0);
        long k1 = Math.min(n, Math.min(own, f.path("n").asLong(0)));
        long k2 = Math.min(n - k1, Math.min(quota(d, pid), loose(d, id)));
        long k = k1 + k2;
        if (k < 1) {
            hub.send(from, withMy(withSt(hub.json().createObjectNode().put("type", "aqua").put("ng", "引きとれる分がないよ").put("rid", rid), d, hub), d, from));
            return;
        }
        f.put("n", Math.max(0, f.path("n").asLong(0) - k));   // 0 になっても行は残す（同じ種類をもう一度「はじめて」にしない）
        if (k1 > 0 && gave.get(pid) instanceof ObjectNode mine) {
            if (own - k1 > 0) mine.put(id, own - k1); else mine.remove(id);
            if (mine.isEmpty()) gave.remove(pid);
        }
        if (d.get("don").get(pid) instanceof ObjectNode me) me.put("f", Math.max(0, me.path("f").asLong(0) - k));
        world.putSystem(KEY, d);
        hub.send(from, withMy(withSt(hub.json().createObjectNode().put("type", "aqua").put("ok", "back").put("rid", rid).put("id", id).put("n", k), d, hub), d, from));
        hub.broadcast(withSt(hub.json().createObjectNode().put("type", "aqua"), d, hub), from);
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
            if (low != null) { don.remove(low); if (d.get("gave") instanceof ObjectNode g) g.remove(low); }
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
        ObjectNode gave = (ObjectNode) d.get("gave");
        String pid = String.valueOf(from.playerId());
        ObjectNode mine = gave.get(pid) instanceof ObjectNode o ? o : gave.putObject(pid);
        mine.put(id, mine.path(id).asLong(0) + n);
        world.putSystem(KEY, d);
        hub.send(from, withMy(withSt(hub.json().createObjectNode().put("type", "aqua").put("ok", "fish").put("rid", rid).put("id", id).put("n", n).put("first", first), d, hub), d, from));
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
        d.get("fish").fields().forEachRemaining(e -> { if (e.getValue().path("n").asLong(0) > 0) fish.putArray(e.getKey()).add(e.getValue().path("n").asLong(0)).add(e.getValue().path("by").asText("")); });
        welcome.set("aqua", w);
    }
}
