# 02. 快速开始

本指南帮助你在 30 分钟内启动并运行 yunxi Agent Platform。

## 你将学到

- 理解 yunxi Agent Platform 与底层框架的关系
- 快速搭建开发环境
- 启动核心服务并发送第一个请求

## 技术栈关系

### 分层架构理论

**分层架构**是软件设计中最经典的架构模式之一：

```
┌─────────────────────────────────────────┐
│  第 4 层: 应用层                          │
│  - 业务逻辑                              │
│  - 用户界面                              │
├─────────────────────────────────────────┤
│  第 3 层: 框架层                          │
│  - 通用能力封装                          │
│  - 业务编排                              │
├─────────────────────────────────────────┤
│  第 2 层: 运行时层                        │
│  - Agent 执行引擎                        │
│  - 工具调用                              │
├─────────────────────────────────────────┤
│  第 1 层: 基础设施层                      │
│  - 存储、缓存、网络                       │
└─────────────────────────────────────────┘
```

**分层的好处**：
| 好处 | 说明 |
|------|------|
| **关注点分离** | 每层只关注自己的职责 |
| **易于替换** | 可以替换某层实现而不影响其他层 |
| **便于测试** | 可以独立测试每层 |
| **降低复杂度** | 将复杂系统分解为可管理的部分 |

### 本框架的技术栈层次

```
┌─────────────────────────────────────────────────────────────┐
│  基础设施层: Spring Boot / Redis / MySQL                      │
│  - Web 容器、缓存、数据库                                      │
├─────────────────────────────────────────────────────────────┤
│  模型层: DashScope / OpenAI / OLLAMA (本地)                   │
│  - 大语言模型 API                                             │
├─────────────────────────────────────────────────────────────┤
│  Harness 内置记忆: MEMORY.md + memory_search                  │
│  - 文件系统双层记忆                                            │
└─────────────────────────────────────────────────────────────┘
```

**关键理解**：
- yunxi Agent Platform 构建在 AgentScope-Java 之上
- 你编写的业务 Agent 最终由 yunxi 的 ChatAppService 编排执行
- 你不需要直接调用 AgentScope-Java 的 API，而是通过 yunxi 的领域模型和 SPI 机制扩展

---

## 环境准备

### 系统要求

- **JDK**: 17 或更高版本
- **Maven**: 3.8 或更高版本
- **Docker Desktop**：用于一键启动数据库、缓存、向量库等基础设施（推荐）
- **数据库**: MySQL 8.0+ 或 PostgreSQL 14+
- **缓存**: Redis 6.0+
- **向量数据库**: Milvus Standalone v2.3.3（知识库/语义检索）
- **操作系统**: Windows / Linux / macOS

### 为什么需要这些组件

| 组件 | 作用 | 替代方案 |
|------|------|----------|
| JDK 17 | Java 运行环境 | 不支持低于 17 |
| Maven | 项目构建 | Gradle |
| MySQL | 数据持久化 | PostgreSQL |
| Redis | 缓存、会话 | 内存模式（开发） |
| Milvus | 向量检索（知识库/语义匹配） | Qdrant |
| Ollama | 本地向量嵌入模型 | 云端 Embedding API |

### 一键启动基础设施

项目根目录提供了 `docker-compose.yml`，一键拉起所有依赖服务。在 IDE 终端或 Windows PowerShell 中执行：

```powershell
# 进入项目根目录
cd yunxi-agent-platform

# 启动所有基础设施（MySQL + Redis + Milvus + etcd + MinIO + OTel Collector + Jaeger + Attu）
docker compose up -d

# 确认所有容器就绪
docker compose ps
```

启动的容器清单：

| 容器名 | 镜像 | 端口 | 说明 |
|--------|------|------|------|
| yunxi-mysql | mysql:8.0 | 3306 | 主数据库 |
| yunxi-redis | redis:7-alpine | 6379 | 缓存与分布式状态 |
| yunxi-milvus | milvusdb/milvus:v2.3.3 | 19530 | 向量数据库 |
| yunxi-milvus-etcd | quay.io/coreos/etcd:v3.5.5 | 2379（内部） | Milvus 元数据协调 |
| yunxi-milvus-minio | minio/minio | 9000 | Milvus 对象存储 |
| yunxi-otel-collector | otel/opentelemetry-collector-contrib | 4318 | 链路追踪接收器 |
| yunxi-jaeger | jaegertracing/all-in-one | 16686（WebUI）/ 4317（gRPC） | 链路追踪可视化 |
| yunxi-attu | zilliz/attu:v2.3.3 | 8000 | Milvus Web 管理界面 |

首次启动约 30-60 秒。Ollama 向量嵌入需在宿主机单独安装：

```powershell
# 安装 Ollama 并拉取嵌入模型
ollama pull nomic-embed-text
```

关闭所有服务：

```powershell
docker compose down          # 停止但保留数据
docker compose down -v       # 停止并清除所有数据（彻底重置）
```

### 检查环境

```bash
# 检查 JDK
java -version

# 检查 Maven
mvn -version

# 检查 Docker 容器状态
docker compose ps
```

