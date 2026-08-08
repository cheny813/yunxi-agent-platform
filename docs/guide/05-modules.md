# 05. 模块说明

> **⚠️ V2.0 包结构变更**：agent-core 内部包结构已从 `framework/`/`shared/`/`infra/` 三层重构为扁平化的 13 个功能包。以下为更新后的结构。

了解 yunxi Agent Platform 各模块的功能和职责。

## 模块概览

```
yunxi-agent-platform/
├── agent-spi               # SPI 接口定义（最底层抽象）
├── agent-config            # 集中化配置管理
├── agent-core              # 核心框架（Agent 生命周期、工作区、MCP、同步引擎、AgentScope-Java 2.0GA Channel 网关接入）
├── agent-muse              # 自进化引擎（技能沙箱评估→LLM 修补→剪枝合并闭环）
├── agent-text2sql          # 自然语言转 SQL
├── agent-app               # 可执行应用打包
└── agent-integration-test  # 跨模块集成测试
```

> 桌面客户端（`agent-desktop` → `yunxi-claw`）和远程节点（`agent-node` → `yunxi-agent-node`）已拆分为独立工程，详见对应仓库。

### 依赖链

```
agent-spi → agent-config → agent-core
                                ↑
                    agent-muse  agent-text2sql
                                ↓
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

## agent-muse（自进化引擎）

### 职责

提供技能自进化闭环：沙箱评估→LLM 修补→剪枝合并。严格遵循"薄适配层（thin adapter layer）"原则——只实现 AgentScope 没有的能力，其余全部复用框架。

### 核心组件

| 组件 | 说明 |
|------|------|
| `SelfEvolutionEngine` | 闭环编排：评估→修补→再评估，首轮全通过立即返回，连续无进展提前停止 |
| `DefaultSkillEvaluator` | 沙箱评估器：子进程/Docker 跑 Java 自测，退出码判通过 |
| `SkillRefiner` | LLM 修补器：注入失败用例名+stderr/stdout 到提示词，经 Model.stream 迭代修补 |
| `DefaultSkillPruner` | 技能剪枝：按使用率归档 + CJK 二元切分 TF-IDF 余弦相似度自动合并重复技能 |
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

详见 [10. 技能系统 - MUSE 自进化引擎](./10-skills.md#muse-自进化引擎) 和 [agent-muse README](../../agent-muse/README.md)。

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
├── pageagent/    ← 页面 Agent（OpenAI 代理 / 后端 LLM 代理；纯后端，无前端决策循环）
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
| `TempAgentFactory` | 临时 Agent 创建工厂（原名 AdvancedAgentFactory） | - |
| `ProfileRouter` | Profile 路由：agentName + profile → Agent 实例 | - |
| `ModelFactory` | 统一模型工厂，复用框架内置 Provider | - |

扩展点（agent/middleware/）：
- `ContentFilterMiddleware` — 提示注入防护（HITL 安全护栏，平台自建）
- `ReActSpanMiddleware` — OpenTelemetry 链路追踪（平台自建，实现 `MiddlewareBase`）
- 优雅关闭由框架内置 `GracefulShutdownMiddleware` 自动注册，无需平台实现

> 说明：上层 Hook 体系已全面迁移为 AgentScope 原生 Middleware 体系；原 `ToolGate`/`ReasoningReview`/`TextToolCallParser` 等自建 Middleware 已在 AgentScope-Java 2.0GA 升级中移除，其能力由框架原生机制（如 `PermissionContextState` 的 ASK 规则、HITL 配置链）承接。

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

MCP 工具由 AgentScope 框架原生管理：`AgentConfigurer.buildMcpClient()` 通过 `McpClientBuilder` 连接 MCP 服务器，`ReconnectingMcpClientWrapper` 提供断线重连兜底。无需独立的 yunxi MCP 注册器。

#### 其他框架组件

| 组件 | 说明 |
|------|------|
| `a2a/` | 跨服务 Agent 协作协议（A2AServer/A2AClient/A2ARegistry） |
| `memory/` | 记忆系统（MemoryRecord/MemoryScene/MemorySceneRegistry + Harness 内置记忆） |
| `skill/` | 技能系统由 AgentScope 原生 `AgentSkillRepository`（文件系统 + 项目级全局目录）管理，由框架 `DynamicSkillMiddleware` 自动装载 |
| `conversation/` | 对话编排（ChatAppService） |
| `intelligent/` | 智能 LLM 服务（IntelligentLlmService + IntelligentProperties + IntelligentAutoConfiguration） |
| `workspace/` | 多租户运行时隔离（AgentScope-Java 2.0GA 原生 `HarnessAgent.workspaceFor(userId, sessionId)` 按用户命名空间隔离工作空间与 AgentState 会话槽） |
| `session/` | 会话管理 |
| `sync/` | 数据同步引擎（MySQL → Milvus） |
| `plan/` | PlanNotebook 持久化、计划模板 |
| `profile/` | 职业画像、概念注册 |
| `pageagent/` | 页面 Agent（OpenAI 代理 / 后端 LLM 代理；前端填表执行已迁移至 `agent-web-sdk` 的 `PageAgentDomEngine`） |
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

参见 [`docs/端口规划.md`](../端口规划.md) 完整列表。

| 服务 | 端口 | 说明 |
|------|------|------|
| agent-core | 40001 | 核心服务 |
| mcp-data | 40602 | 业务数据 MCP |

---

**上一页**: [04. 架构设计](./04-architecture.md)  
**下一页**: [06. 配置指南 →](./06-configuration.md)
