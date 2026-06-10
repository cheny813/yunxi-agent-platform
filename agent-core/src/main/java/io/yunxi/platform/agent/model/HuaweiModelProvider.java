package io.yunxi.platform.agent.model;

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
 * 华为盘古大模型供应商。
 *
 * <p>
 * 封装华为 ModelArts Studio V2 接口调用，兼容 OpenAI 格式。
 * 使用 AK/SK 签名认证（HMAC-SHA256），将其转换为 OpenAI 兼容的 HTTP 接口。
 * 将内部 role 映射为 {@link MsgRole} 规范的华为 API 格式。
 * </p>
 *
 * <p>
 * 认证流程：
 * 1. 将 timestamp + nonce + body 拼接为待签名字符串
 * 2. 使用 SK 作为密钥进行 HMAC-SHA256 签名
 * 3. 将签名结果 Base64 编码后放入 X-Signature 请求头
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
public class HuaweiModelProvider implements Model {

    /** 华为盘古 API 地址 */
    private static final String API_URL = "https://pangu.huaweicloud.com/api/v2/chat/completions";

    /** 华为 Access Key (AK) */
    private final String accessKey;

    /** 华为 Secret Key (SK)，用于签名认证 */
    private final String secretKey;

    /** 模型名称（如 pangu-chat），默认 pangu-chat */
    private final String modelName;

    /** 生成选项默认值，调用时未指定 options 时使用 */
    private final GenerateOptions defaultOptions;

    /** HTTP 客户端，用于调用华为 API */
    private final OkHttpClient httpClient;

    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;

    /**
     * 创建华为模型供应商。
     *
     * @param accessKey      华为 Access Key
     * @param secretKey      华为 Secret Key
     * @param modelName      模型名称（如 pangu-chat）
     * @param defaultOptions 生成选项默认值
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

    /**
     * 流式调用华为盘古对话 API。
     *
     * <p>
     * 当前实现为伪流式（一次性返回完整结果），因为华为盘古部分模型
     * 不支持 SSE 流式输出。返回的 Flux 中只包含一个 ChatResponse。
     * </p>
     *
     * @param messages 消息列表
     * @param tools    工具 Schema 列表（当前未使用）
     * @param options  生成选项
     * @return ChatResponse 流
     */
    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        log.info("Huawei Pangu chat request: {}, model: {}", messages, modelName);

        return Mono.fromCallable(() -> {
            // 构建请求体并序列化为 JSON
            Map<String, Object> requestBody = buildRequestBody(messages, options);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 生成华为 HMAC-SHA256 签名
            String timestamp = String.valueOf(Instant.now().toEpochMilli());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String signature = generateSignature(timestamp, nonce, jsonBody);

            // 构建 HTTP 请求，添加签名相关请求头
            Request request = new Request.Builder()
                    .url(API_URL)
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .addHeader("X-Access-Key", accessKey)
                    .addHeader("X-Signature", signature)
                    .addHeader("X-Timestamp", timestamp)
                    .addHeader("X-Nonce", nonce)
                    .addHeader("Content-Type", "application/json")
                    .build();

            // 执行 HTTP 调用
            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                throw new RuntimeException("Huawei Pangu API error: " + response.code() + " - " + responseBody);
            }

            // 解析响应，提取 OpenAI 格式的 choices[0].message.content
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

    /**
     * 获取模型名称。
     *
     * @return 模型名称
     */
    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 生成华为 API HMAC-SHA256 签名。
     *
     * <p>
     * 签名算法：
     * 1. 将 timestamp + nonce + body 拼接为待签名字符串
     * 2. 使用 SK 作为密钥进行 HMAC-SHA256 运算
     * 3. 将结果 Base64 编码后作为签名值
     * </p>
     *
     * @param timestamp 时间戳（毫秒）
     * @param nonce     随机字符串（UUID 去除连字符）
     * @param body      请求体 JSON 字符串
     * @return Base64 编码的签名
     * @throws Exception 签名计算失败时抛出
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
     * 构建华为盘古兼容 OpenAI 格式的请求体，包含角色映射。
     *
     * <p>
     * 请求体包含：
     * - model：模型名称
     * - messages：消息列表，每条消息包含 role 和 content
     * - temperature：生成温度，默认 0.7
     * - top_p：核采样概率阈值，默认 0.9
     * </p>
     *
     * @param messages 消息列表
     * @param options  生成选项（为 null 时使用 defaultOptions）
     * @return 请求体 Map
     */
    private Map<String, Object> buildRequestBody(List<Msg> messages, GenerateOptions options) {
        Map<String, Object> body = new ConcurrentHashMap<>();
        body.put("model", modelName);

        // 将 MsgRole 映射为华为 API 要求的角色字符串
        body.put("messages", messages.stream()
                .map(msg -> {
                    Map<String, Object> msgMap = new HashMap<>();
                    msgMap.put("role", mapHuaweiRole(msg.getRole()));
                    msgMap.put("content", msg.getTextContent());
                    return msgMap;
                })
                .toList());

        // 合并生成选项，优先使用调用时传入的 options，否则使用 defaultOptions
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
     * 将 MsgRole 枚举映射为华为 API 要求的角色字符串。
     *
     * <p>
     * 华为盘古兼容 OpenAI 格式，支持 system / user / assistant / tool 四种角色，
     * 因此可以直接映射。未知角色默认映射为 user。
     * </p>
     *
     * @param role 内部消息角色
     * @return 华为 API 角色字符串
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
