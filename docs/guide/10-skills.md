# 10. 技能系统

> **V2.0 技能系统**：本平台技能存放在 `.agentscope/workspace/skills/` 目录，Agent 可通过 `skill_manage` 和 `skill_propose` 工具管理技能。MUSE 自进化引擎在此基础上提供技能的自动评估、修补与剪枝能力。

---

## 概述

### 技能目录

| 位置 | 说明 | 框架加载 |
|------|------|----------|
| `.agentscope/workspace/skills/` | **主技能目录**，存放所有技能 | ✅ Agent 可访问 |

> **推荐**：所有技能都放在 `.agentscope/workspace/skills/` 目录。该目录由 `agent-config` 的 `agentscope.core.skill.project-global-dir`（默认 `.agentscope/workspace/skills`）配置，作为 `projectGlobalSkillsDir` 被框架 `composeSkillRepositories()` 自动装载到四层技能链的 Layer 1。MUSE 启动时也会把内建技能（`builtin-skills/`）落地到此目录。

### 内置工具

- **skill_manage** — 列出、启用、禁用技能
- **skill_propose** — 提案新技能（写入 `workspace/skills/`）

---

## 技能目录结构

技能存放在 `.agentscope/workspace/skills/` 全局目录中，与 `agents/` 目录并列。完整的 workspace 目录布局如下：

```
.agentscope/workspace/
├── skills/                    # 全局共享技能目录（框架唯一识别，实际内置 24 个技能）
│   ├── skill-vetter/          # 技能安全审查
│   │   └── SKILL.md
│   ├── java-developer/        # Java 开发
│   ├── git-operator/          # Git 操作
│   ├── deployer/              # 应用部署
│   ├── docker-builder/        # Docker 构建
│   ├── code-reviewer/         # 代码审查
│   ├── machine-controller/    # 设备控制
│   ├── nutrition-knowledge/   # 营养业务知识
│   ├── nutrition-recipe/      # 营养食谱
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

### 方式一：开发者直接创建

在 `.agentscope/workspace/skills/` 下创建技能目录：

```bash
.agentscope/workspace/skills/
├── new-skill/
│   └── SKILL.md
```

### 方式二：Agent 提案

Agent 调用 `skill_propose` 提案新技能：

```
skill_propose(name="新技能名", description="技能描述", triggers=["触发词"])
```

### 两种方式对比

| 创建方式 | 谁创建 | 场景 |
|----------|--------|------|
| **开发者直接创建** | 人 | 预置技能、团队共享技能 |
| **Agent 提案** | Agent | 运行时发现的工作模式，自动生成 |

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

在 `config/agentscope.yml` 中配置：

```yaml
agentscope:
  core:
    skill:
      enabled: ${SKILL_ENABLED:false}
      project-global-dir: ${SKILL_PROJECT_DIR:.agentscope/workspace/skills}
