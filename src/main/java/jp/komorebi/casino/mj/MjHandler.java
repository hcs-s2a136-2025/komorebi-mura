package jp.komorebi.casino.mj;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * {"type":"mj","act":"list|create|sit|start|leave|look|discard|tsumo|kan|kyuushu|ron|pon|minkan|chi|pass|next","a":0,"b":0} … みんなの麻雀。
 * create: a=1で半荘戦（0は東風戦）、b=レート（0ノーレート〜4デカピン）／sit: a=卓の番号／discard: a=牌、b=1で立直／kan: a=牌の種類／chi: a=候補の番号
 */
@Component
public class MjHandler implements MessageHandler {

    private final MjTableService table;

    public MjHandler(MjTableService table) {
        this.table = table;
    }

    @Override
    public String type() {
        return "mj";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        table.command(from, msg);
    }
}
