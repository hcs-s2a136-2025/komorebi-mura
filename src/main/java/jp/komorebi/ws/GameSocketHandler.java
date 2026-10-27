package jp.komorebi.ws;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.config.KomorebiProperties;
import jp.komorebi.feedback.FeedbackViews.AdminReplied;
import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.village.VillageService;
import jp.komorebi.village.Villages;
import jp.komorebi.village.WatchService;

/**
 * ゲームのリアルタイム通信の窓口。
 * 1. つないだら最初に {"type":"hello","token":...} を送ってもらって本人確認
 * 2. それ以降のメッセージは "type" を見て MessageHandler に振り分ける
 */
@Component
public class GameSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameSocketHandler.class);

    private final Map<String, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, MessageHandler> handlers;
    private final PlayerService players;
    private final ObjectMapper om;
    private final KomorebiProperties props;
    private final ObjectProvider<WelcomeContributor> contributors;
    private final VillageService villages;
    private final WatchService watch;
    /** 見学している人が送ってもいいメッセージ（村には何も届かない・何も変わらないもの） */
    private static final java.util.Set<String> WATCH_OK = java.util.Set.of("ping", "house");

    public GameSocketHandler(List<MessageHandler> handlerList, PlayerService players, ObjectMapper om, KomorebiProperties props, ObjectProvider<WelcomeContributor> contributors,
                             VillageService villages, WatchService watch) {
        this.handlers = handlerList.stream().collect(Collectors.toMap(MessageHandler::type, Function.identity()));
        this.players = players;
        this.om = om;
        this.props = props;
        this.contributors = contributors;
        this.villages = villages;
        this.watch = watch;
        log.info("WebSocketのメッセージ種類: {}", handlers.keySet());
    }

    // ---------- 接続まわり ----------

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5000, 2 * 1024 * 1024);
        sessions.put(session.getId(), new PlayerSession(safe));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        PlayerSession s = sessions.get(session.getId());
        if (s == null || message.getPayloadLength() > 64 * 1024 || !s.allow(30)) return;
        JsonNode msg;
        try {
            msg = om.readTree(message.getPayload());
        } catch (IOException e) {
            return;
        }
        String type = msg.path("type").asText("");
        if (!s.isAuthed()) {
            if ("hello".equals(type)) hello(s, msg);
            return;
        }
        MessageHandler h = handlers.get(type);
        if (h == null) return;
        if (s.watcher && !WATCH_OK.contains(type)) return;
        if (s.watcher && "house".equals(type) && !"get".equals(msg.path("act").asText(""))) return;
        Exception[] err = new Exception[1];
        Villages.run(s.village, () -> { try { h.handle(s, msg, this); } catch (Exception e) { err[0] = e; } });
        if (err[0] != null) throw err[0];
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        PlayerSession s = sessions.remove(session.getId());
        if (s != null && s.isAuthed() && !s.watcher && !isOnline(s.playerId)) {
            ObjectNode m = om.createObjectNode().put("type", "leave").put("id", s.playerId);
            broadcastTo(s.village, m, null);
        }
    }

    private void hello(PlayerSession s, JsonNode msg) throws IOException {
        String tok = msg.path("token").asText(null);
        if (WatchService.isWatchToken(tok)) { helloWatch(s, msg, tok); return; }
        Optional<Player> p = players.findByToken(tok);
        if (p.isEmpty()) {
            boolean gone = players.isDeletedToken(msg.path("token").asText(null));
            send(s, om.createObjectNode().put("type", "error").put("code", gone ? "deleted" : "auth").put("text", gone ? "このキャラクターは削除されました" : "もう一度参加してください"));
            s.ws.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        // 同じ人が別の画面でつないでいたら、古いほうを閉じる
        for (PlayerSession o : sessions.values()) {
            if (o != s && p.get().getId().equals(o.playerId)) {
                send(o, om.createObjectNode().put("type", "notice").put("text", "別の画面でつながったので、こちらは切れました"));
                o.playerId = null;
                o.ws.close(CloseStatus.NORMAL);
            }
        }
        s.village = p.get().getVillageId();
        villages.remember(p.get().getId(), s.village);
        s.playerId = p.get().getId();
        s.name = p.get().getName();
        String map = Sanitize.map(msg.get("map"));
        int x = Sanitize.coord(msg.get("x")), y = Sanitize.coord(msg.get("y"));
        s.moveTo(map == null ? "village" : map, Math.max(0, x), Math.max(0, y), Sanitize.dir(msg.get("dir")));
        s.setCfg(Sanitize.cfg(msg.get("cfg"), om));
        Villages.run(s.village, () -> {
            send(s, welcome(s));
            ObjectNode join = om.createObjectNode().put("type", "join");
            join.set("player", describe(s));
            broadcast(join, s);
        });
    }

    /** 管理人の見学：見えない人として、その村につなぐ */
    private void helloWatch(PlayerSession s, JsonNode msg, String tok) throws IOException {
        long v = watch.village(tok);
        if (v < 0) {
            send(s, om.createObjectNode().put("type", "error").put("code", "watch").put("text", "見学の期限が切れたみたい。管理人室からもう一度「見学する」を押してね"));
            s.ws.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        s.watcher = true;
        s.village = v;
        s.playerId = watch.newWatcherId();
        s.name = "管理人（見学）";
        s.moveTo("village", 0, 0, "down");
        Villages.run(v, () -> send(s, welcome(s)));
    }

    private ObjectNode welcome(PlayerSession s) {
        ObjectNode welcome = om.createObjectNode().put("type", "welcome").put("version", props.version());
        welcome.putObject("you").put("id", s.playerId).put("name", s.name);
        villages.find(s.village).ifPresent(v -> {
            ObjectNode vn = welcome.putObject("village").put("id", v.id()).put("name", v.name()).put("main", v.main());
            if (!v.main() && !s.watcher) vn.put("code", v.code()).put("owner", v.ownerId() != null && v.ownerId().equals(s.playerId));
        });
        if (s.watcher) welcome.put("watch", true);
        ArrayNode list = welcome.putArray("players");
        for (PlayerSession o : sessions.values()) if (o != s && o.isAuthed() && !o.watcher && o.village == s.village) list.add(describe(o));
        contributors.orderedStream().forEach(c -> c.contribute(s, welcome));
        return welcome;
    }

    // ---------- ハンドラーやサービスから使う道具 ----------

    public ObjectNode describe(PlayerSession s) {
        ObjectNode n = om.createObjectNode().put("id", s.playerId).put("name", s.name).put("map", s.map)
            .put("x", s.x).put("y", s.y).put("dir", s.dir);
        n.set("cfg", s.cfg == null ? om.createObjectNode() : s.cfg);
        return n;
    }

    public ObjectMapper json() {
        return om;
    }

    /** except 以外の、参加済みの全員に送る。いまの村が決まっていれば（{@link Villages#scoped()}）、その村の人にだけ */
    public void broadcast(ObjectNode msg, PlayerSession except) {
        Long v = Villages.scoped();
        if (v != null) { broadcastTo(v, msg, except); return; }
        broadcastAll(msg, except);
    }

    /** その村の人にだけ送る */
    public void broadcastTo(long village, ObjectNode msg, PlayerSession except) {
        String text = msg.toString();
        for (PlayerSession o : sessions.values()) if (o != except && o.isAuthed() && o.village == village) sendRaw(o, text);
    }

    /** 全部の村の人に送る（管理人からのお知らせなど） */
    public void broadcastAll(ObjectNode msg, PlayerSession except) {
        String text = msg.toString();
        for (PlayerSession o : sessions.values()) if (o != except && o.isAuthed()) sendRaw(o, text);
    }

    public void send(PlayerSession s, ObjectNode msg) {
        sendRaw(s, msg.toString());
    }

    /** 同じ場所（map）にいる人にだけ送る。例：同じ家の中にいる人 */
    public void sendToMap(String map, ObjectNode msg, PlayerSession except) {
        String text = msg.toString();
        long v = Villages.current();
        for (PlayerSession o : sessions.values()) if (o != except && o.isAuthed() && o.village == v && map.equals(o.map())) sendRaw(o, text);
    }

    /** 家の中にいる人みんなへ（上の階 "house:キー_f2" にいる人もふくむ）。本人（except）には送らない */
    public void sendToHouse(String key, ObjectNode msg, PlayerSession except) {
        String text = msg.toString(), base = "house:" + key;
        long v = Villages.current();
        for (PlayerSession o : sessions.values()) {
            if (o == except || !o.isAuthed() || o.village != v || o.map() == null) continue;
            String m = o.map();
            if (m.equals(base) || (m.startsWith(base + "_f") && m.length() == base.length() + 3)) sendRaw(o, text);
        }
    }

    public void sendToPlayer(Long playerId, ObjectNode msg) {
        String text = msg.toString();
        for (PlayerSession o : sessions.values()) if (playerId.equals(o.playerId)) sendRaw(o, text);
    }

    private void sendRaw(PlayerSession s, String text) {
        try {
            if (s.ws.isOpen()) s.ws.sendMessage(new TextMessage(text));
        } catch (Exception e) {
            log.debug("送信できなかった: {}", e.getMessage());
        }
    }

    /** 削除した人がつないでいたら、知らせて切る（その端末のデータも消してもらう） */
    public void kickDeleted(Long playerId) {
        String text = om.createObjectNode().put("type", "error").put("code", "deleted").put("text", "このキャラクターは削除されました").toString();
        for (PlayerSession o : sessions.values()) {
            if (!playerId.equals(o.playerId)) continue;
            sendRaw(o, text);
            o.playerId = null;
            try { o.ws.close(CloseStatus.NORMAL); } catch (Exception e) { /* もう切れている */ }
        }
        ObjectNode m = om.createObjectNode().put("type", "leave").put("id", playerId);
        broadcastTo(villages.of(playerId), m, null);
    }

    public boolean isOnline(Long playerId) {
        return playerId != null && sessions.values().stream().anyMatch(o -> !o.watcher && playerId.equals(o.playerId));
    }

    /** いまの村（{@link Villages#current()}）でつないでいるか */
    public boolean onlineHere(Long playerId) {
        long v = Villages.current();
        return playerId != null && sessions.values().stream().anyMatch(o -> !o.watcher && o.village == v && playerId.equals(o.playerId));
    }

    /** つないでいる人の数（全部の村。見学の人は数えない） */
    public int onlineCount() {
        return (int) sessions.values().stream().filter(o -> o.isAuthed() && !o.watcher).count();
    }

    /** その村でつないでいる人の数 */
    public int onlineCount(long village) {
        return (int) sessions.values().stream().filter(o -> o.isAuthed() && !o.watcher && o.village == village).count();
    }

    public record OnlinePlayer(Long id, String name, String map, int x, int y, long village) {
    }

    /** いまの村でつないでいる人（村が決まっていなければ全部の村） */
    public List<OnlinePlayer> onlinePlayers() {
        Long v = Villages.scoped();
        List<OnlinePlayer> out = new ArrayList<>();
        for (PlayerSession o : sessions.values()) if (o.isAuthed() && !o.watcher && (v == null || o.village == v)) out.add(new OnlinePlayer(o.playerId, o.name, o.map, o.x, o.y, o.village));
        return out;
    }

    /** お知らせ。いまの村が決まっていればその村だけ、決まっていなければ全部の村 */
    public void announce(String text) {
        broadcast(om.createObjectNode().put("type", "notice").put("text", text), null);
    }

    public void announceAll(String text) {
        broadcastAll(om.createObjectNode().put("type", "notice").put("text", text), null);
    }

    public void renamed(Long playerId, String name) {
        for (PlayerSession o : sessions.values()) if (playerId.equals(o.playerId)) o.name = name;
        broadcastTo(villages.of(playerId), om.createObjectNode().put("type", "rename").put("id", playerId).put("name", name), null);
    }

    /** 村の名前が変わったら、その村の人に知らせる */
    public void villageChanged(long village) {
        villages.find(village).ifPresent(v -> {
            for (PlayerSession o : sessions.values()) {
                if (!o.isAuthed() || o.watcher || o.village != village) continue;
                ObjectNode m = om.createObjectNode().put("type", "village");
                m.putObject("village").put("id", v.id()).put("name", v.name()).put("main", v.main()).put("code", v.code()).put("owner", v.ownerId() != null && v.ownerId().equals(o.playerId));
                send(o, m);
            }
        });
    }

    /** 管理人がお便りに返事したら、つないでいる本人に知らせる */
    @TransactionalEventListener
    public void onAdminReplied(AdminReplied e) {
        sendToPlayer(e.playerId(), om.createObjectNode().put("type", "feedbackReply").put("threadId", e.threadId()).put("title", e.title()));
    }
}
