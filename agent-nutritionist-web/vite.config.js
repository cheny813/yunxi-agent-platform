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
// mcp-formfill 独立工程（STOMP/WebSocket），默认 40601。
// 通过平台网关代理 /ws/formfill → 40601，前端用同源 wsPath='/ws/formfill' 连接，
// 避免暴露 MCP 端口、规避浏览器跨域。
const FORMFILL_HOST = process.env.FORMFILL_HOST || 'localhost';
const FORMFILL_PORT = process.env.FORMFILL_PORT || '40601';

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
    fs: {
      // 允许 Vite 服务兄弟目录的 agent-web-sdk 共享源
      allow: [resolve(__dirname, '..')],
    },
    proxy: {
      '/api': {
        target: `http://${BACKEND_HOST}:${BACKEND_PORT}`,
        changeOrigin: true,
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
      },
      // 原生 PageAgentSDK 透传层所依赖的 /js/page-agent.sdk.js 等静态资源
      // 由后端 agent-config（默认 40001）在 /js 下提供，dev 时转发避免 404。
      '/js': {
        target: `http://${BACKEND_HOST}:${BACKEND_PORT}`,
        changeOrigin: true,
      },
      // mcp-formfill STOMP/WebSocket 端点（独立工程，默认 40601）。
      // 注意：ws 代理需 changeOrigin + ws:true，Vite 才能正确升级协议。
      '/ws/formfill': {
        target: `http://${FORMFILL_HOST}:${FORMFILL_PORT}`,
        changeOrigin: true,
        ws: true,
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
