# 11. 最佳实践

## Agent 设计原则

> 平台遵循通用软件工程原则（单一职责、开闭、依赖倒置、DRY、KISS、YAGNI）。下文聚焦与 yunxi 平台落地直接相关的设计实践。

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
    private ExecutionFacade executionFacade; // 对话执行（无状态门面）

    public AgentResponse handleRequest(AgentCommand command) {
        // 不保存任何实例变量状态；每次调用经 ExecutionFacade 驱动 agent.streamEvents
        Flux<AgentEvent> events = executionFacade.execute(command);
        // 处理请求...
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

### 提示词工程

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

### 2. 少样本学习（Few-Shot）

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

### 3. 思维链（Chain of Thought）

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

### 1. 短期记忆

对话上下文由 AgentScope-Java 的会话状态（`AgentStateStore`）承载，按 `userId + sessionId` 持久化，平台支持进程内 / Redis / MySQL / PostgreSQL 等后端（见 [06. 配置指南 · 语义与状态](./06-configuration.md#语义与状态)）。

### 2. 长期记忆

跨会话长期记忆直接复用框架原生 `MEMORY.md`（`agentscope-harness` 的 `MemoryFlushMiddleware` / `MemoryMaintenanceMiddleware` + 内置 `memory_search` / `memory_get` 工具），yunxi 不自建跨会话记忆存储，避免双份真相（保持产品平面纯净，不重复实现框架已有能力）。阈值与刷新策略由 `yunxi.memory.*` 经 `MemoryProperties` 配置，记忆文件落工作区文件系统。

RAG 文档分块存储已迁移到框架共享存储抽象（`BaseStore`）：分布式部署走框架 Redis 实现，单机走 yunxi 内存实现，共用同一抽象使 RAG 检索平面在任意部署形态下可存续，不引入第二份分块存储真相源；检索为轻量「关键词命中率 × 时间衰减」融合打分，见 `yunxi-runtime/rag/SharedStoreBackedDocumentChunkStore`。

---

## 安全合规

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

### 1. 缓存使用

```java
@Cacheable(value = "agent-response", key = "#userId + ':' + #message")
public String getResponse(String userId, String message) {
    // 只有缓存未命中时才执行
}
```

### 2. 异步处理

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

本平台基于 **AgentScope-Java**（阿里巴巴开源，2.1.0-BETA1），在实际使用中遇到了一些底层框架的设计限制。

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

早期版本曾通过 `assignUngroupedTools()` 反射 hack 将未分组工具强行分配到 "general" 组。该方案已在 2.0.0 中移除，原因：

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

#### 按需摘除框架默认工具（而非重新实现）

上述"未分组、始终活跃"的内置工具里，**`read_file` / `write_file` / `list_files` / `grep_files` / `glob_files` / `execute`** 属于 AgentScope-Java 默认工作区工具集，每个 Agent 构建时自动装备。它们**不在** `updateToolGroups()` 的 6 个已知组里，因此工具组开关无法关闭它们——这正是早期"反射 hack 强行归组"被废弃的原因。

> `load_skill_through_path` 是技能查看器工具，只在 Agent 配置了技能仓库（AgentSkillRepository，如 MUSE 自进化挂载的元工具）时才由框架注册。只有「明确不让该 Agent 加载技能」时才 deny 它，否则会破坏需要技能的 Agent 的技能加载。

如果某个业务 Agent 确实不需要文件 / 代码执行能力（如营养师），**正确做法**是在 Agent 定义 YAML 中声明 `toolkit.deniedToolNames`（见 [06. 配置指南 · 工具箱裁剪](./06-configuration.md#工具箱裁剪按-agent-摘除框架默认工具)），由 `AgentConfigurer` 翻译为框架 `ToolsConfig.deny`，于 `HarnessAgent` 构建期从工具箱移除。

> 需要文件能力时，不声明 `toolkit` 字段（保留框架默认）即可，不要新增等价工具。

#### 涉及的文件

| 文件 | 说明 |
|------|------|
| `yunxi-runtime/.../agent/AgentConfigurer.java` | `applyToolGroupActivation()` 只操作已知的 6 个应用层工具组 |

---

### 2. MCP 工具组隔离

#### 问题

框架的 `Toolkit` 是单例模式，所有 MCP 服务器的工具都注册在同一个 `Toolkit` 实例上。当不同 Agent 需要访问不同的 MCP 工具集合时（例如 nutrition-assistant 应只能看到 nutrition 相关工具，不应看到 database 工具），缺乏天然隔离机制。

#### 解决方案

通过框架原生的 `Toolkit.registration()` API 在每个 Agent 的 `Toolkit` 中按 MCP 服务器名独立分组注册（由 `AgentConfigurer` 经 `McpDirectory` 使用框架原生 `McpClientBuilder` 连接外部 MCP 服务器）：

```java
toolkit.registration()
    .mcpClient(wrapper)   // McpClientBuilder：AgentScope MCP SDK 客户端
    .group(serverName)    // 按 MCP 服务器名分组
    .apply();
```

然后在 Agent YAML 配置中按需激活（工具组激活由 `SessionToolGroupActivationMiddleware` 承载，按 `permission.toolGate.tools` 与 `toolkit.deniedToolNames` 控制可见工具，未配置时默认激活 `memory` 组）：

```yaml
mcp:
  servers: [database]                # 订阅 mcp.yml 中同名 static-server，加载并激活 database 服务器
permission:
  toolGate:
    enabled: false
    tools: []                         # 如需限制可见工具组，在此声明允许的 MCP 服务器名 / 工具组
toolkit:
  deniedToolNames: []                # 显式拒绝的工具名
```

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
**下一页**: [常见问题 →](./faq.md)
