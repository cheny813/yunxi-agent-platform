package io.yunxi.platform.framework.model;

import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.yunxi.platform.shared.config.AgentModelConfig;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型工厂 — 统一创建框架 Model 实例
 * <p>
 * 直接使用 agentscope 框架的 Model 实现（OpenAIChatModel / AnthropicChatModel / DashScopeChatModel 等）。
 * 各提供商已内置正确的消息角色映射和缓存支持。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    /** 全局默认配置 */
    private final AgentscopeCoreProperties coreProperties;

    public ModelFactory(AgentscopeCoreProperties coreProperties) {
        this.coreProperties = coreProperties;
    }

    /**
     * 根据 Agent 模型配置创建框架 Model 实例
     *
     * @param config Agent 模型配置（可 null，为 null 时使用全局默认配置）
     * @return 框架 Model 实例
     * @throws IllegalArgumentException 提供商不支持时抛出
     */
    public Model create(AgentModelConfig config) {
        // 解析配置，未指定时回退到全局默认
        String provider = config != null && config.getProvider() != null
                ? config.getProvider()
                : coreProperties.getProvider();
        String apiKey = config != null && config.getApiKey() != null
                ? config.getApiKey()
                : coreProperties.getApiKey();
        String modelName = config != null && config.getModelName() != null
                ? config.getModelName()
                : coreProperties.getModelName();
        String baseUrl = config != null ? config.getBaseUrl() : null;

        // 构建框架生成参数
        GenerateOptions options = buildGenerateOptions(config);

        log.info("创建 Model: provider={}, model={}", provider, modelName);

        return switch (provider.toLowerCase()) {
            case "openai" -> OpenAIChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .baseUrl(baseUrl != null ? baseUrl : "https://api.openai.com/v1")
                    .stream(true)
                    .generateOptions(options)
                    .build();

            case "claude" -> AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .baseUrl(baseUrl)
                    .stream(true)
                    .defaultOptions(options)
                    .build();

            case "dashscope" -> DashScopeChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .stream(true)
                    .defaultOptions(options)
                    .build();

            case "deepseek" -> OpenAIChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .baseUrl(baseUrl != null ? baseUrl : "https://api.deepseek.com/v1")
                    .stream(true)
                    .formatter(new DeepSeekFormatter(true))
                    .generateOptions(options)
                    .build();

            case "baidu" -> new BaiduModelProvider(apiKey, apiKey, modelName, options);

            case "huawei" -> new HuaweiModelProvider(apiKey, apiKey, modelName, options);

            default -> throw new IllegalArgumentException("不支持的模型提供商: " + provider);
        };
    }

    /**
     * 构建框架 {@link GenerateOptions}，将 yunxi 配置映射到框架参数
     * <p>
     * 合并策略：Agent 定义配置 > 全局默认配置 > 框架默认值
     * </p>
     *
     * @param config Agent 模型配置（可 null）
     * @return 框架生成参数
     */
    public GenerateOptions buildGenerateOptions(AgentModelConfig config) {
        var gen = coreProperties.getGeneration();

        GenerateOptions.Builder builder = GenerateOptions.builder();

        Double temperature = config != null && config.getTemperature() != null
                ? config.getTemperature()
                : gen.getTemperature();
        if (temperature != null) {
            builder.temperature(temperature);
        }

        Integer maxTokens = config != null && config.getMaxTokens() != null
                ? config.getMaxTokens()
                : gen.getMaxTokens();
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }

        Double topP = config != null && config.getTopP() != null
                ? config.getTopP()
                : gen.getTopP();
        if (topP != null) {
            builder.topP(topP);
        }

        Boolean cacheControl = config != null && config.getCacheControl() != null
                ? config.getCacheControl()
                : gen.getCacheControl();
        if (Boolean.TRUE.equals(cacheControl)) {
            builder.cacheControl(true);
        }

        return builder.build();
    }
}