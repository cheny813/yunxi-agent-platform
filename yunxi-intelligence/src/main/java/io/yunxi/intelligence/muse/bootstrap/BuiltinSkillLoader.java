package io.yunxi.intelligence.muse.bootstrap;

import io.yunxi.intelligence.muse.config.EvolutionConfig;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 内建技能测试物化器：应用启动时把 classpath 下 builtin-skills 资源中每个技能的 {@code tests/}
 * 物化到 {@code yunxi.muse.builtin-export-dir}（默认 .agentscope/workspace/skills），供 MUSE 评估器
 * （{@code SkillEvalTool}）在沙箱中执行测试脚本（评估器需要磁盘上的脚本文件）。
 *
 * <p>内置技能的 SKILL.md 已由 {@code AgentConfigurer} 经 {@code ClasspathSkillRepository} 零复制
 * 加载（框架 Layer 2），本 Bean 不再落地 SKILL.md，从而消除「双份真相」。对外暴露
 * {@link #copyBuiltinSkills()} 供运行时按需重新物化测试（无需重启）。</p>
 *
 * <p>本 Bean 在 yunxi.muse.enabled 默认开启（matchIfMissing=true）时即被扫描注册（组件自带 @ConditionalOnProperty 控制）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true", matchIfMissing = true)
public class BuiltinSkillLoader implements ApplicationRunner {

    private final EvolutionConfig config;
    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver();

    public BuiltinSkillLoader(EvolutionConfig config) {
        this.config = config;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!config.isBuiltinCopyOnStart()) {
            log.info("[muse] 内建技能测试物化已关闭（yunxi.muse.builtin-copy-on-start=false）");
            return;
        }
        copyBuiltinSkills();
    }

    /**
     * 重新从 classpath 物化内建技能的 tests/ 到磁盘目标目录。
     *
     * <p>此方法启动时由 {@link #run(ApplicationArguments)} 自动调用，也可通过
     * 管理 API 在运行时按需触发。返回值供调用方报告物化结果。</p>
     *
     * @return 物化结果（技能总数、目标目录）
     */
    public BuiltinCopyResult copyBuiltinSkills() {
        Path exportDir = Path.of(config.getBuiltinExportDir());
        int ok = 0;
        StringBuilder errs = new StringBuilder();
        try {
            Files.createDirectories(exportDir);
        } catch (Exception e) {
            log.warn("[muse] 无法创建内建技能落地目录 {}: {}", exportDir, e.getMessage());
            return new BuiltinCopyResult(0, exportDir.toAbsolutePath().toString(), e.getMessage());
        }
        try {
            Resource[] skills = resolver.getResources("classpath*:builtin-skills/*/SKILL.md");
            for (Resource skillRes : skills) {
                String name = skillNameOf(skillRes);
                if (name == null) continue;
                try {
                    Path skillDir = exportDir.resolve(name);
                    Files.createDirectories(skillDir);
                    // 内置技能 SKILL.md 已由 ClasspathSkillRepository 零复制加载（Layer 2），此处仅物化 tests/。
                    copyTests(name, skillDir);
                    log.info("[muse] 内建技能 {} 的测试已物化到 {}", name, skillDir);
                    ok++;
                } catch (Exception e) {
                    log.warn("[muse] 内建技能 {} 落地失败: {}", name, e.getMessage());
                    errs.append(name).append("; ");
                }
            }
            String error = errs.length() == 0 ? null : errs.toString();
            log.info("[muse] 内建技能测试物化完成，成功 {} / 共 {}", ok, skills.length);
            return new BuiltinCopyResult(ok, exportDir.toAbsolutePath().toString(), error);
        } catch (Exception e) {
            log.warn("[muse] 内建技能扫描失败: {}", e.getMessage());
            return new BuiltinCopyResult(ok, exportDir.toAbsolutePath().toString(), e.getMessage());
        }
    }

    /** 内建技能落地结果，供管理 API 返回。 */
    public record BuiltinCopyResult(int count, String targetDir, String error) {}

    /** 将 classpath 内 {name}/tests/* 下的测试资源复制到落地目录（供评估器在沙箱内执行）。 */
    private void copyTests(String name, Path skillDir) {
        try {
            Resource[] tests = resolver.getResources(
                    "classpath*:builtin-skills/" + name + "/tests/*");
            if (tests.length == 0) return;
            Path testDir = skillDir.resolve("tests");
            Files.createDirectories(testDir);
            for (Resource t : tests) {
                if (t.getFilename() == null) continue;
                try (InputStream in = t.getInputStream()) {
                    Files.copy(in, testDir.resolve(t.getFilename()),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    log.warn("[muse] 技能 {} 测试文件 {} 复制失败（已跳过）: {}",
                            name, t.getFilename(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[muse] 技能 {} 复制测试资源失败（已跳过）: {}", name, e.getMessage());
        }
    }

    /** 从 {@code builtin-skills/{name}/SKILL.md} 的 URL 中提取技能名。 */
    private String skillNameOf(Resource res) {
        try {
            String url = res.getURL().toString();
            int i = url.lastIndexOf("/builtin-skills/");
            if (i < 0) return null;
            String sub = url.substring(i + "/builtin-skills/".length());
            int slash = sub.indexOf('/');
            return slash < 0 ? sub : sub.substring(0, slash);
        } catch (Exception e) {
            return null;
        }
    }
}
