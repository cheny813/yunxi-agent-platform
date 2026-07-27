package io.yunxi.platform.muse.engine;

import io.yunxi.platform.muse.config.EvolutionConfig;
import io.yunxi.platform.muse.eval.SkillEvaluator;
import io.yunxi.platform.muse.model.EvalReport;
import io.yunxi.platform.muse.model.EvolveOutcome;
import io.yunxi.platform.muse.prune.SkillPruner;
import io.yunxi.platform.muse.refine.SkillRefiner;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 自进化引擎：编排「评估 -> 按失败反馈修补 -> 再评估」闭环，直到测试全过或达到最大迭代次数。
 *
 * <p>这是 MUSE 相对 AgentScope 原生技能管理（只能被动加载/管理技能）最大的差异点：
 * 让技能在测试驱动下自我进化。评估走 {@link SkillEvaluator}（沙箱跑测试），
 * 修补走 {@link SkillRefiner}（LLM + 持久化），均由 AgentScope 提供底座能力。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SelfEvolutionEngine {

    private final SkillEvaluator evaluator;
    private final SkillRefiner refiner;
    private final SkillPruner pruner;
    private final EvolutionConfig config;

    public SkillPruner getPruner() { return pruner; }

    /**
     * 对一个技能运行自进化闭环。
     *
     * @param skillName       技能名
     * @param skillDir        技能目录（磁盘绝对路径，含 tests/）
     * @param initialContent  初始技能正文；为空时从 skillDir/SKILL.md 读取
     */
    public EvolveOutcome evolve(String skillName, String skillDir, String initialContent) {
        String content = (initialContent == null || initialContent.isBlank())
                ? readDiskSkill(skillDir) : initialContent;

        EvolveOutcome outcome = EvolveOutcome.builder().skillName(skillName).build();
        int maxIter = config.getRefine().getMaxIterations();
        boolean stopOnNoProgress = config.getRefine().isStopOnNoProgress();

        String bestContent = content;
        double bestPass = -1.0;

        for (int iter = 1; iter <= maxIter; iter++) {
            EvalReport report = evaluator.evaluate(skillName, skillDir);
            outcome.setIterations(iter);
            outcome.setLastReport(report);

            double passRate = report.getPassRate();
            if (passRate > bestPass) {
                bestPass = passRate;
                bestContent = content;
            }

            if (report.isAllPassed()) {
                outcome.setSucceeded(true);
                outcome.setFinalContent(content);
                log.info("[muse] skill={} 第{}轮全部通过", skillName, iter);
                return outcome;
            }

            if (stopOnNoProgress && passRate <= outcome.getPrevPassRate()) {
                log.info("[muse] skill={} 第{}轮通过率无进展({} vs {}), 提前停止",
                        skillName, iter, passRate, outcome.getPrevPassRate());
                break;
            }
            outcome.setPrevPassRate(passRate);

            content = refiner.refine(skillName, content, report, iter);
            refiner.persist(skillName, content, skillDir);
        }

        outcome.setSucceeded(bestPass >= 1.0);
        outcome.setFinalContent(bestContent);
        return outcome;
    }

    private String readDiskSkill(String skillDir) {
        try {
            return Files.readString(Path.of(skillDir, "SKILL.md"));
        } catch (Exception e) {
            return "";
        }
    }
}
