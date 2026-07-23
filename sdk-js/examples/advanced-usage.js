/**
 * 高级使用示例
 * 运行: node examples/advanced-usage.js
 *
 * 演示 chatStreamEvents：将 SSE 事件解析为结构化回调，
 * 分别渲染文本、思考过程与工具调用卡片（含工具内流式进度）。
 */
const { AgentClient } = require('../src');

async function main() {
  const client = new AgentClient('http://localhost:40001', {
    defaultUserId: 'example-user',
    defaultAgentName: 'general-assistant',
    timeout: 600000,
  });

  const toolCards = new Map();

  function describeTool(state) {
    return { success: '成功', error: '失败', interrupted: '已中断', denied: '已拒绝' }[state] || state;
  }

  try {
    await client.chatStreamEvents('帮我查一下北京今天的天气，并说明穿衣建议', {
      onThinking: (s) => process.stdout.write(`💡 思考: ${s}\n`),
      onToolCall: (t) => {
        if (!toolCards.has(t.toolCallId)) {
          toolCards.set(t.toolCallId, t.toolCallName);
          console.log(`🔧 工具调用: ${t.toolCallName} (${t.toolCallId})`);
        }
      },
      onToolResultDelta: (d) => {
        process.stdout.write(`   · ${d.toolCallName} 流式: ${d.delta}`);
      },
      onToolResult: (t) => console.log(`   ✅ ${t.toolCallName} ${describeTool(t.state)}`),
      onText: (s) => process.stdout.write(s),
      onError: (e) => console.error('错误:', e && e.message ? e.message : e),
      onDone: () => console.log('\n--- 完成 ---'),
    });
  } catch (err) {
    console.error('调用失败:', err && err.message ? err.message : err);
  }
}

main();
