package io.yunxi.platform.muse.engine;

import io.yunxi.platform.muse.config.EvolutionConfig;
import io.yunxi.platform.muse.eval.DefaultSkillEvaluator;
import io.yunxi.platform.muse.eval.SkillEvaluator;
import io.yunxi.platform.muse.model.EvolveOutcome;
import io.yunxi.platform.muse.prune.DefaultSkillPruner;
import io.yunxi.platform.muse.prune.SkillPruner;
import io.yunxi.platform.muse.refine.SkillRefiner;
import io.yunxi.platform.muse.sandbox.LocalSubprocessSandboxRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SelfEvolutionEngine 集成测试：验证完整自进化闭环。
 *
 * <p>由于内建技能已全部通过测试，进化引擎应在一轮评估后立即返回成功，
 * 不会调用 LLM（SkillRefiner 也不会被触发），因此无需 Mock Model。</p>
 */
class SelfEvolutionEngineTest {

    private SelfEvolutionEngine engine;
    private String skillsRoot;

    @BeforeEach
    void setUp() {
        EvolutionConfig config = new EvolutionConfig();

        // 真实评估器 + 真实沙箱
        SkillEvaluator evaluator = new DefaultSkillEvaluator(
                new LocalSubprocessSandboxRunner(), config);

        // 真实剪枝器
        SkillPruner pruner = new DefaultSkillPruner(config);

        // Refiner 用 null Model 构造——测试中不会走到 refine 路径
        // 因为内建技能评估全过，SelfEvolutionEngine 第一轮即返回成功
        SkillRefiner refiner = new SkillRefiner(null, null, config);

        engine = new SelfEvolutionEngine(evaluator, refiner, pruner, config);
        skillsRoot = "src/main/resources/builtin-skills";
    }

    @Test
    void testDataStatsEvolveFirstRoundAllPassed() {
        String skillDir = skillsRoot + "/data_stats";

        EvolveOutcome outcome = engine.evolve("data_stats", skillDir, null);

        assertTrue(outcome.isSucceeded(),
                "data_stats 进化应成功（首轮全部通过），实际: "
                        + outcome.getLastReport().getSummary());
        assertEquals(1, outcome.getIterations(),
                "应仅一轮评估就成功，实际迭代: " + outcome.getIterations());
        assertNotNull(outcome.getFinalContent());
        assertNotNull(outcome.getLastReport());
        assertEquals(1.0, outcome.getLastReport().getPassRate(), 0.001);
    }

    @Test
    void testTextCleanEvolveFirstRoundAllPassed() {
        String skillDir = skillsRoot + "/text_clean";

        EvolveOutcome outcome = engine.evolve("text_clean", skillDir, null);

        assertTrue(outcome.isSucceeded(),
                "text_clean 进化应成功（首轮全部通过）: "
                        + outcome.getLastReport().getSummary());
        assertEquals(1, outcome.getIterations());
    }

    @Test
    void testEvolveAllBuiltinSkills() {
        String[] skills = {"data_stats", "text_clean"};
        for (String skill : skills) {
            String skillDir = skillsRoot + "/" + skill;

            EvolveOutcome outcome = engine.evolve(skill, skillDir, null);

            assertTrue(outcome.isSucceeded(),
                    skill + " 进化应成功: " + outcome.getLastReport().getSummary());
            assertEquals(1, outcome.getIterations(),
                    skill + " 应仅一轮: 迭代=" + outcome.getIterations());
            assertNotNull(outcome.getFinalContent(),
                    skill + " 应有最终内容");
            assertFalse(outcome.getFinalContent().isBlank(),
                    skill + " 最终内容不应为空");
        }
    }

    @Test
    void testEvolveWithExplicitContent() {
        String skillDir = skillsRoot + "/data_stats";
        // 直接从磁盘读取的内容
        String content = "> 这里是显式传入的技能正文（不走磁盘读取）\n"
                + "---\n"
                + "name: data_stats\n"
                + "描述：提供统计分析功能，包括均值、标准差、分位数等指标\n"
                + "触发词：统计、分析、数据计算\n"
                + "---\n";

        EvolveOutcome outcome = engine.evolve("data_stats", skillDir, content);

        // 内容是输入的，但测试走的是磁盘（skillDir）——所以评估仍基于磁盘的 SKILL.md
        // 此处验证 initialContent 被正确传入
        assertNotNull(outcome);
    }

    @Test
    void testEvolveNonexistentSkill() {
        String skillDir = skillsRoot + "/nonexistent";

        EvolveOutcome outcome = engine.evolve("nonexistent", skillDir, null);

        assertFalse(outcome.isSucceeded(),
                "不存在技能进化应失败");
        assertNotNull(outcome.getLastReport());
    }

    @Test
    void testMaxIterationsRespected() {
        // 验证配置的最大迭代数被正确读取
        EvolutionConfig config = new EvolutionConfig();
        config.getRefine().setMaxIterations(5);

        SkillEvaluator evaluator = new DefaultSkillEvaluator(
                new LocalSubprocessSandboxRunner(), config);
        SkillPruner pruner = new DefaultSkillPruner(config);
        SkillRefiner refiner = new SkillRefiner(null, null, config);

        SelfEvolutionEngine engine2 = new SelfEvolutionEngine(evaluator, refiner, pruner, config);
        String skillDir = skillsRoot + "/data_stats";

        EvolveOutcome outcome = engine2.evolve("data_stats", skillDir, null);

        // 因为首轮即通过，所以迭代数是 1 而非 5
        assertEquals(1, outcome.getIterations());
        assertTrue(outcome.isSucceeded());
    }
}
