package io.yunxi.platform.shared.dto;

import java.time.Instant;

/**
 * Agent 信息 DTO
 *
 * <p>
 * 用于返回 Agent 的基本信息，包括名称、描述、提示词、模型和创建时间。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class AgentInfoDto {

    private String name;

    /** Agent 显示名称/描述（前端下拉框展示） */
    private String description;

    private String prompt;

    private String modelName;

    private Instant createdAt;

    public AgentInfoDto() {
    }

    public AgentInfoDto(String name, String prompt, String modelName, Instant createdAt) {
        this.name = name;
        this.prompt = prompt;
        this.modelName = modelName;
        this.createdAt = createdAt;
    }

    public AgentInfoDto(String name, String description, String prompt, String modelName, Instant createdAt) {
        this.name = name;
        this.description = description;
        this.prompt = prompt;
        this.modelName = modelName;
        this.createdAt = createdAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
