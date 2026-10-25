package jp.komorebi.ws;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 送られてきた値をそのまま信じないための、チェック用の小道具 */
final class Sanitize {
    private Sanitize() {
    }

    private static final Pattern MAP = Pattern.compile("[A-Za-z0-9:_\\-]{1,40}");
    static final Set<String> DIRS = Set.of("up", "down", "left", "right");

    static String map(JsonNode n) {
        String s = n == null ? "" : n.asText("");
        return MAP.matcher(s).matches() ? s : null;
    }

    static int coord(JsonNode n) {
        int v = n == null ? -1 : n.asInt(-1);
        return v < 0 || v > 255 ? -1 : v;
    }

    static String dir(JsonNode n) {
        String s = n == null ? "" : n.asText("");
        return DIRS.contains(s) ? s : "down";
    }

    /** 顔に描いたメイク（よこ10×たて9マス、1マス16色を16進数1文字で） */
    private static final Pattern PAINT = Pattern.compile("[0-9a-f]{90}");

    /** 見た目の設定：文字列の値だけ・20項目まで・各20文字まで（メイクの paint だけは決まった形の90文字） */
    static ObjectNode cfg(JsonNode n, ObjectMapper om) {
        ObjectNode out = om.createObjectNode();
        if (n == null || !n.isObject()) return out;
        int count = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext() && count < 20; ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().length() > 12) continue;
            JsonNode v = e.getValue();
            if ("paint".equals(e.getKey())) { if (v.isTextual() && PAINT.matcher(v.asText()).matches()) { out.put("paint", v.asText()); count++; } continue; }
            if (v.isTextual() && v.asText().length() <= 20) { out.put(e.getKey(), v.asText()); count++; }
            else if (v.isBoolean()) { out.put(e.getKey(), v.asBoolean()); count++; }
        }
        return out;
    }
}
