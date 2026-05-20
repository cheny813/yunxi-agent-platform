package io.yunxi.platform.infra.sse;

/**
 * 任务进度监听器接口
 *
 * <p>
 * 用于异步任务执行过程中推送进度更新
 * </p>
 *
 * @param <T> 结果类型
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface ProgressListener<T> {

    /**
     * 任务开始
     *
     * @param taskId   任务ID
     * @param taskName 任务名称
     */
    default void onStart(String taskId, String taskName) {
    }

    /**
     * 进度更新
     *
     * @param taskId    任务ID
     * @param current   当前进度
     * @param total     总数
     * @param message   进度消息
     */
    default void onProgress(String taskId, int current, int total, String message) {
    }

    /**
     * 阶段变更
     *
     * @param taskId     任务ID
     * @param phase      阶段名称
     * @param phaseIndex 阶段索引
     * @param totalPhases 总阶段数
     */
    default void onPhase(String taskId, String phase, int phaseIndex, int totalPhases) {
    }

    /**
     * 任务完成
     *
     * @param taskId 任务ID
     * @param result 任务结果
     */
    default void onComplete(String taskId, T result) {
    }

    /**
     * 任务失败
     *
     * @param taskId 任务ID
     * @param error  错误信息
     */
    default void onError(String taskId, Throwable error) {
    }
}
