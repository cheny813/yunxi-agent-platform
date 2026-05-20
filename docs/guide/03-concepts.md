# 03. 核心概念

## 理论基础

### 什么是 Agent 的核心能力

Agent 的核心能力可以用 **"感知-思考-行动"** 循环来概括：

```
┌─────────────────────────────────────────┐
│           Agent 核心能力模型              │
├─────────────────────────────────────────┤
│                                         │
│   感知层          认知层          行动层   │
│  ┌─────┐        ┌─────┐        ┌─────┐  │
│  │输入 │───────→│推理 │───────→│输出 │  │
│  │解析 │        │规划 │        │执行 │  │
│  └─────┘        └─────┘        └─────┘  │
│     ↑              ↑              ↑     │
│     └──────────────┴──────────────┘     │
│              记忆/知识库                  │
│                                         │
└─────────────────────────────────────────┘
```

**感知层**：理解用户输入、解析环境信息
**认知层**：推理、规划、决策
**行动层**：调用工具、生成回复、修改状态

---

## Agent

### 定义

Agent 是具备自主决策能力的智能实体，能够：
1. **理解目标**：明确需要完成的任务
2. **制定计划**：将目标分解为可执行的步骤
3. **执行行动**：调用工具或生成回复
4. **反思改进**：根据反馈调整策略

### Agent 的组成

```
Agent = LLM + 记忆 + 工具 + 提示词

┌─────────────────────────────────────────┐
│                Agent                    │
├─────────────────────────────────────────┤
│  ┌─────────┐  ┌─────────┐  ┌─────────┐ │
│  │   LLM   │  │  记忆   │  │  工具   │ │
│  │ (大脑)  │  │ (经验)  │  │ (手脚)  │ │
│  └────┬────┘  └────┬────┘  └────┬────┘ │
│       │            │            │      │
│       └────────────┼────────────┘      │
│                    │                   │
│              ┌─────▼─────┐             │
│              │  提示词   │             │
│              │ (指令)   │             │
│              └───────────┘             │
└─────────────────────────────────────────┘
```

**LLM（大脑）**：负责推理和决策
**记忆（经验）**：存储历史对话和重要信息
**工具（手脚）**：与外部世界交互的能力
**提示词（指令）**：定义 Agent 的行为和目标

### 在本框架中的实现

本框架中，Agent 通过 YAML 配置定义：

```yaml
agents:
  nutrition-assistant:
    name: "营养专家助手"
    system-prompt: "你是营养专家..."  # 提示词
    model: "qwen-max"                  # LLM
    memory:                            # 记忆
      type: "smart"
      max-memories: 100
    tools:                             # 工具
      - query_database
      - calculate_nutrition
    mcp-servers:                       # MCP 工具
      - nutrition-knowledge
```

---

## 场景 (Scene)

### 理论基础：上下文管理

**核心问题**：同样的用户输入，在不同场景下需要不同的处理方式。

**示例**：
- 用户说"苹果"：在营养场景指水果，在科技场景指公司
- 用户说"分析"：在食谱场景分析营养，在代码场景分析逻辑

### 场景的定义

场景是特定业务上下文下的工作模式，包含：
- **场景标识**：唯一标识符
- **领域归属**：属于哪个业务领域
- **关键词**：触发该场景的关键词
- **上下文组装**：如何准备上下文信息
- **提示词模板**：该场景下的系统提示词

### 场景识别流程

```
用户输入
    ↓
关键词匹配
    ↓
┌─────────────────┐
│ 场景候选列表     │
│ - 场景A (置信度0.8)│
│ - 场景B (置信度0.6)│
│ - 场景C (置信度0.3)│
└────────┬────────┘
         ↓
置信度排序
         ↓
选择最高置信度场景
         ↓
组装场景上下文
         ↓
调用对应 Agent
```

### 在本框架中的实现

通过 `SceneContributor` SPI 定义场景：

