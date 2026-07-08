# 05. 模块说明

> **⚠️ V2.0 包结构变更**：agent-core 内部包结构已从 `framework/`/`shared/`/`infra/` 三层重构为扁平化的 13 个功能包。以下为更新后的结构。

了解 yunxi Agent Platform 各模块的功能和职责。

## 模块概览

```
yunxi-agent-platform/
├── agent-spi               # SPI 接口定义（最底层抽象）
├── agent-config            # 集中化配置管理
├── agent-core              # 核心框架（Agent 生命周期、工作区、MCP、同步引擎）
├── agent-gateway           # 统一消息网关（企微/钉钉/飞书/Web API）
├── agent-rule-engine       # 规则引擎 + 评分引擎
├── agent-text2sql          # 自然语言转 SQL
├── agent-app               # 可执行应用打包
└── agent-integration-test  # 跨模块集成测试
```

> 桌面客户端（`agent-desktop` → `yunxi-claw`）和远程节点（`agent-node` → `yunxi-agent-node`）已拆分为独立工程，详见对应仓库。

### 依赖链

```
agent-spi → agent-config → agent-core → agent-gateway
                ↑               ↑
          agent-rule-engine  agent-text2sql
                ↓               ↓
            agent-app (聚合 + 启动入口)
```

---

## agent-spi（SPI 扩展接口）

### 职责

定义所有可插拔扩展点的接口，是**最底层模块**，仅依赖 spring-core 和 jackson。

### 定义的 SPI 接口

| 接口 | 用途 | 扩展方向 |
|------|------|---------|
| `CacheProvider` | 缓存服务（命名空间、TTL、Hash 操作） | Redis / Caffeine / 本地 |
| `VectorSearchProvider` | 向量搜索 | Milvus / Qdrant / ES |
| `VectorPersistenceProvider` | 向量持久化（记忆存储与语义检索） | Milvus / Qdrant / PGVector |
| `UserProfileProvider` | 用户画像（身份、上下文、社会关系） | 业务系统对接 |
| `EmbeddingService` | 嵌入服务 | DashScope / OpenAI / 本地 |
| `DatabaseClient` | 数据库客户端 | MySQL / PostgreSQL |
| `Text2SqlFacade` | 自然语言转 SQL 统一入口 | agent-text2sql 实现 |

### 设计原则

遵循依赖倒置原则：框架层（agent-core）定义抽象，业务层实现——高层不依赖低层具体实现。

---

## agent-core（核心框架）

### agent-core 内部包结构（V2.0 扁平化后）

```
agent-core/src/main/java/io/yunxi/platform/
├── agent/        ← Agent 核心（工厂、网关、Middleware、工作区）
├── cache/        ← Redis 缓存
├── config/       ← 配置类（含 @ComponentScan 扫描）
├── controller/   ← REST 控制器
├── embedding/    ← Embedding 提供商
├── file/         ← 文件处理
├── gateway/      ← SSE 消息通道
├── knowledge/    ← 知识库（通过 Middleware 注入）
├── lifecycle/    ← 生命周期
├── mcp/          ← MCP 协议层
├── persistence/  ← 持久化（Milvus + Repository）
├── security/     ← 安全（HITL、审计、认证）
├── session/      ← 会话管理
├── tracing/      ← 可观测性（OpenTelemetry）
├── a2a/          ← 跨服务 Agent 协作
├── conversation/ ← 对话编排
├── sync/         ← 数据同步
├── tool/         ← 自定义工具
├── memory/       ← 记忆系统
├── prompt/       ← 场景检测
├── intelligent/  ← 智能自动配置
├── pageagent/    ← 页面 Agent
├── desktop/      ← 桌面客户端中继
├── structured/   ← Schema 注册
└── async/        ← 异步执行器配置
```

### agent/ Agent 核心包

