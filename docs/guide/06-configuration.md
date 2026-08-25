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

**配置来源**（按优先级从高到低）：
```
1. 命令行参数
2. JVM 系统属性 (-D)
3. 环境变量
4. application-{profile}.yml
5. application.yml
6. @PropertySource
7. 默认值
```

**优先级原理**：
- 越靠近运行时的配置优先级越高
- 高优先级配置覆盖低优先级
- 便于在不同环境灵活调整

---

## 配置体系设计

### 配置层次架构

```
┌─────────────────────────────────────────┐
│  运行时配置（最高优先级）                │
│  - 命令行参数                           │
│  - 环境变量                             │
├─────────────────────────────────────────┤
│  环境配置                               │
│  - application-dev.yml                  │
│  - application-test.yml                 │
│  - application-prod.yml                 │
├─────────────────────────────────────────┤
│  模块配置                               │
│  - application-datasource.yml           │
│  - application-llm.yml                  │
│  - application-security.yml             │
├─────────────────────────────────────────┤
│  默认配置（最低优先级）                  │
│  - application.yml                      │
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

// 2. YAML 配置
llm:
  default-provider: dashscope
  providers:
    dashscope:
      api-key: xxx

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

**连接池原理**：
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
spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/${MYSQL_DATABASE:yunxi_agent_platform}?useUnicode=true&characterEncoding=utf-8
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
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
```

### LLM 配置

**多提供商支持原理**：
```
┌─────────────────────────────────────────┐
│  LLM 抽象层                              │
│  - 统一接口                              │
│  - 多提供商支持                           │
├─────────────────────────────────────────┤
│  Provider A    Provider B    Provider C │
│  - DashScope   - OpenAI      - Claude   │
│  - 通义千问    - GPT         - 克劳德   │
└─────────────────────────────────────────┘
```

```yaml
llm:
  default-provider: dashscope
  providers:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:}
      model: qwen-max
      base-url: https://dashscope.aliyuncs.com/api/v1
    openai:
      api-key: ${OPENAI_API_KEY:}
      model: gpt-4
      base-url: https://api.openai.com/v1
```

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

- **单租户（默认）**：不填 `apiKey`/`baseUrl` → 回退到全局 `agentscope.core.*` 或对应环境变量，所有 Agent 共用平台账号。
- **多租户（按需）**：在 Agent 定义中显式填写 `apiKey`/`baseUrl` → 经框架 `ModelCreationContext` 透传给对应提供商工厂，实现每 Agent 独立账号。openai / dashscope / anthropic / claude / deepseek 此前为死字段，现已修复生效；gemini / ollama 无自定义工厂，由 SPI 提供商经同一 context 自动消费。

**配置优先级（统一）**：`AgentModelConfig` 显式值 > provider 级配置（`agentscope.core.<provider>`）> 全局配置（`agentscope.core.api-key` 等）> 环境变量。

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

**缓存策略对自定义工厂与 SPI 提供方一致生效**：`ModelRegistry` 解析顺序为 named → cache → 用户工厂（最新注册在前）→ SPI 提供方。yunxi 注册的 `openai` / `dashscope` / `anthropic` / `claude` / `deepseek` / `baidu` / `huawei` 七条正则走 `ContextModelFactory`（两参，消费 `ModelCreationContext`）；其中 `baidu` / `huawei` 框架未内置，用自定义 `BaiduModelProvider` / `HuaweiModelProvider` 注册为工厂，路径与内置 Provider 完全一致。`gemini` / `ollama` 及任何未注册的提供商由 `ServiceLoader` 从 `META-INF/services/io.agentscope.core.model.spi.ModelProvider` 自动发现并调用 `provider.create(modelId, context)`。SPI 接口为简单提供方保留了 `supports(String)` / `create(String)` 的兼容默认实现（`context` 参数在 `default` 方法中被忽略），因此只实现旧 1 参 API 的厂商也能即开即用。

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

