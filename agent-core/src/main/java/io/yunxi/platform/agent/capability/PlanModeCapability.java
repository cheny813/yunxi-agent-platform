package io.yunxi.platform.agent.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;

/**
 * 计划模式能力。
 *
 * <p>由框架原生提供：走 {@code HarnessAgent.Builder} 的 {@code enablePlanMode} 开关，由框架在
 * build 阶段一次性完成三件事 —— 构造计划管理器、注册 {@code plan_enter} / {@code plan_write} /
 * {@code plan_exit} 三个控制工具、装配 {@code PlanModeMiddleware}。</p>
 *
 * <p>此前的实现在此处手工拼装 {@code PlanModeMiddleware}，只做到了三件事中的第一件和第三件：
 * 三工具始终没有注册进 {@code Toolkit}，模型拿不到进入与退出计划的手段，只读阶段因此不可退出。
 * 手工拼装的另一处隐患是自行构造 {@code WorkspaceManager}，其根路径与 Builder 实际生效的工作区根
 * 可能不是同一个，计划文件会落到无人读取的目录。改回原生开关后两处一并消失。</p>
 *
 * <p>只读阶段的放行判定由框架按工具的 {@code readOnly} 元数据完成（见工具上的
 * {@code @Tool(readOnly = true)} 标注），yunxi 不另行提供关键字匹配。</p>
 *
 * <p>启用条件取定义级开关或全局开关之一。</p>
 *
 * @author yunxi-agent-platform
 */
public class PlanModeCapability implements AgentCapability {

    private static final Logger log = LoggerFactory.getLogger(PlanModeCapability.class);

    private final AgentscopeCoreProperties properties;

    public PlanModeCapability(AgentscopeCoreProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "planMode";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public boolean supports(AgentDefinition definition) {
        boolean yamlEnabled = definition != null && definition.getPlan() != null
                && definition.getPlan().isEnabled();
        return yamlEnabled || properties.getPlan().isEnabled();
    }

    @Override
    public void configure(HarnessAgent.Builder builder, AgentDefinition definition) {
        AgentscopeCoreProperties.PlanProperties plan = properties.getPlan();
        boolean allowShell = isYamlAllowShell(definition) || plan.isAllowShellInPlanMode();

        builder.enablePlanMode(true);
        builder.planFileDirectory(plan.getPlanDir());
        builder.allowShellInPlanMode(allowShell);

        log.info("Agent '{}' 已启用计划模式（planDir={}, allowShell={}）",
                definition.getName(), plan.getPlanDir(), allowShell);
    }

    private boolean isYamlAllowShell(AgentDefinition definition) {
        return definition != null && definition.getPlan() != null
                && definition.getPlan().isAllowShellInPlanMode();
    }
}
