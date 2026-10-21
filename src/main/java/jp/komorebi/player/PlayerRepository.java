package jp.komorebi.player;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByTokenHash(String tokenHash);

    /** 同じなまえの人（古いデータには同じなまえが何人かいることがある）。最近来た順 */
    List<Player> findByNameOrderByLastSeenDesc(String name);

    List<Player> findAllByOrderByLastSeenDesc();
}
