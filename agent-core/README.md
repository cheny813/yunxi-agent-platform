# agent-core

核心框架模块，提供 Agent 生命周期管理、对话编排、记忆服务、MCP 调用等核心能力。

## 职责

- Agent 生命周期管理
- 对话/会话调度与编排
- 记忆存储与检索接口
- MCP 调用能力抽象

## 架构分层

```
agent-core/
├── framework/    # 框架层 - 核心抽象与扩展点
├── infra/        # 基础设施层 - 技术实现
└── shared/       # 共享层 - 通用组件
```

## 核心组件

| 组件 | 说明 |
|------|------|
| AgentRegistry | Agent 注册与发现 |
| SessionManager | 会话管理 |
| MemoryService | 记忆服务 |
| McpClientService | MCP 客户端 |
| SceneRouter | 场景路由 |

## SPI 扩展点

| 扩展点 | 接口 | 说明 |
|--------|------|------|
| 领域贡献 | DomainContributor | 注册业务领域 |
| 场景贡献 | SceneContributor | 注册业务场景 |
| 上下文增强 | ContextEnricher | 丰富对话上下文 |

## 文档

详细文档见 [用户指南](../docs/guide/05-modules.md)。
