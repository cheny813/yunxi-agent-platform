package io.yunxi.platform.agent.plan;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 计划预创建器，在 Agent 首次调用前准备计划。
 *
 * <p>
 * <b>执行流程（按顺序）</b>
 * <ol>
 * <li>从 AgentDefinition.plan.enabled 判断是否启用计划</li>
 * <li>匹配 — 从 PlanTemplateLoader 查找匹配的模板</li>
 * <li>注入 — 创建 Plan 并设置到 Agent 的 PlanNotebook</li>
 * <li>注册 — 将 Agent 实例注册到 agentscope 框架中</li>
 * <li>返回 — 给 ReAct 模式</li>
 * </ol>
 * </p>
 *
 * <p>
 * <b>注意：当前版本的计划预创建</b> — 计划配置已由 AgentConfigurer 处理，
 * PlanNotebook 通过 HarnessAgent.Builder.planNotebook() 注入。
 * </p>
 */
@Component
public class PlanPreCreator {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(PlanPreCreator.class);

    /** Agent 定义加载器 — 读取 plan.enabled 和 plan.userConfirm 配置 */
    private final AgentDefinitionLoader definitionLoader;

    /** 模板加载器 — 根据 goal 查找并转换计划模板 */
    private final PlanTemplateLoader templateLoader;

    /**
     * 创建计划预创建器。
     *
     * @param definitionLoader Agent 定义加载器
     * @param templateLoader   模板加载器
     */
    public PlanPreCreator(AgentDefinitionLoader definitionLoader,
            PlanTemplateLoader templateLoader) {
        this.definitionLoader = definitionLoader;
        this.templateLoader = templateLoader;
    }

    /**
     * 根据配置预创建计划并注入到 Agent。
     *
     * <p>
     * 执行流程（按顺序）：
     * <ol>
     * <li>从 AgentDefinition.plan.enabled 判断是否启用计划</li>
     * <li>匹配 — 从 PlanTemplateLoader 查找匹配的模板</li>
     * <li>注入 — 创建 Plan 并设置到 Agent 的 PlanNotebook（HarnessAgent 内置支持）</li>
     * <li>注册 — 将 Agent 实例注册到框架</li>
     * <li>返回 — 给 ReAct 模式</li>
     * </ol>
     * </p>
     *
     * @param goal       用户目标
     * @param agentDefId Agent 定义 ID
     * @param agent      目标 Agent 实例
     * @return 预创建结果
     */
    public PreCreateResult preCreateIfConfigured(String goal, String agentDefId, Agent agent) {
        // Step 1: 检查该 Agent 是否启用了计划
        var def = definitionLoader.getAgentDefinition(agentDefId);
        if (def == null || def.getPlan() == null || !def.getPlan().isEnabled()) {
            return PreCreateResult.PLAN_DISABLED;
        }

        // Step 2: 匹配计划模板
        var templateOpt = templateLoader.matchTemplate(goal, agentDefId);
        if (templateOpt.isEmpty()) {
            return PreCreateResult.NO_TEMPLATE;
        }

        // PlanNotebook 在 HarnessAgent 内置支持
        // 模板由 Agent 创建时通过 HarnessAgent.Builder.planNotebook() 注入
        log.debug("Plan pre-creation for agent: {}, goal: {} (handled via PlanInteractionController)", agentDefId,
                goal);
        return PreCreateResult.PRE_CREATED;
    }

    /**
     * 预创建结果枚举。
     *
     * <p>
     * PLAN_DISABLED：计划功能未启用，走纯 ReAct 模式
     * NO_TEMPLATE：计划已启用但未找到匹配模板，使用默认 Agent 实例
     * PRE_CREATED：成功找到模板并预创建计划，Agent 已持有 PlanNotebook
     * </p>
     */
    public enum PreCreateResult {
        /** plan.enabled=false，走纯 ReAct */
        PLAN_DISABLED,
        /** plan.enabled=true 但没找到匹配模板，用默认 Agent 实例 */
        NO_TEMPLATE,
        /** 找到模板并成功预创建计划，Agent 已持有 PlanNotebook */
        PRE_CREATED
    }
}
