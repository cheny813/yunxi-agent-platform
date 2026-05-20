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

### Gateway Token

```http
X-Gateway-Token: your-token
```

### MCP Token

```http
X-MCP-Token: your-token
```

### Bearer Token

```http
Authorization: Bearer your-jwt-token
```

---

## Gateway API

### 发送消息

**请求**

```http
POST /api/gateway/webapi/chat
Content-Type: application/json
X-Gateway-Token: your-token

{
  "userId": "user001",
  "sessionId": "session-123",
  "message": "你好",
  "platform": "web"
}
```

**响应**

```json
{
  "message": "你好！有什么可以帮助你的？",
  "type": "text",
  "timestamp": 1704067200000
}
```

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

## MCP API

### MCP 协议概述

**MCP（Model Context Protocol）** 是 Anthropic 提出的标准化协议：
- 基于 JSON-RPC 2.0
- 支持工具发现、调用、通知
- 统一的错误处理

**协议栈**：
```
应用层：MCP 语义（tools/list, tools/call）
协议层：JSON-RPC 2.0
传输层：HTTP / SSE
```

### 初始化连接

```http
POST /mcp
Content-Type: application/json
X-MCP-Token: your-token

{
  "jsonrpc": "2.0",
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05"
  },
  "id": 1
}
```

### 获取工具列表

```http
POST /mcp
Content-Type: application/json
X-MCP-Token: your-token

{
  "jsonrpc": "2.0",
  "method": "tools/list",
  "id": 2
}
```

### 调用工具

```http
POST /mcp
Content-Type: application/json
X-MCP-Token: your-token

{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "query_database",
    "arguments": {
      "sql": "SELECT * FROM users LIMIT 10"
    }
  },
  "id": 3
}
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

### MCP 错误码

| 错误码 | 名称 | 说明 |
|--------|------|------|
| -32700 | PARSE_ERROR | 解析错误 |
| -32600 | INVALID_REQUEST | 无效请求 |
| -32601 | METHOD_NOT_FOUND | 方法未找到 |
| -32602 | INVALID_PARAMS | 无效参数 |
| -32603 | INTERNAL_ERROR | 内部错误 |

---

## SDK 使用

### SDK 设计原则

**SDK（Software Development Kit）** 封装了 API 调用细节：
- 简化调用流程
- 统一错误处理
- 类型安全
- 自动重试

### Java SDK

```java
AgentClient client = AgentClient.builder()
    .baseUrl("http://localhost:40003")
    .token("your-token")
    .build();

ChatResponse response = client.chat(ChatRequest.builder()
    .userId("user001")
    .message("你好")
    .build());
```

### JavaScript SDK

```javascript
const client = new AgentClient({
  baseUrl: 'http://localhost:40003',
  token: 'your-token'
});

const response = await client.chat({
  userId: 'user001',
  message: '你好'
});
```

---

**上一页**: [08. 部署指南](./08-deployment.md)  
**下一页**: [10. 技能系统 →](./10-skills.md)
