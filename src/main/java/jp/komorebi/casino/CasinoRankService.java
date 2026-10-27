package jp.komorebi.casino;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.world.SharedWorldService;

/**
 * カジノの番付（収益と損失のランキング）。
 * 全期間は、その人のセーブにある「ギャンブル収支」（gambleNet）をそのまま使う。
 * 1週間は、届いた勝ち負けを日本時間の日ごとに足しておき、今日をふくむ7日分を合わせる。
 * 共有の村の "_casinorank" に {プレイヤーID: {a: 全期間, d: {"2026-10-27": その日の収支, ...}}} で持つ（"_" で始まるのでプレイヤーには配られない）。
 */
@Service
public class CasinoRankService {

    static final String KEY = "_casinorank";
    static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    static final long MAX_TOTAL = 1_000_000_000_000L;
    static final long MAX_ADD = 100_000_000L;
    public static final int WEEK_DAYS = 7;
    static final int TOP = 10;

    private final SharedWorldService world;
    private final PlayerService players;
    private final ObjectMapper om;

    private final jp.komorebi.village.VillageService villages;

    public CasinoRankService(SharedWorldService world, PlayerService players, ObjectMapper om, jp.komorebi.village.VillageService villages) {
        this.world = world;
        this.players = players;
        this.om = om;
        this.villages = villages;
    }

    static LocalDate today() {
        return LocalDate.now(JST);
    }

    /** total：その人の全期間の収支（null なら変えない）。add：前に送ってから増えた（減った）分 */
    public synchronized void record(long playerId, Long total, long add) {
        ObjectNode all = data();
        String key = String.valueOf(playerId);
        ObjectNode e = all.path(key) instanceof ObjectNode o ? o : all.putObject(key);
        if (total != null) e.put("a", Math.max(-MAX_TOTAL, Math.min(MAX_TOTAL, total)));
        long a = Math.max(-MAX_ADD, Math.min(MAX_ADD, add));
        ObjectNode d = e.path("d") instanceof ObjectNode o ? o : e.putObject("d");
        LocalDate now = today();
        if (a != 0) {
            String day = now.toString();
            d.put(day, Math.max(-MAX_TOTAL, Math.min(MAX_TOTAL, d.path(day).asLong(0) + a)));
        }
        // 8日より前の日は捨てる
        String oldest = now.minusDays(WEEK_DAYS).toString();
        for (Iterator<String> it = d.fieldNames(); it.hasNext(); ) if (it.next().compareTo(oldest) < 0) it.remove();
        world.putSystem(KEY, all);
    }

    /** 今日をふくむ7日分の収支 */
    static long week(JsonNode e, LocalDate now) {
        String from = now.minusDays(WEEK_DAYS - 1).toString(), to = now.toString();
        long s = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = e.path("d").fields(); it.hasNext(); ) {
            var f = it.next();
            if (f.getKey().compareTo(from) >= 0 && f.getKey().compareTo(to) <= 0) s += f.getValue().asLong(0);
        }
        return s;
    }

    private record Row(long id, String name, long v) { }

    /** {all:{win:[{name,v}],lose:[...]}, week:{...}, me:{all,week,allWin,allLose,weekWin,weekLose}} （順位は 0 なら圏外）。いまの村だけ */
    public synchronized ObjectNode board(Long me) {
        return board(me, false);
    }

    /** everywhere なら全部の村をあわせる（なまえのうしろに村の名前） */
    public synchronized ObjectNode board(Long me, boolean everywhere) {
        LocalDate now = today();
        List<Row> a = new ArrayList<>(), w = new ArrayList<>();
        if (everywhere && villages != null) {
            Map<Long, List<Player>> by = new HashMap<>();
            for (Player p : players.listEverywhere()) by.computeIfAbsent(p.getVillageId(), k -> new ArrayList<>()).add(p);
            for (var v : villages.list()) {
                JsonNode d = world.getSystemIn(v.id(), KEY);
                if (!(d instanceof ObjectNode o)) continue;
                Map<Long, String> names = new HashMap<>();
                for (Player p : by.getOrDefault(v.id(), List.of())) names.put(p.getId(), p.getName() + "（" + v.name() + "）");
                rows(o, names, now, a, w);
            }
        } else {
            Map<Long, String> names = new HashMap<>();
            for (Player p : players.listAll()) names.put(p.getId(), p.getName());
            rows(data(), names, now, a, w);
        }
        ObjectNode out = om.createObjectNode();
        ObjectNode mine = out.putObject("me");
        put(out.putObject("all"), a, me, mine, "all");
        put(out.putObject("week"), w, me, mine, "week");
        out.put("from", now.minusDays(WEEK_DAYS - 1).toString()).put("to", now.toString()).put("scope", everywhere ? "everywhere" : "village");
        return out;
    }

    private static void rows(ObjectNode all, Map<Long, String> names, LocalDate now, List<Row> a, List<Row> w) {
        for (Iterator<Map.Entry<String, JsonNode>> it = all.fields(); it.hasNext(); ) {
            var f = it.next();
            long id;
            try { id = Long.parseLong(f.getKey()); } catch (NumberFormatException ex) { continue; }
            String name = names.get(id);
            if (name == null) continue;
            a.add(new Row(id, name, f.getValue().path("a").asLong(0)));
            w.add(new Row(id, name, week(f.getValue(), now)));
        }
    }

    private void put(ObjectNode o, List<Row> rows, Long me, ObjectNode mine, String tag) {
        List<Row> win = rows.stream().filter(r -> r.v() > 0).sorted(Comparator.comparingLong(Row::v).reversed().thenComparingLong(Row::id)).toList();
        List<Row> lose = rows.stream().filter(r -> r.v() < 0).sorted(Comparator.comparingLong(Row::v).thenComparingLong(Row::id)).toList();
        fill(o.putArray("win"), win, me);
        fill(o.putArray("lose"), lose, me);
        long v = rows.stream().filter(r -> me != null && r.id() == me).mapToLong(Row::v).findFirst().orElse(0);
        mine.put(tag, v);
        mine.put(tag + "Win", rank(win, me));
        mine.put(tag + "Lose", rank(lose, me));
    }

    private static int rank(List<Row> rows, Long me) {
        for (int i = 0; i < rows.size(); i++) if (me != null && rows.get(i).id() == me) return i + 1;
        return 0;
    }

    private static void fill(ArrayNode arr, List<Row> rows, Long me) {
        for (int i = 0; i < rows.size() && i < TOP; i++) {
            ObjectNode r = arr.addObject().put("name", rows.get(i).name()).put("v", rows.get(i).v());
            if (me != null && rows.get(i).id() == me) r.put("me", true);
        }
    }

    private ObjectNode data() {
        JsonNode cur = world.getSystem(KEY);
        return cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
    }
}
