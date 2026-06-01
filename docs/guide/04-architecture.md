# 04. 架构设计

## 软件架构理论基础

### 什么是软件架构

**软件架构**是系统的高级结构，包括：
- **组件**：系统的组成部分（模块、类、服务）
- **关系**：组件之间的连接和交互方式
- **原则**：设计和演进的指导方针

**好的架构特征**：
| 特征 | 说明 | 本框架体现 |
|------|------|-----------|
| **可维护性** | 易于理解和修改 | 分层清晰，职责单一 |
| **可扩展性** | 易于添加新功能 | SPI 机制，插件化 |
| **可测试性** | 易于测试 | 依赖接口，便于 Mock |
| **可靠性** | 稳定运行 | 熔断降级，故障隔离 |
| **性能** | 响应迅速 | 缓存，异步，并行 |

### 分层架构模式

**分层架构**是最经典的架构模式，将系统分为水平层次：

```
┌─────────────────────────────────────────┐
│  表示层 (Presentation)                   │
│  - 用户界面                              │
├─────────────────────────────────────────┤
│  业务层 (Business)                       │
│  - 业务逻辑                              │
├─────────────────────────────────────────┤
│  持久层 (Persistence)                    │
│  - 数据访问                              │
├─────────────────────────────────────────┤
│  数据库 (Database)                       │
│  - 数据存储                              │
└─────────────────────────────────────────┘
```

**分层原则**：
- **单向依赖**：上层依赖下层，下层不依赖上层
- **层间隔离**：每层只与相邻层交互
- **职责分离**：每层有明确的职责

### 依赖倒置原则 (DIP)

**传统分层的问题**：
```
业务层 ──→ 持久层 ──→ 数据库
   ↑         ↑
   └─────────┘
   高层依赖低层具体实现
```

**依赖倒置的改进**：
```
业务层 ──→ 持久接口 ←── 持久实现
   ↑                      ↑
   └──────────────────────┘
   高层依赖抽象，低层实现抽象
```

**本框架的实践**：
- `framework` 层定义 SPI 接口
- `infra` 层实现 SPI 接口
- `framework` 通过接口使用 `infra` 服务

---

## 本框架的分层架构

### 四层架构详解

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 4: Business 业务层                                    │
│  - 业务逻辑实现                                              │
│  - SPI 扩展实现                                              │
│  - 领域模型                                                  │
├─────────────────────────────────────────────────────────────┤
│  Layer 3: Framework 框架层                                   │
│  - 核心能力封装                                              │
│  - SPI 接口定义                                              │
│  - 业务流程编排                                              │
├─────────────────────────────────────────────────────────────┤
│  Layer 2: Infra 基础设施层                                   │
│  - 技术实现                                                  │
│  - SPI 接口实现                                              │
│  - 外部服务集成                                              │
├─────────────────────────────────────────────────────────────┤
│  Layer 1: Shared 共享层                                      │
│  - 通用组件                                                  │
│  - SPI 接口定义                                              │
│  - 基础工具                                                  │
└─────────────────────────────────────────────────────────────┘
```

### 各层职责与关系

#### Shared 共享层（最底层）

**定位**：被所有其他层引用，不依赖任何业务层

**职责**：
- 定义技术无关的 SPI 接口
- 提供通用工具类
- 定义共享的 DTO 和实体

**设计原则**：
- 零业务依赖
- 纯技术抽象
- 向后兼容

**示例**：
```java
// SPI 接口定义
public interface CacheProvider {
    void set(String key, Object value, Duration ttl);
    <T> T get(String key, Class<T> type);
}

