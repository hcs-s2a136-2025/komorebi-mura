package jp.komorebi.casino;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * {"type":"jp","add":30} … スロットを回したぶんの積み立て（賭け金の3%）。新しい額をみんなに配る。
 * 賭けた分が消えないように、大きい額も短い間隔の分も捨てない（v0.82.1。前は 30,000 をこえる分と、0.3秒以内の2回目を捨てていた）。
 * 送りすぎは、1人のメッセージぜんぶの制限（PlayerSession.allow）で止まる
 */
@Component
public class JackpotHandler implements MessageHandler {

    private final JackpotService jackpot;

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
        if (add <= 0) return;
        var o = hub.json().createObjectNode().put("type", "jp");
        JackpotService.put(o, "pot", "ps", jackpot.add(add));
        hub.broadcast(o, null);
    }
}
