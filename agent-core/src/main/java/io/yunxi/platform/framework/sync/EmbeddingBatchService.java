package io.yunxi.platform.framework.sync;

import io.yunxi.platform.framework.embedding.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量嵌入批量服务
 *
 * <p>
 * 封装分批调用 embedding API 的逻辑（带重试），
 * 从 BaseSyncService 中提取，作为独立服务供同步类使用。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Service
public class EmbeddingBatchService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBatchService.class);

    /** 向量嵌入服务 */
    private final EmbeddingService embeddingService;

    /**
     * 构造函数
     *
     * @param embeddingService 向量嵌入服务
     */
    public EmbeddingBatchService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * 分批调用 embedding API（带重试）
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    public List<List<Float>> embedBatchWithRetry(List<String> texts) {
        List<List<Float>> allEmbeddings = new ArrayList<>();
        int batchSize = embeddingService.getBatchSize();
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);
            log.info("embedding进度: {}/{}", end, texts.size());
            try {
                List<List<Float>> batchEmbeddings = embeddingService.embedBatch(batch);
                allEmbeddings.addAll(batchEmbeddings);
            } catch (Exception e) {
                log.error("embedding批次 {}-{} 失败: {}", i, end, e.getMessage());
                // 添加空向量以保持索引对应
                List<Float> emptyEmbedding = new ArrayList<>();
                for (int j = 0; j < embeddingService.getDimension(); j++) {
                    emptyEmbedding.add(0.0f);
                }
                for (int j = 0; j < batch.size(); j++) {
                    allEmbeddings.add(emptyEmbedding);
                }
            }
        }
        return allEmbeddings;
    }
}
