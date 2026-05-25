package io.yunxi.platform.framework.knowledge;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.framework.embedding.EmbeddingService;
import io.yunxi.platform.infra.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;
import io.yunxi.platform.shared.constants.ConfigDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SimpleKnowledge 本地知识库创建器
 *
 * <p>
 * 根据配置创建内存级别的 {@code SimpleKnowledge} 实例，适合开发和测试场景。
 * 复用项目已有的 {@link EmbeddingService} 作为嵌入模型，通过 {@link AgentscopeEmbeddingModelAdapter} 桥接。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class SimpleKnowledgeCreator implements KnowledgeCreator {

    private final EmbeddingService embeddingService;

    public SimpleKnowledgeCreator(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    public String getType() {
        return "simple";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public Knowledge create(KnowledgeBaseConfig config) {
        try {
            Class<?> simpleKnowledgeClass = Class.forName("io.agentscope.core.rag.impl.SimpleKnowledge");
            Class<?> inMemoryStoreClass = Class.forName("io.agentscope.core.rag.store.InMemoryStore");

            // 向量维度
            int dimension = config.getDimension() != null
                    ? config.getDimension()
                    : embeddingService.getDimension();
            if (dimension <= 0) {
                dimension = ConfigDefaults.DEFAULT_EMBEDDING_DIMENSION;
            }

            // 创建 InMemoryStore
            Object storeBuilder = inMemoryStoreClass.getMethod("builder").invoke(null);
            storeBuilder = inMemoryStoreClass.getMethod("dimensions", int.class)
                    .invoke(storeBuilder, dimension);
            Object store = inMemoryStoreClass.getMethod("build").invoke(storeBuilder);

            // 创建 EmbeddingModel 适配器
            AgentscopeEmbeddingModelAdapter adapter = new AgentscopeEmbeddingModelAdapter(embeddingService);
            Object embeddingModel = adapter.getAdaptedInstance();

            if (embeddingModel == null) {
                throw new IllegalStateException("EmbeddingModel 不可用，请检查 agentscope-core 依赖");
            }

            // 创建 SimpleKnowledge
            Object builder = simpleKnowledgeClass.getMethod("builder").invoke(null);
            builder = simpleKnowledgeClass.getMethod("embeddingModel",
                    Class.forName("io.agentscope.core.rag.embedding.EmbeddingModel"))
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