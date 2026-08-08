# 11. 最佳实践

## 软件工程原则

### SOLID 原则回顾

**SOLID** 是面向对象设计的五大原则：

| 原则 | 全称 | 核心思想 | 在本框架中的体现 |
|------|------|----------|-----------------|
| **S** | Single Responsibility | 单一职责 | 每个 Service 只负责一件事 |
| **O** | Open/Closed | 开闭原则 | 通过 SPI 扩展，不修改框架 |
| **L** | Liskov Substitution | 里氏替换 | 接口实现可互换 |
| **I** | Interface Segregation | 接口隔离 | 细粒度 SPI 接口 |
| **D** | Dependency Inversion | 依赖倒置 | 依赖接口而非实现 |

### 其他重要原则

#### DRY（Don't Repeat Yourself）

**核心思想**：不要重复自己

**实践方式**：
```java
// 不好的做法 - 重复代码
public void method1() {
    // 相同的 10 行代码
}

public void method2() {
    // 相同的 10 行代码
}

// 好的做法 - 提取公共方法
private void commonLogic() {
    // 公共的 10 行代码
}

public void method1() {
    commonLogic();
}

public void method2() {
    commonLogic();
}
```

#### KISS（Keep It Simple, Stupid）

**核心思想**：保持简单

**实践方式**：
- 避免过度设计
- 优先使用简单方案
- 代码应该易于理解

#### YAGNI（You Aren't Gonna Need It）

**核心思想**：你不会需要它

**实践方式**：
- 不要实现当前不需要的功能
- 避免预测未来需求
- 需要时再添加

---

## Agent 设计原则

### 1. 单一职责

每个 Agent 只负责一个明确的业务领域。

```java
// 好的设计
@Component
public class BusinessAgent {
    // 只处理业务相关
}

@Component
public class DevOpsAgent {
    // 只处理运维相关
}

// 不好的设计 - 职责混杂
@Component
public class UniversalAgent {
    // 什么都做，难以维护
}
```

**为什么重要**：
- 易于理解和维护
- 便于测试
- 支持并行开发

### 2. 状态无关

Agent 应该是无状态的，依赖外部存储保存状态。

```java
@Service
public class MyAgent {
    
    @Autowired
    private ChatAppService chatAppService; // 外部存储
    
    public AgentResponse handleRequest(AgentRequest request) {
        // 从外部存储获取状态
        Conversation conv = chatAppService.getConversation(request.getSessionId());
        // 处理请求...
        // 不保存任何实例变量状态
    }
}
```

**无状态的优势**：
| 优势 | 说明 |
|------|------|
| **可扩展** | 可以轻松水平扩展 |
| **容错** | 实例故障不丢失状态 |
| **简单** | 不需要考虑并发状态问题 |

---

## 提示词工程

### 提示词工程理论基础

**什么是提示词工程**：
- 设计和优化给 LLM 的输入（Prompt）
- 影响 LLM 输出的质量和准确性
- 是开发 AI 应用的核心技能

**提示词的基本结构**：
```
┌─────────────────────────────────────────┐
│  角色定义 (Role)                         │
│  "你是..."                               │
├─────────────────────────────────────────┤
│  任务描述 (Task)                         │
│  "你的任务是..."                          │
├─────────────────────────────────────────┤
│  输入格式 (Input)                        │
│  "用户会提供..."                          │
├─────────────────────────────────────────┤
│  输出格式 (Output)                       │
│  "请以...格式输出"                        │
├─────────────────────────────────────────┤
│  约束条件 (Constraints)                  │
│  "注意..."                               │
├─────────────────────────────────────────┤
│  示例 (Examples)                         │
│  "例如：..."                             │
└─────────────────────────────────────────┘
```

### 1. 清晰的指令

```java
String prompt = """
    你是业务专家助手。
    
    任务：分析用户提供的业务数据，给出改进建议。
    
    输出格式：
    1. 指标分析
    2. 数据构成
    3. 改进建议
    """;
```

