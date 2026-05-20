package io.yunxi.platform.gateway.channel.dingtalk;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.chatbot.BotReplier;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import io.yunxi.platform.gateway.channel.MessageContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 钉钉 Stream Mode 客户端
 *
 * <p>
 * 通过 dingtalk-stream SDK 建立长连接接收钉钉消息，无需公网回调地址。
 * </p>
 *
 * <p>
 * 消息回复通过 {@link BotReplier} + sessionWebhook 实现，
 * 每条消息携带临时 webhook URL（约 10 分钟有效），可多次回复。
 * </p>
 */
@Slf4j
public class DingTalkStreamClient {

    private final DingTalkProperties properties;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** 消息接收回调 */
    private java.util.function.BiConsumer<MessageContext, String> messageHandler;

    /** conversationId -> BotReplier 映射（用于回复，含 sessionWebhook） */
    private final Map<String, BotReplier> repliers = new ConcurrentHashMap<>();

    /** conversationId -> webhook 过期时间 */
    private final Map<String, Long> webhookExpirations = new ConcurrentHashMap<>();

    /** SDK 客户端实例 */
    private OpenDingTalkClient streamClient;

    public DingTalkStreamClient(DingTalkProperties properties) {
        this.properties = properties;
    }

    /**
     * 连接钉钉 Stream 服务
     *
     * <p>
     * 使用 dingtalk-stream SDK 建立长连接，注册机器人消息回调。
     * </p>
     */
    public void connect() {
        if (properties.getClientId() == null || properties.getClientId().isBlank()) {
            log.error("[DingTalk] 配置不完整：缺少 clientId");
            return;
        }
        if (properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
            log.error("[DingTalk] 配置不完整：缺少 clientSecret");
            return;
        }

        log.info("[DingTalk] Stream 客户端初始化, clientId={}***",
                properties.getClientId().substring(0, Math.min(4, properties.getClientId().length())));

        try {
            streamClient = OpenDingTalkStreamClientBuilder.custom()
                    .credential(new AuthClientCredential(properties.getClientId(), properties.getClientSecret()))
                    .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC,
                            (OpenDingTalkCallbackListener<ChatbotMessage, Map<String, String>>) this::onMessage)
                    .build();

            streamClient.start();
            connected.set(true);
            log.info("[DingTalk] Stream 客户端已启动并连接成功");
        } catch (Exception e) {
            log.error("[DingTalk] Stream 客户端启动失败", e);
            connected.set(false);
        }
    }

    /**
     * 钉钉机器人消息回调
     *
     * <p>
     * SDK 回调提供类型化的 {@link ChatbotMessage}，包含：
     * </p>
     * <ul>
     * <li>senderStaffId / senderNick: 发送者信息</li>
     * <li>conversationId / conversationType: 会话信息</li>
     * <li>sessionWebhook: 临时回复 URL</li>
     * <li>text / content: 消息内容</li>
     * </ul>
     */
    private Map<String, String> onMessage(ChatbotMessage message) {
        try {
            // 缓存 BotReplier（用于后续回复）
            String conversationId = message.getConversationId();
            String sessionWebhook = message.getSessionWebhook();
            if (conversationId != null && sessionWebhook != null) {
                repliers.put(conversationId, BotReplier.fromWebhook(sessionWebhook));
                // 记录过期时间（SDK 提供毫秒时间戳，提前 1 分钟失效）
                Long expiredTime = message.getSessionWebhookExpiredTime();
                long expireAt = expiredTime != null
                        ? expiredTime - 60_000
                        : System.currentTimeMillis() + 9 * 60_000;
                webhookExpirations.put(conversationId, expireAt);
            }

            // 转换为统一 MessageContext
            MessageContext context = DingTalkMessageMapper.toMessageContext(message);

            // 提取文本
            String text = DingTalkMessageMapper.extractText(message);
            if (text != null && messageHandler != null) {
                messageHandler.accept(context, text);
            }
        } catch (Exception e) {
            log.error("[DingTalk] 处理消息失败", e);
        }

        return Map.of();
    }

    /**
     * 发送文本回复
     *
     * <p>
     * 通过缓存的 BotReplier（sessionWebhook）回复。
     * </p>
     */
    public void sendReply(String conversationId, String text) {
        BotReplier replier = getValidReplier(conversationId);
        if (replier == null) {
            log.warn("[DingTalk] 无法回复：sessionWebhook 已过期或不存在, conversationId={}", conversationId);
            return;
        }
        try {
            replier.replyText(text);
            log.debug("[DingTalk] 文本回复成功, conversationId={}", conversationId);
        } catch (Exception e) {
            log.error("[DingTalk] 文本回复失败, conversationId={}", conversationId, e);
        }
    }

    /**
     * 发送 Markdown 回复
     */
    public void sendMarkdownReply(String conversationId, String title, String markdown) {
        BotReplier replier = getValidReplier(conversationId);
        if (replier == null) {
            log.warn("[DingTalk] 无法回复：sessionWebhook 已过期或不存在, conversationId={}", conversationId);
            return;
        }
        try {
            replier.replyMarkdown(title, markdown);
            log.debug("[DingTalk] Markdown 回复成功, conversationId={}", conversationId);
        } catch (Exception e) {
            log.error("[DingTalk] Markdown 回复失败, conversationId={}", conversationId, e);
        }
    }

    /**
     * 获取有效的 BotReplier（检查过期时间）
     */
    private BotReplier getValidReplier(String conversationId) {
        Long expireAt = webhookExpirations.get(conversationId);
        if (expireAt != null && System.currentTimeMillis() > expireAt) {
            repliers.remove(conversationId);
            webhookExpirations.remove(conversationId);
            return null;
        }
        return repliers.get(conversationId);
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        if (streamClient != null) {
            try {
                streamClient.stop();
            } catch (Exception e) {
                log.warn("[DingTalk] 停止 Stream 客户端异常", e);
            }
        }
        repliers.clear();
        webhookExpirations.clear();
        connected.set(false);
        log.info("[DingTalk] Stream 客户端已断开");
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void setMessageHandler(java.util.function.BiConsumer<MessageContext, String> handler) {
        this.messageHandler = handler;
    }
}
