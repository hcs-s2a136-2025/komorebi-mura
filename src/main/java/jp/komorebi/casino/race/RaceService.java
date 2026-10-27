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
    /** 村ごとのレース場 */
    private final jp.komorebi.village.PerVillage<RaceGame> games;
    private final ObjectProvider<jp.komorebi.village.VillageService> villages;

    public RaceService(ObjectProvider<GameSocketHandler> hub, ObjectMapper om, ClockService clock, ObjectProvider<jp.komorebi.village.VillageService> villages) {
        this.hub = hub;
        this.om = om;
        this.clock = clock;
        this.villages = villages;
        this.games = new jp.komorebi.village.PerVillage<>(v -> new RaceGame(new SecureRandom(), new RaceGame.Out() {
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

    private RaceGame game() {
        return games.get();
    }

    @Scheduled(fixedDelay = 500)
    public void tick() {
        // レースは、だれもいない村でも時間どおりに進む（村の時計の日にちを使う）
        jp.komorebi.village.VillageService vs = villages.getIfAvailable();
        for (long v : vs == null ? java.util.List.of(jp.komorebi.village.Villages.MAIN) : vs.ids())
            jp.komorebi.village.Villages.run(v, () -> games.of(v).tick(System.currentTimeMillis(), clock.day()));
    }

    public void bet(PlayerSession from, int runner, String kind, int amount) {
        game().bet(from.playerId(), from.name(), runner, kind, amount, System.currentTimeMillis());
    }

    public ObjectNode state() {
        return om.valueToTree(game().state(System.currentTimeMillis()));
    }

    public Map<String, Object> daySummary(int day) {
        return game().daySummary(day);
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        welcome.set("race", state());
        welcome.set("raceResults", om.valueToTree(game().results()));
    }
}
