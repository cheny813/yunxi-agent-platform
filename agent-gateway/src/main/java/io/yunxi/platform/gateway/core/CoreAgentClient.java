package io.yunxi.platform.gateway.core;

import io.yunxi.platform.gateway.GatewayProperties;
import io.yunxi.platform.shared.dto.ChatResponse;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * agent-core HTTP + SSE 客户端
 *
 * <p>通过 REST API 调用 agent-core 的 Chat 接口</p>
 */
@Slf4j
public class CoreAgentClient {

    private final WebClient webClient;
    private final GatewayProperties properties;

    public CoreAgentClient(WebClient webClient, GatewayProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    /**
     * 同步调用 Chat API
     */
    public ChatResponse chatSync(UnifiedChatRequest request) {
        try {
            return webClient.post()
                    .uri("/api/conversations/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ChatResponse.class)
                    .block(Duration.ofSeconds(properties.getConcurrency().getRequestTimeoutSeconds()));
        } catch (Exception e) {
            log.error("[CoreAgentClient] 同步调用失败", e);
            return null;
        }
    }

    /**
     * 流式调用 Chat API，返回 SSE 事件流
     */
    public Flux<SseEvent> chatStream(UnifiedChatRequest request) {
        return webClient.post()
                .uri("/api/conversations/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .map(this::parseSseEvent)
                .filter(event -> event != null)
                .timeout(Duration.ofSeconds(properties.getConcurrency().getRequestTimeoutSeconds()));
    }

    /**
     * 创建新会话
     */
    public String createConversation(String userId, String agentName) {
        // 通过发送一条空消息触发会话创建，获取 conversationId
        // 实际由 GatewaySessionManager 管理
        return null;
    }

    /**
     * 删除会话
     */
    public void deleteConversation(String conversationId) {
        try {
            webClient.delete()
                    .uri("/api/conversations/{id}", conversationId)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("[CoreAgentClient] 删除会话失败: {}", conversationId, e);
        }
    }

    /**
     * 解析 SSE 事件
     *
     * <p>格式：{"type":"...","timestamp":"...","content":"..."}</p>
     */
    private SseEvent parseSseEvent(String data) {
        try {
            if (data == null || data.isBlank()) {
                return null;
            }
            // 简单解析 JSON 格式的 SSE 数据
            String type = extractJsonValue(data, "type");
            String content = extractJsonValue(data, "content");
            return new SseEvent(type, content);
        } catch (Exception e) {
            log.debug("[CoreAgentClient] 解析 SSE 事件失败: {}", data);
            return null;
        }
    }

    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) {
            // 尝试非字符串值
            pattern = "\"" + key + "\":";
            start = json.indexOf(pattern);
            if (start < 0) return null;
            start += pattern.length();
            int end = json.indexOf(",", start);
            if (end < 0) end = json.indexOf("}", start);
            return end > start ? json.substring(start, end).trim().replace("\"", "") : null;
        }
        start += pattern.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    /**
     * SSE 事件记录
     */
    public record SseEvent(String type, String content) {
        public boolean isDone() {
            return "done".equals(type);
        }
        public boolean isError() {
            return "error".equals(type);
        }
        public boolean isContent() {
            return "content".equals(type);
        }
        public boolean isThinking() {
            return "thinking".equals(type);
        }
    }
}