---

## 下载与构建

### 1. 克隆项目

```bash
git clone https://gitcode.com/chenyao813/yunxi-agent-platform.git
cd yunxi-agent-platform
```

### 2. 编译项目

```bash
# 编译所有模块
mvn clean install -DskipTests
```

编译完成后，各模块的 jar 包会生成在 `target/` 目录下。

**编译过程说明**：
```
源代码 → 编译 → 测试 → 打包 → 安装到本地仓库
   ↓       ↓      ↓      ↓          ↓
 .java   .class  JUnit  .jar    ~/.m2/repository
```

---

## 配置环境

### 1. 设置环境变量

创建 `.env` 文件或在系统中设置：

```bash
# 数据库配置
export MYSQL_HOST=localhost
export MYSQL_PORT=3306
export MYSQL_DATABASE=yunxi_agent_platform
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=your_password

# Redis 配置
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=your_password

# LLM API Key
export DASHSCOPE_API_KEY=your_api_key

# ============================================
# 可选：知识库（RAG）配置
# ============================================
# 启用知识库自动配置后，agentscope.yml 中配置的知识库将自动注册为 Bean
export AGENTSCOPE_AUTO_CONFIG=true

# 百炼知识库（阿里云 RAG）
export BAILIAN_ENABLED=false
export BAILIAN_ACCESS_KEY_ID=
export BAILIAN_ACCESS_KEY_SECRET=
export BAILIAN_WORKSPACE_ID=
export BAILIAN_INDEX_ID=

# Dify 知识库
export DIFY_ENABLED=false
export DIFY_API_KEY=
export DIFY_API_URL=
export DIFY_DATASET_ID=

# RAGFlow 知识库
export RAGFLOW_ENABLED=false
export RAGFLOW_API_KEY=
export RAGFLOW_API_URL=http://localhost:9380
export RAGFLOW_DATASET_ID=

# Simple 本地知识库（开发测试用）
export SIMPLE_KB_ENABLED=false

# ============================================
# 可选：RAG 检索默认参数
# ============================================
export RAG_DEFAULT_LIMIT=5
export RAG_DEFAULT_SCORE_THRESHOLD=0.5
```

**为什么使用环境变量**：
- 敏感信息不写入代码
- 不同环境不同配置
- 便于容器化部署

### 2. 初始化数据库

数据库表结构由 Spring Boot / agentscope-harness 框架自动管理（JPA/Hibernate DDL Auto），无需手动执行 SQL 脚本。

---

## 启动服务

### 启动顺序

yunxi Agent Platform 已整合为单体服务（agent-app），一键启动即可：

```bash
# 启动 agent-app（整合所有模块，端口 40001）
mvn spring-boot:run -pl agent-app
```

### 方式二：脚本启动（Windows PowerShell）

```powershell
# 推荐：使用统一启动脚本（自动打包并启动 agent-app，端口 40001）
.\启动项目.ps1

# 可选参数：
.\启动项目.ps1 -Fast     # 快速启动（已打包时使用，跳过打包步骤）
.\启动项目.ps1 -Maven    # Maven spring-boot:run 模式（支持热重载）
.\启动项目.ps1 -Clean    # 清理并重新打包启动
```

---

## 验证启动

### 1. 健康检查

```bash
# 检查核心服务
curl http://localhost:40001/actuator/health
```

### 2. 查看指标

```bash
# Prometheus 指标
curl http://localhost:40001/actuator/prometheus
```

---

## 第一个请求

### 通过 Web API 发送消息

```bash
curl -X POST http://localhost:40001/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user001",
    "message": "你好"
  }'
```

### 请求处理流程

```
你的请求
    │
    ▼
┌─────────────────────────────────────────┐
│ Agent App (端口 40001)                    │  ← yunxi 统一入口
│ - WebSocket · SSE · 飞书 · 钉钉 · 企业微信 │
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│ ChatAppService                            │  ← yunxi 编排层
│ - 场景路由、推理、工具调用                  │
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│ MCP Server (端口 40101+)                 │  ← MCP 工具层
│ - 数据库查询、文件操作等                  │
└─────────────────────────────────────────┘
```

**关键点**：
- 你的业务代码写在 yunxi 的 Agent 中（实现 DomainContributor 或 SceneContributor 接口）
- yunxi 负责路由、编排
- AgentScope-Java 负责实际的 LLM 交互和工具调用

---

## 下一步

- 了解 [核心概念](./03-concepts.md)
- 查看 [模块说明](./05-modules.md)
- 阅读 [配置指南](./06-configuration.md)

## 常见问题

**Q: 启动失败，提示端口被占用？**

A: 修改对应模块的 `application.yml` 中的 `server.port`。

**Q: 数据库连接失败？**

A: 检查环境变量是否正确设置，数据库服务是否启动。

**Q: Redis 连接失败？**

A: 检查 Redis 服务是否启动，密码是否正确。

---

**上一页**: [01. 介绍](./01-introduction.md)  
**下一页**: [03. 核心概念 →](./03-concepts.md)
