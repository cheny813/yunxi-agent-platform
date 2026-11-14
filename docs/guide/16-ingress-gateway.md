# 16. 统一入口网关

yunxi 以「统一入口网关（`yunxi-ingress`）」作为 **web / AG-UI / A2A / Chat Completions 四类 web 级协议**的统一网络边缘；IM 渠道（钉钉 / 企业微信 / 飞书 / GitHub / GitLab / 微信个人号 iLink）与编程式 ChatUi 渠道走**第二条入口路径**（见 16.8），由 AgentScope-Java 的 `GatewayBootstrap` 接入框架 `HarnessGateway`。两条路径最终都收敛到框架 `HarnessAgent`，差异只在协议终结位置。本章说明网关的分层设计、职责边界，以及它与底层框架的协作方式，帮助架构师与开发者理解请求从进入平台到抵达 Agent 的完整路径。

## 16.1 设计目标

- **只做边缘，不做推理**：网关负责终结协议、完成认证与身份解析、做路由与限流，但**不承载任何推理语义**。真正的推理 100% 发生在 AgentScope-Java 运行时，表现为 Middleware / Tool / ModelProvider / Store / Formatter 等原生扩展件。
- **框架已提供的协议能力直接复用**：不重造协议服务端，而是注册少量适配器，让框架原生的协议端点带上平台身份。
- **协议无关的横切只写一份**：凡是所有调用协议都要统一生效的能力（内容安全、用户画像注入、追踪、记忆压缩），实现为框架 Middleware，由 Agent 装配点统一挂到每个 Agent 上。
- **只服务于自有协议的编排保留在引擎**：仅服务于 web / A2A 这类自有协议、且消费 web 专属请求结构的编排逻辑，保留在[统一执行引擎](17-execution-engine.md)的拦截器链中。

## 16.2 目标分层（从外到内）

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

## 16.3 协议入口的实现方式

框架 Starter 是闭环的 Controller，直接调用 `Agent.streamEvents`。平台的协议入口按以下方式组织：

- 协议服务端直接使用框架 Starter（框架已提供的协议能力直接复用）；
- 边缘横切用平台自己的传输层过滤器实现；
- 身份注入通过平台提供的 Resolver 接入框架；
- 调用接缝统一为框架的 `Agent.streamEvents`。

## 16.4 职责归属（公开视角）

| 关注点 | 实现位置 |
| --- | --- |
| 协议服务端（AG-UI / A2A / Chat Completions） | AgentScope-Java 协议 Starter |
| 边缘认证 / 限流 / 可观测 | 平台传输层过滤器（yunxi-ingress） |
| 身份解析与注入 | 平台提供 Resolver，喂给框架 RuntimeContext |
| Agent 装配与权威 | 框架 Agent registry（由 AgentConfigurer 声明） |
| 协议无关横切（内容安全 / 画像 / 追踪 / 记忆压缩） | 框架 Middleware（由 AgentConfigurer 统一装配） |
| 协议相关编排（意图 / RAG / 记忆构建） | 统一执行引擎（仅 web / A2A 生效） |
| 管理平面端点 | 平台独立包 |

## 16.5 多协议支持

网关借助框架 Starter 同时暴露三类协议：

- **AG-UI**：面向有状态 UI 的流式协议，支持长时间运行的对话与中途人机确认。
- **A2A**：智能体之间的互操作协议，用于跨智能体委派任务。
- **Chat Completions**：OpenAI 兼容的无状态对话接口，便于既有客户端接入。

三类协议共用同一条 Agent 装配链与身份注入逻辑，确保「对所有协议统一生效」的目标。

## 16.6 与执行引擎的边界

网关的工作在「解析出 agentId / RuntimeContext / 输入消息」后即结束，随后把控制权交给 Agent。两者的接缝是：

```
Agent.streamEvents(RuntimeContext, List<Msg>)
```

网关之后的业务逻辑编排（意图识别、RAG 检索、记忆构建、权限快照、实体注入、指标、阶段状态、审计、事件增强）由[第 17 章 统一执行引擎](17-execution-engine.md)承接。

## 16.8 IM 渠道网关（GatewayBootstrap）

除上文四类 web 级协议由 `yunxi-ingress` 承接外，IM 渠道与编程式 ChatUi 渠道走**第二条入口路径**：由 `yunxi-app` 经 AgentScope-Java 的 `GatewayBootstrap` 接入框架 `HarnessGateway` + `ChannelManager`。当前双网关并存，两条路径最终都收敛到框架 `HarnessAgent`，差异只在协议终结位置（web 协议由 yunxi-ingress 的框架 Starter 终结；IM 渠道由框架 `Channel` 终结回调并翻译入站）。

### 16.8.1 接入形态

