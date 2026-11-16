# 14. 可观测性

yunxi 的可观测性由两部分组成：链路追踪 / 指标（yunxi 自建 `OtelTracingMiddleware` 复用框架 `MiddlewareBase` 契约 + 全局 `OpenTelemetry` API；度量经 `MetricRegistry` → Micrometer → OTLP 导出）与审计 / 指标汇聚（`yunxi-observability` 模块）。

> **历史说明**：AgentScope-Java 2.0 起废弃了原 `Tracer` / `TracerRegistry` 接口，改为在运行时内直接使用 OpenTelemetry 全局实例创建 Span。yunxi 旧版 `OpenTelemetryTracer.java` 已删除，现由 `OtelTracingMiddleware` 承担，不再实现任何 `Tracer` 接口。

了解 yunxi Agent Platform 的可观测性设计。

---

## 架构

yunxi 在 `AgentConfigurer.configureMiddlewares` 装配一条 `OtelTracingMiddleware`（order=90，近乎最内层），拦截 `AgentBase.call()` 的洋葱模型，在 Agent / Model / Tool 三层创建 OpenTelemetry Span：

```
┌─────────────────────────────────────────────────────────────┐
│                    AgentScope 运行时                          │
│                                                             │
│  AgentBase.call() → Middleware 洋葱模型                      │
│  ReActAgent 内部  → callModel() / callTool()               │
│  迭代循环         → Middleware.onAgent/onModelCall/onActing  │
└──────────────────────┬──────────────────────────────────────┘
                       │ 注册（configureMiddlewares）
             ┌─────────▼──────────┐
             │ OtelTracingMiddleware │  yunxi 自建，实现框架 MiddlewareBase
             │   （yunxi-runtime）   │  order=90
             └─────────┬──────────┘
                       │
             ┌─────────▼──────────┐
             │   双通道上报          │
             │  OpenTelemetry Span  │→ OTLP HTTP（4318）→ OTel Collector → Jaeger
             │  MetricRegistry 计数 │→ Micrometer → OTLP push（与 Span 同一 Collector）
             └─────────────────────┘
```

### 与框架组件分工

| 组件 | 位置 | 机制 | 产出 |
|------|------|------|------|
| `OtelTracingMiddleware` | `io.yunxi.runtime.middleware`（yunxi 自建） | 实现框架 `MiddlewareBase` | `invoke_agent` / `chat` / `execute_tool` Span + `MetricRegistry` 计数 |
| `AgentTraceMiddleware` | 框架 `io.agentscope.harness.agent.middleware` | SLF4J 文本日志 | 文本链路日志（可选，框架内置） |
| `GlobalOpenTelemetry` | OpenTelemetry SDK | 直连 API | `llm.invoke` / `tool.execute` 等底层 Span |

> yunxi 的 `OtelTracingMiddleware` 与框架 `io.agentscope.core.tracing.OtelTracingMiddleware` 是**两个不同实现**；yunxi 走的是前者（产品平面自建，挂在 `configureMiddlewares` 统一横切装配点，对所有协议生效）。

---

## Span 树结构

一个典型的 Agent 对话生成的 Span 树（yunxi `OtelTracingMiddleware` 产出）：

```
invoke_agent <agentName>          # 包裹整轮回复（onAgent）
├── chat <modelName>              # 每次模型调用（onModelCall）
├── execute_tool                  # 每次工具执行（onActing）
├── chat <modelName>
└── execute_tool
```

### Span 属性（yunxi 实际写入）

| Span 名称 | 属性 | 说明 |
|-----------|------|------|
| `invoke_agent <name>` | `agent.id` | Agent 名称 |
| | `session.id` | 会话 ID（来自 `RuntimeContext`，空则为空串） |
| `chat <model>` | `model.id` | 调用的模型名称 |
| `execute_tool` | `tool.count` | 本轮工具调用数量 |

### 指标（经 `MetricRegistry`）

`OtelTracingMiddleware` 通过 `yunxi-observability` 的 `MetricRegistry` 递增计数。生产装配（`RuntimeConfiguration.metricRegistry`）为 `MicrometerMetricRegistry`——桥接到进程内 Micrometer registry，由导出器决定去向：

| 计数器 | 递增时机 |
|--------|----------|
| `agent.invocations` | 每次 `onAgent` 进入 |
| `model.calls` | 每次 `onModelCall` 进入 |
| `tool.calls` | 每次 `onActing` 进入 |

