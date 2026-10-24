package jp.komorebi.sports;

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
import jp.komorebi.ws.WelcomeContributor;

/** 村の運動会を WebSocket と管理人室につなぐ係。ルールは {@link SportsDayGame} */
@Service
public class SportsDayService implements WelcomeContributor {

    private final ObjectProvider<GameSocketHandler> hub;
    private final ObjectMapper om;
    private final SportsDayGame game;

    public SportsDayService(ObjectProvider<GameSocketHandler> hub, ObjectMapper om) {
        this.hub = hub;
        this.om = om;
        this.game = new SportsDayGame(new SecureRandom(), new SportsDayGame.Out() {
            @Override
            public void toAll(Map<String, Object> msg) {
                GameSocketHandler h = hub.getIfAvailable();
                if (h != null) h.broadcast(om.valueToTree(msg), null);
            }

            @Override
            public void toPlayer(long playerId, Map<String, Object> msg) {
                GameSocketHandler h = hub.getIfAvailable();
                if (h != null) h.sendToPlayer(playerId, om.valueToTree(msg));
            }
        });
    }

    public void command(PlayerSession from, JsonNode msg) {
        game.command(from.playerId(), from.name(), msg.path("act").asText(""), msg.path("round").asInt(-1), msg.path("value").asInt(0), System.currentTimeMillis());
    }

    public boolean open() { return game.open(System.currentTimeMillis()); }

    public String startNow() { return game.startNow(System.currentTimeMillis()); }

    public void stop() { game.stop(System.currentTimeMillis()); }

    public Object[] summary() { return game.summary(); }

    @Scheduled(fixedDelay = 500)
    public void tick() {
        GameSocketHandler h = hub.getIfAvailable();
        if (h == null) return;
        game.tick(System.currentTimeMillis(), id -> h.isOnline(id));
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        if (game.active()) welcome.set("sd", om.valueToTree(game.state(System.currentTimeMillis())));
    }
}
