package jp.komorebi.ws;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * WebSocketのメッセージ1種類ぶんの処理。
 * 新しい機能を足すときは、このインターフェースを実装した @Component を1つ作るだけでOK。
 * type() が返す文字列と同じ "type" のメッセージが届くと handle() が呼ばれる。
 */
public interface MessageHandler {
    String type();

    void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) throws Exception;
}
