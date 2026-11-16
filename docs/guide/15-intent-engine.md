# 15. 意图引擎

本章讲解 yunxi Agent Platform 的意图引擎（Intent Engine）：一套把用户自然语言文本**语义归类到目标 Agent** 的路由组件，位于 `yunxi-intelligence` 模块（包 `io.yunxi.intelligence`）。核心是四段式管线：**归一化与槽位抽取 → 候选收窄 → 本地决策模型闭集分类 → 决策融合与三级降级**。

> **当前范围**：管线已实现语义多域分类（本地决策模型 + 结构化输出）、实体槽位抽取与改写、租户候选隔离、置信度阈值判定、三级 fail-open 降级与决策留痕。**不含**指代消解（多轮上文补全，v2 演进项）、多意图并发分发、意图驱动的模型分流（仅透传标签）、向量检索分类器与在线学习。

## 为什么需要意图引擎

在按 `agentId` 显式指定目标 Agent 之外，统一入口（`/api/conversations`）需要"按文本语义自动选 Agent"：关键词子串匹配对同义表达无能（"血糖高吃什么" vs "糖尿病饮食"），且平台已有多个领域 Agent（通用 / 编码 / 营养 / Text2SQL）。意图引擎以候选 Agent 卡片为**闭集**做单标签分类，并让解析结果进入执行链路。

## 管线与契约

```
IntentRequest{text, tenantId, sessionId, maxCandidates}
        │
        ├─ L0 归一化（IntentTextNormalizer）+ 槽位抽取（SlotExtractor）
        ├─ L1 候选收窄（AgentCandidateProvider：ConfigCenter 目录 ∩ 租户允许集合）
        │        单候选 / 空白文本 / 关键词显式目标命中 → 短路，零模型调用
        ├─ L2 分类判定（IntentClassifier：本地决策模型，闭集单标签）
        └─ L3 决策融合（IntentDecider：越权拒绝 → 阈值 → 关键词 → 默认）
        │
IntentResolution{label, confidence, targetAgentId, entities, rewrittenQuery, source}
```

### IntentResolution（解析结果）

```java
public record IntentResolution(
        String label,                  // 业务意图标签；无匹配为 "unknown"
        double confidence,             // 0~1
        String targetAgentId,          // 目标 Agent；null/空白表示无匹配
        Map<String, String> entities,  // 规范化实体（规则值优先于模型值）
        String rewrittenQuery,         // 实体改写后的查询；不替换用户原文
        Source source) {               // LLM | KEYWORD | FALLBACK
    public Intent asIntent() { ... }   // 投影为轻量 Intent 供执行链路消费
}
```

`Intent`（`label` / `confidence` / `targetAgentId`）保留为轻量投影；执行命令的 `intent` 字段与模型路由消费的就是它。

### IntentRequest（带上下文的请求）

解析器与分类器统一接收 `IntentRequest{text, tenantId, sessionId, maxCandidates}`：租户维度决定候选过滤，候选上限控制分类成本。`IntentEngine.resolve(String)` 保留为兼容投影。

### AgentCandidateProvider（候选收窄，唯一真相源）

```java
public interface AgentCandidateProvider {
    List<AgentCandidate> candidates(String tenantId, int limit);
}
```

默认实现 `ConfigCenterAgentCandidateProvider`：候选 = `ConfigCenter` Agent 目录 ∩ 租户允许集合（`TenantAgentAllocationService`），不新增第二份 Agent 注册表。分类卡片由 `AgentCardWriter` 派生（名称 + 首句简介，剥离角色指令前缀、截断 120 字符）；作者可在定义平面用 `AgentSpec.intent.hint` 显式声明卡片，声明时优先于自动派生——**系统提示词全文不进入分类输入**，既省 token 也缩小提示注入面。

### IntentClassifier（分类判定 SPI）

```java
public interface IntentClassifier {
    IntentResolution classify(String normalizedText, Map<String, String> entities, List<AgentCandidate> candidates);
}
```

默认实现 `LocalDecisionIntentClassifier`：经 `ModelResolver` 解析决策模型（`classifier.model-id: decision` → `defaults.decision` → 本机 Ollama 决策模型），`temperature=0` + 有限 token + 超时降级，优先 `responseFormat`（jsonSchema）约束输出；模型不支持时回退普通调用，并按"首个平衡括号 JSON 对象"容错解析。回退只在分类预算内进行——结构化调用已耗尽超时预算时直接放弃，不再二次调用。输出非法、模型不可用、超时一律返回 null（计数 `resolve.llm_fail`），由引擎降级，绝不抛出到入口。

