package io.yunxi.platform.tracing;

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
 * @see OpenTelemetryTracer
 * @see ReActSpanMiddleware
 * @see LlmMetrics
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "yunxi.observability.enabled", matchIfMissing = true)
public class ObservabilityAutoConfiguration {

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
            log.info("[Observability] 启用 OTLP HTTP 导出: {}", otlpEndpoint);
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(otlpEndpoint).setTimeout(Duration.ofSeconds(10)).build();
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
        }

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(builder.build()).buildAndRegisterGlobal();
        return sdk;
    }

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

    private static String resolveConfig(String propertyName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (value != null && !value.isEmpty()) return value;
        String envName = propertyName.toUpperCase().replace('.', '_');
        value = System.getenv(envName);
        if (value != null && !value.isEmpty()) return value;
        return defaultValue;
    }

    @Bean
    public OpenTelemetryTracer openTelemetryTracer(OpenTelemetry openTelemetry) {
        Tracer otelTracer = openTelemetry.getTracer("io.yunxi.platform", "1.0.0");
        OpenTelemetryTracer tracer = new OpenTelemetryTracer(otelTracer);
        TracerRegistry.register(tracer);
        log.info("[Observability] OpenTelemetryTracer 已注册到 AgentScope TracerRegistry");
        return tracer;
    }

    @Bean
    public ReActSpanMiddleware reActSpanHook(OpenTelemetry openTelemetry) {
        Tracer otelTracer = openTelemetry.getTracer("io.yunxi.platform", "1.0.0");
        return new ReActSpanMiddleware(otelTracer);
    }

    @Bean
    public LlmMetrics llmMetrics(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.meterBuilder("io.yunxi.platform").build();
        return new LlmMetrics(meter);
    }
}
