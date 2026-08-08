/**
 * ============================================================================
 * FormFillDebug — mcp-formfill 通用调试面板（SDK 附加组件，默认不启用）
 * ============================================================================
 *
 * 归属：yunxi-agent-platform/agent-web-sdk（单一源，勿在产品中拷贝）
 * 引用方式：Vite alias `@web-sdk`（随 formfill-client.js 一并打包）
 *
 * 设计原则：
 *   - 纯通用、零业务字段。不依赖任何具体场景（recipe/safety/budget…）。
 *   - 默认不挂载。业务页面想看实时通道状态时主动调用：
 *       window.FormFillClient.getClient().createDebugPanel({ mount: true })
 *     或在 dev 环境根据 import.meta.env.DEV 自行启用。
 *   - 仅作为开发期可观测性工具，不进入生产数据流。
 *
 * 面板能力（全部基于 FormFillClient 已暴露的能力，不重复造轮子）：
 *   1. 连接状态指示（WS 已连 / 断线 / 未初始化）+ 手动重连
 *   2. 查看 mcp-formfill 已加载场景（走 /app/formfill 回写约定）
 *   3. 手动触发 get_structure：用 pageController.getBrowserState() 读真实
 *      页面结构，面板内以表格展示（index / name / label / tag）
 *   4. 手填一条 JSON 测试 fill：直接复用 fillForm 写入页面
 *
 * 注意：本面板不向页面注入任何业务行为，只调用底层 pageController 读取/
 * 写入（写入路径与正式通道完全一致：getBrowserState → index → inputText/
 * selectOption/clickElement），因此本身也是对"写路径正确"的回归验证工具。
 */

