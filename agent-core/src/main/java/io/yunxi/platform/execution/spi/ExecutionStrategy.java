package io.yunxi.platform.execution.spi;

import io.yunxi.platform.execution.ExecutionContext;

/**
 * 执行策略：拦截器链执行完后，按模式（阻塞/流式）驱动 Agent 调用并产出结果。
 *
 * <p>策略只负责"如何调用 Agent 并收口结果"，不关心拦截器链细节。两种模式：
 * <ul>
 *   <li>{@code BlockingStrategy}：聚合完整回复（QUICK/BLOCKING 通道）；</li>
 *   <li>{@code StreamingStrategy}：返回 Flux&lt;AgentEvent&gt; 供 SSE 推送。</li>
 * </ul>
 * 两者均统一经事件算子链（Metrics/PhaseTracker），保证可观测性一致。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public interface ExecutionStrategy {

    /**
     * 是否处理该模式（由 ctx.getRequest().isQuickMode() 决定）。
     */
    boolean supports(ExecutionContext ctx);

    /**
     * 执行：在拦截器链已填充 ctx 后调用。
     *
     * @param ctx 已填充的上下文
     * @return 模式相关结果对象（Blocking 返回 Msg，Streaming 返回 Flux&lt;AgentEvent&gt; 包装）
     */
    Object execute(ExecutionContext ctx);
}
