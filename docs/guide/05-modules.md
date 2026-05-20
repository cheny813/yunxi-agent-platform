# 05. 模块说明

了解 yunxi Agent Platform 各模块的功能和职责。

## 模块化设计理论

### 什么是模块化

**模块化**是将系统分解为独立、可替换的模块的设计方法。

**模块化的优势**：
| 优势 | 说明 | 本框架体现 |
|------|------|-----------|
| **可维护性** | 修改一个模块不影响其他 | 各模块独立演进 |
| **可复用性** | 模块可在不同系统使用 | core 模块可复用 |
| **可测试性** | 可独立测试模块 | 单元测试隔离 |
| **并行开发** | 团队并行开发不同模块 | 多团队分工 |
| **可替换性** | 可替换模块实现 | 升级不影响整体 |

### 模块间通信方式

**1. 依赖注入（DI）**
```java
// 通过 Spring 注入服务
@Autowired
private AgentDomainService agentService;
```

**2. 事件驱动**
```java
// 发布事件
eventBus.publish(new AgentCreatedEvent(agent));

// 监听事件
@EventListener
public void onAgentCreated(AgentCreatedEvent event) { }
```

**3. SPI 扩展**
```java
// 框架定义接口
public interface DomainContributor { }

// 业务模块实现
@Component
public class NutritionDomainContributor implements DomainContributor { }
```

---

## 模块概览

```
yunxi-agent-platform/
├── agent-core              # 核心框架
├── agent-gateway           # 统一网关
├── agent-rule-engine       # 规则引擎
├── agent-business          # 业务实现
├── agent-text2sql          # SQL生成
├── agent-app               # 通用应用
├── agent-desktop           # 桌面端
├── agent-spi               # SPI 接口定义
├── agent-config            # 配置管理
└── agent-integration-test  # 集成测试
```

---

## agent-core（核心框架）

### 职责

- Agent 生命周期管理
- 对话/会话调度与编排
- 记忆存储与检索接口
- MCP 调用能力抽象

### 核心组件

| 组件 | 说明 |
|------|------|
| AgentDomainService | Agent 生命周期管理（创建、缓存、获取） |
| SessionDatabaseService / SessionSearchService | 会话持久化与检索 |
| MemoryCoordinatorService | 记忆协调服务 |
| McpClientService | MCP 客户端 |
| SceneDetectionService | 场景检测与识别 |
| **ProfileRouter** | Profile 路由（根据 agentName + profileName 解析 Agent 实例） |
| **OrchestrationConfig** | 编排模式配置（将 mode 展开为具体参数） |
| **ConversationDomainService / PlanPreCreator** | 对话编排与计划预创建 |
| **BuiltinMode** | 内置模式枚举（CHAT / TOOL / EXPERT / ADVANCED） |
| **ProfileDefinition** | Profile 定义模型（描述一个 Profile 的完整配置） |

### 架构分层

```
agent-core/
├── framework/          # 框架层 - 核心抽象与扩展点
│   ├── agent/         # Agent 生命周期管理
│   ├── conversation/  # 对话编排
│   ├── session/       # 会话管理
│   ├── memory/        # 记忆系统（含 reme/ 子包）
│   ├── mcp/           # MCP 调用
│   ├── skill/         # 技能系统
│   ├── tool/          # 工具
│   ├── plan/          # 计划编排
│   ├── profile/       # 用户画像
│   ├── embedding/     # 嵌入模型
│   ├── security/      # 安全
│   ├── a2a/           # 跨服务协作
│   ├── intelligent/   # 智能子系统
│   ├── controller/    # 控制器
│   └── sync/          # 同步
├── infra/             # 基础设施层 - 技术实现
│   ├── milvus/        # 向量数据库（optional）
│   ├── redis/         # 缓存
│   └── persistence/   # 持久化
└── shared/            # 共享层 - 通用组件
    ├── util/          # 工具类
    └── model/         # 共享模型
```

### SPI 扩展点

| 扩展点 | 接口 | 说明 |
|--------|------|------|
| 领域贡献 | DomainContributor | 注册业务领域 |
| 场景贡献 | SceneContributor | 注册业务场景 |
| 上下文增强 | ContextEnricher | 丰富对话上下文 |
| 工具处理器 | ToolHandler | 处理特定工具调用 |

---

## agent-gateway（统一网关）

### 网关模式理论

