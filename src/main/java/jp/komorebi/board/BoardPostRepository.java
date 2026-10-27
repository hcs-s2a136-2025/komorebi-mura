package jp.komorebi.board;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardPostRepository extends JpaRepository<BoardPost, Long> {
    List<BoardPost> findTop100ByDeletedFalseOrderByCreatedAtDesc();

    List<BoardPost> findTop100ByVillageIdAndDeletedFalseOrderByCreatedAtDesc(Long villageId);
}
