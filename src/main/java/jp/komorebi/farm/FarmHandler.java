package jp.komorebi.farm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/** {"type":"farm","crops":[[x,y,"tomato",2]],"animals":[["cow","ハナ",x,y]],"pets":[["cat",3,"こむぎ"]]} … 自分の畑・家畜・ペットのようす（ほかの人は見るだけ） */
@Component
public class FarmHandler implements MessageHandler {

    private final FarmService farms;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public FarmHandler(FarmService farms) {
        this.farms = farms;
    }

    @Override
    public String type() {
        return "farm";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null) return;
        long now = System.currentTimeMillis();
        Long prev = last.put(from.playerId(), now);
        if (prev != null && now - prev < 1500) return;
        hub.broadcast(farms.put(from.playerId(), from.name(), msg), from);
    }
}
