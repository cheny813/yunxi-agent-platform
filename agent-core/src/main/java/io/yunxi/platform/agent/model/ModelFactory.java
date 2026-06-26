package io.yunxi.platform.agent.model;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.yunxi.platform.shared.config.AgentModelConfig;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型工厂，通过 {@link ModelRegistry} 统一创建 Model 实例。
 *
 * <p>
 * 在初始化时将 yunxi 自定义工厂注册到 ModelRegistry，随后统一通过
 * {@code ModelRegistry.resolve("provider:modelName")} 创建。
 * 利用 RC3 框架的 ModelRegistry 机制，避免手动构造各 ChatModel。
 * </p>
 *
 * <p>
 * 支持的供应商：
 * <ul>
 * <li>openai / dashscope / claude / deepseek — 通过 ModelRegistry 工厂</li>
 * <li>baidu / huawei — 框架未内置，保留自定义 BaiduModelProvider /
 * HuaweiModelProvider</li>
 * <li>ollama — 框架已内置，通过 {@code ollama:modelName} 格式</li>
 * </ul>
 * </p>
 */
@Component
public class ModelFactory {

        private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

        private final AgentscopeCoreProperties coreProperties;

        public ModelFactory(AgentscopeCoreProperties coreProperties) {
                this.coreProperties = coreProperties;
        }

        /**
         * 初始化时注册自定义 Model 工厂到 ModelRegistry。
         *
         * <p>
         * 框架内置的 ModelRegistry 工厂仅通过环境变量获取 API Key，
         * 而 yunxi 使用 Spring 配置系统。因此通过 registerFactory() 覆盖内置工厂，
         * 使用 yunxi 配置的 API Key、BaseURL、GenerateOptions。
         * </p>
         */
        @PostConstruct
        public void init() {
                var gen = coreProperties.getGeneration();

                // OpenAI 工厂
                ModelRegistry.registerFactory("openai:.+", modelId -> {
                        String modelName = modelId.substring("openai:".length());
                        var cfg = coreProperties.getOpenai();
                        return io.agentscope.core.model.OpenAIChatModel.builder()
                                        .apiKey(cfg.getApiKey() != null ? cfg.getApiKey() : coreProperties.getApiKey())
                                        .modelName(modelName)
                                        .baseUrl(cfg.getBaseUrl() != null ? cfg.getBaseUrl()
                                                        : "https://api.openai.com/v1")
                                        .stream(true)
                                        .generateOptions(buildOptions(gen, null))
                                        .build();
                });

                // DashScope 工厂
                ModelRegistry.registerFactory("dashscope:.+", modelId -> {
                        String modelName = modelId.substring("dashscope:".length());
                        var cfg = coreProperties.getDashscope();
                        // API Key优先级: provider级配置 > 全局配置 > 环境变量
                        String apiKey = cfg != null && cfg.getApiKey() != null && !cfg.getApiKey().isBlank()
                                ? cfg.getApiKey()
                                : (coreProperties.getApiKey() != null && !coreProperties.getApiKey().isBlank()
                                        ? coreProperties.getApiKey()
                                        : System.getenv("DASHSCOPE_API_KEY"));
                        if (apiKey == null || apiKey.isBlank()) {
                                throw new IllegalArgumentException(
                                        "DashScope API Key 未配置。请设置 agentscope.core.dashscope.api-key 或"
                                                + " agentscope.core.api-key 或环境变量 DASHSCOPE_API_KEY");
                        }
                        return io.agentscope.core.model.DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName(modelName)
                                        .stream(true)
                                        .defaultOptions(buildOptions(gen, null))
                                        .build();
                });

                // Anthropic/Claude 工厂
                ModelRegistry.registerFactory("anthropic:.+", modelId -> {
                        String modelName = modelId.substring("anthropic:".length());
                        var cfg = coreProperties.getOpenai(); // Anthropic 暂无独立配置，复用全局
                        return io.agentscope.core.model.AnthropicChatModel.builder()
                                        .apiKey(cfg.getApiKey() != null ? cfg.getApiKey() : coreProperties.getApiKey())
                                        .modelName(modelName)
                                        .baseUrl(cfg.getBaseUrl())
                                        .stream(true)
                                        .defaultOptions(buildOptions(gen, null))
                                        .build();
                });
                // 简写 claude: 映射到 anthropic
                ModelRegistry.registerFactory("claude:.+", modelId -> {
                        String modelName = modelId.substring("claude:".length());
                        var cfg = coreProperties.getOpenai();
                        return io.agentscope.core.model.AnthropicChatModel.builder()
                                        .apiKey(cfg.getApiKey() != null ? cfg.getApiKey() : coreProperties.getApiKey())
                                        .modelName(modelName)
                                        .baseUrl(cfg.getBaseUrl())
                                        .stream(true)
                                        .defaultOptions(buildOptions(gen, null))
                                        .build();
                });

                // DeepSeek 工厂（通过 OpenAI 兼容 + DeepSeekFormatter）
                ModelRegistry.registerFactory("deepseek:.+", modelId -> {
                        String modelName = modelId.substring("deepseek:".length());
                        var cfg = coreProperties.getOpenai();
                        return io.agentscope.core.model.OpenAIChatModel.builder()
                                        .apiKey(cfg.getApiKey() != null ? cfg.getApiKey() : coreProperties.getApiKey())
                                        .modelName(modelName)
                                        .baseUrl(cfg.getBaseUrl() != null ? cfg.getBaseUrl()
                                                        : "https://api.deepseek.com/v1")
                                        .stream(true)
                                        .formatter(new io.agentscope.core.formatter.openai.DeepSeekFormatter(true))
                                        .generateOptions(buildOptions(gen, null))
                                        .build();
                });

                log.info("ModelFactory: 已注册 openai/dashscope/anthropic/claude/deepseek 工厂到 ModelRegistry");
        }

