package jp.komorebi.ws;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.world.SharedWorldService;

/** {"type":"edits","list":[{"l":"trees","k":"11,11","v":{...}}]} … 共有の村の変更を受け取って、みんなに配る */
@Component
public class EditsHandler implements MessageHandler {

    private final SharedWorldService world;

    public EditsHandler(SharedWorldService world) {
        this.world = world;
    }

    @Override
    public String type() {
        return "edits";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        ArrayNode applied = world.apply(msg.get("list"));
        if (applied.isEmpty()) return;
        ObjectNode out = hub.json().createObjectNode().put("type", "edits").put("by", from.playerId());
        out.set("list", applied);
        hub.broadcast(out, from);
    }
}
