package jp.komorebi.admin;

import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * 管理人室から、プレイヤーに物を届ける（なくなった物の埋め合わせなど）。
 * 本人がつないでいればすぐ {"type":"adminGift","items":[{id,n}],"text":...} を届け、
 * いなければ共有の村の "_admingift" に取っておいて、次の welcome（adminGift: [ {items, text}, ... ]）で渡す。
 * どの ID が本当にある物かはゲームの画面が知っているので、知らない物は画面のほうで家の収納箱に入れずに知らせる。
 */
@Service
public class ItemGiftService implements WelcomeContributor {

    static final String PENDING = "_admingift";
    static final Pattern ID = Pattern.compile("[\\p{L}\\p{N}_:\\-]{1,32}");
    public static final int MAX_N = 999;

    private final ObjectProvider<GameSocketHandler> hub;
    private final ObjectMapper om;
    private final SharedWorldService world;

    public ItemGiftService(ObjectProvider<GameSocketHandler> hub, ObjectMapper om, SharedWorldService world) {
        this.hub = hub;
        this.om = om;
        this.world = world;
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** @return true ならすぐ届いた、false なら次につないだときに届く */
    public synchronized boolean give(long playerId, String id, int n, String text) {
        int c = Math.max(1, Math.min(MAX_N, n));
        String t = text == null ? "" : text.strip();
        if (t.length() > 60) t = t.substring(0, 60);
        ObjectNode gift = om.createObjectNode();
        ArrayNode items = gift.putArray("items");
        items.addObject().put("id", id).put("n", c);
        gift.put("text", t);
        GameSocketHandler h = hub.getIfAvailable();
        if (h != null && h.isOnline(playerId)) {
            ObjectNode msg = gift.deepCopy(); msg.put("type", "adminGift");
            h.sendToPlayer(playerId, msg);
            return true;
        }
        JsonNode cur = world.getSystem(PENDING);
        ObjectNode p = cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
        String key = String.valueOf(playerId);
        ArrayNode list = p.path(key) instanceof ArrayNode a ? a : p.putArray(key);
        if (list.size() < 50) list.add(gift);
        p.set(key, list);
        world.putSystem(PENDING, p);
        return false;
    }

    /** 届いていない分の数：{プレイヤーID: 件数} */
    public synchronized ObjectNode pending() {
        JsonNode cur = world.getSystem(PENDING);
        return cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
    }

    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        if (session.playerId() == null) return;
        JsonNode cur = world.getSystem(PENDING);
        String key = String.valueOf(session.playerId());
        if (!(cur instanceof ObjectNode o) || !o.has(key)) return;
        ObjectNode p = o.deepCopy();
        welcome.set("adminGift", p.get(key));
        p.remove(key);
        world.putSystem(PENDING, p);
    }
}
