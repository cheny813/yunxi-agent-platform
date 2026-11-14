# 10. 技能系统

> **V2.0 技能系统**：本平台技能在运行时装载于 `.agentscope/workspace/skills/` 目录（内置技能源码随 jar 分发、启动落地）；Agent 可通过 `skill_manage` 和 `skill_propose` 工具管理技能。MUSE 自进化引擎在此基础上提供技能的自动评估、修补与剪枝能力。

---

## 概述

### 技能目录：两类来源，各自单一真相源

技能并非只有「一个目录」，而是分两类，各有真相源：

| 类别 | 真相源位置 | 进版本库？ | 框架加载 |
|------|-----------|-----------|----------|
| **内置技能**（平台随包分发，19 个通用技能） | jar 内 `builtin-skills/`（源码：`yunxi-intelligence/src/main/resources/builtin-skills/`） | ✅ 进平台仓库（git） | `ClasspathSkillRepository` 从 jar 零复制加载（Layer2），无需落地 |
| **运行时技能**（团队/用户创建、MUSE 生成/修改） | `.agentscope/workspace/skills/`（运行时目录，`.gitignore` 已排除） | ❌ 不进版本库（当前） | ✅ Agent 可访问 |

> **关键区分（易被误解）**：`.agentscope/workspace/skills/` 是**框架运行期装载技能的目录**，属运行时数据、**不进版本库**——它不是「放技能源码的地方」。内置技能的源码真相在平台仓库的 `builtin-skills/`；团队/用户/Agent 创建的技能当前也写进这个运行时目录。**请勿把该目录纳入 git（会重复内置技能、并混淆平台制品与客户制品）**；企业级「团队/MUSE 技能需版本化」的诉求由平台规划的「多仓库分层」能力解决，见下方「创建技能 · 版本管理」。

### 内置工具

- **skill_manage** — 列出、启用、禁用技能
- **skill_propose** — 提案新技能（写入 `workspace/skills/`）

---

## 技能目录结构

运行时技能（MUSE/用户创建）存放在 `.agentscope/workspace/skills/` 全局目录中，与 `agents/` 目录并列；内置技能的 `SKILL.md` 不在该目录（由 jar 零复制加载），仅其 `tests/` 物化于此。完整的 workspace 目录布局如下：

```
.agentscope/workspace/
├── skills/                    # 全局共享技能目录（Layer3：MUSE/用户技能；内置技能的 tests/ 也物化于此）
│   ├── skill-vetter/          # 技能安全审查
│   │   └── SKILL.md
│   ├── java-developer/        # Java 开发
│   ├── git-operator/          # Git 操作
│   ├── code-reviewer/         # 代码审查
│   ├── api-developer/         # API 开发
│   ├── test-generator/        # 单元测试生成
│   ├── pdf-extractor/         # PDF 内容抽取
│   ├── data_stats/            # 数据统计
│   ├── text_clean/            # 文本清洗
│   └── ...（其余技能见下方「完整技能列表」）
└── agents/                    # Agent 工作空间统一目录
    ├── food-chat/             # 饮食问答助手
    │   ├── agents/            # 子智能体定义
    │   ├── AGENTS.md          # Agent 身份定义
    │   └── user-001/          # 用户运行时数据
    ├── nutrition-assistant/   # 校园餐营养助手
    ├── resident-nutrition-assistant/  # 居民营养配餐助手
    └── ...（共 9 个 Agent）
```

> **设计原则**：`skills/` 目录与 `agents/` 目录同级，所有 Agent 工作空间统一放在 `agents/` 子目录下（遵循底层 agentscope-java 框架约定）。全局技能对所有 Agent 可见。

---

## 创建技能

### 方式一：开发者直接创建（运行时目录）

在运行时目录 `.agentscope/workspace/skills/` 下创建技能目录（框架会自动装载）：

```bash
.agentscope/workspace/skills/
├── new-skill/
│   └── SKILL.md
```

