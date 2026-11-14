# 03. 核心概念

> **核心概念更新**：yunxi-agent-platform 基于 **AgentScope-Java 2.1.0-BETA1**。该版本将 Hook 体系替换为 Middleware 体系（拦截点），`Tracer`/`TracerRegistry` 已废弃（改用 OpenTelemetry 直连 API），`ModelRegistry` 提供统一模型工厂机制，`Event`/`EventType` 已替换为 `AgentEvent`/`AgentEventType`。包结构已扁平化（移除 framework/infra 分层），编排支持 single/supervisor/pipeline/routing 四种模式，Skill 系统采用框架原生 `AgentSkillRepository`。`Session` 包保留（承担会话管理），分布式协调由 `DistributedStore` 承担。`Model.stream()` 为 Model 层调用方式，`Agent.streamEvents()` 为 Agent 层事件流 API。

## Agent

### 定义

Agent 是具备自主决策能力的智能实体，能够：
1. **理解目标**：明确需要完成的任务
2. **制定计划**：将目标分解为可执行的步骤
3. **执行行动**：调用工具或生成回复
4. **反思改进**：根据反馈调整策略

### Agent 的组成

Agent = LLM（推理决策）+ 记忆（历史与经验）+ 工具（与外部世界交互）+ 提示词（行为指令）。

### 在本框架中的实现

本框架中，Agent 通过 YAML 配置定义（扁平 `AgentSpec`，由 `AgentDefinitionLoader` 扫描 `classpath*:agent-definitions/**/*.yml` 加载，一个文件一个 Agent）：

```yaml
# agent-definitions/business-assistant.yml
identity:
  agentId: business-assistant     # 必填，Agent 唯一标识，同时作为 ModelRoutingPolicy 路由键
  name: 业务数据管理助手           # 必填，展示名
  description: 你是业务专家，用简洁准确的中文回答用户问题。   # 作为系统提示词
mcp:
  servers: [mcp-nutrition]        # 订阅 mcp.yml 中 yunxi.mcp.static-servers 的命名服务器
state:
  store: inmemory
  persist: false
permission:
  toolGate:
    enabled: false
    tools: []
    message: ""
# 模型不在 AgentSpec 中声明，统一由 yunxi-config/config/models.yml 的 routing 段按 identity.agentId 解析（未声明则走自动选型）
# 多 Agent 编排（supervisor / pipeline / routing）、任务迭代上限、RAG 模式等由 AgentScope-Java 框架能力支撑，不在当前 AgentSpec 字段内声明
```

---

## 知识库 (RAG)

### 应用层 RAG（ApplicationRAG）

RAG（Retrieval-Augmented Generation）将信息检索与文本生成结合：Agent 回答前先从知识库检索相关信息，作为上下文交给 LLM 生成回答。

**说明**：V2.0（AgentScope-Java 2.0）升级后，原 `knowledge-bases` 配置段（bailian/dify/ragflow/simple）及对应的 `KnowledgeAutoConfiguration` / `*KnowledgeCreator` 已删除（框架 `io.agentscope.core.rag` 包整体 `@Deprecated(forRemoval=true)`）。应用层 RAG 由 AgentScope-Java 框架 `io.agentscope.core.rag` 包承担，yunxi 不自建 RAG 中间件。

### 工作模式

RAG 由 AgentScope-Java 框架 `io.agentscope.core.rag` 提供，检索结果经框架 Middleware 注入对话（yunxi 不自建 RAG 中间件）：

| 模式 | 行为 | 注入位置 |
|------|------|---------|
| `NONE` | 不启用 RAG | — |
| `GENERIC` | 检索结果注入系统提示前缀，LLM 生成回答时参考（默认） | 消息列表头部 |
| `AGENTIC` | 检索结果注入用户消息，Agent 自主决定如何使用 | 消息列表头部 |

