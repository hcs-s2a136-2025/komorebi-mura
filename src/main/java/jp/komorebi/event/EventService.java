package jp.komorebi.event;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import jp.komorebi.event.EventRules.Score;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * 管理人が開くイベント。開催中のイベントは1つだけ（時間が重なったら、先に始まる方から順に）。
 * - 開始・終了は3秒ごとに確かめる。管理人室から「今すぐ開始」「終了」もできる
 * - 記録は event_score、ごほうびは event_reward（(イベント, 人) で1行なので二度は渡さない）
 * - ごほうびは本人に evReward で届け、本人が受け取ったら claim が来て claimed_at を書く。留守の人には次の welcome で届ける
 */
@Service
public class EventService implements WelcomeContributor {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() { };
    private static final long FISH_GAP_MS = 2500, COLLECT_GAP_MS = 700;

    /** 管理人室・ゲームに見せるイベントの中身 */
    public record Ev(long id, String name, String kind, String scoring, String rules, Instant startsAt, Instant endsAt,
                     int joinFee, int minLevel, Map<String, String> rewards, String status) {
        public String kindName() { return EventRules.KIND_NAMES.getOrDefault(kind, kind); }
        public String scoringName() { return EventRules.SCORING_NAMES.getOrDefault(scoring, scoring); }
    }

    public record Rank(int rank, long playerId, String name, long score) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final ObjectProvider<GameSocketHandler> hub;
    private Ev active;
    private final Map<Long, Score> scores = new HashMap<>();
    private final Map<Long, String> names = new HashMap<>();
    private final Map<Long, Long> lastAct = new HashMap<>();
    private boolean boardDirty;

    public EventService(JdbcTemplate jdbc, ObjectMapper om, ObjectProvider<GameSocketHandler> hub) {
        this.jdbc = jdbc;
        this.om = om;
        this.hub = hub;
    }

    @PostConstruct
    synchronized void load() {
        List<Ev> a = query("WHERE status = 'active' ORDER BY starts_at");
        if (!a.isEmpty()) setActive(a.get(0));
    }

    // ---------------------------------------------------------------- 管理人室

    public synchronized List<Ev> list() {
        return query("ORDER BY starts_at DESC");
    }

    public synchronized Ev find(long id) {
        List<Ev> l = query("WHERE id = ?", id);
        return l.isEmpty() ? null : l.get(0);
    }

    public synchronized List<Rank> ranking(long id, int limit) {
        Ev e = find(id);
        if (e == null) return List.of();
        List<Rank> out = new ArrayList<>();
        jdbc.query("SELECT player_id, name, score FROM event_score WHERE event_id = ? ORDER BY score DESC, updated_at ASC LIMIT " + Math.max(1, Math.min(200, limit)),
            rs -> { out.add(new Rank(out.size() + 1, rs.getLong(1), rs.getString(2), rs.getLong(3))); }, id);
        return out;
    }

