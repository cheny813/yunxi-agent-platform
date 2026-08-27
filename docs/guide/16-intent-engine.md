# 16. 意图引擎

本章讲解 yunxi Agent Platform 的意图引擎（Intent Engine）：一条位于对话请求主链路上的前置管道，负责将用户原始消息解析为结构化意图，进而驱动场景识别与 Agent 路由。

意图引擎是**框架层通用能力**，与具体业务解耦。它采用**数据两级模型**：框架 jar（`agent-core`）只内置一份"最小演示集"用于演示与冒烟验证，真正的业务数据由部署方通过配置指向自己的数据文件（yunxi 默认部署的业务数据在 `agent-config/config/intent/`）。任何业务方均可通过配置替换数据，无需改动 Java 代码。

经过 M2 系列迭代，引擎已从 M1 的单域规则通道演进为**多域（M2.2）+ 分类通道（M2.3）+ 热更新（M2.4）+ 意图路由（M2.1）**的完整形态：

| 里程碑 | 能力 | 状态 |
|--------|------|------|
| M1 | 单域四阶段规则管道（NER → 改写 → 分类 → 映射） | 已上线（冻结基线） |
| M2.1 | 意图路由（routeHint 参与会话入口路由决策，默认关闭渐进式上线） | 已上线 |
| M2.2 | 多域数据模型（`domains` + `resolver` 领域解析） | 已上线 |
| M2.3 | 分类通道（`rule` / `llm` / `hybrid` 三模式 + LLM 结果缓存） | 已上线 |
| M2.4 | 热更新（actuator 端点 + 文件轮询 + 监听器 + 审计） | 已上线 |

## 为什么需要意图引擎

在接入 Agent 之前，用户消息只是一段文本。若不加以结构化，会出现三类问题：

1. **歧义**：同样的"苹果"，营养场景指水果，选品场景指品牌。
2. **口语不一**："减肥餐"、"减脂食谱"、"低卡餐"指向同一诉求，直接做关键词匹配会漏召回。
3. **路由粗放**：无法精确到"该用哪个 Agent、哪个专家、启哪些工具"。

意图引擎通过四个阶段，把"文本 → 结构化意图 → 路由建议"的整条链路沉淀为框架能力，业务方只需提供数据即可复用。

## 架构总览

```
用户原始消息（agentName / profile / 显式 domain 上下文）
    │
    ▼
┌────────────────────────────────────────────────────────────┐
│  DomainResolver（M2.2 领域解析）                              │
│  显式 domain → 规则匹配（agent 前缀 / profile）→ default → base │
└───────────────────────────┬────────────────────────────────┘
                            ▼
┌────────────────────────────────────────────────────────────┐
│            IntentEngine（统一入口）                           │
│                                                            │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  ┌─────────┐ │
│  │  ① NER   │→ │ ② 改写    │→ │ ③ 分类        │→ │ ④ 映射   │ │
│  │ 实体识别  │  │ 术语归一  │  │ rule/llm/hybrid│ │ 路由     │ │
│  └──────────┘  └──────────┘  └──────────────┘  └─────────┘ │
│    RuleBased     Terminology   HybridIntent     Intent     │
│    NerStage      Processor     Classifier       Mapping    │
│                                (M2.3 分类通道)              │
└────────────────────────────────────────────────────────────┘
    │ 产出 IntentResult（含 domain）
    ▼
场景名（MemoryScene 体系） + 实体注入（K6） + 路由建议（M2.1）
    │
    ▼
┌────────────────────────────────────────────────────────────┐
│  M2.4 热更新（reload 能力）                                  │
│  actuator 端点 /actuator/intent/* │ IntentFilePoller 轮询    │
│  IntentReloader（门面+审计） → IntentReloadListener 广播      │
└────────────────────────────────────────────────────────────┘
```

### 四阶段职责

| 阶段 | 组件 | 职责 | 数据来源 |
|------|------|------|---------|
| ① NER | `RuleBasedNerStage` | 识别实体（时间/人群/菜名/过敏原等） | `config/intent/ner-dictionaries.yml` |
| ② 改写 | `TerminologyProcessor` | 用户口语 → 规范术语 | `config/intent/terminology.yml` |
| ③ 分类 | `RuleIntentClassifier` / `HybridIntentClassifier` | 命中意图节点 + 计算场景名；`hybrid`/`llm` 模式叠加 LLM 通道 | `config/intent/intent-tree.yml` + 内置三级链 |
| ④ 映射 | `IntentMappingTable` | 意图 → Agent/专家/工具组 | `config/intent/intent-mapping.yml` |

