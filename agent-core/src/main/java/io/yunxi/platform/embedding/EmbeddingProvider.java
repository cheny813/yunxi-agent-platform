package io.yunxi.platform.embedding;

import java.util.List;

/**
 * Embedding 提供者接口
 * <p>
 * 定义统一的向量化接口，不同AI服务商可以有不同的实现
 * </p>
 *
 * @author yunxi-agent-platform
 */
public interface EmbeddingProvider {

    /**
     * 获取提供者名称
     *
     * @return 提供者名称
     */
    String getProviderName();

    /**
     * 向量化
     *
     * @param text 文本
     * @return 向量化结果
     */
    List<Float> embed(String text);

    /**
     * 批量向量化
     *
     * @param texts 文本列表
     * @return 向量化结果列表
     */
    List<List<Float>> embedBatch(List<String> texts);

    /**
     * 获取向量维度
     *
     * @return 向量维度
     */
    int getDimension();

    /**
     * 获取模型名称
     *
     * @return 模型名称
     */
    String getModelName();
}
