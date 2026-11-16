package io.yunxi.ingress;

import io.agentscope.core.event.AgentEvent;
import io.yunxi.intelligence.IntentEngine;
import io.yunxi.intelligence.IntentRequest;
import io.yunxi.intelligence.IntentResolution;
import io.yunxi.runtime.AgentCommand;
import io.yunxi.runtime.ExecutionFacade;
import io.yunxi.runtime.NoSuchAgentException;
import io.yunxi.runtime.Settle;
import io.yunxi.definition.TenantStatus;
import io.yunxi.runtime.tenant.TenantContext;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 入口织面。统一入口网关。聚合并路由 web / A2A / AG-UI / Chat Completions 四种协议，
 * 把协议请求交给对应 {@link ProtocolAdapter} 翻译为 {@link AgentCommand}，再经唯一执行门面
 * {@link ExecutionFacade} 运行；最终把事件流末端的回复文本与 {@link Settle} 回写为协议响应。
 *
 * <p>本类是传输层，不含任何推理语义；所有横切关切统一在运行时装配点（AgentConfigurer）完成。
 */
public class IngressGateway {

    private final ExecutionFacade executionFacade;

    private final List<ProtocolAdapter> adapters;

    private final IntentEngine intentEngine;

    private final TenantLifecycleService tenantLifecycleService;

    /** 注入执行门面、协议适配器列表、意图引擎与租户准入闸门（门面与意图引擎不可为空）。 */
    public IngressGateway(ExecutionFacade executionFacade,
            List<ProtocolAdapter> adapters, IntentEngine intentEngine,
            TenantLifecycleService tenantLifecycleService) {
        this.executionFacade = Objects.requireNonNull(executionFacade);
        this.adapters = adapters != null ? adapters : List.of();
        this.intentEngine = Objects.requireNonNull(intentEngine);
        this.tenantLifecycleService = Objects.requireNonNull(tenantLifecycleService);
    }

    /** 按协议流式执行，透传 AgentScope-Java 事件流。准入闸门在入口同步拒绝非 ACTIVE 租户。 */
    public Flux<AgentEvent> stream(ConversationRequest request) {
        ProtocolAdapter adapter = resolveAdapter(request.getProtocol());
        String tenantId = request.getTenantId();
        // 准入判定先于意图解析：非 ACTIVE 租户不得触达分类模型，避免为必然被拒的请求付出模型调用。
        TenantStatus status = tenantLifecycleService.admit(tenantId);
        AgentCommand command = adapter.toCommand(resolveAgentId(request));
        return TenantContext.withTenant(executionFacade.execute(command), tenantId, status);
    }

    /** 按协议执行并结算为单一响应（提取末端回复文本）。准入闸门在入口同步拒绝非 ACTIVE 租户。 */
    public Mono<ConversationResponse> complete(ConversationRequest request) {
        ProtocolAdapter adapter = resolveAdapter(request.getProtocol());
        String tenantId = request.getTenantId();
        TenantStatus status = tenantLifecycleService.admit(tenantId);
        AgentCommand command = adapter.toCommand(resolveAgentId(request));
        return TenantContext.withTenant(
                executionFacade.settle(command)
                        .map(settle -> adapter.toResponse(settle, extractText(settle, command))),
                tenantId, status);
    }

    /**
     * 入口路由：当请求未显式指定 {@code agentId} 时，调用意图引擎（带租户与会话上下文）推断目标
     * Agent 并回填；解析结果（标签 / 置信度 / 来源 / 实体 / 改写文本）以 {@code yunxi.intent.*}
     * 键写入请求上下文，随命令透传执行链路。显式指定 {@code agentId} 的请求优先级更高，
     * 不触发意图解析（避免覆盖调用方的明确意图，且零模型调用）。
     */
    private ConversationRequest resolveAgentId(ConversationRequest request) {
        if (request.getAgentId() != null && !request.getAgentId().isBlank()) {
            return request;
        }
        IntentResolution resolution = intentEngine.resolve(
                new IntentRequest(request.getText(), request.getTenantId(), request.getSessionId(), 0));
        if (resolution.hasTarget()) {
            request.setAgentId(resolution.targetAgentId());
        }
        request.getContext().put("yunxi.intent.label", resolution.label());
        request.getContext().put("yunxi.intent.confidence", resolution.confidence());
        request.getContext().put("yunxi.intent.source", resolution.source().name());
        if (resolution.rewrittenQuery() != null) {
            request.getContext().put("yunxi.intent.rewrittenQuery", resolution.rewrittenQuery());
        }
        if (!resolution.entities().isEmpty()) {
            request.getContext().put("yunxi.intent.entities", resolution.entities());
        }
        return request;
    }

    /** 按协议标识选出支持的适配器；无匹配时抛 {@link IllegalArgumentException}。 */
    private ProtocolAdapter resolveAdapter(String protocol) {
        for (ProtocolAdapter adapter : adapters) {
            if (adapter.supports(protocol)) {
                return adapter;
            }
        }
        throw new IllegalArgumentException("unsupported protocol: " + protocol);
    }

    /** 优先取结算携带的回复文本，缺失时回退到命令末条消息文本。 */
    private String extractText(Settle settle, AgentCommand command) {
        if (settle.status() == Settle.Status.NO_AGENT) {
            return "";
        }
        // 优先取执行终态携带的 Agent 真实回复；上游未聚合时回退到命令消息。
        if (settle.text() != null && !settle.text().isBlank()) {
            return settle.text();
        }
        return command.messages().isEmpty() ? "" : command.messages().get(command.messages().size() - 1).getTextContent();
    }
}
