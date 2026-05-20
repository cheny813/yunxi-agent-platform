package io.yunxi.platform.infra.sse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 任务进度事件
 *
 * <p>
 * 用于 SSE 推送的进度事件数据结构
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskProgressEvent {

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 事件类型
     */
    private EventType type;

    /**
     * 当前阶段
     */
    private String phase;

    /**
     * 当前进度
     */
    private Integer current;

    /**
     * 总数
     */
    private Integer total;

    /**
     * 进度百分比 (0-100)
     */
    private Integer percentage;

    /**
     * 消息
     */
    private String message;

    /**
     * 结果数据（完成时）
     */
    private Object result;

    /**
     * 错误信息（失败时）
     */
    private String error;

    /**
     * 时间戳
     */
    @Builder.Default
    private Long timestamp = Instant.now().toEpochMilli();

    /**
     * 事件类型枚举
     */
    public enum EventType {
        /**
         * 任务开始
         */
        START,

        /**
         * 进度更新
         */
        PROGRESS,

        /**
         * 阶段变更
         */
        PHASE,

        /**
         * 任务完成
         */
        COMPLETE,

        /**
         * 任务失败
         */
        ERROR
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建任务开始事件
     *
     * @param taskId  任务 ID
     * @param message 消息
     * @return 任务开始事件
     */
    public static TaskProgressEvent start(String taskId, String message) {
        return TaskProgressEvent.builder()
                .taskId(taskId)
                .type(EventType.START)
                .message(message)
                .percentage(0)
                .build();
    }

    /**
     * 创建进度更新事件
     *
     * @param taskId  任务 ID
     * @param current 当前进度
     * @param total   总数
     * @param message 进度消息
     * @return 进度更新事件
     */
    public static TaskProgressEvent progress(String taskId, int current, int total, String message) {
        int percentage = total > 0 ? (int) ((current * 100.0) / total) : 0;
        return TaskProgressEvent.builder()
                .taskId(taskId)
                .type(EventType.PROGRESS)
                .current(current)
                .total(total)
                .percentage(percentage)
                .message(message)
                .build();
    }

    /**
     * 创建阶段变更事件
     *
     * @param taskId       任务 ID
     * @param phase        阶段名称
     * @param phaseIndex   阶段索引
     * @param totalPhases  总阶段数
     * @return 阶段变更事件
     */
    public static TaskProgressEvent phase(String taskId, String phase, int phaseIndex, int totalPhases) {
        int percentage = totalPhases > 0 ? (int) ((phaseIndex * 100.0) / totalPhases) : 0;
        return TaskProgressEvent.builder()
                .taskId(taskId)
                .type(EventType.PHASE)
                .phase(phase)
                .current(phaseIndex)
                .total(totalPhases)
                .percentage(percentage)
                .message("进入阶段: " + phase)
                .build();
    }

    /**
     * 创建任务完成事件
     *
     * @param taskId 任务 ID
     * @param result 任务结果
     * @return 任务完成事件
     */
    public static TaskProgressEvent complete(String taskId, Object result) {
        return TaskProgressEvent.builder()
                .taskId(taskId)
                .type(EventType.COMPLETE)
                .percentage(100)
                .result(result)
                .message("任务完成")
                .build();
    }

    /**
     * 创建任务失败事件
     *
     * @param taskId       任务 ID
     * @param errorMessage 错误信息
     * @return 任务失败事件
     */
    public static TaskProgressEvent error(String taskId, String errorMessage) {
        return TaskProgressEvent.builder()
                .taskId(taskId)
                .type(EventType.ERROR)
                .error(errorMessage)
                .message("任务失败: " + errorMessage)
                .build();
    }
}