    public synchronized int participants(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM event_score WHERE event_id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    /** イベントを作る。まちがいがあればその説明、うまくいけば null */
    public synchronized String create(String name, String kind, String scoring, String rules, Instant start, Instant end,
                                      int fee, int minLevel, Map<String, String> rewards) {
        if (name == null || name.isBlank() || name.length() > 40) return "イベント名は1〜40文字で入れてください";
        if (!EventRules.KINDS.contains(kind)) return "イベントの種類をえらんでください";
        scoring = EventRules.fixScoring(kind, scoring);
        if (start == null || end == null || !end.isAfter(start)) return "終わりは始まりより後にしてください";
        if (end.isAfter(start.plusSeconds(7L * 24 * 3600))) return "開催期間は7日までにしてください";
        if (end.isBefore(Instant.now())) return "終わりがもう過ぎています";
        if (fee < 0 || fee > 100000) return "参加費は0〜100000Gにしてください";
        if (minLevel < 0 || minLevel > 10) return "参加できる評判レベルは0〜10にしてください";
        if (rules != null && rules.length() > 1000) return "ルールの説明は1000文字までにしてください";
        Map<String, String> rw = new LinkedHashMap<>();
        for (String k : List.of("r1", "r2", "r3", "all", "goal")) {
            String v = rewards.getOrDefault(k, "").trim();
            if (!EventRules.validReward(v)) return "ごほうびの書き方がちがいます（" + k + "）：" + v;
            rw.put(k, v);
        }
        String gs = rewards.getOrDefault("goalScore", "0").trim();
        if (!gs.matches("\\d{1,7}")) return "達成ラインは数字で入れてください";
        rw.put("goalScore", gs);
        String json;
        try { json = om.writeValueAsString(rw); } catch (Exception ex) { return "ごほうびを保存できませんでした"; }
        jdbc.update("INSERT INTO game_event (name, kind, scoring, rules, starts_at, ends_at, join_fee, min_level, rewards, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'scheduled', ?)",
            name.trim(), kind, scoring, rules == null ? "" : rules.trim(), Timestamp.from(start), Timestamp.from(end), fee, minLevel, json, Timestamp.from(Instant.now()));
        tick();
        return null;
    }

    public synchronized String startNow(long id) {
        Ev e = find(id);
        if (e == null || !"scheduled".equals(e.status())) return "始められるのは、まだ始まっていないイベントだけです";
        if (active != null) return "いまは「" + active.name() + "」を開催中です。終わってから始めてください";
        Instant now = Instant.now();
        Instant end = e.endsAt().isAfter(now) ? e.endsAt() : now.plusSeconds(1800);
        jdbc.update("UPDATE game_event SET starts_at = ?, ends_at = ? WHERE id = ?", Timestamp.from(now), Timestamp.from(end), id);
        tick();
        return null;
    }

    public synchronized String endNow(long id) {
        Ev e = find(id);
        if (e == null || "ended".equals(e.status())) return "そのイベントは終わっています";
        jdbc.update("UPDATE game_event SET ends_at = ? WHERE id = ?", Timestamp.from(Instant.now()), id);
        if ("scheduled".equals(e.status())) { jdbc.update("UPDATE game_event SET status = 'ended' WHERE id = ?", id); return null; }
        tick();
        return null;
    }

    public synchronized String delete(long id) {
        Ev e = find(id);
        if (e == null) return "見つかりませんでした";
        if (!"scheduled".equals(e.status())) return "消せるのは、まだ始まっていないイベントだけです";
        jdbc.update("DELETE FROM game_event WHERE id = ?", id);
        return null;
    }

    // ---------------------------------------------------------------- 時間

    @Scheduled(fixedDelay = 3000)
    public synchronized void tick() {
        Instant now = Instant.now();
        if (active != null && !now.isBefore(active.endsAt())) finish(active);
        if (active == null) {
            List<Ev> due = query("WHERE status = 'scheduled' AND starts_at <= ? ORDER BY starts_at", Timestamp.from(now));
            for (Ev e : due) {
                if (!now.isBefore(e.endsAt())) { jdbc.update("UPDATE game_event SET status = 'ended' WHERE id = ?", e.id()); continue; }
                jdbc.update("UPDATE game_event SET status = 'active' WHERE id = ?", e.id());
                setActive(find(e.id()));
                broadcastState();
                log.info("イベント開始：{}", e.name());
                break;
            }
        }
        if (boardDirty && active != null) { boardDirty = false; broadcastBoard(); }
    }

    private void setActive(Ev e) {
        active = e;
        scores.clear(); names.clear(); lastAct.clear();
        if (e == null) return;
        jdbc.query("SELECT player_id, name, detail FROM event_score WHERE event_id = ?", rs -> {
            scores.put(rs.getLong(1), Score.load(rs.getString(3)));
            names.put(rs.getLong(1), rs.getString(2));
        }, e.id());
    }

    /** 終わったイベントの順位を決めて、ごほうびを渡す */
    private void finish(Ev e) {
        jdbc.update("UPDATE game_event SET status = 'ended' WHERE id = ?", e.id());
        List<Rank> rank = ranking(e.id(), 200);
        Map<String, String> rw = e.rewards();
        long goal = parseLong(rw.get("goalScore"));
        boolean ranked = !"none".equals(e.scoring());
        GameSocketHandler h = hub.getIfAvailable();
        for (Rank r : rank) {
            String spec = EventRules.combine(ranked && r.rank() <= 3 ? rw.get("r" + r.rank()) : "", rw.get("all"),
                goal > 0 && r.score() >= goal ? rw.get("goal") : "");
            try {
                jdbc.update("INSERT INTO event_reward (event_id, player_id, rank_no, reward, created_at) VALUES (?, ?, ?, ?, ?)",
                    e.id(), r.playerId(), ranked ? r.rank() : 0, spec, Timestamp.from(Instant.now()));
            } catch (DuplicateKeyException dup) {
                continue; // もう渡してある
            }
            if (h != null && h.isOnline(r.playerId())) h.sendToPlayer(r.playerId(), rewardMsg(e.id(), e.name(), ranked ? r.rank() : 0, rank.size(), spec));
        }
        log.info("イベント終了：{}（参加 {} 人）", e.name(), rank.size());
        active = null;
        scores.clear();
        broadcastState();
        // 結果をみんなに
        if (h != null) {
            ObjectNode m = om.createObjectNode().put("type", "evEnd").put("id", e.id()).put("name", e.name());
            ArrayNode top = m.putArray("top");
            for (Rank r : rank.subList(0, Math.min(3, rank.size()))) top.addObject().put("rank", r.rank()).put("name", r.name()).put("score", r.score());
            h.broadcast(m, null);
        }
    }

    private ObjectNode rewardMsg(long id, String name, int rank, int total, String spec) {
        return om.createObjectNode().put("type", "evReward").put("id", id).put("name", name).put("rank", rank).put("total", total).put("reward", spec);
    }

    // ---------------------------------------------------------------- ゲームから

    public synchronized void handle(PlayerSession from, JsonNode msg) {
        GameSocketHandler h = hub.getIfAvailable();
        if (h == null) return;
        long me = from.playerId();
        String act = msg.path("act").asText("");
        if ("claim".equals(act)) {
            jdbc.update("UPDATE event_reward SET claimed_at = ? WHERE event_id = ? AND player_id = ? AND claimed_at IS NULL", Timestamp.from(Instant.now()), msg.path("id").asLong(), me);
            return;
        }
        if ("look".equals(act)) { h.sendToPlayer(me, meMsg(me)); return; }
        Ev e = active;
        if (e == null) { h.sendToPlayer(me, om.createObjectNode().put("type", "evNg").put("text", "いまは開催中のイベントがありません")); return; }
        String activity = EventRules.activity(e.kind(), e.scoring());
        long now = System.currentTimeMillis();
        switch (act) {
            case "join" -> {
                if (scores.containsKey(me)) { h.sendToPlayer(me, meMsg(me)); return; }
                if (msg.path("level").asInt(0) < e.minLevel()) { h.sendToPlayer(me, om.createObjectNode().put("type", "evNg").put("text", "評判レベル" + e.minLevel() + "から参加できます")); return; }
                Score s = new Score();
                scores.put(me, s);
                names.put(me, from.name());
                jdbc.update("INSERT INTO event_score (event_id, player_id, name, score, detail, updated_at) VALUES (?, ?, ?, 0, ?, ?)", e.id(), me, from.name(), s.save(), Timestamp.from(Instant.now()));
                ObjectNode m = meMsg(me);
                m.put("joined", true).put("fee", e.joinFee());
                h.sendToPlayer(me, m);
                boardDirty = true;
            }
            case "fish", "collect" -> {
                Score s = scores.get(me);
                if (s == null || !act.equals(activity.equals("fish") ? "fish" : "collect") || "none".equals(activity)) return;
                long gap = "fish".equals(act) ? FISH_GAP_MS : COLLECT_GAP_MS;
                if (now - lastAct.getOrDefault(me, 0L) < gap) return;
                lastAct.put(me, now);
                if ("fish".equals(act)) { if (!EventRules.addFish(s, msg.path("fish").asText(""), msg.path("size").asInt(0))) return; }
                else s.collected++;
                jdbc.update("UPDATE event_score SET name = ?, score = ?, detail = ?, updated_at = ? WHERE event_id = ? AND player_id = ?",
                    from.name(), s.value(e.scoring()), s.save(), Timestamp.from(Instant.now()), e.id(), me);
                names.put(me, from.name());
                h.sendToPlayer(me, meMsg(me));
                boardDirty = true;
            }
            default -> { }
        }
    }

    /** つないだときに、開催中のイベントと、まだ受け取っていないごほうびを渡す */
    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        if (active != null) welcome.set("event", eventNode(active, session.playerId()));
        ArrayNode list = welcome.putArray("evRewards");
        jdbc.query("SELECT r.event_id, e.name, r.rank_no, r.reward, (SELECT COUNT(*) FROM event_score s WHERE s.event_id = r.event_id) FROM event_reward r JOIN game_event e ON e.id = r.event_id WHERE r.player_id = ? AND r.claimed_at IS NULL",
            rs -> { list.add(rewardMsg(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(5), rs.getString(4))); }, session.playerId());
    }