- 检索基于用户最新查询文本（从输入消息中提取），按 `userId` 隔离
- 默认 `topK=5`，命中为空时跳过注入
- **Milvus 未启用时向量检索不可用，RAG 自动降级为透传**，不影响 Agent 正常对话

### 开启方式

> **当前 yunxi `AgentSpec`（`identity` / `skill` / `mcp` / `state` / `permission` / `toolkit`）不含 `ragMode` 字段**。RAG 模式（`NONE` / `GENERIC` / `AGENTIC`）由 AgentScope-Java 的 `ReActAgent.Builder.ragMode(RAGMode)` 构建期方法控制（该 API 自 2.0.0 起已 `@Deprecated(forRemoval=true)`，且 rag 核心包已移除，检索应放在应用层集成），yunxi 现不在 `AgentConfigurer` 装配路径设置该值，统一走框架默认 `GENERIC`，RAG 检索在配置向量库后始终生效。请勿在 AgentSpec YAML 中写 `agent.ragMode`（旧 schema，会被忽略）。

对话请求体为 `ConversationRequest`（`yunxi-ingress`）：`agentId` / `userId` / `sessionId` / `text` / `protocol` / `context`，**不含 `ragMode` / `message` / `agentName` 字段**；同步用 `POST /api/conversations`，流式用 `POST /api/conversations/stream`（SSE）。若需请求级覆盖 RAG 模式，需框架在请求协议层暴露对应字段，当前 yunxi 未暴露，故统一走框架默认配置。

---

## 场景 (Scene)

### 在本框架中的实现

场景识别由**意图引擎**（`io.yunxi.intelligence.IntentEngine`）承载：`yunxi-intelligence` 模块提供一个关键词 → Agent 的极简路由库（`DefaultIntentEngine` 组合多个 `IntentResolver`，内置 `KeywordIntentResolver`），按关键词命中返回 `Intent`（`label` / `confidence` / `targetAgentId`）并路由到目标 Agent。意图引擎已**接入对话请求主链路**：入口织面 `IngressGateway` 在请求未显式指定 `agentId` 时调用 `IntentEngine.resolve(text)` 推断目标 Agent 并回填，从而把「未指定 Agent 的通用请求」路由到合适 Agent；显式指定 `agentId` 的请求优先级更高，不触发意图解析。路由规则经 `yunxi.intent.*` 配置（默认兜底到 `example-assistant`，运营可在 `config/intent.yml` 以 `rules` 增删关键词规则，无需改码）。完整的字段结构与扩展方式见 [15. 意图引擎](./15-intent-engine.md)。

> yunxi 不维护场景注册表类（`MemoryScene` / `MemorySceneRegistry` / `SceneDetectionService` 均不存在于当前代码），意图引擎当前实现也不产出 `sceneName`；场景化记忆由框架 Harness 统一管理（见下方「场景化记忆」）。

---

## Profile (配置档)

Profile 是 AgentScope-Java 框架原生能力：一个 Agent 可定义多个 Profile，每个 Profile 是一组配置参数的集合，代表 Agent 的一种工作模式。

### Profile 的定义

Profile 包含以下关键属性：

| 属性 | 说明 | 示例 |
|------|------|------|
| **name** | Profile 名称，唯一标识 | `chat`, `business-make` |
| **label** | 展示名称 | `智能咨询`, `内容生成` |
| **description** | 描述 | `回答业务咨询问题` |
| **mode** | 内置模式选择 | `chat`, `expert`, `advanced` |
| **prompt** | 专用 system prompt | 覆盖 Agent 级别的 prompt |
| **toolGroups** | 激活的工具组列表 | `[search, database]` |
| **mcpServers** | MCP 服务器列表 | `[formfill, milvus]` |
| **maxIters** | 最大迭代次数 | `3`, `25` |
| **skillConfig** | 专家配置 | 多智能体协作时的专家列表 |

## 前端执行引擎（PageAgentDomEngine）

