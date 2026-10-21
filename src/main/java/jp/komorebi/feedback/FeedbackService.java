package jp.komorebi.feedback;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jp.komorebi.feedback.FeedbackMessage.Sender;
import jp.komorebi.feedback.FeedbackThread.Category;
import jp.komorebi.feedback.FeedbackThread.Status;
import jp.komorebi.feedback.FeedbackViews.AdminReplied;
import jp.komorebi.feedback.FeedbackViews.MessageView;
import jp.komorebi.feedback.FeedbackViews.ThreadView;
import jp.komorebi.player.Player;

@Service
public class FeedbackService {

    private final FeedbackThreadRepository threads;
    private final FeedbackMessageRepository messages;
    private final ApplicationEventPublisher events;

    public FeedbackService(FeedbackThreadRepository threads, FeedbackMessageRepository messages, ApplicationEventPublisher events) {
        this.threads = threads;
        this.messages = messages;
        this.events = events;
    }

    // ---------- プレイヤー側 ----------

    @Transactional
    public ThreadView create(Player player, Category category, String title, String body, String context) {
        FeedbackThread t = threads.save(new FeedbackThread(player, category, clip(title, 60), clip(context, 1000)));
        messages.save(new FeedbackMessage(t, Sender.PLAYER, clip(body, 2000)));
        return view(t);
    }

    @Transactional
    public ThreadView playerReply(Player player, Long threadId, String body) {
        FeedbackThread t = own(player, threadId);
        messages.save(new FeedbackMessage(t, Sender.PLAYER, clip(body, 2000)));
        t.setAdminUnread(true);
        if (t.getStatus() == Status.CLOSED) t.setStatus(Status.OPEN);
        t.touch();
        return view(t);
    }

    @Transactional
    public void markReadByPlayer(Player player, Long threadId) {
        own(player, threadId).setPlayerUnread(false);
    }

    @Transactional(readOnly = true)
    public List<ThreadView> listFor(Player player) {
        return threads.findByPlayerOrderByUpdatedAtDesc(player).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public long unreadFor(Player player) {
        return threads.countByPlayerAndPlayerUnreadTrue(player);
    }

    // ---------- 管理人側 ----------

    @Transactional(readOnly = true)
    public List<ThreadView> listAll(String status, String category) {
        return threads.findAllByOrderByUpdatedAtDesc().stream()
            .filter(t -> status == null || status.isBlank() || t.getStatus().name().equals(status))
            .filter(t -> category == null || category.isBlank() || t.getCategory().name().equals(category))
            .map(this::view).toList();
    }

    @Transactional
    public ThreadView openAsAdmin(Long id) {
        FeedbackThread t = find(id);
        t.setAdminUnread(false);
        return view(t);
    }

    @Transactional
    public void adminReply(Long id, String body) {
        FeedbackThread t = find(id);
        messages.save(new FeedbackMessage(t, Sender.ADMIN, clip(body, 2000)));
        t.setPlayerUnread(true);
        t.setAdminUnread(false);
        if (t.getStatus() == Status.OPEN) t.setStatus(Status.WIP);
        t.touch();
        events.publishEvent(new AdminReplied(t.getPlayer().getId(), t.getId(), t.getTitle()));
    }

    @Transactional
    public void changeStatus(Long id, Status status) {
        find(id).setStatus(status);
    }

    @Transactional(readOnly = true)
    public long adminUnreadCount() {
        return threads.countByAdminUnreadTrue();
    }

    // ---------- 内部 ----------

    private FeedbackThread find(Long id) {
        return threads.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private FeedbackThread own(Player player, Long id) {
        FeedbackThread t = find(id);
        if (!t.getPlayer().getId().equals(player.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return t;
    }

    private ThreadView view(FeedbackThread t) {
        List<MessageView> ms = messages.findByThreadOrderByCreatedAtAscIdAsc(t).stream().map(MessageView::of).toList();
        return new ThreadView(t.getId(), t.getCategory().name(), t.getTitle(), t.getStatus().name(), t.getContext(),
            t.isPlayerUnread(), t.isAdminUnread(), t.getCreatedAt(), t.getUpdatedAt(),
            t.getPlayer().getId(), t.getPlayer().getName(), ms);
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String v = s.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
