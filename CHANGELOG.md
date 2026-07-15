# 更新日志

本文件面向使用本项目的最终用户与贡献者，按 [Keep a Changelog](https://keepachangelog.com/) 规范记录**用户可见的能力、破坏性变更、修复与安全相关**内容。

> 历次发布的完整技术细节（内部重构、死代码清理、类名迁移等）保留在 [`CHANGELOG.internal.md`](./CHANGELOG.internal.md)，供团队与贡献者回溯。

版本号自 `2.0.0` 起与底层 [AgentScope-Java](https://github.com/agentscope-ai/agentscope-java) 保持同步；`1.0.0` 与 `3.x` 为早期独立版本线，其变更内容依然有效。

---

## [Unreleased]

### 新增

- **模型按 Agent 覆盖 / 轻量多租户**：Agent 定义 YAML 支持 `model.apiKey` / `model.baseUrl` / `model.stream` 覆盖，可为每个 Agent 配置独立模型账号，不配置时回退全局配置或环境变量。
- **LLM 调用 Usage 可观测性**：
  - 每次 LLM 调用输出 INFO 日志 `[LLM Usage]`（含输入/输出/缓存 token 与耗时）。
  - 上报 OpenTelemetry 指标 `llm.token.total`（区分 prompt/completion）与 `llm.duration`（耗时直方图），可通过 Prometheus 暴露。
  - `PageAgentService` 打印响应内容摘要（文本 / 推理 / 工具调用 / 数据块）。
  - 修复 OpenAI 兼容代理 `usage` 被硬编码为 0 的问题，现聚合真实 token 消耗。

### 变更

- 模型缓存策略文档化：单租户按 `modelId` 复用实例，多租户默认不复用，避免不同账号的 Key / BaseURL 串用。

---

## [2.0.0] - 2026-07-12

> 本版本对应 AgentScope-Java **2.0.0 正式版（GA）**。

### ⚠️ 破坏性变更

- 底层框架由 AgentScope-Java 2.0.0-RC3 升级至 **2.0.0 GA**，全面对齐 GA 原生 API。
- 移除 `agent-gateway` 模块（IM 渠道未上线，已由框架原生 channel 覆盖）。
- 移除 `agent-rule-engine` 模块（暂无实际业务使用）。
- 多租户隔离改为运行时注入 `RuntimeContext`，移除旧的 `UserWorkspaceService`。

### 新增

- **MCP 真注册**：支持 SSE / STDIO / HTTP 三种传输，按服务器名分组并与工具组激活衔接。
- **权限引擎**：将人工确认（HITL）配置映射为框架权限上下文，支持允许 / 询问 / 拒绝规则。
- **应用层 RAG**：在系统提示阶段注入检索上下文。
- **Plan 模式**：启用框架计划中间件，支持任务规划与执行。
- **Skill 系统**：启用框架原生技能仓库，由动态技能中间件自动装载。
- **韧性配置**：支持重试、降级模型、超时等模型执行层配置。

### 修复 / 验证

- 集成测试按新架构重写并通过（13 个用例）。
- 本地向量库（Milvus）ETL 链路验证跑通。

---

## [3.5.0] - 2026-06-26

### ⚠️ 破坏性变更

- 升级底层框架至 AgentScope-Java 2.0.0-RC3，涉及多项 API 不兼容：
  - 会话持久化 `Session` → `DistributedStore`。
  - 链路追踪 `Tracer` / `TracerRegistry` 废弃，改用 OpenTelemetry 直连。
  - 流式调用 `stream()` → `streamEvents()`。
  - 中间件签名新增 `RuntimeContext` 参数。
  - 事件体系 `Event` / `EventType` → `AgentEvent` / `AgentEventType`。

### 新增

- `RedisDistributedBackendConfig` 按条件自动装配 Redis 后端。
- `ModelFactory` 采用框架 `ModelRegistry` 工厂机制集中注册各模型提供商。
- 本地 `Plan` / `SubTask` 模型类替代框架已删除的计划包。

### 修复

- `start.ps1` 增加 `-Djava.net.preferIPv4Stack=true`，修复 IPv6 优先导致的 DNS 解析失败。
- SSE 流式响应异常改为写入 SSE error 事件，避免框架层异常外泄。
- `milvus.yml` 默认 `enabled: false`，消除未启动时的连接超时报错。

---

## [3.4.0] - 2026-06-04

### 新增

- 复用底层框架 Model 体系，统一支持 openai / claude / dashscope / deepseek / baidu / huawei 六种模型提供商。
- `ShellToolFactory`：封装框架 Shell 工具，支持命令白名单与审批回调。
- **提示注入防护**：`ContentFilter` 在推理阶段检测中英文注入模式并阻断。
- **Prompt Caching 配置化**：`cache-control: true` 即可启用各提供商的提示缓存能力。
- 生成参数全局配置（temperature / maxTokens / topP）。

### 移除

- 自建的 `ChatModelProvider` 系列与 `CommandSafety` 系列，改为复用框架内置能力。

---

## [3.3.1] - 2026-06-03

### 修复

- 修正内置工具未分组（ungrouped）问题，统一归入 `general` 工具组受管控。
- 修复 Toolkit 深拷贝后工具组激活失效的问题。

---

## [3.2.0] - 2026-06-02

### 新增

- 启用框架会话持久化（内置 Hook 自动持久化运行时状态）。
- **Graceful Shutdown**：支持优雅关闭并在中断后恢复执行。
- 统一采用 `HarnessAgent` 构建模式，面向 `Agent` 接口编程。
- Redis 会话支持：配置 `agentscope.core.session.type=redis` 切换 Redis 后端，实现跨实例状态共享（默认文件系统，零配置可用）。
- `AgentCustomizer` SPI 扩展点。

### 修复

- 修复工作区自动发现误报 WARN 的问题。

---

## [1.0.0] - 2026-05-09

### 新增

- 初始版本发布。
- 支持多 Agent 协作。
- 集成 MCP 协议。
- 多平台接入（Web、企业微信、钉钉、飞书）。

### 模块

| 模块 | 说明 |
|------|------|
| `agent-core` | 核心框架（含网关接入、Agent 编排、记忆、技能、安全） |
| `agent-text2sql` | SQL 生成 |
| `agent-spi` | SPI 接口定义 |
| `agent-config` | 统一配置 |
| `agent-app` | 启动入口 |

---

## 内部变更（技术细节，供贡献者参考）

以下为各版本的内部重构与实现细节摘要，完整记录见 [`CHANGELOG.internal.md`](./CHANGELOG.internal.md)。

- **[Unreleased]**：`ModelFactory` 由 1 参 `registerFactory` 改为 2 参 `ContextModelFactory`，各官方提供商统一经 `ModelCreationContext` 透传覆盖值；清理死代码 `registerModelWithOptions`、`resolveApiKey`（baidu/huawei 改走 `ModelRegistry.resolve` 后已无调用）；`baidu` / `huawei` 自定义 Provider 也在 `init()` 注册为 `ModelRegistry` 工厂，与内置 Provider 走完全一致的 `ModelRegistry.resolve` 路径（按 Agent 透传 `apiKey` / `options`）；新增 `BaiduCredential` / `HuaweiCredential`（继承框架 `CredentialBase`，闭合 `getChatModelClass()` 钩子，与内置 Provider 在 Credential 抽象层对齐；`listModels()` 沿用框架默认桩，前端模型发现须走 yunxi 自有目录）；新增 `LlmMetrics.recordAndLogUsage` 统一入口并落地于 `ChatAppService` / `PageAgentService`；`application.yml` 增加 `io.agentscope.core.model: DEBUG` 开关。
- **[2.0.0]**：删除 7 个自建 MCP 客户端类与 `DatabaseToolkit`，数据同步改直连 JDBC；新增 `PermissionConfig` / `ApplicationRAG`；删除 `ToolGateMiddleware` / `ReasoningReviewMiddleware` / `Knowledge*.java` / `Plan*.java` / `AgentInterruptService` 等屏蔽或重复类；场景检测链整体删除；`agentscope.version` 升至 `2.0.0`。
- **[3.5.0]**：`Session` → `DistributedStore` 迁移；`OpenTelemetryTracer` 删除改用全局 OTel；`MiddlewareBase` 签名新增 `RuntimeContext`；A2A 包名变更；6 个中间件文件签名更新。
- **[3.4.0]**：拆除自建 ChatModelProvider（约 500 行）与 CommandSafety（约 310 行）改复用框架；`AgentDomainService` 等缓存类型 `ChatModelProvider` → `Model`；新增 `model/` / `embedding/` 分包。
- **[3.3.1]**：`AgentConfigurer` 新增 `resolveAgentToolkit()` / `assignUngroupedTools()`；通过 `HarnessAgent.getDelegate().getToolkit()` 修正组激活。
- **[3.2.0]**：引入 `agentscope-harness`；删除 `MemoryCoordinatorService` / `AsyncConversationPersistenceService`；缓存类型 `Map<String, ReActAgent>` → `Map<String, Agent>`；移除反射获取 Toolkit。
