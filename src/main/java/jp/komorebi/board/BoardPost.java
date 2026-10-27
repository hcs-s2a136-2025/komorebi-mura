package jp.komorebi.board;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 伝言板の書き込み1件 */
@Entity
@Table(name = "board_post")
public class BoardPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(nullable = false, length = 16)
    private String name;

    @Column(nullable = false, length = 400)
    private String body;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 書いた人の村 */
    @Column(name = "village_id", nullable = false)
    private Long villageId = 1L;

    protected BoardPost() {
    }

    public BoardPost(Long playerId, String name, String body, long villageId) {
        this.playerId = playerId;
        this.name = name;
        this.body = body;
        this.villageId = villageId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getPlayerId() { return playerId; }
    public String getName() { return name; }
    public String getBody() { return body; }
    public boolean isDeleted() { return deleted; }
    public void delete() { this.deleted = true; }
    public long getVillageId() { return villageId == null ? 1L : villageId; }
    public Instant getCreatedAt() { return createdAt; }
}
