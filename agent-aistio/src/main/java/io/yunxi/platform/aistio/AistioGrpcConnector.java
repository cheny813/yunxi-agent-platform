package io.yunxi.platform.aistio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;

import org.springframework.core.env.Environment;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentscope.core.agent.Agent;
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.adapter.HarnessAgentTaskStarter;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.extensions.aistio.adapter.HarnessWorkspaceAgentFactory;
import io.agentscope.extensions.aistio.transport.HttpSelfRegistration;
import io.yunxi.platform.agent.model.ModelFactory;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.aistio.task.AistioTaskBridge;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.intent.IntentEngine;
import io.yunxi.platform.shared.dto.AgentInfoDto;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/**
 * aistio 数据面集成连接器（单一装配入口）。
 *
 * <p>复用 AgentScope-Java 官方 {@code agentscope-extensions-aistio} 扩展，把 yunxi 数据面接入 aistio 控制面。
 * 本连接器是端到端集成的唯一装配点，一次性闭环三件事：
 * <ol>
 *   <li>HTTP 自注册：向控制面目录创建本数据面 instance（{@code POST /api/v1/agent-registrations}）；</li>
 *   <li>周期心跳保活：驱动 instance 健康，避免被控制面 {@code Poller} 标 stale；</li>
 *   <li>gRPC 对话流：经 ASDP 双向流接收 {@code ConversationTurnCommand} 并回报，承载端到端对话。</li>
 * </ol>
 * 注册、心跳、握手身份回填、断线重连、{@code conversation-inbound} 能力声明全部由框架
 * {@code HttpSelfRegistration} / {@code GrpcTransport} 原生完成。注册成功后，框架通过
 * {@code identityListener} 把稳定身份（agentId/bindingId/instanceKey/generation/registrationCredential）
 * 实时喂给 gRPC 握手——这是早前 yunxi 自建桥接漏写、导致 gRPC 永远起不来的根因，现由框架原生闭环。</p>
 *
 * <p>多 agent 注册：除保留 {@code yunxi-agent-platform} 通用路由 agent（做意图分流）外，同时把每个业务 agent
 * 各自注册为独立的 aistio catalog 条目，使控制台既能选具体 agent、也能走通用入口由系统按意图分派。每个 agent
 * 各自建一条 {@code SessionBridge}（独立 agentKey + 独立 gRPC 流），会话天然落到对应 bridge 的 adapter。</p>
 */
