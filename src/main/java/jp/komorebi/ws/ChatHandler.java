package jp.komorebi.ws;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** {"type":"chat","text":"こんにちは"} … いまつないでいる人どうしの吹き出しチャット（最近の30件だけ覚えておく） */
@Component
public class ChatHandler implements MessageHandler, WelcomeContributor {

    public record ChatLine(String name, String text, Instant at) {
    }

    /** 村ごとの最近のチャット */
    private final jp.komorebi.village.PerVillage<Deque<ChatLine>> recentBy = new jp.komorebi.village.PerVillage<>(v -> new ArrayDeque<>());
    private final Map<Long, Long> lastSent = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "chat";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        String text = clean(msg.path("text").asText(""));
        if (text.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long prev = lastSent.put(from.playerId(), now);
        if (prev != null && now - prev < 1000) return;
        ChatLine line = new ChatLine(from.name(), text, Instant.ofEpochMilli(now));
        Deque<ChatLine> recent = recentBy.get();
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > 30) recent.pollFirst();
        }
        hub.broadcast(hub.json().createObjectNode().put("type", "chat").put("id", from.playerId())
            .put("name", from.name()).put("text", text).put("at", now), from);
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        ArrayNode arr = welcome.putArray("chat");
        for (ChatLine c : recentLines()) arr.addObject().put("name", c.name()).put("text", c.text()).put("at", c.at().toEpochMilli());
    }

    /** いまの村の最近のチャット */
    public List<ChatLine> recentLines() {
        Deque<ChatLine> recent = recentBy.get();
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    static String clean(String s) {
        String t = s.replaceAll("[\\p{Cntrl}\\u200B-\\u200F\\u2028-\\u202E]", "").replaceAll("\\s+", " ").strip();
        return t.codePointCount(0, t.length()) > 60 ? t.substring(0, t.offsetByCodePoints(0, 60)) : t;
    }
}
