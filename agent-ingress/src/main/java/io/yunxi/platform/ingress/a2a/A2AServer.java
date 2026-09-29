package io.yunxi.platform.ingress.a2a;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.a2a.A2AClient;
import io.yunxi.platform.a2a.A2ARegistry;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.execution.spi.ExecutionEngine;
import io.yunxi.platform.ingress.a2a.dto.DeregisterRequest;
import io.yunxi.platform.ingress.a2a.dto.InvokeRequest;
import io.yunxi.platform.ingress.a2a.dto.RegisterRequest;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.execution.ExecutionResult;
import io.yunxi.platform.shared.dto.AgentInfoDto;

/**
 * A2A（Agent-to-Agent）协议服务端控制器。
 *
 * <p>职责：对外暴露 A2A 协议的 HTTP 端点（注册 / 发现 / 调用 / 注销 / 健康检查），
 * 并作为控制面对接远程 Agent。本地 Agent 的真相源由框架 AgentScope-Java 的 AgentService 与
 * aistio 注册中心（A2ARegistry）持有，本类不再维护第二份 Agent 注册表。</p>
 *
 * <p><b>执行收敛（统一接入网关设计 A 项 / A1）</b>：本控制器不再直连 HarnessAgent、
 * 不再自建 RuntimeContext、不再自管会话级工具组激活；调用语义统一交由
 * {@link ExecutionEngine} 兑现（Agent 解析、拦截器链、会话级工具组激活、统一 RuntimeContext
 * 均由引擎负责），本类只构造归一化 {@link ExecutionRequest} 并取回结果。A2A 系统身份固定为
 * {@code userId="a2a"}，由引擎拦截器 AuthResolve 直接透传（其仅在 userId 为空时回退 SecurityContext，
 * 不会因系统身份触发用户态解析）。</p>
 *
 * <p>激活开关沿用 {@code yunxi.a2a.enabled}（yunxi 产品平面统一开关，区别于框架扩展命名空间；
 * 与 GatewayHealthController 健康聚合读取同一键）。</p>
 */
@RestController
@RequestMapping("/api/a2a")
@ConditionalOnProperty(name = "yunxi.a2a.enabled", havingValue = "true", matchIfMissing = true)
public class A2AServer {

    private static final Logger log = LoggerFactory.getLogger(A2AServer.class);

    /** A2A 协议固定系统身份，供引擎拦截器 AuthResolve 透传 */
    private static final String A2A_SYSTEM_USER = "a2a";

    private final AgentService agentDomainService;
    private final A2ARegistry registry;
    private final A2AClient a2aClient;
    private final ExecutionEngine executionEngine;

    @Value("${a2a.server.host:localhost}")
    private String serverHost;

    @Value("${a2a.server.port:8080}")
    private int serverPort;

    @Autowired
    public A2AServer(AgentService agentDomainService,
                     A2ARegistry registry,
                     A2AClient a2aClient,
                     ExecutionEngine executionEngine) {
        this.agentDomainService = agentDomainService;
        this.registry = registry;
        this.a2aClient = a2aClient;
        this.executionEngine = executionEngine;
    }

    /**
     * 设置本实例对外暴露的 host/port（供远程 Agent 回连）。
     */
    public void setServerInfo(String host, int port) {
        this.serverHost = host;
        this.serverPort = port;
    }

