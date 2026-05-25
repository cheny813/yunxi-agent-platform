package io.yunxi.platform.framework.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.infra.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * RAGFlow 知识库创建器
 *
 * <p>
 * 根据配置创建 {@code RAGFlowKnowledge} 实例。
 * 支持多数据集、文档过滤、元数据筛选和知识图谱检索。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class RAGFlowKnowledgeCreator implements KnowledgeCreator {

    @Override
    public String getType() {
        return "ragflow";
    }

    @Override
    public Knowledge create(KnowledgeBaseConfig config) {
        try {
            Class<?> configClass = Class.forName("io.agentscope.core.rag.impl.RAGFlowConfig");
            Class<?> knowledgeClass = Class.forName("io.agentscope.core.rag.impl.RAGFlowKnowledge");

            Object ragflowConfig = configClass.getMethod("builder").invoke(null);

            // 必填参数
            ragflowConfig = configClass.getMethod("apiKey", String.class)
                    .invoke(ragflowConfig, config.getApiKey());

            // baseUrl 或 apiUrl
            String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                    ? config.getBaseUrl()
                    : config.getApiUrl();
            if (baseUrl != null && !baseUrl.isBlank()) {
                ragflowConfig = configClass.getMethod("baseUrl", String.class)
                        .invoke(ragflowConfig, baseUrl);
            }

            // 支持逗号分隔的多个 datasetId
            if (config.getDatasetId() != null && !config.getDatasetId().isBlank()) {
                List<String> datasetIds = Arrays.asList(config.getDatasetId().split(","));
                for (String dsId : datasetIds) {
                    dsId = dsId.trim();
                    if (!dsId.isEmpty()) {
                        ragflowConfig = configClass.getMethod("addDatasetId", String.class)
                                .invoke(ragflowConfig, dsId);
                    }
                }
            }

            // 可选参数
            if (config.getTopK() != null) {
                ragflowConfig = configClass.getMethod("topK", int.class)
                        .invoke(ragflowConfig, config.getTopK());
            }
            if (config.getSimilarityThreshold() != null) {
                ragflowConfig = configClass.getMethod("similarityThreshold", double.class)
                        .invoke(ragflowConfig, config.getSimilarityThreshold());
            } else if (config.getScoreThreshold() != null) {
                ragflowConfig = configClass.getMethod("similarityThreshold", double.class)
                        .invoke(ragflowConfig, config.getScoreThreshold());
            }
            if (config.getVectorSimilarityWeight() != null) {
                ragflowConfig = configClass.getMethod("vectorSimilarityWeight", double.class)
                        .invoke(ragflowConfig, config.getVectorSimilarityWeight());
            }

            Object builtConfig = configClass.getMethod("build").invoke(ragflowConfig);

            Object builder = knowledgeClass.getMethod("builder").invoke(null);
            builder = knowledgeClass.getMethod("config", configClass)
                    .invoke(builder, builtConfig);
            Knowledge knowledge = (Knowledge) knowledgeClass.getMethod("build").invoke(builder);

            log.debug("已创建 RAGFlowKnowledge: baseUrl={}, datasetIds={}", baseUrl, config.getDatasetId());
            return knowledge;

        } catch (Exception e) {
            log.error("创建 RAGFlowKnowledge 失败: {}", e.getMessage());
            throw new IllegalArgumentException("创建 RAGFlowKnowledge 失败: " + e.getMessage(), e);
        }
    }
}