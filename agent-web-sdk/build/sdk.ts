/**
 * Custom IIFE SDK entry — bundle of PageAgent / PageController / zod / tool
 * helpers for in-page enhanced DOM scanning.
 *
 * 本文件是 page-agent 上游仓之外的定制构建入口（单一源在 yunxi-agent-platform/
 * agent-web-sdk）。它只决定"打包哪些类、暴露哪些全局变量"，不含业务逻辑。
 *
 * 产物为不自动初始化的 IIFE 单文件：消费方（PageAgentSDK 加载器）
 * 自行实例化 PageController 获取增强 pageController，无 Panel UI、无 Agent 循环。
 *
 * 构建（在 page-agent 仓根执行，复用其 node_modules 与工具链）：
 *   cd <page-agent-repo>
 *   npx vite build -c <agent-web-sdk>/build/vite.sdk.config.js
 *
 * 产物输出到 agent-web-sdk/dist/page-agent.sdk.js。
 */
import { PageAgent } from '@page-agent/page-agent'
import { PageController } from '@page-agent/page-controller'
import * as z from 'zod/v4'
import { tool } from '@page-agent/core'

declare global {
  interface Window {
    /** 完整 PageAgent 类（含 Panel UI，一般仅供自定义集成） */
    PageAgent: typeof PageAgent
    /** 独立 PageController 类：加载器用它实例化增强 pageController（无 UI、无 Agent 循环） */
    PageAgentPageController: typeof PageController
    /** zod v4，供页面内自定义工具的参数 schema 声明 */
    PageAgentZod: typeof z
    /** 自定义工具工厂 */
    PageAgentTool: typeof tool
  }
}

// Mount to global window object
window.PageAgent = PageAgent
window.PageAgentPageController = PageController
// Expose zod and tool helper for custom tool creation
window.PageAgentZod = z
window.PageAgentTool = tool
