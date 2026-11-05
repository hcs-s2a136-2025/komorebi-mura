package jp.komorebi.duel;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.village.Villages;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;
import jp.komorebi.world.SharedWorldService;

/**
 * こもれび闘鶏の果たし状（村ごと）。共有の村の "_duel" = {seq, ros:{ID:{n, list:[{k,n,ab,sk}]}}, box:[果たし状], res:{ID:[結果]}}。
 * <ul>
 *   <li>{"type":"duel","a":"put","list":[{k,n,ab:{spd,sta,pow,gut,wis},sk:[...]}]} … 自分のにわとりを見せる（20羽まで・返事なし）</li>
 *   <li>{"a":"list"} … 本人に {"type":"duel","a":"list","list":[{id,owner,chs:[{k,n,s,sk}]}]}（同じ村のほかの人）</li>
 *   <li>{"a":"send","to","tk","me":{k,n,ab,sk},"st"} … 果たし状を送る。本人に {"ok":"send","c"}、相手がいれば {"ev":"in","c"}（いなければ次の welcome の "duel.in"）。送れないときは {"ng":"…"}</li>
 *   <li>{"a":"cancel","id"} … 送った人が取り下げる／{"a":"no","id"} … 受けた人が断る。どちらも相手に {"ev":"gone","id"} だけ（断ったことは知らせない）</li>
 *   <li>{"a":"yes","id","me":{k,n,ab,sk},"st"} … 受ける。サーバーがレース（{@link DuelRace}）を決めて、ふたりに {"ev":"race","r"}。送った人がいなければ "res" に取っておいて次の welcome で渡す</li>
 * </ul>
 * 果たし状は1人5通まで送れて、1人10通まで受けとれる。3日（本物の時間）たつと消える。同じ相手には1通ずつ。
 */
@Component
public class DuelHandler implements MessageHandler, WelcomeContributor {

    static final String KEY = "_duel";
    static final int MAX_OUT = 5, MAX_IN = 10, MAX_RES = 5, MAX_CH = 20, CAP = 400, MAX_SK = 6;
    static final long LIFE = 3L * 24 * 3600 * 1000;
    static final Pattern KEYRE = Pattern.compile("[a-z0-9]{1,16}");
    static final Set<String> SKILLS = Set.of("senko", "nobi", "saku", "nebari", "ochi", "kaze", "oikomi", "kokoro");
    static final Set<String> STRATS = Set.of(DuelRace.STRATS);
    static final String[] AB = { "spd", "sta", "pow", "gut", "wis" };

    private final PlayerService players;
    private final SharedWorldService world;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public DuelHandler(PlayerService players, SharedWorldService world) {
        this.players = players;
        this.world = world;
    }

    @Override
    public String type() {
        return "duel";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        long me = from.playerId();
        String a = msg.path("a").asText("");
        if (!"list".equals(a) && !"put".equals(a)) {
            long now = System.currentTimeMillis();
            Long prev = last.put(me, now);
            if (prev != null && now - prev < 400) { ng(from, hub, "少し待ってからもう一度ためしてね"); return; }
        }
        synchronized (this) {
            switch (a) {
                case "put" -> put(from, msg, hub);
                case "list" -> list(from, hub);
                case "send" -> send(from, msg, hub);
                case "cancel", "no" -> drop(from, msg.path("id").asLong(0), "cancel".equals(a), hub);
                case "yes" -> yes(from, msg, hub);
                default -> { }
            }
        }
    }

    // ---- しまってある値 ----
    private ObjectNode load(GameSocketHandler hub) {
        JsonNode cur = world.getSystem(KEY);
        ObjectNode d = loadFrom(cur instanceof ObjectNode ? cur : hub.json().createObjectNode());
        if (!(d.path("ros") instanceof ObjectNode)) d.putObject("ros");
        return d;
    }

    private void save(ObjectNode d) {
        world.putSystem(KEY, d);
    }

    private static ObjectNode out(GameSocketHandler hub) {
        return hub.json().createObjectNode().put("type", "duel");
    }

    private static void ng(PlayerSession s, GameSocketHandler hub, String text) {
        hub.send(s, out(hub).put("ng", text));
    }