**核心问题**：后端 Agent 能产出结构化填表数据，但「如何把数据精准写入用户眼前真实渲染的页面」无法在后端完成——后端看不到 React/Vue 的响应式表单、JS 动态渲染的下拉项、条件显示的隐藏输入框。

**解决方案**：前端部署一个纯 DOM 执行引擎（`PageAgentDomEngine`），仅负责读字段（scan）和写字段（fill），不含任何 LLM 决策逻辑。后端 AgentScope 通过 `mcp-formfill` WebSocket 下发结构化指令，`FormFillClient` 桥接层调用 DomEngine 写入页面。

**职责边界**：
- 后端 Agent 负责「想什么」：多工具编排、业务推理、长任务规划；
- 前端 DomEngine 负责「怎么操作页面」：真实 DOM 感知、即时视觉反馈、本地失败重试。
- 前端执行引擎不持有 LLM 决策循环（原 Path A 的浏览器端 Agent 循环已退场），避免形成决策孤岛、无法与后端其他工具协作。

**关键能力**：`scan`（零依赖读取真实页面字段）、`fill` / `batchFill`（兼容 React/Vue 原生事件派发）、`click`、`highlight`（脉冲高亮反馈）。详见 [05. 模块总览 - agent-web-sdk](./05-modules.md) 与 [07. 实战示例](./07-development.md)。

---

### Profile 的覆盖规则

```
Agent 级别默认配置
    │
    ▼
┌─────────────────────────────────────┐
│  Profile 继承 Agent 默认值           │
│  - 未设置的字段继承 Agent 级别配置    │
│  - 显式设置的字段覆盖 Agent 配置     │
└──────────┬──────────────────────────┘
           ▼
┌─────────────────────────────────────┐
│  Mode 展开为具体参数                 │
│  - CHAT    → 应用聊天默认值          │
│  - EXPERT  → 应用专家默认值          │
│  - ADVANCED→ 保留业务自定义          │
└──────────┬──────────────────────────┘
           ▼
┌─────────────────────────────────────┐
│  业务显式配置覆盖 mode 默认          │
│  - 业务配置优先级最高                │
└─────────────────────────────────────┘
```

### 在本框架中的实现

> **当前 yunxi `AgentSpec` 不含 `profiles` / `prompt` / `orchestration` 字段**。`Profile`（一个 Agent 多工作模式）是 AgentScope-Java 框架原生能力，其声明位置与覆盖规则由框架定义，不在当前 AgentSpec YAML schema 内（系统提示词经 `identity.description` 承载，非独立 `prompt` 字段）。下方为 Profile 概念示意，不可直接抄写为 AgentSpec 字段：

```yaml
# Profile 概念示意（框架原生能力，非 yunxi AgentSpec 字段）
profiles:
  chat:                         # Profile 1：聊天模式
    label: 智能咨询
    description: 面向用户的对话模式
    prompt: 你是一个业务顾问...

  business-make:                # Profile 2：内容生成模式
    label: 内容生成
    description: 面向内容生成的专家模式
    prompt: 你是内容生成专家...
```

---

## Mode (模式)

框架预定义四种内置模式，业务层只需选择模式，无需理解底层参数。

### 四种内置模式

四种模式的核心区别在于**两个正交维度**：工具（有/无）和智能体（单/多）。

```
              工具
          ┌─────┴─────┐
         无工具      有工具
          │           │
     ┌───chat──┐  ┌──tool──┐     ← 单智能体
     │ 最快    │  │ 快速   │
     │ 纯对话  │  │ 有工具  │
     └─────────┘  └───┬────┘
                      │ 需要多专家协作？
                      │
                   ┌──┴──┐
                   │ 是  │ 否
                   │     │
               ┌─expert─┐
               │ 全功能  │
               │ 多专家  │
               └────────┘

     advanced: 完全自定义，不受模式约束
```

### 模式对比

