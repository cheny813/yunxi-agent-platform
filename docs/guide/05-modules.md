# 05. 模块说明

了解 yunxi Agent Platform 各平面（Maven 模块）的功能和职责。

## 模块概览

### Maven 模块（根 `pom.xml` 的 `<modules>`，共 10 个）

```
yunxi-agent-platform/
├── yunxi-definition        # 唯一契约平面（AgentSpec 记录、校验与编解码 YAML/JSON）
├── yunxi-config            # 唯一配置中心（model/state/routing/observability），解析 AgentSpec → ResolvedConfig
├── yunxi-observability     # 轨迹 / 审计 / 度量存储接口 + 内存实现（独立，被多平面依赖）
├── yunxi-capability        # 唯一 MCP 注册路径（McpDirectory）、技能注册表、模型路由策略
├── yunxi-runtime           # 唯一装配点（AgentConfigurer）、统一执行门面（ExecutionFacade）、运行时上下文富化、主备模型提供器
├── yunxi-ingress           # 传输适配器与统一入口网关（聚合 web / A2A / AG-UI / Chat Completions 四协议）
├── yunxi-fabric            # aistio 控制面 / 数据面接入 + 分布式运行时后端接线
├── yunxi-intelligence       # 意图路由（关键词规则，`yunxi.intent.rules` 配置化）+ MUSE 技能自进化引擎
├── yunxi-app               # 可执行应用打包（统一入口，端口 40001）
└── yunxi-integration-test  # 跨模块集成测试
```

### 附属工程（非 Maven 模块，独立构建）

```
├── agent-web-sdk/          # 浏览器端前端 SDK（npm 包 @yunxi/web-sdk，前端 SDK 单一源）
└── yunxi-nutritionist-web/ # 参考业务前端（营养师业务），演示基于平台 API 构建 Web 应用
```

> 桌面客户端（`yunxi-claw`）和远程节点（`yunxi-agent-node`）已拆分为独立工程，详见对应仓库。

### 依赖链

```
yunxi-definition → yunxi-config → yunxi-capability → yunxi-intelligence → yunxi-runtime
                                          ↑
                                yunxi-observability（独立，被 capability / runtime 依赖）
yunxi-ingress  ──► yunxi-runtime
yunxi-fabric   ──► yunxi-runtime
yunxi-app      ──►（聚合全部平面 + 启动入口）
```

---

## yunxi-definition（契约平面）

### 职责

平台的**唯一契约平面**。承载 `AgentSpec` 记录、校验与编解码（YAML/JSON），是各平面共同消费的唯一契约。被所有业务平面依赖，是编解码契约与 SPI 扩展点的唯一实现位置。

---

## yunxi-config（配置中心）

### 职责

平台的**唯一配置中心**：集中管理 `model` / `state` / `routing` / `observability` 等配置，将 `AgentSpec` 解析为 `ResolvedConfig`，是配置绑定的唯一来源。详见 [06. 配置指南](./06-configuration.md)。

---

## yunxi-observability（可观测性平面）

### 职责

提供轨迹 / 审计 / 度量存储的接口定义与内存实现，被 `yunxi-capability`、`yunxi-runtime` 等平面依赖，是跨平面可观测能力的统一底座。

---

## yunxi-capability（能力平面）

### 职责

平台的**唯一 MCP 注册路径**（`McpDirectory`）、技能注册表与模型路由策略所在平面。负责 MCP 服务器的注册 / 发现、技能元信息的注册、以及按用户与场景的模型路由策略选择，是业务能力接入的控制面。

---

## yunxi-runtime（核心运行时）

平台的心脏：唯一装配点（`AgentConfigurer`）、统一执行门面（`ExecutionFacade`）、运行时上下文富化、主备模型提供器。

### yunxi-runtime 内部包结构（当前扁平包 `io.yunxi.runtime`）

