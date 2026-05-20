# 02. 快速开始

本指南帮助你在 30 分钟内启动并运行 yunxi Agent Platform。

## 你将学到

- 理解 yunxi Agent Platform 与底层框架的关系
- 快速搭建开发环境
- 启动核心服务并发送第一个请求

## 技术栈关系

### 分层架构理论

**分层架构**是软件设计中最经典的架构模式之一：

```
┌─────────────────────────────────────────┐
│  第 4 层: 应用层                          │
│  - 业务逻辑                              │
│  - 用户界面                              │
├─────────────────────────────────────────┤
│  第 3 层: 框架层                          │
│  - 通用能力封装                          │
│  - 业务编排                              │
├─────────────────────────────────────────┤
│  第 2 层: 运行时层                        │
│  - Agent 执行引擎                        │
│  - 工具调用                              │
├─────────────────────────────────────────┤
│  第 1 层: 基础设施层                      │
│  - 存储、缓存、网络                       │
└─────────────────────────────────────────┘
```

**分层的好处**：
| 好处 | 说明 |
|------|------|
| **关注点分离** | 每层只关注自己的职责 |
| **易于替换** | 可以替换某层实现而不影响其他层 |
| **便于测试** | 可以独立测试每层 |
| **降低复杂度** | 将复杂系统分解为可管理的部分 |

### 本框架的技术栈层次

```
┌─────────────────────────────────────────────────────────────┐
│  基础设施层: Spring Boot / Redis / MySQL                      │
│  - Web 容器、缓存、数据库                                      │
├─────────────────────────────────────────────────────────────┤
│  模型层: DashScope / OpenAI / OLLAMA (本地)                   │
│  - 大语言模型 API                                             │
├─────────────────────────────────────────────────────────────┤
│  第三方服务: Agentscope-ReMe (远程)                           │
│  - 反射式记忆系统                                             │
└─────────────────────────────────────────────────────────────┘
```

**关键理解**：
- yunxi Agent Platform 构建在 AgentScope-Java 之上
- 你编写的业务 Agent 最终由 yunxi 的 ChatAppService 编排执行
- 你不需要直接调用 AgentScope-Java 的 API，而是通过 yunxi 的领域模型和 SPI 机制扩展

---

## 环境准备

### 系统要求

- **JDK**: 17 或更高版本
- **Maven**: 3.8 或更高版本
- **数据库**: MySQL 8.0+ 或 PostgreSQL 14+
- **缓存**: Redis 6.0+
- **操作系统**: Windows / Linux / macOS

### 为什么需要这些组件

| 组件 | 作用 | 替代方案 |
|------|------|----------|
| JDK 17 | Java 运行环境 | 不支持低于 17 |
| Maven | 项目构建 | Gradle |
| MySQL | 数据持久化 | PostgreSQL |
| Redis | 缓存、会话 | 内存模式（开发） |

### 检查环境

```bash
# 检查 JDK
java -version

# 检查 Maven
mvn -version

# 检查 Redis
redis-cli ping
```

---

## 下载与构建

### 1. 克隆项目

```bash
git clone <repository-url>
cd yunxi-agent-platform
```

### 2. 编译项目

```bash
# 编译所有模块
mvn clean install -DskipTests
```

编译完成后，各模块的 jar 包会生成在 `target/` 目录下。

**编译过程说明**：
```
源代码 → 编译 → 测试 → 打包 → 安装到本地仓库
   ↓       ↓      ↓      ↓          ↓
 .java   .class  JUnit  .jar    ~/.m2/repository
```

---

## 配置环境

### 1. 设置环境变量

创建 `.env` 文件或在系统中设置：

```bash
# 数据库配置
export MYSQL_HOST=localhost
export MYSQL_PORT=3306
export MYSQL_DATABASE=agent_platform
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=your_password

# Redis 配置
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=your_password

# LLM API Key
export DASHSCOPE_API_KEY=your_api_key
```

**为什么使用环境变量**：
- 敏感信息不写入代码
- 不同环境不同配置
- 便于容器化部署

### 2. 初始化数据库

```bash
# 执行数据库脚本
mysql -u root -p < sql/init-database.sql
```

---

## 启动服务

### 微服务启动顺序

**为什么需要按顺序启动**：
```
规则引擎 (40002) ──→ 核心服务 (40001)
                           ↓
                      网关 (40003) ←── 对外提供服务
```

- 核心服务依赖规则引擎
- 网关依赖核心服务

### 方式一：命令行启动

```bash
# 启动规则引擎（端口 40002）
cd agent-rule-engine
mvn spring-boot:run

# 新终端 - 启动网关（端口 40003）
cd agent-gateway
mvn spring-boot:run

# 新终端 - 启动核心服务（端口 40001）
cd agent-core
mvn spring-boot:run
```

### 方式二：脚本启动（Windows）

```bash
# 双击启动脚本
启动规则引擎.bat
启动 agent-app 模块

# 可选：启动记忆系统服务
启动ReMe服务.bat
```

---

## 验证启动

### 1. 健康检查

```bash
# 检查核心服务
curl http://localhost:40001/actuator/health

# 检查网关
curl http://localhost:40003/actuator/health

# 检查规则引擎
curl http://localhost:40002/actuator/health
```

### 2. 查看指标

```bash
# Prometheus 指标
curl http://localhost:40001/actuator/prometheus
```

---

## 第一个请求

### 通过 Web API 发送消息

```bash
curl -X POST http://localhost:40003/api/gateway/webapi/chat \
  -H "Content-Type: application/json" \
  -H "X-Gateway-Token: your-token" \
  -d '{
    "userId": "user001",
    "message": "你好"
  }'
```

### 请求处理流程

```
你的请求
    │
    ▼
┌─────────────────────────────────────────┐
│ Gateway (端口 40003)                     │  ← yunxi 统一网关层
│ - 协议适配、认证、限流                    │
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│ Rule Engine (端口 40002)                 │  ← yunxi 规则引擎层
│ - PRE 规则检查                           │
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│ Agent Core (端口 40001)                  │  ← yunxi 核心层
│ - 场景路由                               │
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│ yunxi ChatAppService                       │  ← yunxi 编排层
│ - 推理、工具调用                         │
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│ MCP Server (端口 40101+)                 │  ← MCP 工具层
│ - 数据库查询、文件操作等                  │
└─────────────────────────────────────────┘
```

**关键点**：
- 你的业务代码写在 yunxi 的 Agent 中（实现 DomainContributor 或 SceneContributor 接口）
- yunxi 负责路由、规则、编排
- AgentScope-Java 负责实际的 LLM 交互和工具调用

---

## 下一步

- 了解 [核心概念](./03-concepts.md)
- 查看 [模块说明](./05-modules.md)
- 阅读 [配置指南](./06-configuration.md)

## 常见问题

**Q: 启动失败，提示端口被占用？**

A: 修改对应模块的 `application.yml` 中的 `server.port`。

**Q: 数据库连接失败？**

A: 检查环境变量是否正确设置，数据库服务是否启动。

**Q: Redis 连接失败？**

A: 检查 Redis 服务是否启动，密码是否正确。

---

**上一页**: [01. 介绍](./01-introduction.md)  
**下一页**: [03. 核心概念 →](./03-concepts.md)
