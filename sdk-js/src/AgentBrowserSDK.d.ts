/**
 * 浏览器端机器控制 SDK（AgentBrowserSDK）
 *
 * 让 AI Agent 能够在用户浏览器中执行脚本和命令。
 * 源码：sdk-js/src/AgentBrowserSDK.js（等价于旧 static/js/agent-sdk/agent-sdk.js）
 */
export interface AgentBrowserSDKConfig {
  /** Agent 服务端 API 基址，默认 /api/agent */
  apiBase?: string;
  /** 是否自动初始化，默认 true */
  autoInit?: boolean;
  /** 是否输出调试日志，默认 false */
  debug?: boolean;
}

export const AgentBrowserSDK: {
  /** 初始化 SDK，注册供 AI 调用的全局方法 */
  init(options?: AgentBrowserSDKConfig): void;
  /** 执行一条浏览器端命令（由 AI 调用） */
  execute?(command: string, args?: unknown): Promise<unknown>;
  /** 运行时配置 */
  config?: AgentBrowserSDKConfig;
  [key: string]: unknown;
};
