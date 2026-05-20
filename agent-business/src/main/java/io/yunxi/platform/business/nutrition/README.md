# Business 业务层

> **定位**：业务逻辑层，封装特定业务领域的规则和流程

## 📦 职责范围

业务层实现具体的业务逻辑，目前包含营养食谱业务线：

| 子包 | 职责 | 示例 |
|------|------|------|
| `service` | 业务服务 | `RecipeStreamService`、`DishVectorSearchProvider` |
| `controller` | API 控制器 | `RecipeSseController`、`NutritionController` |
| `domain` | 领域模型 | 营养计算、配平算法 |

## 🔗 依赖关系

```
┌─────────────────────────────────────────────────────┐
│                   business 层                        │
│       实现业务逻辑，调用 framework 服务               │
└─────────────────────────────────────────────────────┘
          │                           │
          ↓ depends on                ↓ implements
┌──────────────────┐         ┌──────────────────┐
│   framework      │         │   SPI (framework)│
│  (通用能力)       │         │   (接口)          │
└──────────────────┘         └──────────────────┘
          │
          ↓ depends on
┌──────────────────┐
│     shared       │
│  (基础组件)       │
└──────────────────┘
```

**依赖规则**：
- ✅ 依赖 `shared` 层（DTO、配置、SPI）
- ✅ 依赖 `framework` 层（Agent、对话、记忆）
- ✅ 实现 `framework` 层定义的 SPI 接口
- ❌ 不依赖 `infra` 层（通过 framework 间接使用）

## 🎯 业务线说明

### Nutrition 营养食谱业务线

营养食谱业务线提供校园餐智能配餐能力：

```
业务流程：
用户需求 → AI 理解 → 菜品推荐 → 营养配平 → 评分优化 → 食谱生成
```

| 核心能力 | 说明 | 实现类 |
|---------|------|--------|
| 食谱生成 | AI 生成营养均衡食谱 | `RecipeStreamService` |
| 营养配平 | 自动调整食材配比 | `RecipeStreamService` |
| 评分优化 | 营养评分达标优化 | `RecipeStreamService` |
| 菜品搜索 | 向量相似度搜索 | `DishVectorSearchProvider` |
| SSE 推送 | 实时进度反馈 | `RecipeSseController` |

## 📁 目录结构

```
business/
└── nutrition/          # 营养食谱业务线
    ├── service/        # 业务服务
    │   ├── RecipeStreamService.java        # 食谱流式生成
    │   └── DishVectorSearchProvider.java   # 菜品向量搜索
    ├── controller/     # API 控制器
    │   ├── RecipeSseController.java        # SSE 推送接口
    │   └── NutritionController.java        # 营养配置接口
    └── domain/         # 领域模型（未来扩展）
        ├── nutrition/
        │   ├── RecipeScorer.java           # 食谱评分
        │   └── NutritionCalculator.java    # 营养计算
        └── vector/
            └── DishVectorIndex.java        # 菜品向量索引
```

## 🔄 典型业务实现

### 服务 1: 食谱流式生成

```java
// business/nutrition/service/RecipeStreamService.java
@Service
public class RecipeStreamService {
    
    // 依赖 framework 层服务（通过接口）
    @Autowired
    private AgentDomainService agentDomainService;
    
    @Autowired
    private AgentscopeProperties properties;
    
    @Autowired
    private SseNotificationProvider emitterManager; // SPI 接口
    
    /**
     * 流式生成食谱
     */
    public Flux<ServerSentEvent<String>> streamGenerateRecipe(StreamChatRequest request) {
        // 1. 获取营养助手 Agent
        ReActAgent agent = agentDomainService.getAgentInstance("nutrition-assistant");
        
        // 2. 构建提示词（业务逻辑）
        String prompt = buildRecipePrompt(request);
        
        // 3. 调用 Agent 生成
        // 4. 流式返回结果
    }
    
    /**
     * 异步生成食谱（SSE 推送）
     */
    @Async
    public void generateRecipeAsync(String sessionId, RecipeGenerateRequest request) {
        ProgressListener<Map<String, Object>> listener = 
            progressListenerAdapter.createWithAutoComplete(sessionId);
        
        // 业务流程编排
        listener.onStart(taskId, "食谱生成任务");
        listener.onPhase(taskId, "数据准备", 1, 3);
        // ... AI 生成逻辑
        listener.onComplete(taskId, result);
    }
}
```

### 服务 2: 菜品向量搜索

```java
// business/nutrition/service/DishVectorSearchProvider.java
@Service
public class DishVectorSearchProvider implements VectorSearchProvider {
    
    @Autowired
    private VectorPersistenceProvider vectorProvider; // SPI 接口
    
    @Override
    public List<VectorData> search(Long schoolId, String query, int topK) {
        // 1. 构建业务特定查询条件
        String collectionName = "school_dishes_" + schoolId;
        
        // 2. 调用向量搜索（通过接口）
        // 3. 过滤和排序结果
        // 4. 返回业务数据
    }
}
```

### 控制器: SSE 推送接口

