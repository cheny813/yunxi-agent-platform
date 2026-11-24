# 07. 开发指南

学习如何扩展和定制 yunxi Agent Platform。

## 设计模式参考

本框架使用了多种设计模式，详细理论请参考：
- [03. 核心概念 - SPI 扩展机制](./03-concepts.md#spi-扩展机制)
- [04. 架构设计 - 关键组件详解](./04-architecture.md#关键组件详解)

### 开发中常用的设计模式

| 模式 | 应用场景 | 参考章节 |
|------|----------|----------|
| **SPI 模式** | 扩展框架功能 | [03. 核心概念](./03-concepts.md) |
| **适配器模式** | 工具适配 | [04. 架构设计](./04-architecture.md) |
| **门面模式** | 简化复杂调用 | [04. 架构设计](./04-architecture.md) |
| **策略模式** | 规则评估 | [04. 架构设计](./04-architecture.md) |

---

## 开发架构理解

```
第 4 层: 你的业务配置与工具
  - 通过 yunxi-config/agent-definitions/*.yml 定义 Agent 的提示词、工具、MCP 服务器
  - 业务工具实现为 Spring @Component + @Tool 注解
  - 编写业务逻辑

第 3 层: yunxi Agent Platform
  - AgentConfigurer (Agent 装配：YAML→HarnessAgent)
  - ConversationController / IngressGateway (传输适配与四协议入口，yunxi-ingress)
  - ImChannelGatewayConfig (IM 渠道网关：钉钉/企微/飞书/GitHub/GitLab/微信/ChatUi 经 GatewayBootstrap 接入框架 HarnessGateway，见 16.8)
  - ModelFactory (模型创建)
  - ProtocolAdapter (跨协议适配：Web / A2A / AG-UI / Chat Completions)

第 2 层: AgentScope-Java + Harness
  - HarnessAgent (Agent 运行时包装器，管理记忆/会话/上下文)
  - ReActAgent (实际 Agent 编排运行时)
  - MemoryManager (记忆管理)
  - A2AProtocol (Agent 间通信协议)

第 1 层: 基础设施
  - Spring Boot / Redis /（可选）MySQL / LLM API
```

**开发原则**：
1. Agent 行为通过 YAML 配置文件定义，由 `AgentConfigurer` 在启动时装配
2. 业务工具直接使用 `@Tool` 注解注册，无需额外的桥接层
3. 对话请求经 `ConversationController` → `IngressGateway` → `ProtocolAdapter` 适配为 `AgentCommand`，由 `ExecutionFacade` 执行（`agent.streamEvents()`），透过 `RuntimeContext` 传递 `userId`/`sessionId`
4. 模型使用通过 `ModelFactory` 统一创建

---

## 创建业务 Agent

Agent 通过 YAML 配置文件定义，由 `AgentConfigurer` 在启动时装配为 HarnessAgent 实例。

### 先分清两类 Agent：发布时定义 vs 运行时定义

Agent 定义有两种来源，区别**不在能力，而在"定义从哪来、归谁改"**。选错会导致"改完不生效"或"升级后行为被悄悄改掉"：

| | **发布时定义**（定义随发布包交付） | **运行时定义**（定义在配置中心创建） |
|---|---|---|
| 定义存放在哪 | 发布包 classpath 的 `agent-definitions/*.yml` | 配置中心（共享存储，Nacos 后端） |
| 谁修改、怎么评审 | 开发者在代码仓库改，走 PR 评审，随版本发布 | 运维 / 业务方在运行时写入，即时生效 |
| 何时生效 | 发布 / 重启后生效，跟随版本 | 写入后热加载，无需重启 |
| 回滚方式 | 回滚版本（定义与代码一同回滚） | 改回配置中心上一版内容 |
| 典型内容 | 带权限模板、工具白名单等安全面配置的平台级 Agent | 某个租户/业务团队自建的 Agent |

**三个场景帮你对号入座：**

- **场景 A（发布时定义）**：平台团队要提供一个"工单助手"，它的提示词、可调用工具白名单、权限模板需要经过代码评审，并且要能随版本一起回滚 → 写成 `agent-definitions/ticket-assistant.yml` 打进发布包。改提示词 = 提 PR + 发版。
- **场景 B（运行时定义）**：某客户上线后想在管理界面自助创建一个"简历筛选助手"，当天创建、当天调整提示词，不想等平台发版 → 写入配置中心，平台热加载即时生效。
- **场景 C（同名冲突）**：同一个 `agentId` 在两处都有定义时，**以发布时定义为准**（代码是最终裁决方）；运行时不允许覆盖发布时定义的 ID，需要新的请用新的 `agentId`。

> 一句话记法：**要评审、要随版本回滚的，放进发布包；要当天生效、业务方自己调的，放配置中心。**

Agent 定义最终都经 `ConfigCenter` 进入运行时（运行时只有一条读路径，不存在两套并行加载），区别在于所有权与更新语义：发布时定义在启动期与配置中心内容比对，不一致才覆盖（内容相同则零写入，避免多实例滚动重启引发热加载风暴）；运行时定义由配置中心直接热加载。

### 运行时定义存在哪、能保留多久

运行时定义存于配置中心的 Agent 定义快照（Nacos 后端为 `yunxi.agent-definitions.json`，全量快照），另有一份 Redis 共享读缓存加速多实例读取。要点：

- **是持久存储，但不是版本库**：写入落 Nacos 配置，Nacos 重启后仍在；每次写入覆盖全量快照，平台自身不保留旧版本、不做自动备份。需要回滚请用 Nacos 控制台的配置历史（保留天数由 Nacos 服务端配置决定），或在写入前自行备份。删除该 dataId 等同于清空全部运行时定义。
- **多人同时编辑必须串行**：写入是"读全量 → 改 → 写全量"，两人同时修改不同 Agent 时，后写者会覆盖前者的修改。开放给多人编辑前，编辑入口需串行化或引入版本校验，不能直接暴露并发的写接口。
- **定义质量与经验不足的应对**：结构校验在写入前完成（`identity.agentId` / `identity.name` 必填等）；更深一层的提示词质量与工具边界评估、草稿—评审—晋升的完整流程尚未内置，属于后续演进项，不要把它当作已有能力。
- **降级期写入只在本实例生效**：配置中心未启用或不可达时，定义写入只更新本实例内存、不持久化，日志会明确告警；cluster 部署建议开启 `yunxi.config.consistency.enforced=true`，让"定义无法跨实例共享"在启动期直接失败，而不是静默运行。

### Agent 定义示例

```yaml
# 以下 AgentSpec 字段结构同真实范本 yunxi-config/src/main/resources/agent-definitions/example-agent.yml
# （顶层即 AgentSpec 字段，无 agent: 包裹；由 AgentDefinitionLoader 扫描 classpath*:agent-definitions/**/*.yml 加载，一个文件一个 Agent）
identity:
  agentId: example-assistant        # 必填，Agent 唯一标识；同时作为 ModelRoutingPolicy 的路由键
  name: 示例助手                     # 必填，展示名
  description: 你是一个通用智能助手，用简洁、准确的中文回答用户问题。   # 作为系统提示词（AgentConfigurer.sysPromptOf 取 identity.description）
mcp:
  servers: []                       # 订阅 mcp.yml 中 yunxi.mcp.static-servers 的命名服务器（按名解析，不含连接信息）；留空表示不接 MCP
state:
  store: inmemory
  persist: false
permission:                           # 必填：显式声明工具策略（白名单 / 黑名单 / 人工确认，语义见 06 章「权限模式」）
  allowedTools:
    - memory
  toolGate:
    enabled: false
    tools: []
# 模型不在 AgentSpec 中声明，统一由 yunxi-config/config/models.yml 的 routing 段按 identity.agentId 解析（未声明则走自动选型）
```

配置文件由 `AgentDefinitionLoader` 在启动期扫描 classpath 的 `agent-definitions/` 反序列化为 `AgentSpec` 并校验后注册进 `ConfigCenter`（全平台 Agent 定义唯一真相源，支持运行时热加载），再经应用平面 `AgentProvisioner` 在启动期调运行时装配入口 `AgentConfigurer.configure(AgentSpec)` 构建为共享的 `HarnessAgent`，注册进框架协议层 `AguiAgentRegistry` / `A2aServerRegistry`（yunxi 不维护第二份注册表）。上述"发布时定义"属于**部署产物**（类比 Spring 中你编写的 `@Controller`），由部署方随镜像或挂载卷放入 classpath 的 `agent-definitions/` 下，平台本身不预置具体业务 Agent；本仓库随附 `example-agent.yml` 等 4 个文件仅作格式范本，可照此自行提供。`AgentConfigurer` 是全仓唯一的运行时装配点，开发者无需自建构建逻辑。

### Agent 加载与装配流程

```
classpath*:agent-definitions/**/*.yml   // AgentDefinitionLoader.LOCATION：classpath 扫描根，定义由部署方提供（非框架内置）
  → AgentDefinitionLoader（@PostConstruct）扫描并反序列化为 AgentSpec，校验后注册进 ConfigCenter（校验不通过则跳过并记严重日志）
  → 运行时装配入口将每个 AgentSpec 调用 AgentConfigurer.configure(AgentSpec)
       · ConfigCenter.resolve(spec)            → ResolvedConfig（合并默认 / profile / 环境配置）
       · ModelRoutingPolicy.route(agentId)     → 主模型 + 备用模型 id
       · modelFactory.create(primaryModelId)  → Model（FailoverModelProvider 主备兜底）
       · buildToolkit                          → @Tool Bean + McpDirectory 工具组 + 各 MuseToolRegistrar 元工具
       · 构建内层 ReActAgent，再 HarnessAgent.builder().fromAgent(inner) 包裹
       · 按 order 统一挂载协议无关 Middleware（审计 / 追踪 / 人机协同 / 韧性 / 会话级工具组激活）
  → aguiAgentRegistry.register(agentId, agent)   // 共享 Agent 实例（框架协议层单例真相源）
```

当前 `AgentSpec` 聚焦单 Agent 定义（字段为 `identity` / `skill` / `mcp` / `state` / `permission` / `toolkit`，无多 Agent 编排字段）。多 Agent 编排（supervisor / pipeline / routing）由 AgentScope-Java 框架原生 SubAgent 能力支持，不在当前 AgentSpec schema 内声明；需要时在框架层按官方 SubAgent 用法组合，yunxi 不额外封装编排字段。

---

## 创建自定义工具

### 工具注册机制（@Tool 注解）

AgentScope-Java 2.0 中 `@Tool` 是**方法级注解**（`io.agentscope.core.tool.Tool`），而非需实现的接口。`Toolkit` 在 Agent 装配阶段自动扫描所有带 `@Tool` 注解的 Spring Bean 并注册，**无需实现额外的 `Tool` 接口或桥接层**。

**工具生命周期**：定义 → 注册 → 发现 → 调用 → 返回

### 最小示例

```java
@Component
public class MyBusinessTool {

    @Tool(name = "query_business_data", description = "查询业务数据")
    public String query(@ToolParam(description = "查询条件") String condition) {
        // 执行业务逻辑
        return result;
    }
}
```

**关键要求**：
- 所有参数必须用 `@ToolParam` 标注（`@ToolEmitter` 流式输出除外）
- 返回类型支持 `String`、`Mono<String>` 等响应式类型
- 工具名建议使用 snake_case（如 `get_weather`），便于 LLM 调用

### 常用注解属性

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `name` | 方法名 | 工具名，建议 snake_case |
| `description` | 自动生成 | 工具描述（做什么、何时用） |
| `readOnly` | `false` | 只读工具自动豁免 `EXPLORE` 权限模式 |
| `strict` | `false` | 严格 JSON Schema 模式 |
| `concurrencySafe` | `true` | 是否可并发调用自身 |
| `externalTool` | `false` | 标记为框架外执行（调用时抛 `ToolSuspendException` 上抛给调用方） |
| `stateInjected` | `false` | 在方法签名中注入 `AgentState` 参数 |
| `dangerousFiles` / `dangerousDirectories` | 空 | 追加危险路径名单，触发权限审批 |
| `converter` | `DefaultToolResultConverter` | 自定义结果转换器（过滤敏感数据/压缩输出） |

### 本地 @Tool vs 远程 MCP 工具

- **本地 `@Tool`**：在应用内直接执行，适合业务逻辑、内部数据访问；
- **远程 MCP 工具**：通过 Agent 定义 YAML 的 `mcp.servers` 订阅命名服务器（如 `[database]`），由 `AgentConfigurer` 经 `McpDirectory` 按名解析连接（连接信息来自 `mcp.yml` 的 `yunxi.mcp.static-servers`，支持 sse / stdio / http 三种传输），适合跨服务复用的能力（如 yunxi-mcp-servers 提供的 40+ 即插即用工具）。

```yaml
# 远程 MCP 工具示例（完整 AgentSpec 字段结构见 yunxi-config/src/main/resources/agent-definitions/example-agent.yml；mcp.servers 只写订阅名）
identity:
  agentId: example-assistant
  name: 示例助手
  description: 你是一个通用智能助手
mcp:
  servers: [business-data-mcp]   # 订阅 mcp.yml 中同名 static-server（连接信息在 mcp.yml 声明，不在 AgentSpec 内）
```

**注册流程**：

```
定义 @Tool Bean 方法 → Spring 启动扫描（@Component）→ Toolkit 反射生成 JSON Schema → 注册到 Agent 的 Toolkit（按分组隔离）→ Agent 按需调用
```

---

## 为 Agent 启用任务清单（TodoList）

面对耗时数十秒至数分钟的长任务，可让 Agent 维护结构化任务清单，实现**进度可见**（前端实时看到 x/y）、
**规划可校验**（任务拆解透明）、**中断可恢复**（状态随会话持久化，可从断点继续）。

能力由 AgentScope-Java 2.0 **原生提供**（`todo_write` 工具 + `TaskReminderMiddleware`），
yunxi 仅负责启用与事件透出——**无需自建工具、存储或状态机**。

### 1. 启用开关

> **`AgentSpec`（`io.yunxi.definition.AgentSpec`）已内置 `plan.taskList` 字段（`PlanConfig`），与 `identity` / `skill` / `mcp` / `state` / `permission` / `toolkit` 并列。** 任务清单（`todo_write`）是 AgentScope-Java 框架原生能力，其启用开关为框架级 `HarnessAgent.Builder.enableTaskList()`；yunxi 已在 `AgentConfigurer` 唯一装配点接线该开关，无需自建。

该能力由 `HarnessAgent.Builder.enableTaskList()` 构建期开关控制（默认关闭），yunxi 采用双通道、默认关闭：**全局开关 `yunxi.agent.taskList.enabled`**（Spring 环境属性，默认 false）与 **per-Agent `agent.plan.taskList`**（经 `AgentSpec.PlanConfig`）二者 OR 生效。即 `agent.plan.taskList: true` 已正式生效。
启用条件与计划模式（`plan.enabled`）一致，取 **YAML 级 或 全局级** 二者之一。

### 2. 引导模型使用

`todo_write` 依赖模型主动调用。可在 `systemPrompt` 中追加领域化引导（框架已内建通用引导，此为可选增强）：

```yaml
  systemPrompt: |
    处理多步骤任务（预计 3 步以上）时，先调用 todo_write 拆解任务清单，
    每个步骤一个任务；执行过程中及时更新任务状态，同一时刻仅保持一个任务为 in_progress。
```

### 3. 语义与约束

| 项 | 说明 |
|------|------|
| 写入语义 | **全量替换**（full-list-replace）——模型每次提交完整列表，不做增量合并 |
| 状态 | `pending` / `in_progress` / `completed` |
| 唯一性约束 | 同一时刻至多一个 `in_progress`；违反时工具返回错误文本，清单不被破坏 |
| 任务标识 | 模型无需传 id；工具按任务内容（subject）匹配原任务以保留 id 与创建时间 |
| 删除任务 | 从列表中省略即可（无独立删除工具） |

### 4. 前端对接

后端 `ConversationController.stream` 经 SSE 以 `event: agent-event` **纯透传**框架 `AgentEvent`（框架 `AgentEventType` 无 `todo_update` 类型）；任务清单由前端 SDK `yunxi-chat.js` 从 `todo_write` 工具的 `TOOL_RESULT_TEXT_DELTA` 累积、`TOOL_RESULT_END` 触发解析（full-list-replace 语义），经 `onTodo` 回调**整体替换**渲染：

```javascript
yunxiChat({
  mode: 'stream',
  message: '生成一份营养配餐方案',
  onDelta: (delta, full) => { /* 文本增量 */ },
  onTodo: (todos, meta) => {
    // todos: [{ content, status, priority }] 全量列表；meta: { toolCallId, revision }
    renderTodoPanel(todos);
  },
  onDone: (full) => { /* 完成 */ }
});
```

> SDK 的 `onTodo` 回调仅在 `mode: 'stream'` 下触发。透出机制与字段说明见
> [09. API 参考](./09-api-reference.md#任务清单透出机制)，启用配置见
> [06. 配置参考](./06-configuration.md#任务清单todolist配置)。

### 5. 与计划模式（PlanMode）的关系

两者相互独立：任务清单不要求先进入计划模式；计划模式侧重"先规划后执行"的只读阶段，
任务清单侧重执行过程中的进度跟踪。可按需单独或同时启用。

> 若还需对命令执行、文件写入等**高危工具**加人工确认，见
> [06. 配置参考 - 人机确认（HITL）配置](./06-configuration.md#人机确认hitl配置)；
> 确认结果的回传接口见 [09. API 参考](./09-api-reference.md#require_user_confirm-事件人机确认)。

---

## AgentScope Service 管控面集成（aistio）

yunxi 通过 `yunxi-fabric` 模块的 `io.yunxi.fabric.aistio` 适配层接入 AgentScope Service 管控面（底层框架生态称 aistio），把每个已装配的 `HarnessAgent` 注册为一个**被管控的数据面 Agent 实例**，从而获得 Agent 管控、治理可视化与会话干预能力。配置项与启用步骤见 [06. 配置参考 - AgentScope Service 管控面配置（aistio）](./06-configuration.md#aistio)。

> 接入形态是**数据面实例接入**：yunxi 作为受管控的 Agent 实例，由控制面下发任务、yunxi 执行并经 gRPC 回传事件。把 yunxi 的 Agent 定义反向导出为管控面 DAG 编排定义（声明式 Workflow 编排图挂载）不在当前实现范围，相关能力以 AgentScope Service 官方文档为准。

### 1. 单一集成点

所有 aistio 接线收敛在 `yunxi-fabric` 一处，绝不在其他模块自建注册 / 心跳 / gRPC 逻辑：

- `AistioConnector`（`@Component`，`yunxi.aistio.enabled=true` 时装配）—— 单一集成点。对每个已装配的 `HarnessAgent` 调用框架 `Aistio.instrument(agent, AistioConfig, adapter)`，将注册 / 心跳 / gRPC 对话流 / 数据面契约 / 任务接活全部交给框架，yunxi 不重实现。
- `AistioIntegrationProperties` —— 仅承载 yunxi 侧连接参数（`yunxi.aistio.*`）；注册 / 心跳 / gRPC / 契约等语义由框架 `Aistio.instrument` 负责。
- `AistioLifecycle` —— 优雅下线闸门（`stopAccepting()`），缩容 / 下线前拒绝新接活。
- `YunxiFrameworkAdapter` —— 实现框架 `FrameworkAdapter`：承载 `handleAgentTask`（任务接活）、上下文抽取与 observer 中间件等。
- `YunxiAgentBuilderSupplier` —— 工作区 Agent 构建器底座，仅补平台底座（MCP 工具箱 / MUSE 元工具 / 分布式存储 / 横切中间件），模型经框架全局 `ModelRegistry` 解析（不重实现执行器 / 工具翻译）。
- `AistioIntegration` —— 实现框架 `AgentBuildContributor`，在 Agent 装配期注入 aistio 接线。
- `RedisDistributedRuntimeConfig` / `InMemoryDistributedRuntimeConfig` —— 分布式运行时装配（`DistributedStore` + `SessionTurnGate`）。

### 2. 已落地的核心能力

| 能力 | 实现 | 说明 |
|------|------|------|
| HTTP 自注册 + 心跳 | 框架 `HttpSelfRegistration`（`AistioConfig.startHttpRegister(true)`，默认开） | 启动期向控制面 `POST /api/v1/agent-registrations` 注册并周期心跳；`instanceKey` 默认取 `${app}-${host}-${port}`（覆盖框架默认 UUID，对齐实例键稳定唯一约定） |
| gRPC 对话流 | 框架 gRPC 传输（`AistioConfig.startGrpc(true)`） | 控制面经 gRPC 推对话、yunxi 回传事件流；`AistioConnector` 默认 `startGrpc=false`，须显式开启 |
| 任务接活（AgentTask） | `HarnessAgentTaskStarter` + `YunxiFrameworkAdapter.handleAgentTask` | 控制面经 `dispatchExternal` 下推 `AgentTaskAssignment`，yunxi 跑该 Agent；含工作区定义任务时经 `HarnessWorkspaceAgentFactory` 声明 `workspace-definition-v1` 能力，避免定义任务被 skip |
| 优雅下线 | `AistioLifecycle.stopAccepting()` | 缩容 / 下线前关闭 accepting 闸门 |
| 跨实例会话串行化 | `RedisSessionTurnGate`（实现框架 `SessionTurnGate`） | 经 `DistributedRuntimeConfig.sessionTurnGate()` 装配，解决跨实例对话互斥（框架 `DistributedStore.sessionTurnGate()` 默认 `null`，yunxi 显式装配） |
| 分布式状态存储 | `RedisDistributedRuntimeConfig` | 装配 `DistributedStore`，多实例共享 Agent 状态 |

### 3. 启用要点

- 总开关 `yunxi.aistio.enabled=true` 时才装配 `AistioConnector`，避免禁用时因空控制面 URL 在 Bean 创建期失败。
- `yunxi.aistio.control-plane-url` 与 `internal-token` 必填；`base-url` 须填**管控面容器可达**的宿主机地址（不可填 `localhost`，否则容器内 prober 探活被拒）。
- `yunxi.aistio.start-grpc=true` 才启用 gRPC 对话流（默认 `false`）。
- `yunxi.aistio.instance-key` 未配置时自动取 `${app}-${host}-${port}`，建议保持默认以获得稳定唯一实例键。

---

## Agent 上下文

Agent 上下文由 AgentScope 框架的 `HarnessAgent.workspaceFor(userId, sessionId)` 管理，通过 `RuntimeContext` 透传 `userId`/`sessionId`。yunxi 不提供自定义上下文的 SPI 扩展点——上下文数据经由 AgentScope 框架的 Middleware（如 `WorkspaceContextMiddleware`）和 `AgentStateStore` 管理。

如需在 Agent 调用前注入额外上下文信息，可在 YAML 的 `systemPrompt` 中使用变量占位符，由模板引擎在装配时替换。

---

## 单元测试

### 编写单元测试

```java
@SpringBootTest
class MyAgentTest {

    @Autowired
    private ExecutionFacade executionFacade;

    @Test
    void testHandleRequest() {
        // 1. 准备测试数据
        AgentCommand command = AgentCommand.builder()
            .agentId("my-agent")
            .userId("test-user")
            .messages(List.of(Msg.user("测试消息")))
            .build();

        // 2. 执行测试
        Settle settle = executionFacade.settle(command).block();

        // 3. 验证结果
        assertNotNull(settle);
        assertTrue(settle.ok());
    }
    
    @Test
    void testWithMock() {
        // 使用 Mockito 模拟依赖
        when(mockService.getData()).thenReturn(testData);
        
        // 执行测试...
    }
}
```

### 测试最佳实践

| 实践 | 说明 | 示例 |
|------|------|------|
| **AAA 模式** | Arrange-Act-Assert | 准备-执行-验证 |
| **独立测试** | 测试之间不依赖 | 每个测试独立运行 |
| **描述性命名** | 测试名描述行为 | `shouldReturnErrorWhenInvalidInput` |
| **单一职责** | 一个测试验证一个概念 | 避免大而全的测试 |

---

## 调试技巧

### 日志调试

**日志级别**：
| 级别 | 使用场景 |
|------|----------|
| ERROR | 错误，需要处理 |
| WARN | 警告，需要注意 |
| INFO | 关键信息，正常运行 |
| DEBUG | 调试信息，开发使用 |
| TRACE | 最详细的信息 |

**日志示例**：
```java
// 记录关键步骤
log.info("开始处理请求: userId={}, query={}", userId, query);

// 记录调试信息
log.debug("上下文数据: {}", context);

// 记录错误
log.error("处理失败", exception);
```

### 断点调试

**常用断点位置**：
- ExecutionFacade 的 execute / settle 方法（及 ConversationController 入口）
- 规则的 evaluate 方法
- MCP 工具的 execute 方法
- 上下文组装逻辑

**调试技巧**：
1. 使用条件断点（如只在特定用户时断住）
2. 使用 Evaluate Expression 查看变量值
3. 使用 Step Over/Into/Out 控制执行流程

---

## 实战示例：食谱生成智能体

下面通过一个完整示例，演示如何用本平台组合多个模块构建一个可落地的 Agent 应用（自动填表场景）。该示例覆盖后端 MCP 服务、前端 SDK 与页面三部分，可作为"任意需要自动填表的业务场景"的参考模板。

**示例涉及的模块**：

| 模块 | 职责 |
|------|------|
| `yunxi-runtime` | Agent 装配与中间件编排 |
| `yunxi-config` | YAML 场景定义与 MCP 配置 |
| `mcp-formfill` | 通用表单填写 MCP 服务器（WebSocket 下发填表指令） |
| `agent-web-sdk` | 前端填表 SDK（`FormFillClient` 桥接层 + `PageAgentDomEngine` 执行引擎） |
| `yunxi-nutritionist-web` | 参考业务前端模块（营养师业务） |

### 后端协作模型

```
用户 → 前端页面 (yunxi-nutritionist-web)
                │ 对话消息
                ▼
        ConversationController / IngressGateway (yunxi-ingress)
                │ 路由到 nutrition-assistant
                ▼
        HarnessAgent + MCP 工具 (mcp-formfill)
                │ 调用 fill_form 工具
                ▼
        mcp-formfill 将结构化结果映射为填表指令
                │ WebSocket 下发 (JSON)
                ▼
        FormFillClient (agent-web-sdk) 透传指令
                │ batchFill(formData)
                ▼
        PageAgentDomEngine 写入真实页面 DOM（高亮反馈）
```

- Agent 通过 `tools.mcpServers: [formfill]` 挂载 `mcp-formfill` 工具；
- `mcp-formfill` 把 Agent 产出的结构化数据（如食谱的食材、步骤）映射为填表指令；
- 填表指令经 WebSocket 下发到前端，`FormFillClient` 调用 `PageAgentDomEngine` 写入页面 DOM（前端执行引擎零 LLM 依赖，兼容 React/Vue 响应式框架）；
- 所有填表映射由 `mcp-formfill` 的 `scenarios/*.json` 声明，新增场景无需改动 Java 代码；
- 前端填表能力统一收敛到 `agent-web-sdk`（单一源），业务页面只导入即可，不重复实现 DOM 操作。

### MCP 消息协议（mcp-formfill）

`fill_form` 工具入参为场景名 + 业务数据，下发到前端的填表指令为结构化 JSON：

```json
{
  "scene": "recipe",
  "data": {
    "title": "番茄炒蛋",
    "ingredients": ["鸡蛋 2 个", "番茄 1 个"],
    "steps": ["打散鸡蛋", "热锅下油"]
  }
}
```

`mcp-formfill` 依据 `scenarios/recipe.json` 中的字段映射，生成填表数据，经 WebSocket 推送到前端，由 `FormFillClient` → `PageAgentDomEngine` 完成页面写入。

### 前端设计（agent-web-sdk + yunxi-nutritionist-web）

前端采用**单一数据源（Single Source of Truth）**原则：页面状态由 `FormFillClient` 统一管理，用户交互与 Agent 填表都通过同一状态入口，避免双向覆盖冲突。前端执行层 `PageAgentDomEngine` 是纯 DOM 引擎，不持有任何 LLM 决策循环（原 Path A 浏览器端 Agent 循环已退场，决策权统一在后端 AgentScope）。

**两种填表模式**：
- **自动填表模式**：Agent 产出结构化结果后，由 `mcp-formfill` 推送指令，`FormFillClient` 调用 `PageAgentDomEngine` 自动写入表单字段，用户确认即可提交；
- **引导填表模式**：Agent 以对话形式逐步询问缺失字段，每轮对话回填一个字段，适合信息不完整或需要用户决策的场景。

**SDK 接入要点**：

```javascript
// 页面入口（entry.js）导入 agent-web-sdk 各模块，自动挂载全局接口
import '@web-sdk/page-agent-dom-engine.js'; // window.PageAgentDomEngine
import '@web-sdk/formfill-client.js';       // window.FormFillClient
import '@web-sdk/formfill-debug.js';        // window.FormFillDebug（调试用）

// 页面内建立填表通道
const FF = window.FormFillClient.getClient();
await FF.connect({ wsPath: '/ws/formfill' });
FF.reportStructure('recipe', { bootstrap: true }); // 上报真实页面字段给后端

// 可选：按需增强（加载原生 page-agent SDK，DomEngine 自动使用其 getBrowserState）
// import '@web-sdk/page-agent-sdk.js';
```

> 说明：原 `new FormFillClient({ wsUrl })` + `client.on('fill', ...)` 手动写 DOM 的方式已废弃；现由 `FormFillClient` 内部直接驱动 `PageAgentDomEngine`，业务页面无需自行操作 DOM。前端不依赖原生 page-agent SDK 即可工作（零外部依赖）。

**复用方式**：业务方只需在 `mcp-formfill/scenarios/` 下新增一份场景 JSON，并在前端页面引入 `agent-web-sdk` 的 `page-agent-dom-engine.js` 与 `formfill-client.js`，即可复用同一套前端执行引擎与 WebSocket 通道，无需改动 Java 或 SDK 代码。

---

## 官方示例与学习资源

AgentScope-Java 框架团队提供了 `agentscope-examples` 模块，包含 49 个教学示例（documentation 子模块）以及 builder / dataagent / codingagent / paw 四个完整应用模块。这些资源是理解 HarnessAgent 用法的优质学习材料。

**与 yunxi 的关系**：
- yunxi 在 AgentScope-Java 之上构建应用层，examples 中的 Web 层、会话管理、认证等与 yunxi 处于**同一生态位**，因此 yunxi 不整模块移植它们，避免重复造轮子；
- 真正值得借鉴的是 `documentation` 的 49 个教学示例，它们演示了框架各种能力的最小用法；
- 当 yunxi 需要某个对应能力（如多 Agent 编排、代码执行）时，examples 中的设计模式可作为有价值的参考。

**学习路径建议**：
1. 先读本指南第 01–06 章建立整体认知；
2. 对照 `agentscope-examples/documentation` 的示例逐个跑通，理解框架 API；
3. 回到本指南第 07 章「实战示例：食谱生成智能体」，理解 yunxi 如何把多个模块组合成落地应用；
4. 需要技能自进化能力时，参见 [10. 技能系统 - MUSE 自进化引擎](./10-skills.md#muse-自进化引擎)。

---

**上一页**: [06. 配置指南](./06-configuration.md)  
**下一页**: [08. 部署指南 →](./08-deployment.md)
