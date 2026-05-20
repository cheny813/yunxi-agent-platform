package io.yunxi.platform.framework.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * SkillBox 自动配置
 *
 * <p>
 * 从多个来源加载 Skill 到 {@link SkillRegistryService}，并创建全局 SkillBox Bean。
 * </p>
 * <p>
 * 各 Agent 可通过 {@link SkillRegistryService#createSkillBox} 获取按需过滤的 SkillBox 实例，
 * 避免全量技能描述注入系统提示词浪费 Token。
 * </p>
 *
 * <p>
 * Skill 来源：
 * </p>
 * <ol>
 * <li>Classpath（src/main/resources/skills/）- 预置 Skill</li>
 * <li>本地文件系统（./skills/）- 自定义 Skill</li>
 * <li>Git 远程仓库 - 开源社区 Skill</li>
 * </ol>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(SkillBoxProperties.class)
@ConditionalOnProperty(prefix = "agentscope.skill-box", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SkillBoxAutoConfiguration {

    /**
     * 创建 Skill 注册中心（存储所有已加载 Skill，支持按需创建过滤 SkillBox）
     */
    @Bean
    public SkillRegistryService skillRegistry(SkillBoxProperties properties) {
        SkillRegistryService registry = new SkillRegistryService(properties);

        log.info("Initializing SkillRegistry...");

        int totalLoaded = 0;

        // 1. 从 classpath 加载预置 Skill
        if (properties.isClasspathEnabled()) {
            totalLoaded += loadFromClasspath(registry, properties);
        }

        // 2. 从 Git 远程仓库 clone 并合并 Skill 到统一目录
        String skillDir = resolveSkillDir(properties);
        totalLoaded += loadFromGit(registry, properties, skillDir);

        // 3. 从统一文件系统目录加载所有 Skill（手写 + Git 合并的）
        totalLoaded += loadFromFileSystem(registry, skillDir);

        log.info("SkillRegistry initialized: {} skills loaded", totalLoaded);
        log.info("Available skill IDs: {}", registry.getAllSkillIds());

        return registry;
    }

    /**
     * 创建全局 SkillBox Bean（包含所有 Skill，向后兼容）
     *
     * <p>
     * 需要全局 Bean 的场景（如 AdvancedAgentFactory）可直接注入；
     * 需要按需过滤的场景应注入 SkillRegistryService。
     * </p>
     */
    @Bean
    public SkillBox skillBox(SkillRegistryService registry) {
        return registry.createSkillBox(null, new Toolkit());
    }

    /**
     * 解析统一的 Skill 目录路径。
     * 优先使用 filesystem-path 配置，默认 ./skills
     */
    private String resolveSkillDir(SkillBoxProperties properties) {
        if (properties.getFilesystemPath() != null && !properties.getFilesystemPath().isBlank()) {
            return properties.getFilesystemPath();
        }
        return "./skills";
    }

    /**
     * 从 classpath 加载预置 Skill 到注册中心
     */
    private int loadFromClasspath(SkillRegistryService registry, SkillBoxProperties properties) {
        try (ClasspathSkillRepository repo = new ClasspathSkillRepository(
                properties.getClasspathPath())) {
            List<AgentSkill> skills = repo.getAllSkills();
            for (AgentSkill skill : skills) {
                registry.register(skill);
                log.info("Loaded classpath skill: {} ({})", skill.getName(), skill.getSkillId());
            }
            return skills.size();
        } catch (Exception e) {
            log.debug("No classpath skills found at '{}': {}", properties.getClasspathPath(), e.getMessage());
            return 0;
        }
    }

    /**
     * 从文件系统加载 Skill 到注册中心
     */
    private int loadFromFileSystem(SkillRegistryService registry, String skillDir) {
        try {
            Path path = Path.of(skillDir).toAbsolutePath();
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.info("Created skill directory: {}", path);
                return 0;
            }

            FileSystemSkillRepository repo = new FileSystemSkillRepository(path, false);
            List<AgentSkill> skills = repo.getAllSkills();
            for (AgentSkill skill : skills) {
                registry.register(skill);
                log.info("Loaded skill: {} ({})", skill.getName(), skill.getSkillId());
            }
            return skills.size();
        } catch (Exception e) {
            log.warn("Failed to load skills from '{}': {}", skillDir, e.getMessage());
            return 0;
        }
    }

    /**
     * 从 Git 仓库 clone Skill 并合并到统一 skill 目录。
     *
     * <p>
     * 策略：clone 到 skillDir/.git-cache/&lt;repo-name&gt;/，
     * 然后将 Skill 子目录复制到 skillDir/，实现所有 Skill 同目录共存。
     * </p>
     */
    private int loadFromGit(SkillRegistryService registry, SkillBoxProperties properties, String skillDir) {
        List<SkillBoxProperties.GitRepoConfig> gitRepos = properties.getGitRepositories();
        if (gitRepos == null || gitRepos.isEmpty()) {
            return 0;
        }

        Path baseSkillPath = Path.of(skillDir).toAbsolutePath();
        int total = 0;

        for (SkillBoxProperties.GitRepoConfig gitConfig : gitRepos) {
            if (gitConfig.getUrl() == null || gitConfig.getUrl().isBlank()) {
                log.warn("Git skill repository URL is empty, skipping");
                continue;
            }

            try {
                Path cloneDir;
                if (gitConfig.getLocalPath() != null && !gitConfig.getLocalPath().isBlank()) {
                    cloneDir = Path.of(gitConfig.getLocalPath()).toAbsolutePath();
                } else {
                    String repoName = extractRepoName(gitConfig.getUrl());
                    cloneDir = baseSkillPath.resolve(".git-cache").resolve(repoName);
                }
                Files.createDirectories(cloneDir.getParent());

                try (GitSkillRepository repo = new GitSkillRepository(
                        gitConfig.getUrl(),
                        gitConfig.getBranch(),
                        cloneDir,
                        gitConfig.getSource(),
                        gitConfig.isAutoSync())) {
                    // 只用 clone 效果，不通过 repo 读 skills
                }

                int merged = mergeSkillsFromCloneDir(cloneDir, baseSkillPath, gitConfig.getUrl());
                total += merged;
                log.info("Merged {} skills from git: {}", merged, gitConfig.getUrl());
            } catch (Exception e) {
                log.warn("Failed to load git skills from '{}': {}", gitConfig.getUrl(), e.getMessage());
            }
        }
        return total;
    }

    /**
     * 扫描 Git clone 目录中的 Skill 子目录，复制到统一的 skill 目录。
     * 如果同名 Skill 已存在则跳过（本地优先）。
     */
    private int mergeSkillsFromCloneDir(Path cloneDir, Path unifiedDir, String sourceUrl) {
        Path skillsSource;
        Path skillsSubDir = cloneDir.resolve("skills");
        if (Files.exists(skillsSubDir) && Files.isDirectory(skillsSubDir)) {
            skillsSource = skillsSubDir;
        } else {
            skillsSource = cloneDir;
        }

        int merged = 0;
        try (Stream<Path> subdirs = Files.list(skillsSource)) {
            for (Path dir : (Iterable<Path>) subdirs::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                if (!Files.exists(dir.resolve("SKILL.md"))) {
                    continue;
                }

                String skillDirName = dir.getFileName().toString();
                Path targetDir = unifiedDir.resolve(skillDirName);

                if (Files.exists(targetDir)) {
                    log.info("Skill '{}' already exists, skipping (local takes priority)", skillDirName);
                    merged++;
                    continue;
                }

                copyDirectory(dir, targetDir);
                log.info("Merged skill '{}' from {} to {}", skillDirName, sourceUrl, targetDir.getFileName());
                merged++;
            }
        } catch (IOException e) {
            log.warn("Failed to scan skills from clone dir '{}': {}", cloneDir, e.getMessage());
        }
        return merged;
    }

    private String extractRepoName(String url) {
        String normalized = url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> entries = Files.walk(source, FileVisitOption.FOLLOW_LINKS)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Path relative = source.relativize(entry);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(entry, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
