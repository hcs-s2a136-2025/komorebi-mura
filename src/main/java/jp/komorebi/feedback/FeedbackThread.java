package jp.komorebi.feedback;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jp.komorebi.player.Player;

/** 管理人へのお便り（1件 = 1つのやりとり） */
@Entity
@Table(name = "feedback_thread")
public class FeedbackThread {

    public enum Category { BUG, IDEA, OTHER }

    public enum Status { OPEN, WIP, DONE, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Category category;

    @Column(nullable = false, length = 60)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(length = 1000)
    private String context;

    @Column(name = "player_unread", nullable = false)
    private boolean playerUnread;

    @Column(name = "admin_unread", nullable = false)
    private boolean adminUnread;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FeedbackThread() {
    }

    public FeedbackThread(Player player, Category category, String title, String context) {
        this.player = player;
        this.category = category;
        this.title = title;
        this.context = context;
        this.status = Status.OPEN;
        this.adminUnread = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public Player getPlayer() { return player; }
    public Category getCategory() { return category; }
    public String getTitle() { return title; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; this.updatedAt = Instant.now(); }
    public String getContext() { return context; }
    public boolean isPlayerUnread() { return playerUnread; }
    public void setPlayerUnread(boolean v) { this.playerUnread = v; }
    public boolean isAdminUnread() { return adminUnread; }
    public void setAdminUnread(boolean v) { this.adminUnread = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }
}
