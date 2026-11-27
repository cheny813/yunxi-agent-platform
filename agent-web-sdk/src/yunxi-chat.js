/**
 * ============================================================================
 * yunxi-chat.js — 云曦平台前端对话 SDK（单一源，勿在产品中拷贝）
 * ============================================================================
 *
 * 对齐 yunxi 后端 /api/conversations 契约（AgentScope-Java agent-event SSE）：
 *   - 同步：POST /api/conversations
 *   - 流式：POST /api/conversations/stream
 *   请求体（ConversationRequest）：{ protocol, agentId, text, sessionId, userId, context }
 *   流式下发的每个 SSE 事件形如：
 *        event:agent-event
 *        data:{"type":"TEXT_BLOCK_DELTA","replyId":"...","blockId":"...","delta":"...",...}
 *
 * 流式渲染：仅 TEXT_BLOCK_DELTA 的 delta 进入正文（full）；THINKING 与工具中间结果
 * 不污染面向用户的正文。
 *
 * A2UI 界面载荷：后端在流末下发 `a2ui-message` 命名事件（载荷 { surfaceId, messages, issues }，
 * 已在后端经协议校验），SDK 分发给 onA2ui 回调；正文中 `<a2ui-json>` 哨兵块为协议传输
 * 载体，SDK 统一剥离（展示细节剥离在此唯一实现，前端不重复解析协议）。
 */

