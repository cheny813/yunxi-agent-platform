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
@ConditionalOnProperty(name = "agentscope.extensions.embedding.ollama.enabled", havingValue = "true", matchIfMissing = false)
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingProvider.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agentscope.extensions.embedding.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${agentscope.extensions.embedding.ollama.model:llama2}")
    private String modelName;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(60))
            .build();

    @Override
    public String getProviderName() {
        return "ollama";
    }

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

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        List<List<Float>> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }

    @Override
    public int getDimension() {
        return 4096;
    }

    @Override
    public String getModelName() {
        return modelName;
    }
}
