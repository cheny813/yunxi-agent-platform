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
 * Ollama 本地嵌入向量 Provider
 *
 * <p>
 * 使用本地 Ollama 服务生成嵌入向量，无需云端 API。
 * </p>
 *
 * <h3>前置条件</h3>
 * <ol>
 * <li>安装 Ollama: https://ollama.com/download</li>
 * <li>拉取嵌入模型: ollama pull bge-m3</li>
 * <li>启动服务: ollama serve (默认端口 11434)</li>
 * </ol>
 *
 * <h3>推荐模型（中文友好）</h3>
 * <ul>
 * <li>bge-m3 - 多语言，1024维，中文效果最佳</li>
 * <li>bge-large-zh - 中文专用，1024维</li>
 * <li>nomic-embed-text - 英文友好，768维</li>
 * <li>mxbai-embed-large - 英文友好，1024维</li>
 * </ul>
 *
 * <h3>配置示例</h3>
 * <pre>
 * embedding:
 *   provider: ollama
 *   ollama:
 *     base-url: http://localhost:11434
 *     model: bge-m3
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component("ollama")
@ConditionalOnProperty(name = "embedding.provider", havingValue = "ollama")
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    @Value("${embedding.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${embedding.ollama.model:bge-m3}")
    private String model;

    @Value("${embedding.ollama.batch-size:50}")
    private int batchSize;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 模型与维度的映射
    private static final Map<String, Integer> MODEL_DIMENSIONS = Map.of(
            "bge-m3", 1024,
            "bge-large-zh", 1024,
            "bge-large-en", 1024,
            "nomic-embed-text", 768,
            "mxbai-embed-large", 1024,
            "all-minilm", 384
    );

    @Override
    public String getName() {
        return "Ollama (" + model + ")";
    }

    @Override
    public int getDimension() {
        Integer mappedDim = MODEL_DIMENSIONS.get(model);
        if (mappedDim != null) {
            return mappedDim;
        }
        // 默认 1024 维
        return 1024;
    }

    @Override
    public int getBatchSize() {
        return batchSize;
    }

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return generateZeroVector();
        }

        // 截断过长文本
        if (text.length() > 8000) {
            text = text.substring(0, 8000);
        }

        try {
            String url = baseUrl + "/api/embeddings";

            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("prompt", text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), headers);

            log.debug("调用 Ollama Embedding API: model={}", model);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                if (root.has("error")) {
                    log.error("Ollama API 错误: {}", root.path("error").asText());
                    return generateMockEmbedding(text);
                }

                JsonNode embeddingNode = root.path("embedding");
                if (embeddingNode.isArray()) {
                    List<Float> vector = new ArrayList<>();
                    for (JsonNode node : embeddingNode) {
                        vector.add((float) node.asDouble());
                    }
                    log.debug("成功获取向量，维度: {}", vector.size());
                    return vector;
                }
            }

            log.warn("Ollama Embedding API 响应异常: {}", response.getBody());
            return generateMockEmbedding(text);

        } catch (Exception e) {
            log.error("调用 Ollama Embedding API 失败: {}", e.getMessage());
            return generateMockEmbedding(text);
        }
    }

    /**
     * 批量嵌入
     * <p>
     * Ollama 本地服务无严格批量限制，但建议分批处理避免内存问题。
     * </p>
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        // 尝试使用 Ollama 批量 API（如果支持）
        // Ollama 0.1.26+ 支持 /api/embed 批量接口
        List<List<Float>> results = tryBatchEmbed(texts);
        
        if (results != null) {
            return results;
        }

        // 降级为逐条处理
        log.debug("Ollama 批量 API 不可用，逐条处理 {} 条文本", texts.size());
        List<List<Float>> allResults = new ArrayList<>(texts.size());
        for (String text : texts) {
            allResults.add(embed(text));
        }
        return allResults;
    }

    /**
     * 尝试使用 Ollama 批量 API
     * <p>
     * Ollama 0.1.26+ 支持 /api/embed 批量接口
     * </p>
     */
    private List<List<Float>> tryBatchEmbed(List<String> texts) {
        try {
            String url = baseUrl + "/api/embed";

            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("input", texts);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                if (root.has("error")) {
                    log.debug("Ollama 批量 API 不支持: {}", root.path("error").asText());
                    return null;
                }

                // 解析批量响应
                JsonNode embeddingsNode = root.path("embeddings");
                if (embeddingsNode.isArray()) {
                    List<List<Float>> vectors = new ArrayList<>();
                    for (JsonNode emb : embeddingsNode) {
                        List<Float> vector = new ArrayList<>();
                        for (JsonNode node : emb) {
                            vector.add((float) node.asDouble());
                        }
                        vectors.add(vector);
                    }
                    log.debug("Ollama 批量嵌入成功: {} 条文本", vectors.size());
                    return vectors;
                }
            }

            return null;

        } catch (Exception e) {
            log.debug("Ollama 批量 API 不可用: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查 Ollama 服务是否可用
     */
    public boolean isAvailable() {
        try {
            String url = baseUrl + "/api/tags";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.warn("Ollama 服务不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取已安装的模型列表
     */
    public List<String> listModels() {
        try {
            String url = baseUrl + "/api/tags";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode models = root.path("models");
                List<String> modelNames = new ArrayList<>();
                for (JsonNode m : models) {
                    modelNames.add(m.path("name").asText());
                }
                return modelNames;
            }
        } catch (Exception e) {
            log.error("获取 Ollama 模型列表失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 生成模拟向量（降级使用）
     */
    private List<Float> generateMockEmbedding(String text) {
        int dim = getDimension();
        Random random = new Random(text.hashCode());
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
     * 生成零向量
     */
    private List<Float> generateZeroVector() {
        List<Float> vector = new ArrayList<>();
        int dim = getDimension();
        for (int i = 0; i < dim; i++) {
            vector.add(0.0f);
        }
        return vector;
    }

    /**
     * 获取支持的模型列表
     */
    public static Map<String, Integer> getSupportedModels() {
        return MODEL_DIMENSIONS;
    }
}

