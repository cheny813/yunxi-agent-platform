# 12. 智能系统

`yunxi-intelligence` 模块（包 `io.yunxi.intelligence`）承载平台的智能能力，由两层构成：意图引擎（Intent）与 MUSE 自进化引擎。二者均是对 AgentScope-Java 框架能力的产品平面封装，不重复实现框架已有能力。

## 实际包结构

| 类 | 包 | 职责 |
|----|-----|------|
| `IntentEngine` / `DefaultIntentEngine` | `io.yunxi.intelligence` | 意图识别入口，解析请求意图并产出路由建议 |
| `IntentResolver` / `KeywordIntentResolver` | `io.yunxi.intelligence` | 关键词意图解析器（rule 路径） |
| `SelfEvolutionEngine` | `io.yunxi.intelligence.muse.engine` | MUSE 自进化主引擎：技能评估 / 迭代 / 合并 / 剪枝 |
| MUSE 组件包 | `io.yunxi.intelligence.muse` | 经 `io.yunxi.intelligence` 统一组件扫描注册；各组件自带 `@ConditionalOnProperty(yunxi.muse.enabled=true)`，默认开启 |
| `SkillEvalTool` / `SkillRefineTool` / `SkillPruneTool` | `io.yunxi.intelligence.muse.tool` | MUSE 元工具（经 AgentScope `@Tool` 注册，由 `MuseToolRegistrar` 挂入 Toolkit） |
| `BuiltinSkillLoader` | `io.yunxi.intelligence.muse.bootstrap` | 内置技能加载 |

## 意图引擎

意图引擎识别用户请求的意图，产出 `Intent`（`label` / `confidence` / `targetAgentId`），由调用方在路由决策时按 `targetAgentId` 消费（当前为关键词→Agent 路由，未接进对话主链路）。其结构与扩展方式见 [15. 意图引擎](./15-intent-engine.md)。

## MUSE 自进化引擎

MUSE 对技能做自动评估 / 迭代 / 合并 / 剪枝，是面向长期资产的自进化能力。核心组件：

- `SelfEvolutionEngine`：主编排（评估 → 迭代 → 合并 → 剪枝）。
- `DefaultSkillEvaluator` / `SkillEvaluator`：技能评估。
- `SkillRefiner`：技能迭代。
- `DefaultSkillPruner` / `SimpleSkillMerger`：剪枝与合并。
- `UsageProfile`：使用画像（供剪枝判据）。
- `SkillEvalTool` / `SkillRefineTool` / `SkillPruneTool`：以 AgentScope `@Tool` 注册，供 Agent 在运行时调用。

完整能力见 [yunxi-intelligence 模块说明](./05-modules.md)。

---

**上一页**: [常见问题](./faq.md)  
**下一页**: [13. A2A 协议 →](./13-a2a-protocol.md)
