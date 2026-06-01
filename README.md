# yunxi Agent Platform

[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-red)](https://maven.apache.org/)

**yunxi Agent Platform** 是一个企业级多 Agent 协作框架，基于 AgentScope-Java 核心运行时，提供开箱即用的 Agent 编排、规则引擎、MCP 协议集成、记忆系统等能力，帮助开发者快速构建智能 Agent 应用。

> **yunxi**（云曦），寓意 AI 平台像晨曦之光赋能万物。

---

## 核心特性

| 特性 | 说明 |
|------|------|
| **多 Agent 编排** | 支持 Supervisor 模式、Agent 路由、Pipeline 编排 |
| **AgentScope-Harness** | 集成 agentscope-harness 1.1.0-RC2，完整的会话管理、工作空间与记忆系统 |
| **规则引擎** | 内置轻量级规则引擎，支持 SpEL 表达式、动态规则加载 |
| **MCP 协议** | 完整支持 Model Context Protocol，30+ 内置 MCP 工具 |
| **记忆系统** | Harness 内置双层文件系统记忆，支持每日日志 + MEMORY.md 精选记忆 |
| **技能系统** | 可插拔技能架构，支持文件和技能仓库管理 |
| **SPI 扩展** | 基于 Java SPI 的插件化扩展机制 |
| **多通道** | 支持 WebSocket、SSE、飞书、钉钉、企业微信等通道 |
| **Text2SQL** | 内置 Text-to-SQL 能力，自然语言查询数据库 |
| **A2A 协议** | 跨服务 Agent 协作协议，支持分布式 Agent 编排 |
| **智能子系统** | 自适应策略、自我改进、智能降级与监控 |

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
| **agent-core** | 核心框架：Agent 编排、会话管理、记忆、技能 | Spring Boot, agentscope-harness |
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
│                 编排层 (Core + Harness)                   │
│   Agent 编排 · 会话管理 · 路由 · Pipeline · 技能 · 工作空间 │
└───────┬──────────────┬──────────────┬───────────────────┘
        │              │              │
┌───────▼───────┐ ┌────▼──────┐ ┌────▼─────────────────┐
│   规则引擎     │ │ MCP 协议  │ │  记忆系统 (Harness 内置) │
│   SpEL 规则   │ │ 40+ 工具  │ │  短期/长期/工作记忆    │
│   动态加载     │ │ SPI 扩展  │ │  反射式记忆           │
└───────────────┘ └───────────┘ └──────────────────────┘
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