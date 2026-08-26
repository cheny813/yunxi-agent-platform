# 13. 智能系统

Intelligent 模块是 yunxi Agent Platform 的基础 LLM 调用服务，提供简化的模型调用接口，封装 AgentScope 框架的 Model API。

## 实际模块结构

> **注意**：当前 Intelligent 模块是一个**轻量级 LLM 调用封装层**，仅包含 3 个类，**不包含**自适应策略引擎、反思机制、监控降级、事件总线等高级功能（这些功能计划在未来版本中实现）。

| 类 | 包 | 职责 |
|----|-----|------|
| `IntelligentLlmService` | `io.yunxi.platform.intelligent.llm` | 封装 `ModelFactory` + `Model.stream()`，提供同步的 `generate()` 和 `generateOrDefault()` 接口 |
| `IntelligentProperties` | `io.yunxi.platform.intelligent.config` | 配置属性，前缀 `intelligent`，含 `learning-loop` 和 `skill` 两个配置组 |
| `IntelligentAutoConfiguration` | `io.yunxi.platform.intelligent.config` | 自动装配 `IntelligentProperties`（`IntelligentLlmService` 使用 `@Service` 自动注册，无需显式声明 Bean） |

## IntelligentLlmService 使用示例

```java
@Autowired
private IntelligentLlmService intelligentLlmService;

// 简化调用（仅用户提示词）
String answer = intelligentLlmService.generate("什么是膳食指南？");

// 带系统提示词的调用
String answer = intelligentLlmService.generate(
    "你是一个营养学专家",
    "请解释蛋白质在人体中的作用"
);

// 带默认值的调用（失败时返回默认值）
String answer = intelligentLlmService.generateOrDefault(
    "你是一个食谱生成器",
    "推荐一道低脂菜",
    "抱歉，暂时无法生成推荐"
);
```

## 实现原理

`IntelligentLlmService.generate()` 的内部调用流程：

```
IntelligentLlmService.generate(systemPrompt, userPrompt)
  → ModelFactory.create(null)                     // 创建 Model 实例
  → Model.stream(messages, null, null)             // 流式调用 LLM
  → Flux<ChatResponse>.blockFirst(timeout)         // 阻塞获取首个响应
  → 解析 TextBlock 提取文本内容                     // 返回结果
```

流式调用超时时间由 `agentscope.core.chat-timeout-seconds` 配置（默认值见 `AgentscopeCoreProperties`）。

## 配置

```yaml
intelligent:
  learning-loop:
    enabled: true            # 是否启用学习循环
    review-model: default    # 审核使用的模型名称
    max-session-chars: 10000 # 会话最大字符数（摘要生成时使用）
  skill:
    enabled: true                      # 是否启用 Skill 自动创建
    auto-creator-min-confidence: 0.5   # 自动创建的置信度阈值
```

---

**上一页**: [12. 常见问题](./12-faq.md)  
**下一页**: [14. A2A 协议 →](./14-a2a-protocol.md)
