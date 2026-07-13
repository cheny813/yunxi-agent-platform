# AgentScope 2.0 GA 升级实施方案（v3：拥抱框架、按需重写）

> **原则（v3 修订）**：RC3→GA 是工程化能力的大跃迁，本方案**大力拥抱框架原生能力**，用 GA 2.0 替代 yunxi 自建的"屏蔽底层能力 / 重复开发"实现，并补齐 GA 新增而平台缺失的能力。但**不盲目删除 GA 未覆盖的合法业务价值组件**（私有模型适配、提示注入检测、HITL、业务工具、Redis 后端、多租户路由）。一句话：**屏蔽的——解锁；重复的——替换；缺失的——补齐；独有的——保留。**
>
> 目标：完成 GA 升级的同时，根治三类历史问题，让平台随框架升级自动受益。

---

> **文档修订（2026-07-11，已对照 GA 源码 `d:\work\code\agentscope-java-2.0GA` 逐行核实）**：
> 1. **入口**：必须用 `HarnessAgent.builder()`（**yunxi 已在用**，见 `AgentConfigurer` L302/L416；GA 源码确认 `HarnessAgent` wrap `ReActAgent` 并持有 `WorkspaceManager`/`CompactionMiddleware`/`PlanModeManager`/`DistributedStore`）。`PlanModeMiddleware`/`WorkspaceContextMiddleware`/`CompactionMiddleware` 等是 harness 专属中间件，依赖 `HarnessAgent` 运行时，`ReActAgent` 无法挂载。**本项重点是补齐 Builder 未用的 GA 配置**（权限/重试降级/超时/skill），非切换入口。
> 2. **重试/降级**：harness 下用 `maxRetries`/`fallbackModel`/`stopOnReject`，非 `ReActAgent` 的 `flat*` 系列。
> 3. **RuntimeContext**：`RuntimeContext.builder().userId(u).sessionId(s).build()`，另有 `.empty()`/`builder(RuntimeContext)`。
> 4. **Toolkit**：GA 公共类为 `Toolkit`（`registerTool`/`createToolGroup`/`registration().subAgent(...)`），无 `ToolkitRegistry`。
> 5. **权限**：`PermissionContextState` Builder 构造；模式仅 `DEFAULT`/`ACCEPT_EDITS`/`EXPLORE`/`BYPASS`/`DONT_ASK` 五种；"需审批"用 `DEFAULT` + `addAskRule`。
> 6. **WorkspaceManager**：`new WorkspaceManager(Path)`（可加 `AbstractFilesystem`/`WorkspaceIndex`/`NamespaceFactory`）。
> 7. **PlanModeManager**：`new PlanModeManager(WorkspaceManager, String planDir)`。
> 8. **RAG**：`rag` 包（`Knowledge`/`GenericRAGHook`/`KnowledgeRetrievalTools`/`RAGMode` 等）整体 `@Deprecated(forRemoval=true, since="2.0.0")`，注释 "integrate retrieval at the application layer"。改用应用层 `MiddlewareBase.onSystemPrompt` 注入检索上下文，或检索工具。
> 9. **MiddlewareBase 仅有 5 个钩子**：`onAgent`/`onReasoning`/`onActing`/`onModelCall`（洋葱式，返 `Flux<AgentEvent>`）+ `onSystemPrompt`（变换式，返 `Mono<String>`）。**无** `beforeToolCall`/`afterToolCall`/`onResponse`/`onPlanning`。
> 10. **GA 不自带业务工具**：仅 `FileToolUtils`（辅助）、coding-agent 示例 `WebSearchTool`；**无** `HttpTool`/`DatabaseTool`/`ShellTool`/`CalculatorTool`。yunxi 的这些是业务工具，非框架重复。
> 11. **agent-spi 真实被消费**：`VectorPersistenceProvider`(Milvus)、`DatabaseClient`/`EmbeddingService`/`Text2SqlFacade`(agent-text2sql)、`CacheProvider`(Redis)、`UserProfileProvider`(UserProfileEvolver) 均有实现方，不可删除。
> 12. **Skill 已留位但未启用**：`AgentRuntimeConfigDto.SkillConfig` 等 DTO 已存在，`AgentConfigurer` 中 `builder.skillSystem(...)` **被注释掉**——属功能缺失，应启用 GA 原生 `SkillSystem`。
> 13. **Subagent 已接通但偏弱**：`SubAgentConfig` + `.subAgent(...)` 已用，`forwardEvents(false)`；可增强为 `true` 以透出子 Agent 事件。
> 14. **记忆已正确使用 GA 原生**：`CompactionConfig`/`DistributedStore`/`RedisDistributedStore`/`MemoryFlushHook` 已在用，**不存在重复**，保留并增强（MemoryConsolidator）。
> 15. **MCP 已对齐 mcp-servers 路径 B 改造（2026-07-11）**：服务端 yunxi-mcp-servers 改用官方 MCP SDK 0.17.0、以 **SSE 为主**对外暴露（database/redis/milvus/knowledge 为 SSE；mcp-nutrition 为 HTTP；官方 puppeteer 为 STDIO）；agent-platform 侧：① 7 个自建 MCP 客户端类已删除、数据同步改直连 JDBC（`ExternalDbQueryService`）；② **残留的 `DatabaseToolkit`/`callMcpTool` 自建 MCP HTTP 客户端（不在前述 7 类中）亦已删除**（功能与 database MCP 服务器重叠且为死代码）；③ **Agent 侧 MCP 真注册已落地（P1-5 DONE）**：`AgentscopeAutoConfiguration.mcpServerBeans()` 死配置删除，`AgentConfigurer.registerMcpServers()` 用框架原生 `McpClientBuilder`（SSE/STDIO/HTTP）按 `agentscope.core.mcp-servers` 注册进 `Toolkit`（以服务器名建组，与分组激活衔接）。GA MCP 真注册按 **SSE 传输**，配置读 `agentscope.core.mcp-servers`（非 `agentscope.extensions`）。STDIO 仅用于官方 puppeteer 等。详见 `docs/mcp-servers-integration-plan.md`。

---

## 〇、GA 源码核实清单（v3 修订依据）

以下结论均对照 AgentScope Java 2.0 GA 源码（`d:\work\code\agentscope-java-2.0GA`）逐类核实，关键已确认类/接口如下（详见各节修订点）：

