package io.yunxi.platform.business.a2apipeline.model;

import io.yunxi.platform.shared.dto.StageResult;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * PipelineInstance 流水线实例
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class PipelineInstance {

    /**
     * 实例ID
     */
    private String instanceId;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 当前状态
     */
    private PipelineState currentState;

    /**
     * 开始时间
     */
    private Instant startTime;

    /**
     * 结束时间
     */
    private Instant endTime;

    /**
     * 执行耗时(毫秒)
     */
    private long durationMs;

    /**
     * 阶段结果列表
     */
    private List<StageResult> stages;

    /**
     * 执行日志列表
     */
    private List<ExecutionLog> logs;

    /**
     * 流水线状态枚举
     */
    public enum PipelineState {
        /** 等待中 */
        PENDING,
        /** 运行中 */
        RUNNING,
        /** 等待审批 */
        WAITING_APPROVAL,
        /** 已完成 */
        COMPLETED,
        /** 已失败 */
        FAILED,
        /** 已取消 */
        CANCELLED
    }

    /**
     * 创建新实例
     *
     * @param instanceId 实例 ID
     * @param taskId     任务 ID
     * @return 新的流水线实例
     */
    public static PipelineInstance create(String instanceId, String taskId) {
        return PipelineInstance.builder()
                .instanceId(instanceId)
                .taskId(taskId)
                .currentState(PipelineState.PENDING)
                .startTime(Instant.now())
                .build();
    }
}