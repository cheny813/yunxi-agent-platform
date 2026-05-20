package io.yunxi.platform.framework.skill;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件系统技能注册表
 *
 * <p>
 * 从 skills 目录加载技能定义并管理技能适配器
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class FileSystemSkillRegistry {

    /** 技能目录路径 */
    @Value("${yunxi.agent.skills.dir:skills}")
    private String skillsDir;

    /** 技能适配器映射 */
    private final Map<String, SkillAdapter> skillAdapters = new ConcurrentHashMap<>();

    /**
     * 初始化技能注册表
     */
    public void initialize() {
        try {
            Path skillsPath = Paths.get(skillsDir);
            if (!Files.exists(skillsPath)) {
                log.warn("技能目录不存在: {}", skillsDir);
                return;
            }

            Files.list(skillsPath)
                    .filter(Files::isDirectory)
                    .forEach(this::loadSkillFromDirectory);

            log.info("技能注册表初始化完成，加载技能数量: {}", skillAdapters.size());
        } catch (IOException e) {
            log.error("初始化技能注册表失败", e);
        }
    }

    /**
     * 从目录加载技能
     */
    private void loadSkillFromDirectory(Path skillDir) {
        try {
            String skillName = skillDir.getFileName().toString();
            Path skillMdFile = skillDir.resolve("SKILL.md");

            if (!Files.exists(skillMdFile)) {
                log.warn("技能文件不存在: {}", skillMdFile);
                return;
            }

            // 读取技能定义文件
            List<String> lines = Files.readAllLines(skillMdFile);
            SkillDefinition definition = parseSkillDefinition(skillName, lines);

            // 创建技能适配器
            SkillAdapter adapter = createSkillAdapter(definition);
            skillAdapters.put(skillName, adapter);

            log.info("加载技能: {} - {}", skillName, definition.getDescription());
        } catch (IOException e) {
            log.error("加载技能失败: {}", skillDir, e);
        }
    }

    /**
     * 解析技能定义
     */
    private SkillDefinition parseSkillDefinition(String name, List<String> lines) {
        SkillDefinition definition = new SkillDefinition();
        definition.setName(name);

        try {
            boolean inFrontMatter = false;
            StringBuilder descriptionBuilder = new StringBuilder();

            for (String line : lines) {
                if (line.trim().equals("---")) {
                    inFrontMatter = !inFrontMatter;
                    continue;
                }

                if (inFrontMatter) {
                    // 解析 YAML front matter
                    if (line.startsWith("name:")) {
                        definition.setName(line.substring("name:".length()).trim());
                    } else if (line.startsWith("description:")) {
                        definition.setDescription(line.substring("description:".length()).trim());
                    }
                } else {
                    // 解析描述内容
                    if (line.startsWith("#") && definition.getDescription() == null) {
                        definition.setDescription(line.substring(line.indexOf(' ')).trim());
                    }
                }
            }

            // 如果从 front matter 中没有找到描述，使用内容的第一行标题
            if (definition.getDescription() == null) {
                String firstTitle = lines.stream()
                        .filter(l -> l.startsWith("# "))
                        .findFirst()
                        .map(l -> l.substring(2).trim())
                        .orElse("");
                definition.setDescription(firstTitle);
            }
        } catch (Exception e) {
            log.warn("解析技能定义失败，使用默认值: {}", name);
            definition.setDescription("技能描述");
        }

        return definition;
    }

    /**
     * 创建技能适配器
     */
    private SkillAdapter createSkillAdapter(SkillDefinition definition) {
        return new BaseSkillAdapter(definition.getName(), definition.getDescription());
    }

    /**
     * 获取所有技能
     */
    public List<SkillAdapter> getAllSkills() {
        return new ArrayList<>(skillAdapters.values());
    }

    /**
     * 根据名称获取技能
     */
    public Optional<SkillAdapter> getSkill(String name) {
        return Optional.ofNullable(skillAdapters.get(name));
    }

    /**
     * 注册技能适配器
     */
    public void registerSkill(SkillAdapter adapter) {
        skillAdapters.put(adapter.getName(), adapter);
        log.info("注册技能: {}", adapter.getName());
    }

    /**
     * 取消注册技能
     */
    public void unregisterSkill(String name) {
        skillAdapters.remove(name);
        log.info("取消注册技能: {}", name);
    }

    /**
     * 技能定义类
     */
    @Data
    private static class SkillDefinition {
        private String name;
        private String description;
    }
}