| 核实项 | 源码类/接口 | 结论 |
|--------|------------|------|
| 入口 Builder | `HarnessAgent` / `HarnessAgent.Builder` | plan/workspace/compaction 为 harness 专属，须用 `HarnessAgent.builder()`，非 `ReActAgent` |
| 中间件钩子 | `MiddlewareBase` | 仅 `onAgent`/`onReasoning`/`onActing`/`onModelCall`/`onSystemPrompt` 5 个；无 `beforeToolCall`/`afterToolCall`/`onResponse`/`onPlanning` |
| 权限 | `PermissionContextState` / `PermissionMode` | 仅 DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK 5 种；"需审批"用 DEFAULT + `addAskRule` |
| 运行时上下文 | `RuntimeContext` | `builder().userId().sessionId().build()`；另有 `.empty()` / `builder(RuntimeContext)` |
| 工具注册 | `Toolkit` | `registerTool` / `createToolGroup` / `registration().subAgent(...)`；无 `ToolkitRegistry` |
| Workspace | `WorkspaceManager` | `new WorkspaceManager(Path)`（可加 `AbstractFilesystem` / `WorkspaceIndex` / `NamespaceFactory`） |
| Plan | `PlanModeManager` | `new PlanModeManager(WorkspaceManager, String planDir)` |
| MCP | `McpTool` / `McpServerRegistrar` / `McpClientManager` | 支持 STDIO/SSE/HTTP 真注册 |
| 事件 | `AgentEvent`（v2） | 文本块增量 / 工具调用细粒度事件，替代自建 SSE 适配 |
| 记忆后端 | `DistributedStore` / `RedisDistributedStore` | yunxi 已正确使用，Redis 后端保留 |
| RAG | `rag` 包（`Knowledge` / `GenericRAGHook` 等） | `@Deprecated(forRemoval=true, since="2.0.0")`，应用层 `onSystemPrompt` 注入 |
| 业务工具 | `core` 默认工具集 | GA 仅 `FileToolUtils` + 示例 `WebSearchTool`；无 Http/Database/Shell，yunxi 业务工具保留 |

> **核实状态（2026-07-11，已对照 GA 源码 + yunxi 源码逐项确认）**：以上 12 项均成立。特别裁决——**Memory 不属"重复开发"**：yunxi 已用 GA 原生 `CompactionConfig`/`DistributedStore`/`RedisDistributedStore`/`MemoryFlushHook`（源码核实），归"已用 GA 原生 / 保留并增强"。另核实 yunxi `AgentConfigurer` **已在用** `HarnessAgent.builder()`（L302/L416）、`skillSystem(...)` **被注释**（L319-320）、`forwardEvents(false)`（L411）、未用 `permissionContext`/`modelExecutionConfig`/`toolExecutionConfig`/`maxRetries`/`fallbackModel`——与文档论断一致。新增发现：`HarnessAgent` 原生提供 `workspaceFor(userId,sessionId)` / `workspaceFactory`，是多租户路由的首选原生钩子。

---

## 一、三类问题 → 逐组件处置总表（核心）

| 组件（yunxi） | 归类 | GA 原生对应 | 处置 |
|------|------|------|------|
| `ToolGateMiddleware` | 屏蔽底层能力 | `permissionContext` + `PermissionMode` + `addAskRule` | **删-替**：删除，改用 GA 权限引擎 |
| `ReasoningReviewMiddleware` | 屏蔽/伪 HITL | GA 权限审批（真 suspend） | **删**：仅打日志不拦截，无价值，用权限审批替代 |
| `TextToolCallParserMiddleware` | 冗余 | 框架内部已解析 | **删**：`onReasoning` 纯透传空壳 |
| `ContentFilterMiddleware` | 独有的安全价值 | GA 无提示注入检测 | **保留-适配**：改实现 `MiddlewareBase.onAgent`，复用检测逻辑 |
| `HumanToolRegistrar` | 独有的 HITL 价值 | 包装 GA `ToolSuspendException` | **保留**：可插拔人机协作，不冲突 |
| `PermissionEngine`(无)/权限 | 功能缺失 | GA `permissionContext` | **启用 GA**：yunxi 无自建权限引擎，全面采用 GA 权限 |
| `KnowledgeAutoConfiguration`/`KnowledgeCreator`/`KnowledgeRetrievalMiddleware` | 屏蔽 + 用废弃 API | 应用层 `onSystemPrompt` 注入 / 检索工具 | **删-替**：迁移到应用层 RAG（`ApplicationRAG`） |
| `PlanPreCreator`/`PlanTemplateLoader`/plan model | 重复开发 | `PlanModeMiddleware` + `PlanModeManager` | **删-替**：启用 GA plan mode |
| `AgentWorkspaceInitializer`/`WorkspaceValidator` | 重复开发（且弱于 GA） | `WorkspaceManager` + `WorkspaceContextMiddleware`（含沙箱/Skill/子Agent/MEMORY.md/knowledge 注入） | **删-替**：改用 GA Harness Workspace（AGENTS.md 生成逻辑内联保留） |
| `WorkspaceAutoDiscoveryEngine`/`WorkspaceConfig`/`SceneDetectionRule`/`DefaultSceneDetector`/`SceneDetectionParser`（场景检测链） | 死代码（AGENTS.md `# 场景检测` 无真实使用；发现能力已被 GA `WorkspaceContextMiddleware` 覆盖） | GA `WorkspaceContextMiddleware`（知识/技能/子Agent 发现）+ yunxi `MemorySceneRegistry`（记忆场景路由） | **删除**（2026-07-12）：整条链移除，`SceneDetectionService` 裁掉 workspace-rules 级、`AgentConfigurer` 移除 `logWorkspaceDiscovery` |
| `UserWorkspaceService`（多租户路由） | 业务独有 | GA `WorkspaceManager(Path)` 单路径 | **已删-替（DONE）**：删除该类；多租户隔离改用 `RuntimeContext(userId,sessionId)` 注入，由 GA `HarnessAgent.workspaceFor(userId,sessionId)` 在运行时按用户命名空间隔离工作空间（调用点：ChatAppService.buildRuntimeContext、AgentGatewayImpl.callStream、DesktopRelayHandler），符合 R3 对策 |
| `ToolCircuitBreaker` | 重复开发 | `toolExecutionConfig`（超时+重试） | **删-替**：用 GA 执行配置 |
| `HttpTool`/`DatabaseTool`/`SessionSearchTool`/`NodeTool`/`CalculatorTool`/`ShellToolFactory` | 业务工具（非框架重复） | GA 不提供 | **保留**：业务工具，不删（注：`DatabaseToolkit` 为残留在 `shared/util/database/` 的自建 MCP HTTP 客户端、死代码，已于 2026-07-11 删除，不在此列） |
| `ModelFactory`/`BaiduModelProvider`/`HuaweiModelProvider` | 业务独有（私有模型） | GA 未覆盖百度/华为 | **保留**：私有模型适配价值-add |
| `RedisDistributedBackendConfig` | 业务独有 | GA `DistributedStore` 需后端 | **保留**：Redis 后端实现 |
| `Memory`（`CompactionConfig`/`DistributedStore` 使用） | 已用 GA 原生 | GA `CompactionMiddleware`/`MemoryConsolidator` | **保留并增强**：启用 MemoryConsolidator 等 |
| MCP（`mcpServerBeans` 仅读配置 → 已替换为 `AgentConfigurer.registerMcpServers` 真注册） | 功能缺失 → **已实现** | `McpClientBuilder`/`McpClientWrapper`/`Toolkit.registration().mcpClient()`（框架原生） | **启用 GA（DONE）**：按 SSE/STDIO/HTTP 传输真正实例化并注册工具，以服务器名建组，与 `mcpServersToolsGroup` 分组激活衔接（依赖 P0-1 将 `agentscope.version` 升至 2.0.0 方可编译） |
| 自建 MCP 客户端（`McpClient`/`McpClientService`/`McpDatabaseClient`/`McpQueryService`/`McpToolController` 等 7 类） | 重复开发（已随 mcp-servers 路径 B 改造删除） | 官方 MCP SDK + `ExternalDbQueryService`（直连 JDBC） | **已删-替（DONE）**：见 `docs/mcp-servers-integration-plan.md` |
| `SkillSystem`（被注释） | 功能缺失 | GA `skillSystem(...)` + `skills/<name>/SKILL.md` | **启用 GA**：取消注释并接技能目录 |
| `SubAgentConfig`（forwardEvents=false） | 功能偏弱 | GA 子 Agent 调度 | **增强**：`forwardEvents(true)` |
| 模型重试/降级/超时 | 功能缺失 | `maxRetries`/`fallbackModel`/`stopOnReject`/`modelExecutionConfig`/`toolExecutionConfig` | **启用 GA**：当前未使用 |
| 沙箱/追踪等 harness 中间件 | 功能缺失 | `SandboxLifecycleMiddleware`/`AgentTraceMiddleware`/`OtelTracingMiddleware`/`GracefulShutdownMiddleware`/`TaskReminderMiddleware` | **按需启用**：随 `workspace`/`plan`/`skill` 自动装载或显式挂载（注：GA 2.0 **有**内置 `TaskReminderMiddleware`，位于 `io.agentscope.core.middleware` 即 core 模块，中间件清单应保留，可启用） |
| `agent-spi` 模块 | 业务 SPI（被消费） | — | **保留**：被 text2sql/core 消费 |
| `SseProgressListenerAdapter`/`TaskProgressEvent` | 部分冗余 | GA `AgentEvent` 细粒度事件 | **删-替**：`ChatAppService` 直接映射 `AgentEvent`→SSE |
| `AgentInterruptService` | 重复开发 | `Agent.interrupt(userId,sessionId)` | **删-替**：用 GA 原生中断 |
| `CallOptions` | 重复开发 | `RuntimeContext` + `modelExecutionConfig`/`toolExecutionConfig` | **删-替**：用 RuntimeContext |
| `agent-gateway` 模块（WeCom/DingTalk/Feishu/WebApi Channel + Dispatcher/Session/限流/admin 认证） | 重复开发 + 未上线 | GA `agentscope-extensions-channel-{wecom,dingtalk,feishu,github,gitlab,common}` | **删-替**：删除自建模块，IM 接入全面用 GA Channel（渠道未上线、GA 全覆盖；网关业务逻辑随之移除） |

