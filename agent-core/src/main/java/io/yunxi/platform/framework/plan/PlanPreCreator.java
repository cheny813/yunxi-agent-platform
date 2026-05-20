package io.yunxi.platform.framework.plan;

import io.agentscope.core.ReActAgent;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 配置驱动的规划预创建器。
 * <p>
 * <b>核心逻辑（不是运行时检测，是配置驱动）：</b>
 * <ol>
 *   <li>查 AgentDefinition.plan.enabled — 配置是否启用规划能力</li>
 *   <li>若启用 → 查 PlanTemplateLoader 是否有匹配模板</li>
 *   <li>有模板 → 创建 Plan 并注入 ReActAgent 的 PlanNotebook</li>
 *   <li>无模板 → 不介入，让 Agent 自驱动创建规划（agentscope 原有行为）</li>
 *   <li>未启用 → 不介入，纯 ReAct 模式</li>
 * </ol>
 * </p>
 *
 * <b>不进行运行时"复杂度检测"</b> — 配置即行为。
 */
@Component
public class PlanPreCreator {

    private static final Logger log = LoggerFactory.getLogger(PlanPreCreator.class);

    private final AgentDefinitionLoader definitionLoader;
    private final PlanTemplateLoader templateLoader;

    public PlanPreCreator(AgentDefinitionLoader definitionLoader,
                          PlanTemplateLoader templateLoader) {
        this.definitionLoader = definitionLoader;
        this.templateLoader = templateLoader;
    }

    /**
     * 根据配置和模板预创建规划。
     * <p>
     * 仅在 plan.enabled=true 且匹配到模板时创建规划。
     * 创建后规划已注入 Agent 的 PlanNotebook，前端可查询并等待用户确认。
     * </p>
     *
     * @param goal      用户请求目标
     * @param agentDefId Agent 定义名称
     * @param agent      已构建的 ReActAgent 实例
     * @return 预创建结果
     */
    public PreCreateResult preCreateIfConfigured(String goal, String agentDefId, ReActAgent agent) {
        // Step 1: 查配置 —— 此 Agent 是否启用了规划能力?
        var def = definitionLoader.getAgentDefinition(agentDefId);
        if (def == null || def.getPlan() == null || !def.getPlan().isEnabled()) {
            return PreCreateResult.PLAN_DISABLED;
        }

        // Step 2: 查配置 —— 是否有匹配的模板?
        var templateOpt = templateLoader.matchTemplate(goal, agentDefId);
        if (templateOpt.isEmpty()) {
            return PreCreateResult.NO_TEMPLATE;
        }

        // Step 3: 创建规划并注入 Agent 的 PlanNotebook
        var plan = templateOpt.get();
        var planNotebook = agent.getPlanNotebook();
        if (planNotebook == null) {
            log.warn("Agent {} 未配置 PlanNotebook，跳过模板规划", agentDefId);
            return PreCreateResult.PLAN_DISABLED;
        }

        try {
            // 使用 PlanNotebook 的 createPlanWithSubTasks 方法注入规划
            planNotebook.createPlanWithSubTasks(
                    plan.getName(),
                    plan.getDescription(),
                    plan.getExpectedOutcome(),
                    plan.getSubtasks()
            );
            log.info("已为 Agent {} 预创建规划: {}, 共 {} 个子任务",
                    agentDefId, plan.getName(),
                    plan.getSubtasks() != null ? plan.getSubtasks().size() : 0);
            return PreCreateResult.PRE_CREATED;
        } catch (Exception e) {
            log.error("预创建规划失败: agent={}, goal={}", agentDefId, goal, e);
            return PreCreateResult.NO_TEMPLATE;
        }
    }

    /**
     * 预创建结果枚举。
     */
    public enum PreCreateResult {
        /** plan.enabled=false → 纯 ReAct */
        PLAN_DISABLED,
        /** plan.enabled=true 但无匹配模板 → 让 Agent 自驱动 */
        NO_TEMPLATE,
        /** 已通过模板预创建规划，等待用户确认 */
        PRE_CREATED
    }
}