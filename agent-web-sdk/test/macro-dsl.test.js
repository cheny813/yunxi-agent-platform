/**
 * macro-dsl 通道单元测试：SDK 从正文中剥离 <macro-a2ui> 哨兵块，
 * 并把 SSE macro-dsl 命名事件分发给 onMacroDsl 回调。
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const api = require('../src/yunxi-chat.js');

test('_stripSentinel: 剥离 macro-a2ui 哨兵块（保留可见正文）', () => {
  assert.strictEqual(typeof api._stripSentinel, 'function');
  const raw = '方案如下：\n<macro-a2ui>[card tt:周报][table][tr 标题 数值][/table][/card]</macro-a2ui>\n以上。';
  assert.strictEqual(api._stripSentinel(raw), '方案如下：\n\n以上。');
});

test('_stripSentinel: 同时剥离 a2ui-json 与 macro-a2ui 两种哨兵', () => {
  const raw = 'A<a2ui-json>{"x":1}</a2ui-json>B<macro-a2ui>[p Hi]</macro-a2ui>C';
  assert.strictEqual(api._stripSentinel(raw), 'ABC');
});

test('_stripSentinel: 流式未闭合 macro 块从开标记截断', () => {
  assert.strictEqual(api._stripSentinel('正文<macro-a2ui>[card tt:未闭合'), '正文');
});

test('_parseSseBlock: macro-dsl 命名事件解析', () => {
  const block = 'event:macro-dsl\ndata:{"dsl":"[p Hello]"}\n';
  const parsed = api._parseSseBlock(block);
  assert.strictEqual(parsed.event, 'macro-dsl');
  assert.strictEqual(parsed.data.dsl, '[p Hello]');
});
