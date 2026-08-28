# 云曦营养师前端（agent-nutritionist-web）

机构级 AI 营养管家网页应用，是云曦营养师（旗舰产品）的独立前端工程。
后端由 `yunxi-agent-platform` 的 `agent-core` 提供 Agent 编排与营养评分能力，
底层营养工具来自 `yunxi-mcp-servers/mcp-nutrition`。

## 与后端的边界

- 本仓库只包含**用户交互入口**（页面 + 前端 SDK 透传层）。
- AI 能力、systemPrompt、工具定义、API Key 全部在后端：
  - 页面配置：`GET /v1/agent/config?pageType=nutritionist`（`agent-core` 的 `OpenAIProxyController`）
  - **主流程对话**：`POST /api/conversations/chat/stream`（`ConversationController`，经统一执行引擎，
    含记忆、意图识别、评分与结构化输出），由公共层 `window.yunxiChat` 封装
  - 轻量问答：`POST /v1/chat/completions`（`OpenAIProxyController`，裸 LLM 透传，**无评分能力**），
    仅用于侧边栏即时问答与自动填表
- 开发时通过 Vite 代理把 `/api`、`/v1`、`/js` 转发到后端（默认 `localhost:40001`），
  无需在前端硬编码任何密钥；`/api` 的 SSE 响应已禁用代理缓冲，保证流式输出逐块转发。

## 页面

- `index.html` —— 产品首页（Hero + 核心能力 + 应用场景 + 工作流程 + CTA），
  通过 `PageAgentSDK.init()` 接入页面级 Agent 能力
- `pages/recipe-make.html` —— 智能配餐工作台（左栏目标表单 + 右栏 AI 助手）：
  - **主流程**（方案生成）走 `yunxiChat({ mode: 'stream' })` → `/api/conversations/chat/stream`，
    具备记忆、意图识别与评分能力；Agent 启用任务清单时，生成过程会实时渲染步骤进度（见下）
  - **侧边栏**（即时问答 / 自动填表）走 `/v1/chat/completions` 轻量透传

> 已剔除：定价/方案页、客户案例/数据背书（无真实数据，不杜撰）。

## 任务清单展示

后端 Agent 若启用任务清单（`plan.taskList: true`），会在长任务执行过程中推送
`todo_update` 事件，页面右上角实时渲染任务卡片（待办 / 进行中 / 已完成 + x/y 进度）。

- 渲染容器：`#todo-panel`（位于 `#result-area` **之外**，避免被方案内容的 `innerHTML` 覆盖）
- 事件接入：`yunxiChat({ onTodo: (todos) => renderTodoCard(todos) })`
- 事件为**全量透出**，页面整体替换渲染，不做增量合并

启用与事件契约见平台文档
[06. 配置参考](../docs/guide/06-configuration.md#任务清单todolist配置) 与
[09. API 参考](../docs/guide/09-api-reference.md#todo_update-事件任务清单)。

## 公共前端 SDK 层（重要）

本工程**不拷贝**任何前端 SDK 透传层。它们来自平台仓的公共层
`yunxi-agent-platform/agent-web-sdk`（单一源，所有 `agent-*-web` 产品共享）：

- `src/entry.js` 通过 Vite alias `@web-sdk`（`vite.config.js`）import 公共层源码，
  dev/build 均指向同一份源文件，避免多产品各自漂移：
  - `page-agent-sdk.js` → `window.PageAgentSDK`（页面级 Agent 能力，用于首页）
  - `yunxi-chat.js` → `window.yunxiChat`（对话客户端，用于配餐工作台主流程）
- 调整透传层逻辑时，**只改 `agent-web-sdk/src/` 对应文件一处**，勿在本工程内新增副本。

## 本地开发

前置：先启动 `yunxi-agent-platform`（agent-core，默认端口 40001）。

```bash
npm install
npm run dev          # http://localhost:5173
```

如需指定后端端口：

```bash
BACKEND_PORT=40001 npm run dev
```

## 构建

```bash
npm run build        # 输出到 dist/（公共 SDK 已打包进 assets/entry-*.js）
npm run preview
```

## 关键约束（AI 验证点）

- 前端不得硬编码 systemPrompt / 工具定义 —— 必须从 `/v1/agent/config` 获取。
- 首页 `index.html` 必须调用 `PageAgentSDK.init({ apiBase: window.location.origin, pageType: 'nutritionist', ... })`。
- `recipe-make.html` 的方案生成**必须走 `yunxiChat`**（`/api/conversations/chat/stream`），
  不得改用 `/v1/chat/completions` —— 后者是裸 LLM 透传，无记忆、意图与评分能力。
- 营养评分数据须来自后端 `mcp-nutrition`，前端不伪造数值。
- 公共 SDK 透传层只存在于 `agent-web-sdk/`，本工程通过 alias 引用，不得另存副本。
