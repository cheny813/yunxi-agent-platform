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
| **code-reviewer** | 代码审查 | Code Review、安全检查 |
| **logger** | 日志分析 | 日志搜索、错误定位 |
| **docker-builder** | Docker构建 | 镜像构建、容器管理 |
| **deployer** | 应用部署 | K8s部署、回滚 |
| **api-developer** | API开发 | REST API、OpenAPI文档 |
| **sql-designer** | 数据库设计 | 表结构、DDL、索引 |
| **notion-writer** | 技术文档 | README、技术方案 |
| **uml-designer** | UML图表 | Mermaid时序图、流程图 |
| page-design | 页面设计 | UI设计 |
| nutrition-recipe | 营养食谱 | 业务相关 |

## 二、技能加载配置

### 技能来源

技能从两个目录加载：
- **classpath**: `src/main/resources/skills/` - 预置技能，打包进 jar
- **filesystem**: `./skills/` - 用户扩展技能，外部目录

### 启用配置

在 `application-agentscope.yml` 中配置：
```yaml
skill-box:
  enabled: true
  classpath-enabled: true
  classpath-path: skills
  filesystem-path: ./skills    # 启用外部技能目录
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
src/main/resources/skills/    # 预置技能（打包进jar）
./skills/                      # 用户扩展技能（外部目录）
```

## 五、配置参考

### 启用 filesystem 技能
```yaml
# application-agentscope.yml
skill-box:
  enabled: true
  classpath-enabled: true
  classpath-path: skills
  filesystem-path: ./skills
```