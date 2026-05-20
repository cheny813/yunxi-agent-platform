package io.yunxi.platform.framework.tool.impl;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolExecutionException;
import io.yunxi.platform.framework.tool.ToolInput;
import io.yunxi.platform.framework.tool.ToolResult;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

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
public class HttpTool implements Tool {

    /** OkHttp 客户端 */
    private final OkHttpClient httpClient;

    /**
     * 构造 HTTP 请求工具
     */
    public HttpTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String getName() {
        return "http_request";
    }

    @Override
    public String getDescription() {
        return "发送 HTTP 请求，获取网页内容或调用 API";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "url": {
                            "type": "string",
                            "description": "请求 URL"
                        },
                        "method": {
                            "type": "string",
                            "enum": ["GET", "POST", "PUT", "DELETE"],
                            "default": "GET",
                            "description": "HTTP 方法"
                        },
                        "headers": {
                            "type": "object",
                            "description": "请求头",
                            "additionalProperties": {"type": "string"}
                        },
                        "body": {
                            "type": "string",
                            "description": "请求体（POST/PUT 时使用）"
                        },
                        "timeout": {
                            "type": "integer",
                            "default": 30,
                            "description": "超时时间（秒）"
                        }
                    },
                    "required": ["url"]
                }
                """;
    }

    @Override
    public ToolResult execute(ToolInput input) throws ToolExecutionException {
        long startTime = System.currentTimeMillis();

        try {
            // 获取参数
            String url = input.getString("url");
            String method = input.getString("method", "GET");

            // 获取 headers 参数
            @SuppressWarnings("unchecked")
            Map<String, String> headers = input.hasParameter("headers")
                    ? (Map<String, String>) input.getParameters().get("headers")
                    : new HashMap<>();

            String body = input.getString("body");

            log.info("HTTP 请求: {} {}", method, url);

            // 构建请求
            Request.Builder builder = new Request.Builder().url(url);

            // 添加请求头
            if (!headers.isEmpty()) {
                headers.forEach(builder::addHeader);
            }

            // 设置方法
            switch (method.toUpperCase()) {
                case "POST" -> builder.post(body != null ? RequestBody.create(body, MediaType.parse("application/json"))
                        : RequestBody.create("", null));
                case "PUT" -> builder.put(body != null ? RequestBody.create(body, MediaType.parse("application/json"))
                        : RequestBody.create("", null));
                case "DELETE" -> builder.delete();
                default -> builder.get();
            }

            // 发送请求
            try (Response response = httpClient.newCall(builder.build()).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.warn("HTTP 请求失败: {} - {}", response.code(), response.message());
                    return ToolResult.error(String.format("HTTP 请求失败: %d - %s, 响应: %s",
                            response.code(), response.message(), responseBody));
                }

                log.info("HTTP 请求成功: {} {} ({} ms)", method, url,
                        System.currentTimeMillis() - startTime);

                // 构建结果
                Map<String, Object> result = new HashMap<>();
                result.put("statusCode", response.code());
                result.put("statusMessage", response.message());
                result.put("headers", response.headers().toMultimap());
                result.put("body", responseBody);
                result.put("size", responseBody.length());

                ToolResult toolResult = ToolResult.success(result);
                toolResult.setDurationMs(System.currentTimeMillis() - startTime);
                return toolResult;
            }

        } catch (Exception e) {
            log.error("HTTP 请求异常", e);
            throw new ToolExecutionException(getName(), "执行 HTTP 请求失败", e);
        }
    }
}
