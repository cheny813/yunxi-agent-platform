# 14. 智能子系统

Intelligent 子系统是 yunxi Agent Platform 的高级功能模块，提供自适应策略、自我改进、智能监控等能力。

## 系统架构

```
IntelligentFacadeService (统一门面)
    │
    ├── AdaptiveStrategyEngine (自适应策略引擎)
    │   ├── TaskAnalyzer (任务分析)
    │   ├── AgentSelector (Agent 选择)
    │   ├── ToolCapabilityRegistry (工具能力注册表)
    │   └── PerformanceTracker (性能追踪)
    │
    ├── AgentReflectionService (Agent 反思)
    │   ├── 执行回顾
    │   ├── 经验提取
    │   └── 改进建议
    │
    ├── UnifiedSelfImprovingService (统一自改进)
    │   ├── 问题识别
    │   ├── 方案生成
    │   └── 效果验证
    │
    ├── IntelligentMonitorService (智能监控)
    │   ├── 异常检测
    │   ├── 性能分析
    │   └── 告警管理
    │
    ├── IntelligentFallbackService (智能降级)
    │   ├── 故障检测
    │   ├── 降级策略
    │   └── 恢复机制
    │
    ├── EvolutionAuditService (进化审计)
    │   ├── 基因管理
    │   ├── 版本控制
    │   └── 回滚机制
    │
    ├── UnifiedMemoryManager (统一记忆管理)
    │   ├── 记忆分层
    │   ├── 记忆同步
    │   └── 记忆检索
    │
    └── IntelligentEventBus (智能事件总线)
        ├── 事件发布
        ├── 事件订阅
        └── 事件处理
```

## 自适应策略引擎

### 功能说明

根据任务特征动态选择最优的 Agent 和工具组合。

### 核心组件

| 组件 | 职责 |
|------|------|
| TaskAnalyzer | 分析任务复杂度、领域、时效性要求 |
| AgentSelector | 根据任务特征选择最适合的 Agent |
| ToolCapabilityRegistry | 管理工具的能力标签和性能指标 |
| PerformanceTracker | 追踪各 Agent 和工具的历史表现 |

### 使用示例

```java
@Service
public class MyService {
    
    @Autowired
    private AdaptiveStrategyEngine strategyEngine;
    
    public void processTask(TaskRequest request) {
        // 分析任务
        TaskProfile profile = strategyEngine.analyze(request);
        
        // 获取推荐策略
        ExecutionStrategy strategy = strategyEngine.recommend(profile);
        
        // 执行
        strategy.execute();
    }
}
```

### 配置

```yaml
intelligent:
  strategy:
    enabled: true
    default-strategy: balanced  # balanced | performance | cost
    learning-rate: 0.1
    history-window: 100  # 历史窗口大小
```

## Agent 反思机制

### 功能说明

Agent 执行后自动回顾执行过程，提取经验教训，持续改进。

### 工作流程

```
1. 执行完成
    ↓
2. 回顾执行过程 (Review)
    - 执行步骤回放
    - 关键决策点分析
    ↓
3. 提取经验 (Extract)
    - 成功经验
    - 失败教训
    - 改进建议
    ↓
4. 存储到经验库
    ↓
5. 应用到后续执行
```

### 使用示例

```java
@Service
public class MyService {
    
    @Autowired
    private AgentReflectionService reflectionService;
    
    public void afterExecution(String executionId) {
        // 触发反思
        ReflectionResult result = reflectionService.reflect(executionId);
        
        // 获取改进建议
        List<ImprovementSuggestion> suggestions = result.getSuggestions();
        
        // 应用到配置
        applySuggestions(suggestions);
    }
}
```

## 统一自改进服务

### 功能说明

自动识别系统问题，生成改进方案，验证改进效果。

### 改进维度

| 维度 | 说明 | 示例 |
|------|------|------|
| 提示词优化 | 根据执行效果优化提示词 | 自动调整 few-shot 示例 |
| 工具选择 | 优化工具调用策略 | 选择更合适的工具组合 |
| 参数调优 | 调整模型参数 | temperature、max_tokens |
| 流程优化 | 优化执行流程 | 减少不必要的步骤 |

### 配置

```yaml
intelligent:
  self-improving:
    enabled: true
    auto-apply: false  # 是否自动应用改进
    review-interval: 24h
    min-samples: 10    # 最小样本数
```

## 智能监控

### 功能说明

