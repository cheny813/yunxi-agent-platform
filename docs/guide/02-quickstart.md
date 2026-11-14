# 02. 快速开始

本指南帮助你在 30 分钟内启动并运行 yunxi Agent Platform。

## 你将学到

- 理解 yunxi Agent Platform 与底层框架的关系
- 快速搭建开发环境
- 启动核心服务并发送第一个请求

## 技术栈关系

### 分层架构理论

**分层与职责**：yunxi 的分层边界、各平面职责与运行时关系见 [04. 架构总览](./04-architecture.md) 与 [05. 模块说明](./05-modules.md)；下方仅列出本平台实际依赖的技术栈层次。

### 本框架的技术栈层次

```
┌─────────────────────────────────────────────────────────────┐
│  基础设施层: Spring Boot / Redis /（可选）MySQL                 │
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
- **数据库（可选）**: 仅当运行 `mcp-nutrition` MCP 服务时需要 MySQL 8.0+（平台运行时数据走 Redis，无需数据库）
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

基础设施编排位于 `deploy/infra/docker-compose.yml`，一键拉起所有依赖服务。在 Windows PowerShell 中执行：

```powershell
# 进入项目根目录
cd yunxi-agent-platform

# 启动所有基础设施（MySQL + Redis + etcd + MinIO + Milvus + Nacos + OTel Collector + Jaeger + Attu，共 9 个）
docker compose -f deploy/infra/docker-compose.yml up -d

# 确认所有容器就绪
docker compose -f deploy/infra/docker-compose.yml ps
```

启动的容器清单（共 9 个）：

| 容器名 | 镜像 | 端口 | 说明 |
|--------|------|------|------|
| yunxi-mysql | mysql:8.0 | 3306 | 主数据库 |
| yunxi-redis | redis:7-alpine | 6379 | 缓存与分布式状态 |
| yunxi-milvus | milvusdb/milvus:v2.3.3 | 19530 | 向量数据库 |
| yunxi-milvus-etcd | quay.io/coreos/etcd:v3.5.5 | 2379（内部） | Milvus 元数据协调 |
| yunxi-milvus-minio | minio/minio | 9000 | Milvus 对象存储 |
| yunxi-nacos | nacos/nacos-server:v3.2.4 | 8848 / 8080 / 9848 | MCP 注册中心 + 配置热更新 + AI Registry |
| yunxi-otel-collector | otel/opentelemetry-collector-contrib | 4318 | 链路追踪接收器 |
| yunxi-jaeger | jaegertracing/all-in-one | 16686（WebUI）/ 4317（gRPC） | 链路追踪可视化 |
| yunxi-attu | zilliz/attu:v2.3.3 | 8000 | Milvus Web 管理界面 |

> **可选：启用 AgentScope-Service（aistio）管控面**。需要 Agent 管控 / 治理可视化 / 会话干预 / 任务接活能力时，单独拉起控制面（不依赖本平台基础设施）：
> ```powershell
> cd deploy/aistio
> docker compose up -d
> ```
> aistio 以 AgentScope-Java 官方 Docker 镜像提供（仓库 `sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope`，平面 `as-controlplane` / `as-dataplane` / `as-gateway` / `as-scheduler`），默认版本 `2.1.0-BETA1`（须与 `yunxi-fabric` 客户端 API 版本一致）。编排脚本与说明见 `deploy/aistio/`（详见 [08. 部署指南 · aistio 控制面本地编排](./08-deployment.md#aistio) 与 [配置指南](./06-configuration.md#aistio)）。该编排包含 5 个容器，见下表：

| 容器名 | 镜像/构建 | 端口 | 说明 |
|--------|----------|------|------|
| aistio-db | postgres:17（官方镜像） | 不映射（仅容器内网） | aistio 元数据库（builder/builder） |
| aistio-control | as-controlplane（官方镜像） | 18081（对外） | 控制面（yunxi `yunxi.aistio.control-plane-url=http://localhost:18081`） |
| aistio-data | as-dataplane（官方镜像） | 18082（对外） | 数据面 |
| aistio-scheduler | as-scheduler（官方镜像） | 18083（对外） | 调度面 |
| aistio-gateway | as-gateway（官方镜像） | 18090（对外） | 网关（对外发布到主机 18090，避开常见 8080 占用） |