// 通用工具
public class JsonUtils {
    public static String toJson(Object obj) { ... }
}
```

#### Infra 基础设施层

**定位**：技术实现层，通过 SPI 向上层提供服务

**职责**：
- 实现 Shared 层的 SPI 接口
- 集成外部技术（Redis、MySQL、Milvus）
- 处理技术细节

**设计原则**：
- 实现接口，不定义接口
- 被上层通过接口调用
- 不依赖 Framework 或 Business 层

**示例**：
```java
@Service
public class RedisCacheService implements CacheProvider {
    // 实现 CacheProvider 接口
    // 使用 Redis 技术
}
```

#### Framework 框架核心层

**定位**：通用能力层，提供框架级服务

**职责**：
- 定义领域 SPI 接口
- 实现核心业务逻辑（Agent 管理、对话编排）
- 通过 SPI 使用 Infra 服务

**设计原则**：
- 依赖 Shared 层
- 通过 SPI 接口使用 Infra 层
- 不直接依赖 Infra 实现类
- 不依赖 Business 层

**示例**：
```java
@Service
public class MemoryCoordinatorService {
    // 依赖接口，不依赖实现
    private final CacheProvider cacheProvider;
    
    public MemoryCoordinatorService(CacheProvider cacheProvider) {
        this.cacheProvider = cacheProvider;
    }
}
```

#### Business 业务层（最上层）

**定位**：业务逻辑层，实现具体业务场景

**职责**：
- 实现 Framework 层的 SPI 接口
- 编写业务逻辑
- 使用下层所有服务

**设计原则**：
- 实现 SPI 接口扩展框架
- 编写纯业务代码
- 不处理技术细节

**示例**：
```yaml
# 在 agent-definitions/<name>.yaml 中声明
name: nutrition-assistant
workspace: ./workspace/nutrition-assistant
tools:
  mcpServers:
    - name: nutrition-data-mcp
      type: sse
      url: http://localhost:40602/sse
```

然后在工作区目录中放置 AGENTS.md 定义人格和行为：

```markdown
<!-- workspace/nutrition-assistant/AGENTS.md -->
# 场景检测
- 场景: nutrition
- 触发关键词: 食谱, 营养, 配餐, 热量
- 场景上下文: 你是一个学校营养餐专家...
```

框架自动从配置 + 工作区文件完成 Agent 装配，无需 Java 代码。

### 依赖关系图

```
┌─────────────────────────────────────────┐
│           Business 业务层                │
│  实现 Framework SPI                      │
│  使用所有下层服务                         │
└─────────────┬───────────────────────────┘
              │ 实现
              ▼
┌─────────────────────────────────────────┐
│          Framework 框架层                │
│  定义领域 SPI                            │
│  使用 Shared SPI                         │
└─────────────┬───────────────────────────┘
              │ 使用（通过接口）
              ▼
┌─────────────────────────────────────────┐
│           Infra 基础设施层               │
│  实现 Shared SPI                         │
│  被上层通过接口使用                       │
└─────────────┬───────────────────────────┘
              │ 实现
              ▼
┌─────────────────────────────────────────┐
│           Shared 共享层                  │
│  定义技术 SPI                            │
│  被所有层引用                             │
└─────────────────────────────────────────┘
```

---

## 关键组件详解

### Agent 生命周期管理

**理论基础：状态机模式**

Agent 的生命周期可以看作一个状态机：

```
┌─────────┐    创建     ┌─────────┐    初始化    ┌─────────┐
│  不存在  │ ─────────→ │  已创建  │ ─────────→ │  就绪   │
└─────────┘            └─────────┘            └────┬────┘
                                                   │
              ┌────────────────────────────────────┘
              │ 处理请求
              ▼
         ┌─────────┐    故障     ┌─────────┐
         │  运行中  │ ─────────→ │  故障   │
         └────┬────┘            └────┬────┘
              │                      │
              │ 完成/销毁            │ 恢复
              ▼                      ▼
         ┌─────────┐            ┌─────────┐
         │  已销毁  │            │  就绪   │
         └─────────┘            └─────────┘
```

**状态说明**：
| 状态 | 说明 | 转换条件 |
|------|------|----------|
| 不存在 | Agent 尚未创建 | 配置加载后创建 |
| 已创建 | Agent 实例已创建 | 初始化完成后就绪 |
| 就绪 | 可以处理请求 | 接收到请求后运行 |
| 运行中 | 正在处理请求 | 处理完成后就绪 |
| 故障 | 发生错误 | 错误恢复后就绪 |
| 已销毁 | Agent 已销毁 | - |

**本框架的实现**：

```java
@Service
public class AgentDomainService {
    // Agent 缓存（统一使用 Agent 接口）
    private final Map<String, Agent> agentInstanceCache = new ConcurrentHashMap<>();
    