提示词把候选清单与用户输入都作为**数据**并以边界包裹（`<<< ... >>>`），数据内自带的边界标记会被中和，避免提前闭合数据区；分类器只允许输出候选集内的 `agentId` 或 `unknown`。规则（关键词）命中的显式目标同样受候选闭集约束，越界按未命中处理。

### IntentDecider（决策融合）

纯函数融合：模型 `agentId` 不在候选集 → 丢弃（防越权/幻觉）；`confidence < min-confidence` → 回落默认但保留标签；`unknown` → 无匹配；模型失败 → 关键词结果；再失败 → 默认 Agent。

## 配置（`yunxi.intent.*`）

```yaml
yunxi:
  intent:
    enabled: true                # false = 不创建管线（等价旧的默认兜底行为）
    strategy: auto               # keyword | llm | auto（auto 与 llm 同管线）
    default-agent-id: example-assistant
    min-confidence: 0.6
    max-candidates: 8
    max-input-chars: 2000
    classifier:
      impl: local                # local（默认）| jev（可选增强，需 JEV_API_KEY）
      model-id: decision         # 经 ModelResolver 解析 defaults.decision
      timeout-millis: 3000
      max-tokens: 512
      structured-output: true
    rewrite:
      enabled: true
    slots: {}                    # 按名覆盖内置槽位（orderId / dateRange / amount / phone / email）
    rules: {}                    # 标签 → 关键词列表（兜底 + 收窄）
    targets: {}                  # 标签 → 目标 Agent（显式路由；命中零模型调用）
```

- `strategy: keyword` 为整体回滚路径（零模型调用，行为等价关键词路由）。
- 关键词规则支持 `targets` 显式目标：命中即路由到声明 Agent（修正旧实现"命中也回落默认"的缺陷），auto 策略下命中后短路不进模型。
- 日期槽位规范化（上个月 → `yyyy-MM`、近 N 天 → `since:yyyy-MM-dd`）对齐时钟，规则值优先于模型值合并。

## 与请求主链路的关系

入口织面 `IngressGateway` 先做租户准入判定（非 ACTIVE 直接拒绝，不触发模型调用），再在请求未显式指定 `agentId` 时，以带租户/会话的 `IntentRequest` 调用引擎并回填目标 Agent；解析结果（标签 / 置信度 / 来源 / 实体 / 改写文本）以 `yunxi.intent.*` 键写入请求上下文，`AbstractProtocolAdapter` 把标签透传到 `AgentCommand.intent`，上下文随命令进入执行链路（模型路由、观测均可消费）。显式指定 `agentId` 的请求优先级更高，不触发意图解析（零模型调用）。A2A / AG-UI / Chat Completions 协议由 AgentScope-Java 框架 starter 直接服务（Agent 在路径/参数中显式指定），不经意图引擎。

## 可观测

- 指标（`MetricRegistry`）：`yunxi.intent.resolve.llm` / `.keyword` / `.fallback` / `.llm_fail` / `.rejected` / `.low_conf` / `.candidates.empty`，耗时 `yunxi.intent.latency.ms`。
- 审计（`AuditSink`）：每次解析发 `type=intent.resolve` 事件（payload 含 label / agentId / confidence / source / candidateCount）。

## 装配

装配唯一点：`IntentEngineConfiguration`（归一化器、槽位抽取器、候选提供者、本地决策模型分类器、引擎按策略组装）。新增解析器 / 判定器只需在此接线，不改其他模块。

## 演进项（未实现，规划依赖前请确认）

- 指代消解 / 多轮补全：`IntentRequest.sessionId` 已预留，需会话历史读取权。
- JEV SHADOW 灰度对比：当前 `classifier.impl=jev` 直接生效（失败自动回落本地决策模型），尚未提供「影子模式跑 JEV 只对比不生效」的灰度形态。

## 已落地的可选能力

- JEV 云端判定（`classifier.impl=jev`）：复用框架 `agentscope-extensions-jev` 契约（`ChoiceQuestion` 闭集单选 + 概率分布），候选卡片即选项，`unknown` 为闭集兜底项；Key（TYPESAFE_API_KEY / JEV_API_KEY）缺失时启动回落本地决策模型，调用失败同样回落，降级链路不变。
- 分类卡片显式声明：定义平面 `AgentSpec.intent.hint`（一句话职责卡片），声明时优先于从 `identity.description` 自动派生的首句卡片；旧 YAML 不声明 `intent` 段零迁移。

---

**上一页**: [14. 可观测性 →](./14-observability.md)
