package io.yunxi.platform.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import okhttp3.*;
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
 * @author yunxi-agent-platform
 */
public class BaiduModelProvider implements Model {

    /** 类级别日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(BaiduModelProvider.class);

    /** 百度 Access Token 获取地址 */
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";

    /** 百度千帆对话 API 地址 */
    private static final String CHAT_URL = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions";

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
    }

    /**
     * 流式调用百度千帆对话 API。
     *
     * <p>
     * 当前实现为伪流式（一次性返回完整结果），因为百度千帆部分模型
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
        log.info("Baidu chat request: {}, model: {}", messages, modelName);

        return Mono.fromCallable(() -> getAccessToken())
                .flatMapMany(token -> {
                    try {
                        // 构建请求体并序列化为 JSON
                        Map<String, Object> requestBody = buildRequestBody(messages, token, options);
                        String jsonBody = objectMapper.writeValueAsString(requestBody);

                        // 构建 HTTP 请求
                        Request request = new Request.Builder()
                                .url(CHAT_URL)
                                .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                                .build();

                        // 执行 HTTP 调用
                        Response response = httpClient.newCall(request).execute();
                        String responseBody = response.body().string();

                        if (!response.isSuccessful()) {
                            throw new RuntimeException("Baidu API error: " + response.code() + " - " + responseBody);
                        }

                        // 解析响应，提取文本内容
                        JsonNode jsonNode = objectMapper.readTree(responseBody);
                        String content = jsonNode.path("result").asText();

                        TextBlock textBlock = TextBlock.builder().text(content).build();
                        ChatResponse chatResponse = ChatResponse.builder()
                                .content(List.of(textBlock))
                                .build();

                        return Flux.just(chatResponse);

                    } catch (IOException e) {
                        log.error("Baidu API call failed", e);
                        return Flux.error(e);
                    }
                })
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
    private Map<String, Object> buildRequestBody(List<Msg> messages, String token, GenerateOptions options) {
        Map<String, Object> body = new ConcurrentHashMap<>();
        body.put("access_token", token);

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
