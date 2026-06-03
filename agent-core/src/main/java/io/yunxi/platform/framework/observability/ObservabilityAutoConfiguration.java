package io.yunxi.platform.framework.observability;

import io.agentscope.core.tracing.TracerRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Collection;

/**
 * Agent 可观测性自动配置。
 * <p>
 * 当 {@code yunxi.observability.enabled=true}（默认）时，自动初始化 OpenTelemetry SDK，
 * 注册以下组件：
 * </p>
 *
 * <h3>注册的 Bean</h3>
 * <table border="1">
 * <caption>Bean 清单</caption>
 * <tr>
 * <th>Bean</th>
 * <th>类型</th>
 * <th>说明</th>
 * </tr>
 * <tr>
 * <td>openTelemetry</td>
 * <td>{@link OpenTelemetry}</td>
 * <td>OTel SDK 实例。同时启用两种导出器：日志导出器（始终）和 OTLP HTTP 导出器（按配置）</td>
 * </tr>
 * <tr>
 * <td>openTelemetryTracer</td>
 * <td>{@link OpenTelemetryTracer}</td>
 * <td>Agent/Model/Tool 三层追踪，自动注册到 AgentScope 的 {@link TracerRegistry}</td>
 * </tr>
 * <tr>
 * <td>reActSpanHook</td>
 * <td>{@link ReActSpanHook}</td>
 * <td>ReAct 循环迭代追踪，通过 AgentScope Hook 机制注入</td>
 * </tr>
 * <tr>
 * <td>llmMetrics</td>
 * <td>{@link LlmMetrics}</td>
 * <td>Token 消耗 + 调用耗时指标</td>
 * </tr>
 * </table>
 *
 * <h3>配置方式</h3>
 * 
 * <pre>{@code
 * # application.yml 开关
 * yunxi:
 *   observability:
 *     enabled: true
 *
 * # JVM 参数（启动脚本已内置）
 * -Dotel.service.name=yunxi-agent-platform
 * -Dotel.exporter.otlp.endpoint=http://127.0.0.1:4318
 * -Dotel.traces.sampler=parentbased_always_on
 * }</pre>
 *
 * <h3>双导出器策略</h3>
 * <ul>
 * <li><b>日志导出器</b>（{@link SimpleSpanProcessor} + 内联 {@link SpanExporter}）：
 * 始终开启。每次 Span 结束时写入 {@code [Trace]} 日志行，零外部依赖</li>
 * <li><b>OTLP HTTP 导出器</b>（{@link BatchSpanProcessor} +
 * {@link OtlpHttpSpanExporter}）：
 * 按 {@code otel.exporter.otlp.endpoint} 配置选择性开启。
 * 将 Span 批量发送到 Jaeger/Tempo/OTel Collector</li>
 * </ul>
 * 两个导出器独立工作，互不干扰。
 *
 * <h3>配置读取优先级</h3>
 * <ol>
 * <li>{@code System.getProperty()} — JVM 参数 {@code -Dotel.xxx=yyy}</li>
 * <li>{@code System.getenv()} — 环境变量 {@code OTEL_XXX=yyy}</li>
 * <li>代码内默认值</li>
 * </ol>
 *
 * @see OpenTelemetryTracer
 * @see ReActSpanHook
 * @see LlmMetrics
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "yunxi.observability.enabled", matchIfMissing = true)
public class ObservabilityAutoConfiguration {

    /**
     * 构建并返回 OpenTelemetry SDK 实例。
     * <p>
     * 手动构建 {@link SdkTracerProvider} 并配置导出器链：
     * </p>
     * <ol>
     * <li>创建 {@link Resource}，设置 {@code service.name}（Jaeger 中显示的服务名）</li>
     * <li>构建 {@link SdkTracerProvider}，添加日志导出器</li>
     * <li>如果配置了 {@code otel.exporter.otlp.endpoint}，添加 OTLP HTTP 导出器</li>
     * <li>构建 {@link OpenTelemetrySdk} 并调用 {@code buildAndRegisterGlobal()}
     * 注册为全局实例</li>
     * </ol>
     * <p>
     * 注意：不使用
     * {@code io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk}，
     * 因为其 SPI 自动发现机制在不同版本间行为不稳定。改为显式构建，完全可控。
     * </p>
     * <p>
     * 依赖冲突说明：OTLP HTTP 导出器内部使用 OkHttp 发送请求。项目已通过 Maven exclusion
     * 排除了 {@code okio-jvm} 子模块，避免与 {@code okio} 主包产生类冲突
     * （{@code NoSuchMethodError: Okio.socket()}）。
     * </p>
     *
     * @return 配置完成的 OpenTelemetrySdk 实例（已注册为全局）
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenTelemetry openTelemetry() {
        // 读取配置：系统属性 > 环境变量 > 默认值
        String serviceName = resolveConfig("otel.service.name", "yunxi-agent-platform");
        String otlpEndpoint = resolveConfig("otel.exporter.otlp.endpoint", null);

        log.info("[Observability] serviceName={}, otlpEndpoint={}",
                serviceName, otlpEndpoint != null ? otlpEndpoint : "(未配置，仅日志导出)");

        // 构建资源：标识服务身份
        Resource resource = Resource.getDefault().toBuilder()
                .put(AttributeKey.stringKey("service.name"), serviceName)
                .build();

        // 构建 TracerProvider，添加日志导出器（始终开启）
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(SimpleSpanProcessor.create(loggingExporter()));

        // 如果配置了 OTLP 端点，添加 OTLP HTTP 批量导出器
        if (otlpEndpoint != null && !otlpEndpoint.isEmpty()) {
            log.info("[Observability] 启用 OTLP HTTP 导出: {}", otlpEndpoint);
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(otlpEndpoint)
                    .setTimeout(Duration.ofSeconds(10))
                    .build();
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
        }

        // 构建 SDK 并注册为全局实例（方便 TracerRegistry 访问）
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(builder.build())
                .buildAndRegisterGlobal();

        return sdk;
    }

    /**
     * 创建日志导出器。
     * <p>
     * 实现 {@link SpanExporter} 接口，将每个 Span 以 {@code [Trace]} 格式写入日志文件。
     * 使用 {@link SimpleSpanProcessor} 封装，每次 Span 结束时同步调用此导出器。
     * </p>
     * <p>
     * 导出的日志格式：{@code [Trace] <span名称> [<耗时ms>] <属性K/V>}
     * </p>
     * <p>
     * 此导出器始终开启，即使没有配置 OTLP 端点也能正常输出 Span 信息，
     * 是日常开发调试的主要工具。
     * </p>
     */
    private static SpanExporter loggingExporter() {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                for (SpanData span : spans) {
                    log.info("[Trace] {} [{}ms] {}",
                            span.getName(),
                            (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000,
                            span.getAttributes());
                }
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }

    /**
     * 读取配置项，按优先级降序查找：
     * <ol>
     * <li>{@link System#getProperty(String)} — JVM 参数方式传入</li>
     * <li>{@link System#getenv(String)} — 操作系统环境变量</li>
     * <li>代码提供的默认值</li>
     * </ol>
     * <p>
     * 示例：属性名 {@code otel.exporter.otlp.endpoint} 对应的
     * 环境变量名为 {@code OTEL_EXPORTER_OTLP_ENDPOINT}。
     * </p>
     *
     * @param propertyName 系统属性名（如 "otel.service.name"）
     * @param defaultValue 默认值（可为 null）
     * @return 配置值，未找到时返回 defaultValue
     */
    private static String resolveConfig(String propertyName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (value != null && !value.isEmpty())
            return value;
        String envName = propertyName.toUpperCase().replace('.', '_');
        value = System.getenv(envName);
        if (value != null && !value.isEmpty())
            return value;
        return defaultValue;
    }

    /**
     * 创建 {@link OpenTelemetryTracer} 并注册到 AgentScope 的 {@link TracerRegistry}。
     * <p>
     * 注册后，AgentScope SDK 内部自动在每个 Agent 调用、LLM 推理、工具执行时
     * 调用 {@link io.agentscope.core.tracing.Tracer} 接口的对应方法，
     * 创建 {@code llm.invoke} / {@code tool.execute} 等 Span。
     * </p>
     * <p>
     * 注意：Agent 调用层面（agent.call）不是通过 Tracer 接口实现，
     * 而是由 {@link ReActSpanHook} 通过 Hook 的 PRE_CALL / POST_CALL 事件处理。
     * 原因是 {@code HarnessAgent} 未实现对 {@code TracerRegistry.callAgent()} 的调用。
     * </p>
     *
     * @param openTelemetry OTel SDK 实例，用于获取 Tracer
     * @return OpenTelemetryTracer 实例（已注册到 TracerRegistry）
     */
    @Bean
    public OpenTelemetryTracer openTelemetryTracer(OpenTelemetry openTelemetry) {
        Tracer otelTracer = openTelemetry.getTracer("io.yunxi.platform", "1.0.0");
        OpenTelemetryTracer tracer = new OpenTelemetryTracer(otelTracer);
        TracerRegistry.register(tracer);
        log.info("[Observability] OpenTelemetryTracer 已注册到 AgentScope TracerRegistry");
        return tracer;
    }

    /**
     * 创建 {@link ReActSpanHook}，通过 AgentScope 的 Hook 机制注入到每个 Agent。
     * <p>
     * 该 Hook 在 {@link io.yunxi.platform.framework.agent.AgentConfigurer}
     * 的 {@code injectStandardHooks()} 方法中注册到 {@code HarnessAgent.Builder}。
     * 监听 PRE_CALL / POST_CALL / PRE_REASONING / POST_REASONING / ERROR 事件，
     * 创建 {@code agent.call} 和 {@code react.iteration} Span。
     * </p>
     *
     * @param openTelemetry OTel SDK 实例，用于获取 Tracer
     * @return ReActSpanHook 实例
     */
    @Bean
    public ReActSpanHook reActSpanHook(OpenTelemetry openTelemetry) {
        Tracer otelTracer = openTelemetry.getTracer("io.yunxi.platform", "1.0.0");
        return new ReActSpanHook(otelTracer);
    }

    /**
     * 创建 {@link LlmMetrics}，记录 LLM 调用维度的指标。
     * <p>
     * 通过 OpenTelemetry Metrics API 创建指标：
     * <ul>
     * <li>{@code llm.token.total} — Counter，按 model / provider / token.type 细分</li>
     * <li>{@code llm.duration} — Histogram，桶边界 500ms / 1s / 2s / 5s / 10s /
     * 30s</li>
     * </ul>
     * 指标数据通过 OTLP 导出到 Prometheus / Grafana 等后端。
     * 目前为预留接口，需在 {@link OpenTelemetryTracer#callModel} 中接入 Token 消耗数据源。
     * </p>
     *
     * @param openTelemetry OTel SDK 实例，用于获取 Meter
     * @return LlmMetrics 实例
     */
    @Bean
    public LlmMetrics llmMetrics(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.meterBuilder("io.yunxi.platform").build();
        return new LlmMetrics(meter);
    }
}
