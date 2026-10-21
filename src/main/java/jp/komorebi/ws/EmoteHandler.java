package jp.komorebi.ws;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/** {"type":"emote","e":"♪"} … 決まったスタンプだけ送れる（自由入力のチャットは今はなし） */
@Component
public class EmoteHandler implements MessageHandler {
    private static final Set<String> OK = Set.of("！", "？", "♪", "♥", "…", "ありがとう", "こっち！", "おつかれ");

    @Override
    public String type() {
        return "emote";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        String e = msg.path("e").asText("");
        if (!OK.contains(e)) return;
        hub.broadcast(hub.json().createObjectNode().put("type", "emote").put("id", from.playerId()).put("e", e), from);
    }
}
