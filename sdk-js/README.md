# yunxi Agent Platform JavaScript/TypeScript SDK

简洁易用的JavaScript/TypeScript客户端，用于调用yunxi Agent Platform服务。

支持Node.js和浏览器环境。

## 📦 安装

### NPM安装

```bash
npm install yunxi-agent-client
```

### 手动引入

下载 `src/AgentClient.js` 并在项目中引入：

```html
<!-- 浏览器环境 -->
<script src="AgentClient.js"></script>
```

```javascript
// Node.js环境
const AgentClient = require('./AgentClient.js');
```

---

## 🚀 快速开始

### 基础使用

```javascript
const AgentClient = require('yunxi-agent-client');

// 创建客户端
const client = new AgentClient('http://localhost:40001');

// 发送消息
const response = await client.chat('如何使用JavaScript?');
console.log(response);
```

### 同步对话

```javascript
const response = await client.chatSync('1+1等于几?');
console.log(response);  // 输出: 1+1等于2
```

### 流式输出

```javascript
await client.chatStream('写个冒泡排序', (chunk) => {
    console.log(chunk);  // 逐块输出
});
```

---

## 📖 详细文档

### 1. 构造函数

```javascript
const client = new AgentClient(baseUrl, options);
```

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| baseUrl | string | 是 | 服务基础URL (例如: "http://localhost:8080") |
| options | object | 否 | 配置选项 |

**options配置：**

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| defaultUserId | string | null | 默认用户ID |
| defaultAgentName | string | null | 默认Agent名称 |
| timeout | number | 300000 | 超时时间(毫秒)，默认5分钟 |
| headers | object | {} | 自定义请求头 |

**示例：**

```javascript
const client = new AgentClient('http://localhost:8080', {
    defaultUserId: 'user123',
    defaultAgentName: 'coding-assistant',
    timeout: 600000  // 10分钟
});
```

---

### 2. 同步对话

等待完整响应返回，适用于简单查询。

```javascript
const response = await client.chatSync(message, options);
```

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| message | string | 是 | 用户消息 |
| options | object | 否 | 可选参数 |

**options配置：**

| 参数 | 类型 | 说明 |
|------|------|------|
| conversationId | string | 会话ID |
| agentName | string | Agent名称 |

**返回值：** `Promise<string>` - Agent的完整回复

**示例：**

```javascript
// 基础调用
const response = await client.chatSync('1+1等于几?');

// 指定Agent
const response = await client.chatSync('写个排序算法', { 
    agentName: 'coding-assistant' 
});

// 指定会话ID
const response = await client.chatSync('继续对话', { 
    conversationId: 'conv-xxx' 
});
```

---

### 3. 流式对话

实时接收Agent的回复，逐块显示。

#### 方式1: 使用回调函数

```javascript
await client.chatStream(message, onChunk, options);
```

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| message | string | 是 | 用户消息 |
| onChunk | function | 是 | 数据块回调函数 `(chunk: string) => void` |
| options | object | 否 | 可选参数 |

**示例：**

```javascript
await client.chatStream('写一首诗', (chunk) => {
    console.log(chunk);  // 逐块输出
});
```

#### 方式2: 使用AsyncGenerator

```javascript
for await (const chunk of client.chatStreamIterator(message, options)) {
    console.log(chunk);
}
```

**示例：**

```javascript
for await (const chunk of client.chatStreamIterator('写篇文章')) {
    console.log(chunk);
}
```

#### 方式3: 结构化事件回调（chatStreamEvents）

`chatStream` 仅回传「原始文本块」，无法区分文本、思考过程与工具调用。`chatStreamEvents` 会把每条 SSE 事件解析为结构化对象，并分派到对应 handler，便于渲染**工具调用卡片**、**工具内流式进度（打字机）**等。

```javascript
await client.chatStreamEvents('查一下北京天气', {
    onToolCall:        (t) => ui.addToolCard(t.toolCallName, t.toolCallId),
    onToolResultStart: (t) => ui.openToolOutput(t.toolCallId),
    onToolResultDelta: (d) => ui.appendToolOutput(d.toolCallId, d.delta), // 工具内流式进度（打字机式）
    onToolResult:      (t) => ui.finishTool(t.toolCallId, t.state),
    onThinking:        (s) => ui.appendThinking(s),
    onText:            (s) => ui.appendAnswer(s),
    onError:           (e) => ui.showError(e.message),
});
```

**handlers 事件类型（均可选）：**

