package io.yunxi.platform.framework.plan;

import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;
import io.yunxi.platform.framework.plan.model.OrchestratedSubTask;
import io.yunxi.platform.framework.plan.model.PlanTemplate;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 规划模板加载器。
 * <p>
 * 从 AgentDefinition YAML 的 plan.templates 配置中加载规划模板，
 * 根据用户请求匹配模板并转换为 agentscope Plan 对象。
 * </p>
 *
 * <b>匹配规则:</b>
 * 用户请求中包含模板的 match 关键词即视为匹配。
 * 例如: match: "月度食谱" 可匹配 "帮我制定一个月的食谱"。
 */
@Component
public class PlanTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(PlanTemplateLoader.class);

    private final AgentDefinitionLoader definitionLoader;

    public PlanTemplateLoader(AgentDefinitionLoader definitionLoader) {
        this.definitionLoader = definitionLoader;
    }

    /**
     * 根据用户请求目标和 Agent 定义 ID 匹配规划模板。
     *
     * @param goal      用户请求目标文本
     * @param agentDefId Agent 定义名称
     * @return 匹配到的模板转换后的 Plan，无匹配返回 empty
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

        return templates.stream()
                .filter(t -> goal.contains(t.getMatch()))
                .findFirst()
                .map(t -> convertToPlan(t, goal));
    }

    /**
     * 将 PlanTemplate 转换为 agentscope Plan。
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
                        ost.getExpectedOutcome()
                );
                subtasks.add(subTask);
            }
        }
        plan.setSubtasks(subtasks);

        log.debug("已从模板创建规划: {}, 共 {} 个子任务", plan.getName(), subtasks.size());
        return plan;
    }

    /**
     * 获取 Agent 定义中配置的所有规划模板。
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