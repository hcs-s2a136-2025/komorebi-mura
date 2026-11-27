package jp.komorebi.ws;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 送られてきた値をそのまま信じないための、チェック用の小道具 */
public final class Sanitize {
    private Sanitize() {
    }

    private static final Pattern MAP = Pattern.compile("[A-Za-z0-9:,_\\-]{1,40}");
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
    /** 顔に描いたメイクの細かい版（よこ20×たて18マス・32ドットの絵用。paint もいっしょに送られる） */
    private static final Pattern PAINT2 = Pattern.compile("[0-9a-f]{360}");
    /** 服のマイデザイン（よこ20×たて10マス、1マスを36進数1文字で。v0.83.1 から35色。前の16進数の柄もそのまま通る） */
    private static final Pattern MYDESIGN = Pattern.compile("[0-9a-z]{200}");

    /** 細かいメイク（v0.87.0）：正面・横・後ろの3面 × よこ32×たて28マス。1マスを36進数1文字（0＝なし・1〜f＝決まった色・g〜y＝自分の色・z＝消す）。
     *  同じ文字のくり返しは、うしろに大文字（26進数・A＝2回）をつけてちぢめる */
    public static final int MK_CELLS = 3 * 32 * 28, PMK_CELLS = 16 * 16, MK_MAX = 3000;
    /** 自分の色（メイクの g〜y）：6けたの16進数 × 19まで */
    private static final Pattern MPL = Pattern.compile("(?:[0-9a-f]{6}){1,19}");
    private static final java.util.Set<String> MK_KEYS = java.util.Set.of("mk", "imk", "i2m", "i3m");

    /** ちぢめたメイクが、ちょうど cells マスになって、なにか描いてあるか */
    public static boolean mkOk(String s, int cells, int maxLen) {
        if (s == null || s.isEmpty() || s.length() > maxLen) return false;
        int n = 0, i = 0; boolean any = false;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z'))) return false;
            long run = 1;
            if (i < s.length() && s.charAt(i) >= 'A' && s.charAt(i) <= 'Z') {
                long v = 0;
                while (i < s.length() && s.charAt(i) >= 'A' && s.charAt(i) <= 'Z') { v = v * 26 + (s.charAt(i++) - 'A'); if (v > 100_000) return false; }
                run = v + 2;
            }
            if (c != '0') any = true;
            n += run; if (n > cells) return false;
        }
        return n == cells && any;
    }
    public static boolean mplOk(String s) { return s != null && MPL.matcher(s).matches(); }

    /** 見た目の設定：文字列の値だけ・72項目まで（v0.88.0 で64から。v0.87.0 で56から）（v0.86.0 で48から。v0.85.0 で40から。アイドルの見た目）・各20文字まで（メイクの paint は決まった形の90文字、pt2 は360文字、服のマイデザイン md は200文字、アイドルのメイク ipt は90文字・ip2 は360文字） */
    static ObjectNode cfg(JsonNode n, ObjectMapper om) {
        ObjectNode out = om.createObjectNode();
        if (n == null || !n.isObject()) return out;
        int count = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext() && count < 72; ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().length() > 12) continue;
            JsonNode v = e.getValue();
            if (MK_KEYS.contains(e.getKey())) { if (v.isTextual() && mkOk(v.asText(), MK_CELLS, MK_MAX)) { out.put(e.getKey(), v.asText()); count++; } continue; }
            if ("mpl".equals(e.getKey())) { if (v.isTextual() && mplOk(v.asText())) { out.put("mpl", v.asText()); count++; } continue; }
            if ("pt2".equals(e.getKey())) { if (v.isTextual() && PAINT2.matcher(v.asText()).matches()) { out.put("pt2", v.asText()); count++; } continue; }
            if ("md".equals(e.getKey())) { if (v.isTextual() && MYDESIGN.matcher(v.asText()).matches()) { out.put("md", v.asText()); count++; } continue; }
            if ("ip2".equals(e.getKey())) { if (v.isTextual() && PAINT2.matcher(v.asText()).matches()) { out.put("ip2", v.asText()); count++; } continue; }
            if ("ipt".equals(e.getKey())) { if (v.isTextual() && PAINT.matcher(v.asText()).matches()) { out.put("ipt", v.asText()); count++; } continue; }
            if ("paint".equals(e.getKey())) { if (v.isTextual() && PAINT.matcher(v.asText()).matches()) { out.put("paint", v.asText()); count++; } continue; }
            if (v.isTextual() && v.asText().length() <= 20) { out.put(e.getKey(), v.asText()); count++; }
            else if (v.isBoolean()) { out.put(e.getKey(), v.asBoolean()); count++; }
        }
        return out;
    }
}
