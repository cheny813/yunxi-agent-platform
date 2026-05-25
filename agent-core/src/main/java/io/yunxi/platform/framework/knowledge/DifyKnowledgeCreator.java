package io.yunxi.platform.framework.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.infra.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Dify 知识库创建器
 *
 * <p>
 * 根据配置创建 {@code DifyKnowledge} 实例。
 * 支持云服务和自托管 Dify，提供 keyword、semantic、hybrid、fulltext 四种检索模式。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class DifyKnowledgeCreator implements KnowledgeCreator {

    @Override
    public String getType() {
        return "dify";
    }

    @Override
    public Knowledge create(KnowledgeBaseConfig config) {
        try {
            Class<?> configClass = Class.forName("io.agentscope.core.rag.impl.DifyRAGConfig");
            Class<?> knowledgeClass = Class.forName("io.agentscope.core.rag.impl.DifyKnowledge");
            Class<?> retrievalModeClass = Class.forName("io.agentscope.core.rag.impl.RetrievalMode");

            Object difyConfig = configClass.getMethod("builder").invoke(null);

            // 必填参数
            difyConfig = configClass.getMethod("apiKey", String.class)
                    .invoke(difyConfig, config.getApiKey());
            difyConfig = configClass.getMethod("datasetId", String.class)
                    .invoke(difyConfig, config.getDatasetId());

            // 可选：API 地址
            if (config.getApiUrl() != null && !config.getApiUrl().isBlank()) {
                // DifyRAGConfig 可能使用 apiUrl 或 baseUrl 字段名
                try {
                    difyConfig = configClass.getMethod("apiUrl", String.class)
                            .invoke(difyConfig, config.getApiUrl());
                } catch (NoSuchMethodException e) {
                    log.debug("DifyRAGConfig 无 apiUrl 方法，尝试 baseUrl");
                    difyConfig = configClass.getMethod("baseUrl", String.class)
                            .invoke(difyConfig, config.getApiUrl());
                }
            }

            // 可选：检索模式
            if (config.getRetrievalMode() != null && !config.getRetrievalMode().isBlank()) {
                try {
                    Object mode = retrievalModeClass.getMethod("valueOf", String.class)
                            .invoke(null, config.getRetrievalMode().toUpperCase());
                    difyConfig = configClass.getMethod("retrievalMode", retrievalModeClass)
                            .invoke(difyConfig, mode);
                } catch (Exception e) {
                    log.warn("无效的 Dify 检索模式: {}，使用默认值", config.getRetrievalMode());
                }
            }

            // 可选参数
            if (config.getTopK() != null) {
                difyConfig = configClass.getMethod("topK", int.class)
                        .invoke(difyConfig, config.getTopK());
            }
            if (config.getScoreThreshold() != null) {
                difyConfig = configClass.getMethod("scoreThreshold", double.class)
                        .invoke(difyConfig, config.getScoreThreshold());
            }

            Object builtConfig = configClass.getMethod("build").invoke(difyConfig);

            Object builder = knowledgeClass.getMethod("builder").invoke(null);
            builder = knowledgeClass.getMethod("config", configClass)
                    .invoke(builder, builtConfig);
            Knowledge knowledge = (Knowledge) knowledgeClass.getMethod("build").invoke(builder);

            log.debug("已创建 DifyKnowledge: datasetId={}", config.getDatasetId());
            return knowledge;

        } catch (Exception e) {
            log.error("创建 DifyKnowledge 失败: {}", e.getMessage());
            throw new IllegalArgumentException("创建 DifyKnowledge 失败: " + e.getMessage(), e);
        }
    }
}