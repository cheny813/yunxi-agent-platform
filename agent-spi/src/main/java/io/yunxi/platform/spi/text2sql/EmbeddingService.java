package io.yunxi.platform.spi.text2sql;

import java.util.List;

/**
 * 嵌入服务 SPI 接口
 *
 * <p>为 agent-text2sql 模块提供文本嵌入向量生成能力的抽象接口。
 * 由 agent-core 或其他模块提供具体实现。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface EmbeddingService {

    /**
     * 生成单条文本的嵌入向量
     *
     * @param text 输入文本
     * @return 嵌入向量（浮点数组）
     */
    float[] embed(String text);

    /**
     * 批量生成文本的嵌入向量
     *
     * @param texts 输入文本列表
     * @return 嵌入向量列表
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * 检查嵌入服务是否可用
     *
     * @return true 如果服务已配置且可用
     */
    boolean isAvailable();
}
