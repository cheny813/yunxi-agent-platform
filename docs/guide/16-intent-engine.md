# 16. 意图引擎

本章讲解 yunxi Agent Platform 的意图引擎（Intent Engine）：一条位于对话请求主链路上的前置管道，负责将用户原始消息解析为结构化意图，进而驱动场景识别与 Agent 路由。

意图引擎是**框架层通用能力**，与具体业务解耦。它采用**数据两级模型**：框架 jar（`agent-core`）只内置一份"最小演示集"用于演示与冒烟验证，真正的业务数据由部署方通过配置指向自己的数据文件（yunxi 默认部署的业务数据在 `agent-config/config/intent/`）。任何业务方均可通过配置替换数据，无需改动 Java 代码。

## 为什么需要意图引擎

在接入 Agent 之前，用户消息只是一段文本。若不加以结构化，会出现三类问题：

1. **歧义**：同样的"苹果"，营养场景指水果，选品场景指品牌。
2. **口语不一**："减肥餐"、"减脂食谱"、"低卡餐"指向同一诉求，直接做关键词匹配会漏召回。
3. **路由粗放**：无法精确到"该用哪个 Agent、哪个专家、启哪些工具"。

意图引擎通过四个阶段，把"文本 → 结构化意图 → 路由建议"的整条链路沉淀为框架能力，业务方只需提供数据即可复用。

## 架构总览

```
用户原始消息
    │
    ▼
┌────────────────────────────────────────────────────┐
│            IntentEngine（统一入口）                  │
│                                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────┐ │
│  │  ① NER   │→ │ ② 改写    │→ │ ③ 分类    │→ │ ④映射│ │
│  │ 实体识别  │  │ 术语归一  │  │ 意图匹配  │  │ 路由│ │
│  └──────────┘  └──────────┘  └──────────┘  └─────┘ │
│    RuleBased     Terminology   RuleIntent    Intent │
│    NerStage      Processor     Classifier    Mapping│
│                                                    │
└────────────────────────────────────────────────────┘
    │ 产出 IntentResult
    ▼
场景名（MemoryScene 体系） + 实体注入（K6） + 路由建议
```

### 四阶段职责

| 阶段 | 组件 | 职责 | 数据来源 |
|------|------|------|---------|
| ① NER | `RuleBasedNerStage` | 识别实体（时间/人群/菜名/过敏原等） | `config/intent/ner-dictionaries.yml` |
| ② 改写 | `TerminologyProcessor` | 用户口语 → 规范术语 | `config/intent/terminology.yml` |
| ③ 分类 | `RuleIntentClassifier` | 命中意图节点 + 计算场景名 | `config/intent/intent-tree.yml` + 内置三级链 |
| ④ 映射 | `IntentMappingTable` | 意图 → Agent/专家/工具组 | `config/intent/intent-mapping.yml` |

