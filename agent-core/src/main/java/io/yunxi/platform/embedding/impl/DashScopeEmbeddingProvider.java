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
 * 阿里云百炼 Embedding 提供者
 * <p>
 * 使用阿里云百炼（DashScope）的 Embedding API
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
@ConditionalOnProperty(name = "agentscope.extensions.embedding.dashscope.enabled", havingValue = "true", matchIfMissing = false)
public class DashScopeEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingProvider.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agentscope.extensions.embedding.dashscope.api-key:}")
    private String apiKey;

    @Value("${agentscope.extensions.embedding.dashscope.model:text-embedding-v2}")
    private String modelName;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(60))
            .build();

    @Override
    public String getProviderName() {
        return "dashscope";
    }

    @Override
    public List<Float> embed(String text) {
        try {
            String requestBody = objectMapper.writeValueAsString(
                    java.util.Map.of("model", modelName, "input", java.util.Map.of("texts", List.of(text))));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode jsonNode = objectMapper.readTree(response.body());

            if (jsonNode.has("output") && jsonNode.get("output").has("embeddings")) {
                JsonNode embeddings = jsonNode.get("output").get("embeddings");
                if (embeddings.isArray() && embeddings.size() > 0) {
                    JsonNode embeddingData = embeddings.get(0);
                    if (embeddingData.has("embedding")) {
                        List<Float> result = new ArrayList<>();
                        for (JsonNode val : embeddingData.get("embedding")) {
                            result.add((float) val.asDouble());
                        }
                        return result;
                    }
                }
            }

            log.error("DashScope Embedding API 返回异常: {}", response.body());
            throw new RuntimeException("DashScope Embedding API 调用失败: " + response.body());

        } catch (Exception e) {
            log.error("DashScope Embedding 调用失败", e);
            throw new RuntimeException("DashScope Embedding 调用失败", e);
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
        return 1536;
    }

    @Override
    public String getModelName() {
        return modelName;
    }
}
