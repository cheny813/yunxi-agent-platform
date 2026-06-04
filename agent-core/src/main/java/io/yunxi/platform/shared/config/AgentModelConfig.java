package io.yunxi.platform.shared.config;

import java.util.Map;

/**
 * Agent 定义中的模型配置（仅用于 YAML 反序列化）
 * <p>
 * 与框架的 ModelProperties 不同，此类仅用于 agent-definitions/*.yml 的配置读取，
 * 是纯 DTO，不含业务逻辑。创建 Agent 时通过 ModelFactory.create() 使用。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class AgentModelConfig {

    private String provider;
    private String apiKey;
    private String modelName;
    private Double temperature;
    private Integer maxTokens;
    private String baseUrl;
    private String structuredOutputSchema;

    // ========== 新增：框架生成参数 ==========

    /** 是否启用 Prompt Caching（默认 false） */
    private Boolean cacheControl;

    /** top_p 采样参数 */
    private Double topP;

    /** 额外框架参数扩展点（如 stop、seed 等） */
    private Map<String, Object> extraOptions;

    public AgentModelConfig() {}

    public AgentModelConfig(String provider, String apiKey, String modelName) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getStructuredOutputSchema() { return structuredOutputSchema; }
    public void setStructuredOutputSchema(String schema) { this.structuredOutputSchema = schema; }
    public Boolean getCacheControl() { return cacheControl; }
    public void setCacheControl(Boolean cacheControl) { this.cacheControl = cacheControl; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public Map<String, Object> getExtraOptions() { return extraOptions; }
    public void setExtraOptions(Map<String, Object> extraOptions) { this.extraOptions = extraOptions; }
}