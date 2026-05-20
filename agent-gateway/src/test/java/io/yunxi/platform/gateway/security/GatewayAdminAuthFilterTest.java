package io.yunxi.platform.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 网关管理 API 认证过滤器单元测试
 *
 * <p>
 * 验证 X-Gateway-Admin-Token 和 Bearer Token 两种认证方式，
 * 以及向后兼容（未配置Token时放行）的行为。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class GatewayAdminAuthFilterTest {

    private GatewayAdminAuthFilter filter;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new GatewayAdminAuthFilter();
    }

    @Nested
    @DisplayName("路径过滤测试")
    class PathFilteringTests {

        @Test
        @DisplayName("非管理API路径应直接放行")
        void nonAdminPath_shouldPassThrough() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/other/endpoint");

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verify(response, never()).setStatus(anyInt());
        }

        @Test
        @DisplayName("health端点应直接放行")
        void healthEndpoint_shouldPassThrough() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/health");

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("管理API路径应进行认证")
        void adminPath_shouldRequireAuth() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            setAdminToken("secret-token");
            setupErrorResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain, never()).doFilter(request, response);
            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("Token认证测试")
    class TokenAuthTests {

        @Test
        @DisplayName("X-Gateway-Admin-Token正确时应放行")
        void correctAdminTokenHeader_shouldPassThrough() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            when(request.getHeader("X-Gateway-Admin-Token")).thenReturn("secret-token");
            setAdminToken("secret-token");

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Authorization Bearer Token正确时应放行")
        void correctBearerToken_shouldPassThrough() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            when(request.getHeader("Authorization")).thenReturn("Bearer secret-token");
            setAdminToken("secret-token");

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Token错误时应返回401")
        void incorrectToken_shouldReturn401() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            when(request.getHeader("X-Gateway-Admin-Token")).thenReturn("wrong-token");
            when(request.getRemoteAddr()).thenReturn("192.168.1.1");
            setAdminToken("secret-token");
            setupErrorResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain, never()).doFilter(request, response);
            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("Token为空时应返回401")
        void emptyToken_shouldReturn401() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            when(request.getHeader("X-Gateway-Admin-Token")).thenReturn("");
            when(request.getRemoteAddr()).thenReturn("192.168.1.1");
            setAdminToken("secret-token");
            setupErrorResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain, never()).doFilter(request, response);
            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("无Token时应返回401")
        void noToken_shouldReturn401() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            when(request.getRemoteAddr()).thenReturn("192.168.1.1");
            setAdminToken("secret-token");
            setupErrorResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain, never()).doFilter(request, response);
            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("向后兼容测试")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("未配置adminToken时应放行（向后兼容）")
        void noAdminTokenConfigured_shouldPassThrough() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            setAdminToken(""); // 空配置

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("adminToken为null时应放行")
        void nullAdminToken_shouldPassThrough() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            setAdminToken(null);

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("Token提取优先级测试")
    class TokenExtractionPriorityTests {

        @Test
        @DisplayName("X-Gateway-Admin-Token应优先于Authorization")
        void adminTokenHeaderShouldTakePriority() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            when(request.getHeader("X-Gateway-Admin-Token")).thenReturn("correct-token");
            when(request.getHeader("Authorization")).thenReturn("Bearer wrong-token");
            setAdminToken("correct-token");

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("X-Gateway-Admin-Token为空时应尝试Authorization")
        void emptyAdminTokenHeaderShouldFallbackToAuth() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            when(request.getHeader("X-Gateway-Admin-Token")).thenReturn("");
            when(request.getHeader("Authorization")).thenReturn("Bearer secret-token");
            setAdminToken("secret-token");

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("Token应去除前后空格")
        void tokenShouldBeTrimmed() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            when(request.getHeader("X-Gateway-Admin-Token")).thenReturn("  secret-token  ");
            setAdminToken("secret-token");

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Bearer Token应去除前后空格")
        void bearerTokenShouldBeTrimmed() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            when(request.getHeader("Authorization")).thenReturn("Bearer   secret-token  ");
            setAdminToken("secret-token");

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Authorization不以Bearer开头时应返回401")
        void authWithoutBearerPrefix_shouldReturn401() throws ServletException, IOException {
            when(request.getRequestURI()).thenReturn("/api/gateway/channels/wecom/start");
            when(request.getHeader("Authorization")).thenReturn("Basic secret-token");
            when(request.getRemoteAddr()).thenReturn("192.168.1.1");
            setAdminToken("secret-token");
            setupErrorResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain, never()).doFilter(request, response);
            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    /**
     * 设置adminToken配置值
     */
    private void setAdminToken(String token) {
        ReflectionTestUtils.setField(filter, "adminToken", token);
    }

    /**
     * 设置错误响应Writer
     */
    private void setupErrorResponse() throws IOException {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);
    }
}
