package io.yunxi.platform.agent.capability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.shared.config.AgentDefinition;

/**
 * Agent 能力装配注册表。
 *
 * <p>收集容器中的全部 {@link AgentCapability} 实现并按顺序装配。新增一类能力只需提供实现类，
 * 装配主流程无需改动。</p>
 *
 * <p>装配顺序由各能力的 {@link AgentCapability#order()} 决定。装配按顺序执行，若某项能力
 * 在装配中抛错，会记录日志并继续后续能力，避免单项配置问题导致 Agent 完全不可用。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AgentCapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentCapabilityRegistry.class);

    private final List<AgentCapability> capabilities;

    public AgentCapabilityRegistry(List<AgentCapability> capabilities) {
        List<AgentCapability> sorted = new ArrayList<>(capabilities);
        sorted.sort(Comparator.comparingInt(AgentCapability::order));
        this.capabilities = List.copyOf(sorted);
    }

    /**
     * 已注册的能力名称，按装配顺序。
     *
     * @return 能力名称列表
     */
    public List<String> capabilityNames() {
        return capabilities.stream().map(AgentCapability::name).toList();
    }

    /**
     * 按顺序装配全部适用能力。
     *
     * @param builder    Agent Builder
     * @param definition Agent 定义
     */
    public void applyAll(HarnessAgent.Builder builder, AgentDefinition definition) {
        for (AgentCapability capability : capabilities) {
            try {
                if (!capability.supports(definition)) {
                    continue;
                }
                capability.configure(builder, definition);
            } catch (Exception e) {
                log.warn("能力装配失败: capability={}, agent={}: {}",
                        capability.name(), definition.getName(), e.getMessage(), e);
            }
        }
    }
}