@Component
@ConditionalOnProperty(prefix = "yunxi.aistio", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AistioGrpcConnector {

    private static final Logger log = LoggerFactory.getLogger(AistioGrpcConnector.class);
    private static final long POLL_INITIAL_SEC = 5L;
    private static final long POLL_PERIOD_SEC = 5L;

    private final AistioIntegrationProperties properties;
    private final AgentService agentService;
    private final IntentEngine intentEngine;
    private final Environment environment;
    private final ModelFactory modelFactory;

    private volatile ScheduledExecutorService scheduler;
    private volatile List<SessionBridge> bridges;
    /** 每条 bridge 对应的接活桥：优雅下线时先 stopAccepting（优雅下线第二步） */
    private volatile List<AistioTaskBridge> taskBridges = new ArrayList<>();
    private volatile boolean started = false;
    /** 实例身份冲突等不可重试错误：置位后停止重试，避免把错误状态刷成常态 */
    private volatile boolean refused = false;

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        if (!properties.isEnabled() || !properties.isGrpcEnabled()) {
            return;
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            log.error(
                    "aistio 集成未启动：yunxi.aistio.internal-token 为空，框架会因缺少令牌跳过注册（gRPC 无身份可握手）。"
                            + " 请在配置中填入 aistio 控制面内部令牌后重启。");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aistio-integration-connector");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(
                this::tryStart, POLL_INITIAL_SEC, POLL_PERIOD_SEC, TimeUnit.SECONDS);
        log.info("aistio 集成连接器已就绪，开始注册数据面并拉起 gRPC 对话流…");
    }

    private void tryStart() {
        if (started || refused) {
            return;
        }
        List<SessionBridge> opened = new ArrayList<>();
        try {
            // 1) 通用路由 agent：做意图分流，代表实例取 chatAgentName；其 adapter 回落默认 = chatAgentName
            //    注册前先读基线 generation，注册后比对增量才能区分「自身注册的 +1」与「外部并发写入」
            Long routerBaseline = readGenerationBaseline(properties.getAgentKey());
            SessionBridge router = instrumentAgent(
                    properties.getAgentKey(), properties.getChatAgentName(), properties.getChatAgentName());
            requireFreshInstance(router, properties.getAgentKey(), routerBaseline);
            opened.add(router);
            // 2) 逐个业务 agent 注册为独立 aistio 条目（adapter 回落默认 = 自身名，每轮仍按意图改道）
            //    注意：所有 bridge 共用同一 instanceKey，第 1 条之后该行的基线已被自身抬高，
            //    故后续 bridge 不再重复做并发判定（否则会把自身链式注册误判为冲突）
            for (AgentInfoDto info : agentService.listAgents()) {
                String name = info.getName();
                if (name == null || name.isBlank() || name.equals(properties.getAgentKey())) {
                    continue; // 跳过与通用路由同名的 key，避免重复注册
                }
                SessionBridge business = instrumentAgent(name, name, name);
                opened.add(business);
            }
            bridges = opened;
            started = true;
            log.info(
                    "aistio 数据面已注册 {} 条 agent（通用路由 + 业务 agent），gRPC 对话流已拉起（controlPlaneHttp={}, grpc={}）",
                    opened.size(),
                    properties.getControlPlaneUrl(),
                    properties.getGrpcControlPlaneUrl());
        } catch (InstanceKeyConflictException e) {
            // 身份冲突不是瞬时故障：重试只会反复 upsert 同一行并抬高 generation，必须停止
            closeQuietly(opened);
            refused = true;
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            log.error("aistio 集成已拒绝启动（不会自动重试，需人工处理）: {}", e.getMessage());
        } catch (Exception e) {
            // 部分失败时回滚已建立的 bridge，下一周期重试
            closeQuietly(opened);
            log.warn("aistio 集成启动失败（下一周期自动重试）: {}", e.getMessage());
        }
    }

    private static void closeQuietly(List<SessionBridge> opened) {
        opened.forEach(b -> {
            try {
                b.close();
            } catch (Exception ignored) {
                // 关闭异常忽略
            }
        });
    }

    /**
     * 实例身份自检：注册前后比对 generation 增量，判定是否有<b>并发写入者</b>抢占了同一 instanceKey。
     *
     * <p>判据不能是「{@code generation > 1}」—— 本进程每次注册都会让控制面对该行 upsert 并 +1，
     * 故第二次启动起该条件必然成立（每重启一次顶一格，实测被顶到 27），把自身注册误判为外部冲突。
     * 正确判据是「本次注册的增量是否恰好为 1」：注册前查一次基线，注册后增量 &gt; 1 才说明
     * 期间有另一个进程也写入了同一行。</p>
     *
     * <p>注册尚未完成时 {@code getRegisteredIdentity()} 为 null，此时不阻断（下一周期自然重试）。</p>
     *
     * @param baseline 注册前从控制面读到的 generation；{@code null} 表示无基线（首次注册 / 查询失败），
     *                 此时无从判定并发写入，放行
     */
    static void requireFreshInstance(SessionBridge bridge, String agentKey, Long baseline) {
        HttpSelfRegistration.RegisteredIdentity identity = bridge.getRegisteredIdentity();
        if (identity == null) {
            return;
        }
        requireFreshGeneration(identity.generation(), agentKey, identity.instanceKey(), baseline);
    }

    /**
     * 纯函数形式的判定，便于单测。
     *
     * @param baseline 注册前基线 generation；{@code null} 表示无基线可依，放行
     */
    static void requireFreshGeneration(long generation, String agentKey, String instanceKey, Long baseline) {
        if (baseline == null) {
            return;
        }
        long delta = generation - baseline;
        if (delta <= 1) {
            return;
        }
        throw new InstanceKeyConflictException(
                "instanceKey 冲突：agentKey=" + agentKey + " instanceKey=" + instanceKey
                        + " generation=" + generation + " baseline=" + baseline + " delta=" + delta
                        + "（本次注册期间该 instanceKey 被另一进程并发写入；请排查重复实例或改用独立 yunxi.aistio.instanceKey）");
    }

    /**
     * 读取注册前基线：控制面目录中该 instanceKey 当前的 generation。
     *
     * <p>取不到（首次注册、控制面不可达、无匹配记录）一律返回 {@code null} 放行 —— 探测失败
     * 不应把正常启动拦下，激进拦截的代价远高于漏判一次并发冲突。</p>
     */
    Long readGenerationBaseline(String agentKey) {
        String instanceKey = resolveInstanceKey();
        try {
            List<Map<String, Object>> instances = queryInstances(agentKey);
            return instances.stream()
                    .filter(it -> instanceKey.equals(String.valueOf(it.get("instanceKey"))))
                    .map(it -> parseLong(it.get("generation")))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.debug("aistio 基线查询失败（按无基线放行）: instanceKey={} err={}", instanceKey, e.getMessage());
            return null;
        }
    }

    /** 按 agentKey 查目录中的 instance 列表；agent 不存在或查询失败返回空列表 */
    private List<Map<String, Object>> queryInstances(String agentKey) throws Exception {
        String base = properties.getControlPlaneUrl();
        if (base == null || base.isBlank()) {
            return List.of();
        }
        ControlPlaneHttpClient http =
                new ControlPlaneHttpClient(base, properties.getInternalToken());
        String agentsPath = "/api/v1/agents?tenant=" + properties.getTenant()
                + "&namespace=" + properties.getNamespace();
        JsonNode agents = ControlPlaneHttpClient.mapper().readTree(http.send("GET", agentsPath, null).body());
        String agentId = null;
        for (JsonNode node : agents.path("items")) {
            if (agentKey.equals(node.path("agentKey").asText())) {
                agentId = node.path("id").asText("");
                break;
            }
        }
        if (agentId == null || agentId.isBlank()) {
            return List.of(); // 首次注册：agent 尚未进目录
        }
        JsonNode instances =
                ControlPlaneHttpClient.mapper().readTree(http.send("GET", "/api/v1/agents/" + agentId + "/instances", null).body());
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode node : instances.path("items")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("instanceKey", node.path("instanceKey").asText(""));
            row.put("generation", node.path("generation").asLong());
            result.add(row);
        }
        return result;
    }

    private static Long parseLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 实例身份/配置类不可重试错误 */
    static class InstanceKeyConflictException extends RuntimeException {
        InstanceKeyConflictException(String message) {
            super(message);
        }
    }

    /**
     * 为单个 agentKey 构建配置并注册一条 {@code SessionBridge}。
     *
     * @param agentKey          注册到 aistio 目录的稳定标识（与 agent 实例对象解耦）
     * @param instrumentAgentName 用于仪表化的代表性 agent 实例名（须为已注册的业务 agent）
     * @param defaultAgentName   该 bridge adapter 的回落默认 agent（意图无建议时使用）
     */
    private SessionBridge instrumentAgent(String agentKey, String instrumentAgentName, String defaultAgentName) {
        AistioConfig config = AistioConfig.builder(agentKey)
                .controlPlane(toGrpcControlPlane(properties.getGrpcControlPlaneUrl()))
                .controlPlaneHttp(properties.getControlPlaneUrl())
                .internalToken(properties.getInternalToken())
                .tenant(properties.getTenant())
                .namespace(properties.getNamespace())
                .instanceKey(resolveInstanceKey())
                .publicBaseUrl(properties.getBaseUrl())
                .startHttp(false)
                .startHttpRegister(true)
                .startGrpc(true)
                .build();

        YunxiFrameworkAdapter adapter =
                new YunxiFrameworkAdapter(agentService, intentEngine, defaultAgentName, properties.getConsoleUserId());
        adapter.setTaskStarter(buildTaskStarter(instrumentAgentName));
        Agent agent = agentService.getAgentInstance(instrumentAgentName);
        return Aistio.instrument(agent, config, adapter);
    }

    /**
     * 装配 AgentTask 接活链（编排任务下推）：框架 {@code HarnessAgentTaskStarter} 承担完整执行生命周期，
     * yunxi 只在外面套一层 {@link AistioTaskBridge} 做优雅下线闸门与 correlation 读回。
     *
     * <p>协作客户端复用控制面 HTTP 地址与内网令牌（与自注册同一套凭据，不新建配置树）。</p>
     */
    private AistioTaskBridge buildTaskStarter(String instrumentAgentName) {
        ControlPlaneHttpClient http =
                new ControlPlaneHttpClient(properties.getControlPlaneUrl(), properties.getInternalToken());
        CollaborationClient collaboration = new CollaborationClient(http);
        HarnessAgentTaskStarter starter = new HarnessAgentTaskStarter(
                        () -> (HarnessAgent) agentService.getAgentInstance(instrumentAgentName), collaboration)
                // 装了 workspace 工厂才能声明 workspace-definition-v1 能力；否则控制面对带 definition
                // 的派发会永久 skip 本实例（注册成功却永不接活）
                .withWorkspaceFactory(new HarnessWorkspaceAgentFactory(workspaceRoot(),
                        () -> HarnessAgent.builder().model(modelFactory.create(null))));
        AistioTaskBridge bridge = new AistioTaskBridge(starter);
        taskBridges.add(bridge);
        return bridge;
    }

    /**
     * 稳定实例标识：显式配置优先，否则 {@code ${app}-${hostname}-${port}}。
     *
     * <p>不可用随机 UUID —— 重启即变会让「generation&gt;1」这个唯一的冲突信号永远观测不到。</p>
     */
    String resolveInstanceKey() {
        String configured = properties.getInstanceKey();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String app = environment == null
                ? "yunxi-agent-platform"
                : environment.getProperty("spring.application.name", "yunxi-agent-platform");
        String port = environment == null ? "8080" : environment.getProperty("server.port", "8080");
        return app + "-" + localHostName() + "-" + port;
    }

    /** workspace 定义物化根目录：显式配置优先，否则落到系统临时目录 */
    private Path workspaceRoot() {
        String configured = properties.getWorkspaceRoot();
        return Path.of(configured == null || configured.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/yunxi-aistio-workspaces"
                : configured.trim());
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknownhost";
        }
    }

    /**
     * 把 yunxi 配置中的 gRPC 控制面地址（可能带 http:// 前缀）规整为框架所需的 {@code host:port}。
     * 框架 {@code controlPlane} 只接受 host:port，不接收 scheme 或路径。
     */
    private static String toGrpcControlPlane(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        if (s.startsWith("http://")) {
            s = s.substring("http://".length());
        } else if (s.startsWith("https://")) {
            s = s.substring("https://".length());
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        // 优雅下线：① 停接新活 → ② 等在跑 attempt 完成 → ③ 关闭连接
        //
        // 顺序与控制面实现实测的差异及理由：协议并未提供独立的「置非健康」端点，实测控制面
        // （dataplane_handler.go:75-96）只有 DELETE /api/v1/dataplanes/{id}，其语义是
        // 「SetAgentInstanceHealth(unhealthy) + registry.Delete」二合一 —— 没有独立的置非健康端点。
        // 若按文档先 DELETE，在跑 attempt 的回传会失去实例归属，反而更容易丢结果。
        // 因此这里把「不再被选中」的责任放在数据面：新 attempt 由 AistioTaskBridge 立即失败回传
        // （而非执行到一半被杀），等存量跑完再关闭。代价是控制面仍会短暂把新 attempt 派给本实例，
        // 多副本场景下不如"真置非健康"优雅 —— 需控制面补一个独立的健康位点才能真正闭环。
        for (AistioTaskBridge taskBridge : taskBridges) {
            taskBridge.stopAccepting();
        }
        waitForActiveAttempts();
        if (bridges != null) {
            for (SessionBridge b : bridges) {
                try {
                    b.close();
                } catch (Exception e) {
                    log.warn("aistio 集成关闭异常: {}", e.getMessage());
                }
            }
            log.info("aistio 集成已优雅关闭（{} 条 bridge）", bridges.size());
        }
    }

    /** 等待在跑 attempt 完成（上限 {@code yunxi.aistio.shutdown-wait-sec}，超时即强制关闭） */
    private void waitForActiveAttempts() {
        long deadline = System.currentTimeMillis() + Math.max(0, properties.getShutdownWaitSec()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            int active = taskBridges.stream().mapToInt(AistioTaskBridge::activeAttemptCount).sum();
            if (active == 0) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待在跑 attempt 时被中断，剩余 {} 个 attempt 可能被丢弃", active);
                return;
            }
        }
        int remaining = taskBridges.stream().mapToInt(AistioTaskBridge::activeAttemptCount).sum();
        log.warn("优雅下线等待超时（{}s），仍有 {} 个 attempt 在跑，强制关闭", properties.getShutdownWaitSec(), remaining);
    }
}
