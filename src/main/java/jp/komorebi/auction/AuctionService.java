package jp.komorebi.auction;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.village.VillageService;
import jp.komorebi.village.Villages;
import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * 週に1回のオークション（村ごと・v0.79.0）。毎週1つだけの限定品を、村のみんなで競り合う。しめきりは金曜の21:00（日本時間）。
 * 共有の村の "_auction" = {seq, cur:{id, item, start, end, k, top:{u,n,g}?}, last:[{id,item,n,g,at}], pend:{人のID:[{pid, why, g, item, id}]}}。
 * <ul>
 *   <li>{"type":"auc","a":"look"} … 本人に {"type":"auc","st":{...}}</li>
 *   <li>{"type":"auc","a":"bid","g","rid"} … 入札。本人に {"type":"auc","ok":"bid","rid","g","back","st"}
 *       （<b>お金は画面がこの返事のあとで g − back だけ引く</b>。back＝自分が前に入れていた額）。上回られた人には、預かっていた額を全額返す（pay の why:"refund"）</li>
 *   <li>{"type":"auc","a":"ack","pids":[...]} … 受けとった pay を消す。pay は、画面が受けとったと言うまで pend に残して、welcome のたびに渡す
 *       （画面は pid で、同じ pay を2回は受けとらない）</li>
 * </ul>
 * しめきりが来たら、いちばん高い人に {why:"win", item}（落札額はそのまま消える）、村に {"ev":"end"}。だれも入れなければ、同じ品を次の週に持ちこす。
 * しめきり前の60秒に入札があると、しめきりを60秒のばす。品物は ITEMS の順（画面の AUC139 と同じ名前）。
 */
@Service
public class AuctionService implements MessageHandler, WelcomeContributor {

    static final String KEY = "_auction";
    /** 出す品（この順にくり返す）。画面の game.js の AUC139 と同じ名前にする */
    public static final String[] ITEMS = { "f_gthrone139", "aura_gold139", "f_gpiano139", "aura_rainbow139", "f_gfount139", "aura_sakura139", "f_gbed139", "f_gstatue139" };
    public static final long MIN_START = 1_000_000L, MAX_BID = 5_000_000_000L, STEP = 10_000L;
    static final int MAX_LAST = 6, MAX_PAY = 20;
    static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final SharedWorldService world;
    private final ObjectMapper om;
    private final ObjectProvider<GameSocketHandler> hubs;
    private final ObjectProvider<VillageService> villages;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public AuctionService(SharedWorldService world, ObjectMapper om, ObjectProvider<GameSocketHandler> hubs, ObjectProvider<VillageService> villages) {
        this.world = world;
        this.om = om;
        this.hubs = hubs;
        this.villages = villages;
    }

    @Override
    public String type() {
        return "auc";
    }

