# 15. 可观测性

> **⚠️ V2.0-RC3 更新**：AgentScope V2.0.0-RC3 废弃了 `Tracer`/`TracerRegistry` 接口，改用 OpenTelemetry 直连 API。平台已删除 `OpenTelemetryTracer.java`，通过 `ReActSpanMiddleware` + `GlobalOpenTelemetry` 实现链路追踪。

了解 yunxi Agent Platform 的可观测性设计。

---

## 架构

通过实现 AgentScope V2.0 SDK 的 `MiddlewareBase` 接口（V2.0-RC3 中 `Tracer` 已废弃），在 Agent/Model/Tool 三层创建 OpenTelemetry Span。

### 组件关系

```
┌─────────────────────────────────────────────────────────────┐
│                    AgentScope SDK                           │
│                                                             │
│  AgentBase.call() → Middleware 洋葱模型                      │
│  ReActAgent 内部  → callModel() / callTool()               │
│  迭代循环         → Middleware.onReasoning/onActing         │
│  Reactor Context  → Hooks.onEachOperator 自动传播           │
└──────────────────────┬──────────────────────────────────────┘
                       │ 注册
             ┌─────────┴──────────┐
             │ ReActSpanMiddleware   │  实现 MiddlewareBase 接口
             └─────────┬──────────┘
                       │
             ┌─────────▼──────────┐
             │   双导出器模式       │
             │                     │
             │ LoggingExporter     │→ 日志文件 [Trace] 行（始终）
             │ OtlpHttpSpanExporter│→ Jaeger 4318 端口（按配置）
             └─────────────────────┘
```

### 与 SDK 组件分工

| 组件 | 机制 | 优先级 | 产出 |
|------|------|--------|------|
| `AgentTraceMiddleware`（框架内置） | SLF4J 日志 | 0 | 文本日志 |
| `ReActSpanMiddleware`（平台自建） | MiddlewareBase 接口 | 30 | agent.call / react.iteration Span |
| OpenTelemetry 全局实例 | 直连 API | SDK 内部 | llm.invoke / tool.execute Span |

> V2.0-RC3 之前：框架通过 `TracerRegistry` → `OpenTelemetryTracer` 收集 Model/Tool 层 Span。RC3 废弃了该机制，改为框架内部直接使用 OpenTelemetry 全局实例创建 Span，平台无需再实现 `Tracer` 接口。已删除 `OpenTelemetryTracer.java`（约 120 行）。

---

## Span 树结构

一个典型的 Agent 对话生成的 Span 树：

```
agent.call (agent.name="nutrition-assistant")
├── react.iteration (iteration=1)
│   ├── llm.invoke (model="qwen-plus", messages=3)
│   └── tool.execute (tool="search_recipe")
├── react.iteration (iteration=2)
│   ├── llm.invoke (model="qwen-plus", messages=5)
│   └── tool.execute (tool="get_nutrition_info")
└── react.iteration (iteration=3)
    └── llm.invoke (model="qwen-plus", messages=2)
```

### Span 属性说明

| Span 名称 | 属性 | 说明 | 来源 |
|-----------|------|------|------|
| `agent.call` | `agent.name` | Agent 名称 | ReActSpanMiddleware |
| | `agent.response_length` | 响应文本长度 | 框架内部 Span |
|...|...|...|...|
| `react.iteration` | `react.iteration` | 当前迭代次数 | ReActSpanMiddleware |
| | `react.stop_requested` | 是否请求停止 | ReActSpanMiddleware |

---

## 日志输出

每次 Span 结束时写入日志文件（`logs/yunxi-agent-platform.log`）：

```
[Trace] agent.call [5234ms] {agent.name=nutrition-assistant}
[Trace] react.iteration [0ms] {react.iteration=1}
[Trace] llm.invoke [2340ms] {llm.model=qwen-plus, llm.message_count=3}
[Trace] tool.execute [567ms] {tool.name=search_recipe}
```

格式：`[Trace] <span名称> [<耗时ms>] <属性>`

---

## 启用与配置

### application.yml

```yaml
yunxi:
  observability:
    enabled: true     # 设为 false 禁用整个可观测性
```

### 启动脚本（默认）

`.\start.ps1` 自动注入以下 JVM 参数：

```ini
-Dotel.service.name=yunxi-agent-platform
-Dotel.exporter.otlp.endpoint=http://127.0.0.1:4318
-Dotel.traces.sampler=parentbased_always_on
```

确保 Jaeger 已在运行，否则 OTLP 导出器会报连接错误（不影响业务）。

### 开发调试（无后端）

如果暂时不启动 Jaeger，日志中的 `[Trace]` 行已经足够调试使用。连接错误信息会打印但业务不受影响。

---

## 接入 Jaeger

### 1. 启动 Jaeger

```bash
docker run -d --name jaeger \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 16686:16686 \
  -p 4317:4317 \
  -p 4318:4318 \
  jaegertracing/all-in-one:latest
```

### 2. 启动应用

```bash
.\start.ps1 -Clean
```

### 3. 查看

访问 http://localhost:16686，Service 选择 `yunxi-agent-platform`，即可看到完整的 Span 树。

---

## 配置参考

### JVM 系统属性

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `otel.service.name` | `yunxi-agent-platform` | 服务名（Jaeger 中显示的名称） |
| `otel.exporter.otlp.endpoint` | 无（不启用） | OTLP HTTP 端点，如 `http://127.0.0.1:4318` |
| `otel.traces.sampler` | 无 | 采样策略，如 `parentbased_always_on` |

配置读取优先级：`System.getProperty()` > `System.getenv()` > 默认值

### 环境变量

| 变量 | 说明 |
|------|------|
| `yunxi.observability.enabled` | 总开关，默认 true |
| `OTEL_SERVICE_NAME` | 服务名（系统属性 > 环境变量） |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP 端点 |
| `OTEL_TRACES_SAMPLER` | 采样策略 |

---

## 代码结构

```
agent-core/.../tracing/
├── ReActSpanMiddleware.java           # MiddlewareBase 接口实现（Agent/迭代层）
├── LlmMetrics.java                    # LLM 指标收集
└── ObservabilityAutoConfiguration.java # Spring Boot 自动配置
```

核心代码约 530 行，零侵入现有业务代码。

> **注**：`OpenTelemetryTracer.java`（约 120 行）已在 V2.0-RC3 升级中删除。框架不再需要平台实现 `Tracer` 接口，改为内部直接使用 `GlobalOpenTelemetry`。

---

## 注意事项

1. 首次打包需下载 OTel SDK 依赖，请确保网络连通
2. Jaeger 不可用时 OTel 导出器会打印错误日志，应用正常运行
3. `okio-jvm` 已通过 Maven exclusion 排除，避免与 `okio` 主包类冲突
4. Token 指标需要模型返回 usage 信息，部分模型可能不返回
