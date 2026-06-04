# 更新日志

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
