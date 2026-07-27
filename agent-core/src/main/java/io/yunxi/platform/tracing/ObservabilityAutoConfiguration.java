package io.yunxi.platform.tracing;

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
import io.yunxi.platform.tracing.middleware.ReActSpanMiddleware;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Collection;

/**
 * Agent 可观测性自动配置
 * <p>当 {@code yunxi.observability.enabled=true}（默认）时，自动初始化 OpenTelemetry SDK。</p>
 *
 * @see ReActSpanMiddleware
 * @see LlmMetrics
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "yunxi.observability.enabled", matchIfMissing = true)
public class ObservabilityAutoConfiguration {

    /**
     * 构建并注册全局 OpenTelemetry SDK。
     * <p>通过 {@code resolveConfig} 读取 service.name 与 OTLP 端点；
     * 未配置端点时仅启用日志型 Span 导出器，配置端点时追加批量 OTLP HTTP 导出。</p>
     *
     * @return 已注册为全局实例的 OpenTelemetry SDK
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenTelemetry openTelemetry() {
        String serviceName = resolveConfig("otel.service.name", "yunxi-agent-platform");
        String otlpEndpoint = resolveConfig("otel.exporter.otlp.endpoint", null);
        log.info("[Observability] serviceName={}, otlpEndpoint={}", serviceName,
                otlpEndpoint != null ? otlpEndpoint : "(未配置，仅日志导出)");

        Resource resource = Resource.getDefault().toBuilder()
                .put(AttributeKey.stringKey("service.name"), serviceName).build();

        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(SimpleSpanProcessor.create(loggingExporter()));

        if (otlpEndpoint != null && !otlpEndpoint.isEmpty()) {
            if (isOtlpCollectorReachable(otlpEndpoint)) {
                log.info("[Observability] OTLP collector 可达，启用 HTTP 导出: {}", otlpEndpoint);
                OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                        .setEndpoint(otlpEndpoint).setTimeout(Duration.ofSeconds(10)).build();
                builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
            } else {
                log.warn("[Observability] OTLP collector 不可达 ({}), "
                        + "降级为仅日志导出。请通过 docker compose up -d otel-collector 启动。", otlpEndpoint);
            }
        }

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(builder.build()).buildAndRegisterGlobal();
        return sdk;
    }

    /**
     * 启动时检测 OTLP collector 是否可达，避免运行时连接失败消耗连接资源。
     * <p>从 endpoint URL 中提取 host:port，用 TCP socket 尝试连接，超时 3 秒。</p>
     */
    private static boolean isOtlpCollectorReachable(String endpoint) {
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port < 0) return false;
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 3000);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建日志型 Span 导出器，将链路追踪数据以日志形式输出（无需外部 collector 即可观察）
     *
     * @return SpanExporter 实例
     */
    private static SpanExporter loggingExporter() {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                for (SpanData span : spans) {
                    log.info("[Trace] {} [{}ms] {}", span.getName(),
                            (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000,
                            span.getAttributes());
                }
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
    }

    /**
     * 解析可观测性配置项：优先取 JVM 系统属性，其次取环境变量（点号转下划线并大写），最后取默认值
     *
     * @param propertyName 配置项名（如 otel.exporter.otlp.endpoint）
     * @param defaultValue 默认值（未配置时返回）
     * @return 最终配置值
     */
    private static String resolveConfig(String propertyName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (value != null && !value.isEmpty()) return value;
        String envName = propertyName.toUpperCase().replace('.', '_');
        value = System.getenv(envName);
        if (value != null && !value.isEmpty()) return value;
        return defaultValue;
    }

    /**
     * 构建 ReAct 链路追踪 Middleware，关联 OpenTelemetry Tracer。
     *
     * @param openTelemetry OpenTelemetry 实例（由 {@link #openTelemetry()} 提供）
     * @return 链路追踪中间件
     */
    @Bean
    public ReActSpanMiddleware reActSpanHook(OpenTelemetry openTelemetry) {
        Tracer otelTracer = openTelemetry.getTracer("io.yunxi.platform", "2.0.0");
        return new ReActSpanMiddleware(otelTracer);
    }

    /**
     * 构建 LLM 指标采集器，关联 OpenTelemetry Meter。
     *
     * @param openTelemetry OpenTelemetry 实例（由 {@link #openTelemetry()} 提供）
     * @return LLM 指标采集器
     */
    @Bean
    public LlmMetrics llmMetrics(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.meterBuilder("io.yunxi.platform").build();
        return new LlmMetrics(meter);
    }
}
