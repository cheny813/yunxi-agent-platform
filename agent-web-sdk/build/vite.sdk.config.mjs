/**
 * Vite config for the custom IIFE page-agent SDK build.
 *
 * 定位：yunxi 侧定制构建配置（单一源）。上游 page-agent 仓只提供 ESM/NPM
 * 产物，无 IIFE 单文件构建，故本配置通过源码别名直接引用上游仓源码打包。
 *
 * 前置条件：page-agent 上游仓已 checkout 且已 npm install（sibling 目录）。
 * 仓库位置默认按 sibling 相对路径解析（../../../page-agent），可用环境变量
 * PAGE_AGENT_REPO 覆盖。
 *
 * 执行（在 page-agent 仓根运行，复用其 node_modules 内的 vite 工具链）：
 *   cd <page-agent-repo>
 *   npx vite build -c <agent-web-sdk>/build/vite.sdk.config.mjs
 *
 * 产物：agent-web-sdk/dist/page-agent.sdk.js（IIFE，不自动初始化）
 */
import { dirname, resolve } from 'path'
import { fileURLToPath, pathToFileURL } from 'url'

const __dirname = dirname(fileURLToPath(import.meta.url))

// page-agent 上游仓位置：默认 sibling 相对路径，可用 PAGE_AGENT_REPO 覆盖
const PAGE_AGENT_REPO = resolve(__dirname, process.env.PAGE_AGENT_REPO || '../../../page-agent')

// 插件为 ESM-only 包（exports 无 CJS 入口），从上游仓 node_modules 按绝对路径动态加载
const cssInjectedByJsModule = await import(
  pathToFileURL(resolve(PAGE_AGENT_REPO, 'node_modules/vite-plugin-css-injected-by-js/dist/index.js'))
)
const cssInjectedByJsPlugin = cssInjectedByJsModule.default || cssInjectedByJsModule

export default async function defineConfig() {
  return {
    plugins: [cssInjectedByJsPlugin({ relativeCSSInjection: true })],
    publicDir: false,
    esbuild: { keepNames: true },
    resolve: {
      alias: {
        // 内部源码别名：直接引用上游仓 TS 源码（与上游单仓结构解耦）
        '@page-agent/page-agent': resolve(PAGE_AGENT_REPO, 'packages/page-agent/src/PageAgent.ts'),
        '@page-agent/page-controller': resolve(PAGE_AGENT_REPO, 'packages/page-controller/src/PageController.ts'),
        '@page-agent/core': resolve(PAGE_AGENT_REPO, 'packages/core/src/PageAgentCore.ts'),
        '@page-agent/ui': resolve(PAGE_AGENT_REPO, 'packages/ui/src/index.ts'),
        '@page-agent/llms': resolve(PAGE_AGENT_REPO, 'packages/llms/src/index.ts'),
        // 运行时依赖同样取自上游仓 node_modules（其版本是上游测试过的组合）
        'zod/v4': resolve(PAGE_AGENT_REPO, 'node_modules/zod/v4'),
        'zod': resolve(PAGE_AGENT_REPO, 'node_modules/zod'),
      },
    },
    build: {
      lib: {
        entry: resolve(__dirname, 'sdk.ts'),
        name: 'PageAgent',
        fileName: () => 'page-agent.sdk.js',
        formats: ['iife'],
      },
      // 直接输出到 agent-web-sdk/dist，免去手工拷贝
      outDir: resolve(__dirname, '..', 'dist'),
      emptyOutDir: true,
      cssCodeSplit: true,
      rollupOptions: {
        onwarn(message, handler) {
          if (message.code === 'EVAL') return
          handler(message)
        },
      },
    },
    define: {
      // 上游 llms 包源码引用的 env 常量：本 SDK 不内置 LLM 配置，置空即可
      'import.meta.env.LLM_MODEL_NAME': JSON.stringify(''),
      'import.meta.env.LLM_API_KEY': JSON.stringify(''),
      'import.meta.env.LLM_BASE_URL': JSON.stringify(''),
    },
  }
}
