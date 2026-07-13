# AgentScope 2.0 RC3 → 2.0 GA 升级分析报告

> 生成日期: 2026-07-10（2026-07-11 对齐 v3 实施方案修订；2026-07-11 对齐 mcp-servers 路径 B 改造：MCP 传输统一为 SSE、自建 MCP 客户端已删除）
> 当前版本: AgentScope 2.0.0-RC3
> 目标版本: AgentScope 2.0.0 GA
> 分析范围: yunxi-agent-platform 全部 8 个模块

---

> **核心结论（v3 修订，已对照 GA 源码 `d:\work\code\agentscope-java-2.0GA` 逐行核实）**：
> RC3→GA 是工程化能力的大跃迁，本报告**大力拥抱框架原生能力**。yunxi 的三类问题处置原则为：**屏蔽的——解锁；重复的——替换；缺失的——补齐；独有的——保留。** 凡 GA 已提供的原生能力，删除 yunxi 自建重复实现；GA 未覆盖的业务价值组件（私有模型适配、提示注入检测、HITL、业务工具、Redis 后端、多租户路由、agent-spi）一律保留。本报告所有「建议」均为**确定性处置**，不再使用「评估是否替换」的含糊表述。逐组件结论见 `docs/agentscope-2.0GA-upgrade-plan.md` 的「逐组件处置总表」与「〇、GA 源码核实清单」，本文档为其能力维度分析。

---

## 一、升级总览

AgentScope 从 RC3 到 GA 是一次重大升级，底层框架新增了大量工程化能力。yunxi 平台目前存在三类典型问题：

| 问题类型 | 说明 | 涉及模块 | 总表处置 |
|---------|------|---------|---------|
| **屏蔽底层能力** | 自定义实现覆盖了框架原生能力，导致框架升级后无法自动受益 | Middleware（权限/RAG）、AgentConfigurer 遗漏配置 | 解锁 GA：删自建、改用原生 |
| **重复开发** | 实现了框架已提供的功能，维护成本高且可能与框架行为不一致 | Workspace、Plan、ToolCircuitBreaker、SSE 适配、中断/选项、**agent-gateway（IM Channel）** | 删-替：用 GA 原生替换 |
| **功能缺失** | 框架 GA 新增的能力在平台完全没有体现 | MCP、Event v2、Skill、Subagent、模型重试/降级/超时、harness 中间件链 | 补齐：启用 GA 原生 |
| （注）独有价值 | GA 未覆盖的合法业务价值 | 业务工具、ModelFactory(私有模型)、ContentFilter、HumanToolRegistrar、agent-spi、Redis 后端、多租户路由、记忆体系 | 保留：不删 |

---

## 二、版本依赖变更

### 2.1 POM 修改

```xml
<!-- 修改前 (RC3) -->
<agentscope.version>2.0.0-RC3</agentscope.version>

<!-- 修改后 (GA) -->
<agentscope.version>2.0.0</agentscope.version>
```

### 2.2 GA 新增可用依赖模块

yunxi 平台目前仅依赖 `agentscope-core` 和少数扩展。GA 新增以下可直接使用的模块：

| 模块 | artifactId | 用途 | yunxi 处置 |
|------|-----------|------|-----------|
| **Harness 运行时** | `agentscope-harness` | Workspace、子Agent、Memory、Plan、Skill | **启用**：替换自建 Workspace/Plan |
| **事件体系 v2** | 已在 core 中 | `AgentEvent` 精确事件追踪 | **启用**：替代 SseProgressListenerAdapter |
| **Channel 扩展** | `agentscope-extensions-channel-{wecom,dingtalk,feishu,github,gitlab,common}` | 企业微信/钉钉/飞书/GitHub/GitLab 原生 Channel+Mapper+Callback | **启用 GA Channel，删除自建 agent-gateway**（渠道均未上线，GA 已全覆盖） |
| **MCP Server** | 已在 core+extensions 中 | STDIO/SSE/HTTP 三种传输 | **启用**：真正注册 MCP 工具 |
| **RAG 扩展** | `agentscope-extensions-rag-*` | Dify、Haystack、RAGFlow 集成 | 不阻塞：`rag` 包已废弃，改用应用层 RAG |
| **Memory 扩展** | `agentscope-extensions-mem-*` | Mem0、百炼、ReMe | 可选增强（记忆已用 GA 原生） |
| **沙箱扩展** | `agentscope-extensions-sandbox-*` | Docker、K8s、e2b、Daytona | 按需启用（随 harness 中间件） |
| **调度器** | `agentscope-extensions-scheduler-*` | Quartz、XXL-Job | 按需 |
| **Spring Boot Starters** | `agentscope-spring-boot-starters` | 一键集成各功能模块 | 仅用于公有模型自动配置，**不替代**私有 ModelFactory |

---

## 三、核心 API 变更：HarnessAgent.Builder

