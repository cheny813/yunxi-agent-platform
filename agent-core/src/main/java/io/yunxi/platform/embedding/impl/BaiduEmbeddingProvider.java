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
 * 百度 Embedding 提供者
 * <p>
 * 使用百度千帆平台的 Embedding API
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
@ConditionalOnProperty(name = "agentscope.extensions.embedding.baidu.enabled", havingValue = "true", matchIfMissing = false)
public class BaiduEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(BaiduEmbeddingProvider.class);

    /** JSON 序列化对象 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agentscope.extensions.embedding.baidu.api-key:}")
    private String apiKey;

    @Value("${agentscope.extensions.embedding.baidu.secret-key:}")
    private String secretKey;

    @Value("${agentscope.extensions.embedding.baidu.model:Embedding-V1}")
    private String modelName;

    private String accessToken;
    private long tokenExpireTime;

    /** HttpClient */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    /**
     * 返回提供者名称
     *
     * @return 固定返回 "baidu"
     */
    @Override
    public String getProviderName() {
        return "baidu";
    }

    /**
     * 对单条文本生成向量嵌入（调用前自动确保 access_token 有效）
     *
     * @param text 待嵌入的文本
     * @return 文本的向量表示（维度由 {@link #getDimension()} 决定）
     * @throws RuntimeException 当百度千帆 API 调用失败或返回结构异常时抛出
     */
    @Override
    public List<Float> embed(String text) {
        try {
            ensureToken();
            String url = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/embeddings/" + modelName
                    + "?access_token=" + accessToken;

            String requestBody = objectMapper.writeValueAsString(
                    java.util.Map.of("input", text));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
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

            log.error("百度 Embedding API 返回异常: {}", response.body());
            throw new RuntimeException("百度 Embedding API 调用失败: " + response.body());

        } catch (Exception e) {
            log.error("百度 Embedding 调用失败", e);
            throw new RuntimeException("百度 Embedding 调用失败", e);
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
     * @return 固定返回 384
     */
    @Override
    public int getDimension() {
        return 384;
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

    /**
     * 获取 access_token
     */
    private void ensureToken() {
        if (System.currentTimeMillis() < tokenExpireTime) {
            return;
        }
        try {
            String authUrl = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials"
                    + "&client_id=" + apiKey + "&client_secret=" + secretKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(authUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode jsonNode = objectMapper.readTree(response.body());

            if (jsonNode.has("access_token")) {
                accessToken = jsonNode.get("access_token").asText();
                tokenExpireTime = System.currentTimeMillis() + (jsonNode.has("expires_in")
                        ? jsonNode.get("expires_in").asLong() * 1000 : 24 * 60 * 60 * 1000);
                log.info("百度 access_token 获取成功");
            } else {
                log.error("百度 access_token 获取失败: {}", response.body());
            }
        } catch (Exception e) {
            log.error("百度 access_token 获取失败", e);
        }
    }
}
