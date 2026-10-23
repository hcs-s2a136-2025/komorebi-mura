package jp.komorebi.admin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * 管理人室から、プレイヤーの所持金を足したり減らしたりする。
 * 本人がつないでいればすぐ届け、いなければ取っておいて次の welcome で渡す（共有の村の "_adminpay"）。
 * text が空なら、本人の画面には何も出さずにお金だけ変わる。
 */
@Service
public class MoneyGiftService implements WelcomeContributor {

    static final String PENDING = "_adminpay";
    public static final long MAX = 10_000_000L;

    private final ObjectProvider<GameSocketHandler> hub;
    private final ObjectMapper om;
    private final SharedWorldService world;

    public MoneyGiftService(ObjectProvider<GameSocketHandler> hub, ObjectMapper om, SharedWorldService world) {
        this.hub = hub;
        this.om = om;
        this.world = world;
    }

    /** @return true ならすぐ届いた、false なら次につないだときに届く */
    public synchronized boolean give(long playerId, long money, String text) {
        long m = Math.max(-MAX, Math.min(MAX, money));
        String t = text == null ? "" : text.strip();
        if (t.length() > 60) t = t.substring(0, 60);
        GameSocketHandler h = hub.getIfAvailable();
        if (h != null && h.isOnline(playerId)) {
            h.sendToPlayer(playerId, om.createObjectNode().put("type", "adminPay").put("money", m).put("text", t));
            return true;
        }
        JsonNode cur = world.getSystem(PENDING);
        ObjectNode p = cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
        String key = String.valueOf(playerId);
        ObjectNode e = p.path(key) instanceof ObjectNode old ? old : om.createObjectNode();
        e.put("money", Math.max(-MAX, Math.min(MAX, e.path("money").asLong(0) + m)));
        if (!t.isEmpty()) e.put("text", t);
        p.set(key, e);
        world.putSystem(PENDING, p);
        return false;
    }

    /** 届いていない分：{プレイヤーID: 金額} */
    public synchronized ObjectNode pending() {
        JsonNode cur = world.getSystem(PENDING);
        return cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
    }

    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        JsonNode cur = world.getSystem(PENDING);
        if (session.playerId() == null) return;
        String key = String.valueOf(session.playerId());
        if (!(cur instanceof ObjectNode o) || !o.has(key)) return;
        ObjectNode p = o.deepCopy();
        welcome.set("adminPay", p.get(key));
        p.remove(key);
        world.putSystem(PENDING, p);
    }
}
