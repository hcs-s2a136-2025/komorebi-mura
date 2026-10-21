package jp.komorebi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import jp.komorebi.feedback.FeedbackService;
import jp.komorebi.feedback.FeedbackThread.Category;
import jp.komorebi.feedback.FeedbackViews.ThreadView;
import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.board.BoardService;
import jp.komorebi.clock.ClockService;
import jp.komorebi.world.SharedWorldService;
import jp.komorebi.save.SaveService;
import jp.komorebi.casino.JackpotService;
import com.fasterxml.jackson.databind.ObjectMapper;

/** ./mvnw test で動く確認。DBはメモリ上のH2を使うので、本物のデータには触らない */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:komorebi-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    "komorebi.invite-code=test-code",
    "komorebi.admin.password=test-pass"
})
class KomorebiApplicationTests {

    @Autowired PlayerService players;
    @Autowired FeedbackService feedback;
    @Autowired SharedWorldService world;
    @Autowired BoardService board;
    @Autowired ObjectMapper om;
    @Autowired ClockService clock;
    @Autowired SaveService saves;
    @Autowired JackpotService jackpot;

    @Test
    void morningAdvancesTheSharedDay() {
        int d = clock.day();
        clock.morningNow();
        assertThat(clock.day()).isEqualTo(d + 1);
        assertThat(clock.time()).isBetween(359.0, 400.0);
        assertThat(world.snapshot().path("_clock").path("day").asInt()).isEqualTo(d + 1);
    }

    @Test
    void sharedWorldKeepsValidEditsOnly() throws Exception {
        var applied = world.apply(om.readTree("[{\"l\":\"trees\",\"k\":\"11,11\",\"v\":{\"hp\":3}},{\"l\":\"evil\",\"k\":\"1,1\",\"v\":1},{\"l\":\"rocks\",\"k\":\"<script>\",\"v\":1}]"));
        assertThat(applied).hasSize(1);
        assertThat(world.snapshot().path("trees").path("11,11").path("hp").asInt()).isEqualTo(3);
        world.apply(om.readTree("[{\"l\":\"trees\",\"k\":\"11,11\",\"v\":null}]"));
        assertThat(world.snapshot().path("trees").has("11,11")).isFalse();
        world.persist();
    }

    @Test
    void boardPostAndDelete() {
        Player p = players.join("test-code", "そうた", "sota1234").player();
        var v = board.post(p, "東の採石場、2段目が開いたよ！");
        assertThat(board.latest()).extracting(BoardService.PostView::id).contains(v.id());
        board.deleteOwn(p, v.id());
        assertThat(board.latest()).extracting(BoardService.PostView::id).doesNotContain(v.id());
    }

    @Test
    void joinNeedsRightInviteCode() {
        assertThatThrownBy(() -> players.join("wrong", "りこ", "riko1234")).isInstanceOf(ResponseStatusException.class);
        PlayerService.Joined j = players.join("test-code", "りこ", "riko1234");
        assertThat(j.token()).isNotBlank();
        assertThat(players.findByToken(j.token())).isPresent();
    }

    @Test
    void feedbackRoundTrip() {
        Player p = players.join("test-code", "りこ", "riko1234").player();
        ThreadView t = feedback.create(p, Category.BUG, "洞窟に入れない", "下から入れません", "春1日目 village (34,17)");
        assertThat(t.messages()).hasSize(1);
        feedback.adminReply(t.id(), "直しました！");
        assertThat(feedback.unreadFor(p)).isEqualTo(1);
        feedback.markReadByPlayer(p, t.id());
        assertThat(feedback.unreadFor(p)).isZero();
        assertThat(feedback.listFor(p).get(0).messages()).hasSize(2);
    }

    @Test
    void sameNameAndPassComesBackAsTheSamePlayer() {
        PlayerService.Joined a = players.join("test-code", "みく", "miku1234");
        PlayerService.Joined b = players.join("test-code", "みく", "miku1234");
        assertThat(b.player().getId()).isEqualTo(a.player().getId());
        assertThat(b.created()).isFalse();
        // 新しく入ると、前の端末のトークンは使えなくなる
        assertThat(players.findByToken(a.token())).isEmpty();
        assertThat(players.findByToken(b.token())).isPresent();
        // 合言葉がちがうと入れない
        assertThatThrownBy(() -> players.join("test-code", "みく", "wrongpass")).isInstanceOf(ResponseStatusException.class);
        // 短すぎる合言葉はだめ
        assertThatThrownBy(() -> players.join("test-code", "はると", "123")).isInstanceOf(ResponseStatusException.class);
        // 管理人が合言葉を消すと、新しい合言葉で入り直せる（同じプレイヤーのまま）
        players.resetPass(a.player().getId());
        assertThat(players.join("test-code", "みく", "newpass99").player().getId()).isEqualTo(a.player().getId());
    }

    @Test
    void saveRoundTripAndDelete() {
        Player p = players.join("test-code", "ゆい", "yui12345").player();
        assertThat(saves.load(p.getId())).isEmpty();
        saves.store(p.getId(), "{\"world\":{\"money\":500}}", 1);
        saves.store(p.getId(), "{\"world\":{\"money\":900}}", 2);
        SaveService.Save got = saves.load(p.getId()).orElseThrow();
        assertThat(got.rev()).isEqualTo(2);
        assertThat(got.doc()).contains("900");
        assertThat(saves.sizes()).containsKey(p.getId());
        assertThatThrownBy(() -> saves.store(p.getId(), "not json", 3)).isInstanceOf(ResponseStatusException.class);
        saves.delete(p.getId());
        assertThat(saves.load(p.getId())).isEmpty();
    }

    @Test
    void jackpotIsShared() {
        long before = jackpot.pot();
        assertThat(jackpot.add(30)).isEqualTo(before + 30);
        assertThat(jackpot.win()).isEqualTo(before + 30);
        assertThat(jackpot.pot()).isEqualTo(JackpotService.START);
    }

    @Test
    void namesAreCleaned() {
        assertThat(PlayerService.cleanName("  <りこ>  ")).isEqualTo("りこ");
        assertThat(PlayerService.cleanName("")).isEqualTo("たびびと");
        assertThat(PlayerService.cleanName("あいうえおかきくけこ")).hasSize(8);
    }
}
