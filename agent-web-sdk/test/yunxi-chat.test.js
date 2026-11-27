/**
 * yunxi-chat.js 任务清单透出单元测试（node:test，无需额外依赖）。
 *
 * 验证点：前端 SDK 从框架 agent-event SSE 中识别 todo_write 工具事件，
 * 还原结构化任务列表并透传给 onTodo 回调。纯前端识别，后端零改动。
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const api = require('../src/yunxi-chat.js');

// 样本：TodoTools.render() 的真实输出格式（见框架 TodoTools.render）
const RENDER_SAMPLE = [
  '2 open todo(s):',
  '- [x] 调研需求',
  '- [~] 设计方案 (priority: high)',
  '- [ ] 总结输出',
].join('\n');

test('_parseAgentEvent: 从 SSE 块解析 agent-event', () => {
  assert.strictEqual(typeof api._parseAgentEvent, 'function', '未暴露 _parseAgentEvent');
  const block =
    'event:agent-event\n' +
    'data:{"type":"TOOL_RESULT_TEXT_DELTA","toolCallId":"t1","toolCallName":"todo_write","delta":"x"}\n';
  const evt = api._parseAgentEvent(block);
  assert.ok(evt);
  assert.strictEqual(evt.type, 'TOOL_RESULT_TEXT_DELTA');
  assert.strictEqual(evt.toolCallName, 'todo_write');
  assert.strictEqual(evt.toolCallId, 't1');
  assert.strictEqual(evt.delta, 'x');
});

test('_parseAgentEvent: 无 data 行 / 坏 JSON 返回 null', () => {
  assert.strictEqual(api._parseAgentEvent('event:agent-event\n'), null);
  assert.strictEqual(api._parseAgentEvent('data:{bad json}\n'), null);
});

test('_extractTodoList: 解析 render 文本为结构化任务', () => {
  assert.strictEqual(typeof api._extractTodoList, 'function', '未暴露 _extractTodoList');
  const todos = api._extractTodoList(RENDER_SAMPLE);
  assert.strictEqual(todos.length, 3);
  assert.deepStrictEqual(todos[0], { content: '调研需求', status: 'completed', priority: null });
  assert.deepStrictEqual(todos[1], { content: '设计方案', status: 'in_progress', priority: 'high' });
  assert.deepStrictEqual(todos[2], { content: '总结输出', status: 'pending', priority: null });
});

test('_extractTodoList: 空 / 清空文本返回空数组', () => {
  assert.deepStrictEqual(api._extractTodoList(''), []);
  assert.deepStrictEqual(api._extractTodoList('Todo list cleared (0 items).'), []);
});

test('_dispatchEvent: 累积 delta + END 触发 onTodo（含 revision）', () => {
  assert.strictEqual(typeof api._dispatchEvent, 'function', '未暴露 _dispatchEvent');
  const captured = [];
  const handlers = { onTodo: (todos, meta) => captured.push({ todos, meta }) };
  const buffers = {};

  // 分两次流式 delta 到达（模拟真实分块）
  api._dispatchEvent(
    { type: 'TOOL_RESULT_TEXT_DELTA', toolCallName: 'todo_write', toolCallId: 't1', delta: '2 open todo(s):\n- [x] 调研需求\n' },
    handlers, buffers
  );
  assert.strictEqual(captured.length, 0, 'delta 阶段不应触发 onTodo');

  api._dispatchEvent(
    { type: 'TOOL_RESULT_TEXT_DELTA', toolCallName: 'todo_write', toolCallId: 't1', delta: '- [ ] 总结输出\n' },
    handlers, buffers
  );

  api._dispatchEvent(
    { type: 'TOOL_RESULT_END', toolCallName: 'todo_write', toolCallId: 't1', metadata: { context: { state_revision: '3' } } },
    handlers, buffers
  );

  assert.strictEqual(captured.length, 1);
  assert.strictEqual(captured[0].todos.length, 2);
  assert.strictEqual(captured[0].todos[0].status, 'completed');
  assert.strictEqual(captured[0].todos[1].status, 'pending');
  assert.strictEqual(captured[0].meta.revision, '3');
  assert.strictEqual(captured[0].meta.toolCallId, 't1');
});

test('_dispatchEvent: 非 todo_write 工具与文本事件不触发 onTodo', () => {
  const called = [];
  const handlers = { onTodo: (t) => called.push(t) };
  const buffers = {};

  const handledNonTodo = api._dispatchEvent(
    { type: 'TOOL_RESULT_END', toolCallName: 'search', toolCallId: 't2' },
    handlers, buffers
  );
  const handledText = api._dispatchEvent(
    { type: 'TEXT_BLOCK_DELTA', delta: 'hi' },
    handlers, buffers
  );
  assert.strictEqual(handledNonTodo, false);
  assert.strictEqual(handledText, false);
  assert.strictEqual(called.length, 0);
});

test('_dispatchEvent: 多工具并发按 toolCallId 隔离累积', () => {
  const captured = [];
  const handlers = { onTodo: (todos, meta) => captured.push({ todos, meta }) };
  const buffers = {};
  api._dispatchEvent({ type: 'TOOL_RESULT_TEXT_DELTA', toolCallName: 'todo_write', toolCallId: 'a', delta: '- [ ] A\n' }, handlers, buffers);
  api._dispatchEvent({ type: 'TOOL_RESULT_TEXT_DELTA', toolCallName: 'todo_write', toolCallId: 'b', delta: '- [ ] B\n' }, handlers, buffers);
  api._dispatchEvent({ type: 'TOOL_RESULT_END', toolCallName: 'todo_write', toolCallId: 'a', metadata: { context: { state_revision: '1' } } }, handlers, buffers);
  assert.strictEqual(captured.length, 1);
  assert.strictEqual(captured[0].todos.length, 1);
  assert.strictEqual(captured[0].todos[0].content, 'A');
});

// ── A2UI：a2ui-message 事件分发 + 正文哨兵块剥离 ──

test('_parseSseBlock: 区分 agent-event 与 a2ui-message 命名事件', () => {
  assert.strictEqual(typeof api._parseSseBlock, 'function', '未暴露 _parseSseBlock');

  const agentEvt = api._parseSseBlock('event:agent-event\ndata:{"type":"TEXT_BLOCK_DELTA","delta":"hi"}\n');
  assert.strictEqual(agentEvt.event, 'agent-event');
  assert.strictEqual(agentEvt.data.type, 'TEXT_BLOCK_DELTA');

  const a2ui = api._parseSseBlock(
    'event:a2ui-message\n' +
    'data:{"surfaceId":"s1","messages":[{"version":"1.0","createSurface":{"surfaceId":"s1","catalogId":"acme:basic"}}],"issues":[]}\n');
  assert.strictEqual(a2ui.event, 'a2ui-message');
  assert.strictEqual(a2ui.data.surfaceId, 's1');
  assert.strictEqual(a2ui.data.messages.length, 1);
  assert.deepStrictEqual(a2ui.data.issues, []);

  assert.strictEqual(api._parseSseBlock('data:{bad json}\n'), null);
});

test('_parseAgentEvent: a2ui-message 块不误入 agent-event 通道', () => {
  const block = 'event:a2ui-message\ndata:{"surfaceId":"s1","messages":[],"issues":[]}\n';
  assert.strictEqual(api._parseAgentEvent(block), null);
});

test('_stripSentinel: 无块原样返回', () => {
  assert.strictEqual(typeof api._stripSentinel, 'function', '未暴露 _stripSentinel');
  assert.strictEqual(api._stripSentinel('今天天气不错。'), '今天天气不错。');
  assert.strictEqual(api._stripSentinel(''), '');
});

test('_stripSentinel: 单块剥离、未闭合截断、多块剥离', () => {
  const block = '<a2ui-json>[{"version":"1.0","createSurface":{"surfaceId":"s1","catalogId":"acme:basic"}}]</a2ui-json>';
  assert.strictEqual(api._stripSentinel('界面如下：' + block + '以上。'), '界面如下：以上。');
  assert.strictEqual(api._stripSentinel('界面如下：<a2ui-json>[{"vers'), '界面如下：');
  assert.strictEqual(
    api._stripSentinel(block + '中段' + block + '尾'),
    '中段尾');
});
