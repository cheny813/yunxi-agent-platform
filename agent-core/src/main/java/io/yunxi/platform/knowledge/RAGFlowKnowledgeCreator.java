package io.yunxi.platform.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * RAGFlow 知识库创建器。
 *
 * <p>
 * 根据配置创建 {@code RAGFlowKnowledge} 实例。
 * 支持多数据集、文档过滤、元数据筛选和知识图谱检索。
 * </p>
 *
 * <p>
 * 必要配置：
 * - apiKey：RAGFlow API Key
 * 可选配置：
 * - baseUrl / apiUrl：RAGFlow API 地址
 * - datasetId：数据集 ID（支持逗号分隔的多个 ID）
 * - topK：返回结果数量
 * - similarityThreshold / scoreThreshold：相似度阈值
 * - vectorSimilarityWeight：向量相似度权重
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
public class RAGFlowKnowledgeCreator implements KnowledgeCreator {

    /**
     * 获取知识库类型标识。
     *
     * @return "ragflow"
     */
    @Override
    public String getType() {
        return "ragflow";
    }

    /**
     * 创建 RAGFlowKnowledge 实例。
     *
     * <p>
     * 创建流程：
     * 1. 通过反射加载 RAGFlowConfig 和 RAGFlowKnowledge 类
     * 2. 构建 RAGFlowConfig（apiKey、baseUrl、datasetIds、topK、threshold 等）
     * 3. 支持逗号分隔的多个 datasetId
     * 4. 构建 RAGFlowKnowledge 实例
     * </p>
     *
     * @param config 知识库配置
     * @return RAGFlowKnowledge 实例
     * @throws IllegalArgumentException 配置参数不合法时抛出
     */
    @Override
    public Knowledge create(KnowledgeBaseConfig config) {
        try {
            // 反射加载 SDK 类
            Class<?> configClass = Class.forName("io.agentscope.core.rag.impl.RAGFlowConfig");
            Class<?> knowledgeClass = Class.forName("io.agentscope.core.rag.impl.RAGFlowKnowledge");

            Object ragflowConfig = configClass.getMethod("builder").invoke(null);

            // 必填参数：API Key
            ragflowConfig = configClass.getMethod("apiKey", String.class)
                    .invoke(ragflowConfig, config.getApiKey());

            // 可选：Base URL（优先使用 baseUrl，其次使用 apiUrl）
            String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                    ? config.getBaseUrl()
                    : config.getApiUrl();
            if (baseUrl != null && !baseUrl.isBlank()) {
                ragflowConfig = configClass.getMethod("baseUrl", String.class)
                        .invoke(ragflowConfig, baseUrl);
            }

            // 可选：数据集 ID（支持逗号分隔的多个 ID）
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

            // 可选参数：topK
            if (config.getTopK() != null) {
                ragflowConfig = configClass.getMethod("topK", int.class)
                        .invoke(ragflowConfig, config.getTopK());
            }

            // 可选参数：相似度阈值（优先使用 similarityThreshold，其次使用 scoreThreshold）
            if (config.getSimilarityThreshold() != null) {
                ragflowConfig = configClass.getMethod("similarityThreshold", double.class)
                        .invoke(ragflowConfig, config.getSimilarityThreshold());
            } else if (config.getScoreThreshold() != null) {
                ragflowConfig = configClass.getMethod("similarityThreshold", double.class)
                        .invoke(ragflowConfig, config.getScoreThreshold());
            }

            // 可选参数：向量相似度权重
            if (config.getVectorSimilarityWeight() != null) {
                ragflowConfig = configClass.getMethod("vectorSimilarityWeight", double.class)
                        .invoke(ragflowConfig, config.getVectorSimilarityWeight());
            }

            Object builtConfig = configClass.getMethod("build").invoke(ragflowConfig);

            // 构建 RAGFlowKnowledge
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
