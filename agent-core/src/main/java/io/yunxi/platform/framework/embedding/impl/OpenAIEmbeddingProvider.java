package io.yunxi.platform.framework.embedding.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.framework.embedding.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * OpenAI 兼容嵌入向量 Provider
 *
 * <p>
 * 支持 OpenAI 以及其他兼容 OpenAI API 的服务：
 * <ul>
 * <li>OpenAI (text-embedding-ada-002, text-embedding-3-small,
 * text-embedding-3-large)</li>
 * <li>Azure OpenAI</li>
 * <li>其他兼容 OpenAI API 的服务</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component("openai")
public class OpenAIEmbeddingProvider implements EmbeddingProvider {

    @Value("${embedding.openai.api-key:}")
    private String apiKey;

    @Value("${embedding.openai.model:text-embedding-ada-002}")
    private String model;

    @Value("${embedding.openai.dimension:1536}")
    private int dimension;

    @Value("${embedding.openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    /**
     * 批量大小配置
     * <p>
     * 限制取决于使用的模型（OpenAI API 限制）：
     * <ul>
     * <li>text-embedding-ada-002: 最多 2048 条/批</li>
     * <li>text-embedding-3-small: 最多 2048 条/批</li>
     * <li>text-embedding-3-large: 最多 2048 条/批</li>
     * </ul>
     * </p>
     */
    @Value("${embedding.openai.batch-size:25}")
    private int batchSize;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "OpenAI";
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
     * 直接将 texts 列表传递给 OpenAI API，内部的批量限制取决于具体模型：
     * <ul>
     * <li>text-embedding-ada-002: 最多 2048 条/批</li>
     * <li>text-embedding-3-small: 最多 2048 条/批</li>
     * <li>text-embedding-3-large: 最多 2048 条/批</li>
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
            log.warn("OpenAI API Key 未配置，使用模拟向量");
            return texts.stream().map(t -> generateMockEmbedding()).toList();
        }

        try {
            String url = baseUrl + "/embeddings";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("input", texts);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            // Azure OpenAI 使用不同的认证头
            if (baseUrl.contains("azure")) {
                headers.set("api-key", apiKey);
            }

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode data = root.path("data");

                if (data.isArray() && data.size() > 0) {
                    List<List<Float>> vectors = new ArrayList<>();
                    for (JsonNode item : data) {
                        JsonNode embedding = item.path("embedding");
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
            }

            log.warn("OpenAI Embedding API 响应异常: {}", response.getBody());
            return texts.stream().map(t -> generateMockEmbedding()).toList();

        } catch (Exception e) {
            log.error("调用 OpenAI Embedding API 失败: {}", e.getMessage());
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