### 3.1 GA 新增但 yunxi 未使用的配置项（属「功能缺失」，应补齐）

yunxi **已使用** `HarnessAgent.builder()`（见 `AgentConfigurer` L302/L416），但以下 GA 新增配置项仍未使用，应补齐（注意：非切换入口，而是补齐 Builder 未用配置）：

| 配置项 | 作用 | yunxi 现状 | 处置 |
|--------|------|-----------|------|
| `modelExecutionConfig(execConfig)` | 独立的模型调用超时/重试/限流 | **未使用** | 启用：补齐模型细粒度控制 |
| `toolExecutionConfig(execConfig)` | 独立的工具执行超时/重试/限流 | **未使用** | 启用：替代自建 ToolCircuitBreaker |
| `permissionContext(ctx)` | 原生权限引擎 (DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK) | **未使用**（自建 ToolGateMiddleware 重复） | 启用：删 ToolGateMiddleware，改用 GA 权限 |
| `enablePendingToolRecovery(true)` | 工具调用中断后自动恢复 | **未使用** | 启用 |
| `maxRetries(n)` / `fallbackModel(model)` / `stopOnReject(boolean)`（harness 等价，非 ReActAgent 的 `flat*` 系列） | 模型调用失败重试与降级 | **未使用** | 启用：补齐可靠性 |
| `stateStore(store)` | 多会话状态持久化 | **未使用**（自建 Redis/Session 管理，合法保留） | 可选：记忆已用 GA 原生 DistributedStore |
| `hooks(List<Hook>)` | Hook 生命周期拦截 | 无实际使用 | 按需 |
| `structuredOutput()` | 结构化输出（类型安全） | **未使用** | 启用：替换 JSON 手动解析 |
| `workspace(Path)` / `compaction(...)` / `skillSystem(...)` / `middleware(...)` | harness 专属能力 | 仅 compaction 在用 | 启用 workspace/plan/skill/middleware 链 |

### 3.2 GA 新增的调用 API

```java
// GA 新增：带 RuntimeContext 的精确控制
agent.call(messages, runtimeContext);
agent.call(messages, OutputType.class, runtimeContext);  // 结构化输出
agent.call(messages, jsonSchema, runtimeContext);        // JSON Schema

// GA 新增：精确会话中断（替代自建 AgentInterruptService）
agent.interrupt(runtimeContext);
agent.interrupt(userId, sessionId);

// GA 新增：细粒度流式事件（替代 SseProgressListenerAdapter）
agent.streamEvents(input, runtimeContext, streamOptions); // 可选择事件类型
```

yunxi 平台目前使用基础的 `call(msg)` / `stream()` 接口，应改用 `RuntimeContext`（多租户隔离）与 `streamEvents`（AgentEvent 事件流）。

### 3.3 处置建议（确定性）

1. 在 `AgentConfigurer` 中引入 `modelExecutionConfig` / `toolExecutionConfig`，并**删除**自建 `ToolCircuitBreaker`。
2. **删除** `ToolGateMiddleware`，改用 `permissionContext` + `PermissionMode.DEFAULT` + `addAskRule`。
3. 利用 `structuredOutput()` 替换现有的 JSON 手动解析逻辑。
4. 在 `ConversationService` 中利用 `RuntimeContext.builder().userId().sessionId().build()` 传递用户/会话标识，并**删除**自建 `CallOptions`。

---

## 四、Middleware 体系：最大差距

### 4.1 GA Harness 中间件全景（15+ 个，属「功能缺失」→ 启用）

| Middleware | 功能 | yunxi 现状 | 总表处置 |
|-----------|------|-----------|---------|
| `PlanModeMiddleware` | 计划模式（只读阶段+执行阶段） | 自建 plan/ 包（不同模型） | 删-替：启 GA PlanModeMiddleware |
| `CompactionMiddleware` | 自动上下文压缩 | 已通过 Builder 配置使用 | 保留并增强 |
| `MemoryFlushMiddleware` | 长会话记忆落盘 | 部分（MemoryFlushHook） | 保留并增强 |
| `MemoryMaintenanceMiddleware` | 记忆去重/过期清理 | 缺失 | 保留：记忆已用 GA 原生，可增强 |
| `WorkspaceContextMiddleware` | 工作空间上下文注入 | 自建 WorkspaceAutoDiscoveryEngine | 删-替：启用 GA WorkspaceManager |
| `HarnessSkillMiddleware` | 技能自动匹配与注入 | `skillSystem(...)` 被注释 | 启用 GA SkillSystem |
| `SubagentsMiddleware` | 子 Agent 自动调度 | 已用 `subAgent(...)` 但 `forwardEvents(false)` | 增强：forwardEvents(true) |
| `DynamicSubagentsMiddleware` | 运行时动态创建子 Agent | 缺失 | 按需 |
| `AsyncToolMiddleware` | 工具异步执行 | 缺失 | 按需 |
| `AtPathExpansionMiddleware` | @路径自动展开 | 缺失 | 随 workspace 自动装载 |
| `InboxMiddleware` | Agent 间消息传递 | 缺失 | 按需 |
| `ToolResultEvictionMiddleware` | 工具结果过期清理 | 缺失 | 随 harness 自动装载 |
| `SandboxLifecycleMiddleware` | 沙箱生命周期管理 | 无沙箱 | 按需启用 |
| `AgentTraceMiddleware` / `OtelTracingMiddleware` | 调用链路追踪 | 缺失 | 启用 |
| `TaskReminderMiddleware` | 任务提醒 | **注：GA 2.0 无此内置中间件，已从 GA 中间件清单移除** | — |
| `SkillCuratorMiddleware` | 技能自学习/进化 | 缺失 | 按需 |

