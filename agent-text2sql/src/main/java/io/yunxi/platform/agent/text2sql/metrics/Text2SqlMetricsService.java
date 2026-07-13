package io.yunxi.platform.agent.text2sql.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * text2sql 指标服务
 *
 * <p>
 * 提供各阶段性能指标监控、LLM 调用统计、SQL 正确率统计等功能。
 * </p>
 *
 */
@Slf4j
@Service
public class Text2SqlMetricsService {

    private final MeterRegistry meterRegistry;

    /** 是否启用指标收集 */
    @Value("${text2sql.metrics.enabled:true}")
    private boolean metricsEnabled;

    private Timer schemaTimer;
    private Timer retrievalTimer;
    private Timer generationTimer;
    private Timer alignmentTimer;
    private Timer votingTimer;
    private Timer totalTimer;

    private Counter successCounter;
    private Counter failureCounter;
    private Counter llmCallCounter;

    /**
     * 构造指标服务并初始化各阶段监控指标。
     *
     * @param meterRegistry Micrometer 指标注册中心，用于注册与上报 Timer / Counter
     */
    @Autowired
    public Text2SqlMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        initMetrics();
    }

    /**
     * 初始化各阶段监控指标。
     * <p>
     * 当指标收集开关（{@code text2sql.metrics.enabled}）关闭时直接返回，不注册任何 Meter。
     * 否则在 {@link MeterRegistry} 上注册各阶段耗时 {@code Timer}（Schema / 检索 / 生成 /
     * 对齐 / 投票 / 总耗时）以及成功、失败、LLM 调用次数 {@code Counter}，供后续
     * {@code recordXxx} 方法写入观测数据。
     * </p>
     */
    private void initMetrics() {
        if (!metricsEnabled) {
            return;
        }

        // 各阶段耗时 Timer
        schemaTimer = Timer.builder("text2sql.schema")
                .description("Schema 生成耗时")
                .register(meterRegistry);

        retrievalTimer = Timer.builder("text2sql.retrieval")
                .description("检索耗时")
                .register(meterRegistry);

        generationTimer = Timer.builder("text2sql.generation")
                .description("SQL 生成耗时")
                .register(meterRegistry);

        alignmentTimer = Timer.builder("text2sql.alignment")
                .description("SQL 对齐耗时")
                .register(meterRegistry);

        votingTimer = Timer.builder("text2sql.voting")
                .description("SQL 投票耗时")
                .register(meterRegistry);

        totalTimer = Timer.builder("text2sql.total")
                .description("总执行耗时")
                .register(meterRegistry);

        // 计数 Counter
        successCounter = Counter.builder("text2sql.success")
                .description("SQL 生成成功次数")
                .register(meterRegistry);

        failureCounter = Counter.builder("text2sql.failure")
                .description("SQL 生成失败次数")
                .register(meterRegistry);

        llmCallCounter = Counter.builder("text2sql.llm.calls")
                .description("LLM 调用次数")
                .register(meterRegistry);
    }

    /**
     * 记录 Schema 生成耗时
     */
    public void recordSchemaTime(long durationMs) {
        if (schemaTimer != null) {
            schemaTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录检索耗时
     */
    public void recordRetrievalTime(long durationMs) {
        if (retrievalTimer != null) {
            retrievalTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录 SQL 生成耗时
     */
    public void recordGenerationTime(long durationMs) {
        if (generationTimer != null) {
            generationTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录 SQL 对齐耗时
     */
    public void recordAlignmentTime(long durationMs) {
        if (alignmentTimer != null) {
            alignmentTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录 SQL 投票耗时
     */
    public void recordVotingTime(long durationMs) {
        if (votingTimer != null) {
            votingTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录总执行耗时
     */
    public void recordTotalTime(long durationMs) {
        if (totalTimer != null) {
            totalTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录成功
     */
    public void recordSuccess() {
        if (successCounter != null) {
            successCounter.increment();
        }
    }

    /**
     * 记录失败
     */
    public void recordFailure() {
        if (failureCounter != null) {
            failureCounter.increment();
        }
    }

    /**
     * 记录 LLM 调用
     */
    public void recordLlmCall() {
        if (llmCallCounter != null) {
            llmCallCounter.increment();
        }
    }

    /**
     * 获取成功率
     */
    public double getSuccessRate() {
        if (successCounter == null || failureCounter == null) {
            return 0.0;
        }
        double success = successCounter.count();
        double failure = failureCounter.count();
        double total = success + failure;
        return total > 0 ? success / total : 0.0;
    }

    /**
     * 获取总调用次数
     */
    public long getTotalCalls() {
        return successCounter != null ? (long) successCounter.count() : 0
                + (failureCounter != null ? (long) failureCounter.count() : 0);
    }
}
