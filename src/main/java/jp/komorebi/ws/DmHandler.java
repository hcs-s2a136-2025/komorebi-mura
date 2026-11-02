package jp.komorebi.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.village.Villages;
import jp.komorebi.world.SharedWorldService;

/**
 * {"type":"dm","to":相手のID,"text":"…"} … ひとりあてのチャット（ひそひそ）。同じ村の人だけ。
 * 相手がつないでいればすぐ {"type":"dm","id","name","text","at"} を届け、いなければ共有の村の "_dm"（{pend:{ID:[...]}}・1人20件まで）に取っておいて、
 * 次の welcome の "dm" で渡す。送った人には {"type":"dmOk","to","name","online"}、送れないときは {"type":"dmNg","text"}。
 */
@Component
public class DmHandler implements MessageHandler, WelcomeContributor {

    static final String KEY = "_dm";
    static final int MAX_PEND = 20;

    private final PlayerService players;
    private final SharedWorldService world;
    private final Map<Long, Long> lastSent = new ConcurrentHashMap<>();

    public DmHandler(PlayerService players, SharedWorldService world) {
        this.players = players;
        this.world = world;
    }

    @Override
    public String type() {
        return "dm";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        String text = ChatHandler.clean(msg.path("text").asText(""));
        long to = msg.path("to").asLong(0);
        if (text.isEmpty() || to <= 0 || to == from.playerId()) return;
        long now = System.currentTimeMillis();
        Long prev = lastSent.put(from.playerId(), now);
        if (prev != null && now - prev < 1000) return;
        Player p = players.find(to).orElse(null);
        if (p == null || p.getVillageId() != Villages.current()) {
            hub.send(from, hub.json().createObjectNode().put("type", "dmNg").put("text", "その人には、ひそひそを送れないみたい"));
            return;
        }
        ObjectNode line = hub.json().createObjectNode().put("type", "dm").put("id", from.playerId()).put("name", from.name()).put("text", text).put("at", now);
        boolean online = hub.onlineHere(to);
        if (online) hub.sendToPlayer(to, line);
        else keep(to, line, hub);
        hub.send(from, hub.json().createObjectNode().put("type", "dmOk").put("to", to).put("name", p.getName()).put("online", online));
    }

    private synchronized void keep(long to, ObjectNode line, GameSocketHandler hub) {
        JsonNode cur = world.getSystem(KEY);
        ObjectNode all = cur instanceof ObjectNode o ? o.deepCopy() : hub.json().createObjectNode();
        ObjectNode pend = all.path("pend") instanceof ObjectNode o ? o : all.putObject("pend");
        ArrayNode arr = pend.path(String.valueOf(to)) instanceof ArrayNode a ? a : pend.putArray(String.valueOf(to));
        arr.addObject().put("id", line.path("id").asLong()).put("name", line.path("name").asText("")).put("text", line.path("text").asText("")).put("at", line.path("at").asLong());
        while (arr.size() > MAX_PEND) arr.remove(0);
        world.putSystem(KEY, all);
    }

    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        if (session.playerId() == null || session.watcher()) return;
        JsonNode cur = world.getSystem(KEY);
        if (!(cur instanceof ObjectNode o)) return;
        JsonNode mine = o.path("pend").path(String.valueOf(session.playerId()));
        if (!mine.isArray() || mine.isEmpty()) return;
        welcome.set("dm", mine.deepCopy());
        ObjectNode all = o.deepCopy();
        ((ObjectNode) all.get("pend")).remove(String.valueOf(session.playerId()));
        world.putSystem(KEY, all);
    }
}
