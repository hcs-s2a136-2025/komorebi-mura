package jp.komorebi.raid;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * 討伐クエスト（v0.59.0）。管理人が「どの魔物を・どこに・いつ・どれくらいの強さで」出すかを決め、
 * 時間になると村に魔物が出る。村の人が攻撃した分（hit）をサーバーが足して、HPが0になったら討伐成功。
 * 時間内にたおせなければ、魔物は帰っていく。
 * たおしたあとは10分の「宴」：料理を持ち寄ると、次の討伐でみんなの攻撃が強くなる（1品+2%・50%まで）。
 * 状態は共有の村の "_raid"（村ごと）に持つ。魔物の攻撃の出し方は、討伐の id と時間からゲーム側で決める（みんな同じに見える）。
 */
@Service
public class RaidService implements WelcomeContributor {

    static final String KEY = "_raid";
    public static final Map<String, String> KINDS = new LinkedHashMap<>();
    public static final Map<String, String> PLACES = new LinkedHashMap<>();
    static {
        KINDS.put("boar", "大イノシシ ドドン");
        KINDS.put("bear", "森のヌシ グマ");
        KINDS.put("golem", "岩のゴーレム");
        KINDS.put("eagle", "雷の大ワシ");
        KINDS.put("octo", "沖の大ダコ");
        PLACES.put("hill", "北の丘");
        PLACES.put("port", "みなと町の沖");
    }
    static final Pattern FOOD = Pattern.compile("[a-z0-9_]{1,32}");
    static final long FEAST_MS = 10 * 60_000L;
    static final int MAX_HIT = 80, BUFF_PER_DISH = 2, BUFF_MAX = 50, DISH_PER_PERSON = 3;

    private final SharedWorldService world;
    private final ObjectMapper om;
    private final ObjectProvider<GameSocketHandler> hub;
    private final ObjectProvider<VillageService> villages;
    private final Map<String, Long> last = new ConcurrentHashMap<>();
    private final Map<Long, Long> hpSent = new ConcurrentHashMap<>();

    public RaidService(SharedWorldService world, ObjectMapper om, ObjectProvider<GameSocketHandler> hub, ObjectProvider<VillageService> villages) {
        this.world = world;
        this.om = om;
        this.hub = hub;
        this.villages = villages;
    }

    private ObjectNode data() {
        JsonNode cur = world.getSystem(KEY);
        ObjectNode d = cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
        if (!d.path("plans").isArray()) d.putArray("plans");
        if (!d.has("seq")) d.put("seq", 0);
        return d;
    }

    private void save(ObjectNode d) { world.putSystem(KEY, d); }

    private void toAll(ObjectNode msg) {
        GameSocketHandler h = hub.getIfAvailable();
        if (h != null) h.broadcastTo(Villages.current(), msg, null);
    }

    // ------------------------------------------------------------------ 管理人室

    public synchronized String plan(String kind, String place, Instant start, int mins, int hp, int reward) {
        if (!KINDS.containsKey(kind)) return "魔物をえらんでください";
        if (!PLACES.containsKey(place)) return "場所をえらんでください";
        if ("octo".equals(kind) && !"port".equals(place)) return "沖の大ダコは、みなと町の沖にしか出せません";
        if (!"octo".equals(kind) && "port".equals(place)) return "みなと町の沖に出せるのは、沖の大ダコだけです";
        if (mins < 3 || mins > 60) return "長さは3〜60分にしてください";
        if (hp < 500 || hp > 2_000_000) return "HPは500〜2,000,000にしてください";
        if (reward < 0 || reward > 100_000) return "ごほうびは0〜100,000Gにしてください";
        ObjectNode d = data();
        ArrayNode plans = (ArrayNode) d.get("plans");
        if (plans.size() >= 10) return "予定は10こまでです";
        int id = d.path("seq").asInt(0) + 1;
        d.put("seq", id);
        plans.addObject().put("id", id).put("kind", kind).put("place", place).put("start", start.toEpochMilli()).put("mins", mins).put("hp", hp).put("reward", reward);
        save(d);
        announcePlans(d);
        return null;
    }

    public synchronized String startNow(int id) {
        ObjectNode d = data();
        if (d.path("cur").isObject()) return "いま討伐の最中です";
        ArrayNode plans = (ArrayNode) d.get("plans");
        for (int i = 0; i < plans.size(); i++) if (plans.get(i).path("id").asInt() == id) { begin(d, (ObjectNode) plans.remove(i), System.currentTimeMillis()); return null; }
        return "その予定は見つかりませんでした";
    }

    public synchronized String cancel(int id) {
        ObjectNode d = data();
        ArrayNode plans = (ArrayNode) d.get("plans");
        for (int i = 0; i < plans.size(); i++) if (plans.get(i).path("id").asInt() == id) { plans.remove(i); save(d); announcePlans(d); return null; }
        return "その予定は見つかりませんでした";
    }

