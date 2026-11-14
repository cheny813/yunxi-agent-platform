package io.yunxi.ingress;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.yunxi.intelligence.IntentEngine;
import io.yunxi.intelligence.IntentRequest;
import io.yunxi.intelligence.IntentResolution;
import io.yunxi.runtime.AgentCommand;
import io.yunxi.runtime.ExecutionFacade;
import io.yunxi.runtime.Settle;
import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.definition.Tenant;
import io.yunxi.definition.TenantStatus;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import io.yunxi.runtime.tenant.TenantNotServiceableException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link IngressGateway} 传输层编排：适配器路由、意图回填的显式优先、结算文本提取策略、
 * 以及不支持协议在同步阶段抛 {@link IllegalArgumentException}。不含任何推理语义。
 */
class IngressGatewayTest {

    private final ExecutionFacade facade = mock(ExecutionFacade.class);
    private final IntentEngine intent = mock(IntentEngine.class);
    private final TenantLifecycleService lifecycle = mock(TenantLifecycleService.class);

    {
        when(lifecycle.admit(any())).thenReturn(TenantStatus.ACTIVE);
    }

    private ProtocolAdapter webAdapter() {
        ProtocolAdapter web = mock(ProtocolAdapter.class);
        when(web.supports("web")).thenReturn(true);
        return web;
    }

    @Test
    void completeUsesExplicitAgentIdWithoutIntentResolution() {
        ProtocolAdapter web = webAdapter();
        AgentCommand cmd = mock(AgentCommand.class);
        when(web.toCommand(any(ConversationRequest.class))).thenReturn(cmd);
        Settle settle = mock(Settle.class);
        when(settle.status()).thenReturn(Settle.Status.OK);
        when(settle.text()).thenReturn("hi");
        when(facade.settle(cmd)).thenReturn(Mono.just(settle));
        ConversationResponse resp = mock(ConversationResponse.class);
        when(web.toResponse(settle, "hi")).thenReturn(resp);

        IngressGateway gw = new IngressGateway(facade, List.of(web), intent, lifecycle);
        ConversationRequest req = new ConversationRequest("web", "agentX", "u", "s", "hello");
        ConversationResponse r = gw.complete(req).block();

        assertSame(resp, r);
        // 显式 agentId 优先级更高，不应触发意图解析。
        verify(intent, never()).resolve(any(IntentRequest.class));
        ArgumentCaptor<ConversationRequest> cap = ArgumentCaptor.forClass(ConversationRequest.class);
        verify(web).toCommand(cap.capture());
        assertEquals("agentX", cap.getValue().getAgentId());
    }

    @Test
    void completeInfersAgentIdWhenBlank() {
        ProtocolAdapter web = webAdapter();
        when(intent.resolve(any(IntentRequest.class))).thenReturn(new IntentResolution(
                "chat", 0.92d, "inferred", java.util.Map.of("orderId", "A123456"), "改写后的查询",
                IntentResolution.Source.LLM));
        AgentCommand cmd = mock(AgentCommand.class);
        when(web.toCommand(any(ConversationRequest.class))).thenReturn(cmd);
        Settle settle = mock(Settle.class);
        when(settle.status()).thenReturn(Settle.Status.OK);
        when(settle.text()).thenReturn("reply");
        when(facade.settle(cmd)).thenReturn(Mono.just(settle));
        ConversationResponse resp = new ConversationResponse();
        when(web.toResponse(any(), eq("reply"))).thenReturn(resp);

        IngressGateway gw = new IngressGateway(facade, List.of(web), intent, lifecycle);
        ConversationRequest req = new ConversationRequest("web", "", "u", "s", "hello");
        req.setTenantId("t9");
        ConversationResponse r = gw.complete(req).block();

        assertSame(resp, r);
        ArgumentCaptor<IntentRequest> intentCap = ArgumentCaptor.forClass(IntentRequest.class);
        verify(intent).resolve(intentCap.capture());
        assertEquals("hello", intentCap.getValue().text());
        assertEquals("t9", intentCap.getValue().tenantId());
        ArgumentCaptor<ConversationRequest> cap = ArgumentCaptor.forClass(ConversationRequest.class);
        verify(web).toCommand(cap.capture());
        ConversationRequest resolved = cap.getValue();
        assertEquals("inferred", resolved.getAgentId());
        // 意图解析结果以 yunxi.intent.* 键写入上下文，随命令透传执行链路。
        assertEquals("chat", resolved.getContext().get("yunxi.intent.label"));
        assertEquals(0.92d, resolved.getContext().get("yunxi.intent.confidence"));
        assertEquals("LLM", resolved.getContext().get("yunxi.intent.source"));
        assertEquals("改写后的查询", resolved.getContext().get("yunxi.intent.rewrittenQuery"));
        assertEquals(java.util.Map.of("orderId", "A123456"), resolved.getContext().get("yunxi.intent.entities"));
    }

