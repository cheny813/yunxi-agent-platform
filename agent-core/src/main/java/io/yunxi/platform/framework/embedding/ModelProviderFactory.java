package io.yunxi.platform.framework.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型提供商工厂
 * <p>
 * 根据配置创建对应的模型提供商实例
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class ModelProviderFactory {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(ModelProviderFactory.class);

    /**
     * 根据配置创建模型提供商
     *
     * @param config 模型配置
     * @return 模型提供商实例
     * @throws IllegalArgumentException 如果不支持的提供商类型
     */
    public ChatModelProvider createProvider(ModelConfig config) {
        String provider = config.getProvider().toLowerCase();

        return switch (provider) {
            case "dashscope" -> {
                log.info("创建 DashScope 模型提供商: {}", config.getModelName());
                yield new DashScopeModelProvider(config);
            }
            case "baidu" -> {
                log.info("创建百度文心一言模型提供商: {}", config.getModelName());
                yield new BaiduModelProvider(config);
            }
            case "huawei" -> {
                log.info("创建华为盘古大模型提供商: {}", config.getModelName());
                yield new HuaweiModelProvider(config);
            }
            case "openai" -> {
                log.info("创建 OpenAI 模型提供商: {}", config.getModelName());
                yield new OpenAIModelProvider(config);
            }
            case "claude" -> {
                log.info("创建 Claude 模型提供商: {}", config.getModelName());
                yield new ClaudeModelProvider(config);
            }
            // 未来可以添加更多提供商
            // case "ollama" -> new OllamaModelProvider(config);
            default -> throw new IllegalArgumentException(
                    "不支持的模型提供商: " + provider + "。支持的提供商: dashscope, baidu, huawei, openai, claude, ollama");
        };
    }

    /**
     * 验证配置是否支持
     *
     * @param provider 提供商名称
     * @return true 如果支持
     */
    public boolean isSupported(String provider) {
        if (provider == null) {
            return false;
        }
        return switch (provider.toLowerCase()) {
            case "dashscope", "baidu", "huawei", "openai", "claude", "ollama" -> true;
            default -> false;
        };
    }
}
