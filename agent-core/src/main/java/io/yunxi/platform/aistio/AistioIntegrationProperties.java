package io.yunxi.platform.aistio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * aistio 集成配置（绑定 {@code yunxi.aistio.*}）。
 *
 * <p>本 Bean <b>始终加载</b>（无 {@code @ConditionalOnProperty}），与 {@link io.yunxi.platform.aistio.registry.ActiveSessionRegistry}
 * 类似——即便集成关闭，读取默认值也不会破坏任何东西。真正的装配开关在 {@code enabled} 上，
 * 由 {@code AistioAutoConfiguration} 与各组件的 {@code @ConditionalOnProperty} 控制。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "yunxi.aistio")
public class AistioIntegrationProperties {

    /** 总开关，默认关闭（符合「零新增基建、不侵入主线」原则） */
    private boolean enabled = false;

    /** aistio 控制面地址（aistiod HTTP，默认 :8081），用于启动时自动注册数据面 */
    private String controlPlaneUrl = "http://localhost:8081";

    /** 与控制面通信的共享令牌；为空表示不校验（建议仅在受信任网络内部署时如此配置） */
    private String internalToken = "";

    /** 本数据面的契约基址（aistio 轮询的 URL），需可被控制面网络访问 */
    private String baseUrl = "http://localhost:40001";

    /** 数据面在 aistio 目录中的稳定标识（agentKey）；同名 agent 已存在时复用并新建 instance */
    private String agentKey = "yunxi-agent-platform";

    /** 租户，上报至控制面；缺省由控制面填 default */
    private String tenant = "default";

    /** 数据面名称，作为注册展示名（displayName）与 /agentscope/info 的 name */
    private String agentName = "yunxi-agent-platform";

    /** 命名空间，上报至 DataPlaneInfo.namespace */
    private String namespace = "default";

    /** 契约等级（L1–L3），默认 3 */
    private int contractLevel = 3;

    /** 是否声明 context-query 能力（/context 端点） */
    private boolean advertiseContext = true;

    /** 是否声明 subagent-inventory 能力（/subagents 端点） */
    private boolean advertiseSubagents = true;

    /** 是否声明 workspace-inventory 能力（/workspaces 端点） */
    private boolean advertiseWorkspaces = true;

    /** 是否开放 session-command 能力（/compress、/terminate 治理动作），默认关闭 */
    private boolean commandEnabled = false;

    /** /context 返回的有效上下文窗口大小（最近 N 轮消息），默认 20 */
    private int contextWindow = 20;

    /** 是否启用 gRPC 实时上报（aistio 团队的实时事件上报通道，需 gRPC proto 代码生成；默认关闭） */
    private boolean grpcEnabled = false;
}
