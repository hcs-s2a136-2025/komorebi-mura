package jp.komorebi.casino;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/** {"type":"bj","act":"sit|leave|bet|hit|stand|double|split|look","amount":100} … みんなのどんぐりBJ */
@Component
public class BjHandler implements MessageHandler {

    private final BjTableService table;

    public BjHandler(BjTableService table) {
        this.table = table;
    }

    @Override
    public String type() {
        return "bj";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        table.command(from, msg);
    }
}
