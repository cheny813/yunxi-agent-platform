package io.yunxi.platform.shared.dto;

/**
 * Agent 配置 DTO
 *
 * <p>
 * 用于传递 Agent 的基础配置参数，包括模型设置和生成参数。
 * 所有字段均为可选，未设置时使用全局默认值。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class AgentConfigDto {

    /**
     * 系统提示词（可选，不填使用默认值）
     */
    private String prompt;

    /**
     * 模型名称（可选，不填使用默认值）
     */
    private String modelName;

    /**
     * 模型提供商（可选，默认 dashscope）
     */
    private String provider;

    /**
     * API Key（可选，不填使用默认值）
     */
    private String apiKey;

    /**
     * 温度参数（可选，控制输出随机性）
     */
    private Double temperature;

    /**
     * 最大 Token 数（可选）
     */
    private Integer maxTokens;

    /**
     * 默认构造函数
     */
    public AgentConfigDto() {
    }

    /**
     * 构造函数
     *
     * @param prompt    系统提示词
     * @param modelName 模型名称
     */
    public AgentConfigDto(String prompt, String modelName) {
        this.prompt = prompt;
        this.modelName = modelName;
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

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
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
}
