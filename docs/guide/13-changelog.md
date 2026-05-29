# 13. 更新日志

## [3.1.0] - 2026-05-26

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
