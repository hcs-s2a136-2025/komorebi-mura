package jp.komorebi.casino.race;

import java.security.SecureRandom;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.clock.ClockService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/** 競どんぐりの進行を WebSocket とつなぐ係。ルールと進行は {@link RaceGame} */
@Service
public class RaceService implements WelcomeContributor {

    private final ObjectProvider<GameSocketHandler> hub;
    private final ObjectMapper om;
    private final ClockService clock;
    private final RaceGame game;

    public RaceService(ObjectProvider<GameSocketHandler> hub, ObjectMapper om, ClockService clock) {
        this.hub = hub;
        this.om = om;
        this.clock = clock;
        this.game = new RaceGame(new SecureRandom(), new RaceGame.Out() {
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

    @Scheduled(fixedDelay = 500)
    public void tick() {
        game.tick(System.currentTimeMillis(), clock.day());
    }

    public void bet(PlayerSession from, int runner, String kind, int amount) {
        game.bet(from.playerId(), from.name(), runner, kind, amount, System.currentTimeMillis());
    }

    public ObjectNode state() {
        return om.valueToTree(game.state(System.currentTimeMillis()));
    }

    public Map<String, Object> daySummary(int day) {
        return game.daySummary(day);
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        welcome.set("race", state());
        welcome.set("raceResults", om.valueToTree(game.results()));
    }
}
