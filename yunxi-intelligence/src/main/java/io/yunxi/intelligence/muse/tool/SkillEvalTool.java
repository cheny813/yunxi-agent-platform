package io.yunxi.intelligence.muse.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.intelligence.muse.config.EvolutionConfig;
import io.yunxi.intelligence.muse.eval.SkillEvaluator;
import io.yunxi.intelligence.muse.model.EvalReport;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MUSE 元工具：评估某个技能（在沙箱跑测试）。
 *
 * <p>作为 {@code @Tool} 挂入 AgentScope toolkit 的 muse 工具组，
 * 让 LLM 能在自主循环里亲自触发技能评估，也可由 {@link io.yunxi.intelligence.muse.engine.SelfEvolutionEngine} 直接调用。
 * 写法完全对齐 yunxi 现有业务工具（如 HttpTool）：@Tool 标方法、返回 String、@ToolParam 逐参。</p>
 *
 * <p>技能目录（skill_dir）默认注入平台 canonical 目录（yunxi.muse.builtin-export-dir 的绝对路径），
 * LLM 留空即可指向框架可自动加载的目录，避免猜错路径导致评估跑在错误目录。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SkillEvalTool {

    private final SkillEvaluator evaluator;
    private final EvolutionConfig config;

    @Tool(name = "muse_skill_eval",
            description = "评估一个技能：在其目录的沙箱里执行测试脚本，返回通过率与失败摘要。"
                    + "skill_name 为技能名，skill_dir 为技能目录绝对路径；"
                    + "留空则默认平台技能目录（yunxi.muse.builtin-export-dir，框架自动加载），通常无需修改。"
                    + "返回评估报告摘要。")
    public String eval(
            @ToolParam(name = "skill_name", description = "技能名") String skillName,
            @ToolParam(name = "skill_dir", required = false,
                    description = "技能目录绝对路径；留空默认平台技能目录（可被框架自动加载），通常无需修改")
            String skillDir) {
        EvalReport report = evaluator.evaluate(skillName, resolveSkillDir(skillDir));
        return String.format("技能[%s] 评估：%s", skillName, report.getSummary());
    }

    /** 解析技能目录：LLM 未传或传空时回退到平台 canonical 目录（builtin-export-dir 绝对路径）。 */
    private String resolveSkillDir(String skillDir) {
        if (skillDir == null || skillDir.isBlank()) {
            return Path.of(config.getBuiltinExportDir()).toAbsolutePath().toString();
        }
        return skillDir;
    }
}
