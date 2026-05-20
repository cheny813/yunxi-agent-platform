package io.yunxi.platform.framework.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.infra.config.AgentscopeExtensionProperties.A2AConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A (Agent-to-Agent) 客户端
 * 
 * <p>
 * 实现 Agent 跨服务协作能力，支持：
 * </p>
 * <ul>
 * <li>远程 Agent 调用</li>
 * <li>服务发现与负载均衡</li>
 * <li>请求路由与容错</li>
 * <li>结果聚合</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * 
 * <pre>
 * // 同步调用远程 Agent
 * AgentResponse response = a2aClient.invoke("nutrition-agent", "生成食谱", context);
 *
 * // 异步调用
 * Mono&lt;AgentResponse&gt; responseMono = a2aClient.invokeAsync("recipe-agent", request);
 *
 * // 批量调用多个 Agent
 * List&lt;AgentResponse&gt; responses = a2aClient.invokeAll(agentNames, request);
 * </pre>
 *
 * <h3>配置示例</h3>
 * 
 * <pre>
 * agentscope:
 *   extensions:
 *     a2a:
 *       enabled: true
 *       registry-type: nacos
 *       registry-addr: localhost:8848
 *       namespace: agent-platform
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
// @ConditionalOnProperty(name = "agentscope.extensions.a2a.enabled",
// havingValue = "true")
public class A2AClient {

    /** A2A 配置 */
    private final A2AConfig config;
    /** A2A 注册中心 */
    private final A2ARegistry registry;
    /** REST 客户端 */
    private final RestClient restClient;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /** Agent 缓存 */
    private final Map<String, List<AgentEndpoint>> agentCache = new ConcurrentHashMap<>();

    /** 默认超时时间 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 构造 A2A 客户端
     *
     * @param config   A2A 配置（可选，为空时使用默认配置）
     * @param registry A2A 注册中心
     */
public A2AClient(ObjectProvider<A2AConfig> configProvider, @Autowired A2ARegistry registry) {
        A2AConfig resolvedConfig = configProvider.getIfAvailable();
        this.config = resolvedConfig != null ? resolvedConfig : createDefaultConfig();
        this.registry = registry;
        this.restClient = RestClient.builder().build();
        this.objectMapper = new ObjectMapper();

        log.info("A2A 客户端初始化完成，注册中心类型: {}", this.config.getRegistryType());
    }

    /**
     * 创建默认 A2A 配置
     *
     * @return 默认配置实例
     */
    private A2AConfig createDefaultConfig() {
        A2AConfig defaultConfig = new A2AConfig();
        defaultConfig.setEnabled(true);
        defaultConfig.setRegistryType("static");
        return defaultConfig;
    }

    // ==================== 核心 API ====================

    /**
     * Agent 调用请求
     */
    public record AgentRequest(
            String conversationId,
            String message,
            Map<String, Object> context,
            Map<String, Object> options) {
        public static AgentRequest of(String message) {
            return new AgentRequest(null, message, Map.of(), Map.of());
        }

        public static AgentRequest of(String message, Map<String, Object> context) {
            return new AgentRequest(null, message, context, Map.of());
        }
    }

    /**
     * Agent 调用响应
     */
    public record AgentResponse(
            String agentName,
            String endpoint,
            boolean success,
            String content,
            Map<String, Object> metadata,
            long durationMs) {
        public static AgentResponse success(String agentName, String endpoint, String content, long durationMs) {
            return new AgentResponse(agentName, endpoint, true, content, Map.of(), durationMs);
        }

        public static AgentResponse failure(String agentName, String endpoint, String error, long durationMs) {
            return new AgentResponse(agentName, endpoint, false, error, Map.of(), durationMs);
        }
    }

    /**
     * Agent 端点信息
     */
    public record AgentEndpoint(
            String name,
            String host,
            int port,
            String protocol,
            Map<String, String> metadata) {
        public String getUrl() {
            return String.format("%s://%s:%d/a2a/invoke", protocol, host, port);
        }
    }

    /**
     * 调用远程 Agent
     *
     * @param agentName Agent 名称
     * @param message   消息内容
     * @return Agent 响应
     */
    public AgentResponse invoke(String agentName, String message) {
        return invoke(agentName, AgentRequest.of(message));
    }

    /**
     * 调用远程 Agent（带上下文）
     *
     * @param agentName Agent 名称
     * @param message   消息内容
     * @param context   上下文信息
     * @return Agent 响应
     */
    public AgentResponse invoke(String agentName, String message, Map<String, Object> context) {
        return invoke(agentName, AgentRequest.of(message, context));
    }

    /**
     * 调用远程 Agent（完整请求）
     *
     * @param agentName Agent 名称
     * @param request   Agent 请求
     * @return Agent 响应
     */
    public AgentResponse invoke(String agentName, AgentRequest request) {
        return invoke(agentName, request, DEFAULT_TIMEOUT);
    }

    /**
     * 调用远程 Agent（带超时）
     *
     * @param agentName Agent 名称
     * @param request   Agent 请求
     * @param timeout   超时时间
     * @return Agent 响应
     */
    public AgentResponse invoke(String agentName, AgentRequest request, Duration timeout) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 服务发现
            List<AgentEndpoint> endpoints = discoverAgent(agentName);
            if (endpoints.isEmpty()) {
                log.warn("未找到 Agent: {}", agentName);
                return AgentResponse.failure(agentName, null, "Agent 未注册", 0);
            }

            // 2. 负载均衡选择端点
            AgentEndpoint endpoint = selectEndpoint(endpoints);
            log.info("调用远程 Agent: {} -> {}", agentName, endpoint.getUrl());

