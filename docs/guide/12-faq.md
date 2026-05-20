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
| `curl` | 测试 API | `curl http://localhost:40001/health` |
| `netstat` | 查看端口 | `netstat -ano \| findstr 40001` |
| `telnet` | 测试连接 | `telnet localhost 3306` |
| `logs` | 查看日志 | `tail -f logs/app.log` |

---

## 安装部署

### Q: 启动失败，提示端口被占用？

**A:** 修改对应模块的 `application.yml`：

```yaml
server:
  port: 40004  # 修改为未被占用的端口
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

**A:**

```yaml
llm:
  providers:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      model: qwen-turbo
    openai:
      api-key: ${OPENAI_API_KEY}
      model: gpt-4
```

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
llm:
  providers:
    dashscope:
      model: qwen-turbo  # 使用更快的模型
      timeout: 10000
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
export GATEWAY_TOKEN=your_random_token
export MCP_API_TOKEN=your_secure_token
```

### Q: 如何限制 API 访问？

**A:**

```yaml
agent:
  gateway:
    whitelist:
      - 10.0.0.0/8
      - 192.168.0.0/16
```

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
**下一页**: [13. 更新日志 →](./13-changelog.md)