### 4.2 yunxi 自建中间件与 GA 的冲突（5 个，3 删 2 留）

| yunxi 中间件 | GA 对应能力 | 冲突分析 | 总表处置 |
|-------------|-----------|---------|---------|
| `KnowledgeRetrievalMiddleware` | （无 GA 等价 Hook） | 使用已废弃 `Knowledge` API；`rag` 包（`GenericRAGHook`/`Knowledge`）整体 `@Deprecated(forRemoval=true)` | **删-替**：迁应用层 `MiddlewareBase.onSystemPrompt` 注入检索结果 |
| `ToolGateMiddleware` | `PermissionEngine` + `permissionContext` | 重复实现，绕过 GA 原生权限 | **删除-替**：改用 GA 权限引擎（DEFAULT+addAskRule） |
| `ReasoningReviewMiddleware` | `PermissionMode.DEFAULT` + `addAskRule` | 重复实现，且仅打日志不拦截，无真实门控价值 | **删除**：用 GA 权限真审批替代 |
| `TextToolCallParserMiddleware` | 框架内部已处理 | 冗余空壳：`onReasoning` 纯透传，框架已解析文本工具调用 | **删除**：无作用 |
| `ContentFilterMiddleware` | 无直接对应 | GA 无提示注入检测能力 | **保留-价值**：平台独有安全价值，改实现 `MiddlewareBase.onAgent` 复用检测逻辑 |

> 另有 `HumanToolRegistrar`（HITL）：GA 无对应，包装 GA `ToolSuspendException`，属平台独有可插拔人机协作价值，**保留**。

### 4.3 处置建议（确定性）

1. **删除并替换 KnowledgeRetrievalMiddleware**：`rag` 包整体废弃，不可再用 `GenericRAGHook`/`Knowledge`。新建 `ApplicationRAG`（应用层 `MiddlewareBase.onSystemPrompt` 注入检索上下文）。
2. **删除 ToolGateMiddleware**：改用 `permissionContext` + `PermissionMode` + `addAskRule`。
3. **删除 ReasoningReviewMiddleware / TextToolCallParserMiddleware**：前者用 GA 权限真审批替代，后者纯冗余。
4. **启用 harness 中间件链**：`workspace(...)`→`WorkspaceContextMiddleware`、`compaction(...)`（已用）、`PlanModeMiddleware`、`SkillSystem`、`AgentTraceMiddleware`、沙箱等随 Builder 标志或显式装载。
5. **增强 SubAgent**：`forwardEvents(true)` 透出子 Agent 事件。
6. **保留 ContentFilterMiddleware / HumanToolRegistrar**：适配新接口即可。

---

## 五、工具系统

### 5.1 GA 工具体系

```
Tool (工具抽象接口)
  ├── FunctionTool / McpTool / ...
  ├── Toolkit (registerTool / createToolGroup / registration().subAgent(...))
  └── toolExecutionConfig (超时+重试，替代自建熔断)
```

> GA 不自带业务工具：仅 `FileToolUtils`（辅助类）与 coding-agent 示例的 `WebSearchTool`；**无** `HttpTool`/`DatabaseTool`/`ShellTool`/`CalculatorTool` 等。

### 5.2 yunxi 现状

- 6 个业务工具类：`CalculatorTool`、`DatabaseTool`、`HttpTool`、`NodeTool`、`SessionSearchTool`、`ShellToolFactory` —— **均为业务工具，GA 不提供等价实现，属平台独有，保留不删**。
  （注：`DatabaseToolkit` 及其 `SchemaInspector`/`RelationshipMapper`/`DataExplorer`/`SqlAnalyzer`/`SqlValidator` 曾内嵌自建 JSON-RPC-over-HTTP 的 `callMcpTool` 客户端，属于**残留的自建 MCP 客户端**，与“完全复用框架”相悖；其功能与 database MCP 服务器重叠且从未被调用（死代码），已于 2026-07-11 随改造一并删除，不属“框架重复”范畴。）
