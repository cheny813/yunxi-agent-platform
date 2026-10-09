# yunxi Agent Platform

[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![AgentScope-Java](https://img.shields.io/badge/AgentScope--Java-2.0.4-blueviolet)](https://github.com/agentscope-ai/agentscope-java)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-red)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

**yunxi（云曦）—— 让多智能体像晨曦一样自然涌现、协同共生。**

yunxi Agent Platform 是基于 **AgentScope-Java 2.0.4（正式版）** 构建的企业级多智能体协作平台，以**意图引擎**为决策核心，开箱即用地提供多 Agent 编排、MCP 协议集成、记忆系统与技能自进化（MUSE）等能力。yunxi 站在经过生产验证的 AgentScope-Java 运行时之上，为企业级落地补齐「编排 · 决策 · 治理 · 可观测」能力，让开发者专注业务而非底层胶水代码。

## 版本策略

| 项 | 值 |
|------|------|
| `agentscope.version`（AgentScope-Java） | `2.0.4`（正式版） |
| `agent-aistio` | 随默认 reactor 一并构建的常规模块（无需 `-Paistio`） |

> 平台整体基于 AgentScope-Java `2.0.4`（正式版）构建，`agent-aistio` 随默认 reactor 一并构建，无需 `-Paistio`。`io.agentscope:*` 进入同一 fat jar 只保留一个版本，请勿单独把 `agent-aistio` 指向其他版本，否则 aistio↔core 边界会出现 `LinkageError`。

## 核心特性

| 特性 | 说明 |
|------|------|
| **意图引擎（决策中枢）** | NER → 改写 → 分类 → 映射四阶段前置管道；多域模型、rule/llm/hybrid 分类通道、actuator 热更新、意图路由，业务数据可配置替换 |
| **多 Agent 编排** | Supervisor 多专家编排（已接线）；`workflow` 为声明型（编排图导出至 aistio 执行，不产生运行时实例）；`pipeline`/`routing`/`handoffs` 未接线，加载期即被拒绝 |
| **统一执行引擎** | 独立模块 `agent-execution` 负责 web / A2A 自有协议的调用编排（意图识别、RAG、记忆构建、权限快照、实体注入、指标、阶段状态、审计），经框架 `Agent.streamEvents` 接缝衔接；协议无关的横切（内容安全 / 画像 / 追踪 / 记忆压缩）实现为 AgentScope-Java Middleware 对所有协议统一生效，详见 [19. 统一执行引擎](docs/guide/19-execution-engine.md) |
| **统一推理轨迹（One Trace, Many Projections）** | 内核只维护一条结构化轨迹（`TraceComposer` → `ReasoningSpan` → `TraceStore`），SSE / AG-UI 只是它的投影；轨迹可落 Redis 跨实例共享，支持历史回放与断点续传 |
| **AgentScope 深度集成** | 复用 AgentScope-Java 的 `Model`/`Toolkit`/`Middleware`/`DistributedStore` 体系 |
| **MCP 协议** | 完整支持 Model Context Protocol，内置 9 个 MCP 服务定义（sse/stdio/http），其中 database/redis/milvus/mcp-nutrition/formfill 共 5 个默认启用；支持运行期动态注册（Nacos 持久化、目标不可达自动重连），一键接入 30+ MCP 服务生态 |
| **记忆系统** | Harness 内置双层文件系统记忆，支持 Redis 跨实例共享 |
| **技能系统** | 复用 AgentScope-Java 原生 `AgentSkillRepository`（文件系统 + 项目级全局目录），由框架 `DynamicSkillMiddleware` 自动装载；默认关闭，需将 `agentscope.core.skill.enabled` 设为 `true` |
| **技能自进化（MUSE）** | 沙箱评估 → LLM 修补 → 剪枝合并闭环；剪枝判据含**使用者广度**维度（只读用户画像，区分「多人各用一次」与「单人高频」，避免误杀小众领域核心技能） |
| **流式事件** | `Model.stream()` 与 `Agent.streamEvents()` 双通道，均按 `AgentEventType` 过滤 |
| **任务清单（TodoList）** | Agent 维护结构化步骤清单（x/y 进度经 `todo_update` 事件实时同步），状态随会话持久化、可断点续跑，复用原生 `todo_write` 工具 |
| **人机确认（HITL）** | 危险工具执行前经 `REQUIRE_USER_CONFIRM` 事件请求确认，未配置 HITL 的 Agent 则直接降级为拒绝 |
| **安全与可观测** | ContentFilterMiddleware 提示注入防护、ShellCommandTool 白名单 + 验证器 + 审批回调、OpenTelemetry 全链路追踪、标准 Actuator 健康检查 |
| **多通道** | WebSocket、SSE、飞书、钉钉、企业微信 |
| **Text2SQL / A2A** | 自然语言查询数据库；跨服务 Agent 协作，分布式编排 |
| **Spring Boot 原生** | `SmartLifecycle` 有序启停，`prototype` 作用域，`@ConditionalOnClass` 按需加载，SPI 插件化扩展 |

## 快速开始

内置 MCP 服务来自独立的 [`yunxi-mcp-servers`](https://gitcode.com/chenyao813/yunxi-mcp-servers) 仓库，需与本项目放在**同级目录**（脚本默认从此推导路径）：

- [`yunxi-mcp-servers`](https://gitcode.com/chenyao813/yunxi-mcp-servers) — 5 个内置 MCP 服务（Java 进程），由 `scripts/start-stack.ps1` 拉起
- aistio 管控面以 **AgentScope-Java 官方 Docker 镜像**提供（见下文「启用 aistio 管控面」），无需克隆任何源码仓

### 环境要求

JDK 17+ · Maven 3.8+ · Docker Desktop（基础设施与 aistio）· Ollama / OpenAI API（LLM 后端，可选一种）

### 第一步：获取 yunxi

```bash
git clone https://gitcode.com/chenyao813/yunxi-agent-platform.git
cd yunxi-agent-platform
git clone https://gitcode.com/chenyao813/yunxi-mcp-servers.git ../yunxi-mcp-servers
```

### 第二步：启动基础设施

```powershell
docker compose up -d
```

拉起 9 个容器：MySQL 8.0（3306）、Redis 7（6379）、Milvus Standalone（19530）、MinIO（9000/9001）、etcd（2379）、Nacos 3.2.4（8848/8080/9848）、OTel Collector（4317/4318）、Jaeger UI（16686）、Attu UI（8000）。首次启动约需 30–60 秒，用 `docker compose ps` 确认状态。

### 第三步：启动 MCP 服务（与可选 aistio）

```powershell
.\scripts\start-stack.ps1 -BuildMcp    # 首次：自动构建缺失的 MCP jar 并拉起
.\scripts\start-stack.ps1              # 后续：跳过已在运行的，仅补齐缺失的
```

5 个 MCP 服务来自 `yunxi-mcp-servers`，由 `agent-config/.../config/mcp-core.yml` 统一配置。脚本同时检查 aistio 控制面（未运行则一并拉起），幂等可重复运行。若暂不接 MCP，可在 `mcp-core.yml` 中将各服务 `enabled` 改为 `false`，平台仍可正常启动。

### 第四步：配置大模型（必须）

平台运行**必须接入一个大模型后端**。默认使用阿里云 DashScope（`qwen-plus`），密钥经环境变量 `DASHSCOPE_API_KEY` 注入（仓库不含任何密钥）：

```powershell
$env:DASHSCOPE_API_KEY = "sk-你的真实Key"
.\启动项目.ps1
```

切换其它厂商（OpenAI / 百度 / 华为）：设置对应 `*_API_KEY`，并将 `agentscope.core.provider` 设为 `openai` / `baidu` / `huawei`。

### 第五步：启动 yunxi 应用

```powershell
.\启动项目.ps1          # 推荐：首次自动 mvn 编译并启动（端口 40001）
# .\启动项目.ps1 -Fast   # 已编译过则跳过打包直接启动
# .\启动项目.ps1 -Web    # 同时拉起前端 dev server（http://localhost:5173/）
```

启动成功日志末行打印 `Started AgentPlatformApplication`；健康检查 `http://localhost:40001/actuator/health` 返回 `{"status":"UP"}`。访问 http://127.0.0.1:40001/chat.html 即可对话。

### 发送第一条消息

```bash
curl -X POST http://localhost:40001/api/conversations/chat \
  -H "Content-Type: application/json" \
  -d '{"agentName":"dish-searcher","message":"你好，请介绍一下自己","mode":"sync"}'
```

`dish-searcher` 是内置示例 Agent，随平台默认加载。平台内置 8 个 Agent 定义（6 个可对话示例 + 测试/导出示例各 1），其中 dish-searcher / recipe-composer / nutrition-evaluator 标记 `hidden`，不出现在 UI 下拉但仍可直接调用。

### 体验前端示例（agent-nutritionist-web，可选）

`agent-nutritionist-web` 是基于 Vite 的**独立前端工程**（后端 JAR 不含其页面），监听 `http://localhost:5173`。它有三种启动方式：

1. **随后端一并拉起**（推荐）：`启动项目.ps1 -Web`，自动调用 `agent-nutritionist-web/start-web.ps1`，并把 vite 日志统一收口到仓库根 `logs/nutritionist-web/`（避免散落日志）。
2. **单独手动启动**：
   ```bash
   cd agent-nutritionist-web
   npm install
   npm run dev        # http://localhost:5173
   ```
3. **仅拉起前端 dev server**（不启后端，等价于方式 2 但走日志收口脚本）：`PowerShell -File agent-nutritionist-web/start-web.ps1`。

**前置条件**：前端开发服务器通过 Vite 代理把 `/api`、`/v1`、`/js` 转发到后端（默认 `localhost:40001`），因此**后端（`agent-core`，端口 40001）必须先启动**，否则页面能打开但对话/配置请求会失败。可用 `BACKEND_HOST` / `BACKEND_PORT` 环境变量覆盖后端地址。

**访问注意**：用 `http`（不是 `https`）访问 `http://localhost:5173`；不加 `-Web` 时 5173 连不上是预期，不是后端故障。MCP 服务或 aistio 未就绪**不会影响页面渲染**，只会影响聊天中实际调用到的工具（如营养评分依赖 `mcp-nutrition`）。详细的页面说明见 [`agent-nutritionist-web/README.md`](agent-nutritionist-web/README.md)。

## 启用 aistio 管控面

aistio 管控面提供 Web 控制台、Agent 注册接管与编排图执行能力，依赖 AgentScope-Java 2.0.4，作为平台常规模块随默认构建一并纳入。

**1. 构建**：`agent-aistio` 已随默认 reactor 一并构建，无需 `-Paistio`；直接 `mvn -pl agent-app -am clean install -DskipTests` 即可把 aistio 管控面对接（控制面注册 / gRPC 对话流 / 编排触发器 / 契约与治理 / 任务接活）一并打进产物。

**2. 接入 aistio 控制面**：aistio 以 AgentScope-Java 官方发布版本提供，首次启用前先运行启动脚本拉起控制面：

```powershell
.\scripts\setup-aistio.ps1                 # 拉取官方 aistio 镜像并启动控制面
# .\scripts\setup-aistio.ps1 -AistioVersion 2.0.4   # 指定镜像版本（须与 agent-aistio 客户端 API 一致）
```

> 重要：aistio 以 AgentScope-Java 官方 Docker 镜像提供，镜像坐标由 `scripts/setup-aistio.ps1` 的 `$AistioRegistry` / `$AistioVersion` 指定（默认仓库 `sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope`、版本 `2.1.0-BETA1`）。镜像版本须与 yunxi `agent-aistio` 客户端协商的 API 版本一致（当前客户端按 `2.0.4` 构建，若官方仓库存在该 tag，请改用 `.\scripts\setup-aistio.ps1 -AistioVersion 2.0.4` 对齐；否则需先升级 `agent-aistio` 客户端到 `2.1.0-BETA1`）。`setup-aistio.ps1` 默认会生成生产密钥到仓库根 `.env.aistio`（git 忽略、仅当前用户可读写），`-Dev` 可跳过生成回退内置开发默认值。

**3. 开启集成**：将 `agent-config/src/main/resources/application.yml` 的 `yunxi.aistio` 段（默认 `enabled: false`）改为 `true`，重启 yunxi：

```yaml
yunxi:
  aistio:
    enabled: true
    control-plane-url: http://localhost:8081
    internal-token: compose-local-internal-token-at-least-32chars
    base-url: http://localhost:40001
```

重启后 Web 控制台由 aistio 提供（http://localhost:8081），yunxi 经控制面 http://localhost:8081 注册并接管 Agent。控制台种子用户 `admin`/`admin` 首次启动自动注入。**生产环境务必替换默认 `internal-token`、控制台弱口令与数据库弱口令。**

> 若启动 yunxi 时 MCP 服务尚未就绪，平台会记连接告警但**不会崩溃**，对应工具暂时不可用；`start-stack.ps1` 拉起服务后调用工具会自动重连恢复，无需重启。

## 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│                       接入层 (Gateway)                       │
│      WebSocket · SSE · 飞书 · 钉钉 · 企业微信 · Web API      │
└───────────────────────────────▼──────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│               编排层 (agent-core + agentscope-harness)        │
│Agent 编排 · 会话管理 · 路由 · 意图引擎 · 技能治理 · 工作空间   │
│统一执行引擎 · 能力装配 (Capability)                          │
└───────────────────────────────▼──────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                统一推理轨迹 (Trace) → 投影 (Projection)       │
│ReasoningSpan · TraceComposer · TraceStore (内存 / Redis)     │
│         ├── SseProjection  → SSE 事件流                       │
│         └── AguiProjection → AG-UI 协议事件                   │
└───────────────────────────────▼──────────────────────────────┘

┌────────────────────────────────┐   ┌──────────────────────────────┐
│            MCP 协议            │   │   AgentScope-Java 2.0.4      │
│30+ 工具 · SPI 扩展             │   │   Model / Toolkit / Memory   │
│运行期动态注册 (Nacos)          │   │   Middleware / State         │
└────────────────────────────────┘   └──────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                    基础设施 (Spring Boot)                    │
│SmartLifecycle · Actuator · Micrometer · OTel · Redis ·       │
│MySQL · Milvus · Nacos · RocketMQ (可选，国产化环境)         │
└──────────────────────────────────────────────────────────────┘
```

## 模块概览

| 模块 | 说明 | 核心技术 |
|------|------|----------|
| **agent-codec** | 全平台唯一 JSON 编解码机制：唯一绑定配置来源、Redis 值编解码、数据库 JSON 类型处理器、失败计数与启动自检 | Jackson, Spring Data Redis, MyBatis |
| **agent-config** | 统一配置：YAML、数据库初始化 | Spring Cloud |
| **agent-spi** | SPI 接口定义（契约层，最底层抽象） | Java SPI |
| **agent-text2sql** | 自然语言转 SQL | LLM, Milvus 向量检索 |
| **agent-core** | 核心框架：Agent 编排、意图引擎、推理轨迹、会话、模型、记忆、技能、安全 | Spring Boot, agentscope-harness |
| **agent-execution** | 统一执行引擎：web / A2A 自有协议的调用编排（意图、RAG、记忆构建、权限快照、实体注入、指标、阶段、审计） | AgentScope-Java Middleware, ExecutionInterceptor |
| **agent-ingress** | 统一接入网关：集中承载所有外部 HTTP 入口与边缘横切（认证 / 限流 / 可观测）、协议服务端接入与路由 | Spring Boot Web, AgentScope-Java 协议 Starter |
| **agent-muse** | 自进化引擎：技能沙箱评估 → LLM 修补 → 剪枝合并闭环 | agentscope, Java 子进程沙箱 |
| **agent-aistio** | aistio 管控面集成（注册、gRPC 对话流、编排触发器、契约与治理、任务接活），尝鲜模式可选 | AgentScope-Java aistio 扩展 |
| **agent-app** | 启动入口：整合所有模块（端口 40001） | Spring Boot |
| **agent-integration-test** | 集成测试 | JUnit, Testcontainers |
| **sdk-js** | JavaScript/TypeScript SDK | TypeScript, Node.js |
| **agent-web-sdk** | 浏览器端 JS SDK（WebSocket 接入） | JavaScript, WebSocket |
| **agent-nutritionist-web** | 营养师助手前端演示 | 静态 HTML + JS |

## 详细文档

完整的用户指南位于 [`docs/guide/`](docs/guide/)，按学习路径组织：

| 章节 | 适合人群 |
|------|----------|
| [01. 介绍](docs/guide/01-introduction.md) | 所有读者 |
| [02. 快速开始](docs/guide/02-quickstart.md) | 开发者 |
| [03. 核心概念](docs/guide/03-concepts.md) | 所有读者 |
| [04. 架构设计](docs/guide/04-architecture.md) | 架构师、开发者 |
| [05. 模块说明](docs/guide/05-modules.md) | 架构师、开发者 |
| [06. 配置指南](docs/guide/06-configuration.md) | 运维工程师、开发者 |
| [07. 开发指南](docs/guide/07-development.md) | 开发者 |
| [08. 部署指南](docs/guide/08-deployment.md) | 运维工程师 |
| [09. API 参考](docs/guide/09-api-reference.md) | 开发者 |
| [10. 技能系统](docs/guide/10-skills.md) | 开发者 |
| [11. 最佳实践](docs/guide/11-best-practices.md) | 架构师、开发者 |
| [12. FAQ](docs/guide/12-faq.md) | 所有读者 |
| [13. 智能系统](docs/guide/13-intelligent-system.md) | 架构师、开发者 |
| [14. A2A 协议](docs/guide/14-a2a-protocol.md) | 架构师 |
| [15. 可观测性](docs/guide/15-observability.md) | 运维工程师 |
| [16. 意图引擎](docs/guide/16-intent-engine.md) | 架构师、开发者 |
| [17. 统一 JSON 编解码机制](docs/guide/17-data-codec.md) | 架构师、开发者 |
| [18. 统一入口网关](docs/guide/18-ingress-gateway.md) | 架构师、开发者 |
| [19. 统一执行引擎](docs/guide/19-execution-engine.md) | 架构师、开发者 |

[📖 查看完整文档](docs/guide/README.md)

## 工作空间目录结构

yunxi 采用 **Agent 优先** 的目录布局，遵循 AgentScope-Java 框架约定，所有 Agent 工作空间汇聚在 `.agentscope/workspace/`：

```
.agentscope/workspace/
├── agents/                   # Agent 工作空间统一目录（框架官方约定）
│   ├── dish-searcher/        # 菜品搜索助手
│   │   ├── agents/           # 子智能体定义
│   │   ├── AGENTS.md         # Agent 身份定义与场景规则
│   │   └── user-001/         # 用户运行时数据（按 userId 隔离）
│   ├── resident-nutrition-assistant/
│   ├── nutrition-evaluator/
│   ├── pagegen-assistant/
│   ├── recipe-composer/
│   └── safety-assistant/
└── skills/                   # 全局共享技能（24 个技能目录）
```

用户运行时数据按 `{userId}/` 子目录隔离（由 `HarnessAgent.workspaceFor(userId, sessionId)` 路由，无需自建扫描器）；`skills/` 为框架唯一识别路径，随仓库附 24 个内置技能，MUSE 自进化引擎会将其 classpath 内 `builtin-skills/` 落地到此目录，共用同一标准格式。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

## 致谢

- [AgentScope-Java](https://github.com/agentscope-ai/agentscope-java) — 多 Agent 框架（yunxi 的底层运行时）
- [Spring AI](https://spring.io/projects/spring-ai) — Spring AI 生态
