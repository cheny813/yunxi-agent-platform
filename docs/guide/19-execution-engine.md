# 19. 统一执行引擎

统一执行引擎是独立于入口网关的模块（包 `io.yunxi.platform.execution`），负责「从 `Agent.streamEvents` 被调用的那一刻起，到事件流回吐为止」之间的所有业务编排。它让平台在不修改底层框架的前提下，统一了 web / A2A 自有协议的调用前 / 后处理。本章说明引擎与网关的边界、核心分层裁决、内部组件与阶段状态机。

## 19.1 与网关的边界

- **网关**负责：协议终结、边缘认证、身份解析与注入、路由。
- **执行引擎**负责：意图管道、RAG 检索、记忆构建、权限快照、实体注入、指标、阶段状态、审计、事件增强。
- **接缝**：`Agent.streamEvents(RuntimeContext, List<Msg>)`。

## 19.2 核心分层裁决

把引擎的工作分成两类，分别落到正确的位置：

- **协议无关横切** → 实现为框架 Middleware，由 Agent 装配点统一挂到每个 Agent，对全部协议（含框架 Starter 直接服务的 AG-UI / A2A / Chat Completions）统一生效。例如内容安全、用户画像注入、追踪、记忆压缩。
- **协议相关编排** → 保留在引擎的 `ExecutionInterceptor` 链中，只服务于 web / A2A 自有协议。

之所以要把协议相关部分留在引擎而非下沉为 Middleware，是因为框架 Starter 是闭环的 Controller，直接调用 `Agent.streamEvents`，不会产出 web 专属的请求对象；而意图、RAG、记忆构建等逻辑消费的是 web / A2A 专属请求结构，无法表达为对所有协议通用的 Middleware。这与「协议无关横切用 Middleware」并不矛盾，而是两层、两个不同关切。

## 19.3 责任切分（公开视角）

| 关注点 | 实现位置 |
| --- | --- |
| 认证 / 限流 / 边缘可观测 | 网关（见[第 18 章](18-ingress-gateway.md)） |
| 意图识别、RAG 检索、记忆构建、权限快照、实体注入 | 引擎 Middleware（onSystemPrompt / onAgent） |
| 指标、阶段状态、审计 | 引擎 Middleware（订阅事件流 / onAgent 收尾） |
| 调用策略 quick / standard / deep | RuntimeContext 字段（由引擎写入，供 Agent 读取） |
| 事件序列化 | 协议服务端自带，或平台自有 SSE 端点的适配器 |

## 19.4 模块结构

引擎内部按职责分为若干组件：

| 组件 | 职责 | 装配方式 |
| --- | --- | --- |
| 意图 / RAG / 消息构建 / Agent 解析 | 消费 web / A2A 专属请求，构建输入与上下文 | 引擎 `ExecutionInterceptor` 链（非框架 Middleware） |
| `PermissionSnapshotMiddleware` | 每次调用把最新权限模板注入会话状态 | 框架 Middleware，onAgent |
| `MetricsMiddleware` | 上报调用与 token 指标 | 框架 Middleware，onReasoning / onActing |
| `PhaseMiddleware` | 推导并上报阶段状态 | 框架 Middleware，onReasoning |
| `AuditMiddleware` | 调用收尾审计 | 框架 Middleware，onAgent |
| `ExecutionContext` | 贯穿一次调用的上下文载体 | —— |
| `SseAgentEventAdapter` | 仅平台自有 SSE 端点的事件序列化 | 引擎组件 |

## 19.5 装配点

协议无关横切的 Middleware 由 `AgentConfigurer` 在 `configureMiddlewares` 中按既定顺序挂到每个 Agent 上，确保 web / A2A / AG-UI / Chat Completions 四种协议都经过同一条统一装配链与身份注入。

## 19.6 阶段状态机（PhaseMiddleware）

`PhaseMiddleware` 根据事件类型推导当前阶段，只在阶段切换时下发一次 `agent_status`，避免每个事件重复广播：

| 触发事件 | 阶段 |
| --- | --- |
| `AGENT_START` | scan |
| `REPLY_START` / `REPLY_TEXT_DELTA` / `REPLY_END` | reply |
| `TOOL_CALL_START` / `TOOL_CALL_ARGS_DELTA` / `TOOL_CALL_END` | tool |
| `AGENT_END` | end |
| `REQUEST_STOP` / `ALL_TOOLS_DENIED` | end |

## 19.7 与底层框架的集成

引擎不重造框架已有的能力：内容过滤、人机确认（HITL）、分布式追踪、记忆压缩等都直接复用 AgentScope-Java 的原生实现。这些 Middleware 的装配顺序敏感（如审计需在收尾、画像演进需在审计之后），统一由 `AgentConfigurer` 控制顺序。

## 19.8 延伸阅读

- 架构设计文档：[`../temp/agent-execution-engine-design.md`](../temp/agent-execution-engine-design.md)（含引擎分层裁决的完整论证，以及与不同协议服务端的衔接细节）
