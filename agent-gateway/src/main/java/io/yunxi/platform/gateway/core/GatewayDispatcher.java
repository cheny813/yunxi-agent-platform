package io.yunxi.platform.gateway.core;

import io.yunxi.platform.gateway.GatewayProperties;
import io.yunxi.platform.gateway.channel.MessageChannel;
import io.yunxi.platform.gateway.channel.MessageContext;
import io.yunxi.platform.gateway.model.GatewaySession;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关消息调度中心
 *
 * <p>接收各平台适配器的消息回调，调用 agent-core，将响应返回给平台</p>
 */
@Slf4j
public class GatewayDispatcher {

    private final CoreAgentClient coreAgentClient;
    private final GatewaySessionManager sessionManager;
    private final GatewayProperties properties;

    /** 已注册的平台适配器 */
    private final Map<String, MessageChannel> channels = new ConcurrentHashMap<>();

    public GatewayDispatcher(CoreAgentClient coreAgentClient,
                              GatewaySessionManager sessionManager,
                              GatewayProperties properties) {
        this.coreAgentClient = coreAgentClient;
        this.sessionManager = sessionManager;
        this.properties = properties;
    }

    /**
     * 注册平台适配器
     */
    public void registerChannel(MessageChannel channel) {
        String key = channel.getPlatformType().getCode();
        channels.put(key, channel);
        log.info("[Dispatcher] 注册平台适配器: {}", key);
    }

    /**
     * 启动所有已注册的平台适配器
     */
    public void startAll() {
        for (MessageChannel channel : channels.values()) {
            try {
                channel.connect(this::onMessage);
                log.info("[Dispatcher] 平台 {} 已连接", channel.getPlatformType());
            } catch (Exception e) {
                log.error("[Dispatcher] 平台 {} 连接失败", channel.getPlatformType(), e);
            }
        }
    }

    /**
     * 停止所有平台适配器
     */
    public void stopAll() {
        for (MessageChannel channel : channels.values()) {
            try {
                channel.disconnect();
            } catch (Exception e) {
                log.warn("[Dispatcher] 平台 {} 断开失败", channel.getPlatformType(), e);
            }
        }
    }

    /**
     * 消息接收回调
     *
     * <p>各平台适配器收到消息后调用此方法</p>
     */
    public void onMessage(MessageContext context, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }

        log.info("[Dispatcher] 收到消息: platform={}, chatId={}, userId={}, text长度={}",
                context.getPlatform(), context.getChatId(), context.getUserId(), text.length());

        try {
            // 1. 检查斜杠命令
            if (text.startsWith("/")) {
                handleSlashCommand(context, text);
                return;
            }

            // 2. 获取或创建会话
            GatewaySession session = sessionManager.getOrCreateSession(context);

            // 3. 发送"思考中"状态
            MessageChannel channel = channels.get(context.getPlatform().getCode());
            if (channel != null && properties.getStreaming().isTypingIndicator()) {
                channel.sendTypingIndicator(context.getChatId(), context);
            }

            // 4. 构造 UnifiedChatRequest
            UnifiedChatRequest request = new UnifiedChatRequest();
            request.setMessage(text);
            request.setAgentName(session.getAgentName());
            request.setUserId(context.getAgentUserId());
            request.setMode("stream");
            request.setAutoManageConversation(true);

            if (StringUtils.hasText(session.getConversationId())) {
                request.setConversationId(session.getConversationId());
            }

            if (StringUtils.hasText(session.getModelOverride())) {
                request.setParameters(Map.of("model", session.getModelOverride()));
            }

            // 5. 调用 agent-core 并处理 SSE 流式响应
            StringBuilder responseBuilder = new StringBuilder();
            coreAgentClient.chatStream(request)
                    .doOnNext(event -> {
                        if (event.isContent() && StringUtils.hasText(event.content())) {
                            responseBuilder.append(event.content());
                        }
                    })
                    .doOnComplete(() -> {
                        String response = responseBuilder.toString();
                        if (StringUtils.hasText(response) && channel != null) {
                            sendResponse(channel, context, response);
                        }
                        log.info("[Dispatcher] 消息处理完成: platform={}, chatId={}",
                                context.getPlatform(), context.getChatId());
                    })
                    .doOnError(error -> {
                        log.error("[Dispatcher] 消息处理失败: platform={}, chatId={}",
                                context.getPlatform(), context.getChatId(), error);
                        if (channel != null) {
                            channel.sendTextMessage(context.getChatId(),
                                    "抱歉，处理您的消息时出错了，请稍后重试。", context);
                        }
                    })
                    .subscribe();

        } catch (Exception e) {
            log.error("[Dispatcher] 消息调度异常", e);
        }
    }

    /**
     * 处理斜杠命令
     */
    private void handleSlashCommand(MessageContext context, String command) {
        MessageChannel channel = channels.get(context.getPlatform().getCode());
        String[] parts = command.trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        String reply;
        switch (cmd) {
            case "/new", "/reset" -> {
                String sessionKey = sessionManager.buildSessionKey(context);
                sessionManager.getSession(sessionKey).ifPresent(session -> {
                    if (StringUtils.hasText(session.getConversationId())) {
                        coreAgentClient.deleteConversation(session.getConversationId());
                    }
                    sessionManager.resetSession(sessionKey, session);
                });
                reply = "会话已重置，开始新的对话。";
            }
            case "/model" -> {
                if (!StringUtils.hasText(args)) {
                    reply = "用法: /model <模型名称>";
                } else {
                    String sessionKey = sessionManager.buildSessionKey(context);
                    sessionManager.getSession(sessionKey).ifPresent(session -> session.setModelOverride(args));
                    reply = "已切换模型为: " + args;
                }
            }
            case "/agent" -> {
                if (!StringUtils.hasText(args)) {
                    reply = "用法: /agent <Agent名称>";
                } else {
                    String sessionKey = sessionManager.buildSessionKey(context);
                    sessionManager.getSession(sessionKey).ifPresent(session -> session.setAgentName(args));
                    reply = "已切换 Agent 为: " + args;
                }
            }
            case "/status" -> {
                String sessionKey = sessionManager.buildSessionKey(context);
                reply = sessionManager.getSession(sessionKey)
                        .map(session -> String.format("当前状态:\n- Agent: %s\n- 模型: %s\n- 消息数: %d\n- 活跃时间: %s",
                                session.getAgentName(),
                                StringUtils.hasText(session.getModelOverride()) ? session.getModelOverride() : "默认",
                                session.getMessageCount(),
                                session.getLastActiveAt()))
                        .orElse("暂无活跃会话");
            }
            case "/help" -> reply = """
                    可用命令:
                    /new - 重置会话
                    /reset - 重置会话
                    /model <名称> - 切换模型
                    /agent <名称> - 切换 Agent
                    /status - 查看当前状态
                    /help - 显示帮助""";
            default -> reply = "未知命令: " + cmd + "\n输入 /help 查看可用命令";
        }

        if (channel != null) {
            channel.sendTextMessage(context.getChatId(), reply, context);
        }
    }

    /**
     * 发送响应到平台（自动分段）
     */
    private void sendResponse(MessageChannel channel, MessageContext context, String response) {
        // 尝试发送 Markdown 格式
        channel.sendMarkdownMessage(context.getChatId(), response, context);
    }

    /**
     * 获取所有已注册的平台适配器
     */
    public Map<String, MessageChannel> getChannels() {
        return Map.copyOf(channels);
    }
}
