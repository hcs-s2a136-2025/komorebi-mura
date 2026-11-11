package jp.komorebi.world;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * 村を新しくしたあと（v0.78.0）、画面が木と岩を生やしなおしたら {"type":"regrow","gen":世代} を送る。
 * それで「まだ生やしていない」のしるし（_regrow）を消す。木と岩そのものは、ふつうの edits で届く。
 */
@Component
public class RegrowHandler implements MessageHandler {

    private final SharedWorldService world;

    public RegrowHandler(SharedWorldService world) {
        this.world = world;
    }

    @Override
    public String type() {
        return "regrow";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        world.regrowDone(msg.path("gen").asLong(0));
    }
}