```java
@Component
public class RecipeSceneContributor implements SceneContributor {
    
    @Override
    public Map<String, List<String>> getSceneKeywords() {
        // 返回 Map Map<场景名称, 关键词列表>
        return Map.of(
            "RECIPE", List.of("食谱", "配餐", "营养分析"),
            "SCORING", List.of("评分", "评估", "检查")
        );
    }
    
    @Override
    public String getExtractionPrompt(String sceneName) {
        return """
            请从用户输入中提取以下信息：
            1. 餐次类型（早餐/午餐/晚餐）
            2. 就餐人数
            3. 特殊要求
            输出格式：JSON
            """;
    }
    
    @Override
    public String assembleContext(String sceneName, String userId, String query) {
        // 查询用户历史偏好
        List<Dish> favorites = dishRepository.findFavorites(userId);
        
        // 返回格式化后的上下文文本
        return String.format("""
            用户历史偏好：%s
            营养标准：%s
            当前查询：%s
            """, favorites, loadStandard(), query);
    }
}
```

---

## Profile (配置档)

### 理论基础：多态配置

**核心问题**：同一个 Agent 在不同场景下需要不同的行为模式，但传统做法需要创建多个独立的 Agent 配置。

**Profile 的解决方案**：一个 Agent 可以定义多个 Profile，每个 Profile 是一组配置参数的集合，代表 Agent 的一种工作模式。

### Profile 的定义

Profile 包含以下关键属性：

| 属性 | 说明 | 示例 |
|------|------|------|
| **name** | Profile 名称，唯一标识 | `chat`, `recipe-make` |
| **label** | 展示名称 | `营养咨询`, `食谱生成` |
| **description** | 描述 | `回答营养健康问题` |
| **mode** | 内置模式选择 | `chat`, `expert`, `advanced` |
| **prompt** | 专用 system prompt | 覆盖 Agent 级别的 prompt |
| **toolGroups** | 激活的工具组列表 | `[search, database]` |
| **mcpServers** | MCP 服务器列表 | `[formfill, milvus]` |
| **maxIters** | 最大迭代次数 | `3`, `25` |
| **skillConfig** | 专家配置 | 多智能体协作时的专家列表 |

### Profile 的覆盖规则

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

### 在本框架中的实现

```yaml
agents:
  - name: nutrition-assistant
    mode: expert                    # Agent 级别默认模式
    prompt: 你是一个专业的营养食谱管理助手...
    
    profiles:
      chat:                         # Profile 1：聊天模式
        label: 营养咨询
        mode: chat                  # 覆盖为聊天模式
        prompt: 你是一个营养健康顾问...
      
      recipe-make:                  # Profile 2：专家模式
        label: 食谱生成
        mode: expert                # 继承 Agent 的 expert 模式
```

---

## Mode (模式)

### 理论基础：策略模式

**核心问题**：不同任务对 Agent 的能力要求不同——简单问答不需要工具，复杂任务需要多专家协作。

**Mode 的解决方案**：框架预定义四种内置模式，业务层只需选择模式，无需理解底层参数。

### 四种内置模式

四种模式的核心区别在于**两个正交维度**：工具（有/无）和智能体（单/多）。

```
              工具
          ┌─────┴─────┐
         无工具      有工具
          │           │
     ┌───chat──┐  ┌──tool──┐     ← 单智能体
     │ 最快    │  │ 快速   │
     │ 纯对话  │  │ 有工具  │
     └─────────┘  └───┬────┘
                      │ 需要多专家协作？
                      │
                   ┌──┴──┐
                   │ 是  │ 否
                   │     │
               ┌─expert─┐
               │ 全功能  │
               │ 多专家  │
               └────────┘

     advanced: 完全自定义，不受模式约束
```

### 模式对比

| 维度 | CHAT | TOOL | EXPERT | ADVANCED |
|------|------|------|--------|----------|
| 工具 | 无 | 有 | 有 | 自定义 |
| 智能体 | 单 | 单 | 多（Supervisor） | 自定义 |
| 规划 | 无 | 可选 | 有 | 自定义 |
| 速度 | 最快 | 快 | 较慢 | 取决于配置 |
| 适用场景 | 咨询/问答 | 有工具的单步任务 | 复杂多步推理 | 特殊需求 |

### 各模式的默认参数

