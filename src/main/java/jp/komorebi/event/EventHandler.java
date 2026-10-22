package jp.komorebi.event;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * {"type":"ev","act":"look|join|fish|collect|claim", ...} … イベント。
 * join: level=評判レベル／fish: fish=魚の名前, size=cm／claim: id=受け取ったイベント
 * 開催・終了・設定は管理人室（/admin/events、ログインが必要）からだけできる。ここからは参加と記録だけ。
 */
@Component
public class EventHandler implements MessageHandler {

    private final EventService events;

    public EventHandler(EventService events) {
        this.events = events;
    }

    @Override
    public String type() {
        return "ev";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        events.handle(from, msg);
    }
}