### 2. 少样本学习（Few-Shot Learning）

**理论基础**：通过示例让 LLM 学习模式

```java
String prompt = """
    将自然语言转换为 SQL。
    
    示例 1：
    用户：查询所有用户
    SQL：SELECT * FROM users
    
    示例 2：
    用户：查询最近 10 个订单
    SQL：SELECT * FROM orders ORDER BY created_at DESC LIMIT 10
    
    现在转换：
    用户：{userInput}
    SQL：
    """;
```

**少样本学习的优势**：
- 不需要微调模型
- 快速适应新任务
- 成本低，效果好

### 3. 思维链（Chain of Thought）

**理论基础**：让 LLM 展示推理过程

```java
String prompt = """
    请逐步思考并回答以下问题。
    
    问题：一个农场有鸡和兔，头共 35 个，脚共 94 只。鸡兔各几只？
    
    思考过程：
    1. 设鸡有 x 只，兔有 y 只
    2. 根据头的数量：x + y = 35
    3. 根据脚的数量：2x + 4y = 94
    4. 解方程组...
    
    答案：鸡 23 只，兔 12 只
    """;
```

---

## 记忆管理

### 记忆类型理论

**人类记忆的类比**：
| 类型 | 人类记忆 | AI 系统 | 存储方式 |
|------|----------|---------|----------|
| **感觉记忆** | 感官输入 | 原始输入 | 临时变量 |
| **短期记忆** | 工作记忆 | 上下文 | 内存/Redis |
| **长期记忆** | 永久存储 | 知识库 | 向量数据库 |

### 1. 短期记忆

```java
// 使用会话级别的上下文
context.put("lastQuery", userQuery);
```

**适用场景**：
- 当前对话的上下文
- 临时计算结果
- 会话状态

### 2. 长期记忆

```java
// 使用向量数据库存储
@Autowired
private VectorSearchProvider vectorSearch;

public void remember(String userId, String content) {
    float[] embedding = embeddingService.embed(content);
    vectorSearch.save(userId, embedding, content);
}
```

**向量数据库原理**：
```
┌─────────────────────────────────────────┐
│  向量数据库工作原理                       │
├─────────────────────────────────────────┤
│                                         │
│  文本 ──→ Embedding 模型 ──→ 向量       │
│                              [0.1,      │
│                               0.3,      │
│                               ...]      │
│                                         │
│  相似度搜索：                             │
│  查询向量 ──→ 计算余弦相似度 ──→ 最相似文本 │
│                                         │
└─────────────────────────────────────────┘
```

---

## 安全合规

### 安全理论基础

**安全三要素（CIA）**：
| 要素 | 说明 | 实践 |
|------|------|------|
| **机密性** | 信息不被未授权访问 | 加密、权限控制 |
| **完整性** | 信息不被篡改 | 校验、签名 |
| **可用性** | 系统持续可用 | 冗余、限流 |

### 1. 输入校验

```java
public AgentResponse handleRequest(AgentRequest request) {
    // 校验输入
    if (StringUtils.isBlank(request.getMessage())) {
        return AgentResponse.error("输入不能为空");
    }
    
    // 防止注入攻击
    String safeInput = sanitize(request.getMessage());
    // ...
}

private String sanitize(String input) {
    // 移除或转义危险字符
    return input.replaceAll("<script>", "")
                .replaceAll("javascript:", "");
}
```

### 2. 敏感信息保护

```java
// 日志中不记录敏感信息
log.info("用户请求: userId={}, messageLength={}", 
    userId, 
    message.length()); // 不记录 message 内容

// 脱敏处理
public String maskPhone(String phone) {
    return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
}
```

---

## 性能优化

### 性能优化理论

**性能优化的层次**：
```
┌─────────────────────────────────────────┐
│  算法优化                                │
│  - 时间复杂度 O(n) → O(log n)           │
├─────────────────────────────────────────┤
│  架构优化                                │
│  - 缓存、异步、并行                       │
├─────────────────────────────────────────┤
│  代码优化                                │
│  - 减少对象创建、避免重复计算              │
├─────────────────────────────────────────┤
│  系统优化                                │
│  - JVM 调优、数据库索引                   │
└─────────────────────────────────────────┘
```

