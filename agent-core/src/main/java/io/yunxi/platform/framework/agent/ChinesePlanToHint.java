/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yunxi.platform.framework.agent;

import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.hint.PlanToHint;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;

/**
 * 自定义 PlanToHint 实现，强制使用中文回复。
 *
 * <p>
 * 基于 AgentScope 的 DefaultPlanToHint 修改，将语言规则改为强制中文。
 * 解决 PlanNotebook 生成的计划是英文导致大模型用英文回复的问题。
 *
 * @author yunxi-agent-platform
 */
public class ChinesePlanToHint implements PlanToHint {

    /** 系统提示前缀 */
    private static final String HINT_PREFIX = "<system-hint>";
    /** 系统提示后缀 */
    private static final String HINT_SUFFIX = "</system-hint>";
    /** 重要规则分隔符 */
    private static final String IMPORTANT_RULES_SEPARATOR = "Important Rules: \n";

    private static final String RULE_WAIT_FOR_CONFIRMATION = "⚠️ 等待用户确认:\n"
            + "- 在执行计划前向用户展示计划并确认\n"
            + "- 如果用户的请求已经暗示了执行意图（例如：\"执行\"、\"执行计划\"），则直接执行而不询问\n"
            + "- 否则，询问：\"是否继续执行此计划？\"\n"
            + "- 只有在用户确认后（例如：\"是\"、\"继续\"、\"执行\"）才开始执行\n"
            + "- 如果用户说其他内容（问题、修改、无关话题），则相应回复但不要开始执行\n";

    private static final String RULE_SUBTASK_LIMIT = "- 子任务限制: 确保计划不超过 {max_subtasks} 个子任务\n";

    private static final String RULE_COMMON = "- 处理每个子任务前更新状态: 处理每个子任务时，调用 get_subtask_count 和 view_subtasks 确认最新信息："
            + " get_subtask_count 用于确认子任务总数以避免遗漏；view_subtasks 用于查询子任务信息，"
            + "严格按照最新信息执行子任务，注意忽略原始请求。\n"
            + "- 用户可能直接修改计划: 用户可以直接添加、编辑或删除子任务，无需经过你。\n"
            + "- 只关注当前内容: 始终遵循最新的计划内容，特别是当原始计划与最新查询的计划冲突时，"
            + "遵循最新查询的计划而不考虑初始需求。\n"
            + "- 不要修改计划: 没有用户的明确计划修改指令，不要修改或修正计划\n"
            + "- 语言一致性: 必须使用中文回复用户，无论计划是什么语言\n";

    private static final String NO_PLAN = "如果用户的查询比较复杂（例如编程网站、游戏或应用），或者需要一长串步骤来完成"
            + "（例如从不同的来源对某个主题进行研究），你需要先通过调用 'create_plan' 创建一个计划。"
            + "否则，你可以直接执行用户的查询而无需计划。\n";

    private static final String AT_THE_BEGINNING = "当前计划:\n"
            + "```\n"
            + "{plan}\n"
            + "```\n"
            + "你的选项包括:\n"
            + "- 通过调用 'update_subtask_state' 并设置 subtask_idx=0 和 state='in_progress' 将第一个子任务标记为 'in_progress'，然后开始执行。\n"
            + "- 如果第一个子任务无法执行，分析原因以及你可以采取什么措施来推进计划，"
            + "例如向用户询问更多信息，或通过调用 'revise_current_plan' 修改计划。\n"
            + "- 如果用户要求你做与计划无关的事情，优先完成用户的查询，然后再返回计划。\n"
            + "- 如果用户不再想执行当前计划，与用户确认并调用 'finish_plan' 函数。\n";

    private static final String WHEN_A_SUBTASK_IN_PROGRESS = "当前计划:\n"
            + "```\n"
            + "{plan}\n"
            + "```\n"
            + "现在索引为 {subtask_idx} 的子任务 '{subtask_name}' 处于 'in_progress' 状态。详情如下:\n"
            + "```\n"
            + "{subtask}\n"
            + "```\n"
            + "你的选项包括:\n"
            + "- 继续执行子任务并获取结果。\n"
            + "- 如果子任务已完成，调用 'finish_subtask' 并指定具体结果。\n"
            + "- 如果需要，向用户询问更多信息。\n"
            + "- 如有必要，通过调用 'revise_current_plan' 修改计划。\n"
            + "- 如果用户要求你做与计划无关的事情，优先完成用户的查询，然后再返回计划。\n";