> **⚠️ V2.0 兼容性说明**：`io.agentscope.core.rag.Knowledge` 在 AgentScope 2.0.0（GA）中标记为 `@Deprecated(forRemoval=true)`。官方新 RAG 模块计划在后续 minor 版本上线，届时需要迁移知识库创建逻辑到新的 SPI 接口。当前通过 `@SuppressWarnings("removal")` 保持功能正常，所有相关代码均标记 `TODO: AgentScope 2.0` 方便后续定位。参见 [框架适配](#) 了解更多。

### 理论基础：检索增强生成

RAG 使 Agent 能够从外部知识库中检索相关信息，弥补 LLM 知识截止日期和领域知识不足的问题。本框架支持 5 种知识库，通过统一的自动配置机制注册为 Spring Bean。

### 检索默认参数

```yaml
agentscope:
  extensions:
    retrieve:
      default-limit: 5                # 默认检索文档数（可选 3-10）
      default-score-threshold: 0.5    # 默认相似度阈值（可选 0.3-0.7）
```

这些参数是全局默认值，API 请求中可通过 `retrieveLimit` / `retrieveScoreThreshold` 按需覆盖。

### 启用自动配置

```yaml
agentscope:
  extensions:
    autoConfigEnabled: true   # 开启后，YAML 中的知识库配置会自动创建为 Bean
    knowledge-bases:
      # ... 知识库配置 ...
```

### 百炼知识库（阿里云）

```yaml
knowledge-bases:
  tech-docs:
    enabled: ${BAILIAN_ENABLED:false}
    type: bailian
    access-key-id: ${BAILIAN_ACCESS_KEY_ID:}
    access-key-secret: ${BAILIAN_ACCESS_KEY_SECRET:}
    workspace-id: ${BAILIAN_WORKSPACE_ID:}
    index-id: ${BAILIAN_INDEX_ID:}
```

### Dify 知识库

```yaml
knowledge-bases:
  product-manual:
    enabled: ${DIFY_ENABLED:false}
    type: dify
    api-key: ${DIFY_API_KEY:}
    api-url: ${DIFY_API_URL:}
    dataset-id: ${DIFY_DATASET_ID:}
    retrieval-mode: ${DIFY_RETRIEVAL_MODE:HYBRID_SEARCH}  # KEYWORD / SEMANTIC / HYBRID / FULLTEXT
```

### RAGFlow 知识库

```yaml
knowledge-bases:
  company-docs:
    enabled: ${RAGFLOW_ENABLED:false}
    type: ragflow
    api-key: ${RAGFLOW_API_KEY:}
    api-url: ${RAGFLOW_API_URL:http://localhost:9380}
    dataset-id: ${RAGFLOW_DATASET_ID:}           # 支持逗号分隔多数据集
    similarity-threshold: ${RAGFLOW_SIMILARITY_THRESHOLD:0.3}
    vector-similarity-weight: ${RAGFLOW_VECTOR_WEIGHT:0.3}
```

### SimpleKnowledge 本地知识库（开发测试）

```yaml
knowledge-bases:
  local-docs:
    enabled: ${SIMPLE_KB_ENABLED:false}
    type: simple
    dimension: ${SIMPLE_KB_DIMENSION:1024}    # 向量维度，默认使用 EmbeddingService 的维度
```

SimpleKnowledge 复用项目已有的 `EmbeddingService`（支持 Ollama/DashScope/OpenAI），无需额外配置嵌入模型。

### 知识库类型对比

| 类型 | 文档管理 | 配置要点 |
|------|---------|---------|
| `bailian` | 百炼控制台 | `access-key-id`、`access-key-secret`、`workspace-id`、`index-id` |
| `dify` | Dify 控制台 | `api-key`、`api-url`、`dataset-id`、`retrieval-mode` |
| `ragflow` | RAGFlow 控制台 | `api-key`、`api-url`、`dataset-id`（支持多数据集） |
| `haystack` | HayStack 管道 | 需安装 `agentscope-extensions-rag-haystack` 依赖 |
| `simple` | 代码管理 | `dimension`（向量维度），复用已有 EmbeddingService |

### 自动配置架构

```
agentscope.yml knowledge-bases 配置
    ↓ @ConfigurationProperties
AgentscopeExtensionProperties
    ↓ KnowledgeAutoConfiguration (@PostConstruct)
遍历 enabled=true 的配置 → 创建对应类型的 Knowledge 实例
    ↓ registerSingleton
Spring 容器中的 Knowledge Bean
    ↓ @Autowired Map<String, Knowledge>
Agent 运行时使用
```

### 扩展新知识库类型

实现自定义 `Knowledge` 类并通过 `KnowledgeAutoConfiguration` 的 `@PostConstruct` 注册。

参考 [03. 核心概念](./03-concepts.md#知识库-knowledge-base) 中的知识库配置说明。

---

## Agent 多 Profile 配置

### 理论基础：多态配置

一个 Agent 可以定义多个 Profile（配置档），每个 Profile 代表一种工作模式。业务层只需在 YAML 中通过 `mode` 字段选择内置模式，无需理解底层参数。

### 配置结构

```yaml
agents:
  - name: business-assistant      # Agent 名称
    description: 业务数据管理助手
    type: react
    enabled: true
    
    # Agent 级别默认模式
    mode: expert
    
    # 默认 RAG 模式（请求未指定时使用此值，可选 GNERIC / AGENTIC / NONE）
    ragMode: GENERIC
    
    # 默认 prompt（未指定 profile 时使用）
    prompt: |
      你是一个专业的业务数据管理助手...
    
    # 默认工具和专家配置
    mcpServers: [formfill, database, milvus]
    skillConfig:
      experts: [data-searcher, business-evaluator, content-composer]
    
    # Profile 映射：name -> ProfileDefinition
    profiles:
      # Profile 1：智能咨询 — 聊天模式
      chat:
        label: 智能咨询
        description: 回答业务咨询问题
        mode: chat                    # ← 只需改 mode
        prompt: |                     # ← 换一个轻量 prompt
          你是一个专业的业务顾问...
      
      # Profile 2：内容生成 — 专家模式
      business-make:
        label: 内容生成
        description: 生成业务内容
        mode: expert                  # ← 继承 Agent 的 expert 模式
        prompt: |                     # ← 覆盖 prompt
          你是一个专业的业务数据管理助手...
```

### 模式选择示例

#### 简单场景：只需选模式

```yaml
agents:
  - name: coding-assistant
    description: 代码编写与审查助手
    mode: expert
    
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
agents:
  - name: custom-agent
    description: 高级自定义智能体
    mode: advanced                    # ← 高级模式，完全自定义
    
    profiles:
      custom-flow:
        label: 自定义流程
        mode: advanced
        toolGroups: [search, database]
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
| 旧 YAML，无 `mode` 字段 | 默认为 `expert`（现有行为不变） |
| 新 YAML，`mode: chat` | 应用聊天模式默认参数 |
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

### 安全基础理论

**身份认证 vs 授权**：
| 概念 | 说明 | 示例 |
|------|------|------|
| **认证（Authentication）** | 验证你是谁 | 用户名密码登录 |
| **授权（Authorization）** | 你能做什么 | 管理员/普通用户权限 |

**Token 认证原理**：
```
┌─────────┐      ┌─────────┐      ┌─────────┐
│  客户端  │ ──→  │ 认证服务 │ ──→  │  服务端  │
└─────────┘      └─────────┘      └─────────┘
     │                │                │
     │ 1. 登录        │                │
     │──────────────→│                │
     │                │ 2. 验证身份     │
     │                │ 3. 生成 Token   │
     │←───────────────│                │
     │   返回 Token   │                │
     │                                 │
     │ 4. 请求 API (带 Token)          │
     │───────────────────────────────→│
     │                                 │ 5. 验证 Token
     │←───────────────────────────────│
     │        返回数据                  │
```

### Gateway 鉴权

```yaml
agent:
  gateway:
    token: ${GATEWAY_TOKEN:}
    admin-token: ${GATEWAY_ADMIN_TOKEN:}
```

### MCP 鉴权

```yaml
mcp:
  auth:
    enabled: true
    type: api-token
    token: ${MCP_API_TOKEN:}
```

### SecurityContext 用户认证

**SecurityContext** 提供统一的用户认证信息获取接口，支持多种认证方式：

#### 1. 请求头方式（默认）

前端请求时携带用户ID：

```javascript
fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'X-User-Id': 'user123'
    },
    body: JSON.stringify({ message: '你好' })
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
fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer eyJhbGciOiJIUzI1NiIs...'
    }
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

