package io.yunxi.platform.embedding.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.embedding.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama Embedding 提供者
 * <p>
 * 使用本地 Ollama 服务的 Embedding API
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
@ConditionalOnProperty(name = "agentscope.extensions.embedding.ollama.enabled", havingValue = "true", matchIfMissing = true)
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingProvider.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${embedding.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${embedding.ollama.model:bge-m3}")
    private String modelName;

    @Value("${embedding.default-dimension:1024}")
    private int dimension;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(60))
            .build();

    /**
     * 返回提供者名称
     *
     * @return 固定返回 "ollama"
     */
    @Override
    public String getProviderName() {
        return "ollama";
    }

    /**
     * 对单条文本生成向量嵌入（调用本地 Ollama 服务）
     *
     * @param text 待嵌入的文本
     * @return 文本的向量表示（维度由 {@link #getDimension()} 决定）
     * @throws RuntimeException 当 Ollama API 调用失败或返回结构异常时抛出
     */
    @Override
    public List<Float> embed(String text) {
        try {
            String requestBody = objectMapper.writeValueAsString(
                    java.util.Map.of("model", modelName, "prompt", text));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode jsonNode = objectMapper.readTree(response.body());

            if (jsonNode.has("embedding")) {
                List<Float> result = new ArrayList<>();
                for (JsonNode val : jsonNode.get("embedding")) {
                    result.add((float) val.asDouble());
                }
                return result;
            }

            log.error("Ollama Embedding API 返回异常: {}", response.body());
            throw new RuntimeException("Ollama Embedding API 调用失败: " + response.body());

        } catch (Exception e) {
            log.error("Ollama Embedding 调用失败", e);
            throw new RuntimeException("Ollama Embedding 调用失败", e);
        }
    }

    /**
     * 批量对文本生成向量嵌入（逐条调用 {@link #embed(String)}）
     *
     * @param texts 待嵌入的文本列表
     * @return 与输入顺序一一对应的向量列表
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        List<List<Float>> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }

    /**
     * 返回当前模型的向量维度
     *
     * @return 配置的向量维度（默认 1024，与 bge-m3 / Milvus 集合一致）
     */
    @Override
    public int getDimension() {
        return dimension;
    }

    /**
     * 返回当前使用的模型名称
     *
     * @return 配置的模型名称
     */
    @Override
    public String getModelName() {
        return modelName;
    }
}
