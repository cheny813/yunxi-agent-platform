package io.yunxi.platform.agent.model;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ModelRegistry.ContextModelFactory;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
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
 * 在初始化时将 yunxi 自定义工厂（{@link ContextModelFactory}）注册到 ModelRegistry，
 * 随后统一通过 {@code ModelRegistry.resolve("provider:modelName", context)} 创建。
 * 利用框架的 ModelRegistry 机制，避免手动构造各 ChatModel；并借助
 * {@link ModelCreationContext} 实现「按 Agent 覆盖 API Key / BaseURL / 流式开关 /
 * 生成选项」的多租户能力。
 * </p>
 *
 * <p>
 * 单租户 vs 多租户（无需额外开关）：
 * <ul>
 * <li>单租户：Agent 定义中不填 {@code apiKey}/{@code baseUrl}/{@code stream}，
 * 工厂回退到全局 {@code agentscope.core.*} 配置（或对应环境变量）。</li>
 * <li>多租户（按需）：Agent 定义中显式填写 {@code apiKey}/{@code baseUrl}/{@code stream}，
 * 这些字段经 {@link ModelCreationContext} 透传给各提供商，实现每 Agent 独立账号。</li>
 * </ul>
 * 优先级统一为：{@code AgentModelConfig} 显式值 &gt; provider 级配置 &gt; 全局配置 &gt; 环境变量。
 * </p>
 *
 * <p>
 * 支持的供应商：
 * <ul>
 * <li>openai / deepseek — 通过 OpenAIChatModel（extensions-model-openai）</li>
 * <li>dashscope — 通过 DashScopeChatModel（extensions-model-dashscope）</li>
 * <li>claude / anthropic — 通过 AnthropicChatModel（extensions-model-anthropic）</li>
 * <li>baidu / huawei — 框架未内置，在 init() 注册为 ModelRegistry 工厂，
 * 复用自定义 BaiduModelProvider / HuaweiModelProvider（已支持按 Agent apiKey）</li>
 * <li>gemini / ollama — 无自定义工厂，由 SPI 提供商经 {@link ModelCreationContext} 自动发现并消费配置</li>
 * </ul>
 * </p>
 *
 * <p>
 * 注：各厂商 ChatModel 已从 {@code io.agentscope.core.model} 拆分到
 * 独立的 {@code agentscope-extensions-model-*} 模块，故本工厂需引入对应的 extension 依赖。
 * </p>
 */
@Component
public class ModelFactory {

        private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

        private final AgentscopeCoreProperties coreProperties;

        /**
         * 构造模型工厂。
         *
         * @param coreProperties 核心配置属性，提供各供应商与默认生成参数
         */
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

                // OpenAI 工厂（ContextModelFactory：从 ModelCreationContext 读取按 Agent 覆盖的配置）
                ModelRegistry.registerFactory("openai:.+", (modelId, ctx) -> {
                        String modelName = modelId.substring("openai:".length());
                        var cfg = coreProperties.getOpenai();
                        String apiKey = firstNonBlank(ctx.getApiKey(),
                                        cfg != null ? cfg.getApiKey() : null, coreProperties.getApiKey());
                        String baseUrl = firstNonBlank(ctx.getBaseUrl(),
                                        cfg != null ? cfg.getBaseUrl() : null, "https://api.openai.com/v1");
                        GenerateOptions options = ctx.component(GenerateOptions.class) != null
                                        ? ctx.component(GenerateOptions.class) : buildOptions(gen, null);
                        return OpenAIChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName(modelName)
                                        .baseUrl(baseUrl)
                                        .stream(streamOf(ctx))
                                        .generateOptions(options)
                                        .build();
                });

                // DashScope 工厂
                ModelRegistry.registerFactory("dashscope:.+", (modelId, ctx) -> {
                        String modelName = modelId.substring("dashscope:".length());
                        var cfg = coreProperties.getDashscope();
                        // 优先级: Agent 覆盖 > provider级配置 > 全局配置 > 环境变量
                        String apiKey = firstNonBlank(ctx.getApiKey(),
                                        cfg != null ? cfg.getApiKey() : null,
                                        coreProperties.getApiKey(), System.getenv("DASHSCOPE_API_KEY"));
                        if (apiKey == null || apiKey.isBlank()) {
                                throw new IllegalArgumentException(
                                        "DashScope API Key 未配置。请设置 agentscope.core.dashscope.api-key 或"
                                                + " agentscope.core.api-key 或环境变量 DASHSCOPE_API_KEY");
                        }
                        GenerateOptions options = ctx.component(GenerateOptions.class) != null
                                        ? ctx.component(GenerateOptions.class) : buildOptions(gen, null);
                        return DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName(modelName)
                                        .stream(streamOf(ctx))
                                        .defaultOptions(options)
                                        .build();
                });

