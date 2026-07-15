package io.yunxi.platform.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import okhttp3.*;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 百度千帆大模型供应商。
 *
 * <p>
 * 封装百度千帆 API 调用逻辑，使用 OAuth 2.0 access_token 认证。
 * 支持 OpenAI 兼容的对话格式，但需要通过 HTTP 接口调用。
 * 将内部 role 映射为 {@link MsgRole} 规范的百度 API 格式。
 * </p>
 *
 * <p>
 * 认证流程：通过 API Key + Secret Key 获取 access_token，
 * 使用双重检查锁定确保 token 刷新的线程安全。
 * token 默认有效期 30 天，提前 1 小时自动刷新。
 * </p>
 *
 * 继承 {@link ChatModelBase} 以复用框架内置的调用追踪（TracerRegistry）、
 * 上下文窗口声明与结构化输出开关，仅需实现 {@code doStream}。
 *
 * @author yunxi-agent-platform
 */
public class BaiduModelProvider extends ChatModelBase {

    /** 类级别日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(BaiduModelProvider.class);

    /** 百度 Access Token 获取地址 */
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";

    /** 百度千帆对话 API 地址 */
    private static final String CHAT_URL = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions";

    /** 未识别模型时的默认上下文窗口大小（token） */
    private static final int DEFAULT_CONTEXT_WINDOW_SIZE = 32768;

    /**
     * 已知百度模型上下文窗口大小（token），用于压缩中间件按 token 预算精准触发。
     * 未在此表内的模型回退到 {@link #DEFAULT_CONTEXT_WINDOW_SIZE}。可按实际部署增补。
     */
    private static final Map<String, Integer> MODEL_CONTEXT_WINDOWS = Map.of(
            "ernie-bot-turbo", 8192,
            "ernie-3.5-8k", 8192,
            "ernie-4.0-8k", 8192,
            "ernie-4.0-8k-preview", 8192,
            "ernie-lite-8k", 8192,
            "ernie-4.5-8k", 8192,
            "ernie-4.5-32k", 32768,
            "ernie-speed-128k", 128000);

    /** 百度 API Key（即 client_id） */
    private final String apiKey;

    /** 百度 Secret Key（即 client_secret） */
    private final String secretKey;

    /** 模型名称（如 ernie-bot-turbo），默认 ernie-bot-turbo */
    private final String modelName;

    /** 生成选项默认值，调用时未指定 options 时使用 */
    private final GenerateOptions defaultOptions;

    /** HTTP 客户端，用于调用百度 API */
    private final OkHttpClient httpClient;

    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;

    /** 百度 Access Token 缓存，避免频繁请求 */
    private String accessToken;

    /** Access Token 过期时间（毫秒时间戳），volatile 确保多线程可见性 */
    private volatile long tokenExpireTime;

