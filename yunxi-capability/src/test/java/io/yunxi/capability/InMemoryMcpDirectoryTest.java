package io.yunxi.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 能力层 MCP 目录验证：{@link InMemoryMcpDirectory} 是产品平面唯一接触 McpClientBuilder 的地方。
 * 本测试只验证注册 / 查询 / 注销的纯内存语义，不调用 {@code buildClient}（那会真实连接 MCP 服务）。
 */
class InMemoryMcpDirectoryTest {

    @Test
    void registerThenGet_present() {
        InMemoryMcpDirectory dir = new InMemoryMcpDirectory();
        McpServerSpec spec = McpServerSpec.stdio("fs", "npx", List.of("fs"), Map.of());
        dir.register(spec);
        Optional<McpServerSpec> got = dir.get("fs");
        assertTrue(got.isPresent());
        assertEquals("fs", got.get().name());
        assertEquals(McpServerSpec.Transport.STDIO, got.get().transport());
    }

    @Test
    void sseSpec_carriesUrl() {
        InMemoryMcpDirectory dir = new InMemoryMcpDirectory();
        McpServerSpec spec = McpServerSpec.sse("web", "http://mcp.example/web");
        dir.register(spec);
        Optional<McpServerSpec> got = dir.get("web");
        assertTrue(got.isPresent());
        assertEquals(McpServerSpec.Transport.SSE, got.get().transport());
        assertEquals("http://mcp.example/web", got.get().url());
    }

    @Test
    void unregister_removesEntry() {
        InMemoryMcpDirectory dir = new InMemoryMcpDirectory();
        dir.register(McpServerSpec.sse("web", "http://x"));
        dir.unregister("web");
        assertTrue(dir.get("web").isEmpty(), "注销后查询必须为空");
    }

    @Test
    void all_returnsRegisteredSpecs() {
        InMemoryMcpDirectory dir = new InMemoryMcpDirectory();
        dir.register(McpServerSpec.stdio("a", "c", List.of(), Map.of()));
        dir.register(McpServerSpec.sse("b", "http://y"));
        assertEquals(2, dir.all().size());
    }

    @Test
    void duplicateRegister_overwritesByName() {
        InMemoryMcpDirectory dir = new InMemoryMcpDirectory();
        dir.register(McpServerSpec.stdio("a", "c1", List.of(), Map.of()));
        dir.register(McpServerSpec.stdio("a", "c2", List.of(), Map.of()));
        assertEquals(1, dir.all().size());
        assertEquals("c2", dir.get("a").orElseThrow().command());
    }

    @Test
    void buildClient_sameSpec_returnsSharedUnderlyingConnection() {
        // N 个 Agent 注册同一 MCP server 时，底层真实连接必须唯一（灭掉 N×S 连接爆炸）。
        InMemoryMcpDirectory dir = new InMemoryMcpDirectory();
        McpServerSpec spec = McpServerSpec.sse("web", "http://mcp.example/web");
        McpClientWrapper a = dir.buildClient(spec);
        McpClientWrapper b = dir.buildClient(spec);
        assertNotSame(a, b, "两次 buildClient 应返回不同的非持有委托包装");
        assertEquals(1, dir.clientCacheSize(), "同一 server 的多次 buildClient 必须共享同一底层连接");
    }

    @Test
    void sharedWrapperClose_isNoOp_doesNotEvictSharedConnection() {
        // 委托包装的 close() 必须为 no-op，否则 Toolkit 关闭生命周期会误杀被共享的连接。
        InMemoryMcpDirectory dir = new InMemoryMcpDirectory();
        McpServerSpec spec = McpServerSpec.sse("web", "http://mcp.example/web");
        McpClientWrapper w = dir.buildClient(spec);
        assertEquals(1, dir.clientCacheSize());
        w.close();
        assertEquals(1, dir.clientCacheSize(), "委托包装 close 不得误杀共享连接");
    }

    @Test
    void unregister_evictsCachedConnection() {
        // "定义删除即连接删除"：注销须从 clientCache 移除并释放底层真实连接。
        InMemoryMcpDirectory dir = new InMemoryMcpDirectory();
        McpServerSpec spec = McpServerSpec.sse("web", "http://mcp.example/web");
        dir.register(spec);
        dir.buildClient(spec);
        assertEquals(1, dir.clientCacheSize());
        dir.unregister("web");
        assertEquals(0, dir.clientCacheSize(), "注销须释放底层真实连接");
    }
}
