package io.yunxi.capability;

import io.agentscope.core.agent.RuntimeContext;

/**
 * 能力平面。决定某个 Agent 在给定轮次应使用哪个模型（及兜底模型）。运行时平面在每次装配时咨询它；它必须协议无关。
 */
public interface ModelRoutingPolicy {

    /** 把给定 Agent / 意图解析为带兜底序列的路由结果（协议无关，运行时每次装配时咨询）。 */
    RoutedModel route(String agentId, String intent, RuntimeContext ctx);

    /**
     * 自动选型：按任务信号从候选池（catalog 中 type=llm 的全部项）动态选择模型。
     * 默认实现不支持，由 {@link ConfigurableModelRoutingPolicy} 提供具体实现。
     *
     * @param agentId 智能体标识
     * @param signals 由请求消息与生成选项提取的任务信号
     * @return 带候选序列的路由结果（auto=false）
     */
    default RoutedModel resolveAuto(String agentId, TaskSignals signals) {
        throw new UnsupportedOperationException(
                "auto model routing is not supported by " + getClass().getName());
    }
}
