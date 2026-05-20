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
 * 百度智能云嵌入向量 Provider
 *
 * <p>
 * 支持百度智能云的文本嵌入模型：
 * <ul>
 * <li>embedding-v1 (384维)</li>
 * <li>embedding-v2 (384维)</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component("baidu")
public class BaiduEmbeddingProvider implements EmbeddingProvider {

    @Value("${embedding.baidu.api-key:}")
    private String apiKey;

    @Value("${embedding.baidu.model:embedding-v1}")
    private String model;

    @Value("${embedding.baidu.dimension:384}")
    private int dimension;

    /**
     * 批量大小配置
     * <p>
     * 限制取决于使用的模型（百度云 API 限制）：
     * <ul>
     * <li>embedding-v1: 最多 30 条/批</li>
     * <li>embedding-v2: 最多 30 条/批</li>
     * </ul>
     * </p>
     */
    @Value("${embedding.baidu.batch-size:25}")
    private int batchSize;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "Baidu";
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
     * 直接将 texts 列表传递给百度云 API，内部的批量限制取决于具体模型：
     * <ul>
     * <li>embedding-v1: 最多 30 条/批</li>
     * <li>embedding-v2: 最多 30 条/批</li>
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
            log.warn("百度云 API Key 未配置，使用模拟向量");
            return texts.stream().map(t -> generateMockEmbedding()).toList();
        }

        try {
            // 百度云 embedding API - 支持批量
            String url = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/embeddings/" + model;

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("input", texts);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Access_Token", apiKey);

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

                // 检查错误
                if (root.has("error_code")) {
                    log.warn("百度云 Embedding API 错误: {} - {}",
                            root.path("error_code").asText(),
                            root.path("error_msg").asText());
                }
            }

            log.warn("百度云 Embedding API 响应异常: {}", response.getBody());
            return texts.stream().map(t -> generateMockEmbedding()).toList();

        } catch (Exception e) {
            log.error("调用百度云 Embedding API 失败: {}", e.getMessage());
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