| 维度 | CHAT | TOOL | EXPERT | ADVANCED |
|------|------|------|--------|----------|
| 工具 | 无 | 有 | 有 | 自定义 |
| 智能体 | 单 | 单 | 多（Supervisor） | 自定义 |
| 规划 | 无 | 可选 | 有 | 自定义 |
| 速度 | 最快 | 快 | 较慢 | 取决于配置 |
| 适用场景 | 咨询/问答 | 有工具的单步任务 | 复杂多步推理 | 特殊需求 |

### 各模式的默认参数

| 参数 | CHAT | EXPERT | ADVANCED |
|------|------|--------|----------|
| `toolGroups` | `[]`（无工具） | 全部已注册组 | 业务自定义 |
| `mcpServers` | `[]` | 全部已配置 | 业务自定义 |
| `maxIters` | `3` | `25` | 业务自定义 |
| `enablePlanNotebook` | `false` | `true` | 业务自定义 |
| `enableMetaTool` | `false` | `true` | 业务自定义 |
| `skillConfig.experts` | 无 | 按配置加载 | 业务自定义 |

### 在本框架中的实现

```java
/**
 * 内置模式枚举 — 框架预定义
 * 业务层通过 YAML 的 mode 字段选择，无需理解底层参数。
 */
public enum BuiltinMode {
    CHAT,     // 纯对话，不调用任何工具
    TOOL,     // 单智能体 + 工具
    EXPERT,   // Supervisor + 多专家智能体
    ADVANCED  // 业务完全自定义
}
```

---

## 记忆系统 (Memory System)

### 记忆分层

记忆系统为 Agent 提供持久化和结构化的记忆能力，支持短期记忆、长期记忆和场景化记忆管理。框架委托 `agentscope-harness` 模块管理 Agent 运行时记忆，业务层直接通过框架记忆 API 与工作区文件访问记忆。

```
┌─────────────────────────────────────────┐
│           记忆系统架构                    │
├─────────────────────────────────────────┤
│                                         │
│  ┌─────────────────────────────────┐    │
│  │  HarnessAgent（运行时记忆管理）    │    │
│  │  - MemoryFlushMiddleware        │    │
│  │  - MemoryMaintenanceMiddleware   │    │
│  │  - CompactionMiddleware          │    │
│  └───────────┬─────────────────────┘    │
│              │                          │
│     ┌────────┴────────┐                │
│     ▼                 ▼                 │
│  ┌─────────┐   ┌───────────┐           │
│  │Scene 检测│   │ ReMe 记忆  │           │
│  │(场景化)  │   │ (持久化)   │           │
│  └─────────┘   └─────┬─────┘           │
│                       │                 │
│              ┌────────┼────────┐        │
│              ▼        ▼        ▼        │
│         ┌────────┐┌────────┐┌────────┐ │
│         │Working ││ Task   ││ Tool   │ │
│         │Memory  ││ Memory ││ Memory │ │
│         └────────┘└────────┘└────────┘ │
│                                         │
└─────────────────────────────────────────┘
```

### HarnessAgent 记忆管理（运行时，V2.0 Middleware 模式）

HarnessAgent 通过内置 Middleware 自动管理 Agent 执行过程中的记忆持久化：

| Middleware | 职责 |
|------|------|
| `MemoryFlushMiddleware` | 每次 Agent 调用完成后将记忆刷新到持久化存储 |
| `MemoryMaintenanceMiddleware` | 定期归档和压缩记忆文件，防止无限增长 |
| `CompactionMiddleware` | 上下文溢出时通过 LLM 摘要压缩，然后重试调用 |
| `ToolResultEvictionMiddleware` | 将过大的工具调用结果卸载到文件系统 |

跨会话长期记忆直接复用框架原生的 `MEMORY.md` 机制（`MemoryFlushMiddleware` / `MemoryMaintenanceMiddleware` + 内置 `memory_search` / `memory_get` / `session_search` 工具），yunxi 不再自建跨会话长期记忆存储，避免与框架原生机制形成双份真相（保持产品平面纯净，不重复实现框架已有能力）。会话内上下文压缩（`CompactionMiddleware`）不受影响。

