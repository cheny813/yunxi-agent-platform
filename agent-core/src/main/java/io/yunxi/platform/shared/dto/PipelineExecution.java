package io.yunxi.platform.shared.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * PipelineExecution 流水线执行记录
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class PipelineExecution {

    /**
     * 执行ID
     */
    private String executionId;

    /**
     * 实例ID
     */
    private String instanceId;

    /**
     * 阶段列表
     */
    @Builder.Default
    private List<PipelineStage> stages = new ArrayList<>();

    /**
     * 当前阶段索引
     */
    private int currentStageIndex;

    /**
     * 开始时间
     */
    private long startTimeMs;

    /**
     * PipelineStage 流水线阶段
     */
    @Data
    @Builder
    public static class PipelineStage {
        private String name;
        private String status;
        private long startTimeMs;
        private long endTimeMs;
        private long durationMs;
        private String errorMessage;
    }
}
