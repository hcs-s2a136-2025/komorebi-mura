package jp.komorebi.clock;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/** {"type":"sleep"} … ベッドで眠ったら、村全体が次の朝になる */
@Component
public class SleepHandler implements MessageHandler {

    private final ClockService clock;

    public SleepHandler(ClockService clock) {
        this.clock = clock;
    }

    @Override
    public String type() {
        return "sleep";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (!clock.sleep(from)) {
            hub.send(from, hub.json().createObjectNode().put("type", "notice").put("text", "さっき朝になったばかり。少し待ってから眠ってね"));
        }
    }
}
