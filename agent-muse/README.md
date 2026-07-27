# Agent MUSE — Self-Evolution Engine

[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)
[![AgentScope](https://img.shields.io/badge/AgentScope--Java-2.0.0-blueviolet)](https://github.com/agentscope-ai/agentscope-java)
[![Tests](https://img.shields.io/badge/tests-32%2F32%20passed-brightgreen)](.)
[![License](https://img.shields.io/badge/License-MIT-blue)](../LICENSE)

**MUSE**（Machine Understanding & Self-Evolution）是 yunxi Agent Platform 的自进化引擎模块，在 AgentScope-Java 2.0 之上实现技能的自动评估、迭代修补、合并与剪枝闭环。

- 框架已有的能力（技能仓库、LLM 交互、记忆压缩、会话管理）——全部复用，不自造轮子
- 框架没有的能力（沙箱测试评分、LLM 迭代修补、TUI 剪枝、自进化闭环编排）——由本模块提供

---

## 核心能力

| 能力 | 说明 | 关键组件 |
|------|------|----------|
| **沙箱评估** | 在隔离环境运行技能测试，退出码判通过 + 兼容 pytest 输出解析 | `DefaultSkillEvaluator` + `LocalSubprocessSandboxRunner` / `DockerSandboxRunner` |
| **LLM 修补** | 将失败用例名 + stderr/stdout 注入提示词，经 LLM 迭代修补技能正文 | `SkillRefiner` |
| **闭环编排** | 评估→修补→再评估，首轮全通过立即返回成功，"连续无进展"提前停止 | `SelfEvolutionEngine` |
| **技能剪枝** | 按使用率归档 + TF-IDF 余弦相似度自动合并重复技能，支持中文 CJK 分词 | `DefaultSkillPruner` + `SimpleSkillMerger` |
| **元工具** | 把评估/进化/剪枝作为工具挂给 LLM，让 Agent 自主调用 | `SkillEvalTool` / `SkillRefineTool` / `SkillPruneTool` |
| **内建技能** | 启动时把 classpath 内建技能落地到 AgentScope 运行时目录，附带纯 Java 自测 | `BuiltinSkillLoader` |

---

## 快速开始

### 启用 MUSE

```yaml
# application.yml
yunxi:
  muse:
    enabled: true                     # 默认 false，零侵入
    model: qwen-max                   # 自进化闭环使用的 LLM
    provider: dashscope
```

### 验证

```bash
cd yunxi-agent-platform
mvn -pl agent-muse test     # 32 个全链路测试，无需 LLM 或 Docker
```

### 内建技能

启动后 `BuiltinSkillLoader` 自动把 classpath `builtin-skills/` 下的技能落地到 `.agentscope/workspace/skills/`（AgentScope 运行时技能目录）：

```
agent-muse/src/main/resources/builtin-skills/
├── data_stats/
│   ├── SKILL.md                       # "数据描述性统计技能"
│   └── tests/
│       ├── SkillStructureTest.java    # 纯 Java 自测（不需要 Python）
│       └── expected.txt               # 验收关键词清单
└── text_clean/
    ├── SKILL.md                       # "文本清洗技能"
    └── tests/
        ├── SkillStructureTest.java
        └── expected.txt
```

---

## 模块结构

```
agent-muse/
├── pom.xml
└── src/main/java/io/yunxi/platform/muse/
    ├── MuseAutoConfiguration.java         # @ConditionalOnProperty(yunxi.muse.enabled)
    ├── MuseToolRegistrarImpl.java         # @Bean → AgentConfigurer 零侵入挂载
    │
    ├── bootstrap/
    │   └── BuiltinSkillLoader.java        # ApplicationRunner：内建技能落地 + 注册
    │
    ├── config/
    │   └── EvolutionConfig.java           # @ConfigurationProperties(yunxi.muse.*)
    │
    ├── model/
    │   ├── TestCaseResult.java            # 单个用例结果
    │   ├── EvalReport.java                # 评估报告（退出码 + 用例级 + 末行汇总）
    │   └── EvolveOutcome.java             # 一次进化结果（succeeded + iterations + report）
    │
    ├── sandbox/
    │   ├── SandboxRunner.java             # 接口 + SandboxRunResult
    │   ├── LocalSubprocessSandboxRunner.java   # local 子进程模式（@Profile !muse-docker）
    │   └── DockerSandboxRunner.java       # Docker 容器隔离（@Profile muse-docker）
    │
    ├── eval/
    │   ├── SkillEvaluator.java            # 接口
    │   └── DefaultSkillEvaluator.java     # 沙箱跑 test-command，退出码判断
    │
    ├── refine/
    │   └── SkillRefiner.java              # Model.stream 迭代修补 + 写回磁盘 + 入库
    │
    ├── prune/
    │   ├── SkillPruner.java               # 接口 + SkillMeta / PruneDecision
    │   ├── SimpleSkillMerger.java         # CJK 二元切分 + 标准 TF-IDF + 余弦相似度
    │   └── DefaultSkillPruner.java         # 归档 + 合并策略
    │
    ├── engine/
    │   └── SelfEvolutionEngine.java       # 闭环编排（评估→修补→再评估→收敛）
    │
    └── tool/
        ├── SkillEvalTool.java             # @Tool muse_skill_eval
        ├── SkillRefineTool.java           # @Tool muse_skill_evolve
        └── SkillPruneTool.java            # @Tool muse_skill_prune
```

---

## 架构设计原则

### 薄适配壳

MUSE 严格遵循 yunxi 的"薄适配壳"原则——只实现 AgentScope 没有的自进化闭环能力，其余全部复用框架：

| 能力 | 实现位置 | 说明 |
|------|----------|------|
| 技能存储/管理 | AgentScope `AgentSkillRepository` | 7 种实现，MUSE 通过 `repo.save()` 入库 |
| LLM 调用 | AgentScope `Model.stream` | 复用 yunxi `ModelFactory` 创建 |
| 记忆/压缩 | AgentScope `CompactionMiddleware` | 框架内置，不重复实现 |
| 工具注册 | AgentScope `Toolkit.registration()` | `@Tool` 注解 + `createToolGroup("muse")` |
| 沙箱执行 | 自建 `SandboxRunner` 接口 | AgentScope 无此能力 |
| 评估→修补闭环 | 自建 `SelfEvolutionEngine` | AgentScope 无此能力 |
| 中文技能剪枝 | 自建 `SimpleSkillMerger` | AgentScope 无此能力 |

### 零侵入

- **未启用时**：`@ConditionalOnProperty(yunxi.muse.enabled)` 默认关闭，MUSE 全部 Bean 不加载，编译期零引用
- **启用后**：`AgentConfigurer` 通过 `ObjectProvider<MuseToolRegistrar>` 懒取，不修改 AgentScope 框架代码
- **SPI 解耦**：`MuseToolRegistrar` 接口定义在 `agent-core/spi`，实现在 `agent-muse`，避免循环依赖

### 升级友好

与 AgentScope 框架升级隔离：
- MUSE 内部调用全部走 AgentScope 公开 API（`Model.stream`、`AgentSkillRepository.save`、`Toolkit.registration`、`AgentSkill.builder`）
- 不依赖框架内部实现类，不 hack 任何 private/package-private API
- 沙箱执行、评估、剪枝逻辑全部自包含，AgentScope 版本升级不影响 MUSE
- 若 AgentScope 未来内置类似能力，只需把 `SkillEvaluator`/`SkillRefiner` 内部委托给框架，上层零改动

---

## 配置参考

```yaml
yunxi:
  muse:
    enabled: true
    model: qwen-max
    provider: dashscope
    builtin-export-dir: .agentscope/workspace/skills
    builtin-copy-on-start: true

    sandbox:
      mode: local                    # local | docker
      image: muse-sandbox:latest
      timeout: 60s
      network-disabled: true
      memory: 256m

    evaluator:
      test-command: "java tests/SkillStructureTest.java"
      max-retries: 2

    refine:
      max-iterations: 3
      stop-on-no-progress: true

    pruner:
      similarity-threshold: 0.85
      min-usage: 1
      max-skills: 200
```

---

## 自进化闭环核心流程

```
SkillCreator（LLM 写 SKILL.md）
       │
       ▼
SkillEvaluator（沙箱跑 java SkillStructureTest.java）
       │
       ├── ALL PASSED → 入库，完成
       │
       └── HAS FAILURES ↓
       │       ▲
       ▼       │
SkillRefiner（LLM 按 stderr 修补正文）───┘
       │          （最多 max-iterations 轮）
       │
       ▼
SkillPruner（按使用率归档 / 相似度合并）
```

---

## 测试

```bash
mvn -pl agent-muse test
```

32 个测试覆盖：

| 测试类 | 覆盖范围 |
|--------|----------|
| `BuiltinSkillLoaderTest` | 技能完整性、目录默认值、落地开关 |
| `EvalReportTest` | Java/pytest 输出解析、空值、错误摘要 |
| `SimpleSkillMergerTest` | 中英文相似度、空值防御、对称性 |
| `DefaultSkillPrunerTest` | 归档、合并、边界条件 |
| `DefaultSkillEvaluatorTest` | 真实子进程执行 data_stats/text_clean 自测 |
| `SelfEvolutionEngineTest` | 首轮通过→立即成功、不存在技能→失败、最大迭代 |

---

## 参考文档

- [MUSE 设计文档](../docs/muse-core-design.md)
- [yunxi Agent Platform 用户指南](../docs/guide/README.md)
- [AgentScope-Java 文档](https://github.com/agentscope-ai/agentscope-java)

---

## 许可证

MIT License — 详见 [LICENSE](../LICENSE)。
