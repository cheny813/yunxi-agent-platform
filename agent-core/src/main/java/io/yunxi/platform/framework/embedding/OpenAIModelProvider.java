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
 * OpenAI 模型提供商实现
 * <p>
 * 使用 OpenAI API（兼容OpenAI格式的其他服务）
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
public class OpenAIModelProvider implements ChatModelProvider {

    /** OpenAI API 默认地址 */
    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";

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
     * 构造 OpenAI 模型提供商
     *
     * @param config 模型配置
     */
    public OpenAIModelProvider(ModelConfig config) {
        this.apiKey = config.getApiKey();
        this.modelName = config.getModelName() != null ? config.getModelName() : "gpt-3.5-turbo";
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
        log.info("OpenAI chat request: {}, model: {}", messages, modelName);

        return Mono.fromCallable(() -> {
            // 构建请求
            Map<String, Object> requestBody = buildRequestBody(messages, options);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 构建请求
            Request request = new Request.Builder()
                    .url(baseUrl)
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                throw new RuntimeException("OpenAI API error: " + response.code() + " - " + responseBody);
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

            throw new RuntimeException("Invalid response from OpenAI API");
        })
                .flux()
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 构建请求体（OpenAI格式）
     */
    private Map<String, Object> buildRequestBody(List<Msg> messages, GenerateOptions options) {
        Map<String, Object> body = new ConcurrentHashMap<>();
        body.put("model", modelName);

        // 转换消息格式
        body.put("messages", messages.stream()
                .map(msg -> {
                    // 简化处理，默认所有消息都是user
                    Map<String, String> msgMap = new ConcurrentHashMap<>();
                    msgMap.put("role", "user");
                    msgMap.put("content", msg.getTextContent());
                    return msgMap;
                })
                .toList());

        // 添加生成参数
        body.put("temperature", options != null && options.getTemperature() != null
                ? options.getTemperature()
                : 0.7);
        body.put("max_tokens", 2000);
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
     * @return "openai"
     */
    @Override
    public String getProvider() {
        return "openai";
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