- `ToolCircuitBreaker` 是自定义熔断器，与 GA `toolExecutionConfig`（超时+重试）**重复**，应删除。
- 无 MCP 工具集成（仅读取配置，未注册）。

### 5.3 处置建议（确定性）

1. **删除 ToolCircuitBreaker**：用 GA `toolExecutionConfig` 超时/重试配置替代。
2. **保留** HttpTool/DatabaseTool/SessionSearchTool/NodeTool/CalculatorTool/ShellToolFactory —— 业务工具，非框架重复，不删。（`DatabaseToolkit` 因内置自建 MCP HTTP 客户端且为死代码，已删除。）
3. **工具类保留并接入 GA Toolkit**：将业务 Tool Bean 注册到 `Toolkit.registerTool`（见实施方案 7.1）。
4. **启用 MCP 工具注册**：见第六节。

---

## 六、MCP (Model Context Protocol) 集成

### 6.1 GA 的 MCP 支持

- 三种传输: STDIO、SSE、HTTP
- `McpTool` 类封装 MCP 工具，`McpServerRegistrar` / `McpClientManager` 负责实例化
- MCP Server 列表通过配置加载

### 6.2 yunxi 现状（2026-07-11 更新：mcp-servers 路径 B 改造已完成）

- **服务端（yunxi-mcp-servers）已完成路径 B 改造**：删除全部自建 JSON-RPC 协议层，改用官方 MCP SDK 0.17.0（`McpSyncServer` + `HttpServletSseServerTransportProvider`），各服务器以 **SSE** 端点对外暴露：`database` `http://localhost:40101/mcp/sse`、`redis` `40102`、`milvus` `40103`、`knowledge` `40107`，另有 `mcp-nutrition`（HTTP `40602`）与官方 `puppeteer`（STDIO）。详见 `docs/mcp-servers-integration-plan.md`。
- **客户端（yunxi-agent-platform）自建 MCP 客户端已删除**：`McpClient`/`McpClientConfig`/`McpClientService`/`McpQueryService`/`McpToolController`/`McpClientException`/`McpDatabaseClient` 共 7 个文件删除；数据同步链路（`SyncEngine`/`SchoolDishSyncRunner`/`BaseSyncService`/`MultiDatabaseQueryService`）从 MCP 代理改为直连 JDBC（新增 `ExternalDbQueryService`）。
- **Agent 侧 MCP 真注册已完成（P1-5，2026-07-11）**：`AgentscopeAutoConfiguration.mcpServerBeans()` 死配置已删除；`AgentConfigurer.registerMcpServers()` 现按 Agent 配置（`tools.mcpServers` / `toolsGroup.mcpServersToolsGroup`）读取 `agentscope.core.mcp-servers`，用框架原生 `McpClientBuilder`（SSE/STDIO/HTTP）实例化 `McpClientWrapper`，并以服务器名建组后注册进 `Toolkit`，与原 `applyToolGroupActivation` 分组激活无缝衔接。单个服务器注册失败仅记录日志，不影响其余服务器与 Agent 启动。

### 6.3 处置建议（确定性）

1. **GA MCP 真注册已落地（见 `AgentConfigurer.registerMcpServers`）**：读取 `agentscope.core.mcp-servers`，用框架原生 `McpClientBuilder`（SSE/STDIO/HTTP）实例化 `McpClientWrapper` 并以服务器名建组注册进 `Toolkit`；`McpServerRegistrar` 默认不建组（工具变未分组，无法满足本平台按服务器名分组激活的语义），故直接走 `McpClientBuilder` + `.group(name)`。STDIO 仅用于官方 puppeteer 等少量服务器。
2. **服务端无需在 agent-platform 内自建**：数据库/Redis/Milvus/知识库/营养等富能力已由 yunxi-mcp-servers 以官方 SDK 暴露；如需将 Text2SQL 对外，作为新增 mcp-servers 模块补充，而非在 agent-platform 内实现 MCP Server 端。（注：规则引擎模块 agent-rule-engine 已于 2026-07-11 删除，未来需要时再重建）
3. 利用 MCP 引入外部能力（外部搜索、代码执行等）。

---

## 七、事件系统

### 7.1 GA v2 事件体系

```java
AgentEvent (基类)
  ├── AgentStartEvent / AgentEndEvent / AgentResultEvent
  ├── TextBlockDeltaEvent / TextBlockStartEvent / TextBlockEndEvent
  ├── ThinkingBlockDeltaEvent
  ├── ToolCallStartEvent / ToolCallEndEvent / ToolCallArgsEvent
  └── ...
```

特点：支持流式事件过滤 (`StreamOptions`)；精确到文本块增量和边界事件；与 Reasoning 分离。

### 7.2 yunxi 现状

