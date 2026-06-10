package io.yunxi.platform.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;

import java.util.List;

/**
 * LLM 调用指标收集器
 * <p>通过 OpenTelemetry Metrics API 记录 LLM 关键指标。</p>
 *
 * @see OpenTelemetryTracer
 * @see ObservabilityAutoConfiguration
 */
public class LlmMetrics {

    private static final AttributeKey<String> ATTR_MODEL = AttributeKey.stringKey("llm.model");
    private static final AttributeKey<String> ATTR_PROVIDER = AttributeKey.stringKey("llm.provider");
    private static final AttributeKey<String> ATTR_TOKEN_TYPE = AttributeKey.stringKey("llm.token.type");

    private final LongCounter tokenCounter;
    private final DoubleHistogram llmDuration;

    public LlmMetrics(Meter meter) {
        this.tokenCounter = meter.counterBuilder("llm.token.total")
                .setDescription("Total LLM tokens consumed").setUnit("{token}").build();
        this.llmDuration = meter.histogramBuilder("llm.duration")
                .setDescription("LLM invocation duration").setUnit("ms")
                .setExplicitBucketBoundariesAdvice(List.of(500d, 1000d, 2000d, 5000d, 10000d, 30000d)).build();
    }

    public void recordTokenUsage(String model, String provider, int promptTokens, int completionTokens) {
        Attributes baseAttrs = Attributes.of(ATTR_MODEL, model, ATTR_PROVIDER, provider);
        tokenCounter.add(promptTokens, baseAttrs.toBuilder().put(ATTR_TOKEN_TYPE, "prompt").build());
        tokenCounter.add(completionTokens, baseAttrs.toBuilder().put(ATTR_TOKEN_TYPE, "completion").build());
    }

    public void recordDuration(String model, String provider, double durationMs) {
        llmDuration.record(durationMs, Attributes.of(ATTR_MODEL, model, ATTR_PROVIDER, provider));
    }
}
