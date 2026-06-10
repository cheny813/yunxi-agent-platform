package io.yunxi.platform.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SSE 进度监听器适配器
 * <p>将 ProgressListener 接口适配到 SSE 推送。</p>
 *
 * @param <T> 结果类型
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class SseProgressListenerAdapter {

    @Autowired
    private SseEmitterManager emitterManager;

    public <T> ProgressListener<T> create(String sessionId) {
        return new ProgressListener<T>() {
            @Override public void onStart(String taskId, String taskName) {
                emitterManager.sendEvent(sessionId, "progress", TaskProgressEvent.start(taskId, taskName));
            }
            @Override public void onProgress(String taskId, int current, int total, String message) {
                emitterManager.sendEvent(sessionId, "progress", TaskProgressEvent.progress(taskId, current, total, message));
            }
            @Override public void onPhase(String taskId, String phase, int phaseIndex, int totalPhases) {
                emitterManager.sendEvent(sessionId, "progress", TaskProgressEvent.phase(taskId, phase, phaseIndex, totalPhases));
            }
            @Override public void onComplete(String taskId, T result) {
                emitterManager.sendEvent(sessionId, "progress", TaskProgressEvent.complete(taskId, result));
                emitterManager.sendEvent(sessionId, "complete", TaskProgressEvent.complete(taskId, result));
            }
            @Override public void onError(String taskId, Throwable error) {
                emitterManager.sendEvent(sessionId, "progress", TaskProgressEvent.error(taskId, error.getMessage()));
                emitterManager.sendEvent(sessionId, "error", TaskProgressEvent.error(taskId, error.getMessage()));
            }
        };
    }

    public <T> ProgressListener<T> createWithAutoComplete(String sessionId) {
        return new ProgressListener<T>() {
            @Override public void onStart(String taskId, String taskName) {
                emitterManager.sendEvent(sessionId, "progress", TaskProgressEvent.start(taskId, taskName));
            }
            @Override public void onProgress(String taskId, int current, int total, String message) {
                emitterManager.sendEvent(sessionId, "progress", TaskProgressEvent.progress(taskId, current, total, message));
            }
            @Override public void onPhase(String taskId, String phase, int phaseIndex, int totalPhases) {
                emitterManager.sendEvent(sessionId, "progress", TaskProgressEvent.phase(taskId, phase, phaseIndex, totalPhases));
            }
            @Override public void onComplete(String taskId, T result) {
                emitterManager.sendEvent(sessionId, "progress", TaskProgressEvent.complete(taskId, result));
                emitterManager.sendEvent(sessionId, "complete", TaskProgressEvent.complete(taskId, result));
                emitterManager.complete(sessionId);
            }
            @Override public void onError(String taskId, Throwable error) {
                emitterManager.sendEvent(sessionId, "progress", TaskProgressEvent.error(taskId, error.getMessage()));
                emitterManager.sendEvent(sessionId, "error", TaskProgressEvent.error(taskId, error.getMessage()));
                emitterManager.complete(sessionId);
            }
        };
    }
}
