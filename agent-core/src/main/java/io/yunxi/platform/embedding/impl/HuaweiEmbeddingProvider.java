package io.yunxi.platform.embedding.impl;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.embedding.EmbeddingProvider;

/**
 * 华为 Embedding 提供者
 * <p>
 * 使用华为云盘古大模型的 Embedding API (HMAC-SHA256 签名认证)
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
@ConditionalOnProperty(name = "agentscope.extensions.embedding.huawei.enabled", havingValue = "true", matchIfMissing = false)
public class HuaweiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(HuaweiEmbeddingProvider.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agentscope.extensions.embedding.huawei.api-key:}")
    private String apiKey;

    @Value("${agentscope.extensions.embedding.huawei.secret-key:}")
    private String secretKey;

    @Value("${agentscope.extensions.embedding.huawei.endpoint:}")
    private String endpoint;

    @Value("${agentscope.extensions.embedding.huawei.model:text-embedding-v1}")
    private String modelName;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    /**
     * 返回提供者名称
     *
     * @return 固定返回 "huawei"
     */
    @Override
    public String getProviderName() {
        return "huawei";
    }

    /**
     * 对单条文本生成向量嵌入（使用 HMAC-SHA256 签名进行认证）
     *
     * @param text 待嵌入的文本
     * @return 文本的向量表示（维度由 {@link #getDimension()} 决定）
     * @throws RuntimeException 当华为云 API 调用失败或返回结构异常时抛出
     */
    @Override
    public List<Float> embed(String text) {
        try {
            String url = endpoint + "/v1/" + modelName + "/embeddings";

            Map<String, Object> requestBodyMap = new LinkedHashMap<>();
            requestBodyMap.put("input", text);

            String requestBody = objectMapper.writeValueAsString(requestBodyMap);
            String timestamp = String.valueOf(System.currentTimeMillis());
            String signature = sign(requestBody, timestamp);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-Sdk-Date", timestamp)
                    .header("Authorization", signature)
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

            log.error("华为 Embedding API 返回异常: {}", response.body());
            throw new RuntimeException("华为 Embedding API 调用失败: " + response.body());

        } catch (Exception e) {
            log.error("华为 Embedding 调用失败", e);
            throw new RuntimeException("华为 Embedding 调用失败", e);
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
     * @return 固定返回 768
     */
    @Override
    public int getDimension() {
        return 768;
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
     * 使用 HMAC-SHA256 对请求体进行签名，生成华为云认证头
     *
     * @param body      待签名的请求体内容
     * @param timestamp 请求时间戳（毫秒）
     * @return 形如 "HMAC-SHA256 <base64>" 的认证字符串
     */
    private String sign(String body, String timestamp) {
        try {
            String stringToSign = "POST\n" +
                    "application/json\n" +
                    timestamp + "\n" +
                    sha256Hex(body);

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] signBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));

            return "HMAC-SHA256 " + Base64.getEncoder().encodeToString(signBytes);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 签名失败", e);
        }
    }

    /**
     * 计算字符串的 SHA-256 十六进制摘要
     *
     * @param content 待计算的内容
     * @return 小写十六进制哈希字符串
     */
    private String sha256Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 计算失败", e);
        }
    }
}
