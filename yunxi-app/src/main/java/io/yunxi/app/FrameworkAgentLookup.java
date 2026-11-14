package io.yunxi.app;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.runtime.AgentLookup;

/**
 * 应用平面薄委托 seam：把运行时 {@link AgentLookup} 接口收口到 AgentScope-Java 框架协议层
 * {@code AguiAgentRegistry}（进程内单例 Agent 真相源）。yunxi 不维护第二份 Agent 注册表，
 * 所有协议（含 yunxi 自有 ingress 经 {@link io.yunxi.runtime.ExecutionFacade}）统一从此读取。
 */
public class FrameworkAgentLookup implements AgentLookup {

    private final AguiAgentRegistry registry;

    /** 收口到框架协议层注册表（进程内 Agent 唯一真相源）。 */
    public FrameworkAgentLookup(AguiAgentRegistry registry) {
        this.registry = registry;
    }

    /** 按 agentId 取出 Agent；未注册时返回 {@code null}。注册进注册表的均为 HarnessAgent，转型安全。 */
    @Override
    public HarnessAgent get(String agentId) {
        // 注册进 AguiAgentRegistry 的均为 HarnessAgent（经 AgentConfigurer 构建），故此处转型安全。
        return (HarnessAgent) registry.getAgent(agentId).orElse(null);
    }
}
