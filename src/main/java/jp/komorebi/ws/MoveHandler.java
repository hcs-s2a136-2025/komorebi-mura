package jp.komorebi.ws;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** {"type":"move","map":"village","x":7,"y":9,"dir":"down"} … 位置が変わったらみんなに知らせる */
@Component
public class MoveHandler implements MessageHandler {
    @Override
    public String type() {
        return "move";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        String map = Sanitize.map(msg.get("map"));
        int x = Sanitize.coord(msg.get("x")), y = Sanitize.coord(msg.get("y"));
        if (map == null || x < 0 || y < 0) return;
        from.moveTo(map, x, y, Sanitize.dir(msg.get("dir")));
        ObjectNode out = hub.json().createObjectNode().put("type", "move").put("id", from.playerId())
            .put("map", map).put("x", x).put("y", y).put("dir", from.dir());
        hub.broadcast(out, from);
    }
}
