package jp.komorebi.raid;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/** {"type":"raid","a":"hit","d":12} 攻撃 ／ {"a":"dish","id":"dish_curry","n":"カレー"} 宴に料理 ／ {"a":"look"} いまのようす */
@Component
public class RaidHandler implements MessageHandler {

    private final RaidService raid;

    public RaidHandler(RaidService raid) {
        this.raid = raid;
    }

    @Override
    public String type() {
        return "raid";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        switch (msg.path("a").asText("")) {
            case "hit" -> raid.hit(from, msg.path("d").asInt(0));
            case "dish" -> raid.dish(from, msg.path("id").asText(""), msg.path("n").asText(""));
            case "look" -> hub.send(from, raid.state());
            default -> { }
        }
    }
}