    /**
     * 调用本地 Agent（A2A 协议调用入口）。
     *
     * <p>构造归一化执行请求交统一门面执行；超时 / 会话级工具组激活 / RuntimeContext 由引擎负责。
     * 响应沿用原 A2A 协议契约（success / agentName / content / durationMs / metadata）。</p>
     */
    @PostMapping("/invoke")
    public ResponseEntity<Map<String, Object>> invoke(@RequestBody InvokeRequest request) {
        long startTime = System.currentTimeMillis();
        String preview = request.message() != null
                ? request.message().substring(0, Math.min(50, request.message().length()))
                : "null";
        log.info("调用 Agent 中... agent={}, message={}", request.agentName(), preview);

        if (request.agentName() == null || request.agentName().isBlank()) {
            return badResponse("agentName 不能为空");
        }
        if (request.message() == null || request.message().isBlank()) {
            return badResponse("message 不能为空");
        }

        try {
            // 会话 ID：未提供 conversationId 时以 agentName 充当，保持与原 A2A 一致的工具组激活键
            String sessionId = request.conversationId() != null && !request.conversationId().isBlank()
                    ? request.conversationId()
                    : request.agentName();
            ExecutionRequest execReq = ExecutionRequest.builder()
                    .agentName(request.agentName())
                    .message(request.message())
                    .userId(A2A_SYSTEM_USER)
                    .conversationId(sessionId)
                    .useA2A(true)
                    .streaming(false)
                    .contextData(mergeContext(request))
                    .build();

            ExecutionResult result = executionEngine.execute(execReq, null);
            if (result.isError()) {
                log.warn("Agent 调用返回错误: agent={}, error={}", request.agentName(), result.getErrorMessage());
                return badResponse(result.getErrorMessage());
            }

            Msg responseMsg = result.getResult();
            String content = responseMsg != null ? responseMsg.getTextContent() : "Agent 无响应";
            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("success", true);
            resultMap.put("agentName", request.agentName());
            resultMap.put("content", content);
            resultMap.put("durationMs", duration);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("conversationId", sessionId);
            metadata.put("protocol", "a2a");
            resultMap.put("metadata", metadata);
            return ResponseEntity.ok(resultMap);
        } catch (Exception e) {
            log.error("Agent 调用失败: {}", request.agentName(), e);
            return badResponse("调用失败: " + e.getMessage());
        }
    }

