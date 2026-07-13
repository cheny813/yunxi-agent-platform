package io.yunxi.platform.integration;

import io.milvus.v2.client.MilvusClientV2;
import io.yunxi.platform.spi.text2sql.EmbeddingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.function.Supplier;

/**
 * 集成测试专用桩配置。
 *
 * <p>生产代码 {@code ColumnRetriever}（agent-text2sql 模块）的构造函数需要两个 Bean：
 * <ul>
 *   <li>{@code Supplier<io.yunxi.platform.spi.text2sql.EmbeddingService>}</li>
 *   <li>{@code Supplier<io.milvus.v2.client.MilvusClientV2>}</li>
 * </ul>
 * 而 Spring 不会把普通 Bean 自动包成 {@code java.util.function.Supplier}；且当前代码库中没有
 * 实现 SPI 接口 {@code io.yunxi.platform.spi.text2sql.EmbeddingService} 的 Bean（
 * {@code io.yunxi.platform.embedding.EmbeddingService} 是同名但无关的类型）。因此在此显式
 * 提供这两个 Supplier 桩 Bean，使包含 text2sql 模块的整个应用上下文能够正常装载。这些桩不会被
 * 集成测试用例实际调用，仅用于满足依赖注入。</p>
 *
 * <p>注意：这里不要定义 {@code MilvusClientV2} 类型的 Bean，否则会与
 * {@code MilvusClientConfig#milvusClient}（在 milvus.enabled=true 时创建）产生
 * NoUniqueBeanDefinitionException。</p>
 */
@Configuration
public class IntegrationTestConfig {

    /** 提供 EmbeddingService（SPI）的 Supplier 桩（空实现，本地不启用向量嵌入）。 */
    @Bean
    public Supplier<EmbeddingService> embeddingServiceSupplier() {
        return () -> new StubEmbeddingService();
    }

    /** 提供 MilvusClientV2 的 Supplier 桩（引用框架创建的 milvusClient Bean，连接失败时可能为 null）。 */
    @Bean
    public Supplier<MilvusClientV2> milvusClientSupplier(MilvusClientV2 milvusClient) {
        return () -> milvusClient;
    }

    /**
     * SPI 接口 {@link EmbeddingService} 的空实现。
     * 仅用于满足 {@code ColumnRetriever} 的依赖注入，不参与任何实际向量计算。
     */
    static class StubEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            return new float[0];
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return List.of();
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    }
}
