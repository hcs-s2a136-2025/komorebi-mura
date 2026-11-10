package jp.komorebi.duel;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.village.VillageService;
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
 * <p><b>賭け（v0.64.0）</b>：受けたとき、村にふたりのほかにつないでいる人がいれば、すぐ走らずに {@link #BET_MS} の「賭けの時間」（"live"）。
 * 村のみんなに {"ev":"open","m"}。{"a":"bet","id","side":0|1,"n":100〜5000} … 戦うふたりは賭けられない・1勝負に1回。本人に {"ok":"bet","id","side","n"}
 * （お金は画面がこの返事のあとで引く）、村のみんなに {"ev":"pool","id","pool":[A,B],"k"}。時間になったら 1秒ごとの {@link #tick()} がレースを決めて、
 * 村のみんなに {"ev":"race","r"}（r.pool・r.pt つき）。賞金は賭けられた合計の3割を勝った子の持ち主に、7割を当てた人で賭けた額に応じて分ける
 * （当てた人がいなければ7割はだれにも行かない・だれも賭けなければ賞金なし）。賭けた人には外れても {"ev":"pay","id","why":"bet","stake","n"}（n=0）を送る
 * （画面は gambleResult(stake, n) を通す）。持ち主には {"why":"owner"}。いない人の分は "pay" に取っておいて次の welcome で渡す。
 * <p><b>闘鶏ポイント</b>："pt" = {ID:{n, p:全期間, wk:週の月曜, wp:その週}}。勝つと +10＋（相手の総合力−自分）/20（0〜10）、負けると −4（0より下がらない）。
 * 同じふたりでポイントが動くのは日本時間の1日3回まで（"pd"）。{"a":"rank"} → {"a":"rank","all":[...],"week":[...],"me":{p,wp}}（この村）
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

    static final long BET_MS = 90_000L;
    static final int BET_MIN = 100, BET_MAX = 5000, MAX_LIVE = 6, MAX_PAY = 30, PAIR_DAY = 3;
    static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final PlayerService players;
    private final SharedWorldService world;
    private final ObjectMapper om;
    private final ObjectProvider<GameSocketHandler> hubs;
    private final ObjectProvider<VillageService> villages;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public DuelHandler(PlayerService players, SharedWorldService world, ObjectMapper om, ObjectProvider<GameSocketHandler> hubs, ObjectProvider<VillageService> villages) {
        this.players = players;
        this.world = world;
        this.om = om;
        this.hubs = hubs;
        this.villages = villages;
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
        if (!"list".equals(a) && !"put".equals(a) && !"rank".equals(a) && !("cup".equals(a) && "look".equals(msg.path("op").asText()))) {
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
                case "bet" -> bet(from, msg, hub);
                case "rank" -> rank(from, hub);
                case "cup" -> cup(from, msg, hub);
                default -> { }
            }
        }
    }

    // ---- しまってある値 ----
    private ObjectNode load(GameSocketHandler hub) {
        JsonNode cur = world.getSystem(KEY);
        return loadFrom(cur instanceof ObjectNode ? cur : om.createObjectNode());
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
        ObjectNode live = ((ObjectNode) c).deepCopy();
        live.set("b2", mine);
        live.putArray("bets");
        long f = c.path("f").asLong();
        boolean others = hub.onlinePlayers().stream().anyMatch(o -> o.id() != null && o.id() > 0 && o.id() != me && o.id() != f);
        if (others && d.withArray("live").size() < MAX_LIVE) {
            live.put("until", System.currentTimeMillis() + BET_MS);
            d.withArray("live").add(live);
            save(d);
            hub.broadcastTo(Villages.current(), (ObjectNode) out(hub).put("ev", "open").set("m", view(live, 0)), null);
            return;
        }
        settle(d, live, hub);
        save(d);
    }

    /** 賭けの時間の勝負を見せる形（賭けた人の名前は出さない。me の賭けがあれば my） */
    static ObjectNode view(JsonNode L, long me) {
        ObjectNode v = new ObjectMapper().createObjectNode().put("id", L.path("id").asLong()).put("until", L.path("until").asLong());
        ObjectNode a = v.putObject("a").put("id", L.path("f").asLong()).put("o", L.path("fn").asText()).put("n", L.path("a").path("n").asText()).put("st", L.path("a").path("st").asText()).put("s", total(L.path("a")));
        a.set("sk", L.path("a").path("sk").deepCopy());
        ObjectNode b = v.putObject("b").put("id", L.path("t").asLong()).put("o", L.path("tn").asText()).put("n", L.path("b2").path("n").asText()).put("st", L.path("b2").path("st").asText()).put("s", total(L.path("b2")));
        b.set("sk", L.path("b2").path("sk").deepCopy());
        long sa = 0, sb = 0; int k = 0;
        for (JsonNode bt : L.path("bets")) { if (bt.path("side").asInt() == 0) sa += bt.path("n").asLong(); else sb += bt.path("n").asLong(); k++; if (bt.path("u").asLong() == me) v.putObject("my").put("side", bt.path("side").asInt()).put("n", bt.path("n").asLong()); }
        v.putArray("pool").add(sa).add(sb);
        v.put("k", k);
        return v;
    }

    private void bet(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        long me = from.playerId(), id = msg.path("id").asLong(0);
        int side = msg.path("side").asInt(-1);
        long n = msg.path("n").asLong(0);
        if ((side != 0 && side != 1) || n < BET_MIN || n > BET_MAX) { ng(from, hub, "賭けられなかった"); return; }
        ObjectNode d = load(hub);
        for (JsonNode L : d.withArray("live")) {
            if (L.path("id").asLong() != id) continue;
            if (System.currentTimeMillis() >= L.path("until").asLong()) { ng(from, hub, "もうレースが始まるよ。賭けの時間はおしまい"); return; }
            if (L.path("f").asLong() == me || L.path("t").asLong() == me) { ng(from, hub, "自分の勝負には賭けられないよ"); return; }
            for (JsonNode bt : L.path("bets")) if (bt.path("u").asLong() == me) { ng(from, hub, "この勝負には、もう賭けてあるよ"); return; }
            ((ArrayNode) L.get("bets")).addObject().put("u", me).put("side", side).put("n", n);
            save(d);
            hub.send(from, out(hub).put("ok", "bet").put("id", id).put("side", side).put("n", n));
            ObjectNode v = view(L, 0);
            hub.broadcastTo(Villages.current(), (ObjectNode) out(hub).put("ev", "pool").put("id", id).put("k", v.path("k").asInt()).set("pool", v.get("pool")), null);
            return;
        }
        ng(from, hub, "その勝負は、もう終わったみたい");
    }

    /** 1秒ごと：賭けの時間が終わった勝負を走らせる（村ごと） */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        GameSocketHandler hub = hubs.getIfAvailable();
        if (hub == null) return;
        VillageService vs = villages.getIfAvailable();
        for (long v : vs == null ? java.util.List.of(Villages.MAIN) : vs.ids()) Villages.run(v, () -> tickHere(hub));
    }

    synchronized void tickHere(GameSocketHandler hub) {
        JsonNode cur = world.getSystem(KEY);
        if (cur instanceof ObjectNode && cupDue(cur.path("cup"))) {
            ObjectNode d = loadFrom(cur);
            runCup(d, hub);
            save(d);
            cur = world.getSystem(KEY);
        }
        if (!(cur instanceof ObjectNode) || !cur.path("live").isArray() || cur.path("live").isEmpty()) return;
        ObjectNode d = loadFrom(cur);
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<JsonNode> it = d.withArray("live").elements(); it.hasNext(); ) {
            JsonNode L = it.next();
            if (now < L.path("until").asLong()) continue;
            it.remove();
            settle(d, L, hub);
            changed = true;
        }
        if (changed) save(d);
    }

    /** レースを決めて、村のみんなに見せ、賞金とポイントを動かす（d は呼んだ側が保存する） */
    private void settle(ObjectNode d, JsonNode L, GameSocketHandler hub) {
        long id = L.path("id").asLong(), f = L.path("f").asLong(), t = L.path("t").asLong();
        ObjectNode r = race(L, L.path("b2"), id);
        int w = r.path("w").asInt();
        long[] pool = new long[2];
        for (JsonNode bt : L.path("bets")) pool[bt.path("side").asInt() == 0 ? 0 : 1] += bt.path("n").asLong();
        long all = pool[0] + pool[1];
        r.putArray("pool").add(pool[0]).add(pool[1]);
        long owner = all * 3 / 10;   // 勝った子の持ち主に3割
        r.put("own", owner);
        // 闘鶏ポイント
        int[] pt = points(d, f, L.path("fn").asText(), t, L.path("tn").asText(), w, total(L.path("a")), total(L.path("b2")));
        r.putArray("pt").add(pt[0]).add(pt[1]);
        for (long who : new long[] { f, t }) {
            if (hub.onlineHere(who)) continue;
            ObjectNode res = (ObjectNode) d.get("res");
            ArrayNode arr = res.path(String.valueOf(who)) instanceof ArrayNode x ? x : res.putArray(String.valueOf(who));
            arr.add(r);
            while (arr.size() > MAX_RES) arr.remove(0);
        }
        // レースを先に見せて、そのあとで賞金（画面は、賭けた勝負のレースを見るために、賭けの記録をレースが来るまで持っている）
        hub.broadcastTo(Villages.current(), (ObjectNode) out(hub).put("ev", "race").set("r", r), null);
        if (owner > 0) pay(d, w == 0 ? f : t, out(hub).put("ev", "pay").put("id", id).put("why", "owner").put("n", owner), hub);
        for (JsonNode bt : L.path("bets")) {
            long n = bt.path("n").asLong(), back = 0;
            if (bt.path("side").asInt() == w && pool[w] > 0) back = (all * 7 / 10) * n / pool[w];   // 当てた人で7割を、賭けた額に応じて
            pay(d, bt.path("u").asLong(), out(hub).put("ev", "pay").put("id", id).put("why", "bet").put("stake", n).put("n", back).put("side", bt.path("side").asInt()), hub);
        }
    }

    private void pay(ObjectNode d, long to, ObjectNode m, GameSocketHandler hub) {
        if (hub.onlineHere(to)) { hub.sendToPlayer(to, m); return; }
        ObjectNode pays = d.path("pay") instanceof ObjectNode o ? o : d.putObject("pay");
        ArrayNode arr = pays.path(String.valueOf(to)) instanceof ArrayNode x ? x : pays.putArray(String.valueOf(to));
        m.remove("type");
        arr.add(m);
        while (arr.size() > MAX_PAY) arr.remove(0);
    }

    static String week() {
        return LocalDate.now(JST).with(DayOfWeek.MONDAY).toString();
    }

    /** 闘鶏ポイントを動かす。返すのは [A の増減, B の増減] */
    private int[] points(ObjectNode d, long f, String fn, long t, String tn, int w, int sa, int sb) {
        ObjectNode pd = d.path("pd") instanceof ObjectNode o ? o : d.putObject("pd");
        String day = LocalDate.now(JST).toString(), pair = Math.min(f, t) + "-" + Math.max(f, t);
        if (!day.equals(pd.path("day").asText())) { pd.removeAll(); pd.put("day", day); }
        ObjectNode pairs = pd.path("pairs") instanceof ObjectNode o ? o : pd.putObject("pairs");
        int n = pairs.path(pair).asInt(0);
        if (n >= PAIR_DAY) return new int[] { 0, 0 };
        pairs.put(pair, n + 1);
        int gain = 10 + Math.max(0, Math.min(10, ((w == 0 ? sb - sa : sa - sb)) / 20));
        ObjectNode pt = d.path("pt") instanceof ObjectNode o ? o : d.putObject("pt");
        String wk = week();
        int[] out = new int[2];
        long[] ids = { f, t };
        String[] names = { fn, tn };
        for (int i = 0; i < 2; i++) {
            ObjectNode e = pt.path(String.valueOf(ids[i])) instanceof ObjectNode o ? o : pt.putObject(String.valueOf(ids[i]));
            if (!wk.equals(e.path("wk").asText())) { e.put("wk", wk); e.put("wp", 0); }
            int p = e.path("p").asInt(0), wp = e.path("wp").asInt(0), dlt = i == w ? gain : -Math.min(4, p);
            e.put("n", name(names[i])).put("p", p + dlt).put("wp", Math.max(0, wp + (i == w ? gain : -4)));
            out[i] = dlt;
        }
        return out;
    }

    private void rank(PlayerSession from, GameSocketHandler hub) {
        ObjectNode d = load(hub);
        String wk = week();
        long here = Villages.current(), me = from.playerId();
        java.util.List<long[]> all = new java.util.ArrayList<>();
        java.util.Map<Long, String> nm = new java.util.HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = d.path("pt").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> en = it.next();
            long id;
            try { id = Long.parseLong(en.getKey()); } catch (NumberFormatException e) { continue; }
            Player p = players.find(id).orElse(null);
            if (p == null || p.getVillageId() != here) continue;
            nm.put(id, p.getName());
            all.add(new long[] { id, en.getValue().path("p").asLong(), wk.equals(en.getValue().path("wk").asText()) ? en.getValue().path("wp").asLong() : 0 });
        }
        ObjectNode o = out(hub).put("a", "rank");
        for (int col = 1; col <= 2; col++) {
            final int c = col;
            ArrayNode arr = o.putArray(c == 1 ? "all" : "week");
            all.stream().filter(x -> x[c] > 0).sorted((x, y) -> Long.compare(y[c], x[c])).limit(20)
                .forEach(x -> { ObjectNode row = arr.addObject().put("name", nm.get(x[0])).put("v", x[c]); if (x[0] == me) row.put("me", true); });
        }
        JsonNode mine = d.path("pt").path(String.valueOf(me));
        o.putObject("me").put("p", mine.path("p").asLong(0)).put("wp", wk.equals(mine.path("wk").asText()) ? mine.path("wp").asLong(0) : 0);
        hub.send(from, o);
    }

    static DuelRace.Runner runner(JsonNode c) {
        JsonNode ab = c.path("ab");
        Set<String> sk = new HashSet<>();
        for (JsonNode s : c.path("sk")) if (SKILLS.contains(s.asText())) sk.add(s.asText());
        return new DuelRace.Runner(ab.path("spd").asInt(0), ab.path("sta").asInt(0), ab.path("pow").asInt(0), ab.path("gut").asInt(0), ab.path("wis").asInt(0), sk, c.path("st").asText("saki"));
    }

    private ObjectNode race(JsonNode c, JsonNode mine, long id) {
        JsonNode a = c.path("a");
        long seed = id * 1_000_003L ^ System.nanoTime();
        ObjectNode r = DuelRace.run(seed, runner(a), runner(mine), om);
        r.put("id", id).put("at", System.currentTimeMillis());
        ObjectNode ra = r.putObject("a").put("id", c.path("f").asLong()).put("o", c.path("fn").asText()).put("k", a.path("k").asText()).put("n", a.path("n").asText()).put("st", a.path("st").asText()).put("s", total(a));
        ra.set("sk", a.path("sk").deepCopy());
        ObjectNode rb = r.putObject("b").put("id", c.path("t").asLong()).put("o", c.path("tn").asText()).put("k", mine.path("k").asText()).put("n", mine.path("n").asText()).put("st", mine.path("st").asText()).put("s", total(mine));
        rb.set("sk", mine.path("sk").deepCopy());
        return r;
    }

    // ---- にわとりグランプリ（v0.73.0）：毎日 21:00（日本時間）に、登録した村のにわとりが一斉に走る ----
    // _duel.cup = {day:登録している回の日付, ent:[{u,o,c:{k,n,ab,sk,st}}], last:前の回の結果, done:走った日}
    // {a:"cup",op:"look"|"join"|"leave",k,me:{k,n,ab,sk},st} → 本人に {a:"cup",cup:{...}}。走ったら村に {ev:"cup",r}、賞金は pay の why:"cup"。
    static final int CUP_HOUR = 21, CUP_MAX = 12, CUP_PER = 2, CUP_FILL = 6;
    static final long[] CUP_PRIZE = { 200_000, 80_000, 40_000 };
    static final int[] CUP_PT = { 30, 20, 10 };
    static final String[] NPC_CH = { "ハヤテ", "コトブキ", "ツバサ", "ヒナタ", "モミジ", "ゴマ", "キナコ", "シロガネ", "アカネ", "ミドリ" };

    /** 登録を受けつけている回の日付（21:00 をすぎたら次の日の回） */
    static String cupDay() {
        java.time.ZonedDateTime z = java.time.ZonedDateTime.now(JST);
        return (z.getHour() >= CUP_HOUR ? z.toLocalDate().plusDays(1) : z.toLocalDate()).toString();
    }

    static boolean cupDue(JsonNode cup) {
        if (!cup.isObject() || !cup.path("ent").isArray() || cup.path("ent").isEmpty()) return false;
        String day = cup.path("day").asText("");
        if (day.isEmpty() || day.equals(cup.path("done").asText(""))) return false;
        java.time.ZonedDateTime z = java.time.ZonedDateTime.now(JST);
        String today = z.toLocalDate().toString();
        return day.compareTo(today) < 0 || (day.equals(today) && z.getHour() >= CUP_HOUR);
    }

    static boolean sunday(String day) {
        try { return LocalDate.parse(day).getDayOfWeek() == DayOfWeek.SUNDAY; } catch (Exception e) { return false; }
    }

    private ObjectNode cupOf(ObjectNode d) {
        ObjectNode c = d.path("cup") instanceof ObjectNode o ? o : d.putObject("cup");
        String want = cupDay();
        if (!want.equals(c.path("day").asText(""))) {
            // 前の回が走っていなければ（だれもいなかった）消して、新しい回にする
            if (!c.path("ent").isArray() || c.path("ent").isEmpty() || c.path("day").asText("").equals(c.path("done").asText(""))) { c.put("day", want); c.putArray("ent"); }
        }
        if (!c.path("ent").isArray()) c.putArray("ent");
        return c;
    }

    private ObjectNode cupView(ObjectNode c, long me, GameSocketHandler hub) {
        ObjectNode v = hub.json().createObjectNode();
        String day = c.path("day").asText("");
        boolean sun = sunday(day);
        v.put("day", day).put("hour", CUP_HOUR).put("max", CUP_MAX).put("per", CUP_PER).put("sun", sun);
        ArrayNode pz = v.putArray("prize");
        for (long x : CUP_PRIZE) pz.add(x * (sun ? 3 : 1));
        ArrayNode ent = v.putArray("ent");
        for (JsonNode e : c.path("ent")) {
            ObjectNode o = ent.addObject().put("u", e.path("u").asLong()).put("o", e.path("o").asText()).put("k", e.path("c").path("k").asText()).put("n", e.path("c").path("n").asText())
                .put("st", e.path("c").path("st").asText()).put("s", total(e.path("c")));
            if (e.path("u").asLong() == me) o.put("me", true);
        }
        if (c.has("last")) {
            JsonNode L = c.get("last");
            ObjectNode lv = v.putObject("last").put("day", L.path("day").asText());
            lv.set("top", L.path("top").deepCopy());
            lv.put("id", L.path("id").asText());
        }
        return v;
    }

    private void cup(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        ObjectNode d = load(hub);
        ObjectNode c = cupOf(d);
        long me = from.playerId();
        String op = msg.path("op").asText("look"), err = null, ok = null;
        ArrayNode ent = (ArrayNode) c.get("ent");
        if ("join".equals(op)) {
            ObjectNode ch = chicken(msg.path("me"), hub);
            String st = msg.path("st").asText("saki");
            int mine = 0;
            for (JsonNode e : ent) if (e.path("u").asLong() == me) mine++;
            boolean dup = false;
            if (ch != null) for (JsonNode e : ent) if (e.path("u").asLong() == me && e.path("c").path("k").asText().equals(ch.path("k").asText())) dup = true;
            if (ch == null) err = "そのにわとりは出られないみたい";
            else if (dup) err = "その子はもう登録してあるよ";
            else if (mine >= CUP_PER) err = "1人 " + CUP_PER + " 羽までだよ";
            else if (ent.size() >= CUP_MAX) err = "今回の登録は " + CUP_MAX + " 羽でいっぱいだよ";
            else {
                ch.put("st", STRATS.contains(st) ? st : "saki");
                ent.addObject().put("u", me).put("o", name(from.name())).set("c", ch);
                ok = "「" + ch.path("n").asText() + "」をにわとりグランプリに登録した。" + CUP_HOUR + ":00 に走るよ";
            }
        } else if ("leave".equals(op)) {
            String k = msg.path("k").asText("");
            boolean gone = false;
            for (Iterator<JsonNode> it = ent.elements(); it.hasNext(); ) { JsonNode e = it.next(); if (e.path("u").asLong() == me && e.path("c").path("k").asText().equals(k)) { it.remove(); gone = true; } }
            if (gone) ok = "登録をとりけした"; else err = "とりけせる登録はないよ";
        }
        if (!"look".equals(op) && err == null) save(d);
        ObjectNode o = out(hub).put("a", "cup");
        if (err != null) o.put("ng", err);
        if (ok != null) o.put("msg", ok);
        o.set("cup", cupView(c, me, hub));
        hub.send(from, o);
    }

    /** グランプリを走らせる（d は呼んだ側が保存する） */
    private void runCup(ObjectNode d, GameSocketHandler hub) {
        ObjectNode c = (ObjectNode) d.get("cup");
        String day = c.path("day").asText();
        boolean sun = sunday(day);
        Random rng = new Random(day.hashCode() * 31L + Villages.current());
        java.util.List<JsonNode> ent = new java.util.ArrayList<>();
        c.path("ent").forEach(ent::add);
        java.util.List<ObjectNode> who = new java.util.ArrayList<>();
        for (JsonNode e : ent) {
            ObjectNode w = om.createObjectNode().put("id", e.path("u").asLong()).put("o", e.path("o").asText()).put("k", e.path("c").path("k").asText()).put("n", e.path("c").path("n").asText())
                .put("st", e.path("c").path("st").asText("saki")).put("s", total(e.path("c")));
            w.set("sk", e.path("c").path("sk").deepCopy());
            w.set("ab", e.path("c").path("ab").deepCopy());
            who.add(w);
        }
        java.util.List<String> npc = new java.util.ArrayList<>(java.util.List.of(NPC_CH));
        java.util.Collections.shuffle(npc, rng);
        while (who.size() < CUP_FILL) {
            ObjectNode w = om.createObjectNode().put("id", 0).put("o", "村").put("k", "npc" + who.size()).put("n", npc.remove(0)).put("st", DuelRace.STRATS[rng.nextInt(4)]);
            ObjectNode ab = w.putObject("ab");
            int tot = 0;
            for (String key : AB) { int v = 50 + rng.nextInt(110); ab.put(key, v); tot += v; }
            w.put("s", tot);
            w.putArray("sk");
            who.add(w);
        }
        DuelRace.Runner[] R = new DuelRace.Runner[who.size()];
        for (int i = 0; i < R.length; i++) R[i] = runner(who.get(i));
        ObjectNode r = DuelRace.runMany(rng.nextLong(), R, om);
        String id = "cup" + day;
        r.put("id", id).put("day", day).put("at", System.currentTimeMillis()).put("sun", sun);
        ArrayNode n = r.putArray("n");
        for (ObjectNode w : who) { ObjectNode x = w.deepCopy(); x.remove("ab"); n.add(x); }
        // 賞金とポイント（持ち主のいる子だけ）
        ArrayNode top = om.createArrayNode();
        ObjectNode pt = d.path("pt") instanceof ObjectNode o ? o : d.putObject("pt");
        String wk = week();
        JsonNode rank = r.get("rank");
        ArrayNode prize = r.putArray("prize");
        for (int pos = 0; pos < rank.size(); pos++) {
            ObjectNode w = who.get(rank.get(pos).asInt());
            long pid = w.path("id").asLong();
            long pz = pos < CUP_PRIZE.length && pid > 0 ? CUP_PRIZE[pos] * (sun ? 3 : 1) : 0;
            prize.add(pz);
            if (pos < 3) top.addObject().put("pos", pos + 1).put("n", w.path("n").asText()).put("o", w.path("o").asText());
            if (pid <= 0) continue;
            if (pos < CUP_PT.length) {
                ObjectNode e = pt.path(String.valueOf(pid)) instanceof ObjectNode o ? o : pt.putObject(String.valueOf(pid));
                if (!wk.equals(e.path("wk").asText())) { e.put("wk", wk); e.put("wp", 0); }
                e.put("n", name(w.path("o").asText())).put("p", e.path("p").asInt(0) + CUP_PT[pos]).put("wp", e.path("wp").asInt(0) + CUP_PT[pos]);
            }
        }
        ObjectNode last = om.createObjectNode().put("day", day).put("id", id);
        last.set("top", top);
        last.set("r", r);
        c.set("last", last);
        c.put("done", day);
        c.putArray("ent");
        c.put("day", cupDay());
        // 見せてから賞金（いない人の結果は次の welcome で）
        hub.broadcastTo(Villages.current(), (ObjectNode) out(hub).put("ev", "cup").set("r", r), null);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        ObjectNode cres = d.path("cres") instanceof ObjectNode o ? o : d.putObject("cres");
        for (int pos = 0; pos < rank.size(); pos++) {
            ObjectNode w = who.get(rank.get(pos).asInt());
            long pid = w.path("id").asLong();
            if (pid <= 0) continue;
            if (seen.add(pid) && !hub.onlineHere(pid)) cres.put(String.valueOf(pid), id);
            long pz = prize.get(pos).asLong();
            if (pz > 0) pay(d, pid, out(hub).put("ev", "pay").put("id", id).put("why", "cup").put("n", pz).put("pos", pos + 1).put("cn", w.path("n").asText()), hub);
        }
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
        ArrayNode live = w.putArray("live");
        for (JsonNode L : d.withArray("live")) live.add(view(L, me));
        boolean changed = false;
        JsonNode res = d.path("res").path(String.valueOf(me));
        if (res.isArray() && !res.isEmpty()) {
            w.set("res", res.deepCopy());
            ((ObjectNode) d.get("res")).remove(String.valueOf(me));
            changed = true;
        }
        JsonNode pays = d.path("pay").path(String.valueOf(me));
        if (pays.isArray() && !pays.isEmpty()) {
            w.set("pay", pays.deepCopy());
            ((ObjectNode) d.get("pay")).remove(String.valueOf(me));
            changed = true;
        }
        // にわとりグランプリ：いないあいだに走った回の結果
        JsonNode cr = d.path("cres").path(String.valueOf(me));
        if (cr.isTextual() && d.path("cup").path("last").path("id").asText().equals(cr.asText())) {
            w.set("cup", d.path("cup").path("last").path("r").deepCopy());
        }
        if (cr.isTextual()) { ((ObjectNode) d.get("cres")).remove(String.valueOf(me)); changed = true; }
        if (changed) save(d);
        if (!in.isEmpty() || !outs.isEmpty() || !live.isEmpty() || w.has("res") || w.has("pay") || w.has("cup")) welcome.set("duel", w);
    }

    /** welcome のとき用（hub がないので、しまってある値から作る） */
    private static ObjectNode loadFrom(JsonNode cur) {
        ObjectNode d = ((ObjectNode) cur).deepCopy();
        if (!(d.path("ros") instanceof ObjectNode)) d.putObject("ros");
        if (!(d.path("box") instanceof ArrayNode)) d.putArray("box");
        if (!(d.path("live") instanceof ArrayNode)) d.putArray("live");
        if (!(d.path("res") instanceof ObjectNode)) d.putObject("res");
        if (!(d.path("pay") instanceof ObjectNode)) d.putObject("pay");
        if (!(d.path("cres") instanceof ObjectNode)) d.putObject("cres");
        long now = System.currentTimeMillis();
        for (Iterator<JsonNode> it = d.withArray("box").elements(); it.hasNext(); ) if (now - it.next().path("at").asLong(0) > LIFE) it.remove();
        return d;
    }
}
