package io.yunxi.platform.infra.monitoring;

import io.micrometer.core.instrument.*;
import io.yunxi.platform.shared.dto.PipelineExecution;
import io.yunxi.platform.shared.dto.StageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流水线指标监控服务
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineMetricsService {

    /** 指标注册表 */
    private final MeterRegistry meterRegistry;

    /** 执行中流水线计数 */
    private final AtomicInteger activePipelines = new AtomicInteger(0);

    /** 阶段执行时间记录 */
    private final Map<String, Timer> stageTimers = new ConcurrentHashMap<>();

    /** Agent响应时间记录 */
    private final Map<String, Timer> agentResponseTimers = new ConcurrentHashMap<>();

    /**
     * 初始化指标
     */
    public void initializeMetrics() {
        Gauge.builder("a2a.pipeline.active", activePipelines, AtomicInteger::get)
                .description("当前执行中的流水线数量")
                .register(meterRegistry);

        Gauge.builder("a2a.pipeline.queue.size", this, s -> getQueueSize())
                .description("等待执行的流水线队列长度")
                .register(meterRegistry);
    }

    /**
     * 记录流水线启动
     *
     * @param pipelineId   流水线ID
     * @param pipelineType 流水线类型
     */
    public void recordPipelineStart(String pipelineId, String pipelineType) {
        activePipelines.incrementAndGet();

        Counter.builder("a2a.pipeline.started")
                .tag("pipeline_type", pipelineType)
                .register(meterRegistry)
                .increment();

        log.info("[Pipeline-Metrics] 流水线启动: id={}, type={}", pipelineId, pipelineType);
    }

    /**
     * 记录流水线完成
     *
     * @param execution 流水线执行记录
     * @param success   是否成功
     */
    public void recordPipelineComplete(PipelineExecution execution, boolean success) {
        activePipelines.decrementAndGet();

        Counter.builder("a2a.pipeline.completed")
                .tag("pipeline_type", "CODE_FIX")
                .tag("status", success ? "success" : "failed")
                .register(meterRegistry)
                .increment();

        log.info("[Pipeline-Metrics] 流水线完成: id={}, success={}",
                execution.getInstanceId(), success);
    }

    /**
     * 记录阶段执行
     *
     * @param stage      阶段结果
     * @param durationMs 执行耗时（毫秒）
     * @param success    是否成功
     */
    public void recordStageExecution(StageResult stage, long durationMs, boolean success) {
        String stageName = stage.getStageName();

        Counter.builder("a2a.stage.execution")
                .tag("stage_name", stageName)
                .tag("status", success ? "success" : "failed")
                .register(meterRegistry)
                .increment();

        log.debug("[Pipeline-Metrics] 阶段执行: name={}, duration={}ms, success={}",
                stageName, durationMs, success);
    }

    /**
     * 获取队列大小
     */
    private double getQueueSize() {
        return 0;
    }
}