---

## 二、变更规模预估

| 操作 | 类别 | 说明 |
|------|------|------|
| 版本号升级 | 强制 | 根 pom `2.0.0-RC3` → `2.0.0`（GA artifact 版本即 `2.0.0`） |
| **删-替（重复/屏蔽）** | 重构 | `ToolGateMiddleware`、`ReasoningReviewMiddleware`、`TextToolCallParserMiddleware`、`Knowledge*`(RAG)、Plan 包、`AgentWorkspaceInitializer` 等 5 类、多租户冗余初始化、`ToolCircuitBreaker`、`SseProgressListenerAdapter`、`AgentInterruptService`、`CallOptions`、**`agent-gateway` 模块** |
| **启用 GA 原生** | 补齐 | `permissionContext` 权限、应用层 RAG、PlanModeMiddleware、GA WorkspaceManager、MCP 真注册、`SkillSystem`、SubAgent forwardEvents、模型重试/降级/超时、harness 中间件链 |
| **保留价值组件** | 不动 | `ContentFilterMiddleware`(适配)、`HumanToolRegistrar`、业务工具(HttpTool/DatabaseTool/...)、`ModelFactory`(私有模型)、`RedisDistributedBackendConfig`、多租户路由逻辑、`agent-spi`、记忆体系 |
| 业务层保留 | 不动 | controller、profile、conversation DB、agent-text2sql |

---

## 三、目标架构

```
yunxi-agent-platform (升级后，拥抱 GA 原生)
├── agent-config/          ← 保留（YAML/MyBatis/静态资源）
├── agent-spi/             ← 保留（被 core/text2sql 消费，不可删）
├── agent-core/
│   ├── config/            ← 增强：MCP 真注册、Skill 启用、权限/重试降级配置
│   ├── controller/        ← 保留微调
│   ├── conversation/      ← 保留业务，事件层改用 GA AgentEvent→SSE
│   ├── agent/
│   │   ├── AgentConfigurer.java       ← 重写：用 GA Builder 全量能力 + permissionContext
│   │   ├── AgentGateway.java          ← 重写：RuntimeContext + streamEvents/interrupt
│   │   ├── AgentService.java          ← 保留（仍用 ModelFactory 私有模型）
│   │   ├── model/ModelFactory.java    ← 【保留】百度/华为私有模型适配
│   │   ├── profile/                   ← 保留
│   │   └── middleware/                ← 【保留+适配】ContentFilter(提示注入)、HumanToolRegistrar(HITL)；删除 ToolGate/ReasoningReview/TextToolCallParser
│   ├── knowledge/         ← 重写：应用层 RAG（ApplicationRAG，替代废弃 rag.Knowledge）
│   ├── tool/              ← 【保留】HttpTool/DatabaseTool/SessionSearchTool/NodeTool/CalculatorTool/ShellToolFactory（DatabaseToolkit 已删除）；删除 ToolCircuitBreaker
│   ├── workspace/         ← 【删-替】删除自建，改用 GA WorkspaceManager（保留多租户路由逻辑 elsewhere）
│   ├── plan/              ← 【删-替】删除自建，改用 GA PlanModeMiddleware
│   ├── gateway/           ← 重写事件映射（AgentEvent→SSE），删除 SseProgressListenerAdapter
│   ├── cache/ file/ embedding/ shared/  ← 保留
│   └── agent/AgentInterruptService.java ← 删除（用 GA 原生 interrupt）
├── agent-gateway/         ← 【删-替】删除自建模块，IM 接入改用 GA agentscope-extensions-channel-{wecom,dingtalk,feishu}（渠道未上线，GA 全覆盖）
├── agent-rule-engine/     ← 已删除（2026-07-11，当前无实际业务使用，未来需要时再重建）
├── agent-text2sql/        ← 保留（消费 agent-spi）
├── agent-app/             ← 保留
└── agent-integration-test/← 保留微调
```

