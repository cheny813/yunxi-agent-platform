package io.yunxi.platform.framework.embedding;

import java.util.List;

/**
 * 嵌入向量 Provider 接口
 *
 * <p>
 * 支持多种嵌入模型提供商：
 * <ul>
 * <li>阿里云 DashScope (text-embedding-v3)</li>
 * <li>OpenAI (text-embedding-ada-002)</li>
 * <li>其他兼容 OpenAI API 的服务</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface EmbeddingProvider {

    /**
     * 获取 Provider 名称
     *
     * @return provider 名称
     */
    String getName();

    /**
     * 获取支持的向量维度
     *
     * @return 向量维度
     */
    int getDimension();

    /**
     * 将文本转换为向量
     *
     * @param text 输入文本
     * @return 向量列表（浮点数）
     */
    List<Float> embed(String text);

    /**
     * 批量将文本转换为向量
     * <p>
     * 注意：具体批量限制取决于各 Provider 实现（通常是 API 限制）。
     * 调用方应使用 {@link #getBatchSize()} 获取推荐的分批大小。
     * </p>
     *
     * @param texts 输入文本列表
     * @return 向量列表
     */
    default List<List<Float>> embedBatch(List<String> texts) {
        return texts.stream()
                .map(this::embed)
                .toList();
    }

    /**
     * 获取批量处理大小
     * <p>
     * 用于控制分批调用 {@link #embedBatch(List)} 的大小。
     * 具体限制取决于各 Provider 实现（通常是 API 限制）。
     * </p>
     *
     * @return 推荐的批量大小
     */
    default int getBatchSize() {
        return 10;
    }
}
