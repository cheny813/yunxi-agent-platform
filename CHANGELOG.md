# 更新日志

## [Unreleased]

### ✨ ModelFactory 支持按 Agent 覆盖 / 轻量多租户

- **多租户开关（无需额外 boolean）**：Agent 定义 YAML 的 `model.apiKey` / `model.baseUrl` / `model.stream` 现在对所有提供商生效。不填 → 回退全局 `agentscope.core.*` 或环境变量（单租户默认）；填写 → 经框架 `ModelCreationContext` 透传，实现每 Agent 独立账号（轻量多租户）。
- **根因修复**：此前 5 个官方提供商工厂（openai / dashscope / anthropic / claude / deepseek）注册的是 `ModelRegistry.registerFactory` 的 1 参重载（`ModelFactory.create(modelId)`），收不到 `ModelCreationContext`，导致 `apiKey`/`baseUrl` 只对 baidu/huawei 自定义实现生效、对官方提供商是死字段。现改为 `ContextModelFactory` 2 参重载（`create(modelId, context)`），从 context 读取覆盖值，优先级统一为 `AgentModelConfig 显式值 > provider 级配置 > 全局配置 > 环境变量`。
- **gemini / ollama 自动消费**：无自定义工厂的 gemini / ollama 由 SPI 提供商经 `ModelCreationContext` 自动发现并消费配置，与官方文档用法对齐。
- **`AgentModelConfig` 新增 `stream` 字段**：支持按 Agent 显式关闭流式输出。
- **清理死代码**：移除原先仅为 `GenerateOptions` 服务的 `registerModelWithOptions` 命名模型注册，改为 context 的 `component(GenerateOptions.class, …)`。
- **模型缓存策略（CachePolicy）语义对齐并文档化**：`ModelFactory` 始终走 `ModelRegistry.resolve(modelId, context)`，不显式设置 `CachePolicy`，自动套用框架 `DEFAULT`——单租户（空 `ModelCreationContext`）按 `modelId` 缓存复用实例，多租户（带 `apiKey`/`baseUrl`/`stream`/`GenerateOptions`）默认不缓存，防止不同租户的 Key / BaseURL / stream 复用到同一 Model 实例。`gemini`/`ollama` 经 SPI 提供方解析后同样适用该策略。详见 `docs/guide/06-configuration.md` 新增的「模型缓存策略」小节。

## [2.0.0] - 2026-07-12

