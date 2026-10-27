package jp.komorebi.phone;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.world.SharedWorldService;

/**
 * スマホのゲームのスコアランキング（いちばんいいスコア）。全期間と、今日をふくむ7日間（日本時間）。
 * 共有の村の "_gamerank" に {ゲームID: {プレイヤーID: {b: 全期間のベスト, d: {"2026-10-27": その日のベスト}}}} で持つ。
 */
@Service
public class GameRankService {

    static final String KEY = "_gamerank";
    static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    static final Pattern GAME = Pattern.compile("[a-z0-9]{1,12}");
    public static final long MAX_SCORE = 100_000_000L;
    static final int TOP = 10, WEEK = 7;

    private final SharedWorldService world;
    private final PlayerService players;
    private final ObjectMapper om;

    public GameRankService(SharedWorldService world, PlayerService players, ObjectMapper om) {
        this.world = world;
        this.players = players;
        this.om = om;
    }

    public static boolean validGame(String g) {
        return g != null && GAME.matcher(g).matches();
    }

    public synchronized void record(String game, long playerId, long score) {
        if (!validGame(game) || score <= 0) return;
        long s = Math.min(MAX_SCORE, score);
        ObjectNode all = data();
        ObjectNode g = all.path(game) instanceof ObjectNode o ? o : all.putObject(game);
        String key = String.valueOf(playerId);
        ObjectNode e = g.path(key) instanceof ObjectNode o ? o : g.putObject(key);
        if (s > e.path("b").asLong(0)) e.put("b", s);
        ObjectNode d = e.path("d") instanceof ObjectNode o ? o : e.putObject("d");
        LocalDate now = LocalDate.now(JST);
        String day = now.toString();
        if (s > d.path(day).asLong(0)) d.put(day, s);
        String oldest = now.minusDays(WEEK).toString();
        for (Iterator<String> it = d.fieldNames(); it.hasNext(); ) if (it.next().compareTo(oldest) < 0) it.remove();
        world.putSystem(KEY, all);
    }

    private record Row(long id, String name, long v) { }

    /** {all:[{name,v,me?}], week:[...], me:{all, week, allRank, weekRank}, from, to} */
    public synchronized ObjectNode board(String game, Long me) {
        ObjectNode out = om.createObjectNode();
        LocalDate now = LocalDate.now(JST);
        String from = now.minusDays(WEEK - 1).toString(), to = now.toString();
        Map<Long, String> names = new HashMap<>();
        for (Player p : players.listAll()) names.put(p.getId(), p.getName());
        List<Row> a = new ArrayList<>(), w = new ArrayList<>();
        JsonNode g = validGame(game) ? data().path(game) : null;
        if (g != null && g.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = g.fields(); it.hasNext(); ) {
                var f = it.next();
                long id;
                try { id = Long.parseLong(f.getKey()); } catch (NumberFormatException ex) { continue; }
                String name = names.get(id);
                if (name == null) continue;
                long b = f.getValue().path("b").asLong(0), wk = 0;
                for (Iterator<Map.Entry<String, JsonNode>> dt = f.getValue().path("d").fields(); dt.hasNext(); ) {
                    var x = dt.next();
                    if (x.getKey().compareTo(from) >= 0 && x.getKey().compareTo(to) <= 0) wk = Math.max(wk, x.getValue().asLong(0));
                }
                if (b > 0) a.add(new Row(id, name, b));
                if (wk > 0) w.add(new Row(id, name, wk));
            }
        }
        Comparator<Row> cmp = Comparator.comparingLong(Row::v).reversed().thenComparingLong(Row::id);
        a.sort(cmp); w.sort(cmp);
        ObjectNode mine = out.putObject("me");
        fill(out.putArray("all"), a, me, mine, "all");
        fill(out.putArray("week"), w, me, mine, "week");
        out.put("game", game).put("from", from).put("to", to);
        return out;
    }

    private static void fill(ArrayNode arr, List<Row> rows, Long me, ObjectNode mine, String tag) {
        long v = 0; int rank = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean isMe = me != null && r.id() == me;
            if (isMe) { v = r.v(); rank = i + 1; }
            if (i < TOP) { ObjectNode o = arr.addObject().put("name", r.name()).put("v", r.v()); if (isMe) o.put("me", true); }
        }
        mine.put(tag, v).put(tag + "Rank", rank);
    }

    private ObjectNode data() {
        JsonNode cur = world.getSystem(KEY);
        return cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
    }
}
