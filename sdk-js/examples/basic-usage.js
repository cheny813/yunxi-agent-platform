/**
 * 基础使用示例
 * 运行: node examples/basic-usage.js
 *
 * 演示如何创建一个 AgentClient 并发起一次同步对话。
 */
const { AgentClient } = require('../src');

async function main() {
  const client = new AgentClient('http://localhost:40001', {
    defaultUserId: 'example-user',
    defaultAgentName: 'general-assistant',
  });

  try {
    // 同步对话：等待完整回复
    const reply = await client.chat('用一句话介绍你自己');
    console.log('回复:', reply);
  } catch (err) {
    console.error('调用失败:', err && err.message ? err.message : err);
  }
}

main();