| 参数 | CHAT | EXPERT | ADVANCED |
|------|------|--------|----------|
| `toolGroups` | `[]`（无工具） | 全部已注册组 | 业务自定义 |
| `mcpServers` | `[]` | 全部已配置 | 业务自定义 |
| `maxIters` | `3` | `25` | 业务自定义 |
| `enablePlanNotebook` | `false` | `true` | 业务自定义 |
| `enableMetaTool` | `false` | `true` | 业务自定义 |
| `skillConfig.experts` | 无 | 按配置加载 | 业务自定义 |

### 在本框架中的实现

```java
/**
 * 内置模式枚举 — 框架预定义
 * 业务层通过 YAML 的 mode 字段选择，无需理解底层参数。
 */
public enum BuiltinMode {
    CHAT,     // 纯对话，不调用任何工具
    TOOL,     // 单智能体 + 工具
    EXPERT,   // Supervisor + 多专家智能体
    ADVANCED  // 业务完全自定义
}
```

---

## 记忆系统 (Memory System)

### 理论基础：记忆分层

记忆系统为 Agent 提供持久化和结构化的记忆能力，支持短期记忆、长期记忆和场景化记忆管理。

```
┌─────────────────────────────────────────┐
│           记忆系统架构                    │
├─────────────────────────────────────────┤
│                                         │
│  ┌─────────────────────────────────┐    │
│  │  MemoryCoordinatorService       │    │
│  │  (短期记忆协调器)               │    │
│  └───────────┬─────────────────────┘    │
│              │                          │
│     ┌────────┴────────┐                │
│     ▼                 ▼                 │
│  ┌─────────┐   ┌───────────┐           │
│  │Scene 记忆│   │ ReMe 记忆  │           │
│  │(场景化)  │   │ (持久化)   │           │
│  └─────────┘   └─────┬─────┘           │
│                       │                 │
│              ┌────────┼────────┐        │
│              ▼        ▼        ▼        │
│         ┌────────┐┌────────┐┌────────┐ │
│         │Working ││ Task   ││ Tool   │ │
│         │Memory  ││ Memory ││ Memory │ │
│         └────────┘└────────┘└────────┘ │
│                                         │
└─────────────────────────────────────────┘
```

### MemoryCoordinatorService（短期记忆管理）

MemoryCoordinatorService 是短期记忆的核心协调器，负责 Agent 对话过程中的实时记忆管理。

**核心职责**：
- 管理 Agent 对话上下文中的短期记忆
- 协调场景记忆与持久化记忆的读写
- 提供记忆的增删改查接口
- 控制记忆容量与过期策略

```java
@Service
public class MemoryCoordinatorService {
    // 短期记忆缓存
    private final Map<String, List<Memory>> shortTermMemory = new ConcurrentHashMap<>();
    
    /**
     * 写入记忆
     */
    public void addMemory(String sessionId, Memory memory) {
        shortTermMemory.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(memory);
    }
    
    /**
     * 读取会话记忆
     */
    public List<Memory> getMemories(String sessionId) {
        return shortTermMemory.getOrDefault(sessionId, List.of());
    }
    
    /**
     * 清除会话记忆
     */
    public void clearMemories(String sessionId) {
        shortTermMemory.remove(sessionId);
    }
}
```

### MemoryScene / MemorySceneRegistry（场景化管理）

MemoryScene 和 MemorySceneRegistry 提供场景化的记忆管理能力，根据不同的业务场景自动切换记忆策略。

**MemoryScene**：定义单个场景的记忆配置
| 属性 | 说明 | 示例 |
|------|------|------|
| **sceneName** | 场景名称 | `recipe-make`, `chat` |
| **retentionPolicy** | 保留策略 | `session`, `persistent` |
| **maxMemories** | 最大记忆条数 | `100` |
| **ttl** | 过期时间 | `30m`, `24h` |

**MemorySceneRegistry**：管理全局场景记忆注册表
- 根据当前场景自动匹配对应的记忆策略
- 支持动态注册和卸载场景记忆配置
- 未匹配场景时使用默认策略