> **注意**：此方式把技能放进**运行时目录**（gitignored，不进版本库）。适合本地试用 / 单机快速起步。若需要团队共享、版本回溯、review，当前需自行用外部手段（备份/导出）管理；平台正规划将这类技能改挂**可写版本化仓库**（见下方「版本管理」）。
>
> **预置技能不要在这里手工写**：平台随包分发的 19 个内置技能源码在 `builtin-skills/`（进 jar、进平台仓库），由 `ClasspathSkillRepository` 零复制加载；往运行时目录手写同名技能会以 Layer3 覆盖内置，来源失真且不属于版本管理。

### 方式二：Agent 提案（运行时目录）

Agent 调用 `skill_propose` 提案新技能，写入同一运行时目录：

```
skill_propose(name="新技能名", description="技能描述", triggers=["触发词"])
```

### 两种方式对比

| 创建方式 | 谁创建 | 落点 | 场景 |
|----------|--------|------|------|
| **开发者直接创建** | 人 | 运行时目录（当前） | 本地试用、单机起步；预置技能走 `builtin-skills/`（平台仓库） |
| **Agent 提案** | Agent | 运行时目录（当前） | 运行时发现的工作模式，自动生成 |

### 版本管理与多仓库分层（已接线，默认关闭）

两类「创建方式」默认落在 gitignored 的运行时目录；需要版本化时，平台已按框架原生「多仓库分层」接线，按技能归属分流到各自真相源（均为开关控制、默认关闭，零回归）：

- **内置技能（L2）**：源码在平台仓库 `builtin-skills/`（进 jar），`AgentConfigurer` 经 `ClasspathSkillRepository("builtin-skills")` 从 jar 零复制加载；`BuiltinSkillLoader` 仅把 `builtin-skills/{name}/tests/` 物化到 `.agentscope/workspace/skills` 供 MUSE 评估器执行。框架同名校验「L4 用户 > L3 workspace/MUSE > L2 内置」，内置作底座，MUSE 演进天然覆盖内置。
- **团队/策展技能（L2，只读）**：`yunxi.skill.git.enabled=true` 后由 `SkillRepositoryConfiguration` 装配框架原生 `GitSkillRepository`（`yunxi.skill.git.url` / `branch` / `localPath` / `autoSync`，读时按需 clone/pull）。**注意它是只读分发，不是可写后端**——写回会静默失败，切勿当可写仓库用。
- **用户/MUSE 技能（L2 末位，可写 + 版本化）**：`yunxi.skill.jdbc.enabled=true` 后装配框架原生 `JdbcAgentSkillRepository`（复用 MySQL，按 scope/namespace 隔离；须先以 `deploy/infra/init/skill-store.sql` 建表）。该 bean 为 `@Primary`，MUSE 的 `SkillRefiner` 写回落到此处，演进历史可回溯。
- **运行时目录（L3）**：MUSE/用户技能默认仍写 `.agentscope/workspace/skills`（gitignored）；开启 JDBC 后写回落到版本化后端。

> 未开启上述开关时，团队/用户技能仍只在运行时目录，跨部署不保留——需要保留请先开启 JDBC 后端（或临时以外部 git 仓管理团队技能子目录）。

---

## SKILL.md 格式

```markdown
# 技能名称
description: 当需要...时使用此技能
triggers:
  - 触发词1
  - 触发词2

# 技能详细说明
## 能力范围
...

## 使用示例
...
```

---

## 技能安全审查

### 何时使用

| 技能来源 | 是否需要审查 | 原因 |
|----------|--------------|------|
| **Git/Nacos 等自动下载** | ✅ 必须审查 | 外部来源，不可信 |
| **开发者手动创建** | ❌ 不需要 | 有人工代码审查 |
| **Agent 提案（有审核闸门）** | ❌ 不需要 | 人工审核时会看到 |

### 如何使用

当需要审查外部技能时，让 Agent 调用 `skill-vetter`：

```
使用 skill-vetter 审查这个技能：
- 检查红牌警告（curl/wget、凭证请求、eval 等）
- 评估权限范围
- 给出风险等级和结论
```

---

## 启用技能系统

在 yunxi 配置（`application.yml` 或环境变量，前缀 `yunxi.muse`）中配置技能目录：

```yaml
yunxi:
  muse:
    builtin-export-dir: ${SKILL_PROJECT_DIR:.agentscope/workspace/skills}   # 内置技能 tests/ 物化目录（MUSE 评估器执行用）
```

