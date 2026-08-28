# 09. API 参考

完整的 API 文档。

## API 设计理论

### RESTful API 设计原则

**REST（Representational State Transfer）** 是一种软件架构风格：

| 原则 | 说明 | 示例 |
|------|------|------|
| **资源识别** | 使用 URI 标识资源 | `/api/users/{id}` |
| **统一接口** | 使用标准 HTTP 方法 | GET、POST、PUT、DELETE |
| **无状态** | 每个请求独立 | 请求包含所有必要信息 |
| **可缓存** | 响应可被缓存 | Cache-Control 头 |

**HTTP 方法语义**：
| 方法 | 操作 | 幂等性 |
|------|------|--------|
| GET | 获取资源 | 是 |
| POST | 创建资源 | 否 |
| PUT | 更新资源（全量） | 是 |
| PATCH | 更新资源（部分） | 否 |
| DELETE | 删除资源 | 是 |

### API 版本控制

**为什么需要版本控制**：
- 向后兼容
- 平滑升级
- 支持多版本共存

**版本策略**：
```
# URL 路径版本
/api/v1/users
/api/v2/users

# Header 版本
Accept: application/vnd.api.v1+json
```

---

## 认证方式

### 认证理论

**认证 vs 授权**：
| 概念 | 说明 | 示例 |
|------|------|------|
| **认证** | 验证身份 | 用户名密码、Token |
| **授权** | 验证权限 | 角色、权限列表 |

**Token 认证流程**：
```
┌─────────┐      ┌─────────┐      ┌─────────┐
│  客户端  │ ──→  │ 认证服务 │ ──→  │  业务服务 │
└─────────┘      └─────────┘      └─────────┘
     │                │                │
     │ 1. 登录        │                │
     │──────────────→│                │
     │                │ 2. 验证身份     │
     │                │ 3. 生成 Token   │
     │←───────────────│                │
     │   返回 Token   │                │
     │                                 │
     │ 4. 请求 API (带 Token)          │
     │───────────────────────────────→│
     │                                 │ 5. 验证 Token
     │←───────────────────────────────│
     │        返回数据                  │
```

### 请求头认证

```http
X-User-Id: your-user-id
```

### Bearer Token（A2A JWT）

```http
Authorization: Bearer your-jwt-token
```

> 说明：平台不提供独立的 MCP Token。外部 MCP 服务器（如 yunxi-mcp-servers 各服务）如需鉴权，在其自身配置中设置，与平台 API 调用无关。

---

## Chat API

统一入口：`POST /api/conversations/chat`（`mode` 决定返回方式）；流式专用入口：`POST /api/conversations/chat/stream`（SSE）。

### 发送消息（同步）

**请求**

```http
POST /api/conversations/chat
Content-Type: application/json
X-User-Id: user001

{
  "agentName": "general-assistant",
  "message": "你好",
  "mode": "sync"
}
```

**请求字段**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | string | 是 | 用户消息 |
| `agentName` | string | 是 | Agent 名称（必填，如 `general-assistant`） |
| `mode` | string | 否 | `stream`（默认，SSE 流式）/ `sync`（完整回复） |
| `conversationId` | string | 否 | 会话 ID，缺省自动创建新会话 |

**响应（sync）**

```json
{
  "reply": "你好！有什么可以帮助你的？",
  "conversationId": "conv-xxx"
}
```

> 说明：同步响应体为 `ChatResponse`，字段为 `reply`（回复文本）与 `conversationId`（会话 ID；非会话模式下为 `null`）。

### 发送消息（流式 SSE）

```http
POST /api/conversations/chat/stream
Content-Type: application/json
X-User-Id: user001

{
  "message": "你好",
  "agentName": "general-assistant"
}
```

流式响应为 `text/event-stream`，每条 `data:` 行包含 `{type, timestamp, content}` 结构事件，`type` 取值包括 `content`（回复增量）、`thinking`（思考过程）、`tool_call`、`tool_result`、`agent_status`、`todo_update`（任务清单，需启用）、`error` 等。

#### todo_update 事件（任务清单）

Agent 执行长任务时，可通过内置 `todo_write` 工具维护结构化任务清单，服务端在任务状态变化后推送该事件。

**启用条件**：Agent 定义 YAML 的 `plan.taskList: true`，或全局 `agentscope.core.plan.task-list: true`（默认关闭）。

**触发时机**：Agent 调用 `todo_write` 工具后（每次提交完整列表）；进入已有会话时，若该会话存在历史任务清单，流开始即补发一次。

**事件负载**（与 `tool_result` 等结构化事件同一编码惯例：**`content` 为 JSON 字符串**，需二次解析后取 `todos` 数组；元素为 AgentScope `Task` 序列化结果）：

```json
{
  "type": "todo_update",
  "timestamp": "2026-08-28T10:00:00Z",
  "conversationId": "conv-123",
  "content": "{\"todos\":[{\"id\":\"a1b2c3…\",\"subject\":\"查询营养成分\",\"description\":\"查询营养成分\",\"state\":\"completed\",\"metadata\":{\"priority\":\"high\"},\"created_at\":\"2026-08-28T09:59:01+08:00\",\"owner\":null,\"blocks\":[],\"blocked_by\":[]},{\"id\":\"d4e5f6…\",\"subject\":\"生成配餐方案\",\"state\":\"in_progress\",…}]}"
}
```