> **版本策略变更**：自本版本起，yunxi Agent Platform 的版本号与底层 [AgentScope-Java](https://github.com/agentscope-ai/agentscope-java) 保持同步，本版本对应 AgentScope-Java **2.0.0 正式版（GA）**。此前的 1.0.0 / 3.x 为独立版本线（见下方历史记录），不影响其变更内容的有效性。

### 🚀 升级 AgentScope 框架至 2.0.0 GA（RC3 → GA 正式版）

本次在 2.0.0-RC3 基础上，全面拥抱 GA 原生能力，根治"屏蔽底层能力 / 重复开发"两类历史问题，并清理无业务使用的模块。

#### 框架 API 全面对齐 GA 2.0.0（已对照 GA 源码逐类核实）

- **入口 Builder 锁定 `HarnessAgent.builder()`**：plan/workspace/compaction 为 harness 专属中间件，依赖 HarnessAgent 运行时。重点补齐此前未用的 GA 配置项（权限、重试降级、超时、Skill、MCP 真注册）。
- **MCP 真注册（P1-5 DONE）**：删除 `AgentscopeAutoConfiguration.mcpServerBeans()` 死配置，改由 `AgentConfigurer.registerMcpServers()` 用框架原生 `McpClientBuilder`（SSE/STDIO/HTTP）按 `agentscope.core.mcp-servers` 实例化并注册进 `Toolkit`（以服务器名建组，与分组激活衔接）。7 个自建 MCP 客户端类 + 残留自建 MCP HTTP 客户端（`DatabaseToolkit` 等）已全部删除，数据同步改直连 JDBC（`ExternalDbQueryService`）。
- **权限引擎（替代 ToolGate/ReasoningReview）**：新增 `PermissionConfig`，将 HITL 配置映射为 `PermissionContextState`（`DEFAULT` + `addAskRule` / `addDenyRule`），经 `builder.permissionContext(...)` 注入。
- **应用层 RAG（替代废弃 `rag.Knowledge`）**：新增 `ApplicationRAG`，经 `MiddlewareBase.onSystemPrompt` 注入检索上下文。
- **Plan 模式（替代自建 PlanPreCreator）**：启用 GA `PlanModeMiddleware` + `PlanModeManager`，复用 `WorkspaceManager(Path)`。
- **Skill 系统**：启用 GA 原生 `AgentSkillRepository`（FileSystem + 项目级全局目录），由框架 `DynamicSkillMiddleware` 自动装载。
- **韧性（重试/降级/超时）**：启用 `maxRetries` / `fallbackModel` / `stopOnReject` / `modelExecutionConfig(timeout)`，完全复用框架 API。
- **多租户路由**：删除 `UserWorkspaceService`，改用 `RuntimeContext(userId,sessionId)` 注入，由 GA `HarnessAgent.workspaceFor(...)` 运行时按用户命名空间隔离工作空间。

#### 移除（屏蔽/重复 / 无业务使用）

- **`agent-gateway` 模块整体删除**：IM 渠道（企微/钉钉/飞书/Web API）未上线，GA `agentscope-extensions-channel-*` 已原生覆盖传输层；根 pom 模块列表、agent-app 依赖同步移除。
- **`agent-rule-engine` 模块整体删除**：当前无实际业务使用（规则引擎预处理/后处理已无调用方），未来需要时再重建。相关文档/配置/测试引用一并清理（端口 40002、SpEL 规则、rule-engine.yml 等）。
- **屏蔽/重复类删除**：`ToolGateMiddleware`、`ReasoningReviewMiddleware`、`TextToolCallParserMiddleware`、`KnowledgeRetrievalMiddleware`、`KnowledgeAutoConfiguration`、`KnowledgeCreator`、`PlanPreCreator`、`PlanTemplateLoader`、`Plan*.java`、`AgentWorkspaceInitializer`、`WorkspaceValidator`、`ToolCircuitBreaker`、`SseProgressListenerAdapter`、`TaskProgressEvent`、`AgentInterruptService`、`CallOptions`、`DatabaseToolkit` 等。
- **场景检测链整体删除**（经二次复核确认为死代码）：`WorkspaceAutoDiscoveryEngine`、`DefaultSceneDetector`、`SceneDetectionParser`、`WorkspaceConfig`、`SceneDetectionRule`；工作空间知识/技能/子 Agent 发现已由 GA 原生 `WorkspaceContextMiddleware` 承载，记忆场景路由由 `MemorySceneRegistry` + `SceneDetectionService` 覆盖。
- 根 pom `agentscope.version` 由 `2.0.0-RC3` 升至 `2.0.0`；项目版本号 `1.0.0` → `2.0.0`，与底层框架同步。

#### 保留（GA 未覆盖的合法价值）

- 业务工具（HttpTool/DatabaseTool/SessionSearchTool/NodeTool/CalculatorTool/ShellToolFactory）、`ModelFactory`（百度/华为私有模型）、`ContentFilterMiddleware`（提示注入检测）、`HumanToolRegistrar`（HITL）、`agent-spi`、`RedisDistributedBackendConfig`、记忆体系（CompactionConfig/DistributedStore）。

#### 验证

- `mvn clean compile -DskipTests` 全模块 BUILD SUCCESS（GA 2.0.0 构件本地仓库可解析）。
- **集成测试重写并通过**：`agent-integration-test` 下 5 个测试类已按新架构（主类 `io.yunxi.platform.AgentPlatformApplication`）重写，脱离已废弃的 `agent-business` 服务。`EndToEndIntegrationTest`、`CrossModuleIntegrationTest`、`ErrorRecoveryIntegrationTest` 共 13 个用例全部通过（真实调用本地 DashScope LLM + 本地 MySQL/Redis）。
- **Milvus ETL 链路验证**：本地 Milvus（`192.168.11.48:19530`）可用，`SyncEngine` 完整跑通「外部库 `nutrition_zhaoxian` (MySQL) → 向量集合」ETL；10 个集合初始化完成，`ingredient_classes`/`nutrient_standard_details`/`ingredient_nutrients`/`cook_book_score_index` 等数据入库并复用。集成测试配置默认启用 `milvus` MCP。

## [3.5.0] - 2026-06-26

### 🏗️ 升级 AgentScope 框架至 2.0.0-RC3（破坏性升级）

此次升级是一次全面采用新框架 API 的破坏性升级，删除了所有已被框架替代的旧代码和废弃 API。

#### 框架 API 变更适配

- **`Session` → `DistributedStore`**：RC3 完全删除 `io.agentscope.core.session` 包。原 `Session session` 注入改为 `DistributedStore distributedBackend`，现有 `RedisDistributedStore.fromJedis(jedis)` 替代 `SessionFactory.createRedisSession()`。新增 `RedisDistributedBackendConfig.java` 按 `@ConditionalOnClass` 自动装配。Redis 扩展依赖从 `agentscope-extensions-session-redis:1.0.12` 升级到 `agentscope-extensions-redis:2.0.0-RC3`。
- **`Tracer`/`TracerRegistry` 废弃 → OpenTelemetry 直连**：删除 `OpenTelemetryTracer.java`，改用 OpenTelemetry 全局实例 `GlobalOpenTelemetry.get()`。
- **`stream()` → `streamEvents()`**：`StreamableAgent.stream()` 在 RC3 废弃。所有调用方（`AgentGatewayImpl`、`ChatAppService`、`ConversationController`）改为 `harnessAgent.streamEvents(List.of(msgs))`，按 `AgentEventType` 过滤事件。
- **`Model` 创建 → `ModelRegistry` 工厂机制**：`ModelFactory.init()` 使用 `ModelRegistry.registerFactory()` 注册各 Provider 工厂（`openai:.+`、`dashscope:.+` 等），API Key 解析优先级为 provider 级配置 → 全局配置 → 环境变量。
- **`MiddlewareBase` 签名变更**：RC3 给所有 Middleware 方法添加 `RuntimeContext ctx` 参数。6 个 Middleware 文件（`ContentFilterMiddleware`、`ToolGateMiddleware`、`ReasoningReviewMiddleware`、`TextToolCallParserMiddleware`、`KnowledgeRetrievalMiddleware`、`ReActSpanMiddleware`）全部更新签名。
- **A2A 模块包名变更**：`spring-boot-starter-runtime-a2a:0.1.0` → `agentscope-a2a-spring-boot-starter:${agentscope.version}`，`@SpringBootApplication(exclude=...)` 包名同步更新。
- **`Event`/`EventType` → `AgentEvent`/`AgentEventType`**：所有事件处理和转换代码适配新的事件类体系。

#### 新增

- **RedisDistributedBackendConfig**：按 `@ConditionalOnClass` 自动装配 `RedisDistributedStore`。
- **ModelFactory.registerFactory()**：利用 RC3 `ModelRegistry` 机制集中注册 Provider 工厂。
- **Plan.java / SubTask.java**：本地模型类替代框架已删除的 `io.agentscope.core.plan.model` 包。

### 🔥 移除

- **OpenTelemetryTracer.java**：因框架 `TracerRegistry` 废弃，改用全局 OpenTelemetry API。
- **RedisSessionConfig.java**：被 `RedisDistributedBackendConfig` 替代。
- **ToolGateHook.java**：被 `ToolGateMiddleware` 替代（Hook → Middleware 迁移）。
- **ReasoningReviewHook.java**：被 `ReasoningReviewMiddleware` 替代。
- **所有对 `stream()`、`Tracer`、`TracerRegistry` 的废弃 API 引用**：zero warnings。

### 🔧 变更

- **start.ps1**：添加 `-Djava.net.preferIPv4Stack=true` 解决 `UnresolvedAddressException`（IPv6 优先导致 DashScope DNS 解析失败）。
- **GlobalExceptionHandler**：添加 SSE 流感知错误处理——检测 `Content-Type: text/event-stream` 时直接写入 SSE error 事件，避免 `HttpMessageNotWritableException`。
- **ChatAppService.buildStreamResponse()**：添加 `.onErrorResume()` 将 `streamEvents()` 中的异常转为 SSE error 事件，防止异常传播到 WebFlux 响应层。
- **agent-integration-test**：打包方式改为 `pom` + `maven-jar-plugin` skip，消除空 JAR 警告。
- **milvus.yml**：默认 `enabled: false`，消除 Milvus 未启动时的连接超时报错。

### 📝 文档更新

- README.md：版本号更新为 RC3；框架适配表新增 Session → DistributedStore、Tracer 废弃 2 项 ✅ 已修复。
- CHANGELOG.md：新增 3.5.0 版本记录。

---

## [3.4.0] - 2026-06-04

### 🏗️ 复用底层框架能力（避免重复造轮子）

- **复用 agentscope 框架的 Model 体系**：拆除自建的 `ChatModelProvider` 接口及 `OpenAIModelProvider`/`ClaudeModelProvider`/`DashScopeModelProvider`（约 500 行），改用框架内置的 `OpenAIChatModel`/`AnthropicChatModel`/`DashScopeChatModel`，利用其正确的角色映射和 Prompt Caching 支持。新增 `model/` 包与 `embedding/` 包分离，消除包名误导。保留 `BaiduModelProvider`/`HuaweiModelProvider`（因认证协议不兼容标准 API），但修复了角色硬编码 Bug。
- **复用框架的 Shell 安全能力**：拆除自建的 `CommandSafety` 枚举和 `CommandSafetyClassifier`（约 310 行），改用框架 `ShellCommandTool` 的白名单 + 平台验证器（Unix/Windows 自动检测）+ 审批回调 + 多命令分隔符/路径穿越检测。
- **新建提示注入防护 Hook**：基于框架 `Hook` 接口实现 `ContentFilterHook`，在 `PostReasoningEvent` 阶段检测中英文注入模式并调用 `stopAgent()` 阻断，弥补框架无现成注入防护的空缺。
- **Prompt Caching 配置化**：`AgentscopeCoreProperties` 新增 `GenerationConfig` 配置段，`cache-control: true` 即可利用框架内置的 Prompt Caching 能力（OpenAI 前缀缓存 / Anthropic cache_control 标记 / DashScope 前缀缓存）。
- **DeepSeek 支持**：通过框架 `DeepSeekFormatter` 即开即用，无需自建 Provider。

### ✨ 新增

- **ModelFactory**：统一创建框架 Model 实例的 Spring Bean，支持 openai/claude/dashscope/deepseek/baidu/huawei 六种提供商。
- **ShellToolFactory**：封装框架 `ShellCommandTool`，从配置注入白名单和审批回调。
- **ContentFilterHook**：基于框架 `Hook` 接口的提示注入防护，含中英文双模式检测。
- **生成参数配置**：`agentscope.core.generation` 全局配置段（temperature/maxTokens/topP/cacheControl）。

### 🔥 移除（已被框架能力替代）

- **ChatModelProvider 接口** → 改用框架 `Model` 接口
- **OpenAIModelProvider** → 改用框架 `OpenAIChatModel`
- **ClaudeModelProvider** → 改用框架 `AnthropicChatModel`
- **DashScopeModelProvider** → 改用框架 `DashScopeChatModel`
- **CommandSafety 枚举** + **CommandSafetyClassifier** → 框架 `ShellCommandTool` 替代

### 🔧 变更

- **AgentConfigurer**：注入 `ContentFilterHook`；`createModelProvider()` 改为 `modelFactory.create()`
- **AgentDomainService**：`ModelFactory` 注入替换自建 `createProvider()`；缓存 `Map<String, ChatModelProvider>` → `Map<String, Model>`
- **AdvancedAgentFactory**：`getAgentModelProvider()` → `getAgentModel()`，类型 `ChatModelProvider` → `Model`
- **PageAgentService**：字段 `ChatModelProvider` → `Model`，构造器注入 `ModelFactory`
- **IntelligentLlmService**：重写为 `ModelFactory.create()`，移除自建 `createModel()`
- **NodeTool**：`CommandSafetyClassifier` 依赖 → `ShellToolFactory`
- **NodeAuditService**：`CommandSafety` 参数类型改为 `String`
- **BaiduModelProvider** / **HuaweiModelProvider**：接口 `ChatModelProvider` → `Model`，修复 role 映射
- **datsource.yml**：数据库连接 URL 固定库名，消除环境变量与 schema.sql 不一致问题

### 📝 文档更新

- README.md：核心特性增加提示注入防护/Shell 安全/Prompt Caching；框架适配表新增 2 条 ✅ 已修复项
- `docs/guide/04-architecture.md`：7 层架构第 5 层改为"模型层"；ModelProviderFactory → ModelFactory；诚实的评估新增 2 条 ✅ 已修复
- `docs/guide/06-configuration.md`：新增"生成参数配置"（含 cache-control 表格）和"Shell 命令安全配置"段

---

## [3.3.1] - 2026-06-03

### 🐛 修复

- **修正工具组分配（ungrouped 问题）**：HarnessAgent/ReActAgent 内置工具通过 `registerTool(Object)` 注册时不指定组名，导致所有 memory/session/filesystem/shell/subagent/task 工具被标记为 "ungrouped"。通过反射调用 `ToolGroupManager.addToolToGroup()` 将未分组工具分配到 "general" 组，使其受 `updateToolGroups()` 管控。详见 `docs/guide/11-best-practices.md` 底层框架适配章节。
- **修复 Toolkit 深拷贝后组激活失效**：`ReActAgent.Builder.build()` 内部深拷贝 Toolkit，但 `applyToolGroupActivation()` 之前操作的是原始 Toolkit。改为通过 `HarnessAgent.getDelegate().getToolkit()` 获取 Agent 内部使用的 Toolkit，使组激活真正生效。

### 🔧 变更

- `AgentConfigurer.java`：新增 `resolveAgentToolkit()` 和 `assignUngroupedTools()` 方法；修改 `initializeSingleAgent()` 和 `createSupervisorAgent()` 在构建后修正未分组工具并在正确的 Toolkit 上执行组激活。

---

## [3.2.0] - 2026-06-02

### 🏗️ 架构变更

- **启用框架 Session 持久化**：`HarnessAgent` 已内置 `SessionPersistenceHook`，在每轮 `call()` 后自动持久化 Agent 运行时状态（Memory、PlanNotebook 等），上层代码无需额外处理。`ConversationDomainService` 写路径随之移除，避免重复建设
- **GracefulShutdown 支持**：注册 `GracefulShutdownHook`，在 `PostReasoningEvent` / `PostActingEvent` 后执行 checkpoint。被优雅关闭中断的客户端可通过检测 `shutdown_interrupted` 标记，调用 `agent.loadIfExists()` 恢复执行
- **引入 agentscope-harness**：Agent 创建统一采用 `HarnessAgent.from(delegate).build()` 模式，业务层面向 `Agent` 接口编程，实现层与接口层解耦
- **缓存类型统一**：`Map<String, ReActAgent>` → `Map<String, Agent>`，面向接口编程，降低对具体实现的耦合

### ✨ 新增

- **agentscope-harness 依赖**：POM 新增 `agentscope-harness` 模块
- **AgentCustomizer SPI**：接口方法优化为 `Agent customize(AgentDefinition, Agent)`
- **AgentDomainService 缓存**：新增 `findAgent()`（返回 null）、`getAgentSysPrompt()`、`getAgentModelProvider()` 方法
- **Redis Session 支持**：新增 `agentscope-extensions-session-redis` 可选依赖，配置 `agentscope.core.session.type=redis` 即可切换 Redis 后端，实现跨实例状态共享。默认使用 `WorkspaceSession`（文件系统），零配置可用
- **AgentSessionConfig**：按配置自动创建 Session Bean，通过 `@ConditionalOnProperty` 控制；`RedisTemplateAdapter` 将 Spring `RedisTemplate` 适配为 agentscope 的 `RedisClientAdapter`
- **GracefulShutdownHook 注册**：在 `AgentConfigurer.injectStandardHooks()` 中调用 `builder.hook(new GracefulShutdownHook(GracefulShutdownManager.getInstance()))`
- **Session 配置项**：新增 `agentscope.core.session.type`（可选值：`workspace` / `redis`）

### 🔥 移除

- **MemoryCoordinatorService**：记忆管理由 HarnessAgent 内部 Hook 接管
- **AsyncConversationPersistenceService**：会话持久化由 HarnessAgent 内部 Hook 接管
- **AgentBuilderHelper 字段注入**：`@Autowired` 字段注入改为构造器注入
- **反射获取 Toolkit**：移除 `ReActAgent.class.getDeclaredField("toolkit")` 反射调用

### 🔧 变更

- **AgentBuilderHelper**：方法参数从 `ReActAgent.Builder` 迁移至 `HarnessAgent.Builder`
- **AgentConfigurer**：Agent 装配流程适配 `HarnessAgent.Builder`；移除无用 `skillRegistryProvider` 字段
- **AdvancedAgentFactory**：`createTempAgent()` 返回类型提升为 `Agent` 接口；移除反射设置模型参数的逻辑
- **ChatAppService**：移除对 `MemoryCoordinatorService` 和 `AsyncConversationPersistenceService` 的所有调用

### 🐛 修复

- **WorkspaceAutoDiscoveryEngine 误报 WARN**：修复 `users/` 目录被误判为 Agent 工作区的问题，改为递归扫描 `users/{userId}/{agentName}/` 层级，正确发现用户级 Agent 工作区

### 📝 注释完善

- 为 `AdvancedAgentFactory`、`AgentGatewayImpl`、`ProfileRouter`、`MemorySceneRegistry`、`AgentConfigurer`、`AgentInterruptService`、`ConversationController`、`AgentController`、`PlanInteractionController`、`PlanPreCreator`、`RequestConfigService`、`SupervisorService`、`RecipeStreamService`、`A2AServer` 等 14+ 个文件补充了完整的中文 Javadoc

---

## [1.0.0] - 2026-05-09

### ✨ 新增

- 初始版本发布
- 支持多 Agent 协作
- 集成 MCP 协议
- 多平台接入（Web、企业微信、钉钉、飞书）

### 📦 模块

| 模块 | 说明 |
|------|------|
| `agent-core` | 核心框架（含网关接入、Agent 编排、记忆、技能、安全） |
| `agent-text2sql` | SQL 生成 |
| `agent-spi` | SPI 接口定义 |
| `agent-config` | 统一配置 |
| `agent-app` | 启动入口 |
