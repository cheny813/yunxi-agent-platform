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
 * OpenAI Embedding 提供者
 * <p>
 * 使用 OpenAI 兼容 API 的 Embedding 服务
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
@ConditionalOnProperty(name = "agentscope.extensions.embedding.openai.enabled", havingValue = "true", matchIfMissing = true)
public class OpenAIEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAIEmbeddingProvider.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agentscope.extensions.embedding.openai.api-key:}")
    private String apiKey;

    @Value("${agentscope.extensions.embedding.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${agentscope.extensions.embedding.openai.model:text-embedding-ada-002}")
    private String modelName;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(60))
            .build();

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public List<Float> embed(String text) {
        try {
            String requestBody = objectMapper.writeValueAsString(
                    java.util.Map.of("input", text, "model", modelName));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode jsonNode = objectMapper.readTree(response.body());

            if (jsonNode.has("data") && jsonNode.get("data").isArray()) {
                JsonNode embeddingData = jsonNode.get("data").get(0);
                if (embeddingData.has("embedding")) {
                    List<Float> result = new ArrayList<>();
                    for (JsonNode val : embeddingData.get("embedding")) {
                        result.add((float) val.asDouble());
                    }
                    return result;
                }
            }

            log.error("OpenAI Embedding API 返回异常: {}", response.body());
            throw new RuntimeException("OpenAI Embedding API 调用失败: " + response.body());

        } catch (Exception e) {
            log.error("OpenAI Embedding 调用失败", e);
            throw new RuntimeException("OpenAI Embedding 调用失败", e);
        }
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        try {
            String requestBody = objectMapper.writeValueAsString(
                    java.util.Map.of("input", texts, "model", modelName));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode jsonNode = objectMapper.readTree(response.body());

            List<List<Float>> results = new ArrayList<>();
            if (jsonNode.has("data") && jsonNode.get("data").isArray()) {
                for (JsonNode item : jsonNode.get("data")) {
                    if (item.has("embedding")) {
                        List<Float> embedding = new ArrayList<>();
                        for (JsonNode val : item.get("embedding")) {
                            embedding.add((float) val.asDouble());
                        }
                        results.add(embedding);
                    }
                }
                return results;
            }

            log.error("OpenAI Embedding API 返回异常: {}", response.body());
            throw new RuntimeException("OpenAI Embedding API 调用失败: " + response.body());

        } catch (Exception e) {
            log.error("OpenAI Embedding 批量调用失败", e);
            throw new RuntimeException("OpenAI Embedding 批量调用失败", e);
        }
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
