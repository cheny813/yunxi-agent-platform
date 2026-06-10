package io.yunxi.platform.a2a;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.config.AgentscopeExtensionProperties.A2AConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * A2A (Agent-to-Agent) 服务器
 * <p>
 * 将 Agent 暴露为 REST API 端点，支持跨服务调用
 * </p>
 * <ul>
 * <li>Agent 注册/注销</li>
 * <li>健康检查</li>
 * <li>远程调用</li>
 * <li>服务发现</li>
 * </ul>
 *
 * <h3>API 端点</h3>
 * <ul>
 * <li>POST /a2a/register - 注册 Agent</li>
 * <li>POST /a2a/deregister - 注销 Agent</li>
 * <li>POST /a2a/invoke - 调用 Agent</li>
 * <li>GET /a2a/health - 健康检查</li>
 * <li>GET /a2a/agents - 获取已注册 Agent 列表</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/a2a")
@ConditionalOnProperty(name = "agentscope.extensions.a2a.enabled", havingValue = "true")
public class A2AServer {

    /** A2A 配置（registryType/registryAddr/namespace 等） */
    private final A2AConfig config;

    /** A2A 注册中心（用于服务发现和 Agent 路由） */
    private final A2ARegistry registry;

    /** Agent 领域服务 —— 查找 Agent 实例 */
    private final AgentService agentDomainService;

    /** 本地 Agent 注册表（agentName → AgentInstance） */
    private final Map<String, AgentInstance> localAgents = new ConcurrentHashMap<>();

    /** 服务器端口（默认 40001，可通过配置覆盖） */
    private int serverPort = 40001;

    /** 服务器主机（默认 localhost，可通过配置覆盖） */
    private String serverHost = "localhost";

    /**
     * 构造 A2A 服务器
     *
     * @param config             A2A 配置
     * @param registry           A2A 注册中心
     * @param agentDomainService Agent 领域服务
     */
    public A2AServer(A2AConfig config, A2ARegistry registry, AgentService agentDomainService) {
        this.config = config;
        this.registry = registry;
        this.agentDomainService = agentDomainService;
        log.info("A2A 服务器配置加载完成");
    }

    /**
     * Agent 实例记录
     */
    public record AgentInstance(
            String name,
            Agent agent,
            List<String> capabilities,
            long registeredAt) {
    }

    // ==================== Agent 注册 API ====================

    /**
     * 注册 Agent
     *
     * @param request 注册请求
     * @return 注册结果
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        log.info("注册 Agent: {}", request.agentName());

        try {
            // 获取 Agent 实例（优先通过 AgentService 查找）
            Agent agent = agentDomainService.findAgent(request.agentName());
            if (agent == null) {
                // 检查 Agent 是否已注册
                try {
                    agent = agentDomainService.getAgentInstance(request.agentName());
                } catch (Exception e) {
                    log.warn("Agent 未找到: {}", request.agentName());
                }
            }

            // 构建元数据
            Map<String, String> metadata = new HashMap<>();
            metadata.put("registeredAt", String.valueOf(System.currentTimeMillis()));
            metadata.put("capabilities", String.join(",", request.capabilities()));

            A2ARegistry.AgentRegistration registration = new A2ARegistry.AgentRegistration(
                    request.agentName(),
                    serverHost,
                    serverPort,
                    "http",
                    metadata);

            // 注册到注册中心
            registry.register(registration);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("agentName", request.agentName());
            response.put("endpoint", String.format("http://%s:%d/a2a/invoke", serverHost, serverPort));
            response.put("message", "Agent 注册成功");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Agent 注册失败: {}", request.agentName(), e);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 注册请求
     */
    public record RegisterRequest(
            String agentName,
            List<String> capabilities) {
    }

