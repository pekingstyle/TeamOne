package cn.teamone.app.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {

    private final TeamOneWsHandler handler;

    public WsConfig(TeamOneWsHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 握手走 HTTP 层 permitAll（见 SecurityConfig /ws）；真正的认证在协议层首帧 auth
        registry.addHandler(handler, "/ws").setAllowedOriginPatterns("*");
    }
}
