package io.yunxi.platform.business.a2apipeline.registry;

import io.yunxi.platform.framework.a2a.A2ARegistry;
import io.yunxi.platform.business.a2apipeline.model.A2AAgentInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Pipeline Agent 注册表
 * <p>
 * 基于 {@link A2ARegistry} 提供 Pipeline 所需的 Agent 发现能力。
 * 原 A2AAgentRegistry 已合并到 A2ARegistry，本类作为 Pipeline 层适配器。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
public class AgentRegistry {

    /** A2A 注册表，提供 Agent 发现能力 */
    private final A2ARegistry a2aRegistry;

    /**
     * 构造 Agent 注册表
     *
     * @param a2aRegistry A2A 注册表实例
     */
    @Autowired
    public AgentRegistry(A2ARegistry a2aRegistry) {
        this.a2aRegistry = a2aRegistry;
    }

    /**
     * 注册 Agent
     *
     * @param registration Agent 注册信息
     */
    public void register(A2ARegistry.AgentRegistration registration) {
        a2aRegistry.register(registration);
    }

    /**
     * 注销 Agent
     *
     * @param agentName Agent 名称
     */
    public void unregister(String agentName) {
        a2aRegistry.deregister(agentName);
    }

    /**
     * 根据名称选择 Agent 实例
     *
     * @param agentType Agent 类型/名称
     * @return Agent 信息
     */
    public Optional<A2AAgentInfo> select(String agentType) {
        var endpoints = a2aRegistry.discover(agentType);
        if (endpoints == null || endpoints.isEmpty()) {
            return Optional.empty();
        }

        // 选择第一个可用端点
        var endpoint = endpoints.get(0);
        return Optional.of(A2AAgentInfo.builder()
                .agentId(endpoint.name())
                .agentType(endpoint.name())
                .name(endpoint.name())
                .endpoint(endpoint.getUrl())
                .capabilities(List.of())
                .build());
    }

    /**
     * 获取所有健康实例
     *
     * @return Agent 信息列表
     */
    public List<A2AAgentInfo> getAllHealthy() {
        return a2aRegistry.getAllAgents().stream()
                .flatMap(agentName -> a2aRegistry.discover(agentName).stream()
                        .map(endpoint -> A2AAgentInfo.builder()
                                .agentId(endpoint.name())
                                .agentType(endpoint.name())
                                .name(endpoint.name())
                                .endpoint(endpoint.getUrl())
                                .capabilities(List.of())
                                .build()))
                .toList();
    }
}
