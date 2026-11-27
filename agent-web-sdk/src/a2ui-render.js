// A2UI 界面渲染（公共前端 SDK；框架无关，React/Vue/原生 HTML 均可复用）。
//
// 分层契约：消息处理引擎 = 官方 @a2ui/web_core（含 v1.0 适配器与 basic catalog，官方为
// 协议语义单一真相源）；本文件只做「引擎产出的组件树 → 最小 DOM 投影」。数据绑定、组件
// 合法性、状态收敛全部由引擎完成，此处不复制任何协议语义。
//
// 组件节点形状（官方 ComponentModel.componentTree）：{ component: 'Text', id, ...属性平铺 }。
// 子组件引用：Row/Column 为 children（id 字符串数组，模板对象降级忽略）；Card/Button 为 child（单 id）。
//
// v1 投影范围：basic catalog 的 Text / Title / Row / Column / Card / Button / Image /
// Divider / Icon；其余组件降级为占位（附组件类型名）。官方 lit 渲染器（Web Components）
// 目前仅支持 0.8/0.9 协议，待官方跟进 v1.0 后整体替换投影层。
//
// 按钮动作：v1 只做展示闭环，动作不回传 Agent（回传链路为范围外）。
//
// 依赖：消费方页面须安装 @a2ui/web_core（源码共享，非 npm 发布包，依赖由消费方解析）。
// 全局接口：window.A2uiRenderer.mountA2ui(container, payload)。

import { MessageProcessor, Catalog } from '@a2ui/web_core';
import { basicCatalog } from '@a2ui/web_core/catalogs/basic/v1';

// 引擎与 catalog 初始化无会话状态依赖；surface 状态在每次 mountA2ui 时重建
// （v1 单会话单 surface，重渲染即全量回放消息）。引擎实例不跨挂载复用：
// 复用会让上一次的 createSurface 残留，同一 surfaceId 再次生成时报
// "Surface already exists"。
// Catalog.fromJson 为同步静态方法（返回 Catalog 实例，非 Promise）。
function createProcessor() {
  return new MessageProcessor([Catalog.fromJson(basicCatalog)]);
}

function resolveText(value, surface) {
  if (value == null) return '';
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  // 字面量绑定 { literal: "..." }；路径绑定 { path: "..." } 经官方数据模型解析
  if (typeof value === 'object') {
    if (value.literal != null) return String(value.literal);
    if (value.path && surface && surface.dataModel) {
      try {
        var resolved = surface.dataModel.resolve(value.path);
        if (resolved != null) return String(resolved);
      } catch (_) { /* 解析失败降级为空文本 */ }
    }
  }
  return '';
}

function childList(proto) {
  var out = [];
  // 容器类（Row/Column）：children 字符串数组；模板对象（数据绑定生成列表）降级忽略
  if (Array.isArray(proto.children)) {
    proto.children.forEach(function (c) {
      if (typeof c === 'string') out.push(c);
      else if (c && typeof c.id === 'string') out.push(c.id);
    });
  }
  // 单子组件（Card/Button）：child
  if (typeof proto.child === 'string') out.push(proto.child);
  else if (proto.child && typeof proto.child.id === 'string') out.push(proto.child.id);
  return out;
}

function renderComponent(id, components, surface) {
  var model = components.get(id);
  if (!model) {
    var missing = document.createElement('div');
    missing.className = 'a2ui-placeholder';
    missing.textContent = '[缺失组件 ' + id + ']';
    return missing;
  }
  var proto = model.componentTree || {};
  var type = proto.component || 'unknown';
  var children = childList(proto);
  var el;

  if (type === 'Text') {
    el = document.createElement('div');
    el.className = 'a2ui-text';
    el.textContent = resolveText(proto.text, surface);
  } else if (type === 'Title') {
    el = document.createElement('h3');
    el.className = 'a2ui-title';
    el.textContent = resolveText(proto.text, surface);
  } else if (type === 'Row' || type === 'Column') {
    el = document.createElement('div');
    el.className = type === 'Row' ? 'a2ui-row' : 'a2ui-column';
  } else if (type === 'Card') {
    el = document.createElement('div');
    el.className = 'a2ui-card';
  } else if (type === 'Image') {
    el = document.createElement('img');
    el.className = 'a2ui-image';
    el.alt = resolveText(proto.description, surface) || 'A2UI 图片';
    el.src = resolveText(proto.url, surface);
    el.loading = 'lazy';
  } else if (type === 'Divider') {
    el = document.createElement('hr');
    el.className = 'a2ui-divider';
  } else if (type === 'Icon') {
    el = document.createElement('span');
    el.className = 'a2ui-icon';
    el.textContent = resolveText(proto.name, surface) || '◆';
  } else if (type === 'Button') {
    el = document.createElement('button');
    el.className = 'a2ui-button';
    el.type = 'button';
    // v1 展示闭环：动作不回传 Agent
    el.disabled = true;
  } else {
    el = document.createElement('div');
    el.className = 'a2ui-placeholder';
    el.textContent = '[' + type + ']';
  }

  el.dataset.a2uiId = id;
  children.forEach(function (cid) { el.appendChild(renderComponent(cid, components, surface)); });
  return el;
}

