package io.yunxi.ingress;

import io.yunxi.runtime.ProtocolUserIdResolver;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantResolver;
import io.agentscope.core.event.AgentEvent;
import io.yunxi.runtime.Settle;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入口织面。四协议聚合的 HTTP 入口。把外部 HTTP 请求翻译为 {@link ConversationRequest}，
 * 交由 {@link IngressGateway} 路由到对应协议适配器并运行，再把 {@link Settle} 映射为 HTTP 状态码与响应体。
 *
 * <p>本控制器是纯传输层：不含任何推理语义；身份、鉴权、横切关切由上游（SecurityConfig / 运行时中间件）承担。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    /** 流式端点最长保持时间：10 分钟，超时由 SSE 连接关闭兜底。 */
    private static final long STREAM_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final IngressGateway ingressGateway;
    private final ProtocolUserIdResolver protocolUserIdResolver;
    private final TenantResolver tenantResolver;
    private final TenantConfig tenantConfig;
    private final ObjectProvider<ShardForwarder> shardForwarder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 注入统一入口网关、身份归一解析器与多租户解析器。 */
    public ConversationController(IngressGateway ingressGateway,
            ProtocolUserIdResolver protocolUserIdResolver,
            TenantResolver tenantResolver,
            TenantConfig tenantConfig,
            ObjectProvider<ShardForwarder> shardForwarder) {
        this.ingressGateway = ingressGateway;
        this.protocolUserIdResolver = protocolUserIdResolver;
        this.tenantResolver = tenantResolver;
        this.tenantConfig = tenantConfig;
        this.shardForwarder = shardForwarder;
    }

    /**
     * 非流式执行：运行并结算为单一响应。
     * 把 {@link Settle.Status} 映射为 HTTP 语义状态码，响应体携带回复文本、状态与错误码。
     *
     * <p>分片转发：请求显式携带 {@code agentId} 且非本实例拥有 → 转发至 owner {@code /api/conversations}（fail-open
     * 无 forwarder / 异常时本地兜底）。显式 agentId 缺失时无法路由，按现状本地处理。</p>
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConversationResponse> complete(@RequestBody ConversationRequest request) {
        ResponseEntity<String> forwarded = tryForward(request);
        if (forwarded != null) {
            return toConversationResponse(forwarded);
        }
        normalize(request);
        ConversationResponse body = ingressGateway.complete(request).block();
        return ResponseEntity.status(mapStatus(body.getStatus())).body(body);
    }

    /**
     * 流式执行：以 Server-Sent Events 透传 AgentScope-Java 事件流。
     * 每个 {@link AgentEvent} 作为一条 {@code agent-event} 命名事件下发；事件流正常结束即关闭连接。
     * 执行故障不再以裸异常 {@code completeWithError} 抛出（客户端只能拿到 500 而无根因），而是追加一条
     * {@code error} 命名事件，载荷为 {@link ConversationResponse}（status/errorCode/description 携带根因），
     * 随后正常关闭连接，使流式消费方能像非流式响应一样读到根因。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ConversationRequest request) {
        normalize(request);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Flux<AgentEvent> events = ingressGateway.stream(request);
        events.subscribe(
                event -> safeSend(emitter, event),
                error -> {
                    Settle settle = Settle.fromError(request.getAgentId(), request.getUserId(),
                            request.getSessionId(), error);
                    // 仅真实执行故障记录完整堆栈（NO_AGENT 等客户端错误不刷屏）；根因同时随 error 事件透传，
                    // 客户端无需依赖日志即可定位。
                    if (settle.status() == Settle.Status.EXECUTION_ERROR) {
                        log.error("stream execution failed: agentId={}, userId={}, sessionId={}, description={}",
                                request.getAgentId(), request.getUserId(), request.getSessionId(),
                                settle.description(), error);
                    }
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(ConversationResponse.from(settle, null)));
                    } catch (IOException ignored) {
                        // 客户端已断开，结构化错误事件无法下发，由上方日志兜底根因
                    }
                    emitter.complete();
                },
                emitter::complete);
        return emitter;
    }

    /** 安全下发单条事件（命名事件 agent-event）；IO 异常转错误完成。 */
    private void safeSend(SseEmitter emitter, AgentEvent event) {
        try {
            emitter.send(SseEmitter.event().name("agent-event").data(event));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * 协议标识缺省时补默认 {@code web}，并把 userId 经四协议统一的 {@link ProtocolUserIdResolver} 归一：
     * 已认证主体优先，回退到请求体携带的 userId；与 Chat Completions / AG-UI 共用同一身份真相源，
     * 杜绝 web 绕开身份归一、信任客户端任意 userId 的分叉。
     *
     * <p>多租户 admit 段：启用时从入站 HTTP 头经 {@link TenantResolver} 解析租户并写入 {@code request.tenantId}，
     * 未携带头时回落 {@code defaultTenant}；未启用时写入 null（装饰器 pass-through）。租户随后经
     * {@link IngressGateway} 注入响应式链，由存储装饰器在键计算时读取。
     */
    private void normalize(ConversationRequest request) {
        if (request.getProtocol() == null || request.getProtocol().isBlank()) {
            request.setProtocol("web");
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        request.setUserId(protocolUserIdResolver.resolve(authentication, request.getUserId()));
        if (tenantConfig.enabled()) {
            String resolved = tenantResolver.resolve(inboundHeaders());
            request.setTenantId(resolved == null || resolved.isBlank() ? tenantConfig.defaultTenant() : resolved);
        } else {
            request.setTenantId(null);
        }
    }

    /** 从当前 servlet 请求收集全部入站 HTTP 头，供 {@link TenantResolver} 按配置头名提取租户。 */
    private Map<String, String> inboundHeaders() {
        Map<String, String> headers = new HashMap<>();
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest httpRequest = servletAttributes.getRequest();
            Enumeration<String> names = httpRequest.getHeaderNames();
            while (names != null && names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, httpRequest.getHeader(name));
            }
        }
        return headers;
    }

    /**
     * 分片转发探测：仅当请求显式携带 {@code agentId} 时尝试转发至 owner 实例。
     * 返回 owner 原始响应（含状态码）；本地处理（无 agentId / 无 forwarder / 异常）返回 {@code null}。
     */
    private ResponseEntity<String> tryForward(ConversationRequest request) {
        String agentId = request.getAgentId();
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        ShardForwarder forwarder = shardForwarder.getIfAvailable();
        if (forwarder == null) {
            return null;
        }
        try {
            String body = objectMapper.writeValueAsString(request);
            return forwarder.forwardIfNeeded(agentId, "/api/conversations", HttpMethod.POST,
                    body, toHttpHeaders(inboundHeaders()));
        } catch (Exception e) {
            return null; // fail-open：序列化/转发异常不阻断本地处理
        }
    }

    /** 把 owner 转发响应（ConversationResponse JSON）还原为本入口同类型响应，透传状态码与头。 */
    private ResponseEntity<ConversationResponse> toConversationResponse(ResponseEntity<String> forwarded) {
        ConversationResponse cr;
        try {
            cr = objectMapper.readValue(forwarded.getBody(), ConversationResponse.class);
        } catch (Exception e) {
            cr = new ConversationResponse(null, null, forwarded.getBody(),
                    Settle.Status.EXECUTION_ERROR, "forward_decode_error");
        }
        return ResponseEntity.status(forwarded.getStatusCode())
                .headers(forwarded.getHeaders())
                .body(cr);
    }

    /** 把入站头 Map 转为 {@link HttpHeaders}，供转发透传。 */
    private HttpHeaders toHttpHeaders(Map<String, String> headers) {
        HttpHeaders result = new HttpHeaders();
        if (headers != null) {
            headers.forEach(result::add);
        }
        return result;
    }

    /** 把结算状态映射为 HTTP 语义状态码：未命中 Agent→404，忙/冲突→409，拒绝/过载→429，执行错误→500，否则 200。 */
    private HttpStatus mapStatus(Settle.Status status) {
        return switch (status) {
            case OK -> HttpStatus.OK;
            case NO_AGENT -> HttpStatus.NOT_FOUND;
            case BUSY, STATE_CONFLICT -> HttpStatus.CONFLICT;
            case REJECTED, OVERLOAD -> HttpStatus.TOO_MANY_REQUESTS;
            case EXECUTION_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
