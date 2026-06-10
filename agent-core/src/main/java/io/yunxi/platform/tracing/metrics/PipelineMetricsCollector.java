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
 * @version 1.0.0
 */
@Slf4j
@Component
public class PipelineMetricsCollector {

    private final Map<String, PipelineMetrics> metricsStore = new ConcurrentHashMap<>();

    public void recordExecution(PipelineExecution execution) {
        metricsStore.put(execution.getExecutionId(), new PipelineMetrics(execution));
    }

    public PipelineMetrics getMetrics(String executionId) {
        return metricsStore.get(executionId);
    }

    public Map<String, PipelineMetrics> getAllMetrics() {
        return metricsStore;
    }

    public static class PipelineMetrics {
        private final String executionId;
        private final long totalDurationMs;
        private final Map<String, Long> stageDurations;
        private final String status;

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
