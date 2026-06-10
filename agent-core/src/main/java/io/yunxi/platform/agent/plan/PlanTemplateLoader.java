package io.yunxi.platform.agent.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;
import io.yunxi.platform.agent.plan.model.OrchestratedSubTask;
import io.yunxi.platform.agent.plan.model.PlanTemplate;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;

/**
 * 计划模板加载器。
 *
 * <p>
 * 从 AgentDefinition YAML 的 plan.templates 配置加载模板，
 * 根据用户目标匹配并转换为 agentscope Plan 实例。
 * </p>
 *
 * <p>
 * <b>匹配规则:</b>
 * 用户目标字符串中包含模板的 match 关键字即视为匹配。
 * 例如 match: "分析报告" 会匹配 "帮我分析报告" 这类目标。
 * 匹配采用首个命中策略（findFirst），即第一个匹配的模板生效。
 * </p>
 */
@Component
public class PlanTemplateLoader {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(PlanTemplateLoader.class);

    /** Agent 定义加载器，从 YAML 配置中读取计划模板 */
    private final AgentDefinitionLoader definitionLoader;

    /**
     * 构造计划模板加载器。
     *
     * @param definitionLoader Agent 定义加载器
     */
    public PlanTemplateLoader(AgentDefinitionLoader definitionLoader) {
        this.definitionLoader = definitionLoader;
    }

    /**
     * 根据用户目标和 Agent 定义 ID 查找匹配的计划模板。
     *
     * <p>
     * 查找流程：
     * 1. 从 AgentDefinition 中获取 plan.templates 配置
     * 2. 遍历模板，找到第一个 match 关键字包含在用户目标中的模板
     * 3. 将匹配的模板转换为 agentscope Plan 实例
     * </p>
     *
     * @param goal       用户目标字符串
     * @param agentDefId Agent 定义 ID
     * @return 匹配的模板转换后的 Plan，无匹配返回 empty
     */
    public Optional<Plan> matchTemplate(String goal, String agentDefId) {
        var def = definitionLoader.getAgentDefinition(agentDefId);
        if (def == null || def.getPlan() == null) {
            return Optional.empty();
        }

        var templates = def.getPlan().getTemplates();
        if (templates == null || templates.isEmpty()) {
            return Optional.empty();
        }

        // 首个命中策略：找到第一个 match 关键字在 goal 中的模板
        return templates.stream()
                .filter(t -> goal.contains(t.getMatch()))
                .findFirst()
                .map(t -> convertToPlan(t, goal));
    }

    /**
     * 将 PlanTemplate 转换为 agentscope Plan 实例。
     *
     * <p>
     * 转换逻辑：
     * - Plan.name：优先使用模板名称，否则使用用户目标
     * - Plan.description：优先使用模板描述，否则使用用户目标
     * - Plan.subtasks：遍历模板的子任务列表，逐个转换为 SubTask
     * </p>
     *
     * @param template 计划模板
     * @param goal     用户目标（作为 name/description 的兜底值）
     * @return agentscope Plan 实例
     */
    private Plan convertToPlan(PlanTemplate template, String goal) {
        var plan = new Plan();
        plan.setName(template.getName() != null ? template.getName() : goal);
        plan.setDescription(template.getDescription() != null ? template.getDescription() : goal);

        // 转换子任务列表
        var subtasks = new ArrayList<SubTask>();
        if (template.getSubtasks() != null) {
            for (OrchestratedSubTask ost : template.getSubtasks()) {
                var subTask = new SubTask(
                        ost.getName(),
                        ost.getDescription(),
                        ost.getExpectedOutcome());
                subtasks.add(subTask);
            }
        }
        plan.setSubtasks(subtasks);

        log.debug("转换计划模板成功: {}, 含 {} 个子任务", plan.getName(), subtasks.size());
        return plan;
    }

    /**
     * 获取 Agent 定义中配置的所有计划模板。
     *
     * @param agentDefId Agent 定义 ID
     * @return 计划模板列表，无配置时返回空列表
     */
    public List<PlanTemplate> getTemplates(String agentDefId) {
        var def = definitionLoader.getAgentDefinition(agentDefId);
        if (def == null || def.getPlan() == null) {
            return List.of();
        }
        var templates = def.getPlan().getTemplates();
        return templates != null ? templates : List.of();
    }
}
