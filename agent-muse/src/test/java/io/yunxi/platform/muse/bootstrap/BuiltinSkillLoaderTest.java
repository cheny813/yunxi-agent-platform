package io.yunxi.platform.muse.bootstrap;

import io.yunxi.platform.muse.config.EvolutionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BuiltinSkillLoader 集成测试：验证内建技能落地的完整性。
 */
class BuiltinSkillLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void testSkillStructureIntegrity() throws Exception {
        // 验证 resources/builtin-skills 下每个技能都有完整的 SKILL.md + tests/
        String[] skills = {"data_stats", "text_clean"};
        Path base = Path.of("src/main/resources/builtin-skills");

        for (String skill : skills) {
            Path skillDir = base.resolve(skill);
            assertTrue(Files.isDirectory(skillDir),
                    skill + " 目录应存在: " + skillDir.toAbsolutePath());

            // SKILL.md 必须存在且非空
            Path skillMd = skillDir.resolve("SKILL.md");
            assertTrue(Files.exists(skillMd),
                    skill + "/SKILL.md 应存在: " + skillMd.toAbsolutePath());
            String content = Files.readString(skillMd);
            assertFalse(content.isBlank(),
                    skill + "/SKILL.md 不应为空");
            assertTrue(content.contains("name:") || content.contains("名称"),
                    skill + "/SKILL.md 应包含技能名");

            // tests 目录必须存在
            Path testsDir = skillDir.resolve("tests");
            assertTrue(Files.isDirectory(testsDir),
                    skill + "/tests 目录应存在: " + testsDir.toAbsolutePath());

            // SkillStructureTest.java 必须存在
            Path testFile = testsDir.resolve("SkillStructureTest.java");
            assertTrue(Files.exists(testFile),
                    skill + "/tests/SkillStructureTest.java 应存在: " + testFile.toAbsolutePath());
            String testContent = Files.readString(testFile);
            assertTrue(testContent.contains("public static void main"),
                    skill + " 测试应包含 main 方法");

            // expected.txt 必须存在
            Path expectedFile = testsDir.resolve("expected.txt");
            assertTrue(Files.exists(expectedFile),
                    skill + "/tests/expected.txt 应存在: " + expectedFile.toAbsolutePath());
            String expectedContent = Files.readString(expectedFile);
            assertFalse(expectedContent.isBlank(),
                    skill + "/tests/expected.txt 不应为空");
        }
    }

    @Test
    void testDefaultExportDirIsAgentScopeSkillsDir() {
        EvolutionConfig config = new EvolutionConfig();
        assertEquals(".agentscope/workspace/skills",
                config.getBuiltinExportDir(),
                "内建技能落地目录应默认为 AgentScope 运行时技能目录");
    }

    @Test
    void testBuiltinCopyOnStartEnabled() {
        EvolutionConfig config = new EvolutionConfig();
        assertTrue(config.isBuiltinCopyOnStart(),
                "默认应启用内建技能落地");
    }
}
