package io.yunxi.platform.aistio.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.agent.service.AgentService;
import lombok.RequiredArgsConstructor;

/**
 * 将 yunxi 运行时 Agent 解析为 AgentScope-Java 的 {@link HarnessAgent}，用于访问其 delegate / stateStore /
 * plan-mode / task 等运行时能力。
 *
 * <p>注意：{@link AgentService#getAgentInstance(String)} 返回的是由 AgentConfigurer 经
 * {@code registerAgentInstance} 注册的<b>已配置共享 Agent</b>（含 stateStore），可直接用于
 * 读取 / 写入会话状态（对应 aistio 治理能力的运行时接入点）。</p>
 */
@Component
@RequiredArgsConstructor
public class HarnessAgentResolver {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentResolver.class);

    private final AgentService agentService;

    /**
     * 按 Agent 名解析运行时 {@link HarnessAgent}。
     *
     * @param agentName 智能体名称
     * @return 解析到的 HarnessAgent（可能为 empty）
     */
    public Optional<HarnessAgent> resolve(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return Optional.empty();
        }
        try {
            Agent agent = agentService.getAgentInstance(agentName);
            if (agent instanceof HarnessAgent harness) {
                return Optional.of(harness);
            }
            log.warn("Agent '{}' 不是 HarnessAgent，无法解析运行时状态", agentName);
        } catch (Exception e) {
            log.warn("解析 HarnessAgent 失败 ({}): {}", agentName, e.getMessage());
        }
        return Optional.empty();
    }
}
