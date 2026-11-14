# 04. 架构设计

> **架构说明**：yunxi-agent-platform 基于 **AgentScope-Java 2.1.0-BETA1** 构建。底层框架采用扁平化的功能包结构（`agent/`、`config/`、`persistence/`、`gateway/`、`conversation/`、`intent/` 等 30+ 个顶层包），yunxi 侧则以 `yunxi-*` 平面模块组织，详见 [模块说明](./05-modules.md)。Hook 体系已全部迁移为框架原生 Middleware 体系，编排支持 single/supervisor/pipeline/routing 四种模式，Skill 系统采用 AgentScope 原生 `AgentSkillRepository` 托管（框架自动装载）。`Session` 包保留（承担会话管理），分布式协调由 `DistributedStore` 承担；`Tracer`/`TracerRegistry` 已废弃（改用 OpenTelemetry 直连 API）。`Model.stream()` 为 Model 层调用方式，`Agent.streamEvents()` 为 Agent 层事件流 API。

## 架构设计原则

### 什么是软件架构

**软件架构**是系统的高级结构，包括：
- **组件**：系统的组成部分（模块、类、服务）
- **关系**：组件之间的连接和交互方式
- **原则**：设计和演进的指导方针

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
- 顶层功能包（`agent/`、`config/`、`persistence/`、`gateway/` 等）是 AgentScope-Java 框架的内部扁平包，定义框架原生 SPI 扩展点
- `yunxi-definition` 模块是当前唯一契约平面，承载与框架技术无关的契约类型（`AgentSpec` / `AgentSpecValidator` / `SpecCodec` / `YamlSpecCodec`）
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

**定位**：第三方 SDK 依赖（AgentScope-Java 2.1.0-BETA1），不可修改

**职责**：
- 提供 Agent/Model/Toolkit/Middleware/State 核心抽象
- ReActAgent / HarnessAgent 运行时（HarnessAgent 与 ReActAgent 各自实现 Agent 接口）
- Middleware 洋葱模型、原生技能治理（`AgentSkillRepository`）

#### Platform 平台层

**定位**：核心能力层，提供生产级 Agent 平台服务

**职责**：
- 实现 AgentScope 扩展点
- Agent 编排自动装配（`AgentConfigurer`）
- MCP 协议集成、记忆系统（含运行时动态注册与 Nacos 协调底座）
- 配置管理、持久化、会话管理

**核心包**（`yunxi-runtime` 扁平包 `io.yunxi.runtime`，入口层在 `yunxi-ingress`）：yunxi 的运行时核心能力落在 `io.yunxi.runtime` 扁平包——`AgentConfigurer`（唯一装配点）、`ExecutionFacade`/`DefaultExecutionFacade`（统一执行门面）、`AgentLookup`（框架 `AguiAgentRegistry` 薄委托 seam）、`ModelFactory`、`AgentDefinitionLoader`、协议无关 `middleware`（审计 / HITL / 追踪 / 限流 / 会话工具组激活）。四协议入口与协议适配在 `yunxi-ingress`：`ConversationController` / `IngressGateway` 终结 web 协议并译为 `AgentCommand`；A2A / AG-UI / Chat Completions 由框架 Starter 直接服务（yunxi 仅在 ingress 提供 `ProtocolAdapter` / `WebProtocolAdapter` 处理 web 协议）。IM 渠道（钉钉 / 企微 / 飞书 / GitHub / GitLab / 微信 iLink）/ 编程式 ChatUi 经 `GatewayBootstrap` 接入框架 `HarnessGateway`，是第二条入口路径，详见 [16.8 IM 渠道网关](./16-ingress-gateway.md) 与 [05. 模块说明](./05-modules.md)。

#### Business 业务层

**定位**：业务逻辑层，通过 YAML 配置 + 工作区文件声明