自动检测异常，分析性能瓶颈，触发告警。

### 监控指标

| 指标类别 | 具体指标 |
|----------|----------|
| Agent 指标 | 响应时间、成功率、调用频率 |
| 工具指标 | 调用次数、执行时间、错误率 |
| 系统指标 | CPU、内存、数据库连接数 |
| 业务指标 | 用户满意度、任务完成率 |

### 告警规则

```yaml
intelligent:
  monitor:
    alerts:
      - name: high-error-rate
        condition: error_rate > 0.1
        duration: 5m
        severity: warning
        
      - name: slow-response
        condition: p99_latency > 5000
        duration: 10m
        severity: critical
```

## 智能降级

### 功能说明

当系统负载过高或依赖故障时，自动降级非核心功能，保证核心服务可用。

### 降级策略

| 策略 | 说明 | 场景 |
|------|------|------|
| 功能降级 | 关闭非核心功能 | 系统负载高 |
| 质量降级 | 使用更快的模型 | 响应时间要求 |
| 缓存降级 | 增加缓存使用 | 数据库压力大 |
| 异步降级 | 同步改异步 | 队列积压 |

### 使用示例

```java
@Service
public class MyService {
    
    @Autowired
    private IntelligentFallbackService fallbackService;
    
    public Response process(Request request) {
        // 检查是否需要降级
        if (fallbackService.shouldFallback("my-feature")) {
            return fallbackService.executeFallback("my-feature", request);
        }
        
        // 正常执行
        return normalProcess(request);
    }
}
```

## 统一记忆管理

### 功能说明

统一管理短期记忆、长期记忆、向量记忆，支持记忆分层和同步。

### 记忆分层

```
L1: 工作记忆 (Working Memory)
    - 当前对话上下文
    - 最近 10 轮对话
    - 内存存储
    
L2: 短期记忆 (Short-term Memory)
    - 本次会话历史
    - 最近 24 小时
    - Redis 存储
    
L3: 长期记忆 (Long-term Memory)
    - 重要事实和偏好
    - 永久存储
    - 向量数据库存储
    
L4: 持久记忆 (Persistent Memory)
    - 结构化知识
    - 关系型数据库存储
```

### 使用示例

```java
@Service
public class MyService {
    
    @Autowired
    private UnifiedMemoryManager memoryManager;
    
    public void chat(String userId, String message) {
        // 检索相关记忆
        List<MemoryEntry> memories = memoryManager.retrieve(
            userId, 
            message, 
            MemoryTier.LONG_TERM,
            5  // topK
        );
        
        // 组装上下文
        Context context = Context.builder()
            .memories(memories)
            .build();
        
        // 执行...
        
        // 存储新记忆
        memoryManager.store(userId, MemoryEntry.builder()
            .content(response)
            .tier(MemoryTier.SHORT_TERM)
            .build());
    }
}
```

## 事件总线

### 功能说明

智能子系统各组件间通过事件总线进行异步通信。

### 事件类型

| 事件 | 说明 |
|------|------|
| ExecutionStartedEvent | 执行开始 |
| ExecutionCompletedEvent | 执行完成 |
| ExecutionFailedEvent | 执行失败 |
| StrategyChangedEvent | 策略变更 |
| ImprovementAppliedEvent | 改进应用 |
| AlertTriggeredEvent | 告警触发 |

### 订阅事件

```java
@Component
public class MyEventHandler implements IntelligentEventHandler {
    
    @Override
    @Subscribe
    public void onExecutionCompleted(ExecutionCompletedEvent event) {
        // 处理执行完成事件
        log.info("Execution completed: {}", event.getExecutionId());
    }
    
    @Override
    @Subscribe
    public void onAlertTriggered(AlertTriggeredEvent event) {
        // 处理告警事件
        sendNotification(event);
    }
}
```

## 配置汇总

```yaml
intelligent:
  enabled: true
  
  # 策略引擎
  strategy:
    enabled: true
    default-strategy: balanced
    
  # 反思机制
  reflection:
    enabled: true
    auto-trigger: true
    
  # 自改进
  self-improving:
    enabled: true
    auto-apply: false
    
  # 监控
  monitor:
    enabled: true
    metrics-export: true
    
  # 降级
  fallback:
    enabled: true
    default-strategy: graceful
    
  # 记忆
  memory:
    enabled: true
    default-tier: short_term
    sync-interval: 5m
```

---

**上一页**: [13. 更新日志](./13-changelog.md)
