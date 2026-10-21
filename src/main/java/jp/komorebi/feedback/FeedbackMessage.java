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

/** お便りの中の1通 */
@Entity
@Table(name = "feedback_message")
public class FeedbackMessage {

    public enum Sender { PLAYER, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "thread_id")
    private FeedbackThread thread;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Sender sender;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FeedbackMessage() {
    }

    public FeedbackMessage(FeedbackThread thread, Sender sender, String body) {
        this.thread = thread;
        this.sender = sender;
        this.body = body;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public FeedbackThread getThread() { return thread; }
    public Sender getSender() { return sender; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
