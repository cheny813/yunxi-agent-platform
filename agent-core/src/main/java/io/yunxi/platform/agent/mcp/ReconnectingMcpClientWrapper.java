/*
 * 轻量适配层：MCP 客户端断线自动重连包装器。
 *
 * 背景：AgentScope GA 内置的 MCP Java SDK（io.modelcontextprotocol:sdk:mcp:0.17.2）中，
 * GA 的 McpClientBuilder 使用 HttpClientSseClientTransport.builder(url) 但未配置 reconnectInterval，
 * 且 GA 的 McpAsyncClientWrapper.callTool 仅记录错误、无重试/重连逻辑。因此当某个 MCP 服务器进程重启、
 * 服务端主动关闭 SSE 长连接后，yunxi 侧的工具调用会静默挂起直至超时。为避免"任一 MCP 服务重启后都必须
 * 重启 yunxi 平台"这一问题，在 yunxi 自有层加一个委托包装器：调用抛连接异常时关闭旧连接、按需重建底层
 * McpClientWrapper 并重试一次。
 *
 * 删除条件：当底层框架满足以下任一条件后即可移除本类：
 *  - GA 的 McpAsyncClientWrapper / McpClientBuilder 内置连接级重试/重连；或
 *  - MCP Java SDK 的 SSE 传输默认自动重连且 GA 的 Builder 已启用（无需 yunxi 侧兜底）。
 * 届时直接删除本类，并把 AgentConfigurer.registerMcpServers 中对 wrapWithReconnect 的调用还原为裸
 * buildMcpClient 即可。
 *
 * 设计约束（遵循"薄适配层"原则：不重写框架能力，仅在框架之上做最小必要适配）：
 * - 不修改 AgentScope 任何代码，不自建 MCP 协议层；仅包装框架原生 McpClientWrapper。
 * - toolkit 注册结构与调用路径完全不变（McpTool 持有的是本包装器引用，运行时只调 callTool）。
 * - 重连只在 callTool 失败且判定为连接级异常时触发，且每个调用至多重试一次，避免重连风暴。
 */
package io.yunxi.platform.agent.mcp;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 委托 {@link McpClientWrapper}，在调用失败且判定为连接异常时自动重建底层客户端并重试一次。
 */
public class ReconnectingMcpClientWrapper extends McpClientWrapper {

    private static final Logger log = LoggerFactory.getLogger(ReconnectingMcpClientWrapper.class);

    /** 底层真实客户端，重连时整体替换 */
    private volatile McpClientWrapper delegate;

    /** 重建底层客户端的工厂（闭包捕获服务器名与配置，等价于一次 buildMcpClient 调用） */
    private final Supplier<McpClientWrapper> factory;

    /** 重连互斥锁，避免并发调用触发多次重建 */
    private final Object reconnectLock = new Object();

    public ReconnectingMcpClientWrapper(String name, Supplier<McpClientWrapper> factory) {
        super(name);
        this.factory = factory;
        this.delegate = factory.get();
    }

    @Override
    public Mono<Void> initialize() {
        return delegate.initialize()
                .doOnError(e -> log.warn(
                        "[MCP] 客户端 '{}' 初始化失败：{}。"
                                + " 请确认对应的 MCP 服务已启动并可访问；首次调用工具时若仍失败将自动重连。",
                        name, e.getMessage()));
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        return delegate.listTools();
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return callTool(toolName, arguments, null);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
        // 捕获当前 delegate 引用，重连判定时用于识别"是否已由其他线程重建"
        McpClientWrapper current = delegate;
        return current.callTool(toolName, arguments, meta)
                .onErrorResume(e -> {
                    if (!isConnectionError(e)) {
                        // 业务级异常（含 MCP isError 结果不会走到这里）不触发重连，原样抛出
                        return Mono.error(e);
                    }
                    log.warn(
                            "MCP 客户端 '{}' 调用工具 '{}' 时检测到连接异常（{}），尝试重建连接并重试一次",
                            name, toolName, e.getClass().getSimpleName());
                    return reconnectAndRetry(current, toolName, arguments, meta, e);
                });
    }

    /**
     * 关闭旧连接、用工厂重建并初始化新连接，然后重试一次调用。若重建或重试仍失败，回退到原始异常，
     * 交由上层 McpTool 的 onErrorResume 转换为工具错误结果（与无重连时行为一致）。
     */
    private Mono<McpSchema.CallToolResult> reconnectAndRetry(
            McpClientWrapper stale,
            String toolName,
            Map<String, Object> arguments,
            Map<String, Object> meta,
            Throwable originalCause) {
        return Mono.defer(() -> {
            McpClientWrapper rebuilt;
            synchronized (reconnectLock) {
                if (delegate != stale) {
                    // 其他线程已完成重建，直接复用当前 delegate，避免重复建连
                    rebuilt = delegate;
                } else {
                    rebuilt = rebuild(stale);
                }
            }
            // 重建或重试再次失败则放弃，保留原始异常作为根因
            return rebuilt.callTool(toolName, arguments, meta)
                    .onErrorResume(e2 -> Mono.error(originalCause));
        });
    }

    /**
     * 在锁内执行：关闭旧连接并构建新连接。新连接需要 initialize() 后才可被 callTool 使用。
     * initialize() 失败（服务器仍未就绪）则抛出异常，由调用方回退到原始异常。
     */
    private McpClientWrapper rebuild(McpClientWrapper stale) {
        try {
            stale.close();
        } catch (Exception ignored) {
            // 旧连接已失效，close 失败可忽略
        }
        McpClientWrapper fresh = factory.get();
        // buildMcpClient 返回的 wrapper 尚未 initialize，必须先初始化才能 callTool
        fresh.initialize().block();
        delegate = fresh;
        log.info("MCP 客户端 '{}' 已重建连接", name);
        return fresh;
    }

    /**
     * 判定是否为"连接级"异常（应触发重连）。覆盖：
     * - IOException 及其子类（SocketException / EofException 等，含 SSE 断流的
     *   "chunked transfer encoding, state: READING_LENGTH"）
     * - 消息中带有连接相关关键词的异常（兜底，如 reactor netty 的部分 TransportException 未继承 IOException）
     */
    private boolean isConnectionError(Throwable e) {
        if (e instanceof IOException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("sse")
                || lower.contains("connection")
                || lower.contains("closed")
                || lower.contains("reset")
                || lower.contains("broken pipe")
                || lower.contains("prematureclose")
                || lower.contains("connection refused")
                || lower.contains("no such host")
                || lower.contains("timed out")
                || lower.contains("timeout");
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } catch (Exception e) {
            log.warn("MCP 客户端 '{}' 关闭异常: {}", name, e.getMessage());
        }
    }
}