`content` 解析后的结构：

```json
{
  "todos": [
    { "id": "a1b2c3…", "subject": "查询营养成分", "description": "查询营养成分",
      "state": "completed", "metadata": { "priority": "high" },
      "created_at": "2026-08-28T09:59:01+08:00", "owner": null,
      "blocks": [], "blocked_by": [] },
    { "id": "d4e5f6…", "subject": "生成配餐方案", "description": "生成配餐方案",
      "state": "in_progress", "metadata": {}, "created_at": "…", "owner": null,
      "blocks": [], "blocked_by": [] }
  ]
}
```

前端取数示意：

```javascript
const payload = JSON.parse(evt.content);   // content 为 JSON 字符串
renderTodoCard(payload.todos);
```

**说明**：

- `state` 取值：`pending`（待执行）/ `in_progress`（执行中）/ `completed`（已完成）；同一时刻至多一个 `in_progress`（模型违反约束时工具返回错误，清单不被破坏）
- **全量透出**：AgentScope 采用 full-list-replace 语义，前端应**整体替换**任务列表渲染，不做增量合并
- 注意 `created_at` 与 `blocked_by` 为下划线命名（与 Java 字段名 camelCase 不同）
- 任务状态持久化于 `AgentState.tasksContext`，默认即有文件存储（`~/.agentscope/state/<agentId>/`），跨会话续传开箱可用；仅多副本部署时需配置 Redis 会话存储

#### REQUIRE_USER_CONFIRM 事件（人机确认）

Agent 调用需人工确认的工具时（由 Agent 配置的 `extensions.hitl.toolGate` 指定），
服务端推送该事件并**挂起 Agent**，需由调用方携带确认结果重新发起请求才会继续执行。

**事件负载**（与 `tool_result` 等结构化事件同一编码惯例：**`content` 为 JSON 字符串**，
需二次解析；解析后即事件对象本身）：

```json
{
  "type": "REQUIRE_USER_CONFIRM",
  "timestamp": "2026-08-29T10:00:00Z",
  "conversationId": "conv-123",
  "content": "{\"type\":\"REQUIRE_USER_CONFIRM\",\"id\":\"evt-001\",\"createdAt\":\"2026-08-29T10:00:00+08:00\",\"replyId\":\"reply-1\",\"toolCalls\":[{\"type\":\"tool_use\",\"id\":\"call_b96c5668e76847468992f1\",\"name\":\"write_file\",\"input\":{\"path\":\"notes.txt\",\"content\":\"hello\"}}]}"
}
```

`content` 解析后的结构：

```json
{
  "type": "REQUIRE_USER_CONFIRM",
  "id": "evt-001",
  "createdAt": "2026-08-29T10:00:00+08:00",
  "replyId": "reply-1",
  "toolCalls": [
    { "type": "tool_use",
      "id": "call_b96c5668e76847468992f1",
      "name": "write_file",
      "input": { "path": "notes.txt", "content": "hello" } }
  ]
}
```

> 回传确认结果时需要的 `toolCallId` / `toolName` / `input`，即取自 `toolCalls[]` 中的
> `id` / `name` / `input`。

**恢复方式**：使用**相同的 `conversationId`** 重新调用流式接口，并在请求体中携带
`confirmResults`（每项对应一个待确认工具调用，字段取自事件中的 `toolCalls[]`）：

```http
POST /api/conversations/chat/stream
Content-Type: application/json
X-User-Id: user001

{
  "agentName": "general-assistant",
  "conversationId": "conv-123",
  "message": "确认执行",
  "confirmResults": [
    { "toolCallId": "call_b96c5668e76847468992f1",
      "approved": true,
      "toolName": "write_file",
      "input": { "path": "notes.txt", "content": "hello" } }
  ]
}
```

**请求字段（`confirmResults[]`）**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `toolCallId` | string | 是 | 待确认的工具调用 ID，取自事件 `toolCalls[].id`。必须对应当前处于待确认状态的调用，否则框架会拒绝 |
| `approved` | boolean | 否 | 是否批准执行，**默认 `false`（拒绝）**——采用安全默认 |
| `toolName` | string | 否 | 工具名，取自事件 `toolCalls[].name`，建议回传 |
| `input` | object | 否 | 工具入参，取自事件 `toolCalls[].input`。用户也可修改入参后再执行 |

**说明**：

- 不携带 `confirmResults` 时即普通对话，行为不变；携带后仅用于恢复挂起的调用
- 拒绝（`approved: false`）时该工具调用被终止，Agent 会收到拒绝结果并继续生成回复
- 若前端始终不回传，会话会停在挂起点——生产环境应确保前端实现确认交互，或将相关工具从确认清单中移除
- 框架负责校验（ID 有效性、重复、是否处于待确认状态），平台仅做参数转换

