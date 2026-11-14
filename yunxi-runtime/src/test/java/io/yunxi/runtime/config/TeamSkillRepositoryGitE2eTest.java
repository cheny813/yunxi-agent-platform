package io.yunxi.runtime.config;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 团队 Git 技能仓库端到端验证（默认跳过，须 {@code -Dyunxi.e2e.git=true} 显式开启）。
 *
 * <p>验证 yunxi 接线的框架原生 {@link GitSkillRepository} 在真实 Git clone 路径下可加载技能：
 * 本测试在本地用系统 git 建一个含 {@code skills/&lt;name&gt;/SKILL.md} 的仓库，再以 {@code file://} 协议
 * 交给 GitSkillRepository clone 并读取（JGit 实现，框架内部已支持 {@code file://} 本地协议）。
 *
 * <p>说明：GitHub 等外网可达性与鉴权（SSH 密钥 / HTTPS token 凭据助手）由系统级 git 配置承载，不在 yunxi 代码内；
 * 本测试验证 clone+load 的代码路径，外网连通性需在你的环境以真实凭据复验。
 */
class TeamSkillRepositoryGitE2eTest {

    private static final boolean ENABLED = Boolean.getBoolean("yunxi.e2e.git");
    private static Path repoDir;
    private static GitSkillRepository repository;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(ENABLED, "跳过：未设置 -Dyunxi.e2e.git=true");

        repoDir = Files.createTempDirectory("yunxi-git-e2e");
        Path skillDir = repoDir.resolve("skills").resolve("hello");
        Files.createDirectories(skillDir);
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: hello\ndescription: 示例技能\n---\n# Hello\n示例技能内容\n");

        runGit(repoDir, "init");
        runGit(repoDir, "add", "-A");
        runGit(
                repoDir,
                "-c", "user.name=yunxi-e2e",
                "-c", "user.email=yunxi-e2e@local",
                "commit", "-m", "init skills");

        // JGit 支持 file:// 本地协议，等价于一次真实 clone 的代码路径。
        repository = new GitSkillRepository(repoDir.toUri().toString());
    }

    @Test
    void cloneAndLoadSkill() {
        Assertions.assertTrue(repository.skillExists("hello"), "应能发现克隆仓库中的 hello 技能");
        AgentSkill skill = repository.getSkill("hello");
        Assertions.assertNotNull(skill, "应能读回技能");
        Assertions.assertEquals("hello", skill.getName());
        Assertions.assertEquals("示例技能", skill.getDescription());
        List<String> names = repository.getAllSkillNames();
        Assertions.assertTrue(names.contains("hello"), "getAllSkillNames 应含 hello");
    }

    @AfterAll
    static void tearDown() {
        // 框架 GitSkillRepository.close() 会删除其内部临时克隆目录；Windows 上 .git/objects/pack/*.pack
        // 可能被进程/索引器占用而删除失败并抛 RuntimeException。此为环境清理细节，与 clone+load 验证无关，忽略。
        if (repository != null) {
            try {
                repository.close();
            } catch (RuntimeException ignored) {
                // Windows 临时克隆目录清理失败，忽略
            }
        }
        if (repoDir != null) {
            deleteRecursively(repoDir);
        }
    }

    private static void runGit(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("git " + String.join(" ", args) + " 失败 (exit=" + code + "): " + output);
        }
    }

    private static void deleteRecursively(Path path) {
        try {
            Files.walk(path)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 临时目录清理失败不影响验证结论
                        }
                    });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
