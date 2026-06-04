package io.yunxi.platform.framework.model;

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
 * 百度文心一言模型实现
 * <p>
 * 使用百度千帆大模型平台 API。百度使用 OAuth 2.0 access_token 认证，
 * 不兼容 OpenAI 协议，因此保留自建 HTTP 实现。
 * 已修复 role 映射：根据 {@link MsgRole} 正确映射角色。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class BaiduModelProvider implements Model {

    private static final Logger log = LoggerFactory.getLogger(BaiduModelProvider.class);

    /** 百度 Access Token 获取地址 */
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    /** 百度聊天补全 API 地址 */
    private static final String CHAT_URL = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions";

    /** API Key */
    private final String apiKey;
    /** Secret Key */
    private final String secretKey;
    /** 模型名称 */
    private final String modelName;
    /** 默认生成参数 */
    private final GenerateOptions defaultOptions;
    /** HTTP 客户端 */
    private final OkHttpClient httpClient;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;
    /** 百度 Access Token 缓存 */
    private String accessToken;
    /** Access Token 过期时间（毫秒时间戳） */
    private volatile long tokenExpireTime;

    /**
     * 构造百度模型
     *
     * @param apiKey         百度 API Key
     * @param secretKey      百度 Secret Key
     * @param modelName      模型名称（如 ernie-bot-turbo）
     * @param defaultOptions 默认生成参数
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

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        log.info("Baidu chat request: {}, model: {}", messages, modelName);

        return Mono.fromCallable(() -> getAccessToken())
                .flatMapMany(token -> {
                    try {
                        Map<String, Object> requestBody = buildRequestBody(messages, token, options);
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

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 获取 access_token（带缓存和双重检查锁）
     */
    private String getAccessToken() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return accessToken;
        }

        synchronized (this) {
            if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
                return accessToken;
            }

            String url = TOKEN_URL + "?grant_type=client_credentials&client_id=" + apiKey + "&client_secret="
                    + secretKey;
            Request request = new Request.Builder().url(url).get().build();

            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                throw new RuntimeException("Failed to get Baidu access token: " + response.code());
            }

            JsonNode jsonNode = objectMapper.readTree(responseBody);
            accessToken = jsonNode.path("access_token").asText();
            int expiresIn = jsonNode.path("expires_in").asInt(2592000); // 默认30天
            tokenExpireTime = System.currentTimeMillis() + (expiresIn - 3600) * 1000L; // 提前1小时过期

            log.info("Baidu access token refreshed, expires in {} seconds", expiresIn);
            return accessToken;
        }
    }

    /**
     * 构建请求体（百度格式），修复 role 映射
     */
    private Map<String, Object> buildRequestBody(List<Msg> messages, String token, GenerateOptions options) {
        Map<String, Object> body = new ConcurrentHashMap<>();
        body.put("access_token", token);

        // 根据 MsgRole 正确映射百度角色
        body.put("messages", messages.stream()
                .map(msg -> {
                    Map<String, Object> msgMap = new HashMap<>();
                    // 修复：根据 MsgRole 映射，不再硬编码 "user"
                    msgMap.put("role", mapBaiduRole(msg.getRole()));
                    msgMap.put("content", msg.getTextContent());
                    return msgMap;
                })
                .toList());

        // 合并生成参数：优先使用调用时传入的 options，否则用 defaultOptions
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
     * 将框架 MsgRole 映射到百度 API 角色字符串
     * <p>
     * 百度千帆平台支持：user / assistant
     * 注意：百度目前不支持 system 角色，system 消息合并到 user 中
     * </p>
     */
    private String mapBaiduRole(MsgRole role) {
        return switch (role) {
            case ASSISTANT -> "assistant";
            case TOOL -> "user"; // 百度不支持 tool，回退到 user
            default -> "user"; // SYSTEM / USER 统一走 user
        };
    }
}