    private static final String WHEN_NO_SUBTASK_IN_PROGRESS = "当前计划:\n"
            + "```\n"
            + "{plan}\n"
            + "```\n"
            + "前 {index} 个子任务已完成，没有子任务处于 'in_progress' 状态。现在你的选项包括:\n"
            + "- 通过调用 'update_subtask_state' 将下一个子任务标记为 'in_progress' 并开始执行。\n"
            + "- 如果需要，向用户询问更多信息。\n"
            + "- 如有必要，通过调用 'revise_current_plan' 修改计划。\n"
            + "- 如果用户要求你做与计划无关的事情，优先完成用户的查询，然后再返回计划。\n";

    private static final String AT_THE_END = "当前计划:\n"
            + "```\n"
            + "{plan}\n"
            + "```\n"
            + "所有子任务已完成。现在你的选项是:\n"
            + "- 通过调用 'finish_plan' 并指定具体结果来完成计划，并向用户总结整个过程和结果。\n"
            + "- 如有必要，通过调用 'revise_current_plan' 修改计划。\n"
            + "- 如果用户要求你做与计划无关的事情，优先完成用户的查询，然后再返回计划。\n";

    /**
     * 根据计划状态生成中文提示
     *
     * @param plan         当前计划（可为 null）
     * @param planNotebook 计划笔记本
     * @return 生成的提示字符串
     */
    @Override
    public String generateHint(Plan plan, PlanNotebook planNotebook) {
        String hint;
        String confirmationRule = planNotebook.isNeedUserConfirm() ? RULE_WAIT_FOR_CONFIRMATION : "";

        if (plan == null) {
            StringBuilder appendRules = new StringBuilder();
            if (planNotebook.isNeedUserConfirm()) {
                appendRules.append(confirmationRule);
            }
            if (planNotebook.getMaxSubtasks() != null) {
                appendRules.append(
                        RULE_SUBTASK_LIMIT.replace(
                                "{max_subtasks}", String.valueOf(planNotebook.getMaxSubtasks())));
            }
            if (appendRules.isEmpty()) {
                hint = NO_PLAN;
            } else {
                hint = NO_PLAN + IMPORTANT_RULES_SEPARATOR + appendRules;
            }
        } else {
            int nTodo = 0;
            int nInProgress = 0;
            int nDone = 0;
            int nAbandoned = 0;
            Integer inProgressIdx = null;

            for (int i = 0; i < plan.getSubtasks().size(); i++) {
                SubTask subtask = plan.getSubtasks().get(i);
                switch (subtask.getState()) {
                    case TODO -> nTodo++;
                    case IN_PROGRESS -> {
                        nInProgress++;
                        inProgressIdx = i;
                    }
                    case DONE -> nDone++;
                    case ABANDONED -> nAbandoned++;
                }
            }

            if (nDone + nAbandoned == plan.getSubtasks().size()) {
                hint = AT_THE_END.replace("{plan}", plan.toMarkdown(false));

            } else if (nInProgress == 0 && nDone == 0) {
                hint = AT_THE_BEGINNING.replace("{plan}", plan.toMarkdown(false))
                        + IMPORTANT_RULES_SEPARATOR
                        + confirmationRule
                        + RULE_COMMON;

            } else if (nInProgress > 0 && inProgressIdx != null) {
                SubTask inProgressSubtask = plan.getSubtasks().get(inProgressIdx);
                String subtaskName = inProgressSubtask.getName();
                if (subtaskName == null) {
                    subtaskName = "未命名子任务";
                }
                hint = WHEN_A_SUBTASK_IN_PROGRESS
                        .replace("{plan}", plan.toMarkdown(false))
                        .replace("{subtask_idx}", String.valueOf(inProgressIdx))
                        .replace("{subtask_name}", subtaskName)
                        .replace("{subtask}", inProgressSubtask.toMarkdown(true))
                        + IMPORTANT_RULES_SEPARATOR
                        + RULE_COMMON;

            } else if (nInProgress == 0 && nDone > 0) {
                hint = WHEN_NO_SUBTASK_IN_PROGRESS
                        .replace("{plan}", plan.toMarkdown(false))
                        .replace("{index}", String.valueOf(nDone))
                        + IMPORTANT_RULES_SEPARATOR
                        + RULE_COMMON;

            } else {
                return null;
            }
        }

        return HINT_PREFIX + hint + HINT_SUFFIX;
    }
}
