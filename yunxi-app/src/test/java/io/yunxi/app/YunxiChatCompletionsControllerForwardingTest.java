package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.converter.OpenAIToolConverter;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import io.yunxi.ingress.ShardForwarder;
import io.yunxi.runtime.AgentLookup;
import io.yunxi.runtime.ExecutionFacade;
import io.yunxi.runtime.ProtocolUserIdResolver;
import io.yunxi.runtime.TenantAgentAllocationService;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import io.yunxi.runtime.tenant.TenantResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * ChatCompletions 入口分片转发接线：non-owned model（agent）经 {@link ShardForwarder} 转发至 owner；
 * 本轮仅非流式转发（流式经 stream 端点，留待后续 task）。
 */
@ExtendWith(MockitoExtension.class)
class YunxiChatCompletionsControllerForwardingTest {

    @Mock ObjectProvider<ReActAgent> agentProvider;
    @Mock ChatMessageConverter messageConverter;
    @Mock ChatCompletionsResponseBuilder responseBuilder;
    @Mock ChatCompletionsStreamingService streamingService;
    @Mock OpenAIToolConverter toolConverter;
    @Mock ExecutionFacade executionFacade;
    @Mock AgentLookup agentLookup;
    @Mock ProtocolUserIdResolver userIdResolver;
    @Mock TenantAgentAllocationService allocation;
    @Mock TenantResolver tenantResolver;
    @Mock TenantConfig tenantConfig;
    @Mock TenantLifecycleService tenantLifecycleService;
    @Mock ShardForwarder forwarder;

    private YunxiChatCompletionsController controllerWith(ShardForwarder resolver) {
        ObjectProvider<ShardForwarder> provider = mock(ObjectProvider.class);
        // lenient：blank model 路径在调用 getIfAvailable() 前已 return，stub 可能不被使用。
        lenient().when(provider.getIfAvailable()).thenReturn(resolver);
        return new YunxiChatCompletionsController(agentProvider, messageConverter, responseBuilder,
                streamingService, toolConverter, executionFacade, agentLookup, userIdResolver, allocation,
                tenantResolver, tenantConfig, tenantLifecycleService, provider);
    }

    @Test
    void nonOwnedModelForwardsToOwner() {
        YunxiChatCompletionsController controller = controllerWith(forwarder);
        ChatCompletionsRequest req = new ChatCompletionsRequest();
        req.setModel("agent-x");
        when(forwarder.forwardIfNeeded(eq("agent-x"), anyString(), eq(HttpMethod.POST), anyString(), any()))
                .thenReturn(ResponseEntity.ok("owner-response"));
        Object result = controller.createCompletion(req);
        ResponseEntity<String> resp = (ResponseEntity<String>) result;
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("owner-response", resp.getBody());
    }

    @Test
    void blankModelDoesNotForward() {
        // model 为空 → 不转发，落本地路径（本地 agent 未找到抛 404，符合预期）；forwarder 未被触达。
        YunxiChatCompletionsController controller = controllerWith(forwarder);
        ChatCompletionsRequest req = new ChatCompletionsRequest();
        try {
            controller.createCompletion(req);
        } catch (ResponseStatusException expected) {
            // 本地路径 agent 未找到（model 空）→ 404
        }
        verify(forwarder, never()).forwardIfNeeded(any(), any(), any(), any(), any());
    }
}
