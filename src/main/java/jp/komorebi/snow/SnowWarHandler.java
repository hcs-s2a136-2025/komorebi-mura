package jp.komorebi.snow;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/** {"type":"sw","act":"join|leave|start|look|hit|grab|cap","to":相手のid} … 雪合戦の陣取り */
@Component
public class SnowWarHandler implements MessageHandler {

    private final SnowWarService service;

    public SnowWarHandler(SnowWarService service) {
        this.service = service;
    }

    @Override
    public String type() {
        return "sw";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        service.command(from, msg);
    }
}