#### 记忆管理的架构分层

记忆系统由「会话内上下文压缩（框架底层）」与「跨会话长期记忆（yunxi 应用平面）」两层职责构成：

| 层 | 所属项目 | 职责 | 可控性 |
|---|---|---|---|
| 会话内上下文压缩 | `agentscope-harness`（外部依赖） | `CompactionMiddleware` 将当前会话历史压成会话级摘要，释放上下文窗口 | 不可直接修改，但 yunxi 经 `MemoryProperties.compaction` 显式拥有阈值 |
| 跨会话长期记忆 | `agentscope-harness`（外部依赖，yunxi 复用） | 框架原生 `MEMORY.md` 文件系统记忆（`MemoryFlushMiddleware`/`MemoryMaintenanceMiddleware` 沉淀，内置工具召回）；yunxi 经 `MemoryProperties` 拥有阈值与刷新策略，不另建存储 | 不可直接修改存储实现，但阈值 / 刷新可由 `yunxi.memory.*` 配置 |

**复用框架原生记忆（不另建）**：yunxi 直接复用框架原生的 `MEMORY.md` 跨会话记忆，故不存在与框架原生机制的重叠，也不需关闭框架原生记忆工具（`disableMemoryTools` / `disableMemoryHooks` 不再使用）。框架是固定基线，跨会话记忆由其原生实现承载，yunxi 只经 `MemoryProperties` 配置阈值与刷新策略，符合产品平面纯净原则（不重复实现框架已有能力）。

**典型问题处理链路（以 Agent 回复 verbose 为例）：**

```
框架原生 MEMORY.md 沉淀（MemoryFlushMiddleware 写侧，多副本经共享存储一致）
  ↓ 持久化的跨会话事实（工作区文件系统记忆）
  ↓
内置 memory_search / memory_get 读侧召回，注入系统提示词作为参考上下文
  ↓
系统提示词约束（优先级更高）
  └─ food-chat.yml 中已添加：
     "不要使用 emoji"、"不要营销话术"、"不要提及服务器路径"
  ↓
Agent 最终回复 → 遵循系统提示词，过滤掉 verbose 风格
```

**要点：**
- 跨会话长期记忆直接由框架原生 `MEMORY.md` 承载，yunxi 不另建存储；系统提示词的约束优先级仍高于记忆参考上下文，即使记忆沉淀带有 verbose 风格，Agent 仍会按提示词要求输出干净回复。
- 记忆沉淀 / 合并的粒度由框架 `MemoryConsolidator` 与 `MemoryProperties` 阈值控制，如需调整抽取粒度或事实格式，改框架记忆配置（`yunxi.memory.*`）即可，不修改 yunxi 代码。
- 记忆文件的整理与淘汰由框架 `MemoryMaintenanceMiddleware` 负责，yunxi 经 `MemoryProperties` 配置刷新触发与节流，不必直接删文件。

### 场景化记忆

yunxi 不单独维护场景注册表类（`MemoryScene` / `MemorySceneRegistry` 均不存在于当前代码）。跨会话长期记忆复用框架原生 `MEMORY.md`，yunxi 不自建记忆层，业务层经框架内置 `memory_search` / `memory_get` 工具接入，不直接操作底层存储文件。

意图引擎当前实现（`Intent` 仅含 `label` / `confidence` / `targetAgentId`，见 [15. 意图引擎](./15-intent-engine.md)）不产出 `sceneName`，也未接入口层；如需按场景差异化记忆，应使用框架原生记忆的多工作区 / 多 Agent 隔离能力，而非在 yunxi 自建记忆层扩展。

