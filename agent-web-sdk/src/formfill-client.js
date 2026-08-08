/**
 * ============================================================================
 * FormFillClient v2 — mcp-formfill (STOMP/WebSocket) → PageAgentDomEngine 桥接层
 * ============================================================================
 *
 * 归属：yunxi-agent-platform/agent-web-sdk（单一源，勿在产品中拷贝）
 *
 * 职责：
 *   1. 连接 mcp-formfill 的 STOMP/WebSocket（端点 /ws/formfill）
 *   2. 订阅 /topic/formfill 接收 AgentScope 后端填表指令
 *   3. 把指令翻译成对 PageAgentDomEngine 的调用 —— DOM 读写唯一真相源
 *   4. 读取真实页面结构回写后端（闭环自举）
 *
 * 底层执行引擎：window.PageAgentDomEngine
 *   scan()           → 扫描所有交互元素
 *   fill(name, val)  → 单字段填充（兼容 React/Vue）
 *   batchFill(obj)   → 批量填充 { fieldName: value }
 *   click(name)      → 点击
 *   refresh()        → 清除扫描缓存
 *
 * 协议（mcp-formfill WebSocket）：
 *   端点：  ws://<host>/ws/formfill
 *   订阅：  /topic/formfill
 *   回写：  /app/formfill
 *
 * FormFillMessage 结构：
 *   { type, scene, formData: { field: value }, params: { requestId, ... }, result }
 */