---

## 第四阶段（原第一阶段）：版本依赖升级（Day 1）

### 4.1 根 pom.xml
```xml
<agentscope.version>2.0.0</agentscope.version>   <!-- 原 2.0.0-RC3 -->
```

### 4.2 agent-core/pom.xml（确认依赖）
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-redis</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

---

## 第五阶段（原第二阶段）：处置"屏蔽/重复"组件（Day 1-2）

> 仅删除下表组件。业务工具、ModelFactory、agent-spi、ContentFilter、HumanToolRegistrar **不在此列**。

```
# Middleware（屏蔽→解锁 GA 权限；冗余→删除）
删除: agent/.../middleware/ToolGateMiddleware.java
删除: agent/.../middleware/ReasoningReviewMiddleware.java
删除: agent/.../middleware/TextToolCallParserMiddleware.java
保留+适配: agent/.../middleware/ContentFilterMiddleware.java   → 改实现 onAgent
保留: agent/.../security/hitl/HumanToolRegistrar.java

# RAG（用废弃 API → 应用层）
删除: knowledge/KnowledgeAutoConfiguration.java (rag.Knowledge 引用)
删除: knowledge/KnowledgeCreator.java (SPI, 用废弃 API)
删除: agent/.../middleware/KnowledgeRetrievalMiddleware.java
新增: knowledge/ApplicationRAG.java (应用层 onSystemPrompt 注入)

# Plan（重复→GA PlanModeMiddleware）
删除: agent/plan/PlanPreCreator.java
删除: agent/plan/PlanTemplateLoader.java
删除: agent/plan/model/*.java
删除: controller/PlanInteractionController.java

# Workspace（重复→GA WorkspaceManager；仅保留多租户路由逻辑）
删除: agent/workspace/AgentWorkspaceInitializer.java
删除: agent/workspace/WorkspaceAutoDiscoveryEngine.java
删除: agent/workspace/WorkspaceValidator.java
删除: agent/workspace/model/WorkspaceConfig.java
删除: agent/workspace/model/SceneDetectionRule.java
保留: UserWorkspaceService 中的 users/{userId}/agent/{agentName} 路由约定（迁移为 WorkspaceManager 的 Path/NamespaceFactory 构造参数）

# 工具熔断（重复→toolExecutionConfig）
删除: tool/ToolCircuitBreaker.java
保留: tool/impl/* 业务工具、tool/ShellToolFactory.java（shared/util/database/DatabaseToolkit.java 已删除，不保留）

# 传输/中断/选项（重复→GA 原生）
删除: gateway/SseProgressListenerAdapter.java
删除: gateway/TaskProgressEvent.java
删除: agent/AgentInterruptService.java
删除: agent/CallOptions.java
```

---

## 第六阶段（原第三阶段）：核心重写（Day 2-4）

### 6.1 AgentConfigurer.java — 全面重写（解锁 GA 全量能力）

```java
@Component
public class AgentConfigurer {

    private final ModelRegistry modelRegistry;            // GA 原生（公有模型）
    private final ModelFactory modelFactory;              // 【保留】私有模型（百度/华为）
    private final Toolkit toolkit;
    private final DistributedStore distributedStore;      // Redis 后端（保留）
    private final PermissionConfig permissionConfig;      // 新增：构造 GA permissionContext
    private final ApplicationRAG applicationRAG;           // 新增：应用层 RAG
    private final SkillsConfig skillsConfig;              // 新增：启用 GA SkillSystem

    /**
     * yunxi 已用 HarnessAgent.builder()（见现状代码 L302/L416）；此处重写重点是补齐 GA 配置项。
     * PlanMode/WorkspaceContext/Compaction 等是 harness 专属中间件，依赖 HarnessAgent 运行时。
     * 多租户：优先用 HarnessAgent 原生 workspaceFactory(userId,sessionId) / workspaceFor(...)，
     * 将 users/{userId}/agent/{agentName} 映射为独立 WorkspaceManager 视图，而非手工拼 Path。
     */
    public HarnessAgent configure(AgentDefinition def) {
        Model model = resolveModel(def);   // 先查 ModelFactory(私有)，再查 ModelRegistry(公有)

        // 子 Agent 注册进 toolkit（forwardEvents=true 透出事件），避免后续再次 .toolkit() 覆盖已注册工具
        Toolkit tk = toolkit;
        for (var e : def.getSubAgents().entrySet()) {
            tk = tk.registration().subAgent(
                e::getValue, SubAgentConfig.builder().forwardEvents(true).build());
        }

        HarnessAgent.Builder builder = HarnessAgent.builder()
            .name(def.getName())
            .description(def.getDescription())
            .sysPrompt(def.getPrompt())
            .model(model)
            .toolkit(tk)
            .maxIters(def.getRuntime().getMaxIters())
            // GA 新增：执行超时
            .modelExecutionConfig(ExecutionConfig.builder()
                .timeout(def.getRuntime().getModelTimeout()).build())
            .toolExecutionConfig(ExecutionConfig.builder()
                .timeout(def.getRuntime().getToolTimeout()).build())
            // GA 新增：权限（替代 ToolGateMiddleware）
            .permissionContext(permissionConfig.build(def))
            // GA 新增：模型重试/降级
            .maxRetries(def.getRuntime().getMaxRetries())
            .fallbackModel(resolveFallback(def))
            .stopOnReject(true)
            .enablePendingToolRecovery(true)
            // GA 原生能力（Builder 标志即触发 harness 自动装载对应中间件）
            .workspace(workspacePathFor(def))             // → WorkspaceContextMiddleware（替代自建 workspace）
            .compaction(buildCompactionConfig(def))        // → CompactionMiddleware（已用，保留）
            .enableMetaTool(true)
            .distributedStore(distributedStore);

        // Plan（替代自建 PlanPreCreator）
        if (def.getRuntime().isPlanEnabled()) {
            builder.middleware(new PlanModeMiddleware(
                new PlanModeManager(workspaceManagerFor(def), planDirFor(def)),
                readOnlyResolver));
        }
        // 应用层 RAG（替代 KnowledgeRetrievalMiddleware）
        builder.middleware(applicationRAG.createMiddleware(def));
        // 提示注入检测（保留 GA 无的能力）
        builder.middleware(new ContentFilterMiddleware());
        // Skill（启用被注释的 GA SkillSystem）
        if (skillsConfig.isEnabled()) {
            builder.skillSystem(skillsConfig.build());
        }
        return builder.build();
    }
}
```

