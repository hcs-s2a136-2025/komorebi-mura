package jp.komorebi.house;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * {"type":"box","key":"48,21","box":"shared|priv","act":"put|take","id":"tomato","n":3,"rid":"abc"} … 家の収納箱に出し入れ。
 * うまくいったら本人に boxOk（このあとで本人の持ち物を増やす・減らす）、だめなら boxNg。
 * 中身が変わったら、同じ家の中にいる人に houseBox で新しい中身を配る。
 */
@Component
public class BoxHandler implements MessageHandler {

    private final HouseService houses;

    public BoxHandler(HouseService houses) {
        this.houses = houses;
    }

    @Override
    public String type() {
        return "box";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        String key = msg.path("key").asText(""), id = msg.path("id").asText(""), act = msg.path("act").asText("");
        String rid = msg.path("rid").asText("");
        if (rid.length() > 20) rid = rid.substring(0, 20);
        long n = msg.path("n").asLong(0), me = from.playerId();
        boolean priv = "priv".equals(msg.path("box").asText("shared"));
        if (!HouseBook.validKey(key) || !houses.exists(key)) { ng(hub, from, rid, "その家は見つからなかった"); return; }
        boolean owner = houses.isOwner(key, me);
        if (priv ? !owner : !houses.canEnter(key, me)) { ng(hub, from, rid, priv ? "鍵がかかっていて開かない" : "この家の箱は使えないみたい"); return; }
        // みんなの家の鍵つきの箱は、ひとりずつ別の箱
        String bookKey = priv && HouseService.HOME.equals(key) ? HouseService.personal(me) : key;
        long left;
        if ("put".equals(act)) left = houses.book().put(bookKey, priv, id, n);
        else if ("take".equals(act)) left = houses.book().take(bookKey, priv, id, n);
        else return;
        if (left < 0) { ng(hub, from, rid, "put".equals(act) ? "箱がいっぱいみたい" : "箱にそれだけ入っていないみたい"); return; }
        houses.save(bookKey);
        hub.send(from, hub.json().createObjectNode().put("type", "boxOk").put("rid", rid).put("act", act).put("id", id).put("n", n));
        ObjectNode mine = hub.json().createObjectNode().put("type", "houseBox").put("key", key);
        mine.setAll(houses.view(key, owner, me));
        mine.remove("layout");
        hub.send(from, mine);
        if (!bookKey.equals(key)) return;
        ObjectNode others = hub.json().createObjectNode().put("type", "houseBox").put("key", key);
        others.setAll(houses.view(key, false));
        others.remove("layout");
        hub.sendToMap("house:" + key, others, from);
    }

    private static void ng(GameSocketHandler hub, PlayerSession to, String rid, String text) {
        hub.send(to, hub.json().createObjectNode().put("type", "boxNg").put("rid", rid).put("text", text));
    }
}
