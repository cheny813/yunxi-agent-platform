package io.yunxi.platform.tracing.metrics;

import io.yunxi.platform.shared.dto.PipelineExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PipelineMetricsCollector 流水线指标收集器
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Component
public class PipelineMetricsCollector {

    private final Map<String, PipelineMetrics> metricsStore = new ConcurrentHashMap<>();

    /**
     * 记录一次流水线执行结果，提取关键指标并缓存
     *
     * @param execution 流水线执行记录
     */
    public void recordExecution(PipelineExecution execution) {
        metricsStore.put(execution.getExecutionId(), new PipelineMetrics(execution));
    }

    /**
     * 获取指定流水线的指标快照
     *
     * @param executionId 流水线执行 ID
     * @return 指标快照，未记录时返回 null
     */
    public PipelineMetrics getMetrics(String executionId) {
        return metricsStore.get(executionId);
    }

    /**
     * 获取所有已记录的流水线指标
     *
     * @return 执行 ID 到指标快照的映射
     */
    public Map<String, PipelineMetrics> getAllMetrics() {
        return metricsStore;
    }

    /**
     * 流水线指标快照，聚合单次执行的总耗时、各阶段耗时与最终状态
     */
    public static class PipelineMetrics {
        /** 流水线执行 ID */
        private final String executionId;
        /** 各阶段耗时之和（毫秒） */
        private final long totalDurationMs;
        /** 阶段名到耗时（毫秒）的映射 */
        private final Map<String, Long> stageDurations;
        /** 最终状态（取最后一个阶段的状态，无阶段则为 UNKNOWN） */
        private final String status;

        /**
         * 根据流水线执行记录构建指标快照
         *
         * @param execution 流水线执行记录
         */
        public PipelineMetrics(PipelineExecution execution) {
            this.executionId = execution.getExecutionId();
            this.totalDurationMs = execution.getStages().stream()
                    .mapToLong(PipelineExecution.PipelineStage::getDurationMs).sum();
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
