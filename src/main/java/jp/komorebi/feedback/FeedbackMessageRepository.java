package jp.komorebi.feedback;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackMessageRepository extends JpaRepository<FeedbackMessage, Long> {
    List<FeedbackMessage> findByThreadOrderByCreatedAtAscIdAsc(FeedbackThread thread);
}
