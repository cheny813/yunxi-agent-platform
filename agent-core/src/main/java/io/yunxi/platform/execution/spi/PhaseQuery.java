package io.yunxi.platform.execution.spi;

import io.yunxi.platform.execution.context.PhaseTrace;

/**
 * 阶段轨迹查询 SPI（驻留 agent-core）。
 *
 * <p>入口网关（agent-ingress）查询某 Agent 最近一次执行阶段轨迹时只依赖本接口，
 * 而非依赖 agent-execution 的具体 {@code PhaseMiddleware} 实现，从而断开循环依赖。
 * 实现位于 agent-execution，经 Spring 注入。</p>
 *
 * @author yunxi-agent-platform
 */
public interface PhaseQuery {

    /**
     * 取指定 Agent + 会话的最近一次阶段轨迹。
     *
     * @param agentName  Agent 名称
     * @param sessionId 会话标识
     * @return 阶段轨迹（含阶段序列与时间戳），无记录时返回空轨迹
     */
    PhaseTrace getLastPhaseTrace(String agentName, String sessionId);
}
