package jp.komorebi.coin;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * スマホコインの WebSocket メッセージ（type は "coin"）。どの返事にも、そのあとの {"type":"coin","a":"state",...} がつづく。
 * {"type":"coin","a":"get"} … いまのようす
 * {"type":"coin","a":"buy","by":"g","n":5,"rid":"x"} … G で5コイン買う（"cash" なら n 枚のお札で）→ {"a":"buy","rid","ok","coins","text"}
 * {"type":"coin","a":"gacha","n":10,"rid":"x"} … ガチャ → {"a":"gacha","rid","ok","items":[...],"text"}
 * {"type":"coin","a":"spend","n":3,"what":"fc","rid":"x"} … コインを使う → {"a":"spend","rid","ok","what","text"}（"fc" なら "fc":[7日分の天気] もつく）
 * {"type":"coin","a":"earn","why":"login"} … 無料のコイン（login・bingo・rank）→ {"a":"earn","why","n"}
 * {"type":"coin","a":"tori","t":{name,sp,jp,st}} … 自分のにわとりを見せる／{"type":"coin","a":"toriList"} → {"a":"toriList","list":[...]}
 */
@Component
public class CoinHandler implements MessageHandler {

    private final CoinService coins;

    public CoinHandler(CoinService coins) {
        this.coins = coins;
    }

    @Override
    public String type() {
        return "coin";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        Long id = from.playerId();
        if (id == null || from.watcher()) return;
        String a = msg.path("a").asText("");
        if (!"get".equals(a) && coins.slow(id)) return;
        ObjectNode out = hub.json().createObjectNode().put("type", "coin").put("a", a).put("rid", msg.path("rid").asText(""));
        switch (a) {
            case "get" -> { }
            case "buy" -> {
                String by = msg.path("by").asText(""); int n = msg.path("n").asInt(0);
                String err = coins.buy(id, by, n);
                out.put("by", by).put("n", n).put("coins", "cash".equals(by) ? n * CoinService.COIN_PER_BILL : n);
                if (err == null) out.put("ok", true); else out.put("ok", false).put("text", err);
                hub.send(from, out);
            }
            case "gacha" -> {
                StringBuilder err = new StringBuilder();
                List<String> got = coins.gacha(id, msg.path("n").asInt(0), err);
                if (got == null) out.put("ok", false).put("text", err.toString());
                else { out.put("ok", true); var arr = out.putArray("items"); got.forEach(arr::add); }
                hub.send(from, out);
            }
            case "spend" -> {
                String what = msg.path("what").asText("");
                String err = coins.spend(id, msg.path("n").asInt(0), what);
                out.put("what", what);
                if (err == null) {
                    out.put("ok", true);
                    if ("fc".equals(what)) { var arr = out.putArray("fc"); coins.forecast().forEach(arr::add); }
                } else out.put("ok", false).put("text", err);
                hub.send(from, out);
            }
            case "earn" -> {
                String why = msg.path("why").asText("");
                out.put("why", why).put("n", coins.earn(id, why));
                hub.send(from, out);
            }
            case "tori" -> { coins.putTori(id, msg.path("t")); return; }
            case "toriList" -> { out.set("list", coins.toriList(id)); hub.send(from, out); return; }
            default -> { return; }
        }
        hub.send(from, coins.state(id));
    }
}