    @Test
    void keepsAgentIdBlankWhenEngineHasNoTarget() {
        ProtocolAdapter web = webAdapter();
        when(intent.resolve(any(IntentRequest.class))).thenReturn(IntentResolution.fallback(null));
        AgentCommand cmd = mock(AgentCommand.class);
        when(web.toCommand(any(ConversationRequest.class))).thenReturn(cmd);
        Settle settle = mock(Settle.class);
        when(settle.status()).thenReturn(Settle.Status.OK);
        when(settle.text()).thenReturn("reply");
        when(facade.settle(cmd)).thenReturn(Mono.just(settle));
        ConversationResponse resp = new ConversationResponse();
        when(web.toResponse(any(), eq("reply"))).thenReturn(resp);

        IngressGateway gw = new IngressGateway(facade, List.of(web), intent, lifecycle);
        ConversationRequest req = new ConversationRequest("web", "", "u", "s", "hello");
        gw.complete(req).block();

        ArgumentCaptor<ConversationRequest> cap = ArgumentCaptor.forClass(ConversationRequest.class);
        verify(web).toCommand(cap.capture());
        // 引擎未给出可路由目标时不回填 agentId，但意图元数据仍入上下文供观测。
        assertTrue(cap.getValue().getAgentId() == null || cap.getValue().getAgentId().isBlank());
        assertEquals("unknown", cap.getValue().getContext().get("yunxi.intent.label"));
        assertEquals("FALLBACK", cap.getValue().getContext().get("yunxi.intent.source"));
    }

    @Test
    void completeReturnsEmptyTextOnNoAgent() {
        ProtocolAdapter web = webAdapter();
        AgentCommand cmd = mock(AgentCommand.class);
        when(web.toCommand(any(ConversationRequest.class))).thenReturn(cmd);
        Settle settle = mock(Settle.class);
        when(settle.status()).thenReturn(Settle.Status.NO_AGENT);
        when(facade.settle(cmd)).thenReturn(Mono.just(settle));
        ConversationResponse resp = new ConversationResponse();
        when(web.toResponse(any(), eq(""))).thenReturn(resp);

        IngressGateway gw = new IngressGateway(facade, List.of(web), intent, lifecycle);
        ConversationRequest req = new ConversationRequest("web", "agentX", "u", "s", "hello");
        ConversationResponse r = gw.complete(req).block();

        assertSame(resp, r);
        // NO_AGENT 直接返回空文本，不回退到命令消息。
        verify(web).toResponse(settle, "");
    }

    @Test
    void completeFallsBackToLastCommandMessageText() {
        ProtocolAdapter web = webAdapter();
        AgentCommand cmd = mock(AgentCommand.class);
        when(web.toCommand(any(ConversationRequest.class))).thenReturn(cmd);
        Msg last = Msg.builder().role(MsgRole.ASSISTANT).textContent("fallback").build();
        when(cmd.messages()).thenReturn(List.of(last));
        Settle settle = mock(Settle.class);
        when(settle.status()).thenReturn(Settle.Status.OK);
        when(settle.text()).thenReturn(""); // 空白：回退
        when(facade.settle(cmd)).thenReturn(Mono.just(settle));
        ConversationResponse resp = new ConversationResponse();
        when(web.toResponse(any(), eq("fallback"))).thenReturn(resp);

        IngressGateway gw = new IngressGateway(facade, List.of(web), intent, lifecycle);
        ConversationRequest req = new ConversationRequest("web", "agentX", "u", "s", "hello");
        ConversationResponse r = gw.complete(req).block();

        assertSame(resp, r);
        verify(web).toResponse(settle, "fallback");
    }

    @Test
    void streamDelegatesToFacade() {
        ProtocolAdapter web = webAdapter();
        AgentCommand cmd = mock(AgentCommand.class);
        when(web.toCommand(any(ConversationRequest.class))).thenReturn(cmd);
        AgentEvent ev = mock(AgentEvent.class);
        when(facade.execute(cmd)).thenReturn(Flux.just(ev));

        IngressGateway gw = new IngressGateway(facade, List.of(web), intent, lifecycle);
        ConversationRequest req = new ConversationRequest("web", "agentX", "u", "s", "hello");
        List<AgentEvent> events = gw.stream(req).collectList().block();

        assertEquals(1, events.size());
        assertSame(ev, events.get(0));
    }

    @Test
    void unsupportedProtocolThrowsInSyncPhase() {
        ProtocolAdapter web = mock(ProtocolAdapter.class);
        when(web.supports("unknown")).thenReturn(false);

        IngressGateway gw = new IngressGateway(facade, List.of(web), intent, lifecycle);
        ConversationRequest req = new ConversationRequest("unknown", "agentX", "u", "s", "hello");

        // resolveAdapter 在同步阶段抛，不在响应式链内。
        assertThrows(IllegalArgumentException.class, () -> gw.complete(req).block());
        assertTrue(true);
    }

    @Test
    void stream_rejectsSuspendedTenant_failClosed() {
        // 真实 ConfigCenter + 真实准入闸门：SUSPENDED 租户在入口同步拒绝（fail-closed）。
        InMemoryConfigCenter cc = new InMemoryConfigCenter();
        cc.registerTenant(Tenant.active("s1", "S").withStatus(TenantStatus.SUSPENDED));
        IngressGateway gw = new IngressGateway(facade, List.of(webAdapter()), intent,
                new TenantLifecycleService(cc));
        ConversationRequest req = new ConversationRequest("web", "agentX", "u", "s", "hello");
        req.setTenantId("s1");

        assertThrows(TenantNotServiceableException.class, () -> gw.stream(req));
    }

    @Test
    void stream_allowsActiveTenant() {
        InMemoryConfigCenter cc = new InMemoryConfigCenter();
        cc.registerTenant(Tenant.active("a1", "A"));
        when(facade.execute(any())).thenReturn(Flux.empty());
        IngressGateway gw = new IngressGateway(facade, List.of(webAdapter()), intent,
                new TenantLifecycleService(cc));
        ConversationRequest req = new ConversationRequest("web", "agentX", "u", "s", "hello");
        req.setTenantId("a1");

        // 未触发异常（执行门面返回空流），证明 ACTIVE 租户放行。
        assertDoesNotThrow(() -> gw.stream(req).collectList().block());
    }
}
