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

    public GameSocketHandler(List<MessageHandler> handlerList, PlayerService players, ObjectMapper om, KomorebiProperties props, ObjectProvider<WelcomeContributor> contributors) {
        this.handlers = handlerList.stream().collect(Collectors.toMap(MessageHandler::type, Function.identity()));
        this.players = players;
        this.om = om;
        this.props = props;
        this.contributors = contributors;
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
        if (h != null) h.handle(s, msg, this);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        PlayerSession s = sessions.remove(session.getId());
        if (s != null && s.isAuthed() && !isOnline(s.playerId)) {
            ObjectNode m = om.createObjectNode().put("type", "leave").put("id", s.playerId);
            broadcast(m, null);
        }
    }

    private void hello(PlayerSession s, JsonNode msg) throws IOException {
        Optional<Player> p = players.findByToken(msg.path("token").asText(null));
        if (p.isEmpty()) {
            send(s, om.createObjectNode().put("type", "error").put("code", "auth").put("text", "もう一度参加してください"));
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
        s.playerId = p.get().getId();
        s.name = p.get().getName();
        String map = Sanitize.map(msg.get("map"));
        int x = Sanitize.coord(msg.get("x")), y = Sanitize.coord(msg.get("y"));
        s.moveTo(map == null ? "village" : map, Math.max(0, x), Math.max(0, y), Sanitize.dir(msg.get("dir")));
        s.setCfg(Sanitize.cfg(msg.get("cfg"), om));

        ObjectNode welcome = om.createObjectNode().put("type", "welcome").put("version", props.version());
        welcome.putObject("you").put("id", s.playerId).put("name", s.name);
        ArrayNode list = welcome.putArray("players");
        for (PlayerSession o : sessions.values()) if (o != s && o.isAuthed()) list.add(describe(o));
        contributors.orderedStream().forEach(c -> c.contribute(s, welcome));
        send(s, welcome);

        ObjectNode join = om.createObjectNode().put("type", "join");
        join.set("player", describe(s));
        broadcast(join, s);
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

    /** except 以外の、参加済みの全員に送る */
    public void broadcast(ObjectNode msg, PlayerSession except) {
        String text = msg.toString();
        for (PlayerSession o : sessions.values()) if (o != except && o.isAuthed()) sendRaw(o, text);
    }

    public void send(PlayerSession s, ObjectNode msg) {
        sendRaw(s, msg.toString());
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

    public boolean isOnline(Long playerId) {
        return sessions.values().stream().anyMatch(o -> playerId.equals(o.playerId));
    }

    public int onlineCount() {
        return (int) sessions.values().stream().filter(PlayerSession::isAuthed).count();
    }

    public record OnlinePlayer(Long id, String name, String map, int x, int y) {
    }

    public List<OnlinePlayer> onlinePlayers() {
        List<OnlinePlayer> out = new ArrayList<>();
        for (PlayerSession o : sessions.values()) if (o.isAuthed()) out.add(new OnlinePlayer(o.playerId, o.name, o.map, o.x, o.y));
        return out;
    }

    public void announce(String text) {
        broadcast(om.createObjectNode().put("type", "notice").put("text", text), null);
    }

    public void renamed(Long playerId, String name) {
        for (PlayerSession o : sessions.values()) if (playerId.equals(o.playerId)) o.name = name;
        broadcast(om.createObjectNode().put("type", "rename").put("id", playerId).put("name", name), null);
    }

    /** 管理人がお便りに返事したら、つないでいる本人に知らせる */
    @TransactionalEventListener
    public void onAdminReplied(AdminReplied e) {
        sendToPlayer(e.playerId(), om.createObjectNode().put("type", "feedbackReply").put("threadId", e.threadId()).put("title", e.title()));
    }
}
