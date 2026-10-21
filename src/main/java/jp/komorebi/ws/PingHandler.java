package jp.komorebi.ws;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/** {"type":"ping"} … 接続を保つための合図。pong を返すだけ */
@Component
public class PingHandler implements MessageHandler {
    @Override
    public String type() {
        return "ping";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        hub.send(from, hub.json().createObjectNode().put("type", "pong"));
    }
}
