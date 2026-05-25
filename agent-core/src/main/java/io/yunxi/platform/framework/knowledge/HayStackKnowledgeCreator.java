package io.yunxi.platform.framework.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.infra.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HayStack 知识库创建器
 *
 * <p>
 * 根据配置创建 {@code HayStackKnowledge} 实例。
 * HayStack 是一个深度学习 RAG 框架，支持灵活的管道配置。
 * </p>
 *
 * <p>
 * 注意：HayStack 扩展包 {@code agentscope-extensions-rag-haystack} 可能未在所有环境中可用。
 * 如果 SDK 类不存在，将记录警告并抛出异常，由 {@link KnowledgeAutoConfiguration} 统一处理。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class HayStackKnowledgeCreator implements KnowledgeCreator {

    @Override
    public String getType() {
        return "haystack";
    }

    @Override
    public Knowledge create(KnowledgeBaseConfig config) {
        try {
            // HayStack Knowledge 类
            Class<?> knowledgeClass = Class.forName("io.agentscope.core.rag.impl.HayStackKnowledge");

            // 尝试查找构建器方法
            Object builder;
            try {
                builder = knowledgeClass.getMethod("builder").invoke(null);
            } catch (NoSuchMethodException e) {
                // 一些版本可能使用 create 静态方法
                builder = knowledgeClass.getMethod("create", KnowledgeBaseConfig.class)
                        .invoke(null, config);
                Knowledge knowledge = (Knowledge) builder;
                log.debug("已创建 HayStackKnowledge（通过 create 方法）");
                return knowledge;
            }

            // 使用 Builder 模式（如果存在）
            // 查找 config 方法
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