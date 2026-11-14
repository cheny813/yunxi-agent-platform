# yunxi Agent Platform

[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![AgentScope-Java](https://img.shields.io/badge/AgentScope--Java-2.1.0--BETA1-blueviolet)](https://github.com/agentscope-ai/agentscope-java)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-red)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

**yunxi（云曦）—— 让多智能体像晨曦一样自然涌现、协同共生。**

yunxi Agent Platform 是一个**企业级多智能体协作平台**，构建于经过生产验证的 AgentScope-Java 运行时之上。它在框架原生运行时之上整合多 Agent 编排、意图路由、MCP 协议生态、长期记忆与技能自进化（MUSE），为企业级落地补齐「编排 · 路由 · 治理 · 可观测」四大能力。

**为什么选择 yunxi**

- **开箱即用**：一条命令启动，预置示例 Agent，数分钟内打通端到端对话；MCP 服务可选接入。
- **意图路由**：请求未显式指定 `agentId` 时，意图引擎以候选 Agent 卡片为闭集做**语义多域分类**（本地决策模型 + 结构化输出；可选 `classifier.impl=jev` 云端判定增强，默认 local 无数据外发），并做实体槽位抽取与改写；Agent 作者可用 `AgentSpec.intent.hint` 显式声明分类卡片（缺省从 description 自动派生）。关键词规则保留为兜底与回滚路径，三级 fail-open（模型 → 关键词 → 默认 Agent）保证入口永不中断。装配点为 `IntentEngineConfiguration`，判定器与解析器均为 SPI。
- **协议无关**：同一套运行时原生贯通 web / A2A / AG-UI / Chat Completions 四大协议，并支持钉钉、企业微信、飞书等 IM 渠道接入。
- **生产级底座**：代码沙箱 fail-closed 隔离、OpenTelemetry 全链路追踪、细粒度权限治理，直接满足企业落地要求。
- **技能自进化（MUSE）**：沙箱评估 → 修补 → 剪枝合并闭环，让技能资产持续进化而无需人工维护。

## 目录

- [核心特性](#核心特性)
- [快速开始](#快速开始)
- [启用 aistio 管控面](#启用-aistio-管控面)
- [架构概览](#架构概览)
- [模块概览](#模块概览)
- [工作空间目录结构](#工作空间目录结构)
- [详细文档](#详细文档)
- [运维平面（admin）](#运维平面admin)
- [许可证](#许可证)
- [致谢](#致谢)

## 核心特性

| 特性 | 说明 |
|------|------|
| **意图路由（缺省 agentId 时）** | 四段式意图管线：归一化/槽位抽取 → 候选收窄（`ConfigCenter` 目录 ∩ 租户允许集合，单候选短路）→ 本地决策模型闭集分类（`temperature=0` + jsonSchema 结构化输出 + 超时容错）→ 决策融合（越权拒绝 / 置信度阈值 / 三级降级）。解析结果（标签 / 实体 / 改写文本）以 `yunxi.intent.*` 上下文随命令透传执行链路；分类卡片支持 `AgentSpec.intent.hint` 显式声明（缺省自动派生），判定器可选 `classifier.impl=jev` 云端增强（默认 `local`，无数据外发）；`strategy=keyword` 可一键回滚为纯关键词路由。详见 [15. 意图引擎](docs/guide/15-intent-engine.md) |
| **多 Agent 编排** | 编排模式（`supervisor` / `workflow` / `pipeline` / `routing` / `handoffs`）的运行时执行统一委托 aistio 控制面，yunxi 不自持编排运行时；声明型 `workflow` 的导出图在 aistio 执行，不产生 yunxi 侧运行时实例 |
| **统一执行引擎** | `yunxi-runtime` 的统一执行门面（`ExecutionFacade`）负责 web / A2A 自有协议的调用编排（意图识别、RAG、记忆构建、权限快照、实体注入、指标、阶段状态、审计），经框架 `Agent.streamEvents` 接缝衔接；协议无关的横切（内容安全 / 画像 / 追踪 / 记忆压缩）实现为 AgentScope-Java Middleware 对所有协议统一生效，详见 [17. 统一执行引擎](docs/guide/17-execution-engine.md) |
| **可观测与轨迹模型** | 运行时经 `OtelTracingMiddleware` 产出 `invoke_agent` / `chat` / `execute_tool` Span，OTLP 上报 Collector → Jaeger，`MetricRegistry` 提供计数指标；轨迹数据模型 `TraceRecord` / `TraceStore`（`InMemoryTraceStore`）已就绪，但**尚未接入运行时写入与查询/回放端点**（轨迹 API 属设计态），详见 [14. 可观测](docs/guide/14-observability.md) |
| **水平扩展（分片路由）** | `yunxi.shard.enabled=true` 后按 `consistentHash(agentId)` 在 Nacos 实例环上选出 owner 实例：每实例只布建 owned Agent，其余请求经 HTTP 转发至 owner；AG-UI SSE 由 HTTP 边界过滤器原字节透传转发（不破坏流式语义），归属表缺失或 owner 不可达时 fail-open 本地兜底 |
| **AgentScope 深度集成** | 复用 AgentScope-Java 的 `Model`/`Toolkit`/`Middleware`/`DistributedStore` 体系 |
| **MCP 协议** | 完整支持 Model Context Protocol；MCP 服务定义位于独立的 [`yunxi-mcp-servers`](https://gitcode.com/chenyao813/yunxi-mcp-servers) 仓库（30+ 个服务模块，按需选用），由平台 `yunxi-config/src/main/resources/config/mcp.yml` 按名声明接入——当前仅声明 `nutrition-mcp` 与 `text2sql-mcp`，且两者均 `enabled: false`（不启用时平台仍可正常启动对话）；支持运行期动态注册（Nacos 持久化、目标不可达自动重连） |
| **记忆系统** | Harness 内置双层文件系统记忆，支持 Redis 跨实例共享 |
| **技能系统** | 技能由框架原生 `AgentSkillRepository` 托管（yunxi 不自建技能仓库）；内置技能经 `AgentConfigurer` 用 `ClasspathSkillRepository("builtin-skills")` 从 jar **零复制**加载（框架 Layer2）；`BuiltinSkillLoader` 在启动时（`yunxi.muse.builtin-copy-on-start`，默认 `true`）仅把内置技能的 `tests/` 物化到 `.agentscope/workspace/skills/` 供 MUSE 评估器执行，不再落地 `SKILL.md`（消除双份真相），MUSE 演进技能写回该目录（Layer3，覆盖内置） |
| **技能自进化（MUSE）** | 沙箱评估 → LLM 修补 → 剪枝合并闭环；剪枝判据含**使用者广度**维度（只读用户画像，区分「多人各用一次」与「单人高频」，避免误杀小众领域核心技能） |
| **流式事件** | `Model.stream()` 与 `Agent.streamEvents()` 双通道，均按 `AgentEventType` 过滤 |
| **任务清单（TodoList）** | Agent 维护结构化步骤清单（复用框架原生 `todo_write` 工具），状态随会话持久化、可断点续跑；进度经 `agent-event` 透传框架事件、由前端从 `TOOL_RESULT_TEXT_DELTA` 解析 `{content, status, priority}` 实时渲染，详见 [09. API 参考](docs/guide/09-api-reference.md#任务清单透出机制) |
| **人机确认（HITL）** | 危险工具执行前经 `REQUIRE_USER_CONFIRM` 事件请求确认，未配置 HITL 的 Agent 则直接降级为拒绝 |
| **安全与可观测** | 代码执行 fail-closed（启动期断言：未绑定真实沙箱后端则禁止放行 `execute`）+ AgentScope-Java 原生沙箱（Docker）隔离执行、OpenTelemetry 全链路追踪、标准 Actuator 健康检查 |
| **多通道** | web / A2A / AG-UI / Chat Completions 四协议；IM 渠道：钉钉、企业微信、飞书、GitHub、GitLab、微信 iLink；外加编程式 ChatUi（双网关接入：四协议经 IngressGateway，IM 经 GatewayBootstrap→框架 HarnessGateway） |
| **Text2SQL / A2A** | 自然语言查询数据库（经 `mcp-text2sql` MCP 服务，按需启用）；跨服务 Agent 协作与分布式编排，agent-ingress 统一聚合 A2A 入口 |
| **Spring Boot 原生** | `SmartLifecycle` 有序启停，`prototype` 作用域，`@ConditionalOnClass` 按需加载，SPI 插件化扩展 |

## 快速开始

MCP 服务来自独立的 [`yunxi-mcp-servers`](https://gitcode.com/chenyao813/yunxi-mcp-servers) 仓库（可选），需与本项目放在**同级目录**（脚本默认从此推导路径）：

- [`yunxi-mcp-servers`](https://gitcode.com/chenyao813/yunxi-mcp-servers) — 30+ 个 MCP 服务模块（Java 进程，可选；不启用时平台仍可正常启动与对话）
- aistio 管控面以 **AgentScope-Java 官方 Docker 镜像**提供（见下文「启用 aistio 管控面」），无需克隆任何源码仓

### 环境要求

JDK 17+ · Maven 3.8+ · Docker Desktop（基础设施与 aistio，基础对话验证可省略）· Ollama / OpenAI API（LLM 后端，可选一种）· Node 18+（仅前端，可选）

### 第一步：获取 yunxi

```bash
git clone https://gitcode.com/chenyao813/yunxi-agent-platform.git
cd yunxi-agent-platform
git clone https://gitcode.com/chenyao813/yunxi-mcp-servers.git ../yunxi-mcp-servers
```

### 第二步：启动基础设施

```powershell
docker compose -f deploy/infra/docker-compose.yml up -d
```

拉起 9 个容器：MySQL 8.0（3306）、Redis 7（6379）、Milvus Standalone（19530）、MinIO（9000/9001）、etcd（2379）、Nacos 3.2.4（8848/8080/9848）、OTel Collector（4317/4318）、Jaeger UI（16686）、Attu UI（8000）。首次启动约需 30–60 秒，用 `docker compose -f deploy/infra/docker-compose.yml ps` 确认状态。

> 若 `up -d` 报容器名冲突（如 `Conflict. The container name "/yunxi-milvus-etcd" is already in use`），说明本机已存在同名容器。本平台基础设施容器统一以 `yunxi-` 前缀命名，冲突通常来自先前启动、尚未清理的实例。先用下方命令移除这些同名容器（仅删容器、不删数据卷），再重新 `up -d`：
> ```powershell
> docker ps -aq --filter label=com.docker.compose.project=yunxi-agent-platform | ForEach-Object { docker rm -f $_ }
> ```

### 第三步：启动 MCP 服务（可选）

MCP 服务来自独立的 [`yunxi-mcp-servers`](https://gitcode.com/chenyao813/yunxi-mcp-servers) 仓库，由 `yunxi-config/src/main/resources/config/mcp.yml` 统一配置。**不启用 MCP 时平台仍可正常启动与对话**，仅在聊天中实际调用到的工具（如营养评分依赖 `nutrition-mcp`）会不可用。若需启用，将 `mcp.yml` 中各服务 `enabled` 改为 `true` 并按该仓库说明拉起对应 Java 进程即可。

### 第四步：配置大模型（DashScope 密钥）

平台默认路由到阿里云 DashScope 的 `qwen3.8-27b`，模型清单与路由在 `yunxi-config/src/main/resources/config/models.yml` 的 `yunxi.models` 段调整。密钥通过环境变量 `DASHSCOPE_API_KEY` 注入（仓库不含任何密钥）。该变量**非强制**：缺失时后端仍能启动，仅对话会失败。

启动时有两种填法，二选一：

- **由启动脚本交互提示（推荐）**：直接运行 `.\start-yunxi.ps1`。若未设置 `DASHSCOPE_API_KEY`，脚本会暂停并提示 `请输入 DASHSCOPE_API_KEY`，在此粘贴真实密钥即可（直接回车留空也能继续，仅对话失败）。
- **预先设置环境变量**：运行脚本前手动赋值，脚本检测到已设置便跳过提示：

```powershell
$env:DASHSCOPE_API_KEY = "sk-你的真实Key"
.\start-yunxi.ps1
```

切换其它厂商（OpenAI / 百度 / 华为）：在 `yunxi.models.catalog` 新增对应 `provider` 条目并把 `defaults.llm` 指向它；各 Provider 的 `*_API_KEY` 仍经环境变量注入。

本地模型只承担专项节点，不承载对话生成：本机 Ollama 已拉取的 `tev1:0.8b` 是**文本决策模型**（`local-decision`，type=decision），`clef-flash:latest` 是**图片决策模型**（type=decision），`bge-m3:latest` 是**矢量模型**（`local-embedding`，type=embedding）。三者均**不能**作为对话 / 推理主模型；对话与推理一律走远程（见 `defaults.llm`）。若需离线专用，只可在专项决策 / 检索节点按名引用这些本地模型，不可设为 agent 的 `primary`。

### 第五步：启动 yunxi 应用

```powershell
.\start-yunxi.ps1            # 推荐：首次自动 mvn 编译并启动（端口 40001）
# .\start-yunxi.ps1 -Fast     # 已编译过则跳过打包直接启动
# .\start-yunxi.ps1 -Web      # 同时拉起前端 dev server（http://localhost:5173/）
# .\start-yunxi.ps1 -Port 8080 # 自定义后端端口
# .\start-yunxi.ps1 -Console  # 前台启动，日志直接输出到控制台（Ctrl+C 停止）
```

启动成功日志末行打印 `Started YunxiApplication`；健康检查 `http://localhost:40001/actuator/health` 返回 `{"status":"UP"}`。如需 Web 界面，按下文「体验前端示例」启动 `yunxi-nutritionist-web`。

> **后端日志位置**：默认后台模式把运行日志写入 `tmp/app40001.log`（控制台只显示启动包装信息，不含应用运行日志）。实时跟踪：
> ```powershell
> Get-Content -Encoding UTF8 -Wait -Tail 50 tmp/app40001.log
> ```
> 想在控制台直接看日志，改用 `.\start-yunxi.ps1 -Console`（前台运行，Ctrl+C 停止）。

### 发送第一条消息（Web 协议，非流式）

> **Windows 必读**：PowerShell 会把 `-d '{...}'` 里开头的 `{` 当作脚本块解析并剥离，导致服务端收到 `protocol...` 而非 `{...}`、报 400 `JSON parse error`。因此**不要内联 JSON**，一律先把请求体写进文件再用 `-d @文件` 引用（该写法在 Linux/macOS 的 `curl` 上同样有效）。此外 PowerShell 里 `curl` 是 `Invoke-WebRequest` 别名，请改用 `curl.exe`；行尾续行用反引号 `` ` ``，Linux/macOS 改为反斜杠 `\`。

```powershell
# 先把请求体写入文件（here-string 原样写入，不受花括号影响）
@'
{"protocol":"web","agentId":"example-assistant","userId":"u1","text":"你好，请介绍一下自己"}
'@ | Set-Content -Encoding utf8 request.json

curl.exe -X POST http://localhost:40001/api/conversations `
  -H "Content-Type: application/json" -u admin:你的口令 -d @request.json
```

> 若返回 `status:"EXECUTION_ERROR"`：请求已正确解析，仅因未配置真实 `DASHSCOPE_API_KEY`（模型调用失败）。请见第四步填入有效密钥后重启再试。

平台默认加载四个 Agent 定义：`example-assistant`（通用示例）、`example-coder`（示例编码助手，演示容器沙箱代码执行）、`resident-nutrition-assistant`（营养师业务，对应 `yunxi-nutritionist-web` 前端）与 `text2sql-assistant`（Text2SQL 业务，见 [18. Text2SQL 业务 Agent](docs/guide/18-text2sql-assistant.md)）。`agentId` 即在 `yunxi-config/src/main/resources/agent-definitions/*.yml` 中声明的 `identity.agentId`。其中 `resident-nutrition-assistant` 是纯业务 Agent，已在定义中通过 `toolkit.deniedToolNames` 关掉了 AgentScope-Java 默认工作区工具（见 [06. 配置指南 · 工具箱裁剪](docs/guide/06-configuration.md#工具箱裁剪按-agent-摘除框架默认工具)）——该行为是有意设计，并非功能缺失。

> 非流式端点返回 `status:"OK"` 与本次对话的 Agent 回复文本；流式（SSE）对话请使用 `yunxi-nutritionist-web` 前端。

### 验证四协议（逐步）

平台统一入口网关聚合 web / A2A / AG-UI / Chat Completions 四协议，可用以下命令逐步验证（将 `你的口令` 替换为启动日志打印的 `YUNXI_SECURITY_USER_PASSWORD`）。

> **Windows 必读**：同上文，POST 带 JSON 的请求体一律先写文件再用 `-d @文件`，不要内联花括号（否则 PowerShell 剥离 `{` 报 400）。PowerShell 中 `curl` 是 `Invoke-WebRequest` 别名，改用 `curl.exe`；行尾续行用反引号 `` ` ``，Linux/macOS 改为反斜杠 `\`。

```powershell
# 0) 健康检查（无请求体，直接发）
curl.exe -u admin:你的口令 http://localhost:40001/actuator/health

# 1) Web 对话：见上文「发送第一条消息」

# 2) Chat Completions（非流式，返回模型真实回复）
@'
{"model":"example-assistant","messages":[{"role":"user","content":"用一句话介绍你自己"}]}
'@ | Set-Content -Encoding utf8 cc.json
curl.exe -X POST http://localhost:40001/v1/chat/completions `
  -H "Content-Type: application/json" -u admin:你的口令 -d @cc.json

# 3) A2A 能力卡片（GET，无请求体）
curl.exe -u admin:你的口令 http://localhost:40001/a2a/example-assistant/.well-known/agent.json

# 4) AG-UI（SSE 流式；需 Accept: text/event-stream）
@'
{"threadId":"t1","runId":"r1","messages":[{"id":"m1","role":"user","content":"用一句话介绍你自己"}]}
'@ | Set-Content -Encoding utf8 agui.json
curl.exe -N -X POST http://localhost:40001/agui/run/example-assistant `
  -H "Accept: text/event-stream" -H "Content-Type: application/json" -u admin:你的口令 -d @agui.json
```

四条协议均返回 Agent 真实回复即代表端到端打通。

### 体验前端示例（yunxi-nutritionist-web，可选）

`yunxi-nutritionist-web` 是基于 Vite 的**独立前端工程**（后端 JAR 不含其页面），监听 `http://localhost:5173`，对应平台侧已定义的 `nutritionist` 业务 Agent。两种启动方式：

1. **随后端一并拉起**（推荐）：`start-yunxi.ps1 -Web`，自动安装依赖并拉起 vite dev server。
2. **单独手动启动**：
   ```bash
   cd yunxi-nutritionist-web
   npm install
   npm run dev        # http://localhost:5173
   ```

**前置条件**：前端开发服务器通过 Vite 代理把 `/api`、`/v1` 转发到后端（默认 `localhost:40001`），因此**后端（`yunxi-app`，端口 40001）必须先启动**，否则页面能打开但对话/配置请求会失败。可用 `BACKEND_HOST` / `BACKEND_PORT` 环境变量覆盖后端地址。

**访问注意**：用 `http`（不是 `https`）访问 `http://localhost:5173`；不加 `-Web` 时 5173 连不上是预期，不是后端故障。详细的页面说明见 [`yunxi-nutritionist-web/README.md`](yunxi-nutritionist-web/README.md)。

## 启用 aistio 管控面

aistio 管控面提供 Web 控制台、Agent 注册接管与编排图执行能力，依赖 AgentScope-Java 2.1.0-BETA1，作为平台常规模块随默认构建一并纳入。

**1. 构建**：aistio 管控面对接能力位于 `yunxi-fabric` 模块，已随默认 reactor 一并构建，无需 `-Paistio`；直接 `mvn -pl yunxi-app -am clean install -DskipTests` 即可把 aistio 管控面对接（控制面注册 / gRPC 对话流 / 编排触发器 / 契约与治理 / 任务接活）一并打进产物。

**2. 接入 aistio 控制面**：aistio 控制面以 AgentScope-Java 官方 Docker 镜像提供，编排脚本位于仓库 `deploy/aistio/`：

```powershell
docker compose -f deploy/aistio/docker-compose.yml up -d   # 拉起控制面 / 数据面 / 调度面 / 网关 / 元数据库共 5 容器
```

> 重要：aistio 控制面以 AgentScope-Java 官方 Docker 镜像提供，编排脚本位于仓库 `deploy/aistio/`，镜像默认 `2.1.0-BETA1`（由 `AISTIO_VERSION` / `AISTIO_REGISTRY` 环境变量覆盖，见 `deploy/aistio/.env.example`）。镜像版本须与 `yunxi-fabric` 中 aistio 客户端协商的 API 版本一致，否则控制面握手可能失败。所有敏感值用 `${VAR:-dev默认值}` 兜底，零配置即以开发默认值启动；生产环境在 `deploy/aistio/` 放置同级 `.env`（已被 `.gitignore` 忽略）覆盖 `BUILDER_JWT_SECRET` / `BUILDER_INTERNAL_TOKEN` / `BUILDER_VAULT_MASTER_KEY` / `CONTROL_PLANE_BOOTSTRAP_PASSWORD` / `AISTIO_POSTGRES_PASSWORD`，切勿向仓库提交明文密钥。控制面主机端口发布在 `18081`（REST/控制台），网关在 `18090`。

**3. 开启集成**：aistio 对接能力已随默认构建编入 `yunxi-fabric` 模块，默认关闭。仓库不提交 aistio 明文配置，通过环境变量开启（等价 JVM 参数 `-Dyunxi.aistio.*`）：

```powershell
$env:YUNXI_AISTIO_ENABLED = "true"
$env:YUNXI_AISTIO_CONTROL_PLANE_URL = "http://localhost:18081"
$env:YUNXI_AISTIO_BASE_URL = "http://localhost:40001"
$env:YUNXI_AISTIO_INTERNAL_TOKEN = "compose-local-internal-token-at-least-32chars"
.\start-yunxi.ps1
```

如需持久化，可在 `yunxi-config/src/main/resources/config/` 下新增 `aistio.yml` 并在 `imports.yml` 中登记。

重启后 Web 控制台由 aistio 提供（http://localhost:18081 或网关 http://localhost:18090），yunxi 经控制面 http://localhost:18081 注册并接管 Agent。控制台种子用户 `admin`/`admin` 首次启动自动注入。**生产环境务必替换默认 `internal-token`、控制台弱口令与数据库弱口令。**

> 若启动 yunxi 时 MCP 服务尚未就绪，平台会记连接告警但**不会崩溃**，对应工具暂时不可用；将对应 MCP 服务拉起后调用工具会自动重连恢复，无需重启。

## 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│                       接入层 (Gateway)                       │
│      WebSocket · SSE · 飞书 · 钉钉 · 企业微信 · Web API      │
└───────────────────────────────▼──────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│               编排层 (yunxi-runtime + agentscope-harness)        │
│Agent 编排 · 会话管理 · 路由 · 意图引擎 · 技能治理 · 工作空间   │
│统一执行引擎 · 能力装配 (Capability)                          │
└───────────────────────────────▼──────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                执行与事件 (Execution & Events)                │
│  统一执行引擎 · 会话闸门 · 中断/恢复 · agent-event 透传       │
│  框架 Agent 事件流 → SSE（web）/ AG-UI（框架适配器）输出      │
└───────────────────────────────▼──────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                    可观测 (Observability)                    │
│  OTel Span (invoke_agent / chat / execute_tool) → Collector  │
│  MetricRegistry · 结构化日志 · 轨迹模型 TraceRecord/TraceStore│
└───────────────────────────────▼──────────────────────────────┘

┌────────────────────────────────┐   ┌──────────────────────────────┐
│            MCP 协议            │   │   AgentScope-Java 2.1.0-BETA1 │
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
| **yunxi-definition** | 唯一契约平面：AgentSpec 记录、校验与编解码（YAML/JSON） | Jackson, Spring |
| **yunxi-config** | 唯一配置中心：model / state / routing / observability，解析 AgentSpec → ResolvedConfig | Spring Cloud |
| **yunxi-observability** | 轨迹 / 审计 / 度量存储接口 + 内存实现 | OpenTelemetry |
| **yunxi-capability** | 唯一 MCP 注册路径（McpDirectory）、技能注册表、模型路由策略 | AgentScope-Java |
| **yunxi-runtime** | 唯一装配点（AgentConfigurer）、统一执行门面（ExecutionFacade）、运行时上下文富化、主备模型提供器 | Spring Boot, agentscope-harness |
| **yunxi-ingress** | 统一接入网关：聚合 web / A2A / AG-UI / Chat Completions 四协议 | Spring Boot Web, AgentScope-Java 协议 Starter |
| **yunxi-fabric** | aistio 管控面集成（注册、gRPC 对话流、编排触发器、契约与治理、任务接活），默认关闭 | AgentScope-Java aistio 扩展 |
| **yunxi-intelligence** | 智能意图识别（四段式管线：归一化/槽位 → 候选收窄 → 本地决策模型闭集分类 → 决策融合降级）+ MUSE 自进化引擎 | agentscope, Java 子进程沙箱 |
| **yunxi-app** | 启动入口：整合所有平面（端口 40001） | Spring Boot |
| **yunxi-integration-test** | 集成测试 | JUnit, Testcontainers |
| **yunxi-nutritionist-web** | 参考业务前端模块（营养师业务），对应平台 nutritionist Agent，演示如何基于平台 API 构建 Web 应用 | Vite |

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
| [12. 智能系统](docs/guide/12-intelligent-system.md) | 架构师、开发者 |
| [13. A2A 协议](docs/guide/13-a2a-protocol.md) | 架构师 |
| [14. 可观测性](docs/guide/14-observability.md) | 运维工程师 |
| [15. 意图引擎](docs/guide/15-intent-engine.md) | 架构师、开发者 |
| [16. 统一入口网关](docs/guide/16-ingress-gateway.md) | 架构师、开发者 |
| [17. 统一执行引擎](docs/guide/17-execution-engine.md) | 架构师、开发者 |
| [18. Text2SQL 业务 Agent](docs/guide/18-text2sql-assistant.md) | 开发者 |
| [19. 端口规划](docs/guide/19-端口规划.md) | 运维工程师、开发者 |
| [常见问题](docs/guide/faq.md) | 所有读者 |

[📖 查看完整文档](docs/guide/README.md)

## 工作空间目录结构

yunxi 采用 **Agent 优先** 的目录布局，遵循 AgentScope-Java 框架约定。以下为平台**运行时**在进程工作目录自动生成与使用的目录（位于 `.agentscope/workspace/`，已被 `.gitignore` 忽略，**不属于源码仓库**）：

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
└── skills/                   # 全局共享技能目录（运行时由内置技能 tests/ 物化 + MUSE 演进写回生成）
```

用户运行时数据按 `{userId}/` 子目录隔离（由 `HarnessAgent.workspaceFor(userId, sessionId)` 路由，无需自建扫描器）；`skills/` 为框架 Layer3 共享技能目录：内置技能 `SKILL.md` 已由 `ClasspathSkillRepository` 从 jar 零复制加载（Layer2），此处仅物化其 `tests/`（供 MUSE 评估器执行），MUSE 演进技能写回于此并覆盖内置（Layer3 > Layer2）。

## 运维平面（admin）

- **admin 运维平面已接入**：yunxi 已集成 `agentscope-admin-spring-boot-starter`（2.1.0-BETA1 基线远程已发布可用 jar）。`AgentProvisioner` 在布建期把 HarnessAgent 注册进框架 `AgentRegistry`（沿用单一真相源），经 `config/agentscope.yml` 的 `agentscope.admin.enabled=true` 开启，`observability.yml` 暴露 `agentscope-agents` 等只读盘点端点，`SecurityConfig` 对所有非 health 端点要求认证。注意：框架 admin inventory 为全局视角（yunxi agent 定义为 tenant-agnostic）；yunxi 在产品平面额外提供 per-tenant 列示端点 `GET /v1/admin/tenants/{tenantId}/agents`（受全局认证保护），仅返回该租户被授权的 Agent，可与框架全局盘点并存。写操作默认经框架 WriteGuard 门禁（`writeEnabled=false`）。四协议（web / A2A / AG-UI / Chat Completions）与 aistio 管控面集成不受影响。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

## 致谢

- [AgentScope-Java](https://github.com/agentscope-ai/agentscope-java) — 多 Agent 框架（yunxi 的底层运行时）
- [Spring AI](https://spring.io/projects/spring-ai) — 大模型接入抽象（经 AgentScope-Java 模型扩展传递引入）
