package jp.komorebi.ws;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {"type":"motion","k":"hoe"} … いま何をしているか（道具をふる・釣り・カジノで遊んでいる など）。
 * 同じ場所にいる人にだけ知らせる。k=catch のとき f=釣れた魚、k=status のとき s=「スロット中」などの短い言葉、
 * k=emote のとき e=エモート（手をふる・おじぎ など）。
 */
@Component
public class MotionHandler implements MessageHandler {
    private static final Set<String> OK = Set.of("hoe", "axe", "pick", "can", "sword", "shovel", "plant", "fish", "catch", "status", "emote");
    /** キャラが動くエモート（手をふる・おじぎ・拍手・ジャンプ・おどる・すわる・ねる・がっかり・とまる） */
    static final Set<String> EMOTES = Set.of("wave", "bow", "clap", "jump", "dance", "sit", "sleep", "sad", "stop");
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "motion";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        String k = msg.path("k").asText("");
        if (!OK.contains(k) || from.playerId() == null) return;
        long now = System.currentTimeMillis();
        Long prev = last.put(from.playerId(), now);
        if (prev != null && now - prev < 120) return;
        ObjectNode out = hub.json().createObjectNode().put("type", "motion").put("id", from.playerId()).put("k", k);
        if ("catch".equals(k)) out.put("f", clean(msg.path("f").asText(""), 16));
        if ("status".equals(k)) out.put("s", clean(msg.path("s").asText(""), 12));
        if ("emote".equals(k)) {
            String e = msg.path("e").asText("");
            if (!EMOTES.contains(e)) return;
            out.put("e", e);
        }
        String map = from.map();
        if (map == null) return;
        hub.sendToMap(map, out, from);
    }

    static String clean(String s, int max) {
        String t = s.replaceAll("[\\p{Cntrl}<>]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
