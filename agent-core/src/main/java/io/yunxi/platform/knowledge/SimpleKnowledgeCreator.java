package io.yunxi.platform.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.agent.YunxiEmbeddingModel;
import io.yunxi.platform.embedding.EmbeddingService;
import io.yunxi.platform.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;
import io.yunxi.platform.shared.constants.ConfigDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SimpleKnowledge 本地知识库创建器。
 *
 * <p>
 * 根据配置创建内存级别的 {@code SimpleKnowledge} 实例，适合开发和测试场景。
 * 复用项目已有的 {@link EmbeddingService} 作为嵌入模型，
 * 通过 V2.0 {@link YunxiEmbeddingModel} 桥接到 AgentScope SDK 接口。
 * </p>
 *
 * <p>
 * SimpleKnowledge 使用 InMemoryStore 存储向量，数据不持久化，
 * 应用重启后需要重新导入知识文件。
 * </p>
 *
 * <p>
 * TODO: AgentScope 2.0 新 RAG 模块上线后，需将反射调用从
 * {@code io.agentscope.core.rag.impl.SimpleKnowledge} 迁移到新 API。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
@SuppressWarnings("removal")
public class SimpleKnowledgeCreator implements KnowledgeCreator {

    /** 嵌入服务，用于将文本转换为向量 */
    private final EmbeddingService embeddingService;

    /**
     * 构造 SimpleKnowledge 创建器。
     *
     * @param embeddingService 嵌入服务
     */
    public SimpleKnowledgeCreator(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * 获取知识库类型标识。
     *
     * @return "simple"
     */
    @Override
    public String getType() {
        return "simple";
    }

    /**
     * SimpleKnowledge 默认启用，无需显式配置 enabled=true。
     *
     * @return true
     */
    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    /**
     * 创建 SimpleKnowledge 实例。
     *
     * <p>
     * 创建流程：
     * 1. 通过反射加载 SimpleKnowledge 和 InMemoryStore 类
     * 2. 确定向量维度（优先使用配置值，其次使用 EmbeddingService 的维度，最后使用默认值）
     * 3. 创建 InMemoryStore 实例
     * 4. 创建 YunxiEmbeddingModel 实例，将 EmbeddingService 桥接到 SDK EmbeddingModel 接口
     * 5. 构建 SimpleKnowledge 实例
     * </p>
     *
     * @param config 知识库配置
     * @return SimpleKnowledge 实例
     * @throws IllegalArgumentException 创建失败时抛出
     */
    @Override
    public Knowledge create(KnowledgeBaseConfig config) {
        try {
            // 反射加载 SDK 类，避免编译期硬依赖
            Class<?> simpleKnowledgeClass = Class.forName("io.agentscope.core.rag.impl.SimpleKnowledge");
            Class<?> inMemoryStoreClass = Class.forName("io.agentscope.core.rag.store.InMemoryStore");

            // 确定向量维度：配置值 > EmbeddingService 维度 > 默认值
            int dimension = config.getDimension() != null
                    ? config.getDimension()
                    : embeddingService.getDimension();
            if (dimension <= 0) {
                dimension = ConfigDefaults.DEFAULT_EMBEDDING_DIMENSION;
            }

            // 创建 InMemoryStore（内存向量存储）
            Object storeBuilder = inMemoryStoreClass.getMethod("builder").invoke(null);
            storeBuilder = inMemoryStoreClass.getMethod("dimensions", int.class)
                    .invoke(storeBuilder, dimension);
            Object store = inMemoryStoreClass.getMethod("build").invoke(storeBuilder);

            // 创建 YunxiEmbeddingModel，将 EmbeddingService 桥接到 AgentScope SDK 接口
            YunxiEmbeddingModel embeddingModel = new YunxiEmbeddingModel(embeddingService);

            // 构建 SimpleKnowledge
            Object builder = simpleKnowledgeClass.getMethod("builder").invoke(null);
            builder = simpleKnowledgeClass.getMethod("embeddingModel",
                    Class.forName("io.agentscope.core.embedding.EmbeddingModel"))
                    .invoke(builder, embeddingModel);
            builder = simpleKnowledgeClass.getMethod("embeddingStore",
                    Class.forName("io.agentscope.core.rag.store.EmbeddingStore"))
                    .invoke(builder, store);
            Knowledge knowledge = (Knowledge) simpleKnowledgeClass.getMethod("build").invoke(builder);

            log.debug("已创建 SimpleKnowledge: dimension={}, provider={}",
                    dimension, embeddingService.getProviderName());
            return knowledge;

        } catch (Exception e) {
            log.error("创建 SimpleKnowledge 失败: {}", e.getMessage());
            throw new IllegalArgumentException("创建 SimpleKnowledge 失败: " + e.getMessage(), e);
        }
    }
}
