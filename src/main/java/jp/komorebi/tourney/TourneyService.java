package jp.komorebi.tourney;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.clock.ClockService;
import jp.komorebi.village.VillageService;
import jp.komorebi.village.Villages;
import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * 村の大会（v0.73.0）。村の人（賞金を出す）か管理人（賞金は村から）が開く。村ごとに共有の村の "_tourney"。
 * <ul>
 *   <li>パズル大会（kind "puzzle"）：スマホのゲーム（game＝blk・tsu・mrg・jmp・puyo）の、時間内のいちばんいいスコアをきそう。賞金は 1着60%・2着25%・3着15%</li>
 *   <li>釣り大会（kind "fish"）：えらんだ魚（またはランダム）を、いちばん先に釣った人が賞金をぜんぶ。時間内にだれも釣れなければ、賞金は開いた人にもどる</li>
 * </ul>
 * {type:"tny", a:"look"} → {type:"tny", st:{cur, last}} ／ a:"host"（kind, game|fish, rnd, mins, purse, title, rid）→ 本人に {ok:"host", rid, purse}（お金は画面がこの返事のあとで引く）、村に {ev:"new", t}
 * ／ a:"join"（id）→ {ok:"join", id} ／ a:"score"（id, s）→ {ok:"score", id, best} ／ a:"catch"（id, f）→ 当たりなら大会がおわる ／ a:"cancel"（id・開いた人・まだだれも参加していないとき）。
 * おわったら村に {ev:"end", t}、賞金は {ev:"pay", id, n, why:"win"|"refund", pos, title}（いない人は次の welcome の tny.pay）。
 */
@Service
public class TourneyService implements WelcomeContributor {

    static final String KEY = "_tourney";
    public static final Map<String, String> GAMES = new LinkedHashMap<>();
    static {
        GAMES.put("blk", "こもれびブロック");
        GAMES.put("tsu", "こもれびつなぎ");
        GAMES.put("mrg", "マージパズル");
        GAMES.put("jmp", "にわとりジャンプ");
        GAMES.put("puyo", "こもれびドロップ");
    }
    /** ランダムの釣り大会に出す魚（季節のほかに、時間・天気・えさ・借金などの条件がない魚だけ）。季節は 春0・夏1・秋2・冬3 */
    static final String[] FISH_ANY = { "フナ", "アジ", "マグロ", "タコ" };
    static final String[][] FISH_SEASON = {
        { "イワナ", "タイ", "ヤマメ", "ニジマス", "ヒラメ" },
        { "ブラックバス", "タイ", "ネッタイギョ", "カツオ", "ヤマメ", "ザリガニ", "キス" },
        { "イワナ", "サケ", "カツオ", "サバ" },
        { "ワカサギ", "ブリ", "ニジマス", "ヒラメ", "ハタハタ" } };
    public static final int[] PUZZLE_MINS = { 10, 30, 60, 180 }, FISH_MINS = { 10, 30, 60 };
    public static final long PURSE_MIN = 10_000, PURSE_MAX = 100_000_000, ADMIN_MAX = 10_000_000;
    static final double[] SPLIT = { 0.60, 0.25, 0.15 };
    static final int MAX_CUR = 3, MAX_LAST = 6, MAX_ENT = 60, MAX_PAY = 20;
    static final Pattern FISH = Pattern.compile("[^\\p{Cntrl}<>&\"'\\\\\\s]{1,16}");
    static final Pattern TITLE = Pattern.compile("[^\\p{Cntrl}<>&\"'\\\\]{1,16}");

    private final SharedWorldService world;
    private final ObjectMapper om;
    private final ObjectProvider<GameSocketHandler> hubs;
    private final ObjectProvider<VillageService> villages;
    private final ClockService clock;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();
    private final Random rnd = new Random();

    public TourneyService(SharedWorldService world, ObjectMapper om, ObjectProvider<GameSocketHandler> hubs, ObjectProvider<VillageService> villages, ClockService clock) {
        this.world = world;
        this.om = om;
        this.hubs = hubs;
        this.villages = villages;
        this.clock = clock;
    }

