# 02. 快速开始

本指南帮助你在 30 分钟内启动并运行 yunxi Agent Platform。

> **完整启动路径速览**（Windows）：① 装 a2ui4j + 克隆主仓（[下载与构建](#下载与构建)）→ ② `docker compose -f deploy/infra/docker-compose.yml up -d` → ③ `.\start-yunxi.ps1 -Web`（一键打包、启动后端**和**前端）→ ④ 浏览器打开 `http://localhost:5173`。
> 脚本会自动注入模型端点 / 管理员口令 / 编码变量；裸跑 `mvn spring-boot:run` 缺这些变量会导致对话 401 或模型调用失败，详见 [启动服务](#启动服务)。

## 你将学到

- 理解 yunxi Agent Platform 与底层框架的关系
- 快速搭建开发环境
- 用统一脚本一键启动后端与前端，并发送第一个请求

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
# 1) a2ui4j：构建必需（io.a2ui4j:*:1.0.0-SNAPSHOT 不在任何公共 Maven 仓库，
#    yunxi-app 经 yunxi-a2ui 传递依赖它；不先装则 yunxi-a2ui 模块依赖解析失败）
git clone https://gitcode.com/chenyao813/a2ui4j.git
cd a2ui4j && mvn clean install -DskipTests && cd ..

# 2) yunxi 主仓库
git clone https://gitcode.com/chenyao813/yunxi-agent-platform.git
cd yunxi-agent-platform
```

### 2. 编译项目

```bash
# 编译所有模块（前置：上一步已 install a2ui4j 到本地仓库）
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

> **用启动脚本时只需配置模型密钥（可选）**：`start-yunxi.ps1` 会在进程内自动注入模型兼容端点
> （`OPENAI_BASE_URL` / `OPENAI_API_KEY`）、本地 dev 口令（`YUNXI_SECURITY_USER_PASSWORD=yunxi-dev-pass`）
> 及 UTF-8 编码变量，**不读取**系统/用户作用域中残留的旧值（避免旧口令导致前后端口令不一致 401）。
> 因此下面除 `DASHSCOPE_API_KEY` 外的变量，仅在你**不用脚本、手动 `mvn spring-boot:run`** 时才需要自己设置。
> 密钥也可通过脚本参数 `-DashscopeKey "sk-..."` 传入（进程内、不落地）。

```powershell
# 模型密钥：缺失时后端仍可启动，但对话会失败（脚本内置 dev key 仅供本地体验，
# 真实调用请传自己的 Key：.\start-yunxi.ps1 -DashscopeKey "sk-你的真实Key"）
$env:DASHSCOPE_API_KEY = "sk-你的真实Key"

# 以下变量仅在手动 spring-boot:run 时需要（脚本会自行注入）：
# $env:OPENAI_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
# $env:OPENAI_API_KEY = $env:DASHSCOPE_API_KEY
# $env:YUNXI_SECURITY_USER_PASSWORD = "yunxi-dev-pass"   # 与前端 Vite 代理硬编码口令一致

# Redis（docker compose 已按 redis.yml 默认值拉起，无需改）
# $env:REDIS_HOST = "localhost"; $env:REDIS_PORT = 6379; $env:REDIS_PASSWORD = "redispass"

# 可选：向量数据库（Milvus）与嵌入模型（启用知识库/RAG 时）
# $env:MILVUS_HOST = "localhost"; $env:MILVUS_PORT = 19530
# $env:EMBEDDING_PROVIDER = "ollama"
# $env:OLLAMA_BASE_URL = "http://localhost:11434"; $env:OLLAMA_EMBEDDING_MODEL = "bge-m3"
```

**为什么使用环境变量**：敏感信息不写入代码、不同环境不同配置、便于容器化部署。
Redis 连接参数以 `yunxi-config/src/main/resources/config/redis.yml` 为唯一权威来源，其他文件不重复声明。

### 2. 初始化数据库（仅可选 MCP 服务需要）

> **yunxi 平台本身不连接 MySQL**：会话状态、跨实例锁等运行时数据走 Redis（由 `yunxi-config/src/main/resources/config/redis.yml` 以 `spring.data.redis` 前缀配置），无需任何数据库表结构即可启动并对话（轨迹存储仅有内存实现，见 [14. 可观测](./14-observability.md)）。
> `deploy/infra` 编排中的 MySQL 容器仅服务于**可选的** `mcp-nutrition` MCP 服务（配套项目 `yunxi-mcp-servers` 的独立 Java 进程），库名 `yunxi_nutrition`。

- **仅当启用 mcp-nutrition 时**：执行 `deploy/infra/init/nutrition-schema.sql` 初始化脚本（建 `yunxi_nutrition` 库及菜品 / 食材 / 营养素表，与平台运行时无关）；MySQL 容器已在 `docker compose up -d` 时自动挂载该脚本完成初始化。
- **不运行 mcp-nutrition 时**：跳过本步骤，平台照常工作。

---

## 启动服务

yunxi Agent Platform 已整合为单体服务（yunxi-app，端口 40001）。**Windows 请用统一启动脚本**——它会先把模型兼容端点 / dev 口令 / 编码变量注入进程，再打包、启动并轮询健康检查；裸跑 `mvn spring-boot:run` 因缺这些变量会出现「后端起来了但对话 401 / 模型调用失败」。

### 方式一：统一启动脚本（Windows PowerShell，推荐）

```powershell
# 首次启动：自动 mvn 打包安装 → 启动后端（后台模式）→ 轮询 /actuator/health
.\start-yunxi.ps1

# 常用参数（可组合）：
.\start-yunxi.ps1 -Fast                       # 已打包过则跳过 mvn 打包，直接启动
.\start-yunxi.ps1 -Web                        # 后端就绪后一并拉起前端 dev server（http://localhost:5173）
.\start-yunxi.ps1 -Console                    # 前台启动，日志直出控制台（Ctrl+C 停止）
.\start-yunxi.ps1 -Port 8080                  # 自定义后端端口
.\start-yunxi.ps1 -DashscopeKey "sk-你的Key"  # 临时传入模型密钥（进程内，不落地）
```

脚本行为要点：
- 启动前**强制释放目标端口**（含前端 5173，`-Web` 时），避免旧实例占用导致 vite 静默跳到 5174；
- 后台模式日志写入 `tmp/app<Port>.log`（默认 `tmp/app40001.log`），实时跟踪：`Get-Content -Encoding UTF8 -Wait -Tail 50 tmp/app40001.log`；
- 启动完成打印后端地址与管理员口令（默认 dev 口令 `yunxi-dev-pass`）；
- 停止后端：`Get-NetTCPConnection -LocalPort 40001 | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }`。

### 方式二：手动 Maven 启动（Linux / macOS / 不依赖脚本时）

```bash
# 先自行注入环境变量（见「配置环境 · 设置环境变量」），再启动：
mvn clean install -DskipTests          # 或 mvn -pl yunxi-app -am install -DskipTests
mvn spring-boot:run -pl yunxi-app      # 默认端口 40001；自定义加 -Dspring-boot.run.arguments=--server.port=8080
```

> Linux/macOS 下若需一并起前端，进入 `yunxi-nutritionist-web` 目录执行 `npm install && npm run dev`（见下节）。

### 体验前端（yunxi-nutritionist-web，可选）

前端是 **独立 Vite 工程**（后端 JAR 不含页面），监听 `http://localhost:5173`，对应平台侧 `resident-nutrition-assistant` Agent。两种启动方式：

1. **随后端一并拉起（Windows 推荐）**：`.\start-yunxi.ps1 -Web` —— 自动 `npm install` 并拉起 vite dev server，脚本会在结束时打印前端地址。
2. **单独启动**：
   ```powershell
   cd yunxi-nutritionist-web
   npm install
   npm run dev          # http://localhost:5173
   # 或直接用仓库脚本（自动释放 5173）：.\start-yunxi-web.ps1
   ```

**前置条件**：前端 Vite 代理把 `/api` / `/v1` 转发到后端（默认 `localhost:40001`），**后端必须先启动**，否则页面能打开但对话/配置请求失败。自定义后端端口时用 `BACKEND_PORT` 环境变量覆盖。用 `http://localhost:5173`（不是 https）；不加 `-Web` 时 5173 连不上是预期，不是后端故障。详见 [`yunxi-nutritionist-web/README.md`](../../yunxi-nutritionist-web/README.md)。

---

## 验证启动

### 1. 健康检查

```powershell
# 脚本启动完成时已自动轮询过；手动验证用 curl.exe（Windows 下 curl 是 Invoke-WebRequest 别名）
curl.exe -u admin:yunxi-dev-pass http://localhost:40001/actuator/health
# 期望 {"status":"UP"}
```

### 2. 查看指标

```powershell
curl.exe -u admin:yunxi-dev-pass http://localhost:40001/actuator/prometheus
```

---

## 第一个请求

### 通过 Web API 发送消息

> **Windows 必读**：PowerShell 会把 `-d '{...}'` 中开头的 `{` 当脚本块解析并剥离，导致服务端报 400 `JSON parse error`；且 `curl` 是 `Invoke-WebRequest` 的别名。因此**一律先把请求体写入文件，再用 `curl.exe -d @文件` 引用**，并带上管理员口令（脚本启动的 dev 口令见启动完成输出，默认 `yunxi-dev-pass`）。该写法在 Linux/macOS 同样有效（续行符改用 `\`）。

```powershell
# 先把请求体写入文件（here-string 原样写入，不受花括号影响）
@'
{"protocol":"web","agentId":"example-assistant","userId":"u-001","sessionId":"s-001","text":"你好"}
'@ | Set-Content -Encoding utf8 request.json

# 同步模式：POST /api/conversations，一次性返回完整回复
curl.exe -X POST http://localhost:40001/api/conversations `
  -H "Content-Type: application/json" -u admin:yunxi-dev-pass -d @request.json

# 流式模式（SSE）：POST /api/conversations/stream
curl.exe -N -X POST http://localhost:40001/api/conversations/stream `
  -H "Content-Type: application/json" -H "Accept: text/event-stream" `
  -u admin:yunxi-dev-pass -d @request.json
```

> 请求体为 `ConversationRequest`：`agentId` 与 `text` 为必填字段（`agentId` 可换成 `yunxi-config/src/main/resources/agent-definitions/` 下任一已加载 Agent 的 `identity.agentId`，如 `example-assistant` / `resident-nutrition-assistant`）；`userId` / `sessionId` 建议填写以打通身份与多轮会话；`protocol` 默认 `web`。同步用 `/api/conversations`，流式用 `/api/conversations/stream`，由端点而非 `mode` 字段决定。若返回 `status:"EXECUTION_ERROR"`，多半是模型密钥无效（见「配置环境」）。四协议完整验证命令见 [仓库 README · 验证四协议](../../README.md#验证四协议逐步)。

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

A: 用脚本启动会**自动结束**占用目标端口的旧实例，无需手动处理；脚本加 `-Port 8080` 可换端口。手动启动时改 `yunxi-config/src/main/resources/config/server.yml` 的 `server.port`（统一配置中心，非各模块 `application.yml`）。前端 5173 占用由 `start-yunxi-web.ps1` 或 `-Web` 自动清理。

**Q: 后端起来了，但页面能打开、对话报 401？**

A: 口令不一致。脚本会强制注入 `YUNXI_SECURITY_USER_PASSWORD=yunxi-dev-pass`，前端 Vite 代理也硬编码同一口令；若你手动启动后端并改过该变量，请改回一致值。请求示例里的 `-u admin:yunxi-dev-pass` 需与启动输出打印的口令一致。

**Q: 后端起来了，但对话报模型错误 / `EXECUTION_ERROR`？**

A: 密钥问题。`start-yunxi.ps1` 内置 dev key 仅供本地体验；真实调用用 `-DashscopeKey "sk-..."` 传入。裸跑 Maven 时需自行设置 `DASHSCOPE_API_KEY` / `OPENAI_BASE_URL` / `OPENAI_API_KEY`（见「配置环境」）。

**Q: 数据库连接失败？**

A: yunxi 平台运行时不连 MySQL，仅可选 MCP 服务（mcp-nutrition）需要；检查其容器是否启动。

**Q: Redis 连接失败？**

A: 检查 Redis 容器是否启动，密码与 `yunxi-config/src/main/resources/config/redis.yml` 是否一致（Spring Boot 3.x 前缀 `spring.data.redis`）。

---

**上一页**: [01. 介绍](./01-introduction.md)  
**下一页**: [03. 核心概念 →](./03-concepts.md)
