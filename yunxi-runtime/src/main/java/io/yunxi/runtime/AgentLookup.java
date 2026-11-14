package io.yunxi.runtime;

import io.agentscope.harness.agent.HarnessAgent;

/**
 * 运行时平面。按 agentId 查找已装配 Agent 的薄委托 seam。
 *
 * <p>yunxi 不维护第二份 Agent 注册表：本接口的实现（应用平面 {@code FrameworkAgentLookup}）直接收口到
 * AgentScope-Java 框架协议层 {@code AguiAgentRegistry}（进程内单例 Agent 真相源）。所有协议（含 yunxi 自有
 * ingress 经 {@link ExecutionFacade}）统一从此读取，保证「同一 Agent 全仓仅一个真相源」。</p>
 */
public interface AgentLookup {

    /** 按 agentId 返回已装配的 {@link HarnessAgent}；不存在时返回 {@code null}。 */
    HarnessAgent get(String agentId);
}
