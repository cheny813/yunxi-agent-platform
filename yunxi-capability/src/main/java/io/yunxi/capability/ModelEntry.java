package io.yunxi.capability;

import java.util.List;

/**
 * 能力层。模型目录中的单条模型定义（纯数据，由 {@code yunxi.models.catalog} 绑定）。
 *
 * <p>不携带任何框架 Provider 实现，只声明「逻辑名 → 框架模型 id」所需的元数据；
 * 框架模型 id 由 {@code provider + ":" + model} 拼接（例如 {@code dashscope:qwen3.8-27b}），
 * 经 {@link ModelFactory} 透传给 AgentScope-Java 的 {@code ModelRegistry} 解析。</p>
 */
public class ModelEntry {

    /** 框架 Provider 标识：dashscope / ollama 等，必须与 AgentScope-Java 已注册 Provider 的 providerId 一致。 */
    private String provider;

    /** 模型名，例如 qwen3.8-27b / bge-m3 / qwen2.5:7b（ollama 模型名可含冒号）。 */
    private String model;

    /** 承载 API Key 的环境变量名，例如 DASHSCOPE_API_KEY；本地模型（ollama）可空。 */
    private String apiKeyEnv;

    /** 用途分类：llm（对话大模型）/ decision（本地决策模型）/ embedding（本地矢量模型），缺省 llm。 */
    private String type = "llm";

    /**
     * 成本档（纯选型元数据，不参与框架 Model 构建）：cheap / standard / premium；缺省 standard。
     * 自动选型在「能力满足」前提下按成本档升序取最便宜的模型以省成本。
     */
    private String costTier = "standard";

    /** 能力强项（纯选型元数据）：reasoning / coding / vision(看图) / video(视频理解) / long-context / multilingual / fast 等。 */
    private List<String> strengths;

    /** 总上下文窗口 token（纯选型元数据）；空表示未知，不参与过滤。 */
    private Integer contextWindow;

    /** 延迟档（纯选型元数据）：low / medium / high；缺省空。 */
    private String latencyTier;

    /** 最大输出 token（纯选型元数据，可选）；缺省空。 */
    private Integer maxOutputTokens;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKeyEnv() {
        return apiKeyEnv;
    }

    public void setApiKeyEnv(String apiKeyEnv) {
        this.apiKeyEnv = apiKeyEnv;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCostTier() {
        return costTier;
    }

    public void setCostTier(String costTier) {
        this.costTier = costTier;
    }

    public List<String> getStrengths() {
        return strengths;
    }

    public void setStrengths(List<String> strengths) {
        this.strengths = strengths;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow;
    }

    public String getLatencyTier() {
        return latencyTier;
    }

    public void setLatencyTier(String latencyTier) {
        this.latencyTier = latencyTier;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }
}