| 组件 | 说明 | 代码量 |
|------|------|:-----:|
| `AgentGateway` | 业务层唯一需要的接口，定义 call/callStream 方法 | 接口 |
| `AgentGatewayImpl` | **核心实现** — 8 步拦截链：审计→限流→优雅关闭→超时→Pre→Agent.call→Post→监控 | ~430行 |
| `AgentService` | Agent 生命周期管理（创建、缓存、获取），通过 HarnessAgent 包装 | ~320行 |
| `AgentConfigurer` | **Agent 自动装配引擎** — 启动时两轮初始化：独立 Agent → 编排 Agent | ~555行 |
| `AgentInterruptService` | Agent 执行中断服务，封装 agentscope interrupt() API | ~240行 |
| `AgentWorkspaceInitializer` | 工作区目录初始化（AGENTS.md、knowledge/、memory/、users/ 等） | - |
| `UserWorkspaceService` | 用户工作空间服务，负责创建用户隔离的 Agent 实例，路径为 `{agentName}/users/{userId}/` | - |
| `WorkspaceAutoDiscoveryEngine` | 启动时扫描 Agent 工作空间（跳过 `skills/`），解析 AGENTS.md 场景规则、knowledge/、skills/、subagents/ | - |
| `TempAgentFactory` | 临时 Agent 创建工厂（原名 AdvancedAgentFactory） | - |
| `ProfileRouter` | Profile 路由：agentName + profile → Agent 实例 | - |
| `ModelFactory` | 统一模型工厂，复用框架内置 Provider | - |

扩展点（agent/middleware/）：
- `ContentFilterMiddleware` — 提示注入防护（替换原 ContentFilterHook）
- `TextToolCallParserMiddleware` — 文本工具调用解析（替换原 TextToolCallParserHook）
- `ToolGateMiddleware` — HITL 工具门控（替换原 ToolGateHook）
- `ReasoningReviewMiddleware` — 推理审查（替换原 ReasoningReviewHook）
- `ReActSpanMiddleware` — OpenTelemetry 链路追踪（替换原 ReActSpanHook）
- `GracefulShutdownMiddleware` — 优雅关闭（替换原 GracefulShutdownHook）

#### 工具体系（tool/）

| 组件 | 说明 |
|------|------|
| `Tool` 接口 | 平台工具接口：getName/getDescription/getParameterSchema/execute |
| `ToolAdapter` | **桥接类** — 将平台 Tool 适配为 agentscope 的 AgentTool，集成熔断器 |
| `ToolRegistry` | 本地工具注册中心 |
| `ToolGroupManager` | 工具分组管理器（MCP 按服务器分组，本地分 agent/page/general） |
| `ToolCircuitBreaker` | 工具级熔断器（Resilience4j） |
| 实现类 | DatabaseTool、HttpTool、CalculatorTool、NodeTool 等 |

#### MCP 协议（mcp/）

| 组件 | 说明 |
|------|------|
| `McpServerRegistrar` | ✅ V2.0 内置 MCP 服务器注册器（替代 McpToolRegistry） |
| `McpClientService` | MCP 客户端，JSON-RPC 2.0 协议调用 |
| `McpClient` / `McpClientConfig` | MCP 客户端配置 |
| `CacheableTool` | 支持缓存的 MCP 工具 |

#### 其他框架组件

| 组件 | 说明 |
|------|------|
| `a2a/` | 跨服务 Agent 协作协议（A2AServer/A2AClient/A2ARegistry） |
| `memory/` | 记忆系统（MemoryRecord/MemoryScene/MemorySceneRegistry + Harness 内置记忆） |
| `skill/` | ❌ 已删除 — 替换为 V2.0 SkillCurator 治理流水线 |
| `conversation/` | 对话编排（ChatAppService/ConversationService） |
| `workspace/` | 工作区自动发现引擎 |
| `session/` | 会话管理 |
| `sync/` | 数据同步引擎（MySQL → Milvus） |
| `plan/` | PlanNotebook 持久化、计划模板 |
| `profile/` | 职业画像、概念注册 |
| `pageagent/` | 页面 Agent（表单、OpenAI 代理） |
| `hitl/` | Human-in-the-Loop（工具门控、推理审查） |
| `security/` | 命令安全分类、节点审计 |
| `embedding/` | 嵌入模型（DashScopeProvider/OpenAIProvider/BaiduProvider/HuaweiProvider/ClaudeProvider） |
| `knowledge/` | 知识库创建器（Bailian/Dify/HayStack/RAGFlow/Simple）。通过 Middleware 注入 Agent 调用链 |
| `controller/` | REST 控制器（Agent/Conversation/Tool/MCP/Plan/Config） |

### 已迁移的功能包（原 shared/infra 层）

