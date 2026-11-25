package io.yunxi.definition;

import java.util.List;
import java.util.Map;

/**
 * 定义平面。yunxi 原生的 AgentSpec 是各平面唯一共同消费的定义契约。它是一份纯数据：不携带任何运行时或框架语义，
 * 也是唯一允许存在的定义类型（关于「一个 Agent 是什么」的唯一真相源）。
 *
 * <p>定义本身是纯数据，不内联任何策略体；模型路由 / 可观测性等运行时关切由产品平面按 agentId 解析，
 * 不在此声明。</p>
 */
public record AgentSpec(
        /** Agent 身份：唯一 id、展示名与职责描述；必填，description 同时是系统提示词全文来源。 */
        Identity identity,
        /** 技能边界：可见 / 可加载的技能集合与选择模式；缺省沿用框架默认全开。 */
        SkillConfig skill,
        /** MCP 订阅：本 Agent 可使用的 MCP 服务名列表，订阅即由权限模板自动放行其工具。 */
        McpConfig mcp,
        /** 会话状态：状态存储后端及是否跨轮持久化；缺省为内存态、不持久化。 */
        StateConfig state,
        /** 工具权限策略（必填）：白名单 / 黑名单 / 人工确认，缺失即校验失败。 */
        PermissionConfig permission,
        /** 工具箱裁剪：从框架默认工具箱中摘除的工具名列表。 */
        ToolkitConfig toolkit,
        /** 子 Agent 声明集合：本 Agent 可委派的通用子 Agent。 */
        SubagentsConfig subagents,
        /** 沙箱执行声明：仅声明时放行主路径代码执行，由隔离容器承载。 */
        SandboxConfig sandbox,
        /** 调度定义：定时 / 周期触发该 Agent 的模式与参数；缺省为交互式调用。 */
        ScheduleConfig schedule,
        /** 任务清单能力开关：是否启用 todo_write 与任务提醒中间件；缺省关闭。 */
        PlanConfig plan,
        /** 意图辅助：意图分类用的短卡片；缺省从 identity.description 自动派生。 */
        IntentConfig intent) {

    /** 兼容构造：既有定义文件不声明 intent 段时保持为零迁移。 */
    public AgentSpec(
            Identity identity,
            SkillConfig skill,
            McpConfig mcp,
            StateConfig state,
            PermissionConfig permission,
            ToolkitConfig toolkit,
            SubagentsConfig subagents,
            SandboxConfig sandbox,
            ScheduleConfig schedule,
            PlanConfig plan) {
        this(identity, skill, mcp, state, permission, toolkit, subagents, sandbox, schedule, plan, null);
    }

    /** 兼容构造：既有定义文件不声明 plan 段时保持为零迁移。 */
    public AgentSpec(
            Identity identity,
            SkillConfig skill,
            McpConfig mcp,
            StateConfig state,
            PermissionConfig permission,
            ToolkitConfig toolkit,
            SubagentsConfig subagents,
            SandboxConfig sandbox,
            ScheduleConfig schedule) {
        this(identity, skill, mcp, state, permission, toolkit, subagents, sandbox, schedule, null);
    }

    /** 兼容构造：既有定义文件不声明调度段与 plan 段时保持为零迁移。 */
    public AgentSpec(
            Identity identity,
            SkillConfig skill,
            McpConfig mcp,
            StateConfig state,
            PermissionConfig permission,
            ToolkitConfig toolkit,
            SubagentsConfig subagents,
            SandboxConfig sandbox) {
        this(identity, skill, mcp, state, permission, toolkit, subagents, sandbox, null, null);
    }

    public record Identity(
            /** Agent 唯一标识，同一配置中心内寻址键。 */
            String agentId,
            /** 展示名称，面向用户与日志。 */
            String name,
            /** 职责描述；同时作为系统提示词的全文来源。 */
            String description) {}

    /**
     * 技能选择模式（产品平面语义，不携带任何 AgentScope-Java 框架类型）。
     *
     * <ul>
     *   <li>{@code ALL}：框架默认，所有技能可见（含 MUSE 动态生成），自进化 Agent 的推荐态。</li>
     *   <li>{@code WHITELIST}：只允许列表内技能；仅用于明确不启用 MUSE 的静态 Agent，误用于自进化 Agent 会杀飞轮。</li>
     *   <li>{@code BLACKLIST}：排除列表内、保留其余（含 MUSE 生成）；自进化 Agent 表达「场景专精」的正确方式。</li>
     *   <li>{@code NONE}：显式零技能，彻底无技能仓库（load_skill_through_path 自动不注册）。</li>
     * </ul>
     */
    public enum SkillMode {
        ALL, WHITELIST, BLACKLIST, NONE
    }

    /**
     * 顶层 Agent 技能边界（产品平面语义，不携带任何 AgentScope-Java 框架类型）。
     *
     * <p>{@code skillMode} 与 {@code skills} 共同决定「哪些技能对该 Agent 可见 / 可加载」。之所以必须显式区分
     * 模式而不用一个裸列表，是因为「只允许这些」与「排除这些」是两种<b>相反</b>语义，且后者（排除式）是自进化
     * Agent 保住飞轮的<b>唯一安全</b>选择：底层框架 {@code SkillFilter.only} 是硬白名单（isAllowed 只认列表内
     * 名字），而 MUSE 运行时生成的技能是动态命名、必然不在任何静态白名单中——若对启用 MUSE 的 Agent 用白名单，
     * 它进化出的技能自己永远加载不了，飞轮死亡。故「场景专精」只能表达为排除式（BLACKLIST），白名单仅适用于
     * 明确不启用 MUSE 的静态 Agent。</p>
     *
     * <p>缺省（skillMode 不声明，即 null）：skills 为空列表 / null 视为 {@code ALL}（沿用框架默认全开，零回归）；
     * skills 非空视为 {@code BLACKLIST}（排除式，保飞轮，安全兜底——不可逆的「杀飞轮」优先于可逆的「多暴露噪音」）。</p>
     */
    public record SkillConfig(
            /** 技能名列表；其语义由 skillMode 决定（白名单成员 / 黑名单成员）。 */
            List<String> skills,
            /** 技能选择模式：ALL / WHITELIST / BLACKLIST / NONE。缺省 null，按 skills 是否为空兜底（见上方类注释）。 */
            SkillMode skillMode) {}

    public record McpConfig(
            /** 订阅的 MCP 服务名列表；订阅即由权限模板自动放行其工具。 */
            List<String> servers) {}

    public record StateConfig(
            /** 会话状态存储后端标识（如 inmemory）。 */
            String store,
            /** 是否跨会话持久化状态。 */
            boolean persist) {}

    /**
     * HITL 权限配置（产品平面语义，不携带任何 AgentScope-Java 框架类型）。
     * <p>mode 不直接出现在配置里，由 {@code io.yunxi.runtime.permission.PermissionTemplateResolver}
     * 在构建期依据本配置派生，翻译为框架 {@code PermissionContextState}。配置即数据，
     * 翻译即代码，二者边界清晰，符合「配置不携带框架语义」的约束。</p>
     */
    public record PermissionConfig(
            /** 工具门控：声明需人工确认（ASK）的工具；与推理复核共用待确认清单。 */
            ToolGate toolGate,
            /** 推理复核：高风险工具调用前二次确认；null 表示不启用。 */
            ReasoningReview reasoningReview,
            /** 工具白名单：仅列出的工具可用；与 deniedTools 不可同时声明（除非启用门控）。 */
            List<String> allowedTools,
            /** 工具黑名单：列出的工具被拒绝，其余放行；与 allowedTools 不可同时声明（除非启用门控）。 */
            List<String> deniedTools) {

        /** 工具门控：声明需要人工确认（ASK）的工具清单。 */
        public record ToolGate(
                /** 是否启用门控；false 时 tools 不生效。 */
                boolean enabled,
                /** 需人工确认的工具名列表。 */
                List<String> tools) {}

        /** 推理复核：对高风险工具调用前做二次确认；复用 {@link ToolGate#tools()} 作为待复核清单。 */
        public record ReasoningReview(
                /** 是否启用复核。 */
                boolean enabled,
                /** 复核策略标识，由构建期翻译。 */
                String strategy,
                /** 触发复核的关键词。 */
                List<String> keywords) {}

        /**
         * 需要人工确认（ASK）的工具清单：{@code toolGate} 或 {@code reasoningReview} 任一启用时，
         * 取 {@link ToolGate#tools()}；否则为空（表示「无人值守」）。
         *
         * <p>这条派生规则是「有人值守 / 无人值守」的唯一判定来源——定义期校验（能否同时声明白名单与黑名单）
         * 与构建期翻译（派生 {@code PermissionMode}）都必须读它。历史上两处各写一份判空逻辑，
         * 一处改动另一处漏改，就会出现「校验通过的配置在运行期落到另一个分支」。
         */
        public List<String> askTools() {
            List<String> tools = toolGate != null ? toolGate.tools() : null;
            if (tools == null || tools.isEmpty()) {
                return List.of();
            }
            boolean gateOn = toolGate.enabled();
            boolean reviewOn = reasoningReview != null && reasoningReview.enabled();
            return gateOn || reviewOn ? tools : List.of();
        }

        /** 是否声明了「有人值守」能力，等价于 {@code !askTools().isEmpty()}。 */
        public boolean hasAskTools() {
            return !askTools().isEmpty();
        }
    }

    /**
     * 工具箱裁剪（产品平面语义，不携带任何 AgentScope-Java 框架类型）。
     *
     * <p>{@code deniedToolNames} 列出要从工具箱摘除的工具名。必须明确区分两类来源，避免把技能工具
     * 误当成框架默认工具去照抄：
     * <p>（1）框架工作区默认工具：read_file / write_file / list_files / grep_files / glob_files /
     * execute——它们是 AgentScope-Java 在构建每个 Agent 时自动装备的框架默认工具（由框架 ToolFilter /
     * ToolsConfig 注册），与 yunxi 业务无关，纯业务 Agent 通常应 deny。
     * <p>（2）load_skill_through_path——<b>不属于框架默认工具</b>。它只在 Agent 挂了技能中间件
     * 技能仓库（AgentSkillRepository，如 MUSE 自进化挂载的元工具）时才由框架注册；只有
     * 「明确不让该 Agent 加载技能」时才 deny 它，切勿照搬到需要技能的 Agent 上（会破坏技能加载）。
     *
     * <p>该声明在装配点 AgentConfigurer 翻译为框架 ToolsConfig.deny，于 HarnessAgent 构建期从工具箱移除。
     * 需要文件 / 代码执行能力的 Agent，不声明 toolkit 字段（保留框架默认），或从 deniedToolNames
     * 中移除对应工具名即可。
     */
    public record ToolkitConfig(
            /** 要从工具箱摘除的工具名列表；两类来源区别见上方类注释。 */
            List<String> deniedToolNames) {}

    /**
     * 子 Agent 声明集合（产品平面语义，不携带任何 AgentScope-Java 框架类型）。
     *
     * <p>通用 sub-agent 由 yunxi 采纳并接线：定义平面只声明「有哪些子 Agent、各自职责与工具边界」，
     * 运行时期望由 {@code io.yunxi.runtime.AgentConfigurer} 翻译为 AgentScope-Java 的
     * {@code SubagentDeclaration} 并经 {@code HarnessAgent.Builder.subagents(...)} 装配，
     * 由框架在构建期自动挂上对应的 sub-agent 中间件与后台执行能力。定义即数据，框架语义翻译留在装配点，
     * 符合「配置不携带框架语义」约束，也不与框架能力形成并行的事实源。</p>
     */
    public record SubagentsConfig(
            /** 本 Agent 可委派的通用子 Agent 声明列表。 */
            List<SubagentSpec> subagents) {}

    /**
     * 真实沙箱执行后端声明（产品平面语义，不携带任何 AgentScope-Java 框架类型）。
     * <p>仅当本字段非 null 时，主路径 {@code execute} 才被放行并经 AgentConfigurer 绑定框架
     * {@code DockerSandboxClient} 提供隔离执行；不声明则主路径代码执行默认 deny。配置即数据，
     * 容器选项的解析与执行器翻译留在装配点，符合「配置不携带框架语义」约束。</p>
     */
    public record SandboxConfig(
            /** 沙箱后端；当前仅支持 docker。 */
            String backend,
            /** 容器镜像；缺省 ubuntu:22.04。 */
            String image,
            /** 工作区根（宿主机可写目录）；缺省框架 /workspace。 */
            String workspaceRoot,
            /** 容器环境变量。 */
            Map<String, String> environment,
            /** 内存上限（字节）。 */
            Long memorySizeBytes,
            /** CPU 核数。 */
            Long cpuCount,
            /** 暴露端口。 */
            List<Integer> exposedPorts,
            /** 容器网络模式。 */
            String network,
            /** 额外 docker run 参数。 */
            List<String> additionalRunArgs,
            /** 隔离粒度：SESSION/USER/AGENT/GLOBAL；缺省 SESSION。 */
            String isolationScope,
            /** 快照策略（见 {@link SnapshotConfig}）。 */
            SnapshotConfig snapshot,
            /**
             * 沙箱执行并发上限（进程内信号量许可数）；缺省 null → AgentConfigurer 按 1（串行化）处理。
             * 防止 USER/AGENT/GLOBAL 隔离范围下多调用者竞争同一持久化 state slot（last-write-wins 覆盖）；
             * 需更高吞吐时显式调大。SESSION 范围每会话独立 state slot，串行化亦无害。
             */
            Integer maxConcurrentSandboxes) {

        /** 兼容构造：未声明并发上限时缺省为 null（→ 串行化）。 */
        public SandboxConfig(String backend, String image, String workspaceRoot,
                Map<String, String> environment, Long memorySizeBytes, Long cpuCount,
                List<Integer> exposedPorts, String network, List<String> additionalRunArgs,
                String isolationScope, SnapshotConfig snapshot) {
            this(backend, image, workspaceRoot, environment, memorySizeBytes, cpuCount,
                    exposedPorts, network, additionalRunArgs, isolationScope, snapshot, null);
        }
    }

    /** 沙箱快照策略。v1 仅支持 noop（不持久化）。 */
    public record SnapshotConfig(
            /** 快照策略类型；v1 仅接受 noop。 */
            String type,
            /** 快照存放位置；noop 策略下不使用。 */
            String location) {}

    /**
     * 计划 / 任务清单能力开关（产品平面语义，不携带任何 AgentScope-Java 框架类型）。
     * <p>该声明在装配点 AgentConfigurer 翻译为框架 HarnessAgent.Builder.enableTaskList(boolean)，
     * 决定是否给该 Agent 注册 todo_write 工具与 TaskReminderMiddleware；全局开关
     * yunxi.agent.taskList.enabled 提供另一通道，二者 OR 生效。配置即数据，框架语义翻译留在装配点，
     * 符合「配置不携带框架语义」约束。</p>
     */
    public record PlanConfig(
            /** 是否启用该 Agent 的任务清单（todo_write）能力；缺省 false（默认关）。 */
            boolean taskList) {}

    /**
     * 意图辅助声明（定义平面纯数据，不携带任何框架类型）。
     *
     * <p>{@code hint} 是作者为意图分类显式提供的短卡片（一句话职责，如「饮食记录助手：识别点餐与热量查询」）。
     * 分类卡片派生时优先于从 {@code identity.description} 自动派生的首句卡片——description 同时是系统提示词
     * 全文，其首句未必适合做分类依据；hint 允许作者用「这个 Agent 承接什么请求」的精确表述提升分类精度。
     * 未声明（intent 为 null）或 hint 为空白时一律回落自动派生，旧定义文件零迁移。</p>
     */
    public record IntentConfig(
            /** 意图分类短卡片；为空时从 identity.description 首句自动派生。 */
            String hint) {}

    /**
     * 调度定义（定义平面纯数据，不携带任何框架类型）。
     *
     * <p>{@code mode} 取 CRON / FIXED_RATE / FIXED_DELAY / NONE；相应模式的时间参数由
     * 校验器守护。{@code prompt} 作为该调度 Agent 的系统提示，{@code triggerUserId}
     * 仅用于触发日志标识，不作为身份认证凭据。</p>
     */
    public record ScheduleConfig(
            /** 调度模式：CRON / FIXED_RATE / FIXED_DELAY / NONE。 */
            String mode,
            /** CRON 表达式；mode=CRON 时必填。 */
            String cron,
            /** 固定周期毫秒；mode=FIXED_RATE 时必填且不小于 MIN_INTERVAL_MILLIS。 */
            Long rateMillis,
            /** 固定延迟毫秒；mode=FIXED_DELAY 时必填且不小于 MIN_INTERVAL_MILLIS。 */
            Long delayMillis,
            /** 首次触发前的延迟毫秒；不得为负。 */
            Long initialDelayMillis,
            /** 调度时区 id，如 Asia/Shanghai。 */
            String zoneId,
            /** 该调度 Agent 触发时使用的系统提示。 */
            String prompt,
            /** 触发用户标识，仅用于日志标识，不作为身份凭据。 */
            String triggerUserId,
            /** 调度归属租户，用于多租户隔离与配额计量。 */
            String tenantId) {

        /** 周期任务最小间隔（毫秒），定义平面与装配点共用同一常量。 */
        public static final long MIN_INTERVAL_MILLIS = 1000L;

        /** 兼容构造：既有定义文件不声明租户维度。 */
        public ScheduleConfig(
                String mode, String cron, Long rateMillis, Long delayMillis,
                Long initialDelayMillis, String zoneId, String prompt, String triggerUserId) {
            this(mode, cron, rateMillis, delayMillis, initialDelayMillis, zoneId,
                    prompt, triggerUserId, null);
        }
    }

    /**
     * 单个子 Agent 声明（产品平面语义）。
     *
     * <p>仅描述子 Agent 的「身份、职责提示、可选模型与工具 / 技能边界」；其余运行细节（最大迭代、步数、
     * 执行模式）缺省时由框架使用默认值，yunxi 不重复定义。model 缺省时由框架继承父 Agent 模型。</p>
     */
    public record SubagentSpec(
            /** 子 Agent 唯一名，父 Agent 经此名在对话中委派任务。 */
            String name,
            /** 子 Agent 职责描述，用于框架生成与展示。 */
            String description,
            /** 子 Agent 的系统提示 / 主体指令，翻译为框架 inlineAgentsBody。 */
            String prompt,
            /** 可选：子 Agent 专用模型；缺省继承父 Agent 模型。 */
            String model,
            /** 可选：子 Agent 可见工具名白名单；缺省沿用父 Agent 工具箱。 */
            List<String> tools,
            /** 可选：子 Agent 可加载的技能名；缺省不额外约束。 */
            List<String> skills,
            /** 可选：最大迭代轮数；缺省框架默认。 */
            Integer maxIters,
            /** 可选：执行步数上限；缺省框架默认。 */
            Integer steps,
            /** 可选：执行模式（如 INLINE / BACKGROUND），对应框架 SubagentDeclaration.Mode；缺省框架默认。 */
            String mode) {}

    /** 由 {@link AgentSpecValidator} 返回的轻量校验结果。 */
    public record ValidationResult(
            /** 校验是否通过。 */
            boolean valid,
            /** 错误明细；通过时为空列表。 */
            List<String> errors) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult fail(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }
}