**什么是网关模式**：
- 网关是系统的统一入口
- 封装内部系统的复杂性
- 提供统一的接口给外部

**网关的核心功能**：
```
┌─────────────────────────────────────────┐
│              网关层                      │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐   │
│  │  认证   │ │  限流   │ │  路由   │   │
│  └────┬────┘ └────┬────┘ └────┬────┘   │
│       └───────────┼───────────┘        │
│                   ▼                    │
│           ┌─────────────┐              │
│           │  协议适配    │              │
│           │  格式转换    │              │
│           └──────┬──────┘              │
│                  │                     │
└──────────────────┼─────────────────────┘
                   │
              转发到后端服务
```

### 职责

- 消息平台适配（企业微信/钉钉/飞书）
- 统一认证与限流
- 消息格式转换
- 会话管理

### 支持平台

| 平台 | 协议 | 端口 |
|------|------|------|
| Web API | HTTP | 40003 |
| 企业微信 | Webhook | - |
| 钉钉 | Stream | - |
| 飞书 | WebSocket | - |

---

## agent-rule-engine（规则引擎）

### 规则引擎理论

**什么是规则引擎**：
- 将业务规则从代码中分离
- 使用声明式语言定义规则
- 支持动态修改规则

**规则引擎 vs 硬编码**：
| 对比项 | 硬编码 | 规则引擎 |
|--------|--------|----------|
| 修改方式 | 改代码、部署 | 修改配置 |
| 响应速度 | 慢（需发布） | 快（即时生效） |
| 业务参与 | 需开发人员 | 业务人员可直接修改 |
| 可追溯性 | 差 | 好 |

### 职责

- 三阶段规则执行（PRE/RUNTIME/POST）
- SpEL 安全表达式
- SPI 扩展机制

### 规则类型

| 阶段 | 用途 |
|------|------|
| PRE | 权限检查、参数校验 |
| RUNTIME | 限流、熔断、监控 |
| POST | 审计日志、结果处理 |

---

## agent-business（业务实现）

### 职责

- 业务领域实现
- 业务规则定义
- 领域模型实现

### 业务域

| 领域 | 说明 |
|------|------|
| nutrition | 营养相关业务 |
| devops | 运维相关业务 |
| a2apipeline | A2A流水线 |
| reportquery | 报表查询 |

---

## agent-text2sql（SQL生成）

### Text2SQL 技术理论

**什么是 Text2SQL**：
- 将自然语言转换为 SQL 语句
- 属于语义解析（Semantic Parsing）任务
- 是 NL2Code 的重要分支

**技术挑战**：
| 挑战 | 说明 | 解决方案 |
|------|------|----------|
| 歧义性 | 自然语言有歧义 | 上下文理解、Schema 关联 |
| 复杂性 | SQL 语法复杂 | 分步生成、语法约束 |
| 领域性 | 不同领域术语不同 | 向量检索、少样本学习 |

### 职责

- 自然语言转 SQL
- 向量检索
- 少样本学习

### 六步流水线

```
Schema检索 → 向量检索 → 少样本学习 → SQL生成 → 结果对齐 → 投票决策
```

**各步骤说明**：
| 步骤 | 功能 | 技术 |
|------|------|------|
| Schema检索 | 找到相关表结构 | 向量相似度 |
| 向量检索 | 找到相似问题 | Embedding 匹配 |
| 少样本学习 | 提供示例 | Few-shot Prompt |
| SQL生成 | 生成 SQL | LLM |
| 结果对齐 | 格式化结果 | 后处理 |
| 投票决策 | 多模型投票 | 集成学习 |

---

## 模块依赖关系

```
agent-app
    ↓
agent-business
    ↓
agent-core ←→ agent-rule-engine

agent-gateway → agent-core
```

**依赖说明**：
- 箭头方向表示依赖关系（A → B 表示 A 依赖 B）
- 双向箭头表示相互依赖
- 上层模块依赖下层模块

---

## 端口规划

| 服务 | 端口 | 说明 |
|------|------|------|
| agent-core | 40001 | 核心服务 |
| agent-rule-engine | 40002 | 规则引擎 |
| agent-gateway | 40003 | 网关服务 |
| mcp-database | 40010 | 数据库MCP |
| mcp-redis | 40011 | Redis MCP |
| mcp-filesystem | 40012 | 文件系统MCP |

---

**上一页**: [04. 架构设计](./04-architecture.md)  
**下一页**: [06. 配置指南 →](./06-configuration.md)