> 说明：内置技能的 `SKILL.md` 由 `AgentConfigurer` 经 `ClasspathSkillRepository("builtin-skills")` 从 jar 零复制加载（框架 Layer2），无需物化到磁盘；`builtin-export-dir` 仅控制 `BuiltinSkillLoader` 把内置技能的 `tests/` 物化到哪里（供 MUSE 评估器在沙箱执行），MUSE 演进的技能写回该目录并覆盖内置（Layer3 > Layer2）。

---

## 完整技能列表

以下为内置的技能（共 19 个，随构建产物分发；`SKILL.md` 由 `ClasspathSkillRepository` 零复制加载，启用 MUSE 后 `BuiltinSkillLoader` 仅将其 `tests/` 物化到 `.agentscope/workspace/skills/`）：

| 技能名称 | 说明 | 触发词 |
|----------|------|--------|
| skill-vetter | 技能安全审查 | 审查技能, 安全检查 |
| java-developer | Java 开发 | Java, Maven, Spring |
| node-developer | Node.js 开发 | Node, npm, JavaScript |
| git-operator | Git 操作 | git, commit, push |
| code-reviewer | 代码审查 | code review, CR |
| qa-tester | 测试工程师 | 测试, unit test |
| test-generator | 单元测试生成 | 生成测试, 单元测试, 覆盖率 |
| devops-engineer | DevOps | K8s, Docker, CI/CD |
| database-admin | 数据库管理 | SQL, backup |
| sql-designer | SQL 设计 | 建表, DDL, 索引 |
| api-developer | API 开发 | REST, OpenAPI, curl |
| notion-writer | 技术文档 | 写文档, README |
| uml-designer | UML 图表 | 时序图, 类图, mermaid |
| logger | 日志分析 | 日志, log error |
| page-design | 页面设计 | 设计页面, 生成 UI |
| skill-creator | 技能创建 | 创建技能, SKILL.md |
| data_stats | 数据统计 | 统计, 数据, 分析 |
| text_clean | 文本清洗 | 清洗, 预处理, 文本 |
| pdf-extractor | PDF 内容抽取 | pdf, 抽取, 文档解析 |

---

## 框架演进说明

> **当前版本（基于 AgentScope-Java 2.1.0-BETA1）**：技能系统由框架原生 `AgentSkillRepository` 托管，提供 `skill_manage`（列出、启用、禁用技能）与 `skill_propose`（提案新技能）等内置工具。
>
> **MUSE 自进化引擎（已实现）**：在框架技能系统之上提供三个闭环能力——`muse_skill_eval`（沙箱评估技能测试）、`muse_skill_evolve`（LLM 修补失败技能）、`muse_skill_prune`（TUI 剪枝与合并）。详见下方「MUSE 自进化引擎」专节和 [yunxi-intelligence 模块说明](./05-modules.md)。
>
> **未来规划**：将支持 Git/Nacos/MySQL 技能市场、可见性过滤等高级功能。届时文档将同步更新。

---

## MUSE 自进化引擎

MUSE（MUSE Unified Skill Evolver）是 yunxi 的技能自进化引擎，在框架原生技能系统之上叠加"评估 → 修补 → 剪枝"闭环，让技能在长期运行中被自动验证与优化，而非一次性写好就不变。

### 自进化闭环

```
        ┌─────────────────────────────────────────────┐
        │            MUSE 自进化主循环                   │
        └─────────────────────────────────────────────┘
                              │
                              ▼
        ① muse_skill_eval ── 沙箱执行技能自带的测试/示例
                              │
              ┌───────────────┴───────────────┐
              │ 通过                        │ 失败
              ▼                              ▼
        报告健康度                  ② muse_skill_evolve
        （无需处理）                LLM 读取失败 + 原始代码
                                     │              │
                                     ▼              ▼
                              生成候选修补      用户审查（可选）
                                     │              │
                                     ▼              ▼
                              ③ muse_skill_prune ── 合并相似 / 删除冗余
                                     │
                                     ▼
                              回写技能仓库（.agentscope/workspace/skills/）
                                     │
                                     └──→ 回到 ① 下一轮
```

