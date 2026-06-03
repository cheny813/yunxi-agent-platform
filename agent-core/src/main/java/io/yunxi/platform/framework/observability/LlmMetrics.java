package io.yunxi.platform.framework.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;

import java.util.List;

/**
 * LLM 调用指标收集器。
 * <p>
 * 通过 OpenTelemetry Metrics API 记录 LLM 特有的关键指标：
 * Token 消耗（按 prompt/completion 维度细分）和 LLM 调用耗时。
 * 所有指标默认携带 {@code llm.model} 和 {@code llm.provider} 标签，
 * 支持按模型/提供商维度进行聚合查询。
 * </p>
 *
 * <h3>指标列表</h3>
 * <table border="1">
 * <caption>导出的 Metrics</caption>
 * <tr>
 * <th>指标名称</th>
 * <th>类型</th>
 * <th>标签</th>
 * <th>说明</th>
 * </tr>
 * <tr>
 * <td>llm.token.total</td>
 * <td>Counter</td>
 * <td>model, provider, token.type</td>
 * <td>累计 Token 消耗</td>
 * </tr>
 * <tr>
 * <td>llm.duration</td>
 * <td>Histogram</td>
 * <td>model, provider</td>
 * <td>LLM 调用耗时分布（ms）</td>
 * </tr>
 * </table>
 *
 * <h3>调用方式</h3>
 *
 * <pre>{@code
 * llmMetrics.recordTokenUsage("qwen-plus", "dashscope", 150, 320);
 * llmMetrics.recordDuration("qwen-plus", "dashscope", 2340.5);
 * }</pre>
 *
 * @see OpenTelemetryTracer
 * @see ObservabilityAutoConfiguration
 */
public class LlmMetrics {

        /** Model 名称标签键 */
        private static final AttributeKey<String> ATTR_MODEL = AttributeKey.stringKey("llm.model");

        /** Provider 名称标签键 */
        private static final AttributeKey<String> ATTR_PROVIDER = AttributeKey.stringKey("llm.provider");

        /** Token 类型标签键（prompt / completion） */
        private static final AttributeKey<String> ATTR_TOKEN_TYPE = AttributeKey.stringKey("llm.token.type");

        /** Token 消耗计数器 */
        private final LongCounter tokenCounter;

        /** LLM 调用耗时直方图 */
        private final DoubleHistogram llmDuration;

        /**
         * 构造 LLM 指标收集器。
         * <p>
         * 初始化两个指标：
         * <ul>
         * <li>{@code llm.token.total} — 累计 Token 消耗，按 prompt/completion 细分</li>
         * <li>{@code llm.duration} — 调用耗时直方图，桶边界为
         * 500ms / 1s / 2s / 5s / 10s / 30s</li>
         * </ul>
         * </p>
         *
         * @param meter OpenTelemetry Meter 实例
         */
        public LlmMetrics(Meter meter) {
                this.tokenCounter = meter.counterBuilder("llm.token.total")
                                .setDescription("Total LLM tokens consumed")
                                .setUnit("{token}")
                                .build();

                this.llmDuration = meter.histogramBuilder("llm.duration")
                                .setDescription("LLM invocation duration")
                                .setUnit("ms")
                                .setExplicitBucketBoundariesAdvice(
                                                List.of(500d, 1000d, 2000d, 5000d, 10000d, 30000d))
                                .build();
        }

        /**
         * 记录 Token 消耗。
         * <p>
         * Prompt 和 Completion Token 分别作为独立的计数器记录，
         * 通过 {@code llm.token.type} 标签区分：
         * <ul>
         * <li>prompt — 输入 Token 数</li>
         * <li>completion — 输出 Token 数</li>
         * </ul>
         * 所有数据点自动携带 {@code llm.model} 和 {@code llm.provider} 标签。
         * </p>
         *
         * @param model            模型名称（如 "qwen-plus"、"gpt-4"）
         * @param provider         提供商名称（如 "dashscope"、"openai"）
         * @param promptTokens     prompt 消耗的 Token 数
         * @param completionTokens completion 消耗的 Token 数
         */
        public void recordTokenUsage(String model, String provider,
                        int promptTokens, int completionTokens) {
                Attributes baseAttrs = Attributes.of(ATTR_MODEL, model, ATTR_PROVIDER, provider);

                tokenCounter.add(promptTokens,
                                baseAttrs.toBuilder().put(ATTR_TOKEN_TYPE, "prompt").build());
                tokenCounter.add(completionTokens,
                                baseAttrs.toBuilder().put(ATTR_TOKEN_TYPE, "completion").build());
        }

        /**
         * 记录 LLM 调用耗时。
         * <p>
         * 耗时数据落入预定义的桶边界（500ms ~ 30s）中，
         * 可用于分析 LLM 调用的 P50/P95/P99 延迟。
         * </p>
         *
         * @param model      模型名称
         * @param provider   提供商名称
         * @param durationMs 调用耗时（毫秒）
         */
        public void recordDuration(String model, String provider, double durationMs) {
                llmDuration.record(durationMs,
                                Attributes.of(ATTR_MODEL, model, ATTR_PROVIDER, provider));
        }
}
