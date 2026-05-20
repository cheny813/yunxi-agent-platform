package io.yunxi.platform.gateway.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.gateway.channel.MessageContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 飞书 WebSocket 客户端
 *
 * <p>连接飞书长连接网关，接收消息事件</p>
 *
 * <p>协议流程（WebSocket 模式）：
 * 1. POST https://open.feishu.cn/open-apis/callback/ws/endpoint 获取 WS 地址
 * 2. 连接 WebSocket
 * 3. 收到消息事件后回调 messageHandler
 * </p>
 *
 * <p>注意：完整实现需要飞书 SDK 或手动实现长连接协议。
 * 此处提供框架代码，当 SDK 可用时启用完整功能。</p>
 */
@Slf4j
public class FeishuWebSocketClient {

    private final FeishuProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicReference<String> accessToken = new AtomicReference<>();
    private ScheduledExecutorService heartbeatExecutor;

    private java.util.function.BiConsumer<MessageContext, String> messageHandler;

    public FeishuWebSocketClient(FeishuProperties properties) {
        this.properties = properties;
    }

    /**
     * 连接飞书服务
     */
    public void connect() {
        if (properties.getAppId() == null || properties.getAppId().isBlank()) {
            log.error("[Feishu] 配置不完整：缺少 appId");
            return;
        }

        log.info("[Feishu] 初始化, appId={}***", properties.getAppId().substring(0, Math.min(4, properties.getAppId().length())));

        // Step 1: 获取 tenant_access_token
        try {
            String token = fetchAccessToken();
            if (token != null) {
                accessToken.set(token);
                log.info("[Feishu] 获取 access_token 成功");
            }
        } catch (Exception e) {
            log.error("[Feishu] 获取 access_token 失败", e);
            return;
        }

        // Step 2: 获取 WebSocket 端点
        // TODO: 完整实现需调用飞书 WS endpoint API 并建立 WebSocket 连接
        // String wsUrl = fetchWsEndpoint(accessToken.get());
        // StandardWebSocketClient client = new StandardWebSocketClient();
        // client.execute(handler, wsUrl);

        connected.set(true);
        log.info("[Feishu] 客户端已启动（框架模式，需飞书 SDK 才能完整运行）");

        // 启动 token 刷新
        startTokenRefresh();
    }

    /**
     * 获取 tenant_access_token
     */
    private String fetchAccessToken() {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "app_id", properties.getAppId(),
                "app_secret", properties.getAppSecret()
        );

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            if (response.getBody() != null) {
                Integer code = (Integer) response.getBody().get("code");
                if (code != null && code == 0) {
                    return (String) response.getBody().get("tenant_access_token");
                } else {
                    log.error("[Feishu] 获取 token 失败: {}", response.getBody().get("msg"));
                }
            }
        } catch (Exception e) {
            log.error("[Feishu] 请求 token 失败", e);
        }
        return null;
    }

    /**
     * 启动 token 自动刷新（飞书 token 有效期 2 小时，提前 5 分钟刷新）
     */
    private void startTokenRefresh() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "feishu-token-refresh");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                String token = fetchAccessToken();
                if (token != null) {
                    accessToken.set(token);
                    log.debug("[Feishu] token 已刷新");
                }
            } catch (Exception e) {
                log.warn("[Feishu] token 刷新失败", e);
            }
        }, 115, 115, TimeUnit.MINUTES); // 1小时55分钟刷新
    }

    /**
     * 发送文本消息
     */
    public void sendMessage(String chatId, String msgType, Map<String, Object> content) {
        String token = accessToken.get();
        if (token == null) {
            log.warn("[Feishu] 无有效 token，无法发送消息");
            return;
        }

        // TODO: 调用飞书发消息 API
        // POST https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id
        // Header: Authorization: Bearer {token}
        // Body: { receive_id: chatId, msg_type: msgType, content: JSON(content) }
        log.info("[Feishu] 发送消息: chatId={}, msgType={}", chatId, msgType);
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        connected.set(false);
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        log.info("[Feishu] 已断开连接");
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void setMessageHandler(java.util.function.BiConsumer<MessageContext, String> handler) {
        this.messageHandler = handler;
    }
}
