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
  - 通过 agent-config/agent-definitions/*.yml 定义 Agent 的提示词、工具、MCP 服务器
  - 业务工具实现为 Spring @Component + @Tool 注解
  - 编写业务逻辑

第 3 层: yunxi Agent Platform
  - AgentConfigurer (Agent 装配：YAML→HarnessAgent)
  - ChatAppService (对话管理)
  - ProfileRouter (用户档案路由)
  - ModelFactory (模型创建)
  - A2AServer (跨服务 Agent 调用协议)

第 2 层: AgentScope-Java + Harness
  - HarnessAgent (Agent 运行时包装器，管理记忆/会话/上下文)
  - ReActAgent (实际 Agent 编排运行时)
  - MemoryManager (记忆管理)
  - A2AProtocol (Agent 间通信协议)

第 1 层: 基础设施
  - Spring Boot / Redis / MySQL / LLM API
```

**开发原则**：
1. Agent 行为通过 YAML 配置文件定义，由 `AgentConfigurer` 在启动时装配
2. 业务工具直接使用 `@Tool` 注解注册，无需额外的桥接层
3. 对话管理通过 `ChatAppService` 编排（`agent.call()` / `agent.streamEvents()`），透过 `RuntimeContext` 传递 `userId`/`sessionId`
4. 模型使用通过 `ModelFactory` 统一创建

---

## 创建业务 Agent

Agent 通过 YAML 配置文件定义，由 `AgentConfigurer` 在启动时装配为 HarnessAgent 实例。

### Agent 定义示例

```yaml
# agent-definitions/my-business-agent.yml（顶层键为 agent:，由 AgentDefinitionLoader 从 classpath 加载）
agent:
  name: my-business-agent
  description: 业务分析助手
  prompt: "你是一个业务分析助手..."
  orchestration: single          # single / supervisor / pipeline / routing
  model:
    provider: dashscope          # dashscope / openai / baidu / huawei
    modelName: qwen-plus
  ragMode: GENERIC               # NONE / GENERIC / AGENTIC
  toolsGroup:
    systemToolsGroup: [memory]
    mcpServersToolsGroup: [database]
```

配置文件将被 `AgentConfigurer.createAgent()` 方法读取，通过 `ModelFactory.create()` 创建 Model，经由 `HarnessAgent.builder()` 装配 Toolkit（`@Tool` Bean + MCP 工具组）与 Middleware 后构建可运行的 Agent。

### Agent 加载流程

```
agent-definitions/*.yml
  → AgentDefinitionLoader 加载定义（AgentDefinition 解析）
  → AgentConfigurer 为每个定义调用 buildAndRegisterAgent(def)
  → ModelFactory.create(config) 创建 Model
  → HarnessAgent.builder()
      .model(model)
      .toolkit(toolkit)                // 注册 @Tool Bean + MCP 工具组
      .addMiddleware(ApplicationRAG / ContentFilterMiddleware ...)
    .build()
  → agentService.registerAgentInstance(name, agent)   // 共享 Agent 实例
```

对于 Supervisor 模式（专家 Agent 编排），在 YAML 中配置 `experts` 列表，由 `createSupervisorAgent()` 设置 `SubAgentConfig.forwardEvents`。

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
- **远程 MCP 工具**：通过 Agent 定义 YAML 的 `tools.mcpServers` 声明，由 `AgentConfigurer.registerMcpServers` 建立连接（sse / stdio / http 三种传输），适合跨服务复用的能力（如 yunxi-mcp-servers 提供的 40+ 即插即用工具）。

```yaml
# agent-definitions/my-business-agent.yml
agent:
  name: my-business-agent
  tools:
    mcpServers:
      - name: business-data-mcp
        type: sse
        url: http://localhost:40602/sse   # 需先启动对应 MCP 服务
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

```yaml
# agent-definitions/<name>.yml
agent:
  name: business-assistant
  plan:
    taskList: true          # 默认 false
```

也可全局开启（`agentscope.yml`）：

```yaml
agentscope:
  core:
    plan:
      task-list: true
```

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

服务端经 SSE `todo_update` 事件推送全量清单，前端应**整体替换**渲染：

```javascript
yunxiChat({
  mode: 'stream',
  message: '生成一份营养配餐方案',
  onDelta: (delta, full) => { /* 文本增量 */ },
  onTodo: (todos, evt) => {
    // todos: [{ id, subject, state, created_at, ... }]，全量列表
    renderTodoCard(todos);
  },
  onDone: (full) => { /* 完成 */ }
});
```

> SDK 的 `onTodo` 回调仅在 `mode: 'stream'` 下触发。事件负载与字段说明见
> [09. API 参考](./09-api-reference.md#todo_update-事件任务清单)，启用配置见
> [06. 配置参考](./06-configuration.md#任务清单todolist配置)。

### 5. 与计划模式（PlanMode）的关系

两者相互独立：任务清单不要求先进入计划模式；计划模式侧重"先规划后执行"的只读阶段，
任务清单侧重执行过程中的进度跟踪。可按需单独或同时启用。

> 若还需对命令执行、文件写入等**高危工具**加人工确认，见
> [06. 配置参考 - 人机确认（HITL）配置](./06-configuration.md#人机确认hitl配置)；
> 确认结果的回传接口见 [09. API 参考](./09-api-reference.md#require_user_confirm-事件人机确认)。

---

## Agent 上下文

Agent 上下文由 AgentScope 框架的 `HarnessAgent.workspaceFor(userId, sessionId)` 管理，通过 `RuntimeContext` 透传 `userId`/`sessionId`。yunxi 不提供自定义上下文的 SPI 扩展点——上下文数据经由 AgentScope 框架的 Middleware（如 `WorkspaceContextMiddleware`）和 `AgentStateStore` 管理。

如需在 Agent 调用前注入额外上下文信息，可在 YAML 的 `systemPrompt` 中使用变量占位符，由模板引擎在装配时替换。

---

## 单元测试

### 测试理论基础

**为什么需要单元测试**：
- 验证代码正确性
- 便于重构（有测试保障）
- 作为代码文档
- 提前发现问题

**测试金字塔**：
```
         /\
        /  \
       / E2E \      端到端测试（少）
      /--------\
     /  集成测试 \   集成测试（中）
    /------------\
   /   单元测试    \  单元测试（多）
  /----------------\
```

### 编写单元测试

```java
@SpringBootTest
class MyAgentTest {

    @Autowired
    private ChatAppService chatAppService;

    @Test
    void testHandleRequest() {
        // 1. 准备测试数据
        AgentRequest request = AgentRequest.builder()
            .message("测试消息")
            .userId("test-user")
            .build();

        // 2. 执行测试
        AgentResponse response = agentService.handleRequest("my-agent", request);

        // 3. 验证结果
        assertNotNull(response);
        assertNotNull(response.getContent());
        assertFalse(response.getContent().isEmpty());
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
- Agent 的 handleRequest 方法
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
| `agent-core` | Agent 装配与中间件编排 |
| `agent-config` | YAML 场景定义与 MCP 配置 |
| `mcp-formfill` | 通用表单填写 MCP 服务器（WebSocket 下发填表指令） |
| `agent-web-sdk` | 前端填表 SDK（`FormFillClient` 桥接层 + `PageAgentDomEngine` 执行引擎） |
| `agent-nutritionist-web` | 示例前端页面（食谱生成） |

### 后端协作模型

```
用户 → 前端页面 (agent-nutritionist-web)
                │ 对话消息
                ▼
        ChatAppService (agent-core)
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

### 前端设计（agent-web-sdk + agent-nutritionist-web）

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
