package io.yunxi.platform.gateway;

/**
 * 任务进度监听器接口
 * <p>用于异步任务执行过程中推送进度更新。</p>
 *
 * @param <T> 结果类型
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface ProgressListener<T> {

    default void onStart(String taskId, String taskName) {}
    default void onProgress(String taskId, int current, int total, String message) {}
    default void onPhase(String taskId, String phase, int phaseIndex, int totalPhases) {}
    default void onComplete(String taskId, T result) {}
    default void onError(String taskId, Throwable error) {}
}
