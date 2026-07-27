package io.yunxi.platform.muse.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.platform.muse.eval.SkillEvaluator;
import io.yunxi.platform.muse.model.EvalReport;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MUSE 元工具：评估某个技能（在沙箱跑测试）。
 *
 * <p>作为 {@code @Tool} 挂入 AgentScope toolkit 的 muse 工具组，
 * 让 LLM 能在自主循环里亲自触发技能评估，也可由 {@link io.yunxi.platform.muse.engine.SelfEvolutionEngine} 直接调用。
 * 写法完全对齐 yunxi 现有业务工具（如 HttpTool）：@Tool 标方法、返回 String、@ToolParam 逐参。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SkillEvalTool {

    private final SkillEvaluator evaluator;

    @Tool(name = "muse_skill_eval",
            description = "评估一个技能：在其目录的沙箱里执行测试脚本，返回通过率与失败摘要。"
                    + "skill_name 为技能名，skill_dir 为技能目录绝对路径。返回评估报告摘要。")
    public String eval(
            @ToolParam(name = "skill_name", description = "技能名") String skillName,
            @ToolParam(name = "skill_dir", description = "技能目录绝对路径") String skillDir) {
        EvalReport report = evaluator.evaluate(skillName, skillDir);
        return String.format("技能[%s] 评估：%s", skillName, report.getSummary());
    }
}
