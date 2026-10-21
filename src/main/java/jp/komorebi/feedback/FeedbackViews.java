package jp.komorebi.feedback;

import java.time.Instant;
import java.util.List;

/** 画面やAPIに返すための形（エンティティをそのまま外に出さない） */
public final class FeedbackViews {
    private FeedbackViews() {
    }

    public record MessageView(String sender, String body, Instant createdAt) {
        static MessageView of(FeedbackMessage m) {
            return new MessageView(m.getSender().name(), m.getBody(), m.getCreatedAt());
        }
    }

    public record ThreadView(Long id, String category, String title, String status, String context,
                             boolean playerUnread, boolean adminUnread, Instant createdAt, Instant updatedAt,
                             Long playerId, String playerName, List<MessageView> messages) {
    }

    /** お便りに管理人が返事したときに出すイベント（WebSocketで本人に知らせる） */
    public record AdminReplied(Long playerId, Long threadId, String title) {
    }
}