```

---

## 完整技能列表

以下为 `.agentscope/workspace/skills/` 中实际内置的技能（共 24 个）：

| 技能名称 | 说明 | 触发词 |
|----------|------|--------|
| skill-vetter | 技能安全审查 | 审查技能, 安全检查 |
| java-developer | Java 开发 | Java, Maven, Spring |
| node-developer | Node.js 开发 | Node, npm, JavaScript |
| git-operator | Git 操作 | git, commit, push |
| code-reviewer | 代码审查 | code review, CR |
| qa-tester | 测试工程师 | 测试, unit test |
| devops-engineer | DevOps | K8s, Docker, CI/CD |
| docker-builder | Docker 构建 | docker, 镜像 |
| deployer | 应用部署 | 部署, kubectl, k8s |
| database-admin | 数据库管理 | SQL, backup |
| sql-designer | SQL 设计 | 建表, DDL, 索引 |
| api-developer | API 开发 | REST, OpenAPI, curl |
| file-manager | 文件管理 | 读文件, 写文件 |
| shell-executor | Shell 执行 | shell, 命令 |
| notion-writer | 技术文档 | 写文档, README |
| uml-designer | UML 图表 | 时序图, 类图, mermaid |
| logger | 日志分析 | 日志, log error |
| page-design | 页面设计 | 设计页面, 生成 UI |
| skill-creator | 技能创建 | 创建技能, SKILL.md |
| machine-controller | 设备控制 | 设备, 控制, 指令 |
| nutrition-knowledge | 营养业务知识 | 营养, 膳食, 标准 |
| nutrition-recipe | 营养食谱 | 食谱, 配餐 |
| data_stats | 数据统计 | 统计, 数据, 分析 |
| text_clean | 文本清洗 | 清洗, 预处理, 文本 |

---

## 框架演进说明

> **当前版本（基于 AgentScope-Java 2.0.3 GA）**：技能系统由框架原生 `AgentSkillRepository` 托管，提供 `skill_manage`（列出、启用、禁用技能）与 `skill_propose`（提案新技能）等内置工具。
>
> **MUSE 自进化引擎（已实现）**：在框架技能系统之上提供三个闭环能力——`muse_skill_eval`（沙箱评估技能测试）、`muse_skill_evolve`（LLM 修补失败技能）、`muse_skill_prune`（TUI 剪枝与合并）。详见下方「MUSE 自进化引擎」专节和 [agent-muse README](../../agent-muse/README.md)。
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

MUSE 代码位于 `agent-muse` 模块，核心类职责：

| 类 | 职责 |
|----|------|
| `SelfEvolutionEngine` | 闭环编排入口（`@Component`），串联 evaluate → refine → 再 evaluate，直到测试全过或达到 `refine.max-iterations` |
| `SkillEvaluator`（接口）/ `DefaultSkillEvaluator`（实现） | 在沙箱中执行技能自带的测试脚本，产出 `EvalReport`（通过率、失败信息） |
| `SkillRefiner` | 调用 LLM，依据 `EvalReport` 生成修补后的技能正文，并写回磁盘/技能仓库 |
| `SkillPruner`（接口）/ `DefaultSkillPruner`（实现） | 基于使用率与相似度，合并（`SimpleSkillMerger`）/删除冗余技能 |
| `SandboxRunner`（接口）/ `LocalSubprocessSandboxRunner`、`DockerSandboxRunner`（实现） | 隔离执行环境，确保修补过程不影响主进程 |
| `BuiltinSkillLoader` | 应用启动时把 classpath 内 `builtin-skills/` 落地到 `builtin-export-dir`（默认 `.agentscope/workspace/skills`） |
| `MuseToolRegistrarImpl` | 注册 MUSE 元工具组（`SkillEvalTool` / `SkillRefineTool` / `SkillPruneTool`）供 Agent 调用 |
| `MuseAutoConfiguration` | 仅在 `yunxi.muse.enabled=true` 时通过 `@ComponentScan` 装配上述组件，对框架零侵入 |

`EvalReport` 是闭环的"事实来源"：eval 产出它，refine 消费它，prune 也参考它的执行统计。

### 配置

MUSE 配置集中在 `agent-config` 的 `config/muse.yml`（前缀 `yunxi.muse`，默认关闭，需显式 `enabled: true`）：

```yaml
yunxi:
  muse:
    enabled: true                       # 是否启用自进化能力
    model: qwen-plus                    # 修补用的 LLM 模型名（走 yunxi ModelFactory）
    provider: dashscope                 # 模型供应商
    builtin-export-dir: .agentscope/workspace/skills  # 内建技能落地目录（启动复制）
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
```

### 接入方式

业务模块通过 `MuseAutoConfiguration` 自动装配 MUSE 组件（默认关闭）。在 `config/muse.yml` 中设置 `yunxi.muse.enabled: true` 即可全局启用，无需改动任何 Agent 定义 YAML——这符合 MUSE「薄壳」设计：不启用时不加载任何 MUSE 类，对框架零侵入。MUSE 与框架技能系统完全兼容——它只读写 `.agentscope/workspace/skills/` 下的标准技能文件，不引入私有格式。

### 对框架升级的友好性

MUSE 严格依赖框架的 `AgentSkillRepository` 标准接口读写技能，不深入框架内部实现。当 AgentScope-Java 升级时，只要 `AgentSkillRepository` 的公开 API 保持稳定，MUSE 无需改动；若框架技能存储路径或序列化格式变化，仅需调整 `SandboxRunner` 与 `EvalReport` 解析两处适配点。

### 下一步建议

在具备 LLM + 沙箱的运行节点上，对 `data_stats` / `text_clean` 跑通 `muse_skill_evolve` 最小闭环；据真实返回微调 `SkillRefiner` 提示词与 `EvalReport` 解析；接入 `SkillPruner` 的定时调度（按使用率/相似度合并剪枝）。

---

**上一页**: [09. API 参考](./09-api-reference.md)  
**下一页**: [11. 最佳实践 →](./11-best-practices.md)
