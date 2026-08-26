# 14. A2A 协议

A2A (Agent-to-Agent) 协议实现 Agent 跨服务协作，支持分布式 Agent 架构。

## 概述

### 什么是 A2A

A2A 协议允许不同服务中的 Agent 相互调用，实现：
- **分布式 Agent 架构**：Agent 部署在不同服务中，通过 A2A 协议通信
- **服务解耦**：各服务独立演进，通过标准协议集成
- **负载均衡**：支持多个 Agent 实例，自动负载均衡
- **故障转移**：某个实例故障时自动切换到其他实例

### 架构

```
┌─────────────────┐         ┌─────────────────┐
│   Service A     │         │   Service B     │
│                 │         │                 │
│  ┌───────────┐  │         │  ┌───────────┐  │
│  │  Agent X  │  │         │  │  Agent Y  │  │
│  └─────┬─────┘  │         │  └─────▲─────┘  │
│        │        │         │        │        │
│  ┌─────▼─────┐  │         │  ┌─────┴─────┐  │
│  │ A2AClient │  │◄───────►│  │ A2AServer │  │
│  │  (客户端)  │  │  HTTP   │  │  (服务端)  │  │
│  └───────────┘  │         │  └───────────┘  │
│        │        │         │        │        │
│  ┌─────▼─────┐  │         │  ┌─────▼─────┐  │
│  │A2ARegistry│  │◄───────►│  │A2ARegistry│  │
│  │(服务发现) │  │         │  │(服务发现) │  │
│  └───────────┘  │         │  └───────────┘  │
└─────────────────┘         └─────────────────┘
```

### 核心组件

| 组件 | 职责 |
|------|------|
| A2AClient | 调用远程 Agent |
| A2AServer | 暴露本地 Agent 为远程服务 |
| A2ARegistry | 服务注册与发现 |
| AgentEndpoint | Agent 端点信息（A2AClient 内部 record） |

## A2AClient

### 功能

A2A 客户端负责调用部署在其他服务中的 Agent。

### 调用方式

```java
@Service
public class MyService {

    @Autowired
    private A2AClient a2aClient;

    // 1. 同步调用（AgentRequest 由 A2AClient.AgentRequest.of(message) 构造）
    public A2AClient.AgentResponse callRemoteAgent() {
        return a2aClient.invoke(
            "data-agent",                                  // Agent 名称
            A2AClient.AgentRequest.of("生成报告")          // 请求内容
        );
    }

    // 2. 同步调用并携带上下文（Map<String,Object>）
    public A2AClient.AgentResponse callRemoteAgentWithContext() {
        Map<String, Object> context = Map.of("userId", "u1", "region", "cn");
        return a2aClient.invoke("data-agent", "生成报告", context);
    }

    // 3. 异步调用
    public Mono<A2AClient.AgentResponse> callRemoteAgentAsync() {
        return a2aClient.invokeAsync(
            "report-agent",
            A2AClient.AgentRequest.of("生成周报")
        );
    }

    // 4. 批量调用（并行发起，等待全部返回）
    public List<A2AClient.AgentResponse> callMultipleAgents() {
        List<String> agentNames = List.of(
            "nutrition-agent",
            "cost-agent",
            "compliance-agent"
        );
        return a2aClient.invokeAll(agentNames, A2AClient.AgentRequest.of("评估方案"));
    }

    // 5. 批量调用并聚合（自动汇总成功/失败，返回 AggregatedResponse）
    public A2AClient.AggregatedResponse aggregatedCall() {
        return a2aClient.invokeAndAggregate(
            List.of("nutrition-agent", "cost-agent"),
            A2AClient.AgentRequest.of("评估方案")
        );
    }
}
```

> **说明**：`AgentRequest` 是 record `(conversationId, message, context, options)`，可通过 `AgentRequest.of(String)`（仅消息）或 `AgentRequest.of(String, Map)`（消息 + 上下文）构造。`AgentResponse` 是 record `(agentName, endpoint, success, content, metadata, durationMs)`，始终通过 `success()` 判断是否成功。

### 调用发现与端点选择

`A2AClient` 通过 `A2ARegistry` 发现 Agent 端点（`AgentEndpoint` record：`name/host/port/protocol/metadata`，`getUrl()` 生成 `<protocol>://<host>:<port>/a2a/invoke`）。**同一 Agent 名对应多个端点时，客户端内部采用简单轮询**（`System.currentTimeMillis() % size`）选择端点——不提供可配置的负载均衡策略，也不需要。

### 超时控制

超时通过 `invoke` 的 `Duration` 参数指定（默认 60 秒）：

```java
A2AClient.AgentResponse response = a2aClient.invoke(
    "nutrition-agent",
    A2AClient.AgentRequest.of("评估方案"),
    Duration.ofSeconds(30)   // 本次调用 30 秒超时
);
```

> **说明**：`A2AConfig` 是普通 POJO（`enabled/registryType/registryAddr/namespace`），**无 Builder、无重试/故障转移配置**。调用失败不重试，由调用方根据 `AgentResponse.success()` 自行决定处理策略。

## A2AServer

### 功能

A2A 服务端负责将本地 Agent 暴露为远程可调用的服务，同时提供 `invoke` / `health` / `agents` 等 REST 端点。

### 注册本地 Agent

**通过 A2AServer 编程式 API**（`@ConditionalOnProperty(name="agentscope.extensions.a2a.enabled", havingValue="true")` 启用，A2A 默认关闭）：

