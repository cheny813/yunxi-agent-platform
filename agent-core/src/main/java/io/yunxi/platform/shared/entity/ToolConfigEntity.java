package io.yunxi.platform.shared.entity;

import java.time.LocalDateTime;

/**
 * 工具配置数据库实体
 * <p>
 * 对应数据库表: tool_configs
 * 用于存储工具插件的配置信息，包括启用状态、配置参数等
 * </p>
 * <p>
 * 字段说明:
 * <ul>
 * <li>id: 配置 ID（自增主键）</li>
 * <li>toolName: 工具名称（唯一）</li>
 * <li>enabled: 是否启用</li>
 * <li>config: 工具配置（JSON格式）</li>
 * <li>description: 工具描述</li>
 * <li>createdAt: 创建时间</li>
 * <li>updatedAt: 更新时间</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class ToolConfigEntity {

    /**
     * 配置 ID（主键）
     * 使用 AUTO_INCREMENT 自增
     */
    private Long id;

    /**
     * 工具名称
     * 全局唯一，对应Tool接口的实现类名称
     * 例如: HttpTool, CalculatorTool, DatabaseTool
     */
    private String toolName;

    /**
     * 是否启用
     * true-启用工具，false-禁用工具
     * 默认: true
     */
    private Boolean enabled;

    /**
     * 工具配置
     * JSON格式存储，包含工具的配置参数
     * 例如: {"timeout":30,"apiKey":"xxx"}
     */
    private String config;

    /**
     * 工具描述
     * 说明工具的功能和用途
     */
    private String description;

    /**
     * 创建时间
     * 工具配置创建的时间戳
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     * 工具配置最后更新的时间戳
     */
    private LocalDateTime updatedAt;

    /**
     * 在插入前初始化时间戳和默认值
     * <p>
     * 在调用Mapper.insert()前调用此方法
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
     * 在调用Mapper.update()前调用此方法
     * 自动设置 updatedAt 为当前时间
     * </p>
     */
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getter和Setter方法

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
