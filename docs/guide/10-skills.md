# 10. 技能系统

> **V2.0 技能系统**：本平台使用 AgentScope V2.0 内置的 **SkillCurator** 治理流水线，提供完整的技能管理生命周期。

---

## 概述

AgentScope V2.0 提供了内置的技能系统，Agent 可以：

- **skill_manage** — 列出、启用、禁用技能
- **skill_propose** — 提案新技能（写入 `workspace/skills/`）

### 技能加载来源

| 来源 | 路径 | 说明 |
|------|------|------|
| Classpath | `src/main/resources/skills/` | 打包进 JAR，预置技能 |
| Filesystem | `./skills/` | 用户扩展技能 |
| Workspace | `.agentscope/workspace/<agent>/skills/` | Agent 提案的技能 |

---

## 启用技能系统

在 `config/agentscope.yml` 中配置：

```yaml
agentscope:
  extensions:
    skills:
      enabled: true
```

---

## 技能目录结构

```
skills/
├── java-developer/           # Java 开发技能
│   └── SKILL.md
├── git-operator/             # Git 操作技能
│   └── SKILL.md
├── deployer/                 # 部署技能
│   └── SKILL.md
└── ...
```

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

## Agent 提案新技能

Agent 在对话中发现重复的工作模式时，可以调用 `skill_propose` 提案创建新技能：

```
skill_propose(name="新技能名", description="技能描述", triggers=["触发词"])
```

提案会被写入 `.agentscope/workspace/<agent>/skills/` 目录，由 SkillCurator 治理流水线处理。

---

## 开发者创建技能

除了 Agent 提案，**开发者也可以直接创建技能**：

```bash
# 技能存放位置
.agentscope/workspace/<agent>/skills/
├── java-developer/           # 开发者直接创建
│   └── SKILL.md
├── git-operator/             # 开发者直接创建
│   └── SKILL.md
└── agent-proposed-skill/     # Agent 提案创建的
    └── SKILL.md
```

### 两种方式对比

| 创建方式 | 谁创建 | 场景 |
|----------|--------|------|
| **开发者直接创建** | 人 | 预置技能、团队共享技能 |
| **Agent 提案** | Agent | 运行时发现的工作模式，自动生成 |

两种方式创建的技能都会被 SkillCurator 治理流水线处理，效果完全一样。

---

## 业务技能

| 技能名称 | 说明 | 触发词 |
|----------|------|--------|
| nutrition-recipe | 食谱规划 | 食谱, 营养配餐 |
| nutrition-knowledge | 营养知识 | 营养成分, 卡路里 |
| page-design | 页面设计 | 设计页面, 生成 UI |
| skill-creator | 技能创建 | 创建技能, 编写 SKILL.md |
| java-developer | Java 开发 | Java, Maven, Spring |
| git-operator | Git 操作 | git, commit, push |
| deployer | 应用部署 | 部署, kubectl, k8s |
| docker-builder | Docker 构建 | docker, 镜像, 容器 |

---

**上一页**: [09. API 参考](./09-api-reference.md)  
**下一页**: [11. 最佳实践 →](./11-best-practices.md)