```java
@Autowired
private A2AServer a2aServer;

// 注册：将本地 Agent 实例暴露为远程可调用服务
a2aServer.registerAgent(agent, List.of("nutrition", "cost"));

// 注销
a2aServer.deregisterAgent("nutrition-agent");

// 设置对外服务地址（默认读取 a2a.server.host / a2a.server.port 配置）
a2aServer.setServerInfo("10.0.0.5", 8080);
```

### API 端点

A2AServer 暴露以下 REST API：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/a2a/register` | POST | 注册 Agent（body: `{agentName, capabilities}`） |
| `/a2a/deregister` | POST | 注销 Agent（body: `{agentName}`） |
| `/a2a/invoke` | POST | 调用 Agent（body: `{agentName, conversationId, message, context, options}`） |
| `/a2a/health` | GET | 健康检查 |
| `/a2a/health/detail` | GET | 详细健康信息（注册中心类型、端点数等） |
| `/a2a/agents` | GET | 列出所有 Agent |
| `/a2a/agents/{agentName}` | GET | 查询单个 Agent 详情 |

## A2ARegistry

### 功能

服务注册中心，管理所有可用的 Agent 端点。

### 注册中心类型

```yaml
agentscope:
  extensions:
    a2a:
      registry-type: nacos  # nacos | consul | static
      registry-addr: localhost:8848
      namespace: agent-platform
```

| 类型 | 说明 | 适用场景 |
|------|------|----------|
| nacos | 阿里巴巴 Nacos（反射加载，依赖缺失自动降级 static） | 云原生环境 |
| consul | HashiCorp Consul（当前版本仅完成初始化，注册降级 static） | 微服务架构 |
| static | 本地注册表（通过 `A2ARegistry.register()` / `A2AServer.registerAgent()` 编程式注册） | 开发测试 |

> **说明**：`static` 模式**没有** YAML 静态端点配置段（`a2a.agents` 配置不存在），端点一律通过编程式 API 注册（见上文"注册本地 Agent"）。

## 使用场景

### 场景 1：多领域协作

```
用户："分析这份业务数据的价值和成本"

SupervisorAgent
    ├── 调用 data-agent (A2A) → 数据分析
    ├── 调用 cost-agent (A2A) → 成本计算
    └── 聚合结果 → 完整报告
```

### 场景 2：异地多活

```
┌─────────────┐     ┌─────────────┐
│  北京机房    │     │  上海机房    │
│             │     │             │
│ Agent-BJ-1  │◄───►│ Agent-SH-1  │
│ Agent-BJ-2  │     │ Agent-SH-2  │
└─────────────┘     └─────────────┘
      │                   │
      └─────────┬─────────┘
                ▼
          A2ARegistry
                │
                ▼
         自动就近路由
```

### 场景 3：蓝绿部署

```
┌─────────────┐     ┌─────────────┐
│   蓝环境     │     │   绿环境     │
│  (当前版本)  │     │  (新版本)   │
│             │     │             │
│  Agent-v1   │◄───►│  Agent-v2   │
│  (100%)     │     │  (0%)       │
└─────────────┘     └─────────────┘
      │
      ▼
  通过 A2ARegistry
  动态调整权重
  实现平滑切换
```

## 配置汇总

```yaml
agentscope:
  extensions:
    a2a:
      enabled: true            # A2A 总开关（默认 false；A2AServer/A2AClient 均以此为准）
      registry-type: nacos     # 注册中心类型：nacos / consul / static（依赖缺失自动降级 static）
      registry-addr: localhost:8848   # 注册中心地址（nacos/consul）
      namespace: agent-platform       # Nacos 命名空间（可选）
      timeout-seconds: 60             # 客户端默认调用超时（秒）
```

> **说明**：仅以上 5 个配置键有效。`client` / `server` / `load-balancer` 等配置段均不存在；服务端阻塞超时另由 `a2a.server.block-timeout-minutes`（默认 5 分钟）控制。

## 最佳实践

### 1. 超时设置

```java
// 根据任务复杂度设置合理超时（Duration 参数）
A2AClient.AgentResponse resp = a2aClient.invoke(
    agentName,
    A2AClient.AgentRequest.of(message),
    Duration.ofSeconds(10));   // 简单任务

A2AClient.AgentResponse resp2 = a2aClient.invoke(
    agentName,
    A2AClient.AgentRequest.of(message),
    Duration.ofMinutes(5));    // 复杂任务
```

### 2. 错误处理（无异常设计）

A2A 调用**不抛业务异常**——错误通过 `AgentResponse.success()` / `AggregatedResponse` 返回，调用方据此降级：

```java
A2AClient.AgentResponse response = a2aClient.invoke(agentName, request);
if (!response.success()) {
    // Agent 未注册 / 不可用 / 超时 → response 的 content 含错误描述，durationMs 记录耗时
    return fallbackService.execute(request);
}
String result = response.content();

// 批量场景：AggregatedResponse 提供失败清单与成功数
A2AClient.AggregatedResponse agg = a2aClient.invokeAndAggregate(agentNames, request);
if (agg.successCount() > 0) { /* 部分成功 */ }
for (A2AClient.AgentResponse failed : agg.failedAgents()) { /* 逐个降级 */ }
```

### 3. 可用性检查与统计

```java
// 探测远端 Agent 是否健康
boolean healthy = a2aClient.isHealthy("nutrition-agent");

// 发现 Agent 端点（刷新本地缓存）
List<A2AClient.AgentEndpoint> endpoints = a2aClient.discoverAgent("nutrition-agent");

// 失败统计通过 AggregatedResponse 的 successCount() / failureCount() 计算，无需额外监控指标
```

---

**上一页**: [13. 智能系统](./13-intelligent-system.md)  
**下一页**: [15. 可观测性 →](./15-observability.md)
