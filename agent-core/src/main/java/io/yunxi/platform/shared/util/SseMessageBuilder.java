package io.yunxi.platform.shared.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * SSE 消息构建器
 * <p>
 * 负责构建 Server-Sent Events 格式的消息，支持多种事件类型
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class SseMessageBuilder {

    private static final Logger log = LoggerFactory.getLogger(SseMessageBuilder.class);

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * 构建 SSE 消息
     *
     * @param type    消息类型
     * @param content 消息内容
     * @return SSE 格式的字符串
     */
    public String buildMessage(String type, String content) {
        try {
            SseMessage message = new SseMessage(type, Instant.now(), content);
            String sseMessage = "data: " + objectMapper.writeValueAsString(message) + "\n\n";
            log.info("构建 SSE 消息 - type: {}, content length: {}",
                    type, content != null ? content.length() : 0);
            return sseMessage;
        } catch (Exception e) {
            log.error("构建 SSE 消息失败", e);
            return buildErrorMessage("内部错误");
        }
    }

    /**
     * 构建 SSE 消息（带请求ID）
     *
     * @param type      消息类型
     * @param content   消息内容
     * @param requestId 请求ID
     * @return SSE 格式的字符串
     */
    public String buildMessageWithRequestId(String type, String content, String requestId) {
        try {
            SseMessageWithRequestId message = new SseMessageWithRequestId(type, Instant.now(), content, requestId);
            String sseMessage = "data: " + objectMapper.writeValueAsString(message) + "\n\n";
            log.info("构建 SSE 消息（带请求ID） - type: {}, requestId: {}, content length: {}",
                    type, requestId, content != null ? content.length() : 0);
            return sseMessage;
        } catch (Exception e) {
            log.error("构建 SSE 消息（带请求ID）失败", e);
            return buildErrorMessage("内部错误");
        }
    }

    /**
     * 构建开始事件消息
     *
     * @return SSE 格式的开始消息
     */
    public String buildStartMessage() {
        return buildMessage("start", null);
    }

    /**
     * 构建开始事件消息（带会话ID）
     *
     * @param conversationId 会话ID
     * @return SSE 格式的开始消息
     */
    public String buildStartMessage(String conversationId) {
        return buildMessage("start", conversationId);
    }

    /**
     * 构建开始事件消息（带请求ID）
     *
     * @param requestId 请求ID
     * @return SSE 格式的开始消息
     */
    public String buildStartMessageWithRequestId(String requestId) {
        return buildMessageWithRequestId("start", null, requestId);
    }

    /**
     * 构建开始事件消息（带会话ID）
     *
     * @param conversationId 会话ID
     * @return SSE 格式的开始消息
     */
    public String buildStartMessageWithConversationId(String conversationId) {
        return buildMessageWithConversationId("start", null, conversationId);
    }

    /**
     * 构建 SSE 消息（带会话ID）
     *
     * @param type           消息类型
     * @param content        消息内容
     * @param conversationId 会话ID
     * @return SSE 格式的字符串
     */
    public String buildMessageWithConversationId(String type, String content, String conversationId) {
        try {
            SseMessageWithConversationId message = new SseMessageWithConversationId(type, Instant.now(), content,
                    conversationId);
            String sseMessage = "data: " + objectMapper.writeValueAsString(message) + "\n\n";
            log.info("构建 SSE 消息（带会话ID） - type: {}, conversationId: {}, content length: {}",
                    type, conversationId, content != null ? content.length() : 0);
            return sseMessage;
        } catch (Exception e) {
            log.error("构建 SSE 消息（带会话ID）失败", e);
            return buildErrorMessage("内部错误");
        }
    }

    /**
     * 构建思考事件消息
     *
     * @param thinkingContent 思考内容
     * @return SSE 格式的思考消息
     */
    public String buildThinkingMessage(String thinkingContent) {
        return buildMessage("thinking", thinkingContent);
    }

    /**
     * 构建内容事件消息
     *
     * @param content 内容片段
     * @return SSE 格式的消息
     */
    public String buildContentMessage(String content) {
        return buildMessage("content", content);
    }

    /**
     * 构建完成事件消息
     *
     * @return SSE 格式的完成消息
     */
    public String buildDoneMessage() {
        return buildMessage("done", null);
    }

    /**
     * 构建错误事件消息
     *
     * @param error 错误信息
     * @return SSE 格式的错误消息
     */
    public String buildErrorMessage(String error) {
        return buildMessage("error", error);
    }

    /**
     * 构建取消事件消息
     *
     * @param requestId 请求ID
     * @return SSE 格式的取消消息
     */
    public String buildCancelledMessage(String requestId) {
        return buildMessageWithRequestId("cancelled", "请求被客户端取消", requestId);
    }

    /**
     * 构建规划等待确认事件消息。
     * <p>
     * 前端收到 type=plan 事件后，应展示规划确认卡片，
     * 等待用户确认/修改后再继续执行。
     * </p>
     *
     * @param planJson 规划数据的 JSON 字符串
     * @return SSE 格式的规划事件消息
     */
    public String buildPlanMessage(String planJson) {
        return buildMessage("plan", planJson);
    }

    /**
     * 构建规划进度更新事件消息。
     * <p>
     * 前端收到 type=plan_progress 事件后，
     * 应更新规划卡片上的步骤状态（✅完成/⏳执行中/⬜等待）。
     * </p>
     *
     * @param progressJson 进度数据的 JSON 字符串
     * @return SSE 格式的规划进度事件消息
     */
    public String buildPlanProgressMessage(String progressJson) {
        return buildMessage("plan_progress", progressJson);
    }

    /**
     * 将对象转换为 JSON 字符串（用于 SSE 响应）
     *
     * @param object 要转换的对象
     * @return JSON 字符串
     */
    public String toJsonString(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            log.error("将对象转换为JSON失败", e);
            return buildErrorMessage("JSON序列化失败: " + e.getMessage());
        }
    }

    /**
     * SSE 消息数据结构
     */
    private record SseMessage(
            String type,
            Instant timestamp,
            String content) {
    }

    /**
     * SSE 消息数据结构（带会话ID）
     */
    private record SseMessageWithConversationId(
            String type,
            Instant timestamp,
            String content,
            String conversationId) {
    }

    /**
     * SSE 消息数据结构（带请求ID）
     */
    private record SseMessageWithRequestId(
            String type,
            Instant timestamp,
            String content,
            String requestId) {
    }
}
