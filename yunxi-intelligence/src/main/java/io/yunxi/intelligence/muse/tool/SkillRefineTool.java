package io.yunxi.intelligence.muse.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.intelligence.muse.config.EvolutionConfig;
import io.yunxi.intelligence.muse.engine.SelfEvolutionEngine;
import io.yunxi.intelligence.muse.model.EvolveOutcome;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MUSE 元工具：对某个技能运行自进化闭环（评估->迭代->再评估直到收敛）。
 *
 * <p>让 LLM 能在对话中直接驱动「技能自我进化」，是 MUSE 与 AgentScope 原生技能管理最大的差异点。</p>
 *
 * <p>技能目录（skill_dir）默认注入平台 canonical 目录（yunxi.muse.builtin-export-dir 的绝对路径），
 * LLM 留空即可写回框架可自动加载的目录——避免 LLM 猜错路径导致「写到了框架读不到的角落」这一假生效风险
 * （运维覆盖 builtin-export-dir 到子目录做隔离实验时适用）。LLM 仍可在需要时显式覆盖（如把进化结果写到子目录做隔离实验）。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SkillRefineTool {

    private final SelfEvolutionEngine engine;
    private final EvolutionConfig config;

    @Tool(name = "muse_skill_evolve",
            description = "对一个技能运行自进化闭环：在沙箱里反复评估并用 LLM 按失败反馈修补技能正文，"
                    + "直到测试全部通过或达到最大迭代次数。skill_dir 为技能目录绝对路径；"
                    + "留空则默认写入平台技能目录（yunxi.muse.builtin-export-dir，框架自动加载），通常无需修改。"
                    + "initial_content 为初始技能正文。返回最终通过率与迭代轮数。")
    public String evolve(
            @ToolParam(name = "skill_name", description = "技能名") String skillName,
            @ToolParam(name = "skill_dir", required = false,
                    description = "技能目录绝对路径；留空默认平台技能目录（可被框架自动加载），通常无需修改")
            String skillDir,
            @ToolParam(name = "initial_content", description = "初始技能正文（markdown）") String initialContent) {
        EvolveOutcome outcome = engine.evolve(skillName, resolveSkillDir(skillDir), initialContent);
        return String.format("技能[%s] 进化完成：迭代 %d 轮，最终 %s",
                skillName, outcome.getIterations(),
                outcome.isSucceeded() ? "全部测试通过" : "仍未全过");
    }

    /** 解析技能目录：LLM 未传或传空时回退到平台 canonical 目录（builtin-export-dir 绝对路径），保证写回位置被框架加载。 */
    private String resolveSkillDir(String skillDir) {
        if (skillDir == null || skillDir.isBlank()) {
            return Path.of(config.getBuiltinExportDir()).toAbsolutePath().toString();
        }
        return skillDir;
    }
}