    public synchronized String stop() {
        ObjectNode d = data();
        if (!d.path("cur").isObject()) return "いまは討伐をしていません";
        end(d, false, System.currentTimeMillis(), true);
        return null;
    }

    /** 管理人室に出す：{plans, cur, last, buff, feast} */
    public synchronized ObjectNode summary() {
        ObjectNode d = data();
        ObjectNode o = om.createObjectNode();
        o.set("plans", d.get("plans"));
        if (d.path("cur").isObject()) o.set("cur", view(d.path("cur")));
        if (d.path("last").isObject()) o.set("last", d.get("last"));
        o.put("buff", d.path("buff").asInt(0));
        return o;
    }

    // ------------------------------------------------------------------ 進行

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        VillageService vs = villages.getIfAvailable();
        for (long v : vs == null ? List.of(Villages.MAIN) : vs.ids()) Villages.run(v, this::tickHere);
    }

    synchronized void tickHere() {
        JsonNode raw = world.getSystem(KEY);
        if (raw == null) return;
        long now = System.currentTimeMillis();
        ObjectNode d = data();
        if (d.path("cur").isObject()) {
            ObjectNode cur = (ObjectNode) d.get("cur");
            if (now >= cur.path("end").asLong()) end(d, false, now, false);
            else {
                Long sent = hpSent.get(Villages.current());
                if (cur.path("dirty").asBoolean(false) && (sent == null || now - sent > 500)) {
                    cur.remove("dirty"); save(d); hpSent.put(Villages.current(), now);
                    ObjectNode m = om.createObjectNode().put("type", "raid").put("ev", "hp");
                    m.put("id", cur.path("id").asInt()).put("hp", cur.path("hp").asLong()).put("max", cur.path("max").asLong());
                    m.set("top", top(cur, 3));
                    toAll(m);
                }
            }
            return;
        }
        ArrayNode plans = (ArrayNode) d.get("plans");
        for (int i = 0; i < plans.size(); i++) {
            if (plans.get(i).path("start").asLong() <= now) { begin(d, (ObjectNode) plans.remove(i), now); return; }
        }
    }

    private void begin(ObjectNode d, ObjectNode p, long now) {
        ObjectNode cur = d.putObject("cur");
        cur.put("id", p.path("id").asInt()).put("kind", p.path("kind").asText()).put("place", p.path("place").asText());
        cur.put("hp", p.path("hp").asLong()).put("max", p.path("hp").asLong()).put("reward", p.path("reward").asInt());
        cur.put("start", now).put("end", now + p.path("mins").asLong(10) * 60_000L);
        cur.put("buff", Math.min(BUFF_MAX, d.path("buff").asInt(0)));
        cur.putObject("dmg"); cur.putObject("names");
        d.put("buff", 0);
        d.remove("feast");
        save(d);
        ObjectNode m = om.createObjectNode().put("type", "raid").put("ev", "start");
        m.set("raid", view(cur));
        toAll(m);
    }

    private void end(ObjectNode d, boolean won, long now, boolean stopped) {
        ObjectNode cur = (ObjectNode) d.get("cur");
        d.remove("cur");
        ObjectNode last = d.putObject("last");
        last.put("id", cur.path("id").asInt()).put("kind", cur.path("kind").asText()).put("won", won).put("at", now);
        last.set("top", top(cur, 10));
        int people = cur.path("dmg").size();
        last.put("people", people);
        // ごほうび：参加した人みんなにもとの額。ダメージの割合で最大2倍まで上乗せ。1位はさらに+50%（前回の結果にも残すので、そのときいなかった人も次につないだときに受けとれる）
        long total = 0;
        for (JsonNode x : cur.path("dmg")) total += x.asLong();
        ObjectNode pay = last.putObject("pay");
        if (won && total > 0) {
            int base = cur.path("reward").asInt(0);
            String first = null; long best = -1;
            for (var it = cur.path("dmg").fields(); it.hasNext(); ) { var e = it.next(); if (e.getValue().asLong() > best) { best = e.getValue().asLong(); first = e.getKey(); } }
            for (var it = cur.path("dmg").fields(); it.hasNext(); ) {
                var e = it.next();
                double share = (double) e.getValue().asLong() / total;
                long g = Math.round(base * (1 + Math.min(1.0, share * people)) * (e.getKey().equals(first) ? 1.5 : 1));
                pay.put(e.getKey(), g);
            }
        }
        if (won) {
            ObjectNode feast = d.putObject("feast");
            feast.put("until", now + FEAST_MS).put("raid", cur.path("id").asInt());
            feast.putArray("dishes"); feast.putObject("by");
        }
        save(d);
        ObjectNode m = om.createObjectNode().put("type", "raid").put("ev", won ? "won" : "fail");
        m.put("id", cur.path("id").asInt()).put("kind", cur.path("kind").asText()).put("stopped", stopped);
        m.set("top", last.get("top"));
        m.put("people", people);
        m.set("pay", pay);
        if (won) m.set("feast", d.get("feast"));
        toAll(m);
    }

    // ------------------------------------------------------------------ プレイヤーから

    private boolean slow(String k, long ms) {
        long now = System.currentTimeMillis();
        Long prev = last.put(k, now);
        return prev != null && now - prev < ms;
    }

    public synchronized void hit(PlayerSession from, int dmg) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        if (slow("h" + from.playerId(), 250)) return;
        ObjectNode d = data();
        if (!d.path("cur").isObject()) return;
        ObjectNode cur = (ObjectNode) d.get("cur");
        long now = System.currentTimeMillis();
        if (now >= cur.path("end").asLong() || cur.path("hp").asLong() <= 0) return;
        int raw = Math.max(1, Math.min(MAX_HIT, dmg));
        long dd = Math.round(raw * (1 + cur.path("buff").asInt(0) / 100.0));
        long hp = Math.max(0, cur.path("hp").asLong() - dd);
        cur.put("hp", hp);
        String id = String.valueOf(from.playerId());
        ((ObjectNode) cur.get("dmg")).put(id, cur.path("dmg").path(id).asLong(0) + dd);
        ((ObjectNode) cur.get("names")).put(id, from.name() == null ? "" : from.name());
        cur.put("dirty", true);
        if (hp <= 0) { cur.remove("dirty"); end(d, true, now, false); return; }
        save(d);
    }

    public synchronized void dish(PlayerSession from, String food, String foodName) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        if (!FOOD.matcher(food == null ? "" : food).matches()) return;
        ObjectNode d = data();
        JsonNode f = d.path("feast");
        if (!f.isObject() || System.currentTimeMillis() > f.path("until").asLong()) return;
        ObjectNode feast = (ObjectNode) f;
        String id = String.valueOf(from.playerId());
        int mine = feast.path("by").path(id).asInt(0);
        if (mine >= DISH_PER_PERSON) return;
        ((ObjectNode) feast.get("by")).put(id, mine + 1);
        ArrayNode dishes = (ArrayNode) feast.get("dishes");
        if (dishes.size() < 60) dishes.addObject().put("id", food).put("n", foodName == null ? "" : foodName.length() > 16 ? foodName.substring(0, 16) : foodName).put("who", from.name() == null ? "" : from.name());
        d.put("buff", Math.min(BUFF_MAX, d.path("buff").asInt(0) + BUFF_PER_DISH));
        save(d);
        ObjectNode m = om.createObjectNode().put("type", "raid").put("ev", "feast");
        m.set("feast", feast);
        m.put("buff", d.path("buff").asInt(0));
        toAll(m);
    }

    public synchronized ObjectNode state() {
        ObjectNode d = data();
        ObjectNode m = om.createObjectNode().put("type", "raid").put("ev", "state");
        if (d.path("cur").isObject()) m.set("raid", view(d.get("cur")));
        // 予定は「いつ・何が・どこに」だけ（HPやごほうびは出さない）
        ArrayNode up = m.putArray("plans");
        List<JsonNode> ps = new ArrayList<>();
        d.path("plans").forEach(ps::add);
        ps.sort(Comparator.comparingLong(p -> p.path("start").asLong()));
        for (JsonNode p : ps) up.addObject().put("id", p.path("id").asInt()).put("kind", p.path("kind").asText()).put("place", p.path("place").asText()).put("start", p.path("start").asLong()).put("mins", p.path("mins").asInt());
        if (d.path("feast").isObject() && System.currentTimeMillis() < d.path("feast").path("until").asLong()) m.set("feast", d.get("feast"));
        m.put("buff", d.path("buff").asInt(0));
        if (d.path("last").isObject()) m.set("last", d.get("last"));
        m.put("now", System.currentTimeMillis());
        return m;
    }

    private ObjectNode view(JsonNode cur) {
        ObjectNode v = om.createObjectNode();
        v.put("id", cur.path("id").asInt()).put("kind", cur.path("kind").asText()).put("place", cur.path("place").asText());
        v.put("hp", cur.path("hp").asLong()).put("max", cur.path("max").asLong()).put("start", cur.path("start").asLong()).put("end", cur.path("end").asLong());
        v.put("buff", cur.path("buff").asInt(0)).put("now", System.currentTimeMillis());
        v.set("top", top(cur, 3));
        return v;
    }

    private ArrayNode top(JsonNode cur, int n) {
        List<Map.Entry<String, JsonNode>> l = new ArrayList<>();
        cur.path("dmg").fields().forEachRemaining(l::add);
        l.sort((a, b) -> Long.compare(b.getValue().asLong(), a.getValue().asLong()));
        ArrayNode a = om.createArrayNode();
        for (int i = 0; i < l.size() && i < n; i++) a.addObject().put("id", Long.parseLong(l.get(i).getKey())).put("name", cur.path("names").path(l.get(i).getKey()).asText("")).put("dmg", l.get(i).getValue().asLong());
        return a;
    }

    private void announcePlans(ObjectNode d) {
        toAll(state());
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        welcome.set("raid", state());
    }
}
