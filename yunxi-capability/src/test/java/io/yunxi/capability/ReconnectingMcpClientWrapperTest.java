package io.yunxi.capability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link ReconnectingMcpClientWrapper} 的协议无关重连语义：连接类异常触发一次重建重试，
 * 非连接异常原样传播；listTools 的连接错误降级为空列表；初始构建失败在 initialize 阶段降级。
 */
class ReconnectingMcpClientWrapperTest {

    private ReconnectingMcpClientWrapper wrap(McpClientWrapper delegate) {
        when(delegate.initialize()).thenReturn(Mono.empty());
        return new ReconnectingMcpClientWrapper("svc", () -> delegate);
    }

    @Test
    void callToolRetriesOnceOnConnectionErrorThenSucceeds() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        McpSchema.CallToolResult ok = mock(McpSchema.CallToolResult.class);
        when(delegate.callTool(eq("t"), anyMap()))
                .thenReturn(Mono.error(new IOException("connection reset")), Mono.just(ok));
        ReconnectingMcpClientWrapper w = wrap(delegate);

        McpSchema.CallToolResult r = w.callTool("t", Map.of()).block();

        assertTrue(r == ok);
        // 首次失败触发 rebuild + 重试，共调用两次 callTool、一次 close。
        verify(delegate, times(2)).callTool(any(), any());
        verify(delegate, times(1)).close();
    }

    @Test
    void callToolDoesNotRetryOnNonConnectionError() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.callTool(any(), any()))
                .thenThrow(new RuntimeException("internal boom"));
        ReconnectingMcpClientWrapper w = wrap(delegate);

        assertThrows(RuntimeException.class, () -> w.callTool("t", Map.of()).block());
        // 非连接异常不应触发 rebuild / 重试。
        verify(delegate, times(1)).callTool(any(), any());
        verify(delegate, times(0)).close();
    }

    @Test
    void listToolsReturnsEmptyOnConnectionError() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.listTools()).thenReturn(Mono.error(new IOException("sse closed")));
        ReconnectingMcpClientWrapper w = wrap(delegate);

        List<McpSchema.Tool> r = w.listTools().block();
        assertTrue(r != null && r.isEmpty());
    }

    @Test
    void listToolsPropagatesNonConnectionError() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        when(delegate.listTools()).thenReturn(Mono.error(new RuntimeException("parse failed")));
        ReconnectingMcpClientWrapper w = wrap(delegate);

        assertThrows(RuntimeException.class, () -> w.listTools().block());
    }

    @Test
    void getCachedToolReturnsNullBeforeDelegateBuilt() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        ReconnectingMcpClientWrapper w = new ReconnectingMcpClientWrapper("svc", () -> delegate);

        // 未触发 ensureDelegate 时，delegate 为 null，读取缓存工具应安全返回 null。
        assertNull(w.getCachedTool("t"));
    }

    @Test
    void initializeDegradesWhenSupplierThrows() {
        ReconnectingMcpClientWrapper w =
                new ReconnectingMcpClientWrapper("svc", () -> {
                    throw new RuntimeException("build failed");
                });

        // 目标不可达时降级为 Mono.empty，不阻断工具注册。
        assertDoesNotThrow(() -> w.initialize().block());
    }

    @Test
    void closeClosesDelegateAndForgetsIt() {
        McpClientWrapper delegate = mock(McpClientWrapper.class);
        ReconnectingMcpClientWrapper w = wrap(delegate);
        w.initialize().block();

        w.close();

        verify(delegate).close();
        assertNull(w.getCachedTool("t"));
    }
}
