# 13. A2A 协议

A2A (Agent-to-Agent) 协议实现 Agent 跨服务协作，支持分布式 Agent 架构。yunxi 不重建 A2A 协议语义，而是直接复用 AgentScope-Java 框架原生的 `AgentScopeA2aServer`（包 `io.agentscope.core.a2a.server`），仅在产品平面做薄适配与同端口路由（不重复实现协议语义）。

## 概述

- **复用框架协议**：A2A 的 AgentCard 发布、JSON-RPC 传输、消息编解码、SSE 流式全部由框架 `AgentScopeA2aServer` 承担。
- **同端口路由**：yunxi 在自身 Web 容器（与 `/api/conversations` 等同端口）以 `/a2a/{agentId}` 暴露每个已注册 Agent 的 A2A 端点，不再起独立 HTTP 服务。
- **启动期自动注册**：`AgentProvisioner` 在上下文刷新后遍历全部 `AgentSpec`，为每个 Agent 构建 `AgentScopeA2aServer` 并注册进 yunxi 的 `A2aServerRegistry` 与框架 `AguiAgentRegistry`。

### 架构

```
┌──────────────────────────────────────────────┐
│  yunxi Web 容器（同一端口）                       │
│                                                │
│  /api/conversations/*   ← ConversationController│
│  /a2a/{agentId}        ← A2aController          │
│            │                                   │
│            ▼                                   │
│  A2aServerRegistry (agentId → AgentScopeA2aServer) │
│            │                                   │
│            ▼                                   │
│  AgentScopeA2aServer（框架：AgentCard + JSON-RPC）│
│            │                                   │
│            ▼                                   │
│  HarnessAgentRunner → HarnessAgent.getDelegate()（ReActAgent）│
└──────────────────────────────────────────────┘
```

### 核心组件（yunxi 产品平面）

| 组件 | 位置 | 职责 |
|------|------|------|
| `AgentScopeA2aServer` | 框架 `io.agentscope.core.a2a.server` | A2A 协议服务端：发布 AgentCard、处理 JSON-RPC 调用、SSE 流式 |
| `A2aServerRegistry` | `io.yunxi.app` | 进程内 `agentId → AgentScopeA2aServer` 索引，供 `A2aController` 按 agentId 路由 |
| `A2aController` | `io.yunxi.app` | A2A 协议层 HTTP 入口（`@RequestMapping("${yunxi.a2a.path-prefix:/a2a}")`） |
| `HarnessAgentRunner` | `io.yunxi.app` | 继承框架 `BaseReActAgentRunner`，把 yunxi 构建的 `HarnessAgent` 适配为框架 A2A 执行器（透传 `getDelegate()`） |
| `AgentProvisioner` | `io.yunxi.app` | 启动期为每个 `AgentSpec` 构建 `AgentScopeA2aServer` 并注册；同时注册进 `AguiAgentRegistry` |

> **说明**：yunxi 没有自建 `A2AClient` / `A2AServer` / `A2ARegistry` / `AgentRequest` / `AggregatedResponse` 等类。出站调用远程 Agent 时，应使用标准 A2A JSON-RPC 客户端（如 Google A2A Java SDK 的 `io.a2a.client.A2AClient`）直接请求对端的 `/a2a/{agentId}` 端点；yunxi 当前不在产品平面封装出站 client bean。

## A2A 服务端装配

`AgentProvisioner.provision()`（`@EventListener(ContextRefreshedEvent.class)`，依赖 `agentDefinitionLoader`）对每个 `AgentSpec` 做：

```java
// AgentProvisioner（yunxi-app）节选
HarnessAgent agent = agentConfigurer.configure(spec);
// AG-UI / 单 Agent 真相源
aguiAgentRegistry.register(agentId, agent);
// A2A 协议层：构建框架 AgentScopeA2aServer 并发布 AgentCard
a2aServerRegistry.register(agentId, buildA2aServer(agentId, agent));

private AgentScopeA2aServer buildA2aServer(String agentId, HarnessAgent agent) {
    ConfigurableAgentCard card = new ConfigurableAgentCard.Builder()
            .name(agentId)
            .description(agent.getName())
            .url("http://" + a2aHost + ":" + a2aPort + a2aPathPrefix + "/" + agentId)
            .version("1.0.0")
            .build();
    TransportProperties transport = TransportProperties.builder(TransportProtocol.JSONRPC.asString())
            .host(a2aHost).port(a2aPort).path(a2aPathPrefix + "/" + agentId)
            .build();
    AgentScopeA2aServer server = AgentScopeA2aServer.builder(new HarnessAgentRunner(agent))
            .agentCard(card)
            .withTransport(transport)
            .build();
    server.postEndpointReady();
    return server;
}
```

`HarnessAgentRunner` 仅把底层 `ReActAgent` 透传给框架，不重建任何推理语义：

