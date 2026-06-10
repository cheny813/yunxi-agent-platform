package io.yunxi.platform.gateway;

import io.yunxi.platform.shared.spi.SseNotificationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE Emitter 管理器
 * <p>管理 SSE 连接的生命周期，支持按会话/任务ID管理多个连接。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Component
public class SseEmitterManager implements SseNotificationProvider {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private static final long DEFAULT_TIMEOUT = 30 * 60 * 1000L;

    @Override
    public SseEmitter createEmitter(String sessionId) { return createEmitter(sessionId, DEFAULT_TIMEOUT); }

    @Override
    public SseEmitter createEmitter(String sessionId, long timeout) {
        removeEmitter(sessionId);
        SseEmitter emitter = new SseEmitter(timeout);
        emitter.onCompletion(() -> { log.debug("SSE 连接完成: {}", sessionId); emitters.remove(sessionId); });
        emitter.onTimeout(() -> { log.debug("SSE 连接超时: {}", sessionId); emitters.remove(sessionId); });
        emitter.onError(throwable -> { log.debug("SSE 连接错误: {}, error: {}", sessionId, throwable.getMessage()); emitters.remove(sessionId); });
        emitters.put(sessionId, emitter);
        log.info("SSE 连接已创建: {}, 当前连接数: {}", sessionId, emitters.size());
        return emitter;
    }

    @Override
    public boolean send(String sessionId, String eventName, Object data) { return sendEvent(sessionId, eventName, data); }

    @Override
    public boolean send(String sessionId, String id, String eventName, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) { log.warn("SSE 连接不存在: {}", sessionId); return false; }
        try {
            emitter.send(SseEmitter.event().id(id).name(eventName).data(data));
            return true;
        } catch (IOException e) { log.error("发送 SSE 事件失败", e); removeEmitter(sessionId); return false; }
    }

    public boolean sendEvent(String sessionId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) { log.warn("SSE 连接不存在: {}", sessionId); return false; }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException e) { log.error("发送 SSE 事件失败", e); removeEmitter(sessionId); return false; }
    }

    public boolean send(String sessionId, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) { log.warn("SSE 连接不存在: {}", sessionId); return false; }
        try { emitter.send(data); return true; }
        catch (IOException e) { log.error("发送 SSE 消息失败", e); removeEmitter(sessionId); return false; }
    }

    @Override
    public void complete(String sessionId) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null) { try { emitter.complete(); } catch (Exception e) { log.debug("完成 SSE 连接异常: {}", sessionId); } emitters.remove(sessionId); }
    }

    @Override
    public void completeWithError(String sessionId, Throwable error) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null) { try { emitter.completeWithError(error); } catch (Exception e) { log.debug("完成 SSE 连接（错误）异常: {}", sessionId); } emitters.remove(sessionId); }
    }

    @Override
    public void removeEmitter(String sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) { try { emitter.complete(); } catch (Exception ignored) {} }
    }

    @Override public boolean hasEmitter(String sessionId) { return emitters.containsKey(sessionId); }
    @Override public int getActiveConnectionCount() { return emitters.size(); }

    @Override
    public void broadcast(String eventName, Object data) {
        emitters.keySet().forEach(sessionId -> send(sessionId, eventName, data));
    }

    @Override
    public void registerProgressListener(String sessionId, ProgressListener listener) {
        send(sessionId, "progress-registered", Map.of("sessionId", sessionId));
    }
}