完整的自研 SSE 体系：
- `SseEmitterManager` - 连接管理（**传输层，框架不负责，保留**）
- `SseProgressListenerAdapter` - 进度事件适配（**与 GA v2 重叠，删除**）
- `TaskProgressEvent` - 五阶段事件（**与 GA v2 重叠，删除**）
- `ProgressListener` - 回调接口（**删除**）

### 7.3 差距分析（确定性）

yunxi 的 SSE 体系存在以下问题，应**删除**重叠部分而非「评估」：
- **事件粒度粗**：只有阶段级事件，无法观察文本块增量和工具调用细粒度过程。
- **与框架脱节**：`SseProgressListenerAdapter` 是自实现的事件监听，与 GA v2 `AgentEvent` 体系重叠，**属重复开发**。
- **处置**：`ChatAppService` 直接映射 GA `AgentEvent`→SSE，`SseEmitterManager`（传输层）保留。

### 7.4 处置建议（确定性）

1. **删除 SseProgressListenerAdapter / TaskProgressEvent / ProgressListener**：`ChatAppService` 直接映射 GA `AgentEvent`（`TEXT_BLOCK_DELTA` / `THINKING_BLOCK_DELTA` / `TOOL_CALL_START` / `TOOL_RESULT_TEXT_DELTA` / `AGENT_RESULT` / `AGENT_END`）→ SSE。
2. 利用 `streamEvents(input, ctx, options)` 替代手动适配。
3. **保留 SseEmitterManager** 作为 SSE 连接管理（传输层，框架不负责）。

---

## 八、Memory 系统

### 8.1 GA Memory 能力

```
agentscope-harness:
  ├── MemoryFlushMiddleware     - 内存刷新到持久存储
  ├── MemoryMaintenanceMiddleware - 过期/冗余记忆清理
  ├── CompactionMiddleware      - 上下文自动压缩
  └── 多后端支持: Mem0 / 百炼 / ReMe

agentscope-core:
  └── stateStore / AgentStateStore - Session级状态管理
```

### 8.2 yunxi 现状（关键纠正）

- 自建会话管理：`ConversationDomainService` + Redis 存储（**合法业务价值，保留**）。
- `ConversationIdSSEConnectionManager` 管理 SSE 连接。
- **已正确使用 GA 原生**（源码核实 2026-07-11）：`CompactionConfig`（`AgentService`/`TempAgentFactory`/`AgentConfigurer` 三处 import + `buildCompactionConfig()`）、`DistributedStore`（`AgentConfigurer.setDistributedBackend`）、`RedisDistributedStore.fromJedis(jedis)`（`RedisDistributedBackendConfig`）、`MemoryFlushHook`（`ChatAppService` 注释引用）已在用，**不存在重复**。⇒ **Memory 不属于"重复开发"，归"已用 GA 原生 / 保留并增强"**（此处与"Memory=重复开发"的直觉归类相反，以源码为准）。
- 缺少：长期记忆落盘增强（MemoryConsolidator）、记忆去重维护（可增强，非必须）。

### 8.3 处置建议（确定性）

1. **保留** yunxi 记忆体系：已正确使用 GA 原生 `CompactionConfig`/`DistributedStore`/`RedisDistributedStore`/`MemoryFlushHook`，**不存在重复**，Redis 后端属平台独有价值。
2. **增强**：可选启用 `MemoryConsolidator` 等 harness 记忆整合能力。
3. **不替换** Redis 后端：GA `DistributedStore` 需后端实现，yunxi 的 Redis 后端是合法 value-add。

---

## 九、Gateway / Channel

### 9.1 GA Channel 扩展

GA 提供完整的 Channel 扩展体系（`agentscope-extensions-channel-*`）：企业微信、钉钉、飞书、GitHub、GitLab，每渠道含 `Channel` + `InboundMapper` + `OutboundClient` + `CallbackController` + `AccessTokenProvider` + `Crypto` + `Properties` + `Registry`。

### 9.2 yunxi 现状

`agent-gateway` 模块完全自建了 IM 接入（WeCom/DingTalk/Feishu/WebApi Channel + `GatewayDispatcher` + `CoreAgentClient.chatStream()`）。模块经根 pom `<module>` 声明 + agent-app 依赖 + `AutoConfiguration.imports` 接入构建，但 IM 渠道未在部署中激活（用户确认尚未使用）。除 IM 传输外，还含 `GatewayDispatcher`（斜杠命令/路由）、`GatewaySessionManager`、`SessionStore`(Sqlite/InMemory)、限流(`GatewayRateLimitFilter`/`RateLimitRule`/`ResourceLimitRule`)、admin 认证(`GatewayAdminAuthFilter`)、tracing 等网关业务逻辑。

### 9.3 分析（确定性）