### 6.2 AgentGateway.java — 重写（RuntimeContext + streamEvents/interrupt）
```java
@Component
public class AgentGateway {
    private final AgentService agentService;
    public Flux<AgentEvent> callStream(String agent, String msg, String userId, String sessionId) {
        return agentService.getAgent(agent)
            .streamEvents(msg, RuntimeContext.builder().userId(userId).sessionId(sessionId).build());
    }
    public void interrupt(String agent, String userId, String sessionId) {
        agentService.getAgent(agent).interrupt(userId, sessionId);  // GA 原生
    }
}
```

### 6.3 ChatAppService.java — 事件层简化（保留业务）
- 上下文注入、Profile 路由**全部保留**；规则引擎预处理/后处理已移除（2026-07-11 删除 agent-rule-engine 模块）。
- 删除手写的 `SseProgressListenerAdapter` 适配，直接在 `toSseEvent(AgentEvent)` 中映射 GA `AgentEvent` 类型（TEXT_BLOCK_DELTA / THINKING_BLOCK_DELTA / TOOL_CALL_START / TOOL_RESULT_TEXT_DELTA / AGENT_RESULT / AGENT_END）。

### 6.4 新建 ApplicationRAG（应用层 RAG，替代废弃 rag.Knowledge）
```java
@Component
public class ApplicationRAG {
    private final KnowledgeBaseService kbService;
    private final EmbeddingService embeddingService;
    public MiddlewareBase createMiddleware(AgentDefinition def) {
        return new MiddlewareBase() {
            @Override public Mono<String> onSystemPrompt(Agent a, RuntimeContext ctx, String p) {
                return kbService.search(def.getKnowledgeBaseId(), p)
                    .map(docs -> inject(p, docs));   // 注入到 system prompt
            }
        };
    }
}
```

### 6.5 新建 PermissionConfig（替代 ToolGateMiddleware + ReasoningReviewMiddleware）
```java
public PermissionContextState build(AgentDefinition def) {
    PermissionContextState.Builder b = PermissionContextState.builder();
    b.mode(def.getRuntime().isRequireApproval() ? PermissionMode.DEFAULT : PermissionMode.BYPASS);
    def.getTools().forEach(t -> {
        if (t.isRequireApproval()) b.addAskRule(t.getName(), i -> true);
        if (t.isDenied())          b.addDenyRule(t.getName(), i -> true);
    });
    return b.build();
}
```

### 6.6 ContentFilterMiddleware — 保留并适配新接口
```java
public class ContentFilterMiddleware implements MiddlewareBase {
    @Override public Flux<AgentEvent> onAgent(Agent a, RuntimeContext ctx, AgentInput in,
            Function<AgentInput, Flux<AgentEvent>> next) {
        for (Msg m : in.getMessages())
            if (containsMaliciousPattern(m.getTextContent()))
                return Flux.error(new ContentBlockedException("检测到风险输入"));
        return next.apply(in);
    }
}
```

---

## 第七阶段（原第四阶段）：配置体系改造（Day 3-4）

### 7.1 AgentscopeAutoConfiguration.java — 扩展（MCP 真注册 + Skill + 权限）
```java
@Configuration
public class AgentscopeAutoConfiguration {

    // 1. GA 原生 ModelRegistry（公有模型）
    @Bean public ModelRegistry modelRegistry(AgentscopeExtensionProperties p) { /* 注册公有模型 */ }

    // 2. Toolkit：收集 Spring 容器中业务 Tool Bean 注册（HttpTool/DatabaseTool 等保留）
    @Bean public Toolkit toolkit(List<Tool> tools) {
        Toolkit tk = new Toolkit();
        tools.forEach(tk::registerTool);
        return tk;
    }

    // 3. MCP 真注册（已落地，见 AgentConfigurer.registerMcpServers）：
    //    不在此处用共享 toolkit Bean 注册，而是由 AgentConfigurer 在构建每个 Agent 的 Toolkit 时，
    //    按 Agent 配置（tools.mcpServers / toolsGroup.mcpServersToolsGroup）读取
    //    agentscope.core.mcp-servers，用框架原生 McpClientBuilder（SSE/STDIO/HTTP）实例化
    //    McpClientWrapper，并以服务器名建组后 toolkit.registration().mcpClient(wrapper).group(name).apply()。
    //    以服务器名建组可与 applyToolGroupActivation 的分组激活（mcpServersToolsGroup）无缝衔接；
    //    McpServerRegistrar 默认不建组（工具变未分组），故此处直接走 McpClientBuilder + group(name)。

    // 4. WorkspaceManager（替代自建）：按多租户路由构造 Path
    @Bean public WorkspaceManager workspaceManager(AgentscopeExtensionProperties p) {
        return new WorkspaceManager(Path.of(p.getWorkspace().getRootDir()));
    }

    // 5. PlanModeManager（启用 GA plan）
    @Bean @ConditionalOnProperty("agentscope.extensions.plan.enabled")
    public PlanModeManager planModeManager(WorkspaceManager wm, AgentscopeExtensionProperties p) {
        return new PlanModeManager(wm, p.getPlan().getPlanDir());
    }

    // 6. AgentStateStore / DistributedStore（Redis 后端保留）
    @Bean public DistributedStore distributedStore(UnifiedJedis jedis) {
        return RedisDistributedStore.fromJedis(jedis);
    }
}
```

### 7.2 YAML 配置示例（新增 MCP / Skill / 权限 / 重试降级 / plan）

> 注意：MCP 服务器配置在 `agentscope.core.mcp-servers`（由 `AgentscopeCoreProperties` 承载，prefix=`agentscope.core`），与 `agentscope.extensions` 分属两个前缀。mcp-servers 路径 B 改造后传输分别为：database/redis/milvus/knowledge=**SSE**，mcp-nutrition=**HTTP**，官方 puppeteer=**STDIO**。

