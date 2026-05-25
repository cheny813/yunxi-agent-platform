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
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/${MYSQL_DATABASE:agent_platform}?useUnicode=true&characterEncoding=utf-8
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

---

## 知识库（RAG）配置

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
    ↓ KnowledgeAutoConfiguration（@PostConstruct）
遍历 enabled=true 的配置 → 按 type 匹配 KnowledgeCreator → 创建 Knowledge 实例
    ↓ registerSingleton
Spring 容器中的 Knowledge Bean
    ↓ @Autowired Map<String, Knowledge>
AdvancedAgentFactory 运行时使用
```

### 扩展新知识库类型

参考 [07. 开发指南](./07-development.md#创建自定义知识库类型) 中的 `KnowledgeCreator` SPI 说明。

---

## Agent 多 Profile 配置

### 理论基础：多态配置

一个 Agent 可以定义多个 Profile（配置档），每个 Profile 代表一种工作模式。业务层只需在 YAML 中通过 `mode` 字段选择内置模式，无需理解底层参数。

### 配置结构

```yaml
agents:
  - name: nutrition-assistant      # Agent 名称
    description: 营养食谱管理助手
    type: react
    enabled: true
    
    # Agent 级别默认模式
    mode: expert
    
    # 默认 RAG 模式（请求未指定时使用此值，可选 GNERIC / AGENTIC / NONE）
    ragMode: GENERIC
    
    # 默认 prompt（未指定 profile 时使用）
    prompt: |
      你是一个专业的校园餐营养食谱管理助手...
    
    # 默认工具和专家配置
    mcpServers: [formfill, database, milvus]
    skillConfig:
      experts: [dish-searcher, nutrition-evaluator, recipe-composer]
    
    # Profile 映射：name -> ProfileDefinition
    profiles:
      # Profile 1：营养咨询 — 聊天模式
      chat:
        label: 营养咨询
        description: 回答营养健康问题
        mode: chat                    # ← 只需改 mode
        prompt: |                     # ← 换一个轻量 prompt
          你是一个专业的营养健康顾问...
      
      # Profile 2：食谱生成 — 专家模式
      recipe-make:
        label: 食谱生成
        description: 生成营养食谱
        mode: expert                  # ← 继承 Agent 的 expert 模式
        prompt: |                     # ← 覆盖 prompt
          你是一个专业的校园餐营养食谱管理助手...
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

**上一页**: [05. 模块说明](./05-modules.md)  
**下一页**: [07. 开发指南 →](./07-development.md)