function findRootId(surface) {
  var all = surface.componentsModel.getAll();
  var ids = Array.from(all.keys());
  if (ids.length === 0) return null;
  // 根 = 未被任何组件 children/child 引用的组件（防御：全被引用时取首个）
  var referenced = new Set();
  all.forEach(function (model) {
    childList(model.componentTree || {}).forEach(function (cid) { referenced.add(cid); });
  });
  return ids.find(function (id) { return !referenced.has(id); }) || ids[0];
}

/**
 * 渲染 A2UI 载荷到容器。
 * @param {HTMLElement} container 渲染容器
 * @param {{surfaceId: string, messages: Array, issues?: string[]}} payload
 *   a2ui-message 事件载荷（后端已校验）
 */
export async function mountA2ui(container, payload) {
  if (!container) return;
  container.innerHTML = '';

  if (payload && Array.isArray(payload.issues) && payload.issues.length > 0) {
    var warn = document.createElement('div');
    warn.className = 'a2ui-issues';
    warn.textContent = '界面载荷校验未通过，已整批丢弃（' + payload.issues.length + ' 条问题）';
    container.appendChild(warn);
    return;
  }
  if (!payload || !Array.isArray(payload.messages) || payload.messages.length === 0) {
    var empty = document.createElement('div');
    empty.className = 'a2ui-empty';
    empty.textContent = '（无界面载荷）';
    container.appendChild(empty);
    return;
  }

  var surface = null;
  try {
    var processor = createProcessor();
    processor.processMessages(payload.messages);
    // surfaceId 以载荷内 createSurface 声明为准（模型/协议的单一真相源）；
    // 事件外层的 surfaceId 是会话标识，可能与界面 surface 名不同（如 "recipe"）。
    var surfaceIds = payload.messages
        .filter(function (m) { return m && m.createSurface; })
        .map(function (m) { return m.createSurface.surfaceId; });
    var candidates = [payload.surfaceId].concat(surfaceIds).filter(Boolean);
    for (var i = 0; i < candidates.length && !surface; i++) {
      surface = processor.getSurface(candidates[i]);
    }
  } catch (e) {
    var err = document.createElement('div');
    err.className = 'a2ui-issues';
    err.textContent = '界面载荷处理失败：' + (e && e.message ? e.message : e);
    container.appendChild(err);
    return;
  }

  if (!surface) {
    var notFound = document.createElement('div');
    notFound.className = 'a2ui-empty';
    notFound.textContent = '（surface ' + payload.surfaceId + ' 尚未创建）';
    container.appendChild(notFound);
    return;
  }

  var rootId = findRootId(surface);
  if (rootId == null) {
    var noComp = document.createElement('div');
    noComp.className = 'a2ui-empty';
    noComp.textContent = '（surface 无组件）';
    container.appendChild(noComp);
    return;
  }
  container.appendChild(renderComponent(rootId, surface.componentsModel, surface));
}

// Vue 迁移预留：mountA2ui(target, payload) 可包装为 Vue 组件生命周期钩子
// （onMounted 时挂载 / watch payload 时重渲染），本文件不依赖任何框架。

// SDK 惯例：IIFE 导入后挂 window 全局；同时保留 ESM 导出供源码级引用。
if (typeof window !== 'undefined') {
  window.A2uiRenderer = window.A2uiRenderer || {};
  window.A2uiRenderer.mountA2ui = mountA2ui;
}