**采集入口**：`io.yunxi.platform.tracing.LlmMetrics#recordAndLogUsage(model, provider, ChatUsage)`。该方法接收 GA 的 `io.agentscope.core.model.ChatUsage`（含 `inputTokens` / `outputTokens` / `cachedTokens` / `totalTokens` / `time` 秒），`usage` 为 null 时直接返回，不打印也不报错（某些 provider 不回填 usage 属正常）。

**1. 日志（INFO）**

`recordAndLogUsage` 以 INFO 级别打印一行 `[LLM Usage]` 日志，格式如下：

```
[LLM Usage] model=report-agent, provider=yunxi, inputTokens=1234, outputTokens=256, cachedTokens=800, totalTokens=1490, time=2.13s
```

除汇总 usage 外，各调用点还会按 block 类型打印响应内容摘要（便于排查推理/工具调用过程）：

| 调用点 | 维度 `model` 取值 | `provider` | 额外打印 |
|--------|------------------|-----------|---------|
| `ChatAppService.chat()` | agent 名 | `yunxi` | — |
| `ChatAppService.chatWithConversation()` | conversation 的 agent 名 | `yunxi` | — |
| `ChatAppService` 流式 `AGENT_RESULT` | `conversationId`（无则 `stream`） | `yunxi` | — |
| `PageAgentService.execute()` | `page-agent` | `yunxi` | `TextBlock`(文本) / `ThinkingBlock`(推理) / `ToolUseBlock`(工具名+入参) / `DataBlock`(名称+来源)，长文本截断至 200 字 |
| `PageAgentService` OpenAI 代理 | `page-agent-proxy` | `yunxi` | 同上；且代理返回的 `usage` 由硬编码 0 改为聚合真实 `ChatUsage` |

