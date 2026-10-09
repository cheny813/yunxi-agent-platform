package io.yunxi.platform.aistio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * aistio 集成配置（绑定 {@code yunxi.aistio.*}）。
 *
 * <p>本 Bean <b>始终加载</b>（无 {@code @ConditionalOnProperty}），与 {@link io.yunxi.platform.session.registry.ActiveSessionRegistry}
 * 类似——即便集成关闭，读取默认值也不会破坏任何东西。真正的装配开关在 {@code enabled} 上，
 * 由 {@code AistioAutoConfiguration} 与各组件的 {@code @ConditionalOnProperty} 控制。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "yunxi.aistio")
public class AistioIntegrationProperties {

    /** 总开关，默认关闭（符合「零新增基建、不侵入主线」原则） */
    private boolean enabled = false;

    /**
     * 数据面实例标识（必须全局唯一，且不能在每次重连/重启时重新生成）。
     *
     * <p>留空时由 {@code ${spring.application.name}-${hostname}-${server.port}} 推导。
     * 同一 instanceKey 重复注册会被控制面 upsert 为同一行并 {@code generation+1}，导致 attempt 归属错位；
     * 而随机生成的 key 会让「generation&gt;1」这个唯一可观测的冲突信号永远不出现，故默认走稳定推导。</p>
     */
    private String instanceKey = "";

    /** 优雅下线时在跑 attempt 的最长等待秒数（超时即强制关闭，避免停机挂死） */
    private int shutdownWaitSec = 30;

    /**
     * workspace 定义物化根目录（控制面下发的 definition 会在此为每个 attempt 建临时工作区）。
     * 留空时用 {@code ${java.io.tmpdir}/yunxi-aistio-workspaces}。
     */
    private String workspaceRoot = "";

    /** aistio 控制面地址（aistiod HTTP，默认 :8081），用于启动时自动注册数据面 */
    private String controlPlaneUrl = "http://localhost:8081";

    /** 与控制面通信的共享令牌；为空表示不校验（建议仅在受信任网络内部署时如此配置） */
    private String internalToken = "";

    /** 本数据面的契约基址（host:port，不含路径）；aistio 在其上拼接 /agentscope/health、/agentscope/sessions 等。
     *  注意：routingKey（契约基址）即本值本身，不要在 yunxi 侧再拼 /agentscope，否则会变成双 /agentscope。 */
    private String baseUrl = "http://localhost:40001";

    /** 数据面心跳间隔（秒）；控制面 Poller 仅做 MarkStale，实例健康需数据面周期性心跳维持，须小于控制面 StaleAfter(45s) */
    private int heartbeatIntervalSec = 15;

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

    /**
     * /context 是否对超窗口对话启用框架 ConversationCompactor 压缩（与主体 Agent 一致的运行时压缩内核）。
     * 关闭、模型不可用或压缩超时均自动回退到朴素「最近 N 轮」窗口。默认开启。
     */
    private boolean contextCompactionEnabled = true;

    /** /context 压缩调用的 LLM 超时（秒），超时即回退朴素窗口。默认 10 */
    private int contextCompactionTimeoutSec = 10;

    /** 是否启用 gRPC 实时上报（aistio 团队的实时事件上报通道，需 gRPC proto 代码生成；默认关闭） */
    private boolean grpcEnabled = false;

    /** aistio ASDP gRPC 控制面地址（数据面对话流长连接目标）。默认 :15010——aistio 容器内 ASDP gRPC 绑定端口，
     *  已在 docker-compose 中发布到宿主机；若该端口未发布，gRPC 对话流无法建立。 */
    private String grpcControlPlaneUrl = "http://localhost:15010";

    /** 端到端对话所调用的 yunxi agent 定义名（对应 agent-config 下 <name>.yml，bean 名 <name>-agent）。
     *  默认 safety-assistant；按实际可调。 */
    private String chatAgentName = "safety-assistant";

    /** 控制台对话会话统一落入的多租户 userId（不同 aistio 会话由唯一 sessionId 隔离，互不影响） */
    private String consoleUserId = "aistio-console";