```yaml
agentscope:
  core:
    mcp-servers:                       # 路径 B 改造后：database/redis/milvus/knowledge=SSE，mcp-nutrition=HTTP，puppeteer=STDIO（官方 MCP SDK 0.17.0）
      database:
        enabled: true
        type: sse
        url: http://localhost:40101/mcp/sse
        timeout: 60000
      redis:
        enabled: true
        type: sse
        url: http://localhost:40102/mcp/sse
      milvus:
        enabled: true
        type: sse
        url: http://localhost:40103/mcp/sse
      puppeteer:                       # 官方 STDIO 服务器（少数例外）
        enabled: true
        type: stdio
        command: npx
        args: ["-y", "@modelcontextprotocol/server-puppeteer"]
  extensions:
    workspace:
      root-dir: /data/workspaces
    plan:
      enabled: true
      read-only-tools: [read, search_documents, list_directory]
    permission:
      default-mode: bypass
      tools-require-approval: [execute_sql, deploy_service]
    skill:
      enabled: true
      skills-dir: /data/workspaces/skills
    models:
      deepseek-v3: { provider: openai, base-url: https://api.deepseek.com/v1, api-key: ${DEEPSEEK_API_KEY}, model-name: deepseek-chat }
    runtime:
      model-timeout-ms: 60000
      tool-timeout-ms: 30000
      max-retries: 2
      fallback-model: deepseek-v3
```

---

## 第八阶段（原第五阶段）：Gateway 模块——删-替为 GA Channel（Day 3）

删除自建 `agent-gateway` 模块（IM 渠道未上线、GA Channel 已原生覆盖 wecom/dingtalk/feishu）。删除步骤：

1. 根 pom 移除 `<module>agent-gateway</module>`
2. agent-app/pom.xml 移除 `agent-gateway` 依赖
3. 删除 `agent-gateway/` 目录（`META-INF/spring/...AutoConfiguration.imports` 随模块移除自动消失）
4. IM 接入改用 GA `agentscope-extensions-channel-*`：按需引入 wecom/dingtalk/feishu 扩展，配置 callback URL / AccessToken / 加解密 / Properties

**注意**：agent-gateway 的网关业务逻辑（斜杠命令、会话路由、限流、admin 认证、session 存储、tracing）随模块一并移除；GA Channel 仅提供 IM 传输层。后续如需这些能力，在 GA Channel 之上按需重建（限流/认证可借 GA 权限引擎与中间件）。

---

## 第九阶段（原第六阶段）：测试与验证（Day 4-5）

- 编译：`mvn clean compile -DskipTests` ✅ **已通过**（含场景检测链删除、forwardEvents 改造）
- 单测：`AgentConfigurer`（构建+权限上下文）、`ApplicationRAG`（注入）、`PermissionConfig`（ask/deny 规则）—— 🔴 当前 `agent-core/src/test` 无单测类，需补建
- 集成：企微/钉钉端到端；MCP 工具实际调用；plan mode 审批流；Skill 加载；子 Agent 调度；私有模型（百度/华为）调用

**2026-07-12 进度（B 部分）**：
- 已新增 `docker-compose.yml`（Redis 7 + MySQL 8），一键 `docker compose up -d` 拉起验证基建。
- 已新增 `docs/verification-checklist.md`，将第九阶段条目映射到当前 GA 架构并标注状态（✅/🟡/🔴）。
- 发现阻塞：`agent-integration-test` 下 5 个集成测试引用 GA 升级前已废弃的 `agent-business` 服务
  （`SmartConversationService`/`ProfessionExtractionService`/`NutritionPageAgentService`/`RecipeStreamService`/`SseNotificationProvider`）
  及不存在的主类 `yunxiAgentPlatformApplication`，**当前无法编译/运行**，需按新架构重写。
- 子 Agent 调度项：本次已将 `SubAgentConfig.forwardEvents` 默认改为 `true`（可经 `ExpertConfig.forwardEvents` 按专家关闭），
  运行态透出子 Agent 事件已具备，待基建就绪即可验证。

---

## 变更文件清单（修正后）

### 删除（~20 个类 + agent-gateway 模块，仅"屏蔽/重复"类）
```
agent/.../middleware/ToolGateMiddleware.java
agent/.../middleware/ReasoningReviewMiddleware.java
agent/.../middleware/TextToolCallParserMiddleware.java
knowledge/KnowledgeAutoConfiguration.java
knowledge/KnowledgeCreator.java
agent/.../middleware/KnowledgeRetrievalMiddleware.java
agent/plan/PlanPreCreator.java
agent/plan/PlanTemplateLoader.java
agent/plan/model/*.java
controller/PlanInteractionController.java
agent/workspace/AgentWorkspaceInitializer.java
agent/workspace/WorkspaceValidator.java
agent/workspace/WorkspaceAutoDiscoveryEngine.java
agent/workspace/DefaultSceneDetector.java
agent/workspace/SceneDetectionParser.java
agent/workspace/model/WorkspaceConfig.java
# 说明（2026-07-12 复核后彻底删除）：上述"场景检测链"经确认为死代码——
#   1) DefaultSceneDetector 从未被装配；
#   2) 其 AGENTS.md `# 场景检测` 规则在任何真实 AGENTS.md 中均不存在（仅出现在文档示例）；
#   3) 工作空间知识/技能/子Agent 的发现已由 GA 原生 WorkspaceContextMiddleware 承载；
#   4) 记忆场景路由由 MemorySceneRegistry + SceneDetectionService(registry/concept/builtin 三级) 覆盖。
#   故整条链删除；SceneDetectionService 移除 detectByWorkspaceRules 级、AgentConfigurer 移除 logWorkspaceDiscovery。
tool/ToolCircuitBreaker.java
shared/util/database/DatabaseToolkit.java 及其 SchemaInspector/RelationshipMapper/DataExplorer/SqlAnalyzer/SqlValidator 等（残留自建 MCP HTTP 客户端，死代码，删除）
gateway/SseProgressListenerAdapter.java
gateway/TaskProgressEvent.java
agent/AgentInterruptService.java
agent/CallOptions.java
agent-gateway/ 整个模块（根 pom <module> + agent-app 依赖 + 目录，IM 渠道改用 GA Channel）
```

### 保留（GA 未覆盖的合法价值，不删）
```
agent/.../middleware/ContentFilterMiddleware.java   (提示注入，适配)
agent/.../security/hitl/HumanToolRegistrar.java      (HITL)
agent/tool/impl/HttpTool.java / DatabaseTool.java / SessionSearchTool.java / NodeTool.java / CalculatorTool.java
agent/tool/ShellToolFactory.java
agent/agent/model/ModelFactory.java + Baidu/HuaweiModelProvider  (私有模型)
cache/RedisDistributedBackendConfig.java             (Redis 后端)
agent-spi/ 模块                                      (被 text2sql/core 消费)
记忆体系 (CompactionConfig/DistributedStore 使用)
```

### 重写（~5 个）
```
agent/AgentConfigurer.java
agent/gateway/AgentGateway.java
conversation/ChatAppService.java
config/AgentscopeAutoConfiguration.java
config/AgentscopeExtensionProperties.java
```

### 新增（实际落地情况）
```
knowledge/ApplicationRAG.java          ← 已新增（应用层 onSystemPrompt 注入检索上下文）
config/PermissionConfig.java           ← 已新增（映射 HITL 配置为 PermissionContextState）
config/SkillsConfig.java               ← 未单独建，技能配置内联进 AgentscopeCoreProperties.SkillProperties，
                                          由 AgentConfigurer.configureSkills 调用（功能已落地）