```
yunxi-runtime/src/main/java/io/yunxi/runtime/
├── AgentConfigurer.java            # 唯一装配点（校验→解析配置→路由模型→构建 Toolkit→装配 HarnessAgent→注册）
├── ExecutionFacade.java            # 统一执行门面接口
├── DefaultExecutionFacade.java     # 实现：经 AgentLookup 取已装配 Agent → RuntimeContextEnricher 富化 → agent.streamEvents
├── ModelFactory.java               # 模型工厂（按 modelId 解析 AgentScope Model，生产装配委托框架 ModelRegistry）
├── AgentLookup.java               # 框架 AguiAgentRegistry 薄委托 seam（configure 后登记，单例 Agent 真相源在框架 AguiAgentRegistry）
├── AgentDefinitionLoader.java      # 加载 classpath*:agent-definitions/**/*.yml 并校验注册进 ConfigCenter
├── DistributedRuntimeConfig.java   # 分布式运行时配置（stateStore / sessionTurnGate 等）
├── MuseToolRegistrar.java          # MUSE 元工具注册 SPI
├── RuntimeContextEnricher.java / RuntimeContextEnrichers.java  # 运行时上下文富化
├── Settle.java / AgentCommand.java / AgentBuiltEvent.java   # 执行结算（record Settle，含 Status 枚举）/ 执行命令（record AgentCommand，ExecutionFacade 入参）/ 构建事件（Spring ApplicationEvent）
├── AgentBuildContributor.java      # 构建期横切贡献点（如 aistio Level-2 事件观测）
├── AgentSessionBusyException.java / NoSuchAgentException.java
├── middleware/   # 协议无关横切中间件（对所有协议统一生效，由 AgentConfigurer 装配）
│   ├── ChatAuditMiddleware.java            # 对话审计
│   ├── HitlMiddleware.java                 # 人工审核（HITL）
│   ├── OtelTracingMiddleware.java          # OpenTelemetry 链路追踪
│   ├── ResilienceMiddleware.java           # 限流 / 超时 / 熔断
│   └── SessionToolGroupActivationMiddleware.java / SessionToolGroupRegistry.java  # 会话工具组激活
└── permission/PermissionTemplateResolver.java  # 权限模板解析
```

### 核心装配与执行

| 组件 | 说明 |
|------|------|
| `AgentConfigurer` | **唯一装配点**：校验 `AgentSpec` → 经 `ConfigCenter` 解析 `ResolvedConfig` → 经 `ModelRoutingPolicy`+`ModelFactory` 路由模型（主备由 `ConfigurableModelRoutingPolicy` 承载）→ 从 `McpDirectory`/`MuseToolRegistrar` 构建 `Toolkit` → 装配 `HarnessAgent` → 应用协议无关 `MiddlewareBase` 链 → 注册进框架 `AguiAgentRegistry`（经 `AgentLookup` 收口，装配集中，新增横切只改此处） |
| `ExecutionFacade` / `DefaultExecutionFacade` | **统一执行门面**：接收 `ConversationRequest`，经 `AgentLookup` 取框架 `AguiAgentRegistry` 中已装配 Agent，经 `RuntimeContextEnricher` 富化 `RuntimeContext`，调用 `agent.streamEvents` 并管理会话轮次闸门（`SessionTurnGate`） |
| `ModelFactory` | 统一模型工厂，按 modelId 解析 AgentScope `Model`（生产装配委托框架 `ModelRegistry`；主备路由策略在 `yunxi-capability` 的 `ModelRoutingPolicy` / `ConfigurableModelRoutingPolicy`） |
| `AgentLookup` | 框架 `AguiAgentRegistry` 薄委托 seam，按 `agentId` 提供已装配 Agent 查询（单例 Agent 真相源在框架 `AguiAgentRegistry`） |
| `AgentDefinitionLoader` | 加载 `classpath*:agent-definitions/**/*.yml`，校验后注册进 `ConfigCenter`（AgentSpec 已扁平化，`orchestration` 编排门禁随旧 schema 移除） |
| `MuseToolRegistrar` | MUSE 元工具注册 SPI（仅 `yunxi.muse.enabled=true` 时存在实现） |

> 说明：上层 Hook 体系已全面迁移为 AgentScope 原生 Middleware 体系。平台自建的协议无关横切全部落在 `io.yunxi.runtime.middleware`，由 `AgentConfigurer` 统一装配，对所有协议（web / A2A / AG-UI / Chat Completions）生效；入口层（`yunxi-ingress`）不再各自实现拦截器链。

#### MCP 协议