    /**
     * 编排图 artifact 导出配置。默认关闭，由运维按需开启：
     * 开启后，进程启动（@PostConstruct）会扫描所有 agent 定义的 workflow 挂载，
     * 将内联图校验并导出为 DefinitionSpec artifact，供控制台导入。
     */
    private WorkflowExportConfig workflow = new WorkflowExportConfig();

    /**
     * 编排触发配置（automation webhook）。默认关闭 —— 编排定义与 automation 须由运维在 aistio
     * 控制台创建并把 secret 下发到此处后，yunxi 才具备触发能力（数据面身份对编排资源一律 403）。
     */
    private TriggerConfig trigger = new TriggerConfig();

    /**
     * 编排触发：definitionKey → automation 绑定。
     *
     * <p>按场景拆分绑定：webhook 限流是 60 次/分钟、按 {@code automationId + ClientIP} 共享，
     * 且 secret 是 automation 级；所有场景共用一个 automation 会让单一场景打满配额，且泄露影响面波及全部场景。</p>
     */
    @Data
    public static class TriggerConfig {

        /** 是否启用编排触发，默认关闭 */
        private boolean enabled = false;

        /** definitionKey → automation 绑定（key 为 yunxi 侧定义引用名，非服务端 definitionId） */
        private Map<String, AutomationBinding> automations = new LinkedHashMap<>();

        @Data
        public static class AutomationBinding {

            /** 控制台创建的 automation ID */
            private String automationId;

            /** 该 automation 下 type=webhook 的 trigger ID */
            private String triggerId;

            /** 控制台创建时一次性下发的 webhook secret */
            private String secret;
        }
    }

    @Data
    public static class WorkflowExportConfig {

        /** 是否启用启动期导出，默认关闭 */
        private boolean exportEnabled = false;

        /** artifact 导出目录，默认 ./workflow-artifacts */
        private String exportDir = "./workflow-artifacts";

        /**
         * 导出后是否自动推送至 aistio 控制面（{@code POST /api/v1/orchestration-definitions}），
         * 消除控制台手工导入。默认关闭。
         *
         * <p>鉴权采用控制台账号令牌（Personal Access Token），以 {@code Authorization: Bearer} 携带。
         * 该端点的协作身份中间价要求登录账号，内部数据面令牌（X-Builder-Internal-Token）不足以创建
     * 编排定义，因此本推送使用控制台 PAT，而非 {@code yunxi.aistio.internal-token}。本推送只能复用框架
     * 已暴露的控制台账号通道，不能改动服务端鉴权（yunxi 不修改 AgentScope-Java 框架本体）。PAT 由运维在
     * aistio 控制台一次性签发后配置于此，之后进程启动即自动推送，无需手工导入。</p>
         */
        private boolean pushEnabled = false;

        /**
         * 推送所用的控制台账号令牌（PAT）。为空时不推送。
         * 仅建议在受信任网络内部署时明文配置；生产环境应经由环境变量 / 密钥管理注入。
         */
        private String pushToken = "";

        /**
         * 推送目标命名空间。留空则默认推送到 {@code default} 命名空间（控制面 global 命名空间，
         * 须确保该 PAT 拥有者在其中具备 developer/operator 写权限，否则控制面返回 403）。
         * 注意控制面的个人命名空间形如 {@code personal-<hash>}（非 {@code ns-<userId>}），无法从 PAT 推导，
         * 若需推送到个人命名空间，须在此显式配置其真实名称。
         */
        private String pushNamespace = "";

        /**
         * 已注册实体的 UUID 白名单（agentId / teamRef / subrun 的 definitionRevisionId）。
         * translator 据此做 E3 校验（节点引用须在本环境已注册）。空集合表示不做 E3 拦截
         * （依赖服务端导入期校验兜底）。
         */
        private List<String> registeredEntityIds = new ArrayList<>();

        /** translator 版本号，写入 manifest 用于版本漂移比对 */
        private String translatorVersion = "1.0.0";
    }
}