    static String name(String s) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}<>]", "").trim();
        return t.length() > 12 ? t.substring(0, 12) : t;
    }

    /** にわとり1羽：{k, n, ab:{5つ}, sk:[...]}。おかしければ null */
    static ObjectNode chicken(JsonNode c, GameSocketHandler hub) {
        String k = c.path("k").asText("");
        if (!KEYRE.matcher(k).matches()) return null;
        ObjectNode o = hub.json().createObjectNode().put("k", k).put("n", name(c.path("n").asText("にわとり")));
        if (o.path("n").asText().isEmpty()) o.put("n", "にわとり");
        ObjectNode ab = o.putObject("ab");
        for (String key : AB) ab.put(key, Math.max(0, Math.min(CAP, c.path("ab").path(key).asInt(0))));
        ArrayNode sk = o.putArray("sk");
        Set<String> seen = new HashSet<>();
        for (JsonNode s : c.path("sk")) { String t = s.asText(""); if (SKILLS.contains(t) && seen.add(t) && sk.size() < MAX_SK) sk.add(t); }
        return o;
    }

    private static int total(JsonNode c) {
        int s = 0;
        for (String key : AB) s += c.path("ab").path(key).asInt(0);
        return s;
    }

    private void put(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        ObjectNode d = load(hub);
        ArrayNode list = hub.json().createArrayNode();
        Set<String> keys = new HashSet<>();
        for (JsonNode c : msg.path("list")) {
            if (list.size() >= MAX_CH) break;
            ObjectNode o = chicken(c, hub);
            if (o != null && keys.add(o.path("k").asText())) list.add(o);
        }
        ObjectNode ros = (ObjectNode) d.get("ros");
        if (list.isEmpty()) ros.remove(String.valueOf(from.playerId()));
        else ros.putObject(String.valueOf(from.playerId())).put("n", name(from.name())).set("list", list);
        save(d);
    }

    private void list(PlayerSession from, GameSocketHandler hub) {
        ObjectNode d = load(hub);
        ArrayNode list = hub.json().createArrayNode();
        long here = Villages.current();
        for (Iterator<Map.Entry<String, JsonNode>> it = d.get("ros").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> en = it.next();
            long id;
            try { id = Long.parseLong(en.getKey()); } catch (NumberFormatException e) { continue; }
            if (id == from.playerId()) continue;
            Player p = players.find(id).orElse(null);
            if (p == null || p.getVillageId() != here) continue;
            ObjectNode row = list.addObject().put("id", id).put("owner", p.getName());
            ArrayNode chs = row.putArray("chs");
            for (JsonNode c : en.getValue().path("list")) chs.addObject().put("k", c.path("k").asText()).put("n", c.path("n").asText()).put("s", total(c)).set("sk", c.path("sk").deepCopy());
            if (list.size() >= 40) break;
        }
        ObjectNode o = out(hub).put("a", "list");
        o.set("list", list);
        hub.send(from, o);
    }

    private void send(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        long me = from.playerId(), to = msg.path("to").asLong(0);
        String tk = msg.path("tk").asText(""), st = msg.path("st").asText("");
        ObjectNode mine = chicken(msg.path("me"), hub);
        if (to <= 0 || to == me || mine == null || !STRATS.contains(st)) { ng(from, hub, "果たし状を書けなかった"); return; }
        Player p = players.find(to).orElse(null);
        if (p == null || p.getVillageId() != Villages.current()) { ng(from, hub, "その人には果たし状を送れないみたい"); return; }
        ObjectNode d = load(hub);
        JsonNode target = null;
        for (JsonNode c : d.path("ros").path(String.valueOf(to)).path("list")) if (tk.equals(c.path("k").asText())) target = c;
        if (target == null) { ng(from, hub, "そのにわとりは、もう牧場にいないみたい"); return; }
        int outN = 0, inN = 0;
        for (JsonNode c : d.get("box")) {
            if (c.path("f").asLong() == me) outN++;
            if (c.path("t").asLong() == to) inN++;
            if (c.path("f").asLong() == me && c.path("t").asLong() == to) { ng(from, hub, "その人には、もう果たし状を送ってあるよ"); return; }
        }
        if (outN >= MAX_OUT) { ng(from, hub, "送れる果たし状は" + MAX_OUT + "通まで。返事を待とう"); return; }
        if (inN >= MAX_IN) { ng(from, hub, "その人には果たし状がたまっているみたい。また今度"); return; }
        long id = d.path("seq").asLong(0) + 1;
        d.put("seq", id);
        mine.put("st", st);
        ObjectNode c = d.withArray("box").addObject().put("id", id).put("f", me).put("fn", name(from.name())).put("t", to).put("tn", p.getName()).put("at", System.currentTimeMillis());
        c.set("a", mine);
        c.putObject("b").put("k", tk).put("n", target.path("n").asText()).put("s", total(target));
        save(d);
        hub.send(from, (ObjectNode) out(hub).put("ok", "send").set("c", c.deepCopy()));
        if (hub.onlineHere(to)) hub.sendToPlayer(to, (ObjectNode) out(hub).put("ev", "in").set("c", c.deepCopy()));
    }

    private void drop(PlayerSession from, long id, boolean bySender, GameSocketHandler hub) {
        long me = from.playerId();
        ObjectNode d = load(hub);
        for (Iterator<JsonNode> it = d.withArray("box").elements(); it.hasNext(); ) {
            JsonNode c = it.next();
            if (c.path("id").asLong() != id || c.path(bySender ? "f" : "t").asLong() != me) continue;
            it.remove();
            save(d);
            long other = c.path(bySender ? "t" : "f").asLong();
            hub.send(from, out(hub).put("ok", bySender ? "cancel" : "no").put("id", id));
            if (hub.onlineHere(other)) hub.sendToPlayer(other, out(hub).put("ev", "gone").put("id", id));
            return;
        }
        hub.send(from, out(hub).put("ok", bySender ? "cancel" : "no").put("id", id));
    }

    private void yes(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        long me = from.playerId(), id = msg.path("id").asLong(0);
        String st = msg.path("st").asText("");
        ObjectNode mine = chicken(msg.path("me"), hub);
        ObjectNode d = load(hub);
        JsonNode c = null;
        for (Iterator<JsonNode> it = d.withArray("box").elements(); it.hasNext(); ) {
            JsonNode x = it.next();
            if (x.path("id").asLong() == id && x.path("t").asLong() == me) { c = x; it.remove(); break; }
        }
        if (c == null) { hub.send(from, out(hub).put("ev", "gone").put("id", id)); return; }
        if (mine == null || !STRATS.contains(st) || !mine.path("k").asText().equals(c.path("b").path("k").asText())) {
            ng(from, hub, "そのにわとりでは受けられないみたい");
            return;
        }
        mine.put("st", st);
        ObjectNode r = race(c, mine, id, hub);
        long f = c.path("f").asLong();
        if (!hub.onlineHere(f)) {
            ObjectNode res = (ObjectNode) d.get("res");
            ArrayNode arr = res.path(String.valueOf(f)) instanceof ArrayNode x ? x : res.putArray(String.valueOf(f));
            arr.add(r);
            while (arr.size() > MAX_RES) arr.remove(0);
        }
        save(d);
        ObjectNode m = (ObjectNode) out(hub).put("ev", "race").set("r", r);
        hub.send(from, m);
        if (hub.onlineHere(f)) hub.sendToPlayer(f, m);
    }

    static DuelRace.Runner runner(JsonNode c) {
        JsonNode ab = c.path("ab");
        Set<String> sk = new HashSet<>();
        for (JsonNode s : c.path("sk")) if (SKILLS.contains(s.asText())) sk.add(s.asText());
        return new DuelRace.Runner(ab.path("spd").asInt(0), ab.path("sta").asInt(0), ab.path("pow").asInt(0), ab.path("gut").asInt(0), ab.path("wis").asInt(0), sk, c.path("st").asText("saki"));
    }

    private ObjectNode race(JsonNode c, ObjectNode mine, long id, GameSocketHandler hub) {
        JsonNode a = c.path("a");
        long seed = id * 1_000_003L ^ System.nanoTime();
        ObjectNode r = DuelRace.run(seed, runner(a), runner(mine), hub.json());
        r.put("id", id).put("at", System.currentTimeMillis());
        ObjectNode ra = r.putObject("a").put("id", c.path("f").asLong()).put("o", c.path("fn").asText()).put("k", a.path("k").asText()).put("n", a.path("n").asText()).put("st", a.path("st").asText()).put("s", total(a));
        ra.set("sk", a.path("sk").deepCopy());
        ObjectNode rb = r.putObject("b").put("id", c.path("t").asLong()).put("o", c.path("tn").asText()).put("k", mine.path("k").asText()).put("n", mine.path("n").asText()).put("st", mine.path("st").asText()).put("s", total(mine));
        rb.set("sk", mine.path("sk").deepCopy());
        return r;
    }

    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        if (session.playerId() == null || session.playerId() <= 0 || session.watcher()) return;
        JsonNode cur = world.getSystem(KEY);
        if (!(cur instanceof ObjectNode)) return;
        long me = session.playerId();
        ObjectNode d = loadFrom(cur);
        ObjectNode w = welcome.objectNode();
        ArrayNode in = w.putArray("in"), outs = w.putArray("out");
        for (JsonNode c : d.get("box")) {
            if (c.path("t").asLong() == me) in.add(c.deepCopy());
            if (c.path("f").asLong() == me) outs.add(c.deepCopy());
        }
        JsonNode res = d.path("res").path(String.valueOf(me));
        if (res.isArray() && !res.isEmpty()) {
            w.set("res", res.deepCopy());
            ((ObjectNode) d.get("res")).remove(String.valueOf(me));
            save(d);
        }
        if (!in.isEmpty() || !outs.isEmpty() || w.has("res")) welcome.set("duel", w);
    }

    /** welcome のとき用（hub がないので、しまってある値から作る） */
    private static ObjectNode loadFrom(JsonNode cur) {
        ObjectNode d = ((ObjectNode) cur).deepCopy();
        if (!(d.path("box") instanceof ArrayNode)) d.putArray("box");
        if (!(d.path("res") instanceof ObjectNode)) d.putObject("res");
        long now = System.currentTimeMillis();
        for (Iterator<JsonNode> it = d.withArray("box").elements(); it.hasNext(); ) if (now - it.next().path("at").asLong(0) > LIFE) it.remove();
        return d;
    }
}