```java
@Component
public class MemorySceneRegistry {
    private final Map<String, MemoryScene> scenes = new ConcurrentHashMap<>();
    
    /**
     * 注册场景记忆配置
     */
    public void registerScene(MemoryScene scene) {
        scenes.put(scene.getSceneName(), scene);
    }
    
    /**
     * 获取场景对应的记忆配置
     */
    public MemoryScene getScene(String sceneName) {
        return scenes.getOrDefault(sceneName, MemoryScene.defaultScene());
    }
}
```

### ReMe 集成（持久化记忆）

ReMe 是框架集成的持久化记忆系统，提供三类结构化的长期记忆：

**1. WorkingMemory（工作记忆）**
- 记录当前任务的中间状态和临时数据
- 生命周期与任务绑定，任务结束后自动清理
- 用于跨步骤的上下文传递

**2. TaskMemory（任务记忆）**
- 记录历史任务的执行记录和结果
- 持久化存储，长期可用
- 支持按任务类型、时间范围检索

**3. ToolMemory（工具记忆）**
- 记录工具调用的输入、输出和执行状态
- 用于工具调用的历史追溯和性能分析
- 支持工具调用链的完整回溯

```java
// WorkingMemory 使用示例
workingMemory.set("currentStep", "analysis");
workingMemory.set("intermediateResult", resultData);

// TaskMemory 使用示例
taskMemory.saveTaskRecord(taskId, taskType, taskResult);
List<TaskRecord> history = taskMemory.queryTaskHistory(taskType, startTime, endTime);

// ToolMemory 使用示例
toolMemory.recordToolCall(toolName, inputArgs, outputResult, status);
List<ToolCallRecord> calls = toolMemory.getToolCallHistory(toolName);
```

**集成方式**：
- ReMe 通过 SPI 集成到 MemoryCoordinatorService
- 业务层通过 MemoryCoordinatorService 统一接口访问所有记忆类型
- 框架自动在对话生命周期中同步短期记忆与持久化记忆

---

## 规则引擎

### 理论基础：基于规则的专家系统

**专家系统**：模拟人类专家决策能力的计算机系统。

**规则引擎核心**：
- **事实 (Facts)**：当前系统的状态
- **规则 (Rules)**：如果条件满足，则执行动作
- **推理机 (Inference Engine)**：匹配规则并执行

### 三阶段规则模型

本框架采用 **PRE-RUNTIME-POST** 三阶段规则模型：

```
请求处理流程：

用户请求
    ↓
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  PRE 阶段   │───→│ RUNTIME 阶段 │───→│  POST 阶段  │
│  (前置检查)  │    │ (运行时监控) │    │ (后置处理)  │
│             │    │             │    │             │
│ • 权限检查   │    │ • 限流控制   │    │ • 审计日志   │
│ • 参数校验   │    │ • 熔断保护   │    │ • 结果处理   │
│ • 安全过滤   │    │ • 性能监控   │    │ • 缓存更新   │
└─────────────┘    └─────────────┘    └─────────────┘
    │                    │                    │
    └────────────────────┴────────────────────┘
                        ↓
                   返回结果
```

**PRE 阶段**：执行前检查，不通过则拒绝请求
**RUNTIME 阶段**：执行期监控，可干预执行过程
**POST 阶段**：执行后处理，记录日志、更新状态

### 规则类型

| 类型 | 执行时机 | 用途 | 示例 |
|------|----------|------|------|
| **PRE** | 执行前 | 准入控制 | 权限检查、参数校验 |
| **RUNTIME** | 执行中 | 运行时保护 | 限流、熔断、监控 |
| **POST** | 执行后 | 后置处理 | 审计日志、结果处理 |

### 规则表达式

本框架使用 **SpEL (Spring Expression Language)** 作为规则表达式：

```java
// 权限检查
"@permissionService.hasPermission(#context.get('userId'), 'nutrition')"

// 参数校验
"#context.get('recipe') != null && #context.get('recipe').getDishes().size() > 0"

// 限流判断
"@rateLimiter.tryAcquire('nutrition-api', 1)"
```

### 在本框架中的实现

