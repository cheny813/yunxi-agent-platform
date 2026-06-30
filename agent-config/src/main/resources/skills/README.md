# 技能系统架构说明

## 一、岗位技能一览

已创建 16 个岗位技能，覆盖软件公司主要岗位：

| 技能名称 | 岗位 | 核心能力 |
|---------|------|----------|
| developer | 开发者 | 代码编写、调试、重构、Code Review、Git操作 |
| java-developer | Java开发 | Maven/Gradle构建、Spring Boot |
| node-developer | 前端开发 | npm/yarn、构建、测试 |
| git-operator | 版本控制 | Git 操作 |
| file-manager | 文件操作 | 文件读写、目录管理 |
| shell-executor | 命令执行 | Shell命令 |
| qa-tester | 测试工程师 | 单元测试、集成测试 |
| devops-engineer | 运维工程 | K8s、Docker、CI/CD |
| database-admin | 数据库管理 | SQL、备份、性能 |
| code-reviewer | 代码审查 | Code Review、安全检查 |
| logger | 日志分析 | 日志搜索、错误定位 |
| docker-builder | Docker构建 | 镜像构建、容器管理 |
| deployer | 应用部署 | K8s部署、回滚 |
| api-developer | API开发 | REST API、OpenAPI文档 |
| sql-designer | 数据库设计 | 表结构、DDL、索引 |
| notion-writer | 技术文档 | README、技术方案 |
| uml-designer | UML图表 | Mermaid时序图、流程图 |
| page-design | 页面设计 | UI设计 |
| nutrition-recipe | 营养食谱 | 业务相关 |

## 二、技能加载配置

### 技能来源

技能从三个目录加载：
- **classpath**: `src/main/resources/skills/` - 预置技能，打包进 jar
- **filesystem**: `./skills/` - 用户扩展技能，外部目录
- **workspace**: `.agentscope/workspace/<agent>/skills/` - Agent 提案的技能

### 启用配置

在 `config/agentscope.yml` 中配置：

```yaml
agentscope:
  extensions:
    skills:
      enabled: true
```

## 三、产研技能矩阵

### 开发 (4)
- developer, java-developer, node-developer, api-developer

### 代码质量 (2)
- code-reviewer, qa-tester

### 版本控制 (1)
- git-operator

### 运维 (3)
- devops-engineer, docker-builder, deployer

### 数据 (2)
- database-admin, sql-designer

### 诊断 (1)
- logger

### 文档 (1)
- notion-writer

### 图表 (1)
- uml-designer

### 基础 (2)
- file-manager, shell-executor

## 四、快速开始

技能目录结构：
```
src/main/resources/skills/         # 预置技能（打包进jar）
./skills/                          # 用户扩展技能（外部目录）
.agentscope/workspace/<agent>/skills/  # Agent 提案的技能
```

## 五、创建技能

### 两种创建方式

| 创建方式 | 谁创建 | 场景 |
|----------|--------|------|
| **开发者直接创建** | 人 | 预置技能、团队共享技能 |
| **Agent 提案** | Agent | 运行时发现的工作模式，自动生成 |

### 开发者直接创建

```bash
# 技能存放位置
.agentscope/workspace/<agent>/skills/
├── java-developer/
│   └── SKILL.md
├── git-operator/
│   └── SKILL.md
└── ...
```

### Agent 提案

Agent 可以通过 `skill_propose` 工具提案新技能：

```
skill_propose(name="新技能名", description="技能描述", triggers=["触发词"])
```

提案会被写入 `.agentscope/workspace/<agent>/skills/` 目录，由 SkillCurator 治理流水线处理。

两种方式创建的技能都会被 SkillCurator 治理流水线处理，效果完全一样。