四个阶段均为**可插拔**实现。M1 规则通道为冻结基线（`classification.mode=rule` 时行为不变）；M2.3 起可按需切换 `llm` / `hybrid` 分类通道，见[分类通道](#分类通道rule--llm--hybrid)。

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
| `domain` | 解析出的领域名（M2.2；仅场景模式为 null） |
| `timings` | 各阶段耗时 |
| `degraded` | 任一阶段降级为 `true` |

> **关键语义**：`sceneName` 恒等于三级检测链输出（自定义场景 → 概念域 → 内置关键词 → `GENERAL`），与意图是否命中无关。它服务于 `MemoryScene` 记忆保留策略，禁止用意图 label 覆盖。

### Intent（意图）

意图节点由 `intent-tree.yml` 定义，含 `id`、`label`、命中规则（`allKeywords` / `anyKeywords` / `entities`）、可选的 `sceneName` 场景关联。未命中任何节点时返回 `Intent.unknown()`。`Intent` 携带 `confidence` 置信度（规则通道按命中强度计算，LLM 通道为模型输出置信度），供混合模式与路由决策使用。

### RouteHint（路由建议）

`routeHint` 携带四个可选分量：`agent`（主 Agent）、`experts`（子专家）、`toolGroups`（工具分组）、`skills`（技能分组），以及 `score`（建议分）。请求方（如 `ChatAppService`）据此决定如何路由；开启 M2.1 意图路由（`yunxi.intent.routing.enabled=true`）后，会话入口消费 `routeHint` 参与路由决策——**advisory 语义**：目标 agent 不存在或 `score < min-route-score` 时保持原路由，不改道。

### 多域模型（M2.2）

业务数据从"单套全局"升级为"按领域隔离"：

- **域**：每个域是一套独立的 NER 词典 / 术语表 / 意图树 / 映射表。`base` 域恒存在（由 M1 顶层字段 `ner-dictionary` 等快捷构造，零迁移）；其他业务域只列差异文件，缺省项继承 `base`。
- **领域解析**（`DomainResolver`）：四层判定链——显式 `IntentContext.domain` → 规则匹配（`resolver.rules`，按 agent 名前缀 / profile 归属）→ `resolver.default` → `base` 兜底。规则命中但域未注册时 warn 并继续下一层，防误配。
- **运行时隔离**：每域一个 `DomainRuntime`（含版本号、加载时间、不可变 `TreeSnapshot` 三索引、NER/术语/映射视图）。`analyze()` 只访问当前解析域的运行时，跨域互不干扰。
- **配置切换**：`domains` 是一个 `Map<域名, 数据源>`；`base` 缺省由顶层字段构造，`resolver` 负责把请求路由到正确域。

### 分类通道（rule / llm / hybrid）

`yunxi.intent.classification.mode` 三值控制分类器装配：

| mode | 行为 | 装配 |
|------|------|------|
| `rule`（默认） | M1 冻结规则通道，不调 LLM、不读缓存 | 直连 `RuleIntentClassifier`，`HybridIntentClassifier` 不装配 |
| `llm` | 跳过规则，白名单内直连 LLM 分类 | `HybridIntentClassifier`（`@Primary`） |
| `hybrid` | 规则优先：规则命中且 `confidence ≥ rule-confidence-threshold` 直出；否则 LLM 兜底 | `HybridIntentClassifier`（`@Primary`） |

关键语义：

- `classification.llm.enabled=false` 时，即使 mode 为 `llm` / `hybrid` 也**退化为纯规则通道**（不调 LLM、不读缓存），实现"规则兜底永不失效"。
- LLM 通道（`LlmIntentClassifier`）使用低成本快模型（默认 `qwen-turbo`），经 resilience4j TimeLimiter 限时（默认 2s），输出低于 `min-confidence`（默认 0.5）不采纳；白名单超过 `max-intents`（默认 30）时按重写后 query 关键词规则粗筛留 Top-N。
- **LLM 结果缓存**（`LlmResultCache`）：key = `domain|normalizedQuery|whitelistVersion`——查询改写、领域规则版本任一变化即失效；TTL 默认 7 天、LRU 上限 10000；`hotEntries(domain, topN)` 按域过滤热度建议词，供运营侧观察高频未命中样本。

## 框架通用性设计

这是意图引擎作为**框架能力**而非业务特化的关键所在，遵循三条原则：

### 原则一：业务数据与框架代码分离（两级数据模型）

所有业务规则都沉淀在 YAML 数据文件中，**Java 代码不含任何业务词条**。数据采用两级模型：

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

M2.2 多域下，以上四项成为 `base` 域的快捷方式，等价 `domains.base.*`；二者同时配置时以 `domains.base.*` 为准。

### 原则二：配置驱动，开箱可替换

每个数据文件都可通过 `yunxi.intent.*` 配置项指向自己的资源，支持 `classpath:` / `file:` / `url:` 三种前缀：`classpath:config/intent/x.yml`（打包资源）、`file:/data/yunxi/intent/x.yml`（部署目录外部化）、`url:https://...`（配置中心）。替换业务数据**不需要修改任何 Java 代码、不需要重新编译**，重启即生效；M2.4 起支持运行期热更新，见[热更新与运维](#热更新与运维)。

### 原则三：降级安全（fail-safe）

任何内部异常都不会向外抛出。某阶段失败时，产出阶段性结果并置 `degraded=true`；当总开关关闭或全部降级时，退化为"仅场景模式"，行为与旧 `SceneDetectionService` 严格等价，保证对话主链路不被意图引擎阻塞。LLM 通道的超时、低置信度、缓存缺失均为"不采纳"而非"抛错"，规则通道始终是兜底。

## 配置指南

配置前缀为 `yunxi.intent`（业务层惯例，对齐 `yunxi.muse.*`），位于 `agent-config/src/main/resources/config/intent.yml`。完整示例：

```yaml
yunxi:
  intent:
    enabled: true                 # 总开关（false = 仅场景模式，等价旧 SceneDetectionService）
    # ── M1 四阶段数据源（base 域快捷方式；domains.base.* 优先级更高）──
    ner-dictionary: classpath:config/intent/ner-dictionaries.yml
    rewrite-enabled: true
    rewrite-processors: [terminology]            # 改写处理器名列表（按序执行）
    terminology-table: classpath:config/intent/terminology.yml
    intent-tree: classpath:config/intent/intent-tree.yml
    mapping-table: classpath:config/intent/intent-mapping.yml
    # ── M2.1 意图路由（默认关闭，渐进式上线）──
    routing:
      enabled: false               # 开启后 routeHint 参与会话入口路由决策（advisory）
      min-route-score: 0.5         # 最低采纳分数（低于此值不改道）
    # ── M2.3 分类通道 ──
    classification:
      mode: rule                   # rule | llm | hybrid
      rule-confidence-threshold: 0.6    # hybrid 模式规则高分直出阈值
      llm:
        enabled: false             # LLM 通道总开关（true 才发模型调用）
        model: qwen-turbo          # 低成本快模型
        timeout-ms: 2000           # 超时（resilience4j TimeLimiter）
        min-confidence: 0.5        # LLM 输出最低采纳置信度
        max-intents: 30            # 白名单上限，超限按查询关键词粗筛 Top-N
        cache:
          ttl-days: 7              # 缓存 TTL（惰性失效，reload 后按版本跳过）
          max-size: 10000          # LRU 上限
    # ── M2.2 多域（单域部署保持默认即可）──
    resolver:
      default: base                # 未命中规则的默认域
      rules: []                    # 多域路由规则（agent 前缀 / profile 归属）
      domains: {}                  # 业务域数据源（见下"多域配置"示例）
    # ── M2.4 热更新 ──
    reload:
      enabled: true                # reload 开关（actuator 端点受控）
      poll-seconds: -1             # >0 时轮询 file: 前缀资源 mtime（默认关闭）
```

### 基础配置项（M1，base 域）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `yunxi.intent.enabled` | `true` | 总开关；`false` 时仅返回场景名，跳过 NER/改写/分类/映射 |
| `yunxi.intent.ner-dictionary` | `classpath:intent/ner-dictionaries.yml` | NER 词典文件；支持 `classpath:` / `file:` / `url:` 前缀 |
| `yunxi.intent.rewrite-enabled` | `true` | 改写阶段开关 |
| `yunxi.intent.rewrite-processors` | `[terminology]` | 改写处理器链；不存在名字 warn 跳过 |
| `yunxi.intent.terminology-table` | `classpath:intent/terminology.yml` | 术语归一表；支持 `classpath:` / `file:` / `url:` 前缀 |
| `yunxi.intent.intent-tree` | `classpath:intent/intent-tree.yml` | 意图树；支持 `classpath:` / `file:` / `url:` 前缀 |
| `yunxi.intent.mapping-table` | `classpath:intent/intent-mapping.yml` | 路由映射表；支持 `classpath:` / `file:` / `url:` 前缀 |

> **fat jar 部署提示**：建议统一使用 `classpath:` 前缀，裸路径在嵌套 jar 场景可能解析失败。

### 多域配置

业务域只列差异文件，缺省项继承 `base`：

```yaml
yunxi:
  intent:
    resolver:
      default: base
      rules:
        - name: nutrition-by-agent
          agent-prefixes: [nutrition, resident-nutrition]   # agent 名前缀命中 → 营养域
          domain: nutrition
        - name: food-by-profile
          profile-in: [校园餐]                                # 画像归属命中 → 校园餐域
          domain: campus
      domains:
        nutrition:
          intent-tree: classpath:config/intent/nutrition/intent-tree.yml
          mapping-table: classpath:config/intent/nutrition/intent-mapping.yml
          # ner-dictionary / terminology-table 缺省 → 继承 base
        campus:
          ner-dictionary: classpath:config/intent/campus/ner-dictionaries.yml
          terminology-table: classpath:config/intent/campus/terminology.yml
```

判定链（`DomainResolver.resolve`）：显式 `IntentContext.domain`（信任调用方，未注册则 warn 回落）→ `resolver.rules` 按序匹配（`agent-prefixes` 前缀 / `profile-in` 归属，命中但域未注册则 warn 继续）→ `resolver.default`（缺省 `base`）→ `base` 兜底。域解析永不返回 null。

### 分类通道配置（M2.3）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `yunxi.intent.classification.mode` | `rule` | `rule` / `llm` / `hybrid` |
| `yunxi.intent.classification.rule-confidence-threshold` | `0.6` | hybrid 模式规则高分直出阈值 |
| `yunxi.intent.classification.llm.enabled` | `false`（部署默认；代码字段默认 `true`，但 `mode=rule` 时不装配） | LLM 通道总开关（false 时 llm/hybrid 退化为纯规则） |
| `yunxi.intent.classification.llm.model` | `qwen-turbo` | 低成本快模型 |
| `yunxi.intent.classification.llm.timeout-ms` | `2000` | 模型调用超时（resilience4j TimeLimiter） |
| `yunxi.intent.classification.llm.min-confidence` | `0.5` | LLM 输出最低采纳置信度 |
| `yunxi.intent.classification.llm.max-intents` | `30` | 白名单上限，超限按查询关键词粗筛 Top-N |
| `yunxi.intent.classification.llm.cache.ttl-days` | `7` | LLM 结果缓存 TTL |
| `yunxi.intent.classification.llm.cache.max-size` | `10000` | 缓存 LRU 上限 |

### 热更新配置（M2.4）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `yunxi.intent.reload.enabled` | `true` | reload 能力总开关（端点/轮询生效前置条件） |
| `yunxi.intent.reload.poll-seconds` | `-1` | 文件轮询间隔秒；`>0` 时对 `file:` 前缀资源按 mtime 自动 reload，默认关闭（仅端点触发） |

## 热更新与运维

M2.4 提供运行期热更新能力，业务数据文件变更后**无需重启应用**：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/actuator/intent/status` | GET | 各域快照信息：版本、加载时间、意图节点数、NER 词条数、术语数、映射数 |
| `/actuator/intent/reload?domain=xx&mode=force` | POST | 热更新；无 `domain` 为全量；`mode=force` 强制重建（缺省复用 registry 缓存语义） |
| `/actuator/intent/suggest-words?domain=xx&topN=10` | GET | LLM 热度建议词（按域过滤）；mode=rule 未装配 LLM 通道时优雅降级返回 `enabled=false` |

示例：

```bash
# 查看各域快照
curl http://localhost:40001/actuator/intent/status

# 全量热更新
curl -X POST "http://localhost:40001/actuator/intent/reload"

# 单域强制重建
curl -X POST "http://localhost:40001/actuator/intent/reload?domain=nutrition&mode=force"
```

机制要点：

- **门面**（`IntentReloader`）：统一封装审计（记录触发时间/方式/域/结果）与广播，`reloadAll` 逐域返回 `List<ReloadResult>`——单域失败不阻断其他域。
- **监听器**（`IntentReloadListener`）：预留 SPI 接口，业务方可注册监听器接收每次 reload 结果（对接 Nacos / Spring Cloud Config 刷新事件等）；默认无实现，空集合安全。
- **文件轮询**（`IntentFilePoller`）：`poll-seconds > 0` 时轮询 `file:` 前缀资源 mtime 自动触发 reload；`classpath:` / `url:` 资源不参与轮询。
- **版本语义**：每次 reload 递增域版本号；LLM 结果缓存的 key 含版本，reload 后旧缓存按版本跳过，避免"热更新不生效"。
- **审计日志**：reload 全程输出 `[INTENT]` 前缀日志（触发源、域、耗时、结果），便于定位"谁在何时改了什么"。

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

开启 M2.1 意图路由后，该映射会自动参与会话入口路由；未开启时 `routeHint` 仅作为分析结果旁路输出，不改变既有路由。

### 场景四：整表替换业务数据（外部化部署）

若业务与校园餐/居民营养差异较大，直接覆盖 4 个配置项，指向自己的数据文件：

```yaml
yunxi:
  intent:
    ner-dictionary:      file:/data/yunxi/intent/ner-dictionaries.yml
    terminology-table:   file:/data/yunxi/intent/terminology.yml
    intent-tree:         file:/data/yunxi/intent/intent-tree.yml
    mapping-table:       file:/data/yunxi/intent/intent-mapping.yml
    reload:
      poll-seconds: 30   # 外部化文件 + 文件轮询 = 免重启热更新
```

数据文件完全脱离 jar，便于部署目录/配置中心统一管理；配合 `file:` 前缀与 `reload.poll-seconds` 可实现免重启热更新。两种方式都不需要修改 Java 代码。

### 场景五：接入 LLM 分类通道

将 `classification.mode` 切到 `hybrid` 并开启 `llm.enabled`：

```yaml
yunxi:
  intent:
    classification:
      mode: hybrid            # 规则优先、LLM 兜底
      llm:
        enabled: true
        model: qwen-turbo
```

规则通道仍是兜底：LLM 超时 / 低置信度 / 缓存未命中均回退规则结果，不会把错误分类直接放出。上线后可通过 `/actuator/intent/suggest-words` 观察高频未命中样本，反哺意图树。

### 场景六：多域隔离

见上文[多域配置](#多域配置)示例。典型用途：不同人群口径（校园餐 vs 居民营养）的意图树、词典完全隔离，`resolver` 按 agent 前缀或画像自动路由，一套引擎服务多个业务域。

## 扩展新阶段

`IntentEngine` 的四个阶段均为接口/抽象，可替换为自定义实现：

| 扩展点 | 接口/基类 | 说明 |
|--------|----------|------|
| NER | `NerStage` | 实现 `extract(query, entityTypes)` 返回实体列表 |
| 改写 | `RewriteProcessor` | 实现 `rewrite(query)` 返回改写后文本；通过 `rewrite-processors` 按名装配 |
| 分类 | `IntentClassifier` | 实现 `classify` 返回意图 + 场景名；M2.3 内置 `rule`/`llm`/`hybrid` 三种实现 |
| 映射 | `IntentMappingTable` | 实现 `route(intent)` 返回 `RouteHint` |

> M2.3 已内置 LLM 分类通道（`LlmIntentClassifier` + `HybridIntentClassifier`），无需自行实现即可获得"规则优先、LLM 兜底"的混合分类。若业务需要自定义分类模型，可实现 `IntentClassifier` 并注册为 `@Primary` 覆盖默认装配（注意 `HybridIntentClassifier` 在 `mode≠rule` 时以 `@Primary` 注册，二者需择一）。

## 与旧场景检测的关系

| 维度 | 旧 `SceneDetectionService` | 意图引擎 |
|------|---------------------------|---------|
| 定位 | 仅场景检测 | 场景 + 实体 + 改写 + 意图 + 路由 + 多域 + 热更新 |
| 场景三级链 | 自定义 → 概念 → 内置关键词 | 等价迁移至 `RuleIntentClassifier.detectSceneName` |
| 状态 | `@Deprecated`，Bean 保留 | 当前主链路 |
| 启动依赖 | `milvus.enabled=true` 才装配 | 无外部依赖，天然可用 |

> 迁移说明：`SceneDetectionService` 已标记 `@Deprecated`，调用方已迁移至 `ChatAppService` 的意图引擎；禁止新代码注入旧服务（规避 `milvus.enabled` 条件 Bean 启动依赖），后续版本将整体移除。

## 设计文档

- [意图引擎设计](../../docs/intent-engine-design.md)：整体架构、降级原则、扩展路线。
- [意图引擎 M1 实施](../../docs/intent-engine-m1-implementation.md)：四阶段落地明细与验证清单。
- [意图引擎 M2.2-M2.4 设计](../../docs/intent-engine-m2-m234-design.md)：多域模型、分类通道、热更新、意图路由的设计与验证清单。

---

**上一页**: [15. 可观测性 →](./15-observability.md)
