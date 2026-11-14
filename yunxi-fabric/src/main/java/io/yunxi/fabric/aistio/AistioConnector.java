package io.yunxi.fabric.aistio;

import io.agentscope.extensions.controlplane.ControlPlane;
import io.agentscope.extensions.controlplane.ControlPlaneConfig;
import io.agentscope.extensions.controlplane.adapter.HarnessAgentTaskStarter;
import io.agentscope.extensions.controlplane.adapter.HarnessWorkspaceAgentFactory;
import io.agentscope.extensions.controlplane.SessionBridge;
import io.agentscope.extensions.controlplane.transport.CollaborationClient;
import io.agentscope.extensions.controlplane.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * aistio 单一集成点（yunxi 产品平面）。
 *
 * <p>把已装配的 {@link HarnessAgent} 委托给框架 {@link ControlPlane#instrument(Object, ControlPlaneConfig, FrameworkAdapter)}，
 * 由框架负责注册/心跳/gRPC 对话流/数据面契约服务器与任务接活——yunxi 不重实现这些。
 * 每 Agent 新建一份 {@link YunxiFrameworkAdapter}（框架 adapter 按 Agent 有状态），并装配官方
 * {@link HarnessAgentTaskStarter} 声明 agent-task 能力；含工作区定义时再叠加框架
 * {@link HarnessWorkspaceAgentFactory}（复用，不重实现），使控制面派发的 definition 任务不再被 skip。</p>
 *
 * <p>仅在 {@code yunxi.aistio.enabled=true} 时装配，避免禁用时因空控制面 URL 在 Bean 创建期失败。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.aistio.enabled", havingValue = "true")
public class AistioConnector {

    private static final Logger log = Logger.getLogger(AistioConnector.class.getName());

    private final AistioIntegrationProperties props;
    private final ControlPlaneHttpClient controlPlaneHttp;
    private final CollaborationClient collaborationClient;
    private final Supplier<HarnessAgent.Builder> builderSupplier;
    private final Environment environment;

    /** 注入 aistio 配置与构建器底座；并按配置构造控制面 HTTP 客户端与协作客户端。 */
    public AistioConnector(AistioIntegrationProperties props,
                           YunxiAgentBuilderSupplier builderSupplier,
                           Environment environment) {
        this.props = Objects.requireNonNull(props);
        this.builderSupplier = Objects.requireNonNull(builderSupplier);
        this.environment = Objects.requireNonNull(environment);
        this.controlPlaneHttp = new ControlPlaneHttpClient(props.getControlPlaneUrl(), props.getInternalToken());
        this.collaborationClient = new CollaborationClient(controlPlaneHttp);
    }

    /** 把已装配的 Agent 委托给控制面：装配官方任务启动器（含工作区工厂），返回持有注册/心跳/gRPC 生命周期的会话桥。 */
    public SessionBridge instrument(String agentName, HarnessAgent agent, YunxiFrameworkAdapter adapter) {
        ControlPlaneConfig config = buildConfig(agentName);
        HarnessAgentTaskStarter starter = new HarnessAgentTaskStarter(() -> agent, collaborationClient)
                .withWorkspaceFactory(new HarnessWorkspaceAgentFactory(
                        Paths.get(props.getWorkspaceRoot()), builderSupplier));
        adapter.setAgentTaskStarter(starter);
        log.info("aistio 委托 Agent " + agentName
                + " (instanceKey=" + config.instanceKey() + ", workspace=" + props.getWorkspaceRoot() + ")");
        return ControlPlane.instrument(agent, config, adapter);
    }

    /** 由 yunxi 配置构建框架 {@link ControlPlaneConfig}：补全 URL/令牌/命名空间/端口/探活地址。 */
    private ControlPlaneConfig buildConfig(String agentName) {
        ControlPlaneConfig.Builder builder = ControlPlaneConfig.builder(agentName)
                .controlPlaneHttp(props.getControlPlaneUrl())
                .internalToken(props.getInternalToken())
                .namespace(props.getNamespace())
                .enableEvents(props.isEnableEvents())
                .contractHttpPort(props.getContractHttpPort())
                .startHttpRegister(props.isStartHttpRegister())
                .startGrpc(props.isStartGrpc())
                .instanceKey(resolveInstanceKey());
        if (props.getControlPlane() != null && !props.getControlPlane().isBlank()) {
            builder.controlPlane(props.getControlPlane());
        }
        // 控制面在 Docker 容器内，探活宿主机 yunxi 必须用宿主机可达地址（host.docker.internal/IP），
        // 不能留空让框架推导为 localhost（容器内 localhost 指向控制面自身，探活永远不通）。
        if (props.getBaseUrl() != null && !props.getBaseUrl().isBlank()) {
            builder.publicBaseUrl(props.getBaseUrl());
        }
        return builder.build();
    }

    /** 稳定唯一实例键：未显式配置时取 ${app}-${host}-${port}，避免框架默认 UUID 导致控制面无法判重。 */
    private String resolveInstanceKey() {
        if (props.getInstanceKey() != null && !props.getInstanceKey().isBlank()) {
            return props.getInstanceKey();
        }
        String app = environment.getProperty("spring.application.name", "yunxi");
        String host = resolveHost();
        String port = environment.getProperty("server.port", "8080");
        return app + "-" + host + "-" + port;
    }

    /** 解析本机名：优先 {@code HOSTNAME} 环境变量，否则回退 InetAddress 主机名，失败取 localhost。 */
    private static String resolveHost() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "localhost";
        }
    }
}
