# yunxi Agent Platform

[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-red)](https://maven.apache.org/)

**yunxi Agent Platform** 是一个企业级多 Agent 协作框架，基于 **AgentScope V2.0**（2.0.0-RC3）核心运行时，提供开箱即用的 Agent 编排、规则引擎、MCP 协议集成、记忆系统等能力。

> **yunxi**（云曦），寓意 AI 平台像晨曦之光赋能万物。
>
> **V2.0-RC3 升级说明**：本平台已完成从 AgentScope V1.1-RC2 → V2.0.0-RC1 → V2.0.0-RC3 的大版本升级。核心变更包括：Hook → Middleware 迁移、`Session` → `DistributedStore` 替换、`Tracer` → OpenTelemetry 直连 API、`stream()` → `streamEvents()`、`ModelRegistry` 统一模型创建、包结构扁平化重构。

---

## 核心特性

| 特性 | 说明 |
|------|------|
| **多 Agent 编排** | Supervisor、Agent 路由、Pipeline 编排 |
| **AgentScope 深度集成** | 基于 AgentScope V2.0-RC3，复用 `Model`/`Toolkit`/`Middleware`/`DistributedStore` 体系 |
| **Spring Boot 原生** | `SmartLifecycle` 有序启停，Agent 实例 `prototype` 作用域，`@ConditionalOnClass` 按需加载 |
| **规则引擎** | 内置轻量级规则引擎，支持 SpEL 表达式、动态规则加载 |
| **MCP 协议** | 完整支持 Model Context Protocol，30+ 内置 MCP 工具 |
| **记忆系统** | Harness 内置双层文件系统记忆，支持 Redis 跨实例共享 |
| **技能系统** | V2.0 内置 `SkillCurator` 治理流水线（原 SkillBox 已移除） |
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
- MySQL 8.0+（可选）
- Redis 6.0+（可选）
- Ollama / OpenAI API（LLM 后端，可选一种即可）

### 启动应用

```bash
# 1. 克隆项目
git clone https://gitcode.com/chenyao813/yunxi-agent-platform.git
cd yunxi-agent-platform

# 2. 编译安装
mvn clean install -DskipTests

# 3. 启动核心服务
mvn spring-boot:run -pl agent-app
```

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
| **agent-core** | 核心框架：Agent 编排、会话管理、模型、记忆、技能、安全 | Spring Boot, agentscope-harness |
| **agent-gateway** | 网关：通道管理、流控、认证 | WebSocket, SSE |
| **agent-rule-engine** | 规则引擎：动态规则、SpEL 评估 | Spring SpEL |
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
└───────┬──────────────┬──────────────┬───────────────────┘
        │              │              │
┌───────▼───────┐ ┌────▼──────┐ ┌────▼─────────────────┐
│   规则引擎     │ │ MCP 协议  │ │  AgentScope V2.0       │
│   SpEL 规则   │ │ 30+ 工具  │ │  Model/Toolkit/Memory  │
│   动态加载     │ │ SPI 扩展  │ │  Middleware/State      │
└───────┬───────┘ └────┬──────┘ └────┬─────────────────┘
        │              │              │
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

本平台基于 **AgentScope-Java**（阿里巴巴开源，V2.0.0-RC3 版本）构建。在实际使用中，我们对底层框架的一些设计限制做了适配：

| 问题 | 根因 | 解决方案 | 文档 |
|------|------|---------|------|
| **工具组分配（ungrouped）** | HarnessAgent 内置工具注册时不指定组名 | 反射调用 `ToolGroupManager.addToolToGroup()` 在构建后修正 | [最佳实践 → 底层框架适配](docs/guide/11-best-practices.md#底层框架适配) |
| **Toolkit 深拷贝后组激活失效** | `applyToolGroupActivation()` 操作原始 Toolkit，非 Agent 内部拷贝 | 通过 `HarnessAgent.getDelegate().getToolkit()` 获取内部 Toolkit | [最佳实践 → 底层框架适配](docs/guide/11-best-practices.md#底层框架适配) |
| **MCP 工具组隔离** | 框架 Toolkit 单例模式，所有工具注册在同一实例 | 按 MCP 服务器名分组 + YAML 配置组激活 | [最佳实践 → 底层框架适配](docs/guide/11-best-practices.md#底层框架适配) |
| ~~**自建 LLM Provider**~~ | ✅ **已修复** — 拆除 `ChatModelProvider` 接口，复用框架 `ModelRegistry` 工厂机制 | 通过 `ModelRegistry.registerFactory()` 注册自定义工厂 | [配置 → 生成参数](docs/guide/06-configuration.md#生成参数配置) |
| ~~**自建 Shell 安全**~~ | ✅ **已修复** — 拆除 `CommandSafetyClassifier`（~200 行），使用框架 `ShellCommandTool` | 白名单+平台验证器+审批回调，含多命令分隔符/路径穿越检测 | [配置 → Shell 安全](docs/guide/06-configuration.md#shell-命令安全配置) |
| ~~**Session 包删除**~~ | ✅ **已适配** — RC3 删除 `io.agentscope.core.session` 包，替换为 `DistributedStore` | 改为注入 `DistributedStore`，通过 `RedisDistributedStore.fromJedis()` 创建 | [配置 → Session](docs/guide/06-configuration.md#session-持久化配置) |
| ~~**Tracer 弃用**~~ | ✅ **已适配** — RC3 废弃 `Tracer`/`TracerRegistry`，改用 OpenTelemetry API | 移除 `OpenTelemetryTracer.java`，直接使用 `OpenTelemetry` 全局实例 | [可观测性](docs/guide/15-observability.md) |
| **RAG 知识库 API 弃用** | AgentScope 2.0.0-RC3 中 `Knowledge` 标记 `@Deprecated(forRemoval=true)`，新 RAG 模块延后到后续版本 | 8 个文件添加 `@SuppressWarnings("removal")` + `TODO: AgentScope 2.0` 迁移标记 | [配置 → 知识库](docs/guide/06-configuration.md#知识库rag配置) |

所有适配代码位于项目中，不修改框架源码，框架升级时通过 try-catch 保证容错回退。

---

## 开源路线

- [x] 重构包名为 `io.yunxi.*`，品牌升级为 **yunxi**
- [x] 完整重构 Agent 编排与记忆系统
- [ ] 补充英文文档
- [ ] 发布 Maven Central
- [ ] 提供 Docker Compose 一键部署
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