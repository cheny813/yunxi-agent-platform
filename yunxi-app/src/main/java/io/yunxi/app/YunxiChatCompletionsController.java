package io.yunxi.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.converter.OpenAIToolConverter;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import io.agentscope.spring.boot.chat.web.ChatCompletionsController;
import io.yunxi.runtime.AgentCommand;
import io.yunxi.runtime.AgentLookup;
import io.yunxi.runtime.ExecutionFacade;
import io.yunxi.runtime.Json;
import io.yunxi.runtime.ProtocolUserIdResolver;
import io.yunxi.runtime.TenantAgentAllocationService;
import io.yunxi.definition.TenantStatus;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantContext;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import io.yunxi.runtime.tenant.TenantResolver;
import io.yunxi.ingress.ShardForwarder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 四协议统一入口之一。替代框架默认的 ChatCompletionsController（经 @ConditionalOnMissingBean 拦截）：
 * 不再直接 agent.call，而是把 ChatCompletionsRequest 翻译为 AgentCommand 交给 ExecutionFacade 执行，
 * 复用 web 入口已有的 streamEvents + RuntimeContext(userId) + SessionTurnGate 统一路径，
 * 消除原覆盖版缺失身份 / 并发互斥的缺口。
 *
 * <p>协议语义（消息转换、响应构建、OpenAI chunk 结构）仍复用框架 ChatMessageConverter /
 * ChatCompletionsResponseBuilder 与产品平面 ChatCompletionsEventMapper，入口本身只做
 * 协议翻译与身份 / 会话归一，不携带推理语义。</p>
 *
 * <p>请求级工具（OpenAI tools 参数）由 Agent 预装配的 toolkit 承担，门面模型不含请求级工具注册，
 * 故本入口不再把工具 schema 注册到全局 Agent toolkit（原覆盖写法有跨请求并发写入风险）。</p>
 *
 * <p>保留 extends ChatCompletionsController 仅用于维持 @ConditionalOnMissingBean 拦截框架默认 bean，
 * 避免两个控制器同时映射同一路径；所有执行逻辑均在下方重写，不再调用父类。</p>
 *
 * <p>租户授权：本入口不走 IngressGateway，需自行经 TenantResolver 解析租户并绑定 TenantContext，
 * 再交由执行门面在 findAgent 之前统一 assertAllowed；buildCommand 在 agentLookup 解析前也预校验一次。</p>
 */
@RestController
public class YunxiChatCompletionsController extends ChatCompletionsController {

    private static final String BASE_PATH = "${agentscope.chat-completions.base-path:/v1/chat/completions}";

    private final ExecutionFacade executionFacade;
    private final AgentLookup agentLookup;
    private final ChatMessageConverter messageConverter;
    private final ChatCompletionsResponseBuilder responseBuilder;
    private final ProtocolUserIdResolver userIdResolver;
    private final TenantAgentAllocationService allocation;
    private final TenantResolver tenantResolver;
    private final TenantConfig tenantConfig;
    private final TenantLifecycleService tenantLifecycleService;
    private final ObjectMapper objectMapper = Json.objectMapper();
    private final ObjectProvider<ShardForwarder> shardForwarder;

    /** 注入执行门面 / Agent 查找 / 消息转换 / 响应构建 / 身份解析 / 租户分配与解析，并透传父类以拦截框架默认 bean。 */
    public YunxiChatCompletionsController(
            ObjectProvider<ReActAgent> agentProvider,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService,
            OpenAIToolConverter toolConverter,
            ExecutionFacade executionFacade,
            AgentLookup agentLookup,
            ProtocolUserIdResolver userIdResolver,
            TenantAgentAllocationService allocation,
            TenantResolver tenantResolver,
            TenantConfig tenantConfig,
            TenantLifecycleService tenantLifecycleService,
            ObjectProvider<ShardForwarder> shardForwarder) {
        super(agentProvider, messageConverter, responseBuilder, streamingService, toolConverter);
        this.messageConverter = messageConverter;
        this.responseBuilder = responseBuilder;
        this.executionFacade = executionFacade;
        this.agentLookup = agentLookup;
        this.userIdResolver = userIdResolver;
        this.allocation = allocation;
        this.tenantResolver = tenantResolver;
        this.tenantConfig = tenantConfig;
        this.tenantLifecycleService = tenantLifecycleService;
        this.shardForwarder = shardForwarder;
    }