> **说明**：跨会话长期记忆的单一真相源是框架原生 `MEMORY.md` 文件系统记忆（由 `agentscope-harness` 实现，yunxi 经 `MemoryProperties` 配置）；yunxi 不另建跨会话记忆存储，符合产品平面纯净原则（不重复实现框架已有能力）。

### Harness 内置文件系统记忆

框架集成 agentscope-harness 后，使用双层文件系统记忆替代了独立的 ReMe 服务：

- **每日日志**：`memory/YYYY-MM-DD.md`，每次对话后 LLM 提取事实追加写入
- **精选记忆**：`MEMORY.md`，定期合并去重
- **检索**：通过 `memory_search` / `memory_get` Agent 工具进行关键词检索（Harness 内置工具）
- 业务层直接访问工作区文件即可读写记忆，无需中间协调器

---

## MCP 协议

MCP（Model Context Protocol）是 LLM 与工具之间的标准化接口，统一工具定义与调用方式，工具独立部署、Agent 动态发现，工具自主控制访问权限。

### 工具定义示例

```json
{
  "name": "query_database",
  "description": "查询数据库",
  "parameters": {
    "type": "object",
    "properties": {
      "sql": {
        "type": "string",
        "description": "SQL 查询语句"
      }
    },
    "required": ["sql"]
  }
}
```

### 调用流程

```
┌─────────┐     ┌─────────┐     ┌─────────┐     ┌─────────┐
│  Agent  │────→│MCP Client│────→│MCP Server│────→│  工具   │
│         │     │         │     │         │     │         │
│ 1.决定  │     │ 2.构造  │     │ 3.解析  │     │ 4.执行  │
│ 调用工具 │     │ JSON-RPC│     │ 请求    │     │ 逻辑    │
│         │←────│         │←────│         │←────│         │
│ 5.接收  │     │ 6.解析  │     │ 7.构造  │     │ 8.返回  │
│ 结果    │     │ 响应    │     │ 响应    │     │ 结果    │
└─────────┘     └─────────┘     └─────────┘     └─────────┘
```

### 在本框架中的实现

**自定义工具注册**：使用 AgentScope 原生 `@Tool` / `@ToolParam` 注解，Spring `@Component` 自动扫描注册：

```java
@Component
public class MyBusinessTools {
    @Tool(name = "database_query", description = "执行只读SQL查询，获取数据库数据")
    public String query(
            @ToolParam(name = "sql", description = "SQL查询语句（仅支持SELECT）") String sql,
            @ToolParam(name = "limit", description = "最大返回行数，默认为500") Integer limit) {
        // 执行查询
        return result;
    }
}
```

> **说明**：注解位于 `io.agentscope.core.tool.Tool` / `io.agentscope.core.tool.ToolParam`，方法需为 `public` 且返回 `String`（或自动转换）。yunxi 不内置业务工具类，业务工具由你的 `@Component` + `@Tool` 提供；MCP 服务器工具则通过 AgentSpec 的 `mcp.servers` 订阅命名服务器 + `AgentConfigurer` 经 `McpDirectory` 按名解析注册为工具组。

---

## SPI 扩展机制

SPI（Service Provider Interface）是框架定义扩展点、业务方实现并运行时加载的插件化机制。

### SPI 工作流程

yunxi 的扩展遵循「框架原生扩展点 + yunxi 契约平面」两层：

1. AgentScope-Java 框架提供横切扩展点（`Middleware` / `Tool` / `ModelProvider` / `Store` / `Formatter`），通过 Spring 自动扫描与 `ObjectProvider<T>` 注入，由 yunxi 统一装配点（`AgentConfigurer`）统一拾取。
2. `yunxi-definition` 是当前唯一契约平面，承载与框架技术无关的契约类型（`AgentSpec` / `AgentSpecValidator` / `SpecCodec` / `YamlSpecCodec`），不定义业务 SPI 子包。

### 核心扩展点

