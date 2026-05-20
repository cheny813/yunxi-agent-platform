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
 * text2sql 鎸囨爣服务
 *
 * <p>
 * 提供鍚勯樁娈垫€ц兘鎸囨爣监控銆丩LM 调用统计銆丼QL 鍑嗙‘鐜囩粺璁＄瓑功能銆? * </p>
 *
 */
@Slf4j
@Service
public class Text2SqlMetricsService {

    private final MeterRegistry meterRegistry;

    /** 是否鍚敤鎸囨爣鏀堕泦 */
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

    @Autowired
    public Text2SqlMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        initMetrics();
    }

    private void initMetrics() {
        if (!metricsEnabled) {
            return;
        }

        // 鍚勯樁娈佃€楁椂 Timer
        schemaTimer = Timer.builder("text2sql.schema")
                .description("Schema 鐢熸垚鑰楁椂")
                .register(meterRegistry);

        retrievalTimer = Timer.builder("text2sql.retrieval")
                .description("鍒楁绱㈣€楁椂")
                .register(meterRegistry);

        generationTimer = Timer.builder("text2sql.generation")
                .description("SQL 鐢熸垚鑰楁椂")
                .register(meterRegistry);

        alignmentTimer = Timer.builder("text2sql.alignment")
                .description("SQL 瀵归綈鑰楁椂")
                .register(meterRegistry);

        votingTimer = Timer.builder("text2sql.voting")
                .description("SQL 鎶曠エ鑰楁椂")
                .register(meterRegistry);

        totalTimer = Timer.builder("text2sql.total")
                .description("鎬绘墽琛岃€楁椂")
                .register(meterRegistry);

        // 璁℃暟 Counter
        successCounter = Counter.builder("text2sql.success")
                .description("SQL 鐢熸垚成功娆℃暟")
                .register(meterRegistry);

        failureCounter = Counter.builder("text2sql.failure")
                .description("SQL 鐢熸垚失败娆℃暟")
                .register(meterRegistry);

        llmCallCounter = Counter.builder("text2sql.llm.calls")
                .description("LLM 调用娆℃暟")
                .register(meterRegistry);
    }

    /**
     * 记录 Schema 鐢熸垚鑰楁椂
     */
    public void recordSchemaTime(long durationMs) {
        if (schemaTimer != null) {
            schemaTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录鍒楁绱㈣€楁椂
     */
    public void recordRetrievalTime(long durationMs) {
        if (retrievalTimer != null) {
            retrievalTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录 SQL 鐢熸垚鑰楁椂
     */
    public void recordGenerationTime(long durationMs) {
        if (generationTimer != null) {
            generationTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录 SQL 瀵归綈鑰楁椂
     */
    public void recordAlignmentTime(long durationMs) {
        if (alignmentTimer != null) {
            alignmentTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录 SQL 鎶曠エ鑰楁椂
     */
    public void recordVotingTime(long durationMs) {
        if (votingTimer != null) {
            votingTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录鎬绘墽琛岃€楁椂
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
     * 获取成功鐜?     */
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
     * 获取鎬昏皟鐢ㄦ鏁?     */
    public long getTotalCalls() {
        return successCounter != null ? (long) successCounter.count() : 0
                + (failureCounter != null ? (long) failureCounter.count() : 0);
    }
}
