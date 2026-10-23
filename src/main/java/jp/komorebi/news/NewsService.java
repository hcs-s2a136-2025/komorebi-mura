package jp.komorebi.news;

import java.util.Iterator;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.casino.race.RaceService;
import jp.komorebi.clock.ClockService;
import jp.komorebi.world.SharedWorldService;

/**
 * 村の新聞。みんなが「きのうの自分のできごと」を送ってきたものを日ごとに取っておき、朝刊として配る。
 * 共有の村の "_news" に、最近7日ぶんだけ持つ（再起動しても消えない）。
 */
@Service
public class NewsService {

    private static final String KEY = "_news";
    private static final int KEEP_DAYS = 7, MAX_ITEMS = 12, MAX_TEXT = 90;

    private final SharedWorldService world;
    private final ObjectMapper om;
    private final ClockService clock;
    private final RaceService race;

    public NewsService(SharedWorldService world, ObjectMapper om, ClockService clock, RaceService race) {
        this.world = world;
        this.om = om;
        this.clock = clock;
        this.race = race;
    }

    private ObjectNode doc() {
        JsonNode cur = world.getSystem(KEY);
        return cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
    }

    /** きのう（か、その前）のできごとを受け取る */
    public synchronized boolean put(long playerId, String name, JsonNode msg) {
        int day = msg.path("day").asInt(0), today = clock.day();
        if (day < today - 2 || day > today || day < 1) return false;
        ObjectNode rep = om.createObjectNode();
        rep.put("name", clean(name, 16));
        ArrayNode items = rep.putArray("items");
        for (JsonNode it : msg.path("items")) {
            if (items.size() >= MAX_ITEMS) break;
            String t = clean(it.path("t").asText(""), MAX_TEXT);
            if (t.isEmpty()) continue;
            items.addObject().put("k", clean(it.path("k").asText(""), 12)).put("t", t).put("s", Math.max(0, Math.min(999, it.path("s").asInt(0))));
        }
        ObjectNode sum = rep.putObject("sum");
        for (String k : new String[] { "harvest", "fish", "chop", "mine", "cook", "money" }) {
            long v = msg.path("sum").path(k).asLong(0);
            sum.put(k, Math.max(0, Math.min(k.equals("money") ? 100_000_000L : 9999, v)));
        }
        ObjectNode d = doc();
        String dk = String.valueOf(day);
        ObjectNode byDay = d.get(dk) instanceof ObjectNode o ? o : d.putObject(dk);
        byDay.set(String.valueOf(playerId), rep);
        // 古い日を捨てる
        for (Iterator<String> it = d.fieldNames(); it.hasNext();) {
            String k = it.next();
            try { if (Integer.parseInt(k) < today - KEEP_DAYS) it.remove(); } catch (NumberFormatException e) { it.remove(); }
        }
        world.putSystem(KEY, d);
        return true;
    }

    /** その日の新聞の材料 */
    public synchronized ObjectNode get(int day) {
        ObjectNode out = om.createObjectNode();
        out.put("type", "news");
        out.put("day", day);
        ArrayNode reps = out.putArray("reports");
        JsonNode byDay = doc().path(String.valueOf(day));
        for (Iterator<Map.Entry<String, JsonNode>> it = byDay.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            ObjectNode r = e.getValue().deepCopy();
            r.put("id", e.getKey());
            reps.add(r);
        }
        out.set("race", om.valueToTree(race.daySummary(day)));
        return out;
    }

    static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}<>]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
