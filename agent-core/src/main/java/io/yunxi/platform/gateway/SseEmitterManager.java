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

    /** 使用默认超时时间为指定会话创建 SSE 连接。
     * @param sessionId 会话唯一标识
     * @return 新建的 SseEmitter 实例
     */
    @Override
    public SseEmitter createEmitter(String sessionId) { return createEmitter(sessionId, DEFAULT_TIMEOUT); }

    /** 为指定会话创建 SSE 连接并注册完成/超时/错误回调。
     * @param sessionId 会话唯一标识
     * @param timeout   连接超时时间（毫秒）
     * @return 新建的 SseEmitter 实例
     */
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

    /** 向指定会话发送带事件名的 SSE 通知（无事件 ID）。
     * @param sessionId  会话唯一标识
     * @param eventName  事件名称
     * @param data       事件载荷
     * @return 发送成功返回 true；连接不存在或发送失败返回 false
     */
    @Override
    public boolean send(String sessionId, String eventName, Object data) { return sendEvent(sessionId, eventName, data); }

    /** 向指定会话发送带事件 ID 与事件名的 SSE 通知。
     * @param sessionId  会话唯一标识
     * @param id         事件 ID
     * @param eventName  事件名称
     * @param data       事件载荷
     * @return 发送成功返回 true；连接不存在或发送失败返回 false
     */
    @Override
    public boolean send(String sessionId, String id, String eventName, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) { log.warn("SSE 连接不存在: {}", sessionId); return false; }
        try {
            emitter.send(SseEmitter.event().id(id).name(eventName).data(data));
            return true;
        } catch (IOException e) { log.error("发送 SSE 事件失败", e); removeEmitter(sessionId); return false; }
    }

    /** 向指定会话发送带事件名的 SSE 事件（内部实现）。
     * @param sessionId  会话唯一标识
     * @param eventName  事件名称
     * @param data       事件载荷
     * @return 发送成功返回 true；连接不存在或发送失败返回 false
     */
    public boolean sendEvent(String sessionId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) { log.warn("SSE 连接不存在: {}", sessionId); return false; }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException e) { log.error("发送 SSE 事件失败", e); removeEmitter(sessionId); return false; }
    }

    /** 向指定会话直接发送原始数据（不指定事件名）。
     * @param sessionId  会话唯一标识
     * @param data       事件载荷
     * @return 发送成功返回 true；连接不存在或发送失败返回 false
     */
    public boolean send(String sessionId, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) { log.warn("SSE 连接不存在: {}", sessionId); return false; }
        try { emitter.send(data); return true; }
        catch (IOException e) { log.error("发送 SSE 消息失败", e); removeEmitter(sessionId); return false; }
    }

    /** 正常结束指定会话的 SSE 连接。
     * @param sessionId 会话唯一标识
     */
    @Override
    public void complete(String sessionId) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null) { try { emitter.complete(); } catch (Exception e) { log.debug("完成 SSE 连接异常: {}", sessionId); } emitters.remove(sessionId); }
    }

    /** 以异常方式结束指定会话的 SSE 连接。
     * @param sessionId 会话唯一标识
     * @param error     导致连接结束的异常
     */
    @Override
    public void completeWithError(String sessionId, Throwable error) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null) { try { emitter.completeWithError(error); } catch (Exception e) { log.debug("完成 SSE 连接（错误）异常: {}", sessionId); } emitters.remove(sessionId); }
    }

    /** 移除并关闭指定会话的 SSE 连接。
     * @param sessionId 会话唯一标识
     */
    @Override
    public void removeEmitter(String sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) { try { emitter.complete(); } catch (Exception ignored) {} }
    }

    /** 判断指定会话是否存在活跃的 SSE 连接。
     * @param sessionId 会话唯一标识
     * @return 存在活跃连接返回 true，否则返回 false
     */
    @Override public boolean hasEmitter(String sessionId) { return emitters.containsKey(sessionId); }

    /** 获取当前活跃的 SSE 连接总数。
     * @return 活跃连接数量
     */
    @Override public int getActiveConnectionCount() { return emitters.size(); }

    /** 向所有活跃会话广播同一事件。
     * @param eventName 事件名称
     * @param data      事件载荷
     */
    @Override
    public void broadcast(String eventName, Object data) {
        emitters.keySet().forEach(sessionId -> send(sessionId, eventName, data));
    }

    /** 为指定会话注册进度监听器（通过发送注册事件通知前端）。
     * @param sessionId 会话唯一标识
     * @param listener  进度监听器实例
     */
    @Override
    public void registerProgressListener(String sessionId, ProgressListener listener) {
        send(sessionId, "progress-registered", Map.of("sessionId", sessionId));
    }
}
