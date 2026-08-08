/**
 * ============================================================================
 * PageAgentDomEngine v1 — 纯 DOM 执行引擎
 * ============================================================================
 *
 * 归属：yunxi-agent-platform/agent-web-sdk（单一源，勿在产品中拷贝）
 *
 * 剥离自 page-agent SDK，仅保留 DOM 读写能力，不含 LLM 决策循环。
 * 为 mcp-formfill 桥接层提供精准的页面交互执行。
 *
 * 核心能力：
 *   1. scan()          — 扫描页面所有交互元素，返回结构化列表（含 index）
 *   2. fill(name,val)  — 按字段名填充（兼容 React/Vue 响应式）
 *   3. batchFill(obj)  — 批量填充 { fieldName: value, ... }
 *   4. click(name)     — 按字段名点击
 *   5. highlight(name) — 视觉高亮反馈
 *
 * 设计原则：
 *   - 纯 vanilla JS，零外部依赖
 *   - 优先使用页面真实 DOM，不依赖后端建模
 *   - 写入操作触发原生 + synthetic 事件（React / Vue / Svelte 均兼容）
 *   - 加载后挂载 window.PageAgentDomEngine
 */

(function () {
  'use strict';

  var LOG = '[PageAgentDomEngine]';
  var HIGHLIGHT_CLASS = 'padom-highlight';
  var HIGHLIGHT_DURATION = 1200;
  var SCAN_CACHE_MS = 300;

  var _scanned = null;
  var _scanTS = 0;
  var _stylesInjected = false;
  var _sentinel = {}; // 哨兵值，避免与合法值混淆

  // ── 样式注入 ────────────────────────────────────────────────────────────

  function injectStyles() {
    if (_stylesInjected) return;
    var s = document.createElement('style');
    s.id = 'padom-styles';
    s.textContent = [
      '.padom-highlight {',
      '  outline: 3px solid #39b6ff !important;',
      '  outline-offset: 3px !important;',
      '  border-radius: 4px !important;',
      '  box-shadow: 0 0 20px rgba(57,182,255,0.55) !important;',
      '  transition: outline 0.18s ease, box-shadow 0.18s ease !important;',
      '  z-index: 2147483647 !important;',
      '  position: relative !important;',
      '}',
    ].join('\n');
    document.head.appendChild(s);
    _stylesInjected = true;
  }

  // ── React / Vue 兼容的值写入 ────────────────────────────────────────────

  function useNativeSetter(el, value) {
    var tag = (el.tagName || '').toLowerCase();
    var proto;

    if (tag === 'input')      proto = window.HTMLInputElement.prototype;
    else if (tag === 'textarea') proto = window.HTMLTextAreaElement.prototype;
    else if (tag === 'select')   proto = window.HTMLSelectElement.prototype;
    else { el.value = value; return; }

    try {
      var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
      if (descriptor && descriptor.set) {
        descriptor.set.call(el, value);
      } else {
        el.value = value;
      }
    } catch (e) {
      el.value = value;
    }

    // 清除 React 16 的 _valueTracker（否则 React 会回退旧值）
    if (el._valueTracker) {
      try { el._valueTracker.setValue(''); } catch (_) {}
    }
  }

  function dispatchInputEvents(el) {
    el.dispatchEvent(new Event('input',    { bubbles: true, cancelable: true }));
    el.dispatchEvent(new Event('change',   { bubbles: true, cancelable: true }));
    el.dispatchEvent(new Event('blur',     { bubbles: true, cancelable: true }));
    // focus → blur 组合确保所有框架的 validation/touched 状态都触发
    el.focus();
    el.blur();
  }

  // ── 可见性检查 ──────────────────────────────────────────────────────────

  function isVisible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var style = window.getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') return false;
    if (parseFloat(style.opacity) === 0) return false;
    var rect = el.getBoundingClientRect();
    if (rect.width < 2 && rect.height < 2) return false;

    // 检查祖先链是否隐藏
    var p = el.parentElement;
    while (p) {
      var ps = window.getComputedStyle(p);
      if (ps.display === 'none' || ps.visibility === 'hidden') return false;
      if (p === document.documentElement) break;
      p = p.parentElement;
    }
    return true;
  }

  // ── 元素标签提取 ────────────────────────────────────────────────────────

  function getLabel(el) {
    var aria = el.getAttribute('aria-label');
    if (aria) return aria.trim();

    if (el.id) {
      var lbl = document.querySelector('label[for="' + _cssEscape(el.id) + '"]');
      if (lbl) { var t = lbl.textContent.trim(); if (t) return t; }
    }

    var w = el.closest('label');
    if (w) { var t = w.textContent.trim(); if (t) return t; }

    var prev = el.previousElementSibling;
    if (prev && prev.tagName === 'LABEL') { var t = prev.textContent.trim(); if (t) return t; }

    var ph = el.getAttribute('placeholder');
    if (ph) return ph;

    var nm = el.getAttribute('name');
    if (nm) return nm;

    return el.id || '';
  }

  // ── 元素类型分类 ────────────────────────────────────────────────────────

  function getType(el) {
    var tag = (el.tagName || '').toLowerCase();
    var t = (el.getAttribute('type') || '').toLowerCase();

    if (tag === 'select')        return 'select';
    if (tag === 'textarea')      return 'textarea';
    if (tag === 'input') {
      if (t === 'checkbox')      return 'checkbox';
      if (t === 'radio')         return 'radio';
      if (t === 'number' || t === 'range') return 'number';
      return 'text';
    }
    if (tag === 'button')        return 'button';
    if (tag === 'a' && el.getAttribute('href')) return 'link';
    return 'element';
  }

  // ── 当前值 ──────────────────────────────────────────────────────────────

  function getValue(el) {
    var tag = (el.tagName || '').toLowerCase();
    var t = (el.getAttribute('type') || '').toLowerCase();

    if (tag === 'select')                return el.value || '';
    if (tag === 'input' && t === 'checkbox') return el.checked;
    if (tag === 'input' || tag === 'textarea') return el.value || '';
    return '';
  }

  // ── 下拉框选项 ──────────────────────────────────────────────────────────

  function getOptions(el) {
    if ((el.tagName || '').toLowerCase() !== 'select') return [];
    var opts = [];
    for (var i = 0; i < el.options.length; i++) {
      var o = el.options[i];
      opts.push({ value: o.value, text: (o.textContent || '').trim() });
    }
    return opts;
  }

  // ── 交互元素判断 ────────────────────────────────────────────────────────

  function isInteractive(el) {
    if (!el || !el.tagName) return false;
    if (el.disabled || el.readOnly) return false;
    if (el.getAttribute('aria-hidden') === 'true') return false;

    var tag = el.tagName.toLowerCase();
    var t = (el.getAttribute('type') || '').toLowerCase();

    if (t === 'hidden' || t === 'submit' || t === 'button' || t === 'image' || t === 'reset')
      return false;

    if (tag === 'input' || tag === 'select' || tag === 'textarea') return true;
    if (tag === 'button') return true;
    if (tag === 'a' && el.getAttribute('href') && el.getAttribute('href') !== '#') return true;

    return false;
  }

  // ── CSS.escape 兼容（Safari < 16 无此 API）──────────────────────────────

  function _cssEscape(str) {
    try { return CSS.escape(str); } catch (_) {}
    return str.replace(/[^\w-]/g, '\\$&');
  }

  // ── 页面扫描 ────────────────────────────────────────────────────────────

  function scanPage(force) {
    if (!force && _scanned && (Date.now() - _scanTS < SCAN_CACHE_MS)) {
      return _scanned;
    }

    // 优先：尝试使用已加载的 page-agent SDK 的 getBrowserState（更成熟的元素检测）
    var sdkElements = null;
    try {
      var externalAgent = window.__PAGE_AGENT__;
      if (externalAgent && externalAgent.pageController &&
          typeof externalAgent.pageController.getBrowserState === 'function') {
        sdkElements = externalAgent.pageController.getBrowserState();
      }
    } catch (_) {}

    if (sdkElements && Array.isArray(sdkElements)) {
      // SDK 返回 { highlightIndex, tagName, type, name, id, description, text, options? }
      _scanned = sdkElements.map(function (el) {
        return {
          index:     el.highlightIndex,
          tag:       el.tagName || '',
          type:      el.type || getTypeByRaw(el.tagName, el.type),
          name:      el.name || '',
          id:        el.id || '',
          label:     el.description || el.text || el.name || '',
          value:     getValueFromRaw(el),
          options:   el.options || [],
          el:        _sentinel, // 不持有 DOM 引用，后续通过 index 去 pageController 操作
        };
      });
      _scanTS = Date.now();
      return _scanned;
    }

    // 兜底：内置 DOM 扫描器
    var list = [];
    var seen = new WeakSet();

    // 主查询：form 内的交互元素 + contenteditable
    var qs = 'input:not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="image"]):not([type="reset"]),' +
             'select, textarea, [contenteditable="true"]';
    var formElements = document.querySelectorAll(qs);

    formElements.forEach(function (el) {
      if (seen.has(el)) return;
      if (!isVisible(el)) return;
      if (!isInteractive(el)) return;
      seen.add(el);
      list.push({
        index:       list.length,
        tag:         el.tagName.toLowerCase(),
        type:        getType(el),
        name:        el.getAttribute('name') || '',
        id:          el.id || '',
        label:       getLabel(el),
        placeholder: el.getAttribute('placeholder') || '',
        value:       getValue(el),
        options:     getOptions(el),
        el:          el,
      });
    });

    _scanned = list;
    _scanTS = Date.now();
    return list;
  }

  function getTypeByRaw(tagName, rawType) {
    var tag = (tagName || '').toLowerCase();
    var t = (rawType || '').toLowerCase();
    if (tag === 'select') return 'select';
    if (tag === 'textarea') return 'textarea';
    if (t === 'checkbox') return 'checkbox';
    if (t === 'radio') return 'radio';
    if (t === 'number' || t === 'range') return 'number';
    return 'text';
  }

  function getValueFromRaw(el) {
    var t = (el.type || '').toLowerCase();
    if (t === 'checkbox' || t === 'radio') return el.checked;
    return el.value || '';
  }

  // ── 视觉高亮 ────────────────────────────────────────────────────────────

  function highlight(el, duration) {
    if (!el) return;
    duration = duration || HIGHLIGHT_DURATION;
    el.classList.add(HIGHLIGHT_CLASS);
    // 滚动元素入视口
    try { el.scrollIntoView({ behavior: 'smooth', block: 'center' }); } catch (_) {}
    setTimeout(function () {
      el.classList.remove(HIGHLIGHT_CLASS);
    }, duration);
  }

  // ── 元素查找 ────────────────────────────────────────────────────────────

  function findField(name) {
    if (name == null) return null;

    // 1) name 属性精确匹配
    var el = document.querySelector('[name="' + _cssEscape(String(name)) + '"]');
    if (el && isVisible(el)) return el;

    // 2) id 精确匹配
    el = document.getElementById(String(name));
    if (el && isVisible(el)) return el;

    // 3) label 文本匹配（关联 for 或内含元素）
    var labels = document.querySelectorAll('label');
    for (var li = 0; li < labels.length; li++) {
      var text = (labels[li].textContent || '').trim().toLowerCase();
      if (text === String(name).toLowerCase()) {
        var fid = labels[li].getAttribute('for');
        if (fid) {
          var t = document.getElementById(fid);
          if (t && isVisible(t) && isInteractive(t)) return t;
        }
        var w = labels[li].querySelector('input, select, textarea');
        if (w && isVisible(w) && isInteractive(w)) return w;
      }
    }

    // 4) label 模糊匹配
    for (var li = 0; li < labels.length; li++) {
      var text = (labels[li].textContent || '').trim().toLowerCase();
      if (text.indexOf(String(name).toLowerCase()) >= 0) {
        var fid = labels[li].getAttribute('for');
        if (fid) {
          var t = document.getElementById(fid);
          if (t && isVisible(t) && isInteractive(t)) return t;
        }
      }
    }

    // 5) placeholder 匹配
    var all = document.querySelectorAll('input, select, textarea');
    for (var i = 0; i < all.length; i++) {
      if (isVisible(all[i]) && isInteractive(all[i]) &&
          (all[i].getAttribute('placeholder') || '').toLowerCase().indexOf(String(name).toLowerCase()) >= 0) {
        return all[i];
      }
    }

    return null;
  }

  function findByDescriptor(field) {
    // field: { index, name, id, label }
    var list = _scanned || scanPage(false);
    if (field.index != null && field.index < list.length && list[field.index].el !== _sentinel) {
      return list[field.index].el;
    }
    return findField(field.name || field.id || field.label);
  }

  // ── 单字段填充 ──────────────────────────────────────────────────────────

  function fillField(name, value) {
    var el = findField(name);
    if (!el) {
      return { success: false, message: '未找到元素: ' + name };
    }

    try {
      var tag = (el.tagName || '').toLowerCase();
      var type = (el.getAttribute('type') || '').toLowerCase();

      if (tag === 'select') {
        fillSelect(el, value);

      } else if (type === 'checkbox') {
        fillCheckbox(el, value);

      } else if (type === 'radio') {
        fillRadio(el, value);

      } else {
        // text, number, textarea, date, time, etc.
        useNativeSetter(el, String(value));
        dispatchInputEvents(el);
      }

      highlight(el);
      return { success: true, message: 'ok' };

    } catch (e) {
      return { success: false, message: e.message };
    }
  }

  function fillSelect(el, value) {
    var sVal = String(value);
    // 精确匹配 value 或 text
    for (var i = 0; i < el.options.length; i++) {
      if (el.options[i].value === sVal ||
          el.options[i].textContent.trim() === sVal) {
        el.value = el.options[i].value;
        dispatchInputEvents(el);
        return;
      }
    }
    // 模糊匹配（部分包含）
    for (var i = 0; i < el.options.length; i++) {
      if (el.options[i].value.indexOf(sVal) >= 0 ||
          el.options[i].textContent.trim().indexOf(sVal) >= 0) {
        el.value = el.options[i].value;
        dispatchInputEvents(el);
        return;
      }
    }
    throw new Error('下拉框无匹配选项: ' + sVal);
  }

  function fillCheckbox(el, value) {
    var shouldCheck = false;
    if (typeof value === 'boolean') {
      shouldCheck = value;
    } else if (typeof value === 'string') {
      shouldCheck = value === 'true' || value === '1' || value === 'on';
    } else if (typeof value === 'number') {
      shouldCheck = value !== 0;
    }
    el.checked = shouldCheck;
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.dispatchEvent(new Event('input',  { bubbles: true }));
  }

  function fillRadio(el, value) {
    el.checked = true;
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  // ── Checkbox 组填充 ─────────────────────────────────────────────────────

  /**
   * 处理同一 name 的 checkbox 组（如 allergy: ["海鲜","坚果"]）。
   * 先将该组全部取消，再勾选值列表中的项。
   */
  function fillCheckboxGroup(name, values) {
    var selector = 'input[type="checkbox"][name="' + _cssEscape(name) + '"]';
    var checkboxes = document.querySelectorAll(selector);

    if (!checkboxes.length) return { success: false, message: '未找到复选框组: ' + name };

    // 构建查找集合
    var valueSet = {};
    if (Array.isArray(values)) {
      values.forEach(function (v) { valueSet[String(v)] = true; });
    } else {
      valueSet[String(values)] = true;
    }

    // 设置选中状态
    checkboxes.forEach(function (cb) {
      cb.checked = !!valueSet[cb.value];
      cb.dispatchEvent(new Event('change', { bubbles: true }));
    });

    if (checkboxes.length > 0) highlight(checkboxes[0]);
    return { success: true, message: 'ok' };
  }

  // ── 批量填充 ────────────────────────────────────────────────────────────

  function batchFill(data) {
    if (!data) return {};

    var results = {};
    var names = Object.keys(data);

    for (var i = 0; i < names.length; i++) {
      var rawKey = names[i];
      var value = data[rawKey];

      // 检测是否为 checkbox 组（值为数组）
      var el = findField(rawKey);
      if (el && (el.getAttribute('type') || '').toLowerCase() === 'checkbox' && Array.isArray(value)) {
        results[rawKey] = fillCheckboxGroup(rawKey, value);
      }
      // 检测是否为同名的多个 checkbox（即使值不是数组，也应该逐个匹配）
      else if (el && el.type === 'checkbox') {
        results[rawKey] = fillField(rawKey, value);
      }
      // 检测是否为一组 checkbox（有多个同 name 元素）
      else if (Array.isArray(value)) {
        var group = document.querySelectorAll('[name="' + _cssEscape(rawKey) + '"]');
        if (group.length > 1 && group[0].type === 'checkbox') {
          results[rawKey] = fillCheckboxGroup(rawKey, value);
        } else {
          // 数组但非 checkbox 组 → 取第一个值填单字段
          var v = (value.length > 0) ? value[0] : '';
          results[rawKey] = fillField(rawKey, v);
        }
      }
      // 普通字段
      else {
        results[rawKey] = fillField(rawKey, value);
      }
    }

    return results;
  }

  // ── 点击 ────────────────────────────────────────────────────────────────

  function clickField(name) {
    var el = findField(name);
    if (!el) return { success: false, message: '未找到元素: ' + name };

    try {
      el.focus();
      el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
      el.dispatchEvent(new MouseEvent('mouseup',   { bubbles: true }));
      el.dispatchEvent(new MouseEvent('click',     { bubbles: true, cancelable: true }));
      highlight(el, 800);
      return { success: true, message: 'ok' };
    } catch (e) {
      return { success: false, message: e.message };
    }
  }

  // ── 公开 API ────────────────────────────────────────────────────────────

  var ENGINE = {

    /**
     * 扫描页面所有交互元素。
     * @param {boolean} force 是否跳过缓存强制重扫
     * @returns {Array<{index, tag, type, name, id, label, placeholder, value, options}>}
     */
    scan: function (force) {
      injectStyles();
      var list = scanPage(force);
      return list.map(function (item) {
        return {
          index:       item.index,
          tag:         item.tag,
          type:        item.type,
          name:        item.name,
          id:          item.id,
          label:       item.label,
          placeholder: item.placeholder || '',
          value:       item.value,
          options:     item.options || [],
        };
      });
    },

    /**
     * 按字段名填充。
     * @param {string} name 字段 name / id / label
     * @param {string} value 值
     */
    fill: function (name, value) {
      return fillField(name, value);
    },

    /**
     * 批量填充 { fieldName: value, ... }。
     * 自动处理 checkbox 组（值为数组时勾选指定选项）。
     * @returns {{[fieldName]: {success, message}}}
     */
    batchFill: function (data) {
      return batchFill(data);
    },

    /**
     * 按字段名点击。
     */
    click: function (name) {
      return clickField(name);
    },

    /**
     * 高亮元素。
     */
    highlight: function (name, duration) {
      var el = findField(name);
      if (el) highlight(el, duration);
    },

    /**
     * 清除扫描缓存（DOM 变化后调用）。
     */
    refresh: function () {
      _scanned = null;
      _scanTS = 0;
    },
  };

  // ── 自动初始化 ──────────────────────────────────────────────────────────

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    injectStyles();
  } else {
    document.addEventListener('DOMContentLoaded', injectStyles);
  }

  window.PageAgentDomEngine = ENGINE;

})();
