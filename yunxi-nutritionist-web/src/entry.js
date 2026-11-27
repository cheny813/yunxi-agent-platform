// 公共前端 SDK 入口（单一源：yunxi-agent-platform/agent-web-sdk）
// 通过 import 把 IIFE 形式的模块纳入 Vite 模块图，
// build 时打进 bundle，运行时挂到 window 命名空间。

// PageAgent DomEngine — 纯 DOM 执行引擎（无 LLM 决策循环）
//   → window.PageAgentDomEngine: scan / fill / batchFill / click / highlight
import '@web-sdk/page-agent-dom-engine.js';

// PageAgent SDK Loader — 按需加载原生 SDK，增强 DOM 元素检测
//   → window.PageAgentSDK: loadSdk / isLoaded / getPageController
//   → 加载后 PageAgentDomEngine 自动使用 SDK 的增强 getBrowserState()
import '@web-sdk/page-agent-sdk.js';

// FormFill Client — mcp-formfill WebSocket → DomEngine 桥接层
//   → window.FormFillClient: connect / reportStructure / fillForm / disconnect
//   → 后端 AgentScope 通过 /ws/formfill 下发指令，客户端自动调用 DomEngine 填表
import '@web-sdk/formfill-client.js';

// FormFill Debug — 可选的调试面板（默认不挂载）
//   → FormFillClient.ensureDebugPanel() 手动挂载
import '@web-sdk/formfill-debug.js';

// Yunxi Chat — 通用对话工具（同步 / 流式统一封装）
//   → window.yunxiChat: chat({ agentId, text, context, mode, onDelta, onDone })
import '@web-sdk/yunxi-chat.js';

// A2UI Renderer — 服务端驱动界面渲染（官方 web_core 引擎 + 最小 DOM 投影）
//   → window.A2uiRenderer.mountA2ui(container, payload)
//   → 依赖 @a2ui/web_core 由本产品（消费方）安装，SDK 源码共享不自带依赖
import '@web-sdk/a2ui-render.js';

// Macro Renderer — @macroui/a2ui 流式界面（报表/图表/表格，30+ 组件、自带主题）
//   → window.MacroRenderer.render(container, dsl) / setTheme(name)
import '@web-sdk/macro-render.js';
