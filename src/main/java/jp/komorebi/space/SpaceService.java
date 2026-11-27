package jp.komorebi.space;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * 宇宙開発（村ごと・v0.89.0）。村のみんなで寄付して、発射台 → ロケット → 月へ → 月面基地 → 火星 → 木星 … と進む。
 * 額はとても大きい（京・垓）ので、ぜんぶ「兆」の単位（1兆G＝1）で数えて、合計は BigInteger の文字列で持つ。
 * 共有の村の "_space" = {fund:"兆の数", lv:進んだ段階の数, ds:{段階:[寄付した人のID]}, don:{人のID:{n:名前, t:"兆の数"}}}。
 * <ul>
 *   <li>{"type":"space","a":"look"} … 本人に {"type":"space","st":{...}}</li>
 *   <li>{"type":"space","a":"give","t":兆の数,"rid"} … 寄付（1京＝10,000兆から・1回に10垓＝1,000,000,000兆まで）。本人に {"ok":"give","rid","t","st"}
 *       （<b>お金は画面がこの返事のあとで t×1兆 だけ引く</b>）。村のほかの人に {"ev":"give","n","t","st"}、段階が進んだら村みんなに {"ev":"up","lv","st"}</li>
 * </ul>
 * 段階は、合計が目標をこえて、かつその段階のあいだに<b>3人以上</b>が寄付していると進む（ひとりで月まで行けないように）。
 * 段階の額（兆）：100京・500京・3,000京・1垓・5垓・25垓、その先は5倍ずつ。画面の game.js の SPACE149 と同じにする。
 */
@Service
public class SpaceService implements MessageHandler, WelcomeContributor {

    static final String KEY = "_space";
    /** 1回の寄付の下限（1京）と上限（10垓）。どちらも兆の単位 */
    public static final long MIN_GIVE = 10_000L, MAX_GIVE = 1_000_000_000L;
    public static final int NEED_DONORS = 3;
    static final int TOP = 10;

    private final SharedWorldService world;
    private final ObjectMapper om;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public SpaceService(SharedWorldService world, ObjectMapper om) {
        this.world = world;
        this.om = om;
    }

    @Override
    public String type() {
        return "space";
    }

    /** 段階 i（0から）の額（兆） */
    public static BigInteger stage(int i) {
        long[] base = { 1_000_000L, 5_000_000L, 30_000_000L, 100_000_000L, 500_000_000L, 2_500_000_000L };
        if (i < base.length) return BigInteger.valueOf(base[i]);
        return BigInteger.valueOf(base[base.length - 1]).multiply(BigInteger.valueOf(5).pow(i - base.length + 1));
    }

    /** 段階 i をおえるのに要る合計（兆） */
    public static BigInteger cum(int i) {
        BigInteger s = BigInteger.ZERO;
        for (int k = 0; k <= i; k++) s = s.add(stage(k));
        return s;
    }

