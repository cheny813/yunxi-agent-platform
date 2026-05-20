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
```java
@Component
public class NutritionDomainContributor implements DomainContributor {
    // 实现 Framework 的 SPI 接口
    // 编写营养领域业务逻辑
}
```

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
    // Agent 缓存
    private final Map<String, ChatAppService> agentInstanceCache = new ConcurrentHashMap<>();
    
    public ChatAppService getAgentInstance(String name) {
        // 从缓存获取，如果不存在则创建
        return agentInstanceCache.computeIfAbsent(name, this::createAgent);
    }
    
    private ChatAppService createAgent(String name) {
        // 1. 读取配置
        AgentDefinition definition = loadDefinition(name);
        
        // 2. 创建模型
        LanguageModel model = createModel(definition.getModel());
        
        // 3. 创建工具集
        Toolkit toolkit = createToolkit(definition.getTools());
        
        // 4. 构建 ChatAppService
        return ChatAppService.builder()
            .name(name)
            .sysPrompt(definition.getSystemPrompt())
            .model(model)
            .toolkit(toolkit)
            .build();
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

### 架构层次关系

```
┌─────────────────────────────────────────┐
│  第 4 层: 业务代码                         │
│  - 实现 SPI 接口                          │
│  - 调用 yunxi 服务                         │
├─────────────────────────────────────────┤
│  第 3 层: yunxi Agent Platform             │
│  - 封装 AgentScope 功能                   │
│  - 提供企业级能力                         │
├─────────────────────────────────────────┤
│  第 2 层: AgentScope-Java                 │
│  - ChatAppService 运行时                      │
│  - MCP/A2A 协议实现                       │
├─────────────────────────────────────────┤
│  第 1 层: 基础设施                         │
│  - Spring Boot / LLM API                  │
└─────────────────────────────────────────┘
```

### Agent 运行时三层抽象

除了上述代码模块分层，从 **Agent 运行时** 视角看，框架还提供另一套三层抽象，帮助不同角色理解系统：

```
┌─────────────────────────────────────────────────────────────┐
│  业务层：只需选择模式，无需理解底层机制                        │
│                                                             │
│  nutrition-assistant:                                        │
│    mode: expert         ← 一句话选择模式                      │
│    profiles:                                                 │
│      chat:  mode: chat  ← 覆盖为聊天模式                      │
│      make:  mode: expert                                    │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  框架层：提供内置模式 + Profile 路由                          │
│                                                             │
│  Built-in Modes:                                             │
│    chat    → tools:[], iters:3, no plan, no expert           │
│    tool    → tools:all, iters:10, no plan, single agent      │
│    expert  → tools:all, iters:25, plan:true, multi-agent     │
│    advanced→ 业务完全自定义                                   │
│                                                             │
│  ProfileRouter: agentName + profileName → Agent 实例         │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  引擎层：AgentScope ChatAppService 运行时                         │
│  工具管理、Prompt 注入、迭代控制                               │
└─────────────────────────────────────────────────────────────┘
```

| 层 | 用户 | 复杂度 | 需要知道什么 |
|----|------|--------|------------|
| 业务层 | 业务配置人员 | 低 | 只需选 `chat` 或 `expert` |
| 框架层 | 平台开发者 | 中 | 理解 Profile、Mode、路由机制 |
| 引擎层 | 框架开发者 | 高 | AgentScope SDK、工具管理 |

**80% 的业务场景只需选模式，20% 需要高级自定义。**

### 封装与增强对比

| 功能 | AgentScope-Java | yunxi 封装 | 增强点 |
|------|-----------------|-----------|--------|
| Agent 创建 | 代码创建 | YAML 配置 | 配置驱动 |
| MCP 支持 | 基础客户端 | 完整服务端+客户端 | 工具注册表 |
| 多 Agent | A2A 协议 | Supervisor 模式 | 业务编排 |
| 规则管控 | 无 | 三阶段规则引擎 | 企业合规 |
| 多平台 | 无 | Gateway 统一适配 | 即开即用 |

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
- Domain：通过 DomainContributor 定义
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
```java
// 新增业务领域，无需修改框架代码
@Component
public class NewDomainContributor implements DomainContributor {
    // 自动被框架识别和使用
}
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
