# 18. 统一入口网关

yunxi 以「统一入口网关」作为平台对外暴露智能体能力的**唯一网络边缘**。所有外部 HTTP 流量——对话调用、管理操作、健康探针——都先经过网关，再被路由到 AgentScope-Java 运行时内的 Agent。本章说明网关的分层设计、职责边界，以及它与底层框架的协作方式，帮助架构师与开发者理解请求从进入平台到抵达 Agent 的完整路径。

## 18.1 设计目标

- **只做边缘，不做推理**：网关负责终结协议、完成认证与身份解析、做路由与限流，但**不承载任何推理语义**。真正的推理 100% 发生在 AgentScope-Java 运行时，表现为 Middleware / Tool / ModelProvider / Store / Formatter 等原生扩展件。
- **框架已提供的协议能力直接复用**：不重造协议服务端，而是注册少量适配器，让框架原生的协议端点带上平台身份。
- **协议无关的横切只写一份**：凡是所有调用协议都要统一生效的能力（内容安全、用户画像注入、追踪、记忆压缩），实现为框架 Middleware，由 Agent 装配点统一挂到每个 Agent 上。
- **只服务于自有协议的编排保留在引擎**：仅服务于 web / A2A 这类自有协议、且消费 web 专属请求结构的编排逻辑，保留在[统一执行引擎](19-execution-engine.md)的拦截器链中。

## 18.2 目标分层（从外到内）

```
外部客户端
   │  HTTP / SSE
   ▼
┌─────────────────────────────────────────────────┐
│ ① 传输层        Spring Boot HTTP 服务器          │
├─────────────────────────────────────────────────┤
│ ② 边缘横切层    单一过滤器链（对所有端点统一）    │
│    AuthN → 身份解析 → 路由 → 限流 → 可观测      │
├─────────────────────────────────────────────────┤
│ ③ 协议服务端    AgentScope-Java 协议 Starter      │
│    AG-UI / A2A / Chat Completions               │
├─────────────────────────────────────────────────┤
│ ④ 身份钩子      Resolver 装配 RuntimeContext     │
├─────────────────────────────────────────────────┤
│ ⑤ Agent 装配    AgentConfigurer 声明 Agent bean   │
│                 并注册到框架 Agent registry       │
├─────────────────────────────────────────────────┤
│ ⑥ 管理平面      agent / MCP / skill / trace /    │
│                 file / userProfile（独立包）     │
└─────────────────────────────────────────────────┘
```

- **① 传输层**：标准 Spring Boot HTTP 服务器，负责 TLS、请求解析等底层网络能力。
- **② 边缘横切层**：以单个过滤器对所有端点统一执行认证、身份解析、路由转发、限流、可观测。这是「边缘」层，不进入运行时推理。
- **③ 协议服务端**：由 AgentScope-Java 各协议 Starter 提供（AG-UI、A2A、Chat Completions 的 HTTP 端点与 SSE 生命周期）。平台不重造协议层，而是注册少量适配器让框架的协议端点带上平台身份。
- **④ 身份钩子**：平台提供 Resolver 实现，框架在收到请求时调用它把解析出的用户标识装配进 `RuntimeContext`，再交给 Agent。
- **⑤ Agent 装配**：`AgentConfigurer` 读取 Agent 定义，声明 Agent bean 并注册到框架的 Agent registry；框架 Starter 据此自动暴露协议端点。Agent 的权威来源是框架 registry，而非平台自建门面。
- **⑥ 管理平面**：与调用路径解耦的独立包，提供 Agent / MCP / skill / trace / file / userProfile 等管理端点。

## 18.3 为什么不再保留早期的自建门面

早期版本曾尝试自建 `AgentRuntime` 门面与协议适配层。源码核实后确认：框架 Starter 是闭环的 Controller，直接调用 `Agent.streamEvents`，并不经过平台门面；自建门面只是叠在对话服务之上的透传层，被框架 Starter 绕过，属于冗余。因此当前架构**撤销自建门面**，改为：

- 协议服务端直接使用框架 Starter（框架已提供的协议能力直接复用）；
- 边缘横切用平台自己的传输层过滤器实现；
- 身份注入通过平台提供的 Resolver 接入框架；
- 调用接缝统一为框架的 `Agent.streamEvents`。

## 18.4 职责归属（公开视角）

| 关注点 | 实现位置 |
| --- | --- |
| 协议服务端（AG-UI / A2A / Chat Completions） | AgentScope-Java 协议 Starter |
| 边缘认证 / 限流 / 可观测 | 平台传输层过滤器（agent-ingress） |
| 身份解析与注入 | 平台提供 Resolver，喂给框架 RuntimeContext |
| Agent 装配与权威 | 框架 Agent registry（由 AgentConfigurer 声明） |
| 协议无关横切（内容安全 / 画像 / 追踪 / 记忆压缩） | 框架 Middleware（由 AgentConfigurer 统一装配） |
| 协议相关编排（意图 / RAG / 记忆构建） | 统一执行引擎（仅 web / A2A 生效） |
| 管理平面端点 | 平台独立包 |

## 18.5 多协议支持

网关借助框架 Starter 同时暴露三类协议：

- **AG-UI**：面向有状态 UI 的流式协议，支持长时间运行的对话与中途人机确认。
- **A2A**：智能体之间的互操作协议，用于跨智能体委派任务。
- **Chat Completions**：OpenAI 兼容的无状态对话接口，便于既有客户端接入。

三类协议共用同一条 Agent 装配链与身份注入逻辑，确保「对所有协议统一生效」的目标。

## 18.6 与执行引擎的边界

网关的工作在「解析出 agentId / RuntimeContext / 输入消息」后即结束，随后把控制权交给 Agent。两者的接缝是：

```
Agent.streamEvents(RuntimeContext, List<Msg>)
```

网关之后的业务逻辑编排（意图识别、RAG 检索、记忆构建、权限快照、实体注入、指标、阶段状态、审计、事件增强）由[第 19 章 统一执行引擎](19-execution-engine.md)承接。

## 18.7 延伸阅读

- 架构设计文档：[`../unified-ingress-gateway-architecture.md`](../unified-ingress-gateway-architecture.md)（含完整的分层裁决与职责归属论证）
