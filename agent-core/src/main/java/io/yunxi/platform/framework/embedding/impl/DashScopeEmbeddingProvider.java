package io.yunxi.platform.framework.embedding.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.framework.embedding.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 阿里云 DashScope 嵌入向量 Provider
 *
 * <p>
 * 支持 DashScope 的嵌入和重排序模型：
 * <ul>
 * <li>文本嵌入模型：</li>
 * <ul>
 * <li>text-embedding-v3 (1024维)</li>
 * <li>text-embedding-v4 (2048维)</li>
 * <li>text-embedding-3-small</li>
 * </ul>
 * <li>多模态嵌入模型：</li>
 * <ul>
 * <li>multimodal-embedding-v1 (1024维)</li>
 * </ul>
 * <li>重排序模型：</li>
 * <ul>
 * <li>qwen3-vl-rerank</li>
 * </ul>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component("dashscope")
@ConditionalOnProperty(name = "embedding.provider", havingValue = "dashscope", matchIfMissing = true)
public class DashScopeEmbeddingProvider implements EmbeddingProvider {

    @Value("${embedding.dashscope.api-key:}")
    private String apiKey;

    @Value("${embedding.dashscope.model:text-embedding-v3}")
    private String model;

    @Value("${embedding.dashscope.dimension:1024}")
    private int dimension;

    /**
     * 批量大小配置
     * <p>
     * DashScope API 限制：每批最多 10 条文本
     * </p>
     */
    @Value("${embedding.dashscope.batch-size:10}")
    private int batchSize;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 模型与维度的映射
    private static final Map<String, Integer> MODEL_DIMENSIONS = Map.of(
            "text-embedding-v3", 1024,
            "text-embedding-v4", 2048,
            "text-embedding-3-small", 1536,
            "multimodal-embedding-v1", 1024,
            "qwen3-vl-rerank", -1 // 重排序模型不返回向量
    );

    @Override
    public String getName() {
        return "DashScope";
    }

    @Override
    public int getDimension() {
        // 如果配置了自动检测维度，则返回配置的维度
        Integer mappedDim = MODEL_DIMENSIONS.get(model);
        if (mappedDim != null && mappedDim > 0) {
            return mappedDim;
        }
        return dimension;
    }

    /**
     * 获取批量处理大小
     * <p>
     * 注意：此方法直接传递 texts 列表给 DashScope API，内部的批量限制取决于模型本身。
     * 本配置仅用于控制调用方（如 StaticDataSyncService）的分批大小。
     * </p>
     *
     * @return 批量大小
     */
    public int getBatchSize() {
        return batchSize;
    }

    @Override
    public List<Float> embed(String text) {
        // 批量调用单个元素
        List<List<Float>> results = embedBatch(List.of(text));
        return results.isEmpty() ? generateMockEmbedding() : results.get(0);
    }

    /**
     * 批量嵌入
     * <p>
     * 自动按 API 限制分批处理，每批最多 10 条。
     * </p>
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("DashScope API Key 未配置，使用模拟向量");
            return texts.stream().map(t -> generateMockEmbedding()).toList();
        }

        // 自动分批处理，每批最多 batchSize 条
        List<List<Float>> allResults = new ArrayList<>(texts.size());
        int actualBatchSize = Math.min(batchSize, 10); // 确保不超过 API 限制

        for (int i = 0; i < texts.size(); i += actualBatchSize) {
            int end = Math.min(i + actualBatchSize, texts.size());
            List<String> batch = texts.subList(i, end);
            log.debug("处理批量 [{}/{}]: {} 条文本", (i / actualBatchSize) + 1, 
                    (texts.size() + actualBatchSize - 1) / actualBatchSize, batch.size());

            List<List<Float>> batchResults = callDashScopeApi(batch);
            allResults.addAll(batchResults);
        }

        return allResults;
    }

    /**
     * 调用 DashScope Embedding API（单批次）
     */
    private List<List<Float>> callDashScopeApi(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // 根据模型类型选择 API
            String url = getApiUrl();

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);

            // 构建输入参数 - 支持批量
            Map<String, Object> input = new HashMap<>();
            input.put("texts", texts);
            requestBody.put("input", input);

            // 构建参数
            Map<String, Object> parameters = buildParameters();
            if (parameters != null) {
                requestBody.put("parameters", parameters);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                // 检查是否有错误
                if (root.has("code") && !"success".equals(root.path("code").asText())) {
                    log.warn("DashScope API 错误: {} - {}",
                            root.path("code").asText(),
                            root.path("message").asText());
                    return texts.stream().map(t -> generateMockEmbedding()).toList();
                }

                // 解析批量响应
                List<List<Float>> vectors = parseBatchResponse(root, texts.size());
                if (vectors != null && !vectors.isEmpty()) {
                    return vectors;
                }
            }

