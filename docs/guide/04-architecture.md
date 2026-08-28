# 04. 架构设计

> **架构说明**：yunxi-agent-platform 基于 **AgentScope-Java 2.0.0（GA 正式版）** 构建。包结构为扁平化的功能包（`agent/`、`config/`、`persistence/`、`gateway/`、`conversation/`、`intent/` 等 30+ 个顶层包），详见 [模块说明](./05-modules.md)。Hook 体系已全部迁移为框架原生 Middleware 体系，编排支持 single/supervisor/pipeline/routing 四种模式，Skill 系统采用 AgentScope 原生 `AgentSkillRepository`（由框架 `DynamicSkillMiddleware` 自动装载）。`Session` 包保留（承担会话管理），分布式协调由 `DistributedStore` 承担；`Tracer`/`TracerRegistry` 已废弃（改用 OpenTelemetry 直连 API）。说明：`Model.stream()` 为 Model 层现役调用方式（AgentScope-Java 2.0 未废弃），`Agent.streamEvents()` 为 Agent 层事件流 API，二者属不同层面的接口，并非替代关系。

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

**本框架的实践**（V2.0 简化后）：
- 顶层功能包（`agent/`、`config/`、`persistence/`、`gateway/` 等）定义 SPI 接口
- `agent-spi` 模块定义共享 SPI 扩展点
- Spring `@Autowired` 依赖注入实现依赖倒置

---

## 本框架的分层架构

### 四层架构详解（V2.0 简化后）

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 3: Business 业务层                                    │
│  - 业务逻辑实现（YAML 配置 + 工作区文件）                      │
│  - SPI 扩展实现                                              │
│  - 领域模型                                                  │
├─────────────────────────────────────────────────────────────┤
│  Layer 2: Platform 平台层                                    │
│  - Agent 编排、MCP、记忆（30+ 个功能包）             │
│  - SPI 接口定义                                              │
│  - 配置驱动自动装配                                            │
├─────────────────────────────────────────────────────────────┤
│  Layer 1: AgentScope 核心运行时                                │
│  - Agent/Model/Toolkit/Middleware/State                     │
│  - ReActAgent / HarnessAgent                                │
└─────────────────────────────────────────────────────────────┘
```

### 各层职责与关系

#### AgentScope 核心运行时（底层）

**定位**：第三方 SDK 依赖（AgentScope-Java 2.0.0 GA），不可修改

**职责**：
- 提供 Agent/Model/Toolkit/Middleware/State 核心抽象
- ReActAgent / HarnessAgent 运行时（HarnessAgent 与 ReActAgent 各自实现 Agent 接口）
- Middleware 洋葱模型、原生技能治理（`AgentSkillRepository` + `DynamicSkillMiddleware`）

#### Platform 平台层

**定位**：核心能力层，提供生产级 Agent 平台服务

**职责**：
- 实现 AgentScope 扩展点
- Agent 编排自动装配（`AgentConfigurer`）
- MCP 协议集成、记忆系统
- 配置管理、持久化、会话管理

**核心包**（`io.yunxi.platform`）：
```
agent/        ← Agent 核心（工厂、网关、装配 AgentConfigurer、工作区）
a2a/          ← A2A 跨服务 Agent 调用（客户端、服务器、注册中心）
config/       ← 配置类（AgentscopeExtensionProperties、Redis 后端等）
conversation/ ← 对话编排（ChatAppService、会话管理）
controller/   ← REST 控制器（对话、Agent、技能、文件、配置管理）
file/         ← 文件处理（上传、向量化入库）
gateway/      ← Agent 网关（AgentGateway 接口 + 默认实现）
intent/       ← 意图引擎（多域 DomainRegistry、rule/llm/hybrid 分类、reload 热更新）
lifecycle/    ← 生命周期管理
memory/       ← 记忆场景管理（MemoryScene、MemorySceneRegistry）
persistence/  ← 持久化（Milvus 向量库、Repository）
rag/          ← 应用层 RAG（ApplicationRAG 中间件工厂）
security/     ← 安全（SecurityContext、认证、审计）
session/      ← 会话管理（多租户会话）
shared/       ← 共享 DTO、配置加载（AgentDefinitionLoader）
tool/         ← 内置业务工具（HttpTool、DatabaseTool 等）
tracing/      ← 可观测性（OpenTelemetry）
embedding/    ← Embedding 提供商
cache/        ← Redis 缓存
```

#### Business 业务层

**定位**：业务逻辑层，通过 YAML 配置 + 工作区文件声明

**示例**：
```yaml
# 在 agent-definitions/<name>.yaml 中声明
name: nutrition-assistant
workspace: ./workspace/agents/nutrition-assistant
tools:
  mcpServers:
    - name: nutrition-data-mcp
      type: sse
      url: http://localhost:40602/sse   # 需先启动对应 MCP 服务（如 mcp-nutrition，端口 40602）
