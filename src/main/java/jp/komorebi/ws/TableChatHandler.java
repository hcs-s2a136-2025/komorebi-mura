package jp.komorebi.ws;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.casino.TableRoster;

/**
 * {"type":"tchat","text":"よろしく"} … 麻雀やBJの、同じ卓の人だけに届くチャット。
 * 卓についていなければ {"type":"tchatNg"} を本人に返す。ログは残さない。
 */
@Component
public class TableChatHandler implements MessageHandler {

    private final List<TableRoster> rosters;
    private final Map<Long, Long> lastSent = new ConcurrentHashMap<>();

    public TableChatHandler(List<TableRoster> rosters) {
        this.rosters = rosters;
    }

    @Override
    public String type() {
        return "tchat";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        String text = ChatHandler.clean(msg.path("text").asText(""));
        if (text.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long prev = lastSent.put(from.playerId(), now);
        if (prev != null && now - prev < 800) return;
        for (TableRoster r : rosters) {
            List<Long> mates = r.mates(from.playerId());
            if (!mates.contains(from.playerId())) continue;
            ObjectNode out = hub.json().createObjectNode().put("type", "tchat").put("id", from.playerId())
                .put("name", from.name()).put("text", text).put("at", now).put("k", r.kind());
            for (Long id : mates) if (!id.equals(from.playerId())) hub.sendToPlayer(id, out);
            return;
        }
        hub.send(from, hub.json().createObjectNode().put("type", "tchatNg"));
    }
}