    /**
     * 注销 Agent
     *
     * @param request 注销请求
     * @return 注销结果
     */
    @PostMapping("/deregister")
    public ResponseEntity<Map<String, Object>> deregister(@RequestBody DeregisterRequest request) {
        log.info("注销 Agent: {}", request.agentName());

        try {
            registry.deregister(request.agentName());
            localAgents.remove(request.agentName());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("agentName", request.agentName());
            response.put("message", "Agent 注销成功");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Agent 注销失败: {}", request.agentName(), e);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 注销请求
     */
    public record DeregisterRequest(String agentName) {
    }

    // ==================== Agent 调用 API ====================

    /**
     * 调用 Agent
     *
     * @param request 调用请求
     * @return 调用结果
     */
    @PostMapping("/invoke")
    public ResponseEntity<Map<String, Object>> invoke(@RequestBody InvokeRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("调用 Agent 中... agent={}, message={}",
                request.agentName(),
                request.message() != null ? request.message().substring(0, Math.min(50, request.message().length()))
                        : "null");

        try {
            // 获取 Agent 实例
            AgentInstance instance = localAgents.get(request.agentName());
            Agent agent = null;

            if (instance == null) {
                // 从 AgentService 中查找
                agent = agentDomainService.findAgent(request.agentName());
                if (agent == null) {
                    log.warn("Agent 未找到: {}", request.agentName());

                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("success", false);
                    response.put("error", "Agent 未找到: " + request.agentName());

                    return ResponseEntity.status(404).body(response);
                }
            } else {
                agent = instance.agent();
            }

            // 构建 AgentScope Agent
            Msg userMsg = Msg.builder()
                    .textContent(request.message())
                    .role(MsgRole.USER)
                    .build();

            // 同步阻塞调用（最多等待 5 分钟）
            Msg msgResponse = agent.call(userMsg)
                    .block(Duration.ofMinutes(5));

            String content = msgResponse != null ? msgResponse.getTextContent() : "Agent 无响应";

            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("agentName", request.agentName());
            result.put("content", content);
            result.put("durationMs", duration);
            result.put("metadata", Map.of(
                    "conversationId", request.conversationId() != null ? request.conversationId() : ""));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Agent 调用失败: {}", request.agentName(), e);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 调用请求
     */
    public record InvokeRequest(
            String agentName,
            String conversationId,
            String message,
            Map<String, Object> context,
            Map<String, Object> options) {
    }

    // ==================== 健康检查 API ====================

    /**
     * 健康检查
     *
     * @return 健康状态
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "healthy");
        response.put("timestamp", System.currentTimeMillis());
        response.put("registryType", config.getRegistryType());
        response.put("registeredAgents", localAgents.size());

        return ResponseEntity.ok(response);
    }

    /**
     * 详细健康检查
     *
     * @return 详细健康状态
     */
    @GetMapping("/health/detail")
    public ResponseEntity<Map<String, Object>> healthDetail() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "healthy");
        response.put("timestamp", System.currentTimeMillis());

        // 注册中心信息
        Map<String, Object> registryStatus = new LinkedHashMap<>();
        registryStatus.put("type", config.getRegistryType());
        registryStatus.put("address", config.getRegistryAddr());
        registryStatus.put("namespace", config.getNamespace());
        response.put("registry", registryStatus);

        // Agent 列表
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Map.Entry<String, AgentInstance> entry : localAgents.entrySet()) {
            Map<String, Object> agentInfo = new LinkedHashMap<>();
            agentInfo.put("name", entry.getKey());
            agentInfo.put("registeredAt", entry.getValue().registeredAt());
            agentInfo.put("capabilities", entry.getValue().capabilities());
            agents.add(agentInfo);
        }
        response.put("agents", agents);

        return ResponseEntity.ok(response);
    }

    // ==================== Agent 列表 API ====================

    /**
     * 获取所有已注册 Agent 列表
     *
     * @return Agent 列表
     */
    @GetMapping("/agents")
    public ResponseEntity<Map<String, Object>> listAgents() {
        List<String> localAgentNames = new ArrayList<>(localAgents.keySet());
        List<String> remoteAgentNames = registry.getAllAgents();

        // 去重
        Set<String> allAgents = new LinkedHashSet<>();
        allAgents.addAll(localAgentNames);
        allAgents.addAll(remoteAgentNames);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", allAgents.size());
        response.put("local", localAgentNames);
        response.put("remote", remoteAgentNames);
        response.put("all", new ArrayList<>(allAgents));

        return ResponseEntity.ok(response);
    }

    /**
     * 获取 Agent 详情
     *
     * @param agentName Agent 名称
     * @return Agent 详情
     */
    @GetMapping("/agents/{agentName}")
    public ResponseEntity<Map<String, Object>> getAgentDetail(@PathVariable String agentName) {
        AgentInstance instance = localAgents.get(agentName);

        if (instance == null) {
            // 检查是否为远程 Agent
            List<A2AClient.AgentEndpoint> endpoints = registry.discover(agentName);

            if (endpoints.isEmpty()) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", false);
                response.put("error", "Agent 未找到: " + agentName);

                return ResponseEntity.status(404).body(response);
            }

            // 返回远程 Agent 信息
            A2AClient.AgentEndpoint endpoint = endpoints.get(0);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", agentName);
            response.put("type", "remote");
            response.put("endpoint", endpoint.getUrl());
            response.put("metadata", endpoint.metadata());

            return ResponseEntity.ok(response);
        }

        // 返回本地 Agent 信息
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", agentName);
        response.put("type", "local");
        response.put("registeredAt", instance.registeredAt());
        response.put("capabilities", instance.capabilities());

        return ResponseEntity.ok(response);
    }

    // ==================== 编程式 API ====================

    /**
     * 编程方式注册 Agent
     *
     * @param agent        Agent 实例
     * @param capabilities 能力描述
     */
    public void registerAgent(Agent agent, List<String> capabilities) {
        String agentName = agent.getName();
        log.info("编程式注册 Agent: {}", agentName);

        AgentInstance instance = new AgentInstance(
                agentName,
                agent,
                capabilities != null ? capabilities.stream().distinct().toList() : List.of(),
                System.currentTimeMillis());

        localAgents.put(agentName, instance);

        // 注册到注册中心
        Map<String, String> metadata = new HashMap<>();
        metadata.put("registeredAt", String.valueOf(System.currentTimeMillis()));
        metadata.put("capabilities", String.join(",", capabilities != null ? capabilities : List.of()));

        A2ARegistry.AgentRegistration registration = new A2ARegistry.AgentRegistration(
                agentName,
                serverHost,
                serverPort,
                "http",
                metadata);

        registry.register(registration);
    }

    /**
     * 编程方式注销 Agent
     *
     * @param agentName Agent 名称
     */
    public void deregisterAgent(String agentName) {
        log.info("编程式注销 Agent: {}", agentName);
        localAgents.remove(agentName);
        registry.deregister(agentName);
    }

    /**
     * 设置服务器主机和端口信息（通过配置文件覆盖默认值）
     */
    public void setServerInfo(String host, int port) {
        this.serverHost = host;
        this.serverPort = port;
    }
}
