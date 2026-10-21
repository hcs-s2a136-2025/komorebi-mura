package jp.komorebi.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.properties の komorebi.* をまとめて受け取る */
@ConfigurationProperties(prefix = "komorebi")
public record KomorebiProperties(String inviteCode, Admin admin, String version, List<String> allowedOrigins) {

    public record Admin(String username, String password) {
    }
}
