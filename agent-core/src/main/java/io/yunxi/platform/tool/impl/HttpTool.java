package io.yunxi.platform.tool.impl;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * HTTP 请求工具
 * <p>
 * 用于 Agent 调用外部 HTTP API 和获取网页内容
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class HttpTool {

    private final OkHttpClient httpClient;

    /**
     * 创建 HTTP 请求工具，初始化带有连接/读写超时的 OkHttpClient。
     */
    public HttpTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Tool(name = "http_request", description = "发送 HTTP 请求，获取网页内容或调用 API")
    public String request(
            @ToolParam(name = "url", description = "请求 URL") String url,
            @ToolParam(name = "method", description = "HTTP 方法: GET/POST/PUT/DELETE，默认 GET") String method,
            @ToolParam(name = "body", description = "请求体（POST/PUT 时使用）") String body) {
        long startTime = System.currentTimeMillis();
        try {
            if (method == null || method.isBlank())
                method = "GET";

            Request.Builder builder = new Request.Builder().url(url);

            switch (method.toUpperCase()) {
                case "POST" -> builder.post(body != null ? RequestBody.create(body, MediaType.parse("application/json"))
                        : RequestBody.create("", null));
                case "PUT" -> builder.put(body != null ? RequestBody.create(body, MediaType.parse("application/json"))
                        : RequestBody.create("", null));
                case "DELETE" -> builder.delete();
                default -> builder.get();
            }

            try (Response response = httpClient.newCall(builder.build()).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    return String.format("HTTP 请求失败: %d - %s", response.code(), response.message());
                }
                log.info("HTTP 请求成功: {} {} ({} ms)", method, url, System.currentTimeMillis() - startTime);
                return responseBody;
            }
        } catch (Exception e) {
            log.error("HTTP 请求异常", e);
            return "执行 HTTP 请求失败: " + e.getMessage();
        }
    }
}
