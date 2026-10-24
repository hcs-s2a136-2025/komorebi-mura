package jp.komorebi.sports;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/** {"type":"sd","act":"join|leave|look|score","round":0,"value":123} … 村の運動会 */
@Component
public class SportsDayHandler implements MessageHandler {

    private final SportsDayService service;

    public SportsDayHandler(SportsDayService service) {
        this.service = service;
    }

    @Override
    public String type() {
        return "sd";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        service.command(from, msg);
    }
}
