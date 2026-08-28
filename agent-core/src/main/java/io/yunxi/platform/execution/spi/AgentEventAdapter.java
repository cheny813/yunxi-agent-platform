package io.yunxi.platform.execution.spi;

import java.util.List;

import io.agentscope.core.event.AgentEvent;
import io.yunxi.platform.execution.ExecutionContext;

/**
 * Agent 事件适配 SPI。
 *
 * <p>把框架事件（{@link AgentEvent}）转换为对端可消费的表示（如 SSE 消息字符串）。
 * 单个事件可产生 0..n 条输出（如 TOOL_CALL_START 同时产生 tool_call 与 agent_status
 * 两条 SSE；AGENT_RESULT 产生多条分块消息）。</p>
 *
 * <p>事件→协议转换与引擎解耦，支持未来新增协议适配器（WebSocket / MQ）。</p>
 *
 * @author yunxi-agent-platform
 */
public interface AgentEventAdapter {

    /**
     * 将单个 Agent 事件转换为 0..n 条输出消息。
     *
     * @param event 框架 Agent 事件
     * @param ctx   执行上下文（含 conversationId/userId/attributes 等）
     * @return 输出消息列表（可为空）
     */
    List<String> convert(AgentEvent event, ExecutionContext ctx);

    /**
     * 流开始时输出（如 SSE start 事件）。
     *
     * <p>协议生命周期钩子：由引擎在事件流适配前调用，消息形态与条数由适配器自决
     * （如携带 conversationId 的 start 消息）。默认无输出。</p>
     *
     * @param ctx 执行上下文（含 conversationId/userId 等）
     * @return 输出消息列表（可为空）
     */
    default List<String> onStart(ExecutionContext ctx) {
        return List.of();
    }

    /**
     * 前置思考提示输出（如 SSE thinking / agent_status 事件）。
     *
     * <p>协议生命周期钩子：思考文本由流式策略写入 ctx 传入，展示形态
     * （thinking 消息或 agent_status 状态消息）由适配器按请求模式（A2A/普通）自决。
     * thinkingText 为 null 时应返回空。</p>
     *
     * @param thinkingText 思考提示文本（可为 null，表示无思考事件）
     * @param ctx          执行上下文
     * @return 输出消息列表（可为空）
     */
    default List<String> onThinking(String thinkingText, ExecutionContext ctx) {
        return List.of();
    }

    /**
     * 流执行错误时输出（如 SSE error 事件兜底）。
     *
     * <p>协议生命周期钩子：由引擎的 onErrorResume 兜底调用。返回 null 表示不输出。</p>
     *
     * @param errorMessage 已格式化的错误消息
     * @param ctx           执行上下文
     * @return 错误消息（null 表示不输出）
     */
    default String onError(String errorMessage, ExecutionContext ctx) {
        return null;
    }
}
