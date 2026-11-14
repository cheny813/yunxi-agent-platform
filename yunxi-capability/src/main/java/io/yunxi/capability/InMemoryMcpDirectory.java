package io.yunxi.capability;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * 能力平面。默认 {@link McpDirectory}。产品平面中唯一接触 {@code McpClientBuilder} 的地方；使用已核实的 AgentScope-Java API：
 * {@code McpClientBuilder.create(name).stdioTransport(...).buildSync()} / {@code .sseTransport(url).buildSync()}。
 */
public class InMemoryMcpDirectory implements McpDirectory {

    private final Map<String, McpServerSpec> specs = new ConcurrentHashMap<>();

    /**
     * 跨 Agent 共享的真实 MCP 客户端连接。唯一持有连接所有权（负责真实 {@code close()} 与 S2 断线重建）；
     * {@link #buildClient} 返回的是非持有委托包装 {@link SharedMcpClientWrapper}，其 {@code close()} 为 no-op，
     * 规避框架 Toolkit 关闭生命周期级联误杀被多个 Agent 共用的连接（已核实框架关闭链路会级联关闭共享连接）。
     */
    private final Map<String, ReconnectingMcpClientWrapper> clientCache = new ConcurrentHashMap<>();

    private final boolean clientCacheEnabled;
    private final boolean evictOnUnregister;

    public InMemoryMcpDirectory() {
        this(true, true);
    }

    public InMemoryMcpDirectory(boolean clientCacheEnabled, boolean evictOnUnregister) {
        this.clientCacheEnabled = clientCacheEnabled;
        this.evictOnUnregister = evictOnUnregister;
    }

    /** 按名称写入（或覆盖）定义。 */
    @Override
    public void register(McpServerSpec spec) {
        specs.put(spec.name(), spec);
    }

    /** 按名称移除定义；共享连接下同步释放底层真实连接（"定义删除即连接删除"，避免泄漏）。 */
    @Override
    public void unregister(String name) {
        specs.remove(name);
        if (evictOnUnregister) {
            ReconnectingMcpClientWrapper removed = clientCache.remove(name);
            if (removed != null) {
                removed.close(); // 释放底层 STDIO 子进程 / SSE 连接
            }
        }
    }

    /** 按名称读取定义；不存在返回空。 */
    @Override
    public Optional<McpServerSpec> get(String name) {
        return Optional.ofNullable(specs.get(name));
    }

    /** 返回当前全部定义（不可变快照）。 */
    @Override
    public List<McpServerSpec> all() {
        return List.copyOf(specs.values());
    }

    @Override
    public McpClientWrapper buildClient(McpServerSpec spec) {
        if (!clientCacheEnabled) {
            // 兼容旧语义：每次新建真实连接（不共享）
            return new ReconnectingMcpClientWrapper(spec.name(), () -> buildRaw(spec));
        }
        // 每 serverName 只建一个真实连接（computeIfAbsent 保证），返回非持有委托包装。
        // N 个 Agent 注册同一 MCP server 时共享同一底层连接，灭掉 N×S 连接爆炸。
        ReconnectingMcpClientWrapper real = clientCache.computeIfAbsent(
                spec.name(),
                k -> new ReconnectingMcpClientWrapper(spec.name(), () -> buildRaw(spec)));
        return new SharedMcpClientWrapper(real);
    }

    /** 测试探针：当前缓存的真实连接数（验证跨 Agent 共享）。 */
    int clientCacheSize() {
        return clientCache.size();
    }

    /** 用 AgentScope-Java 的 McpClientBuilder 按传输类型构建并初始化真实客户端（STDIO / SSE）。 */
    private McpClientWrapper buildRaw(McpServerSpec spec) {
        McpClientBuilder builder = McpClientBuilder.create(spec.name())
                .initializationTimeout(Duration.ofSeconds(5));
        if (spec.transport() == McpServerSpec.Transport.STDIO) {
            List<String> args = spec.args() != null ? spec.args() : List.of();
            if (spec.env() != null && !spec.env().isEmpty()) {
                builder = builder.stdioTransport(spec.command(), args, spec.env());
            } else {
                builder = builder.stdioTransport(spec.command(), args.toArray(new String[0]));
            }
        } else {
            builder = builder.sseTransport(spec.url());
        }
        return builder.buildSync();
    }

    /**
     * 非持有委托包装：连接生命周期唯一归 {@link #clientCache}，本包装的 {@code close()} 为 no-op，
     * 规避框架 {@code Toolkit} 关闭生命周期级联误杀被多个 Agent 共享的连接
     * （已核实框架关闭链路：{@code Toolkit.closeMcpClients()} → {@code McpClientManager.removeMcpClient} → {@code wrapper.close()}）。
     * 其余初始化 / 工具调用 / 缓存查询全部委托给真实包装，对调用方透明（含 S2 重连重建）。
     */
    private static final class SharedMcpClientWrapper extends McpClientWrapper {

        private final McpClientWrapper delegate;

        SharedMcpClientWrapper(McpClientWrapper delegate) {
            super(delegate.getName());
            this.delegate = delegate;
        }

        @Override
        public Mono<Void> initialize() {
            return delegate.initialize();
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            return delegate.listTools();
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
            return delegate.callTool(toolName, arguments);
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
            return delegate.callTool(toolName, arguments, meta);
        }

        @Override
        public McpSchema.Tool getCachedTool(String toolName) {
            return delegate.getCachedTool(toolName);
        }

        @Override
        public boolean isInitialized() {
            return delegate.isInitialized();
        }

        @Override
        public boolean isPropagateMeta() {
            return delegate.isPropagateMeta();
        }

        @Override
        public void setPropagateMeta(boolean propagateMeta) {
            delegate.setPropagateMeta(propagateMeta);
        }

        @Override
        public void close() {
            // no-op：连接所有权归 clientCache，绝不由 toolkit 生命周期 / 注册失败误关
        }
    }
}
