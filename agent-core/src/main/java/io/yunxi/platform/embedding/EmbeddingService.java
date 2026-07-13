package io.yunxi.platform.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 服务
 * <p>
 * 提供统一的向量化接口，自动路由到配置的提供者
 * 支持多提供者配置和缓存
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** 默认提供者名称 */
    private static final String DEFAULT_PROVIDER = "openai";

    /** 注册的提供者 */
    private final Map<String, EmbeddingProvider> providers = new ConcurrentHashMap<>();

    /**
     * 注入并注册所有可用的 Embedding 提供者
     *
     * @param providerList 自动装配的提供者列表（可能为 null）
     */
    @Autowired(required = false)
    public void setProviders(List<EmbeddingProvider> providerList) {
        if (providerList != null) {
            for (EmbeddingProvider provider : providerList) {
                providers.put(provider.getProviderName(), provider);
                log.info("注册 Embedding 提供者: {}", provider.getProviderName());
            }
        }
        if (!providers.containsKey(DEFAULT_PROVIDER)) {
            log.warn("默认 Embedding 提供者 {} 未注册", DEFAULT_PROVIDER);
        }
    }

    /**
     * 获取提供者
     *
     * @param providerName 提供者名称
     * @return Embedding 提供者
     */
    public EmbeddingProvider getProvider(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return providers.get(DEFAULT_PROVIDER);
        }
        EmbeddingProvider provider = providers.get(providerName);
        if (provider == null) {
            log.warn("未找到 Embedding 提供者: {}, 使用默认提供者", providerName);
            return providers.get(DEFAULT_PROVIDER);
        }
        return provider;
    }

    /**
     * 获取当前默认提供者的名称
     *
     * @return 默认提供者名称，未注册时返回 "unknown"
     */
    public String getProviderName() {
        EmbeddingProvider provider = providers.get(DEFAULT_PROVIDER);
        return provider != null ? provider.getProviderName() : "unknown";
    }

    /**
     * 向量化
     *
     * @param text 文本
     * @return 向量化结果
     */
    public List<Float> embed(String text) {
        return embed(text, null);
    }

    /**
     * 向量化
     *
     * @param text         文本
     * @param providerName 提供者名称
     * @return 向量化结果
     */
    public List<Float> embed(String text, String providerName) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        EmbeddingProvider provider = getProvider(providerName);
        if (provider == null) {
            log.error("没有可用的 Embedding 提供者");
            return Collections.emptyList();
        }
        return provider.embed(text);
    }

    /**
     * 批量向量化
     *
     * @param texts 文本列表
     * @return 向量化结果列表
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        return embedBatch(texts, null);
    }

    /**
     * 批量向量化
     *
     * @param texts        文本列表
     * @param providerName 提供者名称
     * @return 向量化结果列表
     */
    public List<List<Float>> embedBatch(List<String> texts, String providerName) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        EmbeddingProvider provider = getProvider(providerName);
        if (provider == null) {
            log.error("没有可用的 Embedding 提供者");
            return Collections.emptyList();
        }
        return provider.embedBatch(texts);
    }

    /**
     * 获取向量维度
     *
     * @return 向量维度
     */
    public int getDimension() {
        return getDimension(null);
    }

    /**
     * 获取向量维度
     *
     * @param providerName 提供者名称
     * @return 向量维度
     */
    public int getDimension(String providerName) {
        EmbeddingProvider provider = getProvider(providerName);
        if (provider == null) {
            log.error("没有可用的 Embedding 提供者");
            return 0;
        }
        return provider.getDimension();
    }

    /**
     * 获取所有注册的提供者名称
     *
     * @return 提供者名称列表
     */
    public List<String> getAvailableProviders() {
        return new ArrayList<>(providers.keySet());
    }
}