    private ObjectNode load() {
        JsonNode cur = world.getSystem(KEY);
        ObjectNode d = cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
        if (!d.path("cur").isArray()) d.putArray("cur");
        if (!d.path("last").isArray()) d.putArray("last");
        if (!(d.path("pend") instanceof ObjectNode)) d.putObject("pend");
        if (!d.has("seq")) d.put("seq", 0);
        return d;
    }

    private void save(ObjectNode d) { world.putSystem(KEY, d); }

    private ObjectNode out() { return om.createObjectNode().put("type", "tny"); }

    /** ランダムの魚を、いまの季節から1つ */
    String randomFish() {
        int s = Math.max(0, clock.day() - 1) / 7 % 4;
        List<String> pool = new ArrayList<>(List.of(FISH_ANY));
        pool.addAll(List.of(FISH_SEASON[s]));
        return pool.get(rnd.nextInt(pool.size()));
    }

    /** 画面に見せる形（参加した人のスコアは上から20人まで） */
    ObjectNode view(JsonNode t, long me) {
        ObjectNode v = om.createObjectNode();
        for (String k : new String[] { "id", "kind", "game", "fish", "rnd", "title", "host", "hostName", "purse", "start", "end", "done" }) if (t.has(k)) v.set(k, t.get(k).deepCopy());
        if ("puzzle".equals(t.path("kind").asText())) v.put("gameName", GAMES.getOrDefault(t.path("game").asText(), ""));
        List<Map.Entry<String, JsonNode>> es = new ArrayList<>();
        t.path("ent").fields().forEachRemaining(es::add);
        es.sort((a, b) -> { int c = Long.compare(b.getValue().path("s").asLong(), a.getValue().path("s").asLong()); return c != 0 ? c : Long.compare(a.getValue().path("at").asLong(), b.getValue().path("at").asLong()); });
        v.put("n", es.size());
        ArrayNode top = v.putArray("top");
        for (int i = 0; i < Math.min(20, es.size()); i++) {
            ObjectNode r = top.addObject().put("n", es.get(i).getValue().path("n").asText()).put("s", es.get(i).getValue().path("s").asLong());
            if (es.get(i).getKey().equals(String.valueOf(me))) r.put("me", true);
        }
        JsonNode mine = t.path("ent").path(String.valueOf(me));
        if (mine.isObject()) v.put("in", true).put("best", mine.path("s").asLong());
        if (t.has("win")) v.set("win", t.get("win").deepCopy());
        if (t.has("pay")) v.set("pay", t.get("pay").deepCopy());
        return v;
    }

    public ObjectNode state(long me) {
        ObjectNode d = load(), st = om.createObjectNode();
        ArrayNode cur = st.putArray("cur"), lst = st.putArray("last");
        for (JsonNode t : d.get("cur")) cur.add(view(t, me));
        for (JsonNode t : d.get("last")) lst.add(view(t, me));
        st.put("now", System.currentTimeMillis());
        ObjectNode o = out();
        o.set("st", st);
        return o;
    }

    private boolean throttled(long me) {
        long now = System.currentTimeMillis();
        Long prev = last.put(me, now);
        return prev != null && now - prev < 300;
    }

    private void ng(PlayerSession s, GameSocketHandler hub, String text, String rid) {
        hub.send(s, out().put("ng", text).put("rid", rid));
    }

