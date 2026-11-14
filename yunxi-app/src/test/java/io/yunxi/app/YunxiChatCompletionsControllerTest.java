package io.yunxi.app;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.converter.OpenAIToolConverter;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import io.yunxi.config.InMemoryConfigCenter;
import io.yunxi.config.TenantAgentAllocation;
import io.yunxi.runtime.AgentLookup;
import io.yunxi.runtime.ExecutionFacade;
import io.yunxi.runtime.NoSuchAgentException;
import io.yunxi.runtime.ProtocolUserIdResolver;
import io.yunxi.runtime.TenantAgentAllocationService;
import io.yunxi.runtime.tenant.HeaderTenantResolver;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 固化 Chat Completions 协议租户授权：createCompletion -> buildCommand 在 agentLookup.get 之前调用
 * allocation.assertAllowed(resolveTenant(), agentId)；租户经 HeaderTenantResolver 从请求头解析并回退到
 * TenantConfig.defaultTenant()。本测试验证越权 agent 在真实网关执行前即被 NO_AGENT 拦截。
 */
class YunxiChatCompletionsControllerTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createCompletionRejectsWhenTenantNotAllowed() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Tenant-Id", "t1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        InMemoryConfigCenter configCenter = new InMemoryConfigCenter();
        configCenter.registerTenantAllocation(new TenantAgentAllocation(Map.of("t1", Set.of("a1")), Set.of()));
        TenantAgentAllocationService allocation = new TenantAgentAllocationService(configCenter);

        ExecutionFacade executionFacade = Mockito.mock(ExecutionFacade.class);
        AgentLookup agentLookup = Mockito.mock(AgentLookup.class);

        YunxiChatCompletionsController controller = new YunxiChatCompletionsController(
                Mockito.mock(ObjectProvider.class),
                Mockito.mock(ChatMessageConverter.class),
                Mockito.mock(ChatCompletionsResponseBuilder.class),
                Mockito.mock(ChatCompletionsStreamingService.class),
                Mockito.mock(OpenAIToolConverter.class),
                executionFacade,
                agentLookup,
                new ProtocolUserIdResolver(),
                allocation,
                new HeaderTenantResolver("X-Tenant-Id"),
                TenantConfig.DISABLED,
                new TenantLifecycleService(configCenter),
                Mockito.mock(ObjectProvider.class));

        ChatCompletionsRequest request = new ChatCompletionsRequest();
        request.setModel("a2");
        // 租户 t1 仅允许 a1；createCompletion -> buildCommand 在真正执行前 assertAllowed 应拦截 a2。
        assertThrows(NoSuchAgentException.class, () -> controller.createCompletion(request));
    }
}
