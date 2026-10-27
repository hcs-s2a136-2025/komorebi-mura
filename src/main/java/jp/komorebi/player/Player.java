package jp.komorebi.player;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 招待コードで参加したプレイヤー。トークンと合言葉はそのものを保存せず、ハッシュだけ持つ */
@Entity
@Table(name = "player")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 16)
    private String name;

    /** 合言葉のハッシュ（BCrypt）。まだ決めていない人は null */
    @Column(name = "pass_hash", length = 100)
    private String passHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    /** 管理人室で削除した日時（削除していなければ null） */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 住んでいる村（1 ＝ こもれび村） */
    @Column(name = "village_id", nullable = false)
    private Long villageId = 1L;

    protected Player() {
    }

    public Player(String tokenHash, String name) {
        this.tokenHash = tokenHash;
        this.name = name;
        this.createdAt = Instant.now();
        this.lastSeen = this.createdAt;
    }

    public Long getId() { return id; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getPassHash() { return passHash; }
    public void setPassHash(String passHash) { this.passHash = passHash; }
    public boolean hasPass() { return passHash != null && !passHash.isBlank(); }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeen() { return lastSeen; }
    public void touch() { this.lastSeen = Instant.now(); }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }
    public long getVillageId() { return villageId == null ? 1L : villageId; }
    public void setVillageId(long villageId) { this.villageId = villageId; }
    public void markDeleted() { this.deletedAt = Instant.now(); this.passHash = null; }
}
