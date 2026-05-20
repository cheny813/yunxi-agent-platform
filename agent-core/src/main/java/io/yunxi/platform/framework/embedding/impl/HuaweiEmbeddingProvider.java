package io.yunxi.platform.framework.embedding.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.framework.embedding.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 华为云嵌入向量 Provider
 *
 * <p>
 * 支持华为云的文本嵌入模型：
 * <ul>
 * <li>embedding-001 (1024维)</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component("huawei")
public class HuaweiEmbeddingProvider implements EmbeddingProvider {
    
    private static final Logger log = LoggerFactory.getLogger(HuaweiEmbeddingProvider.class);

    @Value("${embedding.huawei.api-key:}")
    private String apiKey;

    @Value("${embedding.huawei.model:embedding-001}")
    private String model;

    @Value("${embedding.huawei.dimension:1024}")
    private int dimension;

    @Value("${embedding.huawei.region:cn-north-4}")
    private String region;

    /**
     * 批量大小配置
     * <p>
     * 限制取决于使用的模型（华为云 API 限制）：
     * <ul>
     * <li>embedding-001: 最多 10 条/批</li>
     * </ul>
     * </p>
     */
    @Value("${embedding.huawei.batch-size:10}")
    private int batchSize;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "Huawei";
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public int getBatchSize() {
        return batchSize;
    }

    @Override
    public List<Float> embed(String text) {
        List<List<Float>> results = embedBatch(List.of(text));
        return results.isEmpty() ? generateMockEmbedding() : results.get(0);
    }

    /**
     * 批量嵌入
     * <p>
     * 直接将 texts 列表传递给华为云 API，内部的批量限制取决于具体模型：
     * <ul>
     * <li>embedding-001: 最多 10 条/批</li>
     * </ul>
     * 调用方需根据使用的模型控制批量大小，或使用 {@link #getBatchSize()} 获取配置值。
     * </p>
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("华为云 API Key 未配置，使用模拟向量");
            return texts.stream().map(t -> generateMockEmbedding()).toList();
        }

        try {
            // 华为云 NLP API - 文本嵌入
            String url = String.format(
                    "https://nlp.%s.myhuaweicloud.com/v1/%s/embeddings",
                    region, model);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("input", texts);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Api-Key", apiKey);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode embeddings = root.path("embeddings");

                if (embeddings.isArray() && embeddings.size() > 0) {
                    List<List<Float>> vectors = new ArrayList<>();
                    for (JsonNode emb : embeddings) {
                        JsonNode embedding = emb.path("embedding");
                        if (!embedding.isMissingNode()) {
                            List<Float> vector = new ArrayList<>();
                            for (JsonNode node : embedding) {
                                vector.add((float) node.asDouble());
                            }
                            vectors.add(vector);
                        }
                    }
                    if (!vectors.isEmpty()) {
                        return vectors;
                    }
                }

                // 检查错误
                if (root.has("error_code")) {
                    log.warn("华为云 Embedding API 错误: {}",
                            root.path("error_msg").asText());
                }
            }

            log.warn("华为云 Embedding API 响应异常: {}", response.getBody());
            return texts.stream().map(t -> generateMockEmbedding()).toList();

        } catch (Exception e) {
            log.error("调用华为云 Embedding API 失败: {}", e.getMessage());
            return texts.stream().map(t -> generateMockEmbedding()).toList();
        }
    }

    /**
     * 生成模拟向量
     */
    private List<Float> generateMockEmbedding() {
        Random random = new Random();
        List<Float> vector = new ArrayList<>();
        for (int i = 0; i < dimension; i++) {
            vector.add(random.nextFloat() * 2 - 1);
        }

        // 归一化
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);

        List<Float> normalized = new ArrayList<>();
        for (float v : vector) {
            normalized.add(v / norm);
        }

        return normalized;
    }
}

