# 云曦营养师前端（agent-nutritionist-web）

机构级 AI 营养管家网页应用，是云曦营养师（旗舰产品）的独立前端工程。
后端由 `yunxi-agent-platform` 的 `agent-core` 提供 Agent 编排与营养评分能力，
底层营养工具来自 `yunxi-mcp-servers/mcp-nutrition`。

## 与后端的边界

- 本仓库只包含**用户交互入口**（页面 + 前端 SDK 透传层）。
- AI 能力、systemPrompt、工具定义、API Key 全部在后端：
  - 页面配置：`GET /v1/agent/config?pageType=nutritionist`（`agent-core` 的 `OpenAIProxyController`）
  - 对话代理：`POST /v1/chat/completions`（`OpenAIProxyController`）
- 开发时通过 Vite 代理把 `/v1` 转发到后端（默认 `localhost:40001`），无需在前端硬编码任何密钥。

## 页面

- `index.html` —— 产品首页（Hero + 核心能力 + 应用场景 + 工作流程 + CTA）
- `pages/recipe-make.html` —— 智能配餐工作台（左栏目标表单 + 右栏 AI 助手，对接 `PageAgentSDK`）

> 已剔除：定价/方案页、客户案例/数据背书（无真实数据，不杜撰）。

## 公共前端 SDK 层（重要）

本工程**不拷贝** PageAgentSDK 透传层。它来自平台仓的公共层
`yunxi-agent-platform/agent-web-sdk`（单一源，所有 `agent-*-web` 产品共享）：

- `src/entry.js` 通过 Vite alias `@web-sdk`（`vite.config.js`）import 公共层的
  `page-agent-sdk.js`，dev/build 均指向同一份源文件，避免多产品各自漂移。
- 调整透传层逻辑时，**只改 `agent-web-sdk/src/page-agent-sdk.js` 一处**，勿在本工程内新增副本。

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
- `recipe-make.html` 必须调用 `PageAgentSDK.init({ apiBase: window.location.origin, pageType: 'nutritionist', ... })`。
- 营养评分数据须来自后端 `mcp-nutrition`，前端不伪造数值。
- 公共 SDK 透传层只存在于 `agent-web-sdk/`，本工程通过 alias 引用，不得另存副本。
