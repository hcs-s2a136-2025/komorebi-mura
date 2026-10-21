package jp.komorebi.casino;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/** {"type":"jp","add":30} … スロットを回したぶんの積み立て。新しい額をみんなに配る */
@Component
public class JackpotHandler implements MessageHandler {

    private final JackpotService jackpot;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public JackpotHandler(JackpotService jackpot) {
        this.jackpot = jackpot;
    }

    @Override
    public String type() {
        return "jp";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        long add = msg.path("add").asLong(0);
        if (add <= 0 || add > 30_000) return;
        long now = System.currentTimeMillis();
        Long prev = last.put(from.playerId(), now);
        if (prev != null && now - prev < 300) return;
        long pot = jackpot.add(add);
        hub.broadcast(hub.json().createObjectNode().put("type", "jp").put("pot", pot), null);
    }
}
