package io.yunxi.platform.gateway;

/**
 * 任务进度监听器接口
 * <p>用于异步任务执行过程中推送进度更新。</p>
 *
 * @param <T> 结果类型
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public interface ProgressListener<T> {

    /** 任务开始时的回调。
     * @param taskId   任务唯一标识
     * @param taskName 任务名称
     */
    default void onStart(String taskId, String taskName) {}

    /** 任务进度更新的回调。
     * @param taskId   任务唯一标识
     * @param current  当前已完成数量
     * @param total    总数量
     * @param message  进度描述信息
     */
    default void onProgress(String taskId, int current, int total, String message) {}

    /** 任务进入新阶段的回调。
     * @param taskId       任务唯一标识
     * @param phase        阶段名称
     * @param phaseIndex   当前阶段索引（从 0 开始）
     * @param totalPhases  阶段总数
     */
    default void onPhase(String taskId, String phase, int phaseIndex, int totalPhases) {}

    /** 任务成功完成时的回调。
     * @param taskId 任务唯一标识
     * @param result 任务结果（泛型 T）
     */
    default void onComplete(String taskId, T result) {}

    /** 任务执行出错时的回调。
     * @param taskId 任务唯一标识
     * @param error  抛出的异常
     */
    default void onError(String taskId, Throwable error) {}
}