**2. OpenTelemetry 指标**

指标由 `ObservabilityAutoConfiguration` 创建的 `LlmMetrics` Bean 上报（需在 `yunxi.observability.enabled=true` 默认开启时生效），可通过 `management.metrics.export.prometheus` 暴露给 Prometheus：

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
| MILVUS_HOST | Milvus 主机 | localhost |
| GATEWAY_TOKEN | Gateway Token | 空 |
| GATEWAY_ADMIN_TOKEN | Gateway Admin Token | 空 |
| AGENTSCOPE_AUTO_CONFIG | 知识库/记忆自动配置开关 | true |
| BAILIAN_ENABLED | 启用百炼知识库 | false |
| BAILIAN_ACCESS_KEY_ID | 百炼 Access Key ID | 空 |
| BAILIAN_ACCESS_KEY_SECRET | 百炼 Access Key Secret | 空 |
| BAILIAN_WORKSPACE_ID | 百炼工作空间 ID | 空 |
| BAILIAN_INDEX_ID | 百炼索引 ID | 空 |
| DIFY_ENABLED | 启用 Dify 知识库 | false |
| DIFY_API_KEY | Dify API Key | 空 |
| DIFY_API_URL | Dify 服务地址 | 空 |
| DIFY_DATASET_ID | Dify 数据集 ID | 空 |
| DIFY_RETRIEVAL_MODE | Dify 检索模式 | HYBRID_SEARCH |
| RAGFLOW_ENABLED | 启用 RAGFlow 知识库 | false |
| RAGFLOW_API_KEY | RAGFlow API Key | 空 |
| RAGFLOW_API_URL | RAGFlow 服务地址 | http://localhost:9380 |
| RAGFLOW_DATASET_ID | RAGFlow 数据集 ID | 空 |

## Session 持久化配置

Agent 运行时状态默认通过 `WorkspaceSession` 持久化到工作区文件系统（零配置）。跨实例共享时切换为 Redis：

```yaml
agentscope:
  core:
    session:
      type: redis      # workspace（默认）| redis
```

`workspace` 模式无需额外依赖，Session 数据按框架设计存储在 `agents/{agentName}/` 工作空间目录下（与 `users/`、`knowledge/` 等同级）。`redis` 模式需 `spring-boot-starter-data-redis`，通过 `RedisTemplateAdapter` 适配。配置文件修改后重启即生效，无需改动 Java 代码。
| SIMPLE_KB_ENABLED | 启用本地知识库 | false |
| **RAG_DEFAULT_LIMIT** | **默认检索文档数** | **5** |
| **RAG_DEFAULT_SCORE_THRESHOLD** | **默认相似度阈值** | **0.5** |

---

## 配置文件模板

### 开发环境

```yaml
spring:
  profiles:
    active: datasource,llm,skill

logging:
  level:
    io.yunxi.platform: debug
```

### 生产环境

