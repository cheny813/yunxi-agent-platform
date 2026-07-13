# Yunxi Platform 与 AgentScope Java v2.0 包结构对照

Yunxi Platform 基于 AgentScope Java SDK 构建，是在其之上的平台级扩展。本文档建立两者包结构的对照关系，方便阅读源码时交叉参考。

---

## 底层核心包（已对齐）

Yunxi (`io.yunxi.platform`) | AgentScope (`io.agentscope.core`) | 说明
---|---|---
`agent/` | `agent/` | Agent 抽象、ReActAgent、自定义 Agent 实现
`tool/` | `tool/` | 工具定义、工具注册、工具调用
`memory/` | `memory/` | 对话记忆、长期记忆、检索增强
`tracing/` | `tracing/` | 执行追踪、调用链、可观测性

---

## 语义对应包（功能等价，命名不同）

Yunxi (`io.yunxi.platform`) | AgentScope (`io.agentscope.core`) | 说明
---|---|---
`knowledge/` | `rag/` | 知识库管理、文档检索、向量化、分块策略
`shared/` | `message/`（部分） | 消息模型、会话消息、工具消息；同时还包含通用工具类、数据模型、常量
`framework/` | `workspace/` + `middleware/` + `hook/` | 框架基础设施：工作空间、中间件链、钩子系统
`persistence/` | `state/`（部分） | 会话持久化、状态序列化、存储适配
`lifecycle/` | `shutdown/`（部分） | Agent 生命周期管理、启动/停止回调
`security/` | `credential/`（部分） | 认证信息管理、凭证处理
`config/` | - | AgentScope 无独立 config 包，配置分散在各模块；Yunxi 集中管理 Bean 配置、属性注入

---

## Yunxi 平台独有包（AgentScope 无对应）

Yunxi 包 | 说明
---|---
`a2a/` | Agent-to-Agent 通信协议实现
`cache/` | 分布式缓存（Redis / Caffeine）
`controller/` | REST API 控制器层
`conversation/` | 对话管理、多轮会话编排
`desktop/` | 桌面端适配
`embedding/` | 文本向量化服务封装
`file/` | 文件上传、解析、管理
`gateway/` | API 网关、路由、限流
`intelligent/` | 智能体编排与调度
`mcp/` | Model Context Protocol 客户端/服务端实现
`pageagent/` | 页面级 Agent（浏览器自动化）
`prompt/` | 提示词模板管理与渲染
`session/` | 会话上下文管理
`spi/` | 服务提供者接口（SPI 扩展点）
`structured/` | 结构化输出（JSON Schema / Function Calling）
`sync/` | 异步任务同步

---

## AgentScope 独有包（Yunxi 未独立拆分）

AgentScope 包 | 说明
---|---
`event/` | 事件总线、Agent 事件发布订阅
`exception/` | SDK 异常体系
`formatter/` | 消息格式化器（86 个文件，格式化逻辑集中）
`interruption/` | Agent 中断机制
`model/` | LLM 模型适配层（54 个文件）
`permission/` | 工具调用权限控制
`skill/` | Skill 系统（技能注册与调用）
`util/` | SDK 内部工具类
`workspace/` | 工作空间管理

---

## 阅读源码路径建议

1. **先读 AgentScope 源码**（SDK 层）理解原语：从 `agent/`、`tool/`、`memory/` 开始
2. **对照读 Yunxi 平台层**看扩展：对照上表找到对应包，理解平台如何封装和增强
3. **读 Yunxi 独有包**看业务能力：`a2a/`、`mcp/`、`pageagent/` 等是平台特有的上层能力
