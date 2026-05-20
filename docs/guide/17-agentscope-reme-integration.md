# 17. Agentscope-ReMe 集成指南

本指南详细介绍如何在 yunxi Agent Platform 中集成和使用 agentscope-ReMe（反射式记忆服务）。

## 概述

**Agentscope-ReMe** 是一个基于 Python 的反射式记忆系统，为 AI 代理提供持久化的记忆管理能力。yunxi Agent Platform 可以与 ReMe 服务对接，实现记忆增强的智能代理行为。

### 核心功能

- **任务记忆**：存储和检索任务执行历史
- **工具记忆**：记录工具使用经验模式
- **个人记忆**：管理用户偏好和个人信息

### 技术架构

```
┌─────────────────────────────────────────┐
│        yunxi Agent Platform               │
│  - Java/Spring Boot 微服务架构          │
└─────────────────────────────────────────┘
                    │ HTTP API
┌─────────────────────────────────────────┐
│        Agentscope-ReMe 服务              │
│  - Python 反射式记忆系统                │
│  - 支持本地 OLLAMA 模型                 │
└─────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────┐
│            本地 OLLAMA                  │
│    - 完全本地化 AI 模型服务             │
└─────────────────────────────────────────┘
```

## 环境要求

### 系统依赖

- **Python**: 3.10 或更高版本
- **OLLAMA**: 本地 LLM 服务 (http://localhost:11434)
- **pip**: Python 包管理器

### 推荐模型

- **LLM 模型**: `qwen2.5:7b` 
- **嵌入模型**: `bge-m3`
- **向量存储**: `memory` (内存模式)

## 项目结构

### agentscope-ReMe 项目位置

```
~/agentscope-ReMe
├── reme_ai/           # 主应用模块
├── reme/              # 核心引擎
├── docs/              # 项目文档
├── requirements.txt   # 依赖清单
└── README.md          # 项目说明
```

### yunxi Agent Platform 集成文件

```
D:\work\code\yunxi-agent-platform
├── 启动ReMe服务.bat   # ReMe 启动脚本
├── docs/              # 本文档位置
└── [其他 yunxi 文件]
```

## 安装步骤

### 1. 确认 OLLAMA 服务可用

确保本地 OLLAMA 服务已启动并运行在端口 11434：

```bash
# 检查 OLLAMA 状态
curl http://localhost:11434/api/tags

# 应有类似输出
{"models": [{"name": "qwen2.5:7b", ...}]}
```

### 2. 拉取所需模型

```bash
# 拉取 LLM 模型
ollama pull qwen2.5:7b

# 拉取嵌入模型
ollama pull bge-m3
```

### 3. 准备项目环境

确保 agentscope-ReMe 项目存在于指定路径：

```bash
ls ~/agentscope-ReMe

# 应有以下关键文件
- reme_ai/
- requirements.txt
- setup.py
```

## 启动 ReMe 服务

### 使用预配置脚本 (推荐)

yunxi Agent Platform 已提供完整的启动脚本：

```bash
# 进入 yunxi 项目目录
cd D:\work\code\yunxi-agent-platform

# 启动 ReMe 服务
启动ReMe服务.bat
```

### 启动脚本详解

**启动脚本位置**: `D:\work\code\yunxi-agent-platform\启动ReMe服务.bat`

**配置项**:

```batch
REM 端口配置
set PORT=8002

REM AI 模型配置
set LLM_MODEL=ollama/qwen2.5:7b
set EMBEDDING_MODEL=ollama/bge-m3
set OLLAMA_BASE_URL=http://localhost:11434

REM API 密钥配置 (本地模式使用占位符)
set FLOW_EMBEDDING_API_KEY=ollama
```

### 手动启动方式

如需自定义配置，可手动启动：

```bash
# 进入 ReMe 项目目录
cd ~/agentscope-ReMe &&

# 安装依赖
pip install -e .[light,litellm]

# 启动服务 (示例配置)
python -m reme_ai.main \
    http.port=8002 \
    llm.default.model_name=ollama/qwen2.5:7b \
    embedding_model.default.model_name=ollama/bge-m3 \
    embedding_model.default.base_url=http://localhost:11434/v1 \
    vector_store.default.backend=memory
```

## 验证服务状态

### 健康检查

服务启动成功后，访问以下端点验证：

```bash
# API 健康检查
curl http://localhost:8002/health

# 模型状态检查
curl http://localhost:8002/api/v1/models
```

### 预期输出

健康服务应返回：

```json
{
  "status": "healthy",
  "version": "x.x.x",
  "models": ["bge-m3:latest"]
}
```

## 配置说明

### 常用配置参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `http.port` | ReMe 服务端口 | 8002 |
| `llm.default.model_name` | LLM 模型名称 | ollama/qwen2.5:7b |
| `embedding_model.default.model_name` | 嵌入模型 | ollama/bge-m3 |
| `embedding_model.default.base_url` | OLLAMA 地址 | http://localhost:11434/v1 |
| `vector_store.default.backend` | 向量存储 | memory |

### 生产环境配置

**推荐配置**：

```yaml
port: 8002
models:
  default:
    backend: openai_compatible
    model_name: ollama/qwen2.5:7b
embedding_models:
  default:
    backend: openai_compatible
    model_name: ollama/bge-m3
    base_url: http://localhost:11434/v1
vector_stores:
  default:
    backend: memory
```

## 与 yunxi 平台集成

### 服务发现配置

在 yunxi 配置文件中添加 ReMe 服务端点：

```yaml
# application.yml
reme:
  service:
    baseUrl: http://localhost:8002
    timeout: 30000
```

### API 端点参考

ReMe 提供的主要 API：

1. **记忆检索**
   ```
   POST /api/v1/memory/retrieve
   {
     "query": "用户查询内容",
     "top_k": 5
   }
   ```

2. **记忆存储**
   ```
   POST /api/v1/memory/store  
   {
     "content": "记忆内容",
     "metadata": {"type": "task"}
   }
   ```

3. **记忆概览**
   ```
   GET /api/v1/memory/summary
   ```

## 故障排除

### 常见问题

**问题 1**: 依赖安装失败
```
ERROR: Failed to install dependencies
```

**解决方案**:
```bash
# 手动安装依赖
pip install -r requirements.txt
pip install flowllm litellm
```

**问题 2**: API 密钥错误
```
Missing credentials. Please pass an `api_key`
```

**解决方案**:
```bash
# 设置环境变量
export FLOW_EMBEDDING_API_KEY=ollama
```

**问题 3**: OLLAMA 连接失败
```
Connection refused: localhost:11434
```

**解决方案**:
```bash
# 启动 OLLAMA 服务
ollama serve
```

### 日志分析

启动日志关键信息：

```
[INFO] 加载配置文件: ~/agentscope-ReMe/reme_ai/config/default.yaml
[INFO] ReMe service is running on port 8002!
```

错误日志示例：
```
[ERROR] OpenAIError: Missing credentials.
        设置 FLOW_EMBEDDING_API_KEY 环境变量
```

## 性能优化

### 向量存储优化

根据不同场景选择合适的存储后端：

| 存储类型 | 适用场景 | 特点 |
|----------|----------|------|
| memory | 开发/测试 | 内存存储，重启后丢失 |
| local | 小型部署 | 本地文件存储，持久化 |
| qdrant | 生产环境 | 高性能向量数据库 |

### 缓存配置

```yaml
# 开启嵌入缓存提升性能
embedding_models:
  default:
    enable_cache: true
    max_cache_size: 10000
```

## 扩展开发

### 自定义记忆类型

ReMe 支持扩展自定义记忆类型：

```python
# 示例：工作流记忆
class WorkflowMemory(BaseMemory):
    def __init__(self, workflow_name):
        self.workflow_name = workflow_name
    
    def store_experience(self, input_data, output_data):
        # 存储工作流执行经验
        pass
```

### 集成到 yunxi Agent

```java
// Java 示例：在 yunxi Agent 中使用 ReMe
@Service
public class ReMeEnhancedAgent extends AbstractAgent {
    
    @Autowired
    private ReMeService remeService;
    
    @Override
    public CompletableFuture<AgentResponse> execute(AgentRequest request) {
        // 检索相关记忆
        List<Memory> relevantMemories = remeService.retrieveMemories(request.getQuery());
        
        // 基于记忆做出决策
        return processWithMemory(request, relevantMemories);
    }
}
```

## 监控与维护

### 服务监控

- **端口监控**: 8002 端口健康检查
- **模型监控**: OLLAMA 模型可用性
- **性能监控**: API 响应时间和吞吐量

### 定期维护

1. **模型更新**: 定期更新 OLLAMA 模型
2. **日志轮转**: 配置日志文件大小限制
3. **备份策略**: 重要记忆数据定期备份

## 安全建议

### 生产部署

- 使用反向代理 (如 Nginx) 保护 ReMe 服务
- 配置防火墙规则限制访问来源
- 定期更新依赖包修复安全漏洞

### API 安全

- 使用 HTTPS 加密通信
- 实现 API 密钥认证机制
- 限制请求频率防止滥用

## 相关资源

- [Agentscope-ReMe 项目主页](https://github.com/modelscope/agentscope-ReMe)
- [OLLAMA 官方文档](https://ollama.ai/)
- [ReMe API 参考文档](./09-api-reference.md)

---

**上一页**: [09. API 参考](./09-api-reference.md)  
**下一页**: [14. 智能子系统 →](./14-intelligent-system.md)

*文档版本: 1.0 | 最后更新: 2026-05-14*