            // 3. 构建请求
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("agentName", agentName);
            requestBody.put("conversationId", request.conversationId());
            requestBody.put("message", request.message());
            requestBody.put("context", request.context());
            requestBody.put("options", request.options());

            // 4. 发送 HTTP 请求
            String responseJson = restClient.post()
                    .uri(endpoint.getUrl())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            // 5. 解析响应
            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(responseJson, Map.class);

            String content = (String) responseMap.get("content");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) responseMap.getOrDefault("metadata", Map.of());

            long duration = System.currentTimeMillis() - startTime;
            log.info("远程 Agent 调用成功: {}, 耗时 {}ms", agentName, duration);

            return new AgentResponse(agentName, endpoint.getUrl(), true, content, metadata, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("远程 Agent 调用失败: {}", agentName, e);
            return AgentResponse.failure(agentName, null, e.getMessage(), duration);
        }
    }

    /**
     * 异步调用远程 Agent
     *
     * @param agentName Agent 名称
     * @param request   Agent 请求
     * @return Mono 包装的响应
     */
    public Mono<AgentResponse> invokeAsync(String agentName, AgentRequest request) {
        return Mono.fromCallable(() -> invoke(agentName, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 批量调用多个 Agent（并行）
     *
     * @param agentNames Agent 名称列表
     * @param request    Agent 请求
     * @return 所有 Agent 的响应
     */
    public List<AgentResponse> invokeAll(List<String> agentNames, AgentRequest request) {
        return invokeAll(agentNames, request, DEFAULT_TIMEOUT);
    }

    /**
     * 批量调用多个 Agent（并行，带超时）
     *
     * @param agentNames Agent 名称列表
     * @param request    Agent 请求
     * @param timeout    超时时间
     * @return 所有 Agent 的响应
     */
    public List<AgentResponse> invokeAll(List<String> agentNames, AgentRequest request, Duration timeout) {
        long startTime = System.currentTimeMillis();
        log.info("批量调用 {} 个 Agent", agentNames.size());

        List<AgentResponse> responses = new ArrayList<>();

        for (String agentName : agentNames) {
            try {
                AgentResponse response = invoke(agentName, request, timeout);
                responses.add(response);
            } catch (Exception e) {
                log.error("Agent 调用失败: {}", agentName, e);
                responses.add(AgentResponse.failure(agentName, null, e.getMessage(), 0));
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("批量调用完成，耗时 {}ms，成功 {} 个", duration,
                responses.stream().filter(AgentResponse::success).count());

        return responses;
    }

    /**
     * 聚合调用多个 Agent
     * 
     * <p>
     * 将多个 Agent 的结果聚合返回，支持结果去重和排序
     * </p>
     *
     * @param agentNames Agent 名称列表
     * @param request    Agent 请求
     * @return 聚合结果
     */
    public AggregatedResponse invokeAndAggregate(List<String> agentNames, AgentRequest request) {
        List<AgentResponse> responses = invokeAll(agentNames, request);

        List<String> successfulContents = responses.stream()
                .filter(AgentResponse::success)
                .map(AgentResponse::content)
                .toList();

        List<String> failedAgents = responses.stream()
                .filter(r -> !r.success())
                .map(AgentResponse::agentName)
                .toList();

        return new AggregatedResponse(successfulContents, failedAgents, responses);
    }

    /**
     * 聚合响应
     */
    public record AggregatedResponse(
            List<String> results,
            List<String> failedAgents,
            List<AgentResponse> allResponses) {
        public int successCount() {
            return results.size();
        }

        public int failureCount() {
            return failedAgents.size();
        }
    }

    // ==================== 服务发现 ====================

    /**
     * 发现 Agent 端点
     *
     * @param agentName Agent 名称
     * @return 端点列表
     */
    public List<AgentEndpoint> discoverAgent(String agentName) {
        // 先从缓存获取
        List<AgentEndpoint> cached = agentCache.get(agentName);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // 从注册中心获取
        List<AgentEndpoint> endpoints = registry.discover(agentName);

        // 更新缓存
        if (!endpoints.isEmpty()) {
            agentCache.put(agentName, endpoints);
        }

        return endpoints;
    }

    /**
     * 刷新 Agent 缓存
     *
     * @param agentName Agent 名称（可选，为空则刷新全部）
     */
    public void refreshCache(String agentName) {
        if (agentName != null) {
            agentCache.remove(agentName);
            log.info("已刷新 Agent 缓存: {}", agentName);
        } else {
            agentCache.clear();
            log.info("已刷新全部 Agent 缓存");
        }
    }

    /**
     * 负载均衡选择端点
     */
    private AgentEndpoint selectEndpoint(List<AgentEndpoint> endpoints) {
        if (endpoints.size() == 1) {
            return endpoints.get(0);
        }

        // 简单轮询（后续可扩展为加权轮询、最少连接等）
        int index = (int) (System.currentTimeMillis() % endpoints.size());
        return endpoints.get(index);
    }

    // ==================== 健康检查 ====================

    /**
     * 检查 Agent 健康状态
     *
     * @param agentName Agent 名称
     * @return 是否健康
     */
    public boolean isHealthy(String agentName) {
        try {
            List<AgentEndpoint> endpoints = discoverAgent(agentName);
            if (endpoints.isEmpty()) {
                return false;
            }

            AgentEndpoint endpoint = endpoints.get(0);
            String healthUrl = String.format("%s://%s:%d/a2a/health",
                    endpoint.protocol(), endpoint.host(), endpoint.port());

            String response = restClient.get()
                    .uri(healthUrl)
                    .retrieve()
                    .body(String.class);

            return response != null && response.contains("healthy");

        } catch (Exception e) {
            log.warn("Agent 健康检查失败: {}", agentName, e);
            return false;
        }
    }
}
