package io.yunxi.platform.agent.model;

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
 * 模型工厂，根据供应商创建对应的 Model 实例。
 *
 * <p>
 * 支持的供应商：
 * - openai：OpenAI GPT 系列（兼容所有 OpenAI API 格式的供应商）
 * - claude：Anthropic Claude 系列
 * - dashscope：阿里通义千问系列
 * - deepseek：DeepSeek 系列（使用 OpenAI 兼容格式 + DeepSeekFormatter）
 * - baidu：百度文心系列（通过 BaiduModelProvider 适配）
 * - huawei：华为盘古系列（通过 HuaweiModelProvider 适配）
 * </p>
 *
 * <p>
 * V2.0 升级：直接使用 agentscope 框架 Model 实现类，无需自定义适配。
 * </p>
 */
@Component
public class ModelFactory {

        /** 类级别日志记录器 */
        private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

        /** 核心配置属性，提供模型参数的默认值 */
        private final AgentscopeCoreProperties coreProperties;

        /**
         * 构造模型工厂。
         *
         * @param coreProperties 核心配置属性
         */
        public ModelFactory(AgentscopeCoreProperties coreProperties) {
                this.coreProperties = coreProperties;
        }

        /**
         * 根据配置创建 Model 实例。
         *
         * <p>
         * 创建流程：
         * 1. 解析供应商、API Key、模型名称、BaseURL（优先使用配置值，兜底使用全局默认值）
         * 2. 构建 GenerateOptions（temperature、maxTokens、topP、cacheControl）
         * 3. 根据供应商类型创建对应的 Model 实现类
         * </p>
         *
         * @param config 模型配置，为 null 时使用全局默认配置
         * @return Model 实例
         * @throws IllegalArgumentException 不支持的供应商时抛出
         */
        public Model create(AgentModelConfig config) {
                // 解析供应商，优先使用配置值，兜底使用全局默认值
                String provider = config != null && config.getProvider() != null
                                ? config.getProvider()
                                : coreProperties.getProvider();
                // 解析 API Key
                String apiKey = config != null && config.getApiKey() != null
                                ? config.getApiKey()
                                : coreProperties.getApiKey();
                // 解析模型名称
                String modelName = config != null && config.getModelName() != null
                                ? config.getModelName()
                                : coreProperties.getModelName();
                // 解析 BaseURL（仅部分供应商需要）
                String baseUrl = config != null ? config.getBaseUrl() : null;

                // 构建生成选项
                GenerateOptions options = buildGenerateOptions(config);

                log.info("创建 Model: provider={}, model={}", provider, modelName);

                // 根据供应商类型创建对应的 Model 实例
                return switch (provider.toLowerCase()) {
                        case "openai" -> OpenAIChatModel.builder()
                                        .apiKey(apiKey).modelName(modelName)
                                        .baseUrl(baseUrl != null ? baseUrl : "https://api.openai.com/v1")
                                        .stream(true).generateOptions(options).build();

                        case "claude" -> AnthropicChatModel.builder()
                                        .apiKey(apiKey).modelName(modelName).baseUrl(baseUrl)
                                        .stream(true).defaultOptions(options).build();

                        case "dashscope" -> DashScopeChatModel.builder()
                                        .apiKey(apiKey).modelName(modelName)
                                        .stream(true).defaultOptions(options).build();

                        case "deepseek" -> OpenAIChatModel.builder()
                                        .apiKey(apiKey).modelName(modelName)
                                        .baseUrl(baseUrl != null ? baseUrl : "https://api.deepseek.com/v1")
                                        .stream(true).formatter(new DeepSeekFormatter(true))
                                        .generateOptions(options).build();

                        // 百度和华为使用自定义 Provider 适配
                        case "baidu" -> new BaiduModelProvider(apiKey, apiKey, modelName, options);
                        case "huawei" -> new HuaweiModelProvider(apiKey, apiKey, modelName, options);

                        default -> throw new IllegalArgumentException("不支持的模型供应商: " + provider);
                };
        }

        /**
         * 构建生成选项。
         *
         * <p>
         * 合并策略：配置中的参数优先，未指定的参数使用全局默认值。
         * 支持的参数：
         * - temperature：生成温度，控制输出随机性
         * - maxTokens：最大生成 Token 数
         * - topP：核采样概率阈值
         * - cacheControl：是否启用缓存控制（Prompt Caching）
         * </p>
         *
         * @param config 模型配置，为 null 时使用全局默认值
         * @return GenerateOptions 实例
         */
        public GenerateOptions buildGenerateOptions(AgentModelConfig config) {
                var gen = coreProperties.getGeneration();
                GenerateOptions.Builder builder = GenerateOptions.builder();

                // 解析 temperature
                Double temperature = config != null && config.getTemperature() != null
                                ? config.getTemperature()
                                : gen.getTemperature();
                if (temperature != null)
                        builder.temperature(temperature);

                // 解析 maxTokens
                Integer maxTokens = config != null && config.getMaxTokens() != null
                                ? config.getMaxTokens()
                                : gen.getMaxTokens();
                if (maxTokens != null)
                        builder.maxTokens(maxTokens);

                // 解析 topP
                Double topP = config != null && config.getTopP() != null
                                ? config.getTopP()
                                : gen.getTopP();
                if (topP != null)
                        builder.topP(topP);

                // 解析 cacheControl（仅 Claude 支持 Prompt Caching）
                Boolean cacheControl = config != null && config.getCacheControl() != null
                                ? config.getCacheControl()
                                : gen.getCacheControl();
                if (Boolean.TRUE.equals(cacheControl))
                        builder.cacheControl(true);

                return builder.build();
        }
}
