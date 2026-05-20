package io.yunxi.platform.framework.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 华为盘古大模型提供商实现
 * <p>
 * 使用华为ModelArts Studio V2 接口（兼容OpenAI格式）
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
public class HuaweiModelProvider implements ChatModelProvider {

    /** 华为盘古 API 地址 */
    private static final String API_URL = "https://pangu.huaweicloud.com/api/v2/chat/completions";

    /** Access Key (AK) */
    private final String accessKey;
    /** Secret Key (SK) */
    private final String secretKey;
    /** 模型名称 */
    private final String modelName;
    /** HTTP 客户端 */
    private final OkHttpClient httpClient;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /**
     * 构造华为盘古模型提供商
     *
     * @param config 模型配置，apiKey 字段填 Access Key，modelName 字段填 Secret Key
     */
    public HuaweiModelProvider(ModelConfig config) {
        // 从config中获取AK/SK
        this.accessKey = config.getApiKey(); // Access Key
        this.secretKey = config.getModelName(); // Secret Key (从modelName字段获取)
        this.modelName = "pangu-chat"; // 华为默认模型

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        log.info("Huawei Pangu chat request: {}, model: {}", messages, modelName);

        return Mono.fromCallable(() -> {
            // 构建请求
            Map<String, Object> requestBody = buildRequestBody(messages);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 生成签名
            String timestamp = String.valueOf(Instant.now().toEpochMilli());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String signature = generateSignature(timestamp, nonce, jsonBody);

            // 构建请求
            Request request = new Request.Builder()
                    .url(API_URL)
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .addHeader("X-Access-Key", accessKey)
                    .addHeader("X-Signature", signature)
                    .addHeader("X-Timestamp", timestamp)
                    .addHeader("X-Nonce", nonce)
                    .addHeader("Content-Type", "application/json")
                    .build();

            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                throw new RuntimeException("Huawei Pangu API error: " + response.code() + " - " + responseBody);
            }

            JsonNode jsonNode = objectMapper.readTree(responseBody);
            JsonNode choices = jsonNode.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).path("message").path("content").asText();

                // 构造返回消息 - 使用 TextBlock
                TextBlock textBlock = TextBlock.builder().text(content).build();
                ChatResponse chatResponse = ChatResponse.builder()
                        .content(List.of(textBlock))
                        .build();

                return chatResponse;
            }

            throw new RuntimeException("Invalid response from Huawei Pangu API");
        })
                .flux()
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 生成华为API签名
     */
    private String generateSignature(String timestamp, String nonce, String body) throws Exception {
        // 构建签名字符串
        String stringToSign = timestamp + nonce + body;

        // HMAC-SHA256 签名
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] signatureBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));

        return Base64.getEncoder().encodeToString(signatureBytes);
    }

    /**
     * 构建请求体（兼容OpenAI格式）
     */
    private Map<String, Object> buildRequestBody(List<Msg> messages) {
        Map<String, Object> body = new ConcurrentHashMap<>();
        body.put("model", modelName);

        // 转换消息格式
        body.put("messages", messages.stream()
                .map(msg -> Map.of(
                        "role", "user",
                        "content", msg.getTextContent()))
                .toList());

        // 添加生成参数
        body.put("temperature", 0.7);
        body.put("top_p", 0.9);

        return body;
    }

    /**
     * 获取当前模型名称
     *
     * @return 模型名称
     */
    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 获取提供商标识
     *
     * @return "huawei"
     */
    @Override
    public String getProvider() {
        return "huawei";
    }

    /**
     * 检查配置是否有效（Access Key 和 Secret Key 不为空）
     *
     * @return 配置是否有效
     */
    @Override
    public boolean isValid() {
        return accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }
}
