# 配置指南

## 概述

本指南说明如何配置 yunxi-agent-platform 项目。所有敏感信息（如 API 密钥、数据库密码等）都应该通过外部配置方式设置，避免硬编码。

## 快速开始

### 方法一：使用环境变量（推荐开发使用）

```bash
# 设置数据库连接
set MYSQL_HOST=127.0.0.1
set MYSQL_USERNAME=your_username
set MYSQL_PASSWORD=your_password

# 设置 DashScope API 密钥
set DASHSCOPE_API_KEY=your_dashscope_api_key

# 设置 Milvus 连接
set MILVUS_USERNAME=root
set MILVUS_PASSWORD=Milvus

# 启动项目
启动项目.bat
```

### 方法二：使用配置文件

1. 复制配置模板到本地配置文件：
```bash
copy config\application-example.yml config\application-local.yml
```

2. 编辑 `config/application-local.yml` 文件，设置你的实际配置：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/agent_platform
    username: your_username
    password: your_password

dashscope:
  api-key: your_dashscope_api_key

milvus:
  username: root
  password: Milvus
```

3. 启动项目时系统会自动加载本地配置：
```bash
启动项目.bat
```

## 配置优先级

1. **环境变量** (最优先)
2. **配置文件** (`config/application-local.yml`)
3. **默认配置文件** (`agent-config/src/main/resources/config/*.yml`)

## 安全建议

- ❌ **不要**在启动脚本中硬编码 API 密钥
- ❌ **不要**在代码中直接写入密码
- ✅ **推荐**使用环境变量或外部配置文件
- ✅ **推荐**使用密钥管理服务（KMS）

## 生产环境部署

生产环境建议采用以下配置方式：

1. **Docker/Kubernetes**: 使用 Secrets 和环境变量
2. **云部署**: 使用云提供商的密钥管理服务
3. **传统部署**: 使用配置文件配合文件权限控制

## 常见配置

### 数据库配置
```bash
# 环境变量方式
set MYSQL_HOST=your_mysql_host
set MYSQL_USERNAME=your_username
set MYSQL_PASSWORD=your_password
set MYSQL_DATABASE=agent_platform
```

### LLM API 配置
```bash
# DashScope
set DASHSCOPE_API_KEY=your_api_key
set AGENTSCOPE_MODEL=qwen-plus

# OpenAI
set OPENAI_API_KEY=your_api_key
set OPENAI_MODEL=gpt-3.5-turbo
```

### Milvus 向量数据库
```bash
set MILVUS_HOST=localhost
set MILVUS_PORT=19530
set MILVUS_USERNAME=root
set MILVUS_PASSWORD=Milvus
```

## 故障排除

### 配置不生效
- 检查配置文件名和格式是否正确
- 确认环境变量是否设置成功
- 检查配置文件编码是否为 UTF-8

### API 密钥错误
- 确认 API 密钥是否正确
- 检查网络连接是否正常
- 确认 API 服务是否可用

## 联系我们

遇到配置问题时请参考各模块的 README 文件，或联系开发团队获取支持。