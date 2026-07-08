# 10. 技能系统

> **V2.0 技能系统**：本平台技能存放在 `.agentscope/workspace/skills/` 目录，Agent 可通过 `skill_manage` 和 `skill_propose` 工具管理技能。

---

## 概述

### 技能目录

| 位置 | 说明 | 框架加载 |
|------|------|----------|
| `.agentscope/workspace/skills/` | **主技能目录**，存放所有技能 | ✅ Agent 可访问 |
| `skills/` | 旧目录，保留参考 | ❌ RC3 不自动加载 |
| `agent-config/.../skills/` | classpath 参考目录 | ❌ 仅参考 |

> **推荐**：所有技能都放在 `.agentscope/workspace/skills/` 目录。

### 内置工具

- **skill_manage** — 列出、启用、禁用技能
- **skill_propose** — 提案新技能（写入 `workspace/skills/`）

---

## 技能目录结构

技能存放在 `.agentscope/workspace/skills/` 全局目录中，与 Agent 工作空间并列。完整的 workspace 目录布局如下：

```
.agentscope/workspace/
├── skills/                    # 全局共享技能目录（框架唯一识别）
│   ├── skill-vetter/          # 安全审查（外部技能审查）
│   ├── java-developer/        # Java 开发
│   │   └── SKILL.md
│   ├── git-operator/          # Git 操作
│   ├── deployer/              # 应用部署
│   ├── docker-builder/        # Docker 构建
│   ├── code-reviewer/         # 代码审查
│   ├── devops-engineer/       # DevOps
│   ├── database-admin/        # 数据库管理
│   ├── api-developer/         # API 开发
│   ├── sql-designer/          # SQL 设计
│   ├── node-developer/        # Node.js 开发
│   ├── qa-tester/             # 测试工程师
│   ├── file-manager/          # 文件管理
│   ├── shell-executor/        # Shell 执行
│   ├── notion-writer/         # 技术文档
│   ├── uml-designer/          # UML 图表
│   └── logger/                # 日志分析
├── food-chat/                 # Agent 工作空间
│   ├── AGENTS.md
│   ├── knowledge/
│   ├── skills/                # Agent 专属技能（与全局 skills/ 独立）
│   └── users/                 # 用户运行时数据
│       └── user-001/
└── nutrition-assistant/       # 另一个 Agent 工作空间
    ├── AGENTS.md
    └── users/
```

> **设计原则**：`skills/` 目录与 Agent 目录同级，由 `WorkspaceAutoDiscoveryEngine` 在其 `discoverOrRecurse` 中跳过处理。全局技能对所有 Agent 可见，Agent 专属技能存放在各自 `{agentName}/skills/` 下。

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
  extensions:
    skills:
      enabled: true
```

---

## 完整技能列表

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
| nutrition-recipe | 食谱规划 | 食谱, 营养配餐 |
| nutrition-knowledge | 营养知识 | 营养成分, 卡路里 |
| page-design | 页面设计 | 设计页面, 生成 UI |
| skill-creator | 技能创建 | 创建技能, SKILL.md |

---

## 框架演进说明

> **RC3 版本**：技能系统仅支持 `skill_manage` 和 `skill_propose` 工具。
>
> **未来 RC4+**：将支持 Git/Nacos/MySQL 技能市场、自学习闭环、可见性过滤等高级功能。届时文档将同步更新。

---

**上一页**: [09. API 参考](./09-api-reference.md)  
**下一页**: [11. 最佳实践 →](./11-best-practices.md)