    public Agent getAgentInstance(String name) {
        // 从缓存获取，如果不存在则抛出 NotFoundException
        Agent agent = agentInstanceCache.get(name);
        if (agent == null) {
            throw new NotFoundException("Agent not found: " + name);
        }
        return agent;
    }
    
    public AgentInfoDto createAgent(String name, AgentConfigDto config) {
        // 1. 读取配置
        ModelConfig modelCfg = buildModelConfig(config);
        
        // 2. 创建模型
        ChatModelProvider modelProvider = modelFactory.createProvider(modelCfg);
        
        // 3. 构建 ReActAgent delegate
        ReActAgent delegate = ReActAgent.builder()
            .name(name)
            .sysPrompt(prompt)
            .model(modelProvider)
            .build();
        
        // 4. 用 HarnessAgent 包装（薄包装器）
        Agent agent = HarnessAgent.from(delegate)
            .disableSubagents()
            .disableSessionPersistence()
            .disableMemoryHooks()
            .disableFilesystemTools()
            .disableShellTool()
            .build();
        
        // 5. 放入缓存
        agentInstanceCache.put(name, agent);
        return new AgentInfoDto(name, prompt, modelName, Instant.now());
    }
}
```

### MCP 工具集成架构

**理论基础：适配器模式**

适配器模式将不兼容的接口转换为兼容的接口：

```
┌─────────────────────────────────────────┐
│           适配器模式                     │
├─────────────────────────────────────────┤
│                                         │
│  ┌─────────┐      ┌─────────┐          │
│  │  Target │◄─────│ Adapter │          │
│  │ (目标)  │      │ (适配器) │          │
│  └────┬────┘      └────┬────┘          │
│       │                │               │
│       │                ▼               │
│       │           ┌─────────┐          │
│       │           │  Adaptee│          │
│       │           │ (被适配) │          │
│       │           └────┬────┘          │
│       │                │               │
│       └────────────────┘               │
│              调用                       │
│                                         │
└─────────────────────────────────────────┘
```

**在本框架中的应用**：

```
┌─────────────────────────────────────────┐
│  AgentScope AgentTool (Target)          │
│  - call(ToolUseBlock)                   │
└─────────────┬───────────────────────────┘
              │ 调用
              ▼
┌─────────────────────────────────────────┐
│  ToolAdapter (Adapter)                  │
│  - 将 AgentTool 接口                    │
│    适配为 ToolHandler 接口               │
└─────────────┬───────────────────────────┘
              │ 调用
              ▼
┌─────────────────────────────────────────┐
│  ToolHandler (Adaptee)                  │
│  - execute(Map<String, Object>)         │
└─────────────────────────────────────────┘
```

**McpToolRegistry 核心功能**：

```java
@Service
public class McpToolRegistry {
    // 工具分组管理
    private final Map<String, List<ToolHandler>> toolGroups = new ConcurrentHashMap<>();
    
    // 动态刷新（每30秒）
    @Scheduled(fixedRate = 30000)
    public void refreshTools() {
        // 检查未连接的 MCP 服务器
        // 自动重连
    }
    
    // 工具查找
    public ToolHandler findTool(String serverName, String toolName) {
        return toolGroups.get(serverName).stream()
            .filter(t -> t.getName().equals(toolName))
            .findFirst()
            .orElseThrow();
    }
}
```

### Supervisor 多 Agent 协作模式

**理论基础：主从模式 (Master-Slave Pattern)**

主从模式是一种常用的并行计算模式：
- **Master（主管）**：负责任务分解和结果聚合
- **Slave（从属）**：负责执行具体任务

**优势**：
- 任务并行化，提高效率
- 职责分离，简化设计
- 易于扩展，增加 Slave 即可

**在本框架中的应用**：

```
┌─────────────────────────────────────────┐
│         Supervisor Agent                │
│         (Master - 主管)                  │
│                                         │
│  1. 接收用户请求                          │
│  2. 分解任务                              │
│  3. 调度专家 Agent                        │
│  4. 聚合结果                              │
│  5. 返回最终答案                          │
└─────────────┬───────────────────────────┘
              │ 调度
    ┌─────────┼─────────┬─────────┐
    ▼         ▼         ▼         ▼
┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐
│专家Agent│ │专家Agent│ │专家Agent│ │专家Agent│
│(Slave)│ │(Slave)│ │(Slave)│ │(Slave)│
└───────┘ └───────┘ └───────┘ └───────┘
```

**任务分解策略**：

| 策略 | 说明 | 示例 |
|------|------|------|
| **按领域分解** | 不同领域由不同 Agent 处理 | 营养、成本、合规 |
| **按步骤分解** | 流程步骤由不同 Agent 处理 | 提取→分析→生成 |
| **按数据分解** | 数据分片由不同 Agent 处理 | 批量处理 |

**结果聚合策略**：

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| **简单合并** | 直接拼接结果 | 独立任务 |
| **投票决策** | 多数表决 | 需要高可靠性 |
| **加权平均** | 按权重聚合 | 数值结果 |
| **智能综合** | LLM 综合各结果 | 复杂分析 |

### A2A 跨服务 Agent 调用

A2A（Agent-to-Agent）协议支持跨服务的 Agent 调用，实现分布式 Agent 架构。

**核心能力**：
- **服务注册与发现**：Agent 自动注册到注册中心
- **负载均衡**：多实例自动负载均衡
- **故障转移**：实例故障时自动切换

**详细内容请参考**：[15. A2A 协议](./15-a2a-protocol.md)

### ProfileRouter — Profile 路由服务

ProfileRouter 是框架层的核心路由服务，负责根据 `agentName + profileName` 解析对应的 Agent 实例。

**职责**：
1. 根据 agentName + profileName 解析对应的 Agent 实例
2. 无 profile 时回退到默认 Agent
3. 无 mode 时使用默认模式（向后兼容）

```java
@Component
public class ProfileRouter {
    
    public ChatAppService resolve(String agentName, String profile) {
        if (profile == null || profile.isBlank()) {
            return agentDomainService.getAgentInstance(agentName);
        }
        String compositeKey = buildCompositeKey(agentName, profile);
        return agentDomainService.getAgentInstance(compositeKey);
    }
    
