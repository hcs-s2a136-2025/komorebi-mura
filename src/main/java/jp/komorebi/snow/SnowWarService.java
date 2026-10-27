package jp.komorebi.snow;

import java.security.SecureRandom;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.clock.ClockService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/** 雪合戦の陣取りを WebSocket とつなぐ係。ルールは {@link SnowWarGame} */
@Service
public class SnowWarService implements WelcomeContributor {

    private final ObjectProvider<GameSocketHandler> hub;
    private final ObjectMapper om;
    private final ClockService clock;
    /** 村ごとの雪合戦 */
    private final jp.komorebi.village.PerVillage<SnowWarGame> games;

    public SnowWarService(ObjectProvider<GameSocketHandler> hub, ObjectMapper om, ClockService clock) {
        this.hub = hub;
        this.om = om;
        this.clock = clock;
        this.games = new jp.komorebi.village.PerVillage<>(v -> new SnowWarGame(new SecureRandom(), new SnowWarGame.Out() {
            @Override
            public void toAll(Map<String, Object> msg) {
                GameSocketHandler h = hub.getIfAvailable();
                if (h != null) h.broadcastTo(v, om.valueToTree(msg), null);
            }

            @Override
            public void toPlayer(long playerId, Map<String, Object> msg) {
                GameSocketHandler h = hub.getIfAvailable();
                if (h != null) h.sendToPlayer(playerId, om.valueToTree(msg));
            }
        }));
    }

    /** いまが冬か（村の時計の日にちから。7日で季節が変わり、春・夏・秋・冬の順） */
    boolean winter() {
        return ((clock.day() - 1) / 7) % 4 == 3;
    }

    public void command(PlayerSession from, JsonNode msg) {
        boolean inVillage = "village".equals(from.map());
        games.get().command(from.playerId(), from.name(), msg.path("act").asText(""), inVillage ? from.x() : -9, inVillage ? from.y() : -9,
            msg.path("to").asLong(0), winter(), System.currentTimeMillis());
    }

    @Scheduled(fixedDelay = 500)
    public void tick() {
        GameSocketHandler h = hub.getIfAvailable();
        if (h == null) return;
        games.each((v, g) -> g.tick(System.currentTimeMillis(), id -> h.isOnline(id)));
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        SnowWarGame game = games.get();
        if (game.active()) welcome.set("sw", om.valueToTree(game.state(System.currentTimeMillis())));
    }
}
