# 03. 核心概念

> **核心概念更新**：yunxi-agent-platform 基于 **AgentScope-Java 2.0.0（GA 正式版）**。该版本将 Hook 体系替换为 Middleware 体系（拦截点），`Tracer`/`TracerRegistry` 已废弃（改用 OpenTelemetry 直连 API），`ModelRegistry` 提供统一模型工厂机制，`Event`/`EventType` 已替换为 `AgentEvent`/`AgentEventType`。包结构已扁平化（移除 framework/infra 分层），编排支持 single/supervisor/pipeline/routing 四种模式，Skill 系统采用框架原生 `AgentSkillRepository`。`Session` 包保留（承担会话管理），分布式协调由 `DistributedStore` 承担。说明：`Model.stream()` 为 Model 层现役调用方式（AgentScope-Java 2.0 未废弃），`Agent.streamEvents()` 为 Agent 层事件流 API，二者属不同层面的接口，并非替代关系。

## 理论基础

### 什么是 Agent 的核心能力

Agent 的核心能力可以用 **"感知-思考-行动"** 循环来概括：

```
┌─────────────────────────────────────────┐
│           Agent 核心能力模型              │
├─────────────────────────────────────────┤
│                                         │
│   感知层          认知层          行动层   │
│  ┌─────┐        ┌─────┐        ┌─────┐  │
│  │输入 │───────→│推理 │───────→│输出 │  │
│  │解析 │        │规划 │        │执行 │  │
│  └─────┘        └─────┘        └─────┘  │
│     ↑              ↑              ↑     │
│     └──────────────┴──────────────┘     │
│              记忆/知识库                  │
│                                         │
└─────────────────────────────────────────┘
```

**感知层**：理解用户输入、解析环境信息
**认知层**：推理、规划、决策
**行动层**：调用工具、生成回复、修改状态

---

## Agent

### 定义

Agent 是具备自主决策能力的智能实体，能够：
1. **理解目标**：明确需要完成的任务
2. **制定计划**：将目标分解为可执行的步骤
3. **执行行动**：调用工具或生成回复
4. **反思改进**：根据反馈调整策略

### Agent 的组成

```
Agent = LLM + 记忆 + 工具 + 提示词

┌─────────────────────────────────────────┐
│                Agent                    │
├─────────────────────────────────────────┤
│  ┌─────────┐  ┌─────────┐  ┌─────────┐ │
│  │   LLM   │  │  记忆   │  │  工具   │ │
│  │ (大脑)  │  │ (经验)  │  │ (手脚)  │ │
│  └────┬────┘  └────┬────┘  └────┬────┘ │
│       │            │            │      │
│       └────────────┼────────────┘      │
│                    │                   │
│              ┌─────▼─────┐             │
│              │  提示词   │             │
│              │ (指令)   │             │
│              └───────────┘             │
└─────────────────────────────────────────┘
```

**LLM（大脑）**：负责推理和决策
**记忆（经验）**：存储历史对话和重要信息
**工具（手脚）**：与外部世界交互的能力
**提示词（指令）**：定义 Agent 的行为和目标

### 在本框架中的实现

本框架中，Agent 通过 YAML 配置定义：

```yaml
agent:
  name: business-assistant
  description: "业务数据管理助手"
  prompt: "你是业务专家..."  # 提示词
  model:
    provider: dashscope
    modelName: qwen-max
    temperature: 0.7
  orchestration: supervisor   # 编排模式: single / supervisor / pipeline / routing
  runtime:
    maxIterations: 10
  toolsGroup:
    systemToolsGroup: default
    mcpServersToolsGroup: mcp-nutrition
  ragMode: GENERIC            # 应用层 RAG: NONE / GENERIC / AGENTIC
  profiles:
    api:
      label: "API 助手"
      description: "面向开发者的 API 助手"
      prompt: "你是 API 专家..."
```

---

## 知识库 (RAG)

### 理论基础：检索增强生成

RAG（Retrieval-Augmented Generation）是一种将信息检索与文本生成相结合的技术。Agent 在回答用户问题前，先从知识库中检索相关信息，再将检索结果作为上下文交给 LLM 生成回答。

