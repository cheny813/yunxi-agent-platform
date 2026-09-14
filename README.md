# yunxi Agent Platform

[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![AgentScope](https://img.shields.io/badge/AgentScope--Java-2.0.3-blueviolet)](https://github.com/agentscope-ai/agentscope-java)
[![GitCode](https://img.shields.io/badge/GitCode-cheny813%2Fyunxi--agent--platform-blue)](https://gitcode.com/cheny813/yunxi-agent-platform)
[![GitHub](https://img.shields.io/badge/GitHub-Mirror-lightgrey?logo=github)](https://github.com/cheny813/yunxi-agent-platform)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-red)](https://maven.apache.org/)
[![Multi-Agent](https://img.shields.io/badge/Architecture-Multi--Agent-ff69b4)](https://github.com/agentscope-ai/agentscope-java)
[![MCP Ready](https://img.shields.io/badge/Protocol-MCP%20Ready-00bcd4)](https://modelcontextprotocol.io)
[![OpenTelemetry](https://img.shields.io/badge/Observability-OpenTelemetry-9cf)](https://opentelemetry.io)
[![Production Ready](https://img.shields.io/badge/Status-Production%20Ready-success)](CHANGELOG.md)

**yunxi Agent Platform** 是基于 **AgentScope-Java 2.0.3（GA）** 构建的企业级多智能体协作平台，以**意图引擎**为决策核心，开箱即用地提供多 Agent 编排、MCP 协议集成、记忆系统与技能自进化（MUSE）等能力。

> **yunxi（云曦）—— 让多智能体像晨曦一样自然涌现、协同共生。**

> **版本策略**：版本号与底层 [AgentScope-Java](https://github.com/agentscope-ai/agentscope-java) 同步，当前 **2.0.3** 基于其 GA 正式版。完整变更见 [CHANGELOG](CHANGELOG.md)。

## 项目介绍

yunxi 站在经过生产验证的 AgentScope-Java 2.0.3 运行时之上，为企业级落地补齐「编排 · 决策 · 治理 · 可观测」能力，让开发者专注业务而非底层胶水代码。

其**意图引擎**将「该调用哪个 Agent、走哪条链路」前置为一套可配置、可热更新的决策中枢：经 NER 识别 → 语义改写 → 场景分类 → 映射路由四阶段，自动将请求分派到最合适的 Agent。配合 **MCP 动态注册**（运行期接入 30+ 工具、无需重启）、**技能自进化 MUSE**（沙箱评估 → LLM 修补 → 剪枝合并闭环）与 **OpenTelemetry 全链路追踪**，一个 Agent 应用从原型走向生产往往只需改配置、不碰代码。

## 为什么选择 yunxi

- **决策中枢：意图引擎** — NER → 改写 → 分类 → 映射四阶段前置管道，自动路由到最合适的 Agent，告别硬编码 if-else 编排。
- **官方稳定底座** — 直接复用 AgentScope-Java 2.0.3 GA 的 Model / Toolkit / Middleware / DistributedStore 体系。
- **协议与工具开箱即用** — 原生 MCP 集成，支持运行期动态注册（Nacos 持久化、目标不可达自动重连），一键接入 30+ MCP 服务生态。
- **技能自进化（MUSE）** — 沙箱评估 → LLM 修补 → 剪枝合并闭环，让技能自我判断、修补、进化。
- **生产级保障** — OpenTelemetry 全链路追踪、提示注入防护、HITL 人机确认、工具最小权限。

---

## 核心特性

| 特性 | 说明 |
|------|------|
| **多 Agent 编排** | Supervisor、Agent 路由、Pipeline 编排 |
| **意图引擎** | NER → 改写 → 分类 → 映射四阶段前置管道；多域模型、rule/llm/hybrid 分类通道、actuator 热更新、意图路由，业务数据可配置替换 |
| **执行引擎** | `AgentExecutionEngine` 门面统一收口同步/流式/结构化/取消调用；5 拦截器链（AuthResolve→Memory→IntentPipeline→RagRetrieval→Audit）+ 3 执行策略 + 事件算子链，新增协议仅需实现 `AgentEventAdapter` |
| **AgentScope 深度集成** | 基于 AgentScope-Java 2.0.3 GA，复用 `Model`/`Toolkit`/`Middleware`/`DistributedStore` 体系 |
| **Spring Boot 原生** | `SmartLifecycle` 有序启停，Agent 实例 `prototype` 作用域，`@ConditionalOnClass` 按需加载 |
| **MCP 协议** | 完整支持 Model Context Protocol，内置 9 个 MCP 服务定义（sse/stdio/http），其中 database/redis/milvus/mcp-nutrition/formfill 共 5 个默认启用，亦支持接入外部 30+ MCP 服务生态 |
| **MCP 动态注册** | 运行期通过 REST API 注册/注销 MCP 服务器，基于 Nacos 配置中心实现目录持久化与跨实例广播，工具即时注入 Agent Toolkit，无需重启；目标不可达时自动重连降级 |
| **记忆系统** | Harness 内置双层文件系统记忆，支持 Redis 跨实例共享 |
| **技能系统** | 启用 AgentScope-Java 2.0 原生 `AgentSkillRepository`（文件系统 + 项目级全局目录），由框架 `DynamicSkillMiddleware` 自动装载 |
| **技能自进化（MUSE）** | 沙箱评估→LLM 修补→剪枝合并闭环 |
| **流式事件** | `Model.stream()` 与 `Agent.streamEvents()` 双通道，均按 `AgentEventType` 过滤 |
| **任务清单（TodoList）** | Agent 维护结构化步骤清单（x/y 进度经 `todo_update` 事件实时同步），状态随会话持久化、可断点续跑，复用原生 `todo_write` 工具 |
| **人机确认（HITL）** | 危险工具执行前经 `REQUIRE_USER_CONFIRM` 事件请求确认，未配置 HITL 的 Agent 则直接降级为拒绝 |
| **工具分组** | 按职责隔离工具（memory/filesystem/execute），默认最小权限，YAML 按需开放 |
| **提示注入防护** | ContentFilterMiddleware 基于框架 Middleware 接口，`onAgent` 拦截中英文注入模式 |
| **Shell 安全** | 复用框架 ShellCommandTool 白名单 + 平台验证器 + 审批回调 |
| **Prompt Caching** | 配置 `cache-control: true` 即可启用，支持 OpenAI/Anthropic/DashScope |
| **SPI 扩展** | 基于 Java SPI 的插件化扩展机制 |
| **多通道** | WebSocket、SSE、飞书、钉钉、企业微信 |
| **Text2SQL** | 自然语言查询数据库 |
| **A2A 协议** | 跨服务 Agent 协作，分布式编排 |
| **可观测性** | OpenTelemetry 链路追踪，标准 Actuator 健康检查 |

---

## 快速开始

按下列顺序操作即可完整跑通（aistio 管控面为可选，跳过也不影响基础启动）。两个配套仓库需与本项目放在**同级目录**，脚本默认从此推导路径：

- [`yunxi-mcp-servers`](https://gitcode.com/chenyao813/yunxi-mcp-servers) — 5 个内置 MCP 服务（Java 进程）
- [`agentscope-java-2.0GA`](https://github.com/agentscope-ai/agentscope-java) — aistio 管控面源码（仅需启用 aistio 时）

### 环境要求

- JDK 17+
- Maven 3.8+
- Docker Desktop（用于启动基础设施与 aistio）
- Ollama / OpenAI API（LLM 后端，可选一种）

### 第一步：获取 yunxi

```bash
# 1) 克隆主项目
git clone https://gitcode.com/chenyao813/yunxi-agent-platform.git
cd yunxi-agent-platform

# 2) 克隆 MCP 服务配套仓（第三步 start-stack.ps1 需要，须与主项目同级目录）
git clone https://gitcode.com/chenyao813/yunxi-mcp-servers.git ../yunxi-mcp-servers
```

> **关于两个配套仓库**：`yunxi-mcp-servers` 首次体验即需要（第三步拉起 MCP 服务），请按上面一并克隆到与主项目同级；`agentscope-java-2.0GA` 仅在你启用 aistio 管控面时才需要，首次可暂不克隆（见第三步 aistio 段）。

> **编译在第四步自动完成**：项目编译已内置在第四步的 `.\启动项目.ps1 -Clean` 中（脚本会执行 `mvn -pl agent-app -am clean install -DskipTests "-Djacoco.skip=true"`，仅构建运行链路、跳过 `agent-integration-test` 测试模块）。如果你希望先单独验证编译，也可现在手动执行这条命令，看到 `BUILD SUCCESS` 即可；否则直接跳到第四步一键完成「编译 + 启动」。

### 第二步：启动基础设施

根目录 `docker-compose.yml` 一键拉起容器化依赖（**不含 MCP 服务进程**，见第三步）：

```powershell
docker compose up -d
```

启动的服务（**9 个容器**；另 Ollama 为可选宿主机进程，见下方说明）：

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL 8.0 | 3306 | 主数据库（会话/技能/配置持久化） |
| Redis 7 | 6379 | 缓存/分布式状态/会话 |
| Milvus Standalone | 19530 | 向量数据库（记忆/语义检索） |
| MinIO | 9000 / 9001 | Milvus 对象存储（9001 为控制台，可选） |
| etcd | 2379 | Milvus 元数据协调 |
| Nacos 3.2.4 | 8848 / 8080 / 9848 | MCP 注册中心 + 配置中心 + AI Registry |
| OTel Collector | 4317 / 4318 | 链路追踪收集器（4317 gRPC / 4318 HTTP） |
| Jaeger UI | 16686 | 链路追踪可视化（http://127.0.0.1:16686） |
| Attu UI | 8000 | Milvus Web 管理界面（http://127.0.0.1:8000） |

首次启动约需 30-60 秒（等 Milvus 就绪），用 `docker compose ps` 确认状态。Ollama 向量嵌入建议在宿主机安装（`ollama pull bge-m3`），由应用通过 `localhost:11434` 调用。

**关闭服务**：`docker compose down`；**彻底重置**：`docker compose down -v && docker compose up -d`（重置会让 MySQL 初始化脚本重新执行，重建 `yunxi_nutrition` 库）。

### 第三步：启动 MCP 外部服务（与可选 aistio）

`scripts/start-stack.ps1` 一键拉起初体验所需的 5 个 MCP 服务，并检查 aistio 控制面（未运行则一并拉起），幂等可重复运行。

这 5 个 MCP 服务都来自配套项目 [`yunxi-mcp-servers`](https://gitcode.com/chenyao813/yunxi-mcp-servers)，由 `agent-config/src/main/resources/config/mcp-core.yml` 统一配置（地址、协议、开关），各 Agent 在 `agent-definitions/*.yml` 的 `mcpServersToolsGroup` 中按需接入，平台通过 SSE（端点 `/mcp/sse`）连接：

| 服务 | 端口 | 提供能力 |
|------|------|----------|
| database | 40101 | 关系数据库查询（query_db / list_tables / describe_table） |
| redis | 40102 | Redis 缓存与状态（redis_get / redis_set / redis_keys …） |
| milvus | 40103 | 菜品向量检索（search_dishes / get_dish_details） |
| mcp-nutrition | 40602 | 营养配餐数据查询与评分（search_public_dishes / evaluate_recipe / sync_data） |
| formfill | 40601 | 业务表单自动填写（formfill_fill / formfill_list_scenes / formfill_get_structure，需前端 WebSocket 配合） |

> **首次运行务必带 `-BuildMcp`**：`yunxi-mcp-servers` 是独立 Java 工程，仓库里**默认没有编译好的 jar**（`target/*.jar` 不存在），必须先 maven 构建。不带 `-BuildMcp` 时 `start-stack.ps1` 只检查 jar、缺失就跳过并提示 `ERROR: jar missing ... (use -BuildMcp to auto-build)`。因此首次启动请用 `-BuildMcp` 自动编译缺失的 jar；已构建过则自动跳过编译、秒级启动：

```powershell
.\scripts\start-stack.ps1 -BuildMcp    # 首次运行：自动 mvn package 构建缺失的 MCP jar 并拉起
.\scripts\start-stack.ps1              # 后续运行：跳过已在运行的，仅补齐缺失的
.\scripts\start-stack.ps1 -Force       # 重启所有 MCP（按端口 kill，aistio 容器不重建）
.\scripts\start-stack.ps1 -OpenConsole # 拉起后自动打开 aistio 控制台
```

若不想用 `-BuildMcp`，也可在该仓根目录手动构建：`cd ../yunxi-mcp-servers && mvn clean install -DskipTests`，再跑不带参数的 `start-stack.ps1`。

脚本行为：逐个检查 5 个 MCP 端口，未运行才用各模块 `target/*.jar` 拉起；检查 aistio 控制面（8081 / 18080），未运行则经 `docker compose --profile aistio up -d` 拉起；末尾汇总所有访问入口（aistio 控制台 `:8081`、网关 `:18080`、各 MCP 的 `/mcp/sse`、yunxi `:40001`）。

> **只想先跑平台、暂不接 MCP**：这 5 个服务默认 `enabled: true`，可在 `mcp-core.yml` 中改为 `false`（或设 `MCP_DATABASE_ENABLED=false` 等环境变量）全部关闭，平台仍可正常启动；待外部服务就绪再开启，或通过 MCP 动态注册 API 运行时接入，无需重启。外部进程未就绪时平台会记连接告警但**不会崩溃**（见第五步的容错说明）。

> **mcp-nutrition 初始化**：`mcp-nutrition` 依赖 MySQL 中的 `yunxi_nutrition` 库（菜品 / 食材 / 营养标准等约 15 张表），默认连接本地 docker MySQL（`jdbc:mysql://localhost:3306/yunxi_nutrition`）。仓库内置 `scripts/init/nutrition-schema.sql` 仅建库建表（**不含任何业务数据**），由 `docker compose up -d` 自动执行；建好后 `mcp-nutrition` 即可正常启动与查询（空库返回空结果）。如需连接自有 MySQL，可用 `NUTRITION_DB_URL` / `NUTRITION_DB_USER` / `NUTRITION_DB_PASSWORD` 环境变量覆盖默认连接。

> **启用 aistio 管控面需先编译**：aistio 无公开镜像，`aistio-data` / `aistio-scheduler` / `aistio-gateway` 三个镜像由 `Dockerfile.plane` 直接 `COPY target/*.jar`，**docker 构建过程不执行 maven**。首次启用 aistio 前，先运行以下脚本（自动打补丁 + maven 编 jar + docker 启动 aistio），再跑 `start-stack.ps1` 即可自动跳过已启动的 aistio：
> ```powershell
> .\scripts\setup-aistio.ps1          # 补丁 + maven 构建 + docker 启动 aistio
> # .\scripts\setup-aistio.ps1 -GitPull  # 首次拉取上游最新代码（目录有本地改动时勿用）
> ```
> 若不使用 aistio，`start-stack.ps1` 会在 aistio 镜像缺失时仅告警跳过，不影响 5 个 MCP 服务。手动构建则需在 AgentScope-Java 仓库执行 `mvn -pl agentscope-service -am install -DskipTests` 生成 `target/*.jar`。
>
> **重要**：aistio 构建时留在 `main` 分支，不要用 `v2.0.3` 标签 / `release/2.0.3` 分支——其前端引用了并不存在的 `features/build` 模块，会编译报 `TS2307: Cannot find module`。脚本默认构建 `main`，产物版本 `2.0.3-SNAPSHOT`。

> **路径约定**：`McpServersRoot` / `AistioRepoRoot` / `YunxiRoot` 默认从脚本位置自动推导为与 `yunxi-agent-platform` 同级目录（如 `../yunxi-mcp-servers`、`../agentscope-java-2.0GA`），目录布局不同时用 `-McpServersRoot` / `-AistioRepoRoot` 指定。

**日志**：yunxi 平台日志在 `logs/yunxi-agent-platform.log`；5 个 MCP 的 stdout/stderr 分别落在 `logs/mcp/<服务名>.out.log` / `.err.log`（实时跟踪：`Get-Content logs/mcp/database.out.log -Tail 50 -Wait`）；aistio 容器日志用 `docker compose --profile aistio logs -f`。关闭外部依赖栈用 `scripts/stop-stack.ps1`（加 `-IncludeYunxi` 一并关闭 yunxi 平台）。

### 第四步前：配置大模型（LLM）

平台运行**必须接入一个大模型后端**才能对话。默认使用阿里云 DashScope（`qwen-plus`），密钥通过环境变量 `DASHSCOPE_API_KEY` 注入。

- **开箱即用（推荐首次体验）**：本仓库的启动脚本 `启动项目.ps1` 内置了一个**示例** `DASHSCOPE_API_KEY`（`start.ps1` 中）。若该 Key 仍有效，直接 `.\启动项目.ps1` 即可对话；若调用返回鉴权 / 额度错误，请按下方设置自己的 `DASHSCOPE_API_KEY` 后重试。
- **使用自己的 Key**：在启动前的终端设置环境变量即可覆盖演示 Key：
  ```powershell
  $env:DASHSCOPE_API_KEY = "sk-你的真实Key"
  .\启动项目.ps1
  ```
  也可在 `agent-config/src/main/resources/config/llm.yml` 中填写 `agentscope.core.dashscope.api-key`。
- **切换其它厂商**（OpenAI / 百度 / 华为）：设置对应的 `OPENAI_API_KEY` / `BAIDU_API_KEY` / `HUAWEI_API_KEY` 等环境变量，并将 `agentscope.core.provider` 设为 `openai` / `baidu` / `huawei`（及相应 `model`）。

> **注意**：若改用 `mvn spring-boot:run -pl agent-app` 启动（绕过 `启动项目.ps1`），不会自动注入演示 Key，必须先 `set DASHSCOPE_API_KEY=sk-xxx`（Windows）再启动，否则对话会因「无可用模型」失败。

### 第四步：启动 yunxi 应用

```powershell
.\启动项目.ps1          # 推荐：首次会自动 mvn 编译并启动（端口 40001，约数分钟）
# .\启动项目.ps1 -Fast  # 已编译过则跳过打包直接启动
# mvn spring-boot:run -pl agent-app   # 备选：需先 set DASHSCOPE_API_KEY（见上一步 LLM 配置）
```

启动成功时日志末行会打印 `Started AgentPlatformApplication`；健康检查 `http://localhost:40001/actuator/health` 返回 `{"status":"UP"}`。随后访问：http://127.0.0.1:40001/chat.html

### 第五步（可选）：启用 aistio 管控面集成

若第三步已启动 aistio，将 `agent-config/src/main/resources/application.yml` 的 `yunxi.aistio` 段（默认 `enabled: false`）改为 `true`（详见 `docs/guide/06-configuration.md#aistio`），重启 yunxi：

```yaml
yunxi:
  aistio:
    enabled: true
    control-plane-url: http://localhost:18080
    internal-token: compose-local-internal-token-at-least-32chars   # 与 docker-compose 中 aistio 服务的默认 BUILDER_INTERNAL_TOKEN 一致，直接沿用即可
    base-url: http://localhost:40001/agentscope
```

重启后 Web 控制台由 aistio 提供（访问 `http://localhost:8081`），yunxi 经网关 `http://localhost:18080` 注册并接管 Agent。控制台种子用户（`admin` / `admin` 等，详见 aistio `seed.go`）首次启动自动注入。**生产环境务必替换默认 `internal-token`、控制台弱口令与数据库弱口令。**

> 若启动 yunxi 时 MCP 服务尚未就绪，平台会记连接告警但**不会崩溃**，对应 MCP 工具暂时不可用；等 `start-stack.ps1` 拉起对应服务后，调用工具时会自动重连恢复，无需重启 yunxi。

### 发送第一条消息

```bash
curl -X POST http://localhost:40001/api/conversations/chat \
  -H "Content-Type: application/json" \
  -d '{
    "agentName": "dish-searcher",
    "message": "你好，请介绍一下自己",
    "mode": "sync"
  }'
```

> `dish-searcher` 是内置示例 Agent，随平台**默认加载**（其 `hidden: true` 仅表示不在前端聊天列表展示，仍可直接按 `agentName` 调用）。更多内置 Agent 见 `agent-config/.../agent-definitions/`。

### 体验前端示例（可选）

`agent-nutritionist-web` 是可运行的前端示例（智能配餐工作台，展示「表单 + AI 助手」完整交互），基于 Vite，`npm run dev` 拉起常驻后台开发服务器：

```bash
cd agent-nutritionist-web
npm install      # 首次或依赖变更后执行
npm run dev      # 监听 http://localhost:5173
```

| 页面 | 地址 | 说明 |
|------|------|------|
| 产品首页 | http://localhost:5173/index.html | 产品介绍与核心能力 |
| 智能配餐工作台 | http://localhost:5173/pages/recipe-make.html | 左栏填目标、右栏 AI 助手；启用任务清单时可实时展示步骤进度 |

Vite 通过 `/api`、`/v1`、`/js` 代理到后端 `localhost:40001`（可用 `BACKEND_HOST` / `BACKEND_PORT` 覆盖），后端端口非 40001 时用 `BACKEND_PORT=40001 npm run dev` 指定。该示例通过 `@web-sdk` alias 直接引用 `agent-web-sdk/src` 源码，无需额外构建。

---

## 模块概览

| 模块 | 说明 | 核心技术 |
|------|------|----------|
| **agent-core** | 核心框架：Agent 编排、会话管理、模型、记忆、技能、安全、网关 | Spring Boot, agentscope-harness |
| **agent-muse** | 自进化引擎：技能沙箱评估→LLM 修补→剪枝合并闭环 | agentscope, Java 子进程沙箱 |
| **agent-text2sql** | 自然语言转 SQL | LLM, Milvus 向量检索 |
| **agent-spi** | SPI 接口定义 | Java SPI |
| **agent-config** | 统一配置：YAML、数据库初始化 | Spring Cloud |
| **agent-app** | 启动入口：整合所有模块（端口 40001） | Spring Boot |
| **agent-integration-test** | 集成测试 | JUnit, Testcontainers |
| **sdk-js** | JavaScript/TypeScript SDK | TypeScript, Node.js |
| **agent-web-sdk** | 浏览器端 JS SDK（WebSocket 接入） | JavaScript, WebSocket |
| **agent-nutritionist-web** | 营养师助手前端演示 | 静态 HTML + JS |

## 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│                       接入层 (Gateway)                       │
│      WebSocket · SSE · 飞书 · 钉钉 · 企业微信 · Web API      │
└───────────────────────────────▼──────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│              编排层 (Core + agentscope-harness)              │
│Agent 编排 · 会话管理 · 路由 · 技能治理 · 工作空间            │
│SmartLifecycle 启停 · prototype 作用域 · @Tool 注解           │
└───────────────────────────────▼──────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐   ┌──────────────────────────────────────────────────────────────┐
│                           MCP 协议                           │   │                       AgentScope V2.0                        │
│30+ 工具                                                      │   │Model / Toolkit / Memory                                      │
│SPI 扩展                                                      │   │Middleware / State                                            │
└───────────────────────────────▼──────────────────────────────┘   └───────────────────────────────▼──────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                    基础设施 (Spring Boot)                    │
│SmartLifecycle · Actuator · Micrometer · OTel                 │
│@ConditionalOnClass · ConfigurationProperties                 │
│Redis · MySQL · RocketMQ · Nacos                              │
└──────────────────────────────────────────────────────────────┘
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

[📖 查看完整文档](docs/guide/README.md)

---

## 工作空间目录结构

yunxi 采用 **Agent 优先** 的目录布局，遵循 agentscope-java 框架约定，所有 Agent 工作空间汇聚在 `agents/` 子目录下，基础路径 `.agentscope/workspace/`：

```
.agentscope/workspace/
├── agents/                   # Agent 工作空间统一目录（框架官方约定）
│   ├── dish-searcher/        # 菜品搜索助手
│   │   ├── agents/           # 子智能体定义
│   │   ├── AGENTS.md         # Agent 身份定义与场景规则
│   │   └── user-001/         # 用户运行时数据（按 userId 隔离）
│   ├── resident-nutrition-assistant/  # 居民营养配餐助手
│   ├── nutrition-evaluator/  # 营养评估
│   ├── pagegen-assistant/    # 页面生成助手
│   ├── recipe-composer/      # 食谱编排
│   └── safety-assistant/     # 安全助手
└── skills/                   # 全局共享技能（24 个技能目录）
```

**设计原则**：工作空间以 `agents/` 为统一入口；用户运行时数据按 `{userId}/` 子目录隔离（由 `HarnessAgent.workspaceFor(userId, sessionId)` 在调用时按用户/会话命名空间路由，无需自建扫描器）；workspace 根目录 `skills/` 为全局共享资源；API 路由用 `compositeKey = agentName + "#" + userId` 定位用户专属 Agent 实例。Agent 定义 YAML 的 `model.apiKey` / `model.baseUrl` / `model.stream` 可为单个 Agent 指定独立 LLM 账号，不填则回退全局 `agentscope.core.*`。

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
| **其他** | 邮件、Wikipedia、表单填写、业务处理、API 网关 |

---

## 未来计划

- 补充英文文档
- 发布 Maven Central
- 公开 MCP Server 市场

---

## 贡献

欢迎贡献代码、报告问题或改进文档！

- [贡献指南](CONTRIBUTING.md)
- [行为准则](CODE_OF_CONDUCT.md)
- [更新日志](CHANGELOG.md)

## 上游贡献

yunxi 的运行时完全建立在 [AgentScope-Java](https://github.com/agentscope-ai/agentscope-java) 之上。生产与压测中定位到的框架级缺陷，yunxi 不只在上层绕过，而是把修复回馈上游，让整个生态受益：

| PR | 修复的问题 | 状态 |
|------|------------|------|
| [agentscope-java#3134](https://github.com/agentscope-ai/agentscope-java/pull/3134) | `HarnessAgent` 关闭后，记忆系统的后台整理 / 落盘模型调用仍继续执行：连接不释放、in-flight 槽位泄漏；多 Agent 共存时取消还会误伤其它存活 Agent 的任务 | 已通过上游维护者评审（Approved），待合并 |

### #3134 fix(harness): cancel leaked memory background model calls on agent shutdown

- **根因**：记忆后台任务（flush / consolidation）在 Agent 关闭后仍在跑模型调用，既没有与 Agent 生命周期绑定，也没有取消点；全局取消又做不到按 Agent 隔离。
- **修复要点**：新增 `MemoryBackgroundTasks` 生命周期跟踪与按持有者（owner）的取消；`HarnessAgent.close()` 触发本 Agent 任务的取消与槽位释放；`MemoryFlushMiddleware` 在关闭后丢弃待执行 flush 并**迭代**排空队列（而非递归，避免深队列下在 `close()` 中栈溢出）；`disposeQuietly` 统一兜底，避免 `Disposable` 抛异常外泄。
- **测试**：新增 4 个测试文件（`HarnessAgentMemoryCancelTest` 等），覆盖 Agent 关闭即取消、双 Agent 隔离、400 层深队列排空等场景，共 41 个用例通过。
- **规模**：4 个提交，+1071 / -48，涉及 8 个文件。

### 版本兼容说明

yunxi 编译期只依赖已发布的 AgentScope-Java（当前 `2.0.3`），`#3134` 的取消能力在运行时按需探测：

- 使用 **>= 2.0.4**（含 #3134）时，Agent 关闭会自动取消遗留的记忆后台模型调用并释放槽位；
- 使用 **2.0.3** 时，退化为"等待后台任务自然结束"，其余功能不受影响。

探测逻辑集中在 `MemoryBackgroundTaskReaper`，上游发布后可整体删除，无需改动业务代码。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

## 致谢

- [AgentScope-Java](https://github.com/agentscope-ai/agentscope-java) — 多 Agent 框架（yunxi 的底层运行时）
- [Spring AI](https://spring.io/projects/spring-ai) — Spring AI 生态
