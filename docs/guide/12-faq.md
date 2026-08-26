# 12. 常见问题

## 故障排查方法论

### 系统性排查流程

当遇到问题时，建议按照以下流程排查：

```
┌─────────────────────────────────────────┐
│  1. 确认问题现象                          │
│     - 错误信息、发生时间、影响范围          │
├─────────────────────────────────────────┤
│  2. 收集相关信息                          │
│     - 日志、配置、环境变量                  │
├─────────────────────────────────────────┤
│  3. 定位问题范围                          │
│     - 网络？数据库？服务？配置？             │
├─────────────────────────────────────────┤
│  4. 尝试解决方案                          │
│     - 根据经验或文档                        │
├─────────────────────────────────────────┤
│  5. 验证修复结果                          │
│     - 确认问题已解决                        │
└─────────────────────────────────────────┘
```

### 常用排查工具

| 工具 | 用途 | 示例 |
|------|------|------|
| `curl` | 测试 API | `curl http://localhost:40001/actuator/health` |
| `netstat` | 查看端口 | `netstat -ano \| findstr 40001` |
| `telnet` | 测试连接 | `telnet localhost 3306` |
| `logs` | 查看日志 | `tail -f logs/app.log` |

---

## 安装部署

### Q: 如何一键启动所有基础设施？

**A:** 项目根目录提供了 `docker-compose.yml`，在 IDE 终端或 PowerShell 中执行：

```powershell
# 进入项目目录
cd yunxi-agent-platform

# 一键启动全部基础设施（MySQL + Redis + Milvus + MinIO + etcd + OTel Collector；可选 Jaeger / Attu）
docker compose up -d

# 确认所有容器就绪
docker compose ps
```

启动后即可直接运行 `.\启动项目.ps1 -Fast` 启动应用。关闭所有服务：`docker compose down`。

详见 README.md「快速开始」章节。

### Q: 日志中出现 "Failed to connect to /127.0.0.1:4318" 错误？

**A:** 这是 OpenTelemetry 链路追踪的 span 导出错误，不影响业务功能。应用尝试将 trace 数据上报到本地的 OTLP Collector（端口 4318），但没有接收端。执行 `docker compose up -d` 即会启动 OTel Collector 接收这些 trace 数据，错误消失。

### Q: 启动失败，提示端口被占用？

**A:** 修改对应模块的 `application.yml`：

```yaml
server:
  port: 40001  # 修改为未被占用的端口（agent-core 默认 40001）
```

**排查方法**：
```bash
# Windows
netstat -ano | findstr 40001

# Linux/Mac
lsof -i :40001
```

### Q: 数据库连接失败？

**A:** 

```bash
# 检查环境变量
echo $MYSQL_HOST
echo $MYSQL_PORT

# 测试连接
mysql -h localhost -u root -p
```

**常见原因**：
- 数据库服务未启动
- 用户名密码错误
- 网络不通（防火墙）

### Q: Redis 连接失败？

**A:**

```bash
# 检查 Redis 状态
redis-cli ping

# 如果设置了密码
redis-cli -a your_password ping
```

### Q: Milvus 容器瞬间退出（Exited 1），日志只有 `tini` 的 Usage 信息？

**A:** 这是 milvus 服务缺少启动命令导致的典型现象。milvusdb/milvus 独立镜像本身没有默认 `Cmd`（镜像 `Config.Cmd=null`，`Entrypoint` 仅为 `["/tini","--"]`），必须显式声明启动命令，否则 `/tini` 没有要托管的程序，容器在 0.x 秒内即退出码 1 退出。

**修复**：确认 `docker-compose.yml` 的 `milvus` 服务包含：

```yaml
command: ["milvus", "run", "standalone"]
```

该字段已在仓库中配置，请勿删除。修改后重建容器：

```bash
docker rm -f yunxi-milvus
docker compose up -d milvus
docker compose ps   # 确认 yunxi-milvus 进入 Up / Healthy
```

