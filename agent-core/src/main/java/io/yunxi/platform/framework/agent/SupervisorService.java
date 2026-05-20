package io.yunxi.platform.framework.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supervisor 模式服务
 * <p>
 * 实现多 Agent 协作的 Supervisor 模式，一个主管 Agent 协调多个专家 Agent。
 * 提供通用的专家 Agent 注册、Toolkit 创建等功能。
 * </p>
 *
 * <h3>架构示例</h3>
 * 
 * <pre>
 * 用户 → Supervisor
 *          │
 *          ├→ call_expert_1 → 专家 Agent 1
 *          ├→ call_expert_2 → 专家 Agent 2
 *          └→ call_expert_3 → 专家 Agent 3
 * </pre>
 *
 * <h3>使用示例</h3>
 * 
 * <pre>
 * // 1. 注册专家 Agent
 * supervisorService.registerExpertAgent("expert-1", expertAgent);
 *
 * // 2. 创建 Toolkit
 * Map&lt;String, ReActAgent&gt; experts = Map.of("expert-1", expertAgent);
 * Toolkit toolkit = supervisorService.createSupervisorToolkit(experts);
 *
 * // 3. 创建 Supervisor Agent
 * ReActAgent supervisor = ReActAgent.builder()
 *         .name("my-supervisor")
 *         .sysPrompt(supervisorPrompt)
 *         .model(model)
 *         .toolkit(toolkit)
 *         .enablePlan()
 *         .build();
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 3.4.0
 */
@Slf4j
@Service
public class SupervisorService {

    /**
     * 专家 Agent 缓存（key: expert name, value: Agent 实例）
     */
    private final Map<String, ReActAgent> expertAgents = new ConcurrentHashMap<>();

    /**
     * Supervisor 配置缓存（key: supervisor name, value: expert names）
     */
    private final Map<String, List<String>> supervisorConfig = new ConcurrentHashMap<>();

    /**
     * 注册专家 Agent
     *
     * @param expertName 专家名称
     * @param agent      Agent 实例
     */
    public void registerExpertAgent(String expertName, ReActAgent agent) {
        expertAgents.put(expertName, agent);
        log.info("注册专家 Agent: {}", expertName);
    }

    /**
     * 获取专家 Agent
     *
     * @param expertName 专家名称
     * @return Agent 实例，不存在则返回 null
     */
    public ReActAgent getExpertAgent(String expertName) {
        return expertAgents.get(expertName);
    }

    /**
     * 注册 Supervisor 配置
     *
     * @param supervisorName Supervisor 名称
     * @param expertNames    专家名称列表
     */
    public void registerSupervisorConfig(String supervisorName, List<String> expertNames) {
        supervisorConfig.put(supervisorName, expertNames);
        log.info("注册 Supervisor 配置: {} -> {}", supervisorName, expertNames);
    }

    /**
     * 获取 Supervisor 的专家列表
     *
     * @param supervisorName Supervisor 名称
     * @return 专家名称列表
     */
    public List<String> getSupervisorExperts(String supervisorName) {
        return supervisorConfig.getOrDefault(supervisorName, Collections.emptyList());
    }

    /**
     * 创建 Supervisor Toolkit
     * <p>
     * 将专家 Agent 注册为 Supervisor 的工具
     * </p>
     *
     * @param experts 专家 Agent 映射（name -> Agent）
     * @return 配置好的 Toolkit
     */
    public Toolkit createSupervisorToolkit(Map<String, ReActAgent> experts) {
        Toolkit toolkit = new Toolkit();

        for (Map.Entry<String, ReActAgent> entry : experts.entrySet()) {
            String expertName = entry.getKey();
            ReActAgent expertAgent = entry.getValue();

            final ReActAgent finalAgent = expertAgent;

            toolkit.registration()
                    .subAgent(() -> finalAgent, SubAgentConfig.builder().forwardEvents(false).build())
                    .apply();

            log.info("Supervisor 注册专家工具: {}", expertName);
        }

        return toolkit;
    }

    /**
     * 获取所有已注册的专家名称
     */
    public Set<String> getRegisteredExperts() {
        return Collections.unmodifiableSet(expertAgents.keySet());
    }

    /**
     * 清除所有缓存
     */
    public void clear() {
        expertAgents.clear();
        supervisorConfig.clear();
        log.info("Supervisor 缓存已清除");
    }
}