(function () {
  'use strict';

  var LOG = '[FormFillDebug]';

  function injectStyle() {
    if (document.getElementById('ff-debug-style')) return;
    var css = [
      '#ff-debug{position:fixed;top:64px;right:16px;z-index:2147483645;width:320px;',
      'font:12px/1.5 system-ui,-apple-system,sans-serif;color:#0f172a;',
      'background:#fff;border:1px solid #e2e8f0;border-radius:12px;',
      'box-shadow:0 8px 30px rgba(15,23,42,.18);overflow:hidden;user-select:none}',
      '#ff-debug header{display:flex;align-items:center;gap:8px;padding:10px 12px;',
      'background:linear-gradient(135deg,#39b6ff,#bd45fb);color:#fff;cursor:move}',
      '#ff-debug header b{flex:1;font-size:13px;font-weight:700}',
      '#ff-debug .dot{width:9px;height:9px;border-radius:50%;background:#cbd5e1;flex:none}',
      '#ff-debug .dot.on{background:#34d399}#ff-debug .dot.off{background:#ef4444}',
      '#ff-debug .body{padding:10px 12px;max-height:60vh;overflow:auto}',
      '#ff-debug button{font:12px system-ui;border:1px solid #cbd5e1;background:#f8fafc;',
      'border-radius:7px;padding:6px 9px;cursor:pointer;color:#0f172a}',
      '#ff-debug button:hover{background:#eef2f7}',
      '#ff-debug button.primary{background:#2563eb;color:#fff;border-color:#2563eb}',
      '#ff-debug .row{display:flex;gap:6px;flex-wrap:wrap;margin:6px 0}',
      '#ff-debug label{display:block;font-size:11px;color:#64748b;margin:8px 0 3px}',
      '#ff-debug select,#ff-debug textarea{width:100%;box-sizing:border-box;border:1px solid #e2e8f0;',
      'border-radius:7px;padding:6px;font:12px ui-monospace,monospace;color:#0f172a}',
      '#ff-debug textarea{height:84px;resize:vertical}',
      '#ff-debug table{width:100%;border-collapse:collapse;font-size:11px;margin-top:6px}',
      '#ff-debug th,#ff-debug td{border:1px solid #e2e8f0;padding:3px 5px;text-align:left;',
      'max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '#ff-debug .msg{margin-top:8px;font-size:11px;color:#475569;white-space:pre-wrap;word-break:break-word}',
      '#ff-debug.collapsed .body{display:none}'
    ].join('');
    var s = document.createElement('style');
    s.id = 'ff-debug-style';
    s.textContent = css;
    document.head.appendChild(s);
  }

  function el(tag, attrs, html) {
    var e = document.createElement(tag);
    if (attrs) for (var k in attrs) if (attrs.hasOwnProperty(k)) e[k] = attrs[k];
    if (html != null) e.innerHTML = html;
    return e;
  }

  function makeDraggable(win, handle) {
    var sx, sy, sl, st, moved, raf;
    handle.addEventListener('mousedown', onDown);
    function onDown(e) {
      moved = false; var pt = e;
      sx = pt.clientX; sy = pt.clientY;
      var r = win.getBoundingClientRect(); sl = r.left; st = r.top;
      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', onUp);
    }
    function onMove(e) {
      if (Math.abs(e.clientX - sx) > 3 || Math.abs(e.clientY - sy) > 3) moved = true;
      if (raf) cancelAnimationFrame(raf);
      raf = requestAnimationFrame(function () {
        win.style.left = Math.max(0, Math.min(window.innerWidth - 80, sl + e.clientX - sx)) + 'px';
        win.style.top = Math.max(0, Math.min(window.innerHeight - 40, st + e.clientY - sy)) + 'px';
        win.style.right = 'auto';
      });
    }
    function onUp() {
      if (raf) { cancelAnimationFrame(raf); raf = null; }
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    }
  }

  /**
   * 在 FormFillClient 原型上挂 createDebugPanel。
   * 幂等：同一 client 实例只创建一个面板；重复调用仅切换显示。
   */
  function attach(clientProto) {
    clientProto.createDebugPanel = function (opts) {
      opts = opts || {};
      if (this._debugWin) {
        this._debugWin.style.display = 'block';
        return this._debugWin;
      }
      injectStyle();

      var self = this;
      var win = el('div', { id: 'ff-debug' });
      var dot = el('span', { className: 'dot' });
      var statusText = el('span', null, '未连接');
      var header = el('header', null, '');
      header.appendChild(dot);
      header.appendChild(el('b', null, 'FormFill 调试'));
      header.appendChild(statusText);
      var collapseBtn = el('button', { textContent: '—' });
      header.appendChild(collapseBtn);
      win.appendChild(header);

      var body = el('div', { className: 'body' });
      win.appendChild(body);

      // 连接控制
      var connRow = el('div', { className: 'row' });
      var reconnectBtn = el('button', { textContent: '重连通道' });
      var closeBtn = el('button', { textContent: '关闭' });
      connRow.appendChild(reconnectBtn);
      connRow.appendChild(closeBtn);
      body.appendChild(connRow);

      // 场景输入（可选：若后端支持 list 则拉取，否则只读自由输入）
      body.appendChild(el('label', null, '场景名（generic / recipe / safety…，用于字段解析）'));
      var sceneSel = el('input', { value: 'generic', placeholder: 'generic' });
      sceneSel.style.width = '100%';
      sceneSel.style.boxSizing = 'border-box';
      sceneSel.style.border = '1px solid #e2e8f0';
      sceneSel.style.borderRadius = '7px';
      sceneSel.style.padding = '6px';
      sceneSel.style.font = '12px system-ui';
      body.appendChild(sceneSel);

      // 手动 get_structure
      body.appendChild(el('label', null, '读取真实页面结构（get_structure）'));
      var structBtn = el('button', { className: 'primary', textContent: '读取页面结构' });
      body.appendChild(structBtn);
      var structBox = el('div');
      body.appendChild(structBox);

      // 手填测试 fill
      body.appendChild(el('label', null, '手填测试（JSON → 写入页面）'));
      var fillArea = el('textarea', {
        placeholder: '{\n  "crowd": "成人",\n  "calorie": 1500\n}'
      });
      body.appendChild(fillArea);
      var fillBtn = el('button', { className: 'primary', textContent: '写入页面' });
      body.appendChild(el('div', { className: 'row' }, '').appendChild(fillBtn).parentNode);

      var msgBox = el('div', { className: 'msg' });
      body.appendChild(msgBox);

      function note(m) { msgBox.textContent = m; }

      function refreshStatus() {
        if (self._connected) { dot.className = 'dot on'; statusText.textContent = '已连接 /ws/formfill'; }
        else { dot.className = 'dot off'; statusText.textContent = '未连接'; }
      }
      // 挂钩 client 日志，顺带刷新状态
      var prevLog = self._onLog;
      self.onLog(function (m) {
        if (prevLog) prevLog(m);
        if (m && m.indexOf('已连接') === 0) refreshStatus();
        if (m && m.indexOf('已断开') === 0) refreshStatus();
      });
      refreshStatus();

      // 拉取场景列表（复用 FormFillerService.listScenes 的回写约定）
      reconnectBtn.addEventListener('click', function () {
        note('正在重连…');
        self.connect({ wsPath: '/ws/formfill' }).then(refreshStatus).catch(function (e) { note('重连失败: ' + e.message); });
      });
      closeBtn.addEventListener('click', function () { win.style.display = 'none'; });
      collapseBtn.addEventListener('click', function () { win.classList.toggle('collapsed'); });

      structBtn.addEventListener('click', function () {
        note('读取中（并回写后端）…');
        self.reportStructure(sceneSel.value.trim() || 'generic', { debug: true })
          .then(function (list) {
            if (!list || !list.length) { structBox.innerHTML = '<p class="msg">页面无交互元素</p>'; return; }
            var rows = list.map(function (f) {
              return '<tr><td>' + (f.index != null ? f.index : '-') + '</td><td>' +
                (f.name || '-') + '</td><td>' + (f.label || '-') + '</td><td>' +
                (f.type || '-') + '</td></tr>';
            }).join('');
            structBox.innerHTML = '<table><thead><tr><th>#</th><th>name</th><th>label</th><th>tag</th></tr></thead><tbody>' +
              rows + '</tbody></table>';
            note('已读取 ' + list.length + ' 个可交互元素，并已回写后端注册');
          }).catch(function (e) { note('读取失败: ' + e.message); });
      });

      fillBtn.addEventListener('click', function () {
        var raw = fillArea.value.trim();
        if (!raw) { note('请先输入 JSON'); return; }
        var data;
        try { data = JSON.parse(raw); } catch (e) { note('JSON 解析失败: ' + e.message); return; }
        var scene = sceneSel.value.trim() || 'generic';
        self.fillForm(data, { scene: scene })
          .then(function () { note('已写入页面 ' + Object.keys(data).length + ' 个字段'); })
          .catch(function (e) { note('写入失败: ' + e.message); });
      });

      makeDraggable(win, header);
      document.body.appendChild(win);
      this._debugWin = win;
      note('调试面板已就绪。连接状态/读结构/手填均基于底层 pageController。');
      return win;
    };
  }

  // 在 formfill-client.js 加载后挂载（同一 IIFE 全局）
  function bind() {
    var root = window.FormFillClient;
    if (!root || !root.FormFillClient) {
      console.warn(LOG, 'FormFillClient 尚未就绪，调试面板未挂载');
      return;
    }
    attach(root.FormFillClient.prototype);
    // 默认不挂载。仅当 URL 含 ?formfill-debug 时自动显示，供开发者调试。
    // 业务页面也可显式调用 client.createDebugPanel() 启用，无需任何耦合代码。
    try {
      if (new URLSearchParams(window.location.search).has('formfill-debug')) {
        root.getClient().createDebugPanel({ mount: true });
      }
    } catch (_) {}
  }

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    setTimeout(bind, 0);
  } else {
    window.addEventListener('DOMContentLoaded', function () { setTimeout(bind, 0); });
  }

})();
