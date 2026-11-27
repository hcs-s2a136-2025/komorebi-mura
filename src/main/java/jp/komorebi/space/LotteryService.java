package jp.komorebi.space;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
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
 * 村ジャンボ宝くじ（村ごと・v0.89.0）。1枚1,000億G・毎週日曜21:00（日本時間）に抽選。
 * 売り上げの3割が1等（1人）、1%ずつが2等（10本）。のこりの6割は消える（村のお金をへらすため）。
 * 1等は「1億枚」のうちの1本なので、売れた枚数が1億枚より少ない週は当たらないことがある。そのときは1等の分を次の週に持ちこす（carry）。
 * 共有の村の "_lottery" = {seq, pseq, carry:"G", cur:{id, draw, sold, tk:{人のID:{n,c}}}, last:[...6], pend:{人のID:[{pid,why,g,id}]}}。
 * <ul>
 *   <li>{"type":"lot","a":"look"} … 本人に {"type":"lot","st":{...}}</li>
 *   <li>{"type":"lot","a":"buy","n":枚数,"rid"} … 1回に1億枚まで・1週に1人10億枚まで。本人に {"ok":"buy","rid","n","st"}（<b>お金は画面がこの返事のあとで n×1,000億 引く</b>）</li>
 *   <li>{"type":"lot","a":"ack","pids":[...]} … 受けとった当せん金を消す（受けとったと言うまで、welcome の lot.pay で毎回渡す）</li>
 * </ul>
 */
@Service
public class LotteryService implements MessageHandler, WelcomeContributor {

    static final String KEY = "_lottery";
    public static final BigInteger PRICE = BigInteger.valueOf(100_000_000_000L);
    public static final long MAX_BUY = 100_000_000L, MAX_WEEK = 1_000_000_000L, ODDS = 100_000_000L;
    static final int WIN2 = 10, MAX_LAST = 6, MAX_PAY = 30;
    static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final SharedWorldService world;
    private final ObjectMapper om;
    private final ObjectProvider<GameSocketHandler> hubs;
    private final ObjectProvider<VillageService> villages;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();
    private final SecureRandom rnd = new SecureRandom();

    public LotteryService(SharedWorldService world, ObjectMapper om, ObjectProvider<GameSocketHandler> hubs, ObjectProvider<VillageService> villages) {
        this.world = world;
        this.om = om;
        this.hubs = hubs;
        this.villages = villages;
    }

    @Override
    public String type() {
        return "lot";
    }

