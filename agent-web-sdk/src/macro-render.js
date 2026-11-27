// @macroui/a2ui 公共渲染组件（框架无关；支持表格/8 类图表/表单/明暗主题/响应式）。
//
// 职责：把 onMacroDsl(dsl) 回调给出的 DSL 文本渲染到容器。DSL 解析、组件渲染、资源
// 保护（maxBuffer/maxDepth）全部由 @macroui/a2ui 包承担，本文件只做生命周期封装：
// 单例 A2UI 实例（避免重复注册事件 handler）、主题切换、容器重置与错误降级。
//
// 依赖：消费方页面须安装 @macroui/a2ui（源码共享模式，依赖由消费方解析）。
// 全局接口：window.MacroRenderer = { render(dsl), setTheme(name) }。

import { A2UI, setTheme } from '@macroui/a2ui';
import '@macroui/a2ui/css';

var instance = null;
var currentContainer = null;

function getRenderer(container) {
  if (!instance || currentContainer !== container) {
    instance = new A2UI({ container: container, theme: 'default', streaming: false });
    currentContainer = container;
  }
  return instance;
}

/**
 * 渲染 macro DSL 到容器。
 * @param {HTMLElement} container 渲染容器
 * @param {string} dsl @macroui DSL 文本（来自 onMacroDsl 回调）
 */
function renderMacroDsl(container, dsl) {
  if (!container || typeof dsl !== 'string' || !dsl.trim()) return;
  try {
    getRenderer(container).render(dsl);
  } catch (e) {
    // 渲染失败不破坏页面：降级为可读错误（官方 parser 自身也有 details.a2ui-error 降级）
    container.innerHTML = '';
    var err = document.createElement('details');
    err.className = 'a2ui-error';
    err.textContent = '界面渲染失败：' + (e && e.message ? e.message : e);
    container.appendChild(err);
  }
}

/** 切换主题（default / dark 或自定义 data-a2ui-theme 值）。 */
function setMacroTheme(name) {
  try { setTheme(name); } catch (e) { /* 主题失败不影响已渲染内容 */ }
}

if (typeof window !== 'undefined') {
  window.MacroRenderer = window.MacroRenderer || {};
  window.MacroRenderer.render = renderMacroDsl;
  window.MacroRenderer.setTheme = setMacroTheme;
}
