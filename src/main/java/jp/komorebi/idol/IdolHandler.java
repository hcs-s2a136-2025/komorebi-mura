package jp.komorebi.idol;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * アイドルの対決・コーデカードのトレード・ファンサとはがし・アイドルグランプリ（v0.85.0・村ごと）。type "idol"。
 * 共有の村の "_idol" = {seq, ros:{ID:{n,i}}, box:[対決状], res:{ID:[結果]}, tres:{ID:[トレードの結果]}, pt:{ID:{n,nm,p,wk,wp}}, pd, tops:{週:{id,n,nm,p}}}。
 * <ul>
 *   <li>{"a":"put","i":アイドル} … 自分のアイドルを見せる（返事なし）。アイドル＝{nm,ty 0〜3,vo,da,ex 0〜300,pw コーデの力 0〜300,lk:{idl,icd,iau}}</li>
 *   <li>{"a":"list"} … 同じ村のほかの人のアイドル → {"a":"list","list":[{id,owner,i}]}</li>
 *   <li>{"a":"send","to","me"} → 本人に {"ok":"send","c"}、相手に {"ev":"in","c"}（いなければ次の welcome）。1人5通まで送れて10通まで受けとれる・同じ相手に1通・3日で消える</li>
 *   <li>{"a":"cancel"|"no","id"} → 相手に {"ev":"gone","id"} だけ（断ったことは知らせない）</li>
 *   <li>{"a":"yes","id","me"} → サーバーが3本勝負（歌・ダンス・表情）を決めて、ふたりに {"ev":"res","r"}（いない人の分は res に10こまで）</li>
 *   <li>{"a":"rank"} → {"a":"rank","all","week","me":{p,wp},"sup","lead","wk":週の月曜}。先週の1位がスーパーアイドル（"sup"）</li>
 *   <li>{"a":"fan","to"} → 相手に {"ev":"fan","from","name"}／{"a":"hagasu","to"} → 相手に {"ev":"hagasu","from","name"}（どちらも同じ村でつないでいる人だけ・とっておかない）</li>
 *   <li>{"a":"tr","op":"ask","to","give":[カード]} … トレードを申しこむ（相手はつないでいる人だけ）。"put"（id, give）・"ok"（id）・"cancel"（id）・"look"。
 *       ふたりとも "ok" を押すと、ふたりに {"ev":"done","id","get","give"}（いない人の分は tres）。カード＝{c:0〜255,p:0|1,s:サイン}・5枚まで。
 *       トレードの部屋はメモリだけ（15分で消える）。カードを持っているのは画面なので、画面は出したカードを "done" まで預かっておく</li>
 * </ul>
 */
@Component
public class IdolHandler implements MessageHandler, WelcomeContributor {

    static final String KEY = "_idol";
    static final int MAX_OUT = 5, MAX_IN = 10, MAX_RES = 10, MAX_TRES = 10, MAX_CARDS = 5, MAX_CARD = 255, PAIR_DAY = 3, MAX_TR = 3, STAT = 300, POW = 300;
    static final long LIFE = 3L * 24 * 3600 * 1000, TR_LIFE = 15L * 60 * 1000;
    static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    static final Pattern CODE = Pattern.compile("[0-9a-z]{1,20}");
    static final String[] ST = { "vo", "da", "ex" };
    /** テイスト（0 キュート＝表情・1 クール＝ダンス・2 ポップ＝歌・3 セクシー＝ぜんぶ少し）が得意なラウンド */
    static final int[] TY_ROUND = { 2, 1, 0, -1 };

    private final PlayerService players;
    private final SharedWorldService world;
    private final ObjectMapper om;
    private final ObjectProvider<GameSocketHandler> hubs;
    private final ObjectProvider<VillageService> villages;
    private final Map<Long, Long> last = new ConcurrentHashMap<>(), lastFan = new ConcurrentHashMap<>();
    /** 村 → トレードの部屋 */
    private final Map<Long, Map<Long, ObjectNode>> trades = new ConcurrentHashMap<>();
    private long trSeq = 0;
    private final Random rnd = new Random();