GA Channel 扩展已原生覆盖 yunxi 全部三个 IM 渠道（`agentscope-extensions-channel-wecom`/`-dingtalk`/`-feishu`，含 Channel+InboundMapper+OutboundClient+CallbackController+AccessTokenProvider+Crypto），外加 GitHub/GitLab。yunxi agent-gateway 渠道未上线、无外部模块引用其业务类（`GatewayDispatcher`/`GatewayAutoConfiguration` 仅模块内部引用）。

- **处置（删-替）**：**删除自建 `agent-gateway` 模块，IM 接入全面采用 GA Channel 扩展**。
- **删除范围**：根 pom `<module>agent-gateway</module>` + agent-app/pom.xml 的 `agent-gateway` 依赖 + `agent-gateway/` 目录（`AutoConfiguration.imports` 随模块移除自动消失）。
- **注意（tradeoff）**：agent-gateway 的网关业务逻辑（斜杠命令、会话路由、限流、admin 认证、session 存储、tracing）随模块一并移除；GA Channel 仅提供 IM 传输层，不含这些。因渠道未上线，可接受；后续如需，在 GA Channel 之上按需重建（限流/认证可借 GA 权限引擎与中间件）。

---

## 十、Workspace / Harness

### 10.1 GA Harness 能力

```
agentscope-harness:
  ├── Workspace 管理（文件、目录、环境变量，WorkspaceManager + WorkspaceContextMiddleware）
  ├── Plan Mode（PlanModeMiddleware + PlanModeManager，只读/执行两阶段）
  ├── Skill Box（技能仓库 + 自动匹配，skillSystem(...)）
  ├── Sub Agent（SubAgentConfig + registration().subAgent(...)）
  ├── Sandbox（Docker/K8s/e2b/Daytona）
  └── 技能自进化 (SkillCurator)
```

### 10.2 yunxi 现状（重复开发，应删-替）

```
agent-core/.../workspace/:   ← 与 GA WorkspaceManager 重叠且更弱
  ├── AgentWorkspaceInitializer
  ├── UserWorkspaceService（仅多租户路由逻辑，保留）
  ├── WorkspaceAutoDiscoveryEngine
  ├── WorkspaceValidator
  └── model/ (WorkspaceConfig, SceneDetectionRule)

agent-core/.../plan/:        ← 与 GA PlanModeMiddleware 重叠
  ├── PlanPreCreator
  ├── PlanTemplateLoader
  └── model/ (Plan, SubTask, PlanTemplate)
```

### 10.3 差距分析

yunxi 的 Workspace 实现是简化版，与 GA Harness 差距：无沙箱隔离、无技能系统、无动态子 Agent 创建、Plan 模式实现路径不同（与 GA `PlanModeMiddleware` 冲突）。

### 10.4 处置建议（确定性）

1. **删-替 Workspace**：删除 `AgentWorkspaceInitializer`/`WorkspaceAutoDiscoveryEngine`/`WorkspaceValidator`/`WorkspaceConfig`/`SceneDetectionRule`，改用 GA `WorkspaceManager(Path)` + `WorkspaceContextMiddleware`。
2. **保留多租户路由**：`UserWorkspaceService` 中 `users/{userId}/agent/{agentName}` 路由约定，迁移为 `WorkspaceManager` 的 Path / `NamespaceFactory` 构造参数（**不可丢失，否则多租户隔离失效**）。
3. **删-替 Plan**：删除 `PlanPreCreator`/`PlanTemplateLoader`/plan model/`PlanInteractionController`，启用 GA `PlanModeMiddleware(new PlanModeManager(wm, planDir), resolver)`。

---

## 十一、Spring Boot Starter 体系

GA 2.0 提供完整 Spring Boot Starter 系列：

| Starter | 功能 | yunxi 处置 |
|---------|------|-----------|
| `agentscope-spring-boot-starter-openai` 等公有模型 | OpenAI/通义/Anthropic/Gemini 自动配置 | 可选用于公有模型自动配置 |
| `agentscope-spring-boot-starter-a2a` | A2A 协议 | yunxi 自建 `A2AClient/A2AServer`，评估是否替换 |
| `agentscope-spring-boot-starter-agui` | Agent UI 协议 | 未使用 |
| `agentscope-spring-boot-starter-admin` | Admin 管理面板 | 未使用 |
| `agentscope-spring-boot-starter-nacos` | Nacos 服务发现 | 未使用 |
| `agentscope-spring-boot-starter-chat-completions-web` | Chat Completions Web | 未使用 |

> **重要**：yunxi 自建 `ModelFactory`（`BaiduModelProvider`/`HuaweiModelProvider`）是 GA 未覆盖的私有模型适配，**属合法 value-add，保留不替代**。Spring Boot Starters 仅可用于公有模型自动配置，不可用于替代私有 ModelFactory。

---

## 十二、MCP Server 端能力

### 12.1 GA 支持

- 将 Agent 包装为 MCP Server 暴露给外部
- 支持 STDIO / SSE 传输

