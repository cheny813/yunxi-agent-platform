package io.yunxi.platform.agent.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;

/**
 * 任务清单能力。
 *
 * <p>由框架原生提供：启用后在构建阶段注册任务写入工具与每轮推理前的任务提醒中间件。
 * 任务状态存放于 AgentState，随会话槽位持久化，跨会话续传天然具备。</p>
 *
 * <p>启用条件取定义级开关或全局开关之一，两者独立于计划模式——任务清单不要求先进入计划模式。</p>
 *
 * @author yunxi-agent-platform
 */
public class TaskListCapability implements AgentCapability {

    private static final Logger log = LoggerFactory.getLogger(TaskListCapability.class);

    private final AgentscopeCoreProperties properties;

    public TaskListCapability(AgentscopeCoreProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "taskList";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean supports(AgentDefinition definition) {
        boolean yamlEnabled = definition != null && definition.getPlan() != null
                && definition.getPlan().isTaskList();
        return yamlEnabled || properties.getPlan().isTaskList();
    }

    @Override
    public void configure(HarnessAgent.Builder builder, AgentDefinition definition) {
        builder.enableTaskList(true);
        log.info("Agent '{}' 已启用任务清单", definition.getName());
    }
}