    /** now のあとの、次の日曜 21:00（日本時間）。1時間より近いときは、その次の週 */
    public static long nextDraw(long now) {
        ZonedDateTime t = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), JST);
        ZonedDateTime sun = t.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).withHour(21).withMinute(0).withSecond(0).withNano(0);
        if (sun.toInstant().toEpochMilli() - now < 3600_000L) sun = sun.plusWeeks(1);
        return sun.toInstant().toEpochMilli();
    }

    static BigInteger big(JsonNode n) {
        String s = n == null ? "" : n.asText("");
        return s.matches("\\d{1,60}") ? new BigInteger(s) : BigInteger.ZERO;
    }

    private ObjectNode load() {
        JsonNode cur = world.getSystem(KEY);
        ObjectNode d = cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
        if (!d.has("seq")) d.put("seq", 0);
        if (!d.has("carry")) d.put("carry", "0");
        if (!(d.get("last") instanceof ArrayNode)) d.putArray("last");
        if (!(d.get("pend") instanceof ObjectNode)) d.putObject("pend");
        return d;
    }

    private ObjectNode out() {
        return om.createObjectNode().put("type", "lot");
    }

    private void open(ObjectNode d, long now) {
        int seq = d.path("seq").asInt(0) + 1;
        d.put("seq", seq);
        ObjectNode c = d.putObject("cur");
        c.put("id", seq).put("draw", nextDraw(now)).put("sold", 0L);
        c.putObject("tk");
    }

    /** 売り上げ（G） */
    static BigInteger sales(long sold) {
        return PRICE.multiply(BigInteger.valueOf(sold));
    }

    ObjectNode st(ObjectNode d, Long me) {
        ObjectNode o = om.createObjectNode();
        o.put("now", System.currentTimeMillis()).put("price", PRICE.toString()).put("odds", ODDS).put("maxBuy", MAX_BUY).put("maxWeek", MAX_WEEK);
        JsonNode c = d.path("cur");
        if (c.isObject()) {
            long sold = c.path("sold").asLong(0);
            BigInteger s = sales(sold);
            o.put("id", c.path("id").asInt()).put("draw", c.path("draw").asLong()).put("sold", sold).put("k", c.path("tk").size());
            o.put("pot1", s.multiply(BigInteger.valueOf(3)).divide(BigInteger.TEN).add(big(d.get("carry"))).toString());
            o.put("pot2", s.divide(BigInteger.valueOf(100)).toString());
            if (me != null) o.put("mine", c.path("tk").path(String.valueOf(me)).path("c").asLong(0));
        }
        o.put("carry", d.path("carry").asText("0"));
        o.set("last", d.get("last").deepCopy());
        return o;
    }

    private void pay(ObjectNode d, GameSocketHandler hub, long to, String why, BigInteger g, int id) {
        ObjectNode pend = (ObjectNode) d.get("pend");
        ArrayNode arr = pend.path(String.valueOf(to)) instanceof ArrayNode x ? x : pend.putArray(String.valueOf(to));
        int seq = d.path("pseq").asInt(0) + 1;
        d.put("pseq", seq);
        ObjectNode p = arr.addObject().put("pid", "l" + seq).put("why", why).put("g", g.toString()).put("id", id);
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
            case "look" -> { synchronized (this) { ObjectNode d = load(); if (!d.path("cur").isObject()) open(d, System.currentTimeMillis()); hub.send(from, out().set("st", st(d, me))); } }
            case "ack" -> ack(me, msg.path("pids"));
            case "buy" -> {
                long now = System.currentTimeMillis();
                Long prev = last.put(me, now);
                if (prev != null && now - prev < 400) { hub.send(from, out().put("ng", "少し待ってからもう一度").put("rid", rid)); return; }
                buy(from, msg.path("n").asLong(0), rid, hub);
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
        for (JsonNode x : pids) if (got.size() < 60) got.add(x.asText(""));
        for (int i = arr.size() - 1; i >= 0; i--) if (got.contains(arr.get(i).path("pid").asText())) arr.remove(i);
        if (arr.isEmpty()) ((ObjectNode) d.get("pend")).remove(String.valueOf(me));
        world.putSystem(KEY, d);
    }

    private synchronized void buy(PlayerSession from, long n, String rid, GameSocketHandler hub) {
        long me = from.playerId(), now = System.currentTimeMillis();
        ObjectNode d = load();
        if (!(d.get("cur") instanceof ObjectNode)) open(d, now);
        ObjectNode c = (ObjectNode) d.get("cur");
        if (now >= c.path("draw").asLong()) { hub.send(from, out().put("ng", "いまは抽選のところ。少しあとでね").put("rid", rid)); return; }
        if (n < 1 || n > MAX_BUY) { hub.send(from, out().put("ng", "1回に買えるのは1〜1億枚だよ").put("rid", rid)); return; }
        ObjectNode tk = (ObjectNode) c.get("tk");
        ObjectNode mine = tk.get(String.valueOf(me)) instanceof ObjectNode x ? x : null;
        long have = mine == null ? 0 : mine.path("c").asLong(0);
        if (have + n > MAX_WEEK) { hub.send(from, out().put("ng", "1週に買えるのは10億枚までだよ（あと " + String.format("%,d", Math.max(0, MAX_WEEK - have)) + " 枚）").put("rid", rid)); return; }
        if (mine == null && tk.size() >= 500) { hub.send(from, out().put("ng", "今週はもう売りきれ").put("rid", rid)); return; }
        if (mine == null) mine = tk.putObject(String.valueOf(me));
        mine.put("n", from.name() == null ? "" : from.name()).put("c", have + n);
        c.put("sold", c.path("sold").asLong(0) + n);
        world.putSystem(KEY, d);
        hub.send(from, out().put("ok", "buy").put("rid", rid).put("n", n).set("st", st(d, me)));
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        GameSocketHandler hub = hubs.getIfAvailable();
        if (hub == null) return;
        VillageService vs = villages.getIfAvailable();
        for (long v : vs == null ? List.of(Villages.MAIN) : vs.ids()) Villages.run(v, () -> tickHere(hub));
    }

    /** 枚数の重みで1人えらぶ */
    private Map.Entry<String, JsonNode> pickBy(List<Map.Entry<String, JsonNode>> list, long sold, long r) {
        long acc = 0;
        for (var e : list) { acc += e.getValue().path("c").asLong(0); if (r < acc) return e; }
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    private long randBelow(long n) {
        return n <= 1 ? 0 : (rnd.nextLong() & Long.MAX_VALUE) % n;
    }

    synchronized void tickHere(GameSocketHandler hub) {
        JsonNode raw = world.getSystem(KEY);
        long now = System.currentTimeMillis();
        if (raw instanceof ObjectNode && raw.path("cur").isObject() && now < raw.path("cur").path("draw").asLong()) return;
        if (!(raw instanceof ObjectNode)) return;   // まだだれも見ていない村は始めない（look で始まる）
        ObjectNode d = load();
        if (!(d.get("cur") instanceof ObjectNode c)) { open(d, now); world.putSystem(KEY, d); return; }
        draw(d, c, hub, now);
    }

    private void draw(ObjectNode d, ObjectNode c, GameSocketHandler hub, long now) {
        long sold = c.path("sold").asLong(0);
        int id = c.path("id").asInt();
        if (sold <= 0) { open(d, now); world.putSystem(KEY, d); return; }
        BigInteger s = sales(sold), carry = big(d.get("carry"));
        List<Map.Entry<String, JsonNode>> list = new ArrayList<>();
        c.path("tk").fields().forEachRemaining(list::add);
        ObjectNode res = om.createObjectNode().put("id", id).put("at", now).put("sold", sold).put("k", list.size());
        BigInteger p1 = s.multiply(BigInteger.valueOf(3)).divide(BigInteger.TEN).add(carry);
        if (sold > 0 && randBelow(Math.max(sold, ODDS)) < sold) {
            var w = pickBy(list, sold, randBelow(sold));
            if (w != null) {
                pay(d, hub, Long.parseLong(w.getKey()), "win1", p1, id);
                res.putObject("w1").put("n", w.getValue().path("n").asText("")).put("g", p1.toString());
                carry = BigInteger.ZERO;
            }
        } else {
            carry = p1;
            res.put("carryOut", p1.toString());
        }
        ArrayNode w2 = res.putArray("w2");
        if (sold > 0) {
            BigInteger g2 = s.divide(BigInteger.valueOf(100));
            for (int i = 0; i < WIN2; i++) {
                var w = pickBy(list, sold, randBelow(sold));
                if (w == null) break;
                pay(d, hub, Long.parseLong(w.getKey()), "win2", g2, id);
                w2.addObject().put("n", w.getValue().path("n").asText("")).put("g", g2.toString());
            }
        }
        d.put("carry", carry.toString());
        ArrayNode lastArr = (ArrayNode) d.get("last");
        lastArr.insert(0, res);
        while (lastArr.size() > MAX_LAST) lastArr.remove(lastArr.size() - 1);
        open(d, now);
        world.putSystem(KEY, d);
        if (sold > 0) hub.broadcast(out().put("ev", "draw").set("res", res.deepCopy()), null);
    }

    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        if (session.playerId() == null || session.playerId() <= 0 || session.watcher()) return;
        JsonNode cur = world.getSystem(KEY);
        if (!(cur instanceof ObjectNode)) return;
        JsonNode p = cur.path("pend").path(String.valueOf(session.playerId()));
        if (p.isArray() && !p.isEmpty()) welcome.putObject("lot").set("pay", p.deepCopy());
    }

    /** テスト・管理用：いまの回を、いますぐ抽選する */
    public synchronized void drawNow() {
        ObjectNode d = load();
        if (d.get("cur") instanceof ObjectNode c) { c.put("draw", System.currentTimeMillis() - 1); world.putSystem(KEY, d); }
    }
}
