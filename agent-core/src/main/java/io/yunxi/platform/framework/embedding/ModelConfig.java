package io.yunxi.platform.framework.embedding;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 模型配置
 * <p>
 * 支持多种模型提供商的统一配置格式
 * </p>
 *
 * @author yunxi-agent-platform
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelConfig {

    /**
     * 模型提供商类型
     * <p>
     * 可选值：dashscope、baidu、huawei、openai、claude、ollama
     * </p>
     */
    @NotBlank
    private String provider;

    /**
     * 主密钥（API Key / Access Key）
     * <p>
     * DashScope：API Key
     * 百度：API Key
     * 华为：Access Key (AK)
     * OpenAI：API Key
     * </p>
     */
    @NotBlank
    private String apiKey;

    /**
     * 模型名称或次级密钥
     * <p>
     * YAML 中可使用 name 键映射到此字段
     * </p>
     */
    @NotBlank
    @JsonAlias({ "name", "modelName" })
    private String modelName;

    /**
     * 华为云项目 ID（可选，仅华为需要）
     */
    private String projectId;

    /**
     * API 基础 URL（可选）
     * <p>
     * 用于自定义 API 端点（如本地部署的模型）
     * </p>
     */
    private String baseUrl;

    /**
     * 温度参数（可选，范围 0.0-2.0）
     */
    private Double temperature;

    /**
     * 最大 Token 数（可选）
     */
    private Integer maxTokens;

    /**
     * 结构化输出 Schema（可选）
     * <p>
     * JSON Schema 字符串，用于强制模型输出结构化数据
     * </p>
     */
    private String structuredOutputSchema;

    /**
     * 无参构造函数
     */
    public ModelConfig() {
    }

    /**
     * 三参数构造函数
     *
     * @param provider  模型提供商类型
     * @param apiKey    API 密钥
     * @param modelName 模型名称
     */
    public ModelConfig(String provider, String apiKey, String modelName) {
        this.provider = provider;
        this.apiKey = apiKey;
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

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
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

    public String getStructuredOutputSchema() {
        return structuredOutputSchema;
    }

    public void setStructuredOutputSchema(String structuredOutputSchema) {
        this.structuredOutputSchema = structuredOutputSchema;
    }
}
