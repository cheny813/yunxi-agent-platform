package io.yunxi.platform.framework.model;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 华为盘古大模型实现
 * <p>
 * 使用华为 ModelArts Studio V2 接口（兼容 OpenAI 格式），
 * 使用 AK/SK 签名认证。不兼容 OpenAI 标准协议，因此保留自建 HTTP 实现。
 * 已修复 role 映射：根据 {@link MsgRole} 正确映射角色。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
public class HuaweiModelProvider implements Model {

    /** 华为盘古 API 地址 */
    private static final String API_URL = "https://pangu.huaweicloud.com/api/v2/chat/completions";

    /** Access Key (AK) */
    private final String accessKey;
    /** Secret Key (SK) */
    private final String secretKey;
    /** 模型名称 */
    private final String modelName;
    /** 默认生成参数 */
    private final GenerateOptions defaultOptions;
    /** HTTP 客户端 */
    private final OkHttpClient httpClient;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /**
     * 构造华为盘古模型
     *
     * @param accessKey      华为 Access Key
     * @param secretKey      华为 Secret Key
     * @param modelName      模型名称（如 pangu-chat）
     * @param defaultOptions 默认生成参数
     */
    public HuaweiModelProvider(String accessKey, String secretKey, String modelName, GenerateOptions defaultOptions) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.modelName = modelName != null ? modelName : "pangu-chat";
        this.defaultOptions = defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
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
            Map<String, Object> requestBody = buildRequestBody(messages, options);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 生成华为 HMAC-SHA256 签名
            String timestamp = String.valueOf(Instant.now().toEpochMilli());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String signature = generateSignature(timestamp, nonce, jsonBody);

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
                TextBlock textBlock = TextBlock.builder().text(content).build();
                return ChatResponse.builder()
                        .content(List.of(textBlock))
                        .build();
            }

            throw new RuntimeException("Invalid response from Huawei Pangu API");
        })
                .flux()
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 生成华为 API HMAC-SHA256 签名
     */
    private String generateSignature(String timestamp, String nonce, String body) throws Exception {
        String stringToSign = timestamp + nonce + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] signatureBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signatureBytes);
    }

    /**
     * 构建请求体（华为兼容 OpenAI 格式），修复 role 映射
     */
    private Map<String, Object> buildRequestBody(List<Msg> messages, GenerateOptions options) {
        Map<String, Object> body = new ConcurrentHashMap<>();
        body.put("model", modelName);

        // 根据 MsgRole 正确映射角色，不再硬编码 "user"
        body.put("messages", messages.stream()
                .map(msg -> {
                    Map<String, Object> msgMap = new HashMap<>();
                    msgMap.put("role", mapHuaweiRole(msg.getRole()));
                    msgMap.put("content", msg.getTextContent());
                    return msgMap;
                })
                .toList());

        // 合并生成参数
        GenerateOptions effective = options != null ? options : defaultOptions;
        if (effective.getTemperature() != null) {
            body.put("temperature", effective.getTemperature());
        } else {
            body.put("temperature", 0.7);
        }
        if (effective.getTopP() != null) {
            body.put("top_p", effective.getTopP());
        } else {
            body.put("top_p", 0.9);
        }

        return body;
    }

    /**
     * 将框架 MsgRole 映射到华为 API 角色字符串
     * <p>
     * 华为盘古兼容 OpenAI 格式，支持：system / user / assistant
     * 此映射与 OpenAI 标准一致。
     * </p>
     */
    private String mapHuaweiRole(MsgRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
            default -> "user";
        };
    }
}
