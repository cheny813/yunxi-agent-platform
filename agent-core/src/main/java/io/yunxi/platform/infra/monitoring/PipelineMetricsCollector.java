package io.yunxi.platform.infra.monitoring;

import io.yunxi.platform.shared.dto.PipelineExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PipelineMetricsCollector 流水线指标收集器
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class PipelineMetricsCollector {

    /**
     * 指标存储
     */
    private final Map<String, PipelineMetrics> metricsStore = new ConcurrentHashMap<>();

    /**
     * 记录执行指标
     *
     * @param execution 流水线执行记录
     */
    public void recordExecution(PipelineExecution execution) {
        metricsStore.put(execution.getExecutionId(), new PipelineMetrics(execution));
    }

    /**
     * 获取指标
     *
     * @param executionId 执行ID
     * @return 流水线指标
     */
    public PipelineMetrics getMetrics(String executionId) {
        return metricsStore.get(executionId);
    }

    /**
     * 获取所有指标
     *
     * @return 所有流水线指标映射
     */
    public Map<String, PipelineMetrics> getAllMetrics() {
        return metricsStore;
    }

    /**
     * PipelineMetrics 流水线指标
     */
    public static class PipelineMetrics {
        /** 执行ID */
        private final String executionId;
        /** 总耗时（毫秒） */
        private final long totalDurationMs;
        /** 各阶段耗时 */
        private final Map<String, Long> stageDurations;
        /** 执行状态 */
        private final String status;

        /**
         * 构造函数
         *
         * @param execution 流水线执行记录
         */
        public PipelineMetrics(PipelineExecution execution) {
            this.executionId = execution.getExecutionId();
            this.totalDurationMs = execution.getStages().stream()
                    .mapToLong(PipelineExecution.PipelineStage::getDurationMs)
                    .sum();
            this.stageDurations = new ConcurrentHashMap<>();
            for (PipelineExecution.PipelineStage stage : execution.getStages()) {
                stageDurations.put(stage.getName(), stage.getDurationMs());
            }
            this.status = execution.getStages().isEmpty() ? "UNKNOWN"
                    : execution.getStages().get(execution.getStages().size() - 1).getStatus();
        }

        public String getExecutionId() {
            return executionId;
        }

        public long getTotalDurationMs() {
            return totalDurationMs;
        }

        public Map<String, Long> getStageDurations() {
            return stageDurations;
        }

        public String getStatus() {
            return status;
        }
    }
}