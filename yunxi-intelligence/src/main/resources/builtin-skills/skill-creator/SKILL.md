---
name: skill-creator
description: "当用户想要创建、设计或修改新的技能定义（SKILL.md）时使用此技能。提供技能编写最佳实践、格式规范和示例的指导。"
metadata:
  openclaw:
    emoji: "🔧"
    requires:
      bins: ""
---

# 技能创建器

本技能指导创建遵循标准 SKILL.md 格式的新 AgentScope 技能。

## SKILL.md 标准格式

每个技能都需要一个包含 YAML 头部的 `SKILL.md` 入口文件：

```markdown
---
name: skill-name
description: "简短描述：当用户需要...时使用此技能。包含关键词帮助AI识别触发条件。"
metadata:
  {
    "openclaw":
      {
        "emoji": "🎯",
        "os": ["darwin", "linux", "windows"],
        "requires": { "bins": ["gh", "git"] },
        "install": [
          { "id": "brew", "kind": "brew", "formula": "gh", "bins": ["gh"], "label": "安装" }
        ],
      },
  }
---

# 技能标题

## When to Use

✅ **USE this skill when:**
- 场景1：...
- 场景2：...

## When NOT to Use

❌ **DON'T use this skill when:**
- 场景1：...
- 场景2：...

## 核心能力

### 命令分类

| 工具 | 描述 | 示例 |
|------|------|------|
| tool-name | 功能描述 | 命令示例 |

## 注意事项

- 关键点1
- 关键点2
```

## 格式规则

### 1. YAML 头部

- `name`: 仅使用小写字母、数字、连字符
- `description`: 使用英文，明确触发条件，包含关键词
- `metadata.openclaw`: 包含 emoji、os（可选）、requires.bins（可选）、install（可选）

### 2. 内容结构

- **When to Use**: 具体使用场景（✅ 标记）
- **When NOT to Use**: 避免使用场景（❌ 标记）
- **核心能力**: 使用 Markdown 表格展示工具

### 3. 内容长度

- SKILL.md 保持在 2k tokens 以内
- 详细文档放在 `references/` 子目录
- 代码示例放在 `examples/` 子目录
- 可执行脚本放在 `scripts/` 子目录

## 资源组织

```
skill-name/
├── SKILL.md          # 入口文件（必填）
├── references/       # 详细文档（可选）
│   └── doc.md
├── examples/         # 工作示例（可选）
│   └── example1.java
├── scripts/          # 可执行脚本（可选）
│   └── tool.py
├── assets/          # 静态资源（可选）
│   ├── templates/   # 模板文件
│   ├── images/      # 图片
│   └── data/       # 数据文件
└── guardrails/      # 安全配置（可选）
    └── allowed-commands.yaml
```

## 技能生命周期

1. AI 在系统提示中看到技能名称 + 描述（约 100 tokens）
2. AI 调用 `load_skill_through_path(skillId, "SKILL.md")` 加载完整指令
3. AI 根据需要通过相同工具访问额外资源
4. 激活后，技能的工具变得可用

## 最佳实践

### 描述编写

- description 使用英文，以 "当用户需要..." 开头说明触发条件
- 包含具体关键词帮助 AI 识别相关任务
- 避免模糊的描述

### 资源说明

- **assets/templates/**: 存放固定格式模板文件（Excel、Word、CSV、JSON）
- **assets/images/**: 存放流程图、示意图、示例截图（PNG、JPG、SVG）
- **assets/data/**: 存放查找表、配置数据、业务规则（JSON、CSV、YAML）

### 安全配置（可选）

如技能需要执行系统命令，应配置 guardrails：

```
skill-name/
├── guardrails/
│   ├── allowed-commands.yaml   # 允许执行的命令白名单
│   └── blocked-paths.yaml      # 禁止访问的路径
└── scripts/
    └── tool.sh
```

## 示例：机器控制技能

```markdown
---
name: machine-controller
description: "Control local machine to execute system commands, file operations. Use when: user wants to run commands, read/write files, list directories."
metadata:
  {
    "openclaw":
      {
        "emoji": "💻",
        "os": ["darwin", "linux", "windows"],
        "requires": { "bins": ["bash", "powershell", "python3"] },
      },
  }
---

# Machine Controller

Control local machine to execute system-level tasks.

## When to Use

✅ **USE this skill when:**
- User asks to "list directory" or "view files"
- User asks to "read file" or "write file"
- User asks to "run command" or "execute script"

## When NOT to Use

❌ **DON'T use this skill when:**
- Simple questions without action required
- UI interactions → use page-agent skill

## Core Capabilities

### File Operations

| Tool | Description | Windows | Linux/Mac |
|------|-------------|---------|-----------|
| list-dir | List directory | `dir` | `ls -la` |
| read-file | Read file | `type` | `cat` |

### System Commands

| Tool | Description |
|------|-------------|
| execute-command | Execute whitelisted commands |

## Cross-Platform Notes

- **Windows**: Use `cmd`, `powershell`, `dir`, `type`
- **Linux/Mac**: Use `bash`, `zsh`, `ls`, `cat`

## Security

All executable commands must be defined in `guardrails/allowed-commands.yaml`.
```

## 模板

### 基础技能模板

```markdown
---
name: my-skill
description: "当用户需要...时使用此技能。包含关键词。"
metadata:
  {
    "openclaw": { "emoji": "🎯", "requires": { "bins": [] } },
  }
---

# 我的技能

## When to Use

✅ **USE this skill when:**
- 场景1
- 场景2

## When NOT to Use

❌ **DON'T use this skill when:**
- 场景1

## Core Capabilities

### 能力分类

| Tool | Description |
|------|-------------|
| tool-name | 功能描述 |

## Notes

- 注意事项1
- 注意事项2
```