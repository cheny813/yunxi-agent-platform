# 常见问题

> 本文是参考附录，不属于顺序学习章节。遇到具体故障先按下方方法论定位，再查对应章节。

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
| `logs` | 查看日志 | `tail -f logs/yunxi-agent-platform.log` |

---

## 安装部署

### Q: 如何一键启动所有基础设施？

**A:** 基础设施编排在 `deploy/infra/docker-compose.yml`，在 PowerShell 中执行：

```powershell
# 进入项目目录
cd yunxi-agent-platform

# 一键启动全部基础设施（MySQL + Redis + Milvus + MinIO + etcd + Nacos + OTel Collector + Jaeger + Attu，共 9 个容器）
docker compose -f deploy/infra/docker-compose.yml up -d

# 确认所有容器就绪
docker compose -f deploy/infra/docker-compose.yml ps
```

启动后即可运行 `.\start-yunxi.ps1` 启动应用（首次自动打包）。关闭所有服务：`docker compose -f deploy/infra/docker-compose.yml down`。

详见 README.md「快速开始」章节。

### Q: 日志中出现 "Failed to connect to /127.0.0.1:4318" 错误？

**A:** 这是 OpenTelemetry 链路追踪的 span 导出错误，不影响业务功能。应用尝试将 trace 数据上报到本地的 OTLP Collector（端口 4318），但没有接收端。执行 `docker compose up -d` 即会启动 OTel Collector 接收这些 trace 数据，错误消失。

### Q: 启动失败，提示端口被占用？

**A:** 端口由 `yunxi-config` 配置中心统一管理，默认 `40001`。覆盖方式二选一：

- 在 `yunxi-config/src/main/resources/config/server.yml` 修改 `server.port`；或
- 启动脚本加 `-Port` 参数：`.\start-yunxi.ps1 -Port 8080`。

**排查方法**：
```bash
# Windows
netstat -ano | findstr 40001

# Linux/Mac
lsof -i :40001
```

### Q: 数据库连接失败？

**A:** 先确认你连的是哪个库：**yunxi 应用本身不连接 MySQL**——会话状态走 Redis；轨迹只有 `TraceStore` 的内存实现，且运行时尚未写入。`deploy/infra` 中的 MySQL 容器仅服务于独立的 `mcp-nutrition` MCP 服务（库 `yunxi_nutrition`，表结构由 `deploy/infra/init/nutrition-schema.sql` 初始化）。

若你在运行 `mcp-nutrition` 时报数据库连接失败：

```bash
# 检查环境变量
echo $MYSQL_HOST
echo $MYSQL_PORT

# 测试连接
mysql -h localhost -u root -p
```

**常见原因**：
- MySQL 容器未启动（`docker compose -f deploy/infra/docker-compose.yml ps`）
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

**修复**：确认 `deploy/infra/docker-compose.yml` 的 `milvus` 服务包含：

```yaml
command: ["milvus", "run", "standalone"]
```

该字段已在仓库中配置，请勿删除。修改后重建容器：

```bash
docker rm -f yunxi-milvus
docker compose -f deploy/infra/docker-compose.yml up -d milvus
docker compose -f deploy/infra/docker-compose.yml ps   # 确认 yunxi-milvus 进入 Up / Healthy
```

**排查要点**：
- 若报 `The container name "/yunxi-milvus-minio" is already in use` 之类冲突，通常是上次启动失败的残留容器导致。执行 `docker rm -f yunxi-milvus yunxi-milvus-minio yunxi-milvus-etcd` 清理后重新 `docker compose -f deploy/infra/docker-compose.yml up -d` 即可，这不是端口冲突。
- Milvus 依赖 etcd、minio 先 `healthy` 才会启动，首次约 30-60 秒，请耐心等待健康检查发现 Ready。

---

## 配置问题

### Q: 如何修改日志级别？

**A:**

```yaml
logging:
  level:
    root: warn
    io.yunxi: info
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

**A:** 模型以「命名模型清单」形式外部化配置在 `yunxi-config/src/main/resources/config/models.yml` 的 `yunxi.models.catalog` 段，每个条目声明 `provider` / `model` / `api-key-env`：

```yaml
yunxi:
  models:
    catalog:
      qwen-plus:
        provider: dashscope
        model: qwen-plus
        api-key-env: DASHSCOPE_API_KEY
        type: llm
      gpt-4o:
        provider: openai
        model: gpt-4o
        api-key-env: OPENAI_API_KEY
        type: llm
    defaults:
      llm: qwen-plus   # 对话 / 推理默认模型
```

密钥经环境变量注入（`DASHSCOPE_API_KEY` / `OPENAI_API_KEY` 等），不写进 YAML。Agent 通过 `routing` 段将 `agentId` 映射到 `catalog` 中的模型名；不显式声明时走自动选型（见 README「配置大模型」与 `models.yml` 顶部注释）。

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

1. 在 `yunxi-config/src/main/resources/config/models.yml` 的 `yunxi.models.catalog` 段为轻量场景选用更快的模型（如 `qwen-turbo`）
2. 优化 LLM 调用（缩短上下文、减少工具往返）
3. 应用层启用缓存与异步

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
export A2A_JWT_SECRET=your_secure_token   # 若 A2A 传输层启用 JWT 鉴权（由框架 A2A 安全配置决定，非 yunxi 当前装配项）时使用
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
**下一页**: [12. 智能系统 →](./12-intelligent-system.md)