agent/AgentContext.java               ← 未单独建，RuntimeContext 工厂内联为 ChatAppService.buildRuntimeContext(...)
agent-config/.../application-agentscope.yml  ← 未单独建，MCP/Skill/权限/重试降级/plan 配置并入现有 agentscope.yml
```

---

## 关键风险与对策

- **R1 误删业务价值组件**（高概率，初稿已犯）：严格按"逐组件处置总表"执行，业务工具/ModelFactory/agent-spi/ContentFilter/HumanToolRegistrar/多租户路由**不删**。
- **R2 权限语义偏差**：GA `PermissionMode` 仅 5 种，无 `ALLOW/ASK/DENY`；"需审批"用 `DEFAULT`+`addAskRule`。映射旧 `ToolGateConfig` 黑白名单到 ask/deny 规则。
- **R3 多租户路由丢失**：删自建 workspace 初始化时，必须把 `users/{userId}/agent/{agentName}` 路由约定保留。**优先用 HarnessAgent 原生 `workspaceFactory(userId,sessionId)` / `workspaceFor(...)`**（GA 源码已确认存在，返回按 userId/sessionId 隔离的 WorkspaceManager 视图），其次才用 `WorkspaceManager(Path)` + `NamespaceFactory`，否则多租户隔离失效。
- **R4 plan mode 行为差异**：GA `PlanModeManager` 的 plan 文件生命周期/审批与自建 `PlanPreCreator` 不同，需联调确认前端交互兼容。
- **R5 RAG 迁移非阻塞**：`@Deprecated(forRemoval=true)` 仅警告不阻断编译；可先升版本跑通，再切 RAG。
- **R6 ChatAppService 丢失业务**：上下文注入/Profile 路由全部保留，规则引擎已移除（2026-07-11 删除模块），仅替换事件转换层。
- **R7 删除 agent-gateway 丢失网关业务逻辑**：GA Channel 仅 IM 传输层，不含斜杠命令/限流/admin 认证/session 存储。渠道未上线故可删；如后续需要这些能力，须在 GA Channel 之上重建（已记入第八阶段注意事项）。

---

## 执行时间线

| 阶段 | 内容 | 耗时 | 里程碑 |
|------|------|------|--------|
| Day 1 | pom 版本升级 + 编译 | 0.5d | 编译通过 |
| Day 1-2 | 删-替屏蔽/重复组件 + 编译 | 1d | 删除完成、业务工具/模型/spi 保留 |
| Day 2-3 | AgentConfigurer/AgentGateway/ChatAppService 重写 | 1.5d | 核心可用 |
| Day 3-4 | 配置改造：MCP 真注册 / Skill 启用 / 权限 / 重试降级 / plan / workspace | 1d | 能力补齐 |
| Day 4 | 删除 agent-gateway + 接 GA Channel | 0.5d | IM 接入就绪 |
| Day 4-5 | 测试 + 修复 | 1d | 上线就绪 |

---

## 实际执行偏差与修正（2026-07-11 全量复核）

本次按"第四~八阶段"逐条对照实际代码复核，结论是**代码阶段已实质完成、编译全绿**；发现 1 处需修正的配置缺陷与数处与计划描述的偏差，记录如下：

### 已修正：配置前缀不匹配（实质缺陷）
- **现象**：`AgentConfigurer` 通过 `coreProperties`（`AgentscopeCoreProperties`，前缀 `agentscope.core`）读取 `getPlan()`/`getSkill()`/`getResilience()`；但 `agentscope.yml` 原把这些段写在 `agentscope.extensions` 下。而 `AgentscopeExtensionProperties`（前缀 `agentscope.extensions`）**根本没有 plan/resilience 字段**，其 skill 字段还叫 `skills`（单复数亦不符）。结果这三个 GA 能力**永远取不到 YAML 值**，只能落到 Java 默认值（plan off、skill off、resilience 全 null）→ Plan/Skill/韧性**无法通过 YAML 开启**。
- **修正**：将 `plan`/`skill`/`resilience` 三段从 `agentscope.extensions` 移到 `agentscope.core`（与 `mcp-servers` 位置一致），注释标注"原误置导致无法绑定"。`AgentscopeExtensionProperties.skills` 字段现为死字段（可后续清理，不影响运行）。
- **验证**：`mvn clean compile -DskipTests` 仍 BUILD SUCCESS；YAML 结构已人工核对（无重复键）。

### 偏差（计划描述 vs 实际实现，均属合理适配，非缺陷）

1. **ApplicationRAG 接入点不同**：计划 6.4 描述为在 `AgentConfigurer` 注入 `applicationRAG.createMiddleware(def)`；实际接在 `TempAgentFactory.createMiddleware(ragMode, userId)`（按需临时 Agent 的逐请求注入）。原因：`ApplicationRAG.createMiddleware` 需要 `userId`，而 `AgentConfigurer` 在启动期构建 Agent 时尚无 userId，故改为运行时（TempAgentFactory/ChatAppService 路径）注入。功能等价，主 Agent 的检索增强改由工作空间 `KNOWLEDGE.md` 经 `WorkspaceContextMiddleware` 自动注入承载。

2. **SubAgent `forwardEvents` 仍为 `false`**：计划附录与 6.1 示例建议增强为 `true` 以透出子 Agent 事件；实际 `AgentConfigurer.createSupervisorAgent` 仍用 `forwardEvents(false)`（L488）。属行为取舍（避免事件噪声），非阻塞，留待后续按需开启。

3. **`AgentscopeAutoConfiguration` 退化为空壳**：计划 7.1 描述在该类声明 `toolkit`/`workspaceManager`/`planModeManager`/`distributedStore` 四个 Bean；实际这些构造已内联进 `AgentConfigurer`（按 Agent 局部 `new Toolkit()`/`new WorkspaceManager(...)`/`new PlanModeManager(...)`，分布式后端由 `RedisDistributedBackendConfig` 经 `setDistributedBackend` 注入）。该类仅保留 `@ConditionalOnProperty` + 注释，MCP 真注册逻辑已整体迁移到 `AgentConfigurer.registerMcpServers`。功能等价，结构更贴合"按 Agent 构造"的语义。

4. **场景检测链已彻底删除（2026-07-12 二次复核修正）**：初判"保留"，二次深挖后确认为**死代码**并删除——`DefaultSceneDetector` 从未装配；其 AGENTS.md `# 场景检测` 规则在任何真实 AGENTS.md 中都不存在（仅文档示例）；工作空间知识/技能/子Agent 发现已由 GA 原生 `WorkspaceContextMiddleware` 承载；记忆场景路由由 `MemorySceneRegistry` + `SceneDetectionService`（registry/concept/builtin 三级）覆盖。故删除 `WorkspaceAutoDiscoveryEngine`/`DefaultSceneDetector`/`SceneDetectionParser`/`WorkspaceConfig`/`SceneDetectionRule` 全链，并裁剪 `SceneDetectionService.detectByWorkspaceRules` 级与 `AgentConfigurer.logWorkspaceDiscovery`。