### 1. 缓存使用

**缓存理论**：
- **缓存命中率**：命中次数 / 总请求次数
- **缓存穿透**：查询不存在的数据
- **缓存击穿**：热点数据过期
- **缓存雪崩**：大量缓存同时过期

```java
@Cacheable(value = "agent-response", key = "#userId + ':' + #message")
public String getResponse(String userId, String message) {
    // 只有缓存未命中时才执行
}
```

### 2. 异步处理

**异步的优势**：
- 不阻塞主线程
- 提高系统吞吐量
- 改善用户体验

```java
@Async
public CompletableFuture<Void> processLongTask(String taskId) {
    // 耗时操作在后台执行
}

// 调用方式
CompletableFuture<Void> future = service.processLongTask(taskId);
// 立即返回，不等待
```

### 3. 性能监控

```java
// 记录执行时间
long start = System.currentTimeMillis();
// ... 执行业务逻辑
long duration = System.currentTimeMillis() - start;
log.info("操作耗时: {}ms", duration);
```

---

## 框架的优势与局限

### 优势

| 优势 | 说明 |
|------|------|
| **快速开发** | YAML 配置即可创建复杂 Agent |
| **企业级** | 内置安全管控 |
| **可扩展** | SPI 机制支持灵活扩展 |
| **多平台** | 统一适配多种 LLM 平台 |
| **智能** | 自适应策略、自我改进 |

### 局限

| 局限 | 说明 | 建议 |
|------|------|------|
| **学习曲线** | 概念较多，需要时间理解 | 按顺序阅读文档 |
| **依赖 LLM** | 效果受 LLM 能力限制 | 选择合适的模型 |
| **调试复杂** | Agent 行为不易预测 | 增加日志和监控 |
| **资源消耗** | 向量数据库等需要资源 | 合理规划资源 |

---

## 底层框架适配

本平台基于 **AgentScope-Java**（阿里巴巴开源，2.0.0 GA 正式版），在实际使用中遇到了一些底层框架的设计限制。

### 1. 工具组管理（理解框架内置工具与应用层工具的分组边界）

#### 框架内置工具不参与分组

`HarnessAgent` 在 `build()` 时通过 `Toolkit.registerTool(Object)` 注册内置工具（memory_search、list_files、execute、agent_spawn、agent_send 等），该方法不接收 group 参数，所以这些工具在 `ToolGroupManager` 中处于**未分组**状态：

```log
io.agentscope.core.tool.Toolkit - Registered tool 'memory_search' in group 'ungrouped'
io.agentscope.core.tool.Toolkit - Registered tool 'list_files' in group 'ungrouped'
io.agentscope.core.tool.Toolkit - Registered tool 'execute' in group 'ungrouped'
io.agentscope.core.tool.Toolkit - Registered tool 'agent_spawn' in group 'ungrouped'
```

这是框架的有意设计——内置工具是 Agent 的"基础设施"，始终可用，不参与工具组的开关管控。

`ToolGroupManager.isActiveTool()` 对未分组工具返回 `true`：

```java
public boolean isActiveTool(String toolName) {
    Set<String> groups = tools.get(toolName);
    if (groups == null || groups.isEmpty()) {
        return true;  // 未分组工具永远可见
    }
    ...
}
```

#### 升级说明：不干预框架内置工具

早期版本曾通过 `assignUngroupedTools()` 反射 hack 将未分组工具强行分配到 "general" 组。该方案已在 2.0.0（GA）中移除，原因：

- 底层框架的内置工具属于 Agent 基础设施能力，不参与分组是框架的设计意图
- 应用框架的职责是管好自己的工具，不应通过反射 hack 干预框架内部状态
- 依赖反射访问 `package-private` 的 `ToolGroupManager`，框架升级时极易断裂