首次启动约 30-60 秒。Ollama 向量嵌入需在宿主机单独安装：

```powershell
# 安装 Ollama 并拉取嵌入模型
ollama pull bge-m3
```

关闭所有服务：

```powershell
docker compose -f deploy/infra/docker-compose.yml down          # 停止但保留数据
docker compose -f deploy/infra/docker-compose.yml down -v       # 停止并清除所有数据（彻底重置）
```

> **`docker compose up -d` 不含 MCP 服务**：它们来自配套项目 [`yunxi-mcp-servers`](https://gitcode.com/chenyao813/yunxi-mcp-servers)，是独立 Java 进程。平台在 `yunxi-config/src/main/resources/config/mcp.yml` 按名声明，当前仅声明两个且均默认关闭：`nutrition-mcp`（端口 40602）与 `text2sql-mcp`（端口 40110）。需要对应工具时，先按 [yunxi-mcp-servers 仓库说明](https://gitcode.com/chenyao813/yunxi-mcp-servers) 拉起 Java 进程，再把该条目改为 `enabled: true`（详见 [仓库 README · MCP 服务（可选）](../../README.md)）；不启用时平台正常启动，仅相关工具不可用。
>
> **关闭这些服务**：MCP 为独立 Java 进程，按需停止对应进程即可（aistio 控制面不在此范围内，需在 `deploy/aistio` 单独用 `docker compose down` 关闭）；aistio 日志用 `cd deploy/aistio && docker compose logs -f`。详见 [仓库 README · MCP 服务（可选）](../../README.md)。

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
# 可选：向量数据库（Milvus）配置
# 启用 RAG（知识库检索）时，需要可访问的 Milvus 服务
# ============================================
export MILVUS_HOST=localhost
export MILVUS_PORT=19530
export MILVUS_DATABASE=default
# Milvus 启用认证时（docker-compose 默认未启用，可不配置）
export MILVUS_USERNAME=root
export MILVUS_PASSWORD=root
export MILVUS_TOKEN=

# ============================================
# 可选：向量嵌入模型（知识库/语义检索需要）
# 提供商：dashscope / ollama / openai / baidu / huawei
# ============================================
# 默认使用本地 Ollama（推荐开发环境，无 API 费用）
export EMBEDDING_PROVIDER=ollama
export OLLAMA_BASE_URL=http://localhost:11434
export OLLAMA_EMBEDDING_MODEL=bge-m3

# 若使用云端嵌入（如阿里云百炼 text-embedding-v3），改用：
# export EMBEDDING_PROVIDER=dashscope
# export MILVUS_EMBEDDING_MODEL=text-embedding-v3
```

**为什么使用环境变量**：
- 敏感信息不写入代码
- 不同环境不同配置
- 便于容器化部署

### 2. 初始化数据库（仅可选 MCP 服务需要）

> **yunxi 平台本身不连接 MySQL**：会话状态、跨实例锁等运行时数据走 Redis（由 `yunxi-config/src/main/resources/config/redis.yml` 以 `spring.data.redis` 前缀配置），无需任何数据库表结构即可启动并对话（轨迹存储仅有内存实现，见 [14. 可观测](./14-observability.md)）。
> `deploy/infra` 编排中的 MySQL 容器仅服务于**可选的** `mcp-nutrition` MCP 服务（配套项目 `yunxi-mcp-servers` 的独立 Java 进程），库名 `yunxi_nutrition`。

- **仅当启用 mcp-nutrition 时**：执行 `deploy/infra/init/nutrition-schema.sql` 初始化脚本（建 `yunxi_nutrition` 库及菜品 / 食材 / 营养素表，与平台运行时无关）；MySQL 容器已在 `docker compose up -d` 时自动挂载该脚本完成初始化。
- **不运行 mcp-nutrition 时**：跳过本步骤，平台照常工作。

---

## 启动服务

### 启动顺序

yunxi Agent Platform 已整合为单体服务（yunxi-app），一键启动即可：

```bash
# 启动 yunxi-app（整合所有模块，端口 40001）
mvn spring-boot:run -pl yunxi-app
```

### 方式二：脚本启动（Windows PowerShell）

```powershell
# 推荐：使用统一启动脚本（自动打包并启动 yunxi-app，端口 40001）
.\start-yunxi.ps1

# 可选参数：
.\start-yunxi.ps1 -Fast     # 快速启动（已打包时使用，跳过打包步骤）
.\start-yunxi.ps1 -Web      # 一并拉起前端 dev server（http://localhost:5173）
.\start-yunxi.ps1 -Port 8080 # 自定义后端端口
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
# 同步模式：POST /api/conversations，一次性返回完整回复
curl -X POST http://localhost:40001/api/conversations \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "example-assistant",
    "userId": "u-001",
    "sessionId": "s-001",
    "text": "你好"
  }'