                // Anthropic/Claude 工厂
                ModelRegistry.registerFactory("anthropic:.+", (modelId, ctx) -> {
                        String modelName = modelId.substring("anthropic:".length());
                        var cfg = coreProperties.getOpenai(); // Anthropic 暂无独立配置，复用全局
                        String apiKey = firstNonBlank(ctx.getApiKey(),
                                        cfg != null ? cfg.getApiKey() : null, coreProperties.getApiKey());
                        GenerateOptions options = ctx.component(GenerateOptions.class) != null
                                        ? ctx.component(GenerateOptions.class) : buildOptions(gen, null);
                        return AnthropicChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName(modelName)
                                        .baseUrl(ctx.getBaseUrl() != null ? ctx.getBaseUrl()
                                                        : (cfg != null ? cfg.getBaseUrl() : null))
                                        .stream(streamOf(ctx))
                                        .defaultOptions(options)
                                        .build();
                });
                // 简写 claude: 映射到 anthropic
                ModelRegistry.registerFactory("claude:.+", (modelId, ctx) -> {
                        String modelName = modelId.substring("claude:".length());
                        var cfg = coreProperties.getOpenai();
                        String apiKey = firstNonBlank(ctx.getApiKey(),
                                        cfg != null ? cfg.getApiKey() : null, coreProperties.getApiKey());
                        GenerateOptions options = ctx.component(GenerateOptions.class) != null
                                        ? ctx.component(GenerateOptions.class) : buildOptions(gen, null);
                        return AnthropicChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName(modelName)
                                        .baseUrl(ctx.getBaseUrl() != null ? ctx.getBaseUrl()
                                                        : (cfg != null ? cfg.getBaseUrl() : null))
                                        .stream(streamOf(ctx))
                                        .defaultOptions(options)
                                        .build();
                });

                // DeepSeek 工厂（通过 OpenAI 兼容 + DeepSeekFormatter）
                ModelRegistry.registerFactory("deepseek:.+", (modelId, ctx) -> {
                        String modelName = modelId.substring("deepseek:".length());
                        var cfg = coreProperties.getOpenai();
                        String apiKey = firstNonBlank(ctx.getApiKey(),
                                        cfg != null ? cfg.getApiKey() : null, coreProperties.getApiKey());
                        String baseUrl = firstNonBlank(ctx.getBaseUrl(),
                                        cfg != null ? cfg.getBaseUrl() : null, "https://api.deepseek.com/v1");
                        GenerateOptions options = ctx.component(GenerateOptions.class) != null
                                        ? ctx.component(GenerateOptions.class) : buildOptions(gen, null);
                        return OpenAIChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName(modelName)
                                        .baseUrl(baseUrl)
                                        .stream(streamOf(ctx))
                                        .formatter(new DeepSeekFormatter(true))
                                        .generateOptions(options)
                                        .build();
                });

                // 百度千帆工厂（框架未内置，使用自定义 BaiduModelProvider）
                // 与上方内置 provider 完全一致的 ContextModelFactory 模式：按 Agent 覆盖 apiKey/options
                ModelRegistry.registerFactory("baidu:.+", (modelId, ctx) -> {
                        String modelName = modelId.substring("baidu:".length());
                        String apiKey = firstNonBlank(ctx.getApiKey(), coreProperties.getApiKey());
                        GenerateOptions options = ctx.component(GenerateOptions.class) != null
                                        ? ctx.component(GenerateOptions.class) : buildOptions(gen, null);
                        // 百度千帆以同一凭据同时作为 client_id / client_secret 获取 access_token
                        return new BaiduModelProvider(apiKey, apiKey, modelName, options);
                });

                // 华为盘古工厂（框架未内置，使用自定义 HuaweiModelProvider）
                ModelRegistry.registerFactory("huawei:.+", (modelId, ctx) -> {
                        String modelName = modelId.substring("huawei:".length());
                        String apiKey = firstNonBlank(ctx.getApiKey(), coreProperties.getApiKey());
                        GenerateOptions options = ctx.component(GenerateOptions.class) != null
                                        ? ctx.component(GenerateOptions.class) : buildOptions(gen, null);
                        return new HuaweiModelProvider(apiKey, apiKey, modelName, options);
                });

