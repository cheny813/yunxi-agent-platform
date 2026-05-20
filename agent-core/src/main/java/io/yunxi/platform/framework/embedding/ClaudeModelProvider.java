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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claude 模型提供商实现
 * <p>
 * 使用 Anthropic Claude API
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
public class ClaudeModelProvider implements ChatModelProvider {

    /** Claude API 默认地址 */
    private static final String DEFAULT_API_URL = "https://api.anthropic.com/v1/messages";

    /** API Key */
    private final String apiKey;
    /** 模型名称 */
    private final String modelName;
    /** API 基础 URL */
    private final String baseUrl;
    /** HTTP 客户端 */
    private final OkHttpClient httpClient;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /**
     * 构造 Claude 模型提供商
     *
     * @param config 模型配置
     */
    public ClaudeModelProvider(ModelConfig config) {
        this.apiKey = config.getApiKey();
        this.modelName = config.getModelName() != null ? config.getModelName() : "claude-3-5-sonnet-20241022";
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_API_URL;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        log.info("Claude chat request: {}, model: {}", messages, modelName);

        return Mono.fromCallable(() -> {
            // 构建请求
            Map<String, Object> requestBody = buildRequestBody(messages, options);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 构建请求
            Request request = new Request.Builder()
                    .url(baseUrl)
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .build();

            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                throw new RuntimeException("Claude API error: " + response.code() + " - " + responseBody);
            }

            JsonNode jsonNode = objectMapper.readTree(responseBody);
            JsonNode contentArray = jsonNode.path("content");

            if (contentArray.isArray() && contentArray.size() > 0) {
                // Claude 返回的内容格式不同，需要遍历查找text类型
                for (JsonNode item : contentArray) {
                    String type = item.path("type").asText();

                    if ("text".equals(type)) {
                        String content = item.path("text").asText();

                        // 构造返回消息 - 使用 TextBlock
                        TextBlock textBlock = TextBlock.builder().text(content).build();
                        ChatResponse chatResponse = ChatResponse.builder()
                                .content(List.of(textBlock))
                                .build();

                        return chatResponse;
                    }
                }
            }

            throw new RuntimeException("Invalid response from Claude API");
        })
                .flux()
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 构建请求体（Claude格式）
     */
    private Map<String, Object> buildRequestBody(List<Msg> messages, GenerateOptions options) {
        Map<String, Object> body = new ConcurrentHashMap<>();
        body.put("model", modelName);

        // Claude 要求 max_tokens
        body.put("max_tokens", 4096);

        // 转换消息格式 - Claude 要求在顶层包装messages数组
        body.put("messages", messages.stream()
                .map(msg -> {
                    Map<String, Object> msgMap = new ConcurrentHashMap<>();
                    msgMap.put("role", "user");
                    msgMap.put("content", msg.getTextContent());
                    return msgMap;
                })
                .toList());

        // 添加生成参数
        if (options != null && options.getTemperature() != null) {
            body.put("temperature", options.getTemperature());
        } else {
            body.put("temperature", 0.7);
        }

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
     * @return "claude"
     */
    @Override
    public String getProvider() {
        return "claude";
    }

    /**
     * 检查配置是否有效（API Key 不为空）
     *
     * @return 配置是否有效
     */
    @Override
    public boolean isValid() {
        return apiKey != null && !apiKey.isBlank();
    }
}