# 流式模式：POST /api/conversations/stream，以 SSE 透传 AgentScope-Java 事件流
curl -X POST http://localhost:40001/api/conversations/stream \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "example-assistant",
    "userId": "u-001",
    "sessionId": "s-001",
    "text": "你好"
  }'
```

> 请求体为 `ConversationRequest`：`agentId` 与 `text` 为必填字段（`agentId` 可换成 `yunxi-config/src/main/resources/agent-definitions/` 下任一已加载 Agent 的 `identity.agentId`，如 `example-assistant`）；`userId` / `sessionId` 建议填写以打通身份与多轮会话；`protocol` 默认 `web`（web / a2a / agui / chat-completions 四协议共用同一请求类型）。同步用 `/api/conversations`，流式用 `/api/conversations/stream`，由端点而非 `mode` 字段决定。

### 请求处理流程

```
你的请求
    │
    ▼
┌─────────────────────────────────────────┐
│ yunxi-app (端口 40001)                    │  ← yunxi 统一入口进程
│  web / A2A / AG-UI / Chat Completions：ConversationController + IngressGateway │
│  IM 渠道（钉钉/企微/飞书/GitHub/GitLab/微信 iLink/ChatUi）：GatewayBootstrap→框架 HarnessGateway │
└─────────────────────────────────────────┘
    │
    ▼  （两条入口路径最终都收敛到框架 HarnessAgent）
┌─────────────────────────────────────────┐
│ HarnessAgent（AgentScope-Java 运行时）     │  ← 协议终结后经 AgentConfigurer 装配的推理型 Agent
│  · AgentScope-Java 负责 LLM 交互与工具调用  │
│  · MCP 工具经框架 McpClientBuilder 注册进 Toolkit（如 mcp-nutrition 端口 40602） │
└─────────────────────────────────────────┘
```

**关键点**：
- 你的 Agent 业务逻辑通过 `yunxi-config/src/main/resources/agent-definitions/` 下的 YAML 配置文件定义（角色提示词、工具、MCP 服务器等），由 `AgentProvisioner` 在启动期经 `AgentConfigurer.configure(AgentSpec)` 装配为 HarnessAgent 并注册进框架协议层
- yunxi 负责协议路由与编排：web 协议经 `IngressGateway` 翻译为协议无关的 `ConversationRequest` 并路由到运行时；IM 渠道（钉钉/企微/飞书/GitHub/GitLab/微信 iLink）/ 编程式 ChatUi 则经 `GatewayBootstrap` 接入框架 `HarnessGateway`（详见 [16.8 IM 渠道网关](./16-ingress-gateway.md)）；两条路径最终都收敛到框架 `HarnessAgent`。身份、鉴权、追踪等横切关切由运行时协议无关 Middleware 链统一承载，对所有协议生效
- AgentScope-Java 负责实际的 LLM 交互和工具调用

---

## 下一步

- 了解 [核心概念](./03-concepts.md)
- 查看 [模块说明](./05-modules.md)
- 阅读 [配置指南](./06-configuration.md)
- 想看一个完整落地示例？跳到 [07. 开发指南 - 实战示例：食谱生成智能体](./07-development.md#实战示例食谱生成智能体)，了解如何把多个模块组合成可运行的 Agent 应用

## 常见问题

**Q: 启动失败，提示端口被占用？**

A: 修改 `yunxi-config/src/main/resources/config/server.yml` 中的 `server.port`（统一配置中心，非各模块 `application.yml`）。

**Q: 数据库连接失败？**

A: 检查环境变量是否正确设置，数据库服务是否启动。

**Q: Redis 连接失败？**

A: 检查 Redis 服务是否启动，密码是否正确。

---

**上一页**: [01. 介绍](./01-introduction.md)  
**下一页**: [03. 核心概念 →](./03-concepts.md)