    static BigInteger big(JsonNode n) {
        try {
            String s = n == null ? "" : n.asText("");
            return s.matches("\\d{1,60}") ? new BigInteger(s) : BigInteger.ZERO;
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    private ObjectNode load() {
        JsonNode cur = world.getSystem(KEY);
        ObjectNode d = cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
        if (!d.has("fund")) d.put("fund", "0");
        if (!d.has("lv")) d.put("lv", 0);
        if (!(d.get("ds") instanceof ObjectNode)) d.putObject("ds");
        if (!(d.get("don") instanceof ObjectNode)) d.putObject("don");
        return d;
    }

    private ObjectNode out() {
        return om.createObjectNode().put("type", "space");
    }

    /** 画面に見せる形 */
    ObjectNode st(ObjectNode d, Long me) {
        ObjectNode o = om.createObjectNode();
        int lv = d.path("lv").asInt(0);
        o.put("fund", d.path("fund").asText("0")).put("lv", lv).put("need", NEED_DONORS);
        o.put("goal", cum(lv).toString()).put("stage", stage(lv).toString());
        JsonNode ds = d.path("ds").path(String.valueOf(lv));
        o.put("dn", ds.isArray() ? ds.size() : 0);
        List<Map.Entry<String, JsonNode>> all = new ArrayList<>();
        d.path("don").fields().forEachRemaining(all::add);
        all.sort((a, b) -> big(b.getValue().path("t")).compareTo(big(a.getValue().path("t"))));
        ArrayNode top = o.putArray("top");
        for (int i = 0; i < Math.min(TOP, all.size()); i++) top.addObject().put("n", all.get(i).getValue().path("n").asText("")).put("t", all.get(i).getValue().path("t").asText("0"));
        o.put("k", all.size());
        if (me != null) {
            JsonNode mine = d.path("don").path(String.valueOf(me));
            o.put("my", mine.isObject() ? mine.path("t").asText("0") : "0");
            o.put("myIn", ds.isArray() && contains(ds, me));
        }
        return o;
    }

    static boolean contains(JsonNode arr, long id) {
        for (JsonNode x : arr) if (x.asLong() == id) return true;
        return false;
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        String a = msg.path("a").asText("");
        String rid = msg.path("rid").asText("");
        if (rid.length() > 16) rid = rid.substring(0, 16);
        long now = System.currentTimeMillis();
        Long prev = last.put(from.playerId(), now);
        if (prev != null && now - prev < 300) { hub.send(from, out().put("ng", "少し待ってからもう一度").put("rid", rid)); return; }
        if ("look".equals(a)) { synchronized (this) { hub.send(from, out().set("st", st(load(), from.playerId()))); } return; }
        if ("give".equals(a)) give(from, msg.path("t").asLong(0), rid, hub);
    }

    private synchronized void give(PlayerSession from, long t, String rid, GameSocketHandler hub) {
        long me = from.playerId();
        if (t < MIN_GIVE || t > MAX_GIVE) { hub.send(from, out().put("ng", t < MIN_GIVE ? "寄付は1京から" : "1回に寄付できるのは10垓まで").put("rid", rid)); return; }
        ObjectNode d = load();
        int lv = d.path("lv").asInt(0);
        d.put("fund", big(d.get("fund")).add(BigInteger.valueOf(t)).toString());
        ObjectNode don = (ObjectNode) d.get("don");
        ObjectNode mine = don.get(String.valueOf(me)) instanceof ObjectNode x ? x : don.putObject(String.valueOf(me));
        mine.put("n", from.name() == null ? "" : from.name()).put("t", big(mine.get("t")).add(BigInteger.valueOf(t)).toString());
        ObjectNode ds = (ObjectNode) d.get("ds");
        ArrayNode cur = ds.get(String.valueOf(lv)) instanceof ArrayNode x ? x : ds.putArray(String.valueOf(lv));
        if (!contains(cur, me) && cur.size() < 200) cur.add(me);
        int before = lv;
        BigInteger fund = big(d.get("fund"));
        while (fund.compareTo(cum(lv)) >= 0) {
            JsonNode who = ds.path(String.valueOf(lv));
            if (!who.isArray() || who.size() < NEED_DONORS) break;
            lv++;
            if (lv > 200) break;
        }
        d.put("lv", lv);
        world.putSystem(KEY, d);
        hub.send(from, out().put("ok", "give").put("rid", rid).put("t", t).set("st", st(d, me)));
        hub.broadcast(out().put("ev", "give").put("n", from.name() == null ? "" : from.name()).put("t", t).set("st", st(d, null)), from);
        if (lv > before) hub.broadcast(out().put("ev", "up").put("lv", lv).put("from", before).set("st", st(d, null)), null);
    }

    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        JsonNode cur = world.getSystem(KEY);
        if (!(cur instanceof ObjectNode o)) return;
        welcome.putObject("space").put("lv", o.path("lv").asInt(0)).put("fund", o.path("fund").asText("0"));
    }

    /** 管理・テスト用：いまの村の宇宙開発を見る */
    public synchronized ObjectNode view() {
        return st(load(), null);
    }
}
