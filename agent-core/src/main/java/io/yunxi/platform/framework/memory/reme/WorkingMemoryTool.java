package io.yunxi.platform.framework.memory.reme;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Agent 可调用的工作记忆压缩 Tool
 *
 * <p>封装 ReMe 的工作记忆端点，当上下文过长时，Agent 可自主调用此工具进行压缩。</p>
 *
 * <ul>
 * <li>{@code POST /summary_working_memory} — 压缩工作记忆</li>
 * </ul>
 */
@Slf4j
@Component
public class WorkingMemoryTool {

    private final String apiBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WorkingMemoryTool() {
        this.apiBaseUrl = "http://localhost:8002";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Tool(description = "Compress and summarize working memory to reduce token usage when the conversation context is too long. Use this when you notice the context is becoming very large")
    public String summaryWorkingMemory(
            @ToolParam(name = "messages", description = "The conversation messages or content to compress and summarize") String messages) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "messages", messages,
                    "working_summary_mode", "auto"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/summary_working_memory"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
                Object answer = body.get("answer");
                if (answer != null && !answer.toString().isEmpty()) {
                    return answer.toString();
                }
                return "Working memory compressed successfully.";
            }
            return "Working memory compression failed with status: " + response.statusCode();
        } catch (Exception e) {
            log.warn("Working memory compression failed: {}", e.getMessage());
            return "Failed to compress working memory: " + e.getMessage();
        }
    }
}