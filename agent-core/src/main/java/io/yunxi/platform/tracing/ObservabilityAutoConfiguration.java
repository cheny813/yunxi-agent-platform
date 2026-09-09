package io.yunxi.platform.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.agentscope.core.tracing.OtelTracingMiddleware;
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
 * @see OtelTracingMiddleware
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
        String otlpEndpoint = normalizeOtlpEndpoint(resolveConfig("otel.exporter.otlp.endpoint", null));
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
                        .setEndpoint(otlpEndpoint + "/v1/traces").setTimeout(Duration.ofSeconds(10)).build();
                builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
            } else {
                log.warn("[Observability] OTLP collector 不可达 ({}), "
                        + "降级为仅日志导出。请通过 docker compose up -d otel-collector 启动。", otlpEndpoint);
            }
        }

        // 指标导出：与链路追踪共用同一 OTLP 管线。GA 仅做链路追踪（无内置指标模块），
        // yunxi 的 LlmMetrics 已基于本全局 SDK 的 Meter 采集 token/duration 等指标；
        // 未配置可达的 OTLP 端点时仅构造无导出器的 MeterProvider，指标本地采集、不对外暴露。
        SdkMeterProviderBuilder meterBuilder = SdkMeterProvider.builder()
                .setResource(resource);
        boolean metricsExportable = otlpEndpoint != null && !otlpEndpoint.isEmpty()
                && isOtlpCollectorReachable(otlpEndpoint);
        if (metricsExportable) {
            log.info("[Observability] 指标 OTLP 导出器已启用：{}", otlpEndpoint);
            meterBuilder.registerMetricReader(PeriodicMetricReader.builder(
                            OtlpHttpMetricExporter.builder()
                                    .setEndpoint(otlpEndpoint + "/v1/metrics")
                                    .setTimeout(Duration.ofSeconds(10))
                                    .build())
                    .setInterval(Duration.ofSeconds(15))
                    .build());
        } else {
            log.info("[Observability] 指标 OTLP 导出器未启用（端点不可达或显式关闭），指标仅本地采集、不对外暴露");
        }

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(builder.build())
                .setMeterProvider(meterBuilder.build())
                .buildAndRegisterGlobal();
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
     * 归一化 OTLP 端点：去掉结尾的 /v1/traces、/v1/metrics、/v1/logs 等信号路径，
     * 使 span 与 metric 导出器都能基于该基础地址追加各自路径，避免重复拼接导致 404。
     *
     * @param endpoint 原始端点（可能为 null）
     * @return 基础地址
     */
    private static String normalizeOtlpEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return endpoint;
        }
        String e = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        for (String suffix : new String[]{"/v1/traces", "/v1/metrics", "/v1/logs"}) {
            if (e.endsWith(suffix)) {
                e = e.substring(0, e.length() - suffix.length());
                break;
            }
        }
        return e;
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
     * 构建 GA 原生 OpenTelemetry 链路追踪 Middleware。
     *
     * <p>复用 yunxi 在 {@link #openTelemetry()} 中注册为全局实例的 OpenTelemetry SDK；
     * {@link OtelTracingMiddleware} 会读取该全局 SDK，产出 invoke_agent / chat / execute_tool
     * 三段 span（带 gen_ai 语义与 Reactor 上下文透传），是此前自研 {@code ReActSpanMiddleware}
     * 的超集，故直接采用框架原生实现，不再自维护一套。
     *
     * @param openTelemetry OpenTelemetry 实例（由 {@link #openTelemetry()} 提供，确保 SDK 已先行初始化）
     * @return 链路追踪中间件
     */
    @Bean
    public OtelTracingMiddleware otelTracingMiddleware(OpenTelemetry openTelemetry) {
        return new OtelTracingMiddleware();
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