**示例**：
```yaml
# 在 agent-definitions/nutrition-assistant.yml 中声明（扁平 AgentSpec）
identity:
  agentId: nutrition-assistant
  name: 校园餐营养助手
  description: 你是一个校园餐营养助手
mcp:
  servers: [nutrition-data-mcp]   # 订阅 mcp.yml 中同名 static-server（连接信息在 mcp.yml 声明，不在 AgentSpec 内）
# workspace 工作空间由 AgentScope-Java 框架按约定在 .agentscope/workspace/agents/<agentId>/ 下管理，无需在 AgentSpec 声明
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
│       AgentScope-Java 2.1.0-BETA1 核心运行时  │
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

> yunxi 不维护第二份 Agent 注册表。装配后的 `HarnessAgent` 注册进框架协议层 `AguiAgentRegistry`（进程内单例 Agent 真相源，源自 AgentScope-Java `agentscope-extensions-agui`），由应用平面薄委托 seam `AgentLookup`（`io.yunxi.runtime`）统一收口读取，供 `ExecutionFacade` 与各协议入口使用。

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

MCP 工具注册同样由 AgentScope 框架原生处理：`AgentConfigurer` 经 `McpDirectory` 取得服务规格、`McpClientBuilder`（`mcpDirectory.buildClient(...)`）连接 MCP 服务器，`Toolkit.registration().mcpClient(wrapper).group(name).apply()` 注册工具分组；断线重连由 yunxi 侧 `ReconnectingMcpClientWrapper` 兜底（框架 `McpClientBuilder` 不原生重连）。除静态 YAML 配置外，平台提供运行时动态注册能力：经 Nacos 配置中心（dataId `yunxi.mcp-servers.json`，group `YUNXI_MCP_GROUP`）持久化配置并向集群内其他实例广播变更，新增或下线 MCP 服务无需重启平台。

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

**详细内容请参考**：[13. A2A 协议](./13-a2a-protocol.md)

### 多 Profile 路由

当前的 `AgentSpec`（`identity` / `skill` / `mcp` / `state` / `permission` / `toolkit`）**不含 `profiles` 字段**。`Profile`（一个 Agent 多工作模式）是 AgentScope-Java 框架原生能力，其声明与解析由框架完成；yunxi 不另设 `ProfileRouter` 类，也不维护 `AgentDefinition` 契约类型（Agent 定义经 `AgentDefinitionLoader` 加载为 `AgentSpec` 并注册进 `ConfigCenter`）。

> 注：`Profile` 的概念示意见 [03. 核心概念 - Profile](./03-concepts.md)，其声明位置由框架定义，不在当前 AgentSpec YAML schema 内。

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
| **2. 统一治理层** | 审计日志、限流、超时控制、优雅关闭、Pre/Post 扩展 | `ExecutionFacade`（统一执行门面） | 否 |
| **3. 接入层（渠道能力内置）** | IM 渠道认证/限流/路由由 AgentScope-Java Channel 承接（经 GatewayBootstrap→HarnessGateway）；web / AG-UI / A2A / Chat Completions 由 yunxi-ingress 承接 | 框架 Channel + @PreAuthorize | 否 |
| **4. 生产特性层** | HITL 人工审核、会话管理、分布式缓存、多租户 | 协议无关 Middleware（ChatAudit / Hitl / OtelTracing / Resilience，由 AgentConfigurer 统一装配） | 否 |
| **5. 模型层** | 复用框架 Model（DashScope / OpenAI / Anthropic / Gemini / DeepSeek 等，经 `ModelRegistry` 按 `provider:model` 解析；yunxi 不维护第二份 Provider） | `ModelFactory`（薄封装 `ModelRegistry.resolve`）、`Model`（框架接口） | 是（全部由框架提供） |
| **6. 持久化与记忆体系** | 状态存储经框架 `AgentStateStore` 多后端（内存 / JSON 文件 / Redis / JDBC / 对象存储，由 `DistributedRuntimeConfig` 选择）；yunxi 以 `TenantAwareAgentStateStore` 叠加租户隔离；记忆由 Harness 内置 | `AgentStateStore`、`TenantAwareAgentStateStore` | 存储抽象：是；租户装饰：否 |
| **7. 编排与自动装配层** | YAML 配置驱动、两轮初始化、Supervisor/Routing | `AgentConfigurer`（约 1261 行） | 否 |

### 关键接线：具体桥接代码解读

#### 1. ExecutionFacade — 统一执行门面

`ExecutionFacade`（`yunxi-runtime`）是**所有 Agent 调用必须经过的统一执行入口**。对外承接 `ConversationRequest`（由 `yunxi-ingress` 的 `IngressGateway` / `ProtocolAdapter` 从各协议请求翻译而来），对内经 `AgentLookup` 取框架 `AguiAgentRegistry`、富化 `RuntimeContext`、驱动 `agent.streamEvents` 并管理会话轮次闸门（`SessionTurnGate`）：

- 协议无关横切（审计 / HITL / 追踪 / 限流 / 会话工具组激活）由 `AgentConfigurer` 统一装配为框架 Middleware，对所有协议统一生效；
- 协议相关适配（web / A2A 专属请求结构，如 `contextData` / `confirmResults`）只存在于 `yunxi-ingress` 的 `ProtocolAdapter`，不渗透运行时；
- 限流、超时、优雅关闭、链路追踪等治理能力由 AgentScope 框架的 Middleware 体系（`GracefulShutdownMiddleware`、`OtelTracingMiddleware`、`Resilience4j` 熔断等）在 Agent 执行链路中承载。

**关键点**：执行门面只做"Agent 查找 + 上下文富化 + 驱动 streamEvents + 会话闸门"四件事，保持薄适配；Agent 的实际执行、权限、HITL、PlanMode、技能、压缩、追踪均由 AgentScope 完成。

#### 2. AgentConfigurer — 配置驱动的自动装配

```java
// AgentProvisioner.java（启动期触发，@DependsOn("agentDefinitionLoader")）
public void provision() {
    // 遍历 AgentDefinitionLoader 从 classpath*:agent-definitions/**/*.yml 加载的 AgentSpec
    for (AgentSpec spec : agentDefinitionLoader.loadAllSpecs()) {
        HarnessAgent agent = agentConfigurer.configure(spec);   // AgentSpec → Model → 中间件 → HarnessAgent
        aguiAgentRegistry.register(spec.identity().agentId(), agent);  // 注册进框架协议层 AguiAgentRegistry
    }
}
```

这本质上是一个 **Agent 容器**——读取 YAML、创建 ModelProvider、构建 HarnessAgent、注册工具、注入 Middleware。AgentScope 只提供了 `HarnessAgent.builder()`，但"怎么把几十个 YAML 配置变成可运行的 Agent 实例"这件事，完全是平台层的。

#### 3. 工具注册 — @Tool 注解直接接入 AgentScope

工具通过 Spring `@Component` + AgentScope `@Tool` 注解直接注册：

```java
@Component
public class MyBusinessTools {
    @Tool(name = "database_query", description = "执行 SQL 查询")
    public String query(@ToolParam(description = "SQL 语句") String sql) {
        return jdbcTemplate.queryForList(sql).toString();
    }
}
```

`Toolkit` 在 Agent 装配时通过 `registrar.setToolComponentSupplier()` 扫描所有带有 `@Tool` 注解的 Spring Bean 并注册。**无需**定义额外的 `Tool` 接口或 `ToolAdapter` 桥接层——AgentScope 的 `AgentTool` 接口已由框架 `Toolkit.registerTool(Object bean)` 自动处理转换。

#### 4. ModelFactory — 统一模型工厂

yunxi 不维护第二份 Provider 实现，`ModelFactory` 只是 `ModelRegistry` 的薄封装：

```java
@Bean
public ModelFactory modelFactory() {
    return modelId -> ModelRegistry.resolve(modelId);
}
```

模型 id 采用框架约定 `provider:model`（如 `dashscope:qwen-plus`、`ollama:bge-m3`），由 `ModelRegistry` 分派到框架内置 Provider；未内置的提供商经 `ServiceLoader` 从 `META-INF/services/io.agentscope.core.model.spi.ModelProvider` 发现。yunxi 侧不注册 Provider 正则、不自建 Provider，也不包裹 `ChatModelProvider` 之类的私有接口。

框架的 `Model` 接口负责"发请求、拿响应"，内置了正确的角色映射（`SYSTEM`/`USER`/`ASSISTANT`/`TOOL`）与 Prompt Caching 支持（`cache-control: true` 自动添加 `cache_control: {"type": "ephemeral"}`）；多租户 `apiKey` / `baseUrl` 的隔离经 `ModelCreationContext` 传递，缓存行为见 [06. 配置指南 · 模型缓存策略](./06-configuration.md#模型缓存策略modelregistry-cachepolicy)。

### 完整架构对比

```
┌──────────────────────────────────────────────────────────────────┐
│  yunxi-agent-platform                                            │
│                                                                  │
│  第 7 层: 编排与自动装配 (AgentConfigurer)                        │
│    YAML定义 → 两轮初始化 → Supervisor/Pipeline/Routing            │
│  ─────────────────────────────────────────────────────────────── │
│  第 6 层: 持久化与记忆 (Harness 内置记忆 + Session 后端)  │
│    5种持久化策略 | Harness 内置记忆 | 分布式会话                            │
│  ─────────────────────────────────────────────────────────────── │
│  第 5 层: 模型层 (ModelFactory + 框架 Model 内置 Provider)          │
│    框架: OpenAI/Claude/DashScope/DeepSeek | 自建: 百度/华为      │
│  ─────────────────────────────────────────────────────────────── │
│  第 4 层: 生产特性 (CircuitBreaker, HITL, Audit, Metrics)        │
│    熔断器 | 人工审核 | 审计 | 监控 | 多租户 Profile                │
│  ─────────────────────────────────────────────────────────────── │
│  第 3 层: 接入层 (AgentScope-Java Channel：GatewayBootstrap→HarnessGateway)             │
│    由 agentscope-extensions-channel-* 原生承载                    │
│  ─────────────────────────────────────────────────────────────── │
│  第 2 层: 统一治理 (ExecutionFacade + 框架 Middleware)       │
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
| 接入层 | 框架 Channel（GatewayBootstrap→HarnessGateway） | 钉钉/企微/飞书/GitHub/GitLab/微信 iLink + ChatUi 编程式；渠道级验签/入站翻译/出站推送由框架 Channel 承载 | ~16 |
| 生产治理 | 无 | 熔断/审计/监控/HITL/优雅关闭 | ~12 |

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
| 会话元数据 | MySQL + Redis | 前端列表展示、标题搜索 | REST API |
| 长期记忆 | workspace/agents/{agentName}/ 下的记忆文件 | 跨会话知识积累 | HarnessAgent 内部 Middleware |

三个存储层各司其职，不重复。Session 负责运行时恢复，入口层（`ConversationController`）负责前端查询，文件系统记忆负责 LLM 可读的上下文。

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
- Domain Service：AgentConfigurer、ExecutionFacade

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
# 在 agent-definitions/ 目录新增 AgentSpec YAML 文件（扁平 AgentSpec）
# 在工作区目录新建知识/技能文件即可
identity:
  agentId: new-business-agent
  name: 新业务助手
  description: 你是一个新业务助手
mcp:
  servers: [business-mcp]   # 订阅 mcp.yml 中同名 static-server
```

### 4. 单一职责原则 (SRP)

**理论来源**：SOLID 原则

**核心思想**：
- 一个类应该只有一个引起变化的原因
- 一个类只负责一项职责

**实践方式**：
```java
// AgentLookup / 框架 AguiAgentRegistry：只负责 Agent 查询与注册
// ExecutionFacade：只负责对话执行编排
```

---

**上一页**: [03. 核心概念](./03-concepts.md)  
**下一页**: [05. 模块说明 →](./05-modules.md)
