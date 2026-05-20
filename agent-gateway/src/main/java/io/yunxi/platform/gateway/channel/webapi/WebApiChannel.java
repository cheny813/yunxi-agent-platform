package io.yunxi.platform.gateway.channel.webapi;

import io.yunxi.platform.gateway.channel.ChannelHealth;
import io.yunxi.platform.gateway.channel.MessageChannel;
import io.yunxi.platform.gateway.channel.MessageContext;
import io.yunxi.platform.gateway.model.PlatformType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Web API 消息通道适配器
 *
 * <p>提供 HTTP REST 接口，允许第三方系统通过 Web API 与 Agent 交互。
 * 适用于自定义前端、移动 App、IoT 设备等场景。</p>
 *
 * <p>此适配器既是 MessageChannel 实现，也暴露 REST 接口接收外部消息。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/webapi")
@ConditionalOnProperty(prefix = "gateway.platforms.webapi", name = "enabled", havingValue = "true")
public class WebApiChannel implements MessageChannel {

    private final WebApiProperties properties;
    private BiConsumer<MessageContext, String> messageHandler;

    /** 等待回复的消息（messageId -> response） */
    private final ConcurrentHashMap<String, String> pendingReplies = new ConcurrentHashMap<>();

    public WebApiChannel(WebApiProperties properties) {
        this.properties = properties;
    }

    // ===== MessageChannel 接口实现 =====

    @Override
    public PlatformType getPlatformType() {
        return PlatformType.WEBAPI;
    }

    @Override
    public void connect(BiConsumer<MessageContext, String> handler) {
        this.messageHandler = handler;
        log.info("[WebApi] Web API 通道已启动");
    }

    @Override
    public void disconnect() {
        log.info("[WebApi] Web API 通道已关闭");
    }

    @Override
    public boolean isConnected() {
        return messageHandler != null;
    }

    @Override
    public void sendTextMessage(String chatId, String text, MessageContext context) {
        // Web API 的回复通过 REST 响应返回，存储到 pendingReplies
        String messageId = context != null ? (String) context.getExtra().get("messageId") : null;
        if (messageId != null) {
            pendingReplies.put(messageId, text);
        }
    }

    @Override
    public ChannelHealth health() {
        return ChannelHealth.healthy(PlatformType.WEBAPI);
    }

    // ===== REST 接口 =====

    /**
     * 发送消息（同步模式，等待 Agent 回复）
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "X-Gateway-Token", required = false) String token) {
        // 鉴权
        if (!authenticate(token)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String message = (String) body.get("message");
        String userId = (String) body.getOrDefault("userId", "webapi:anonymous");
        String chatId = (String) body.getOrDefault("chatId", "default");
        String agentName = (String) body.get("agentName");

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        // 生成消息 ID 用于匹配回复
        String messageId = java.util.UUID.randomUUID().toString();

        MessageContext context = MessageContext.builder()
                .platform(PlatformType.WEBAPI)
                .chatId(chatId)
                .chatType("dm")
                .userId(userId)
                .userName("WebAPI User")
                .extra(Map.of("messageId", messageId))
                .build();

        // 如果指定了 Agent，需要设置到会话中
        // （通过 GatewayDispatcher 的斜杠命令机制处理）

        // 触发消息处理
        if (messageHandler != null) {
            messageHandler.accept(context, message);
        }

        // 等待回复（带超时）
        String reply = waitForReply(messageId, 60);
        if (reply != null) {
            return ResponseEntity.ok(Map.of("reply", reply, "messageId", messageId));
        } else {
            return ResponseEntity.ok(Map.of("messageId", messageId, "status", "processing"));
        }
    }

    /**
     * 查询消息回复
     */
    @GetMapping("/reply/{messageId}")
    public ResponseEntity<Map<String, Object>> getReply(@PathVariable String messageId,
                                                         @RequestHeader(value = "X-Gateway-Token", required = false) String token) {
        if (!authenticate(token)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String reply = pendingReplies.remove(messageId);
        if (reply != null) {
            return ResponseEntity.ok(Map.of("reply", reply, "status", "completed"));
        }
        return ResponseEntity.ok(Map.of("status", "processing"));
    }

    private boolean authenticate(String token) {
        if (properties.getApiToken() == null || properties.getApiToken().isBlank()) {
            return true; // 未配置 token = 不鉴权
        }
        return properties.getApiToken().equals(token);
    }

    private String waitForReply(String messageId, long timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            String reply = pendingReplies.get(messageId);
            if (reply != null) {
                pendingReplies.remove(messageId);
                return reply;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
