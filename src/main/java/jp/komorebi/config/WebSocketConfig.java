package jp.komorebi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import jp.komorebi.ws.GameSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameSocketHandler handler;
    private final KomorebiProperties props;

    public WebSocketConfig(GameSocketHandler handler, KomorebiProperties props) {
        this.handler = handler;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOriginPatterns(props.allowedOrigins().toArray(String[]::new));
    }

    /** 共有の村をまとめて送るので、1通の上限を大きめに（Tomcatの初期値は8KB） */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(128 * 1024);
        c.setMaxBinaryMessageBufferSize(128 * 1024);
        c.setMaxSessionIdleTimeout(120_000L);
        return c;
    }
}
