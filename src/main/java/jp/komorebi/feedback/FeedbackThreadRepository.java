package jp.komorebi.feedback;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.komorebi.player.Player;

public interface FeedbackThreadRepository extends JpaRepository<FeedbackThread, Long> {
    List<FeedbackThread> findByPlayerOrderByUpdatedAtDesc(Player player);

    List<FeedbackThread> findAllByOrderByUpdatedAtDesc();

    long countByAdminUnreadTrue();

    long countByPlayerAndPlayerUnreadTrue(Player player);
}