```

然后在工作区目录中放置 AGENTS.md 定义人格和行为：

```markdown
<!-- workspace/agents/nutrition-assistant/AGENTS.md -->
# 场景检测
- 场景: business
- 触发关键词: 业务, 报告, 记录, 指标
- 场景上下文: 你是一个业务数据专家...
```

框架自动从配置 + 工作区文件完成 Agent 装配，无需 Java 代码。

**工作空间目录结构**：yunxi 遵循底层 agentscope-java 框架约定，所有 Agent 工作空间汇聚在 `agents/` 子目录下：

```
.agentscope/workspace/
├── agents/                     # Agent 工作空间统一目录（框架官方约定）
│   ├── food-chat/              # 饮食问答助手
│   │   ├── agents/             # 子智能体定义
│   │   ├── AGENTS.md           # Agent 身份 + 场景规则
│   │   └── user-001/           # 用户运行时数据（按 userId 隔离）
│   ├── general-assistant/      # 通用助手
│   ├── nutrition-assistant/    # 校园餐营养助手（校园人群口径）
│   ├── resident-nutrition-assistant/  # 居民营养配餐助手（居民人群口径）
│   ├── dish-searcher/          # 菜品搜索
│   ├── nutrition-evaluator/    # 营养评估
│   ├── pagegen-assistant/      # 页面生成助手
│   ├── recipe-composer/        # 食谱编排
│   └── safety-assistant/       # 安全助手
└── skills/                     # 全局共享技能（Agent 不可在此创建）
```

多租户运行时隔离由 AgentScope-Java 2.0 原生 `HarnessAgent.workspaceFor(userId, sessionId)` 实现：按用户命名空间隔离工作空间与 AgentState 会话槽，用户数据按 `{userId}/` 子目录由框架运行时按需创建。根级 `skills/` 为全局共享资源。

### 依赖关系图

```
┌─────────────────────────────────────────┐
│           Business 业务层                │
│  YAML 配置 + 工作区文件声明域逻辑          │
│  使用平台层所有服务                        │
└─────────────┬───────────────────────────┘
              │ 
              ▼
┌─────────────────────────────────────────┐
│          Platform 平台层                 │
│  30+ 个功能包：agent/config/gateway/      │
│  conversation/intent/memory/rag/...      │
└─────────────┬───────────────────────────┘
              │ 依赖
              ▼
┌─────────────────────────────────────────┐
│       AgentScope V2.0 核心运行时          │
│  Agent/Model/Toolkit/Middleware/State    │
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
public class AgentService {
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
        // 1. 通过 ModelFactory 创建框架 Model 实例（复用 agentscope 内置 Provider)
        Model model = modelFactory.create(toModelConfig(config));
        
        // 2. 注册 prototype Bean（每次 getBean 返回新实例）
        registerPrototypeAgentBean(name, model, prompt, workspacePath);
        