    private ObjectNode meMsg(long me) {
        ObjectNode m = om.createObjectNode().put("type", "evMe");
        if (active == null) { m.putNull("event"); return m; }
        m.set("event", eventNode(active, me));
        return m;
    }

    private ObjectNode eventNode(Ev e, long me) {
        ObjectNode n = om.createObjectNode().put("id", e.id()).put("name", e.name()).put("kind", e.kind()).put("kindName", e.kindName())
            .put("scoring", e.scoring()).put("scoringName", e.scoringName()).put("activity", EventRules.activity(e.kind(), e.scoring()))
            .put("rules", e.rules()).put("startsAt", e.startsAt().toEpochMilli()).put("endsAt", e.endsAt().toEpochMilli()).put("now", System.currentTimeMillis())
            .put("joinFee", e.joinFee()).put("minLevel", e.minLevel());
        n.set("rewards", om.valueToTree(e.rewards()));
        Score s = scores.get(me);
        n.put("joined", s != null);
        if (s != null) {
            n.put("score", s.value(e.scoring())).put("count", s.count).put("points", s.points).put("kinds", s.kinds.size()).put("best", s.best).put("collected", s.collected);
        }
        n.set("top", topNode(e));
        n.put("people", scores.size());
        return n;
    }

    private ArrayNode topNode(Ev e) {
        List<Map.Entry<Long, Score>> l = new ArrayList<>(scores.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue().value(e.scoring()), a.getValue().value(e.scoring())));
        ArrayNode top = om.createArrayNode();
        for (int i = 0; i < Math.min(10, l.size()); i++) top.addObject().put("name", names.getOrDefault(l.get(i).getKey(), "?")).put("score", l.get(i).getValue().value(e.scoring()));
        return top;
    }

    private void broadcastBoard() {
        GameSocketHandler h = hub.getIfAvailable();
        if (h == null || active == null) return;
        ObjectNode m = om.createObjectNode().put("type", "evBoard").put("id", active.id()).put("people", scores.size());
        m.set("top", topNode(active));
        h.broadcast(m, null);
    }

    private void broadcastState() {
        GameSocketHandler h = hub.getIfAvailable();
        if (h == null) return;
        ObjectNode m = om.createObjectNode().put("type", "evState");
        if (active == null) m.putNull("event");
        else m.set("event", eventNode(active, -1));
        h.broadcast(m, null);
    }

    // ---------------------------------------------------------------- DB

    private List<Ev> query(String where, Object... args) {
        List<Ev> out = new ArrayList<>();
        jdbc.query("SELECT id, name, kind, scoring, rules, starts_at, ends_at, join_fee, min_level, rewards, status FROM game_event " + where, rs -> {
            Map<String, String> rw;
            try { rw = om.readValue(rs.getString(10), MAP); } catch (Exception ex) { rw = new LinkedHashMap<>(); }
            out.add(new Ev(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(), rs.getInt(8), rs.getInt(9), rw, rs.getString(11)));
        }, args);
        return out;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s == null ? "0" : s.trim()); } catch (Exception e) { return 0; }
    }
}
