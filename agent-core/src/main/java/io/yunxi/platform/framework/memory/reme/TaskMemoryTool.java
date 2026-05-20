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
 * Agent 可调用的任务记忆 Tool
 *
 * <p>封装 ReMe 的任务记忆端点，让 Agent 能自主检索和存储任务执行经验。</p>
 *
 * <ul>
 * <li>{@code POST /retrieve_task_memory} — 检索任务记忆</li>
 * <li>{@code POST /summary_task_memory} — 存储任务记忆</li>
 * </ul>
 */
@Slf4j
@Component
public class TaskMemoryTool {

    private final String apiBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TaskMemoryTool() {
        this.apiBaseUrl = "http://localhost:8002";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Tool(description = "Retrieve relevant task memories based on the current query to learn from past task execution experiences")
    public String retrieveTaskMemory(
            @ToolParam(name = "query", description = "The search query for finding relevant task memories") String query) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "workspace_id", "default",
                    "query", query,
                    "top_k", 5));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/retrieve_task_memory"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
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
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) body.get("metadata");
                if (metadata != null && metadata.get("memory_list") != null) {
                    return response.body();
                }
                return "No relevant task memories found.";
            }
            return "Task memory retrieval failed with status: " + response.statusCode();
        } catch (Exception e) {
            log.warn("Task memory retrieval failed: {}", e.getMessage());
            return "Failed to retrieve task memories: " + e.getMessage();
        }
    }

    @Tool(description = "Store task execution experience as memory for future reference and learning")
    public String storeTaskMemory(
            @ToolParam(name = "content", description = "The task memory content to store, describing what was learned") String content) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "workspace_id", "default",
                    "content", content));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/summary_task_memory"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return "Task memory stored successfully.";
            }
            return "Task memory storage failed with status: " + response.statusCode();
        } catch (Exception e) {
            log.warn("Task memory storage failed: {}", e.getMessage());
            return "Failed to store task memory: " + e.getMessage();
        }
    }
}