package io.yunxi.platform.business.a2apipeline.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * PipelineEvent 流水线事件
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Getter
public class PipelineEvent extends ApplicationEvent {

    /**
     * 事件类型
     */
    private final EventType eventType;

    /**
     * 实例ID
     */
    private final String instanceId;

    /**
     * 事件数据
     */
    private final Object data;

    /**
     * 事件类型枚举
     */
    public enum EventType {
        /** 流水线已启动 */
        STARTED,
        /** 阶段已启动 */
        STAGE_STARTED,
        /** 阶段已完成 */
        STAGE_COMPLETED,
        /** 阶段执行失败 */
        STAGE_FAILED,
        /** 需要人工审批 */
        APPROVAL_REQUIRED,
        /** 流水线已完成 */
        COMPLETED,
        /** 流水线执行失败 */
        FAILED,
        /** 流水线已取消 */
        CANCELLED
    }

    /**
     * 创建流水线事件
     *
     * @param source     事件源对象
     * @param eventType  事件类型
     * @param instanceId 实例 ID
     * @param data       事件附加数据
     */
    public PipelineEvent(Object source, EventType eventType, String instanceId, Object data) {
        super(source);
        this.eventType = eventType;
        this.instanceId = instanceId;
        this.data = data;
    }
}