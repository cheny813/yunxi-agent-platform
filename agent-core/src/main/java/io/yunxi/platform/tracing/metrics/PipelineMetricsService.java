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
 * @version 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineMetricsService {

        private final MeterRegistry meterRegistry;
        private final AtomicInteger activePipelines = new AtomicInteger(0);
        private final Map<String, Timer> stageTimers = new ConcurrentHashMap<>();
        private final Map<String, Timer> agentResponseTimers = new ConcurrentHashMap<>();

        /**
         * 初始化流水线相关的 Micrometer 指标（活跃数、队列长度）
         */
        public void initializeMetrics() {
                Gauge.builder("a2a.pipeline.active", activePipelines, AtomicInteger::get)
                                .description("当前执行中的流水线数量").register(meterRegistry);
                Gauge.builder("a2a.pipeline.queue.size", this, s -> getQueueSize())
                                .description("等待执行的流水线队列长度").register(meterRegistry);
        }

        /**
         * 记录流水线启动：活跃计数 +1 并累加 started 计数器
         *
         * @param pipelineId   流水线 ID
         * @param pipelineType 流水线类型（用于指标 tag）
         */
        public void recordPipelineStart(String pipelineId, String pipelineType) {
                activePipelines.incrementAndGet();
                Counter.builder("a2a.pipeline.started").tag("pipeline_type", pipelineType)
                                .register(meterRegistry).increment();
                log.info("[Pipeline-Metrics] 流水线启动: id={}, type={}", pipelineId, pipelineType);
        }

        /**
         * 记录流水线完成：活跃计数 -1 并累加 completed 计数器
         *
         * @param execution 流水线执行记录
         * @param success   是否成功
         */
        public void recordPipelineComplete(PipelineExecution execution, boolean success) {
                activePipelines.decrementAndGet();
                Counter.builder("a2a.pipeline.completed").tag("pipeline_type", "CODE_FIX")
                                .tag("status", success ? "success" : "failed").register(meterRegistry).increment();
                log.info("[Pipeline-Metrics] 流水线完成: id={}, success={}", execution.getInstanceId(), success);
        }

        /**
         * 记录单个阶段执行：累加 stage.execution 计数器（含阶段名与状态 tag）
         *
         * @param stage       阶段结果
         * @param durationMs  阶段耗时（毫秒，当前未上报）
         * @param success     是否成功
         */
        public void recordStageExecution(StageResult stage, long durationMs, boolean success) {
                Counter.builder("a2a.stage.execution").tag("stage_name", stage.getStageName())
                                .tag("status", success ? "success" : "failed").register(meterRegistry).increment();
        }

        /**
         * 队列长度指标回调（当前实现恒为 0，预留扩展）
         *
         * @return 队列长度
         */
        private double getQueueSize() {
                return 0;
        }
}
