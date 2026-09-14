package io.yunxi.platform.aistio.reporter;

/**
 * aistio 事件上报抽象。真实实现可对接 gRPC 通道（aistio 团队的实时上报通道）；
 * 当前默认实现为日志桥接（{@link LoggingAistioReporter}）。
 */
public interface AistioEventReporter {

    /** 上行一个运行时事件（如工具调用、推理完成）。 */
    void emitEvent(String sessionId, String type, Object payload);

    /** 上行会话上下文快照。 */
    void emitContext(String sessionId, String context);

    /** 上行编排 / 工具清单（inventory）。 */
    void emitInventory(String agentName, Object topology);
}
