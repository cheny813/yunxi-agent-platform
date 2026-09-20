package io.yunxi.platform.aistio;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.agentscope.core.agent.Agent;
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.SessionBridge;
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

    private volatile ScheduledExecutorService scheduler;
    private volatile List<SessionBridge> bridges;
    private volatile boolean started = false;

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
        if (started) {
            return;
        }
        List<SessionBridge> opened = new ArrayList<>();
        try {
            // 1) 通用路由 agent：做意图分流，代表实例取 chatAgentName；其 adapter 回落默认 = chatAgentName
            opened.add(instrumentAgent(
                    properties.getAgentKey(), properties.getChatAgentName(), properties.getChatAgentName()));
            // 2) 逐个业务 agent 注册为独立 aistio 条目（adapter 回落默认 = 自身名，每轮仍按意图改道）
            for (AgentInfoDto info : agentService.listAgents()) {
                String name = info.getName();
                if (name == null || name.isBlank() || name.equals(properties.getAgentKey())) {
                    continue; // 跳过与通用路由同名的 key，避免重复注册
                }
                opened.add(instrumentAgent(name, name, name));
            }
            bridges = opened;
            started = true;
            log.info(
                    "aistio 数据面已注册 {} 条 agent（通用路由 + 业务 agent），gRPC 对话流已拉起（controlPlaneHttp={}, grpc={}）",
                    opened.size(),
                    properties.getControlPlaneUrl(),
                    properties.getGrpcControlPlaneUrl());
        } catch (Exception e) {
            // 部分失败时回滚已建立的 bridge，下一周期重试
            opened.forEach(b -> {
                try {
                    b.close();
                } catch (Exception ignored) {
                    // 关闭异常忽略
                }
            });
            log.warn("aistio 集成启动失败（下一周期自动重试）: {}", e.getMessage());
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
                .instanceKey(UUID.randomUUID().toString())
                .publicBaseUrl(properties.getBaseUrl())
                .startHttp(false)
                .startHttpRegister(true)
                .startGrpc(true)
                .build();

        YunxiFrameworkAdapter adapter =
                new YunxiFrameworkAdapter(agentService, intentEngine, defaultAgentName, properties.getConsoleUserId());
        Agent agent = agentService.getAgentInstance(instrumentAgentName);
        return Aistio.instrument(agent, config, adapter);
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
}