| handler | 触发时机 | 入参 |
|---------|----------|------|
| onText | 文本增量（type=`content`） | string |
| onThinking | 思考过程增量（type=`thinking`） | string |
| onToolCall | 工具调用开始（type=`tool_call`） | object `{toolCallId, toolCallName}` |
| onToolCallDone | 工具参数就绪（type=`tool_call_done`） | object `{toolCallId, toolCallName}` |
| onToolResult | 工具结果（type=`tool_result`） | object `{toolCallId, toolCallName, state}` |
| onToolResultStart | 工具流式输出开始（type=`tool_result_start`） | object `{toolCallId, toolCallName}` |
| onToolResultDelta | 工具流式输出增量（type=`tool_result_delta`） | object `{toolCallId, toolCallName, delta}` |
| onStatus | 状态提示（type=`agent_status`） | object |
| onError | 错误（type=`error`） | object |
| onEvent | 每条解析后的事件兜底分发 | object `{type, timestamp, content}` |
| onDone | 流结束 | - |

> 后端对 `content`/`thinking` 下发纯文本，对其余友好消息下发 JSON 字符串，`chatStreamEvents` 会自动 `JSON.parse` 后传入对应 handler（`tool_result_delta` 自动提取 `delta` 字符串）。

**其它说明：**
- `chatStream` 仍向后兼容（继续回传原始文本块），并额外支持 `options.onEvent(evt)` 手动解析每条 SSE 事件。
- 若前端不走 SDK、直接消费 SSE，可对 `data:` 行做 `JSON.parse(data.content)`，按 `type` 字段自行渲染 `tool_result_delta` 等事件。

---

### 4. 简化调用

默认使用流式模式，返回完整响应。

```javascript
const response = await client.chat(message, options);
```

**示例：**

```javascript
const response = await client.chat('如何使用Python?');
console.log(response);
```

**指定会话ID：**

```javascript
const response = await client.chatWithConversation('继续对话', 'conv-xxx');
```

---

### 5. 自动会话管理

指定用户ID，系统自动管理会话生命周期。

```javascript
const client = new AgentClient('http://localhost:8080', {
    defaultUserId: 'user123'
});

// 第一次调用：创建新会话
const r1 = await client.chat('什么是Java?');

// 第二次调用：自动复用同一会话
const r2 = await client.chat('如何使用Stream?');
```

---

### 6. 结构化输出

返回JSON格式的结构化数据。

```javascript
const result = await client.chatStructured(message, schema, options);
```

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| message | string | 是 | 用户消息 |
| schema | object | 是 | JSON Schema |
| options | object | 否 | 可选参数 |

**返回值：** `Promise<object>` - 结构化数据

**示例：**

```javascript
const schema = {
    type: 'object',
    properties: {
        name: { type: 'string' },
        age: { type: 'integer' },
        skills: {
            type: 'array',
            items: { type: 'string' }
        }
    },
    required: ['name', 'age', 'skills']
};

const result = await client.chatStructured(
    '生成一个Java开发者的信息',
    schema
);

console.log(result);
// 输出: { name: '张三', age: 25, skills: ['Java', 'Spring'] }
```

---

### 7. 健康检查

检查服务是否可用。

```javascript
const isAvailable = await client.isAvailable();
```

**返回值：** `Promise<boolean>` - 服务是否可用

**示例：**

```javascript
if (await client.isAvailable()) {
    console.log('✅ 服务正常');
    const response = await client.chat('Hello');
} else {
    console.log('❌ 服务不可用');
}
```

---

### 8. 获取服务信息

```javascript
const info = await client.getServiceInfo();
```

**返回值：** `Promise<object>` - 服务信息

**示例：**

```javascript
try {
    const info = await client.getServiceInfo();
    console.log('服务信息:', info);
} catch (error) {
    console.log('无法获取服务信息');
}
```

---

## 💡 使用场景

### 场景1: Node.js后端应用

```javascript
const express = require('express');
const AgentClient = require('yunxi-agent-client');

const app = express();
const client = new AgentClient('http://localhost:8080', {
    defaultUserId: 'web-app'
});

app.post('/api/chat', async (req, res) => {
    try {
        const { message } = req.body;
        const response = await client.chatSync(message);
        res.json({ success: true, response });
    } catch (error) {
        res.status(500).json({ success: false, error: error.message });
    }
});

app.listen(3000);
```

### 场景2: React前端应用

```javascript
import React, { useState } from 'react';
import AgentClient from 'yunxi-agent-client';

const client = new AgentClient('http://localhost:8080', {
    defaultUserId: 'react-user'
});

function ChatApp() {
    const [message, setMessage] = useState('');
    const [response, setResponse] = useState('');
    const [loading, setLoading] = useState(false);

    const handleSubmit = async () => {
        setLoading(true);
        try {
            const result = await client.chatStream(message, (chunk) => {
                setResponse(prev => prev + chunk);
            });
        } catch (error) {
            console.error(error);
        }
        setLoading(false);
    };

    return (
        <div>
            <textarea value={message} onChange={e => setMessage(e.target.value)} />
            <button onClick={handleSubmit} disabled={loading}>
                {loading ? '发送中...' : '发送'}
            </button>
            <div>{response}</div>
        </div>
    );
}
```

