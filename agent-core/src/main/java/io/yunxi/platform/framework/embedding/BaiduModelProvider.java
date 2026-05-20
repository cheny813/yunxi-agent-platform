package io.yunxi.platform.framework.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 百度文心一言模型提供商实现
 * <p>
 * 使用百度千帆大模型平台API
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class BaiduModelProvider implements ChatModelProvider {

    /** 日志记录器 */
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
    /** HTTP 客户端 */
    private final OkHttpClient httpClient;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;
    /** 百度 Access Token 缓存 */
    private String accessToken;
    /** Access Token 过期时间（毫秒时间戳） */
    private volatile long tokenExpireTime;

    /**
     * 构造百度模型提供商
     *
     * @param config 模型配置，apiKey 字段填 API Key，modelName 字段填 Secret Key
     */
    public BaiduModelProvider(ModelConfig config) {
        this.apiKey = config.getApiKey(); // API Key
        this.secretKey = config.getModelName(); // Secret Key (从modelName字段获取)
        this.modelName = "ernie-bot-turbo"; // 百度默认模型
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
                        // 构建请求
                        Map<String, Object> requestBody = buildRequestBody(messages, token);
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

                        // 构造返回消息 - 使用 TextBlock
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
     * 获取 access_token
     */
    private String getAccessToken() throws IOException {
        // 检查token是否过期
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return accessToken;
        }

        synchronized (this) {
            // 双重检查
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
     * 构建请求体
     */
    private Map<String, Object> buildRequestBody(List<Msg> messages, String token) {
        Map<String, Object> body = new ConcurrentHashMap<>();
        body.put("access_token", token);

        // 百度 API 消息格式
        body.put("messages", messages.stream()
                .map(msg -> Map.of(
                        "role", "user",
                        "content", msg.getTextContent()))
                .toList());

        // 添加模型参数
        body.put("temperature", 0.7);
        body.put("top_p", 0.8);

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
     * @return "baidu"
     */
    @Override
    public String getProvider() {
        return "baidu";
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