```java
// business/nutrition/controller/RecipeSseController.java
@RestController
@RequestMapping("/api/nutrition")
public class RecipeSseController {
    
    @Autowired
    private RecipeStreamService recipeStreamService;
    
    /**
     * 流式生成食谱
     */
    @PostMapping("/recipe/stream")
    public Flux<ServerSentEvent<String>> streamRecipe(@RequestBody StreamChatRequest request) {
        return recipeStreamService.streamGenerateRecipe(request);
    }
    
    /**
     * 异步生成食谱（SSE）
     */
    @GetMapping("/recipe/async")
    public SseEmitter generateRecipeAsync(@RequestParam String sessionId,
                                          @RequestBody RecipeGenerateRequest request) {
        SseEmitter emitter = emitterManager.createEmitter(sessionId);
        recipeStreamService.generateRecipeAsync(sessionId, request);
        return emitter;
    }
}
```

## 🎯 业务层与框架层协作

### 协作模式 1: 使用框架服务

```java
// business 层使用 framework 层的通用能力
@Service
public class RecipeStreamService {
    
    // 使用 framework 层的 Agent 管理
    @Autowired
    private AgentDomainService agentDomainService;
    
    // 使用 framework 层的记忆系统
    @Autowired
    private SmartMemoryDomainService smartMemoryDomainService;
    
    // 使用 framework 层的对话服务
    @Autowired
    private ConversationDomainService conversationDomainService;
}
```

### 协作模式 2: 实现框架 SPI

```java
// framework 定义 SPI
// framework/spi/VectorSearchProvider.java
public interface VectorSearchProvider {
    List<VectorData> search(Long schoolId, String query, int topK);
}

// business 层实现 SPI
// business/nutrition/service/DishVectorSearchProvider.java
@Service
public class DishVectorSearchProvider implements VectorSearchProvider {
    @Override
    public List<VectorData> search(Long schoolId, String query, int topK) {
        // 业务特定的菜品搜索逻辑
    }
}

// framework 层调用（通过接口注入）
// framework/conversation/ChatAppService.java
@Service
public class ChatAppService {
    @Autowired
    private VectorSearchProvider vectorSearchProvider; // 自动注入 business 实现
}
```

## ⚠️ 开发规范

### ✅ 应该放在 business 层

- 具体业务逻辑（营养计算、食谱生成）
- 业务特定服务（菜品搜索、配平算法）
- 业务 API 控制器
- 领域模型和业务规则

### ❌ 不应放在 business 层

- 通用能力（Agent 管理、对话管理）→ framework
- 技术实现（数据库、缓存、MQ）→ infra
- 基础组件（DTO、异常、工具类）→ shared

### 🔒 依赖检查示例

```java
// ✅ 正确的依赖
import io.yunxi.platform.framework.agent.AgentDomainService;
import io.yunxi.platform.framework.spi.VectorSearchProvider;
import io.yunxi.platform.shared.config.AgentscopeProperties;

// ❌ 错误的依赖
import io.yunxi.platform.infra.cache.RedisCacheService;  // 应通过 framework 间接使用
import io.yunxi.platform.infra.vector.MilvusClient;      // 应通过 SPI 接口使用
```

## 📋 扩展新业务线

添加新业务线的步骤：

### 1. 创建业务线目录

```
business/
└── new-business/       # 新业务线
    ├── service/
    ├── controller/
    └── domain/
```

### 2. 实现业务服务

```java
// business/new-business/service/NewBusinessService.java
@Service
public class NewBusinessService {
    
    @Autowired
    private AgentDomainService agentDomainService; // 使用 framework 能力
    
    // 业务逻辑实现
}
```

### 3. 定义业务 Agent

```yaml
# resources/agents/new-business-agent.yml
name: new-business-agent
sys_prompt: |
  你是一个新业务助手...
model: qwen-plus
```

### 4. 创建 API 控制器

```java
// business/new-business/controller/NewBusinessController.java
@RestController
@RequestMapping("/api/new-business")
public class NewBusinessController {
    
    @Autowired
    private NewBusinessService service;
    
    @PostMapping("/process")
    public Result process(@RequestBody Request request) {
        return service.process(request);
    }
}
```

## 🧪 测试策略

```java
// 业务服务测试
@SpringBootTest
class RecipeStreamServiceTest {
    
    @Autowired
    private RecipeStreamService recipeStreamService;
    
    @MockBean
    private AgentDomainService agentDomainService; // Mock framework 层
    
    @Test
    void testGenerateRecipe() {
        // 测试业务逻辑
    }
}

// SPI 实现测试
class DishVectorSearchProviderTest {
    
    @Mock
    private VectorPersistenceProvider vectorProvider; // Mock infra 层
    
    @InjectMocks
    private DishVectorSearchProvider searchProvider;
    
    @Test
    void testSearch() {
        // 测试向量搜索逻辑
    }
}
```

## 📖 相关文档

- [Framework 层说明](../../framework/README.md)
- [Infra 层说明](../../infra/README.md)
- [Shared 层说明](../../shared/README.md)
- [配置指南](../../../../../../../docs/配置指南.md)

## 🔮 未来扩展

业务层可根据业务发展持续扩展：

- `business/education/` - 教育业务线
- `business/health/` - 健康管理业务线
- `business/finance/` - 金融业务线

每个业务线独立开发，互不影响。