```
用户问题
    ↓
┌─────────────────────┐
│  知识库检索          │
│  - 向量相似度搜索     │
│  - 语义匹配           │
└─────────┬───────────┘
          ↓
┌─────────────────────┐     ┌─────────────────┐
│  检索结果            │────→│  LLM 生成回答    │
│  (相关文本片段)       │     │  (结合上下文)    │
└─────────────────────┘     └─────────────────┘
```

### 应用层 RAG（ApplicationRAG）

**说明**：V2.0（AgentScope-Java 2.0）升级后，原 `knowledge-bases` 配置段（bailian/dify/ragflow/simple）及对应的 `KnowledgeAutoConfiguration` / `*KnowledgeCreator` 已删除（框架 `io.agentscope.core.rag` 包整体 `@Deprecated(forRemoval=true)`）。本框架的应用层 RAG 由 `ApplicationRAG`（`io.yunxi.platform.rag`）承担，它复用平台既有的**文件级向量检索后端** `FileVectorService`（Milvus + EmbeddingService），与 `ChatAppService` 共用同一条检索链路。

### 工作模式

`ApplicationRAG.createMiddleware(ragMode, userId)` 通过 AgentScope 原生 `MiddlewareBase.onAgent` 钩子将检索结果注入对话：

| 模式 | 行为 | 注入位置 |
|------|------|---------|
| `NONE` | 不启用 RAG | — |
| `GENERIC` | 检索结果注入系统提示前缀，LLM 生成回答时参考（默认） | 消息列表头部 |
| `AGENTIC` | 检索结果注入用户消息，Agent 自主决定如何使用 | 消息列表头部 |

- 检索基于用户最新查询文本（从输入消息中提取），按 `userId` 隔离
- 默认 `topK=5`，命中为空时跳过注入
- **Milvus 未启用时 `FileVectorService` 不可用，中间件自动降级为透传**，不影响 Agent 正常对话

### 开启方式

RAG 模式可在两处设置（请求级优先）：

1. **Agent 定义级**（`agent-definitions/*.yml` 的 `agent.ragMode` 字段，默认 `GENERIC`）：
```yaml
agent:
  name: business-assistant
  ragMode: AGENTIC   # NONE / GENERIC / AGENTIC
```

2. **请求级**（`POST /api/conversations/chat` 请求体的 `ragMode` 字段，覆盖 Agent 默认值）：
```json
{
  "message": "根据知识库回答：...",
  "agentName": "business-assistant",
  "ragMode": "GENERIC"
}
```

### 在 API 请求中使用

```json
{
  "message": "根据知识库回答：查询产品信息",
  "agentName": "business-assistant",
  "ragMode": "GENERIC"
}
```

> 说明：RAG 知识来源已收敛为「Agent 工作空间文档 + 文件向量检索」（`FileVectorService` + Milvus），请求体不再使用 `knowledgeBases` 字段。上述 `ragMode` 请求级覆盖即为唯一入口，与 Agent 定义 YAML 中的 `ragMode` 默认值联动。

---

## 场景 (Scene)

### 理论基础：上下文管理

**核心问题**：同样的用户输入，在不同场景下需要不同的处理方式。

**示例**：
- 用户说"苹果"：在商品场景指水果，在科技场景指公司
- 用户说"分析"：在报表场景分析指标，在代码场景分析逻辑

### 场景的定义

场景是特定业务上下文下的工作模式，包含：
- **场景标识**：唯一标识符
- **领域归属**：属于哪个业务领域
- **关键词**：触发该场景的关键词
- **上下文组装**：如何准备上下文信息
- **提示词模板**：该场景下的系统提示词

### 场景识别流程

```
用户输入
    ↓
关键词匹配
    ↓
┌─────────────────┐
│ 场景候选列表     │
│ - 场景A (置信度0.8)│
│ - 场景B (置信度0.6)│
│ - 场景C (置信度0.3)│
└────────┬────────┘
         ↓
置信度排序
         ↓
选择最高置信度场景
         ↓
组装场景上下文
         ↓
调用对应 Agent
```

### 在本框架中的实现

