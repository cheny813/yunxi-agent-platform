# 15. A2A 协议

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
| AgentEndpoint | Agent 端点信息 |
| AgentCapability | Agent 能力描述 |

## A2AClient

### 功能

A2A 客户端负责调用部署在其他服务中的 Agent。

### 调用方式

```java
@Service
public class MyService {
    
    @Autowired
    private A2AClient a2aClient;
    
    // 1. 同步调用
    public AgentResponse callRemoteAgent() {
        return a2aClient.invoke(
            "nutrition-agent",           // Agent 名称
            "生成食谱",                   // 请求内容
            context                      // 上下文
        );
    }
    
    // 2. 异步调用
    public Mono<AgentResponse> callRemoteAgentAsync() {
        return a2aClient.invokeAsync(
            "recipe-agent",
            request
        );
    }
    
    // 3. 批量调用
    public List<AgentResponse> callMultipleAgents() {
        List<String> agentNames = List.of(
            "nutrition-agent",
            "cost-agent",
            "compliance-agent"
        );
        
        return a2aClient.invokeAll(agentNames, request);
    }
    
    // 4. 并行调用并聚合
    public AggregatedResponse parallelCall() {
        List<AgentResponse> responses = a2aClient.invokeParallel(
            agentNames,
            request,
            Duration.ofSeconds(30)  // 超时时间
        );
        
        return aggregate(responses);
    }
}
```

### 负载均衡

A2AClient 支持多种负载均衡策略：

```yaml
agentscope:
  extensions:
    a2a:
      load-balancer:
        strategy: round-robin  # round-robin | random | least-connections | weighted
        health-check:
          enabled: true
          interval: 30s
```

| 策略 | 说明 |
|------|------|
| round-robin | 轮询，依次选择 |
| random | 随机选择 |
| least-connections | 最少连接数 |
| weighted | 加权轮询 |

### 故障转移

```java
// 配置重试策略
A2AConfig config = A2AConfig.builder()
    .retryTimes(3)                    // 重试次数
    .retryInterval(Duration.ofSeconds(1))  // 重试间隔
    .failoverEnabled(true)            // 启用故障转移
    .build();

// 调用时会自动重试和故障转移
AgentResponse response = a2aClient.invoke(
    "nutrition-agent",
    request,
    config
);
```

## A2AServer

### 功能

A2A 服务端负责将本地 Agent 暴露为远程可调用的服务。

### 自动暴露

默认情况下，所有 Agent 会自动注册到 A2AServer：

```java
// 框架自动完成，无需手动配置
// AgentInitializationService 会自动注册
```

### 手动注册

```java
@Service
public class MyService {
    
    @Autowired
    private A2AServer a2aServer;
    
    public void registerCustomAgent() {
        AgentRegistration registration = AgentRegistration.builder()
            .name("my-custom-agent")
            .version("1.0.0")
            .capabilities(List.of(
                AgentCapability.builder()
                    .name("text-generation")
                    .description("文本生成")
                    .build()
            ))
            .endpoint(AgentEndpoint.builder()
                .url("http://localhost:40001")
                .healthCheckUrl("/actuator/health")
                .build())
            .build();
        
        a2aServer.register(registration);
    }
}
```

### API 端点

A2AServer 暴露以下 REST API：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/a2a/register` | POST | 注册 Agent |
| `/a2a/unregister` | POST | 注销 Agent |
| `/a2a/invoke` | POST | 调用 Agent |
| `/a2a/health` | GET | 健康检查 |
| `/a2a/agents` | GET | 列出所有 Agent |

## A2ARegistry

### 功能

服务注册中心，管理所有可用的 Agent 端点。

### 注册中心类型

```yaml
agentscope:
  extensions:
    a2a:
      registry-type: nacos  # nacos | consul | eureka | static
      registry-addr: localhost:8848
      namespace: agent-platform
```

| 类型 | 说明 | 适用场景 |
|------|------|----------|
| nacos | 阿里巴巴 Nacos | 云原生环境 |
| consul | HashiCorp Consul | 微服务架构 |
| eureka | Netflix Eureka | Spring Cloud |
| static | 静态配置 | 开发测试 |

### 静态配置示例

```yaml
agentscope:
  extensions:
    a2a:
      registry-type: static
      agents:
        nutrition-agent:
          - url: http://localhost:40001
            weight: 100
          - url: http://localhost:40002
            weight: 100
        recipe-agent:
          - url: http://localhost:40003
```

## AgentCapability

### 功能

描述 Agent 的能力，用于服务发现和路由。

### 能力定义

```java
AgentCapability.builder()
    .name("nutrition-analysis")           // 能力名称
    .description("营养分析")               // 能力描述
    .inputSchema(Schema.builder()         // 输入参数 Schema
        .type("object")
        .property("recipe", Schema.string())
        .build())
    .outputSchema(Schema.builder()        // 输出结果 Schema
        .type("object")
        .property("nutritionReport", Schema.object())
        .build())
    .qos(QosRequirement.builder()         // QoS 要求
        .maxLatency(5000)
        .availability(0.99)
        .build())
    .build();
```

## 使用场景

### 场景 1：多领域协作

```
用户："分析这份食谱的营养价值和成本"

SupervisorAgent
    ├── 调用 nutrition-agent (A2A) → 营养分析
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
      enabled: true
      
      # 客户端配置
      client:
        timeout: 30s
        retry-times: 3
        retry-interval: 1s
        
      # 服务端配置
      server:
        enabled: true
        port: 40001
        
      # 注册中心
      registry-type: nacos
      registry-addr: localhost:8848
      namespace: agent-platform
      
      # 负载均衡
      load-balancer:
        strategy: round-robin
        health-check:
          enabled: true
          interval: 30s
          timeout: 5s
```

## 最佳实践

### 1. 超时设置

```java
// 根据任务复杂度设置合理超时
a2aClient.invoke(agentName, request, A2AConfig.builder()
    .timeout(Duration.ofSeconds(10))  // 简单任务
    .build());

a2aClient.invoke(agentName, request, A2AConfig.builder()
    .timeout(Duration.ofMinutes(5))   // 复杂任务
    .build());
```

### 2. 错误处理

```java
try {
    AgentResponse response = a2aClient.invoke(agentName, request);
} catch (AgentNotFoundException e) {
    // Agent 未注册
    log.error("Agent not found: {}", agentName);
} catch (AgentUnavailableException e) {
    // Agent 不可用，触发降级
    return fallbackService.execute(request);
} catch (TimeoutException e) {
    // 超时，重试或降级
    return retryOrFallback(agentName, request);
}
```

### 3. 监控指标

```java
// A2A 调用指标
a2a_call_total{agent="nutrition-agent", status="success"}
a2a_call_duration_seconds{agent="nutrition-agent", quantile="0.99"}
a2a_call_errors_total{agent="nutrition-agent", error="timeout"}
```

---

**上一页**: [14. 智能子系统](./14-intelligent-system.md)
