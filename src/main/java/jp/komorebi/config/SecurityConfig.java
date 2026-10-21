package jp.komorebi.config;

import java.security.SecureRandom;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

/**
 * 管理画面 /admin/** だけログインが必要。
 * ゲームのAPI(/api/**)とWebSocket(/ws)はプレイヤーのトークンで本人確認するので、ここではCSRF対象外にしている。
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/admin/login", "/admin.css").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll())
            .formLogin(f -> f
                .loginPage("/admin/login")
                .defaultSuccessUrl("/admin", true)
                .failureUrl("/admin/login?error"))
            .logout(l -> l
                .logoutUrl("/admin/logout")
                .logoutSuccessUrl("/admin/login?logout"))
            .csrf(c -> c.ignoringRequestMatchers("/api/**", "/ws/**", "/ws"))
            // 画面を長く開いたままにしてCSRFの合い言葉が切れたときは、白いエラー画面ではなくログイン画面に戻す
            .exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) -> {
                if (ex instanceof CsrfException) {
                    res.sendRedirect(req.getContextPath() + "/admin/login?expired");
                } else {
                    res.sendError(403);
                }
            }))
            .headers(h -> h.frameOptions(fo -> fo.sameOrigin()));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService adminUsers(KomorebiProperties props, PasswordEncoder encoder) {
        String user = props.admin().username();
        String pass = props.admin().password();
        if (pass == null || pass.isBlank()) {
            byte[] b = new byte[9];
            new SecureRandom().nextBytes(b);
            pass = HexFormat.of().formatHex(b);
            log.warn("==============================================================");
            log.warn(" ADMIN_PASSWORD が設定されていないので、今回だけのパスワードを作りました");
            log.warn(" 管理画面 /admin  ユーザー: {}  パスワード: {}", user, pass);
            log.warn(" 本番では環境変数 ADMIN_PASSWORD を必ず設定してください");
            log.warn("==============================================================");
        }
        return new InMemoryUserDetailsManager(
            User.withUsername(user).password(encoder.encode(pass)).roles("ADMIN").build());
    }
}
