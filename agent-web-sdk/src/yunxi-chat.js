/**
 * yunxi-chat.js —— 页面侧通用对话工具（同步 / 流式统一封装）
 *
 * 设计目标：让页面用几乎相同的代码在「同步返回」和「流式返回」之间切换，
 * 页面只需改一个 mode 字段，业务逻辑（构造 message、渲染结果）保持不变。
 *
 * 用法：
 *   const full = await yunxiChat({
 *     agentName: 'resident-nutrition-assistant',
 *     profile:   'recipe-make',
 *     mode:      'stream',                 // 'stream' | 'sync'（默认 sync）
 *     autoManageConversation: true,        // 可选，前端托管会话
 *     userId:    '...',                    // 可选
 *     message:   '...',
 *     onDelta:   (delta) => { ... },       // 仅 stream 模式回调，逐段文本增量
 *     onDone:    (fullText) => { ... },    // 必填，完整文本（两种情况都会调）
 *   });
 *
 * 返回：完整文本（也会在 onDone 中回调）。
 * 请求路径为绝对 '/api/...'，兼容 Vite 代理与直接部署两种场景。
 */
(function (global) {
  const DEFAULT_TOKEN = 'page-agent';
  const SYNC_URL = '/api/conversations/chat';
  const STREAM_URL = '/api/conversations/chat/stream';

  /**
   * 同步请求：POST /api/conversations/chat，返回 reply 文本
   */
  async function chatSync(body) {
    const res = await fetch(SYNC_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Agent-Token': DEFAULT_TOKEN },
      credentials: 'include',
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error('请求失败（HTTP ' + res.status + '）');
    const data = await res.json();
    return data.reply || '';
  }

  /**
   * 流式请求：POST /api/conversations/chat/stream，逐段回调 content 增量
   * SSE 事件格式：data: {"type":"content","timestamp":...,"content":"..."}
   */
  async function chatStream(body, onDelta) {
    const res = await fetch(STREAM_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Agent-Token': DEFAULT_TOKEN },
      credentials: 'include',
      body: JSON.stringify(body),
    });
    if (!res.ok || !res.body) throw new Error('流式请求失败（HTTP ' + res.status + '）');

    const reader = res.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let fullText = '';

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // SSE 以空行(\n\n)分隔事件；每行 data: <json>
      let sep;
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        const raw = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        const dataLine = raw.split('\n').find((l) => l.startsWith('data:'));
        if (!dataLine) continue;
        const payloadStr = dataLine.slice(5).trim();
        if (!payloadStr || payloadStr === '[DONE]') continue;
        try {
          const evt = JSON.parse(payloadStr);
          if (evt.type === 'content' && evt.content) {
            fullText += evt.content;
            if (typeof onDelta === 'function') onDelta(evt.content, fullText);
          }
        } catch (_) {
          /* 忽略非 JSON 控制行 */
        }
      }
    }
    return fullText;
  }

  /**
   * 统一入口：根据 mode 选择同步或流式
   * @param {object} opts
   *   - mode: 'stream' | 'sync'（默认 'sync'）
   *   - 其余字段透传给后端 UnifiedChatRequest
   *   - onDelta: 流式增量回调 (delta, fullSoFar)
   *   - onDone:  完成回调 (fullText)
   * @returns {string} 完整文本
   */
  async function yunxiChat(opts) {
    const { mode = 'sync', onDelta, onDone, ...rest } = opts;
    let fullText;

    if (mode === 'stream') {
      fullText = await chatStream(rest, onDelta);
    } else {
      fullText = await chatSync(rest);
    }

    if (typeof onDone === 'function') onDone(fullText);
    return fullText;
  }

  global.yunxiChat = yunxiChat;
})(window);
