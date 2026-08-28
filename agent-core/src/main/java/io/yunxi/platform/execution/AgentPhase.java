package io.yunxi.platform.execution;

/**
 * 用户可见执行阶段（事件流推导）。
 *
 * <p>由 {@code PhaseTracker} 对 {@code Flux<AgentEvent>} 做纯函数阶段推导
 * （事件类型 → 阶段），仅在阶段切换时以 {@code CustomEvent(name="agent_status")}
 * 形式注入一条阶段标记，SSE 适配器将其转换为 {@code agent_status} 协议负载，
 * 前端据此渲染状态视图。</p>
 *
 * @author yunxi-agent-platform
 */
public enum AgentPhase {

    /** 初始 / 前置链完成 */
    IDLE("准备中"),
    /** 推理中（MODEL_CALL_START / THINKING_BLOCK_START） */
    THINKING("思考中"),
    /** 调用工具（TOOL_CALL_START） */
    TOOL_CALL("调用工具"),
    /** 生成回答（TEXT_BLOCK_START / TEXT_BLOCK_DELTA / AGENT_RESULT） */
    ANSWER("生成回答"),
    /** 执行出错（异常 / ERROR 事件） */
    ERROR("执行出错"),
    /** 流完成 */
    DONE("处理完成");

    private final String label;

    AgentPhase(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
