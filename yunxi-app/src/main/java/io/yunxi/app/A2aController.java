package io.yunxi.app;

import io.a2a.spec.AgentCard;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.TransportProtocol;
import io.a2a.util.Utils;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import io.yunxi.runtime.TenantAgentAllocationService;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantContext;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import io.yunxi.runtime.tenant.TenantResolver;
import io.yunxi.ingress.ShardForwarder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * A2A 协议层 HTTP 入口。复用框架 AgentScopeA2aServer 的 JSON-RPC TransportWrapper 处理请求，
 * 在 yunxi 自身 Web 容器同端口以 /a2a/{agentId} 暴露，避免再起独立 HTTP 服务。
 *
 * <p>租户授权：本入口不走 IngressGateway，需自行经 TenantResolver 解析租户并绑定 TenantContext，
 * 在 registry.get 之前 assertAllowed 越权拦截；servlet 同步线程以 try/finally 绑/解租户。</p>
 */
@RestController
@RequestMapping("${yunxi.a2a.path-prefix:/a2a}")
public class A2aController {

    private final A2aServerRegistry registry;
    private final TenantAgentAllocationService allocation;
    private final TenantResolver tenantResolver;
    private final TenantConfig tenantConfig;
    private final TenantLifecycleService tenantLifecycleService;
    private final ObjectProvider<ShardForwarder> shardForwarder;

    /** 注入 A2A server 索引与租户分配/解析。 */
    public A2aController(A2aServerRegistry registry,
            TenantAgentAllocationService allocation,
            TenantResolver tenantResolver,
            TenantConfig tenantConfig,
            TenantLifecycleService tenantLifecycleService,
            ObjectProvider<ShardForwarder> shardForwarder) {
        this.registry = registry;
        this.allocation = allocation;
        this.tenantResolver = tenantResolver;
        this.tenantConfig = tenantConfig;
        this.tenantLifecycleService = tenantLifecycleService;
        this.shardForwarder = shardForwarder;
    }

    /** 按 URL 路径返回 AgentCard（OAuth/服务发现用）。 */
    @GetMapping("/{agentId}")
    public ResponseEntity<AgentCard> cardByUrl(
            @PathVariable String agentId,
            @RequestHeader Map<String, String> headers) {
        return respondCard(agentId, resolveTenant(headers));
    }

    /** 按 .well-known 路径返回 AgentCard（A2A 标准发现端点）。 */
    @GetMapping("/{agentId}/.well-known/agent.json")
    public ResponseEntity<AgentCard> cardWellKnown(
            @PathVariable String agentId,
            @RequestHeader Map<String, String> headers) {
        return respondCard(agentId, resolveTenant(headers));
    }

    /** 取注册表中的 AgentCard；越权或未知 agentId 返回 404。准入闸门在 resolveTenant 内已拒绝非 ACTIVE 租户。 */
    private ResponseEntity<AgentCard> respondCard(String agentId, String tenant) {
        try {
            allocation.assertAllowed(tenant, agentId);
            AgentScopeA2aServer server = registry.get(agentId);
            if (server == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(server.getAgentCard());
        } finally {
            TenantContext.clear();
        }
    }

    /** 处理 A2A JSON-RPC 请求：非流式直接回 JSON，流式以 SSE 回包；registry.get 之前做越权拦截。 */
    @PostMapping("/{agentId}")
    public ResponseEntity<?> handle(
            @PathVariable String agentId,
            @RequestHeader Map<String, String> headers,
            @RequestBody String body,
            HttpServletRequest request) {
        // 分片转发：non-owned agent 直接转发至 owner 实例，本实例不处理（owner==本实例时 forwardIfNeeded 返回 null 本地处理）。
        // fail-open：无 ShardForwarder bean（分片关闭）/ 转发异常 → 本地兜底。
        ShardForwarder forwarder = shardForwarder.getIfAvailable();
        if (forwarder != null) {
            ResponseEntity<String> forwarded = forwarder.forwardIfNeeded(
                    agentId, request.getRequestURI(), HttpMethod.valueOf(request.getMethod()),
                    body, toHttpHeaders(headers));
            if (forwarded != null) {
                return forwarded;
            }
        }
        String tenant = resolveTenant(headers);
        try {
            allocation.assertAllowed(tenant, agentId);
            AgentScopeA2aServer server = registry.get(agentId);
            if (server == null) {
                return ResponseEntity.notFound().build();
            }
            JsonRpcTransportWrapper transport =
                    server.getTransportWrapper(TransportProtocol.JSONRPC.asString(), JsonRpcTransportWrapper.class);
            Object result = transport.handleRequest(body, headers, Map.of());
            if (result instanceof Flux) {
                @SuppressWarnings("unchecked")
                Flux<JSONRPCResponse> flux = (Flux<JSONRPCResponse>) result;
                Flux<ServerSentEvent<String>> sse =
                        flux.map(response -> ServerSentEvent.<String>builder().data(toJson(response)).build());
                return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(sse);
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(toJson(result));
        } finally {
            TenantContext.clear();
        }
    }

    private String resolveTenant(Map<String, String> headers) {
        String tenant = tenantResolver.resolve(headers);
        if (tenant == null) {
            tenant = tenantConfig.defaultTenant();
        }
        tenantLifecycleService.admit(tenant); // fail-closed：SUSPENDED/ARCHIVED 抛异常
        return tenant;
    }

    /** 把 A2A 响应对象序列化为 JSON；失败抛 {@link IllegalStateException}。 */
    private String toJson(Object value) {
        try {
            return Utils.OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize a2a response", e);
        }
    }

    /** 把入站头 Map 转为 {@link HttpHeaders}，供转发透传。 */
    private HttpHeaders toHttpHeaders(Map<String, String> headers) {
        HttpHeaders result = new HttpHeaders();
        if (headers != null) {
            headers.forEach(result::add);
        }
        return result;
    }
}
