# 18. Text2SQL 业务 Agent

本章介绍面向「自然语言问数」场景的业务 Agent 的配置与使用，基于自研 `yunxi-mcp-servers/mcp-text2sql`（两步法 MCP 服务）开箱即用；配套代码在 `yunxi-mcp-servers/mcp-text2sql` 与本仓 `yunxi-config/src/main/resources/agent-definitions/text2sql-assistant.yml`。本章说明该 Agent 的功能、架构概览、工作流与安全机制，帮助开发者快速完成启用与验证。

## 18.1 功能概述

让平台用户用自然语言对指定数据库提问，由 Agent 自动完成「理解意图 → 生成 SQL → 执行取数」，产出结构化结果。默认只读，禁止任何写操作 / DDL，保证数据安全。

## 18.2 分层架构

```
用户对话
  │
  ▼
text2sql-assistant（yunxi 业务 Agent）
  │  systemPrompt：限定只读 + 三步工作流
  │  toolkit.deniedToolNames：裁剪框架默认工具
  ▼
text2sql-mcp（MCP 服务，SSE 独立进程）
  │  text2sql_start：JDBC 内省 schema
  │  text2sql_chat：NL → SQL（两步法）
  │  text2sql_execute：执行 + SqlGuard 护栏
  ▼
目标数据库（如 nutrition，MySQL）
```

- **Agent 定义平面**：本仓 `agent-definitions/text2sql-assistant.yml`，声明订阅 `text2sql-mcp`、系统提示词、工具裁剪。
- **MCP 服务平面**：`yunxi-mcp-servers/mcp-text2sql`，独立 Spring Boot 进程（端口 40110，SSE 端点 `/mcp/sse`），只依赖 LLM + JDBC，不依赖平台。
- **装配**：`mcp.yml` 的 `text2sql-mcp.enabled=true` 后，平台启动期将其注入 `McpDirectory`；`PermissionTemplateResolver` 自动放行三个 text2sql 工具，Agent 构建期按名解析即获得 SQL 能力。引用未启用/缺失的 MCP 服务时 `AgentConfigurer` 跳过该服务，不导致启动失败。

## 18.3 两步法工作流（降低大模型幻觉）

1. **`text2sql_start`**：给定 `sessionId` + `datasource`（默认取配置首个数据源），用 JDBC `DatabaseMetaData` 内省表名 / 列名 / 类型 / 主键，写入进程内会话。先定位相关表，避免让大模型盲猜全库 schema。
2. **`text2sql_chat`**：携带自然语言问题 + 已内省 schema 调用 LLM，产出 SQL（OpenAI 兼容接口）。
3. **`text2sql_execute`**：执行 SQL，返回 `row_count` + 行数据，并执行注入护栏。

## 18.4 安全护栏（纵深防御）

| 层 | 机制 | 说明 |
|---|---|---|
| Agent 提示词层 | systemPrompt 明示只允许 `SELECT / SHOW / DESCRIBE / EXPLAIN` | 约束模型行为，第一道闸 |
| 工具裁剪层 | `toolkit.deniedToolNames` 关掉框架默认文件/执行类工具 | 收窄攻击面，避免 LLM 空转调被拒内置工具 |
| 服务执行层 | `SqlGuard` 多语句检测（字符串外分号切分，非空语句数 > 1 即拦） | 兜底拦截 `SELECT 1; DROP` 类注入 |

## 18.5 启用步骤

1. 配置并启动 `mcp-text2sql` 服务：
   - 环境变量：`DB_HOST / DB_NAME / DB_USERNAME / DB_PASSWORD`（目标库连接）、`LLM_API_KEY`（OpenAI 兼容 LLM，默认 `qwen-plus` 端点 `https://dashscope.aliyuncs.com/compatible-mode/v1`）。
   - `java -jar mcp-text2sql-1.0.0.jar`，监听 40110。
2. 本仓 `config/mcp.yml` 已置 `text2sql-mcp.enabled=true`（默认 `url=http://localhost:40110/mcp/sse`）。
3. 重启平台，Agent `text2sql-assistant` 即具备 SQL 能力。

> 模型走全局自动选型兜底，无需为该 Agent 手动接线。

## 18.6 端到端验证结论

针对真实 `nutrition` 库（MySQL，124 张表）与真实 LLM 验证通过：

- MCP 服务启动正常，注册 `text2sql_chat / text2sql_execute / text2sql_start` 三工具。
- 真实 SSE 传输握手：`initialize` → `tools/list` → `text2sql_start`（内省 124 表）→ `text2sql_chat` 生成 `SELECT COUNT(*) AS table_count FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'nutrition';`（`isError=false`）。
- Bean 级 gated 测试 `Text2SqlE2eTest`（默认不运行，需 `-Dtext2sql.e2e=true` 且外部 DB/LLM 可达）覆盖 introspect → NL→SQL → execute 全链路。

## 18.7 使用须知

- `mcp-text2sql` 是带外部依赖（DB + LLM）的独立组件，需自行部署与运维；平台侧仅做订阅与工具放行。
- 多实例下 `text2sql_start` 的会话内省状态为进程内，会话亲和由 MCP 客户端会话保持；跨实例共享需后续接入分布式会话存储（非本 Agent 范围）。
- 环境 `DASHSCOPE_API_KEY`（shell 级）曾失效导致 LLM 401，验证时改用有效的开发 Key；平台整体 LLM 可用性依赖该环境变量正确配置。