### 12.2 yunxi 机会

yunxi 平台很多富能力可作为 MCP 工具对外开放：
- **Text2SQL** (`agent-text2sql`)：自然语言转 SQL
- **数据库查询**：DatabaseTool 的复杂查询能力
- **知识检索**：应用层 RAG（`ApplicationRAG`）替代废弃 `Knowledge`
- **文件处理**：文件上传/解析能力

---

## 十三、升级优先级矩阵（确定性处置）

### P0 - 必须立即处理（阻塞性/高风险）

| 编号 | 事项 | 原因 | 影响范围 |
|------|------|------|---------|
| P0-1 | 版本号升至 `2.0.0` | 消除 API 不兼容风险 | 全部模块 pom.xml |
| P0-2 | 删除 `KnowledgeRetrievalMiddleware` 的废弃 `Knowledge` API，改应用层 RAG（`ApplicationRAG.onSystemPrompt`）；`GenericRAGHook` 同属废弃 `rag` 包，不可作替代 | 已废弃 API，升级后编译可能失败 | agent-core |
| P0-3 | 适配 `HarnessAgent.Builder` API 变更（plan/workspace/compaction 为 harness 专属，须用 `HarnessAgent.builder()`，非 `ReActAgent`） | API 签名可能已变，编译需通过 | agent-core |

### P1 - 高优先级（消除重复开发/补齐缺失）

| 编号 | 事项 | 原因 | 影响范围 |
|------|------|------|---------|
| P1-1 | **删除 ToolGateMiddleware，改用** `permissionContext` + `PermissionMode.DEFAULT` + `addAskRule`（含删 ReasoningReviewMiddleware / TextToolCallParserMiddleware） | 消除权限逻辑重复 | agent-core |
| P1-2 | 引入 `modelExecutionConfig` / `toolExecutionConfig`，**删除 ToolCircuitBreaker** | 补齐超时/重试/限流，消除重复 | agent-core |
| P1-3 | 引入 `CompactionMiddleware` + Memory 中间件（保留已用原生，增强） | 补齐长会话记忆管理 | agent-core |
| P1-4 | **删除 SseProgressListenerAdapter / TaskProgressEvent，直接映射** `AgentEvent`→SSE | 事件粒度、与框架一致，消除重复 | agent-core |
| P1-5 | **MCP 工具注册已完成（DONE）**：`AgentConfigurer.registerMcpServers()` 用框架原生 `McpClientBuilder`（SSE/STDIO/HTTP）按 `agentscope.core.mcp-servers` 真注册进 `Toolkit`（以服务器名建组，与分组激活衔接）；`AgentscopeAutoConfiguration.mcpServerBeans()` 死配置已删除（注：该实现依赖 P0-1 将 `agentscope.version` 升至 `2.0.0` 方可编译通过） | 服务端已改用官方 SDK 暴露 SSE 端点、客户端自建层（含残留的 DatabaseToolkit 自建 HTTP 客户端）已删 | agent-core |

### P2 - 中优先级（增强能力/替换重复）

| 编号 | 事项 | 原因 | 影响范围 |
|------|------|------|---------|
| P2-1 | **删除自建 Plan 包，启用** `PlanModeMiddleware` + `PlanModeManager` | Plan 模式统一，消除重复 | agent-core |
| P2-2 | **删除自建 Workspace 初始化，启用** `WorkspaceManager` + `WorkspaceContextMiddleware`（保留多租户路由） | Workspace 统一，消除重复 | agent-core |
| P2-3 | **启用被注释的** `skillSystem(...)` GA SkillSystem + 技能目录 | 技能能力补齐 | agent-core |
| P2-4 | 增强 `SubAgentConfig.forwardEvents(true)` | 透出子 Agent 事件 | agent-core |
| P2-5 | 启用 harness 模型重试/降级：`maxRetries` / `fallbackModel` / `stopOnReject` | 模型调用可靠性 | agent-core |
| P2-6 | `structuredOutput()` API 使用 | 输出可靠性 | agent-core, controller |

### P3 - 低优先级（优化/锦上添花）

| 编号 | 事项 | 原因 | 影响范围 |
|------|------|------|---------|
| P3-1 | 删除自建 agent-gateway，IM 接入全面采用 GA Channel 扩展（wecom/dingtalk/feishu 原生覆盖） | 渠道未上线、GA 全覆盖；网关业务逻辑随之移除，后续按需重建 | agent-gateway + agent-app pom |
| P3-2 | 评估 GA Spring Boot Starters 用于**公有模型**自动配置（**不替代**私有 ModelFactory） | 简化公有模型配置 | agent-core |
| P3-3 | 将平台能力暴露为 MCP Server | 扩大生态 | 全平台 |
| P3-4 | 引入 GA Memory 扩展 (Mem0/百炼) 作为补充后端（**不替换**已用原生 + Redis 后端） | 记忆能力增强 | agent-core |
| P3-5 | 引入 RAG 扩展 (Dify/RAGFlow) 作为 `ApplicationRAG` 检索后端选项 | RAG 能力增强 | agent-core |

