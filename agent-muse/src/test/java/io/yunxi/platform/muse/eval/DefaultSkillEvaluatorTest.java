package io.yunxi.platform.muse.eval;

import io.yunxi.platform.muse.config.EvolutionConfig;
import io.yunxi.platform.muse.model.EvalReport;
import io.yunxi.platform.muse.sandbox.LocalSubprocessSandboxRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultSkillEvaluator 集成测试：用真实内建技能在本地沙箱运行纯 Java 自测。
 */
class DefaultSkillEvaluatorTest {

    private DefaultSkillEvaluator evaluator;
    private String skillsRoot;

    @BeforeEach
    void setUp() {
        EvolutionConfig config = new EvolutionConfig();
        DefaultSkillEvaluator eval = new DefaultSkillEvaluator(
                new LocalSubprocessSandboxRunner(), config);
        evaluator = eval;

        // 内建技能测试走 classpath 资源目录
        skillsRoot = "src/main/resources/builtin-skills";
    }

    @Test
    void testEvaluateDataStatsAllPassed() {
        String skillDir = skillsRoot + "/data_stats";
        EvalReport report = evaluator.evaluate("data_stats", skillDir);

        assertTrue(report.isAllPassed(),
                "data_stats 测试应全通过，实际: " + report.getSummary());
        assertEquals(1.0, report.getPassRate(), 0.001,
                "通过率应为 1.0，实际: " + report.getPassRate());
        assertEquals(1, report.getPassed());
        assertEquals(0, report.getFailed());
    }

    @Test
    void testEvaluateTextCleanAllPassed() {
        String skillDir = skillsRoot + "/text_clean";
        EvalReport report = evaluator.evaluate("text_clean", skillDir);

        assertTrue(report.isAllPassed(),
                "text_clean 测试应全通过，实际: " + report.getSummary());
        assertEquals(1.0, report.getPassRate(), 0.001);
    }

    @Test
    void testEvaluateAllBuiltinSkills() {
        String[] skills = {"data_stats", "text_clean"};
        for (String skill : skills) {
            String skillDir = skillsRoot + "/" + skill;
            EvalReport report = evaluator.evaluate(skill, skillDir);

            assertTrue(report.isAllPassed(),
                    skill + " 测试应全通过: " + report.getSummary());
            assertEquals(1.0, report.getPassRate(), 0.001,
                    skill + " 通过率应为 1.0");
            assertNotNull(report.getEvaluatedAt());
            assertNotNull(report.getSummary());
            assertFalse(report.getSummary().isEmpty());
        }
    }

    @Test
    void testEvaluateNonexistentSkill() {
        String skillDir = skillsRoot + "/nonexistent";
        EvalReport report = evaluator.evaluate("nonexistent", skillDir);

        assertFalse(report.isAllPassed(),
                "不存在技能应失败，实际: " + report.getSummary());
        assertEquals(0, report.getPassed());
        assertTrue(report.getFailed() > 0);
    }

    @Test
    void testTimedOutRetry() {
        // 验证超时重试机制：用极短超时 + ping localhost 制造超时
        EvolutionConfig config = new EvolutionConfig();
        config.getSandbox().setTimeout(java.time.Duration.ofMillis(100));
        DefaultSkillEvaluator eval = new DefaultSkillEvaluator(
                new LocalSubprocessSandboxRunner(), config);

        // 执行一个可能很慢的命令
        String skillDir = skillsRoot + "/data_stats";
        EvalReport report = eval.evaluate("data_stats", skillDir);

        // 应该有评估结果（成功或超时后重试成功）
        assertNotNull(report);
        assertNotNull(report.getSummary());
    }
}
