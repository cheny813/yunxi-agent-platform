package io.yunxi.app;

import io.a2a.spec.TransportProtocol;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.spring.boot.admin.registry.AgentRegistry;
import io.yunxi.capability.McpDirectory;
import io.yunxi.definition.AgentSpec;
import org.springframework.beans.factory.ObjectProvider;
import io.yunxi.app.shard.AgentShardResolver;
import io.yunxi.app.startup.StartupGate;
import io.yunxi.runtime.AgentConfigurer;
import io.yunxi.runtime.AgentDefinitionLoader;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 应用平面。Agent 布建器。把 AgentConfigurer 从配置中心构建出的 HarnessAgent 注册进框架协议层 registry，
 * 由框架 web starter 直接服务，yunxi 不重建任何协议语义：
 *
 * <ul>
 *   <li>AG-UI / 单 Agent 真相源：{@link AguiAgentRegistry}</li>
 *   <li>A2A：AgentScopeA2aServer 发布 AgentCard，由 A2aController 同端口路由</li>
 *   <li>admin 运维平面：框架 AgentRegistry（全局 Agent inventory 列示，经 {@code agentscope.admin.enabled} 开启）</li>
 * </ul>
 *
 * <p>Chat Completions 不在此注册（无独立 registry），运行时由 YunxiChatCompletionsController 经 AgentLookup 按 model 选取。</p>
 *
 * <p>admin 运维平面（agentscope-admin-spring-boot-starter）：框架 admin 在 2.1.0-BETA1 基线已随远程发布可用。
 * yunxi 在此把构建出的 HarnessAgent 一并注册进框架 admin 的
 * {@code AgentRegistry}（沿用本布建器的单一真相源，不另建注册表），供 {@code /actuator/agentscope-agents}
 * 等只读盘点端点列示全局 agent inventory。admin 关闭（{@code agentscope.admin.enabled=false}）时该 Bean
 * 不存在，经 {@code ObjectProvider#ifAvailable} 安全跳过，不破坏启动。</p>
 */
@Component
@DependsOn("agentDefinitionLoader")
public class AgentProvisioner {

    private final AgentDefinitionLoader agentDefinitionLoader;
    private final AgentConfigurer agentConfigurer;
    private final AguiAgentRegistry aguiAgentRegistry;
    private final A2aServerRegistry a2aServerRegistry;
    private final HarnessGateway harnessGateway;
    private final List<MiddlewareBase> middlewares;
    private final ObjectProvider<AgentRegistry> agentRegistryProvider;

    private final String a2aHost;
    private final int a2aPort;
    private final String a2aPathPrefix;
    private final StartupGate startupGate;
    private final AgentShardResolver shardResolver;
    private final McpDirectory mcpDirectory;

    /** 构建期 MCP 注册失败的待换装登记（agentId → 定义快照与失败服务器集合）。 */
    private final Map<String, PendingRebuild> pendingRebuilds = new ConcurrentHashMap<>();

    /** 本实例绑定的主 Agent（首个注册的分片 Agent），换装后需重新绑定网关 main agent。 */
    private volatile String mainAgentId;

    private static final Logger log = LoggerFactory.getLogger(AgentProvisioner.class);

    public AgentProvisioner(
            AgentDefinitionLoader agentDefinitionLoader,
            AgentConfigurer agentConfigurer,
            AguiAgentRegistry aguiAgentRegistry,
            A2aServerRegistry a2aServerRegistry,
            HarnessGateway harnessGateway,
            List<MiddlewareBase> middlewares,
            ObjectProvider<AgentRegistry> agentRegistryProvider,
            StartupGate startupGate,
            AgentShardResolver shardResolver,
            McpDirectory mcpDirectory,
            @Value("${yunxi.a2a.host:localhost}") String a2aHost,
            @Value("${yunxi.a2a.port:8080}") int a2aPort,
            @Value("${yunxi.a2a.path-prefix:/a2a}") String a2aPathPrefix) {
        this.agentDefinitionLoader = agentDefinitionLoader;
        this.agentConfigurer = agentConfigurer;
        this.aguiAgentRegistry = aguiAgentRegistry;
        this.a2aServerRegistry = a2aServerRegistry;
        this.harnessGateway = harnessGateway;
        this.middlewares = middlewares;
        this.agentRegistryProvider = agentRegistryProvider;
        this.startupGate = startupGate;
        this.shardResolver = shardResolver;
        this.mcpDirectory = mcpDirectory;
        this.a2aHost = a2aHost;
        this.a2aPort = a2aPort;
        this.a2aPathPrefix = a2aPathPrefix;
    }

    /**
     * 上下文刷新后遍历全部 AgentSpec：并行构建 HarnessAgent（configure 无共享可变状态——每 spec 独立
     * Toolkit / McpClientBuilder 实例，McpDirectory#buildClient 经线程安全 ConcurrentHashMap 共享连接），
     * 再串行注册进 AG-UI、A2A 与网关 runStream 三处真相源（避免多实例并发注册竞态、保持 main-agent 绑定顺序幂等）。
     * 任一构建失败即 fail-fast：不注册任何 Agent，抛异常终止启动（并行构建 HarnessAgent、串行注册三处真相源，降低启动期资源竞争与构建风暴）。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void provision() {
        boolean leader = startupGate.tryAcquire();
        if (!leader) {
            // 未取得 leader 资格：不再整体跳过，每实例按 owns 过滤构建自身分片，不依赖 leader 预热。
            // StartupGate 保留作 leader 标记，可扩展全局设施初始化（控制面 instrument / registry 注册只需一次）。
            log.warn("AgentProvisioner：未取得启动 leader 资格，仍按分片构建自身归属 Agent（不依赖 leader 预热）");
        }
        try {
            if (leader) {
                // 预留：leader 初始化全局设施（控制面 instrument 由框架在 AguiAgentRegistry 注册时处理；当前无显式全局步骤）
            }
            doProvision();
        } finally {
            if (leader) {
                startupGate.release();
            }
        }
    }

    private void doProvision() {
        List<AgentSpec> specs = List.copyOf(agentDefinitionLoader.loadAllSpecs());
        if (specs.isEmpty()) {
            log.warn("AgentProvisioner：未发现任何 AgentSpec，跳过布建");
            return;
        }
        int parallelism = Math.min(specs.size(), 8);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(parallelism);
        executor.setMaxPoolSize(parallelism);
        executor.setThreadNamePrefix("agent-provision-");
        executor.initialize();
        record ProvisionedBuild(String agentId, AgentSpec spec, AgentConfigurer.ProvisionedAgent provisioned) {}
        List<ProvisionedBuild> built = new ArrayList<>(specs.size());
        List<Throwable> errors = new ArrayList<>();
        try {
            List<Future<ProvisionedBuild>> futures = specs.stream()
                    .map(spec -> executor.submit(() -> {
                        AgentConfigurer.ProvisionedAgent provisioned = agentConfigurer.configureDetailed(spec);
                        return new ProvisionedBuild(spec.identity().agentId(), spec, provisioned);
                    }))
                    .collect(Collectors.toList());
            for (Future<ProvisionedBuild> future : futures) {
                try {
                    built.add(future.get());
                } catch (ExecutionException e) {
                    errors.add(e.getCause() != null ? e.getCause() : e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.add(e);
                }
            }
        } finally {
            executor.shutdown();
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "AgentProvisioner：并行构建失败 " + errors.size() + " 个 Agent，终止布建", errors.get(0));
        }
        boolean first = true;
        for (ProvisionedBuild entry : built) {
            if (!shardResolver.owns(entry.agentId())) {
                // 非本实例分片：跳过本地注册，由转发层承接（每实例仅构建自身分片）。
                continue;
            }
            registerAll(entry.agentId(), entry.provisioned().agent());
            if (!entry.provisioned().failedMcpServers().isEmpty()) {
                // 构建期 MCP 不可达：登记待换装，目标恢复可达后整体重建实例（probeAndRebuild 调度）。
                pendingRebuilds.put(entry.agentId(), new PendingRebuild(
                        entry.spec(), Set.copyOf(entry.provisioned().failedMcpServers())));
            }
            if (first) {
                mainAgentId = entry.agentId();
                harnessGateway.bindMainAgent(entry.provisioned().agent());
                first = false;
            }
        }
    }

    /** 构建期 MCP 注册失败的待换装登记（agentId → 定义快照与失败服务器集合）。 */
    private record PendingRebuild(AgentSpec spec, Set<String> failedServers) {}

    /**
     * 恢复探测调度入口（由 McpRecoveryScheduler 定时调用）：对每个待换装登记中的构建期失败服务器探活，
     * 任一恢复可达即触发该 Agent 实例换装——经 AgentConfigurer 整体重建（工具装载与权限上下文派生
     * 同源同刻换装，不引入增量补规则的第二个真相时刻），并替换四处注册真相源。换装后按重建结果刷新登记：
     * 仍失败的服务器保留待下轮，全部恢复则移除。换装失败保留登记下轮重试。
     */
    public synchronized void probeAndRebuild() {
        if (pendingRebuilds.isEmpty()) {
            return;
        }
        for (Map.Entry<String, PendingRebuild> entry : pendingRebuilds.entrySet()) {
            boolean recovered = entry.getValue().failedServers().stream().anyMatch(mcpDirectory::probe);
            if (recovered) {
                rebuildAgent(entry.getKey(), entry.getValue());
            }
        }
    }

    private void rebuildAgent(String agentId, PendingRebuild pending) {
        try {
            AgentConfigurer.ProvisionedAgent rebuilt = agentConfigurer.configureDetailed(pending.spec());
            registerAll(agentId, rebuilt.agent());
            if (agentId.equals(mainAgentId)) {
                harnessGateway.bindMainAgent(rebuilt.agent());
            }
            // 已缓存 A2A server 持有旧 Agent 引用，驱逐后经工厂基于新实例惰性重建。
            a2aServerRegistry.evict(agentId);
            if (rebuilt.failedMcpServers().isEmpty()) {
                pendingRebuilds.remove(agentId);
                log.info("MCP 恢复换装完成：agent={} 失败服务器全部恢复，移除待换装登记", agentId);
            } else {
                pendingRebuilds.put(agentId,
                        new PendingRebuild(pending.spec(), Set.copyOf(rebuilt.failedMcpServers())));
                log.info("MCP 恢复换装完成：agent={} 仍有失败服务器 {}，保留待换装登记",
                        agentId, rebuilt.failedMcpServers());
            }
        } catch (Exception e) {
            log.warn("MCP 恢复换装失败：agent={}，保留待换装登记下轮重试", agentId, e);
        }
    }

    /**
     * 串行注册：四处共享真相源（AG-UI / A2A / 网关 runStream / admin AgentRegistry），保持注册顺序幂等。
     * AG-UI 经框架原生 registerFactory 捕获已为网关 eager 构建的同一实例（不每请求重建，不分裂单实例真相源）；
     * A2A 经 yunxi 自管 registerFactory（首个 A2A 请求才惰性拉起 server）；网关/admin 仍 eager 构建。
     */
    private void registerAll(String agentId, HarnessAgent agent) {
        // AG-UI / 单 Agent 真相源：经框架原生 registerFactory 注册（捕获同一 eager 实例，不每请求重建），框架 AG-UI 协议层服务端由此取 Agent。
        aguiAgentRegistry.registerFactory(agentId, () -> agent);
        // A2A 协议层：经 yunxi 自管 registerFactory 惰性注册（首个 A2A 请求才 buildA2aServer 拉起 server），由 A2aController 同端口路由。
        a2aServerRegistry.registerFactory(agentId, () -> buildA2aServer(agentId, agent));
        // 网关 runStream 真相源：web / Chat Completions 统一经 HarnessGateway#runStream 驱动 Agent。
        harnessGateway.registerAgent(agentId, agent);
        // admin 运维平面：注册进框架 AgentRegistry，供 /actuator/agentscope-agents 等只读盘点端点列示全局 agent inventory。
        // admin 关闭（agentscope.admin.enabled=false）时该 Bean 不存在，ObjectProvider#ifAvailable 安全跳过，不破坏启动。
        agentRegistryProvider.ifAvailable(reg -> reg.register(agent));
    }

    /** 为指定 Agent 构建框架 A2A server（含 AgentCard 与 JSON-RPC 传输），并标记为就绪。 */
    private AgentScopeA2aServer buildA2aServer(String agentId, HarnessAgent agent) {
        ConfigurableAgentCard card = new ConfigurableAgentCard.Builder()
                .name(agentId)
                .description(agent.getName())
                .url("http://" + a2aHost + ":" + a2aPort + a2aPathPrefix + "/" + agentId)
                .version("1.0.0")
                .build();
        TransportProperties transport = TransportProperties.builder(TransportProtocol.JSONRPC.asString())
                .host(a2aHost)
                .port(a2aPort)
                .path(a2aPathPrefix + "/" + agentId)
                .build();
        AgentScopeA2aServer server = AgentScopeA2aServer.builder(new HarnessAgentRunner(agent))
                .agentCard(card)
                .withTransport(transport)
                .build();
        server.postEndpointReady();
        return server;
    }
}
