# 17. 统一执行引擎

运行时执行能力由 `yunxi-runtime` 的 `ExecutionFacade`（统一执行门面）与 `yunxi-ingress` 的 `ProtocolAdapter`（协议适配）共同承担，负责「从 `Agent.streamEvents` 被调用的那一刻起，到事件流回吐为止」之间的业务编排。它让平台在不修改底层框架的前提下，统一了 web / A2A / AG-UI / Chat Completions 四种协议的调用前 / 后处理。本章说明执行层与网关的边界、核心分层裁决、内部组件与阶段状态机。

## 17.1 与网关的边界

- **网关**（`yunxi-ingress`，`ConversationController`/`IngressGateway`/`ProtocolAdapter`）负责：协议终结、边缘认证、身份解析与注入、路由、协议请求↔`AgentCommand` 互译。
- **执行层**（`yunxi-runtime` 的 `ExecutionFacade`）负责：Agent 注册查找、上下文富化、驱动 `agent.streamEvents`、会话轮次闸门、事件流规范化。
- **接缝**：`ExecutionFacade.execute(AgentCommand)` → `Flux<AgentEvent>`。

## 17.2 核心分层裁决

把引擎的工作分成两类，分别落到正确的位置：

- **协议无关横切** → 实现为框架 Middleware，由 `AgentConfigurer` 统一挂到每个 Agent，对全部协议（含框架 Starter 直接服务的 AG-UI / A2A / Chat Completions）统一生效。例如内容安全、用户画像注入、追踪、记忆压缩、HITL、限流。
- **协议相关适配** → 由 `yunxi-ingress` 的 `ProtocolAdapter`（`WebProtocolAdapter`）承担，把 web 专属请求（如 `contextData` / `confirmResults`，来自 `ConversationRequest`）译为运行时 `AgentCommand`；A2A / AG-UI / Chat Completions 由框架 Starter 直接服务，各自构建请求调用 `Agent.streamEvents`，不经由 yunxi 的 `ProtocolAdapter`。

之所以把协议相关部分留在入口网关而非下沉为 Middleware，是因为框架 Starter 是闭环的 Controller，直接调用 `Agent.streamEvents`，不会产出 web 专属的请求对象；而意图、RAG、记忆构建等协议相关逻辑消费的是 web / A2A 专属请求结构，只能由协议适配器消费。这与「协议无关横切用 Middleware」并不矛盾，而是两层、两个不同关切。

## 17.3 责任切分（公开视角）

| 关注点 | 实现位置 |
| --- | --- |
| 认证 / 限流 / 边缘可观测 | 网关（见[第 16 章](16-ingress-gateway.md)） |
| 意图识别、RAG 检索、记忆构建、权限快照、实体注入 | 框架 Middleware（由 `AgentConfigurer` 统一装配，onSystemPrompt / onAgent） |
| 指标、审计 | 框架 Middleware（onAgent 收尾） |
| 阶段状态（agent_status） | ingress 协议适配器基于 `AgentEvent` 流推导 |
| 调用策略 quick / standard / deep | RuntimeContext 字段（由运行时写入，供 Agent 读取） |
| 事件序列化 | 协议服务端自带，或 `ProtocolAdapter.toResponse` 回写 |

## 17.4 模块结构

执行层按职责分为两类组件：

| 组件 | 模块 | 职责 |
| --- | --- | --- |
| `ExecutionFacade` / `DefaultExecutionFacade` | yunxi-runtime | 统一执行门面：经 `AgentLookup` 取已装配 Agent → 富化 `RuntimeContext` → `agent.streamEvents`，管理 `SessionTurnGate` |
| `AgentLookup` / `FrameworkAgentLookup` | yunxi-runtime / yunxi-app | 已装配 Agent 查询（按 name）；`FrameworkAgentLookup` 是薄委托 seam，收口到框架协议层 `AguiAgentRegistry`（yunxi 不维护第二份注册表） |
| `RuntimeContextEnricher` / `RuntimeContextEnrichers` | yunxi-runtime | 每个调用的 `RuntimeContext` 富化（身份 + 业务上下文） |
| `AgentCommand` / `Settle` | yunxi-runtime | 运行时契约：执行命令与结算终态 |
| `ConversationController` / `IngressGateway` | yunxi-ingress | HTTP 入口与协议路由 |
| `ProtocolAdapter` / `WebProtocolAdapter` | yunxi-ingress | web 协议请求↔`AgentCommand` 互译、阶段状态推导（yunxi 仅 `WebProtocolAdapter`；A2A / AG-UI / Chat Completions 由框架 Starter 直接服务，各自构建请求调用 `Agent.streamEvents`，不经由 yunxi 的 `ProtocolAdapter`） |
| 协议无关 Middleware（ChatAudit / Hitl / OtelTracing / Resilience / SessionToolGroupActivation） | yunxi-runtime.middleware | 对所有协议统一生效的横切 |

## 17.5 装配点

协议无关横切的 Middleware 由 `AgentConfigurer` 在 `configureMiddlewares` 中按既定顺序挂到每个 Agent 上，确保 web / A2A / AG-UI / Chat Completions 四种协议都经过同一条统一装配链与身份注入。

## 17.6 阶段状态机

阶段状态（`agent_status`）由 `yunxi-ingress` 的协议适配器基于框架 `AgentEvent` 流推导，只在阶段切换时下发一次，避免每个事件重复广播：

| 触发事件 | 阶段 |
| --- | --- |
| Agent 进入执行 | scan |
| 回复开始 / 增量 / 结束 | reply |
| 工具调用开始 / 参数增量 / 结束 | tool |
| 执行结束（StreamCompletedEvent / StreamFailedEvent） | end |
| REQUEST_STOP / ALL_TOOLS_DENIED | end |

## 17.7 与底层框架的集成

引擎不重造框架已有的能力：内容过滤、人机确认（HITL）、分布式追踪、记忆压缩等都直接复用 AgentScope-Java 的原生实现。这些 Middleware 的装配顺序敏感（如审计需在收尾、画像演进需在审计之后），统一由 `AgentConfigurer` 控制顺序。

## 17.8 延伸阅读

- 模块与组件总览：[`05-modules.md`](./05-modules.md)（含 `yunxi-runtime` / `yunxi-ingress` 组件归属）