#### 应用层工具的正确分组做法

应用框架创建的**自己的工具**（如 Supervisor 的子 Agent 工具），通过底层框架的 fluent API 正确归组：

```java
// AgentConfigurer.createSupervisorAgent()
toolkit.registration()
    .subAgent(provider, SubAgentConfig.builder().forwardEvents(false).build())
    .group("agent")        // 明确指定组名
    .apply();
```

组激活通过 YAML 配置管控，只操作已知的应用层工具组：

```java
// applyToolGroupActivation() 只操作这些已知组
List<String> knownGroups = List.of("agent", "memory", "filesystem", "execute", "page", "general");
```

| 组名 | 用途 | 创建方式 |
|------|------|----------|
| `agent` | 子 Agent 工具（Supervisor 用） | `createToolGroup()` + `registration().group("agent")` |
| `memory` | 记忆检索工具（预留） | 仅 `createToolGroup()` |
| `filesystem` | 文件系统工具（预留） | 仅 `createToolGroup()` |
| `execute` | 命令执行工具（预留） | 仅 `createToolGroup()` |
| `page` | 页面操作工具（预留） | 仅 `createToolGroup()` |
| `general` | 通用工具（预留） | 仅 `createToolGroup()` |

#### 关键理解

| 层面 | 分组状态 | 管控方式 |
|------|---------|----------|
| 框架内置工具（memory_search、agent_spawn 等） | 未分组，始终活跃 | 不受 `updateToolGroups()` 影响 |
| 应用层工具（Supervisor 的子 Agent、MCP 工具等） | 通过 `registration().group()` 归组 | 受 `updateToolGroups()` 管控 |

#### 涉及的文件

| 文件 | 说明 |
|------|------|
| `agent-core/.../agent/AgentConfigurer.java` | `applyToolGroupActivation()` 只操作已知的 6 个应用层工具组 |

---

### 2. MCP 工具组隔离

#### 问题

框架的 `Toolkit` 是单例模式，所有 MCP 服务器的工具都注册在同一个 `Toolkit` 实例上。当不同 Agent 需要访问不同的 MCP 工具集合时（例如 nutrition-assistant 应只能看到 nutrition 相关工具，不应看到 database 工具），缺乏天然隔离机制。

#### 解决方案

通过框架原生的 `Toolkit.registration()` API 在每个 Agent 的 `Toolkit` 中按 MCP 服务器名独立分组注册：

```java
toolkit.registration()
    .agentTool(agentTool)
    .group(serverName)  // 按 MCP 服务器名分组
    .apply();
```

然后在 Agent YAML 配置中按需启用：

```yaml
tools:
  groups:
    - mcp-data       # 只启用业务相关工具
    - database
```

通过 `applyToolGroupActivation()` 控制哪些组在推理时可见。

---

### 3. Toolkit 深拷贝后的组激活失效

#### 问题

`ReActAgent.Builder.build()` 内部会对 `Toolkit` 做深拷贝（`this.toolkit.copy()`），但 `AgentConfigurer` 的 `applyToolGroupActivation()` 之前操作的是原始 `Toolkit`，对 Agent 实际使用的拷贝没有效果。

```java
// ReActAgent.Builder.build() (框架源码)
Toolkit agentToolkit = this.toolkit.copy();  // 深拷贝
// 工具注册在拷贝上
agentToolkit.registerTool(...);
return new ReActAgent(agentToolkit);  // Agent 使用拷贝
```

#### 解决方案

通过 `HarnessAgent.getDelegate().getToolkit()` 获取 Agent 实际使用的 Toolkit：

```java
private Toolkit resolveAgentToolkit(Agent agent) {
    if (agent instanceof HarnessAgent harnessAgent) {
        return harnessAgent.getDelegate().getToolkit();
    }
    return null;
}
```

之后所有组激活操作都在此 Toolkit 上执行，确保生效。

---

**上一页**: [10. 技能系统](./10-skills.md)  
**下一页**: [12. 常见问题 →](./12-faq.md)