场景识别当前由**意图引擎**（`io.yunxi.platform.intent.IntentEngine`）承载：请求到达时，先经 `DomainResolver` 解析领域（多域模型，显式 domain → 规则 → default → base），再走四阶段前置管道（NER → 改写 → 分类 → 映射）产出结构化意图结果，其中 `sceneName` 字段由 `RuleIntentClassifier.detectSceneName` 的三级链计算（自定义场景 → 概念域 → 内置关键词 → `GENERAL`），语义与原 `SceneDetectionService` 严格等价。

原 `SceneDetectionService` 仍保留（`io.yunxi.platform.prompt`），Bean 供存量引用兼容。场景配置由 `MemorySceneRegistry` 管理：内置场景通过 `memory.scene.builtins` 配置字符串声明（格式 `name:displayName:description:retentionDays:keywords`），自定义场景通过 `register()` 在运行时注册，无需重启。意图引擎的完整说明见 [16. 意图引擎](./16-intent-engine.md)。

---

## Profile (配置档)

### 理论基础：多态配置

**核心问题**：同一个 Agent 在不同场景下需要不同的行为模式，但传统做法需要创建多个独立的 Agent 配置。

**Profile 的解决方案**：一个 Agent 可以定义多个 Profile，每个 Profile 是一组配置参数的集合，代表 Agent 的一种工作模式。

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

