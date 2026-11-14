import { resolve } from 'path';
import { defineConfig } from 'vite';

// 云曦营养师前端开发配置
// 后端 agent-core 默认地址 localhost:40001（见 agent-config/src/main/resources/config/server.yml）
// 可通过环境变量覆盖：BACKEND_HOST（默认 localhost）、BACKEND_PORT（默认 40001）。
// 开发时把 /api、/v1 代理到后端，避免浏览器跨域；apiBase 在前端使用 window.location.origin 即指向本 dev server。
// 注意：此 proxy 仅用于 dev，不会进入生产产物；生产靠同源或生产反代访问 /api、/v1。
//
// 公共层：PageAgentSDK 透传层来自平台仓的 agent-web-sdk（单一源，勿在产品中拷贝）。
// 产品侧 src/entry.js 通过相对 import 引用 ../agent-web-sdk/src/page-agent-sdk.js，
// 经 fs.allow 授权跨目录读取，dev/build 均指向同一份源文件，避免拷贝漂移。
const BACKEND_HOST = process.env.BACKEND_HOST || 'localhost';
const BACKEND_PORT = process.env.BACKEND_PORT || '40001';
// 注：mcp-formfill（STOMP/WebSocket，原 /ws/formfill → 40601）暂未纳入依赖，相关代理已移除。

// 后端 yunxi 安全策略：除 /actuator/health 外所有 /api 端点必须 HTTP Basic 认证。
// dev 代理层代为注入凭据，避免浏览器裸调被 401。仅 dev 使用，不进入生产产物；
// 可用环境变量 YUNXI_UI_USER / YUNXI_UI_PASSWORD 覆盖（默认 demo 凭据）。生产经网关鉴权。
const UI_USER = process.env.YUNXI_UI_USER || 'admin';
const UI_PASSWORD = process.env.YUNXI_UI_PASSWORD || 'yunxi-dev-pass';
const DEV_BASIC = 'Basic ' + Buffer.from(`${UI_USER}:${UI_PASSWORD}`).toString('base64');

export default defineConfig({
  root: '.',
  resolve: {
    alias: {
      // 公共前端 SDK 层（单一源：yunxi-agent-platform/agent-web-sdk）
      // dev 与 build 均经此 alias 解析，避免跨 root 被 Rollup 拒绝
      '@web-sdk': resolve(__dirname, '../agent-web-sdk/src'),
    },
  },
  server: {
    port: 5173,
    // 端口被占时直接报错退出，而不是静默跳到 5174，避免浏览器仍访问 5173 命中残留旧实例。
    strictPort: true,
    fs: {
      // 允许 Vite 服务兄弟目录的 agent-web-sdk 共享源
      allow: [resolve(__dirname, '..')],
    },
    proxy: {
      '/api': {
        target: `http://${BACKEND_HOST}:${BACKEND_PORT}`,
        changeOrigin: true,
        // dev 代理注入 Basic 凭据（后端要求 /api 端点认证）
        headers: { Authorization: DEV_BASIC },
        // SSE 友好：禁用代理缓冲，保证流式输出逐块转发到浏览器
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if (proxyRes.headers['content-type'] && proxyRes.headers['content-type'].includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache, no-transform';
              proxyRes.headers['connection'] = 'keep-alive';
              proxyRes.headers['x-accel-buffering'] = 'no';
            }
          });
        },
      },
      '/v1': {
        target: `http://${BACKEND_HOST}:${BACKEND_PORT}`,
        changeOrigin: true,
        headers: { Authorization: DEV_BASIC },
      },
      // 原生 PageAgentSDK 透传层所依赖的 /js/page-agent.sdk.js 等静态资源
      // 由后端 agent-config（默认 40001）在 /js 下提供，dev 时转发避免 404。
      // 同样注入 Basic 凭据，否则未认证请求会被后端 401 并触发浏览器原生密码框。
      '/js': {
        target: `http://${BACKEND_HOST}:${BACKEND_PORT}`,
        changeOrigin: true,
        headers: { Authorization: DEV_BASIC },
      },
    },
  },
  build: {
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        'recipe-make': resolve(__dirname, 'pages/recipe-make.html'),
      },
    },
  },
});