```yaml
spring:
  profiles:
    active: datasource,llm,skill,milvus,embedding,persistence

logging:
  level:
    io.yunxi.platform: warn
```

---

## Agent 工具组配置

Agent 的工具按职责分组隔离，避免 LLM 调用不相关的工具导致偏离主题或安全风险。

工具组分为两大类：**系统内置组**（框架代码定义）和 **MCP 服务器组**（由 MCP 服务器动态创建）。

### 一、系统内置组（所有 Agent 通用）

这些组由框架代码硬编码定义，组名固定，不依赖外部配置。

| 组名 | 包含的工具 | 用途 |
|------|-----------|------|
| `memory` | `memory_search`, `memory_get`, `session_history`, `session_search`, `session_list` | 查询对话历史和长期记忆 |
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
    systemToolsGroup: [agent, search]          # 系统内置组 + @Tool 派生组
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

---

## MUSE 配置

[MUSE 自进化引擎](./10-skills.md#muse-自进化引擎) 的配置集中在 `agent-config` 的 `config/muse.yml`（前缀 `yunxi.muse`，默认关闭，需显式 `enabled: true`）：

```yaml
yunxi:
  muse:
    enabled: true                       # 是否启用自进化能力
    model: qwen-plus                    # 修补用的 LLM 模型名（走 yunxi ModelFactory）
    provider: dashscope                 # 模型供应商
    builtin-export-dir: .agentscope/workspace/skills
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
```

在 `config/muse.yml` 中设置 `yunxi.muse.enabled: true` 即可全局启用，无需改动 Agent 定义 YAML。

## 意图引擎配置

[意图引擎](./16-intent-engine.md) 的配置集中在 `agent-config` 的 `config/intent.yml`（前缀 `yunxi.intent`），通过 `imports.yml` 随 Spring 配置自动导入：

```yaml
yunxi:
  intent:
    enabled: true                       # 总开关（false = 仅场景模式，等价旧 SceneDetectionService）
    ner-dictionary: classpath:config/intent/ner-dictionaries.yml   # NER 实体词典（部署业务数据）
    rewrite-enabled: true               # 改写阶段是否启用
    rewrite-processors: [terminology]   # 改写处理器名列表（按序执行；不存在名字 warn 跳过）
    terminology-table: classpath:config/intent/terminology.yml     # 术语/别名归一表
    intent-tree: classpath:config/intent/intent-tree.yml           # 意图树（分类规则）
    mapping-table: classpath:config/intent/intent-mapping.yml      # 意图 → Agent 路由映射表
```

各配置项说明（默认值 = 框架最小演示集路径；`agent-config` 显式覆盖为部署业务数据；**fat jar 部署下建议统一使用 `classpath:` 前缀**）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `yunxi.intent.enabled` | `true` | 总开关；`false` 时仅返回场景名，跳过 NER/改写/分类/映射 |
| `yunxi.intent.ner-dictionary` | `classpath:intent/ner-dictionaries.yml` | NER 词典文件；支持 `classpath:` / `file:` / `url:` 前缀 |
| `yunxi.intent.rewrite-enabled` | `true` | 改写阶段开关 |
| `yunxi.intent.rewrite-processors` | `[terminology]` | 改写处理器链 |
| `yunxi.intent.terminology-table` | `classpath:intent/terminology.yml` | 术语归一表；支持 `classpath:` / `file:` / `url:` 前缀 |
| `yunxi.intent.intent-tree` | `classpath:intent/intent-tree.yml` | 意图树；支持 `classpath:` / `file:` / `url:` 前缀 |
| `yunxi.intent.mapping-table` | `classpath:intent/intent-mapping.yml` | 路由映射表；支持 `classpath:` / `file:` / `url:` 前缀 |

> **框架通用性**：意图引擎是框架层通用能力，与具体业务解耦，采用**数据两级模型**——`agent-core` 内置最小演示集（兜底），`agent-config` 的 `config/intent/*.yml` 为部署业务数据，也支持 `file:` 前缀完全外部化。业务方只需修改上表配置项指向自己的文件即可整体替换，无需改动 Java 代码。完整定制指南见 [16. 意图引擎](./16-intent-engine.md#业务定制指南)。

---

**上一页**: [05. 模块说明](./05-modules.md)  
**下一页**: [07. 开发指南 →](./07-development.md)