### 会话管理

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/conversations` | 创建会话 |
| GET | `/api/conversations/list?userId=` | 按用户查询会话列表 |
| GET | `/api/conversations/{conversationId}` | 查询会话信息 |
| GET | `/api/conversations/{conversationId}/messages` | 查询会话消息列表 |
| POST | `/api/conversations/{conversationId}/chat` | 追加会话聊天 |
| POST | `/api/conversations/{conversationId}/stream` | 追加会话流式聊天 |
| POST | `/api/conversations/cancel/{cancelToken}` | 取消正在执行的任务 |
| GET | `/api/conversations/requests/active-count` | 当前活跃请求数 |

### Agent 中断控制

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/conversations/agent/{name}/interrupt` | 中断 Agent 执行（一次性暂停信号，框架在本次迭代结束后自动消费） |
| GET | `/api/conversations/agent/{name}/status` | 查询 Agent 执行状态 |
| POST | `/api/conversations/agent/{name}/resume` | 恢复 Agent（清除中断状态；框架采用协作式中断、执行会自动继续，此端点兼容保留） |

### 健康检查

```http
GET /actuator/health
```

**响应**

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" }
  }
}
```

---

## MCP 工具（外部服务器注册）

平台**不对外暴露** HTTP MCP 端点（不存在 `POST /mcp` 或 `X-MCP-Token` 请求头）。MCP 服务器作为独立进程运行（见 [yunxi-mcp-servers](https://gitcode.com/chenyao813/yunxi-mcp-servers)，端口 40101+），平台作为 MCP **客户端**连接它们，并把工具注册进 Agent 的 Toolkit。

### 注册机制

Agent 启动时由 `AgentConfigurer.registerMcpServers()` 读取 `agentscope.core.mcp-servers` 配置段（见 `agent-config/src/main/resources/config/mcp-core.yml`），使用框架原生 `McpClientBuilder` 以 SSE / stdio / Streamable HTTP 三种传输方式连接服务器，随后通过 `Toolkit.registration().mcpClient(...).group(name).apply()` 把工具按服务器名分组注册进各 Agent。启用/停用由 `enabled` 开关控制（可配环境变量覆盖）。

### 配置示例（SSE 模式）

```yaml
agentscope:
  core:
    mcp-servers:
      database:
        enabled: true            # 环境变量：MCP_DATABASE_ENABLED
        type: sse
        url: http://localhost:40101/mcp/sse
        timeout: 60000
        description: "数据库操作 MCP 服务器"
```

### 配置示例（stdio 模式）

```yaml
agentscope:
  core:
    mcp-servers:
      puppeteer:
        enabled: false
        type: stdio
        command: npx
        args: ["-y", "@modelcontextprotocol/server-puppeteer"]
```

### 工具调用方式

注册后的 MCP 工具与普通工具一样，由 Agent 在对话过程中根据任务自主调用（工具名以服务器名前缀区分），客户端无需直接调用 MCP 接口，通过 Chat API 即可触发。

---

## 错误码

### HTTP 状态码

| 状态码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |

### MCP 协议错误码（参考，供 MCP 服务器实现使用）

| 错误码 | 名称 | 说明 |
|--------|------|------|
| -32700 | PARSE_ERROR | 解析错误 |
| -32600 | INVALID_REQUEST | 无效请求 |
| -32601 | METHOD_NOT_FOUND | 方法未找到 |
| -32602 | INVALID_PARAMS | 无效参数 |
| -32603 | INTERNAL_ERROR | 内部错误 |

---

## SDK 使用

平台提供官方 **JavaScript/TypeScript SDK**（`sdk-js`，npm 包 `yunxi-agent-client`），支持 Node.js 与浏览器。当前无官方 Java SDK，Java 侧可直接调用上文 REST 端点。

### JavaScript SDK（推荐）

```javascript
const AgentClient = require('yunxi-agent-client');

// 创建客户端
const client = new AgentClient('http://localhost:40001', {
    defaultUserId: 'user001',   // 默认用户 ID（对应 X-User-Id 请求头）
    defaultAgentName: 'general-assistant'
});

// 同步对话：等待完整回复后返回
const response = await client.chatSync('你好，请介绍一下自己');
console.log(response);

// 流式对话：逐块输出
await client.chatStream('写一个冒泡排序', (chunk) => {
    console.log(chunk);
});

// 结构化事件：可渲染思考过程、工具调用卡片
await client.chatStreamEvents('查询今天天气', {
    onText:     (s) => console.log('[回复]', s),
    onThinking: (s) => console.log('[思考]', s),
    onToolCall: (t) => console.log('[工具]', t.toolCallName),
    onDone:     () => console.log('[结束]')
});
```

**常用方法**：`chatSync(message, options)`、`chatStream(message, onChunk, options)`、`chatStreamIterator`、`chatStreamEvents`、`chatStructured(message, schema)`、`isAvailable()`、`getServiceInfo()`。

浏览器中也可直接使用内置静态资源（`http://localhost:40001/static/js/AgentClient.js`），完整 API 见 [sdk-js/README.md](../../sdk-js/README.md)。

---

**上一页**: [08. 部署指南](./08-deployment.md)  
**下一页**: [10. 技能系统 →](./10-skills.md)
