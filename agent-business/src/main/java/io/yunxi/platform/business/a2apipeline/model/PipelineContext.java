package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * PipelineContext 流水线上下文
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class PipelineContext {

    /**
     * 实例ID
     */
    private String instanceId;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 请求数据
     */
    private CodeFixRequest request;

    /**
     * 上下文数据
     */
    private Map<String, Object> contextData;

    /**
     * 当前阶段
     */
    private String currentStage;

    /**
     * 当前迭代次数
     */
    private int currentIteration;

    /**
     * 创建上下文
     *
     * @param instanceId 实例 ID
     * @param taskId     任务 ID
     * @param request    代码修复请求
     * @return 新的流水线上下文实例
     */
    public static PipelineContext create(String instanceId, String taskId, CodeFixRequest request) {
        return PipelineContext.builder()
                .instanceId(instanceId)
                .taskId(taskId)
                .request(request)
                .currentStage("START")
                .currentIteration(0)
                .build();
    }
}