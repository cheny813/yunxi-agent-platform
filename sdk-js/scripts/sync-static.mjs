#!/usr/bin/env node
/**
 * sync-static.mjs
 *
 * 将 sdk-js 的源码同步为后端静态资源（供浏览器演示页 /js/* 引用）。
 *
 * 单一真相源原则：
 *   sdk-js/src 是 SDK 的唯一源码。
 *   agent-config/src/main/resources/static/js 下的副本由本脚本生成，
 *   请勿手工编辑；改完 sdk-js/src 后运行 `npm run sync` 重新生成。
 *
 * 用法：在 sdk-js 目录下执行 `npm run sync`
 */
import { copyFileSync, mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const SDK_ROOT = resolve(__dirname, '..');
const STATIC_ROOT = resolve(SDK_ROOT, '..', 'agent-config', 'src', 'main', 'resources', 'static');

const jobs = [
  {
    from: join(SDK_ROOT, 'src', 'AgentClient.js'),
    to: join(STATIC_ROOT, 'js', 'AgentClient.js'),
    banner:
      '/* AUTO-GENERATED from sdk-js/src/AgentClient.js — 请勿手工编辑，运行 `npm run sync` 重新生成 */\n',
  },
  {
    from: join(SDK_ROOT, 'src', 'AgentBrowserSDK.js'),
    to: join(STATIC_ROOT, 'js', 'agent-sdk', 'agent-sdk.js'),
    banner:
      '/* AUTO-GENERATED from sdk-js/src/AgentBrowserSDK.js — 请勿手工编辑，运行 `npm run sync` 重新生成 */\n',
  },
];

let ok = 0;
for (const job of jobs) {
  if (!existsSync(job.from)) {
    console.warn(`[skip] 源文件不存在: ${job.from}`);
    continue;
  }
  mkdirSync(dirname(job.to), { recursive: true });
  const content = readFileSync(job.from, 'utf8');
  writeFileSync(job.to, job.banner + content, 'utf8');
  console.log(`[ok] ${job.from}\n     -> ${job.to}`);
  ok += 1;
}

console.log(`\n静态资源同步完成（${ok}/${jobs.length}）。`);
