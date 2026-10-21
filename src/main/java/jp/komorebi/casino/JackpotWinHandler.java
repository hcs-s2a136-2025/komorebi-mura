package jp.komorebi.casino;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/** {"type":"jpWin"} … だれかがジャックポットを当てた。額を戻して、みんなにお知らせ */
@Component
public class JackpotWinHandler implements MessageHandler {

    private final JackpotService jackpot;

    public JackpotWinHandler(JackpotService jackpot) {
        this.jackpot = jackpot;
    }

    @Override
    public String type() {
        return "jpWin";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        long won = jackpot.win();
        hub.broadcast(hub.json().createObjectNode().put("type", "jp").put("pot", jackpot.pot())
            .put("won", won).put("by", from.playerId()).put("winner", from.name()), null);
    }
}
