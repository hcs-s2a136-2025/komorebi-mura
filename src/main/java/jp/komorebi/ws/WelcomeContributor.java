package jp.komorebi.ws;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 参加したとき（welcome）に送る内容へ、機能ごとに情報を足すための入口。
 * 例：共有の村の地形、最近のチャット。実装した @Component を作るだけで自動で使われる。
 */
public interface WelcomeContributor {
    void contribute(PlayerSession session, ObjectNode welcome);
}
