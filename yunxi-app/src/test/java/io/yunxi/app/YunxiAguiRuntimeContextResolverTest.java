package io.yunxi.app;

import io.yunxi.runtime.ProtocolUserIdResolver;
import io.yunxi.runtime.tenant.HeaderTenantResolver;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantResolver;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agent.RuntimeContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 固化 AG-UI 身份解析的线程模型不回归：AG-UI 控制器把请求处理提交到独立线程池（非 HTTP 请求线程），
 * Spring Security 的 SecurityContext ThreadLocal 在异步线程为 null；身份必须由 {@link UserIdCaptureFilter}
 * 在请求线程解析并暂存到请求属性，resolver 再从同一请求对象读回。本测试验证：即使 SecurityContext 为空
 * （模拟异步线程），resolver 仍能从请求属性取回 userId；并验证 SecurityContext / 请求头两级回退。
 */
class YunxiAguiRuntimeContextResolverTest {

    private final ProtocolUserIdResolver userIdResolver = new ProtocolUserIdResolver();
    private final TenantResolver tenantResolver = new HeaderTenantResolver("X-Tenant-Id");
    private final TenantConfig tenantConfig = TenantConfig.DISABLED;
    private final YunxiAguiRuntimeContextResolver resolver =
            new YunxiAguiRuntimeContextResolver(userIdResolver, tenantResolver, tenantConfig);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private AguiRuntimeContextRequest<HttpServletRequest> requestWith(MockHttpServletRequest servletRequest) {
        return AguiRuntimeContextRequest.<HttpServletRequest>builder()
                .transport(AguiRuntimeContextRequest.Transport.MVC)
                .input(RunAgentInput.builder().threadId("t-1").runId("r-1").build())
                .nativeRequest(servletRequest)
                .build();
    }

    @Test
    void resolveReadsUserIdFromRequestAttributeOnAsyncThread() {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserIdCaptureFilter.USER_ID_ATTR, "user-attr");
        RuntimeContext ctx = resolver.resolve(requestWith(req));
        assertEquals("user-attr", ctx.getUserId());
    }

    @Test
    void resolveFallsBackToSecurityContextWhenAttributeAbsent() {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("user-auth", null, "ROLE_USER"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        RuntimeContext ctx = resolver.resolve(requestWith(req));
        assertEquals("user-auth", ctx.getUserId());
    }

    @Test
    void resolveFallsBackToHeaderWhenNoContextAndNoAttribute() {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest();
        AguiRuntimeContextRequest<HttpServletRequest> request = AguiRuntimeContextRequest.<HttpServletRequest>builder()
                .transport(AguiRuntimeContextRequest.Transport.MVC)
                .input(RunAgentInput.builder().threadId("t-1").runId("r-1").build())
                .nativeRequest(req)
                .headers(Map.of("X-Yunxi-User", List.of("user-header")))
                .build();
        RuntimeContext ctx = resolver.resolve(request);
        assertEquals("user-header", ctx.getUserId());
    }

    @Test
    void resolveReturnsNullWhenNoIdentity() {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertNull(resolver.resolve(requestWith(req)));
    }

    @Test
    void requestAttributeTakesPrecedenceOverSecurityContext() {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("user-auth", null));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserIdCaptureFilter.USER_ID_ATTR, "user-attr");
        RuntimeContext ctx = resolver.resolve(requestWith(req));
        assertEquals("user-attr", ctx.getUserId());
    }
}
