# 更新日志

本文件面向使用本项目的最终用户与贡献者，按 [Keep a Changelog](https://keepachangelog.com/) 规范记录**用户可见的能力、破坏性变更、修复与安全相关**内容。

版本号自 `2.0.0` 起与底层 [AgentScope-Java](https://github.com/agentscope-ai/agentscope-java) 保持同步；`1.0.0` 与 `3.x` 为早期独立版本线，其变更内容依然有效。

---

## [Unreleased]

### 新增

- **居民营养配餐助手（`resident-nutrition-assistant`）**：新增独立 Agent 定义，与既有校园餐助手 `nutrition-assistant` 按服务对象拆分，避免两套人群口径混在同一份配置里。
  - 人群口径：儿童 / 成人 / 老人 / 孕产 / 慢病，营养标准取《中国居民膳食营养素参考摄入量（2023 版）》DRIs。
  - 沿用 Supervisor 编排与同一套 MCP 工具（`search_dishes` / `evaluate_recipe` / database / formfill 等），提示词去除 schoolId、民族饮食禁忌、学校类型等校园专属约束。
  - 保留 `chat` 与 `recipe-make` 两个 Profile，`recipe-make` 的 `microRatings` 键名与前端渲染兼容。
  - 前端 `recipe-make.html` 的人群选项本就只有居民 5 类，调用目标由 `nutrition-assistant` 修正为 `resident-nutrition-assistant`。
- **评分引擎支持居民人群标准（`mcp-nutrition`）**：`scoring-rules.yml` 的 `standards` 追加居民 5 类人群的宏量标准（热量/蛋白质/脂肪/碳水），并新增 `micro-rda-resident` 段按人群差异化覆盖微量元素 RDA（老人钙 1000mg、孕产铁 24mg、乳母维 A 1300μgRE 等）。引擎新增 `resolveCrowdRda()`，按人群名命中则使用居民 RDA，未命中回退校园默认 `micro-rda`，校园评分逻辑不受影响。
  - 修复：此前页面传入居民人群（如"老人""孕产"）时 `findStandard` 匹配落空、回退到"教师/默认"标准，导致微量元素评分失真。
- **模型按 Agent 覆盖 / 轻量多租户**：Agent 定义 YAML 支持 `model.apiKey` / `model.baseUrl` / `model.stream` 覆盖，可为每个 Agent 配置独立模型账号，不配置时回退全局配置或环境变量。
- **LLM 调用 Usage 可观测性**：
  - 每次 LLM 调用输出 INFO 日志 `[LLM Usage]`（含输入/输出/缓存 token 与耗时）。
  - 上报 OpenTelemetry 指标 `llm.token.total`（区分 prompt/completion）与 `llm.duration`（耗时直方图），可通过 Prometheus 暴露。
  - `PageAgentService` 打印响应内容摘要（文本 / 推理 / 工具调用 / 数据块）。
  - 修复 OpenAI 兼容代理 `usage` 被硬编码为 0 的问题，现聚合真实 token 消耗。
- **权限模式全面透传 GA 原生枚举（弃兼容、拥抱底层框架）**：`PermissionConfig` 只保留 `build(hitl, PermissionMode)` 一种形态，把 GA 的 5 种模式（`DEFAULT` / `ACCEPT_EDITS` / `EXPLORE` / `BYPASS` / `DONT_ASK`）完整透传，已删除上一版的 `build(HITLConfig)` 单参兼容入口与 `PermissionRunMode` 封装枚举。yunxi 不封装、不裁剪，调用方（如 `AgentConfigurer`）直接决定要用的 GA 模式——HITL 配了需确认工具即透传 `DEFAULT`（被点名工具执行前挂起返回 `PERMISSION_ASKING`），未配则透传 `BYPASS`。yunxi 仅负责把 HITL（ToolGate / ReasoningReview）配置映射为 ASK 规则；`DONT_ASK` 下不注入 ASK 规则以规避 GA `checkAskRules` 不看 mode 的无人值守死锁；危险路径保护由 GA `ToolBase` / `ToolDangerousPathConstants` 在框架层自动强制 ASK（即便 `BYPASS` 也生效），yunxi 不重复实现。
- **意图引擎 M2 系列（M2.1-M2.4）**：在 M1 四阶段规则管道（NER → 改写 → 分类 → 映射）基础上补齐识别到路由的完整闭环：
  - **M2.1 意图路由**（默认关闭，渐进式上线）：`routeHint` 参与会话入口路由决策（advisory——目标 Agent 不存在或分数低于 `min-route-score` 时保持原路由不改道），新增 `routing.enabled` / `routing.min-route-score` 配置。
  - **M2.2 多域模型**：业务数据按领域隔离（`resolver.domains` + `resolver.rules`），`DomainResolver` 四层判定链（显式 domain → 规则匹配 → default → base 兜底）；旧顶层四文件保留为 `base` 域快捷方式，零迁移。
  - **M2.3 分类通道**：`classification.mode` 支持 `rule` / `llm` / `hybrid` 三模式，新增 `HybridIntentClassifier`（规则优先、LLM 兜底）与 `LlmIntentClassifier` + `LlmResultCache`（按 `domain|normalizedQuery|whitelistVersion` 缓存，TTL 7 天）；`llm.enabled=false` 时退化纯规则，规则兜底永不失效。
  - **M2.4 热更新**：actuator 端点 `/actuator/intent/status` / `/actuator/intent/reload` / `/actuator/intent/suggest-words`（LLM 热度建议词，按域过滤）；`IntentFilePoller` 支持 `file:` 前缀资源 mtime 轮询（默认关闭）；reload 全程审计日志，单域失败不阻断其他域。
  - 重构：`TreeSnapshot.build()` 统一两遍扫描工厂、`IntentKeywordMatcher` / `IntentYaml` 共享工具消除跨类重复、reload 监听器 SPI（`IntentReloadListener`）预留。
- **MCP 动态注册（运行时 REST API + Nacos 协调底座）**：支持运行期通过 REST API 动态注册/注销 MCP 服务器，无需重启即可将工具注入 Agent Toolkit。协调底座基于 Nacos 配置中心：目录条目持久化到 `dataId`（默认 `yunxi.mcp-servers.json`），并通过 Nacos Naming 在 `yunxi-mcp-coordinator` 下跨实例广播；Nacos 未启用时自动退化为本地内存目录。采用「内存权威目录 + Nacos 全量快照」机制避免并发读-改-写竞态；目标不可达时仅 WARN 降级并在首次调用时自动重连，不阻塞 HTTP 请求。接口与配置见 [09. API 参考 · MCP 动态注册](./docs/guide/09-api-reference.md) 与 [06. 配置指南 · MCP 动态注册配置](./docs/guide/06-configuration.md)。

### 变更

- 模型缓存策略文档化：单租户按 `modelId` 复用实例，多租户默认不复用，避免不同账号的 Key / BaseURL 串用。
- 意图引擎配置项扩展（`routing` / `classification` / `resolver` / `reload`），详见 [16. 意图引擎](./docs/guide/16-intent-engine.md)。

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

以下为各版本的内部重构与实现细节摘要：

- **[Unreleased]**：`ModelFactory` 由 1 参 `registerFactory` 改为 2 参 `ContextModelFactory`，各官方提供商统一经 `ModelCreationContext` 透传覆盖值；清理死代码 `registerModelWithOptions`、`resolveApiKey`（baidu/huawei 改走 `ModelRegistry.resolve` 后已无调用）；`baidu` / `huawei` 自定义 Provider 也在 `init()` 注册为 `ModelRegistry` 工厂，与内置 Provider 走完全一致的 `ModelRegistry.resolve` 路径（按 Agent 透传 `apiKey` / `options`）；新增 `BaiduCredential` / `HuaweiCredential`（继承框架 `CredentialBase`，闭合 `getChatModelClass()` 钩子，与内置 Provider 在 Credential 抽象层对齐；`listModels()` 沿用框架默认桩，前端模型发现须走 yunxi 自有目录）；新增 `LlmMetrics.recordAndLogUsage` 统一入口并落地于 `ChatAppService` / `PageAgentService`；`application.yml` 增加 `io.agentscope.core.model: DEBUG` 开关。
- **[2.0.0]**：删除 7 个自建 MCP 客户端类与 `DatabaseToolkit`，数据同步改直连 JDBC；新增 `PermissionConfig` / `ApplicationRAG`；删除 `ToolGateMiddleware` / `ReasoningReviewMiddleware` / `Knowledge*.java` / `Plan*.java` / `AgentInterruptService` 等屏蔽或重复类；场景检测链整体删除；`agentscope.version` 升至 `2.0.0`。
- **[3.5.0]**：`Session` → `DistributedStore` 迁移；`OpenTelemetryTracer` 删除改用全局 OTel；`MiddlewareBase` 签名新增 `RuntimeContext`；A2A 包名变更；6 个中间件文件签名更新。
- **[3.4.0]**：拆除自建 ChatModelProvider（约 500 行）与 CommandSafety（约 310 行）改复用框架；`AgentDomainService` 等缓存类型 `ChatModelProvider` → `Model`；新增 `model/` / `embedding/` 分包。
- **[3.3.1]**：`AgentConfigurer` 新增 `resolveAgentToolkit()` / `assignUngroupedTools()`；通过 `HarnessAgent.getDelegate().getToolkit()` 修正组激活。
- **[3.2.0]**：引入 `agentscope-harness`；删除 `MemoryCoordinatorService` / `AsyncConversationPersistenceService`；缓存类型 `Map<String, ReActAgent>` → `Map<String, Agent>`；移除反射获取 Toolkit。