> 注意：yunxi 的 `OtelTracingMiddleware` 不输出 `[Trace] <span> [<ms>]` 形式的文本日志；链路以 OpenTelemetry Span 形式上报。文本日志由框架 `AgentTraceMiddleware`（SLF4J）另行提供，二者并存不冲突。

---

## 启用与配置

### 标准 OpenTelemetry 配置

链路上报由 OpenTelemetry SDK 的标准配置驱动（系统属性 / 环境变量），`yunxi.observability.enabled` 这类独立开关当前不存在——只要 OTel SDK 提供了导出器，Span 即上报；无导出器时 SDK 为 no-op，每个钩子近乎零开销。

`.\start-yunxi.ps1` 默认注入以下 JVM 参数（如未启动 Collector，导出器仅打连接错误，不影响业务）：

```ini
-Dotel.service.name=yunxi-agent-platform
-Dotel.exporter.otlp.endpoint=http://127.0.0.1:4318
-Dotel.traces.sampler=parentbased_always_on
```

### actuator 端点暴露

`yunxi-config/src/main/resources/config/observability.yml` 仅集中管理 `management.*`（红线：禁止在其它片段重复声明 `management` 顶层键）：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,agentscope-agents,agentscope-status,agentscope-tools,agentscope-models,agentscope-usage
  endpoint:
    health:
      show-details: when-authorized
  otlp:
    metrics:
      export:
        enabled: ${YUNXI_OTLP_METRICS_ENABLED:false}
        url: ${YUNXI_OTLP_METRICS_URL:http://127.0.0.1:4318/v1/metrics}
```

### 指标导出（OTLP push，默认关闭）

度量经 Micrometer 的 OTLP registry 上报到与链路相同的 Collector。默认 `enabled=false`：未部署 Collector 时不产生外发流量；此时度量仍可经 `/actuator/metrics/<name>` 读取。

```bash
# 启用导出（与 tracing 共用 4318 端口）
YUNXI_OTLP_METRICS_ENABLED=true
YUNXI_OTLP_METRICS_URL=http://127.0.0.1:4318/v1/metrics   # 指向实际 Collector
```

> 说明：`MetricRegistry` 目前不携带维度标签（name-only），带租户 / 模型维度的度量属后续按需扩展，不在当前范围。

### 开发调试（无后端）

暂不启动 Jaeger 时，OTel 导出器连接错误会打印但业务不受影响；如需本地查看链路，按下方接入 Jaeger 即可。

---

## 接入 Jaeger

Jaeger 与 OTel Collector 已集成进 `deploy/infra/docker-compose.yml`，一键启动即可：

```bash
cd <yunxi-agent-platform>
docker compose -f deploy/infra/docker-compose.yml up -d
```

`otel-collector` 将 trace 输出到日志与 Jaeger。启动应用后访问 http://127.0.0.1:16686 ，Service 选择 `yunxi-agent-platform`，点击 "Find Traces" 查看调用链。

> `http://127.0.0.1:4318/` 根路径返回 `404 page not found` 属正常（4318 是 OTLP 接收端口而非网页，仅 `/v1/traces` 等上报路径有效）。

---

## 代码结构

```
yunxi-observability/.../observability/        # 审计 / 指标 / 轨迹存储
├── MetricRegistry.java          # 指标注册接口（平台唯一度量记录入口）
├── MicrometerMetricRegistry.java # 生产实现：桥接到 Micrometer，经 OTLP 导出
├── InMemoryMetricRegistry.java  # 内存实现（测试 / 演示替身）
├── AuditEvent.java              # 审计事件
├── AuditSink.java               # 审计落点接口
├── InMemoryAuditSink.java       # 内存实现
├── TraceStore.java              # 轨迹存储接口
├── TraceRecord.java             # 轨迹记录
├── InMemoryTraceStore.java      # 内存实现

yunxi-runtime/.../middleware/
└── OtelTracingMiddleware.java   # yunxi 自建链路追踪中间件（实现框架 MiddlewareBase，order=90）
```

核心代码零侵入业务代码，全部挂在 `configureMiddlewares` 统一横切装配点。

---

## 注意事项

1. 首次打包需下载 OTel SDK 依赖，请确保网络连通
2. Jaeger 不可用时 OTel 导出器会打印错误日志，应用正常运行
3. 指标经 `MicrometerMetricRegistry` 桥接到 Micrometer；未启用 OTLP 导出时仅进程内可读（`/actuator/metrics`），启用后经 Collector 外发

---

**上一页**: [13. A2A 协议](./13-a2a-protocol.md)  
**下一页**: [15. 意图引擎 →](./15-intent-engine.md)
