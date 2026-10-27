package jp.komorebi.cash;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * 現金（お札）まわりで、プレイヤーどうしや村全体にかかわること。
 * ・ポチ袋：お札をほかの人に贈る。中に偽札が何枚まざっていたかは、贈った人の画面だけが知っている
 *   （f：贈った人も偽札だとわかっている分。u：贈った人も知らずに持っていた分。u は帳簿につけない）。
 *   サーバーは「だれからだれに偽札を何枚送ったか」を帳簿（led）につけておき、通報が来たらその枚数までだけ受けつける。
 * ・通報：もらったお札から偽札が見つかったら、送った人に「警察」の知らせ（罰金と牢獄）、見つけた人に返金の知らせ。
 * ・村の偽札：お店で通ってしまった偽札は村に出回り（pool）、VIPルームの払い戻しなどに、たまにまざって戻ってくる。
 * 共有の村の "_cash" に {pool, led: {"送った人>受けとった人": 枚数}, pend: {プレイヤーID: [知らせ...]}} で持つ。
 * 本人がつないでいなければ、知らせは次の welcome（"cash77": [...]）で渡す。
 */
@Service
public class CashService implements WelcomeContributor {

    static final String KEY = "_cash";
    public static final int MAX_BILLS = 200;
    static final long MAX_POOL = 100_000;

    private final SharedWorldService world;
    private final PlayerService players;
    private final ObjectProvider<GameSocketHandler> hub;
    private final ObjectMapper om;

    public CashService(SharedWorldService world, PlayerService players, ObjectProvider<GameSocketHandler> hub, ObjectMapper om) {
        this.world = world;
        this.players = players;
        this.hub = hub;
        this.om = om;
    }

    public Optional<Player> player(long id) {
        return players.listAll().stream().filter(p -> p.getId() == id).findFirst();
    }

    /** ポチ袋。null なら届いた（届く）。文字列ならうまくいかなかった理由 */
    public synchronized String pochi(long from, String fromName, long to, int n, int fakes, int unknown, String msg) {
        if (from == to) return "自分には贈れないよ";
        if (n < 1 || n > MAX_BILLS) return "お札は1〜" + MAX_BILLS + "枚で贈ってね";
        if (player(to).isEmpty()) return "その人は見つからなかった";
        int f = Math.max(0, Math.min(n, fakes)), u = Math.max(0, Math.min(n - f, unknown));
        String text = msg == null ? "" : msg.strip();
        if (text.length() > 40) text = text.substring(0, 40);
        ObjectNode d = data();
        if (f > 0) {
            ObjectNode led = obj(d, "led");
            String k = from + ">" + to;
            led.put(k, Math.min(1_000_000, led.path(k).asLong(0) + f));
        }
        ObjectNode ev = om.createObjectNode().put("kind", "pochi").put("from", from).put("name", fromName == null ? "だれか" : fromName).put("n", n).put("f", f).put("u", u).put("msg", text);
        deliver(d, to, ev);
        world.putSystem(KEY, d);
        return null;
    }

    /** もらったお札から、origin さんが送った偽札が n 枚見つかった。受けつけた枚数を返す */
    public synchronized int report(long reporter, String reporterName, long origin, int n) {
        if (reporter == origin || n <= 0) return 0;
        ObjectNode d = data();
        ObjectNode led = obj(d, "led");
        String k = origin + ">" + reporter;
        long have = led.path(k).asLong(0);
        int m = (int) Math.min(have, Math.min(n, MAX_BILLS));
        if (m <= 0) return 0;
        if (have - m > 0) led.put(k, have - m); else led.remove(k);
        String originName = player(origin).map(Player::getName).orElse("だれか");
        deliver(d, reporter, om.createObjectNode().put("kind", "refund").put("n", m).put("name", originName));
        deliver(d, origin, om.createObjectNode().put("kind", "police").put("n", m).put("by", reporterName == null ? "だれか" : reporterName));
        world.putSystem(KEY, d);
        return m;
    }

    /** お店で通ってしまった偽札が、村に出回る */
    public synchronized long pass(int n) {
        ObjectNode d = data();
        long pool = Math.min(MAX_POOL, Math.max(0, d.path("pool").asLong(0)) + Math.max(0, Math.min(MAX_BILLS, n)));
        d.put("pool", pool);
        world.putSystem(KEY, d);
        return pool;
    }

    /** n 枚の払い戻しのうち、何枚が村に出回っている偽札か（出回っている数が多いほど、まざりやすい） */
    public synchronized int draw(int n) {
        int want = Math.max(0, Math.min(MAX_BILLS, n));
        ObjectNode d = data();
        long pool = Math.max(0, d.path("pool").asLong(0));
        if (pool == 0 || want == 0) return 0;
        double p = Math.min(0.25, pool / (pool + 300.0));
        int k = 0;
        for (int i = 0; i < want && k < pool; i++) if (ThreadLocalRandom.current().nextDouble() < p) k++;
        if (k > 0) { d.put("pool", pool - k); world.putSystem(KEY, d); }
        return k;
    }

    public synchronized long pool() {
        return Math.max(0, data().path("pool").asLong(0));
    }

    private void deliver(ObjectNode d, long to, ObjectNode ev) {
        GameSocketHandler h = hub.getIfAvailable();
        if (h != null && h.isOnline(to)) {
            ObjectNode msg = om.createObjectNode().put("type", "cash77");
            msg.set("ev", ev);
            h.sendToPlayer(to, msg);
            return;
        }
        ObjectNode pend = obj(d, "pend");
        String key = String.valueOf(to);
        ArrayNode list = pend.path(key) instanceof ArrayNode a ? a : pend.putArray(key);
        if (list.size() < 50) list.add(ev);
    }

    private static ObjectNode obj(ObjectNode d, String k) {
        return d.path(k) instanceof ObjectNode o ? o : d.putObject(k);
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
        welcome.set("cash77", pend.get(key));
        ((ObjectNode) d.get("pend")).remove(key);
        world.putSystem(KEY, d);
    }
}
