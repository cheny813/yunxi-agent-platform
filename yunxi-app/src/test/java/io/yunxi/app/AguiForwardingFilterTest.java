package io.yunxi.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.spring.boot.agui.common.AguiProperties;
import io.yunxi.app.shard.AgentForwardingRouter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link AguiForwardingFilter} 决策测试（纯单元）：非 AG-UI 路径 / 分片关闭 / owned → 透传 {@code chain}；
 * non-owned → 经 {@link AgentForwardingRouter#forwardStreamTo} 转发且不调用 {@code chain}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AguiForwardingFilterTest {

    @Mock
    private AguiProperties props;
    @Mock
    private ObjectProvider<AgentForwardingRouter> routerProvider;
    @Mock
    private AgentForwardingRouter router;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    private AguiForwardingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AguiForwardingFilter(routerProvider, props);
        when(props.getPathPrefix()).thenReturn("/agui");
        when(props.isEnablePathRouting()).thenReturn(false);
        when(props.getAgentIdHeader()).thenReturn("X-Agent-Id");
        when(props.getDefaultAgentId()).thenReturn("default");
    }

    private void mockRequest(String uri, String contextPath, String agentIdHeader, String body) throws Exception {
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getContextPath()).thenReturn(contextPath);
        when(request.getHeaderNames()).thenReturn(null);
        if (agentIdHeader != null) {
            when(request.getHeader("X-Agent-Id")).thenReturn(agentIdHeader);
        }
        when(request.getInputStream()).thenReturn(new TestServletInputStream(body.getBytes(StandardCharsets.UTF_8)));
        ServletOutputStream out = mock(ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(out);
    }

    /** 将 {@link ByteArrayInputStream} 适配为 {@link jakarta.servlet.ServletInputStream} 用于测试。 */
    static class TestServletInputStream extends jakarta.servlet.ServletInputStream {
        private final ByteArrayInputStream delegate;

        TestServletInputStream(byte[] data) {
            this.delegate = new ByteArrayInputStream(data);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
            // no-op
        }
    }

    @Test
    void nonAguiPathPassesThrough() throws Exception {
        when(routerProvider.getIfAvailable()).thenReturn(router);
        mockRequest("/other/api", "", null, "{}");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(router, never()).forwardStreamTo(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shardDisabledPassesThrough() throws Exception {
        when(routerProvider.getIfAvailable()).thenReturn(null);
        mockRequest("/agui/run", "", null, "{}");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(router, never()).forwardStreamTo(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonOwnedForwardsAndDoesNotChain() throws Exception {
        when(routerProvider.getIfAvailable()).thenReturn(router);
        when(router.forwardStreamTo(any(), any(), any(), any(), any(), any())).thenReturn(true);
        mockRequest("/agui/run", "", "agent-x", "{}");

        filter.doFilter(request, response, chain);

        verify(router).forwardStreamTo(any(), any(), eq("/agui/run"), any(), any(), any());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void ownedFallsThroughToChain() throws Exception {
        when(routerProvider.getIfAvailable()).thenReturn(router);
        when(router.forwardStreamTo(any(), any(), any(), any(), any(), any())).thenReturn(false);
        mockRequest("/agui/run", "", "agent-x", "{}");

        filter.doFilter(request, response, chain);

        verify(router).forwardStreamTo(any(), any(), any(), any(), any(), any());
        verify(chain).doFilter(request, response);
    }
}
