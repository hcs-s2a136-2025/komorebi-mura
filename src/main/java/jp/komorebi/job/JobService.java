package jp.komorebi.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.player.PlayerService;
import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * 村のアルバイトのうち、ほかの人がかかわるもの。
 * ・郵便配達：ほかの人の家へお手紙を届ける。相手がつないでいなければ、共有の村の "_job" に {pend: {プレイヤーID: [お手紙...]}} で取っておき、
 *   次の welcome（"job93": [...]）で渡す（1人20通まで）。お手紙の中身は文面の番号（0〜5）だけ。文面はゲーム側にある
 * ・脱走の手引き：労働施設の中の人が「助けて」を出す（help）→ 村のほかの人が一覧で見て、引き受ける（join）→ 2人で合図を送りあう（rs）。
 *   助けを求めている人・組はつないでいるあいだだけメモリに持つ（サーバーを再起動すると消える）。20分で期限切れ
 */
@Service
public class JobService implements WelcomeContributor {

    static final String KEY = "_job";
    static final int MAX_PEND = 20, MAX_KIND = 5;
    static final long HELP_TTL = 20 * 60_000L;
    static final Set<String> OPS = Set.of("flash", "hit", "miss", "next", "ready", "lost", "bye");

    /** 助けを求めている人（inmate）と、引き受けた人（helper） */
    static final class Rescue {
        final long inmate;
        final String name;
        final long since = System.currentTimeMillis();
        volatile Long helper;
        volatile String helperName = "";

        Rescue(long inmate, String name) {
            this.inmate = inmate;
            this.name = name;
        }
    }

    private final SharedWorldService world;
    private final PlayerService players;
    private final ObjectMapper om;
    private final Map<Long, Map<Long, Rescue>> rescues = new ConcurrentHashMap<>();
    private final Map<String, Long> last = new ConcurrentHashMap<>();

    public JobService(SharedWorldService world, PlayerService players, ObjectMapper om) {
        this.world = world;
        this.players = players;
        this.om = om;
    }

    private final Map<Long, long[]> rsWin = new ConcurrentHashMap<>();

    /** 合図は同じ瞬間に2通（最後の合図とおしまいの知らせ）続くことがあるので、1秒に20通まで */
    boolean rsOk(long id) {
        long now = System.currentTimeMillis();
        long[] w = rsWin.computeIfAbsent(id, k -> new long[] { now, 0 });
        synchronized (w) {
            if (now - w[0] > 1000) { w[0] = now; w[1] = 0; }
            return ++w[1] <= 20;
        }
    }

    boolean slow(String key, long ms) {
        long now = System.currentTimeMillis();
        Long prev = last.put(key, now);
        return prev != null && now - prev < ms;
    }

    private Map<Long, Rescue> mine(PlayerSession s) {
        return rescues.computeIfAbsent(s.village(), k -> new ConcurrentHashMap<>());
    }

    private ObjectNode msg(String a) {
        return om.createObjectNode().put("type", "job").put("a", a);
    }

    private ObjectNode ev(String kind) {
        ObjectNode m = om.createObjectNode().put("type", "job");
        m.putObject("ev").put("kind", kind);
        return m;
    }

    // ---- 郵便配達 ----

    /** お手紙を届ける。null なら届いた（届く）。文字列ならうまくいかなかった理由 */
    public synchronized String letter(GameSocketHandler hub, PlayerSession from, long to, int kind) {
        long me = from.playerId();
        if (to == me) return "自分あてのお手紙は届けられないよ";
        if (kind < 0 || kind > MAX_KIND) return "お手紙が見つからなかった";
        if (slow("l" + me, 1000)) return "少し待ってから届けてね";
        boolean exists = players.listAll().stream().anyMatch(p -> p.getId() == to && p.getVillageId() == from.village());
        if (!exists) return "その家の人は見つからなかった";
        ObjectNode e = om.createObjectNode().put("kind", "letter").put("from", me).put("name", from.name() == null ? "だれか" : from.name()).put("k", kind);
        if (hub.isOnline(to)) {
            ObjectNode m = om.createObjectNode().put("type", "job");
            m.set("ev", e);
            hub.sendToPlayer(to, m);
            return null;
        }
        ObjectNode d = data();
        ObjectNode pend = d.path("pend") instanceof ObjectNode o ? o : d.putObject("pend");
        String key = String.valueOf(to);
        ArrayNode list = pend.path(key) instanceof ArrayNode a ? a : pend.putArray(key);
        if (list.size() < MAX_PEND) list.add(e);
        world.putSystem(KEY, d);
        return null;
    }

