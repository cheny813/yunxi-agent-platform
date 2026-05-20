package io.yunxi.platform.shared.entity;

import java.time.LocalDateTime;

/**
 * Agent 数据库实体
 * <p>
 * 对应数据库表: agents
 * 用于存储 AI Agent 的配置信息，包括提示词、模型参数等
 * </p>
 * <p>
 * 字段说明:
 * <ul>
 * <li>name: Agent 唯一标识（主键）</li>
 * <li>prompt: 系统提示词</li>
 * <li>modelName: 使用的模型名称</li>
 * <li>provider: 模型提供商（如：openai, claude, dashscope）</li>
 * <li>temperature: 温度参数，控制输出的随机性</li>
 * <li>maxTokens: 最大生成长度</li>
 * <li>enabled: 是否启用</li>
 * <li>description: Agent 描述</li>
 * <li>apiKeyHash: API Key 的哈希值（可选，用于安全存储）</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class AgentEntity {

    /**
     * Agent 名称（主键）
     * 全局唯一，作为Agent的标识
     */
    private String name;

    /**
     * 系统提示词
     * 定义 Agent 的角色和行为模式
     */
    private String prompt;

    /**
     * 模型名称
     * 例如: gpt-4, claude-3, qwen-plus
     */
    private String modelName;

    /**
     * 模型提供商
     * 例如: openai, claude, dashscope
     */
    private String provider;

    /**
     * 温度参数
     * 范围: 0.0-2.0，值越高输出越随机
     * 默认: 0.7
     */
    private Double temperature;

    /**
     * 最大Token数
     * 限制模型生成的最大长度
     */
    private Integer maxTokens;

    /**
     * 是否启用
     * true-启用，false-禁用
     * 默认: true
     */
    private Boolean enabled;

    /**
     * Agent 描述
     * 用于说明 Agent 的用途和功能
     */
    private String description;

    /**
     * API Key 哈希值
     * 可选字段，用于安全存储 API Key 的哈希值
     * 建议不要直接存储 API Key，而是存储其哈希值
     */
    private String apiKeyHash;

    /**
     * 创建时间
     * 记录 Agent 创建的时间戳
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     * 记录 Agent 最后更新的时间戳
     */
    private LocalDateTime updatedAt;

    /**
     * 在插入前初始化时间戳和默认值
     * <p>
     * 在调用Mapper.save()前调用此方法
     * 自动设置:
     * <ul>
     * <li>createdAt: 当前时间</li>
     * <li>updatedAt: 当前时间</li>
     * <li>enabled: true（如果为null）</li>
     * </ul>
     * </p>
     */
    public void preInsert() {
        LocalDateTime now = LocalDateTime.now();

        this.createdAt = now;
        this.updatedAt = now;
        if (this.enabled == null) {
            this.enabled = true;
        }
    }

    /**
     * 在更新前更新时间戳
     * <p>
     * 在调用Mapper.save()更新已存在记录前调用此方法
     * 自动设置 updatedAt 为当前时间
     * </p>
     */
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getter和Setter方法

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public void setApiKeyHash(String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
