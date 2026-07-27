package io.yunxi.platform.muse.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.platform.muse.engine.SelfEvolutionEngine;
import io.yunxi.platform.muse.model.EvolveOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MUSE 元工具：对某个技能运行自进化闭环（评估->迭代->再评估直到收敛）。
 *
 * <p>让 LLM 能在对话中直接驱动「技能自我进化」，是 MUSE 与 AgentScope 原生技能管理最大的差异点。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SkillRefineTool {

    private final SelfEvolutionEngine engine;

    @Tool(name = "muse_skill_evolve",
            description = "对一个技能运行自进化闭环：在沙箱里反复评估并用 LLM 按失败反馈修补技能正文，"
                    + "直到测试全部通过或达到最大迭代次数。skill_dir 为技能目录绝对路径，"
                    + "initial_content 为初始技能正文。返回最终通过率与迭代轮数。")
    public String evolve(
            @ToolParam(name = "skill_name", description = "技能名") String skillName,
            @ToolParam(name = "skill_dir", description = "技能目录绝对路径") String skillDir,
            @ToolParam(name = "initial_content", description = "初始技能正文（markdown）") String initialContent) {
        EvolveOutcome outcome = engine.evolve(skillName, skillDir, initialContent);
        return String.format("技能[%s] 进化完成：迭代 %d 轮，最终 %s",
                skillName, outcome.getIterations(),
                outcome.isSucceeded() ? "全部测试通过" : "仍未全过");
    }
}
