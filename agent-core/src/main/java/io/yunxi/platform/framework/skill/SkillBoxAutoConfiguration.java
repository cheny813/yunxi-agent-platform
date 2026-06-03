package io.yunxi.platform.framework.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        // 2. 从统一文件系统目录加载所有 Skill
        String skillDir = resolveSkillDir(properties);
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
}