### 已修正：清理 `agentscope.extensions.skills` 死配置（2026-07-12）
- 配置前缀修复后，GA 技能统一走 `agentscope.core.skill`（`SkillProperties`）。`AgentscopeExtensionProperties.skills`（`SkillsConfig`，仅 `enabled`）已成死字段，仅被 `ConfigManagementController` 用于配置概览上报。
- 修正：删除 `AgentscopeExtensionProperties.skills` 字段与 `SkillsConfig` 内部类；`ConfigManagementController` 的 `/overview`、`/skills` 端点改为上报 GA 原生 `coreProperties.getSkill()`，语义与真实生效配置一致。
- 同时清理 `AgentConfigurer` 未使用的 `AgentStateStore` import。

### 结论修订
经本轮（2026-07-12）复核，原"偏差 4（场景检测链保留）"已消除为**删除**；yunxi 侧不再保留 GA 已覆盖的场景/工作空间发现能力，符合"以底层框架为主、消除特殊存在"的升级目标。`mvn clean compile -DskipTests` 全量 BUILD SUCCESS。

### 结论
除上表偏差外，计划所列"删除（屏蔽/重复）类""启用 GA 原生""保留价值组件"均已落地。第九阶段（测试与验证）：编译已 ✅ 通过；基建 harness（`docker-compose.yml`）+ 验证清单（`docs/verification-checklist.md`）已备齐；但 `agent-integration-test` 下 5 个集成测试因引用 GA 升级前已废弃的 `agent-business` 服务而无法编译，需按新架构重写后方可执行（本机无 Redis/DB/LLM 亦为限制之一）。详见 `docs/verification-checklist.md`。

## 附录：三类问题 → 消除方案（修正）

| 问题类型 | 组件 | 根因 | 消除方案 |
|---------|------|------|---------|
| 屏蔽底层能力 | ToolGateMiddleware | 自建门控绕过 GA 权限引擎 | 删除，改用 `permissionContext` + `PermissionMode.DEFAULT` + `addAskRule` |
| 屏蔽底层能力 | ReasoningReviewMiddleware | 伪 HITL（只 log 不 suspend） | 删除，用 GA 权限真审批 |
| 屏蔽底层能力 | KnowledgeRetrievalMiddleware | 用废弃 `rag.Knowledge` | 删除，应用层 `onSystemPrompt` 注入（ApplicationRAG） |
| 屏蔽底层能力 | AgentConfigurer 遗漏配置 | Builder 使用不全 | 全面使用 GA 新 API（重试/降级/超时/权限） |
| 重复开发 | Plan 系统 | 与 GA PlanModeMiddleware 重叠 | 删除自建，启用 GA plan mode |
| 重复开发 | Workspace 系统 | 与 GA WorkspaceManager 重叠且更弱 | 删除自建，用 GA WorkspaceManager + WorkspaceContextMiddleware（保留多租户路由） |
| 重复开发 | ToolCircuitBreaker | 与 `toolExecutionConfig` 重叠 | 删除，用 GA 执行配置 |
| 重复开发 | SseProgressListenerAdapter | 与 GA AgentEvent v2 重叠 | 删除，直接映射 AgentEvent→SSE |
| 重复开发 | AgentInterruptService | 与 GA `interrupt()` 重叠 | 删除，用 GA 原生 |
| 重复开发 | CallOptions | 与 RuntimeContext 重叠 | 删除，用 RuntimeContext |
| 功能缺失 | MCP | 配置加载未注册工具 | 用 `McpClientBuilder`/`McpClientWrapper`（`Toolkit.registration().mcpClient(wrapper).group(name).apply()`）真注册，见 `AgentConfigurer.registerMcpServers`（注：`McpServerRegistrar` 默认不建组，无法满足按服务器名分组激活，故未采用） |
| 功能缺失 | Skill | `skillSystem(...)` 被注释 | 启用 GA SkillSystem + 技能目录 |
| 功能缺失 | SubAgent | forwardEvents=false | 增强为 true 透出事件 |
| 功能缺失 | 模型重试/降级/超时 | 未使用 | 启用 `maxRetries`/`fallbackModel`/`stopOnReject`/`modelExecutionConfig`/`toolExecutionConfig` |
| 功能缺失 | harness 中间件链 | 未充分利用 | 随 workspace/plan/skill 自动装载沙箱/追踪/记忆整合等（注：GA 2.0 **有**内置 `TaskReminderMiddleware`，位于 core 模块 `io.agentscope.core.middleware`，可启用） |
| 保留-价值 | HttpTool/DatabaseTool/业务工具 | GA 不提供 | 保留 |
| 保留-价值 | ModelFactory(百度/华为) | GA 未覆盖私有模型 | 保留 |
| 保留-价值 | ContentFilter(提示注入)/HumanToolRegistrar(HITL) | GA 无 | 保留 |
| 保留-价值 | agent-spi / Redis 后端 / 多租户路由 / 记忆体系 | 业务独有或被消费 | 保留 |
