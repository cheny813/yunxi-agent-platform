package io.yunxi.platform.config;

import io.yunxi.platform.desktop.relay.DesktopRelayHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 * <p>注册桌面中继处理器的 WebSocket 端点。</p>
 *
 * @author yunxi-agent-platform
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private DesktopRelayHandler desktopRelayHandler;

    /**
     * 注册 WebSocket 处理器。
     *
     * @param registry WebSocket 处理器注册表
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(desktopRelayHandler, "/ws/desktop").setAllowedOrigins("*");
    }
}
