package io.yunxi.platform.tracing.metrics;

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

        private final MeterRegistry meterRegistry;
        private final AtomicInteger activePipelines = new AtomicInteger(0);
        private final Map<String, Timer> stageTimers = new ConcurrentHashMap<>();
        private final Map<String, Timer> agentResponseTimers = new ConcurrentHashMap<>();

        public void initializeMetrics() {
                Gauge.builder("a2a.pipeline.active", activePipelines, AtomicInteger::get)
                                .description("当前执行中的流水线数量").register(meterRegistry);
                Gauge.builder("a2a.pipeline.queue.size", this, s -> getQueueSize())
                                .description("等待执行的流水线队列长度").register(meterRegistry);
        }

        public void recordPipelineStart(String pipelineId, String pipelineType) {
                activePipelines.incrementAndGet();
                Counter.builder("a2a.pipeline.started").tag("pipeline_type", pipelineType)
                                .register(meterRegistry).increment();
                log.info("[Pipeline-Metrics] 流水线启动: id={}, type={}", pipelineId, pipelineType);
        }

        public void recordPipelineComplete(PipelineExecution execution, boolean success) {
                activePipelines.decrementAndGet();
                Counter.builder("a2a.pipeline.completed").tag("pipeline_type", "CODE_FIX")
                                .tag("status", success ? "success" : "failed").register(meterRegistry).increment();
                log.info("[Pipeline-Metrics] 流水线完成: id={}, success={}", execution.getInstanceId(), success);
        }

        public void recordStageExecution(StageResult stage, long durationMs, boolean success) {
                Counter.builder("a2a.stage.execution").tag("stage_name", stage.getStageName())
                                .tag("status", success ? "success" : "failed").register(meterRegistry).increment();
        }

        private double getQueueSize() {
                return 0;
        }
}
