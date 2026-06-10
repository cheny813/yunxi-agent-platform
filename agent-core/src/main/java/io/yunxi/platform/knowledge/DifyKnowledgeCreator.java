package io.yunxi.platform.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Dify 知识库创建器。
 *
 * <p>
 * 根据配置创建 {@code DifyKnowledge} 实例。
 * 支持云服务和自托管 Dify，提供 keyword、semantic、hybrid、fulltext 四种检索模式。
 * </p>
 *
 * <p>
 * 必要配置：
 * - apiKey：Dify API Key
 * - datasetId：Dify 数据集 ID
 * 可选配置：
 * - apiUrl：Dify API 地址（自托管时需要）
 * - retrievalMode：检索模式（keyword/semantic/hybrid/fulltext）
 * - topK：返回结果数量
 * - scoreThreshold：相似度阈值
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
public class DifyKnowledgeCreator implements KnowledgeCreator {

    /**
     * 获取知识库类型标识。
     *
     * @return "dify"
     */
    @Override
    public String getType() {
        return "dify";
    }

    /**
     * 创建 DifyKnowledge 实例。
     *
     * <p>
     * 创建流程：
     * 1. 通过反射加载 DifyRAGConfig、DifyKnowledge 和 RetrievalMode 类
     * 2. 构建
     * DifyRAGConfig（apiKey、datasetId、apiUrl、retrievalMode、topK、scoreThreshold）
     * 3. 构建 DifyKnowledge 实例
     * </p>
     *
     * @param config 知识库配置
     * @return DifyKnowledge 实例
     * @throws IllegalArgumentException 配置参数不合法时抛出
     */
    @Override
    public Knowledge create(KnowledgeBaseConfig config) {
        try {
            // 反射加载 SDK 类
            Class<?> configClass = Class.forName("io.agentscope.core.rag.impl.DifyRAGConfig");
            Class<?> knowledgeClass = Class.forName("io.agentscope.core.rag.impl.DifyKnowledge");
            Class<?> retrievalModeClass = Class.forName("io.agentscope.core.rag.impl.RetrievalMode");

            Object difyConfig = configClass.getMethod("builder").invoke(null);

            // 必填参数：API Key 和 Dataset ID
            difyConfig = configClass.getMethod("apiKey", String.class)
                    .invoke(difyConfig, config.getApiKey());
            difyConfig = configClass.getMethod("datasetId", String.class)
                    .invoke(difyConfig, config.getDatasetId());

            // 可选：API 地址（自托管 Dify 需要配置）
            if (config.getApiUrl() != null && !config.getApiUrl().isBlank()) {
                try {
                    difyConfig = configClass.getMethod("apiUrl", String.class)
                            .invoke(difyConfig, config.getApiUrl());
                } catch (NoSuchMethodException e) {
                    // 不同版本可能使用不同的字段名，尝试 baseUrl
                    log.debug("DifyRAGConfig 无 apiUrl 方法，尝试 baseUrl");
                    difyConfig = configClass.getMethod("baseUrl", String.class)
                            .invoke(difyConfig, config.getApiUrl());
                }
            }

            // 可选：检索模式（keyword/semantic/hybrid/fulltext）
            if (config.getRetrievalMode() != null && !config.getRetrievalMode().isBlank()) {
                try {
                    Object mode = retrievalModeClass.getMethod("valueOf", String.class)
                            .invoke(null, config.getRetrievalMode().toUpperCase());
                    difyConfig = configClass.getMethod("retrievalMode", retrievalModeClass)
                            .invoke(difyConfig, mode);
                } catch (Exception e) {
                    log.warn("无效的 Dify 检索模式 {}，使用默认值", config.getRetrievalMode());
                }
            }

            // 可选参数：topK 和 scoreThreshold
            if (config.getTopK() != null) {
                difyConfig = configClass.getMethod("topK", int.class)
                        .invoke(difyConfig, config.getTopK());
            }
            if (config.getScoreThreshold() != null) {
                difyConfig = configClass.getMethod("scoreThreshold", double.class)
                        .invoke(difyConfig, config.getScoreThreshold());
            }

            Object builtConfig = configClass.getMethod("build").invoke(difyConfig);

            // 构建 DifyKnowledge
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
