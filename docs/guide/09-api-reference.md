# 09. API 参考

完整的 API 文档。

## API 设计原则

平台对外暴露 REST 风格的 HTTP 端点，统一以 `/api` 为前缀、使用标准 HTTP 方法（GET/POST/PUT/DELETE）与状态码，接口按资源语义组织（会话、轨迹、MCP 服务器等）。下文按功能分组列出端点；完整契约以各模块实际 Controller 为准。

---

## 认证方式

平台 web 协议以请求头传递身份：`X-User-Id` 标识用户，`Authorization: Bearer <jwt>` 用于 A2A 跨服务 JWT 鉴权。IM 渠道的身份由框架 Channel 验签后在 `RuntimeContext` 注入（见 [16.8 IM 渠道网关](./16-ingress-gateway.md)）。应用层安全模型（端点放行、角色约束）见 [06. 配置指南 · 安全配置](./06-configuration.md#安全配置)。

```http
X-User-Id: your-user-id
```

```http
Authorization: Bearer your-jwt-token
```

> 说明：平台不提供独立的 MCP Token。外部 MCP 服务器（如 yunxi-mcp-servers 各服务）如需鉴权，在其自身配置中设置，与平台 API 调用无关。

---

## Chat API

统一入口：`POST /api/conversations`（非流式，一次性返回 `ConversationResponse`）；流式专用入口：`POST /api/conversations/stream`（SSE，事件以 `event: agent-event` 命名事件下发）。

> **端点范围说明**：`yunxi-ingress` 当前仅由 `ConversationController` 暴露这两个端点。下方「会话管理」「Agent 中断控制」「MCP 动态注册」等端点表描述的是其他模块 Controller 提供的附加能力（分属 capability / yunxi-app 等），**不**在 `ConversationController` 内，使用前请对照实际 Controller 校验路径与字段；「推理轨迹」一节仅为设计态，当前无对应端点。

### 发送消息（同步）

**请求**

```http
POST /api/conversations
Content-Type: application/json
X-User-Id: user001

{
  "agentId": "example-assistant",
  "userId": "user001",
  "sessionId": "s-001",
  "text": "你好"
}
```

**请求字段**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `agentId` | string | 是 | Agent 唯一标识（对应 AgentSpec 的 `identity.agentId`，如 `example-assistant`） |
| `text` | string | 是 | 用户消息文本 |
| `userId` | string | 否 | 用户标识（建议填写，用于身份与多轮隔离；经 `X-User-Id` 头或字段传入均可） |
| `sessionId` | string | 否 | 会话 ID，缺省由框架自动创建新会话 |
| `protocol` | string | 否 | 协议类型，默认 `web`（web / a2a / agui / chat-completions 共用同一请求类型） |
| `context` | object | 否 | 附加上下文（Map，透传给运行时） |

**响应（sync）**

`POST /api/conversations` 返回 `ConversationResponse`（HTTP 状态码由 `Settle.Status` 映射：`OK`→200、`NO_AGENT`→404、`BUSY`/`STATE_CONFLICT`→409、`REJECTED`/`OVERLOAD`→429、`EXECUTION_ERROR`→500）：

```json
{
  "agentId": "example-assistant",
  "sessionId": "s-001",
  "text": "你好！有什么可以帮助你的？",
  "status": "OK",
  "errorCode": null
}
```

### 发送消息（流式 SSE）

```http
POST /api/conversations/stream
Content-Type: application/json
X-User-Id: user001

{
  "agentId": "example-assistant",
  "userId": "user001",
  "sessionId": "s-001",
  "text": "你好"
}
```

流式响应为 `text/event-stream`，每条事件以 **`event: agent-event`** 命名事件下发，其 `data:` 为一条 AgentScope-Java `AgentEvent` JSON（含 `type` / `timestamp` / `content` 等字段），`type` 取值包括 `content`（回复增量）、`thinking`（思考过程）、`tool_call`、`tool_result`、`agent_status`、`error` 等。框架 `AgentEventType` **无 `todo_update` 类型**——任务清单不经由独立事件下发（详见 [任务清单透出机制](#任务清单透出机制)）。

#### 任务清单透出机制

任务清单（`todo_write`）是 AgentScope-Java 框架原生能力，由 `HarnessAgent.Builder.enableTaskList()` 构建期开关控制。yunxi 已在 `AgentConfigurer.configure()` 唯一装配点接线该开关，采用双通道、默认关闭：**全局开关 `yunxi.agent.taskList.enabled`**（Spring 环境属性，默认 false）与 **per-Agent `agent.plan.taskList`**（经 `AgentSpec.PlanConfig`，YAML 反序列化 `FAIL_ON_UNKNOWN_PROPERTIES=false` 容忍旧定义，默认 false）二者 OR 生效。即 `agent.plan.taskList: true` 已正式生效，无需额外配置即可按 Agent 开启任务清单。

**透出链路（与代码/前端实现一致）**：后端 `ConversationController.stream` 以 `event: agent-event` **纯透传**框架 `AgentEvent`，**不合成** `todo_update` 事件；任务清单随 `todo_write` 工具执行，以 `TOOL_RESULT_TEXT_DELTA`（`toolCallName=todo_write`，即 `TodoTools.render()` 的 markdown）流式下发，`TOOL_RESULT_END` 收尾。前端 SDK `yunxi-chat.js` 按 `toolCallId` 累积这些增量、在 `TOOL_RESULT_END` 还原为结构化列表并经 `onTodo` 回调透出（full-list-replace 语义）。

**前端取数**：

```javascript
yunxiChat({
  mode: 'stream',
  onTodo: (todos, meta) => {
    // todos: [{ content, status, priority }] 全量列表；meta: { toolCallId, revision }
    renderTodoPanel(todos);
  }
});
```

> 框架原生另提供 `CUSTOM`（`name=state_updated`）结构化信号表达任务/权限状态变更，可作为未来更健壮的取数来源；本次透出机制以已实现的 `TOOL_RESULT_TEXT_DELTA` 解析为准。

**说明**：

- `status` 取值：`pending`（待执行）/ `in_progress`（执行中）/ `completed`（已完成）；同一时刻至多一个 `in_progress`（模型违反约束时工具返回错误，清单不被破坏）
- **全量透出**：AgentScope 采用 full-list-replace 语义，前端应**整体替换**任务列表渲染，不做增量合并
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
POST /api/conversations/stream
Content-Type: application/json
X-User-Id: user001

{
  "agentId": "example-assistant",
  "conversationId": "conv-123",
  "sessionId": "s-001",
  "text": "确认执行",
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

### 推理轨迹（Trace，设计态未接线）

`yunxi-observability` 目前只提供轨迹的**数据模型与存储接口**：`TraceRecord`（`traceId` / `userId` / `sessionId` / `createdAt` / `spans`）、`TraceStore`、`InMemoryTraceStore`，由 `RuntimeConfiguration` 暴露为 bean；但**运行时尚未向 `TraceStore` 写入**，仓库内也**没有轨迹查询 / 回放 HTTP 端点**（无 `/api/traces` 控制器，框架 starter 亦不提供）。SSE / AG-UI 两条输出直接由框架 Agent 事件流产生，不经过轨迹库。

> 规划中的 `GET/DELETE /api/traces*` 与 AG-UI 回放（含跨实例实时增量）尚未实现，落地前请勿按本节调用。运行时可观测请见 [14. 可观测](./14-observability.md)。

### 健康检查

```http
GET /actuator/health
```

**响应**

```json
{
  "status": "UP",
  "components": {
    "redis": { "status": "UP" }
  }
}
```

> 健康检查的核心组件为 `redis`（`spring.data.redis.*`，Spring Boot 3.x 的绑定前缀）：Redis 不可用时聚合状态变为 `DOWN`（HTTP 503）。平台默认不连接 MySQL，故通常不含 `db` 组件；库号由 `REDIS_DATABASE` 环境变量控制。若 `redis` 组件报 `NOAUTH`，说明连接未带上密码——先确认配置前缀是否为 `spring.data.redis`（写成旧的 `spring.redis` 会被静默忽略，表现为「配置看着全对但健康检查 503」）。

---

## MCP 工具（外部服务器注册）

平台**不对外暴露** HTTP MCP 端点（不存在 `POST /mcp` 或 `X-MCP-Token` 请求头）。MCP 服务器作为独立进程运行（见 [yunxi-mcp-servers](https://gitcode.com/chenyao813/yunxi-mcp-servers)，端口以各服务 README 为准，如 `nutrition-mcp` 40602、`text2sql-mcp` 40110），平台作为 MCP **客户端**连接它们，并把工具注册进 Agent 的 Toolkit。

### 注册机制

Agent 启动时由 `AgentConfigurer` 经 `McpDirectory` 读取 MCP 服务器配置段（见 `yunxi-config/src/main/resources/config/mcp.yml` 的 `yunxi.mcp.static-servers`），使用框架原生 `McpClientBuilder` 以 SSE / stdio / Streamable HTTP 三种传输方式连接服务器，随后通过 `Toolkit.registration().mcpClient(...).group(name).apply()` 把工具按服务器名分组注册进各 Agent。启用/停用由 `enabled` 开关控制（可配环境变量覆盖）。

### 配置示例（SSE 模式）

```yaml
yunxi:
  mcp:
    static-servers:
      - name: database
        enabled: true            # 环境变量：YUNXI_MCP_STATIC_SERVERS_0_ENABLED
        transport: sse
        url: http://localhost:40101/mcp/sse
        timeout: 60000
        description: "数据库操作 MCP 服务器"
```

### 配置示例（stdio 模式）

```yaml
yunxi:
  mcp:
    static-servers:
      - name: puppeteer
        enabled: false
        transport: stdio
        command: npx
        args: ["-y", "@modelcontextprotocol/server-puppeteer"]
```

### 工具调用方式

注册后的 MCP 工具与普通工具一样，由 Agent 在对话过程中根据任务自主调用（工具名以服务器名前缀区分），客户端无需直接调用 MCP 接口，通过 Chat API 即可触发。

### 动态注册（运行时 REST API）

除静态配置外，平台提供运行期动态注册 MCP 服务器的 REST API，适用于「不停机接入新工具」「多实例统一治理」等场景。动态注册以 Nacos 配置中心为协调底座：

- 每个 MCP 服务器作为目录条目持久化到 Nacos `dataId`（默认 `yunxi.mcp-servers.json`，`group` 默认 `YUNXI_MCP_GROUP`），进程重启后目录不丢失；
- 通过 Nacos Naming 进行服务发现与健康探测，注册实例在协调服务名 `yunxi-mcp-coordinator` 下广播，便于多实例感知；
- 目标服务器不可达时，该服务器仅 WARN 降级并在下次调用时自动重连，**不会**阻塞 HTTP 请求或抛 500；
- Nacos 未启用时自动退化为本地内存目录（单实例、重启即清空）。

> 说明：动态注册的服务器与静态 `yunxi.mcp.static-servers` 段共享同一套 `Toolkit` 注册逻辑，工具同样按服务器名分组、由 Agent 在对话中自主调用。所有接口位于 `/api/mcp/servers`，受平台统一安全模型保护（见 [06. 配置指南 · 安全配置](./06-configuration.md#安全配置)）。

**列出当前目录**

```http
GET /api/mcp/servers
```

返回 `McpServerConfig` 列表（字段对齐 MCP 官方 `server.json` 规范，便于生态互认；`status` 为运行时只读字段）。示例：

```json
[
  {
    "name": "nutrition",
    "transport": "sse",
    "url": "http://localhost:40602/mcp/sse",
    "enabled": true,
    "status": "UP",
    "group": "nutrition"
  }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | 服务器名（即工具组名） |
| `transport` | string | 传输方式：`sse` / `stdio` / `streamable-http` |
| `url` | string | sse / streamable-http 模式地址 |
| `command` | string | stdio 模式启动命令 |
| `args` | string | stdio 模式参数（空格分隔） |
| `headers` | string | 自定义请求头（JSON） |
| `env` | string | 环境变量（JSON） |
| `timeout` | string | 连接超时（毫秒） |
| `enabled` | boolean | 是否注入 Toolkit |
| `status` | string | 健康状态：`UP` / `DOWN`（Naming 健康探测）或 `local`（Nacos 未启用降级） |

**注册（或更新）一个服务器**

```http
POST /api/mcp/servers?name=nutrition
```

请求体为 `McpServerConfig`，常用字段：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 传输方式：`sse` / `stdio` / `streamable-http` |
| `url` | string | sse/http 必填 | 服务器地址 |
| `command` | string | stdio 必填 | 启动命令 |
| `args` | array | stdio 可选 | 启动参数 |
| `headers` | map | 可选 | 自定义请求头 |
| `env` | map | 可选 | 环境变量 |
| `timeout` | int | 可选 | 连接超时（毫秒，默认 60000） |
| `description` | string | 可选 | 服务器描述 |
| `group` | string | 可选 | 工具组名（默认与 `name` 相同） |

SSE 模式示例：

```bash
curl -X POST "http://localhost:40001/api/mcp/servers?name=nutrition" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "sse",
    "url": "http://localhost:40602/mcp/sse",
    "timeout": 60000,
    "description": "营养计算 MCP 服务器"
  }'
```

返回：

```json
{ "name": "nutrition", "status": "registered", "coordinator": "127.0.0.1:8848" }
```

注册为异步注入：HTTP 仅创建目录条目并激活工具组，真实连接（含 `initialize` 握手）在后台完成；若目标此刻不可达，仅记录 WARN 并在首次调用时自动重连。

**注销一个服务器**

```http
DELETE /api/mcp/servers/{name}
```

从目录与 Naming 中移除，并卸载对应工具组。返回：

```json
{ "name": "nutrition", "status": "unregistered" }
```

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

平台提供官方 **JavaScript/TypeScript SDK**（`agent-web-sdk`，npm 包 `@yunxi/web-sdk`），支持 Node.js 与浏览器。当前无官方 Java SDK，Java 侧可直接调用上文 REST 端点。

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

浏览器中也可直接使用内置静态资源（`http://localhost:40001/static/js/AgentClient.js`），完整 API 见 [agent-web-sdk/README.md](../../agent-web-sdk/README.md)。

---

**上一页**: [08. 部署指南](./08-deployment.md)  
**下一页**: [10. 技能系统 →](./10-skills.md)
