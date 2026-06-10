package io.yunxi.platform.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 任务进度事件
 * <p>用于 SSE 推送的进度事件数据结构。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskProgressEvent {

    private String taskId;
    private EventType type;
    private String phase;
    private Integer current;
    private Integer total;
    private Integer percentage;
    private String message;
    private Object result;
    private String error;
    @Builder.Default
    private Long timestamp = Instant.now().toEpochMilli();

    public enum EventType { START, PROGRESS, PHASE, COMPLETE, ERROR }

    public static TaskProgressEvent start(String taskId, String message) {
        return TaskProgressEvent.builder().taskId(taskId).type(EventType.START).message(message).percentage(0).build();
    }

    public static TaskProgressEvent progress(String taskId, int current, int total, String message) {
        int percentage = total > 0 ? (int) ((current * 100.0) / total) : 0;
        return TaskProgressEvent.builder().taskId(taskId).type(EventType.PROGRESS).current(current).total(total).percentage(percentage).message(message).build();
    }

    public static TaskProgressEvent phase(String taskId, String phase, int phaseIndex, int totalPhases) {
        int percentage = totalPhases > 0 ? (int) ((phaseIndex * 100.0) / totalPhases) : 0;
        return TaskProgressEvent.builder().taskId(taskId).type(EventType.PHASE).phase(phase).current(phaseIndex).total(totalPhases).percentage(percentage).message("进入阶段: " + phase).build();
    }

    public static TaskProgressEvent complete(String taskId, Object result) {
        return TaskProgressEvent.builder().taskId(taskId).type(EventType.COMPLETE).percentage(100).result(result).message("任务完成").build();
    }

    public static TaskProgressEvent error(String taskId, String errorMessage) {
        return TaskProgressEvent.builder().taskId(taskId).type(EventType.ERROR).error(errorMessage).message("任务失败: " + errorMessage).build();
    }
}
