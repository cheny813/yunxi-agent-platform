package io.yunxi.platform.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HayStack 知识库创建器。
 *
 * <p>
 * 根据配置创建 {@code HayStackKnowledge} 实例。
 * HayStack 是一个深度学习 RAG 框架，支持灵活的管道配置。
 * </p>
 *
 * <p>
 * 注意：HayStack 扩展包 {@code agentscope-extensions-rag-haystack} 可能未在所有环境中可用。
 * 如果 SDK 类不存在，将记录警告并抛出异常，由
 * {@link io.yunxi.platform.infra.config.KnowledgeAutoConfiguration} 统一处理。
 * </p>
 *
 * <p>
 * TODO: AgentScope 2.0 新 RAG 模块上线后迁移到新 API。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
@SuppressWarnings("removal")
public class HayStackKnowledgeCreator implements KnowledgeCreator {

    /**
     * 获取知识库类型标识。
     *
     * @return "haystack"
     */
    @Override
    public String getType() {
        return "haystack";
    }

    /**
     * 创建 HayStackKnowledge 实例。
     *
     * <p>
     * 创建流程：
     * 1. 通过反射加载 HayStackKnowledge 类
     * 2. 尝试 Builder 模式创建（优先）
     * 3. 如果 Builder 不可用，尝试 create 静态方法
     * 4. 如果 HayStackConfig 可用，构建配置对象并注入
     * </p>
     *
     * @param config 知识库配置
     * @return HayStackKnowledge 实例
     * @throws IllegalArgumentException 扩展包未安装或配置不合法时抛出
     */
    @Override
    public Knowledge create(KnowledgeBaseConfig config) {
        try {
            // 反射加载 SDK 类
            Class<?> knowledgeClass = Class.forName("io.agentscope.core.rag.impl.HayStackKnowledge");

            // 尝试 Builder 模式创建
            Object builder;
            try {
                builder = knowledgeClass.getMethod("builder").invoke(null);
            } catch (NoSuchMethodException e) {
                // 一些版本可能使用 create 静态方法而非 Builder
                builder = knowledgeClass.getMethod("create", KnowledgeBaseConfig.class)
                        .invoke(null, config);
                Knowledge knowledge = (Knowledge) builder;
                log.debug("已创建 HayStackKnowledge（通过 create 方法）");
                return knowledge;
            }

            // 使用 Builder 模式，尝试加载 HayStackConfig
            try {
                Class<?> haystackConfigClass = Class.forName("io.agentscope.core.rag.impl.HayStackConfig");
                Object haystackConfig = haystackConfigClass.getMethod("builder").invoke(null);

                // 设置端点地址
                if (config.getApiUrl() != null && !config.getApiUrl().isBlank()) {
                    try {
                        haystackConfig = haystackConfigClass.getMethod("url", String.class)
                                .invoke(haystackConfig, config.getApiUrl());
                    } catch (NoSuchMethodException e) {
                        log.debug("HayStackConfig 无 url 方法");
                    }
                }

                // 设置 API Key
                if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                    try {
                        haystackConfig = haystackConfigClass.getMethod("apiKey", String.class)
                                .invoke(haystackConfig, config.getApiKey());
                    } catch (NoSuchMethodException e) {
                        log.debug("HayStackConfig 无 apiKey 方法");
                    }
                }

                Object builtConfig = haystackConfigClass.getMethod("build").invoke(haystackConfig);
                builder = knowledgeClass.getMethod("config", haystackConfigClass)
                        .invoke(builder, builtConfig);
            } catch (ClassNotFoundException e) {
                // HayStackConfig 类不存在，尝试不带 config 参数创建
                log.debug("HayStackConfig 类不存在，尝试不带 config 参数创建");
            }

            Knowledge knowledge = (Knowledge) knowledgeClass.getMethod("build").invoke(builder);
            log.debug("已创建 HayStackKnowledge");
            return knowledge;

        } catch (ClassNotFoundException e) {
            log.warn("HayStackKnowledge 类不存在，请检查 agentscope-extensions-rag-haystack 依赖: {}", e.getMessage());
            throw new IllegalArgumentException("HayStack 扩展包未安装: agentscope-extensions-rag-haystack", e);
        } catch (Exception e) {
            log.error("创建 HayStackKnowledge 失败: {}", e.getMessage());
            throw new IllegalArgumentException("创建 HayStackKnowledge 失败: " + e.getMessage(), e);
        }
    }
}
