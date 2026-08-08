/**
 * ============================================================================
 * page-agent.js v2 — 新架构兼容层
 * ============================================================================
 *
 * 从 v1（LLM Agent 决策循环：Panel / Ball / init / show / hide / dispose）
 * 平滑过渡到 v2（DomEngine + FormFillClient）：
 *
 *   旧路径：LLM 在浏览器里自己做决策 → 调用页面工具 → 写入页面
 *   新路径：后端 AgentScope 决策 → mcp-formfill WebSocket → FormFillClient → DomEngine
 *
 * 核心能力迁移：
 *   1. DOM 读写       → PageAgentDomEngine（agent-web-sdk/page-agent-dom-engine.js）
 *   2. 表单桥接       → FormFillClient（agent-web-sdk/formfill-client.js）
 *   3. 增强元素检测   → PageAgentSDK（agent-web-sdk/page-agent-sdk.js）
 *   4. 配置/工具定义  → 移除（不再需要浏览器端 LLM Agent 配置）
 *
 * 加载后挂载 window.PageAgentSDK（兼容旧 API，实际均委托给新模块）。
 */

(function () {
  'use strict';

  // ── 兼容 API（委托给新架构模块）──────────────────────────────────────

  window.PageAgentSDK = window.PageAgentSDK || {
    /** 已废弃：不再启动浏览器端 LLM Agent 循环。请改用 FormFillClient + DomEngine。 */
    init: function () {
      console.warn('[PageAgentSDK] init() 已废弃。LLM 决策循环已迁移到后端 AgentScope，前端改用 FormFillClient + PageAgentDomEngine。');
      return Promise.resolve();
    },

    /** 已废弃：不再需要悬浮球。 */
    showBall: function () {
      console.warn('[PageAgentSDK] showBall() 已废弃。');
    },
    hideBall: function () {
      console.warn('[PageAgentSDK] hideBall() 已废弃。');
    },
    onBallClick: function () {},

    /** 返回当前 pageController（若有加载 SDK）。 */
    getPageController: function () {
      return (window.__PAGE_AGENT__ && window.__PAGE_AGENT__.pageController) || null;
    },

    /** 获取 DomEngine 实例（新架构唯一真相源）。 */
    getDomEngine: function () {
      return window.PageAgentDomEngine || null;
    },

    /** 获取 FormFillClient 单例。 */
    getClient: function () {
      return window.FormFillClient ? window.FormFillClient.getClient() : null;
    },
  };

  console.log('[PageAgentSDK] v2 兼容层已加载。LLM 决策循环已移除，请使用 FormFillClient + PageAgentDomEngine。');

})();
