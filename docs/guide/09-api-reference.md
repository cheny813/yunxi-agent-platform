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

流式响应为 `text/event-stream`，每条 `data:` 行包含 `{type, timestamp, content}` 结构事件，`type` 取值包括 `content`（回复增量）、`thinking`（思考过程）、`tool_call`、`tool_result`、`agent_status`、`error` 等。

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
| POST | `/api/conversations/agent/{name}/resume` | 恢复 Agent（清除中断状态） |

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
