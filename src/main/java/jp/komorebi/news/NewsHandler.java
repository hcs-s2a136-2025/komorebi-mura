package jp.komorebi.news;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * {"type":"news","act":"put","day":3,"items":[{"k":"fish","t":"…","s":50}],"sum":{...}} … きのうの自分のできごとを送る
 * {"type":"news","act":"get","day":3} … その日の朝刊の材料をもらう
 */
@Component
public class NewsHandler implements MessageHandler {

    private final NewsService news;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public NewsHandler(NewsService news) {
        this.news = news;
    }

    @Override
    public String type() {
        return "news";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        long now = System.currentTimeMillis();
        Long prev = last.put(from.playerId(), now);
        if (prev != null && now - prev < 300) return;
        String act = msg.path("act").asText("");
        if ("put".equals(act)) news.put(from.playerId(), from.name(), msg);
        else if ("get".equals(act)) hub.send(from, news.get(msg.path("day").asInt(0)));
    }
}