    /** 非流式入口：把请求翻译为 AgentCommand，经执行门面取终态结果并构建 OpenAI 响应（异常转错误响应）。 */
    @PostMapping(value = BASE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    @Override
    public Object createCompletion(@Valid @RequestBody ChatCompletionsRequest request) {
        // 分片转发：non-owned agent（model）直接转发至 owner；fail-open 无 forwarder 时本地处理。
        // 本轮仅非流式转发（流式经 stream 端点，留待后续 task）。
        ResponseEntity<String> forwarded = tryForward(request);
        if (forwarded != null) {
            return forwarded;
        }
        String tenant = resolveTenant();
        TenantStatus status = TenantContext.status();
        if (Boolean.TRUE.equals(request.getStream())) {
            return ResponseEntity.ok()
                    .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(TenantContext.withTenant(createCompletionStream(request), tenant, status));
        }
        String requestId = UUID.randomUUID().toString();
        AgentCommand command = buildCommand(request, resolveUserId(), sessionHeader());
        // 绑定租户到 TenantContext：执行门面延迟到订阅后读取 current() 做 assertAllowed。
        return TenantContext.withTenant(
                executionFacade.execute(command)
                        .filter(e -> e instanceof AgentResultEvent)
                        .next()
                        .map(e -> responseBuilder.buildResponse(
                                request, ((AgentResultEvent) e).getResult(), requestId))
                        .onErrorResume(error -> Mono.just(
                                responseBuilder.buildErrorResponse(request, error, requestId))),
                tenant, status);
    }

    @PostMapping(value = BASE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    /** 流式入口：把请求翻译为命令，经执行门面产出事件流经映射器转 chunk，封装为 SSE 并以 [DONE] 收尾。 */
    @Override
    public Flux<ServerSentEvent<String>> createCompletionStream(@Valid @RequestBody ChatCompletionsRequest request) {
        String tenant = resolveTenant();
        TenantStatus status = TenantContext.status();
        String requestId = UUID.randomUUID().toString();
        if (Boolean.FALSE.equals(request.getStream())) {
            return TenantContext.withTenant(Flux.error(new IllegalArgumentException(
                    "Non-streaming requests (stream=false) should use the non-streaming endpoint")), tenant, status);
        }
        ChatCompletionsEventMapper mapper =
                new ChatCompletionsEventMapper(requestId, request.getModel(), responseBuilder);
        return TenantContext.withTenant(
                mapper.toChunks(executionFacade.execute(buildCommand(request, resolveUserId(), sessionHeader())))
                        .map(this::toSse)
                        .concatWith(Mono.just(doneSse()))
                        .onErrorResume(error -> Flux.just(toSse(
                                ChatCompletionsChunk.textChunk(requestId, request.getModel(),
                                        "Error: " + (error.getMessage() != null ? error.getMessage() : "unknown"))))),
                tenant, status);
    }

    /** 把 ChatCompletionsRequest 翻译为 AgentCommand：校验 agentId 存在、推导会话 id、转换消息列表。 */
    private AgentCommand buildCommand(ChatCompletionsRequest request, String userId, String sessionHeader) {
        String agentId = request.getModel();
        // 租户授权优先于 agent 存在性检查：越权请求应在 agentLookup 解析前被 TenantAgentAllocationService 拒绝。
        allocation.assertAllowed(resolveTenant(), agentId);
        if (agentLookup.get(agentId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent not found for model: " + agentId);
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages must not be empty");
        }
        String sessionId = (sessionHeader != null && !sessionHeader.isBlank())
                ? sessionHeader : "sess-" + (userId != null ? userId : "anon");
        List<Msg> messages = messageConverter.convertMessages(request.getMessages());
        return new AgentCommand(
                agentId, null, userId, sessionId, messages,
                Map.of(), null, Map.of(), null, null, null);
    }

    /** 从安全主体与请求头 {@code X-Yunxi-User} 解析当前 userId。 */
    private String resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return userIdResolver.resolve(auth, headerValue("X-Yunxi-User"));
    }

    /** 取请求头 {@code X-Yunxi-Session} 作为会话标识（缺失时由 {@link #buildCommand} 推导）。 */
    private String sessionHeader() {
        return headerValue("X-Yunxi-Session");
    }

    /** 自入站 HTTP 头经 TenantResolver 解析租户；未解析则回落 defaultTenant（关闭时为 null）。 */
    private String resolveTenant() {
        String tenant = tenantResolver.resolve(requestHeaders());
        if (tenant == null) {
            tenant = tenantConfig.defaultTenant();
        }
        tenantLifecycleService.admit(tenant); // fail-closed：SUSPENDED/ARCHIVED 抛异常
        return tenant;
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> headers = new HashMap<>();
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest req = servletAttrs.getRequest();
            Enumeration<String> names = req.getHeaderNames();
            while (names != null && names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, req.getHeader(name));
            }
        }
        return headers;
    }

    /** 从当前请求上下文读取指定请求头（非 web 上下文返回 null）。 */
    private String headerValue(String name) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sa) {
            HttpServletRequest req = sa.getRequest();
            return req.getHeader(name);
        }
        return null;
    }

    /**
     * 分片转发探测：若 {@code request.model} 指向非本实例拥有的 agent，序列化请求并转发至 owner。
     * 返回 owner 原始 HTTP 响应；本地处理（owned / 无 forwarder / 异常）时返回 {@code null}（fail-open 本地兜底）。
     */
    private ResponseEntity<String> tryForward(ChatCompletionsRequest request) {
        String agentId = request.getModel();
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        ShardForwarder forwarder = shardForwarder.getIfAvailable();
        if (forwarder == null) {
            return null;
        }
        try {
            String body = objectMapper.writeValueAsString(request);
            return forwarder.forwardIfNeeded(agentId, requestUri(), HttpMethod.POST, body, toHttpHeaders(requestHeaders()));
        } catch (Exception e) {
            return null; // fail-open：序列化/转发异常不阻断本地处理
        }
    }

    /** 取当前请求 URI（供转发拼接 owner baseUrl）；非 web 上下文回落默认路径。 */
    private String requestUri() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sa) {
            return sa.getRequest().getRequestURI();
        }
        return "/v1/chat/completions";
    }

    /** 把入站头 Map 转为 {@link HttpHeaders}，供转发透传。 */
    private HttpHeaders toHttpHeaders(Map<String, String> headers) {
        HttpHeaders result = new HttpHeaders();
        if (headers != null) {
            headers.forEach(result::add);
        }
        return result;
    }

    /** 把 chunk 序列化为 SSE 数据帧；序列化失败回退为 error 帧。 */
    private ServerSentEvent<String> toSse(ChatCompletionsChunk chunk) {
        try {
            return ServerSentEvent.<String>builder()
                    .data(objectMapper.writeValueAsString(chunk))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder()
                    .data("{\"error\":\"serialization_failed\"}")
                    .build();
        }
    }

    /** 构造流结束标记帧 {@code [DONE]}。 */
    private ServerSentEvent<String> doneSse() {
        return ServerSentEvent.<String>builder().data("[DONE]").build();
    }
}
