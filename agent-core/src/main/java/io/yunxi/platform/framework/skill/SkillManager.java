package io.yunxi.platform.framework.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.yunxi.platform.framework.skill.model.SkillTemplate;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 技能管理服务
 * <p>
 * 负责技能的创建、加载、更新、删除等操作
 * 支持YAML格式的技能文件
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Service
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    /** 技能输出路径 */
    @Value("${yunxi.skill.output-path:~/.yunxi/skills/}")
    private String skillsOutputPath;

    /** YAML 对象映射器 */
    private final ObjectMapper yamlMapper;
    /** 技能缓存 */
    private final Map<String, SkillTemplate> skillCache = new ConcurrentHashMap<>();

    /**
     * 构造技能管理器
     */
    public SkillManager() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * 初始化技能管理器
     */
    @PostConstruct
    public void init() {
        try {
            // 确保技能目录存在
            Path skillsPath = Paths.get(expandHomeDir(skillsOutputPath));
            if (!Files.exists(skillsPath)) {
                Files.createDirectories(skillsPath);
                log.info("Created skills directory: {}", skillsPath);
            }

            // 加载所有现有技能
            loadAllSkills();
            log.info("SkillManager initialized: loaded {} skills", skillCache.size());
        } catch (Exception e) {
            log.error("Failed to initialize SkillManager", e);
        }
    }

    /**
     * 创建技能
     *
     * @param template 技能模板
     * @return 是否成功
     */
    public boolean createSkill(SkillTemplate template) {
        if (template == null || template.getName() == null || template.getName().isBlank()) {
            log.error("Invalid skill template: name is required");
            return false;
        }

        try {
            // 验证技能名称
            if (!isValidSkillName(template.getName())) {
                log.error("Invalid skill name: {}", template.getName());
                return false;
            }

            // 检查是否已存在
            if (skillExists(template.getName())) {
                log.warn("Skill already exists: {}", template.getName());
                return false;
            }

            // 创建技能目录
            Path skillDir = getSkillDirectory(template.getName());
            Files.createDirectories(skillDir);

            // 创建skill.yaml文件
            Path skillFile = skillDir.resolve("skill.yaml");
            String yamlContent = formatSkillYaml(template);
            Files.write(skillFile, yamlContent.getBytes(StandardCharsets.UTF_8));

            // 创建README.md文件
            Path readmeFile = skillDir.resolve("README.md");
            String readmeContent = formatSkillReadme(template);
            Files.write(readmeFile, readmeContent.getBytes(StandardCharsets.UTF_8));

            // 更新缓存
            skillCache.put(template.getName(), template);

            log.info("Created skill '{}' at: {}", template.getName(), skillDir);
            return true;
        } catch (Exception e) {
            log.error("Failed to create skill: {}", template.getName(), e);
            return false;
        }
    }

    /**
     * 加载技能
     *
     * @param skillName 技能名称
     * @return 技能模板（如果存在）
     */
    public Optional<SkillTemplate> loadSkill(String skillName) {
        // 先从缓存中查找
        SkillTemplate cached = skillCache.get(skillName);
        if (cached != null) {
            return Optional.of(cached);
        }

        try {
            Path skillFile = getSkillFilePath(skillName);
            if (!Files.exists(skillFile)) {
                return Optional.empty();
            }

            SkillTemplate template = yamlMapper.readValue(skillFile.toFile(), SkillTemplate.class);
            skillCache.put(skillName, template);
            return Optional.of(template);
        } catch (Exception e) {
            log.error("Failed to load skill: {}", skillName, e);
            return Optional.empty();
        }
    }

    /**
     * 列出所有技能
     *
     * @return 技能名称列表
     */
    public List<String> listSkills() {
        try {
            Path skillsPath = Paths.get(expandHomeDir(skillsOutputPath));
            if (!Files.exists(skillsPath)) {
                return Collections.emptyList();
            }

            try (Stream<Path> paths = Files.list(skillsPath)) {
                return paths
                        .filter(Files::isDirectory)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(this::isValidSkillName)
                        .sorted()
                        .collect(java.util.stream.Collectors.toList());
            }
        } catch (Exception e) {
            log.error("Failed to list skills", e);
            return Collections.emptyList();
        }
    }

    /**
     * 更新技能
     *
     * @param skillName 技能名称
     * @param template  新的技能模板
     * @return 是否成功
     */
    public boolean updateSkill(String skillName, SkillTemplate template) {
        if (!skillExists(skillName)) {
            log.error("Skill not found: {}", skillName);
            return false;
        }

        try {
            // 确保名称一致
            template.setName(skillName);

            // 更新技能文件
            Path skillFile = getSkillFilePath(skillName);
            String yamlContent = formatSkillYaml(template);
            Files.write(skillFile, yamlContent.getBytes(StandardCharsets.UTF_8));

            // 更新README文件
            Path readmeFile = getSkillDirectory(skillName).resolve("README.md");
            String readmeContent = formatSkillReadme(template);
            Files.write(readmeFile, readmeContent.getBytes(StandardCharsets.UTF_8));

            // 更新缓存
            skillCache.put(skillName, template);

            log.info("Updated skill: {}", skillName);
            return true;
        } catch (Exception e) {
            log.error("Failed to update skill: {}", skillName, e);
            return false;
        }
    }

    /**
     * 删除技能
     *
     * @param skillName 技能名称
     * @return 是否成功
     */
    public boolean deleteSkill(String skillName) {
        if (!skillExists(skillName)) {
            log.warn("Skill not found: {}", skillName);
            return false;
        }

        try {
            Path skillDir = getSkillDirectory(skillName);
            deleteDirectory(skillDir);
            skillCache.remove(skillName);
            log.info("Deleted skill: {}", skillName);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete skill: {}", skillName, e);
            return false;
        }
    }

    /**
     * 检查技能是否存在
     *
     * @param skillName 技能名称
     * ~return 是否存在
     */
    public boolean skillExists(String skillName) {
        return Files.exists(getSkillDirectory(skillName)) ||
                skillCache.containsKey(skillName);
    }

    /**
     * 获取技能路径
     *
     * @param skillName 技能名称
     * @return 技能目录路径
     */
    public String getSkillPath(String skillName) {
        return getSkillDirectory(skillName).toString();
    }

    /**
     * 获取技能文件路径
     *
     * @param skillName 技能名称
     * @return skill.yaml文件路径
     */
    private Path getSkillFilePath(String skillName) {
        return getSkillDirectory(skillName).resolve("skill.yaml");
    }

    /**
     * 获取技能目录
     *
     * @param skillName 技能名称
     * @return 技能目录路径
     */
    private Path getSkillDirectory(String skillName) {
        return Paths.get(expandHomeDir(skillsOutputPath), skillName);
    }

    /**
     * 加载所有技能
     */
    private void loadAllSkills() {
        List<String> skillNames = listSkills();
        for (String skillName : skillNames) {
            loadSkill(skillName).ifPresent(template ->
                    skillCache.put(skillName, template)
            );
        }
    }

    /**
     * 验证技能名称
     *
     * @param name 技能名称
     * @return 是否有效
     */
    private boolean isValidSkillName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }

        // 只允许字母、数字、下划线和短横线
        return name.matches("^[a-zA-Z0-9_-]+$");
    }

    /**
     * 格式化技能为YAML
     *
     * @param template 技能模板
     * @return YAML字符串
     */
    private String formatSkillYaml(SkillTemplate template) {
        StringBuilder yaml = new StringBuilder();

        // YAML前置信息
        yaml.append("# Skill: ").append(template.getName()).append("\n");
        yaml.append("# Description: ").append(template.getDescription()).append("\n");
        yaml.append("# Category: ").append(template.getCategory()).append("\n");
        yaml.append("# Created: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        // 元数据
        if (template.getMetadata() != null) {
            yaml.append("metadata:\n");
            SkillTemplate.SkillMetadata meta = template.getMetadata();
            if (meta.getAuthor() != null) {
                yaml.append("  author: \"").append(meta.getAuthor()).append("\"\n");
            }
            if (meta.getVersion() != null) {
                yaml.append("  version: \"").append(meta.getVersion()).append("\"\n");
            }
            if (meta.getDifficulty() != null) {
                yaml.append("  difficulty: \"").append(meta.getDifficulty()).append("\"\n");
            }
            if (meta.getTags() != null && !meta.getTags().isEmpty()) {
                yaml.append("  tags:\n");
                for (String tag : meta.getTags()) {
                    yaml.append("    - \"").append(tag).append("\"\n");
                }
            }
            yaml.append("\n");
        }

        // 基本信息
        yaml.append("name: \"").append(template.getName()).append("\"\n");
        yaml.append("description: \"").append(template.getDescription()).append("\"\n");
        if (template.getCategory() != null) {
            yaml.append("category: \"").append(template.getCategory()).append("\"\n");
        }

        // 前置条件
        if (template.getPrerequisites() != null && !template.getPrerequisites().isEmpty()) {
            yaml.append("prerequisites:\n");
            for (String prereq : template.getPrerequisites()) {
                yaml.append("  - \"").append(prereq).append("\"\n");
            }
        }

        // 关键步骤
        if (template.getSteps() != null && !template.getSteps().isEmpty()) {
            yaml.append("steps:\n");
            for (String step : template.getSteps()) {
                yaml.append("  - \"").append(step).append("\"\n");
            }
        }

        // 注意事项
        if (template.getNotes() != null && !template.getNotes().isEmpty()) {
            yaml.append("notes:\n");
            for (String note : template.getNotes()) {
                yaml.append("  - \"").append(note).append("\"\n");
            }
        }

        // 使用示例
        if (template.getExample() != null) {
            yaml.append("example: |\n");
            for (String line : template.getExample().split("\n")) {
                yaml.append("  ").append(line).append("\n");
            }
        }

        // 验证方法
        if (template.getValidationMethod() != null) {
            yaml.append("validation_method: \"").append(template.getValidationMethod()).append("\"\n");
        }

        // 相关技能
        if (template.getRelatedSkills() != null && !template.getRelatedSkills().isEmpty()) {
            yaml.append("related_skills:\n");
            for (String related : template.getRelatedSkills()) {
                yaml.append("  - \"").append(related).append("\"\n");
            }
        }

        return yaml.toString();
    }

    /**
     * 格式化技能README
     *
     * @param template 技能模板
     * @return README字符串
     */
    private String formatSkillReadme(SkillTemplate template) {
        StringBuilder readme = new StringBuilder();

        readme.append("# ").append(template.getName()).append("\n\n");

        if (template.getDescription() != null) {
            readme.append("## Description\n\n");
            readme.append(template.getDescription()).append("\n\n");
        }

        if (template.getCategory() != null) {
            readme.append("**Category**: ").append(template.getCategory()).append("\n\n");
        }

        if (template.getPrerequisites() != null && !template.getPrerequisites().isEmpty()) {
            readme.append("## Prerequisites\n\n");
            for (String prereq : template.getPrerequisites()) {
                readme.append("- ").append(prereq).append("\n");
            }
            readme.append("\n");
        }

        if (template.getSteps() != null && !template.getSteps().isEmpty()) {
            readme.append("## Steps\n\n");
            for (int i = 0; i < template.getSteps().size(); i++) {
                readme.append((i + 1)).append(". ").append(template.getSteps().get(i)).append("\n");
            }
            readme.append("\n");
        }

        if (template.getNotes() != null && !template.getNotes().isEmpty()) {
            readme.append("## Notes\n\n");
            for (String note : template.getNotes()) {
                readme.append("- ").append(note).append("\n");
            }
            readme.append("\n");
        }

        if (template.getExample() != null) {
            readme.append("## Example\n\n");
            readme.append("```\n");
            readme.append(template.getExample()).append("\n");
            readme.append("```\n\n");
        }

        if (template.getMetadata() != null) {
            readme.append("## Metadata\n\n");
            if (template.getMetadata().getAuthor() != null) {
                readme.append("**Author**: ").append(template.getMetadata().getAuthor()).append("\n");
            }
            if (template.getMetadata().getVersion() != null) {
                readme.append("**Version**: ").append(template.getMetadata().getVersion()).append("\n");
            }
            if (template.getMetadata().getDifficulty() != null) {
                readme.append("**Difficulty**: ").append(template.getMetadata().getDifficulty()).append("\n");
            }
            readme.append("\n");
        }

        return readme.toString();
    }

    /**
     * 递归删除目录
     *
     * @param path 目录路径
     * @throws IOException IO异常
     */
    private void deleteDirectory(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> paths = Files.list(path)) {
                paths.forEach(child -> {
                    try {
                        deleteDirectory(child);
                    } catch (IOException e) {
                        log.error("Failed to delete: {}", child, e);
                    }
                });
            }
        }
        Files.delete(path);
    }

    /**
     * 扩展家目录
     *
     * @param path 路径（可能包含~）
     * @return 扩展后的路径
     */
    private String expandHomeDir(String path) {
        if (path.startsWith("~/")) {
            String userHome = System.getProperty("user.home");
            return userHome + path.substring(1);
        }
        return path;
    }

    /**
     * 清空缓存
     */
    public void clearCache() {
        skillCache.clear();
        log.info("Skill cache cleared");
    }

    /**
     * 重新加载所有技能
     */
    public void reloadAllSkills() {
        clearCache();
        loadAllSkills();
        log.info("Reloaded all skills: {}", skillCache.size());
    }

    /**
     * 获取缓存统计信息
     *
     * @return 统计信息
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cachedSkills", skillCache.size());
        stats.put("totalSkills", listSkills().size());
        stats.put("skillsPath", expandHomeDir(skillsOutputPath));
        return stats;
    }
}
