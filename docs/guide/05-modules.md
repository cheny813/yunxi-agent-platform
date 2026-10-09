# 05. 模块说明

> **⚠️ V2.0 包结构变更**：agent-core 内部包结构已从 `framework/`/`shared/`/`infra/` 三层重构为扁平化的 30 个功能包。以下为更新后的结构。

了解 yunxi Agent Platform 各模块的功能和职责。

## 模块概览

### Maven 模块（根 `pom.xml` 的 `<modules>`，共 11 个）

```
yunxi-agent-platform/
├── agent-codec             # 全平台唯一 JSON 编解码机制（最底层，无内部模块依赖）
├── agent-spi               # SPI 接口定义（最底层抽象）
├── agent-config            # 集中化配置管理
├── agent-text2sql          # 自然语言转 SQL
├── agent-core              # 核心框架（Agent 生命周期、工作区、MCP、意图引擎、轨迹系统）
├── agent-execution         # 统一执行引擎（web / A2A 调用编排）
├── agent-ingress           # 统一接入网关（边缘横切 + 协议服务端接入）
├── agent-aistio            # aistio 控制面 / 数据面集成（依赖 ${agentscope.version}=2.0.4 框架构件）
├── agent-muse              # 自进化引擎（技能沙箱评估→LLM 修补→剪枝合并闭环）
├── agent-app               # 可执行应用打包（统一入口，端口 40001）
└── agent-integration-test  # 跨模块集成测试
```

### 附属工程（非 Maven 模块，独立构建）

```
├── agent-nutritionist-web  # 营养师前端演示（静态页 + 对话 SDK 调用，Vite 构建）
├── agent-web-sdk           # 浏览器端 JS SDK（WebSocket 接入）
└── sdk-js                  # JavaScript/TypeScript SDK（Node.js 接入）
```

> 桌面客户端（`agent-desktop` → `yunxi-claw`）和远程节点（`agent-node` → `yunxi-agent-node`）已拆分为独立工程，详见对应仓库。

### 依赖链

```
agent-codec → agent-spi → agent-config → agent-core
                                          ↑        ↑
                  agent-execution  agent-ingress  agent-muse  agent-aistio（仅依赖 agent-core）
                                          ↓
                          agent-app (聚合 + 启动入口)
```

---

## agent-codec / agent-execution / agent-ingress（编解码、接入与执行）

这三者分别对应平台对外暴露能力的「编解码」「网关」「执行编排」三个横切面，均为 Maven 模块：

| 模块 | 职责 | 说明 |
|------|------|------|
| `agent-codec` | 全平台唯一 JSON 编解码机制 | 唯一绑定配置来源、Redis 值编解码、数据库 JSON 类型处理器、失败计数与启动自检；不依赖任何业务模块，被所有业务模块依赖，是编解码契约的唯一实现位置。详见 [17. 统一 JSON 编解码机制](./17-data-codec.md) |
| `agent-ingress` | 统一接入网关 | 集中承载所有外部 HTTP 入口与边缘横切（认证 / 限流 / 可观测）、协议服务端接入与路由，是平台对外暴露智能体能力的唯一网络边缘。详见 [18. 统一入口网关](./18-ingress-gateway.md) |
| `agent-execution` | 统一执行引擎 | 独立于网关的模块（`io.yunxi.platform.execution`），负责「从 `Agent.streamEvents` 被调用到事件流回吐」之间的业务编排（意图、RAG、记忆构建、权限快照、实体注入、指标、阶段、审计）。详见 [19. 统一执行引擎](./19-execution-engine.md) |

> `agent-ingress` 的 HTTP 边缘由过滤器统一实现；aistio 管控面的 HTTP 边缘（`AistioIngressController`，基址 `/agentscope`）也收编于此模块，但对 `agent-aistio` 保持编译隔离。

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

## agent-muse（自进化引擎）

### 职责

提供技能自进化闭环：沙箱评估→LLM 修补→剪枝合并。严格遵循"薄适配层（thin adapter layer）"原则——只实现 AgentScope 没有的能力，其余全部复用框架。

### 核心组件

| 组件 | 说明 |
|------|------|
| `SelfEvolutionEngine` | 闭环编排：评估→修补→再评估，首轮全通过立即返回，连续无进展提前停止 |
| `DefaultSkillEvaluator` | 沙箱评估器：子进程/Docker 跑 Java 自测，退出码判通过 |
| `SkillRefiner` | LLM 修补器：注入失败用例名+stderr/stdout 到提示词，经 Model.stream 迭代修补 |
| `DefaultSkillPruner` | 技能剪枝：按使用率归档 + CJK 二元切分 TF-IDF 余弦相似度自动合并重复技能 + 画像驱动判据（使用者广度） |
| `UsageProfileAggregator` | 只读聚合调用轨迹与用户画像，产出技能的使用者画像特征（`UsageProfile`），供剪枝参考 |
| `BuiltinSkillLoader` | 内建技能落地：启动时把 classpath 内建技能拷贝到 AgentScope 运行时技能目录 |
| `SkillEvalTool` / `SkillRefineTool` / `SkillPruneTool` | 元工具：把以上能力挂给 LLM，Agent 自主调用自进化 |

