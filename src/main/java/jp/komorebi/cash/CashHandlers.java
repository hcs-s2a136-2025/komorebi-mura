package jp.komorebi.cash;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * 現金まわりの WebSocket メッセージ。
 * {"type":"pochi","to":12,"n":5,"f":1,"u":0,"msg":"おめでとう","rid":"x"} … ポチ袋（f はわかっている偽札、u は知らずに持っていた偽札の枚数）。{"type":"pochiOk","rid","ok","text"} が返る
 * {"type":"fakeRep","origin":12,"n":2} … もらったお札から、その人が送った偽札が見つかった
 * {"type":"fake77","act":"pass","n":3} … お店で偽札が通った（村に出回る）
 * {"type":"fake77","act":"draw","n":10,"rid":"x"} … 払い戻しのお札のうち、偽札は何枚？ {"type":"fake77","rid","k"} が返る
 */
@Configuration
public class CashHandlers {

    private final Map<String, Long> last = new ConcurrentHashMap<>();

    private boolean slow(String key, long ms) {
        long now = System.currentTimeMillis();
        Long prev = last.put(key, now);
        return prev != null && now - prev < ms;
    }

    @Bean
    MessageHandler pochiHandler(CashService cash) {
        return new MessageHandler() {
            @Override public String type() { return "pochi"; }
            @Override public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
                Long id = from.playerId();
                if (id == null) return;
                var out = hub.json().createObjectNode().put("type", "pochiOk").put("rid", msg.path("rid").asText(""));
                if (slow("p" + id, 1500)) { hub.send(from, out.put("ok", false).put("text", "少し待ってから贈ってね")); return; }
                String err = cash.pochi(id, from.name(), msg.path("to").asLong(0), msg.path("n").asInt(0), msg.path("f").asInt(0), msg.path("u").asInt(0), msg.path("msg").asText(""));
                hub.send(from, err == null ? out.put("ok", true) : out.put("ok", false).put("text", err));
            }
        };
    }

    @Bean
    MessageHandler fakeReportHandler(CashService cash) {
        return new MessageHandler() {
            @Override public String type() { return "fakeRep"; }
            @Override public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
                Long id = from.playerId();
                if (id == null || slow("r" + id + ":" + msg.path("origin").asLong(0), 300)) return;
                cash.report(id, from.name(), msg.path("origin").asLong(0), msg.path("n").asInt(0));
            }
        };
    }

    @Bean
    MessageHandler fakePoolHandler(CashService cash) {
        return new MessageHandler() {
            @Override public String type() { return "fake77"; }
            @Override public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
                Long id = from.playerId();
                if (id == null) return;
                String act = msg.path("act").asText("");
                if ("pass".equals(act)) { if (!slow("s" + id, 500)) cash.pass(msg.path("n").asInt(0)); }
                else if ("draw".equals(act)) {
                    int k = slow("d" + id, 400) ? 0 : cash.draw(msg.path("n").asInt(0));
                    hub.send(from, hub.json().createObjectNode().put("type", "fake77").put("rid", msg.path("rid").asText("")).put("k", k));
                }
            }
        };
    }
}