    public IdolHandler(PlayerService players, SharedWorldService world, ObjectMapper om, ObjectProvider<GameSocketHandler> hubs, ObjectProvider<VillageService> villages) {
        this.players = players;
        this.world = world;
        this.om = om;
        this.hubs = hubs;
        this.villages = villages;
    }

    @Override
    public String type() {
        return "idol";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        long me = from.playerId();
        String a = msg.path("a").asText("");
        long now = System.currentTimeMillis();
        if ("fan".equals(a) || "hagasu".equals(a)) {
            Long prev = lastFan.put(me, now);
            if (prev != null && now - prev < 1500) return;
            relay(from, msg.path("to").asLong(0), a, hub);
            return;
        }
        if (!"list".equals(a) && !"put".equals(a) && !"rank".equals(a) && !("tr".equals(a) && "look".equals(msg.path("op").asText()))) {
            Long prev = last.put(me, now);
            if (prev != null && now - prev < 300) { ng(from, hub, "少し待ってからもう一度ためしてね"); return; }
        }
        synchronized (this) {
            switch (a) {
                case "put" -> put(from, msg, hub);
                case "list" -> list(from, hub);
                case "send" -> send(from, msg, hub);
                case "cancel", "no" -> drop(from, msg.path("id").asLong(0), "cancel".equals(a), hub);
                case "yes" -> yes(from, msg, hub);
                case "rank" -> rank(from, hub);
                case "tr" -> trade(from, msg, hub);
                default -> { }
            }
        }
    }

    // ---- しまってある値 ----
    private ObjectNode load() {
        JsonNode cur = world.getSystem(KEY);
        ObjectNode d = cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
        for (String k : new String[] { "ros", "res", "tres", "pt", "pd", "tops" }) if (!(d.path(k) instanceof ObjectNode)) d.putObject(k);
        if (!(d.path("box") instanceof ArrayNode)) d.putArray("box");
        long now = System.currentTimeMillis();
        for (Iterator<JsonNode> it = d.withArray("box").elements(); it.hasNext(); ) if (now - it.next().path("at").asLong(0) > LIFE) it.remove();
        return d;
    }

    private void save(ObjectNode d) {
        world.putSystem(KEY, d);
    }

    private static ObjectNode out(GameSocketHandler hub) {
        return hub.json().createObjectNode().put("type", "idol");
    }

    private static void ng(PlayerSession s, GameSocketHandler hub, String text) {
        hub.send(s, out(hub).put("ng", text));
    }

    static String name(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}<>\"&\\u200b-\\u200f\\u2028-\\u202f\\u2060-\\u206f\\ufeff]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** アイドル1人：おかしな値は直す */
    static ObjectNode idol(JsonNode c, ObjectMapper om) {
        if (c == null || !c.isObject()) return null;
        ObjectNode o = om.createObjectNode();
        String nm = name(c.path("nm").asText(""), 10);
        o.put("nm", nm.isEmpty() ? "アイドル" : nm).put("ty", clamp(c.path("ty").asInt(0), 0, 3));
        for (String k : ST) o.put(k, clamp(c.path(k).asInt(0), 0, STAT));
        o.put("pw", clamp(c.path("pw").asInt(0), 0, POW));
        ObjectNode lk = o.putObject("lk");
        for (String k : new String[] { "idl", "icd", "iau" }) { String v = c.path("lk").path(k).asText(""); if (CODE.matcher(v).matches()) lk.put(k, v); }
        return o;
    }

    private static int total(JsonNode i) {
        return i.path("vo").asInt() + i.path("da").asInt() + i.path("ex").asInt() + i.path("pw").asInt();
    }

    private Player here(long id) {
        Player p = players.find(id).orElse(null);
        return p != null && p.getVillageId() == Villages.current() ? p : null;
    }

    private void put(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        ObjectNode d = load(), i = idol(msg.path("i"), om);
        if (i == null) ((ObjectNode) d.get("ros")).remove(String.valueOf(from.playerId()));
        else ((ObjectNode) d.get("ros")).putObject(String.valueOf(from.playerId())).put("n", name(from.name(), 12)).set("i", i);
        save(d);
    }

