# 06. 配置指南

本章讲解 yunxi Agent Platform 的配置体系。

## 配置管理理论

### 什么是配置管理

**配置管理**是将应用程序中可能变化的部分外部化的实践。

**为什么需要配置管理**：
| 问题 | 解决方案 |
|------|----------|
| 环境差异 | 不同环境不同配置 |
| 敏感信息 | 密码等不放入代码 |
| 动态调整 | 无需重启修改配置 |
| 团队协作 | 配置与代码分离 |

### 配置的来源与优先级

**配置来源**（按优先级从高到低，标准 Spring Boot 外部化配置顺序）：
```
1. 命令行参数
2. JVM 系统属性 (-D)
3. 环境变量
4. yunxi-config 的 config/*.yml（由 config/imports.yml 全量加载，统一配置中心）
5. yunxi-app 的 application.yml（bootstrap 默认值）
6. @ConfigurationProperties / @PropertySource 默认值
```

**说明**：
- yunxi **不使用** `application-{profile}.yml` 分层文件承载业务配置；所有业务配置片段（`server` / `redis` / `models` / `mcp` / `a2a` / `security` / `observability` / `aistio` 等）均放在 `yunxi-config/src/main/resources/config/`，由 `config/imports.yml` 一次性 `spring.config.import` 全量加载（见 [06. 配置指南 · 配置文件结构](#配置文件结构)）。
- `spring.profiles.active` 仅控制带 `@Profile` 注解的 Bean 启用（当前仅 `muse-docker` 等少数 Bean 用它切环境），**不改变**配置文件的加载范围。
- 越靠近运行时的配置优先级越高（命令行 > 环境变量 > `config/*.yml` > 默认值），高优先级覆盖低优先级。

---

## 配置体系设计

### 配置层次架构

```
┌─────────────────────────────────────────┐
│  运行时配置（最高优先级）                │
│  - 命令行参数                           │
│  - 环境变量                             │
├─────────────────────────────────────────┤
│  统一配置中心（config/*.yml）           │
│  - config/server.yml / redis.yml        │
│  - config/models.yml（模型 catalog）    │
│  - config/mcp.yml / a2a.yml             │
│  - config/security.yml / observability  │
│  （均由 config/imports.yml 加载）       │
├─────────────────────────────────────────┤
│  bootstrap 默认配置                      │
│  - application.yml（yunxi-app）         │
└─────────────────────────────────────────┘
```

### Spring Boot 配置原理

**配置绑定机制**：
```java
// 1. 定义配置类
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    private String defaultProvider;
    private Map<String, ProviderConfig> providers;
}

// 2. YAML 配置（密钥推荐用环境变量注入，勿硬编码）
llm:
  default-provider: dashscope
  providers:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}

// 3. 自动绑定
@Autowired
private LlmProperties llmProperties;
```

**Profile 机制**：
```java
// 根据环境加载不同配置
@Profile("dev")
@Bean
public DataSource devDataSource() { }

@Profile("prod")
@Bean
public DataSource prodDataSource() { }
```

---

## 核心配置

### 数据库配置

> **yunxi 平台运行时默认不连接 MySQL**。会话状态、跨实例锁等运行时数据走 Redis（见下节，`redis.yml` 以 `spring.data.redis` 前缀配置）；轨迹存储目前只有内存实现（`InMemoryTraceStore`，且运行时尚未写入）。`deploy/infra` 编排中的 MySQL 容器仅服务于**可选的** `mcp-nutrition` MCP 服务（独立 Java 进程，库 `yunxi_nutrition`，表结构由 `deploy/infra/init/nutrition-schema.sql` 初始化）。

**连接池原理（MySQL 适用场景）**：
```
┌─────────────────────────────────────────┐
│  连接池（Connection Pool）               │
│                                         │
│  应用 ──→ 从池中获取连接 ──→ 执行 SQL    │
│            ↓                            │
│         归还连接到池                     │
│                                         │
│  优势：                                  │
│  - 避免频繁创建/销毁连接                  │
│  - 控制并发连接数                         │
│  - 提高性能                               │
└─────────────────────────────────────────┘
```

```yaml
# 仅 mcp-nutrition 等需要 MySQL 的组件使用；yunxi 平台本身无需配置数据源。
spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/${MYSQL_DATABASE:yunxi_nutrition}?useUnicode=true&characterEncoding=utf-8
    username: ${MYSQL_USERNAME:root}
    password: ${MYSQL_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
```

### Redis 配置

**Redis 使用场景**：
| 场景 | 说明 | 本框架应用 |
|------|------|-----------|
| 缓存 | 加速数据访问 | Agent 响应缓存 |
| 会话 | 分布式会话 | 用户会话存储 |
| 队列 | 异步任务 | 消息队列 |
| 计数 | 频率限制 | 限流控制 |

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
```

### LLM 配置

模型以「命名模型清单」形式外部化配置在 `yunxi-config/src/main/resources/config/models.yml` 的 `yunxi.models` 段，代码与 Agent 定义中均不出现具体模型名。

- `catalog`：命名模型清单，每条声明 `provider` / `model` / `api-key-env` / `type`（`llm` / `decision` / `embedding`）。
- `routing`：将 `agentId` 映射到 `catalog` 中的主模型（`primary`）+ 兜底（`fallbacks`）；未声明路由的 Agent 走**自动选型**（`AutoModelProvider` 按任务信号动态选最便宜且满足能力的模型）。
- `defaults`：各用途分类默认模型（`llm` / `decision` / `embedding`）。

```yaml
yunxi:
  models:
    catalog:
      qwen-plus:
        provider: dashscope
        model: qwen-plus
        api-key-env: DASHSCOPE_API_KEY
        type: llm
      qwen3-8-27b:
        provider: dashscope
        model: qwen3.8-27b
        api-key-env: DASHSCOPE_API_KEY
        type: llm
      local-decision:
        provider: ollama
        model: tev1:0.8b
        type: decision
      local-embedding:
        provider: ollama
        model: bge-m3:latest
        type: embedding
    routing:
      example-assistant:
        primary: auto
        fallbacks: [qwen-plus]
    defaults:
      llm: qwen3-8-27b
      decision: local-decision
      embedding: local-embedding
```

> 密钥经环境变量注入（`DASHSCOPE_API_KEY` / `OPENAI_API_KEY` 等），不写进 YAML。本地 Ollama 模型（decision / embedding）需本机已拉取对应模型且 `ollama` 服务在 `OLLAMA_BASE_URL`（默认 `http://localhost:11434`）可达。多模态（图文 / 视频）与多语言输入由自动选型按信号分支到 `qwen3.8-omni-flash` / `qwen-mt-uni` 等候选。
> 为某 Agent 固定模型：在 `routing` 段为该 `agentId` 显式声明 `primary`（覆盖系统默认自动选型）。

### 生成参数配置

通过 `agentscope.core.generation` 全局控制 LLM 生成参数，Agent 定义 YAML 中的配置会覆盖全局默认：

```yaml
agentscope:
  core:
    generation:
      temperature: 0.7        # 生成温度（默认 0.7）
      max-tokens: 4096        # 最大输出 token 数（默认 4096）
      top-p: 0.9              # top_p 采样参数（默认 0.9）
      cache-control: false    # 是否启用 Prompt Caching（默认关闭）
```

**Prompt Caching**（`cache-control: true`）：

| 提供商 | 机制 | 说明 |
|--------|------|------|
| OpenAI | 自动前缀缓存（>1024 token） | 框架自动添加 `cache_control: {"type": "ephemeral"}` 到 system 消息和最后一条消息 |
| Anthropic | 显式 cache_control 标记 | 通过 SDK 原生支持 |
| DashScope (Qwen) | 自动前缀缓存 | 框架自动处理 |

> **注意**：启用缓存时，确保 system prompt 中不包含时间戳、随机数等动态内容，否则会导致缓存频繁失效。

### Agent 模型配置（按 Agent 覆盖 / 多租户）

每个 Agent 定义 YAML 的 `model:` 段可独立指定模型及其参数，由 `ModelFactory.create()` 解析为框架 Model 实例。所有生成参数均可覆盖全局 `agentscope.core.generation` 默认值。

```yaml
model:
  provider: dashscope      # 提供商：openai / dashscope / anthropic / claude / deepseek / baidu / huawei / gemini / ollama
  modelName: qwen-plus     # 模型名
  temperature: 0.5         # 覆盖全局 generation.temperature
  maxTokens: 2000          # 覆盖全局 generation.max-tokens
  topP: 0.9                # 覆盖全局 generation.top-p
  cacheControl: false      # 覆盖全局 generation.cache-control
  stream: true             # 是否流式输出（默认 true）；按 Agent 显式 false 时关闭
  # ── 以下字段实现「按 Agent 覆盖 / 轻量多租户」，不填则回退全局配置 ──
  apiKey: ${AGENT_API_KEY:}    # 每 Agent 独立账号；不为空时透传给所有提供商
  baseUrl: https://...     # 自定义接入点（OpenAI 兼容网关等）
```

**多租户开关（无需额外 boolean）**：

平台的多租户在架构层是工作空间/数据隔离（`RuntimeContext(userId,sessionId)` 共享 Agent 实例），模型在 Agent 创建时构建一次、跨租户复用。因此「同一 Agent 运行时由每个终端用户带各自 LLM Key」这种重多租户不必要，但 `model.apiKey` / `model.baseUrl` 提供轻量多租户能力：

- **单租户（默认）**：不填 `apiKey`/`baseUrl` → 回退到 `models.yml` catalog 中同名模型的 `api-key-env` 环境变量，所有 Agent 共用平台账号。
- **多租户（按需）**：在 Agent 定义中显式填写 `apiKey`/`baseUrl` → 经框架 `ModelCreationContext` 透传给对应提供商工厂，实现每 Agent 独立账号。openai / dashscope / anthropic / claude / deepseek 经框架 `ModelCreationContext` 透传 apiKey/baseUrl，实现每 Agent 独立账号；gemini / ollama 无自定义工厂，由 SPI 提供商经同一 context 自动消费。

**配置优先级（统一）**：`routing.<agentId>.primary`（显式路由）> `defaults.<type>`（按用途默认：llm / decision / embedding）> 自动选型（`AutoModelProvider` 按任务信号动态选最便宜且满足能力的模型）。密钥经 `models.yml` catalog 中各模型的 `api-key-env` 环境变量注入，不写进 YAML。

> **实现要点**：`ModelFactory` 注册的是 `ModelRegistry` 的 `ContextModelFactory`（`create(modelId, context)` 两参）重载，而非仅 1 参的 `ModelFactory`；`create()` 通过 `ModelRegistry.resolve(modelId, context)` 解析，把 `apiKey`/`baseUrl`/`stream`/`GenerateOptions` 封装为 `ModelCreationContext` 传递，确保官方提供商也能消费这些覆盖值。

### 模型缓存策略（ModelRegistry CachePolicy）

`ModelFactory` 始终通过 `ModelRegistry.resolve(modelId, context)` 解析模型，并且**从不显式设置 `CachePolicy`**，因此自动套用框架的 `DEFAULT` 策略。该策略与上文「按 Agent 覆盖 / 多租户」的安全语义天然对齐：

| 场景 | `ModelCreationContext` 是否为空 | 缓存行为 |
|------|-------------------------------|---------|
| 单租户（Agent 不覆盖任何字段，走 `ModelCreationContext.empty()`） | 空 | 按 `modelId` 缓存（legacy 行为），复用同一 Model 实例，避免重复构建 |
| 多租户（Agent 填了 `apiKey` / `baseUrl` / `stream`，或带了 `GenerateOptions` 组件） | 非空 | `DEFAULT` 下**不缓存**，杜绝不同租户的 Key / BaseURL / stream 复用到同一实例 |

**`CachePolicy` 取值**（框架 `io.agentscope.core.model.CachePolicy`）：

- **`DEFAULT`**：简单解析（`resolve(String)`）保持按 `modelId` 缓存；带非空 `context` 解析（`resolve(String, context)`）默认不缓存。yunxi 当前采用此默认，无需任何额外配置。
- **`DISABLED`**：永不缓存，每次解析都新建 Model 实例。
- **`ENABLED`**：显式开启缓存；必须以 `cacheId(...)` 表达租户或配置维度的身份。若搭配 `option(...)` / `component(...)` 使用却未提供 `cacheId`，框架会抛 `IllegalArgumentException`。

**缓存策略对自定义工厂与 SPI 提供方一致生效**：`ModelRegistry` 解析顺序为 named → cache → 用户工厂（最新注册在前）→ SPI 提供方。yunxi **不注册 Provider 正则、也不自建 Provider**——`ModelFactory` 只把 `provider:model` 透传给 `ModelRegistry.resolve`，DashScope / OpenAI / Anthropic / Gemini / DeepSeek / Ollama 等由框架内置 Provider 创建；未内置的提供商由 `ServiceLoader` 从 `META-INF/services/io.agentscope.core.model.spi.ModelProvider` 自动发现并调用 `provider.create(modelId, context)`。SPI 接口为简单提供方保留了 `supports(String)` / `create(String)` 的兼容默认实现（`context` 参数在 `default` 方法中被忽略），因此只实现旧 1 参 API 的厂商也能即开即用。

> **可选优化（非必须）**：若希望「仅带生成参数、但配置完全相同的单租户 Agent」也能命中缓存，可在 `buildContext` 中改用 `.cachePolicy(ENABLED).cacheId(<配置指纹>)`，但必须为 `GenerateOptions` 组件提供显式 `cacheId`，否则会触发框架校验异常。当前保持 `DEFAULT` 是最稳妥的安全默认。

### Shell 命令安全配置

通过 `agentscope.core.shell` 控制框架 `ShellCommandTool` 的安全策略：

```yaml
agentscope:
  core:
    shell:
      allowed-commands: [ls, cat, grep, python, node]  # 白名单：自动执行的命令
      approval-enabled: false                           # 是否启用人工审批回调
      base-dir: /data/workspace                         # 工作目录限制（null=不限制）
```

**安全策略**：白名单内的命令自动执行；白名单外的命令如果启用了审批则等待人工确认，否则直接拒绝。框架还内置了多命令分隔符检测（`&`、`|`、`;`、换行符）和路径穿越检测（`../`）。

---

## 知识库（RAG）配置

> **⚠️ 实现说明**：yunxi 的 RAG 检索是**产品平面自持能力**，由自研 `@Tool` `rag_retrieve` 经 `RagToolRegistrar` SPI 挂入 Agent toolkit 的 `rag` 工具组，**不依赖**框架 `io.agentscope.core.rag` 包（`Knowledge` / `GenericRAGHook`）——该包自框架 2.0.0 起已标记为 `@Deprecated(forRemoval = true)`，官方明确“RAG 包已移除，检索应在应用层自行集成”。因此 yunxi 在框架之上自持检索抽象 `DocumentChunkStore` 与嵌入/向量后端，而非接入框架已废弃的 RAG 契约。

### 检索默认参数（向量检索）

RAG 检索由 `yunxi.rag.*` 配置驱动，默认走**向量检索（仅向量模式）**：

```yaml
yunxi:
  rag:
    enabled: true                 # 启用 RAG 检索（启用后默认走向量检索）
    default-top-k: 5              # rag_retrieve 默认返回条数
    milvus:                       # 向量库连接（docker-compose 预置 Milvus:19530）
      host: ${MILVUS_HOST:127.0.0.1}
      port: ${MILVUS_PORT:19530}
      database: ${MILVUS_DATABASE:default}
      username: ${MILVUS_USERNAME:root}
      password: ${MILVUS_PASSWORD:Milvus}
      collection-prefix: yunxi_rag # 集合名前缀；多租户/多知识库经 collection 字段过滤隔离
      dimension: 1024             # 与嵌入模型维度一致（bge-m3 = 1024）
    embedding:                    # 嵌入服务（Ollama bge-m3，本机:11434）
      host: ${OLLAMA_HOST:127.0.0.1}
      port: ${OLLAMA_PORT:11434}
      model: bge-m3
```

> **降级说明（重要）**：RAG 当前为**仅向量模式**，无关键词静默回退。Milvus 或 Ollama 嵌入服务不可用时，`rag_retrieve` 调用将返回明确的"检索暂不可用"提示，而非降级为无增强透传。请在生产环境确保 Milvus + Ollama(bge-m3) 可达。

### 数据存储与检索架构

```
文档分块入库：DocumentChunkStore.save(chunk)
    → Ollama(bge-m3) 嵌入文本
    → 正排内容写 BaseStore（命名空间 ["yunxi-rag", collection]，唯一真相源）
    → 向量写 Milvus（派生索引，可由正排重建）

查询：rag_retrieve({collection, query, topK})
    → Ollama(bge-m3) 嵌入 query
    → Milvus 最近邻（filter: collection == X && user_id == Y）
    → 回查 BaseStore 还原内容 → 返回 DocumentChunk 列表
```

参考 [03. 核心概念](./03-concepts.md#应用层-ragapplicationrag) 中的 RAG 说明。检索能力由 yunxi 产品平面的 `DocumentChunkStore` 抽象与自研 `@Tool` `rag_retrieve` 承载，框架原生 rag 包自 2.0.0 起已废弃。

---

## Agent 多 Profile 配置

### 配置结构

```yaml
agent:
  name: business-assistant      # Agent 名称
  description: 业务数据管理助手
  enabled: true

  # 编排模式：single / supervisor / pipeline / routing
  orchestration: single

  # 默认 RAG 模式：yunxi 当前不在 AgentSpec 暴露该字段，统一走框架默认 GENERIC

  # 默认 prompt（未指定 profile 时使用）
  prompt: |
    你是一个专业的业务数据管理助手...

  # 默认工具组配置
  toolsGroup:
    systemToolsGroup: default
    mcpServersToolsGroup: [formfill, database, milvus]

  # 编排为 supervisor 时的专家列表（orchestration: supervisor 时生效）
  # orchestration:
  #   experts:
  #     - name: data-searcher
  #       prompt: 你是数据检索专家...

  # Profile 映射：name -> ProfileDefinition
  profiles:
    # Profile 1：智能咨询 — 聊天模式
    chat:
      label: 智能咨询
      description: 回答业务咨询问题
      mode: chat                    # ← 工作模式（ProfileDefinition.mode）
      prompt: |                     # ← 换一个轻量 prompt
        你是一个专业的业务顾问...

    # Profile 2：内容生成 — 专家模式
    business-make:
      label: 内容生成
      description: 生成业务内容
      mode: expert                  # ← 专家模式
      prompt: |                     # ← 覆盖 prompt
        你是一个专业的业务数据管理助手...
```

### 模式选择示例

#### 简单场景：只需选模式

```yaml
agent:
  name: coding-assistant
  description: 代码编写与审查助手
  orchestration: expert

  profiles:
    chat:
      label: 编程咨询
      mode: chat                    # 纯对话，快速响应

    code-review:
      label: 代码审查
      mode: expert                  # 全功能，多专家协作
```

#### 高级场景：完全自定义

```yaml
agent:
  name: custom-agent
  description: 高级自定义智能体
  orchestration: advanced           # 自定义命名模式（AgentDefinition.modes 定义）

  profiles:
    custom-flow:
      label: 自定义流程
      mode: advanced
      toolGroups: [database]        # 系统内置组（agent/memory/filesystem/execute/page/general）+ MCP 组
      mcpServers: [milvus]
      maxIters: 30
      enablePlanNotebook: true
      enableMetaTool: false         # 关闭动态工具，使用固定工具集
      prompt: |
        你是一个自定义智能体...
```

### 向后兼容性

| 场景 | 行为 |
|------|------|
| 旧请求，无 `profile` 字段 | 使用默认 Agent（现有行为不变） |
| 旧 YAML，无 `orchestration` 字段 | 默认为 `single`（现有行为不变） |
| Profile 显式 `mode: chat` | 应用聊天模式默认参数 |
| 新请求，`profile=chat` | 路由到 chat Profile |
| 请求的 profile 不存在 | 回退到默认 Agent + 日志警告 |

### Profile 继承与覆盖规则

```
Agent 级别默认配置
    │
    ▼
┌─────────────────────────────────────┐
│  Profile 继承 Agent 默认值           │
│  - 未设置的字段继承 Agent 级别配置    │
│  - 显式设置的字段覆盖 Agent 配置     │
└──────────┬──────────────────────────┘
           ▼
┌─────────────────────────────────────┐
│  Mode 展开为具体参数                 │
│  - CHAT    → 应用聊天默认值          │
│  - EXPERT  → 应用专家默认值          │
│  - ADVANCED→ 保留业务自定义          │
└──────────┬──────────────────────────┘
           ▼
┌─────────────────────────────────────┐
│  业务显式配置覆盖 mode 默认          │
│  - 业务配置优先级最高                │
└─────────────────────────────────────┘
```

---

## 安全配置

### 鉴权与安全模型

> **说明**：本框架不设独立的网关 Token / MCP 网关鉴权配置（`agent.gateway.token`、`mcp.auth` 均不存在）。安全模型由两层构成：**应用层**通过 `SecurityContext` 完成用户认证（见下）；**Agent 执行层**由 AgentScope 原生权限引擎在 `onActing` 阶段强制执行（平台 `PermissionConfig` 将 YAML 中的 HITL / 权限规则映射为框架原生 `PermissionContextState` 的 ASK 规则）。

### SecurityContext 用户认证

**SecurityContext** 提供统一的用户认证信息获取接口，支持多种认证方式：

#### 1. 请求头方式（默认）

前端请求时携带用户ID：

```javascript
fetch('/api/conversations/chat/stream', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'X-User-Id': 'user123'
    },
    body: JSON.stringify({ agentName: 'business-assistant', message: '你好' })
});
```

后端使用：

```java
@Autowired
private SecurityContext securityContext;

public void someMethod() {
    String userId = securityContext.getCurrentUserId();
}
```

#### 2. JWT Token 集成

配置密钥：

```yaml
jwt:
  secret: your-secret-key-at-least-256-bits-long
  header: Authorization
  prefix: "Bearer "
```

前端请求：

```javascript
fetch('/api/conversations/chat/stream', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer eyJhbGciOiJIUzI1NiIs...'
    },
    body: JSON.stringify({ agentName: 'business-assistant', message: '你好' })
});
```

后端解析：

```java
String userId = securityContext.getCurrentUserId();
boolean valid = securityContext.validateJwtToken(token);
```

**认证优先级**：ThreadLocal → 请求属性 → Spring Security → JWT → 请求头 → 默认值

---

## 监控配置

### 可观测性理论

**可观测性三支柱**：
| 支柱 | 说明 | 工具 |
|------|------|------|
| **Metrics（指标）** | 数值化度量 | Prometheus |
| **Logs（日志）** | 离散事件记录 | ELK/Loki |
| **Traces（追踪）** | 请求链路追踪 | Jaeger/Zipkin |

**为什么需要监控**：
- 了解系统运行状态
- 快速定位问题
- 性能优化依据
- 容量规划参考

### LLM 调用 Usage 可观测性

yunxi 业务层现已统一采集每次 LLM 调用的 token 消耗与耗时，并在日志与指标两个维度暴露，便于排查成本、性能与异常。

**采集入口**：yunxi 业务层统一封装的 usage 采集（落 `yunxi-observability` 的 `MetricRegistry` / `TraceStore`），接收 AgentScope 的 `io.agentscope.core.model.ChatUsage`（含 `inputTokens` / `outputTokens` / `cachedTokens` / `totalTokens` / `time` 秒），`usage` 为 null 时直接返回，不打印也不报错（某些 provider 不回填 usage 属正常）。

**1. 日志（INFO）**

`recordAndLogUsage` 以 INFO 级别打印一行 `[LLM Usage]` 日志，格式如下：

```
[LLM Usage] model=report-agent, provider=yunxi, inputTokens=1234, outputTokens=256, cachedTokens=800, totalTokens=1490, time=2.13s
```

除汇总 usage 外，各调用点还会按 block 类型打印响应内容摘要（便于排查推理/工具调用过程）：

| 调用点 | 维度 `model` 取值 | `provider` | 额外打印 |
|--------|------------------|-----------|---------|
| `ExecutionFacade.execute(AgentCommand)` | agent 名 | `yunxi` | — |
| `ConversationController`（会话入口） | conversation 的 agent 名 | `yunxi` | — |
| `ExecutionFacade` 流式 `Flux<AgentEvent>` | `conversationId`（无则 `stream`） | `yunxi` | — |
| `page` 工具组（`pagegen_*` 工具） | `page-agent` | `yunxi` | `TextBlock`(文本) / `ThinkingBlock`(推理) / `ToolUseBlock`(工具名+入参) / `DataBlock`(名称+来源)，长文本截断至 200 字 |
| `page` 工具组 OpenAI 代理 | `page-agent-proxy` | `yunxi` | 同上；且代理返回的 `usage` 由硬编码 0 改为聚合真实 `ChatUsage` |

**2. OpenTelemetry 指标**

指标落 `yunxi-observability` 的 `MetricRegistry`（生产装配为 Micrometer 桥接，见 [14. 可观测性](./14-observability.md)）；导出经 OTLP push 到 OpenTelemetry Collector（`management.otlp.metrics.export.enabled=true` 开启），未启用时度量经 `/actuator/metrics` 只读端点查看：

| 指标名 | 类型 | 单位 | 维度（attribute） | 说明 |
|--------|------|------|------------------|------|
| `llm.token.total` | LongCounter | `{token}` | `llm.model`、`llm.provider`、`llm.token.type`（`prompt` / `completion`） | 累计消耗 token，区分输入/输出 |
| `llm.duration` | DoubleHistogram | `ms` | `llm.model`、`llm.provider` | 模型调用耗时直方图（桶边界 0.5/1/2/5/10/30 秒） |

**3. 框架级 DEBUG 日志（可选）**

除业务层日志外，可开启 AgentScope 模型调用层的 DEBUG 日志，观察更完整的请求/响应体与框架侧 usage 信息：

```yaml
logging:
  level:
    io.agentscope.core.model: DEBUG
```

> 注意：DEBUG 级别会打印较完整的模型请求/响应体（可能含 prompt 内容），生产环境建议保持关闭或配合脱敏。

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
```

---

## 环境变量清单

### 必需变量

| 变量名 | 说明 |
|--------|------|
| MYSQL_HOST | MySQL 主机 |
| MYSQL_PORT | MySQL 端口 |
| MYSQL_DATABASE | 数据库名 |
| MYSQL_USERNAME | 数据库用户 |
| MYSQL_PASSWORD | 数据库密码 |
| REDIS_HOST | Redis 主机 |
| REDIS_PORT | Redis 端口 |
| DASHSCOPE_API_KEY | DashScope API Key |

### 可选变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| REDIS_PASSWORD | Redis 密码 | 空 |
| MILVUS_HOST | Milvus 主机 | 127.0.0.1（默认本地；生产环境替换为真实 Milvus 地址） |
| MILVUS_PORT | Milvus 端口 | 19530 |
| MILVUS_DATABASE | Milvus 数据库 | default |
| MILVUS_USERNAME | Milvus 用户名 | root |
| MILVUS_PASSWORD | Milvus 密码 | root |
| OPENAI_API_KEY | OpenAI API Key | 空 |
| BAIDU_API_KEY | 百度千帆 API Key | 空 |
| HUAWEI_API_KEY | 华为盘古 API Key | 空 |
| OSS_SECRET_KEY / HUAWEI_SECRET_KEY / MINIO_SECRET_KEY | 对象存储密钥 | 空 |
| ALIYUN_OCR_ACCESS_KEY_SECRET / ALIYUN_ASR_ACCESS_KEY_SECRET | 阿里云 OCR/ASR 密钥 | 空 |
| MCP_XXX_ENABLED / MCP_XXX_URL | 可选 MCP 服务器开关与地址（如 MCP_PLAYWRIGHT_ENABLED、MCP_GITHUB_URL），见 `config/mcp-external.yml` | false / localhost 占位 |
| A2A_JWT_SECRET | A2A 安全 JWT 密钥（`a2a.security.authentication.type=jwt` 时使用） | 空 |

## Session 持久化配置

Agent 运行时状态默认通过 `WorkspaceSession` 持久化到工作区文件系统（零配置）。跨实例共享时切换为 Redis：

```yaml
agentscope:
  core:
    session:
      type: redis      # workspace（默认）| redis
```

`workspace` 模式无需额外依赖，Session 数据按框架设计存储在 `agents/{agentName}/` 工作空间目录下（与 `users/`、`knowledge/` 等同级）。`redis` 模式需 `spring-boot-starter-data-redis`，通过 `RedisTemplateAdapter` 适配。配置文件修改后重启即生效，无需改动 Java 代码。

---

## 配置文件模板

### 开发环境

```yaml
spring:
  profiles:
    active: datasource,redis,llm,milvus,embedding,persistence,mcp-core,resilience,file-upload

logging:
  level:
    io.yunxi: debug
```

### 生产环境

```yaml
spring:
  profiles:
    active: datasource,redis,llm,milvus,embedding,persistence,mcp-core,mcp-external,resilience,file-upload

logging:
  level:
    io.yunxi: warn
```

> **说明**：所有 `config/*.yml` 由 `config/imports.yml` 全量加载，`spring.profiles.active` 只控制 `@Profile` 注解 Bean 的启用。当前代码中仅 `muse-docker` 一个 `@Profile` 开关（`yunxi-intelligence` 沙箱实现切换），其余列出的 profile 名仅作配置分组说明，并无对应 `@Profile` Bean。

---

## Agent 工具组配置

Agent 的工具按职责分组隔离，避免 LLM 调用不相关的工具导致偏离主题或安全风险。

工具组分为两大类：**系统内置组**（框架代码定义）和 **MCP 服务器组**（由 MCP 服务器动态创建）。

### 一、系统内置组（所有 Agent 通用）

这些组由框架代码硬编码定义，组名固定，不依赖外部配置。

| 组名 | 包含的工具 | 用途 |
|------|-----------|------|
| `memory` | `memory_search`, `memory_get`, `session_history`, `session_search`, `session_history_search`, `session_list` | 查询对话历史和长期记忆（`session_history_search` 检索应用数据库中的已持久化会话） |
| `filesystem` | `read_file`, `write_file`, `edit_file`, `glob_files`, `list_files`, `grep_files` | 读写工作区文件 |
| `execute` | `execute` | 执行 Shell 命令（高危） |
| `agent` | `call_agent`, `agent_send`, `agent_spawn`, `task_list`, `task_cancel`, `task_output` | 调用其他 Agent |
| `page` | `pagegen_xxx`（以 `pagegen_` 开头的工具） | 生成前端页面 |
| `general` | 未归类的其他本地工具 | 兜底组 |

### 二、MCP 服务器组（由 MCP 服务器注册时动态创建）

组名 = MCP 服务器名。你在 `tools.mcpServers` 里配了哪个服务器，就多了哪个组。

例如配置了 `mcpServers: [database, redis]`，则生成的组：

| 组名 | 包含的工具 | 来源 |
|------|-----------|------|
| `database` | `database_query`, `database_execute` 等 | database MCP 服务器注册 |
| `redis` | `redis_get`, `redis_set` 等 | redis MCP 服务器注册 |

### 配置示例

```yaml
# 只用系统内置组
agent:
  name: my-chat-agent
  toolsGroup:
    systemToolsGroup: [memory]

# 只用 MCP 工具（mcpServersToolsGroup 同时加载服务器 + 激活组）
agent:
  name: safety-assistant
  toolsGroup:
    mcpServersToolsGroup: [redis, database]

# 混合使用
agent:
  name: nutrition-assistant
  toolsGroup:
    systemToolsGroup: [agent, memory]          # 系统内置组（agent/memory/filesystem/execute/page/general）
    mcpServersToolsGroup: [formfill, database] # MCP 服务器，自动加载并激活
```

### 如何知道一个组是系统内置还是 MCP 的？

| 特征 | 系统内置组 | MCP 服务器组 |
|------|-----------|-------------|
| 配置位置 | `toolsGroup.systemToolsGroup` | `toolsGroup.mcpServersToolsGroup` 或 `tools.mcpServers` |
| 组名来源 | 代码硬编码（见上表） | 你配的 `mcpServers` 列表 |
| 包含哪些工具 | 上表列出了每个组的所有工具 | 由 MCP 服务器的实现决定 |
| 是否需要 `tools.mcpServers` | 不需要 | 需要（或通过 `mcpServersToolsGroup` 隐含） |
| 举例 | `memory`, `filesystem`, `execute`, `agent`, `page`, `general` | `database`, `redis`, `formfill`, `milvus`, `pagegen`, `playwright` |

**简单记忆法**：上表"系统内置组"列出来的就是内置的，不在表里的都是 MCP 服务器组。

### 默认行为

**不配 `tools` 字段时，Agent 默认仅激活 `memory` 组**，只能查询记忆，无法读写文件或执行命令。

### 工具箱裁剪（按 Agent 摘除框架默认工具）

**关键事实：框架默认工作区工具**。以下工具**不是 yunxi 自己实现的，而是 AgentScope-Java 在构建每个 Agent 时自动装备的框架默认工作区工具**（由框架 `ToolFilter` / `ToolsConfig` 注册，处于"未分组、始终活跃"状态，普通工具组开关管不到它们）：

- `read_file` / `write_file` / `list_files` / `grep_files` / `glob_files` / `execute`

纯业务 Agent（如营养师）不需要文件系统 / 代码执行能力时，应在 Agent 定义 YAML 中声明 `toolkit.deniedToolNames`，于构建期把这些工具从工具箱**彻底移除**（yunxi 唯一装配点 `AgentConfigurer` 将其翻译为框架 `ToolsConfig.deny` 注入 `HarnessAgent.Builder`）。效果：

- 杜绝 LLM 反复调用被权限拒绝的内置工具造成的空转（生成慢的根因之一）；
- 避免向业务 Agent 暴露无关的文件 / 代码执行能力。

> **`load_skill_through_path` 不属于框架默认工具，请勿照搬到 `deniedToolNames`**。
> 它是技能查看器工具，只在 Agent 配置了技能仓库（AgentSkillRepository，如 MUSE 自进化挂载的元工具）时才由框架注册，并非对所有 Agent 无条件装备。只有「明确不让该 Agent 加载技能」时才 deny 它，否则会破坏需要技能的 Agent 的技能加载。营养师因系统提示词已声明"禁止加载技能"，才在下方示例中额外 deny 它。

```yaml
# agent-definitions/<name>.yml
agent:
  name: nutrition-assistant
  # 工具箱裁剪：营养师是纯业务 Agent，关闭两类工具。
  toolkit:
    deniedToolNames:
      # 1) 框架默认工作区工具：每个 Agent 自动装备，营养师不需要文件读写 / 代码执行。
      - read_file
      - write_file
      - list_files
      - grep_files
      - glob_files
      - execute
      # 2) load_skill_through_path：技能查看器工具，仅在挂了技能中间件时存在。
      #    营养师系统提示词已声明「禁止加载技能」，故额外 deny；需要技能的 Agent 请勿照搬此行。
      - load_skill_through_path
```

需要文件 / 代码执行能力的 Agent：

- 直接**不声明** `toolkit` 字段（保留框架默认的全部内置工具）；或
- 从 `deniedToolNames` 中**移除对应工具名**即可，不要新增等价工具。

> 该配置是定义平面（`AgentSpec.ToolkitConfig`）的纯数据，框架语义翻译留在装配点。

---

## MCP 动态注册配置（Nacos 协调底座）

平台支持运行期通过 REST API 动态注册/注销 MCP 服务器（接口见 [09. API 参考 · MCP 工具（外部服务器注册）](./09-api-reference.md#mcp-工具外部服务器注册)）。该能力的协调底座基于 **Nacos 配置中心**：

- **目录持久化**：每个服务器条目写入 Nacos `dataId`（默认 `yunxi.mcp-servers.json`），进程重启不丢失；
- **跨实例广播**：通过 Nacos Naming 在协调服务名 `yunxi-mcp-coordinator` 下注册实例，多实例可统一感知服务器上下线；
- **优雅降级**：Nacos 未启用或不可达时，自动退化为本地内存目录（单实例、重启清空），动态注册接口仍可正常使用。

### 配置项

```yaml
yunxi:
  mcp:
    nacos:
      enabled: true                      # 是否启用 Nacos 协调底座（默认 false，退化为本地内存）
      server-addr: 127.0.0.1:8848        # Nacos 服务地址
      namespace: public                  # 命名空间（多租户隔离用）
      group: YUNXI_MCP_GROUP             # 配置 / 服务分组
      data-id: yunxi.mcp-servers.json    # 目录持久化 dataId
      coordinator-service: yunxi-mcp-coordinator   # Naming 协调服务名
      coordinator-group: YUNXI_MCP_GROUP          # 协调服务分组
```

### 行为说明

- 启动引导（`bootstrap`）会一次性合并发布所有在 `yunxi.mcp.static-servers` 中标记为 `enabled: true` 的静态服务器到目录，保证静态与动态服务器处于同一权威目录；
- 注册/注销采用「内存权威目录 + Nacos 全量快照」机制：所有写操作先更新内存目录，再发布完整快照，避免并发读-改-写导致的覆盖丢失，从而解决多实例最终一致下的目录竞态；
- 目标服务器不可达时仅 WARN 降级，首次调用时自动重连，不阻塞 HTTP 请求；
- 动态注册的服务器与静态 `yunxi.mcp.static-servers` 段共享同一套 `Toolkit` 注册逻辑，工具同样按服务器名分组、由 Agent 在对话中自主调用。

---

## 任务清单（TodoList）配置

任务清单是 **AgentScope-Java 2.0 原生能力**：启用后由框架注册 `todo_write` 工具与 `TaskReminderMiddleware`，
Agent 可维护结构化任务清单，yunxi 侧经前端 SDK `yunxi-chat.js` 解析 `todo_write` 工具的 `TOOL_RESULT_TEXT_DELTA` 透出（后端 `ConversationController.stream` 纯透传框架事件，**无 `todo_update` 事件类型**）。**yunxi 不自建工具、不自建存储**，仅提供开关。
yunxi 已在 `AgentConfigurer.configure()` 装配点接线该开关，默认关闭（全局开关与 per-Agent 字段均默认 false），对既有 Agent 零回归。

### 启用方式（二者之一生效）

```yaml
# 方式一：Agent 定义 YAML（按 Agent 开启）
agent:
  name: business-assistant
  plan:
    taskList: true                      # 默认 false
```

```yaml
# 方式二：全局开关（Spring 环境属性，对所有 Agent 生效；application.yml / 环境变量 / 启动参数均可）
yunxi:
  agent:
    taskList:
      enabled: true                     # 默认 false
```

启用条件与计划模式（`plan.enabled`）一致，取 **per-Agent `agent.plan.taskList` 或 全局 `yunxi.agent.taskList.enabled`** 二者之一（OR 生效）。

### 与计划模式（PlanMode）的关系

两者**相互独立**：任务清单不要求先进入计划模式，`todo_write` 可随时调用；反之亦然。

| 能力 | 开关 | 说明 |
|------|------|------|
| 计划模式 | `plan.enabled` | 先规划后执行的只读阶段，`plan_exit` 需人工确认 |
| 任务清单 | `plan.taskList` | 结构化任务跟踪，无 HITL 门禁，自主推进 |

### 语义与状态

- **写入语义**：全量替换（full-list-replace）——模型每次提交完整列表，不做增量合并
- **状态**：`pending`（待执行）/ `in_progress`（执行中）/ `completed`（已完成），同一时刻至多一个 `in_progress`（违反时工具返回错误，任务列表不被破坏）
- **持久化**：存于 `AgentState.tasksContext`，随 AgentState 按 (userId, sessionId) 槽位持久化，跨会话续传天然具备
- **默认即有持久化**：框架默认装配文件存储（`~/.agentscope/state/<agentId>/`），无需额外配置即可跨会话续传；仅**跨实例/多副本**部署时才需启用 `agentscope.core.stateStore（Redis 后端，经 DistributedRuntimeConfig 装配）`

### 透出机制（前端契约）

后端 `ConversationController.stream` 以 `event: agent-event` **纯透传**框架 `AgentEvent`，**不合成** `todo_update` 事件（框架 `AgentEventType` 无此类型）。任务清单随 `todo_write` 工具执行，以 `TOOL_RESULT_TEXT_DELTA`（`toolCallName=todo_write`，即 `TodoTools.render()` 的 markdown）流式下发，`TOOL_RESULT_END` 收尾；前端 SDK `yunxi-chat.js` 按 `toolCallId` 累积增量、在 `TOOL_RESULT_END` 还原为结构化列表并经 `onTodo` 回调透出（full-list-replace 语义）：

```javascript
yunxiChat({
  mode: 'stream',
  onTodo: (todos, meta) => {
    // todos: [{ content, status, priority }] 全量列表；meta: { toolCallId, revision }
    renderTodoPanel(todos);
  }
});
```

任务清单为**全量透出**，前端应整体替换清单而非合并。

---

## 人机确认（HITL）配置

对于命令执行、文件写入等高危操作，可要求**人工确认后再执行**：Agent 执行前挂起并推送
`REQUIRE_USER_CONFIRM` 事件，由调用方回传确认结果后继续。

能力基于 AgentScope 原生权限引擎实现，平台仅负责把 YAML 配置映射为框架原生的
ASK 规则，以及提供确认结果回传入口。

### 配置方式

```yaml
# agent-definitions/<name>.yml（顶层即 AgentSpec 字段，无 agent: 包裹）
identity:
  agentId: general-assistant
  name: 通用助手
  description: 你是通用助手。
permission:                            # 必填
  toolGate:
    enabled: true
    tools:
      - execute      # 命令执行
      - write_file   # 文件写入
      - edit_file    # 文件编辑
    message: "该操作需要你确认后执行"
```

### 权限模式

`permission` 段**必填**：缺失或写成空壳会让校验失败、定义不加载。不按"未配置即不限制"解释——漏写只能由代码替作者猜模式，猜成全拒（Agent 静默失去全部业务工具）或全放行（限制根本没生效）都不会报错。

具体模式由声明了哪类工具清单派生：

| 声明 | 模式 | 未列出的工具 | 适用场景 |
|------|------|-----------------|----------|
| `toolGate`（`enabled: true` 且 `tools` 非空） | `DEFAULT` | 请求确认（挂起） | 有人值守；可同时用 `allowedTools` 免确认、`deniedTools` 硬拒 |
| 只有 `allowedTools` | `DONT_ASK` | **直接拒绝** | 无人值守白名单。`allowedTools: []` 表示除 MCP 订阅工具与平台治理工具外一律拒绝 |
| 只有 `deniedTools` | `BYPASS` | 放行 | 无人值守黑名单。工具池很大、只需禁少数几个时用这个；基线 `execute` 始终禁止 |

> 因此**未配置 HITL 不会导致卡死**：未命中放行规则的工具被直接拒绝，而非挂起等待。

**交叉声明一律拒绝加载，不做优先级裁决**：

- 无人值守下（无 `toolGate`）同时声明 `allowedTools` 与 `deniedTools` → 拒绝。两者对"未列出的工具"语义相反（拒绝 / 放行），同时给必然丢掉其中一半意图；
- 同一工具名同时出现在任意两个清单中（`allowedTools` / `deniedTools` / `toolGate.tools`）→ 拒绝。框架判定顺序为 deny → ask → allow，若靠顺序裁决，作者写的"放行"会变成"每次确认"。

> 黑名单模式的固有代价是**新增工具默认放行**：工具池新增高危工具时，用黑名单的 Agent 会自动获得它。因此高危工具应同时加入代码基线禁止集，或该 Agent 改用白名单。

**平台级治理工具的内置放行**：以下工具不属于任何单个业务 Agent 的职责，而是平台治理能力，因此平台在所有权限模式下统一放行，**不参与 Agent 的业务工具白/黑名单**——否则会出现「工具已注册、工具组已激活，但模型调用时报 permission denied」：

| 工具 | 用途 | 生效条件 |
|------|------|----------|
| `todo_write` | 任务清单（TodoList） | 始终放行 |
| `muse_skill_eval` / `muse_skill_evolve` / `muse_skill_prune` | MUSE 自进化元工具 | `yunxi.muse.enabled: true` 时注册并放行 |

> 若业务 Agent 声明了 `permission.allowedTools` 白名单，白名单**只约束业务工具**；上表中的平台级工具在白名单之外仍被放行。

### 注意事项（易踩坑）

1. **工具名必须是系统中真实存在的名称**。配置不存在的名称**不会报错**，但规则永不生效，
   人工确认形同虚设。可用工具名见启动日志中的 `Registered tool 'xxx'` 记录。
2. **工具是否可用还取决于该 Agent 激活的工具组**（`toolsGroup.systemToolsGroup`）。
   未激活组中的工具，即便列在此处也不会被触发。
3. **前端必须实现确认交互**：收到 `REQUIRE_USER_CONFIRM` 后需回传 `confirmResults`
   重新发起请求，否则会话停在挂起点。接口用法见
   [09. API 参考](./09-api-reference.md#require_user_confirm-事件人机确认)。

> **确认轮载荷约束（重要）**：框架在确认轮 `validateAndAcceptConfirmResults` 通过后**直接** `resumeAgent()` 跳进执行阶段、不再补一次推理。因此确认轮随行提交的用户新问题**会被静默丢弃、永远得不到回答**，却会进入上下文。若用户想在确认后追问，必须**等恢复流结束之后另起一次请求**，而不是把新问题塞进确认回传。该约束由 `MemoryInterceptor` 在确认轮剥离用户文本与 `contextData`、仅保留 `METADATA_CONFIRM_RESULTS` 强制保证。

### 权限规则变更的生效范围

权限上下文在框架侧只在**新建会话（fresh state）**时从构建期模板生成；已有会话从持久化的会话状态加载权限快照，框架**不做模板版本比对**。为避免「改了权限规则但调用仍被拒」，平台在**每次调用前**把最新模板写回会话状态（复用工具组激活的同一条会话级修正钩子），因此：

- **权限规则变更即时生效**：修改 `PermissionConfig` 或 `hitl` 配置并重启后，**存量会话**（同一 `userId`）下次调用即按新规则执行，无需清空会话、无需更换 `userId`。
- 若你自定义了调用入口而未走平台的会话级修正钩子，则仍可能出现「旧会话沿用旧规则」，此时用全新 `userId` 即可区分「规则没配对」与「旧会话未刷新」。

### 已知限制

未配置 HITL 时（`DONT_ASK` 模式），未命中放行规则的工具**一律被拒绝，包括
`list_files` / `read_file` 等只读工具**。原因是工具的 `readOnly` 属性只在框架
`EXPLORE` / `ACCEPT_EDITS` 模式下参与判定，在 `DONT_ASK` 模式下不生效。

只读 **MCP** 工具不受此限制（框架工具自检直接放行）。若业务需要在该模式下使用内置只读工具，
可让 Agent 显式配置 HITL 或为只读工具补充放行规则。

---

## MUSE 配置

[MUSE 自进化引擎](./10-skills.md#muse-自进化引擎) 的配置前缀为 `yunxi.muse`（由 `EvolutionConfig` 绑定，**代码默认开启**；平台未提供默认 `muse.yml`，需覆盖时在 `application.yml` 或环境变量中声明）：

```yaml
yunxi:
  muse:
    enabled: true                       # 是否启用自进化能力
    model: qwen-plus                    # 修补用的 LLM 模型名（走 yunxi ModelFactory）
    provider: dashscope                 # 模型供应商
    builtin-export-dir: .agentscope/workspace/skills   # 内置技能 tests/ 物化目录（MUSE 评估器执行用）
    sandbox:
      mode: local                       # local=本机子进程 / docker=容器沙箱（推荐）
      timeout: 60s
    evaluator:
      test-command: "java tests/SkillStructureTest.java"
      max-retries: 2
    refine:
      max-iterations: 3
      stop-on-no-progress: true
    pruner:
      similarity-threshold: 0.85
      min-usage: 1
      max-skills: 200
      # 画像驱动判据：把「使用次数」升级为「使用者广度」（只读用户画像）
      profile-aware:
        enabled: true
        scan-limit: 500                 # 轨迹枚举上限（性能护栏）
        overfit-usage-threshold: 5      # 单一使用者高频 → 标记过拟合（需泛化）
        general-usage-threshold: 3      # 使用者广度达标 → 撤销低使用率归档
```

MUSE 默认开启，无需改动 Agent 定义 YAML；如需关闭，在 `application.yml` 或环境变量中设置 `yunxi.muse.enabled: false`。

> **注意**：下列 `pruner.profile-aware.*` 仅完成配置绑定（`EvolutionConfig.ProfileAware`），聚合器 `UsageProfileAggregator` **尚未实现**，当前配置不产生任何效果；剪枝实际只用「使用率 + 相似度」两条判据。

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `pruner.profile-aware.enabled` | `true` | 画像驱动剪枝判据开关（设计态，当前无消费者） |
| `pruner.profile-aware.scan-limit` | `500` | 轨迹枚举上限（设计态；轨迹存储无按技能反查能力，此为扫描成本护栏） |
| `pruner.profile-aware.overfit-usage-threshold` | `5` | 去重使用者数为 1 且调用次数达到该值 → 标记 `OVERFIT`（设计态） |
| `pruner.profile-aware.general-usage-threshold` | `3` | 去重使用者数达到该值 → 视为有通用价值，撤销低使用率归档（设计态） |

> **设计上的数据来源与降级**（聚合器 `UsageProfileAggregator` 尚未实现）：画像驱动判据只**读**调用轨迹与用户画像，绝不回写画像。画像或轨迹数据缺失、或 `enabled: false` 时，聚合返回空值，剪枝退化为原有两条规则。详见 [10. 技能系统 - 画像驱动的剪枝判据（设计态）](./10-skills.md#画像驱动的剪枝判据设计态未接入)。

## 意图引擎配置

[意图引擎](./15-intent-engine.md) 当前是一个**关键词 → Agent 的极简路由库**，位于 `yunxi-intelligence` 模块（包 `io.yunxi.intelligence`）。以代码为准，**没有** `yunxi.intent.*` 外部化配置，也**没有** `config/intent/*.yml` 数据文件（NER 词典 / 改写术语表 / 意图树 / 映射表 / 路由 / 分类通道 / 多域 / 热更新均不在当前代码范围内）。

关键词规则由构造 `KeywordIntentResolver(Map<String, List<String>> rules, String defaultAgentId)` 时以代码传入；如需从配置加载，应在装配层新增读取 `yunxi.intent.*` 的配置类并构建 `KeywordIntentResolver`（当前未实现）。扩展分类能力可实现 `IntentResolver` 并注册进 `DefaultIntentEngine`。

> **历史说明**：早期方案文档曾描述一套完整的 `yunxi.intent.*` 配置（NER / 改写 / 意图树 / 映射表 / 路由 / 分类通道 / 多域 / 热更新），该描述对应已被简化的早期方案，与当前代码不符，请勿据此配置。意图引擎当前未接进对话请求主链路。

---

## AgentScope Service 管控面配置（yunxi 集成模块名 aistio）<a name="aistio"></a>

yunxi 通过应用内 `io.yunxi.fabric.aistio` 适配层接入底层框架的 **AgentScope Service** 管控面，补齐 **Agent 管控 / 治理可视化 / 会话干预** 能力。该能力默认关闭，需显式开启并指向一个已运行的 AgentScope Service 控制面（见 [08. 部署指南 · aistio 控制面本地编排](./08-deployment.md#aistio)）。

### 术语对照（yunxi 模块名 ↔ 底层框架官方名）

本章出现的 "aistio" 是 yunxi 对 AgentScope-Java 生态 **AgentScope Service** 管控面的集成模块命名，同时也是 AgentScope Service 后端（`agentscope-service` 仓库）自身的部署代号（Go 二进制 `aistiod`、平面 `aistio-control` / `aistio-data` / `aistio-scheduler` / `aistio-gateway`，容器间经 `BUILDER_DATA_URL=http://aistio-data:8082` 等硬编码主机名互联）。因此代码层沿用 `yunxi.aistio.*` 配置前缀与 `io.yunxi.fabric.aistio` 适配包、docker 服务名沿用 `aistio-gateway` / `aistio-scheduler` / `aistio-data`，以与真实后端保持一致，不可改名。底层框架对外发布的官方产品名为 **AgentScope Service**（官方文档：<https://java.agentscope.io/v2/zh/service>，当前为预览版，且官方站点称 "AgentScope Service"、源码与容器称 "aistio"，二者同源）。yunxi 的 docker-compose 中上述 service 的镜像坐标使用官方发布产物 `as-controlplane` / `as-dataplane` / `as-gateway` / `as-scheduler`（仓库 `sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope`，为官方预编译镜像，**非** `agentscope-service` 源码构建），详见 [08. 部署指南 · aistio 控制面本地编排](./08-deployment.md#aistio)。为降低开源后的学习门槛，本章行文统一使用框架官方概念名，关键映射如下：

| yunxi 文档用语 | AgentScope Service 官方概念 |
| --- | --- |
| aistio 管控面 / 控制面 | AgentScope Service 管控面（Control Plane） |
| 人工审批节点 | Approval（人工关口） |
| 命名空间 | Namespace |
| 控制台 / 网关 | AgentScope Service 控制台 / 网关 |

### 配置项

```yaml
yunxi:
  aistio:
    enabled: false                                  # 总开关（默认 false，不连接管控面）
    control-plane-url: http://localhost:18081        # aistio 控制面 REST/控制台 地址
    control-plane: ""                                # aistio gRPC 端点（host:port）；仅 start-grpc=true 时需要
    internal-token: compose-local-internal-token-at-least-32chars   # 与 aistio 各平面 BUILDER_INTERNAL_TOKEN 一致
    base-url: http://localhost:40001                 # yunxi 数据面对管控面可达的基座地址（裸 host:port）
    namespace: default                               # 上报至管控面的命名空间
    instance-key: ""                                 # 实例键；留空自动取 ${app}-${host}-${port} 稳定唯一
    workspace-root: ${java.io.tmpdir}/yunxi-workspaces  # 工作区定义 Agent 本地根目录
    contract-http-port: 40001                        # 数据面契约 HTTP 端口
    enable-events: false                             # Level-2 事件流（当前默认关）
    start-http-register: true                        # 启动期 HTTP 自注册 + 周期心跳
    start-grpc: false                                # 是否启用 gRPC 对话流
```

### 配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `yunxi.aistio.enabled` | `false` | 是否启用管控面接入；开启后平台向 aistio 注册 Agent、上报会话事件并接收干预指令 |
| `yunxi.aistio.control-plane-url` | — | aistio 控制面地址；`deploy/aistio` 编排默认发布到主机 `18081`，故填 `http://localhost:18081` |
| `yunxi.aistio.internal-token` | — | 内部令牌，须与 aistio 各平面的 `BUILDER_INTERNAL_TOKEN` 环境变量完全一致（compose 本地默认 `compose-local-internal-token-at-least-32chars`） |
| `yunxi.aistio.base-url` | `http://localhost:40001` | yunxi 数据面**对管控面可达**的基座地址（裸 host:port，管控面自行拼接 `/agentscope/health` 等），用于两处：① 管控面 prober 轮询 `base-url/agentscope/health` 探活；② 管控面回调本平台会话。注意此处是从**管控面容器视角**解析的地址，**绝不能填 `localhost`**——当管控面运行在 Docker 中、yunxi 运行在宿主机时，容器内的 `localhost` 指向容器自身而非宿主机，prober 连接被拒，Agent 全部显示 `unavailable`。正确填法：Windows/Mac Docker Desktop 用 `http://host.docker.internal:40001`，或宿主机局域网 IP `http://<host-ip>:40001`（如 `http://192.168.x.x:40001`） |
| `yunxi.aistio.namespace` | `default` | 上报至管控面的命名空间 |
| `yunxi.aistio.instance-key` | — | 实例键；留空自动取 `${app}-${host}-${port}` 稳定唯一，覆盖框架默认 UUID（对齐实例键稳定唯一约定） |
| `yunxi.aistio.workspace-root` | `${java.io.tmpdir}/yunxi-workspaces` | 工作区定义 Agent 本地根目录；受控面派发含 `definition` 的任务时启用 |
| `yunxi.aistio.contract-http-port` | `40001` | 数据面契约 HTTP 端口 |
| `yunxi.aistio.enable-events` | `false` | Level-2 事件流开关（当前默认关，相关设计规范尚存未决歧义） |
| `yunxi.aistio.start-http-register` | `true` | 启动期 HTTP 自注册（`HttpSelfRegistration`）+ 周期心跳 |
| `yunxi.aistio.start-grpc` | `false` | 是否启用 gRPC 对话流（控制面经 gRPC 推对话、yunxi 回传事件） |

> **Agent key 是什么**：管控面「新建 Agent」表单的 **Agent key** 是该 Agent 在 `namespace` 范围内的**稳定身份 slug**，格式仅含小写字母、数字、`-`、`_`（建议 kebab-case，如 `purchase-review-agent`）。它在同一 `namespace` 内唯一，且不随显示名（displayName）变更而变；与后端自动生成的 UUID `id`、显示名均属不同概念。
>
> - **yunxi 自动注册，通常无需手动建**：开启 `yunxi.aistio.enabled=true` 后，yunxi 在启动期通过 AgentScope-Java 原生 `aistio` 扩展的 `HttpSelfRegistration` 向管控面 `POST /api/v1/agent-registrations` 注册自身数据面，并完成周期心跳与握手身份回填（由 `AistioConnector` 装配 `AistioConfig.startHttpRegister(true)` 触发）。本数据面在管控面目录中的身份由 `HarnessAgent` 的名称与 `yunxi.aistio.instance-key`（默认 `${app}-${host}-${port}`）共同决定，落在 `yunxi.aistio.namespace`（默认 `default`）。因此一般不必在控制台手动新建同名 Agent；该表单用于注册**其他** Agent（托管 Agent 或额外外部数据面）。手动建 Agent 时其 key 不要与 yunxi 默认实例身份冲突，否则会被复用而非新建。

> **Agent 在控制台显示 `unavailable` 的根因**：状态由管控面 **prober 轮询数据面 `base-url/agentscope/health`** 决定，连不通即 `unavailable`，与你在控制台怎么填 Agent key / 显示名无关。最常见原因有二：① `yunxi.aistio.base-url` 填了 `localhost` 而管控面在 Docker 中（见上表，容器内 `localhost` 不等于宿主机）——把 base-url 改为 `host.docker.internal` 或宿主机 IP 后重启 yunxi 即可；② 该 Agent 没有真实运行的数据面认领它（例如仅在控制台建了目录条目、却无 yunxi 或上游 dataplane 实例在跑）。yunxi 自身的 `yunxi-agent-platform` 要 available，前提是 yunxi 已启动且 `yunxi.aistio.enabled=true`，并以容器可达的 base-url 注册。修复后 prober 下一轮轮询才会翻绿，非即时生效。

### 接入步骤（deploy/aistio 方式）

1. 在仓库 `deploy/aistio/` 目录执行 `docker compose up -d` 拉起管控面（详见 [08. 部署指南 · aistio 控制面本地编排](./08-deployment.md#aistio)；生产环境在 `deploy/aistio/` 放置 `.env` 强随机密钥）；
2. 启动管控面：`cd deploy/aistio && docker compose up -d`；
3. 在 yunxi 的 `application.yml`（或环境变量）将 `enabled` 置为 `true`，并填好 `control-plane-url` / `internal-token` / `base-url` 等必填项；
4. 重启 yunxi（`mvn spring-boot:run -pl yunxi-app`），观察启动日志确认 `aistio` 适配层连接成功；
5. 浏览器访问 `http://localhost:18090`（网关）或 `http://localhost:18081`（控制面）打开管控面，验证 Agent 注册与会话干预。

> 生产环境务必在 `deploy/aistio/` 放置 `.env`（CSPRNG 强随机密钥，文件权限受限且已被 `.gitignore` 忽略），不要沿用内置开发默认值（如 `BUILDER_INTERNAL_TOKEN=compose-local-internal-token-...`、`AISTIO_POSTGRES_PASSWORD=builder`），并避免将 `aistio-db` 的 `5432` 端口直接映射到公网。

---

**上一页**: [05. 模块说明](./05-modules.md)  
**下一页**: [07. 开发指南 →](./07-development.md)
