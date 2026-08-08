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
# agent-config/src/main/resources/agent-definitions/my-business-agent.yml
agentDefinitions:
  my-business-agent:
    name: "业务助手"
    systemPrompt: "你是一个业务分析助手..."
    model:
      provider: dashscope
      name: qwen-plus
    tools:
      enabled: true
    mcp-servers:
      - database
```

配置文件将被 `AgentConfigurer.createAgent()` 方法读取，生成 `AgentModelConfig`，通过 `ModelFactory.create()` 创建 Model，经由 `HarnessAgent.builder()` 装配 Middleware 和工具后构建可运行的 Agent。

### Agent 加载流程

```
agent-definitions/*.yml
  → AgentConfigurer 解析 @ConfigurationProperties
  → 为每个定义调用 createAgent(def)
  → ModelFactory.create(config) 创建 Model
  → HarnessAgent.builder()
      .model(model)
      .addMiddleware(ContentFilterMiddleware...)
      .toolComponentSupplier() 注册 @Tool Bean
    .build()
  → agentCache.put(name, agent)   // 共享 Agent 实例
```

对于 Supervisor 模式（专家 Agent 编排），在 YAML 中配置 `experts` 列表，由 `createSupervisorAgent()` 设置 `SubAgentConfig.forwardEvents`。

---

## 创建 MCP 工具

### MCP 工具理论基础

**什么是 MCP 工具**：
- MCP（Model Context Protocol）是 Anthropic 提出的标准协议
- 允许 LLM 调用外部工具和服务
- 统一的工具描述格式和调用方式

**工具的生命周期**：
```
定义 → 注册 → 发现 → 调用 → 返回
```

### 创建工具处理器

```java
@Component
public class MyTool implements Tool {

    @Override
    public String getName() {
        return "my_tool";
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name(getName())
            .description("我的工具")
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        // 执行业务逻辑
        return ToolResult.success("结果");
    }
}
```

**关键概念**：
- **ToolDefinition**：工具元数据定义
- **execute**：工具执行逻辑
- **ToolResult**：工具执行结果

### 注册到框架

```java
@Component
public class MyTool implements Tool {

    @Override
    public String getName() {
        return "my_tool";
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name(getName())
            .description("我的工具")
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        // 执行业务逻辑
        return ToolResult.success("结果");
    }
}
```

**原理**：
```
┌─────────────────────────────────────────┐
│  MCP 工具注册流程                        │
├─────────────────────────────────────────┤
│                                         │
│  1. 创建 Tool 实现类                       │
│     ↓                                   │
│  2. 在 Controller 中注册                  │
│     ↓                                   │
│  3. Spring 启动时扫描                     │
│     ↓                                   │
│  4. 注册到 Agent 的 Toolkit（按服务器分组）  │
│     ↓                                   │
│  5. Agent 可以调用工具                    │
│                                         │
└─────────────────────────────────────────┘
```

---

## Agent 上下文

Agent 上下文由 GA 框架的 `HarnessAgent.workspaceFor(userId, sessionId)` 管理，通过 `RuntimeContext` 透传 `userId`/`sessionId`。yunxi 不提供自定义上下文的 SPI 扩展点——上下文数据经由 AgentScope 框架的 Middleware（如 `WorkspaceContextMiddleware`）和 `AgentStateStore` 管理。

如需在 Agent 调用前注入额外上下文信息，可在 YAML 的 `systemPrompt` 中使用变量占位符，由模板引擎在装配时替换。

---

## 实现自定义工具

自定义工具通过 Spring `@Component` + AgentScope `@Tool` 注解注册：

```java
@Component
public class MyBusinessTool {
    @Tool(name = "my_business_query", description = "查询业务数据")
    public String query(@ToolParam(description = "查询条件") String condition) {
        // 业务逻辑
        return result;
    }
}
```

无需实现额外的 `Tool` 接口或 `ToolAdapter` 桥接层。`Toolkit` 在 Agent 装配阶段自动扫描所有带 `@Tool` 注解的 Spring Bean 并注册。

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