三个动作均通过 `SelfEvolutionEngine` 统一编排，既可独立调用（经 `muse_skill_eval` / `muse_skill_evolve` / `muse_skill_prune` 元工具），也可由引擎自身串成完整闭环。

### 模块结构

MUSE 代码位于 `yunxi-intelligence` 模块，核心类职责：

| 类 | 职责 |
|----|------|
| `SelfEvolutionEngine` | 闭环编排入口（`@Component`），串联 evaluate → refine → 再 evaluate，直到测试全过或达到 `refine.max-iterations` |
| `SkillEvaluator`（接口）/ `DefaultSkillEvaluator`（实现） | 在沙箱中执行技能自带的测试脚本，产出 `EvalReport`（通过率、失败信息） |
| `SkillRefiner` | 调用 LLM，依据 `EvalReport` 生成修补后的技能正文，并写回磁盘/技能仓库 |
| `SkillPruner`（接口）/ `DefaultSkillPruner`（实现） | 基于使用率与相似度，合并（`SimpleSkillMerger`）/ 删除冗余技能；画像驱动判据为设计态（见下） |
| `UsageProfile`（模型类） | 画像模型已定义；聚合器 `UsageProfileAggregator` 与画像存储 `RedisUserProfileStore` **尚未实现**（设计态，见下） |
| `SandboxRunner`（接口）/ `LocalSubprocessSandboxRunner`、`DockerSandboxRunner`（实现） | 隔离执行环境，确保修补过程不影响主进程 |
| `BuiltinSkillLoader` | 应用启动时把 classpath 内 `builtin-skills/` 的 `tests/` 物化到 `builtin-export-dir`（默认 `.agentscope/workspace/skills`）；`SKILL.md` 由 `ClasspathSkillRepository` 零复制加载，不落地 |
| `MuseToolRegistrarImpl` | 注册 MUSE 元工具组（`SkillEvalTool` / `SkillRefineTool` / `SkillPruneTool`）供 Agent 调用 |
| MUSE 组件装配 | `io.yunxi.intelligence.muse` 包经 `io.yunxi.intelligence` 统一组件扫描注册，各组件自带 `@ConditionalOnProperty(yunxi.muse.enabled=true)`；默认开启，对框架零侵入 |

`EvalReport` 是闭环的"事实来源"：eval 产出它，refine 消费它，prune 也参考它的执行统计。

### 画像驱动的剪枝判据（设计态未接入）

> **现状（2026-11-13 直读代码）**：只有画像模型 `UsageProfile`（`muse.prune` 包，含单测）；`UsageProfileAggregator`、`RedisUserProfileStore` **尚未实现**，`SkillPruneTool` 的 Javadoc 亦明确「该只读聚合器暂不接入」。配置绑定 `yunxi.muse.pruner.profile-aware.*`（`EvolutionConfig.ProfileAware`）已定义，但无消费方。因此当前剪枝只使用「使用率 + 相似度」两条判据，下述画像判据为设计目标。

`SkillMeta.usageCount` 是**全局累计计数**，本身分不清两种本质不同的情况：

| 情形 | 单看 `usageCount` | 实际应有判定 |
|------|------------------|--------------|
| 10 个用户各用 1 次 | 看起来"高频" | 通用技能，保留 |
| 1 个用户用了 10 次 | 看起来"高频" | 单一用户过拟合，**标记需泛化** |
| 新人刚用 1 次 | 看起来"低频" | 样本不足，不应归档 |

设计上，剪枝引入**使用者广度**维度：把「使用次数」升级为「谁在用」。数据来源为两处**只读**输入——调用轨迹（`TraceStore`）与用户画像存储，由 `UsageProfileAggregator` 聚合为 `UsageProfile`：

- `distinctUsers`：去重使用者数（区分「多人各一次」与「单人多次」）
- `categoryDistribution`：画像类别 → 覆盖的不同用户数
- `successCalls`：轨迹终态为成功的调用数

设计聚合链路：轨迹标识格式为 `userId:sessionId`，使用者身份可直接从中解析（无需扩展轨迹结构）；轨迹中技能调用表现为 `TOOL_CALL` 节点，按负载里的工具名匹配。