    /**
     * 合并 A2A 调用方传入的 context 与 options 为引擎统一的 contextData。
     * 两者均为可选且当前无强制消费方；合并进单一上下文通道，避免在引擎侧引入第二个上下文概念。
     */
    private Map<String, Object> mergeContext(InvokeRequest request) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (request.context() != null) {
            merged.putAll(request.context());
        }
        if (request.options() != null) {
            merged.putAll(request.options());
        }
        return merged.isEmpty() ? null : merged;
    }

    private ResponseEntity<Map<String, Object>> badResponse(String error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 注册 Agent 到 A2A 注册中心（供远程调用发现）。
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        log.info("注册 Agent 到 A2A 注册中心: agent={}, host={}, port={}",
                request.agentName(), request.host(), request.port());

        if (request.agentName() == null || request.agentName().isBlank()) {
            return badResponse("agentName 不能为空");
        }

        // 校验本地是否存在该 Agent（真相源：框架 AgentService）
        Agent agent = agentDomainService.findAgent(request.agentName());
        if (agent == null) {
            try {
                agent = agentDomainService.getAgentInstance(request.agentName());
            } catch (Exception e) {
                log.warn("本地 Agent 实例获取失败（注册仍继续）: {}", request.agentName(), e);
            }
        }
        if (agent == null) {
            return badResponse("Agent 不存在: " + request.agentName());
        }

        String host = request.host() != null ? request.host() : serverHost;
        int port = request.port() != 0 ? request.port() : serverPort;
        Map<String, String> metadata = request.metadata() != null
                ? new LinkedHashMap<>(request.metadata()) : new LinkedHashMap<>();
        if (request.capabilities() != null && !request.capabilities().isEmpty()) {
            metadata.put("capabilities", String.join(",", request.capabilities()));
        }

        A2ARegistry.AgentRegistration registration = new A2ARegistry.AgentRegistration(
                request.agentName(), host, port,
                request.protocol() != null ? request.protocol() : "http",
                metadata);
        registry.register(registration);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("agentName", request.agentName());
        response.put("endpoint", host + ":" + port);
        response.put("message", "Agent 注册成功");
        return ResponseEntity.ok(response);
    }

    /**
     * 从 A2A 注册中心注销 Agent。
     */
    @DeleteMapping("/deregister")
    public ResponseEntity<Map<String, Object>> deregister(@RequestBody DeregisterRequest request) {
        log.info("从 A2A 注册中心注销 Agent: {}", request.agentName());
        registry.deregister(request.agentName());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("agentName", request.agentName());
        response.put("message", "Agent 注销成功");
        return ResponseEntity.ok(response);
    }

    /**
     * 健康检查端点。
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("registeredAgents", agentDomainService.countAgents());
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }

    /**
     * 详细健康信息（含本地托管的 Agent 摘要）。
     */
    @GetMapping("/health/detail")
    public ResponseEntity<Map<String, Object>> healthDetail() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("registeredAgents", agentDomainService.countAgents());
        health.put("timestamp", System.currentTimeMillis());

        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentInfoDto info : agentDomainService.listAgents()) {
            Map<String, Object> agentInfo = new LinkedHashMap<>();
            agentInfo.put("agentName", info.getName());
            agentInfo.put("description", info.getDescription());
            agents.add(agentInfo);
        }
        health.put("agents", agents);
        return ResponseEntity.ok(health);
    }

    /**
     * 列出本实例托管及注册中心可见的 Agent。
     */
    @GetMapping("/agents")
    public ResponseEntity<Map<String, Object>> listAgents() {
        List<String> remote = registry.getAllAgents();
        List<String> local = agentDomainService.listAgents().stream()
                .map(AgentInfoDto::getName)
                .toList();
        List<String> all = new ArrayList<>(local);
        for (String name : remote) {
            if (!all.contains(name)) {
                all.add(name);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("local", local);
        response.put("remote", new ArrayList<>(remote));
        response.put("all", all);
        response.put("count", all.size());
        return ResponseEntity.ok(response);
    }

    /**
     * 获取指定 Agent 的详情（优先本地托管，其次注册中心）。
     */
    @GetMapping("/agents/{name}")
    public ResponseEntity<Map<String, Object>> getAgentDetail(@PathVariable("name") String name) {
        Map<String, Object> detail = new LinkedHashMap<>();
        Agent local = agentDomainService.findAgent(name);
        if (local != null) {
            detail.put("name", name);
            detail.put("source", "local");
            detail.put("description", agentDomainService.listAgents().stream()
                    .filter(a -> name.equals(a.getName()))
                    .map(AgentInfoDto::getDescription)
                    .findFirst().orElse(""));
            return ResponseEntity.ok(detail);
        }
        List<A2AClient.AgentEndpoint> regs = registry.discover(name);
        if (regs != null && !regs.isEmpty()) {
            A2AClient.AgentEndpoint reg = regs.get(0);
            detail.put("name", name);
            detail.put("source", "registry");
            detail.put("host", reg.host());
            detail.put("port", reg.port());
            detail.put("protocol", reg.protocol());
            detail.put("capabilities",
                    reg.metadata() != null ? reg.metadata().getOrDefault("capabilities", "") : "");
            return ResponseEntity.ok(detail);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Agent 未找到: " + name));
    }

    /**
     * 编程式注册 Agent（供其他模块在 Agent 就绪后调用）。
     */
    public void registerAgent(Agent agent, List<String> capabilities) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (capabilities != null && !capabilities.isEmpty()) {
            metadata.put("capabilities", String.join(",", capabilities));
        }
        A2ARegistry.AgentRegistration registration = new A2ARegistry.AgentRegistration(
                agent.getName(), serverHost, serverPort, "http", metadata);
        registry.register(registration);
        log.info("Agent 已注册到 A2A 注册中心: {}", agent.getName());
    }

    /**
     * 编程式注销 Agent。
     */
    public void deregisterAgent(String agentName) {
        registry.deregister(agentName);
        log.info("Agent 已从 A2A 注册中心注销: {}", agentName);
    }

    /**
     * 调用远程 Agent（通过注册中心发现后发起 HTTP 调用）。
     */
    public A2AClient.AgentResponse invokeRemote(String agentName, String message) {
        return a2aClient.invoke(agentName, message);
    }
}