            log.warn("DashScope Embedding API 响应异常: {}", response.getBody());
            return texts.stream().map(t -> generateMockEmbedding()).toList();

        } catch (Exception e) {
            log.error("调用 DashScope Embedding API 失败: {}", e.getMessage());
            return texts.stream().map(t -> generateMockEmbedding()).toList();
        }
    }

    /**
     * 解析批量响应
     */
    private List<List<Float>> parseBatchResponse(JsonNode root, int expectedSize) {
        List<List<Float>> results = new ArrayList<>();
        JsonNode output = root.path("output");

        if (model.contains("rerank")) {
            // 重排序模型返回 scores
            JsonNode resultsNode = output.path("results");
            if (resultsNode.isArray()) {
                for (JsonNode result : resultsNode) {
                    List<Float> scores = new ArrayList<>();
                    for (JsonNode item : result) {
                        float score = (float) item.path("relevance_score").asDouble();
                        scores.add(score);
                    }
                    results.add(scores);
                }
            }
        } else {
            // 普通嵌入模型
            JsonNode embeddings = output.path("embeddings");
            if (embeddings.isArray()) {
                for (JsonNode emb : embeddings) {
                    JsonNode vectorNode = emb.path("embedding");
                    List<Float> vector = new ArrayList<>();
                    for (JsonNode node : vectorNode) {
                        vector.add((float) node.asDouble());
                    }
                    results.add(vector);
                }
            }
        }

        return results;
    }

    /**
     * 获取 API URL
     */
    private String getApiUrl() {
        // 重排序模型使用不同的 API
        if (model.contains("rerank")) {
            return "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/rerank";
        }
        // 多模态嵌入使用不同的 API
        if (model.contains("multimodal")) {
            return "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/text-embedding";
        }
        // 默认文本嵌入 API
        return "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    }

    /**
     * 构建输入参数
     */
    private Map<String, Object> buildInput(String text) {
        Map<String, Object> input = new HashMap<>();

        if (model.contains("multimodal")) {
            // 多模态嵌入：支持文本和图片
            // 暂时只支持文本
            input.put("texts", Collections.singletonList(text));
        } else if (model.contains("rerank")) {
            // 重排序模型：需要 query 和 documents
            input.put("query", text);
            input.put("documents", Collections.singletonList(text));
        } else {
            // 普通文本嵌入
            input.put("texts", Collections.singletonList(text));
        }

        return input;
    }

    /**
     * 构建参数
     */
    private Map<String, Object> buildParameters() {
        // 重排序模型不需要 dimension 参数
        if (model.contains("rerank")) {
            return null;
        }

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("text_type", "query");

        // 多模态嵌入模型不需要 dimension 参数
        if (!model.contains("multimodal")) {
            parameters.put("dimension", dimension);
        }

        return parameters;
    }

    /**
     * 解析响应
     */
    private List<Float> parseResponse(JsonNode root) {
        JsonNode output = root.path("output");

        if (model.contains("rerank")) {
            // 重排序模型返回 scores
            JsonNode results = output.path("results");
            if (results.isArray() && results.size() > 0) {
                // 重排序返回的是相似度分数，不是向量
                // 这里返回分数作为单一维度
                List<Float> scores = new ArrayList<>();
                for (JsonNode result : results) {
                    float score = (float) result.path("relevance_score").asDouble();
                    scores.add(score);
                }
                log.info("重排序模型返回 {} 个结果", scores.size());
                return scores;
            }
        }

        // 普通嵌入模型
        JsonNode embeddings = output.path("embeddings");
        if (embeddings.isArray() && embeddings.size() > 0) {
            JsonNode vectorNode = embeddings.get(0).path("embedding");
            List<Float> vector = new ArrayList<>();
            for (JsonNode node : vectorNode) {
                vector.add((float) node.asDouble());
            }
            return vector;
        }

        return null;
    }

    /**
     * 生成模拟向量
     */
    private List<Float> generateMockEmbedding() {
        int dim = getDimension();
        // 重排序模型返回分数列表
        if (model.contains("rerank")) {
            return List.of(0.5f);
        }

        Random random = new Random();
        List<Float> vector = new ArrayList<>();
        for (int i = 0; i < dim; i++) {
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

    /**
     * 获取支持的模型列表
     */
    public static Map<String, Integer> getSupportedModels() {
        return MODEL_DIMENSIONS;
    }
}