    /** now のあとの、次の金曜 21:00（日本時間）。6時間より近いときは、その次の週 */
    public static long nextEnd(long now) {
        ZonedDateTime t = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), JST);
        ZonedDateTime fri = t.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)).withHour(21).withMinute(0).withSecond(0).withNano(0);
        if (fri.toInstant().toEpochMilli() - now < 6 * 3600_000L) fri = fri.plusWeeks(1);
        return fri.toInstant().toEpochMilli();
    }

    /** つぎに入れられる、いちばん低い額 */
    static long minNext(JsonNode cur) {
        long top = cur.path("top").path("g").asLong(0);
        if (top <= 0) return MIN_START;
        long n = (long) Math.ceil(top * 1.05 / STEP) * STEP;
        return Math.max(n, top + STEP);
    }

    private ObjectNode load() {
        JsonNode cur = world.getSystem(KEY);
        ObjectNode d = cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
        if (!d.has("seq")) d.put("seq", 0);
        if (!(d.get("last") instanceof ArrayNode)) d.putArray("last");
        if (!(d.get("pend") instanceof ObjectNode)) d.putObject("pend");
        return d;
    }

    private void save(ObjectNode d) {
        world.putSystem(KEY, d);
    }

    private ObjectNode out() {
        return om.createObjectNode().put("type", "auc");
    }

    /** いまの品がなければ出す（item＝持ちこす品。null なら次の品） */
    private void open(ObjectNode d, String item, long now) {
        int seq = d.path("seq").asInt(0) + 1;
        d.put("seq", seq);
        ObjectNode c = d.putObject("cur");
        c.put("id", seq).put("item", item != null ? item : ITEMS[Math.floorMod(d.path("n").asInt(0), ITEMS.length)]).put("start", now).put("end", nextEnd(now)).put("k", 0);
        if (item == null) d.put("n", d.path("n").asInt(0) + 1);
    }

    /** 画面に見せる形 */
    private ObjectNode st(ObjectNode d) {
        ObjectNode o = om.createObjectNode();
        o.put("now", System.currentTimeMillis());
        JsonNode c = d.path("cur");
        if (c.isObject()) {
            ObjectNode v = o.putObject("cur");
            v.put("id", c.path("id").asInt()).put("item", c.path("item").asText()).put("end", c.path("end").asLong()).put("k", c.path("k").asInt()).put("min", minNext(c));
            if (c.path("top").isObject()) v.putObject("top").put("n", c.path("top").path("n").asText("")).put("g", c.path("top").path("g").asLong()).put("u", c.path("top").path("u").asLong());
        }
        o.set("last", d.get("last").deepCopy());
        return o;
    }

    /** 渡すもの（返金・落札）を、受けとったと言われるまで取っておく。つないでいれば、すぐにも送る */
    private void pay(ObjectNode d, GameSocketHandler hub, long to, String why, long g, String item, int id) {
        ObjectNode pend = (ObjectNode) d.get("pend");
        ArrayNode arr = pend.path(String.valueOf(to)) instanceof ArrayNode x ? x : pend.putArray(String.valueOf(to));
        int seq = d.path("pseq").asInt(0) + 1;
        d.put("pseq", seq);
        ObjectNode p = arr.addObject().put("pid", "a" + seq).put("why", why).put("g", g).put("item", item).put("id", id);
        while (arr.size() > MAX_PAY) arr.remove(0);
        if (hub != null && hub.onlineHere(to)) { ObjectNode m = out().put("ev", "pay"); m.putArray("pay").add(p.deepCopy()); hub.sendToPlayer(to, m); }
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        String a = msg.path("a").asText("");
        String rid = msg.path("rid").asText("");
        if (rid.length() > 16) rid = rid.substring(0, 16);
        long me = from.playerId();
        switch (a) {
            case "look" -> { synchronized (this) { hub.send(from, out().set("st", st(load()))); } }
            case "ack" -> ack(me, msg.path("pids"));
            case "bid" -> {
                long now = System.currentTimeMillis();
                Long prev = last.put(me, now);
                if (prev != null && now - prev < 500) { hub.send(from, out().put("ng", "少し待ってからもう一度").put("rid", rid)); return; }
                bid(from, msg.path("g").asLong(0), rid, hub);
            }
            default -> { }
        }
    }

    private synchronized void ack(long me, JsonNode pids) {
        if (!pids.isArray() || pids.isEmpty()) return;
        ObjectNode d = load();
        JsonNode p = d.get("pend").path(String.valueOf(me));
        if (!(p instanceof ArrayNode arr)) return;
        java.util.Set<String> got = new java.util.HashSet<>();
        for (JsonNode x : pids) if (got.size() < 40) got.add(x.asText(""));
        for (int i = arr.size() - 1; i >= 0; i--) if (got.contains(arr.get(i).path("pid").asText())) arr.remove(i);
        if (arr.isEmpty()) ((ObjectNode) d.get("pend")).remove(String.valueOf(me));
        save(d);
    }

    private synchronized void bid(PlayerSession from, long g, String rid, GameSocketHandler hub) {
        ObjectNode d = load();
        long now = System.currentTimeMillis(), me = from.playerId();
        if (!(d.get("cur") instanceof ObjectNode c) || now >= c.path("end").asLong()) { hub.send(from, out().put("ng", "いまは入札できないよ（しめきったところ）").put("rid", rid)); return; }
        long min = minNext(c);
        if (g < min || g > MAX_BID || g % STEP != 0) {
            hub.send(from, out().put("ng", g > MAX_BID ? "入札は 50億G までだよ" : g % STEP != 0 ? "入札は 1万G きざみでね" : "いまは " + String.format("%,d", min) + "G から入れられるよ").put("rid", rid).set("st", st(d)));
            return;
        }
        long back = 0;
        JsonNode top = c.path("top");
        if (top.isObject()) {
            long prevU = top.path("u").asLong(), prevG = top.path("g").asLong();
            if (prevU == me) back = prevG;                        // 自分で上げた：前の分は、画面が差し引く
            else pay(d, hub, prevU, "refund", prevG, c.path("item").asText(), c.path("id").asInt());   // 上回られた人に、全額返す
        }
        c.putObject("top").put("u", me).put("n", from.name() == null ? "" : from.name()).put("g", g);
        c.put("k", c.path("k").asInt(0) + 1);
        if (c.path("end").asLong() - now < 60_000) c.put("end", now + 60_000);
        save(d);
        hub.send(from, out().put("ok", "bid").put("rid", rid).put("g", g).put("back", back).set("st", st(d)));
        hub.broadcast(out().put("ev", "bid").put("n", from.name() == null ? "" : from.name()).put("g", g).set("st", st(d)), from);
    }

    /** 1秒ごと：品がなければ出す・しめきりが来たら落札 */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        GameSocketHandler hub = hubs.getIfAvailable();
        if (hub == null) return;
        VillageService vs = villages.getIfAvailable();
        for (long v : vs == null ? List.of(Villages.MAIN) : vs.ids()) Villages.run(v, () -> tickHere(hub));
    }

    private synchronized void tickHere(GameSocketHandler hub) {
        JsonNode raw = world.getSystem(KEY);
        long now = System.currentTimeMillis();
        if (raw instanceof ObjectNode && raw.path("cur").isObject() && now < raw.path("cur").path("end").asLong()) return;
        ObjectNode d = load();
        if (!(d.get("cur") instanceof ObjectNode c)) { open(d, null, now); save(d); return; }
        String item = c.path("item").asText();
        JsonNode top = c.path("top");
        if (top.isObject()) {
            long u = top.path("u").asLong(), g = top.path("g").asLong();
            pay(d, hub, u, "win", g, item, c.path("id").asInt());
            ArrayNode lastArr = (ArrayNode) d.get("last");
            lastArr.insertObject(0).put("id", c.path("id").asInt()).put("item", item).put("n", top.path("n").asText("")).put("g", g).put("at", now);
            while (lastArr.size() > MAX_LAST) lastArr.remove(lastArr.size() - 1);
            open(d, null, now);
            save(d);
            hub.broadcast(out().put("ev", "end").put("item", item).put("n", top.path("n").asText("")).put("g", g).set("st", st(d)), null);
        } else {
            open(d, item, now);   // だれも入れなかった：同じ品を次の週へ
            save(d);
        }
    }

    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        if (session.playerId() == null || session.playerId() <= 0 || session.watcher()) return;
        JsonNode cur = world.getSystem(KEY);
        if (!(cur instanceof ObjectNode)) return;
        JsonNode p = cur.path("pend").path(String.valueOf(session.playerId()));
        if (p.isArray() && !p.isEmpty()) welcome.putObject("auc").set("pay", p.deepCopy());
    }

    /** テスト・管理用：いまの品を、いますぐしめきる */
    public synchronized void endNow() {
        ObjectNode d = load();
        if (d.get("cur") instanceof ObjectNode c) { c.put("end", System.currentTimeMillis() - 1); save(d); }
    }
}
