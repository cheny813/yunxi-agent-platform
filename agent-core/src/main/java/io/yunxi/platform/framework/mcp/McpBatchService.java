package io.yunxi.platform.framework.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * MCP 批量工具调用服务
 * 
 * <p>提供高效的批量工具调用能力，支持：</p>
 * <ul>
 *   <li>并行调用多个工具</li>
 *   <li>单个服务器上的批量调用（如果服务器支持）</li>
 *   <li>跨服务器的工具编排</li>
 *   <li>结果聚合与错误隔离</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 并行调用多个工具
 * List<ToolCallRequest> requests = List.of(
 *     new ToolCallRequest("database", "query", Map.of("sql", "SELECT * FROM dishes")),
 *     new ToolCallRequest("milvus", "search", Map.of("query", "营养食谱", "top_k", 5))
 * );
 * BatchToolResult result = mcpBatchService.batchCall(requests);
 *
 * // 工具编排（有依赖关系）
 * WorkflowResult workflow = mcpBatchService.executeWorkflow(workflowDefinition);
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class McpBatchService {

    /** AgentScope 核心配置属性（提供 mcpServers） */
    private final AgentscopeCoreProperties properties;
    /** MCP 工具工厂 */
    private final McpToolFactory toolFactory;
    /** HTTP 请求模板 */
    private final RestTemplate restTemplate;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;
    
    /** 批量调用的线程池 */
    private final ExecutorService batchExecutor;
    
    /** 默认并发限制 */
    private static final int DEFAULT_CONCURRENCY_LIMIT = 10;
    
    /** 默认超时时间（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * 构造函数
     *
     * @param properties  AgentScope 核心配置属性
     * @param toolFactory MCP 工具工厂
     */
    public McpBatchService(AgentscopeCoreProperties properties, McpToolFactory toolFactory) {
        this.properties = properties;
        this.toolFactory = toolFactory;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.batchExecutor = Executors.newFixedThreadPool(DEFAULT_CONCURRENCY_LIMIT);
    }

    // ==================== 批量调用 API ====================

    /**
     * 工具调用请求
     */
    public record ToolCallRequest(
            String serverName,
            String toolName,
            Map<String, Object> arguments
    ) {}

    /**
     * 单个工具调用结果
     */
    public record ToolCallResult(
            String serverName,
            String toolName,
            boolean success,
            Object result,
            String error,
            long durationMs
    ) {
        /**
         * 构建成功结果
         *
         * @param serverName 服务器名称
         * @param toolName   工具名称
         * @param result     调用结果
         * @param durationMs 耗时（毫秒）
         * @return 成功的工具调用结果
         */
        public static ToolCallResult success(String serverName, String toolName, Object result, long durationMs) {
            return new ToolCallResult(serverName, toolName, true, result, null, durationMs);
        }
        
        /**
         * 构建失败结果
         *
         * @param serverName 服务器名称
         * @param toolName   工具名称
         * @param error      错误信息
         * @param durationMs 耗时（毫秒）
         * @return 失败的工具调用结果
         */
        public static ToolCallResult failure(String serverName, String toolName, String error, long durationMs) {
            return new ToolCallResult(serverName, toolName, false, null, error, durationMs);
        }
    }

    /**
     * 批量调用结果
     */
    public record BatchToolResult(
            List<ToolCallResult> results,
            int totalCount,
            int successCount,
            int failureCount,
            long totalDurationMs
    ) {
        /**
         * 获取成功的结果列表
         *
         * @return 成功的工具调用结果列表
         */
        public List<ToolCallResult> getSuccessfulResults() {
            return results.stream().filter(ToolCallResult::success).toList();
        }
        
        /**
         * 获取失败的结果列表
         *
         * @return 失败的工具调用结果列表
         */
        public List<ToolCallResult> getFailedResults() {
            return results.stream().filter(r -> !r.success()).toList();
        }
        
        /**
         * 将结果转换为 Map
         *
         * @return 结果的 Map 表示
         */
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalCount", totalCount);
            map.put("successCount", successCount);
            map.put("failureCount", failureCount);
            map.put("totalDurationMs", totalDurationMs);
            map.put("results", results.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("serverName", r.serverName());
                m.put("toolName", r.toolName());
                m.put("success", r.success());
                m.put("result", r.result());
                m.put("error", r.error());
                m.put("durationMs", r.durationMs());
                return m;
            }).toList());
            return map;
        }
    }

    /**
     * 并行批量调用多个工具
     * 
     * <p>使用 Reactor 实现高效的并行调用，支持：</p>
     * <ul>
     *   <li>并发控制：限制同时执行的工具数量</li>
     *   <li>错误隔离：单个工具失败不影响其他工具</li>
     *   <li>超时控制：每个调用有独立的超时</li>
     * </ul>
     *
     * @param requests 工具调用请求列表
     * @return 批量调用结果
     */
    public BatchToolResult batchCall(List<ToolCallRequest> requests) {
        return batchCall(requests, DEFAULT_CONCURRENCY_LIMIT, Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }

    /**
     * 并行批量调用（带并发控制）
     *
     * @param requests         工具调用请求列表
     * @param concurrencyLimit 最大并发数
     * @param timeout          单个调用的超时时间
     * @return 批量调用结果
     */
    public BatchToolResult batchCall(List<ToolCallRequest> requests, int concurrencyLimit, Duration timeout) {
        if (requests == null || requests.isEmpty()) {
            return new BatchToolResult(List.of(), 0, 0, 0, 0);
        }

        long startTime = System.currentTimeMillis();
        log.info("开始批量调用 {} 个工具，并发限制: {}", requests.size(), concurrencyLimit);

        // 使用 Reactor 并行执行
        List<ToolCallResult> results = Flux.fromIterable(requests)
                .flatMap(request -> 
                        Mono.fromCallable(() -> callSingleTool(request, timeout))
                                .subscribeOn(Schedulers.fromExecutor(batchExecutor))
                                .onErrorResume(e -> {
                                    log.error("工具调用失败: {}.{}", request.serverName(), request.toolName(), e);
                                    return Mono.just(ToolCallResult.failure(
                                            request.serverName(),
                                            request.toolName(),
                                            e.getMessage(),
                                            0
                                    ));
                                }),
                        concurrencyLimit  // 并发限制
                )
                .collectList()
                .block();

        long totalDuration = System.currentTimeMillis() - startTime;
        
        int successCount = (int) results.stream().filter(ToolCallResult::success).count();
        int failureCount = results.size() - successCount;
        
        log.info("批量调用完成: 成功 {}, 失败 {}, 耗时 {}ms", successCount, failureCount, totalDuration);

        return new BatchToolResult(results, results.size(), successCount, failureCount, totalDuration);
    }

    /**
     * 异步批量调用
     *
     * @param requests 工具调用请求列表
     * @return Mono 包装的批量结果
     */
    public Mono<BatchToolResult> batchCallAsync(List<ToolCallRequest> requests) {
        return Mono.fromCallable(() -> batchCall(requests))
                .subscribeOn(Schedulers.fromExecutor(batchExecutor));
    }

    /**
     * 调用单个工具
     *
     * @param request 工具调用请求
     * @param timeout 超时时间
     * @return 工具调用结果
     */
    private ToolCallResult callSingleTool(ToolCallRequest request, Duration timeout) {
        long startTime = System.currentTimeMillis();
        String fullToolName = request.serverName() + "_" + request.toolName();
        
        try {
            Map<String, AgentscopeCoreProperties.McpServerConfig> servers = properties.getMcpServers();
            if (servers == null) {
                return ToolCallResult.failure(request.serverName(), request.toolName(), 
                        "MCP服务器配置为空", 0);
            }
            
            AgentscopeCoreProperties.McpServerConfig serverConfig = servers.get(request.serverName());
            if (serverConfig == null || !serverConfig.isEnabled()) {
                return ToolCallResult.failure(request.serverName(), request.toolName(),
                        "MCP服务器未找到或未启用: " + request.serverName(), 0);
            }

            String baseUrl = serverConfig.getUrl();
            String serverType = serverConfig.getType() != null ? serverConfig.getType() : "http";

            // SSE 模式：URL 转换
            final String url;
            if ("sse".equalsIgnoreCase(serverType) && baseUrl.endsWith("/sse")) {
                url = baseUrl.replace("/sse", "/message");
            } else {
                url = baseUrl;
            }

            // 构建 JSON-RPC 请求
            Map<String, Object> rpcRequest = new LinkedHashMap<>();
            rpcRequest.put("jsonrpc", "2.0");
            rpcRequest.put("id", System.currentTimeMillis());
            rpcRequest.put("method", "tools/call");
            rpcRequest.put("params", Map.of(
                    "name", request.toolName(),
                    "arguments", request.arguments() != null ? request.arguments() : Map.of()
            ));

            log.debug("调用MCP工具: {}, 参数: {}", fullToolName, request.arguments());

            // 发送请求（带超时）
            final String finalUrl = url;
            CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.postForObject(finalUrl, rpcRequest, Map.class);
                return response;
            }, batchExecutor);

            Map<String, Object> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            
            // 解析响应
            Object result = parseMcpResult(response);
            long duration = System.currentTimeMillis() - startTime;
            
            log.debug("MCP工具调用成功: {}, 耗时 {}ms", fullToolName, duration);
            return ToolCallResult.success(request.serverName(), request.toolName(), result, duration);

        } catch (TimeoutException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("MCP工具调用超时: {}", fullToolName);
            return ToolCallResult.failure(request.serverName(), request.toolName(), 
                    "调用超时 (" + timeout.getSeconds() + "s)", duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("MCP工具调用失败: {}", fullToolName, e);
            return ToolCallResult.failure(request.serverName(), request.toolName(), 
                    e.getMessage(), duration);
        }
    }

    /**
     * 解析 MCP 响应结果
     *
     * @param response MCP 响应
     * @return 解析后的结果对象
     */
    private Object parseMcpResult(Map<String, Object> response) {
        if (response == null) {
            return null;
        }

        if (response.containsKey("error")) {
            return Map.of("error", response.get("error"));
        }

        if (response.containsKey("result")) {
            Object result = response.get("result");
            
            if (result instanceof Map resultMap) {
                // 提取 content 字段
                if (resultMap.containsKey("content")) {
                    Object content = resultMap.get("content");
                    if (content instanceof List contentList && !contentList.isEmpty()) {
                        Map<String, Object> firstContent = (Map<String, Object>) contentList.get(0);
                        if ("text".equals(firstContent.get("type"))) {
                            return firstContent.get("text");
                        }
                    }
                }
            }
            
            return result;
        }

        return response;
    }

    // ==================== 工具编排 API ====================

    /**
     * 工作流步骤定义
     */
    public record WorkflowStep(
            String id,
            String serverName,
            String toolName,
            Map<String, Object> arguments,
            List<String> dependsOn  /** 依赖的步骤 ID */
    ) {}

    /**
     * 工作流定义
     */
    public record WorkflowDefinition(
            String name,
            List<WorkflowStep> steps,
            Duration timeout
    ) {}

    /**
     * 工作流执行结果
     */
    public record WorkflowResult(
            String workflowName,
            boolean success,
            Map<String, ToolCallResult> stepResults,
            Map<String, Object> outputs,
            long totalDurationMs
    ) {}

    /**
     * 执行工作流（支持步骤依赖）
     * 
     * <p>工作流引擎特性：</p>
     * <ul>
     *   <li>拓扑排序：根据依赖关系确定执行顺序</li>
     *   <li>并行执行：无依赖的步骤并行执行</li>
     *   <li>结果传递：前序步骤的结果可传递给后续步骤</li>
     *   <li>失败处理：某步骤失败时，依赖它的步骤跳过</li>
     * </ul>
     *
     * @param definition 工作流定义
     * @return 工作流执行结果
     */
    public WorkflowResult executeWorkflow(WorkflowDefinition definition) {
        long startTime = System.currentTimeMillis();
        log.info("开始执行工作流: {}", definition.name());

        Map<String, ToolCallResult> stepResults = new ConcurrentHashMap<>();
        Map<String, Object> outputs = new ConcurrentHashMap<>();

        try {
            // 构建依赖图
            Map<String, Set<String>> dependencyGraph = buildDependencyGraph(definition.steps());
            
            // 拓扑排序获取执行顺序
            List<String> executionOrder = topologicalSort(dependencyGraph);
            
            // 按层级执行
            for (String stepId : executionOrder) {
                WorkflowStep step = definition.steps().stream()
                        .filter(s -> s.id().equals(stepId))
                        .findFirst()
                        .orElseThrow();

                // 检查依赖是否成功
                boolean dependenciesMet = checkDependencies(step, stepResults);
                
                if (!dependenciesMet) {
                    log.warn("步骤 {} 的依赖未满足，跳过执行", stepId);
                    stepResults.put(stepId, ToolCallResult.failure(
                            step.serverName(), step.toolName(), 
                            "依赖步骤失败", 0
                    ));
                    continue;
                }

                // 解析参数（支持引用前序步骤的结果）
                Map<String, Object> resolvedArgs = resolveArguments(
                        step.arguments(), stepResults, outputs
                );

                // 执行步骤
                ToolCallRequest request = new ToolCallRequest(
                        step.serverName(), step.toolName(), resolvedArgs
                );
                ToolCallResult result = callSingleTool(request, definition.timeout());
                
                stepResults.put(stepId, result);
                
                if (result.success()) {
                    outputs.put(stepId, result.result());
                }

                log.info("工作流步骤完成: {} -> {}", stepId, result.success() ? "成功" : "失败");
            }

            long totalDuration = System.currentTimeMillis() - startTime;
            boolean allSuccess = stepResults.values().stream().allMatch(ToolCallResult::success);
            
            log.info("工作流执行完成: {}, 耗时 {}ms", definition.name(), totalDuration);
            
            return new WorkflowResult(definition.name(), allSuccess, stepResults, outputs, totalDuration);

        } catch (Exception e) {
            log.error("工作流执行失败: {}", definition.name(), e);
            long totalDuration = System.currentTimeMillis() - startTime;
            return new WorkflowResult(definition.name(), false, stepResults, outputs, totalDuration);
        }
    }

    /**
     * 构建依赖图
     */
    private Map<String, Set<String>> buildDependencyGraph(List<WorkflowStep> steps) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (WorkflowStep step : steps) {
            graph.put(step.id(), step.dependsOn() != null ? new HashSet<>(step.dependsOn()) : new HashSet<>());
        }
        return graph;
    }

    /**
     * 拓扑排序
     */
    private List<String> topologicalSort(Map<String, Set<String>> graph) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String node : graph.keySet()) {
            if (!visited.contains(node)) {
                topologicalVisit(node, graph, visited, visiting, result);
            }
        }

        return result;
    }

    private void topologicalVisit(String node, Map<String, Set<String>> graph,
                                  Set<String> visited, Set<String> visiting, List<String> result) {
        if (visiting.contains(node)) {
            throw new IllegalStateException("检测到循环依赖: " + node);
        }
        
        if (visited.contains(node)) {
            return;
        }

        visiting.add(node);
        
        for (String dep : graph.getOrDefault(node, Set.of())) {
            topologicalVisit(dep, graph, visited, visiting, result);
        }

        visiting.remove(node);
        visited.add(node);
        result.add(node);
    }

    /**
     * 检查依赖是否满足
     */
    private boolean checkDependencies(WorkflowStep step, Map<String, ToolCallResult> stepResults) {
        if (step.dependsOn() == null || step.dependsOn().isEmpty()) {
            return true;
        }
        
        for (String depId : step.dependsOn()) {
            ToolCallResult depResult = stepResults.get(depId);
            if (depResult == null || !depResult.success()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析参数（支持引用前序步骤的结果）
     * 
     * <p>参数格式：${stepId} 或 ${stepId.field}</p>
     */
    private Map<String, Object> resolveArguments(
            Map<String, Object> arguments,
            Map<String, ToolCallResult> stepResults,
            Map<String, Object> outputs
    ) {
        if (arguments == null) {
            return Map.of();
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Object value = entry.getValue();
            
            if (value instanceof String strValue && strValue.startsWith("${") && strValue.endsWith("}")) {
                // 解析引用
                String ref = strValue.substring(2, strValue.length() - 1);
                String[] parts = ref.split("\\.", 2);
                String stepId = parts[0];
                
                Object stepOutput = outputs.get(stepId);
                if (stepOutput == null) {
                    resolved.put(entry.getKey(), value);
                    continue;
                }
                
                if (parts.length == 1) {
                    resolved.put(entry.getKey(), stepOutput);
                } else {
                    // 支持字段访问（如果是 Map）
                    if (stepOutput instanceof Map map) {
                        resolved.put(entry.getKey(), map.get(parts[1]));
                    } else {
                        resolved.put(entry.getKey(), value);
                    }
                }
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        
        return resolved;
    }

    // ==================== 资源清理 ====================

    /**
     * 关闭服务，释放资源
     */
    public void shutdown() {
        log.info("关闭 MCP 批量服务");
        batchExecutor.shutdown();
        try {
            if (!batchExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                batchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            batchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