```java
@Component
public class MyRuleProvider implements RuleDefinitionProvider {
    
    @Override
    public List<RuleDefinition> getRuleDefinitions() {
        return List.of(
            // PRE 规则：权限检查
            SpELRule.builder()
                .name("permission-check")
                .phase(RulePhase.PRE)
                .condition("@permissionService.hasPermission(#context.get('userId'), 'nutrition')")
                .violationAction("#context.put('error', '无权访问')")
                .build(),
            
            // RUNTIME 规则：限流
            SpELRule.builder()
                .name("rate-limit")
                .phase(RulePhase.RUNTIME)
                .condition("@rateLimiter.tryAcquire('api', 1)")
                .violationAction("#context.put('error', '请求过于频繁')")
                .build(),
            
            // POST 规则：审计日志
            SpELRule.builder()
                .name("audit-log")
                .phase(RulePhase.POST)
                .condition("true")
                .action("@auditService.log('nutrition', #context)")
                .build()
        );
    }
}
```

---

## MCP 协议

### 理论基础：标准化接口

**类比理解**：
- USB 协议：让不同厂商的设备可以互联互通
- MCP 协议：让不同厂商的 LLM 和工具可以互联互通

**核心思想**：
1. **标准化**：统一工具定义和调用方式
2. **解耦**：工具独立部署，Agent 动态发现
3. **安全**：工具自主控制访问权限

### MCP 协议栈

```
┌─────────────────────────────────────────┐
│  应用层：Tool Definition                 │
│  - 工具名称、描述、参数 Schema            │
├─────────────────────────────────────────┤
│  协议层：JSON-RPC 2.0                    │
│  - 请求格式：{jsonrpc, method, params, id}│
│  - 响应格式：{jsonrpc, result/error, id} │
├─────────────────────────────────────────┤
│  传输层：HTTP / SSE                      │
│  - HTTP：请求/响应式调用                  │
│  - SSE：服务器推送事件                    │
└─────────────────────────────────────────┘
```

### 工具定义示例

```json
{
  "name": "query_database",
  "description": "查询数据库",
  "parameters": {
    "type": "object",
    "properties": {
      "sql": {
        "type": "string",
        "description": "SQL 查询语句"
      }
    },
    "required": ["sql"]
  }
}
```

### 调用流程

```
┌─────────┐     ┌─────────┐     ┌─────────┐     ┌─────────┐
│  Agent  │────→│MCP Client│────→│MCP Server│────→│  工具   │
│         │     │         │     │         │     │         │
│ 1.决定  │     │ 2.构造  │     │ 3.解析  │     │ 4.执行  │
│ 调用工具 │     │ JSON-RPC│     │ 请求    │     │ 逻辑    │
│         │←────│         │←────│         │←────│         │
│ 5.接收  │     │ 6.解析  │     │ 7.构造  │     │ 8.返回  │
│ 结果    │     │ 响应    │     │ 响应    │     │ 结果    │
└─────────┘     └─────────┘     └─────────┘     └─────────┘
```

### 在本框架中的实现

**MCP 工具注册**：

```java
@Component
public class MyTool implements ToolHandler {
    
    @Override
    public String getName() {
        return "query_database";
    }
    
    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name(getName())
            .description("查询数据库")
            .parameters(List.of(
                ToolParameter.builder()
                    .name("sql")
                    .type("string")
                    .description("SQL 查询语句")
                    .required(true)
                    .build()
            ))
            .build();
    }
    
    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String sql = (String) arguments.get("sql");
        // 执行查询
        return ToolResult.success(result);
    }
}
```

---

## SPI 扩展机制

### 理论基础：依赖倒置与插件化

**依赖倒置原则 (DIP)**：
- 高层模块不应该依赖低层模块，两者都应该依赖抽象
- 抽象不应该依赖细节，细节应该依赖抽象

**插件化架构**：
- 框架定义扩展点（接口）
- 业务方实现扩展点
- 框架运行时加载实现

### SPI 工作流程

