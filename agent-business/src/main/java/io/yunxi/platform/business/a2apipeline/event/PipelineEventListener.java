package io.yunxi.platform.business.a2apipeline.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * PipelineEventListener 流水线事件监听器
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class PipelineEventListener {

    /**
     * 异步监听流水线事件，根据事件类型记录不同级别的日志
     *
     * @param event 流水线事件
     */
    @Async
    @EventListener
    public void onPipelineEvent(PipelineEvent event) {
        switch (event.getEventType()) {
            case STARTED -> log.info("Pipeline started: instanceId={}", event.getInstanceId());
            case STAGE_STARTED -> log.info("Stage started: instanceId={}", event.getInstanceId());
            case STAGE_COMPLETED -> log.info("Stage completed: instanceId={}", event.getInstanceId());
            case STAGE_FAILED -> log.warn("Stage failed: instanceId={}", event.getInstanceId());
            case COMPLETED -> log.info("Pipeline completed: instanceId={}", event.getInstanceId());
            case FAILED -> log.error("Pipeline failed: instanceId={}", event.getInstanceId());
            default -> log.debug("Pipeline event: type={}, instanceId={}", event.getEventType(), event.getInstanceId());
        }
    }
}