---

## 十四、风险警告

### 14.1 高危：KnowledgeRetrievalMiddleware 使用的已废弃 API

```java
// yunxi 当前代码使用了 @Deprecated(forRemoval=true) 的 API
kb.retrieve(query, config).block()  // Knowledge.retrieve() 已废弃
```

GA 2.0 中该 API 已随整个 `rag` 包标记 `@Deprecated(forRemoval=true)`，升级后编译可能直接失败。**必须改为应用层 RAG 实现（`ApplicationRAG.onSystemPrompt` 注入检索结果）；`GenericRAGHook` 与 `Knowledge` 同属废弃 `rag` 包，不能作为替代。**

### 14.2 中危：HarnessAgent.Builder 接口变更

yunxi 应通过 `HarnessAgent.builder()` 构建 Agent（PlanMode/WorkspaceContext/Compaction 等是 harness 专属中间件，依赖 HarnessAgent 运行时，ReActAgent 无法挂载）。Builder 接口如有 Breaking Change 需逐一适配。

### 14.3 中危：MiddlewareBase 接口变更

`MiddlewareBase` 仅有 5 个钩子：`onAgent`/`onReasoning`/`onActing`/`onModelCall`（洋葱式，返 `Flux<AgentEvent>`）+ `onSystemPrompt`（变换式，返 `Mono<String>`）；**无** `beforeToolCall`/`afterToolCall`/`onResponse`/`onPlanning`。5 个自建中间件中：**3 个删除**（ToolGate/ReasoningReview/TextToolCallParser）、**2 个保留适配**（ContentFilter→`onAgent`、HumanToolRegistrar→包装 `ToolSuspendException`）。

### 14.4 低危：事件体系兼容

`ChatAppService` 改用 GA `AgentEvent` 映射后，需确认原有 SSE 前端消费字段兼容；`SseEmitterManager` 保留。

---

## 十五、升级执行建议

### 阶段一：编译通过（预计 0.5-1 天）

1. 修改 pom.xml 版本号 (`2.0.0-RC3` → `2.0.0`)
2. 处理编译错误（重点：废弃 Knowledge API、ReActAgent/Builder API、MiddlewareBase 接口）
3. 运行全部单元测试

### 阶段二：核心能力对齐（删-替重复/屏蔽，预计 1-2 天）

1. **删除** `KnowledgeRetrievalMiddleware` 废弃 `Knowledge` API → 新建 `ApplicationRAG`（`onSystemPrompt` 注入）
2. **删除** `ToolGateMiddleware` / `ReasoningReviewMiddleware` / `TextToolCallParserMiddleware` → 改用 `permissionContext` + `addAskRule`
3. **删除** `ToolCircuitBreaker` → 用 `toolExecutionConfig`
4. **删除** `SseProgressListenerAdapter` / `TaskProgressEvent` / `AgentInterruptService` / `CallOptions` → 用 GA 原生 `streamEvents`/`interrupt`/`RuntimeContext`
5. **删除** 自建 `Plan` 包、`Workspace` 初始化（保留多租户路由逻辑）

### 阶段三：补齐缺失 + 保留业务（预计 2-4 天）

1. 启用 `permissionContext` / `maxRetries` / `fallbackModel` / `stopOnReject` / `modelExecutionConfig` / `toolExecutionConfig`
2. 启用 GA v2 事件体系（`AgentEvent`→SSE）
3. **MCP 真注册已落地**（`AgentConfigurer.registerMcpServers`，框架原生 `McpClientBuilder`）
4. 启用 `WorkspaceManager` + `PlanModeMiddleware` + 被注释的 `skillSystem(...)`
5. 增强 `SubAgent` `forwardEvents(true)`
6. 保留：业务工具、ModelFactory(私有模型)、ContentFilter、HumanToolRegistrar、agent-spi、Redis 后端、记忆体系、多租户路由
7. **删除自建 `agent-gateway` 模块**，IM 接入改用 GA `agentscope-extensions-channel-{wecom,dingtalk,feishu}`（渠道未上线、GA 全覆盖；网关业务逻辑随之移除，后续按需重建）

---

## 十六、参考来源

1. AgentScope Java 2.0 GA 源代码 (`d:\work\code\agentscope-java-2.0GA`)
2. yunxi-agent-platform 源代码 (`d:\work\code\yunxi-agent-platform`)
3. AgentScope Java SKILL.md (项目根目录)
4. 配套处置总表：`docs/agentscope-2.0GA-upgrade-plan.md`（逐组件处置总表 + 目标架构 + 重写代码示例）