### 启用

```yaml
yunxi:
  muse:
    enabled: true
    model: qwen-max
    provider: dashscope
```

未启用时 MUSE 全部 Bean 不加载（`@ConditionalOnProperty`），编译期零引用。

### 设计原则

- **薄适配层（thin adapter layer）**：沙箱、评估、修补、剪枝——全部是 AgentScope 没有的能力，不造框架已有的轮子
- **零侵入**：通过 SPI 接口 + `ObjectProvider` 懒注入，不修改 AgentScope 框架代码
- **升级友好**：内部调用全部走 AgentScope 公开 API，不依赖框架内部实现类
- **自包含**：中文分词（CJK 二元切分）+ TF-IDF + 余弦相似度，零外部依赖
- **画像只读**：剪枝可参考使用者画像（使用者广度维度），但方向严格单向——MUSE 只**读**画像，不回写；画像抽取链路也不读剪枝结果。二者是消费关系，不是双向挂钩

详见 [10. 技能系统 - MUSE 自进化引擎](./10-skills.md#muse-自进化引擎) 和 [agent-muse README](../../agent-muse/README.md)。

---

## agent-core（核心框架）

### agent-core 内部包结构（V2.0 扁平化后）

```
agent-core/src/main/java/io/yunxi/platform/
├── agent/        ← Agent 核心（工厂、网关、装配 AgentConfigurer、工作区）
├── a2a/          ← 跨服务 Agent 协作（A2AServer/A2AClient/A2ARegistry）
├── cache/        ← Redis 缓存
├── config/       ← 配置类（AgentscopeExtensionProperties、Redis 后端等）
├── controller/   ← REST 控制器（Agent/Conversation/Tool/Skill/File/Config）
├── conversation/ ← 对话编排（ChatAppService）
├── desktop/      ← 桌面客户端中继
├── embedding/    ← Embedding 提供商
├── file/         ← 文件处理（上传、向量化入库）
├── framework/    ← 框架基础类（@Configuration 装配入口）
├── gateway/      ← SSE 消息通道
├── intelligent/  ← 智能 LLM 服务（IntelligentLlmService + IntelligentProperties）
├── intent/       ← 意图引擎（IntentProperties、多域 DomainRegistry、rule/llm/hybrid 分类、热更新 reload）
├── knowledge/    ← （空目录，V2.0 已弃用；RAG 由 rag/ 承担）
├── lifecycle/    ← 生命周期
├── mcp/          ← （空目录，MCP 客户端由 AgentConfigurer 直接构建）
├── memory/       ← 记忆系统（MemoryScene/MemorySceneRegistry）
├── pageagent/    ← 页面 Agent（OpenAI 代理 / 后端 LLM 代理）
├── persistence/  ← 持久化（Milvus + Repository）
├── prompt/       ← 场景检测（SceneDetectionService）
├── rag/          ← 应用层 RAG（ApplicationRAG 中间件工厂）
├── security/     ← 安全（SecurityContext、审计、认证）
├── session/      ← 会话管理
├── shared/       ← 共享 DTO、配置加载（AgentDefinitionLoader）
├── spi/          ← 平台内 SPI 接口
├── structured/   ← Schema 注册
├── sync/         ← 数据同步
├── tool/         ← 自定义工具（@Tool 注解）
└── tracing/      ← 可观测性（OpenTelemetry）
```

### agent/ Agent 核心包

| 组件 | 说明 | 代码量 |
|------|------|:-----:|
| `AgentExecutionEngine` | **统一执行编排入口** — 串联拦截器链（Auth/Memory/Intent/RAG/Audit）与执行策略（阻塞/流式），最终调用 AgentScope 原生 `streamEvents/call` 并适配事件流，薄适配 | ~420行 |
| `AgentService` | Agent 生命周期管理（创建、缓存、获取），通过 HarnessAgent 包装 | ~320行 |
| `AgentConfigurer` | **Agent 自动装配引擎** — 启动时两轮初始化：独立 Agent → 编排 Agent（supervisor/pipeline/routing） | 约 1261 行 |
| `TempAgentFactory` | 临时 Agent 创建工厂（原名 AdvancedAgentFactory） | - |
| `ProfileRouter` | Profile 路由：agentName + profile → Agent 实例 | - |
| `ModelFactory` | 统一模型工厂，复用框架内置 Provider | - |

扩展点（agent/middleware/）：
- `ContentFilterMiddleware` — 提示注入防护（HITL 安全护栏，平台自建）
- `OtelTracingMiddleware` — OpenTelemetry 链路追踪（框架原生，复用全局 OpenTelemetry SDK，取代早期自研 `ReActSpanMiddleware`）
- 优雅关闭由框架内置 `GracefulShutdownMiddleware` 自动注册，无需平台实现

> 说明：上层 Hook 体系已全面迁移为 AgentScope 原生 Middleware 体系；原 `ToolGate`/`ReasoningReview`/`TextToolCallParser` 等自建 Middleware 已在 AgentScope-Java 2.0 升级中移除，其能力由框架原生机制（如 `PermissionContextState` 的 ASK 规则、HITL 配置链）承接。

#### 工具体系（tool/）

平台内置以下工具实现类，直接通过 AgentScope 框架的 `@Tool` 注解注册供 Agent 调用：

| 组件 | 说明 |
|------|------|
| `DatabaseTool` | 数据库查询工具（JDBC） |
| `HttpTool` | HTTP 请求工具（GET/POST） |
| `CalculatorTool` | 数学计算工具 |
| `NodeTool` | 节点/任务操作工具 |
| `ShellToolFactory` | Shell 命令执行工具工厂 |
| `SessionSearchTool` | 会话搜索工具 |

> 说明：工具通过 Spring `@Component` + `@Tool` 注解直接注册到 AgentScope 框架的 Toolkit，**无需**额外的 `Tool` 接口、`ToolAdapter` 桥接类或本地 `ToolRegistry`。MCP 工具同样由 AgentScope 框架原生 `McpClientBuilder` + `Toolkit.registration().mcpClient()` 注册。

#### MCP 协议（mcp/）

MCP 工具由 AgentScope 框架原生管理：`AgentConfigurer.buildMcpClient()` 通过 `McpClientBuilder` 连接 MCP 服务器，`ReconnectingMcpClientWrapper` 提供断线重连兜底。无需独立的 yunxi MCP 注册器。运行时动态注册通过 `McpController` 暴露 REST API（`GET /api/mcp/servers`、`POST /api/mcp/servers?name=`、`DELETE /api/mcp/servers/{name}`），由 `McpConfigStore` + Nacos 配置中心协调底座持久化并跨实例广播，无需重启即可接入新的 MCP 服务。

#### 其他框架组件

| 组件 | 说明 |
|------|------|
| `a2a/` | 跨服务 Agent 协作协议（A2AServer/A2AClient/A2ARegistry） |
| `memory/` | 记忆系统（MemoryScene/MemorySceneRegistry + Harness 内置文件系统记忆） |
| `skill/`（无独立包） | 技能系统由 AgentScope 原生 `AgentSkillRepository`（文件系统 + 项目级全局目录）管理，由框架 `DynamicSkillMiddleware` 自动装载 |
| `conversation/` | 对话编排（ChatAppService） |
| `intelligent/` | 智能 LLM 服务（IntelligentLlmService + IntelligentProperties + IntelligentAutoConfiguration） |
| `workspace/`（无独立包） | 多租户运行时隔离（AgentScope-Java 2.0 原生 `HarnessAgent.workspaceFor(userId, sessionId)` 按用户命名空间隔离工作空间与 AgentState 会话槽） |
| `session/` | 会话管理 |
| `sync/` | 数据同步引擎（MySQL → Milvus） |
| `rag/` | 应用层 RAG（ApplicationRAG 中间件工厂，FileVectorService 检索） |
| `pageagent/` | 页面 Agent（OpenAI 代理 / 后端 LLM 代理；前端填表执行已迁移至 `agent-web-sdk` 的 `PageAgentDomEngine`） |
| `security/` | 安全（SecurityContext 用户认证、审计、HITL 权限配置） |
| `embedding/` | 嵌入模型（DashScopeProvider/OpenAIProvider/BaiduProvider/HuaweiProvider/ClaudeProvider） |
| `intent/` | 意图引擎（多域 DomainRegistry + rule/llm/hybrid 分类 + reload 热更新，IntentProperties + IntentAwareAgentResolver 路由） |
| `controller/` | REST 控制器（Agent/Conversation/Tool/SkillManagement/FileUpload/ConfigManagement/McpController 动态注册） |

> **说明**：`knowledge/` 与 `mcp/` 目录在 V2.0 已清空（知识库创建器与自建 MCP 客户端已删除），对应能力分别由 `rag/`（ApplicationRAG）与 AgentScope 框架原生 `McpClientBuilder` + `Toolkit.registration().mcpClient()` 承担。

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

## agent-aistio（aistio 控制面集成）

### 职责

承载 yunxi 与 aistio 管控面的全部对接，是**传输层 / 外部集成**，不携带任何推理语义：

- **注册与心跳**：`AistioGrpcConnector` 复用 AgentScope-Java 框架 `Aistio.instrument(agent, AistioConfig, adapter)` 完成自注册、心跳与 gRPC 对话流；yunxi 不自建注册与心跳调度。
- **编排触发器 / 导出器**：`WebhookOrchestrationTrigger`、`OrchestrationTranslator`、`WorkflowArtifactExporter` 把 yunxi 的编排 manifest 与控制面对齐。
- **契约与治理**：`AistioDataPlaneContract`（实现 `agent-core` 的 `DataPlaneContract` 接口，委托 `GovernanceService` / `WorkspaceInventoryService` / `SessionInventoryService` 提供数据面视图）；HTTP 边缘由 `agent-ingress` 的 `AistioIngressController` 收编（基址 `/agentscope`），ingress 对 `agent-aistio` 保持编译隔离。
- **任务接活**：`AistioTaskBridge` 把控制面下推的 `AgentTaskAssignment` 转交框架官方 `HarnessAgentTaskStarter`，yunxi 不另研执行器。

### 包结构

```
agent-aistio/src/main/java/io/yunxi/platform/aistio/
├── AistioAutoConfiguration.java     # 自动装配入口（经主类组件扫描 io.yunxi.platform 装载）
├── AistioGrpcConnector.java         # gRPC 数据面连接器
├── YunxiFrameworkAdapter.java       # 框架适配桥
├── AistioIntegrationProperties.java # yunxi.aistio.* 配置绑定
├── client/ controller/ dto/ interceptor/ orchestration/ registrar/ registry/ reporter/ service/ task/
```

> **启用开关**：`yunxi.aistio.enabled`（默认 `false`，符合「零新增基建、不侵入主线」原则）。关闭时 `AistioAutoConfiguration` 不装配，控制面相关能力整体离线，不影响其它模块。

### 与 agent-core 的依赖边界（单一方向）

`agent-aistio` **只依赖 `agent-core`**，反向不允许（否则形成 Maven 循环依赖）。唯一需要共享的状态——`ActiveSessionRegistry`（活跃会话索引，由对话生命周期写入、控制面治理读取）——已下沉为 `agent-core` 的 `io.yunxi.platform.registry.ActiveSessionRegistry` 常驻 Bean，aistio 侧仅注入使用，不持有其定义。除该索引外，`agent-core` 不得再引用 `agent-aistio` 任何类。

### 装配

`agent-aistio` 是库模块，由 `agent-app` 引入后，其 `@Configuration` / `@Component` 经主类 `scanBasePackages = io.yunxi.platform` 自动扫描装载，无需在 `agent-app` 增加任何扫描配置。

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
agent-core
```

业务能力通过 MCP 协议接入，不依赖 Java 模块：

```
yunxi-mcp-servers/          ← 独立项目，30+ MCP 服务
├── mcp-data                # 业务数据查询（端口 40602）
├── mcp-database            # 通用数据库查询（端口 40101）
├── mcp-redis               # Redis 操作（端口 40102）
├── mcp-milvus              # 向量检索（端口 40103）
├── mcp-filesystem          # 文件系统（端口 40501）
├── mcp-git                 # Git 操作（端口 40509）
└── ...                     # 30+ 更多 MCP 服务
```

---

## 端口规划

平台各服务默认占用端口如下（均可在对应配置文件或启动参数中覆盖）：

| 服务 | 端口 | 说明 |
|------|------|------|
| agent-app | 40001 | 统一入口（可执行服务，内含 agent-core 核心框架） |
| aistio 管控面 | 8081 / 18080 | 可选，见 [08. 部署运维](./08-deployment.md) |
| mysql | 3306 | 业务数据（`agent-config` 中配置） |
| redis | 6379 | 会话 / 缓存 / 轨迹存储（见 [06. 配置指南](./06-configuration.md)） |
| nacos | 8848 | 配置中心（MCP 动态注册底座） |
| milvus | 19530 | 向量检索 |
| MCP 服务组 | 40101-40103、40501-40509、40602 | `yunxi-mcp-servers` 仓库提供 |
| OTLP 接收端 | 4318 | OpenTelemetry 导出目标（可选） |

---

**上一页**: [04. 架构设计](./04-architecture.md)  
**下一页**: [06. 配置指南 →](./06-configuration.md)