(function (global) {
  'use strict';

  var SYNC_URL = '/api/conversations';
  var STREAM_URL = '/api/conversations/stream';

  // 跨请求复用的会话 id（autoManageConversation 时维持同一会话，支持多轮）
  var _sessionId = null;

  function _uuid() {
    if (global.crypto && typeof global.crypto.randomUUID === 'function') {
      return global.crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = (Math.random() * 16) | 0;
      var v = c === 'x' ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }

  // 构造会话请求体（ConversationRequest 契约）
  function _buildBody(opts, sessionId) {
    return {
      protocol: 'web',
      agentId: opts.agentId,
      text: opts.text || '',
      sessionId: sessionId || null,
      userId: opts.userId || null,
      context: opts.context || {}
    };
  }

  // 解析单个 SSE 事件块：返回 { event: 命名事件（缺省 agent-event），data: 载荷对象 }，
  // 无 data 行 / 坏 JSON 返回 null。a2ui-message 块的 data 只经 _parseSseBlock 通道分发，
  // 不进 agent-event 事件流。
  function _parseSseBlock(chunk) {
    if (!chunk) return null;
    var eventName = null;
    var dataLine = '';
    chunk.split('\n').forEach(function (line) {
      if (line.indexOf('event:') === 0) {
        eventName = line.slice(6).replace(/^\s+/, '');
      } else if (line.indexOf('data:') === 0) {
        dataLine += line.slice(5).replace(/^\s+/, '');
      }
    });
    if (!dataLine) return null;
    var data;
    try { data = JSON.parse(dataLine); } catch (_) { return null; }
    return { event: eventName || 'agent-event', data: data };
  }

  // 解析单个 SSE 事件块为 agent-event 对象（a2ui-message 块不进此通道）
  function _parseAgentEvent(chunk) {
    var parsed = _parseSseBlock(chunk);
    if (!parsed || parsed.event !== 'agent-event') return null;
    return parsed.data;
  }

  // 剥离正文中的界面协议哨兵块（纯展示细节；协议解析由后端/专用渲染器完成，
  // 此处只隐藏传输载体）。支持 Google A2UI 1.0 的 <a2ui-json> 与 @macroui 的
  // <macro-a2ui> 两种哨兵，可混用；流式期间未闭合的块从开标记处截断隐藏。
  var SENTINELS = [
    { open: '<a2ui-json>', close: '</a2ui-json>' },
    { open: '<macro-a2ui>', close: '</macro-a2ui>' }
  ];

  function _stripSentinel(raw) {
    if (!raw) return '';
    var out = '';
    var rest = raw;
    while (true) {
      var earliest = -1;
      var tag = null;
      SENTINELS.forEach(function (s) {
        var at = rest.indexOf(s.open);
        if (at !== -1 && (earliest === -1 || at < earliest)) { earliest = at; tag = s; }
      });
      if (earliest === -1) { out += rest; break; }
      out += rest.slice(0, earliest);
      var close = rest.indexOf(tag.close, earliest);
      if (close === -1) break;
      rest = rest.slice(close + tag.close.length);
    }
    return out;
  }

  // 从 SSE 块提取进入正文的文本增量（仅 TEXT_BLOCK_DELTA）
  function _extractTextDelta(chunk) {
    var evt = _parseAgentEvent(chunk);
    if (!evt || evt.type !== 'TEXT_BLOCK_DELTA') return '';
    return typeof evt.delta === 'string' ? evt.delta : '';
  }

  // 将 TodoTools.render() 的任务清单文本解析为结构化任务数组。
  // render 输出形如（见框架 TodoTools.render）：
  //   2 open todo(s):
  //   - [x] 调研需求
  //   - [~] 设计方案 (priority: high)
  //   - [ ] 总结输出
  // 仅 front-end 展示用，真相源仍是框架 AgentState.tasksContext；yunxi 不存副本。
  function _extractTodoList(text) {
    if (!text) return [];
    var todos = [];
    var re = /^-\s*\[([ x~])\]\s+(.+?)(?:\s*\(priority:\s*([a-z]+)\))?\s*$/;
    String(text).split('\n').forEach(function (line) {
      var m = line.match(re);
      if (!m) return;
      var mark = m[1];
      var status = mark === 'x' ? 'completed' : mark === '~' ? 'in_progress' : 'pending';
      todos.push({ content: m[2], status: status, priority: m[3] || null });
    });
    return todos;
  }

  // 分发 todo_write 工具事件：累积 TOOL_RESULT_TEXT_DELTA，遇 TOOL_RESULT_END 触发
  // onTodo 透出结构化任务列表（full-list-replace 语义，每次 END 均为全量快照）。
  // 多工具并发时按 toolCallId 隔离累积。返回 true 表示已处理（调用方可跳过默认分支）。
  function _dispatchEvent(evt, handlers, todoBuffers) {
    if (!evt || !evt.type) return false;
    if (evt.type === 'TOOL_RESULT_TEXT_DELTA' && evt.toolCallName === 'todo_write') {
      var k = evt.toolCallId || '_';
      todoBuffers[k] = (todoBuffers[k] || '') + (evt.delta || '');
      return true;
    }
    if (evt.type === 'TOOL_RESULT_END' && evt.toolCallName === 'todo_write') {
      var k2 = evt.toolCallId || '_';
      var raw = todoBuffers[k2] || '';
      delete todoBuffers[k2];
      var todos = _extractTodoList(raw);
      if (typeof handlers.onTodo === 'function') {
        var ctx = evt.metadata && evt.metadata.context;
        handlers.onTodo(todos, {
          toolCallId: evt.toolCallId,
          revision: ctx ? ctx.state_revision : undefined
        });
      }
      return true;
    }
    return false;
  }

  async function _chatStream(body, handlers) {
    var res = await fetch(STREAM_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (!res.ok || !res.body) {
      var msg = 'HTTP ' + res.status;
      try {
        var t = await res.text();
        if (t) msg += ' ' + t;
      } catch (_) {}
      throw new Error(msg);
    }

    var reader = res.body.getReader();
    var decoder = new TextDecoder('utf-8');
    var buffer = '';
    var raw = '';   // 模型正文原样累积（含 A2UI 哨兵块传输载体）
    var full = '';  // 可见正文快照（哨兵块已剥离）
    // 按 toolCallId 隔离累积 todo_write 的 TOOL_RESULT_TEXT_DELTA
    var todoBuffers = {};

    while (true) {
      var step = await reader.read();
      if (step.done) break;
      buffer += decoder.decode(step.value, { stream: true });

      // SSE 以空行 (\n\n) 分隔事件块
      var idx;
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        var block = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        var parsed = _parseSseBlock(block);
        if (!parsed) continue;
        if (parsed.event === 'a2ui-message') {
          if (typeof handlers.onA2ui === 'function') handlers.onA2ui(parsed.data);
          continue;
        }
        if (parsed.event === 'macro-dsl') {
          if (typeof handlers.onMacroDsl === 'function') {
            handlers.onMacroDsl(typeof parsed.data.dsl === 'string' ? parsed.data.dsl : '');
          }
          continue;
        }
        var evt = parsed.data;
        if (evt.type === 'TEXT_BLOCK_DELTA') {
          var delta = typeof evt.delta === 'string' ? evt.delta : '';
          if (delta) {
            raw += delta;
            full = _stripSentinel(raw);
            if (typeof handlers.onDelta === 'function') handlers.onDelta(delta, full);
          }
          continue;
        }
        // 其余事件（含 todo_write 工具事件）交由 dispatcher 处理
        _dispatchEvent(evt, handlers, todoBuffers);
      }
    }
    // 处理尾部残留（无结尾空行）
    if (buffer.trim()) {
      var tailParsed = _parseSseBlock(buffer);
      if (tailParsed) {
        if (tailParsed.event === 'a2ui-message') {
          if (typeof handlers.onA2ui === 'function') handlers.onA2ui(tailParsed.data);
        } else if (tailParsed.event === 'macro-dsl') {
          if (typeof handlers.onMacroDsl === 'function') {
            handlers.onMacroDsl(typeof tailParsed.data.dsl === 'string' ? tailParsed.data.dsl : '');
          }
        } else {
          var tailEvt = tailParsed.data;
          if (tailEvt.type === 'TEXT_BLOCK_DELTA') {
            var tail = typeof tailEvt.delta === 'string' ? tailEvt.delta : '';
            if (tail) {
              raw += tail;
              full = _stripSentinel(raw);
              if (typeof handlers.onDelta === 'function') handlers.onDelta(tail, full);
            }
          } else {
            _dispatchEvent(tailEvt, handlers, todoBuffers);
          }
        }
      }
    }

    if (typeof handlers.onDone === 'function') handlers.onDone(full);
  }

  async function _chatSync(body) {
    var res = await fetch(SYNC_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (!res.ok) {
      var msg = 'HTTP ' + res.status;
      try {
        var t = await res.text();
        if (t) msg += ' ' + t;
      } catch (_) {}
      throw new Error(msg);
    }
    var data = await res.json().catch(function () { return null; });
    if (data && data.result && typeof data.result.text === 'string') return data.result.text;
    if (typeof data === 'string') return data;
    return data ? JSON.stringify(data) : '';
  }

  /**
   * 统一对话入口（yunxi /api/conversations 契约）。
   * @param {Object} opts
   *   agentId   后端 Agent 唯一标识（必填）
   *   text      用户消息正文
   *   context   每轮请求的附加上下文（Map，可携 profile 等元数据）
   *   sessionId 显式会话 id；缺省且 autoManageConversation 时自动生成并复用
   *   userId    终端用户标识（可选，用于画像/审计）
   *   mode      'stream'（默认）| 'sync'
   *   autoManageConversation 默认 true，维持同一会话支持多轮
 *   onDelta(delta, full) 流式增量（full 为剥离哨兵块后的可见正文）
 *   onDone(full)          流/同步结束（同上，可见正文）
   *   onA2ui(payload)       Google A2UI 1.0 界面载荷（payload = { surfaceId, messages, issues }）
   *   onMacroDsl(dsl)       @macroui/a2ui DSL 文本（报表/图表/表格，流末下发）
   *   onError(err)          错误回调（仍会向上抛出）
   */
  async function yunxiChat(opts) {
    opts = opts || {};
    var handlers = {
      onDelta: opts.onDelta,
      onDone: opts.onDone,
      onTodo: opts.onTodo,
      onA2ui: opts.onA2ui,
      onMacroDsl: opts.onMacroDsl
    };

    var sessionId = opts.sessionId || null;
    if (opts.autoManageConversation !== false && !sessionId) {
      sessionId = _uuid();
    }
    var body = _buildBody(opts, sessionId);
    if (opts.autoManageConversation !== false && sessionId) {
      _sessionId = sessionId;
    }

    try {
      if (opts.mode === 'sync') {
        var text = _stripSentinel(await _chatSync(body));
        if (typeof handlers.onDone === 'function') handlers.onDone(text);
        return text;
      }
      await _chatStream(body, handlers);
    } catch (err) {
      if (typeof opts.onError === 'function') opts.onError(err);
      throw err;
    }
  }

  /** 重置自动管理的会话（新对话） */
  function resetConversation() {
    _sessionId = null;
  }

  global.yunxiChat = yunxiChat;
  global.resetYunxiConversation = resetConversation;

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      yunxiChat: yunxiChat,
      resetConversation: resetConversation,
      _parseAgentEvent: _parseAgentEvent,
      _parseSseBlock: _parseSseBlock,
      _stripSentinel: _stripSentinel,
      _extractTodoList: _extractTodoList,
      _dispatchEvent: _dispatchEvent
    };
  }

})(typeof window !== 'undefined' ? window : this);