**排查要点**：
- 若报 `The container name "/yunxi-milvus-minio" is already in use` 之类冲突，通常是上次启动失败的残留容器导致。执行 `docker rm -f yunxi-milvus yunxi-milvus-minio yunxi-milvus-etcd` 清理后重新 `docker compose up -d` 即可，这不是端口冲突。
- Milvus 依赖 etcd、minio 先 `healthy` 才会启动，首次约 30-60 秒，请耐心等待健康检查发现 Ready。

---

## 配置问题

### Q: 如何修改日志级别？

**A:**

```yaml
logging:
  level:
    root: warn
    io.yunxi.platform: info
```

**日志级别说明**：
| 级别 | 说明 | 使用场景 |
|------|------|----------|
| ERROR | 错误 | 系统错误 |
| WARN | 警告 | 需要注意的问题 |
| INFO | 信息 | 正常运行信息 |
| DEBUG | 调试 | 开发调试 |
| TRACE | 追踪 | 最详细的信息 |

### Q: 如何配置多个 LLM 提供商？

**A:** 提供商账号级配置挂在 `agentscope.core.<provider>` 前缀下（见 `config/llm.yml`）：

```yaml
agentscope:
  core:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      model: qwen-turbo
    openai:
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o
```

Agent 定义 YAML 的 `model.provider` / `model.modelName` 决定实际使用哪个提供商。

---

## 开发问题

### Q: 如何调试 Agent？

**A:** 

1. 在 IDEA 中设置断点
2. 使用 Debug 模式启动
3. 查看日志输出

```java
log.debug("Agent 接收到消息: {}", message);
```

**调试技巧**：
- 在 `handleRequest` 方法入口设置断点
- 查看上下文数据
- 跟踪工具调用流程

### Q: 单元测试失败？

**A:**

```bash
# 跳过测试编译
mvn clean install -DskipTests

# 只运行特定测试
mvn test -Dtest=MyAgentTest
```

---

## 性能问题

### Q: 响应速度慢？

**A:**

1. 检查数据库慢查询
2. 启用缓存
3. 优化 LLM 调用

```yaml
agentscope:
  core:
    dashscope:
      model: qwen-turbo  # 使用更快的模型
      timeout: 30s
```

**性能优化层次**：
```
1. 应用层优化
   - 缓存、异步、批量处理

2. 数据库优化
   - 索引、查询优化、连接池

3. 系统层优化
   - JVM 调优、GC 优化
```

### Q: 内存占用过高？

**A:**

```bash
# 调整 JVM 参数
java -Xms2g -Xmx4g -jar app.jar
```

---

## 安全问题

### Q: 如何修改默认密码？

**A:** 务必修改所有默认凭证：

```bash
export MYSQL_PASSWORD=your_strong_password
export REDIS_PASSWORD=your_strong_password
export A2A_JWT_SECRET=your_secure_token   # 启用 a2a.security.authentication.type=jwt 时使用
```

### Q: 如何限制 API 访问？

**A:**

> **说明**：框架**不提供** IP 白名单配置（`agent.gateway.whitelist` 不存在）。网络层白名单请通过反向代理（Nginx/网关）实现；应用层安全模型见 [06. 配置](./06-configuration.md#鉴权与安全模型)。

---

## 监控问题

### Q: 如何查看监控指标？

**A:**

```bash
# Prometheus 指标
curl http://localhost:40001/actuator/prometheus

# 健康检查
curl http://localhost:40001/actuator/health
```

### Q: 如何配置告警？

**A:** 在 Prometheus 中配置告警规则：

```yaml
groups:
  - name: agent-platform
    rules:
      - alert: HighErrorRate
        expr: rate(agent_request_error_count[5m]) > 0.1
```

---

**上一页**: [11. 最佳实践](./11-best-practices.md)  
**下一页**: [13. 智能系统 →](./13-intelligent-system.md)