**设计上的两类处置**（在原有两条规则之上追加，**只做修正、不新增删除**，避免误杀小众领域的核心技能）：

1. **撤销归档**：某技能被第一遍判为低使用率，但去重使用者数达到阈值 → 多用户使用证明有通用价值，移除其归档决定。
2. **标记过拟合**：去重使用者数为 1 且调用次数达到阈值 → 标记为 `OVERFIT`（需泛化），**不删除**——「只有一类用户在用」可能恰恰是该行业的核心技能。

**设计上的边界与降级**：开关关闭或画像 / 轨迹数据缺失时，聚合返回空值，剪枝退化为原有两条规则，行为与未引入该特性时一致；聚合异常隔离，不影响剪枝产出。轨迹存储只提供「枚举」而非「按技能反查」，故聚合需枚举近期轨迹后逐个过滤，扫描量受上限约束。

### 配置

MUSE 配置前缀为 `yunxi.muse`（由 `EvolutionConfig` 绑定，**代码默认开启**；平台未提供默认 `muse.yml`，如需覆盖请在 `application.yml` 或环境变量中声明）：

```yaml
yunxi:
  muse:
    enabled: true                       # 是否启用自进化能力
    model: qwen-plus                    # 修补用的 LLM 模型名（走 yunxi ModelFactory）
    provider: dashscope                 # 模型供应商
    builtin-export-dir: .agentscope/workspace/skills  # 内置技能 tests/ 物化目录（MUSE 评估器执行用）
    sandbox:
      mode: local                       # local=本机子进程 / docker=容器沙箱（推荐）
      timeout: 60s
    evaluator:
      test-command: "java tests/SkillStructureTest.java"  # 技能目录下执行的测试命令
      max-retries: 2
    refine:
      max-iterations: 3                 # 单技能最大迭代修补次数
      stop-on-no-progress: true         # 连续无改进即停止
    pruner:
      similarity-threshold: 0.85        # 余弦相似度超过该值则建议合并
      min-usage: 1                      # 使用次数低于该值标记为可剪枝
      max-skills: 200                   # 技能库上限，超出触发剪枝
      # 画像驱动判据（只读用户画像，不反向修改画像）；关闭或数据缺失时退化为上面两条规则
      profile-aware:
        enabled: true
        scan-limit: 500                 # 轨迹枚举上限（性能护栏，超长历史下结果为近似）
        overfit-usage-threshold: 5      # 单一使用者高频阈值 → 标记过拟合（需泛化）
        general-usage-threshold: 3      # 使用者广度阈值 → 撤销低使用率归档
```

### 接入方式

业务模块通过 `io.yunxi.intelligence` 统一组件扫描装配 MUSE 组件（各组件自带 `@ConditionalOnProperty(yunxi.muse.enabled=true)`，默认开启）。MUSE 经 `matchIfMissing=true` 默认启用，无需改动任何 Agent 定义 YAML 即获得技能自进化能力；如需关闭，设置 `yunxi.muse.enabled=false`——这符合 MUSE「薄壳」设计：关闭时不加载任何 MUSE 类，对框架零侵入。MUSE 与框架技能系统完全兼容——它只读写 `.agentscope/workspace/skills/` 下的标准技能文件，不引入私有格式。

### 对框架升级的友好性

MUSE 严格依赖框架的 `AgentSkillRepository` 标准接口读写技能，不深入框架内部实现。当 AgentScope-Java 升级时，只要 `AgentSkillRepository` 的公开 API 保持稳定，MUSE 无需改动；若框架技能存储路径或序列化格式变化，仅需调整 `SandboxRunner` 与 `EvalReport` 解析两处适配点。

### 下一步建议

在具备 LLM + 沙箱的运行节点上，对 `data_stats` / `text_clean` 跑通 `muse_skill_evolve` 最小闭环；据真实返回微调 `SkillRefiner` 提示词与 `EvalReport` 解析；接入 `SkillPruner` 的**定时调度**（当前剪枝仅在显式调用 `muse_skill_prune` 时触发，尚未挂定时任务，按使用率/相似度/使用者广度合并剪枝）。

---

**上一页**: [09. API 参考](./09-api-reference.md)  
**下一页**: [11. 最佳实践 →](./11-best-practices.md)
