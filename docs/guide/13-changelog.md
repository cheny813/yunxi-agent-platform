# 13. 更新日志

## [3.2.0] - 2026-06-02

### 架构变更

- **启用底层 Session 持久化**：HarnessAgent 已内置 `SessionPersistenceHook`，自动在每轮 `call()` 后保存 Agent 运行时状态（Memory、PlanNotebook 等），无需上层代码干预。不再重复建设 ConversationDomainService 的写路径
- **GracefulShutdown 支持**：注册 `GracefulShutdownHook`，在 PostReasoningEvent/PostActingEvent 后做 checkpoint。被优雅关闭中断后，客户端检测 `shutdown_interrupted` 标记后可通过 `agent.loadIfExists()` 恢复
- **新增 `agentscope-extensions-session-redis` 可选依赖**：配置 `agentscope.core.session.type=redis` 即可切换为 Redis 后端，实现跨实例状态共享。默认使用 `WorkspaceSession`（文件系统），零配置可用

### 修复

- **WorkspaceAutoDiscoveryEngine 误报 WARN**：`users/` 目录不再被误判为 Agent 工作区，改为递归扫描 `users/{userId}/{agentName}/` 层级，正确发现用户级 Agent 工作区

### 新增

- **AgentSessionConfig**：按配置自动创建 Session Bean，`@ConditionalOnProperty` 控制，`RedisTemplateAdapter` 将 Spring RedisTemplate 适配为 agentscope 的 `RedisClientAdapter`
- **GracefulShutdownHook 注册**：在 `AgentConfigurer.injectStandardHooks()` 中调用 `builder.hook(new GracefulShutdownHook(GracefulShutdownManager.getInstance()))`
- **Session 配置项**：`agentscope.core.session.type`（workspace/redis）

### 移除

- 无重复轮子被移除。ConversationDomainService 保留读路径（前端列表查询），写路径已由 SessionPersistenceHook 接管

---

### 架构变更

- **引入 agentscope-harness**：Agent 创建改用 `HarnessAgent.from(delegate).build()`，所有业务层通过 `Agent` 接口调用，实现层与接口层解耦
- **缓存类型统一**：`Map<String, ReActAgent>` → `Map<String, Agent>`，面向接口编程

### 移除

- **MemoryCoordinatorService**：已删除，记忆管理由 HarnessAgent 内部 Hook 负责
- **AsyncConversationPersistenceService**：已删除，会话持久化由 HarnessAgent 内部 Hook 负责
- **AgentBuilderHelper** 中的 `@Autowired` 字段注入：改为构造器注入
- **反射获取 toolkit**：`ReActAgent.class.getDeclaredField("toolkit")` 已移除

### 新增

- **agentscope-harness 依赖**：POM 中新增 `agentscope-harness`
- **AgentCustomizer SPI**：接口方法改为 `Agent customize(AgentDefinition, Agent)`
- **AgentDomainService 缓存**：新增 `findAgent()`（返回 null）、`getAgentSysPrompt()`、`getAgentModelProvider()`

### 变更

- **AgentBuilderHelper**：所有方法参数 `ReActAgent.Builder` → `HarnessAgent.Builder`
- **AgentConfigurer**：Agent 装配流程适配 HarnessAgent.Builder；移除无用 `skillRegistryProvider` 字段
- **AdvancedAgentFactory**：`createTempAgent()` 返回 `Agent`；移除反射模型参数设置
- **ChatAppService**：移除 MemoryCoordinatorService 和 AsyncConversationPersistenceService 所有调用

### 注释完善

- AdvancedAgentFactory、AgentGatewayImpl、ProfileRouter、MemorySceneRegistry、AgentConfigurer、AgentInterruptService、ConversationController、AgentController、PlanInteractionController、PlanPreCreator、RequestConfigService、SupervisorService、RecipeStreamService、A2AServer 等 14+ 文件增加了完整的中文 Javadoc

---

## [1.0.0] - 2026-05-09

### 新增

- 初始版本发布
- 支持多 Agent 协作
- 集成 MCP 协议
- 规则引擎支持
- 多平台接入（Web、企业微信、钉钉、飞书）

### 模块

- agent-core: 核心框架
- agent-gateway: 统一网关
- agent-rule-engine: 规则引擎
- agent-business: 业务实现
- agent-text2sql: SQL 生成
- mcp-common: MCP 协议

---

---

**上一页**: [12. 常见问题](./12-faq.md)  
**下一页**: [14. 智能子系统 →](./14-intelligent-system.md)
