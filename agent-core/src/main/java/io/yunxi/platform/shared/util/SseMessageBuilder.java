package io.yunxi.platform.shared.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.event.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

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
            // 仅返回 JSON 负载；data: 前缀与 \\n\\n 事件分隔符由 Spring 的 text/event-stream
            // 流式响应自动封装，避免在 Flux<String> 场景下出现双重 data: 前缀导致客户端解析失败。
            return objectMapper.writeValueAsString(message);
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
            // 仅返回 JSON 负载；data: 前缀由 Spring 流式响应自动封装
            return objectMapper.writeValueAsString(message);
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
            // 仅返回 JSON 负载；data: 前缀与 \n\n 事件分隔符由 Spring 的 text/event-stream 流式响应自动封装，
            // 避免在 Flux<String> 场景下出现双重 data: 前缀导致客户端解析失败。
            log.info("构建 SSE 消息（带会话ID） - type: {}, conversationId: {}, content length: {}",
                    type, conversationId, content != null ? content.length() : 0);
            return objectMapper.writeValueAsString(message);
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
     * 构建 Agent 状态事件消息（与 thinking 区分，前端按行独立展示状态文本）。
     *
     * @param statusText 状态文本（如"正在执行: 读取文件"、"读取文件 完成"）
     * @return SSE 格式的状态消息
     */
    public String buildAgentStatusMessage(String statusText) {
        return buildMessage("agent_status", statusText);
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
     * 构建工具调用开始事件消息。
     * <p>
     * 前端收到 type=tool_call 事件后，应在消息气泡下方显示工具调用指示器。
     * 数据透传自 AgentScope 的 {@code ToolCallStartEvent}。
     * </p>
     *
     * @param toolCallId   工具调用唯一标识
     * @param toolCallName 工具名称（如 search_web、execute_sql）
     * @return SSE 格式的工具调用消息
     */
    public String buildToolCallMessage(String toolCallId, String toolCallName) {
        return buildMessage("tool_call", toJsonString(Map.of(
                "toolCallId", toolCallId,
                "toolCallName", toolCallName
        )));
    }

    /**
     * 构建工具调用结束事件消息。
     * <p>
     * 前端收到 type=tool_call_done 事件后，应标记参数组装完成。
     * 数据透传自 AgentScope 的 {@code ToolCallEndEvent}。
     * </p>
     *
     * @param toolCallId   工具调用唯一标识
     * @param toolCallName 工具名称
     * @return SSE 格式的工具调用完成消息
     */
    public String buildToolCallDoneMessage(String toolCallId, String toolCallName) {
        return buildMessage("tool_call_done", toJsonString(Map.of(
                "toolCallId", toolCallId,
                "toolCallName", toolCallName
        )));
    }

    /**
     * 构建工具结果事件消息。
     * <p>
     * 前端收到 type=tool_result 事件后，应更新工具调用指示器的状态。
     * 数据透传自 AgentScope 的 {@code ToolResultEndEvent}。
     * </p>
     *
     * @param toolCallId   工具调用唯一标识
     * @param toolCallName 工具名称
     * @param state        执行结果（success/error/interrupted/denied/running）
     * @return SSE 格式的工具结果消息
     */
    public String buildToolResultMessage(String toolCallId, String toolCallName, String state) {
        return buildMessage("tool_result", toJsonString(Map.of(
                "toolCallId", toolCallId,
                "toolCallName", toolCallName,
                "state", state != null ? state : "unknown"
        )));
    }

    /**
     * 构建工具流式输出开始事件消息。
     * <p>
     * 前端收到 type=tool_result_start 事件后，应在对应工具卡片内打开「输出」区域，
     * 准备接收后续的流式进度文本（{@code tool_result_delta}）。
     * 数据透传自 AgentScope 的 {@code ToolResultStartEvent}。
     * </p>
     *
     * @param toolCallId   工具调用唯一标识
     * @param toolCallName 工具名称
     * @return SSE 格式的工具流式输出开始消息
     */
    public String buildToolResultStartMessage(String toolCallId, String toolCallName) {
        return buildMessage("tool_result_start", toJsonString(Map.of(
                "toolCallId", toolCallId,
                "toolCallName", toolCallName
        )));
    }

    /**
     * 构建工具流式输出进度事件消息。
     * <p>
     * 前端收到 type=tool_result_delta 事件后，应将 {@code delta} 追加到对应工具卡片的
     * 「输出」区域，形成打字机式实时进度。
     * 数据透传自 AgentScope 的 {@code ToolResultTextDeltaEvent}。
     * </p>
     *
     * @param toolCallId   工具调用唯一标识
     * @param toolCallName 工具名称
     * @param delta        增量输出文本
     * @return SSE 格式的工具流式进度消息
     */
    public String buildToolResultDeltaMessage(String toolCallId, String toolCallName, String delta) {
        return buildMessage("tool_result_delta", toJsonString(Map.of(
                "toolCallId", toolCallId,
                "toolCallName", toolCallName,
                "delta", delta != null ? delta : ""
        )));
    }

    /**
     * 透传 AgentScope 原生事件给前端。
     * <p>
     * 将 AgentEvent 完整序列化为 JSON，SSE 消息的 type 为事件类型名
     * （如 "TOOL_CALL_DELTA"、"SUBAGENT_EXPOSED" 等），content 为事件 JSON。
     * 前端 {@code JSON.parse(data.content)} 即可获得完整事件数据。
     * </p>
     *
     * @param event AgentScope 原生事件
     * @return SSE 格式的消息
     */
    public String buildAgentEvent(AgentEvent event) {
        try {
            String eventJson = objectMapper.writeValueAsString(event);
            return buildMessage(event.getType().getValue(), eventJson);
        } catch (Exception e) {
            log.error("序列化 Agent 事件失败: type={}", event.getType().getValue(), e);
            return buildErrorMessage("事件序列化失败");
        }
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