```yaml
agent:
  name: business-assistant
  description: 业务数据管理助手
  prompt: 你是一个专业的业务数据管理助手...
  orchestration: single   # single / supervisor / pipeline / routing

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

### 理论基础：策略模式

**核心问题**：不同任务对 Agent 的能力要求不同——简单问答不需要工具，复杂任务需要多专家协作。

**Mode 的解决方案**：框架预定义四种内置模式，业务层只需选择模式，无需理解底层参数。

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

### 理论基础：记忆分层

记忆系统为 Agent 提供持久化和结构化的记忆能力，支持短期记忆、长期记忆和场景化记忆管理。框架委托 `agentscope-harness` 模块管理 Agent 运行时记忆，业务层通过 `MemorySceneRegistry` 定义场景分类。

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

当前集成阶段，这些 Middleware 默认**启用**，无需额外配置。如有特殊需求可通过 HarnessAgent.Builder 的 `disableMemoryTools()` 方法关闭 `memory_search` / `memory_get` / `session_search` 三个内置记忆工具。

#### 记忆管理的两层架构

记忆系统由**底层框架层**和**应用框架层**共同完成：

| 层 | 所属项目 | 职责 | 可控性 |
|---|---|---|---|
| **底层框架** | `agentscope-harness`（外部依赖） | MemoryFlushMiddleware → 写 `memory/YYYY-MM-DD.md`（每日流水）<br>MemoryMaintenanceMiddleware → `MemoryConsolidator`（LLM 合并 → MEMORY.md） | 不可直接修改，提示词写死在 `MemoryConsolidator.java` 的 `private static final` 常量中 |
| **应用框架** | `yunxi-agent-platform`（本项目） | Agent 系统提示词中的回答风格约束<br>Agent 定义 YAML 中的行为规范<br>工作区文件的维护和清理 | 完全可控，通过修改 `agent-definitions/*.yml` 的 `prompt` 字段实现 |

**典型问题处理链路（以 Agent 回复 verbose 为例）：**

```
底层框架 MemoryConsolidator (LLM)
  ↓ 生成 verbose 风格的 MEMORY.md（含 emoji、营销话术）
  ↓
Agent 读取 MEMORY.md 作为参考上下文
  ↓
系统提示词约束（优先级更高）
  └─ food-chat.yml 中已添加：
     "不要使用 emoji"、"不要营销话术"、"不要提及服务器路径"
  ↓
Agent 最终回复 → 遵循系统提示词，过滤掉 verbose 风格
```

**要点：**
- 系统提示词的约束优先级高于 MEMORY.md 的参考上下文，即使底层框架生成的 MEMORY.md 带有 emoji 或营销话术，Agent 仍会按提示词的要求输出干净回复。
- 如果 MEMORY.md 积累过多冗余内容，可以直接删除，框架会自动从每日流水中重新合并生成。
- 如需从根本上控制 MEMORY.md 的内容风格，需要在 `agentscope-harness` 中修改 `MemoryConsolidator.java` 的 `CONSOLIDATION_PROMPT`，本文成稿时该提示词不支持从外部配置。

### MemoryScene / MemorySceneRegistry（场景化管理）

MemoryScene 和 MemorySceneRegistry 提供场景化的识别能力，根据不同的业务场景关键词自动匹配场景。

**MemoryScene**：是**常量类**（`io.yunxi.platform.memory.MemoryScene`），预置两个标准场景常量：

| 常量 | 值 | 说明 |
|------|-----|------|
| `PERSONAL_ASSISTANT` | `"personal_assistant"` | 通用个人助手场景 |
| `GENERAL` | `"general"` | 兜底通用场景（未匹配时返回） |

并提供 `isLongTerm(String sceneName)` 静态方法判断场景是否为长期记忆场景（`PERSONAL_ASSISTANT` 为 true）。

**MemorySceneRegistry**：管理全局场景注册表（`io.yunxi.platform.memory.MemorySceneRegistry`）：
- 内置场景通过配置声明：`memory.scene.builtins`（格式 `name:displayName:description:retentionDays:keywords`）
- 自定义场景通过 `register()` 在运行时注册，无需重启
- 根据用户输入关键词自动匹配场景（`detect()`），未匹配时回退到 `GENERAL`

```java
// 注册自定义场景（5 参数）
sceneRegistry.register("school_meal", "校园餐", "校园餐业务场景", 90,
        List.of("校园餐", "营养餐", "菜谱"));

// 检测场景：从用户输入中按关键词匹配
String scene = sceneRegistry.detect("帮我推荐一份校园营养餐");   // → "school_meal"

// 场景查询
boolean longTerm = sceneRegistry.isLongTerm(scene);
int retentionDays = sceneRegistry.getRetentionDays(scene);
List<SceneEntry> scenes = sceneRegistry.getScenes();             // 全部场景
```

> **说明**：场景化识别用于记忆保留策略（不同场景不同保留天数）。框架层记忆由 agentscope-harness 的文件系统记忆（`MEMORY.md` + `memory/*.md`）承载，场景只决定保留策略，不替代框架记忆实现。

### Harness 内置文件系统记忆

框架集成 agentscope-harness 后，使用双层文件系统记忆替代了独立的 ReMe 服务：

- **每日日志**：`memory/YYYY-MM-DD.md`，每次对话后 LLM 提取事实追加写入
- **精选记忆**：`MEMORY.md`，定期合并去重
- **检索**：通过 `memory_search` / `memory_get` Agent 工具进行关键词检索（Harness 内置工具）
- 业务层直接访问工作区文件即可读写记忆，无需中间协调器

---

## MCP 协议

### 理论基础：标准化接口

**类比理解**：
- USB 协议：让不同厂商的设备可以互联互通
- MCP 协议：让不同厂商的 LLM 和工具可以互联互通

**核心思想**：
1. **标准化**：统一工具定义和调用方式
2. **解耦**：工具独立部署，Agent 动态发现
3. **安全**：工具自主控制访问权限

### MCP 协议栈

```
┌─────────────────────────────────────────┐
│  应用层：Tool Definition                 │
│  - 工具名称、描述、参数 Schema            │
├─────────────────────────────────────────┤
│  协议层：JSON-RPC 2.0                    │
│  - 请求格式：{jsonrpc, method, params, id}│
│  - 响应格式：{jsonrpc, result/error, id} │
├─────────────────────────────────────────┤
│  传输层：HTTP / SSE                      │
│  - HTTP：请求/响应式调用                  │
│  - SSE：服务器推送事件                    │
└─────────────────────────────────────────┘
```

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
public class DatabaseTool {
    @Tool(name = "database_query", description = "执行只读SQL查询，获取数据库数据")
    public String query(
            @ToolParam(name = "sql", description = "SQL查询语句（仅支持SELECT）") String sql,
            @ToolParam(name = "limit", description = "最大返回行数，默认为500") Integer limit) {
        // 执行查询
        return result;
    }
}
```

> **说明**：注解位于 `io.agentscope.core.tool.Tool` / `io.agentscope.core.tool.ToolParam`，方法需为 `public` 且返回 `String`（或自动转换）。内置业务工具见 `io.yunxi.platform.tool.impl`（`HttpTool`、`DatabaseTool`、`CalculatorTool`、`NodeTool`、`SessionSearchTool` 等）。MCP 服务器工具则通过 `tools.mcpServers` 配置 + `AgentConfigurer.registerMcpServers` 注册为工具组。

---

## SPI 扩展机制

### 理论基础：依赖倒置与插件化

**依赖倒置原则 (DIP)**：
- 高层模块不应该依赖低层模块，两者都应该依赖抽象
- 抽象不应该依赖细节，细节应该依赖抽象

**插件化架构**：
- 框架定义扩展点（接口）
- 业务方实现扩展点
- 框架运行时加载实现

### SPI 工作流程

```
┌─────────────────────────────────────────┐
│              SPI 工作流程                │
├─────────────────────────────────────────┤
│                                         │
│  1. agent-spi 模块定义接口                │
│     public interface VectorSearchProvider│
│                                         │
│  2. 业务实现接口                          │
│     @Service                             │
│     public class DataVectorSearchProvider│
│         implements VectorSearchProvider  │
│                                         │
│  3. Spring 自动扫描                       │
│     发现并注册所有实现类                   │
│                                         │
│  4. 框架使用实现                          │
│     @Autowired                           │
│     ObjectProvider<VectorSearchProvider> │
│                                         │
│  5. 调用业务逻辑                          │
│     provider.search(query, topK)         │
│                                         │
└─────────────────────────────────────────┘
```

### 核心扩展点

| 扩展点 | 用途 | 说明 |
|--------|------|------|
| **Agent 定义 YAML** | 配置 Agent 行为 | agent-definitions/*.yml（顶层键 `agent:`） |
| **@Tool 注解** | 注册自定义工具 | Spring @Component + @Tool 注解 |
| **CacheProvider** | 缓存 SPI | agent-spi 模块 `cache` 包 |
| **UserProfileProvider** | 用户画像 SPI | agent-spi 模块 `profile` 包 |
| **DatabaseClient / Text2SqlFacade / EmbeddingService** | 数据查询与向量化 SPI | agent-spi 模块 `text2sql` 包 |
| **VectorPersistenceProvider / VectorSearchProvider** | 向量存储与检索 SPI | agent-spi 模块 `vector` 包 |
| **IntelligentLlmService** | 简化 LLM 调用封装 | intelligent/ 模块 |

### 分层职责

```
┌─────────────────────────────────────────┐
│           Business 业务层                │
│  - 实现 SPI 接口 (agent-spi)             │
│  - 编写业务逻辑                          │
├─────────────────────────────────────────┤
│  SPI 接口 ←── 实现                       │
├─────────────────────────────────────────┤
│          Platform 平台层                 │
│  - 30+ 个功能包定义业务 SPI              │
│  - 调用 SPI 实现                         │
│  - 编排业务流程                          │
├─────────────────────────────────────────┤
│  SPI 接口 ←── 实现                       │
├─────────────────────────────────────────┤
│           agent-spi 模块                 │
│  - 定义技术无关的 SPI 接口                │
└─────────────────────────────────────────┘
```

---

## 数据流

### 请求处理完整流程

```
用户请求
    ↓
┌─────────────────────────────────────────┐
│ 1. ConversationController               │
│    POST /api/conversations/chat         │
│    - 解析 UnifiedChatRequest            │
│    - 认证 (SecurityContext)             │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 2. ChatAppService (对话编排)             │
│    - 意图路由 (IntentAwareAgentResolver) │
│    - RAG 模式选择 (ragMode)             │
│    - 组装 RuntimeContext(userId, sessionId) │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 3. AgentGatewayImpl (统一网关)           │
│    callStream(agentName, message, ...)  │
│    - 获取 Agent 实例                     │
│    - 调用 streamEvents(messages, ctx)   │
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
│    - 内置 @Tool (HttpTool/DatabaseTool) │
│    - MCP 服务器工具 (远程转发)           │
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
