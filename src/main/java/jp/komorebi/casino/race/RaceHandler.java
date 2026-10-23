package jp.komorebi.casino.race;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/** {"type":"race","act":"bet|look","runner":0,"kind":"win|place","amount":100} … 競どんぐり */
@Component
public class RaceHandler implements MessageHandler {

    private final RaceService race;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public RaceHandler(RaceService race) {
        this.race = race;
    }

    @Override
    public String type() {
        return "race";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        String act = msg.path("act").asText("");
        if ("look".equals(act)) { hub.send(from, race.state()); return; }
        if (!"bet".equals(act)) return;
        long now = System.currentTimeMillis();
        Long prev = last.put(from.playerId(), now);
        if (prev != null && now - prev < 250) return;
        race.bet(from, msg.path("runner").asInt(-1), msg.path("kind").asText(""), msg.path("amount").asInt(0));
    }
}
