package jp.komorebi.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import jp.komorebi.raid.RaidService;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:komorebi-admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    "komorebi.invite-code=test-code", "komorebi.admin.password=test-pass" })
@AutoConfigureMockMvc
class AdminPagesRenderTest {
    @Autowired MockMvc mvc;
    @Autowired RaidService raid;
    @Autowired jp.komorebi.player.PlayerService players;

    void hit(String url) {
        try {
            var r = mvc.perform(get(url).with(user("admin").roles("ADMIN"))).andReturn();
            System.out.println("## " + url + " -> " + r.getResponse().getStatus() + (r.getResolvedException() != null ? " EX " + r.getResolvedException() : ""));
            if (r.getResolvedException() != null) r.getResolvedException().printStackTrace(System.out);
        } catch (Throwable e) {
            System.out.println("## " + url + " -> THROWN " + e);
            Throwable c = e; while (c != null) { System.out.println("Caused by: " + c); c = c.getCause(); }
            e.printStackTrace(System.out);
        }
    }

    @Test
    void pages() {
        players.join("test-code", "りこ", "riko1234");
        for (String u : new String[] { "/admin", "/admin/rank", "/admin/raid", "/admin/villages", "/admin/money", "/admin/gift", "/admin/events" }) hit(u);
        System.out.println("## plan " + raid.plan("boar", "hill", Instant.now().plusSeconds(600), 15, 30000, 3000));
        hit("/admin/raid");
        System.out.println("## start " + raid.startNow(1));
        hit("/admin/raid");
        System.out.println("## stop " + raid.stop());
        hit("/admin/raid");
    }
}
