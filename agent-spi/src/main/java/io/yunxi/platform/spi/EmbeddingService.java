package io.yunxi.platform.spi;

import java.util.List;

/**
 * 文本嵌入向量服务接口（产品平面统一契约）。
 *
 * <p>为依赖 agent-spi 的下层模块（如 agent-text2sql）提供文本向量化能力抽象，
 * 由 agent-core 的 {@code io.yunxi.platform.embedding.EmbeddingService} 实现。
 * 单一接口 + 单一实现，避免与具体实现类同名的重复抽象。</p>
 */
public interface EmbeddingService {

    /**
     * 生成单条文本的嵌入向量
     *
     * @param text 输入文本
     * @return 嵌入向量
     */
    List<Float> embed(String text);

    /**
     * 批量生成文本的嵌入向量
     *
     * @param texts 输入文本列表
     * @return 嵌入向量列表
     */
    List<List<Float>> embedBatch(List<String> texts);

    /**
     * 获取向量维度
     *
     * @return 向量维度
     */
    int getDimension();

    /**
     * 获取当前默认提供者的名称
     *
     * @return 提供者名称
     */
    String getProviderName();
}