| 扩展点 | 用途 | 说明 |
|--------|------|------|
| **Agent 定义 YAML** | 配置 Agent 行为 | `classpath*:agent-definitions/**/*.yml`（顶层即 `AgentSpec` 字段，无 `agent:` 包裹；由 `yunxi-definition` 的 `YamlSpecCodec` 反序列化为 `AgentSpec`，`AgentDefinitionLoader` 加载并校验后注册进 `ConfigCenter`） |
| **@Tool 注解** | 注册自定义工具 | Spring `@Component` + `@Tool` 注解，框架自动扫描 |
| **Middleware / ModelProvider / Store / Formatter** | 框架横切扩展 | AgentScope-Java 原生扩展点，yunxi 经 `AgentConfigurer` 统一装配（对所有协议生效） |
| **IntelligentLlmService** | 简化 LLM 调用封装 | `yunxi-intelligence` 模块 `io.yunxi.intelligence` 包 |

### 分层职责

```
┌─────────────────────────────────────────┐
│           Business 业务层                │
│  - 实现框架扩展点 (@Tool / Middleware)    │
│  - 编写业务逻辑                          │
├─────────────────────────────────────────┤
│  扩展点 ←── 实现                         │
├─────────────────────────────────────────┤
│      AgentScope-Java 框架层              │
│  - 30+ 个扁平功能包提供横切扩展点        │
│  - 调用扩展实现、编排业务流程             │
├─────────────────────────────────────────┤
│  扩展点 ←── 实现                         │
├─────────────────────────────────────────┤
│       yunxi-definition 契约平面          │
│  - AgentSpec / AgentSpecValidator /      │
│    SpecCodec / YamlSpecCodec             │
│    （技术无关契约类型）                    │
└─────────────────────────────────────────┘
```

---

## 数据流

### 请求处理完整流程

```
用户请求
    ↓
┌─────────────────────────────────────────┐
│ 1. ConversationController（yunxi-ingress）        │
│    POST /api/conversations（同步）或 /api/conversations/stream（SSE） │
│    - 解析 ConversationRequest（agentId / text / userId / sessionId） │
│    - 认证（Spring Security）                        │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 2. ConversationController / IngressGateway (对话入口 + 协议适配) │
│    - 按 agentId 路由（意图引擎为关键词路由，当前未接入口层，见 16）       │
│    - （RAG 检索在配置向量库后由框架自动生效，入口层不选择 ragMode）                │
│    - 组装 AgentCommand(userId, sessionId)  │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 3. ExecutionFacade (执行编排)             │
│    execute(AgentCommand)                  │
│    - 协议无关 Middleware（审计/HITL/追踪/限流）│
│    - 调用 streamEvents + 事件适配         │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 4. HarnessAgent (AgentScope 运行时)      │
│    - MiddlewareChain 执行                │
│      (ApplicationRAG / Compaction /     │
│       MemoryFlush / Tracing ...)        │
│    - ReAct 推理循环 (LLM + 工具决策)     │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 5. 工具层                                │
│    - 业务 @Tool（你的 @Component）+ MCP 服务器工具（远程转发） │
└─────────────┬───────────────────────────┘
              ↓
   AgentEvent 流 → SSE 响应 → 返回结果
```

---

## 安全模型

### 分层安全架构

```
┌─────────────────────────────────────────┐
│  接入层安全                               │
│  - HTTPS 传输加密                         │
│  - 请求头认证 (X-User-Id)                 │
│  - IP 白名单                              │
├─────────────────────────────────────────┤
│  应用层安全                               │
│  - 权限控制 (RBAC)                        │
│  - 输入校验                               │
├─────────────────────────────────────────┤
│  数据层安全                               │
│  - 敏感信息脱敏                           │
│  - 数据访问审计                           │
│  - 加密存储                               │
└─────────────────────────────────────────┘
```

---

**上一页**: [02. 快速开始](./02-quickstart.md)  
**下一页**: [04. 架构设计 →](./04-architecture.md)