```
┌─────────────────────────────────────────┐
│              SPI 工作流程                │
├─────────────────────────────────────────┤
│                                         │
│  1. 框架定义接口                          │
│     public interface DomainContributor   │
│                                         │
│  2. 业务实现接口                          │
│     @Component                           │
│     public class MyContributor           │
│         implements DomainContributor     │
│                                         │
│  3. Spring 自动扫描                       │
│     发现并注册所有实现类                   │
│                                         │
│  4. 框架使用实现                          │
│     @Autowired                           │
│     List<DomainContributor> contributors │
│                                         │
│  5. 调用业务逻辑                          │
│     for (c : contributors) c.contribute()│
│                                         │
└─────────────────────────────────────────┘
```

### 核心扩展点

| 扩展点 | 用途 | 实现示例 |
|--------|------|----------|
| **DomainContributor** | 定义业务领域 | NutritionDomainContributor |
| **SceneContributor** | 定义业务场景 | RecipeSceneContributor |
| **ContextEnricher** | 增强上下文 | NutritionContextEnricher |
| **RuleDefinitionProvider** | 定义业务规则 | NutritionRuleProvider |
| **VectorSearchProvider** | 向量搜索实现 | DishVectorSearchProvider |

### 分层职责

```
┌─────────────────────────────────────────┐
│           Business 业务层                │
│  - 实现 Framework SPI 接口               │
│  - 编写业务逻辑                          │
├─────────────────────────────────────────┤
│  SPI 接口 ←── 实现                       │
├─────────────────────────────────────────┤
│          Framework 框架层                │
│  - 定义领域 SPI 接口                      │
│  - 调用 SPI 实现                         │
│  - 编排业务流程                          │
├─────────────────────────────────────────┤
│  SPI 接口 ←── 实现                       │
├─────────────────────────────────────────┤
│           Infra 基础设施层               │
│  - 实现 Shared SPI 接口                   │
│  - 提供技术实现                          │
├─────────────────────────────────────────┤
│  SPI 接口 ←── 定义                       │
├─────────────────────────────────────────┤
│           Shared 共享层                  │
│  - 定义技术无关的 SPI 接口                │
└─────────────────────────────────────────┘
```

---

## 数据流

### 请求处理完整流程

```
用户请求
    ↓
┌─────────────────────────────────────────┐
│ 1. Gateway (网关层)                      │
│    - 协议适配 (HTTP/WebSocket/Webhook)   │
│    - 统一认证 (Token 校验)               │
│    - 限流熔断 (Rate Limiting)            │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 2. Rule Engine PRE (规则引擎-前置)       │
│    - 权限检查                            │
│    - 参数校验                            │
│    - 安全过滤                            │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 3. Scene Router (场景路由)               │
│    - 领域识别 (Domain Detection)         │
│    - 场景匹配 (Scene Matching)           │
│    - 上下文组装 (Context Assembly)       │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 4. Agent Core (核心层)                   │
│    - Agent 选择                          │
│    - 记忆检索                            │
│    - 提示词组装                          │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 5. AgentScope ChatAppService (运行时)        │
│    - LLM 推理                            │
│    - 工具调用决策                        │
│    - 循环执行                            │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 6. MCP Tool (工具层)                     │
│    - 工具执行                            │
│    - 结果返回                            │
└─────────────┬───────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│ 7. Rule Engine POST (规则引擎-后置)      │
│    - 审计日志                            │
│    - 结果处理                            │
│    - 缓存更新                            │
└─────────────┬───────────────────────────┘
              ↓
         返回结果
```

---

## 安全模型

### 分层安全架构

```
┌─────────────────────────────────────────┐
│  接入层安全                               │
│  - HTTPS 传输加密                         │
│  - Token 认证 (X-Gateway-Token)          │
│  - IP 白名单                              │
├─────────────────────────────────────────┤
│  应用层安全                               │
│  - 权限控制 (RBAC)                        │
│  - 规则引擎过滤                           │
│  - 输入校验                               │
├─────────────────────────────────────────┤
│  数据层安全                               │
│  - 敏感信息脱敏                           │
│  - 数据访问审计                           │
│  - 加密存储                               │
└─────────────────────────────────────────┘
```

---

**上一页**: [02. 快速开始](./02-quickstart.md)  
**下一页**: [04. 架构设计 →](./04-architecture.md)
