package io.yunxi.ingress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.runtime.ProtocolUserIdResolver;
import io.yunxi.runtime.Settle;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

/**
 * web 入口（{@link ConversationController}）分片转发接线：请求显式携带 agentId 且非本实例拥有 → 转发至 owner
 * {@code /api/conversations}；无 forwarder（分片关闭）或异常 → fail-open 本地兜底。
 */
@ExtendWith(MockitoExtension.class)
class ConversationControllerForwardingTest {

    @Mock IngressGateway ingressGateway;
    @Mock ProtocolUserIdResolver protocolUserIdResolver;
    @Mock TenantResolver tenantResolver;
    @Mock TenantConfig tenantConfig;
    @Mock ShardForwarder forwarder;

    private ConversationController controllerWith(ShardForwarder resolver) {
        ObjectProvider<ShardForwarder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        return new ConversationController(ingressGateway, protocolUserIdResolver, tenantResolver,
                tenantConfig, provider, new A2uiStreamProcessor(), new MacroDslStreamProcessor());
    }

    @Test
    void explicitAgentIdNonOwnedForwardsToOwner() throws Exception {
        ConversationController controller = controllerWith(forwarder);
        ConversationRequest req = new ConversationRequest();
        req.setAgentId("agent-x");
        req.setText("hi");
        ConversationResponse ownerResp = new ConversationResponse("agent-x", "s1", "owner-reply",
                Settle.Status.OK, null);
        when(forwarder.forwardIfNeeded(eq("agent-x"), eq("/api/conversations"), eq(HttpMethod.POST),
                anyString(), any()))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().writeValueAsString(ownerResp)));
        ResponseEntity<ConversationResponse> result = controller.complete(req);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("owner-reply", result.getBody().getText());
        verifyNoInteractions(ingressGateway);
    }

    @Test
    void noForwarderFallsThroughToLocal() {
        ConversationController local = new ConversationController(ingressGateway, protocolUserIdResolver,
                tenantResolver, tenantConfig, mock(ObjectProvider.class), new A2uiStreamProcessor(),
                new MacroDslStreamProcessor());
        ConversationRequest req = new ConversationRequest();
        req.setText("hi");
        ConversationResponse localResp = new ConversationResponse("a", "s", "local-reply", Settle.Status.OK, null);
        when(protocolUserIdResolver.resolve(any(), any())).thenReturn("u1");
        when(tenantConfig.enabled()).thenReturn(false);
        when(ingressGateway.complete(any())).thenReturn(Mono.just(localResp));
        ResponseEntity<ConversationResponse> result = local.complete(req);
        assertEquals("local-reply", result.getBody().getText());
        verify(ingressGateway).complete(any());
    }
}
