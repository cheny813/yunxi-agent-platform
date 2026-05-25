# 07. 开发指南

学习如何扩展和定制 yunxi Agent Platform。

## 设计模式参考

本框架使用了多种设计模式，详细理论请参考：
- [03. 核心概念 - SPI 扩展机制](./03-concepts.md#spi-扩展机制)
- [04. 架构设计 - 关键组件详解](./04-architecture.md#关键组件详解)

### 开发中常用的设计模式

| 模式 | 应用场景 | 参考章节 |
|------|----------|----------|
| **SPI 模式** | 扩展框架功能 | [03. 核心概念](./03-concepts.md) |
| **适配器模式** | 工具适配 | [04. 架构设计](./04-architecture.md) |
| **门面模式** | 简化复杂调用 | [04. 架构设计](./04-architecture.md) |
| **策略模式** | 规则评估 | [04. 架构设计](./04-architecture.md) |

---

## 开发架构理解

```
第 4 层: 你的业务代码
  - 实现 yunxi 的 SPI 接口 (DomainContributor, SceneContributor)
  - 调用 yunxi 的服务接口 (AgentDomainService, ChatAppService)
  - 编写业务逻辑

第 3 层: yunxi Agent Platform
  - AgentDomainService (Agent 生命周期管理)
  - ChatAppService (对话编排)
  - SupervisorService (多 Agent 编排)
  - RuleEngine (规则管控)
  - SceneRouter (场景路由)
  - McpClientService (MCP 工具调用)
  - A2AClient (跨服务 Agent 调用)
  - IntelligentFacadeService (智能服务门面)

第 2 层: AgentScope-Java
  - ChatAppService (实际 Agent 编排运行时)
  - McpClient (MCP 协议客户端)
  - MemoryManager (记忆管理)
  - A2AProtocol (Agent 间通信协议)

第 1 层: 基础设施
  - Spring Boot / Redis / MySQL / LLM API
```

**开发原则**：
1. 你的代码只与第 4 层和第 3 层交互
2. 不要直接调用 AgentScope-Java 的 API
3. 通过 yunxi 的 SPI 机制扩展功能
4. 通过 yunxi 的服务接口使用框架能力

---

## 创建业务领域

### 实现 DomainContributor

业务领域通过实现 `DomainContributor` 接口定义：

```java
@Component
public class MyDomainContributor implements DomainContributor {
    
    @Override
    public Map<String, List List<Pattern>> getDomainPatterns() {
        // 返回 Map Map<领域名称, 关键词模式列表>
        return Map.of(
            "nutrition",
            List.of(
                Pattern.compile("营养|食谱|配餐"),
                Pattern.compile("热量|蛋白质|维生素")
            )
        );
    }
    
    @Override
    public Map<String, Set<String>> getAgentCapabilities() {
        // 返回 Map Map<Agent名称, 能力集合>
        return Map.of(
            "nutrition-assistant",
            Set.of("recipe-analysis", "nutrition-calculation", "scoring")
        );
    }
}
```

**关键概念**：
- **DomainPattern**：定义领域识别模式
- **AgentCapability**：定义该领域支持的 Agent 能力
- **@Component**：Spring 自动扫描注册

### 实现 SceneContributor

定义场景和上下文组装逻辑：

```java
@Component
public class MySceneContributor implements SceneContributor {
    
    @Autowired
    private MyDataRepository dataRepository;
    
    @Override
    public Map<String, List<String>> getSceneKeywords() {
        // 返回 Map Map<场景名称, 关键词列表>
        return Map.of(
            "RECIPE_ANALYSIS", List.of("食谱", "分析", "营养"),
            "SCORING", List.of("评分", "评估", "检查")
        );
    }
    
    @Override
    public String getExtractionPrompt(String sceneName) {
        return """
            请从用户输入中提取以下信息：
            1. 参数A
            2. 参数B
            输出格式：JSON
            """;
    }
    
    @Override
    public String assembleContext(String sceneName, String userId, String query) {
        // 查询业务数据
        MyData data = dataRepository.findByUserId(userId);
        
        // 返回格式化后的上下文文本
        return String.format("""
            用户数据：%s
            当前查询：%s
            """, data, query);
    }
}
```

**关键概念**：
- **SceneKeywords**：场景识别关键词
- **ExtractionPrompt**：参数提取的 LLM Prompt
- **assembleContext**：组装上下文数据

### 注册到框架

Spring 会自动扫描并注册所有 SPI 实现。

**原理**：
```java
// Spring 的依赖注入机制
@Service
public class DomainRouter {
    // 自动注入所有 DomainContributor 实现
    @Autowired
    private List List<DomainContributor> contributors;
    
    @PostConstruct
    public void init() {
        // 遍历所有实现，构建路由表
        for (DomainContributor contributor : contributors) {
            register(contributor);
        }
    }
}
```

---

## 创建自定义规则

### 规则理论基础

**什么是业务规则**：
- 业务规则是描述业务约束和逻辑的声明
- 与业务流程分离，便于独立管理
- 支持动态修改，无需重新部署

**规则引擎的优势**：
| 优势 | 说明 |
|------|------|
| **声明式** | 用声明式语言描述规则，而非代码 |
| **可维护** | 业务人员可直接修改规则 |
| **可追踪** | 规则执行结果可追溯 |
| **高性能** | 规则引擎优化执行效率 |

### 实现 RuleDefinitionProvider

```java
@Component
public class MyRuleProvider implements RuleDefinitionProvider {

    @Override
    public List List<RuleDefinition> getRuleDefinitions() {
        return List.of(
            SpELRule.builder()
                .name("my-check")
                .phase(RulePhase.PRE)
                .condition("#context.get('user') != null")
                .build()
        );
    }
}
```

**关键概念**：
- **RulePhase**：规则执行阶段（PRE/IN/POST）
- **SpEL**：Spring Expression Language，表达式语言
- **condition**：规则条件表达式

### SpEL 表达式详解

**基本语法**：
```java
// 访问属性
#context.user.name

// 方法调用
#context.getUser().getName()

// 逻辑运算
#context.age >= 18 && #context.verified

// 集合操作
#context.roles.contains('admin')

// 正则匹配
#context.email matches '[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}'
```

---

## 创建 MCP 工具

### MCP 工具理论基础

**什么是 MCP 工具**：
- MCP（Model Context Protocol）是 Anthropic 提出的标准协议
- 允许 LLM 调用外部工具和服务
- 统一的工具描述格式和调用方式

**工具的生命周期**：
```
定义 → 注册 → 发现 → 调用 → 返回
```

### 创建工具处理器

```java
@Component
public class MyTool implements Tool {

    @Override
    public String getName() {
        return "my_tool";
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name(getName())
            .description("我的工具")
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        // 执行业务逻辑
        return ToolResult.success("结果");
    }
}
```

**关键概念**：
- **ToolDefinition**：工具元数据定义
- **execute**：工具执行逻辑
- **ToolResult**：工具执行结果

### 注册到框架

```java
@Component
public class MyTool implements Tool {

    @Override
    public String getName() {
        return "my_tool";
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name(getName())
            .description("我的工具")
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        // 执行业务逻辑
        return ToolResult.success("结果");
    }
}
```

**原理**：
```
┌─────────────────────────────────────────┐
│  MCP 工具注册流程                        │
├─────────────────────────────────────────┤
│                                         │
│  1. 创建 Tool 实现类                       │
│     ↓                                   │
│  2. 在 Controller 中注册                  │
│     ↓                                   │
│  3. Spring 启动时扫描                     │
│     ↓                                   │
│  4. 注册到 McpToolRegistry               │
│     ↓                                   │
│  5. Agent 可以调用工具                    │
│                                         │
└─────────────────────────────────────────┘
```

---

## 扩展上下文

### 上下文扩展理论基础

**什么是上下文（Context）**：
- 上下文是请求执行时的环境信息集合
- 包含用户、场景、参数、中间结果等
- 贯穿整个请求处理流程

**为什么需要上下文扩展**：
- 框架提供的上下文字段有限
- 业务需要自定义字段传递数据
- 实现跨组件数据共享

### 实现 ContextEnricher

```java
@Component
public class MyContextEnricher implements ContextEnricher {

    @Override
    public boolean supports(Map<String, Object> contextData) {
        // 判断是否支持处理该上下文
        return contextData.containsKey("scene") 
            && "nutrition".equals(contextData.get("scene"));
    }

    @Override
    public String enrich(Map<String, Object> contextData, String userMessage) {
        // 搜索/补充额外信息，返回格式化的增强文本
        String userId = (String) contextData.get("userId");
        UserPreference pref = loadUserPreference(userId);
        return String.format("用户偏好：%s", pref);
    }
    
    @Override
    public String formatKey(String key, Object value) {
        // 格式化特定 key 的上下文数据
        if ("calories".equals(key)) {
            return String.format("热量：%s kcal", value);
        }
        return null; // 不处理该 key
    }
    
    @Override
    public String appendPrompt(Map<String, Object> contextData) {
        // 追加提示文本到上下文
        return "请注意营养均衡搭配。";
    }
}
```

**关键概念**：
- **supports**：判断是否支持处理该上下文
- **enrich**：搜索/补充额外信息
- **formatKey**：格式化特定 key 的数据
---

## 创建自定义知识库类型

### 扩展点：KnowledgeCreator

`KnowledgeCreator` SPI 接口用于扩展新的知识库类型。新增类型只需三步，无需修改任何已有代码。

**接口定义**：

```java
public interface KnowledgeCreator {
    /** 返回知识库类型标识（如 "elasticsearch"、"pgvector"），与 YAML 中 type 字段对应 */
    String getType();
    /** 根据配置创建 Knowledge 实例 */
    Knowledge create(KnowledgeBaseConfig config);
    /** 是否默认启用 */
    default boolean isEnabledByDefault() { return false; }
}
```

### 扩展示例：Elasticsearch 知识库

```java
@Component
public class ElasticsearchKnowledgeCreator implements KnowledgeCreator {

    @Override
    public String getType() {
        return "elasticsearch";
    }

    @Override
    public Knowledge create(KnowledgeBaseConfig config) {
        // 使用 AgentScope SDK 的 Builder API 创建
        return ElasticsearchKnowledge.builder()
                .config(ElasticsearchConfig.builder()
                        .url(config.getApiUrl())
                        .apiKey(config.getApiKey())
                        .indexName(config.getDatasetId())
                        .build())
                .build();
    }
}
```

### 配置 YAML

```yaml
agentscope:
  extensions:
    knowledge-bases:
      my-elastic:
        enabled: true
        type: elasticsearch       # ← 与 getType() 返回值一致
        api-url: http://localhost:9200
        api-key: ${ES_API_KEY:}
        dataset-id: my-index
```

### 自动注册流程

```
@Component 扫描 → KnowledgeAutoConfiguration 发现 ElasticsearchKnowledgeCreator
                         ↓ 注册到 creatorMap（type → creator）
YAML 中配置 type: elasticsearch → 匹配 ElasticsearchKnowledgeCreator
                         ↓ create(config)
ElasticsearchKnowledge 实例 → registerSingleton("myElastic")
```

---

## 单元测试

### 测试理论基础

**为什么需要单元测试**：
- 验证代码正确性
- 便于重构（有测试保障）
- 作为代码文档
- 提前发现问题

**测试金字塔**：
```
         /\
        /  \
       / E2E \      端到端测试（少）
      /--------\
     /  集成测试 \   集成测试（中）
    /------------\
   /   单元测试    \  单元测试（多）
  /----------------\
```

### 编写单元测试

```java
@SpringBootTest
class MyAgentTest {

    @Autowired
    private AgentDomainService agentService;

    @Test
    void testHandleRequest() {
        // 1. 准备测试数据
        AgentRequest request = AgentRequest.builder()
            .message("测试消息")
            .userId("test-user")
            .build();

        // 2. 执行测试
        AgentResponse response = agentService.handleRequest("my-agent", request);

        // 3. 验证结果
        assertNotNull(response);
        assertNotNull(response.getContent());
        assertFalse(response.getContent().isEmpty());
    }
    
    @Test
    void testWithMock() {
        // 使用 Mockito 模拟依赖
        when(mockService.getData()).thenReturn(testData);
        
        // 执行测试...
    }
}
```

### 测试最佳实践

| 实践 | 说明 | 示例 |
|------|------|------|
| **AAA 模式** | Arrange-Act-Assert | 准备-执行-验证 |
| **独立测试** | 测试之间不依赖 | 每个测试独立运行 |
| **描述性命名** | 测试名描述行为 | `shouldReturnErrorWhenInvalidInput` |
| **单一职责** | 一个测试验证一个概念 | 避免大而全的测试 |

---

## 调试技巧

### 日志调试

**日志级别**：
| 级别 | 使用场景 |
|------|----------|
| ERROR | 错误，需要处理 |
| WARN | 警告，需要注意 |
| INFO | 关键信息，正常运行 |
| DEBUG | 调试信息，开发使用 |
| TRACE | 最详细的信息 |

**日志示例**：
```java
// 记录关键步骤
log.info("开始处理请求: userId={}, query={}", userId, query);

// 记录调试信息
log.debug("上下文数据: {}", context);

// 记录错误
log.error("处理失败", exception);
```

### 断点调试

**常用断点位置**：
- Agent 的 handleRequest 方法
- 规则的 evaluate 方法
- MCP 工具的 execute 方法
- 上下文组装逻辑

**调试技巧**：
1. 使用条件断点（如只在特定用户时断住）
2. 使用 Evaluate Expression 查看变量值
3. 使用 Step Over/Into/Out 控制执行流程

---

**上一页**: [06. 配置指南](./06-configuration.md)  
**下一页**: [08. 部署指南 →](./08-deployment.md)
