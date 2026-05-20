package io.yunxi.platform.framework.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP工具工厂
 * <p>
 * 创建AgentScope AgentTool接口的MCP工具实现
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class McpToolFactory {

    /** HTTP 请求模板 */
    private final RestTemplate restTemplate;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /** 最大重试次数（纯计算工具无需重试，网络抖动由MCP服务器自身处理） */
    private static final int MAX_RETRIES = 1;

    /** 缓存容量上限 */
    private static final int CACHE_MAX_SIZE = 500;

    /** 缓存过期时间（毫秒）：10 分钟 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000;

    /**
     * MCP 工具结果缓存
     * key: toolName + "|" + 输入参数 hash
     * value: CacheEntry（含过期时间）
     * 仅对只读工具生效
     */
    private final Map<String, CacheEntry> toolResultCache = new ConcurrentHashMap<>();

    /** 允许缓存的工具名称（只读操作），可通过 application.yml 配置 */
    private final Set<String> cacheableTools;

    /** 通用只读工具默认值（框架层通用） */
    private static final Set<String> DEFAULT_CACHEABLE_TOOLS = Set.of(
            "query", "list_tables", "describe_table");

    /**
     * 构造函数
     *
     * @param configuredTools 可缓存的工具名称列表（从配置读取）
     */
    public McpToolFactory(
            @Value("${mcp.cacheable-tools:}") List<String> configuredTools) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        Set<String> tools = new HashSet<>(DEFAULT_CACHEABLE_TOOLS);
        if (configuredTools != null && !configuredTools.isEmpty()) {
            tools.addAll(configuredTools);
        }
        this.cacheableTools = Set.copyOf(tools);
        log.info("MCP 缓存工具列表: {}", this.cacheableTools);
    }

    /**
     * 创建MCP工具
     *
     * @param serverName   MCP服务器名称
     * @param serverConfig MCP服务器配置
     * @param toolName     工具名称
     * @param description  工具描述
     * @param inputSchema  工具参数Schema（从MCP服务器动态获取）
     * @return AgentTool实例
     */
    public AgentTool createTool(String serverName, AgentscopeCoreProperties.McpServerConfig serverConfig,
            String toolName, String description, Map<String, Object> inputSchema) {
        String url = serverConfig.getUrl();
        String serverType = serverConfig.getType() != null ? serverConfig.getType() : "http";

        // SSE 模式：将 /sse 替换为 /message
        if ("sse".equalsIgnoreCase(serverType) && url.endsWith("/sse")) {
            url = url.replace("/sse", "/message");
        }

        final String finalUrl = url;
        final String finalServerType = serverType;
        final Map<String, Object> finalInputSchema = inputSchema;

        return new AgentTool() {
            @Override
            public String getName() {
                return serverName + "_" + toolName;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public Map<String, Object> getParameters() {
                // 优先使用从MCP服务器动态获取的参数Schema
                if (finalInputSchema != null && !finalInputSchema.isEmpty()) {
                    log.debug("使用动态获取的参数Schema: toolName={}", toolName);
                    log.debug("参数Schema详情: {}", finalInputSchema);
                    return finalInputSchema;
                }

                // 兜底：使用硬编码的Redis工具参数（向后兼容）
                log.debug("未找到工具参数Schema，使用兜底参数定义: toolName={}", toolName);
                return getFallbackToolParameters(toolName, finalServerType);
            }

            /**
             * 获取工具的完整信息（用于调试和日志）
             * 展示大模型"看到"的工具信息
             */
            public Map<String, Object> getToolMetadata() {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("name", getName());
                metadata.put("description", getDescription());
                metadata.put("parameters", getParameters());
                metadata.put("serverName", serverName);
                metadata.put("toolName", toolName);
                metadata.put("hasDynamicSchema", finalInputSchema != null && !finalInputSchema.isEmpty());
                return metadata;
            }

            /**
             * 兜底的工具参数定义（向后兼容Redis MCP服务器）
             * 建议：自研MCP服务器应在 tools/list 响应中提供完整的 inputSchema
             */
            private Map<String, Object> getFallbackToolParameters(String toolName, String serverType) {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");
                schema.put("required", List.of());

                Map<String, Object> properties = new HashMap<>();

                switch (toolName) {
                    case "set": {
                        Map<String, Object> keyParam = new HashMap<>();
                        keyParam.put("type", "string");
                        keyParam.put("description", "Redis key");
                        properties.put("key", keyParam);

                        Map<String, Object> valueParam = new HashMap<>();
                        valueParam.put("type", "string");
                        valueParam.put("description", "Value to set");
                        properties.put("value", valueParam);

                        Map<String, Object> ttlParam = new HashMap<>();
                        ttlParam.put("type", "integer");
                        ttlParam.put("description", "Time to live in seconds (optional)");
                        properties.put("ttl", ttlParam);

                        schema.put("required", List.of("key", "value"));
                        break;
                    }

                    case "get": {
                        Map<String, Object> keyParam = new HashMap<>();
                        keyParam.put("type", "string");
                        keyParam.put("description", "Redis key to retrieve");
                        properties.put("key", keyParam);
                        schema.put("required", List.of("key"));
                        break;
                    }

                    case "delete": {
                        Map<String, Object> keyParam = new HashMap<>();
                        keyParam.put("type", "string");
                        keyParam.put("description", "Redis key to delete");
                        properties.put("key", keyParam);

                        Map<String, Object> keysParam = new HashMap<>();
                        keysParam.put("type", "array");
                        keysParam.put("description", "Array of keys to delete (alternative to single key)");
                        Map<String, String> items = new HashMap<>();
                        items.put("type", "string");
                        keysParam.put("items", items);
                        properties.put("keys", keysParam);
                        break;
                    }

                    case "keys": {
                        Map<String, Object> patternParam = new HashMap<>();
                        patternParam.put("type", "string");
                        patternParam.put("description", "Pattern to match keys (e.g., '*', 'user:*')");
                        properties.put("pattern", patternParam);

                        Map<String, Object> countParam = new HashMap<>();
                        countParam.put("type", "integer");
                        countParam.put("description", "Maximum number of keys to return (optional)");
                        properties.put("count", countParam);

                        schema.put("required", List.of("pattern"));
                        break;
                    }

                    case "list_ops": {
                        Map<String, Object> operationParam = new HashMap<>();
                        operationParam.put("type", "string");
                        operationParam.put("description", "List operation: push, pop, range, len, etc.");
                        operationParam.put("enum",
                                List.of("push", "pop", "range", "len", "lpush", "rpush", "lpop", "rpop"));
                        properties.put("operation", operationParam);

                        Map<String, Object> keyParam = new HashMap<>();
                        keyParam.put("type", "string");
                        keyParam.put("description", "Redis list key");
                        properties.put("key", keyParam);

                        Map<String, Object> valueParam = new HashMap<>();
                        valueParam.put("type", "string");
                        valueParam.put("description", "Value for push operations (optional)");
                        properties.put("value", valueParam);

                        Map<String, Object> startParam = new HashMap<>();
                        startParam.put("type", "integer");
                        startParam.put("description", "Start index for range (optional, default 0)");
                        properties.put("start", startParam);

                        Map<String, Object> stopParam = new HashMap<>();
                        stopParam.put("type", "integer");
                        stopParam.put("description", "Stop index for range (optional, default -1)");
                        properties.put("stop", stopParam);

                        schema.put("required", List.of("operation", "key"));
                        break;
                    }

                    case "hash_ops": {
                        Map<String, Object> operationParam = new HashMap<>();
                        operationParam.put("type", "string");
                        operationParam.put("description", "Hash operation: get, set, delete, keys, values, etc.");
                        operationParam.put("enum",
                                List.of("get", "set", "delete", "keys", "values", "exists", "hgetall"));
                        properties.put("operation", operationParam);

                        Map<String, Object> keyParam = new HashMap<>();
                        keyParam.put("type", "string");
                        keyParam.put("description", "Redis hash key");
                        properties.put("key", keyParam);

                        Map<String, Object> fieldParam = new HashMap<>();
                        fieldParam.put("type", "string");
                        fieldParam.put("description", "Hash field (for get/set/delete operations)");
                        properties.put("field", fieldParam);

                        Map<String, Object> valueParam = new HashMap<>();
                        valueParam.put("type", "string");
                        valueParam.put("description", "Value for set operation (optional)");
                        properties.put("value", valueParam);

                        schema.put("required", List.of("operation", "key"));
                        break;
                    }

                    default: {
                        Map<String, Object> argsParam = new HashMap<>();
                        argsParam.put("type", "object");
                        argsParam.put("description", "Tool arguments as key-value pairs");
                        properties.put("args", argsParam);
                        break;
                    }
                }

                schema.put("properties", properties);
                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                // 尝试从缓存获取
                ToolResultBlock cached = getFromCache(toolName, param.getInput());
                if (cached != null) {
                    log.debug("MCP工具命中缓存: {}", toolName);
                    return Mono.just(cached);
                }

                return Mono.fromCallable(() -> callWithRetry(param, finalUrl, finalServerType))
                        // 失败重试（最多3次，指数退避）
                        .retryWhen(reactor.util.retry.Retry.backoff(MAX_RETRIES,
                                java.time.Duration.ofMillis(500))
                                .maxBackoff(java.time.Duration.ofSeconds(5))
                                .doBeforeRetry(signal -> log.warn("MCP工具调用失败，第{}次重试: {}",
                                        signal.totalRetries() + 1, toolName)));
            }

            /**
             * 带重试的 MCP 工具调用（实际执行）
             */
            private ToolResultBlock callWithRetry(ToolCallParam param, String url, String serverType) throws Exception {
                String fullToolName = getName();
                log.info("调用MCP工具: {}, URL: {}, 模式: {}, input: {}", fullToolName, url, serverType,
                        param.getInput());

                try {
                    Map<String, Object> request = new HashMap<>();
                    request.put("jsonrpc", "2.0");
                    request.put("id", System.currentTimeMillis());
                    request.put("method", "tools/call");

                    Map<String, Object> params = new HashMap<>();
                    params.put("name", toolName);
                    params.put("arguments", param.getInput());
                    request.put("params", params);

                    log.debug("MCP请求体: {}", objectMapper.writeValueAsString(request));

                    ToolResultBlock result;
                    if ("sse".equalsIgnoreCase(serverType)) {
                        String sseResponse = restTemplate.postForObject(url, request, String.class);
                        log.debug("SSE原始响应: {}", sseResponse);

                        if (sseResponse != null && sseResponse.contains("data: ")) {
                            int dataIndex = sseResponse.indexOf("data: ");
                            String jsonResponse = sseResponse.substring(dataIndex + 6).trim();
                            jsonResponse = jsonResponse.split("\n")[0];

                            @SuppressWarnings("unchecked")
                            Map<String, Object> response = objectMapper.readValue(jsonResponse, Map.class);
                            result = parseMcpResponse(fullToolName, response);
                        } else {
                            result = ToolResultBlock.error("SSE响应格式错误: " + sseResponse);
                        }
                    } else {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
                        log.info("MCP工具调用完成: {}, 响应: {}", fullToolName, response);
                        result = parseMcpResponse(fullToolName, response);
                    }

                    // 成功结果写入缓存
                    putToCache(toolName, param.getInput(), result);
                    return result;

                } catch (Exception e) {
                    log.error("调用MCP工具失败: {}, URL: {}", fullToolName, url, e);
                    throw new RuntimeException("调用MCP工具失败: " + e.getMessage(), e);
                }
            }

            /**
             * 解析 MCP 响应并返回 ToolResultBlock
             */
            private ToolResultBlock parseMcpResponse(String fullToolName, Map<String, Object> response) {
                if (response == null) {
                    return ToolResultBlock.error("响应为空");
                }

                if (response.containsKey("error")) {
                    Object error = response.get("error");
                    String errorMsg = error != null ? error.toString() : "未知错误";
                    log.error("MCP工具调用错误: {} - {}", fullToolName, errorMsg);
                    return ToolResultBlock.error(errorMsg);
                }

                if (response.containsKey("result")) {
                    Object result = response.get("result");

                    if (result instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) result;

                        if (resultMap.containsKey("content")) {
                            Object content = resultMap.get("content");
                            if (content instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> contentList = (List<Map<String, Object>>) content;
                                if (!contentList.isEmpty()) {
                                    Map<String, Object> firstContent = contentList.get(0);
                                    if ("text".equals(firstContent.get("type"))) {
                                        String textContent = (String) firstContent.get("text");
                                        log.debug("MCP工具返回内容: {}", textContent);
                                        return ToolResultBlock.text(textContent);
                                    }
                                }
                            }
                        }

                        if (Boolean.TRUE.equals(resultMap.get("isError"))) {
                            String errorMsg = resultMap.containsKey("content") ? resultMap.get("content").toString()
                                    : "MCP工具执行错误";
                            return ToolResultBlock.error(errorMsg);
                        }
                    }

                    try {
                        String resultStr = result != null ? objectMapper.writeValueAsString(result) : "success";
                        return ToolResultBlock.text(resultStr);
                    } catch (Exception e) {
                        return ToolResultBlock.text(result != null ? result.toString() : "success");
                    }
                }

                try {
                    return ToolResultBlock.text(objectMapper.writeValueAsString(response));
                } catch (Exception e) {
                    return ToolResultBlock.text(response.toString());
                }
            }
        };
    }

    // ==================== 缓存方法 ====================

    /**
     * 从缓存中获取工具结果
     *
     * @param toolName 工具名称
     * @param input    工具输入参数
     * @return 缓存的工具结果，未命中返回 null
     */
    private ToolResultBlock getFromCache(String toolName, Map<String, Object> input) {
        if (!cacheableTools.contains(toolName)) {
            return null;
        }
        String cacheKey = buildCacheKey(toolName, input);
        CacheEntry entry = toolResultCache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return entry.result;
        }
        if (entry != null) {
            toolResultCache.remove(cacheKey);
        }
        return null;
    }

    /**
     * 将工具结果写入缓存
     *
     * @param toolName 工具名称
     * @param input    工具输入参数
     * @param result   工具调用结果
     */
    private void putToCache(String toolName, Map<String, Object> input, ToolResultBlock result) {
        if (!cacheableTools.contains(toolName)) {
            return;
        }
        if (result == null) {
            return;
        }
        // ToolResultBlock 可能是 error 类型，通过 toString 判断
        try {
            String content = result.toString();
            if (content.toLowerCase().contains("error") || content.toLowerCase().contains("失败")) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        String cacheKey = buildCacheKey(toolName, input);
        if (toolResultCache.size() >= CACHE_MAX_SIZE) {
            evictOldEntries(CACHE_MAX_SIZE / 5);
        }
        toolResultCache.put(cacheKey, new CacheEntry(result));
    }

    /**
     * 构建缓存 key
     *
     * @param toolName 工具名称
     * @param input    工具输入参数
     * @return 缓存 key
     */
    private String buildCacheKey(String toolName, Map<String, Object> input) {
        return toolName + "|" + input.hashCode();
    }

    /**
     * 淘汰过期的缓存条目
     *
     * @param count 淘汰数量
     */
    private void evictOldEntries(int count) {
        int evicted = 0;
        Iterator<Map.Entry<String, CacheEntry>> it = toolResultCache.entrySet().iterator();
        while (it.hasNext() && evicted < count) {
            if (it.next().getValue().isExpired()) {
                it.remove();
                evicted++;
            }
        }
        if (evicted < count) {
            it = toolResultCache.entrySet().iterator();
            while (it.hasNext() && evicted < count) {
                it.next();
                it.remove();
                evicted++;
            }
        }
    }

    /**
     * 缓存条目
     */
    private static class CacheEntry {
        /** 缓存的工具结果 */
        final ToolResultBlock result;
        /** 缓存创建时间戳 */
        final long createdAt;

        /**
         * 构造函数
         *
         * @param result 工具调用结果
         */
        CacheEntry(ToolResultBlock result) {
            this.result = result;
            this.createdAt = System.currentTimeMillis();
        }

        /**
         * 判断缓存是否已过期
         *
         * @return 是否已过期
         */
        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }
}
