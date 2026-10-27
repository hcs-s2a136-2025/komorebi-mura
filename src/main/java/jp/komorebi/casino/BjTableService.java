package jp.komorebi.casino;

import java.security.SecureRandom;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;

/** みんなのどんぐりBJの卓を1つ持っていて、WebSocket とつなぐ係。ルールは {@link BjGame} */
@Service
public class BjTableService implements TableRoster {

    private final ObjectProvider<GameSocketHandler> hub;
    private final ObjectMapper om;
    /** 村ごとの卓 */
    private final jp.komorebi.village.PerVillage<BjGame> games;

    public BjTableService(ObjectProvider<GameSocketHandler> hub, ObjectMapper om) {
        this.hub = hub;
        this.om = om;
        this.games = new jp.komorebi.village.PerVillage<>(v -> new BjGame(new SecureRandom(), new BjGame.Out() {
            @Override
            public void toAll(Map<String, Object> msg) {
                GameSocketHandler h = hub.getIfAvailable();
                if (h != null) h.broadcastTo(v, toNode(msg), null);
            }

            @Override
            public void toPlayer(long playerId, Map<String, Object> msg) {
                GameSocketHandler h = hub.getIfAvailable();
                if (h != null) h.sendToPlayer(playerId, toNode(msg));
            }
        }));
    }

    private BjGame game() {
        return games.get();
    }

    private ObjectNode toNode(Map<String, Object> msg) {
        return om.valueToTree(msg);
    }

    public void command(PlayerSession from, JsonNode msg) {
        game().command(from.playerId(), from.name(), msg.path("act").asText(""), msg.path("amount").asLong(0), System.currentTimeMillis());
    }

    @Scheduled(fixedDelay = 500)
    public void tick() {
        final GameSocketHandler h = hub.getIfAvailable();
        if (h == null) return;
        games.each((v, g) -> g.tick(System.currentTimeMillis(), id -> h.isOnline(id)));
    }

    /** 管理人室用：いま座っている人数 */
    @Override
    public String kind() {
        return "bj";
    }

    @Override
    public java.util.List<Long> mates(long playerId) {
        return game().mates(playerId);
    }

    public int seated() {
        Object seats = game().state(System.currentTimeMillis()).get("seats");
        return seats instanceof java.util.List<?> l ? l.size() : 0;
    }
}