    public List<ProfileInfo> getAvailableProfiles(String agentName) {
        AgentDefinition def = agentDefinitionRepository.findByName(agentName);
        if (def == null || def.getProfiles() == null) return List.of();
        return def.getProfiles().entrySet().stream()
            .map(e -> new ProfileInfo(
                e.getKey(), 
                e.getValue().getLabel(), 
                e.getValue().getDescription(),
                e.getValue().getMode() != null ? e.getValue().getMode() : def.getMode()
            ))
            .toList();
    }
}
```

---
## 与 AgentScope-Java 的集成

### 核心关系：引擎 vs 平台

理解 yunxi-agent-platform 与 agentscope-javaRC2 的关系，可以用一个比喻：

```
agentscope-javaRC2 = 发动机 + 变速箱 + 底盘（汽车核心组件）
yunxi-agent-platform = 整车制造平台（含：车身、方向盘、仪表盘、安全气囊、中控系统、导航）
```

**agentscope-javaRC2** 是通用 Agent SDK，提供 Agent 抽象、LLM 集成、消息系统、工具系统、Hook 机制——但它是**被嵌入的组件**，不是一个可部署的生产系统。

**yunxi-agent-platform** 在此基础上构建了完整的**生产平台**，增加了以下 **7 层能力**：

### 7 层价值分层

| 层次 | 能力范畴 | 关键代码 | agentscope 内置？ |
|------|---------|---------|:--:|
| **1. Spring Boot 集成层** | 自动配置、Bean 管理、YAML 配置加载 | `AgentscopeAutoConfiguration`、`WebMvcConfig` | 否 |
| **2. 统一治理层** | 审计日志、限流、超时控制、优雅关闭、Pre/Post 扩展 | `AgentGatewayImpl` 8 步拦截链 | 否 |
| **3. 多通道网关层** | 钉钉/飞书/企微/Web API 多渠道接入 | `agent-gateway` 模块、`MessageChannel` | 否 |
| **4. 生产特性层** | 熔断器、HITL 人工审核、会话管理、分布式缓存、多租户 | `ToolCircuitBreaker`、`ToolGateHook`、`ConversationDomainService` | 否 |
| **5. 国产化 LLM 适配层** | DashScope/百度/华为/Claude 统一接入 | `ModelProviderFactory`、`ChatModelProvider` | 部分 |
| **6. 持久化与记忆体系** | 5 种持久化策略、多种 Repository、Harness 内置记忆 | `PersistenceManager`、`HybridPersistenceStrategy` | 否 |
| **7. 编排与自动装配层** | YAML 配置驱动、两轮初始化、Supervisor/Pipeline/Routing | `AgentConfigurer`（~555行） | 否 |

### 关键接线：具体桥接代码解读

#### 1. AgentGatewayImpl — 统一调用入口

`AgentGatewayImpl` 是**所有 Agent 调用必须经过的唯一入口**，它自动插入的 8 步拦截链：

```java
// AgentGatewayImpl.java (核心: callWithChain 方法)
private Mono<String> callWithChain(String agentName, AgentInvokeInfo info,
        String message, CallOptions options) {
    return Mono.just(message)
        // 2.限流检查
        .transformDeferred(this::rateLimit)
        // 3.优雅关闭检查
        .doOnSubscribe(s -> GracefulShutdownManager.getInstance().ensureAcceptingRequests())
        // 4.超时控制 + 实际 Agent 调用（这里才用到 agentscope 的 Agent.call()）
        .flatMap(m -> {
            Duration timeout = resolveTimeout(info.definition(), options);
            Msg userMsg = Msg.builder().textContent(m).build();  // ← agentscope Msg
            return info.agent().call(userMsg).timeout(timeout);   // ← agentscope Agent
        })
        .map(Msg::getTextContent)        // ← agentscope Msg
        // 5.PreProcessor → 7.PostProcessor → 8.监控
        .flatMap(text -> applyPreProcessorsOnMono(agentName, text))
        .flatMap(result -> applyPostProcessors(agentName, message, result))
        .doOnSubscribe(s -> metricsStart(agentName))
        .doOnError(e -> metricsError(agentName, e));
}
```

**关键点**：实际调用 agentscope 的 `Agent.call()` 只占其中一步（第4步），其余7步都是平台治理能力。如果不用 AgentGateway，每个调用方都要自己写限流、超时、监控——这正是"平台代码多"的原因。

#### 2. AgentConfigurer — 配置驱动的自动装配

```java
// AgentConfigurer.java (ApplicationReadyEvent 触发)
@EventListener(ApplicationReadyEvent.class)
public void configureAgents() {
    // 第一轮：初始化所有独立 Agent
    for (AgentDefinition def : definitions) {
        if (!isOrchestrated(def))
            initializeSingleAgent(def);  // YAML → Model → HarnessAgent → 注册
    }
    // 第二轮：创建编排 Agent（Supervisor/Pipeline/Routing）
    for (AgentDefinition def : definitions) {
        if (isOrchestrated(def))
            createOrchestratedAgent(def);
    }
}
```

这本质上是一个 **Agent 容器**——读取 YAML、创建 ModelProvider、构建 HarnessAgent、注册工具、注入 Hook。agentscope 只提供了 `ReActAgent.builder()`，但"怎么把几十个 YAML 配置变成可运行的 Agent 实例"这件事，完全是平台层的。

#### 3. ToolAdapter — 平台工具到 agentscope 的桥梁

```
我们的 Tool 接口:         agentscope 的 AgentTool 接口:
  getName()                getName()
  getDescription()         getDescription()
  getParameterSchema()     getParameters()
  execute(ToolInput)       callAsync(ToolCallParam)
                              ↓
                         ToolAdapter (桥梁)
                           集成熔断器保护
                           统一结果格式转换