    private void list(PlayerSession from, GameSocketHandler hub) {
        ObjectNode d = load();
        ArrayNode list = hub.json().createArrayNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = d.get("ros").fields(); it.hasNext() && list.size() < 40; ) {
            Map.Entry<String, JsonNode> en = it.next();
            long id;
            try { id = Long.parseLong(en.getKey()); } catch (NumberFormatException e) { continue; }
            if (id == from.playerId()) continue;
            Player p = here(id);
            if (p == null) continue;
            list.addObject().put("id", id).put("owner", p.getName()).set("i", en.getValue().path("i").deepCopy());
        }
        ObjectNode o = out(hub).put("a", "list");
        o.set("list", list);
        hub.send(from, o);
    }

    private void send(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        long me = from.playerId(), to = msg.path("to").asLong(0);
        ObjectNode mine = idol(msg.path("me"), om);
        if (to <= 0 || to == me || mine == null) { ng(from, hub, "対決状を書けなかった"); return; }
        Player p = here(to);
        if (p == null) { ng(from, hub, "その人には対決状を送れないみたい"); return; }
        ObjectNode d = load();
        JsonNode target = d.path("ros").path(String.valueOf(to)).path("i");
        if (!target.isObject()) { ng(from, hub, "その人のアイドルが見つからなかった"); return; }
        int outN = 0, inN = 0;
        for (JsonNode c : d.get("box")) {
            if (c.path("f").asLong() == me) outN++;
            if (c.path("t").asLong() == to) inN++;
            if (c.path("f").asLong() == me && c.path("t").asLong() == to) { ng(from, hub, "その人には、もう対決状を送ってあるよ"); return; }
        }
        if (outN >= MAX_OUT) { ng(from, hub, "送れる対決状は" + MAX_OUT + "通まで。返事を待とう"); return; }
        if (inN >= MAX_IN) { ng(from, hub, "その人には対決状がたまっているみたい。また今度"); return; }
        long id = d.path("seq").asLong(0) + 1;
        d.put("seq", id);
        ObjectNode c = d.withArray("box").addObject().put("id", id).put("f", me).put("fn", name(from.name(), 12)).put("t", to).put("tn", p.getName()).put("at", System.currentTimeMillis());
        c.set("a", mine);
        c.set("b", target.deepCopy());
        save(d);
        hub.send(from, (ObjectNode) out(hub).put("ok", "send").set("c", c.deepCopy()));
        if (hub.onlineHere(to)) hub.sendToPlayer(to, (ObjectNode) out(hub).put("ev", "in").set("c", c.deepCopy()));
    }

    private void drop(PlayerSession from, long id, boolean bySender, GameSocketHandler hub) {
        long me = from.playerId();
        ObjectNode d = load();
        for (Iterator<JsonNode> it = d.withArray("box").elements(); it.hasNext(); ) {
            JsonNode c = it.next();
            if (c.path("id").asLong() != id || c.path(bySender ? "f" : "t").asLong() != me) continue;
            it.remove();
            save(d);
            long other = c.path(bySender ? "t" : "f").asLong();
            if (hub.onlineHere(other)) hub.sendToPlayer(other, out(hub).put("ev", "gone").put("id", id));
            break;
        }
        hub.send(from, out(hub).put("ok", bySender ? "cancel" : "no").put("id", id));
    }

    private void yes(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        long me = from.playerId(), id = msg.path("id").asLong(0);
        ObjectNode mine = idol(msg.path("me"), om);
        ObjectNode d = load();
        JsonNode c = null;
        for (Iterator<JsonNode> it = d.withArray("box").elements(); it.hasNext(); ) {
            JsonNode x = it.next();
            if (x.path("id").asLong() == id && x.path("t").asLong() == me) { c = x; it.remove(); break; }
        }
        if (c == null) { hub.send(from, out(hub).put("ev", "gone").put("id", id)); return; }
        if (mine == null) { ng(from, hub, "アイドルの用意ができていないみたい"); return; }
        long f = c.path("f").asLong();
        ObjectNode r = battle(c.path("a"), mine, rnd);
        r.put("id", id).put("at", System.currentTimeMillis());
        ((ObjectNode) r.get("a")).put("id", f).put("o", c.path("fn").asText());
        ((ObjectNode) r.get("b")).put("id", me).put("o", name(from.name(), 12));
        int w = r.path("w").asInt();
        int[] pt = points(d, f, c.path("fn").asText(), c.path("a").path("nm").asText(), me, name(from.name(), 12), mine.path("nm").asText(), w, total(c.path("a")), total(mine));
        r.putArray("pt").add(pt[0]).add(pt[1]);
        for (long who : new long[] { f, me }) {
            if (hub.onlineHere(who)) { hub.sendToPlayer(who, (ObjectNode) out(hub).put("ev", "res").set("r", r.deepCopy())); continue; }
            ObjectNode res = (ObjectNode) d.get("res");
            ArrayNode arr = res.path(String.valueOf(who)) instanceof ArrayNode x ? x : res.putArray(String.valueOf(who));
            arr.add(r.deepCopy());
            while (arr.size() > MAX_RES) arr.remove(0);
        }
        save(d);
    }

    /** 3本勝負（歌・ダンス・表情）。各ラウンド＝その力＋コーデの力×0.4＋テイストのボーナス＋0〜90。スペシャルアピールは1人1回（+45） */
    static ObjectNode battle(JsonNode a, JsonNode b, Random rnd) {
        ObjectNode r = new ObjectMapper().createObjectNode();
        r.set("a", a.deepCopy());
        r.set("b", b.deepCopy());
        ArrayNode rd = r.putArray("rd");
        JsonNode[] s = { a, b };
        boolean[] sp = new boolean[2];
        int[] wins = new int[2], tot = new int[2];
        for (int k = 0; k < 3; k++) {
            int[] sc = new int[2];
            int[] used = new int[2];
            for (int i = 0; i < 2; i++) {
                int ty = s[i].path("ty").asInt(0);
                double v = s[i].path(ST[k]).asInt(0) + s[i].path("pw").asInt(0) * 0.4 + (TY_ROUND[ty] == k ? 30 : ty == 3 ? 12 : 0) + rnd.nextInt(91);
                if (!sp[i] && (k == 2 || rnd.nextDouble() < 0.35)) { sp[i] = true; used[i] = 1; v += 45; }
                sc[i] = (int) Math.round(v);
                tot[i] += sc[i];
            }
            if (sc[0] > sc[1]) wins[0]++; else if (sc[1] > sc[0]) wins[1]++;
            rd.addArray().add(sc[0]).add(sc[1]).add(used[0]).add(used[1]);
        }
        int w = wins[0] != wins[1] ? (wins[0] > wins[1] ? 0 : 1) : tot[0] != tot[1] ? (tot[0] > tot[1] ? 0 : 1) : rnd.nextInt(2);
        r.put("w", w);
        return r;
    }

    static String week(int back) {
        return LocalDate.now(JST).with(DayOfWeek.MONDAY).minusWeeks(back).toString();
    }

    /** 対決ポイント：勝つと +10＋(相手の力−自分)/60（0〜10）、負けても +3。同じふたりは日本時間の1日3回まで */
    private int[] points(ObjectNode d, long f, String fn, String fnm, long t, String tn, String tnm, int w, int sa, int sb) {
        ObjectNode pd = (ObjectNode) d.get("pd");
        String day = LocalDate.now(JST).toString(), pair = Math.min(f, t) + "-" + Math.max(f, t);
        if (!day.equals(pd.path("day").asText())) { pd.removeAll(); pd.put("day", day); }
        ObjectNode pairs = pd.path("pairs") instanceof ObjectNode o ? o : pd.putObject("pairs");
        int n = pairs.path(pair).asInt(0);
        if (n >= PAIR_DAY) return new int[] { 0, 0 };
        pairs.put(pair, n + 1);
        int gain = 10 + clamp((w == 0 ? sb - sa : sa - sb) / 60, 0, 10);
        ObjectNode pt = (ObjectNode) d.get("pt");
        String wk = week(0);
        int[] res = new int[2];
        long[] ids = { f, t };
        String[] names = { fn, tn }, idols = { fnm, tnm };
        for (int i = 0; i < 2; i++) {
            ObjectNode e = pt.path(String.valueOf(ids[i])) instanceof ObjectNode o ? o : pt.putObject(String.valueOf(ids[i]));
            if (!wk.equals(e.path("wk").asText())) { e.put("wk", wk); e.put("wp", 0); }
            int dlt = i == w ? gain : 3;
            e.put("n", name(names[i], 12)).put("nm", name(idols[i], 10)).put("p", e.path("p").asInt(0) + dlt).put("wp", e.path("wp").asInt(0) + dlt);
            res[i] = dlt;
        }
        // 今週の1位を残す（来週のスーパーアイドル）
        ObjectNode top = leader(d, wk);
        ObjectNode tops = (ObjectNode) d.get("tops");
        if (top != null) tops.set(wk, top);
        List<String> ks = new ArrayList<>();
        tops.fieldNames().forEachRemaining(ks::add);
        ks.sort(null);
        while (ks.size() > 6) tops.remove(ks.remove(0));
        return res;
    }

    private static ObjectNode leader(ObjectNode d, String wk) {
        ObjectNode best = null;
        int bp = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = d.path("pt").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> en = it.next();
            JsonNode e = en.getValue();
            if (!wk.equals(e.path("wk").asText())) continue;
            int p = e.path("wp").asInt(0);
            if (p <= bp) continue;
            long id;
            try { id = Long.parseLong(en.getKey()); } catch (NumberFormatException x) { continue; }
            bp = p;
            best = new ObjectMapper().createObjectNode().put("id", id).put("n", e.path("n").asText()).put("nm", e.path("nm").asText()).put("p", p);
        }
        return best;
    }

    /** 先週の1位（スーパーアイドル） */
    private static JsonNode sup(ObjectNode d) {
        JsonNode s = d.path("tops").path(week(1));
        return s.isObject() ? s : null;
    }

    private void rank(PlayerSession from, GameSocketHandler hub) {
        ObjectNode d = load();
        String wk = week(0);
        long me = from.playerId();
        List<ObjectNode> rows = new ArrayList<>();
        ObjectNode o = out(hub).put("a", "rank");
        ObjectNode my = o.putObject("me").put("p", 0).put("wp", 0);
        for (Iterator<Map.Entry<String, JsonNode>> it = d.path("pt").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> en = it.next();
            long id;
            try { id = Long.parseLong(en.getKey()); } catch (NumberFormatException e) { continue; }
            Player p = here(id);
            if (p == null) continue;
            JsonNode e = en.getValue();
            int wp = wk.equals(e.path("wk").asText()) ? e.path("wp").asInt(0) : 0;
            rows.add(hub.json().createObjectNode().put("id", id).put("n", p.getName()).put("nm", e.path("nm").asText()).put("p", e.path("p").asInt(0)).put("wp", wp));
            if (id == me) my.put("p", e.path("p").asInt(0)).put("wp", wp);
        }
        ArrayNode all = o.putArray("all"), wkRows = o.putArray("week");
        rows.sort((x, y) -> y.path("p").asInt() - x.path("p").asInt());
        for (ObjectNode r : rows) if (all.size() < 20 && r.path("p").asInt() > 0) all.add(r.deepCopy());
        rows.sort((x, y) -> y.path("wp").asInt() - x.path("wp").asInt());
        for (ObjectNode r : rows) if (wkRows.size() < 20 && r.path("wp").asInt() > 0) wkRows.add(r.deepCopy());
        JsonNode s = sup(d);
        if (s != null) o.set("sup", s.deepCopy());
        ObjectNode lead = leader(d, wk);
        if (lead != null) o.set("lead", lead);
        o.put("wk", wk);
        hub.send(from, o);
    }

    private void relay(PlayerSession from, long to, String a, GameSocketHandler hub) {
        if (to <= 0 || to == from.playerId() || !hub.onlineHere(to)) return;
        hub.sendToPlayer(to, out(hub).put("ev", a).put("from", from.playerId()).put("name", name(from.name(), 12)));
    }

    // ---- トレード（メモリだけ） ----
    private Map<Long, ObjectNode> rooms() {
        return trades.computeIfAbsent(Villages.current(), v -> new ConcurrentHashMap<>());
    }

    /** カードの並び：おかしな物は捨てる（5枚まで） */
    static ArrayNode cards(JsonNode list, ObjectMapper om) {
        ArrayNode out = om.createArrayNode();
        if (list == null || !list.isArray()) return out;
        for (JsonNode c : list) {
            if (out.size() >= MAX_CARDS) break;
            if (!c.isObject() || !c.path("c").canConvertToInt()) continue;
            int id = c.path("c").asInt(-1);
            if (id < 0 || id > MAX_CARD) continue;
            out.addObject().put("c", id).put("p", c.path("p").asInt(0) == 1 ? 1 : 0).put("s", name(c.path("s").asText(""), 10));
        }
        return out;
    }

    private void trade(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        long me = from.playerId();
        String op = msg.path("op").asText("");
        Map<Long, ObjectNode> R = rooms();
        if ("look".equals(op)) {
            ArrayNode l = hub.json().createArrayNode();
            for (ObjectNode T : R.values()) if (T.path("f").asLong() == me || T.path("t").asLong() == me) l.add(T.deepCopy());
            ObjectNode o = out(hub).put("a", "tr");
            o.set("list", l);
            hub.send(from, o);
            return;
        }
        if ("ask".equals(op)) {
            long to = msg.path("to").asLong(0);
            Player p = to > 0 && to != me ? here(to) : null;
            if (p == null || !hub.onlineHere(to)) { ng(from, hub, "トレードは、いま同じ村でつないでいる人とだけできるよ"); return; }
            int mineN = 0;
            for (ObjectNode T : R.values()) {
                if (T.path("f").asLong() == me || T.path("t").asLong() == me) mineN++;
                if ((T.path("f").asLong() == me && T.path("t").asLong() == to) || (T.path("f").asLong() == to && T.path("t").asLong() == me)) { ng(from, hub, "その人とは、もうトレードの話をしているよ"); return; }
            }
            if (mineN >= MAX_TR) { ng(from, hub, "トレードは同時に" + MAX_TR + "つまで"); return; }
            ArrayNode give = cards(msg.path("give"), om);
            long id = ++trSeq;
            ObjectNode T = om.createObjectNode().put("id", id).put("f", me).put("fn", name(from.name(), 12)).put("t", to).put("tn", p.getName()).put("okf", false).put("okt", false).put("at", System.currentTimeMillis());
            T.set("gf", give);
            T.putArray("gt");
            R.put(id, T);
            hub.send(from, (ObjectNode) out(hub).put("a", "tr").put("ok", "ask").set("tr", T.deepCopy()));
            hub.sendToPlayer(to, (ObjectNode) out(hub).put("a", "tr").put("ev", "in").set("tr", T.deepCopy()));
            return;
        }
        long id = msg.path("id").asLong(0);
        ObjectNode T = R.get(id);
        if (T == null || (T.path("f").asLong() != me && T.path("t").asLong() != me)) { hub.send(from, out(hub).put("a", "tr").put("ev", "gone").put("id", id)); return; }
        boolean isF = T.path("f").asLong() == me;
        long other = isF ? T.path("t").asLong() : T.path("f").asLong();
        switch (op) {
            case "put" -> {
                T.set(isF ? "gf" : "gt", cards(msg.path("give"), om));
                T.put("okf", false).put("okt", false).put("at", System.currentTimeMillis());
                for (long who : new long[] { me, other }) hub.sendToPlayer(who, (ObjectNode) out(hub).put("a", "tr").put("ev", "up").set("tr", T.deepCopy()));
            }
            case "ok" -> {
                T.put(isF ? "okf" : "okt", true);
                if (T.path("okf").asBoolean() && T.path("okt").asBoolean()) {
                    R.remove(id);
                    ObjectNode d = load();
                    done(d, T.path("f").asLong(), T, T.get("gt"), T.get("gf"), hub);
                    done(d, T.path("t").asLong(), T, T.get("gf"), T.get("gt"), hub);
                    save(d);
                } else for (long who : new long[] { me, other }) hub.sendToPlayer(who, (ObjectNode) out(hub).put("a", "tr").put("ev", "up").set("tr", T.deepCopy()));
            }
            case "cancel" -> {
                R.remove(id);
                for (long who : new long[] { me, other }) hub.sendToPlayer(who, out(hub).put("a", "tr").put("ev", "gone").put("id", id));
            }
            default -> { }
        }
    }

    private void done(ObjectNode d, long who, ObjectNode T, JsonNode get, JsonNode give, GameSocketHandler hub) {
        ObjectNode m = out(hub).put("a", "tr").put("ev", "done").put("id", T.path("id").asLong()).put("with", who == T.path("f").asLong() ? T.path("tn").asText() : T.path("fn").asText());
        m.set("get", get.deepCopy());
        m.set("give", give.deepCopy());
        if (hub.onlineHere(who)) { hub.sendToPlayer(who, m); return; }
        ObjectNode tres = (ObjectNode) d.get("tres");
        ArrayNode arr = tres.path(String.valueOf(who)) instanceof ArrayNode x ? x : tres.putArray(String.valueOf(who));
        m.remove("type");
        arr.add(m);
        while (arr.size() > MAX_TRES) arr.remove(0);
    }

    /** 15分たったトレードを消す（村ごと） */
    @Scheduled(fixedDelay = 5000)
    public void tick() {
        GameSocketHandler hub = hubs.getIfAvailable();
        if (hub == null) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, Map<Long, ObjectNode>> en : trades.entrySet()) {
            Villages.run(en.getKey(), () -> {
                synchronized (this) {
                    for (Iterator<ObjectNode> it = en.getValue().values().iterator(); it.hasNext(); ) {
                        ObjectNode T = it.next();
                        if (now - T.path("at").asLong() < TR_LIFE) continue;
                        it.remove();
                        for (long who : new long[] { T.path("f").asLong(), T.path("t").asLong() }) hub.sendToPlayer(who, out(hub).put("a", "tr").put("ev", "gone").put("id", T.path("id").asLong()));
                    }
                }
            });
        }
    }

    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        if (session.playerId() == null || session.playerId() <= 0 || session.watcher()) return;
        long me = session.playerId();
        JsonNode cur = world.getSystem(KEY);
        ObjectNode w = welcome.objectNode();
        ArrayNode trs = w.putArray("trs");
        for (ObjectNode T : rooms().values()) if (T.path("f").asLong() == me || T.path("t").asLong() == me) trs.add(T.path("id").asLong());
        if (!(cur instanceof ObjectNode)) { welcome.set("idolx", w); return; }
        ObjectNode d = load();
        ArrayNode in = w.putArray("in"), outs = w.putArray("out");
        for (JsonNode c : d.get("box")) {
            if (c.path("t").asLong() == me) in.add(c.deepCopy());
            if (c.path("f").asLong() == me) outs.add(c.deepCopy());
        }
        boolean changed = false;
        for (String k : new String[] { "res", "tres" }) {
            JsonNode res = d.path(k).path(String.valueOf(me));
            if (res.isArray() && !res.isEmpty()) {
                w.set(k, res.deepCopy());
                ((ObjectNode) d.get(k)).remove(String.valueOf(me));
                changed = true;
            }
        }
        JsonNode s = sup(d);
        if (s != null) w.set("sup", s.deepCopy());
        welcome.set("idolx", w);
        if (changed) save(d);
    }
}