    /** 大会を開く。host＝0 は管理人（お金は村から）。だめなら理由を返す */
    synchronized String open(long host, String hostName, String kind, String game, String fish, boolean random, int mins, long purse, String title) {
        ObjectNode d = load();
        ArrayNode cur = (ArrayNode) d.get("cur");
        if (cur.size() >= MAX_CUR) return "いっぺんに開ける大会は " + MAX_CUR + " つまで。いまの大会がおわってからね";
        if (host > 0) for (JsonNode t : cur) if (t.path("host").asLong() == host) return "開いている大会がおわってから、次を開けるよ";
        boolean puzzle = "puzzle".equals(kind);
        if (!puzzle && !"fish".equals(kind)) return "大会の種類がわからなかった";
        int[] allowed = puzzle ? PUZZLE_MINS : FISH_MINS;
        boolean okMin = false;
        for (int m : allowed) if (m == mins) okMin = true;
        if (!okMin) return "時間の長さがおかしいよ";
        if (puzzle && !GAMES.containsKey(game)) return "そのゲームでは大会を開けないよ";
        if (!puzzle && random) fish = randomFish();
        if (!puzzle && (fish == null || !FISH.matcher(fish).matches())) return "魚の名前がおかしいよ";
        long max = host > 0 ? PURSE_MAX : ADMIN_MAX;
        if (purse < PURSE_MIN || purse > max || purse % 1000 != 0) return "賞金は " + PURSE_MIN + "〜" + max + "G（1,000Gきざみ）だよ";
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) t = puzzle ? GAMES.get(game) + "大会" : (random ? "なぞの魚" : fish) + "釣り大会";
        if (!TITLE.matcher(t).matches()) return "大会の名前は1〜16文字で、記号の < > & などは使えないよ";
        long now = System.currentTimeMillis();
        int id = d.path("seq").asInt(0) + 1;
        d.put("seq", id);
        ObjectNode n = cur.addObject().put("id", id).put("kind", kind).put("title", t).put("host", host).put("hostName", host > 0 ? (hostName == null ? "" : hostName) : "管理人")
            .put("purse", purse).put("start", now).put("end", now + mins * 60_000L);
        if (puzzle) n.put("game", game); else n.put("fish", fish).put("rnd", random);
        n.putObject("ent");
        save(d);
        GameSocketHandler hub = hubs.getIfAvailable();
        if (hub != null) hub.broadcastTo(Villages.current(), (ObjectNode) out().put("ev", "new").set("t", view(n, 0)), null);
        return null;
    }

    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        long me = from.playerId();
        String a = msg.path("a").asText(""), rid = msg.path("rid").asText("");
        if (rid.length() > 16) rid = rid.substring(0, 16);
        if ("look".equals(a)) { hub.send(from, state(me)); return; }
        if (throttled(me)) { ng(from, hub, "少し待ってからもう一度", rid); return; }
        switch (a) {
            case "host" -> {
                String err = open(me, from.name(), msg.path("kind").asText(""), msg.path("game").asText(""), msg.path("fish").asText(""), msg.path("rnd").asBoolean(false),
                    msg.path("mins").asInt(0), msg.path("purse").asLong(0), msg.path("title").asText(""));
                if (err != null) ng(from, hub, err, rid);
                else hub.send(from, out().put("ok", "host").put("rid", rid).put("purse", msg.path("purse").asLong(0)));
            }
            case "join" -> join(from, msg.path("id").asInt(0), rid, hub);
            case "score" -> score(from, msg.path("id").asInt(0), msg.path("s").asLong(0), rid, hub);
            case "catch" -> caught(from, msg.path("f").asText(""), hub);
            case "cancel" -> cancel(from, msg.path("id").asInt(0), rid, hub);
            default -> { }
        }
    }

    private ObjectNode find(ObjectNode d, int id) {
        for (JsonNode t : d.get("cur")) if (t.path("id").asInt() == id) return (ObjectNode) t;
        return null;
    }

    private synchronized void join(PlayerSession from, int id, String rid, GameSocketHandler hub) {
        ObjectNode d = load(), t = find(d, id);
        if (t == null) { ng(from, hub, "その大会は、もうおわったみたい", rid); return; }
        ObjectNode ent = (ObjectNode) t.get("ent");
        String k = String.valueOf(from.playerId());
        if (!ent.has(k)) {
            if (ent.size() >= MAX_ENT) { ng(from, hub, "参加は " + MAX_ENT + " 人まで。もういっぱいだよ", rid); return; }
            ent.putObject(k).put("n", from.name() == null ? "" : from.name()).put("s", 0).put("at", System.currentTimeMillis());
            save(d);
        }
        hub.send(from, (ObjectNode) out().put("ok", "join").put("id", id).put("rid", rid).set("t", view(t, from.playerId())));
    }

    private synchronized void score(PlayerSession from, int id, long s, String rid, GameSocketHandler hub) {
        ObjectNode d = load(), t = find(d, id);
        if (t == null || !"puzzle".equals(t.path("kind").asText())) return;
        if (System.currentTimeMillis() > t.path("end").asLong()) return;
        JsonNode e = t.path("ent").path(String.valueOf(from.playerId()));
        if (!(e instanceof ObjectNode me)) return;
        s = Math.max(0, Math.min(99_999_999L, s));
        if (s > me.path("s").asLong()) { me.put("s", s).put("at", System.currentTimeMillis()); save(d); }
        hub.send(from, out().put("ok", "score").put("id", id).put("best", me.path("s").asLong()).put("rid", rid));
    }

    /** 釣った魚の名前。参加している釣り大会の魚なら、その人の勝ち */
    private synchronized void caught(PlayerSession from, String f, GameSocketHandler hub) {
        ObjectNode d = load();
        long now = System.currentTimeMillis();
        for (JsonNode t0 : d.get("cur")) {
            ObjectNode t = (ObjectNode) t0;
            if (!"fish".equals(t.path("kind").asText()) || t.has("win") || now > t.path("end").asLong()) continue;
            if (!t.path("ent").has(String.valueOf(from.playerId())) || !t.path("fish").asText().equals(f)) continue;
            t.putObject("win").put("id", from.playerId()).put("n", from.name() == null ? "" : from.name()).put("at", now);
            t.put("end", now);
        }
        tickWith(d, hub, now);
    }

    private synchronized void cancel(PlayerSession from, int id, String rid, GameSocketHandler hub) {
        ObjectNode d = load(), t = find(d, id);
        if (t == null || t.path("host").asLong() != from.playerId()) { ng(from, hub, "とりやめられる大会はないよ", rid); return; }
        for (Iterator<String> it = t.path("ent").fieldNames(); it.hasNext(); ) if (!it.next().equals(String.valueOf(from.playerId()))) { ng(from, hub, "もう参加している人がいるので、とりやめられないよ", rid); return; }
        t.put("cancel", true).put("end", 0);
        tickWith(d, hub, System.currentTimeMillis());
        hub.send(from, out().put("ok", "cancel").put("id", id).put("rid", rid));
    }

    /** 管理人室から大会をおわらせる（賞金は今の順位で払う） */
    public synchronized String stopNow(int id) {
        ObjectNode d = load(), t = find(d, id);
        if (t == null) return "その大会はありません";
        t.put("end", 0);
        GameSocketHandler hub = hubs.getIfAvailable();
        if (hub != null) tickWith(d, hub, System.currentTimeMillis()); else save(d);
        return null;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        GameSocketHandler hub = hubs.getIfAvailable();
        if (hub == null) return;
        VillageService vs = villages.getIfAvailable();
        for (long v : vs == null ? List.of(Villages.MAIN) : vs.ids()) Villages.run(v, () -> {
            JsonNode cur = world.getSystem(KEY);
            if (!(cur instanceof ObjectNode) || !cur.path("cur").isArray() || cur.path("cur").isEmpty()) return;
            long now = System.currentTimeMillis();
            boolean due = false;
            for (JsonNode t : cur.path("cur")) if (now >= t.path("end").asLong()) due = true;
            if (due) synchronized (this) { tickWith(load(), hub, now); }
        });
    }

    /** 時間の来た大会をおわらせて、賞金を払う（d を保存する） */
    private void tickWith(ObjectNode d, GameSocketHandler hub, long now) {
        ArrayNode cur = (ArrayNode) d.get("cur"), lst = (ArrayNode) d.get("last");
        boolean changed = false;
        for (Iterator<JsonNode> it = cur.elements(); it.hasNext(); ) {
            ObjectNode t = (ObjectNode) it.next();
            if (now < t.path("end").asLong()) continue;
            it.remove();
            changed = true;
            t.put("done", true);
            long purse = t.path("purse").asLong(), host = t.path("host").asLong();
            String title = t.path("title").asText();
            ArrayNode pay = t.putArray("pay");
            long back = 0;
            if (t.path("cancel").asBoolean(false)) back = purse;
            else if ("fish".equals(t.path("kind").asText())) {
                if (t.has("win")) { long w = t.path("win").path("id").asLong(); pay.addObject().put("pos", 1).put("n", t.path("win").path("n").asText()).put("g", purse); payTo(d, hub, w, t, purse, "win", 1); }
                else back = purse;
            } else {
                List<Map.Entry<String, JsonNode>> es = new ArrayList<>();
                t.path("ent").fields().forEachRemaining(es::add);
                es.removeIf(e -> e.getValue().path("s").asLong() <= 0);
                es.sort((a, b) -> { int c = Long.compare(b.getValue().path("s").asLong(), a.getValue().path("s").asLong()); return c != 0 ? c : Long.compare(a.getValue().path("at").asLong(), b.getValue().path("at").asLong()); });
                long given = 0;
                for (int pos = 0; pos < Math.min(SPLIT.length, es.size()); pos++) {
                    long g = Math.round(purse * SPLIT[pos]);
                    given += g;
                    long who = Long.parseLong(es.get(pos).getKey());
                    pay.addObject().put("pos", pos + 1).put("n", es.get(pos).getValue().path("n").asText()).put("g", g).put("s", es.get(pos).getValue().path("s").asLong());
                    payTo(d, hub, who, t, g, "win", pos + 1);
                }
                back = purse - given;
            }
            if (back > 0 && host > 0) payTo(d, hub, host, t, back, "refund", 0);
            t.put("back", back);
            ObjectNode v = view(t, 0);
            hub.broadcastTo(Villages.current(), (ObjectNode) out().put("ev", "end").set("t", v), null);
            ((ObjectNode) t).remove("ent");
            lst.insert(0, t);
            while (lst.size() > MAX_LAST) lst.remove(lst.size() - 1);
        }
        if (changed) save(d);
    }

    private void payTo(ObjectNode d, GameSocketHandler hub, long to, JsonNode t, long n, String why, int pos) {
        ObjectNode m = out().put("ev", "pay").put("id", t.path("id").asInt()).put("n", n).put("why", why).put("pos", pos).put("title", t.path("title").asText());
        if (hub.onlineHere(to)) { hub.sendToPlayer(to, m); return; }
        ObjectNode pend = (ObjectNode) d.get("pend");
        ArrayNode arr = pend.path(String.valueOf(to)) instanceof ArrayNode x ? x : pend.putArray(String.valueOf(to));
        m.remove("type");
        arr.add(m);
        while (arr.size() > MAX_PAY) arr.remove(0);
    }

    /** 管理人室用：いまの大会と前の大会 */
    public synchronized ObjectNode summary() {
        ObjectNode d = load(), s = om.createObjectNode();
        s.set("cur", d.get("cur").deepCopy());
        s.set("last", d.get("last").deepCopy());
        return s;
    }

    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        if (session.playerId() == null || session.playerId() <= 0 || session.watcher()) return;
        JsonNode cur = world.getSystem(KEY);
        if (!(cur instanceof ObjectNode)) return;
        ObjectNode d = load(), w = welcome.objectNode();
        long me = session.playerId();
        ArrayNode c = w.putArray("cur");
        for (JsonNode t : d.get("cur")) c.add(view(t, me));
        JsonNode p = d.path("pend").path(String.valueOf(me));
        if (p.isArray() && !p.isEmpty()) { w.set("pay", p.deepCopy()); ((ObjectNode) d.get("pend")).remove(String.valueOf(me)); save(d); }
        if (!c.isEmpty() || w.has("pay")) welcome.set("tny", w);
    }

    static final Set<String> KINDS = Set.of("puzzle", "fish");
}
