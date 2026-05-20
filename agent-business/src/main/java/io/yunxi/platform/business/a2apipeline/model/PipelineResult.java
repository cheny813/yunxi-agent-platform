package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * PipelineResult 流水线结果
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class PipelineResult {

    /**
     * 实例ID
     */
    private String instanceId;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 最终状态
     */
    private PipelineInstance.PipelineState state;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 结果数据
     */
    private Map<String, Object> resultData;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 总耗时(毫秒)
     */
    private long totalDurationMs;

    /**
     * 创建成功结果
     *
     * @param instanceId 实例 ID
     * @param taskId     任务 ID
     * @return 成功的流水线结果实例
     */
    public static PipelineResult success(String instanceId, String taskId) {
        return PipelineResult.builder()
                .instanceId(instanceId)
                .taskId(taskId)
                .state(PipelineInstance.PipelineState.COMPLETED)
                .success(true)
                .build();
    }

    /**
     * 创建失败结果
     *
     * @param instanceId   实例 ID
     * @param taskId       任务 ID
     * @param errorMessage 错误消息
     * @return 失败的流水线结果实例
     */
    public static PipelineResult failure(String instanceId, String taskId, String errorMessage) {
        return PipelineResult.builder()
                .instanceId(instanceId)
                .taskId(taskId)
                .state(PipelineInstance.PipelineState.FAILED)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}