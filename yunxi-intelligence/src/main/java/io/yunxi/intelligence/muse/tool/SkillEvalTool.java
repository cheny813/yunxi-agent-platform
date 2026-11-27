package io.yunxi.intelligence.muse.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.intelligence.muse.config.EvolutionConfig;
import io.yunxi.intelligence.muse.eval.SkillEvaluator;
import io.yunxi.intelligence.muse.model.EvalReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
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
 * <p>技能目录（skill_dir）默认按技能名解析为平台 canonical 目录下的技能子目录（builtin-export-dir/&lt;skill_name&gt;）：
 * 评估器在其沙箱里以该目录为工作目录执行测试脚本，脚本中的相对路径（如 {@code java tests/Xxx.java}）以技能目录为
 * cwd 才能找到文件。LLM 留空即可评估框架可自动加载的技能；仅评估 canonical 目录之外的技能时才需要显式传路径。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SkillEvalTool {

    /** 技能名即技能子目录名（MUSE 命名约束：字母/数字/下划线/连字符）；含其它字符的名字不参与目录解析。 */
    private static final Pattern SAFE_SKILL_NAME = Pattern.compile("[A-Za-z0-9_-]+");

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
        EvalReport report = evaluator.evaluate(skillName, resolveSkillDir(skillName, skillDir));
        return String.format("技能[%s] 评估：%s", skillName, report.getSummary());
    }

    /**
     * 解析技能目录：LLM 未传或传空时，若 canonical 目录下存在同名技能子目录则解析到该子目录；
     * 子目录不存在（或技能名不安全、无法参与目录解析）时回退到 canonical 根目录——保持既有兜底行为，
     * 由评估器以「找不到 SKILL.md / 测试」的形式报告，而不是在工具层静默吞掉。
     */
    private String resolveSkillDir(String skillName, String skillDir) {
        if (skillDir == null || skillDir.isBlank()) {
            Path canonical = Path.of(config.getBuiltinExportDir()).toAbsolutePath();
            if (skillName != null && SAFE_SKILL_NAME.matcher(skillName).matches()) {
                Path skillPath = canonical.resolve(skillName);
                if (Files.isDirectory(skillPath)) {
                    return skillPath.toString();
                }
            }
            return canonical.toString();
        }
        return skillDir;
    }
}
