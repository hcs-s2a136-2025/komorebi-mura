package jp.komorebi.village;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 管理人室の画面のあいだ、「いまえらんでいる村」を {@link Villages} にセットする。
 * ?village=番号 で村をかえると、ログインしているあいだはその村のまま（セッションに覚える）。
 * これで、管理人室の今までの画面（お金の調整・プレゼント・お知らせ・リセット・イベントなど）が、えらんだ村の分になる。
 */
@Component
public class AdminVillageInterceptor implements HandlerInterceptor {

    static final String KEY = "adminVillage";
    private final VillageService villages;

    public AdminVillageInterceptor(VillageService villages) {
        this.villages = villages;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        HttpSession session = req.getSession(true);
        long v = Villages.MAIN;
        Object saved = session.getAttribute(KEY);
        if (saved instanceof Long l) v = l;
        String q = req.getParameter("village");
        if (q != null && q.matches("\\d{1,9}")) v = Long.parseLong(q);
        final long want = v;
        if (villages.find(want).isEmpty()) v = Villages.MAIN;
        session.setAttribute(KEY, v);
        req.setAttribute(KEY, v);
        Villages.set(v);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        Villages.set(null);
    }
}
