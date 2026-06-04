# 更新日志

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
- 规则引擎支持
- 多平台接入（Web、企业微信、钉钉、飞书）

### 📦 模块

| 模块 | 说明 |
|------|------|
| `agent-core` | 核心框架 |
| `agent-gateway` | 统一网关 |
| `agent-rule-engine` | 规则引擎 |
| `agent-business` | 业务实现 |
| `agent-text2sql` | SQL 生成 |
| `mcp-common` | MCP 协议 |
