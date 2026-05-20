package io.yunxi.platform.framework.config;

import io.yunxi.platform.framework.controller.DesktopRelayHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 *
 * @author yunxi-agent-platform
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** 桌面中继处理器 */
    @Autowired
    private DesktopRelayHandler desktopRelayHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 中继服务器端点：桌面客户端连接
        registry.addHandler(desktopRelayHandler, "/ws/desktop")
                .setAllowedOrigins("*");
    }
}