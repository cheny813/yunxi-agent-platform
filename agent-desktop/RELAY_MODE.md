# yunxiClaw 中继服务器模式

## 架构说明

```
┌─────────────┐    ┌─────────────────────┐    ┌─────────────────┐
│   AI 大模型  │───▶│   后端服务 (40001)   │───▶│  DesktopController│
└─────────────┘    │  /api/desktop/*     │    └────────┬────────┘
                   └─────────────────────┘             │
                            │                          │
                            ▼                          ▼
                   ┌─────────────────────┐    ┌─────────────────┐
                   │ /ws/desktop         │◀───│ 桌面客户端       │
                   │ WebSocket 中继      │    │ (yunxiClaw)      │
                   └─────────────────────┘    └─────────────────┘
```

## 数据流

1. **AI 发送命令** → 后端 `/api/desktop/command/{clientId}`
2. **后端转发** → WebSocket 发送到指定桌面客户端
3. **客户端执行** → 执行命令并返回结果
4. **同步等待** → 后端等待客户端返回结果（最多30秒）
5. **结果返回** → API 响应给 AI

## 使用步骤

### 1. 启动后端服务

```bash
cd agent-app
mvn spring-boot:run
```

确保端口 40001 启动成功。

### 2. 启动桌面客户端

```bash
cd agent-desktop
npm install
npm start
```

### 3. 连接中继服务器

在桌面客户端界面：
1. 确认远程服务器地址为 `ws://localhost:40001/ws/desktop`
2. 点击"连接"按钮
3. 状态显示"已连接"即表示成功

### 4. AI 调用示例

```bash
# 1. 查看在线客户端
curl http://localhost:40001/api/desktop/clients

# 2. 发送同步命令（等待结果）
curl -X POST http://localhost:40001/api/desktop/command/yunxi-claw-PC-123 \
  -H "Content-Type: application/json" \
  -d '{
    "type": "execute",
    "command": "echo",
    "args": ["Hello from AI!"]
  }'

# 响应示例（成功）:
{
  "status": "success",
  "stdout": "Hello from AI!",
  "exitCode": 0,
  "requestId": "xxx"
}

# 3. 发送异步命令（立即返回，轮询结果）
curl -X POST http://localhost:40001/api/desktop/command/async/yunxi-claw-PC-123 \
  -H "Content-Type: application/json" \
  -d '{
    "type": "execute",
    "command": "mvn",
    "args": ["test"]
  }'

# 4. 轮询获取异步结果
curl http://localhost:40001/api/desktop/result/{requestId}
```

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/desktop/clients` | 获取所有在线客户端 |
| POST | `/api/desktop/command/{clientId}` | 同步发送命令，等待结果（30秒超时） |
| POST | `/api/desktop/command/async/{clientId}` | 异步发送命令，立即返回 |
| GET | `/api/desktop/result/{requestId}` | 轮询获取异步命令结果 |
| POST | `/api/desktop/broadcast` | 广播命令到所有客户端 |
| GET | `/api/desktop/status` | 获取中继服务器状态 |

## 支持的命令类型

| type | 说明 | 参数 |
|------|------|------|
| `execute` | 执行系统命令 | command, args, cwd |
| `git` | Git 操作 | operation, args |
| `list-dir` | 列出目录 | path |
| `read-file` | 读取文件 | path |
| `write-file` | 写入文件 | path, content |
| `ping` | 心跳 | - |

## 请求示例

### 执行命令

```json
POST /api/desktop/command/{clientId}
{
  "type": "execute",
  "command": "mvn",
  "args": ["clean", "package", "-DskipTests"],
  "cwd": "D:\\project"
}
```

### Git 操作

```json
POST /api/desktop/command/{clientId}
{
  "type": "git",
  "operation": "status"
}
```

### 读取文件

```json
POST /api/desktop/command/{clientId}
{
  "type": "read-file",
  "path": "D:\\project\\pom.xml"
}
```

### 写入文件

```json
POST /api/desktop/command/{clientId}
{
  "type": "write-file",
  "path": "D:\\project\\test.java",
  "content": "public class Test { }"
}
```

## 响应格式

### 成功

```json
{
  "status": "success",
  "stdout": "命令输出...",
  "stderr": "",
  "exitCode": 0,
  "requestId": "req-xxx"
}
```

### 失败

```json
{
  "success": false,
  "error": "TIMEOUT",
  "message": "命令执行超时",
  "requestId": "req-xxx"
}
```

### 客户端离线

```json
{
  "success": false,
  "error": "CLIENT_OFFLINE",
  "message": "客户端不在线或已断开连接"
}
```

## 心跳机制

- **服务端心跳**：每30秒向所有客户端发送 ping
- **客户端响应**：客户端收到 ping 后返回 pong
- **超时清理**：超过2分钟无心跳的客户端会被断开

## 注意事项

1. 同步命令默认超时30秒
2. 异步结果保留1分钟后过期
3. 客户端自动重连（指数退避策略）
4. 客户端 ID 格式：`yunxi-claw-{hostname}-{timestamp}`