MCP 工具由 AgentScope 框架原生管理：`AgentConfigurer.buildToolkit()` 经 `McpDirectory.get(serverName)` 取服务规格、`McpClientBuilder`（`mcpDirectory.buildClient(...)`）连接 MCP 服务器，再 `Toolkit.registration().mcpClient(wrapper).group(name).apply()` 注册工具分组；断线重连由 yunxi 侧 `ReconnectingMcpClientWrapper` 兜底（框架 `McpClientBuilder` 不原生重连）。

#### 跨平面组件归属（非 yunxi-runtime 内部类）

| 能力 | 真实位置 |
|------|----------|
| MCP 注册路径 / 模型路由 | `yunxi-capability`（McpDirectory / ModelRoutingPolicy / RoutedModel） |
| 配置解析（AgentSpec → ResolvedConfig） | `yunxi-config`（ConfigCenter / ResolvedConfig） |
| 契约（AgentSpec / 校验 / 编解码） | `yunxi-definition`（AgentSpec / AgentSpecValidator / SpecCodec） |
| 意图引擎 / MUSE 自进化 | `yunxi-intelligence`（DefaultIntentEngine / SelfEvolutionEngine 等，见本节末） |
| 传输适配与四协议入口 | `yunxi-ingress`（ConversationController / IngressGateway / ProtocolAdapter） |
| aistio 控制面 / 数据面接入 | `yunxi-fabric.aistio`（AistioConnector / YunxiFrameworkAdapter） |

---

## yunxi-ingress（统一入口网关）

### 职责

平台的**传输适配器与统一入口网关**，集中承载 web / A2A / AG-UI / Chat Completions 四类 web 级协议的所有外部 HTTP 入口与边缘横切（认证 / 限流 / 可观测）、协议服务端接入与路由。IM 渠道（钉钉 / 企微 / 飞书 / GitHub / GitLab / 微信 iLink）/ 编程式 ChatUi 走第二条入口路径（见 [16.8 IM 渠道网关](./16-ingress-gateway.md)）。聚合 web / A2A / AG-UI / Chat Completions 四协议，详见 [16. 统一入口网关](./16-ingress-gateway.md)。

> aistio 管控面的 HTTP 边缘（基址 `/agentscope`，如 `/agentscope/health`）由 AgentScope-Java 框架 aistio 扩展的自注册端点提供，yunxi 不另行实现该控制器；`yunxi-ingress` 与 `yunxi-fabric` 保持编译隔离。

---

## yunxi-fabric（aistio 控制面集成）

### 职责

承载 yunxi 与 aistio 管控面的全部对接，是**传输层 / 外部集成**，不携带任何推理语义：

- **注册与心跳**：`AistioConnector` 复用 AgentScope-Java 框架 `Aistio.instrument(agent, AistioConfig, adapter)` 完成自注册、心跳与 gRPC 对话流；yunxi 不自建注册与心跳调度。
- **编排对齐**：yunxi 经 `AgentDefinitionLoader` 加载 AgentSpec 并注册进 `ConfigCenter`（当前 `AgentSpec` 不含 `orchestration` 字段，编排门禁随旧 schema 移除），控制面对齐由 `AistioIntegration` 承担；yunxi 不另建编排 trigger / translator / exporter。
- **数据面视图**：由框架 `HarnessAgent` 直接提供，yunxi 不另建 `DataPlaneContract` / 治理视图类。HTTP 边缘（基址 `/agentscope`）由框架 aistio 扩展的自注册端点提供，ingress 对 `yunxi-fabric` 保持编译隔离。
- **任务接活**：`YunxiFrameworkAdapter` 把控制面下推的 `AgentTaskAssignment` 转交框架官方 `HarnessAgentTaskStarter`，yunxi 不另研执行器。

### 包结构