    private ObjectNode data() {
        JsonNode cur = world.getSystem(KEY);
        return cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
    }

    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        if (session.playerId() == null) return;
        ObjectNode d = data();
        JsonNode pend = d.path("pend");
        String key = String.valueOf(session.playerId());
        if (!pend.has(key)) return;
        welcome.set("job93", pend.get(key));
        ((ObjectNode) d.get("pend")).remove(key);
        world.putSystem(KEY, d);
    }

    // ---- 脱走の手引き ----

    private void purge(GameSocketHandler hub, PlayerSession s) {
        long now = System.currentTimeMillis();
        mine(s).values().removeIf(r -> now - r.since > HELP_TTL || !hub.onlineHere(r.inmate));
    }

    /** 「助けて」を出す／ひっこめる */
    public synchronized void help(GameSocketHandler hub, PlayerSession from, boolean on) {
        long me = from.playerId();
        Map<Long, Rescue> m = mine(from);
        if (on) {
            if (slow("h" + me, 2000)) return;
            purge(hub, from);
            if (m.containsKey(me)) return;
            m.put(me, new Rescue(me, from.name() == null ? "だれか" : from.name()));
        } else {
            Rescue r = m.remove(me);
            if (r == null) return;
            if (r.helper != null) hub.sendToPlayer(r.helper, ev("bye").put("id", me));
        }
        ObjectNode b = ev("help");
        ((ObjectNode) b.get("ev")).put("id", me).put("name", from.name() == null ? "だれか" : from.name()).put("on", on);
        hub.broadcastTo(from.village(), b, from);
    }

    public synchronized void list(GameSocketHandler hub, PlayerSession from) {
        purge(hub, from);
        ObjectNode out = msg("list");
        ArrayNode arr = out.putArray("list");
        List<Rescue> rs = new ArrayList<>(mine(from).values());
        rs.sort(Comparator.comparingLong(r -> r.since));
        for (Rescue r : rs) {
            if (r.inmate == from.playerId()) continue;
            arr.addObject().put("id", r.inmate).put("name", r.name).put("busy", r.helper != null);
        }
        hub.send(from, out);
    }

    /** 引き受ける */
    public synchronized void join(GameSocketHandler hub, PlayerSession from, long inmate) {
        long me = from.playerId();
        purge(hub, from);
        Rescue r = mine(from).get(inmate);
        ObjectNode out = msg("joined").put("id", inmate);
        if (r == null || inmate == me) { hub.send(from, out.put("ok", false).put("text", "その人の「助けて」は、もう終わったみたい")); return; }
        if (r.helper != null && !r.helper.equals(me)) { hub.send(from, out.put("ok", false).put("text", "ほかの人が助けに行っているよ")); return; }
        r.helper = me;
        r.helperName = from.name() == null ? "だれか" : from.name();
        hub.send(from, out.put("ok", true).put("name", r.name));
        ObjectNode e = ev("joined");
        ((ObjectNode) e.get("ev")).put("id", me).put("name", r.helperName);
        hub.sendToPlayer(inmate, e);
    }

    /** 2人のあいだで合図を送りあう。helper が "won" を送るとおしまい（中の人が解放される） */
    public synchronized void relay(GameSocketHandler hub, PlayerSession from, long inmate, String op, int c, int n) {
        long me = from.playerId();
        Rescue r = mine(from).get(inmate);
        if (r == null || r.helper == null) return;
        boolean isInmate = me == r.inmate, isHelper = me == r.helper;
        if (!isInmate && !isHelper) return;
        long to = isInmate ? r.helper : r.inmate;
        if ("won".equals(op)) {
            if (!isHelper) return;
            mine(from).remove(inmate);
            ObjectNode e = ev("freed");
            ((ObjectNode) e.get("ev")).put("id", me).put("name", r.helperName);
            hub.sendToPlayer(to, e);
            ObjectNode b = ev("help");
            ((ObjectNode) b.get("ev")).put("id", inmate).put("name", r.name).put("on", false);
            hub.broadcastTo(from.village(), b, from);
            return;
        }
        if (!OPS.contains(op)) return;
        if ("bye".equals(op)) { if (isHelper) r.helper = null; else mine(from).remove(inmate); }
        ObjectNode e = ev("rs");
        ((ObjectNode) e.get("ev")).put("id", inmate).put("op", op).put("c", Math.max(0, Math.min(3, c))).put("n", Math.max(0, Math.min(99, n)));
        hub.sendToPlayer(to, e);
    }
}