```java
public class HarnessAgentRunner extends BaseReActAgentRunner {
    private final HarnessAgent harnessAgent;
    @Override
    protected ReActAgent buildReActAgent() {
        return harnessAgent.getDelegate();   // 直接复用 yunxi 已装配好的推理型 Agent
    }
}
```

## HTTP 端点

`A2aController` 在同端口以 `${yunxi.a2a.path-prefix:/a2a}` 为前缀暴露以下端点（path-prefix 默认 `/a2a`）：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/a2a/{agentId}` | GET | 获取该 Agent 的 `AgentCard`（A2A 发现） |
| `/a2a/{agentId}/.well-known/agent.json` | GET | 标准 A2A 发现路径，返回 `AgentCard` |
| `/a2a/{agentId}` | POST | JSON-RPC 调用入口；请求体为 A2A JSON-RPC，`transport.handleRequest` 处理后返回（流式以 `text/event-stream` 下发） |

> 注意：yunxi 不暴露 `/a2a/register`、`/a2a/deregister`、`/a2a/health`、`/a2a/agents` 这类端点——Agent 注册在启动期由 `AgentProvisioner` 自动完成，无需运行时 REST 注册。

## AgentCard 与服务发现

- 每个 Agent 的 `AgentCard` 由 `ConfigurableAgentCard.Builder` 构建，`url` 指向 `http://{yunxi.a2a.host}:{yunxi.a2a.port}{yunxi.a2a.path-prefix}/{agentId}`。
- 远程服务通过 `GET /a2a/{agentId}` 或标准 `.well-known/agent.json` 路径拉取 `AgentCard` 完成发现。
- 跨实例/跨服务注册发现（如 Nacos）由框架 `agentscope-extensions-nacos-a2a` 提供，yunxi 当前仅做进程内 `A2aServerRegistry` 索引，未在产品平面封装注册中心客户端。

## 调用方式（出站）

出站调用对端 Agent 即向对端的 `/a2a/{agentId}` 发送标准 A2A JSON-RPC 请求。以下为概念示意（使用 Google A2A Java SDK client，非 yunxi 封装类）：

```java
// 概念示意：出站调用对端 A2A 端点（标准 A2A JSON-RPC client）
A2AClient client = new A2AClient("http://remote-host:8080/a2a/data-agent");
// send 走 JSON-RPC，流式以 SSE 接收；具体 API 以所用 A2A SDK 版本为准
client.send(task);
```

> yunxi 当前不在产品平面提供 `A2AClient` bean；如需在平台内发起跨服务 Agent 调用，直接依赖框架/SDK 的 A2A client 访问目标服务的 `/a2a/{agentId}` 即可。

## 配置

yunxi 侧 A2A 配置（来自 `AgentProvisioner` 的 `@Value`，无独立 enabled 开关，A2A 随 Agent 注册自动可用）：

```yaml
yunxi:
  a2a:
    host: localhost            # AgentCard 对外 host（默认 localhost）
    port: 40001                # AgentCard 对外 port（须与 yunxi-app 统一入口一致，见《端口规划》；@Value 默认 8080，部署时在 config/server.yml 或环境变量覆盖为实际 Web 端口）
    path-prefix: /a2a          # A2A 端点前缀（默认 /a2a）
```

框架 A2A 服务端 transport 配置位于 `agentscope.a2a.*` 命名空间（由框架 `A2aCommonProperties` / `A2aAgentCardProperties` 等承载），yunxi 通过 `TransportProperties` 在构建 `AgentScopeA2aServer` 时设置 host/port/path，无需在 yunxi 配置中重复声明。

## 使用场景

### 场景 1：多领域协作

```
用户："分析这份业务数据的价值和成本"

调度方 Agent（经 A2A 调用对端 /a2a/data-agent）
    ├── 调用 data-agent (A2A) → 数据分析
    ├── 调用 cost-agent  (A2A) → 成本计算
    └── 聚合结果 → 完整报告
```

### 场景 2：异地多活 / 蓝绿部署

多个 yunxi 实例各自在同端口暴露 `/a2a/{agentId}`；调用方经服务发现（框架 Nacos A2A registry 或对端 `AgentCard`）获取可达端点，实现就近路由与平滑切换。

## 最佳实践

### 1. 端口与路径一致性

`yunxi.a2a.port` 应与实际 Web 端口一致（默认 8080），否则 `AgentCard.url` 指向不可达地址，远程发现会失败。

### 2. 不要重建协议语义

A2A 的 AgentCard、JSON-RPC、SSE 流式全部由框架 `AgentScopeA2aServer` 承担。yunxi 只做薄适配（`HarnessAgentRunner` 透传 `getDelegate()`、`A2aController` 同端口路由），不要在产品平面重复实现 A2A 服务端逻辑。

---

**上一页**: [12. 智能系统](./12-intelligent-system.md)  
**下一页**: [14. 可观测性 →](./14-observability.md)