                log.info("ModelFactory: 已注册 openai/dashscope/anthropic/claude/deepseek/baidu/huawei"
                                + " 工厂到 ModelRegistry（支持通过 ModelCreationContext 透传按 Agent 覆盖配置）");
        }

        /**
         * 根据配置创建 Model 实例。
         *
         * <p>
         * 通过 {@code ModelRegistry.resolve("provider:modelName", context)} 解析模型标识。
         * 将 {@link AgentModelConfig} 中的 {@code apiKey}/{@code baseUrl}/{@code stream}/
         * 生成选项封装为 {@link ModelCreationContext} 透传：
         * <ul>
         * <li>config 为 null 或未填写覆盖字段 → 空 context → 回退到全局/环境变量（单租户）；</li>
         * <li>config 填写了覆盖字段 → context 携带它们 → 各提供商/工厂按 Agent 生效（多租户）。</li>
         * </ul>
         * baidu/huawei 框架未内置，保留自定义实现（自身已支持按 Agent apiKey）。
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

                // baidu/huawei 框架未内置，但已在 init() 注册为 ModelRegistry 工厂，
                // 与 openai/dashscope 等内置 provider 走完全一致的 ModelRegistry.resolve 路径
                if ("baidu".equals(provider) || "huawei".equals(provider)) {
                        ModelCreationContext ctx = buildContext(config);
                        log.info("创建 Model (ModelRegistry): provider={}, model={}, tenantContext={}",
                                        provider, modelName, !ctx.isEmpty());
                        return ModelRegistry.resolve(modelId, ctx);
                }

                // 其余 provider 通过 ModelRegistry + ModelCreationContext 创建（含 gemini/ollama 的 SPI）
                ModelCreationContext ctx = buildContext(config);
                log.info("创建 Model (ModelRegistry): provider={}, model={}, tenantContext={}",
                                provider, modelName, !ctx.isEmpty());
                return ModelRegistry.resolve(modelId, ctx);
        }

        /**
         * 构建生成选项。
         *
         * <p>
         * 合并策略：配置中的参数优先，未指定的参数使用全局默认值。
         * </p>
         *
         * @param config 模型配置，为 null 时全部使用全局默认值
         * @return 构建完成的 GenerateOptions 实例
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

        /**
         * 基于全局生成配置构建 GenerateOptions。
         *
         * @param gen     全局 GenerationConfig（提供温度、最大 Token、topP、缓存控制）
         * @param config 模型配置（当前未参与默认选项构建，预留扩展）
         * @return 构建完成的 GenerateOptions 实例
         */
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

        /**
         * 判断模型配置是否包含自定义生成参数。
         *
         * <p>当温度、最大 Token、topP、缓存控制中任一字段非空时，视为需要透传专属 GenerateOptions。</p>
         *
         * @param config 模型配置
         * @return true 表示存在自定义生成选项
         */
        private boolean hasCustomOptions(AgentModelConfig config) {
            return config.getTemperature() != null
                                || config.getMaxTokens() != null
                                || config.getTopP() != null
                                || config.getCacheControl() != null;
        }

        /**
         * 将 {@link AgentModelConfig} 转换为 {@link ModelCreationContext}。
         *
         * <p>仅当对应字段被显式填写时才放入 context（空字符串会被忽略），否则返回空 context，
         * 使工厂/SPI 回退到全局配置或环境变量（单租户）。</p>
         *
         * @param config 模型配置，可能为 null
         * @return 非空则代表需要按 Agent 覆盖的创建上下文
         */
        private ModelCreationContext buildContext(AgentModelConfig config) {
                if (config == null) {
                        return ModelCreationContext.empty();
                }
                var builder = ModelCreationContext.builder();
                if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                        builder.apiKey(config.getApiKey());
                }
                if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
                        builder.baseUrl(config.getBaseUrl());
                }
                if (config.getStream() != null) {
                        builder.stream(config.getStream());
                }
                if (hasCustomOptions(config)) {
                        builder.component(GenerateOptions.class, buildGenerateOptions(config));
                }
                return builder.build();
        }

        /** 返回首个非 null 且非空白的字符串，全部为空时返回 null。 */
        private static String firstNonBlank(String... values) {
                for (String v : values) {
                        if (v != null && !v.isBlank()) {
                                return v;
                        }
                }
                return null;
        }

        /** 从 context 读取流式开关，未指定时默认 true。 */
        private static boolean streamOf(ModelCreationContext ctx) {
                return ctx.getStream() != null ? ctx.getStream() : true;
        }
}
