package io.yunxi.capability;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 能力平面。带自动重连的 MCP 客户端包装。框架的 {@code McpClientBuilder} 没有原生重连能力，
 * 目标服务器在运行中掉线时连接不会自动恢复；本包装在连接异常时关闭旧 client 并经 supplier 重建一次，
 * 使 Nutrition 等"先构建后掉线"的场景能自愈，而不阻断 Agent 构建或 HTTP 请求。
 *
 * <p>构建（{@code buildSync}）被延迟到首次 {@code initialize()}/{@code listTools()}/{@code callTool()} 调用，
 * 因此目标服务器在 Agent 构建期不可达时只 WARN 降级（注册阶段不抛错），待其恢复后首次调用即重连。
 */
public class ReconnectingMcpClientWrapper extends McpClientWrapper {

    private static final Logger log = LoggerFactory.getLogger(ReconnectingMcpClientWrapper.class);

    private final Supplier<McpClientWrapper> clientSupplier;
    private volatile McpClientWrapper delegate;

    public ReconnectingMcpClientWrapper(String name, Supplier<McpClientWrapper> clientSupplier) {
        super(name);
        this.clientSupplier = clientSupplier;
    }

    private McpClientWrapper ensureDelegate() {
        McpClientWrapper d = delegate;
        if (d == null) {
            synchronized (this) {
                d = delegate;
                if (d == null) {
                    d = clientSupplier.get();
                    d.initialize().block();
                    delegate = d;
                    this.initialized = true;
                }
            }
        }
        return d;
    }

    private void rebuild() {
        synchronized (this) {
            if (delegate != null) {
                try {
                    delegate.close();
                } catch (Exception ignored) {
                    // 关闭旧连接失败不影响重建
                }
            }
            delegate = null;
        }
    }

    private static boolean isConnectionError(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof IOException
                    || cur instanceof TimeoutException
                    || cur instanceof ConnectException
                    || cur instanceof ClosedChannelException
                    || cur instanceof InterruptedException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("connection") || m.contains("sse") || m.contains("closed")
                        || m.contains("reset") || m.contains("timeout") || m.contains("refused")
                        || m.contains("unreachable") || m.contains("broken") || m.contains("channel")
                        || m.contains("failed to initialize")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 探活：对底层连接做一次惰性建连 + 工具列举，判定目标服务器当前是否可达。
     * 连接类失败时重置连接（下次调用重新建连），返回 false；列举成功返回 true。
     * 供构建期注册失败的换装恢复探测使用，不抛异常。
     */
    public boolean probeRecovery() {
        try {
            McpClientWrapper d = ensureDelegate();
            d.listTools().block();
            return true;
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("MCP[{}] 探活失败（目标不可达）：{}", getName(), e.getMessage());
            }
            if (isConnectionError(e)) {
                rebuild();
            }
            return false;
        }
    }

    @Override
    public Mono<Void> initialize() {
        try {
            ensureDelegate();
        } catch (Exception e) {
            // 目标不可达：降级，不阻断工具注册
            if (log.isWarnEnabled()) {
                log.warn("MCP[{}] 初始化失败，降级跳过（目标不可达）：{}",
                        getName(), e.getMessage());
            }
            return Mono.empty();
        }
        return Mono.empty();
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        return Mono.defer(() -> {
            try {
                return Mono.just(ensureDelegate().listTools().block());
            } catch (Exception e) {
                if (isConnectionError(e)) {
                    if (log.isWarnEnabled()) {
                        log.warn("MCP[{}] 工具列举失败，降级返回空工具集（目标不可达）：{}",
                                getName(), e.getMessage());
                    }
                    return Mono.just(List.of());
                }
                return Mono.error(e);
            }
        });
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return callToolResilient(() -> ensureDelegate().callTool(toolName, arguments));
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
        return callToolResilient(() -> ensureDelegate().callTool(toolName, arguments, meta));
    }

    private Mono<McpSchema.CallToolResult> callToolResilient(
            Supplier<Mono<McpSchema.CallToolResult>> op) {
        return Mono.defer(op)
                .onErrorResume(t -> isConnectionError(t), t -> Mono.defer(() -> {
                    rebuild();
                    return op.get();
                }));
    }

    @Override
    public McpSchema.Tool getCachedTool(String toolName) {
        McpClientWrapper d = delegate;
        return d != null ? d.getCachedTool(toolName) : null;
    }

    @Override
    public void setPropagateMeta(boolean propagateMeta) {
        McpClientWrapper d = delegate;
        if (d != null) {
            d.setPropagateMeta(propagateMeta);
        }
        super.setPropagateMeta(propagateMeta);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (delegate != null) {
                try {
                    delegate.close();
                } catch (Exception ignored) {
                    // 关闭失败忽略
                }
                delegate = null;
            }
        }
    }
}
