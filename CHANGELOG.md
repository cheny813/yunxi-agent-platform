# 更新日志

所有重要的项目变更都将记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)。

## [未发布] - 开发中

### 新增
- 新功能
- 新模块

### 变更
- 功能变更

### 废弃
- 即将移除的功能

### 修复
- Bug 修复

### 安全
- 安全相关修复

---

## [1.0.0] - 2026-05-09

### 新增

#### 核心框架
- `agent-core` 模块：Agent 核心框架
  - ReActAgent 支持
  - A2A 协议实现
  - MCP 协议集成
  - 工具系统
  - 技能管理
  - 记忆系统
  - 对话管理
  - 多厂商 LLM/Embedding 支持

#### 业务模块
- `agent-business` 模块：业务实现
  - 营养配餐 Agent
  - A2A 流水线
  - 页面生成 Agent
  - DevOps 工具集

#### 网关模块
- `agent-gateway` 模块：消息网关
  - 企微 WebSocket 适配器
  - 钉钉 Webhook 适配器
  - 飞书 WebSocket 适配器
  - Web 通道

#### 规则引擎
- `agent-rule-engine` 模块：规则引擎
  - EasyRules 集成
  - SpEL 表达式支持
  - PermissionRule 权限规则
  - AuditLogRule 审计规则
  - RateLimitRule 限流规则
  - ResourceLimitRule 资源限制规则

#### Text-to-SQL
- `agent-text2sql` 模块：自然语言转 SQL
  - Schema 生成
  - 列检索（Milvus 向量检索）
  - Few-shot 学习
  - SQL 生成（DashScope LLM）
  - SQL 对齐
  - SQL 投票

#### SPI 接口
- `agent-spi` 模块：公共接口
  - Text2SqlFacade 接口
  - DatabaseClient 接口
  - EmbeddingService 接口
  - UserProfileProvider 接口
  - VectorSearchProvider 接口
  - CacheProvider 接口

#### 部署支持
- Dockerfile 多阶段构建
- Kubernetes 部署文件（deployment, service, configmap, ingress, hpa）
- Helm Chart

### 变更
- 项目结构重构为多模块 Maven 项目
- 依赖管理优化，核心依赖改为可选
- 健康检查配置完善

### 废弃
- 旧版单模块结构（已移除）

### 修复
- 编译错误修复
- 依赖冲突解决

### 安全
- MCP 服务 Token + Bearer 鉴权
- 工具级权限控制
- 文件系统目录白名单
- SQL 类型限制

### 文档
- 用户指南（11 章）
- 模块 README
- 端到端示例
- 架构图与调用链图
- GETTING_STARTED.md
- CONTRIBUTING.md
- CODE_OF_CONDUCT.md

---

## [0.x.x] - 早期版本

### 新增
- 初始项目结构
- 基础 Agent 抽象
- 简单对话功能

[未发布]: https://gitcode.com/chenyao813/yunxi-agent-platform/compare/v1.0.0...HEAD
[1.0.0]: https://gitcode.com/chenyao813/yunxi-agent-platform/releases/tag/v1.0.0
[0.x.x]: https://gitcode.com/chenyao813/yunxi-agent-platform/releases/tag/initial
