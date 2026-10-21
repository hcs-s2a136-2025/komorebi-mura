package jp.komorebi.ws;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** {"type":"cfg","cfg":{...}} … 着せ替えたらみんなの画面でも見た目が変わる */
@Component
public class CfgHandler implements MessageHandler {
    @Override
    public String type() {
        return "cfg";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        ObjectNode cfg = Sanitize.cfg(msg.get("cfg"), hub.json());
        from.setCfg(cfg);
        ObjectNode out = hub.json().createObjectNode().put("type", "cfg").put("id", from.playerId());
        out.set("cfg", cfg);
        hub.broadcast(out, from);
    }
}
