package io.yunxi.platform.agent.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.yunxi.platform.agent.plan.model.Plan;
import io.yunxi.platform.agent.plan.model.SubTask;
import io.yunxi.platform.agent.plan.model.OrchestratedSubTask;
import io.yunxi.platform.agent.plan.model.PlanTemplate;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;

/**
 * 计划模板加载器。
 *
 * <p>
 * 从 AgentDefinition YAML 的 plan.templates 配置加载模板，
 * 根据用户目标匹配并转换为 yunxi 本地 Plan 实例。
 * </p>
 *
 * <p>
 * V2.0-RC3: 框架已将 {@code io.agentscope.core.plan.model} 包完全删除
 * （v2 改用 HarnessAgent.enablePlanMode() + 纯 markdown 方案）。
 * yunxi 保留 PlanTemplateLoader 作为模板匹配功能，使用本地 Plan/SubTask 模型类。
 * 待框架统一 plan 方案上线后迁移。
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
     * @param goal       用户目标字符串
     * @param agentDefId Agent 定义 ID
     * @return 匹配的模板转换后的 Plan，无匹配返回 empty
     */
    public Optional<? extends Plan> matchTemplate(String goal, String agentDefId) {
        var def = definitionLoader.getAgentDefinition(agentDefId);
        if (def == null || def.getPlan() == null) {
            return Optional.empty();
        }

        var templates = def.getPlan().getTemplates();
        if (templates == null || templates.isEmpty()) {
            return Optional.empty();
        }

        return templates.stream()
                .filter(t -> goal.contains(t.getMatch()))
                .findFirst()
                .map(t -> convertToPlan(t, goal));
    }

    /**
     * 将 PlanTemplate 转换为 yunxi Plan 实例。
     *
     * @param template 计划模板
     * @param goal     用户目标
     * @return yunxi Plan 实例
     */
    private Plan convertToPlan(PlanTemplate template, String goal) {
        var plan = new Plan();
        plan.setName(template.getName() != null ? template.getName() : goal);
        plan.setDescription(template.getDescription() != null ? template.getDescription() : goal);

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