```
yunxi-fabric/src/main/java/io/yunxi/fabric/
├── aistio/
│   ├── AistioConnector.java            # 复用框架 Aistio.instrument 的自注册 / 心跳 / gRPC 对话流连接器
│   ├── AistioIntegration.java          # aistio 集成装配（单一集成点，实现 AgentBuildContributor）
│   ├── AistioIntegrationProperties.java # yunxi.aistio.* 配置绑定
│   ├── AistioLifecycle.java            # 生命周期
│   ├── YunxiFrameworkAdapter.java      # 框架适配桥（AgentTask 接活转交官方 Starter）
│   └── YunxiAgentBuilderSupplier.java  # 工作区构建器底座（@Component）
├── distributed/
│   ├── InMemoryDistributedRuntimeConfig.java
│   └── RedisDistributedRuntimeConfig.java
└── RedisSessionTurnGate.java           # 跨实例会话轮次闸门
```

> **启用开关**：`yunxi.aistio.enabled`（默认 `false`）。关闭时 `yunxi-fabric` 的 aistio 集成（`AistioIntegration` 等）不装配，控制面相关能力整体离线，不影响其它模块。控制面编排脚本见 [08. 部署运维 · aistio 控制面本地编排](./08-deployment.md#aistio)（`deploy/aistio/`）。

### 与 yunxi-runtime 的依赖边界（单一方向）

`yunxi-fabric` **只依赖 `yunxi-runtime`**，反向不允许（否则形成 Maven 循环依赖）。共享状态经由框架 `DistributedStore` 与 `yunxi-runtime` 的 `AgentLookup` / `RuntimeContextEnricher` 提供，fabric 侧仅注入使用。`yunxi-runtime` 不得再引用 `yunxi-fabric` 任何类。

### 装配

`yunxi-fabric` 是库模块，由 `yunxi-app` 引入后，其 `@Configuration` / `@Component` 经主类 `scanBasePackages = io.yunxi` 自动扫描装载，无需在 `yunxi-app` 增加任何扫描配置。

---

## yunxi-intelligence（意图引擎 + MUSE 自进化引擎）

### 意图引擎

平台的**意图路由能力**：关键词规则「意图标签 → 触发关键词列表」经 `yunxi-config/src/main/resources/config/intent.yml` 的 `yunxi.intent.rules` 配置化（`KeywordIntentResolver` 只做匹配，Java 代码不含任何业务词条）；会话入口请求未显式指定 `agentId` 时，由入口织面调用 `IntentEngine.resolve(text)` 回填目标 Agent，命中即路由、未命中回退 `default-agent-id`。当前不含 NER / 语义改写 / LLM 分类；需要更复杂策略时新增 `IntentResolver` 实现并注册进 `DefaultIntentEngine`。详见 [15. 意图引擎](./15-intent-engine.md)。

### MUSE 自进化引擎

提供技能自进化闭环：沙箱评估→LLM 修补→剪枝合并。严格遵循"薄适配层（thin adapter layer）"原则——只实现 AgentScope 没有的能力，其余全部复用框架。

#### 核心组件

| 组件 | 说明 |
|------|------|
| `SelfEvolutionEngine` | 闭环编排：评估→修补→再评估，首轮全通过立即返回，连续无进展提前停止 |
| `DefaultSkillEvaluator` | 沙箱评估器：子进程/Docker 跑 Java 自测，退出码判通过 |
| `SkillRefiner` | LLM 修补器：注入失败用例名+stderr/stdout 到提示词，经 Model.stream 迭代修补 |
| `DefaultSkillPruner` | 技能剪枝：按使用率归档 + CJK 二元切分 TF-IDF 余弦相似度自动合并重复技能（画像驱动判据为设计态，未接入） |
| `UsageProfile`（模型） | 画像特征模型（`muse.prune` 包）；聚合器 `UsageProfileAggregator` 与画像存储尚未实现（设计态） |
| `BuiltinSkillLoader` | 内建技能测试物化器：启动时把 classpath 内建技能的 `tests/` 物化到 `yunxi.muse.builtin-export-dir`（供评估器执行）；`SKILL.md` 由 `ClasspathSkillRepository` 零复制加载，不再落地 |
| `SkillEvalTool` / `SkillRefineTool` / `SkillPruneTool` | 元工具：把以上能力挂给 LLM，Agent 自主调用自进化 |

#### 启用

```yaml
yunxi:
  muse:
    enabled: true      # 默认开启（matchIfMissing=true）；设为 false 可关闭全部 MUSE Bean
    model: qwen-max    # 评估 / 修补所用模型名（经 yunxi ModelFactory 解析）
    provider: dashscope
```

> MUSE 的修补模型取自 `yunxi.muse.model`（默认 `qwen-max`），由 `SkillRefiner` 经 `ModelFactory.create(name)` 走统一模型解析，不读 `models.yml` 的 `routing` / `defaults`。

未启用时 MUSE 全部 Bean 不加载（`@ConditionalOnProperty`），编译期零引用。

#### 设计原则

- **薄适配层（thin adapter layer）**：沙箱、评估、修补、剪枝——全部是 AgentScope 没有的能力，不造框架已有的轮子
- **零侵入**：通过 SPI 接口 + `ObjectProvider` 懒注入，不修改 AgentScope 框架代码
- **升级友好**：内部调用全部走 AgentScope 公开 API，不依赖框架内部实现类
- **自包含**：中文分词（CJK 二元切分）+ TF-IDF + 余弦相似度，零外部依赖
- **画像只读**：剪枝可参考使用者画像（使用者广度维度），但方向严格单向——MUSE 只**读**画像，不回写

详见 [10. 技能系统 - MUSE 自进化引擎](./10-skills.md#muse-自进化引擎)。

---

## yunxi-app（应用打包与启动）

### 职责

可执行应用打包模块，聚合全部业务平面，提供统一入口（`SecurityConfig` 与启动引导），对外端口 `40001`。构建产物为 `yunxi-app-*.jar`，可经 `java -jar yunxi-app-*.jar` 或 `mvn spring-boot:run -pl yunxi-app` 启动，亦可经 `yunxi-app/Dockerfile` 构建为容器镜像。此外 yunxi-app 还承载 **IM 渠道网关**（`ImChannelGatewayConfig`）：经 AgentScope-Java 的 `GatewayBootstrap` 接入框架 `HarnessGateway`，把钉钉 / 企业微信 / 飞书 / GitHub / GitLab / 微信（iLink）/ 编程式 ChatUi 渠道注册进统一 `ChannelManager`（详见 [16.8 IM 渠道网关](./16-ingress-gateway.md)）。

---

## yunxi-integration-test（集成测试平面）

### 职责

跨模块启动与契约测试平面，承载端到端验证（如 aistio 真实控制面注册、四协议入口契约等）。

---

## 模块依赖关系

```
yunxi-app
    ↓
yunxi-runtime / yunxi-ingress / yunxi-fabric / yunxi-intelligence（按依赖链回溯）
```

业务能力通过 MCP 协议接入，不依赖 Java 模块：

```
yunxi-mcp-servers/          ← 独立项目，30+ MCP 服务
├── mcp-data                # 业务数据查询（端口 40602）
├── mcp-database            # 通用数据库查询（端口 40101）
├── mcp-redis               # Redis 操作（端口 40102）
├── mcp-milvus              # 向量检索（端口 40103）
├── mcp-filesystem          # 文件系统（端口 40501）
├── mcp-git                 # Git 操作（端口 40509）
└── ...                     # 30+ 更多 MCP 服务
```

---

## 端口规划

平台各服务默认占用端口如下（均可在对应配置文件或启动参数中覆盖）：

| 服务 | 端口 | 说明 |
|------|------|------|
| yunxi-app | 40001 | 统一入口（可执行服务，内含 yunxi-runtime 核心框架） |
| aistio 管控面 | 18081 / 18090 | 可选，见 [08. 部署运维 · aistio 控制面本地编排](./08-deployment.md#aistio)（`deploy/aistio/`） |
| mysql | 3306 | 业务数据（在 `yunxi-config` 中配置） |
| redis | 6379 | 会话 / 缓存 / 分布式锁（见 [06. 配置指南](./06-configuration.md)） |
| nacos | 8848 | 配置中心（MCP 动态注册底座） |
| milvus | 19530 | 向量检索 |
| MCP 服务组 | 40101-40103、40501-40509、40602 | `yunxi-mcp-servers` 仓库提供 |
| OTLP 接收端 | 4317 / 4318 | OpenTelemetry 导出目标（可选） |

---

**上一页**: [04. 架构设计](./04-architecture.md)  
**下一页**: [06. 配置指南 →](./06-configuration.md)