    /**
     * 创建百度模型供应商。
     *
     * @param apiKey         百度 API Key
     * @param secretKey      百度 Secret Key
     * @param modelName      模型名称（如 ernie-bot-turbo）
     * @param defaultOptions 生成选项默认值
     */
    public BaiduModelProvider(String apiKey, String secretKey, String modelName, GenerateOptions defaultOptions) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.modelName = modelName != null ? modelName : "ernie-bot-turbo";
        this.defaultOptions = defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
        // 声明上下文窗口，使 compaction 中间件可基于 token 预算精准触发
        setContextWindowSize(resolveContextWindowSize(modelName));
    }

    /**
     * 根据模型名称解析上下文窗口大小（token）。
     *
     * @param name 模型名称
     * @return 对应窗口大小，未知模型回退到 {@link #DEFAULT_CONTEXT_WINDOW_SIZE}
     */
    private static int resolveContextWindowSize(String name) {
        Integer size = name != null ? MODEL_CONTEXT_WINDOWS.get(name) : null;
        return size != null ? size : DEFAULT_CONTEXT_WINDOW_SIZE;
    }

    /**
     * 调用百度千帆对话 API。
     *
     * <p>
     * 默认开启 SSE 真流式：逐行解析 {@code data: {...}} 事件，每个增量片段发射一个
     * {@link ChatResponse}，末片携带 {@code finish_reason} 与 {@code usage}。仅当调用方
     * 显式 {@code GenerateOptions.stream=false} 时才退化为一次性返回完整结果。
     * </p>
     *
     * @param messages 消息列表
     * @param tools    工具 Schema 列表（当前未使用）
     * @param options  生成选项
     * @return ChatResponse 流
     */
    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        GenerateOptions effective = options != null ? options : defaultOptions;
        // 默认开启流式；仅当调用方显式关闭时才退化为单次返回
        boolean stream = effective.getStream() == null || effective.getStream();
        log.info("Baidu chat request: {}, model: {}, stream: {}", messages, modelName, stream);

        return Mono.fromCallable(() -> getAccessToken())
                .flatMapMany(token -> stream
                        ? streamBaidu(messages, token, effective)
                        : Flux.defer(() -> Flux.just(nonStreamBaidu(messages, token, effective))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 非流式调用：一次性返回完整结果（兼容显式关闭流式的场景）。
     */
    private ChatResponse nonStreamBaidu(List<Msg> messages, String token, GenerateOptions options) {
        try {
            Map<String, Object> requestBody = buildRequestBody(messages, token, options, false);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(CHAT_URL)
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("Baidu API error: " + response.code() + " - " + responseBody);
            }

            JsonNode jsonNode = objectMapper.readTree(responseBody);
            String content = jsonNode.path("result").asText();
            TextBlock textBlock = TextBlock.builder().text(content).build();
            return ChatResponse.builder()
                    .content(List.of(textBlock))
                    .usage(parseBaiduUsage(jsonNode))
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Baidu API call failed", e);
        }
    }

    /**
     * 流式调用：按 SSE 逐行解析，每个增量片段发射一个 ChatResponse。
     */
    private Flux<ChatResponse> streamBaidu(List<Msg> messages, String token, GenerateOptions options) {
        return Flux.create(sink -> {
            try {
                Map<String, Object> requestBody = buildRequestBody(messages, token, options, true);
                String jsonBody = objectMapper.writeValueAsString(requestBody);

                Request request = new Request.Builder()
                        .url(CHAT_URL)
                        .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                        .build();

                Response response = httpClient.newCall(request).execute();
                if (!response.isSuccessful()) {
                    sink.error(new RuntimeException(
                            "Baidu API error: " + response.code() + " - " + response.body().string()));
                    return;
                }

                try (ResponseBody body = response.body()) {
                    BufferedSource source = body.source();
                    while (!source.exhausted()) {
                        String line = source.readUtf8Line();
                        if (line == null) {
                            break;
                        }
                        line = line.trim();
                        // 跳过空行与非 data 行（如 SSE 注释、心跳）
                        if (line.isEmpty() || !line.startsWith("data:")) {
                            continue;
                        }
                        String data = line.substring(5).trim();
                        if ("[DONE]".equals(data)) {
                            break;
                        }
                        JsonNode node = objectMapper.readTree(data);
                        boolean isEnd = node.path("is_end").asBoolean(false);
                        String delta = node.path("result").asText(null);
                        String finishReason = node.path("finish_reason").asText(null);
                        if (delta == null && !isEnd) {
                            // 增量内容为空且非结束事件，跳过
                            continue;
                        }
                        ChatResponse.Builder crBuilder = ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text(delta == null ? "" : delta).build()));
                        if (isEnd) {
                            if (finishReason != null) {
                                crBuilder.finishReason(finishReason);
                            }
                            crBuilder.usage(parseBaiduUsage(node));
                        }
                        sink.next(crBuilder.build());
                        if (isEnd) {
                            break;
                        }
                    }
                    sink.complete();
                }
            } catch (Exception e) {
                log.error("Baidu streaming failed", e);
                sink.error(e);
            }
        });
    }

    /**
     * 解析百度响应中的 token 用量（流式末片与非流式响应均可能携带）。
     *
     * @param node 已解析的响应 JSON 节点
     * @return 用量对象，缺失时为 null
     */
    private ChatUsage parseBaiduUsage(JsonNode node) {
        JsonNode usage = node.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        return ChatUsage.builder()
                .inputTokens(usage.path("prompt_tokens").asInt(0))
                .outputTokens(usage.path("completion_tokens").asInt(0))
                .time(usage.path("time").asDouble(0.0))
                .build();
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
     * 获取百度 Access Token，使用双重检查锁定确保线程安全。
     *
     * <p>
     * Token 刷新策略：
     * 1. 快速路径：检查 token 是否存在且未过期，未过期直接返回
     * 2. 慢速路径：加锁后再次检查，确认需要刷新才发起 HTTP 请求
     * 3. 提前 1 小时刷新，避免使用即将过期的 token
     * </p>
     *
     * @return 有效的 access_token
     * @throws IOException HTTP 请求失败时抛出
     */
    private String getAccessToken() throws IOException {
        // 快速路径：token 存在且未过期
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return accessToken;
        }

        // 慢速路径：加锁后双重检查
        synchronized (this) {
            if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
                return accessToken;
            }

            // 构建 token 请求 URL
            String url = TOKEN_URL + "?grant_type=client_credentials&client_id=" + apiKey + "&client_secret="
                    + secretKey;
            Request request = new Request.Builder().url(url).get().build();

            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                throw new RuntimeException("Failed to get Baidu access token: " + response.code());
            }

            // 解析 token 和过期时间
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            accessToken = jsonNode.path("access_token").asText();
            int expiresIn = jsonNode.path("expires_in").asInt(2592000); // 默认30天
            // 提前1小时刷新，避免使用即将过期的 token
            tokenExpireTime = System.currentTimeMillis() + (expiresIn - 3600) * 1000L;

            log.info("Baidu access token refreshed, expires in {} seconds", expiresIn);
            return accessToken;
        }
    }

    /**
     * 构建百度 API 请求体，将内部消息格式转换为百度格式。
     *
     * <p>
     * 百度千帆 API 请求体包含：
     * - access_token：认证令牌
     * - messages：消息列表，每条消息包含 role 和 content
     * - temperature：生成温度，默认 0.7
     * - top_p：核采样概率阈值，默认 0.8
     * </p>
     *
     * @param messages 消息列表
     * @param token    Access Token
     * @param options  生成选项（为 null 时使用 defaultOptions）
     * @return 请求体 Map
     */
    private Map<String, Object> buildRequestBody(List<Msg> messages, String token, GenerateOptions options, boolean stream) {
        Map<String, Object> body = new ConcurrentHashMap<>();
        body.put("access_token", token);
        if (stream) {
            body.put("stream", true);
        }

        // 将 MsgRole 映射为百度消息格式
        body.put("messages", messages.stream()
                .map(msg -> {
                    Map<String, Object> msgMap = new HashMap<>();
                    msgMap.put("role", mapBaiduRole(msg.getRole()));
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
            body.put("top_p", 0.8);
        }

        return body;
    }

    /**
     * 将 MsgRole 枚举映射为百度 API 要求的角色字符串。
     *
     * <p>
     * 百度千帆仅支持 user / assistant 两种角色，
     * 其他类型（如 system、tool）统一映射为 user。
     * </p>
     *
     * @param role 内部消息角色
     * @return 百度 API 角色字符串
     */
    private String mapBaiduRole(MsgRole role) {
        return switch (role) {
            case ASSISTANT -> "assistant";
            case TOOL -> "user"; // 百度没有 tool 角色，映射为 user
            default -> "user"; // SYSTEM / USER 统一为 user
        };
    }
}
