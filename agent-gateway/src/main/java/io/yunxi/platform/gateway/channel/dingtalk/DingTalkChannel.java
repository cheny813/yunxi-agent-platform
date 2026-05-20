package io.yunxi.platform.gateway.channel.dingtalk;

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
 * 钉钉消息通道适配器
 *
 * <p>
 * 通过 Stream Mode 长连接与钉钉通信。回复通过 SDK 内置的
 * {@link com.dingtalk.open.app.api.chatbot.BotReplier} + sessionWebhook 实现。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.platforms.dingtalk", name = "enabled", havingValue = "true")
public class DingTalkChannel implements MessageChannel {

    private final DingTalkProperties properties;
    private DingTalkStreamClient streamClient;

    public DingTalkChannel(DingTalkProperties properties) {
        this.properties = properties;
    }

    @Override
    public PlatformType getPlatformType() {
        return PlatformType.DINGTALK;
    }

    @Override
    public void connect(BiConsumer<MessageContext, String> handler) {
        if (!properties.isEnabled()) {
            log.info("[DingTalk] 平台未启用，跳过连接");
            return;
        }

        streamClient = new DingTalkStreamClient(properties);
        streamClient.setMessageHandler((context, text) -> {
            if (!isAllowed(context)) {
                log.info("[DingTalk] 消息被白名单过滤: userId={}, chatId={}", context.getUserId(),
                        context.getChatId());
                return;
            }
            handler.accept(context, text);
        });

        streamClient.connect();
    }

    @Override
    public void disconnect() {
        if (streamClient != null) {
            streamClient.disconnect();
        }
    }

    @Override
    public boolean isConnected() {
        return streamClient != null && streamClient.isConnected();
    }

    @Override
    public void sendTextMessage(String chatId, String text, MessageContext context) {
        if (streamClient != null) {
            streamClient.sendReply(chatId, text);
        }
    }

    @Override
    public void sendMarkdownMessage(String chatId, String markdown, MessageContext context) {
        if (streamClient != null) {
            streamClient.sendMarkdownReply(chatId, "Agent 回复", markdown);
        }
    }

    @Override
    public ChannelHealth health() {
        if (streamClient == null) {
            return ChannelHealth.unhealthy(PlatformType.DINGTALK, "未初始化");
        }
        return streamClient.isConnected()
                ? ChannelHealth.healthy(PlatformType.DINGTALK)
                : ChannelHealth.unhealthy(PlatformType.DINGTALK, "未连接");
    }

    private boolean isAllowed(MessageContext context) {
        List<String> allowedUsers = properties.getAllowedUsers();
        List<String> allowedChats = properties.getAllowedChats();

        if ((allowedUsers == null || allowedUsers.isEmpty()) && (allowedChats == null || allowedChats.isEmpty())) {
            return true;
        }

        if (allowedUsers != null && !allowedUsers.isEmpty() && context.getUserId() != null) {
            if (allowedUsers.contains(context.getUserId())) {
                return true;
            }
        }

        if (allowedChats != null && !allowedChats.isEmpty() && "group".equals(context.getChatType())) {
            return allowedChats.contains(context.getChatId());
        }

        return "dm".equals(context.getChatType()) && (allowedUsers == null || allowedUsers.isEmpty());
    }
}