        /**
         * 根据配置创建 Model 实例。
         *
         * <p>
         * 通过 ModelRegistry 解析 "provider:modelName" 格式的模型标识。
         * 先注册命名模型（带 GenerateOptions），再调用 resolve。
         * </p>
         *
         * @param config 模型配置，为 null 时使用全局默认配置
         * @return Model 实例
         * @throws IllegalArgumentException 不支持的供应商时抛出
         */
        public Model create(AgentModelConfig config) {
                String provider = config != null && config.getProvider() != null
                                ? config.getProvider()
                                : coreProperties.getProvider();
                String modelName = config != null && config.getModelName() != null
                                ? config.getModelName()
                                : coreProperties.getModelName();
                String modelId = provider + ":" + modelName;

                // 对 baidu/huawei — 框架未内置，保留自定义实现
                // V2.0-RC3: 框架未内置 Baidu/Huawei ChatModel，待官方补充后迁移
                if ("baidu".equals(provider)) {
                        GenerateOptions options = buildGenerateOptions(config);
                        log.info("创建 Model (自定义): provider=baidu, model={}", modelName);
                        return new BaiduModelProvider(
                                        resolveApiKey(config, "baidu"),
                                        resolveApiKey(config, "baidu"),
                                        modelName, options);
                }
                if ("huawei".equals(provider)) {
                        GenerateOptions options = buildGenerateOptions(config);
                        log.info("创建 Model (自定义): provider=huawei, model={}", modelName);
                        return new HuaweiModelProvider(
                                        resolveApiKey(config, "huawei"),
                                        resolveApiKey(config, "huawei"),
                                        modelName, options);
                }

                // 其他 provider 通过 ModelRegistry 创建
                log.info("创建 Model (ModelRegistry): provider={}, model={}", provider, modelName);

                // 若配置中传了 GenerateOptions，注册一个带特定 options 的命名模型
                if (config != null && hasCustomOptions(config)) {
                        GenerateOptions options = buildGenerateOptions(config);
                        // 用全名注册，避免与其他同名模型冲突
                        String namedKey = modelId + "#" + System.identityHashCode(options);
                        registerModelWithOptions(namedKey, provider, modelName, options);
                        return ModelRegistry.resolve(namedKey);
                }

                return ModelRegistry.resolve(modelId);
        }

