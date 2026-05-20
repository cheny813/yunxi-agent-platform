package io.yunxi.platform.framework.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.yunxi.platform.shared.util.DashScopeSchemaUtils;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DashScope 模型提供商实现
 * <p>
 * 适配器模式，封装 DashScopeChatModel 并实现 ChatModelProvider 接口
 * 支持结构化输出（JSON Mode）
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class DashScopeModelProvider implements ChatModelProvider {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(DashScopeModelProvider.class);
    /** DashScope API 兼容模式地址 */
    private static final String DASHSCOPE_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    /** DashScope 聊天模型委托实例 */
    private final DashScopeChatModel delegate;
    /** API Key */
    private final String apiKey;
    /** 模型名称 */
    private final String modelName;
    /** HTTP 客户端 */
    private final OkHttpClient httpClient;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    // 存储结构化输出的 schema
    private String structuredOutputSchema;

    /**
     * 构造 DashScope 模型提供商
     *
     * @param config 模型配置
     */
    public DashScopeModelProvider(ModelConfig config) {
        this.apiKey = config.getApiKey();
        this.modelName = config.getModelName() != null ? config.getModelName() : "qwen-plus";
        this.delegate = DashScopeChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .build();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();

        // 设置结构化输出 schema
        if (config.getStructuredOutputSchema() != null && !config.getStructuredOutputSchema().isBlank()) {
            this.structuredOutputSchema = config.getStructuredOutputSchema();
            log.info("DashScope 模型提供商初始化，启用结构化输出");
        }
    }

    /**
     * 设置结构化输出 schema
     *
     * @param schema JSON Schema 字符串
     */
    public void setStructuredOutputSchema(String schema) {
        this.structuredOutputSchema = schema;
        log.info("设置结构化输出 schema, 长度: {}", schema != null ? schema.length() : 0);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        log.info("DashScope chat request: {}", messages);

        // 如果设置了结构化输出 schema，使用自定义实现
        if (structuredOutputSchema != null && !structuredOutputSchema.isBlank()) {
            return streamWithStructuredOutput(messages, tools, options);
        }

        // 否则使用原始实现
        List<ToolSchema> processedTools = processTools(tools);
        return delegate.stream(messages, processedTools, options);
    }

    /**
     * 使用结构化输出的流式请求
     */
    private Flux<ChatResponse> streamWithStructuredOutput(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Mono.fromCallable(() -> {
            // 构建请求体
            Map<String, Object> requestBody = buildStructuredOutputRequest(messages, tools, options);
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.info("结构化输出请求体: {}", jsonBody.substring(0, Math.min(500, jsonBody.length())));

            // 构建请求
            Request request = new Request.Builder()
                    .url(DASHSCOPE_API_URL)
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                throw new RuntimeException("DashScope API error: " + response.code() + " - " + responseBody);
            }

            JsonNode jsonNode = objectMapper.readTree(responseBody);
            JsonNode choices = jsonNode.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).path("message").path("content").asText();

                // 构造返回消息
                TextBlock textBlock = TextBlock.builder().text(content).build();
                ChatResponse chatResponse = ChatResponse.builder()
                        .content(List.of(textBlock))
                        .build();

                return chatResponse;
            }

            throw new RuntimeException("Invalid response from DashScope API");
        })
                .flux()
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 构建结构化输出请求
     */
    private Map<String, Object> buildStructuredOutputRequest(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Map<String, Object> body = new ConcurrentHashMap<>();
        body.put("model", modelName);

        // 转换消息格式
        List<Map<String, String>> formattedMessages = new ArrayList<>();
        for (Msg msg : messages) {
            Map<String, String> msgMap = new HashMap<>();
            msgMap.put("role", "user");
            msgMap.put("content", msg.getTextContent());
            formattedMessages.add(msgMap);
        }
        body.put("messages", formattedMessages);

        // 设置结构化输出
        Map<String, Object> responseFormat = new HashMap<>();
        responseFormat.put("type", "json_object");
        body.put("response_format", responseFormat);

        // 添加生成参数
        body.put("temperature", options != null && options.getTemperature() != null
                ? options.getTemperature()
                : 0.7);
        body.put("max_tokens", 2000);

        // 处理工具
        if (tools != null && !tools.isEmpty()) {
            List<ToolSchema> processedTools = processTools(tools);
            // 添加工具定义（如果需要）
            log.info("结构化输出模式，工具数量: {}", processedTools.size());
        }

        return body;
    }

    // 处理后的工具 schema 缓存（key 为工具名组合的 hash）
    private volatile List<ToolSchema> cachedProcessedTools;
    private volatile int cachedToolsHash;

    /**
     * 处理工具 schema，展开所有 $ref 引用
     * DashScope API 不支持 JSON Schema 的 $ref 引用格式
     * <p>
     * 优化：缓存处理结果，避免每次 LLM 调用都重复处理 30 个工具的 schema。
     * 同一组工具在多次调用中不会变化，直接复用缓存即可。
     * </p>
     */
    private List<ToolSchema> processTools(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return tools;
        }

        // 基于工具名列表计算 hash，判断是否与缓存一致
        int toolsHash = tools.stream().map(ToolSchema::getName).toList().hashCode();
        if (toolsHash == cachedToolsHash && cachedProcessedTools != null) {
            log.debug("复用已缓存的 {} 个工具 schema", tools.size());
            return cachedProcessedTools;
        }

        log.info("Processing {} tools for DashScope compatibility (首次或工具变更)", tools.size());
        List<ToolSchema> result = new ArrayList<>();

        for (ToolSchema tool : tools) {
            log.debug("Processing tool: {}", tool.getName());
            Map<String, Object> processedParams = DashScopeSchemaUtils.inlineSchemaReferences(tool.getParameters());
            ToolSchema processedTool = ToolSchema.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .parameters(processedParams)
                    .build();
            result.add(processedTool);
        }

        // 更新缓存
        this.cachedProcessedTools = result;
        this.cachedToolsHash = toolsHash;
        return result;
    }

    /**
     * 获取当前模型名称
     *
     * @return 模型名称
     */
    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    /**
     * 获取提供商标识
     *
     * @return "dashscope"
     */
    @Override
    public String getProvider() {
        return "dashscope";
    }

    /**
     * 检查配置是否有效
     *
     * @return 委托实例是否存在
     */
    @Override
    public boolean isValid() {
        return delegate != null;
    }

    /**
     * 获取 DashScope 聊天模型委托实例
     *
     * @return DashScopeChatModel 实例
     */
    public DashScopeChatModel getDelegate() {
        return delegate;
    }
}