        // 3. 返回摘要信息（Agent 实例由 BeanFactory 按需创建）
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

工具定义不再需要 `ToolAdapter` 桥接层。直接在方法上标注 `@Tool` 注解即可：

```java
@Component
public class WeatherTools {
    @Tool(name = "get_weather", description = "查询城市天气")
    public String getWeather(
            @ToolParam(description = "城市名称") String city) {
        return weatherService.query(city);
    }
}
```

`@Tool` 注解的方法会被 `Toolkit.registerTool(Object bean)` 自动扫描注册，无需手动维护注册表。框架自动从方法签名生成 JSON Schema。

MCP 工具注册同样由 AgentScope 框架原生处理：`AgentConfigurer.buildMcpClient()` 通过 `McpClientBuilder` 连接 MCP 服务器，`Toolkit.registration().mcpClient(wrapper).group(name).apply()` 注册工具分组。

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
| **按领域分解** | 不同领域由不同 Agent 处理 | 业务、成本、合规 |
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

**详细内容请参考**：[14. A2A 协议](./14-a2a-protocol.md)

### ProfileRouter — Profile 路由服务

ProfileRouter 是框架层的核心路由服务，负责根据 `agentName + profileName` 解析对应的 Agent 实例。

**职责**：
1. 根据 agentName + profileName 解析对应的 Agent 实例
2. 无 profile 时回退到默认 Agent
3. 无 mode 时使用默认模式（向后兼容）

```java
@Component
public class ProfileRouter {
    // 组合键格式：agentName#profileName
    public String buildCompositeKey(String agentName, String profile) {
        return agentName + "#" + profile;
    }

    // 路由逻辑：profile 为空 → 原始 Agent；非空 → 查找/创建 Profile Agent；不存在 → 降级
    public Agent resolve(String agentName, String profile) {
        if (profile == null || profile.isBlank()) {
            return agentService.getAgentInstance(agentName);
        }
        String compositeKey = buildCompositeKey(agentName, profile);
        try {
            return agentService.getAgentInstance(compositeKey);
        } catch (Exception e) {
            // Profile Agent 不存在，降级返回原始 Agent
            log.warn("Profile '{}' 未找到对应 Agent '{}'，降级返回原始 Agent", profile, agentName);
            return agentService.getAgentInstance(agentName);
        }
    }

    public List<ProfileInfo> getAvailableProfiles(String agentName) {
        AgentDefinition def = definitionLoader.getAgentDefinition(agentName);
        if (def == null || def.getProfiles() == null || def.getProfiles().isEmpty()) {
            return Collections.emptyList();
        }
        return def.getProfiles().entrySet().stream()
                .map(entry -> new ProfileInfo(
                        entry.getKey(),
                        entry.getValue().getLabel(),
                        entry.getValue().getDescription(),
                        null))
                .toList();
    }
}
```

> **说明**：`resolve()` 返回的是 AgentScope 框架的 `Agent` 接口实例（`io.agentscope.core.agent.Agent`），而非业务服务。Profile 定义来自 `agent-definitions/*.yml` 的 `profiles` 节点，由 `AgentDefinitionLoader` 加载。

---
## 与 AgentScope V2.0 的集成

### 核心关系：引擎 vs 平台

理解 yunxi-agent-platform 与 AgentScope V2.0 的关系：

```
AgentScope V2.0 = 发动机 + 变速箱 + 底盘（汽车核心组件）
yunxi-agent-platform = 整车制造平台（含：车身、方向盘、仪表盘、安全气囊、中控系统、导航）
```

**AgentScope V2.0** 是通用 Agent SDK，提供 Agent 抽象、LLM 集成、消息系统、工具系统、Middleware 机制——但它是**被嵌入的组件**，不是一个可部署的生产系统。

**yunxi-agent-platform** 在此基础上构建了完整的**生产平台**，增加了以下 **7 层能力**：

### 7 层价值分层

| 层次 | 能力范畴 | 关键代码 | agentscope 内置？ |
|------|---------|---------|:--:|
| **1. Spring Boot 集成层** | 自动配置、Bean 管理、YAML 配置加载 | `AgentscopeAutoConfiguration`、`WebMvcConfig` | 否 |
| **2. 统一治理层** | 审计日志、限流、超时控制、优雅关闭、Pre/Post 扩展 | `AgentGatewayImpl`（网关统一入口） | 否 |
| **3. 统一治理层（网关能力内置）** | 接入层认证/限流/路由由 AgentScope-Java 2.0 Channel + AgentGatewayImpl 承接 | `AgentGatewayImpl`、`agent-core` | 否 |
| **4. 生产特性层** | HITL 人工审核、会话管理、分布式缓存、多租户 | `ContentFilterMiddleware`（提示注入防护）、`ChatAppService` | 否 |
| **5. 模型层** | 复用框架 Model（OpenAI/Claude/DashScope/DeepSeek）+ Baidu/华为适配 | `ModelFactory`、`Model`（框架接口） | 是（框架内置 5 个，自建 2 个） |
| **6. 持久化与记忆体系** | 5 种持久化策略、多种 Repository、Harness 内置记忆 | `PersistenceManager`、`HybridPersistenceStrategy` | 否 |
| **7. 编排与自动装配层** | YAML 配置驱动、两轮初始化、Supervisor/Routing | `AgentConfigurer`（约 1261 行） | 否 |

### 关键接线：具体桥接代码解读

#### 1. AgentGatewayImpl — 统一调用入口

`AgentGatewayImpl` 是**所有 Agent 调用必须经过的唯一入口**，它将外部请求转化为 AgentScope 原生调用并映射事件流 / 中断信号：

```java
// AgentGateway.java (接口) → AgentGatewayImpl.java (默认实现，包内私有类)
public interface AgentGateway {
    // 以流式事件方式调用 Agent（注入 RuntimeContext 实现多租户隔离）
    Flux<AgentEvent> callStream(String agentName, String message, String userId, String sessionId);

    // 中断指定 Agent 的当前执行（AgentScope 原生协作式中断，下次调用自动恢复）
    void interrupt(String agentName);
}
```

```java
// AgentGatewayImpl.java (核心实现)
@Override
public Flux<AgentEvent> callStream(String agentName, String message, String userId, String sessionId) {
    Agent agent = agentService.getAgentInstance(agentName);
    RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    // streamEvents 是 HarnessAgent/ReActAgent 的原生方法（Agent 接口未声明），需转型调用；
    // 重载签名为 streamEvents(List<Msg>, RuntimeContext)（非单 Msg）。
    HarnessAgent harnessAgent = (HarnessAgent) agent;
    return harnessAgent.streamEvents(
            List.of(Msg.builder().textContent(message).build()), ctx);
}

@Override
public void interrupt(String agentName) {
    log.info("中断 Agent: {}", agentName);
    agentService.getAgentInstance(agentName).interrupt();   // 委托给 AgentScope 原生 Agent.interrupt()
}
```

**关键点**：网关自身不实现限流/超时/审计等治理逻辑——这些由 AgentScope 框架的 Middleware 体系（`GracefulShutdownMiddleware`、`OtelTracingMiddleware` 等）在 Agent 执行链路中承载，网关只做"请求转换 + 事件透出 + 中断透传"三件事，保持薄适配。

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

这本质上是一个 **Agent 容器**——读取 YAML、创建 ModelProvider、构建 HarnessAgent、注册工具、注入 Middleware。AgentScope 只提供了 `HarnessAgent.builder()`，但"怎么把几十个 YAML 配置变成可运行的 Agent 实例"这件事，完全是平台层的。

#### 3. 工具注册 — @Tool 注解直接接入 AgentScope

工具通过 Spring `@Component` + AgentScope `@Tool` 注解直接注册：

```java
@Component
public class DatabaseTool {
    @Tool(name = "database_query", description = "执行 SQL 查询")
    public String query(@ToolParam(description = "SQL 语句") String sql) {
        return jdbcTemplate.queryForList(sql).toString();
    }
}
```

`Toolkit` 在 Agent 装配时通过 `registrar.setToolComponentSupplier()` 扫描所有带有 `@Tool` 注解的 Spring Bean 并注册。**无需**定义额外的 `Tool` 接口或 `ToolAdapter` 桥接层——AgentScope 的 `AgentTool` 接口已由框架 `Toolkit.registerTool(Object bean)` 自动处理转换。

#### 4. ModelFactory — 统一模型工厂

```java
// ModelFactory 统一经框架 ModelRegistry 创建，不复用自建 Provider 接口
@Component
public class ModelFactory {

    @PostConstruct
    public void init() {
        // 内置 Provider（openai/dashscope/anthropic/claude/deepseek）通过 ModelRegistry
        // 内置工厂创建（框架按 provider:modelName 模式自动匹配）
        // baidu / huawei 框架未内置，用自定义 Provider 注册为工厂，路径与内置完全一致：
        ModelRegistry.registerFactory("baidu:.+", (id, ctx) -> {
            String name = id.substring("baidu:".length());
            return new BaiduModelProvider(ctx.getApiKey(), ctx.getApiKey(), name,
                    ctx.component(GenerateOptions.class)); // 实际含 null → 全局默认 fallback
        });
        ModelRegistry.registerFactory("huawei:.+", (id, ctx) -> {
            String name = id.substring("huawei:".length());
            return new HuaweiModelProvider(ctx.getApiKey(), ctx.getApiKey(), name,
                    ctx.component(GenerateOptions.class));
        });
    }

    public Model create(AgentModelConfig config) {
        // 所有 Provider（含 baidu/huawei）统一走 resolve，由 ModelRegistry 分派工厂
        ModelCreationContext ctx = buildContext(config);
        return ModelRegistry.resolve(provider + ":" + modelName, ctx);
    }
}
```

框架的 `Model` 接口负责"发请求、拿响应"，内置了正确的角色映射（`SYSTEM`/`USER`/`ASSISTANT`/`TOOL`）和 Prompt Caching 支持（`cache-control: true` 自动添加 `cache_control: {"type": "ephemeral"}`）。平台层保留百度/华为的自建实现（因认证协议不兼容标准 OpenAI），但已修复角色映射 Bug，现通过 `ModelRegistry` 工厂注册，与内置 Provider 走完全一致的 `ModelRegistry.resolve` 路径（按 Agent 透传 `apiKey`/`options`）。

**拆除自建 Provider**：原 `ChatModelProvider` 接口 + `OpenAIModelProvider`/`ClaudeModelProvider`/`DashScopeModelProvider` 已删除（约 500 行），全部委托给框架内置实现。详见上文「4. ModelFactory — 统一模型工厂」章节。

### 完整架构对比

```
┌──────────────────────────────────────────────────────────────────┐
│  yunxi-agent-platform                                            │
│                                                                  │
│  第 7 层: 编排与自动装配 (AgentConfigurer)                        │
│    YAML定义 → 两轮初始化 → Supervisor/Pipeline/Routing            │
│  ─────────────────────────────────────────────────────────────── │
│  第 6 层: 持久化与记忆 (PersistenceManager, ChatAppService)  │
│    5种持久化策略 | Harness 内置记忆 | 分布式会话                            │
│  ─────────────────────────────────────────────────────────────── │
│  第 5 层: 模型层 (ModelFactory + 框架 Model 内置 Provider)          │
│    框架: OpenAI/Claude/DashScope/DeepSeek | 自建: 百度/华为      │
│  ─────────────────────────────────────────────────────────────── │
│  第 4 层: 生产特性 (CircuitBreaker, HITL, Audit, Metrics)        │
│    熔断器 | 人工审核 | 审计 | 监控 | 多租户 Profile                │
│  ─────────────────────────────────────────────────────────────── │
│  第 3 层: 接入层 (AgentScope-Java 2.0 Channel: 企微/钉钉/飞书/Web API)             │
│    由 agentscope-extensions-channel-* 原生承载                    │
│  ─────────────────────────────────────────────────────────────── │
│  第 2 层: 统一治理 (AgentGatewayImpl + 框架 Middleware)           │
│    网关薄适配 | GracefulShutdown/Tracing 由框架中间件承载          │
│  ─────────────────────────────────────────────────────────────── │
│  第 1 层: Spring Boot 集成 (AutoConfiguration)                    │
│    @ConditionalOnProperty | Bean注册 | YAML加载                   │
├──────────────────────────────────────────────────────────────────┤
│  AgentScope V2.0 (嵌入式 SDK)                                  │
│                                                                  │
│  HarnessAgent | Agent接口 | Msg | Toolkit | Middleware | State   │
│  这是被嵌入的引擎，不是平台                                             │
├──────────────────────────────────────────────────────────────────┤
│  基础设施: Spring Boot / LLM API / MySQL / Redis / Milvus        │
└──────────────────────────────────────────────────────────────────┘
```

### 封装与增强对比

| 功能 | AgentScope 提供 | yunxi 增强 | 增加的文件数 |
|------|---------------|-----------|:---------:|
| Agent 创建 | HarnessAgent.builder() | 配置驱动 + HarnessAgent 包装 + 自动装配 + DistributedStore | ~15 |
| 工具系统 | @Tool 注解 | 内置业务工具 + Spring 自动扫描注册 | ~6 |
| LLM 集成 | Model (框架接口) + Factory | 复用框架内置 Provider + 百度/华为适配 + 缓存/角色映射支持 | ~3 |
| 记忆 | InMemoryMemory | Harness 内置文件系统记忆 + 5 种持久化策略 + 场景管理 | ~15 |
| MCP | 基础客户端 | 自动重连 + 缓存 + 跨 Agent 共享 + 动态刷新 | ~8 |
| 多 Agent | A2A 协议 | Supervisor/Routing 编排 + Profile 路由 | ~10 |
| 接入层 | 无 | 多通道（飞书/钉钉/企微/WebSocket/SSE）+ 会话 + 认证 | ~16 |
| 生产治理 | 无 | 熔断/审计/监控/HITL/优雅关闭 | ~12 |

### 诚实的评估：哪些代码可以优化？

1. **YAML 配置 → DTO 的转换链**：`AgentDefinition` → `AgentConfigDto` → `AgentInfoDto` 有多层映射，部分可以合并
2. ~~**自建 LLM Provider**~~：✅ **已修复** — 拆除 `ChatModelProvider` 接口及 3 个自建 Provider，复用框架 `ModelRegistry` 工厂机制
3. ~~**自建 Shell 命令安全**~~：✅ **已修复** — 拆除 `CommandSafetyClassifier`，使用框架 `ShellCommandTool` 白名单/验证器
4. **Session 会话管理**：`session/` 包保留（多租户会话管理），分布式协调能力由 AgentScope 原生 `DistributedStore` + `RedisDistributedStore.fromJedis()` 承载，二者职责分离、各司其职
5. ~~**Tracer 废弃适配**~~：✅ **已适配** — 删除 `OpenTelemetryTracer.java`，改用全局 `OpenTelemetry` API
6. **工具注册**：业务工具直接使用 `@Tool` 注解注册，无需单独的接口或桥接层。

但**绝大多数代码是合理的**——它们解决的是不同层次的问题。业务工具只需用 `@Tool` 注解标注就能被 Agent 调用，这才是平台的价值所在。

---

## 状态持久化与优雅关闭

### 状态持久化架构

Agent 运行时状态（Memory、PlanNotebook、消息历史等）由底层框架自动管理，上层无需干预：

```
                         HarnessAgent.call()
                              │
            ┌─────────────────┼─────────────────┐
            ▼                 ▼                  ▼
    PreCallEvent      PostReasoningEvent     PostActingEvent
    (每轮 ReAct)      (每轮推理后)           (每轮工具后)
            │                 │                  │
            ▼                 ▼                  ▼
    GracefulShutdown    CompactionMiddleware     GracefulShutdown
    (去重检测)           (消息压缩)          (checkpoint)
            │
            ▼
      PostCallEvent / ErrorEvent
            │
            ▼
    SessionPersistenceMiddleware (优先级 900)
    → saveTo(session, sessionKey)
    → 递归收集所有 StateModule 的状态
    → 写入 Session 后端
```

**状态数据流向**：

```
Agent 运行时                        Session 后端
┌─────────────────┐               ┌─────────────────────┐
│ InMemoryMemory  │──getState()──▶│  WorkspaceSession   │
│  (StateModule)  │               │  (文件系统, 默认)    │
├─────────────────┤               │                     │
│ PlanNotebook    │──getState()──▶│  RedisSession       │
│  (StateModule)  │               │  (跨实例共享, 可选)  │
├─────────────────┤               └─────────────────────┘
│ 其他 StateModule│
└─────────────────┘
     agent.saveTo(session, sessionKey)
```

**恢复流程**：

```java
// 被中断后重新创建 Agent 并恢复
ReActAgent newAgent = createAgent();
newAgent.loadIfExists(session, sessionKey);
// loadIfExists() 内部调用 setState() 恢复所有 StateModule
```

**配置方式**（`application.yml`）：

```yaml
agentscope:
  core:
    session:
      type: redis   # workspace（默认）| redis
```

### 存储分层

| 数据 | 存储后端 | 职责 | 查询方式 |
|------|---------|------|---------|
| Agent 运行时状态 | Session（workspace/redis） | 崩溃恢复、弹性迁移 | `agent.loadIfExists()` |
| 会话元数据 | MySQL + Redis（ChatAppService） | 前端列表展示、标题搜索 | REST API |
| 长期记忆 | workspace/agents/{agentName}/ 下的记忆文件 | 跨会话知识积累 | HarnessAgent 内部 Middleware |

三个存储层各司其职，不重复。Session 负责运行时恢复，ChatAppService 负责前端查询，文件系统记忆负责 LLM 可读的上下文。

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
- Entity：Agent、Session
- Domain Service：ChatAppService、AgentConfigurer

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
  workspace: ./workspace/agents/new-business-agent
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
// AgentService：只负责 Agent 生命周期
// ChatAppService：只负责对话编排
```

---

**上一页**: [03. 核心概念](./03-concepts.md)  
**下一页**: [05. 模块说明 →](./05-modules.md)
