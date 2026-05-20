# Agent 配置体系说明

## 目录结构

```
agent-config/src/main/resources/
├── application.yml                    # 配置入口，导入所有 config/*.yml
├── logback-spring.xml                 # 日志配置 (Logback)
│
├── config/                            # 【系统配置】通过 spring.config.import 加载
│   ├── agentscope.yml                 #   API密钥、默认模型、A2A、记忆存储
│   ├── server.yml                     #   Tomcat 端口、线程池、连接数
│   ├── datasource.yml                 #   数据库连接池 (HikariCP)
│   ├── redis.yml                      #   Redis 缓存
│   ├── cache.yml                      #   二级缓存 (Caffeine + Redis)
│   ├── async.yml                      #   异步线程池配置
│   ├── llm.yml                        #   LLM 服务参数
│   ├── milvus.yml                     #   向量数据库
│   ├── embedding.yml                  #   向量嵌入服务
│   ├── persistence.yml                #   持久化配置
│   ├── mcp-core.yml                   #   MCP 核心服务器
│   ├── mcp-external.yml               #   MCP 外部服务器
│   ├── skill.yml                      #   技能系统 (SkillBox)
│   ├── resilience.yml                 #   熔断/限流/重试
│   ├── gateway.yml                    #   API 网关路由
│   ├── business.yml                   #   业务服务配置
│   ├── text2sql.yml                   #   Text2SQL 配置
│   ├── rule-engine.yml                #   规则引擎
│   ├── a2a-pipeline.yml               #   A2A Pipeline 配置
│   └── file-upload.yml                #   文件上传
│
├── core/                              # 【核心叠加配置】加载于 config 之后覆盖
│   └── application.yml
│
├── agent-definitions/                 # 【Agent 定义】由 AgentDefinitionLoader 加载
│   ├── nutrition-assistant.yml        #   营养助手 (Supervisor + 4 专家)
│   ├── nutrition-experts.yml          #   专家 Agent (dish-searcher 等)
│   ├── pagegen-assistant.yml          #   页面生成助手
│   ├── safety-assistant.yml           #   食品安全助手
│   ├── food-chat.yml                  #   食品聊天助手 (单 Agent，轻量)
│   ├── code-fix-pipeline.yml          #   示例：Pipeline 模式
│   └── full-stack-assistant.yml       #   示例：嵌套编排
│
├── page-configs/                      # 【Page Agent 配置】由 PageAgentService 加载
│   └── page-agent-config.yml          #   页面类型提示词 + 工具定义，非 Agent
│
├── prompts/                           # 【提示词参考文档】给 Agent prompt 引用的知识
│   ├── nutrition-page-templates.md
│   ├── recipe-scoring-rules.md
│   └── superdesign-guidelines.md
│
├── skills/                            # 【技能包】SkillBox 本地技能目录
│   ├── machine-controller/
│   ├── nutrition-knowledge/
│   ├── nutrition-recipe/
│   ├── page-design/
│   └── skill-creator/
│
├── a2a/                               # 【A2A 工作流定义】
│   └── code-fix-workflow-state.yml
├── debate/                            # 【辩论模式配置】
│   └── nutrition-debate.yml
│
├── gateway/                           # 【网关配置】
│   ├── application-gateway.yml
│   └── spring/
│
├── mapper/                            # 【MyBatis 映射文件】
│   ├── AgentMapper.xml
│   ├── ConversationMapper.xml
│   └── ...
│
├── sql/                               # 【数据库 Schema】
│   └── schema.sql
│
├── static/                            # 【前端静态资源】
│   ├── chat.html                      #   聊天页面
│   ├── page-agent-recipe.html         #   Page Agent 页面
│   └── js/
│
└── a2a-code-pipeline-dynamic.yml      # A2A Pipeline 动态配置

---

## 配置加载逻辑

### 加载顺序

```
application.yml
    │
    └── spring.config.import → config/*.yml (按列表顺序加载)
                                    │
                                    ├── server.yml → 先加载
                                    ├── datasource.yml
                                    ├── ...
                                    └── agentscope.yml → 后加载，覆盖前面的
```

**后加载的会覆盖先加载的**，所以 `agentscope.yml` 中的 `server.port` 可以覆盖 `server.yml` 中的值。

### Agent 定义加载

Agent 定义由 `AgentDefinitionLoader` 在启动时加载，不经过 Spring 的 `spring.config.import`，**不支持 `${...}` 占位符**：

```
AgentDefinitionLoader 启动时扫描：
    │
    ├── 1. agent-definitions/*.yml
    │
    └── 2. agent-definitions/**/*.yml
```

**格式**：`agent: { name: ..., orchestration: {...}, runtime: {...}, plan: {...} }`

---

## 配置加载逻辑

### Agent 定义格式

### 标准格式 (`agent-definitions/*.yml`)

```yaml
agent:
  name: nutrition-assistant
  prompt: "你是一个营养助手..."
  orchestration:
    pattern: supervisor          # supervisor | pipeline | routing | single
    experts:
      - name: dish-searcher
        description: "搜索菜品"
      - name: nutrition-evaluator
        description: "评估营养"
  model:
    provider: dashscope
    name: qwen-plus
    temperature: 0.7
  runtime:
    maxIterations: 25
    enableMetaTool: true
  plan:
    enabled: true
    userConfirm: false
```

---

## 模型配置的继承关系

```
config/agentscope.yml (全局默认)
  agentscope.core.api-key: ${DASHSCOPE_API_KEY}
  agentscope.core.model-name: qwen-plus
  agentscope.core.provider: dashscope
        │
        ▼
Agent 级 model:（如果省略，则继承全局）
  model:
    provider: dashscope           # 可选，省略则用全局
    name: qwen-plus               # 可选，省略则用全局
    temperature: 0.7              # 可选，省略默认 0.7
```

**不需要在每个 Agent 配置里重复写 API key 和模型名。** 如果 Agent 的 `model:` 段完全省略，`AgentConfigurer` 会自动使用 `agentscope.yml` 中的全局配置。

---

## 常用配置速查

| 需求 | 改哪里 | 关键字段 |
|---|---|---|
| 修改 API Key | `config/agentscope.yml` 或环境变量 `DASHSCOPE_API_KEY` | `agentscope.core.api-key` |
| 切换模型 | `config/agentscope.yml` | `agentscope.core.model-name` |
| 修改端口 | `config/server.yml` | `server.port` |
| 添加新 Agent | `agent-definitions/` 下新建 `.yml` | `name: 唯一标识` |
| 指定专家列表 | Agent 定义中 | `skillConfig.experts` (旧) / `orchestration.experts` (新) |
| 开启/关闭 PlanNotebook | Agent 定义中 | `enablePlanNotebook` (旧) / `plan.enabled` (新) |
| 配置数据库 | `config/datasource.yml` | 连接池参数 |
| 关闭技能系统 | `config/skill.yml` | `agentscope.skill-box.enabled: false` |
| 关闭记忆系统 | `config/agentscope.yml` | `extensions.memory-stores.reme.enabled: false` |