- **Webhook 类渠道（钉钉 / 企业微信 / 飞书 / GitHub / GitLab）**：平台回调 → 框架 `*CallbackController` / `*WebhookController`（yunxi 因基包 `io.yunxi` 扫不到 `io.agentscope`，须显式 `@Bean` 暴露）→ 框架 `*InboundMapper` 入站翻译 → `ChannelManager` → `Channel.dispatch` → `YunxiChannelRuntimeContextResolver` 注入 `RuntimeContext`（渠道身份归一，与 AG-UI 的 `AguiRuntimeContextResolver` 同构）→ `HarnessGateway` → 已装配 `HarnessAgent`。出站回复由框架 `*OutboundClient` 经 `ChannelManager.deliver` 主动推送，渠道级验签由框架 `*Crypto` 自包含承担。
- **微信（iLink）长轮询渠道**：无 HTTP 入站端点，由 `WeixinChannel` 长轮询 iLink 服务端拉取消息；凭据为固定 `botToken`（经 `WeixinCredentialProvider.fixed` 注入，非扫码登录）；状态持久化由 `WeixinStateStore` 负责，默认进程内 `inMemory()`，设 `yunxi.im.weixin.state-store=redis` 启用 yunxi 自实现 `RedisWeixinStateStore`（复用平台全局 Redis，重启可恢复 cursor / lease / inbox / context token）。
- **编程式 ChatUi 渠道**：经 `ChannelManager` 注册的无 HTTP 端点编程式渠道，供前端 ChatUi 直接驱动对话。

### 16.8.2 接线点与配置

- 接线集中在 `yunxi-app` 的 `ImChannelGatewayConfig`（`@EventListener(ContextRefreshedEvent)` + `@DependsOn("agentProvisioner")` 保证晚于 Agent 装配；构造 `GatewayBootstrap` 并 `.build().start()` 注册所有启用渠道）。
- 各渠道配置位于统一配置中心的 `config/im.yml`（`yunxi.im.{dingtalk,wecom,feishu,github,gitlab,weixin}.enabled` 默认全关闭，凭据经环境变量注入不进仓库）。
- 回调端点由 `SecurityConfig` 放行 `permitAll`（真实性由框架 `*Crypto` 验签保证）；微信 iLink 无端点，无需放行。

### 16.8.3 鉴权与边界

- 框架回调端点层放行 + 应用层 `*Crypto` 验签两层并存：端点层挡未授权访问，应用层校验平台签名防伪造。
- 显式配置飞书 `verificationToken` 覆盖框架默认 `null` 无鉴权（框架默认关闭鉴权，须平台侧显式开启凭证校验）。

### 16.8.4 缺口（yunxi 产品平面责任，登记）

多渠道统一身份（userKey↔yunxi userId 映射）、消息审计入站/出站留痕、渠道配额、灰度与可观测性——均属上层职责，由 yunxi 后续闭环，不回压框架。

## 16.9 水平分片与跨实例转发

多实例部署时，网关在「路由」环节增加一层**按 Agent 归属的分片**，让同一 `agentId` 的请求始终落在持有该 Agent 的实例，避免每实例全量布建：

- **归属计算**：`AgentShardResolver` 是唯一归属入口。`yunxi.shard.enabled=false`（默认）时装配 `AllOwnsShardResolver`（本实例拥有全部 Agent，行为等同单机）；开启后装配 `NacosBackedShardResolver`，从 `NamingService`（服务名 `yunxi.shard.service-name`，默认 `yunxi-runtime`）发现实例集，每实例映射 `yunxi.shard.virtual-nodes`（默认 160）个虚拟节点构成一致性哈希环，`agentId` 经 MD5 取 long 落环定位 owner，实例增删仅迁移约 1/N 归属。
- **实例注册**：`ShardInstanceRegistrar` 把本实例（`app-host-port` 身份）注册进 Nacos，携带 `baseUrl` metadata，供转发寻址。
- **仅 owner 布建**：`AgentProvisioner` 按 `owns(agentId)` 过滤，非 owner 实例不为该 Agent 创建 HarnessAgent。
- **请求转发**：`AgentForwardingRouter`（实现 `ShardForwarder`）对 non-owned 请求从 owner 实例 metadata 取 `baseUrl`，以 HTTP 转发并透传原始请求体，web 四协议入口（`ConversationController`）统一走这一条路径；超时由 `yunxi.shard.forward.connect-timeout-ms`（默认 3000）与 `read-timeout-ms`（默认 10000）控制。
- **AG-UI 流式**：`AguiForwardingFilter` 在 HTTP 边界拦截 `/agui/run`、`/agui/run/*`，把 owner 实例的 SSE 原始字节直接泵回客户端（不二次封装 `data:` 帧），框架 `AguiRestController` 保持原样——协议适配器不可替换，故转发落在传输层。
- **fail-open**：分片关闭、归属表缺失或 owner 不可达时一律本地兜底执行，不丢请求。

## 16.7 延伸阅读

- 架构设计：[04. 架构设计](./04-architecture.md) 说明了网关在整体分层中的定位与职责边界。
