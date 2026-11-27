package io.yunxi.fabric.aistio;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.capability.McpDirectory;
import io.yunxi.capability.McpServerSpec;
import io.yunxi.runtime.DistributedRuntimeConfig;
import io.yunxi.runtime.MuseToolRegistrar;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.agentscope.harness.agent.bus.MessageBus;

/**
 * 为 aistio 工作区定义任务提供「按次新建」的 {@link HarnessAgent.Builder} 底座。
 *
 * <p>框架 {@code HarnessWorkspaceAgentFactory} 从工作区定义里声明模型 / 系统提示 / 工具 / 技能 / 权限，
 * 并通过全局 {@code ModelRegistry} 解析 {@code .model(String)}，因此本 Supplier 只需补齐平台底座：
 * 工具箱（来自唯一 {@link McpDirectory} 的 MCP 客户端 + MUSE 元工具）、会话分布式存储与协议无关横切中间件。
 * 不重实现任何执行器或工具翻译逻辑——这些是框架 {@code HarnessWorkspaceAgentFactory} 的职责。</p>
 */
@Component
public class YunxiAgentBuilderSupplier implements Supplier<HarnessAgent.Builder> {

    private final DistributedRuntimeConfig distributedRuntimeConfig;
    private final McpDirectory mcpDirectory;
    private final List<MiddlewareBase> middlewares;
    private final List<MuseToolRegistrar> museToolRegistrars;

    /**
     * 跨实例事件总线（cluster 模式由 RuntimeConfiguration 装配的 {@code RedisMessageBus} bean；
     * standalone 无此 bean，{@link ObjectProvider#getIfAvailable()} 返回 null，装配时跳过（fail-open）。
     * 经字段注入：直接 new 构造（集成测试）时该字段为 null，get() 内需空守卫。
     */
    @Autowired
    private ObjectProvider<MessageBus> messageBusProvider;

    /** 注入平台底座依赖：分布式运行时、MCP 目录、横切中间件与 MUSE 工具注册器。 */
    public YunxiAgentBuilderSupplier(DistributedRuntimeConfig distributedRuntimeConfig,
                                     McpDirectory mcpDirectory,
                                     List<MiddlewareBase> middlewares,
                                     List<MuseToolRegistrar> museToolRegistrars) {
        this.distributedRuntimeConfig = distributedRuntimeConfig;
        this.mcpDirectory = mcpDirectory;
        this.middlewares = middlewares != null ? middlewares : List.of();
        this.museToolRegistrars = museToolRegistrars != null ? museToolRegistrars : List.of();
    }

    /** 构建工作区 Agent 底座：注册全部启用 MCP 客户端 + MUSE 元工具，挂分布式存储与横切中间件。 */
    @Override
    public HarnessAgent.Builder get() {
        Toolkit toolkit = new Toolkit();
        for (McpServerSpec spec : mcpDirectory.all()) {
            if (!spec.enabled()) {
                continue;
            }
            McpClientWrapper wrapper = mcpDirectory.buildClient(spec);
            toolkit.registration().mcpClient(wrapper).group(spec.name()).apply();
        }
        for (MuseToolRegistrar registrar : museToolRegistrars) {
            // 工作区 Agent 的技能由框架 HarnessWorkspaceAgentFactory 从工作区定义装配，本底座不持有单 Agent
            // 技能模式判定，整组挂载（是否挂载的判定由具备技能边界信息的装配点裁决）。
            registrar.registerTools(toolkit);
        }
        HarnessAgent.Builder builder = HarnessAgent.builder().toolkit(toolkit);
        DistributedStore store = distributedRuntimeConfig.distributedStore();
        if (store != null) {
            builder.distributedStore(store);
        }
        // 跨实例事件总线：cluster 模式经 RedisMessageBus 接入；standalone 或总线 bean 缺失时跳过（fail-open）。
        MessageBus messageBus = messageBusProvider != null ? messageBusProvider.getIfAvailable() : null;
        if (messageBus != null) {
            builder.messageBus(messageBus);
        }
        for (MiddlewareBase middleware : middlewares) {
            builder.middleware(middleware);
        }
        return builder;
    }
}