        /**
         * 构建生成选项。
         *
         * <p>
         * 合并策略：配置中的参数优先，未指定的参数使用全局默认值。
         * </p>
         */
        public GenerateOptions buildGenerateOptions(AgentModelConfig config) {
                var gen = coreProperties.getGeneration();
                var builder = GenerateOptions.builder();

                Double temperature = config != null && config.getTemperature() != null
                                ? config.getTemperature()
                                : gen.getTemperature();
                if (temperature != null)
                        builder.temperature(temperature);

                Integer maxTokens = config != null && config.getMaxTokens() != null
                                ? config.getMaxTokens()
                                : gen.getMaxTokens();
                if (maxTokens != null)
                        builder.maxTokens(maxTokens);

                Double topP = config != null && config.getTopP() != null
                                ? config.getTopP()
                                : gen.getTopP();
                if (topP != null)
                        builder.topP(topP);

                Boolean cacheControl = config != null && config.getCacheControl() != null
                                ? config.getCacheControl()
                                : gen.getCacheControl();
                if (Boolean.TRUE.equals(cacheControl))
                        builder.cacheControl(true);

                return builder.build();
        }

        // ==================== 私有辅助方法 ====================

        private GenerateOptions buildOptions(AgentscopeCoreProperties.GenerationConfig gen,
                        AgentModelConfig config) {
                var builder = GenerateOptions.builder();
                if (gen.getTemperature() != null)
                        builder.temperature(gen.getTemperature());
                if (gen.getMaxTokens() != null)
                        builder.maxTokens(gen.getMaxTokens());
                if (gen.getTopP() != null)
                        builder.topP(gen.getTopP());
                if (Boolean.TRUE.equals(gen.getCacheControl()))
                        builder.cacheControl(true);
                return builder.build();
        }

        private String resolveApiKey(AgentModelConfig config, String provider) {
                if (config != null && config.getApiKey() != null)
                        return config.getApiKey();
                if (coreProperties.getApiKey() != null && !coreProperties.getApiKey().isBlank())
                        return coreProperties.getApiKey();
                // 兜底：环境变量（与框架内置 ModelRegistry 行为一致）
                String envKey = switch (provider) {
                        case "dashscope" -> "DASHSCOPE_API_KEY";
                        case "openai", "deepseek" -> "OPENAI_API_KEY";
                        case "claude", "anthropic" -> "ANTHROPIC_API_KEY";
                        default -> null;
                };
                if (envKey != null) {
                        String envVal = System.getenv(envKey);
                        if (envVal != null && !envVal.isBlank())
                                return envVal;
                }
                return null;
        }

        private boolean hasCustomOptions(AgentModelConfig config) {
                return config.getTemperature() != null
                                || config.getMaxTokens() != null
                                || config.getTopP() != null
                                || config.getCacheControl() != null;
        }

        private void registerModelWithOptions(String namedKey, String provider,
                        String modelName, GenerateOptions options) {
                ModelRegistry.register(namedKey, switch (provider) {
                        case "openai" -> io.agentscope.core.model.OpenAIChatModel.builder()
                                        .apiKey(coreProperties.getApiKey()).modelName(modelName)
                                        .stream(true).generateOptions(options).build();
                        case "dashscope" -> io.agentscope.core.model.DashScopeChatModel.builder()
                                        .apiKey(coreProperties.getApiKey()).modelName(modelName)
                                        .stream(true).defaultOptions(options).build();
                        case "claude", "anthropic" -> io.agentscope.core.model.AnthropicChatModel.builder()
                                        .apiKey(coreProperties.getApiKey()).modelName(modelName)
                                        .stream(true).defaultOptions(options).build();
                        default -> throw new IllegalArgumentException("不支持的供应商: " + provider);
                });
        }
}