```

```java
// ToolAdapter.callAsync() — 桥梁核心
public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
    // 熔断检查（平台特色）
    if (circuitBreaker != null && circuitBreaker.isCircuitOpen(tool.getName())) {
        return Mono.just(ToolResultBlock.error("工具暂时不可用"));
    }
    // 调用平台 Tool 接口
    ToolInput input = new ToolInput(param.getInput());
    ToolResult result = tool.execute(input);
    // 转换为 agentscope 的 ToolResultBlock
    return Mono.just(ToolResultBlock.text(resultJson));
}
```

业务工具只需实现简单的 `Tool` 接口，不需要直接依赖 agentscope 的 `AgentTool`——这是**解耦**，不是重复。

#### 4. ModelProviderFactory — 模型提供商的统一工厂

```java
public ChatModelProvider createProvider(ModelConfig config) {
    return switch (config.getProvider().toLowerCase()) {
        case "dashscope" -> new DashScopeModelProvider(config);
        case "baidu"     -> new BaiduModelProvider(config);
        case "huawei"    -> new HuaweiModelProvider(config);
        case "openai"    -> new OpenAIModelProvider(config);
        case "claude"    -> new ClaudeModelProvider(config);
    };
}
```

agentscope 的 `Model` 接口只负责"发请求、拿响应"。平台层的 `ChatModelProvider` 在此基础上增加了：配置管理、多厂商统一抽象、与 `ReActAgent.builder().model(provider)` 的无缝集成。

### 完整架构对比

```
┌──────────────────────────────────────────────────────────────────┐
│  yunxi-agent-platform                                            │
│                                                                  │
│  第 7 层: 编排与自动装配 (AgentConfigurer)                        │
│    YAML定义 → 两轮初始化 → Supervisor/Pipeline/Routing            │
│  ─────────────────────────────────────────────────────────────── │
│  第 6 层: 持久化与记忆 (PersistenceManager, ConversationService)  │
│    5种持久化策略 | Harness 内置记忆 | 分布式会话                            │
│  ─────────────────────────────────────────────────────────────── │
│  第 5 层: 国产化LLM适配 (ModelProviderFactory)                    │
│    DashScope | 百度 | 华为 | Claude | OpenAI                     │
│  ─────────────────────────────────────────────────────────────── │
│  第 4 层: 生产特性 (CircuitBreaker, HITL, Audit, Metrics)        │
│    熔断器 | 人工审核 | 审计 | 监控 | 多租户 Profile                │
│  ─────────────────────────────────────────────────────────────── │
│  第 3 层: 多通道网关 (agent-gateway)                              │
│    钉钉 | 飞书 | 企微 | Web API | WS                              │
│  ─────────────────────────────────────────────────────────────── │
│  第 2 层: 统一治理 (AgentGatewayImpl)                             │
│    审计→限流→优雅关闭→超时→Pre→Agent.call→Post→监控               │
│  ─────────────────────────────────────────────────────────────── │
│  第 1 层: Spring Boot 集成 (AutoConfiguration)                    │
│    @ConditionalOnProperty | Bean注册 | YAML加载                   │
├──────────────────────────────────────────────────────────────────┤
│  agentscope-javaRC2 (嵌入式 SDK)                                  │
│                                                                  │
│  ReActAgent | Agent接口 | Msg | Toolkit | Hook系统 | Pipeline     │
│  这是被嵌入的引擎，不是平台                                             │
├──────────────────────────────────────────────────────────────────┤
│  基础设施: Spring Boot / LLM API / MySQL / Redis / Milvus        │
└──────────────────────────────────────────────────────────────────┘
```

### 封装与增强对比

| 功能 | AgentScope 提供 | yunxi 增强 | 增加的文件数 |
|------|---------------|-----------|:---------:|
| Agent 创建 | ReActAgent.builder() | 配置驱动 + HarnessAgent 包装 + 自动装配 | ~15 |
| 工具系统 | Tool + AgentTool 接口 | ToolAdapter 桥接 + 熔断器 + 本地/远程/MCP 统一注册 | ~12 |
| LLM 集成 | ModelRegistry + SPI | 模型工厂 + 国产化适配 (百度/华为) + 配置绑定 | ~10 |
| 记忆 | InMemoryMemory | Harness 内置文件系统记忆 + 5 种持久化策略 + 场景管理 | ~15 |
| MCP | 基础客户端 | 自动重连 + 缓存 + 跨 Agent 共享 + 动态刷新 | ~8 |
| 多 Agent | A2A 协议 | Supervisor/Pipeline/Routing 编排 + Profile 路由 | ~10 |
| 网关 | 无 | 4 通道 + 会话 + 限流 + 认证 | ~20 |
| 规则管控 | 无 | 三阶段规则引擎 + SpEL | ~15 |
| 生产治理 | 无 | 熔断/审计/监控/HITL/优雅关闭 | ~12 |

### 诚实的评估：哪些代码可以优化？

1. **YAML 配置 → DTO 的转换链**：`AgentDefinition` → `AgentConfigDto` → `AgentInfoDto` 有多层映射，部分可以合并
2. **WorkspaceAutoDiscoveryEngine + SceneDetectionService**：场景检测逻辑可能和 agentscope 现有能力有重叠
3. **ToolRegistry 与 agentscope Toolkit 中的 ToolRegistry**：功能有部分重叠，可以考虑直接委托

但**绝大多数代码是合理的**——它们解决的是不同层次的问题。业务工具只需实现简单的 `Tool` 接口就能被 Agent 调用，这才是平台的价值所在。

---

## 设计理念

### 1. 领域驱动设计 (DDD)

**理论来源**：Eric Evans《领域驱动设计》

**核心概念**：
- **领域 (Domain)**：业务问题的范围
- **限界上下文 (Bounded Context)**：领域的边界
- **实体 (Entity)**：有唯一标识的对象
- **值对象 (Value Object)**：无标识的属性集合
- **领域服务 (Domain Service)**：跨实体的业务逻辑

**在本框架中的实践**：
- Domain：通过 YAML Agent 定义 + 工作区 AGENTS.md 声明
- Bounded Context：通过模块划分
- Entity：Agent、Scene、Rule
- Domain Service：SupervisorService、RuleEngine

### 2. 依赖倒置原则 (DIP)

**理论来源**：Robert C. Martin SOLID 原则

**核心思想**：
- 高层模块不应该依赖低层模块
- 两者都应该依赖抽象

**实践方式**：
```java
// 依赖抽象（接口）
private final CacheProvider cacheProvider;

// 不依赖具体实现
// private final RedisCacheService cacheService; // 错误！
```

### 3. 开闭原则 (OCP)

**理论来源**：SOLID 原则

**核心思想**：
- 对扩展开放
- 对修改关闭

**实践方式**：
```yaml
# 新增业务领域，无需修改框架代码
# 在 agent-definitions/ 目录新增 YAML 文件
# 在工作区目录新建知识/技能文件即可
- name: new-business-agent
  workspace: ./workspace/new-business-agent
  tools:
    mcpServers:
      - name: business-mcp
        type: sse
        url: http://localhost:40603/sse
```

### 4. 单一职责原则 (SRP)

**理论来源**：SOLID 原则

**核心思想**：
- 一个类应该只有一个引起变化的原因
- 一个类只负责一项职责

**实践方式**：
```java
// AgentDomainService：只负责 Agent 生命周期
// ChatAppService：只负责对话编排
// RuleEngine：只负责规则执行
```

---

**上一页**: [03. 核心概念](./03-concepts.md)  
**下一页**: [05. 模块说明 →](./05-modules.md)