| 原路径 | 目标路径 | 说明 |
|--------|---------|------|
| `shared/config/` | `config/` | 核心配置模型 + YAML 加载器 + @ConfigurationProperties |
| `shared/dto/entity/mapper/exception/` | `persistence/` + 保留在 shared/ | DTO、实体、Mapper、异常 |
| `infra/persistence/` + `infra/milvus/` | `persistence/` | 持久化策略 + Milvus 向量数据库 |
| `infra/sse/` | `gateway/` | SSE 推送支持 |
| `infra/file/` | `file/` | 文件处理 |
| `infra/lifecycle/` | `lifecycle/` | 生命周期管理 |
| `infra/monitoring/` + `framework/observability/` | `tracing/` | 可观测性 |
| `infra/cache/` | `cache/` | Redis 缓存 |

---

## agent-gateway（统一消息网关）

### 通道支持

| 平台 | 协议 | 实现类 |
|------|------|--------|
| Web API | HTTP/REST | `WebApiChannel` |
| 企业微信 | Webhook | `WeComChannel` |
| 钉钉 | Stream | `DingTalkChannel` |
| 飞书 | WebSocket | `FeishuChannel` |

### 核心组件

| 组件 | 说明 |
|------|------|
| `GatewayDispatcher` | 消息调度中心，接收回调 → 调用 agent-core → 返回响应 |
| `CoreAgentClient` | 通过 WebClient 调用 agent-core 的 SSE 流式接口 |
| `GatewaySessionManager` | 会话管理 |
| `MessageChannel` 接口 | 消息通道统一抽象 |
| `GatewayRateLimitFilter` | 限流过滤 |
| `GatewayAdminAuthFilter` | 认证过滤 |
| `InMemorySessionStore` / `SqliteSessionStore` | 会话存储 |

---

## agent-rule-engine（规则引擎）

基于 `easy-rules-core` 4.1.0 + Spring SpEL（替代不安全的 MVEL）：

| 组件 | 说明 |
|------|------|
| `core/RuleEngine` | 规则引擎门面，支持三阶段：PRE → RUNTIME → POST |
| `core/SpELRule` | Spring Expression Language 规则 |
| `spi/RuleDefinitionProvider` | 规则定义 SPI 扩展点 |
| `model/Rule/RuleType/RulePriority/RuleResult` | 数据模型 |
| `repository/RuleRepository` | 规则仓库 |

三阶段执行：
- **PRE**：权限检查、参数校验
- **RUNTIME**：限流、熔断、运行时约束
- **POST**：审计日志、结果校验

---

## agent-text2sql（SQL生成）

6 步流水线：

| 步骤 | 组件 | 说明 |
|:----:|------|------|
| 1 | `schema/SchemaGenerator` | 生成数据库 Schema |
| 2 | `retrieval/ColumnRetriever` | Milvus 向量检索相关列 |
| 3 | `fewshot/FewShotManager` | 检索相似示例 |
| 4 | `generation/SqlGenerator` | LLM 生成候选 SQL |
| 5 | `alignment/SqlAligner` | SQL 对齐（可选） |
| 6 | `voting/SqlVoter` | 投票选择最优 SQL（可选） |

---

## 模块依赖关系

```
agent-app
    ↓
agent-core ←→ agent-rule-engine
    ↓
agent-gateway → agent-core
```

业务能力通过 MCP 协议接入，不依赖 Java 模块：

```
yunxi-mcp-servers/          ← 独立项目，30+ MCP 服务
├── mcp-nutrition           # 营养数据查询（端口 40602）
├── mcp-database            # 通用数据库查询（端口 40101）
├── mcp-redis               # Redis 操作（端口 40102）
├── mcp-milvus              # 向量检索（端口 40103）
├── mcp-filesystem          # 文件系统（端口 40501）
├── mcp-git                 # Git 操作（端口 40509）
└── ...                     # 30+ 更多 MCP 服务
```

---

## 端口规划

参见 [`docs/端口规划.md`](../端口规划.md) 完整列表。

| 服务 | 端口 | 说明 |
|------|------|------|
| agent-core | 40001 | 核心服务 |
| agent-rule-engine | 40002 | 规则引擎 |
| agent-gateway | 40003 | 网关服务 |
| mcp-nutrition | 40602 | 营养数据 MCP |

---

**上一页**: [04. 架构设计](./04-architecture.md)  
**下一页**: [06. 配置指南 →](./06-configuration.md)
