package io.yunxi.platform.integration;

import io.milvus.v2.client.MilvusClientV2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Supplier;

/**
 * 集成测试专用桩配置。
 *
 * <p>生产代码 {@code ColumnRetriever}（agent-text2sql 模块）的构造函数需要 {@code Supplier<MilvusClientV2>}
 * 以便可选注入 Milvus 客户端；而 Spring 不会把普通 Bean 自动包成 {@code java.util.function.Supplier}，
 * 因此在此显式提供该 Supplier 桩 Bean，使包含 text2sql 模块的整个应用上下文能够正常装载。该桩不会被
 * 集成测试用例实际调用，仅用于满足依赖注入。</p>
 *
 * <p>嵌入服务 {@code EmbeddingService}（agent-spi 中性接口，由 agent-core 的
 * {@code io.yunxi.platform.embedding.EmbeddingService} 实现）通过 {@code ObjectProvider} 注入
 * {@code ColumnRetriever}：集成测试上下文启用 agent-core 时由核心 Bean 直接满足，未启用时
 * {@code ObjectProvider#getIfAvailable()} 返回 null 并由 {@code ColumnRetriever} 跳过，无需此处提供桩。</p>
 *
 * <p>注意：这里不要定义 {@code MilvusClientV2} 类型的 Bean，否则会与
 * {@code MilvusClientConfig#milvusClient}（在 milvus.enabled=true 时创建）产生
 * NoUniqueBeanDefinitionException。</p>
 */
@Configuration
public class IntegrationTestConfig {

    /** 提供 MilvusClientV2 的 Supplier 桩（引用框架创建的 milvusClient Bean，连接失败时可能为 null）。 */
    @Bean
    public Supplier<MilvusClientV2> milvusClientSupplier(MilvusClientV2 milvusClient) {
        return () -> milvusClient;
    }
}