### 场景3: Vue前端应用

```javascript
<script>
import AgentClient from 'yunxi-agent-client';

export default {
    data() {
        return {
            client: new AgentClient('http://localhost:8080'),
            message: '',
            response: '',
            loading: false
        };
    },
    methods: {
        async send() {
            this.loading = true;
            this.response = '';
            try {
                await this.client.chatStream(this.message, (chunk) => {
                    this.response += chunk;
                });
            } catch (error) {
                console.error(error);
            }
            this.loading = false;
        }
    }
};
</script>
```

---

## 🎯 最佳实践

### 1. 复用客户端实例

```javascript
// ✅ 正确：复用客户端实例
class ChatService {
    constructor() {
        this.client = new AgentClient('http://localhost:8080', {
            defaultUserId: 'service-user'
        });
    }

    async chat(message) {
        return await this.client.chatSync(message);
    }
}

const service = new ChatService();
service.chat('Hello');
service.chat('World');
```

### 2. 错误处理

```javascript
try {
    const response = await client.chatSync('测试');
    console.log(response);
} catch (error) {
    if (error.message.includes('超时')) {
        console.error('请求超时，请重试');
    } else if (error.message.includes('HTTP')) {
        console.error('网络错误');
    } else {
        console.error('其他错误:', error.message);
    }
}
```

### 3. 并发处理

```javascript
const questions = [
    '什么是Java?',
    '什么是Python?',
    '什么是Go?'
];

// 并发处理
const responses = await Promise.all(
    questions.map(q => client.chatSync(q))
);

console.log(responses);
```

---

## 🔧 TypeScript支持

SDK提供完整的TypeScript类型定义：

```typescript
import AgentClient from 'yunxi-agent-client';

const client: AgentClient = new AgentClient('http://localhost:8080', {
    defaultUserId: 'user123',
    timeout: 600000
});

const response: string = await client.chatSync('测试');
```

---

## 📚 示例代码

仓库 `sdk-js/examples/` 目录下提供了可直接运行的 Node.js 示例：

- `examples/basic-usage.js` - 基础对话（`chat`）示例
- `examples/advanced-usage.js` - 结构化流式事件（`chatStreamEvents`，含工具卡片渲染）示例

运行示例：

```bash
# 需先启动 yunxi-agent-platform 后端（默认 http://localhost:40001）
node examples/basic-usage.js
node examples/advanced-usage.js
```

---

## 🖥️ 浏览器演示与静态资源

后端（`agent-config`）把若干演示页作为静态资源对外提供，访问地址：

- `http://localhost:40001/chat.html` - 基础聊天演示
- `http://localhost:40001/chat-tool-call.html` - 工具调用流式演示
- `http://localhost:40001/page-agent-recipe.html` - 页面智能体演示

> **单一真相源**：这些演示页引用的 `static/js/AgentClient.js` 与 `static/js/agent-sdk/agent-sdk.js`
> **不是手工维护的副本**，而是由本包通过 `npm run sync` 从 `sdk-js/src` 自动生成。
> 修改 SDK 后请在 `sdk-js/` 目录执行 `npm run sync` 重新生成静态资源，**请勿直接编辑** `static/js` 下的文件。
>
> ```bash
> cd sdk-js
> npm run sync
> ```

---

## ❓ 常见问题

### Q: Node.js中报错 "fetch is not defined"

**A:** SDK会自动检测环境，如果是Node.js，需要安装 `node-fetch`：

```bash
npm install node-fetch
```

### Q: 如何设置超时时间？

```javascript
const client = new AgentClient('http://localhost:8080', {
    timeout: 600000  // 10分钟
});

// 或运行时设置
client.setTimeout(600000);
```

### Q: 浏览器中如何使用？

```html
<!DOCTYPE html>
<html>
<head>
    <script src="AgentClient.js"></script>
</head>
<body>
    <script>
        const client = new AgentClient('http://localhost:8080');
        client.chat('Hello').then(response => {
            console.log(response);
        });
    </script>
</body>
</html>
```

### Q: 如何处理CORS问题？

如果服务端没有配置CORS，浏览器会报跨域错误。请在服务端配置CORS头，或使用代理。

---

## 📄 许可证

MIT License

---

## 🤝 贡献

欢迎提交Issue和Pull Request！

---

## 📞 技术支持

- GitCode Issues: [https://gitcode.com/chenyao813/yunxi-agent-platform/issues](https://gitcode.com/chenyao813/yunxi-agent-platform/issues)
- 文档: [SDK使用指南](../SDK_USAGE_GUIDE.md)
