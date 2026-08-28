# agent-web-sdk

yunxi Agent Platform 的前端 SDK 单一源（Single Source of Truth）。所有前端页面（如 `agent-nutritionist-web`）只需从本包导入，无需自行实现表单填写 / DOM 操作逻辑。

## 架构分层

```
┌───────────────────────────────────────────────────────────┐
│  后端 Agent 层（AgentScope，运行在 agent-core / mcp-formfill）│
│  - Agent 多工具编排、业务推理、长任务规划                     │
│  - 产出结构化 formData  → 经 mcp-formfill 下发 WebSocket 指令 │
└───────────────────────────┬───────────────────────────────┘
                            │  WebSocket(JSON)
                            ▼
┌───────────────────────────────────────────────────────────┐
│  前端桥接层：FormFillClient (formfill-client.js)            │
│  - 连接后端 /ws/formfill，接收填表指令                       │
│  - 幂等去重、失败字段自动重试、自动上报页面结构              │
└───────────────────────────┬───────────────────────────────┘
                            │  batchFill(formData)
                            ▼
┌───────────────────────────────────────────────────────────┐
│  前端执行引擎：PageAgentDomEngine (page-agent-dom-engine.js)│
│  - 纯 DOM 读写，零外部依赖（不依赖原生 page-agent SDK）      │
│  - scan / fill / batchFill / click / highlight              │
│  - 兼容 React / Vue 响应式框架（正确派发原生输入事件）        │
└───────────────────────────────────────────────────────────┘
```

> **重要**：原 page-agent 的「浏览器端 LLM Agent 决策循环」已整体退场（Path A）。决策权统一收敛到后端 AgentScope，前端只负责精准执行。原生 page-agent SDK 降级为可选增强层（`page-agent-sdk.js` 仅提供加载器，DomEngine 加载后可自动使用其 `getBrowserState()` 增强检测）。

## 文件清单

| 文件 | 角色 |
|------|------|
| `src/page-agent-dom-engine.js` | **核心执行引擎**：纯 DOM 读写，提供 `scan` / `fill` / `batchFill` / `click` / `highlight`。零依赖、兼容 React/Vue |
| `src/formfill-client.js` | **桥接层**：WebSocket 通信 + 指令执行。自动连接、幂等去重、重试、结构上报 |
| `src/formfill-debug.js` | **调试面板**：浏览器控制台可视化查看/编辑填表数据、手动触发 fill |
| `src/page-agent-sdk.js` | **可选 SDK 加载器**：按需加载原生 page-agent SDK（加载后 DomEngine 使用其增强状态）。不再包含任何 LLM 决策循环 |
| `src/yunxi-chat.js` | **对话客户端**：封装 `/api/conversations/chat`（同步）与 `/api/conversations/chat/stream`（SSE 流式），提供 `window.yunxiChat`；支持文本增量、任务清单、人机确认结果回传 |

## 快速接入

```javascript
// 在页面入口（如 entry.js）导入本包各模块即可挂载全局接口
import '@web-sdk/page-agent-dom-engine.js'; // window.PageAgentDomEngine
import '@web-sdk/formfill-client.js';       // window.FormFillClient
import '@web-sdk/formfill-debug.js';        // window.FormFillDebug（调试用）

// 页面内建立填表通道
const client = window.FormFillClient.getClient();
await client.connect({ wsPath: '/ws/formfill' });
client.reportStructure('recipe', { bootstrap: true }); // 上报真实页面字段给后端
```

### 全局接口

- `window.PageAgentDomEngine`
  - `scan(detailed?)` — 扫描页面所有可填字段，返回 `{index, tag, type, name, label, value, selector}` 数组
  - `fill(fieldRef, value)` — 按字段索引 / name / selector 填值（自动识别 input/select/checkbox/radio）
  - `batchFill(formData)` — 批量填表，返回 `{ [key]: { success, message } }`
  - `click(target)` — 按文本 / selector 点击元素
  - `highlight(selector)` — 脉冲高亮，给用户即时视觉反馈
- `window.FormFillClient.getClient()`
  - `.connect({ wsPath })` — 连接后端 WebSocket
  - `.onLog(fn)` / `.onFill(fn)` — 事件回调
  - `.reportStructure(scene, opts?)` — 上报页面结构（供后端 `getFormStructure` 工具使用）
  - `.fillForm(formData, opts?)` — 本地直接触发填表（不经过 WebSocket，调试用）
- `window.yunxiChat(opts)` — 发起对话，返回完整文本（也会在 `onDone` 回调）
  - `opts.mode` — `sync`（默认，一次性返回）/ `stream`（SSE 流式）
  - 其余 `opts` 字段作为请求体透传：`agentName`、`message`、`conversationId`、`userId`、`confirmResults` 等
  - `onDelta(delta, fullSoFar)` — 流式文本增量（仅 `stream` 模式）
  - `onDone(fullText)` — 完成回调（两种模式都会调用）
  - `onTodo(todos, evt)` — 任务清单全量更新（仅 `stream` 模式；需 Agent 启用任务清单）
  - `confirmResults` — 人机确认结果回传，用于恢复因权限确认而挂起的对话

**任务清单示例**（需 Agent 配置 `plan.taskList: true`）：

```javascript
yunxiChat({
  mode: 'stream',
  agentName: 'general-assistant',
  message: '帮我规划一次团建活动',
  onDelta: (delta) => { /* 追加文本 */ },
  onTodo: (todos) => {
    // todos 为全量列表：[{ id, subject, state, created_at, ... }]
    // state: pending / in_progress / completed
    renderTodoCard(todos); // 整体替换渲染，不做增量合并
  },
  onDone: (full) => { /* 完成 */ }
});
```

**人机确认回传**（Agent 调用需确认的工具时会收到 `REQUIRE_USER_CONFIRM` 事件并挂起，
需携带 `confirmResults` 用同一会话重新请求才会继续）：

```javascript
const confirmEvt = JSON.parse(evt.content);  // content 为 JSON 字符串，需先解析
const call = confirmEvt.toolCalls[0];

yunxiChat({
  mode: 'stream',
  agentName: 'general-assistant',
  conversationId: evt.conversationId,   // 必须是同一会话
  message: '确认执行',
  confirmResults: [{
    toolCallId: call.id,      // 取自待确认事件
    approved: true,           // 默认 false（拒绝）
    toolName:  call.name,
    input:     call.input     // 也可修改入参后再执行
  }]
});
```

> 说明：SSE 结构化事件的 `content` 均为 **JSON 字符串**（与 `tool_result` 等同一编码惯例），
> 需 `JSON.parse` 后使用；`onTodo` 回调的 `todos` 已由 SDK 内部解析，可直接使用。
> 事件与字段详见 [docs/guide/09-api-reference.md](../docs/guide/09-api-reference.md)。

## 现有页面

`page-agent-config.yml` (agent-config) 中定义的 Path A 工具与提示词已不再被前端使用，仅保留供参考；实际配置路由迁移至 AgentScope 的 `agent-definitions/*.yml`。

## 设计原则

1. **决策与执行分离**：AI 推理在后端，DOM 操作在前端。前端没有任何 LLM 调用。
2. **零 SDK 依赖**：DomEngine 不依赖原生 page-agent SDK，可独立部署。
3. **单一数据源**：页面状态统一管理，用户交互与 Agent 填表都通过同一入口，避免双向覆盖冲突。
4. **开源友好**：本包纯前端、无内部依赖，可作为独立 npm 包发布。
