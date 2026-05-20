package io.yunxi.platform.framework.skill.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 技能模板
 * <p>
 * 用于生成技能内容的模板结构
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class SkillTemplate {

    /**
     * 技能名称
     */
    private String name;

    /**
     * 技能描述
     */
    private String description;

    /**
     * 技能分类
     */
    private String category;

    /**
     * 前置条件
     */
    private List<String> prerequisites = new ArrayList<>();

    /**
     * 关键步骤
     */
    private List<String> steps = new ArrayList<>();

    /**
     * 注意事项
     */
    private List<String> notes = new ArrayList<>();

    /**
     * 使用示例
     */
    private String example;

    /**
     * 参数说明（参数名 -> 描述）
     */
    private Map<String, String> parameters;

    /**
     * 使用示例列表
     */
    private List<String> examples = new ArrayList<>();

    /**
     * 验证方法
     */
    private String validationMethod;

    /**
     * 相关技能
     */
    private List<String> relatedSkills = new ArrayList<>();

    /**
     * 额外元数据
     */
    private SkillMetadata metadata;

    // Getter methods
    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public List<String> getPrerequisites() {
        return prerequisites;
    }

    public List<String> getSteps() {
        return steps;
    }

    public List<String> getNotes() {
        return notes;
    }

    public String getExample() {
        return example;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public List<String> getExamples() {
        return examples;
    }

    public String getValidationMethod() {
        return validationMethod;
    }

    public List<String> getRelatedSkills() {
        return relatedSkills;
    }

    public SkillMetadata getMetadata() {
        return metadata;
    }

    // Setter methods
    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setPrerequisites(List<String> prerequisites) {
        this.prerequisites = prerequisites;
    }

    public void setSteps(List<String> steps) {
        this.steps = steps;
    }

    public void setNotes(List<String> notes) {
        this.notes = notes;
    }

    public void setExample(String example) {
        this.example = example;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    public void setExamples(List<String> examples) {
        this.examples = examples;
    }

    public void setValidationMethod(String validationMethod) {
        this.validationMethod = validationMethod;
    }

    public void setRelatedSkills(List<String> relatedSkills) {
        this.relatedSkills = relatedSkills;
    }

    public void setMetadata(SkillMetadata metadata) {
        this.metadata = metadata;
    }

    public static class SkillMetadata {
        /**
         * 作者
         */
        private String author;

        /**
         * 版本
         */
        private String version;

        /**
         * 创建时间
         */
        private String createdAt;

        /**
         * 标签
         */
        private List<String> tags;

        /**
         * 难度等级（beginner, intermediate, advanced）
         */
        private String difficulty;

        // Getter methods
        public String getAuthor() {
            return author;
        }

        public String getVersion() {
            return version;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public List<String> getTags() {
            return tags;
        }

        public String getDifficulty() {
            return difficulty;
        }

        // Setter methods
        public void setAuthor(String author) {
            this.author = author;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public void setDifficulty(String difficulty) {
            this.difficulty = difficulty;
        }
    }
}
