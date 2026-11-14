package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.yunxi.app.A2aServerRegistry;
import io.yunxi.ingress.ShardForwarder;
import io.yunxi.runtime.TenantAgentAllocationService;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantLifecycleService;
import io.yunxi.runtime.tenant.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * A2A 入口分片转发接线：non-owned agent 经 {@link ShardForwarder} 转发至 owner；
 * 无 forwarder（分片关闭）时落本地路径（fail-open）。
 */
@ExtendWith(MockitoExtension.class)
class A2aControllerForwardingTest {

    @Mock A2aServerRegistry registry;
    @Mock TenantAgentAllocationService allocation;
    @Mock TenantResolver tenantResolver;
    @Mock TenantConfig tenantConfig;
    @Mock TenantLifecycleService tenantLifecycleService;
    @Mock ShardForwarder forwarder;
    @Mock HttpServletRequest servletRequest;

    private A2aController controllerWith(ShardForwarder resolver) {
        ObjectProvider<ShardForwarder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        return new A2aController(registry, allocation, tenantResolver, tenantConfig,
                tenantLifecycleService, provider);
    }

    @Test
    void nonOwnedForwardsToOwner() {
        A2aController controller = controllerWith(forwarder);
        when(servletRequest.getRequestURI()).thenReturn("/a2a/agent-x");
        when(servletRequest.getMethod()).thenReturn("POST");
        when(forwarder.forwardIfNeeded(eq("agent-x"), eq("/a2a/agent-x"), eq(HttpMethod.POST), eq("{}"), any()))
                .thenReturn(ResponseEntity.ok("owner-response"));
        ResponseEntity<?> result = controller.handle("agent-x", Map.of(), "{}", servletRequest);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("owner-response", result.getBody());
        // 转发命中后不应触达本地解析/注册表路径
        verifyNoInteractions(registry, allocation, tenantLifecycleService, tenantResolver);
    }

    @Test
    void forwarderAbsentFallsThroughToLocal() {
        A2aController local = new A2aController(registry, allocation, tenantResolver, tenantConfig,
                tenantLifecycleService, mock(ObjectProvider.class));
        // forwarder 为 null → handle 不进入转发分支，故不依赖 servletRequest 的 URI/Method；
        // 落本地路径后 registry.get 返回 null → 404（agent 本地不存在）。
        when(tenantResolver.resolve(any())).thenReturn("t1");
        when(registry.get("agent-x")).thenReturn(null);
        ResponseEntity<?> result = local.handle("agent-x", Map.of(), "{}", servletRequest);
        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
    }
}
