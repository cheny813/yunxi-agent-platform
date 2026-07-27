package io.yunxi.platform.muse.bootstrap;

import io.yunxi.platform.muse.config.EvolutionConfig;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 内建技能落地器：应用启动时把 classpath 下的 builtin-skills 资源（MUSE 内建技能的权威源，
 * 随 JAR 打包）复制到 {@code yunxi.muse.builtin-export-dir}（默认 .agentscope/workspace/skills），
 * 并对外暴露 {@link #copyBuiltinSkills()} 供运行时按需重新落地（无需重启）。
 *
 * <p>技能加载完全交由 AgentScope 框架原生四层技能链：落地目录通过
 * {@code builder.projectGlobalSkillsDir(path)} 作为 Layer 1 注册，
 * 框架 {@code composeSkillRepositories()} 自动组装 Layer 1→2→3→4 优先级链。
 * 框架 {@code DynamicSkillMiddleware.reloadSkills()} 在每次 agent.call() 时重新扫描
 * Layer 1 目录，因此通过本类或手动放置到 skills/ 下的技能文件在下次对话中立即生效。</p>
 *
 * <p>仅当 yunxi.muse.enabled=true 时本 Bean 才会被扫描注册（由 MuseAutoConfiguration 控制）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
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
            log.info("[muse] 内建技能复制已关闭（yunxi.muse.builtin-copy-on-start=false）");
            return;
        }
        copyBuiltinSkills();
    }

    /**
     * 重新从 classpath 复制内建技能到磁盘目标目录，覆盖已有 SKILL.md。
     *
     * <p>此方法启动时由 {@link #run(ApplicationArguments)} 自动调用，也可通过
     * 管理 API 在运行时按需触发。返回值供调用方报告落地结果。</p>
     *
     * @return 复制结果（技能总数、落地目录）
     */
    public BuiltinCopyResult copyBuiltinSkills() {
        Path exportDir = Path.of(config.getBuiltinExportDir());
        try {
            Files.createDirectories(exportDir);
            Resource[] skills = resolver.getResources("classpath*:builtin-skills/*/SKILL.md");
            for (Resource skillRes : skills) {
                String name = skillNameOf(skillRes);
                if (name == null) continue;
                String md = readString(skillRes);
                Path skillDir = exportDir.resolve(name);
                Files.createDirectories(skillDir);
                Files.writeString(skillDir.resolve("SKILL.md"), md);
                copyTests(name, skillDir);
                log.info("[muse] 内建技能 {} 已落地到 {}", name, skillDir);
            }
            log.info("[muse] 内建技能落地完成，共 {} 个", skills.length);
            return new BuiltinCopyResult(skills.length, exportDir.toAbsolutePath().toString(), null);
        } catch (Exception e) {
            log.warn("[muse] 内建技能落地失败: {}", e.getMessage());
            return new BuiltinCopyResult(0, exportDir.toAbsolutePath().toString(), e.getMessage());
        }
    }

    /** 内建技能落地结果，供管理 API 返回。 */
    public record BuiltinCopyResult(int count, String targetDir, String error) {}

    /** 将 classpath 内 {name}/tests/* 下的测试资源复制到落地目录。 */
    private void copyTests(String name, Path skillDir) throws Exception {
        Resource[] tests = resolver.getResources(
                "classpath*:builtin-skills/" + name + "/tests/*");
        if (tests.length == 0) return;
        Path testDir = skillDir.resolve("tests");
        Files.createDirectories(testDir);
        for (Resource t : tests) {
            if (t.getFilename() == null) continue;
            try (InputStream in = t.getInputStream()) {
                Files.copy(in, testDir.resolve(t.getFilename()));
            }
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

    /** 读取资源全文为 UTF-8 字符串。 */
    private String readString(Resource res) throws Exception {
        try (InputStream in = res.getInputStream()) {
            return new String(in.readAllBytes());
        }
    }

    /** 从技能 frontmatter 中提取 {@code description:} 字段，未找到则以技能名回退。 */
    private String extractDescription(String md, String fallback) {
        for (String line : md.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("description:")) {
                return t.substring("description:".length()).strip();
            }
        }
        return fallback;
    }
}
