package io.yunxi.platform.framework.plan;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 配置驱动的规划预创建器。
 * <p>
 * <b>核心逻辑（不是运行时检测，是配置驱动）：</b>
 * <ol>
 * <li>查 AgentDefinition.plan.enabled — 配置是否启用规划能力</li>
 * <li>若启用 → 查 PlanTemplateLoader 是否有匹配模板</li>
 * <li>有模板 → 创建 Plan 并注入 ReActAgent 的 PlanNotebook</li>
 * <li>无模板 → 不介入，让 Agent 自驱动创建规划（agentscope 原有行为）</li>
 * <li>未启用 → 不介入，纯 ReAct 模式</li>
 * </ol>
 * </p>
 *
 * <b>不进行运行时"复杂度检测"</b> — 配置即行为。
 */
@Component
public class PlanPreCreator {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(PlanPreCreator.class);

    /** Agent 定义加载器 — 读取 plan.enabled、plan.userConfirm 等配置 */
    private final AgentDefinitionLoader definitionLoader;

    /** 规划模板加载器 — 根据 goal 匹配预定义规划模板 */
    private final PlanTemplateLoader templateLoader;

    /**
     * 构造规划预创建器
     *
     * @param definitionLoader Agent 定义加载器
     * @param templateLoader   规划模板加载器
     */
    public PlanPreCreator(AgentDefinitionLoader definitionLoader,
            PlanTemplateLoader templateLoader) {
        this.definitionLoader = definitionLoader;
        this.templateLoader = templateLoader;
    }

    /**
     * 根据配置和模板预创建规划。
     * <p>
     * 核心逻辑（配置驱动，非运行时检测）：
     * <ol>
     * <li>查 AgentDefinition.plan.enabled — 配置是否启用规划能力</li>
     * <li>若启用 → 查 PlanTemplateLoader 是否有匹配模板</li>
     * <li>有模板 → 创建 Plan 并注入 Agent 的 PlanNotebook（由 HarnessAgent 内部管理）</li>
     * <li>无模板 → 不介入，让 Agent 自驱动创建规划</li>
     * <li>未启用 → 不介入，纯 ReAct 模式</li>
     * </ol>
     * </p>
     *
     * @param goal       用户请求目标
     * @param agentDefId Agent 定义名称
     * @param agent      已构建的 Agent 实例
     * @return 预创建结果枚举
     */
    public PreCreateResult preCreateIfConfigured(String goal, String agentDefId, Agent agent) {
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

        // PlanNotebook 由 HarnessAgent 内部管理
        // 规划注入在 Agent 构建时通过 HarnessAgent.Builder.planNotebook() 完成
        log.debug("Plan pre-creation for agent: {}, goal: {} (handled via PlanInteractionController)", agentDefId,
                goal);
        return PreCreateResult.PRE_CREATED;
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