(function () {
  'use strict';

  var LOG = '[FormFillClient]';

  var INSTANCE = null;

  function FormFillClient() {
    this._stomp = null;
    this._subscriptions = [];
    this._connected = false;
    this._seen = {};
    this._onLog = null;
    this._pendingConnects = [];
    this._retryCount = 0;
    this._maxRetries = 3;
  }

  FormFillClient.prototype._log = function (msg) {
    console.log(LOG, msg);
    if (typeof this._onLog === 'function') this._onLog(msg);
  };
  FormFillClient.prototype._warn = function (msg) {
    console.warn(LOG, msg);
    if (typeof this._onLog === 'function') this._onLog('⚠ ' + msg);
  };
  FormFillClient.prototype.onLog = function (cb) { this._onLog = cb; return this; };

  // ── 连接 ─────────────────────────────────────────────────────────────────

  /**
   * 连接 mcp-formfill。
   * @param {Object} opts
   *   wsPath        默认 '/ws/formfill'
   *   brokerURL     完整 ws:// 地址；默认由 location 推断
   *   reconnectDelay 默认 5000ms
   *   autoReport    是否连接后自动上报页面结构，默认 true
   *   autoScene     自动上报的场景名，默认 'generic'
   */
  FormFillClient.prototype.connect = function (opts) {
    opts = opts || {};
    var self = this;

    if (this._connected) { this._log('已连接，忽略重复 connect'); return Promise.resolve(); }

    // 懒加载 STOMP
    return _ensureStomp().then(function (Stomp) {
      return new Promise(function (resolve, reject) {
        var proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        var brokerURL = opts.brokerURL ||
          (proto + '//' + window.location.host + (opts.wsPath || '/ws/formfill'));

        self._log('正在连接 ' + brokerURL);

        if (Stomp.Client) {
          // @stomp/stompjs v7
          self._stomp = new Stomp.Client({
            brokerURL: brokerURL,
            reconnectDelay: opts.reconnectDelay || 5000,
            onConnect: function () {
              self._onConnect(opts);
              resolve();
            },
            onStompError: function (f) {
              var err = f.body || (f.headers && f.headers.message) || 'STOMP 错误';
              self._warn(err);
              reject(new Error(err));
            },
          });
          self._stomp.activate();
        } else {
          // 兼容旧版 stomp.js
          self._stomp = Stomp.client(brokerURL);
          self._stomp.reconnect_delay_in_ms = opts.reconnectDelay || 5000;
          self._stomp.connect({},
            function () { self._onConnect(opts); resolve(); },
            function (f) { reject(new Error(f.body || '连接失败')); }
          );
        }
      });
    }).catch(function (e) {
      self._warn('连接失败: ' + e.message);
      throw e;
    });
  };

  FormFillClient.prototype._onConnect = function (opts) {
    this._connected = true;
    this._log('已连接 /topic/formfill');
    var self = this;

    // 订阅消息
    try {
      var sub = this._stomp.subscribe('/topic/formfill', function (frame) {
        self._onFrame(frame);
      });
      if (sub) this._subscriptions.push(sub);
    } catch (e) {
      this._warn('订阅失败: ' + e.message);
    }

    // 自动上报页面结构
    if (opts.autoReport !== false) {
      this.reportStructure(opts.autoScene || 'generic', { bootstrap: true })
        .catch(function () {});
    }
  };

  FormFillClient.prototype._onFrame = function (frame) {
    var msg;
    try { msg = JSON.parse(frame.body); }
    catch (e) { this._warn('消息体非 JSON，忽略'); return; }

    // 幂等去重
    if (msg.params && msg.params.requestId) {
      if (this._seen[msg.params.requestId]) {
        this._log('跳过重复 requestId=' + msg.params.requestId);
        return;
      }
      this._seen[msg.params.requestId] = true;
    }

    switch (msg.type) {
      case 'get_structure': this._handleGetStructure(msg); break;
      case 'fill':          this._handleFill(msg);          break;
      case 'batch_fill':    this._handleBatchFill(msg);     break;
      case 'result':        this._log('收到 result: ' + JSON.stringify(msg.result)); break;
      default:             this._warn('未知 type=' + msg.type + '，忽略');
    }
  };

  // ── 页面结构读取 ─────────────────────────────────────────────────────────

  FormFillClient.prototype._handleGetStructure = function (msg) {
    var self = this;
    this._readPageStructure()
      .then(function (fields) {
        self._sendBack({
          type: 'get_structure',
          scene: msg.scene,
          formData: { fields: fields },
          params: msg.params || {},
          result: { success: true, message: 'structure fields=' + fields.length },
        });
        self._log('已回写页面结构，字段数=' + fields.length);
      })
      .catch(function (e) {
        self._warn('读取页面结构失败: ' + e.message);
        self._sendBack({
          type: 'get_structure',
          scene: msg.scene,
          formData: { fields: [] },
          params: msg.params || {},
          result: { success: false, message: e.message },
        });
      });
  };

  FormFillClient.prototype._readPageStructure = function () {
    var engine = window.PageAgentDomEngine;
    if (!engine || typeof engine.scan !== 'function') {
      return Promise.reject(new Error('PageAgentDomEngine 未加载'));
    }
    try {
      return Promise.resolve(engine.scan(true));
    } catch (e) {
      return Promise.reject(e);
    }
  };

  /**
   * 公开：读取真实页面结构并回写后端。
   * 业务页面可在初始化、SPA 路由切换后调用。
   * @returns {Promise<Array>} 字段清单
   */
  FormFillClient.prototype.reportStructure = function (scene, params) {
    var self = this;
    return this._readPageStructure().then(function (fields) {
      self._sendBack({
        type: 'get_structure',
        scene: scene || 'generic',
        formData: { fields: fields },
        params: params || {},
        result: { success: true, message: 'structure fields=' + fields.length },
      });
      self._log('已上报页面结构，字段数=' + fields.length);
      return fields;
    });
  };

  // ── 填表处理 ─────────────────────────────────────────────────────────────

  FormFillClient.prototype._handleFill = function (msg) {
    var self = this;
    this._doFill(msg.formData, msg.params)
      .then(function (results) {
        self._sendBack({
          type: 'fill',
          scene: msg.scene,
          formData: msg.formData,
          params: msg.params || {},
          result: { success: true, message: 'filled', results: results },
        });
      })
      .catch(function (e) {
        self._warn('填表失败: ' + e.message);
        self._sendBack({
          type: 'fill',
          scene: msg.scene,
          formData: msg.formData,
          params: msg.params || {},
          result: { success: false, message: e.message },
        });
      });
  };

  FormFillClient.prototype._handleBatchFill = function (msg) {
    var self = this;
    var list = (msg.formData && msg.formData.items) || [];
    var chain = Promise.resolve();
    var allResults = [];
    list.forEach(function (item) {
      chain = chain.then(function () {
        return self._doFill(item.formData, item.params).then(function (r) {
          allResults.push(r);
        });
      });
    });
    chain.then(function () {
      self._sendBack({
        type: 'batch_fill',
        scene: msg.scene,
        formData: msg.formData,
        params: msg.params || {},
        result: { success: true, message: 'batch filled', items: allResults },
      });
      self._log('批量填表完成，条数=' + list.length);
    }).catch(function (e) {
      self._warn('批量填表失败: ' + e.message);
    });
  };

  /**
   * 执行填表：遍历 formData 逐个字段填入，失败字段自动重试。
   */
  FormFillClient.prototype._doFill = function (formData, params) {
    if (!formData) return Promise.resolve({});

    var engine = window.PageAgentDomEngine;
    if (!engine || typeof engine.batchFill !== 'function') {
      return Promise.reject(new Error('PageAgentDomEngine 未加载'));
    }

    engine.refresh();

    var results = engine.batchFill(formData);

    // 检测失败字段
    var failures = [];
    for (var key in results) {
      if (results.hasOwnProperty(key) && !results[key].success) {
        failures.push(key);
      }
    }

    if (failures.length > 0 && this._retryCount < this._maxRetries) {
      this._retryCount++;
      this._warn('填表重试 ' + this._retryCount + '/' + this._maxRetries +
                 ' (失败字段: ' + failures.join(', ') + ')');

      return new Promise(function (resolve) {
        setTimeout(function () {
          engine.refresh();
          var retryData = {};
          failures.forEach(function (k) { retryData[k] = formData[k]; });
          var retryResults = engine.batchFill(retryData);
          // 合并结果
          for (var k in retryResults) {
            if (retryResults.hasOwnProperty(k)) results[k] = retryResults[k];
          }
          resolve(results);
        }, 400);
      });
    }

    this._retryCount = 0;
    return Promise.resolve(results);
  };

  /** 公开：把数据写入页面表单。 */
  FormFillClient.prototype.fillForm = function (data, params) {
    return this._doFill(data, params || {});
  };

  // ── 消息回写 ─────────────────────────────────────────────────────────────

  FormFillClient.prototype._sendBack = function (msg) {
    if (!this._stomp || !this._connected) {
      this._warn('未连接，无法回写');
      return;
    }
    try {
      if (this._stomp.publish) {
        this._stomp.publish({ destination: '/app/formfill', body: JSON.stringify(msg) });
      } else {
        this._stomp.send('/app/formfill', {}, JSON.stringify(msg));
      }
    } catch (e) {
      this._warn('回写失败: ' + e.message);
    }
  };

  // ── 断开 ─────────────────────────────────────────────────────────────────

  FormFillClient.prototype.disconnect = function () {
    if (this._stomp) {
      // 取消所有订阅
      this._subscriptions.forEach(function (sub) {
        try { if (sub.unsubscribe) sub.unsubscribe(); } catch (_) {}
      });
      this._subscriptions = [];
      // 断开连接
      try {
        if (this._stomp.deactivate) this._stomp.deactivate();
        else if (this._stomp.disconnect) this._stomp.disconnect();
      } catch (_) {}
    }
    this._stomp = null;
    this._connected = false;
    this._log('已断开');
  };

  // ── 已连接检测 ───────────────────────────────────────────────────────────

  FormFillClient.prototype.isConnected = function () {
    return this._connected;
  };

  // ── STOMP 懒加载 ─────────────────────────────────────────────────────────

  // @stomp/stompjs v7 UMD 全局名是 StompJs（非 Stomp），旧版 stomp.js 才是 Stomp。
  function _resolveStompGlobal() {
    return window.StompJs || window.Stomp || null;
  }

  function _ensureStomp() {
    var exist = _resolveStompGlobal();
    if (exist) return Promise.resolve(exist);
    return new Promise(function (ok, fail) {
      var s = document.createElement('script');
      s.src = 'https://cdn.jsdelivr.net/npm/@stomp/stompjs@7.0.0/bundles/stomp.umd.min.js';
      s.onload = function () {
        var g = _resolveStompGlobal();
        if (g) ok(g); else fail(new Error('STOMP 已加载但全局变量 StompJs/Stomp 均未找到'));
      };
      s.onerror = function () { fail(new Error('STOMP CDN 加载失败')); };
      document.head.appendChild(s);
    });
  }

  // ── 单例 ─────────────────────────────────────────────────────────────────

  function getClient() {
    if (!INSTANCE) INSTANCE = new FormFillClient();
    return INSTANCE;
  }

  window.FormFillClient = { getClient: getClient, FormFillClient: FormFillClient };

})();
