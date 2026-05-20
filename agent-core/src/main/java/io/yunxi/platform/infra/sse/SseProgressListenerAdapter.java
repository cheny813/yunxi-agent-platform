package io.yunxi.platform.infra.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SSE 进度监听器适配器
 *
 * <p>
 * 将 ProgressListener 接口适配到 SSE 推送
 * </p>
 *
 * @param <T> 结果类型
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class SseProgressListenerAdapter {

    /** SSE Emitter 管理器 */
    @Autowired
    private SseEmitterManager emitterManager;

    /**
     * 创建一个进度监听器
     *
     * @param sessionId SSE 会话ID
     * @param <T>       结果类型
     * @return 进度监听器
     */
    public <T> ProgressListener<T> create(String sessionId) {
        return new ProgressListener<T>() {
            @Override
            public void onStart(String taskId, String taskName) {
                TaskProgressEvent event = TaskProgressEvent.start(taskId, taskName);
                emitterManager.sendEvent(sessionId, "progress", event);
            }

            @Override
            public void onProgress(String taskId, int current, int total, String message) {
                TaskProgressEvent event = TaskProgressEvent.progress(taskId, current, total, message);
                emitterManager.sendEvent(sessionId, "progress", event);
            }

            @Override
            public void onPhase(String taskId, String phase, int phaseIndex, int totalPhases) {
                TaskProgressEvent event = TaskProgressEvent.phase(taskId, phase, phaseIndex, totalPhases);
                emitterManager.sendEvent(sessionId, "progress", event);
            }

            @Override
            public void onComplete(String taskId, T result) {
                TaskProgressEvent event = TaskProgressEvent.complete(taskId, result);
                emitterManager.sendEvent(sessionId, "progress", event);
                emitterManager.sendEvent(sessionId, "complete", event);
            }

            @Override
            public void onError(String taskId, Throwable error) {
                TaskProgressEvent event = TaskProgressEvent.error(taskId, error.getMessage());
                emitterManager.sendEvent(sessionId, "progress", event);
                emitterManager.sendEvent(sessionId, "error", event);
            }
        };
    }

    /**
     * 创建一个带自动完成功能的进度监听器
     *
     * @param sessionId SSE 会话ID
     * @param <T>       结果类型
     * @return 进度监听器
     */
    public <T> ProgressListener<T> createWithAutoComplete(String sessionId) {
        return new ProgressListener<T>() {
            @Override
            public void onStart(String taskId, String taskName) {
                TaskProgressEvent event = TaskProgressEvent.start(taskId, taskName);
                emitterManager.sendEvent(sessionId, "progress", event);
            }

            @Override
            public void onProgress(String taskId, int current, int total, String message) {
                TaskProgressEvent event = TaskProgressEvent.progress(taskId, current, total, message);
                emitterManager.sendEvent(sessionId, "progress", event);
            }

            @Override
            public void onPhase(String taskId, String phase, int phaseIndex, int totalPhases) {
                TaskProgressEvent event = TaskProgressEvent.phase(taskId, phase, phaseIndex, totalPhases);
                emitterManager.sendEvent(sessionId, "progress", event);
            }

            @Override
            public void onComplete(String taskId, T result) {
                TaskProgressEvent event = TaskProgressEvent.complete(taskId, result);
                emitterManager.sendEvent(sessionId, "progress", event);
                emitterManager.sendEvent(sessionId, "complete", event);
                // 自动完成 SSE 连接
                emitterManager.complete(sessionId);
            }

            @Override
            public void onError(String taskId, Throwable error) {
                TaskProgressEvent event = TaskProgressEvent.error(taskId, error.getMessage());
                emitterManager.sendEvent(sessionId, "progress", event);
                emitterManager.sendEvent(sessionId, "error", event);
                // 发生错误时完成 SSE 连接
                emitterManager.complete(sessionId);
            }
        };
    }
}
