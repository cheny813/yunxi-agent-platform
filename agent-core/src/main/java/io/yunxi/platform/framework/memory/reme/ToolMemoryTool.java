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
import java.util.List;
import java.util.Map;

/**
 * Agent 可调用的工具记忆 Tool
 *
 * <p>封装 ReMe 的工具记忆端点，让 Agent 能自主检索工具使用经验和记录工具调用结果。</p>
 *
 * <ul>
 * <li>{@code POST /retrieve_tool_memory} — 检索工具记忆</li>
 * <li>{@code POST /add_tool_call_result} — 记录工具调用结果</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolMemoryTool {

    private final String apiBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ToolMemoryTool() {
        this.apiBaseUrl = "http://localhost:8002";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Tool(description = "Retrieve tool usage patterns, best practices, and past experiences for a specific tool")
    public String retrieveToolMemory(
            @ToolParam(name = "toolName", description = "The tool name to look up usage patterns for") String toolName) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "tool_names", toolName));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/retrieve_tool_memory"))
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
                return "No tool memory found for: " + toolName;
            }
            return "Tool memory retrieval failed with status: " + response.statusCode();
        } catch (Exception e) {
            log.warn("Tool memory retrieval failed: {}", e.getMessage());
            return "Failed to retrieve tool memory: " + e.getMessage();
        }
    }

    @Tool(description = "Record a tool call result for learning and improvement, so the agent can remember which tools work well in which situations")
    public String addToolCallResult(
            @ToolParam(name = "toolName", description = "The name of the tool that was called") String toolName,
            @ToolParam(name = "input", description = "The input/parameters passed to the tool") String input,
            @ToolParam(name = "output", description = "The output/result returned by the tool") String output,
            @ToolParam(name = "success", description = "Whether the tool call was successful") boolean success) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "tool_call_results", List.of(Map.of(
                            "tool_name", toolName,
                            "input", input,
                            "output", output,
                            "success", success,
                            "time_cost", 0,
                            "token_cost", 0,
                            "create_time", System.currentTimeMillis()))));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/add_tool_call_result"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return "Tool call result recorded successfully for: " + toolName;
            }
            return "Tool call result recording failed with status: " + response.statusCode();
        } catch (Exception e) {
            log.warn("Tool call result recording failed: {}", e.getMessage());
            return "Failed to record tool call result: " + e.getMessage();
        }
    }
}