四个阶段均为**可插拔**实现，当前为规则通道（M1）。后续版本可替换为模型通道，见[扩展新阶段](#扩展新阶段)。

## 核心概念

### IntentResult（分析结果）

`analyze()` 永不返回 null，产出 `IntentResult` record：

| 字段 | 说明 |
|------|------|
| `originalQuery` | 原始用户消息 |
| `rewrittenQuery` | 改写后消息（改写关闭时等于原始） |
| `entities` | NER 实体列表（可空，永不为 null） |
| `intent` | 命中的意图（未命中为 `Intent.unknown()`） |
| `routeHint` | 路由建议（Agent / 专家 / 工具组 / 技能） |
| `sceneName` | 三级链场景名（兼容 MemoryScene 体系） |
| `timings` | 各阶段耗时 |
| `degraded` | 任一阶段降级为 `true` |

> **关键语义**：`sceneName` 恒等于三级检测链输出（自定义场景 → 概念域 → 内置关键词 → `GENERAL`），与意图是否命中无关。它服务于 `MemoryScene` 记忆保留策略，禁止用意图 label 覆盖。

### Intent（意图）

意图节点由 `intent-tree.yml` 定义，含 `id`、`label`、命中规则（`allKeywords` / `anyKeywords` / `entities`）、可选的 `sceneName` 场景关联。未命中任何节点时返回 `Intent.unknown()`。

### RouteHint（路由建议）

`routeHint` 携带四个可选分量：`agent`（主 Agent）、`experts`（子专家）、`toolGroups`（工具分组）、`skills`（技能分组）。请求方（如 `ChatAppService`）据此决定如何路由。

## 框架通用性设计

这是意图引擎作为**框架能力**而非业务特化的关键所在，遵循三条原则：

### 原则一：业务数据与框架代码分离（两级数据模型）

所有业务规则都沉淀在 YAML 数据文件中，**Java 代码不含任何业务词条**（唯一的例外是 `RuleIntentClassifier` 中的个人记忆场景兜底关键词，属框架级 `PERSONAL_ASSISTANT` 场景，非业务特化）。数据采用两级模型：

| 层级 | 位置 | 定位 |
|------|------|------|
| 框架最小演示集 | `agent-core/src/main/resources/intent/*.yml` | 每个文件 2-4 条中性示例，演示引擎能力、支撑开箱即用冒烟验证；**框架发布后不应被修改** |
| 部署业务数据 | `agent-config/src/main/resources/config/intent/*.yml` | yunxi 默认部署的完整业务词典（校园餐/居民营养），随 `agent-app` 打包；业务方按需替换 |

两级数据通过 `yunxi.intent.*` 配置项切换：`agent-config` 的 `intent.yml` 显式指向 `config/intent/*.yml`（部署数据）；若某配置项缺失，框架回落到 `agent-core` 内置演示集（兜底）。

| 文件 | 内容 | 前缀配置项 |
|------|------|-----------|
| `ner-dictionaries.yml` | NER 实体词典 | `yunxi.intent.ner-dictionary` |
| `terminology.yml` | 术语归一表 | `yunxi.intent.terminology-table` |
| `intent-tree.yml` | 意图树 | `yunxi.intent.intent-tree` |
| `intent-mapping.yml` | 路由映射表 | `yunxi.intent.mapping-table` |

### 原则二：配置驱动，开箱可替换

每个数据文件都可通过 `yunxi.intent.*` 配置项指向自己的资源，支持 `classpath:` / `file:` / `url:` 三种前缀：`classpath:config/intent/x.yml`（打包资源）、`file:/data/yunxi/intent/x.yml`（部署目录外部化）、`url:https://...`（配置中心）。替换业务数据**不需要修改任何 Java 代码、不需要重新编译**，重启即生效。

### 原则三：降级安全（fail-safe）

任何内部异常都不会向外抛出。某阶段失败时，产出阶段性结果并置 `degraded=true`；当总开关关闭或全部降级时，退化为"仅场景模式"，行为与旧 `SceneDetectionService` 严格等价，保证对话主链路不被意图引擎阻塞。

## 配置指南

配置前缀为 `yunxi.intent`（业务层惯例，对齐 `yunxi.muse.*`），位于 `agent-config/src/main/resources/config/intent.yml`：

```yaml
yunxi:
  intent:
    enabled: true                 # 总开关（false = 仅场景模式，等价旧 SceneDetectionService）
    ner-dictionary: classpath:config/intent/ner-dictionaries.yml   # NER 词典（部署业务数据）
    rewrite-enabled: true         # 改写阶段是否启用
    rewrite-processors: [terminology]             # 改写处理器名列表（按序执行）
    terminology-table: classpath:config/intent/terminology.yml     # 术语表
    intent-tree: classpath:config/intent/intent-tree.yml           # 意图树
    mapping-table: classpath:config/intent/intent-mapping.yml      # 路由映射表
```

各配置项说明（默认值 = 框架最小演示集路径；`agent-config` 显式覆盖为部署业务数据；**fat jar 部署下建议统一使用 `classpath:` 前缀**，裸路径在嵌套 jar 场景可能解析失败）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `yunxi.intent.enabled` | `true` | 总开关；`false` 时仅返回场景名，跳过 NER/改写/分类/映射 |
| `yunxi.intent.ner-dictionary` | `classpath:intent/ner-dictionaries.yml` | NER 词典文件；支持 `classpath:` / `file:` / `url:` 前缀 |
| `yunxi.intent.rewrite-enabled` | `true` | 改写阶段开关 |
| `yunxi.intent.rewrite-processors` | `[terminology]` | 改写处理器链；不存在名字 warn 跳过 |
| `yunxi.intent.terminology-table` | `classpath:intent/terminology.yml` | 术语归一表；支持 `classpath:` / `file:` / `url:` 前缀 |
| `yunxi.intent.intent-tree` | `classpath:intent/intent-tree.yml` | 意图树；支持 `classpath:` / `file:` / `url:` 前缀 |
| `yunxi.intent.mapping-table` | `classpath:intent/intent-mapping.yml` | 路由映射表；支持 `classpath:` / `file:` / `url:` 前缀 |

## 业务定制指南

以下场景说明如何在不改代码的前提下定制意图引擎。**覆盖边界**：框架发布后不得修改 `agent-core` 内置演示集，业务定制一律通过 `agent-config` 的 `config/intent/*.yml`（或外部文件）进行。

### 场景一：新增一个业务实体类型

在 `agent-config/src/main/resources/config/intent/ner-dictionaries.yml` 中注册类型并追加词条：

```yaml
entity-types:
  BRAND: { priority: 100 }       # 新实体类型：品牌

dictionaries:
  BRAND: [三元, 蒙牛, 伊利]      # 词条
```

### 场景二：新增一个业务意图

在 `config/intent/intent-tree.yml` 中追加节点（`id` 全局唯一）：

```yaml
intents:
  - id: product.recommend
    label: 商品推荐
    match:
      anyKeywords: [推荐, 买什么, 怎么选]
      entities: [BRAND]
```

### 场景三：把意图路由到业务 Agent

在 `config/intent/intent-mapping.yml` 中追加映射（`intent` 与意图树 `id` 对齐）：

```yaml
intent-mappings:
  - intent: product.recommend
    agent: product-assistant
    experts: [选品专家]
    tool-groups: [product]
    skills: [recommendation]
```

### 场景四：整表替换业务数据（外部化部署）

若业务与校园餐/居民营养差异较大，直接覆盖 4 个配置项，指向自己的数据文件：

```yaml
yunxi:
  intent:
    ner-dictionary:      file:/data/yunxi/intent/ner-dictionaries.yml
    terminology-table:   file:/data/yunxi/intent/terminology.yml
    intent-tree:         file:/data/yunxi/intent/intent-tree.yml
    mapping-table:       file:/data/yunxi/intent/intent-mapping.yml
```

数据文件完全脱离 jar，便于部署目录/配置中心统一管理；或直接编辑 `agent-config` 的 `config/intent/*.yml`（随包部署）。两种方式都不需要修改 Java 代码。

## 扩展新阶段

`IntentEngine` 的四个阶段均为接口/抽象，可替换为自定义实现：

| 扩展点 | 接口/基类 | 说明 |
|--------|----------|------|
| NER | `NerStage` | 实现 `extract(query, entityTypes)` 返回实体列表 |
| 改写 | `RewriteProcessor` | 实现 `rewrite(query)` 返回改写后文本；通过 `rewrite-processors` 按名装配 |
| 分类 | `IntentClassifier` | 实现 `classify` 返回意图 + 场景名 |
| 映射 | `IntentMappingTable` | 实现 `route(intent)` 返回 `RouteHint` |

> 例如，若要引入模型通道，可实现一个基于 LLM 的 `IntentClassifier`，替换默认的 `RuleIntentClassifier`，其余阶段保持不变。

## 与旧场景检测的关系

| 维度 | 旧 `SceneDetectionService` | 意图引擎 |
|------|---------------------------|---------|
| 定位 | 仅场景检测 | 场景 + 实体 + 改写 + 意图 + 路由 |
| 场景三级链 | 自定义 → 概念 → 内置关键词 | 等价迁移至 `RuleIntentClassifier.detectSceneName` |
| 状态 | `@Deprecated`，Bean 保留 | 当前主链路 |
| 启动依赖 | `milvus.enabled=true` 才装配 | 无外部依赖，天然可用 |

> 迁移说明：`SceneDetectionService` 已标记 `@Deprecated`，调用方已迁移至 `ChatAppService` 的意图引擎；禁止新代码注入旧服务（规避 `milvus.enabled` 条件 Bean 启动依赖），后续版本将整体移除。

## 设计文档

- [意图引擎设计](../../docs/intent-engine-design.md)：整体架构、降级原则、扩展路线。
- [意图引擎 M1 实施](../../docs/intent-engine-m1-implementation.md)：四阶段落地明细与验证清单。

---

**上一页**: [15. 可观测性 →](./15-observability.md)
