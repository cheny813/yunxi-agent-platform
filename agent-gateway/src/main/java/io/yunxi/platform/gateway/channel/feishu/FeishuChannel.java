package io.yunxi.platform.gateway.channel.feishu;

import io.yunxi.platform.gateway.channel.ChannelHealth;
import io.yunxi.platform.gateway.channel.MessageChannel;
import io.yunxi.platform.gateway.channel.MessageContext;
import io.yunxi.platform.gateway.model.PlatformType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 飞书消息通道适配器
 *
 * <p>通过 WebSocket 长连接与飞书通信</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.platforms.feishu", name = "enabled", havingValue = "true")
public class FeishuChannel implements MessageChannel {

    private final FeishuProperties properties;
    private FeishuWebSocketClient wsClient;

    public FeishuChannel(FeishuProperties properties) {
        this.properties = properties;
    }

    @Override
    public PlatformType getPlatformType() {
        return PlatformType.FEISHU;
    }

    @Override
    public void connect(BiConsumer<MessageContext, String> handler) {
        if (!properties.isEnabled()) {
            log.info("[Feishu] 平台未启用，跳过连接");
            return;
        }

        wsClient = new FeishuWebSocketClient(properties);
        wsClient.setMessageHandler((context, text) -> {
            if (!isAllowed(context)) {
                log.info("[Feishu] 消息被白名单过滤: userId={}, chatId={}", context.getUserId(), context.getChatId());
                return;
            }
            handler.accept(context, text);
        });

        wsClient.connect();
    }

    @Override
    public void disconnect() {
        if (wsClient != null) {
            wsClient.disconnect();
        }
    }

    @Override
    public boolean isConnected() {
        return wsClient != null && wsClient.isConnected();
    }

    @Override
    public void sendTextMessage(String chatId, String text, MessageContext context) {
        if (wsClient != null) {
            wsClient.sendMessage(chatId, "text", FeishuMessageMapper.buildTextReply(text));
        }
    }

    @Override
    public void sendMarkdownMessage(String chatId, String markdown, MessageContext context) {
        if (wsClient != null) {
            wsClient.sendMessage(chatId, "interactive", FeishuMessageMapper.buildMarkdownReply(markdown));
        }
    }

    @Override
    public ChannelHealth health() {
        if (wsClient == null) {
            return ChannelHealth.unhealthy(PlatformType.FEISHU, "未初始化");
        }
        return wsClient.isConnected()
                ? ChannelHealth.healthy(PlatformType.FEISHU)
                : ChannelHealth.unhealthy(PlatformType.FEISHU, "未连接");
    }

    private boolean isAllowed(MessageContext context) {
        List<String> allowedUsers = properties.getAllowedUsers();
        if (allowedUsers == null || allowedUsers.isEmpty()) {
            return true;
        }
        return context.getUserId() != null && allowedUsers.contains(context.getUserId());
    }
}
