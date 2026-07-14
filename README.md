# yunxi Agent Platform

[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![AgentScope](https://img.shields.io/badge/AgentScope--Java-2.0.0-blueviolet)](https://github.com/agentscope-ai/agentscope-java)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-red)](https://maven.apache.org/)

**yunxi Agent Platform** 是一个企业级多 Agent 协作框架，基于 **AgentScope-Java 2.0.0（GA 正式版）** 核心运行时，提供开箱即用的 Agent 编排、MCP 协议集成、记忆系统等能力。

> **yunxi**（云曦），寓意 AI 平台像晨曦之光赋能万物。
>
> **版本策略**：yunxi Agent Platform 的版本号与底层 [AgentScope-Java](https://github.com/agentscope-ai/agentscope-java) 保持同步。当前版本 **2.0.0** 基于 AgentScope-Java 2.0.0 正式版（GA）构建。完整的版本变更记录见 [CHANGELOG](CHANGELOG.md)。

---

## 核心特性

| 特性 | 说明 |
|------|------|
| **多 Agent 编排** | Supervisor、Agent 路由、Pipeline 编排 |
| **AgentScope 深度集成** | 基于 AgentScope-Java 2.0.0 GA，复用 `Model`/`Toolkit`/`Middleware`/`DistributedStore` 体系 |
| **Spring Boot 原生** | `SmartLifecycle` 有序启停，Agent 实例 `prototype` 作用域，`@ConditionalOnClass` 按需加载 |
| **MCP 协议** | 完整支持 Model Context Protocol，30+ 内置 MCP 工具 |
| **记忆系统** | Harness 内置双层文件系统记忆，支持 Redis 跨实例共享 |
| **技能系统** | 启用 GA 原生 `AgentSkillRepository`（文件系统 + 项目级全局目录），由框架 `DynamicSkillMiddleware` 自动装载 |
| **流式事件** | 使用 `streamEvents()` 替代废弃的 `stream()`，按 `AgentEventType` 过滤事件 |
| **工具分组** | 按职责隔离工具（memory/filesystem/execute），默认最小权限，YAML 按需开放 |
| **提示注入防护** | ContentFilterMiddleware 基于框架 Middleware 接口，`onAgent` 拦截点拦截中英文注入模式 |
| **Shell 安全** | 复用框架 ShellCommandTool 白名单+平台验证器+审批回调，替代自建分级系统 |
| **Prompt Caching** | 配置 `cache-control: true` 即可启用，支持 OpenAI/Anthropic/DashScope |
| **SPI 扩展** | 基于 Java SPI 的插件化扩展机制 |
| **多通道** | WebSocket、SSE、飞书、钉钉、企业微信 |
| **Text2SQL** | 自然语言查询数据库 |
| **A2A 协议** | 跨服务 Agent 协作，分布式编排 |
| **可观测性** | OpenTelemetry 链路追踪，标准 Actuator 健康检查 |

---

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- Docker Desktop（推荐，用于一键启动基础设施）
- Ollama / OpenAI API（LLM 后端，可选一种即可）

### 第一步：一键启动基础设施

项目根目录的 `docker-compose.yml` 提供了所有依赖服务的一键启动能力。在 IDE 终端或 PowerShell 中执行：

```powershell
# 在项目根目录下执行（IDE 终端或 Windows PowerShell 均可）
docker compose up -d
```

这将启动以下服务（共 6 个容器）：

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL 8.0 | 3306 | 主数据库（会话/技能/配置持久化） |
| Redis 7 | 6379 | 缓存/分布式状态/会话 |
| Milvus Standalone | 19530 | 向量数据库（记忆/语义检索） |
| MinIO | 9000 | Milvus 对象存储（内部） |
| etcd | 2379 | Milvus 元数据协调（内部） |
| OTel Collector | 4318 | 链路追踪收集器（消除 "Failed to connect to 127.0.0.1:4318" 日志错误） |

首次启动约需 30-60 秒（主要等 Milvus 就绪）。可用 `docker compose ps` 确认所有容器状态。

Ollama 向量嵌入服务建议在宿主机安装（`ollama pull nomic-embed-text`），由应用通过 `localhost:11434` 调用。

**关闭服务**：`docker compose down`；**彻底重置**：`docker compose down -v && docker compose up -d`

### 第二步：启动应用

```bash
# 1. 克隆项目
git clone https://gitcode.com/chenyao813/yunxi-agent-platform.git
cd yunxi-agent-platform

# 2. 编译安装
mvn clean install -DskipTests

# 3. 启动核心服务
mvn spring-boot:run -pl agent-app

# 或使用 PowerShell 启动脚本（推荐）
.\启动项目.ps1 -Fast
```

启动后访问：http://127.0.0.1:40001/chat.html

### 发送第一条消息

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [{"role": "user", "content": "你好，请介绍一下自己"}]
  }'
```

---

## 模块概览

| 模块 | 说明 | 核心技术 |
|------|------|----------|
| **agent-core** | 核心框架：Agent 编排、会话管理、模型、记忆、技能、安全、网关（GA Channel 接入） | Spring Boot, agentscope-harness |
| **agent-text2sql** | 自然语言转 SQL | LLM, Milvus 向量检索 |
| **agent-spi** | SPI 接口定义 | Java SPI |
| **agent-config** | 统一配置：YAML、数据库初始化 | Spring Cloud |
| **agent-app** | 启动入口：整合所有模块 | Spring Boot |

---

## 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                  接入层 (Gateway)                         │
│   WebSocket · SSE · 飞书 · 钉钉 · 企业微信 · Web API      │
└───────────────────────┬─────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────┐
│               编排层 (Core + agentscope-harness)          │
│  Agent 编排 · 会话管理 · 路由 · 技能治理 · 工作空间        │
│   SmartLifecycle 启停 · prototype 作用域 · @Tool 注解     │
└───────┬──────────────┬───────────────────────────────┘
        │              │
┌───────▼──────┐ ┌────▼─────────────────┐
│   MCP 协议    │ │  AgentScope V2.0       │
│   30+ 工具   │ │  Model/Toolkit/Memory  │
│   SPI 扩展   │ │  Middleware/State      │
└───────┬──────┘ └────┬─────────────────┘
        │              │
┌───────▼──────────────▼──────────────▼─────────────────┐
│                基础设施 (Spring Boot)                   │
│   SmartLifecycle · Actuator · Micrometer · OTel       │
│   @ConditionalOnClass · ConfigurationProperties       │
│   Redis · MySQL · RocketMQ · Nacos                    │
└────────────────────────────────────────────────────────┘
```

---

## 详细文档

完整的用户指南位于 [`docs/guide/`](docs/guide/)，按学习路径组织：

| 章节 | 适合人群 |
|------|----------|
| [01. 介绍](docs/guide/01-introduction.md) | 所有读者 |
| [02. 快速开始](docs/guide/02-quickstart.md) | 开发者 |
| [03. 核心概念](docs/guide/03-concepts.md) | 所有读者 |
| [04. 架构设计](docs/guide/04-architecture.md) | 架构师、开发者 |
| [07. 开发指南](docs/guide/07-development.md) | 开发者 |
| [08. 部署指南](docs/guide/08-deployment.md) | 运维工程师 |

[📖 查看完整文档](docs/guide/README.md)

---

## 工作空间目录结构

yunxi 采用 **Agent 优先** 的目录布局，遵循底层 agentscope-java 框架约定，所有 Agent 工作空间汇聚在 `agents/` 子目录下。基础路径为 `.agentscope/workspace/`。

```
.agentscope/workspace/
├── AGENTS.md                 # 根级共享 Agent 人格（可选）
├── agents/                   # Agent 工作空间统一目录（框架官方约定）
│   ├── food-chat/            # Agent 工作空间（agents/{agentName}）
│   │   ├── AGENTS.md         # Agent 身份定义与场景规则
│   │   ├── knowledge/        # 知识文档
│   │   ├── memory/           # 记忆文件（YYYY-MM-DD.md + MEMORY.md）
│   │   ├── sessions/         # 原始对话日志（永不压缩）
│   │   ├── skills/           # Agent 专属技能
│   │   ├── subagents/        # 子智能体定义
│   │   └── users/            # 用户运行时数据（agents/{agentName}/users/{userId}/）
│   │       └── user-001/     # 用户隔离的工作空间
│   └── nutrition-assistant/  # 另一个 Agent
│       ├── AGENTS.md
│       ├── knowledge/
│       └── users/
│           └── user-001/
└── skills/                   # 全局共享技能（框架唯一识别，Agent 不可在此创建）
```

**设计原则**：
- **Agent 优先**：工作空间以 `agents/` 为统一入口，`users/` 嵌套在 Agent 下
- **用户隔离**：同一 Agent 的不同用户数据完全隔离在 `agents/{agentName}/users/{userId}/` 下
- **多租户运行时隔离**：由 GA 原生 `HarnessAgent.workspaceFor(userId, sessionId)` 在调用时按用户/会话命名空间路由到独立工作空间视图，无需自建扫描器（调用点：`ChatAppService`、`DesktopRelayHandler`）
- **模型级多租户（按需）**：Agent 定义 YAML 的 `model.apiKey` / `model.baseUrl` / `model.stream` 经框架 `ModelCreationContext` 透传，可为单个 Agent 指定独立 LLM 账号；不填则回退全局 `agentscope.core.*`（单租户默认，无需额外开关）
- **根级共享**：workspace 根目录下的 `AGENTS.md` 和 `skills/` 为全局共享资源
- API 路由使用 `compositeKey = agentName + "#" + userId` 定位用户专属 Agent 实例

---

## MCP 工具生态

yunxi 与 [yunxi-mcp-servers](https://gitcode.com/chenyao813/yunxi-mcp-servers) 配合使用，提供 40+ 即插即用的 MCP 工具：

| 类别 | 工具 |
|------|------|
| **数据库与存储** | MySQL、Redis、Milvus、Qdrant、MongoDB、Elasticsearch |
| **文件系统** | 文件读写、目录管理、搜索 |
| **外部服务** | GitHub、百度 OCR/ASR/搜索、钉钉、企微 |
| **AI 能力** | 浏览器自动化（Playwright）、图表生成、页面生成、知识库、记忆管理 |
| **文档处理** | PDF、Excel、PPTX |
| **基础设施** | Docker、K8s、Git、S3、MQTT、日志查询、系统监控 |
| **其他** | 邮件、Wikipedia、表单填写、营养配餐、API 网关 |

---

## 框架适配

本平台基于 **AgentScope-Java 2.0.0（GA 正式版）** 构建。在实际使用中，我们对底层框架的一些设计限制做了适配：

| 问题 | 根因 | 解决方案 | 文档 |
|------|------|---------|------|
| **工具组管理** | HarnessAgent 内置工具通过 `registerTool(Object)` 注册时不指定组名，已确认这是框架有意设计——内置工具属于 Agent 基础设施，不参与分组 | 应用层工具（Supervisor 子 Agent、MCP 工具）通过 `registration().group("name").apply()` 正确归组，受 YAML 配置管控 | [最佳实践 → 工具组管理](docs/guide/11-best-practices.md#工具组管理理解框架内置工具与应用层工具的分组边界) |
| **Toolkit 深拷贝后组激活失效** | `applyToolGroupActivation()` 操作原始 Toolkit，非 Agent 内部拷贝 | 通过 `HarnessAgent.getDelegate().getToolkit()` 获取内部 Toolkit | [最佳实践 → 底层框架适配](docs/guide/11-best-practices.md#底层框架适配) |
| **MCP 工具组隔离** | 框架 Toolkit 单例模式，所有工具注册在同一实例 | 按 MCP 服务器名分组 + YAML 配置组激活 | [最佳实践 → 底层框架适配](docs/guide/11-best-practices.md#底层框架适配) |
| ~~**自建 LLM Provider**~~ | ✅ **已修复** — 拆除 `ChatModelProvider` 接口，复用框架 `ModelRegistry` 工厂机制 | 通过 `ModelRegistry.registerFactory()` 注册 `ContextModelFactory` 两参工厂，按 Agent 透传 `apiKey`/`baseUrl`/`stream` | [配置 → Agent 模型/多租户](docs/guide/06-configuration.md#agent-模型配置按-agent-覆盖--多租户) |
| ~~**自建 Shell 安全**~~ | ✅ **已修复** — 拆除 `CommandSafetyClassifier`（~200 行），使用框架 `ShellCommandTool` | 白名单+平台验证器+审批回调，含多命令分隔符/路径穿越检测 | [配置 → Shell 安全](docs/guide/06-configuration.md#shell-命令安全配置) |
| ~~**Session 包删除**~~ | ✅ **已适配** — GA 删除 `io.agentscope.core.session` 包，替换为 `DistributedStore` | 改为注入 `DistributedStore`，通过 `RedisDistributedStore.fromJedis()` 创建 | [配置 → Session](docs/guide/06-configuration.md#session-持久化配置) |
| ~~**Tracer 弃用**~~ | ✅ **已适配** — GA 废弃 `Tracer`/`TracerRegistry`，改用 OpenTelemetry API | 移除 `OpenTelemetryTracer.java`，直接使用 `OpenTelemetry` 全局实例 | [可观测性](docs/guide/15-observability.md) |
| ~~**RAG 知识库 API 弃用**~~ | ✅ **已适配** — GA 中 `rag.Knowledge` 标记 `@Deprecated(forRemoval=true)`，官方建议在应用层集成检索 | 迁移到应用层 RAG（`ApplicationRAG`），经 `MiddlewareBase.onSystemPrompt` 注入检索上下文 | [配置 → 知识库](docs/guide/06-configuration.md#知识库rag配置) |

所有适配代码位于项目中，不修改框架源码，框架升级时通过 try-catch 保证容错回退。

---

## 开源路线

- [x] 重构包名为 `io.yunxi.*`，品牌升级为 **yunxi**
- [x] 完整重构 Agent 编排与记忆系统
- [x] 提供 Docker Compose 一键部署（`docker compose up -d`）
- [ ] 补充英文文档
- [ ] 发布 Maven Central
- [ ] 公开 MCP Server 市场

---

## 贡献

欢迎贡献代码、报告问题或改进文档！

- [贡献指南](CONTRIBUTING.md)
- [行为准则](CODE_OF_CONDUCT.md)
- [更新日志](CHANGELOG.md)

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

## 致谢

- [AgentScope](https://github.com/modelscope/agentscope) — 多 Agent 框架
- [Spring AI](https://spring.io/projects/spring-ai) — Spring AI 生态