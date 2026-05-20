package io.yunxi.platform.gateway.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.gateway.channel.MessageContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业微信 WebSocket 客户端
 *
 * <p>连接企业微信 AI Bot WebSocket 网关，实现消息收发</p>
 *
 * <p>协议流程：
 * 1. 连接 wss://openws.work.weixin.qq.com
 * 2. 发送 aibot_subscribe 命令认证
 * 3. 接收 aibot_subscribe_ack 确认
 * 4. 接收 aibot_recv_msg 消息
 * 5. 发送 aibot_send_msg 回复
 * </p>
 */
@Slf4j
public class WeComWebSocketClient {

    private final WeComProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebSocketSession wsSession;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger backoffIndex = new AtomicInteger(0);
    private ScheduledExecutorService reconnectExecutor;

    /** 消息接收回调 */
    private java.util.function.BiConsumer<MessageContext, String> messageHandler;

    public WeComWebSocketClient(WeComProperties properties) {
        this.properties = properties;
    }

    /**
     * 连接企业微信 WebSocket 网关
     */
    public void connect() throws Exception {
        reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wecom-reconnect");
            t.setDaemon(true);
            return t;
        });

        doConnect();
        log.info("[WeComWS] 启动连接，botId={}", properties.getBotId());
    }

    private void doConnect() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String wsUrl = properties.getWsUrl();

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                wsSession = session;
                log.info("[WeComWS] WebSocket 连接已建立");
                // 发送订阅命令
                sendSubscribe(session);
            }

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
                String payload = message.getPayload().toString();
                dispatchPayload(payload);
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
                log.error("[WeComWS] 传输错误", exception);
                connected.set(false);
                scheduleReconnect();
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                log.warn("[WeComWS] 连接关闭: {}", closeStatus);
                connected.set(false);
                scheduleReconnect();
            }

            @Override
            public boolean supportsPartialMessages() {
                return false;
            }
        };

        client.execute(handler, wsUrl);
    }

    /**
     * 发送订阅认证命令
     */
    private void sendSubscribe(WebSocketSession session) throws Exception {
        String reqId = java.util.UUID.randomUUID().toString();
        Map<String, Object> subscribe = Map.of(
                "cmd", "aibot_subscribe",
                "headers", Map.of("req_id", reqId),
                "body", Map.of(
                        "bot_id", properties.getBotId(),
                        "secret", properties.getSecret()
                )
        );
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(subscribe)));
        log.info("[WeComWS] 已发送订阅命令, reqId={}", reqId);
    }

    /**
     * 分发收到的消息
     */
    private void dispatchPayload(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String cmd = root.path("cmd").asText();

            switch (cmd) {
                case "aibot_subscribe_ack" -> {
                    int errCode = root.path("body").path("errcode").asInt(-1);
                    if (errCode == 0) {
                        connected.set(true);
                        backoffIndex.set(0);
                        log.info("[WeComWS] 订阅成功");
                    } else {
                        String errMsg = root.path("body").path("errmsg").asText("unknown");
                        log.error("[WeComWS] 订阅失败: errcode={}, errmsg={}", errCode, errMsg);
                    }
                }
                case "aibot_recv_msg" -> handleIncomingMessage(root);
                case "heartbeat" -> {
                    // 心跳响应，无需处理
                }
                default -> log.debug("[WeComWS] 收到未知命令: {}", cmd);
            }
        } catch (Exception e) {
            log.error("[WeComWS] 解析消息失败: {}", payload, e);
        }
    }

    /**
     * 处理收到的消息
     */
    private void handleIncomingMessage(JsonNode root) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.convertValue(root.path("body"), Map.class);
            MessageContext context = WeComMessageMapper.toMessageContext(body);
            String text = WeComMessageMapper.extractText(body);

            if (text != null && messageHandler != null) {
                messageHandler.accept(context, text);
            }
        } catch (Exception e) {
            log.error("[WeComWS] 处理消息失败", e);
        }
    }

    /**
     * 发送文本消息
     */
    public void sendMessage(String chatId, String chatType, String text, String callbackId) {
        if (wsSession == null || !wsSession.isOpen()) {
            log.warn("[WeComWS] 连接未建立，无法发送消息");
            return;
        }

        try {
            // 按长度限制分段发送
            java.util.List<String> parts = WeComMessageMapper.splitMessage(text, properties.getMaxMessageLength());
            for (String part : parts) {
                Map<String, Object> payload = WeComMessageMapper.buildSendPayload(chatId, chatType, part, callbackId);
                wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (Exception e) {
            log.error("[WeComWS] 发送消息失败", e);
        }
    }

    /**
     * 发送 Markdown 消息
     */
    public void sendMarkdownMessage(String chatId, String chatType, String markdown, String callbackId) {
        if (wsSession == null || !wsSession.isOpen()) {
            log.warn("[WeComWS] 连接未建立，无法发送消息");
            return;
        }

        try {
            java.util.List<String> parts = WeComMessageMapper.splitMessage(markdown, properties.getMaxMessageLength());
            for (String part : parts) {
                Map<String, Object> payload = WeComMessageMapper.buildMarkdownPayload(chatId, chatType, part, callbackId);
                wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (Exception e) {
            log.error("[WeComWS] 发送 Markdown 消息失败", e);
        }
    }

    /**
     * 计划重连
     */
    private void scheduleReconnect() {
        if (reconnectExecutor == null || reconnectExecutor.isShutdown()) {
            return;
        }

        int idx = Math.min(backoffIndex.get(), properties.getReconnectBackoff().size() - 1);
        int delay = properties.getReconnectBackoff().get(idx);
        backoffIndex.incrementAndGet();

        log.info("[WeComWS] 将在 {} 秒后重连（第 {} 次）", delay, backoffIndex.get());
        reconnectExecutor.schedule(() -> {
            try {
                log.info("[WeComWS] 开始重连...");
                doConnect();
            } catch (Exception e) {
                log.error("[WeComWS] 重连失败", e);
                scheduleReconnect();
            }
        }, delay, TimeUnit.SECONDS);
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        connected.set(false);
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
        }
        if (wsSession != null && wsSession.isOpen()) {
            try {
                wsSession.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.warn("[WeComWS] 关闭连接异常", e);
            }
        }
        log.info("[WeComWS] 已断开连接");
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void setMessageHandler(java.util.function.BiConsumer<MessageContext, String> handler) {
        this.messageHandler = handler;
    }
}
