package io.yunxi.platform.framework.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文本嵌入向量服务
 *
 * <p>
 * 将文本转换为向量表示，用于语义搜索
 * 支持多种嵌入模型提供商，通过配置切换
 * </p>
 *
 * <p>
 * 配置说明：
 * <ul>
 * <li>embedding.provider: 使用的嵌入服务提供商 (dashscope / openai)</li>
 * <li>embedding.dashscope.*: DashScope 配置</li>
 * <li>embedding.openai.*: OpenAI 配置</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /**
     * 嵌入服务提供商
     * 可选值：dashscope, openai
     */
    @Value("${embedding.provider:dashscope}")
    private String provider;

    /** 默认向量维度 */
    @Value("${embedding.default-dimension:1024}")
    private int defaultDimension;

    /** 嵌入向量提供商映射，按 Spring Bean 名称注入 */
    @Autowired
    private Map<String, EmbeddingProvider> embeddingProviders;

    /** 当前使用的嵌入向量提供商 */
    private EmbeddingProvider currentProvider;

    // 缓存：避免重复计算相同文本的向量
    private final Map<String, List<Float>> embeddingCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 10000;

    /**
     * 初始化嵌入服务，选择配置指定的 Provider
     */
    @PostConstruct
    public void init() {
        // 选择指定的 Provider
        if (embeddingProviders != null && embeddingProviders.containsKey(provider)) {
            currentProvider = embeddingProviders.get(provider);
            log.info("使用嵌入向量 Provider: {} (维度: {})",
                    currentProvider.getName(), currentProvider.getDimension());
        } else {
            // 默认使用第一个可用的 Provider
            if (embeddingProviders != null && !embeddingProviders.isEmpty()) {
                currentProvider = embeddingProviders.values().iterator().next();
                log.warn("Provider '{}' 不存在，使用默认 Provider: {}",
                        provider, currentProvider.getName());
            } else {
                log.error("没有可用的嵌入向量 Provider!");
            }
        }
    }

    /**
     * 获取实际的向量维度
     *
     * @return 向量维度
     */
    public int getDimension() {
        return currentProvider != null ? currentProvider.getDimension() : defaultDimension;
    }

    /**
     * 获取当前 Provider 名称
     *
     * @return Provider 名称
     */
    public String getProviderName() {
        return currentProvider != null ? currentProvider.getName() : "Unknown";
    }

    /**
     * 获取当前 Provider 的批量大小限制
     *
     * @return 批量大小
     */
    public int getBatchSize() {
        return currentProvider != null ? currentProvider.getBatchSize() : 10;
    }

    /**
     * 将文本转换为向量
     *
     * @param text 输入文本
     * @return 向量列表（浮点数）
     */
    public List<Float> embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 清理文本
        text = text.trim();
        if (text.length() > 8000) {
            text = text.substring(0, 8000);
        }

        // 检查缓存
        String cacheKey = text.hashCode() + "_" + text.length() + "_" + getProviderName();
        if (embeddingCache.containsKey(cacheKey)) {
            return embeddingCache.get(cacheKey);
        }

        if (currentProvider == null) {
            log.error("嵌入向量 Provider 未初始化");
            return Collections.emptyList();
        }

        try {
            List<Float> embedding = currentProvider.embed(text);

            // 缓存结果
            if (embedding != null && !embedding.isEmpty()) {
                if (embeddingCache.size() < MAX_CACHE_SIZE) {
                    embeddingCache.put(cacheKey, embedding);
                }
            }

            return embedding;

        } catch (Exception e) {
            log.error("获取文本向量失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 批量向量化 - 使用Provider的批量API
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        // 过滤空文本并缓存检查
        List<String> validTexts = new ArrayList<>();
        List<Integer> validIndices = new ArrayList<>();
        List<List<Float>> cachedResults = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.trim().isEmpty()) {
                cachedResults.add(Collections.emptyList());
                continue;
            }

            text = text.trim();
            if (text.length() > 8000) {
                text = text.substring(0, 8000);
            }

            // 检查缓存
            String cacheKey = text.hashCode() + "_" + text.length() + "_" + getProviderName();
            if (embeddingCache.containsKey(cacheKey)) {
                cachedResults.add(embeddingCache.get(cacheKey));
            } else {
                validTexts.add(text);
                validIndices.add(i);
            }
        }

        // 批量调用Provider
        List<List<Float>> providerResults = new ArrayList<>();
        if (!validTexts.isEmpty()) {
            if (currentProvider == null) {
                log.error("嵌入向量 Provider 未初始化");
                return texts.stream().map(t -> Collections.<Float>emptyList()).toList();
            }

            try {
                // 批量调用API
                providerResults = currentProvider.embedBatch(validTexts);

                // 缓存结果
                int resultIdx = 0;
                for (int i = 0; i < validTexts.size(); i++) {
                    String text = validTexts.get(i);
                    List<Float> embedding = providerResults.get(i);
                    String cacheKey = text.hashCode() + "_" + text.length() + "_" + getProviderName();
                    if (embedding != null && !embedding.isEmpty() && embeddingCache.size() < MAX_CACHE_SIZE) {
                        embeddingCache.put(cacheKey, embedding);
                    }
                }
            } catch (Exception e) {
                log.error("批量获取文本向量失败: {}", e.getMessage());
                // 返回空向量
                for (int i = 0; i < validTexts.size(); i++) {
                    providerResults.add(Collections.emptyList());
                }
            }
        }

        // 合并结果
        List<List<Float>> finalResults = new ArrayList<>();
        int providerIdx = 0;
        int cacheIdx = 0;
        for (int i = 0; i < texts.size(); i++) {
            if (cachedResults.size() > i && !cachedResults.get(i).isEmpty()) {
                finalResults.add(cachedResults.get(i));
            } else if (providerIdx < providerResults.size()) {
                finalResults.add(providerResults.get(providerIdx++));
            } else {
                finalResults.add(Collections.emptyList());
            }
        }

        return finalResults;
    }

    /**
     * 计算两个向量的余弦相似度
     *
     * @param v1 向量1
     * @param v2 向量2
     * @return 相似度（-1 到 1）
     */
    public double cosineSimilarity(List<Float> v1, List<Float> v2) {
        if (v1 == null || v2 == null || v1.size() != v2.size()) {
            return 0;
        }

        double dotProduct = 0;
        double norm1 = 0;
        double norm2 = 0;

        for (int i = 0; i < v1.size(); i++) {
            dotProduct += v1.get(i) * v2.get(i);
            norm1 += v1.get(i) * v1.get(i);
            norm2 += v2.get(i) * v2.get(i);
        }

        if (norm1 == 0 || norm2 == 0) {
            return 0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        embeddingCache.clear();
        log.info("Embedding 缓存已清除");
    }

    /**
     * 获取所有可用的 Provider
     *
     * @return Provider 名称列表
     */
    public Set<String> getAvailableProviders() {
        return embeddingProviders != null ? embeddingProviders.keySet() : Collections.emptySet();
    }
}
