package jp.komorebi.board;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jp.komorebi.player.Player;

@Service
public class BoardService {

    public record PostView(Long id, Long playerId, String name, String body, Instant createdAt) {
        static PostView of(BoardPost p) {
            return new PostView(p.getId(), p.getPlayerId(), p.getName(), p.getBody(), p.getCreatedAt());
        }
    }

    private final BoardPostRepository posts;

    public BoardService(BoardPostRepository posts) {
        this.posts = posts;
    }

    @Transactional(readOnly = true)
    public List<PostView> latest() {
        return posts.findTop100ByDeletedFalseOrderByCreatedAtDesc().stream().map(PostView::of).toList();
    }

    @Transactional
    public PostView post(Player p, String body) {
        String b = body == null ? "" : body.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
        if (b.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "空っぽです");
        if (b.codePointCount(0, b.length()) > 200) b = b.substring(0, b.offsetByCodePoints(0, 200));
        return PostView.of(posts.save(new BoardPost(p.getId(), p.getName(), b)));
    }

    @Transactional
    public void deleteOwn(Player p, Long id) {
        BoardPost post = posts.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!post.getPlayerId().equals(p.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        post.delete();
    }

    @Transactional
    public void adminDelete(Long id) {
        posts.findById(id).ifPresent(BoardPost::delete);
    }
}
