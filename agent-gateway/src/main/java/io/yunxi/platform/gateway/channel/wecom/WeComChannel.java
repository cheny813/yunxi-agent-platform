package io.yunxi.platform.gateway.channel.wecom;

import io.yunxi.platform.gateway.channel.ChannelHealth;
import io.yunxi.platform.gateway.channel.MessageChannel;
import io.yunxi.platform.gateway.channel.MessageContext;
import io.yunxi.platform.gateway.model.PlatformType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 企业微信消息通道适配器
 *
 * <p>通过 WebSocket 连接企业微信 AI Bot 网关</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.platforms.wecom", name = "enabled", havingValue = "true")
public class WeComChannel implements MessageChannel {

    private final WeComProperties properties;
    private WeComWebSocketClient wsClient;

    public WeComChannel(WeComProperties properties) {
        this.properties = properties;
    }

    @Override
    public PlatformType getPlatformType() {
        return PlatformType.WECOM;
    }

    @Override
    public void connect(BiConsumer<MessageContext, String> handler) {
        if (!properties.isEnabled()) {
            log.info("[WeCom] 平台未启用，跳过连接");
            return;
        }

        if (properties.getBotId() == null || properties.getBotId().isBlank()) {
            log.error("[WeCom] 配置不完整：缺少 botId");
            return;
        }
        if (properties.getSecret() == null || properties.getSecret().isBlank()) {
            log.error("[WeCom] 配置不完整：缺少 secret");
            return;
        }

        wsClient = new WeComWebSocketClient(properties);
        wsClient.setMessageHandler((context, text) -> {
            // 白名单过滤
            if (!isAllowed(context)) {
                log.info("[WeCom] 消息被白名单过滤: userId={}, chatId={}", context.getUserId(), context.getChatId());
                return;
            }
            handler.accept(context, text);
        });

        try {
            wsClient.connect();
            log.info("[WeCom] 企业微信通道已启动");
        } catch (Exception e) {
            log.error("[WeCom] 连接失败", e);
        }
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
            wsClient.sendMessage(chatId, context.getChatType(), text, null);
        }
    }

    @Override
    public void sendMarkdownMessage(String chatId, String markdown, MessageContext context) {
        if (wsClient != null) {
            wsClient.sendMarkdownMessage(chatId, context.getChatType(), markdown, null);
        }
    }

    @Override
    public void sendTypingIndicator(String chatId, MessageContext context) {
        // 企微 AI Bot 支持"思考中"状态
        // 可通过发送特殊消息类型实现，暂用文本提示
        if (wsClient != null) {
            wsClient.sendMessage(chatId, context.getChatType(), "思考中...", null);
        }
    }

    @Override
    public ChannelHealth health() {
        if (wsClient == null) {
            return ChannelHealth.unhealthy(PlatformType.WECOM, "未初始化");
        }
        return wsClient.isConnected()
                ? ChannelHealth.healthy(PlatformType.WECOM)
                : ChannelHealth.unhealthy(PlatformType.WECOM, "未连接");
    }

    /**
     * 白名单过滤
     */
    private boolean isAllowed(MessageContext context) {
        List<String> allowedUsers = properties.getAllowedUsers();
        List<String> allowedChats = properties.getAllowedChats();

        // 空白名单 = 允许所有
        if ((allowedUsers == null || allowedUsers.isEmpty()) && (allowedChats == null || allowedChats.isEmpty())) {
            return true;
        }

        // 检查用户白名单
        if (allowedUsers != null && !allowedUsers.isEmpty() && context.getUserId() != null) {
            if (allowedUsers.contains(context.getUserId())) {
                return true;
            }
        }

        // 检查群聊白名单
        if (allowedChats != null && !allowedChats.isEmpty() && "group".equals(context.getChatType())) {
            return allowedChats.contains(context.getChatId());
        }

        // DM 模式下如果用户不在白名单，拒绝
        return "dm".equals(context.getChatType()) && (allowedUsers == null || allowedUsers.isEmpty());
    }
}
